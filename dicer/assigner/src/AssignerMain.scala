package com.databricks.dicer.assigner

import java.io.File
import java.net.URI
import java.util.UUID

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import io.prometheus.client.{Counter, Gauge}

import com.databricks.DatabricksMain
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{
  CachingErrorCode,
  EtcdClient,
  PrefixLogger,
  Severity,
  WhereAmIHelper
}
import com.databricks.common.status.ProbeStatusSource
import com.databricks.common.status.liveness.LivenessStatusSource
import com.databricks.conf.Config
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.{
  InternalTargetConfigMap,
  TargetConfigProvider,
  TargetConfigProviderFactory
}
import com.databricks.dicer.assigner.config.TargetConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
import com.databricks.dicer.common.{
  AppIdentifier,
  AssignerServiceInfo,
  EtcdBootstrapper,
  Incarnation
}

/**
 * The Assigner's main logic as a class so tests can drive the real [[DatabricksMain]] bootstrap via
 * constructor injection; the bootstrap installs the readiness probe via [[newReadinessSource]] and
 * binds it in [[wrappedMain]]. The production entry point is the [[AssignerMain]] singleton.
 *
 * @param rawConfigOverrideOpt overrides the process-wide config; production passes `None`.
 * @param confFactory builds the Assigner config from the resolved [[Config]]; production passes a
 *                    plain [[DicerAssignerConf]] and tests pass one with test SSL args.
 * @param kubernetesMembershipCheckerFactoryOverrideOpt overrides the membership-checker factory
 *                    (e.g. with a fake-Kubernetes-backed one); production passes `None` and builds
 *                    the default factory from env vars at startup.
 */
private[assigner] class AssignerMainBase(
    rawConfigOverrideOpt: Option[Config],
    confFactory: Config => DicerAssignerConf,
    kubernetesMembershipCheckerFactoryOverrideOpt: Option[KubernetesMembershipChecker.Factory])
    extends DatabricksMain(Project.DicerAssigner, rawConfigOpt = rawConfigOverrideOpt) {

  private val prefixLogger = PrefixLogger.create(this.getClass, "")

  /**
   * The Assigner config. MUST stay `lazy`: the probe-source hooks ([[newReadinessSource]] /
   * [[newLivenessSource]]) read it during `initDatabricks`, but `rawConfig` is only populated by
   * the [[com.databricks.DatabricksMain]] superclass constructor, which runs after this subclass's
   * field initializers. An eager `val` would call `confFactory(rawConfig)` on a not-yet-initialized
   * `rawConfig` and fail construction; `lazy` defers the read until the first hook call, by which
   * point the superclass is fully constructed.
   */
  private lazy val conf: DicerAssignerConf = confFactory(rawConfig)

  /**
   * Backs both the readiness and liveness probes from one membership-checker connection-health
   * cell; [[wrappedMain]] binds that cell with the AssignerProbeSource via `init`, and the
   * [[newReadinessSource]] / [[newLivenessSource]] hooks expose the probes that Kubernetes actually
   * reads, both from this single instance.
   */
  private val assignerProbeSource: AssignerProbeSource = new AssignerProbeSource()

  /**
   * Temporary metric to allow us to check the consistency of cluster location information from
   * WhereAmI between Slicelets and Assigner. This ensures alignment between CP Slicelets and the
   * Assigner before using the cluster URI in Target identifiers for Slicelets. Inconsistencies
   * could cause the Dicer assigner to incorrectly treat CP Slicelets as remote.
   */
  private val locationInfoGauge = Gauge
    .build()
    .name("dicer_assigner_location_info")
    .help("Records the location info for the Assigner provided through WhereAmI, if available.")
    .labelNames("whereAmIClusterUri")
    .register()

  /**
   * Temporary metric to allow us to check the status of the assigner service info. This will be
   * used to validate that assigner service info is available for all Assigners before we make it
   * required. Incremented exactly once per Assigner startup, with the status this Assigner
   * resolved.
   */
  private val assignerServiceInfoStatusCounter = Counter
    .build()
    .name("dicer_assigner_service_info_status_total")
    .help(
      "Counts Assigner startups by whether the assigner service info is available, labeled by " +
      "status, name, and instance id."
    )
    .labelNames("status", "name", "instanceId")
    .register()

  /**
   * Starts the Assigner in different modes depending on the value of
   * `databricks.dicer.assigner.executionMode`:
   *
   * - In [[DicerAssignerConf.ExecutionMode.ASSIGNER_SERVICE]], this starts the Assigner server,
   *   responding to watch requests by generating and distributing assignments and blocks the main
   *   thread until being shut down.
   *
   * - In [[DicerAssignerConf.ExecutionMode.ETCD_BOOTSTRAPPER]], this runs a one-off task to write
   *   initial metadata to the etcd instance pointed to by
   *   `databricks.dicer.assigner.preferredAssigner.etcd.endpoints`. The process will exit with a
   *   code depending on the result of the initialization.
   */
  override final def wrappedMain(args: Array[String]): Unit = {
    wrappedMainInternal(conf, lingerAfterFinish = BOOTSTRAPPER_LINGER_AFTER_FINISH) match {
      case Left(assigner: Assigner) =>
        // Bind the shared connection-health cell now that the Assigner is live; see
        // [[AssignerProbeSource]] for the contract.
        assignerProbeSource.init(healthCell = assigner.probePollHealthWatchCell)
      case Right(statusCode: Int) =>
        // Only exit eagerly when wrappedMainInternal returns a status code. Under normal
        // circumstances, we want the DatabricksMain.main implementation to handle the exit.
        sys.exit(statusCode)
    }
  }

  // One flag gates both probes because they act on the same connection-health signal and are
  // rolled out together.
  override protected def newReadinessSource(): Option[ProbeStatusSource] =
    Some(assignerProbeSource.forReadiness(conf.gateProbesOnK8sConnectionHealthFlagProvider))

  override protected def newLivenessSource(): Option[LivenessStatusSource] =
    Some(assignerProbeSource.forLiveness(conf.gateProbesOnK8sConnectionHealthFlagProvider))

  /**
   * See [[wrappedMain]]. Extracted into its own method for testing. Reads the env-driven inputs
   * (NAMESPACE / APP_NAME), builds the production [[KubernetesMembershipChecker.DefaultFactory]]
   * from them, and dispatches to [[wrappedMainInternalWithCheckerFactory]]. Tests that need to
   * inject a fake/no-op checker factory call [[wrappedMainInternalWithCheckerFactory]] directly.
   *
   * TODO(<internal bug>): Refactor this function so we don't build the K8s membership checker factories
   * when running in ETCD_BOOTSTRAPPER mode, which does not require them.
   */
  private def wrappedMainInternal(
      conf: DicerAssignerConf,
      lingerAfterFinish: FiniteDuration): Either[Assigner, Int] = {
    // Build the checker factory from env vars. The assigner service requires a checker, so if these
    // are unset the checker construction in `Assigner.createAndStart` fails startup (bootstrapper
    // mode never constructs one).
    val membershipCheckerNamespace: String = Option(System.getenv("NAMESPACE")).getOrElse("")
    val membershipCheckerAppName: String = Option(System.getenv("APP_NAME")).getOrElse("")
    val localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory =
      kubernetesMembershipCheckerFactoryOverrideOpt.getOrElse(
        KubernetesMembershipChecker.DefaultFactory.create(
          membershipCheckerNamespace,
          membershipCheckerAppName,
          pollingInterval = KubernetesMembershipChecker.DEFAULT_POLLING_INTERVAL,
          rpcPort = conf.dicerAssignerRpcPort
        )
      )

    // Build a factory that creates a Kubernetes membership checker targeting a remote cluster.
    //
    // NOTE: This is currently only used by the [[TargetMigrator]] for Assigners participating
    // in an active target migration. These Assigners must have the relevant configuration to build
    // a remote cluster membership checker factory.
    //
    // However, not all Assigner deployments participate in this target migration and thus not all
    // Assigners have the relevant configuration to build a remote cluster membership checker
    // factory. In this case, the factory creation will return None.
    val remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory] =
      RemoteMembershipCheckerFactory.tryCreate(
        conf,
        membershipCheckerNamespace,
        membershipCheckerAppName
      )

    wrappedMainInternalWithCheckerFactory(
      conf,
      localClusterMembershipCheckerFactory,
      remoteClusterMembershipCheckerFactoryOpt,
      lingerAfterFinish
    )
  }

  /**
   * See [[wrappedMainInternal]]. Extracted into its own method for testing. Returns [[Left]]
   * with the [[Assigner]] when starting the assigner service, or [[Right]] with a status code
   * to indicate failure in bootstrapper mode, in which case the caller should [[sys.exit()]]
   * with that code.
   *
   * @param conf the assigner configuration.
   * @param localClusterMembershipCheckerFactory factory for creating a
   *        [[KubernetesMembershipChecker]] that discovers assigner pods in the local cluster via
   *        the Kubernetes API. In production, this is a
   *        [[KubernetesMembershipChecker.DefaultFactory]]; in tests, a no-op or fake-backed
   *        factory.
   * @param remoteClusterMembershipCheckerFactoryOpt factory for creating a
   *        [[KubernetesMembershipChecker]] that discovers assigner pods in a remote cluster (i.e.
   *        clusters other than the one this Assigner is running in) via the Kubernetes API. If no
   *        remote cluster to watch is configured in the `conf`, this factory will not create any
   *        watchers.
   */
  private def wrappedMainInternalWithCheckerFactory(
      conf: DicerAssignerConf,
      localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory,
      remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory],
      lingerAfterFinish: FiniteDuration
  ): Either[Assigner, Int] = {
    conf.executionMode match {
      case DicerAssignerConf.ExecutionMode.ASSIGNER_SERVICE =>
        Left(
          startAssignerService(
            conf,
            localClusterMembershipCheckerFactory,
            remoteClusterMembershipCheckerFactoryOpt
          )
        )
      case DicerAssignerConf.ExecutionMode.ETCD_BOOTSTRAPPER =>
        Right(bootstrapPreferredAssignerEtcdNamespaceBlocking(conf, lingerAfterFinish).value)
    }
  }

  private def startAssignerService(
      conf: DicerAssignerConf,
      localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory,
      remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory]
  ): Assigner = {
    // The main function factored in such a way that startServer can be called from here and tests.

    // Initialize and start the target config provider.
    val configProvider: TargetConfigProvider =
      TargetConfigProviderFactory.createBlocking(
        staticTargetConfigMap = InternalTargetConfigMap.create(
          conf.getConfigScope,
          new File(conf.targetConfigDirectory),
          new File(conf.advancedTargetConfigDirectory)
        ),
        conf,
        DEFAULT_INITIAL_POLL_TIMEOUT
      )

    // Get the assigner UUID and host name from system env.
    val uuid = UUID.fromString(
      Option(System.getenv("POD_UID"))
        .getOrElse(throw new IllegalStateException("Environment variable POD_UID is not set."))
    )
    val hostName: String = Option(System.getenv("POD_IP"))
      .getOrElse(throw new IllegalStateException("Environment variable POD_IP is not set."))

    // Get the cluster URI from the environment. WhereAmIHelper requires the cluster URI to be set
    // in the LOCATION environment variable of the pod. If the cluster URI is not set, the assigner
    // will fail to start.
    val assignerClusterUri: URI = WhereAmIHelper.getClusterUri.getOrElse(
      throw new IllegalStateException("Assigner cannot determine its cluster URI.")
    )

    // Record WhereAmI cluster location in metrics. Note that we use the ASCII string representation
    // of the URI to align with our use of the ASCII string representation for the cluster label in
    // target metrics (see TargetHelper.getTargetClusterLabel).
    locationInfoGauge.labels(assignerClusterUri.toASCIIString()).set(1)

    // Convert this process' app identity into Dicer's assigner service info at the process
    // boundary. If the app identity is absent or invalid, return `None`. Record availability
    // of assigner service info in the metric.
    // TODO(<internal bug>): Make required once validated all Assigners have valid service info.
    val assignerServiceInfoOpt: Option[AssignerServiceInfo] = try {
      AppIdentifier.getFromEnv match {
        case Some(appIdentifier: AppIdentifier) =>
          val serviceInfo = AssignerServiceInfo(
            appIdentifier.name,
            appIdentifier.instanceId
          )
          assignerServiceInfoStatusCounter
            .labels(ServiceInfoStatus.Valid.toString, serviceInfo.name, serviceInfo.instanceId)
            .inc()
          Some(serviceInfo)
        case None =>
          // No app identifier is set, so the Assigner starts with no service info and generated
          // assignments carry no service info.
          assignerServiceInfoStatusCounter.labels(ServiceInfoStatus.Absent.toString, "", "").inc()
          None
      }
    } catch {
      case NonFatal(ex) =>
        // This can happen if the app metadata is present but invalid or other non fatal
        // exceptions. We log a warning and fail open with None as assigner service info is
        // non blocking to Assigner initialization.
        prefixLogger.warn(s"Tried to get app identifier, but failed: $ex")
        assignerServiceInfoStatusCounter.labels(ServiceInfoStatus.Invalid.toString, "", "").inc()
        None
    }

    // Try to create a KubernetesTargetWatcher factory. If it fails, log an alert and fall back to a
    // factory which returns no-op target watchers. It is OK to proceed starting up the Assigner in
    // this case because Kubernetes signals are not strictly necessary, as we will still hear about
    // pod terminations in a timely fashion from Slicelets for planned restarts. Taking a hard
    // dependency on creating a k8s watcher, thus, would unnecessarily compromise the availability
    // of Dicer.
    val kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory =
      KubernetesTargetWatcher.newFactory() match {
        case Success(kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory) =>
          kubernetesTargetWatcherFactory
        case Failure(ex: Throwable) =>
          prefixLogger.alert(
            Severity.DEGRADED,
            CachingErrorCode.KUBERNETES_INIT,
            "Failed to create KubernetesTargetWatcher factory. Falling back to a factory which " +
            s"will generate no-op target watchers: $ex"
          )
          KubernetesTargetWatcher.NoOpFactory
      }

    // Create the assigner and start the RPC server.
    Assigner.createAndStart(
      conf,
      configProvider,
      uuid,
      hostName,
      assignerClusterUri,
      kubernetesTargetWatcherFactory,
      localClusterMembershipCheckerFactory,
      remoteClusterMembershipCheckerFactoryOpt,
      assignerServiceInfoOpt
    )
  }

  /**
   * How long the ETCD_BOOTSTRAPPER one-off job lingers after finishing its writes before returning,
   * so Prometheus can scrape the result metric of the short-lived Kubernetes job (the scrape
   * happens every 30 seconds to 1 minute).
   */
  private val BOOTSTRAPPER_LINGER_AFTER_FINISH: FiniteDuration = 3.minutes

  /**
   * Runs the task to initialize the preferred-assigner version high watermark in etcd. The etcd
   * cluster is specified in config "databricks.dicer.assigner.preferredAssigner.etcd.endpoints",
   * and the high bits of the version high watermark are specified by
   * `conf.preferredAssignerStoreIncarnation`.
   *
   * After trying to write the initial metadata to etcd, this function returns an exit code that
   * the caller propagates to `sys.exit` so the bootstrap kubernetes job can act properly
   * (succeed, fail, or retry). See specs for [[EtcdBootstrapper.ExitCode]] for more details.
   *
   * @param conf The assigner configuration supplying etcd endpoints, TLS options, and the
   *             preferred-assigner store incarnation used to initialize the watermark.
   */
  private def bootstrapPreferredAssignerEtcdNamespaceBlocking(
      conf: DicerAssignerConf,
      lingerAfterFinish: FiniteDuration): EtcdBootstrapper.ExitCode = {
    val bootstrapRequest: EtcdBootstrapper.BootstrapRequest = EtcdBootstrapper.BootstrapRequest(
      client = EtcdClient.create(
        conf.preferredAssignerEtcdEndpoints,
        if (conf.preferredAssignerEtcdSslEnabled) conf.dicerTlsOptions else None,
        EtcdClient.Config(Assigner.getPreferredAssignerEtcdNamespace(conf))
      ),
      incarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
    )
    EtcdBootstrapper.bootstrapEtcdBlocking(
      Seq(bootstrapRequest),
      lingerAfterFinish = lingerAfterFinish
    )
  }

  /** Test-only access to the otherwise-private boot internals (used by `AssignerMainSuite`). */
  private[assigner] object staticForTest {
    def wrappedMainInternal(conf: DicerAssignerConf): Either[Assigner, Int] =
      AssignerMainBase.this.wrappedMainInternal(conf, lingerAfterFinish = Duration.Zero)
    def wrappedMainInternalWithCheckerFactory(
        conf: DicerAssignerConf,
        localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory,
        remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory]
    ): Either[Assigner, Int] =
      AssignerMainBase.this.wrappedMainInternalWithCheckerFactory(
        conf,
        localClusterMembershipCheckerFactory,
        remoteClusterMembershipCheckerFactoryOpt,
        // Don't linger the bootstrapper in tests.
        lingerAfterFinish = Duration.Zero
      )
  }
}

/** The production Dicer Assigner entry point. */
object AssignerMain
    extends AssignerMainBase(
      rawConfigOverrideOpt = None,
      confFactory = config => new DicerAssignerConf(config),
      kubernetesMembershipCheckerFactoryOverrideOpt = None
    )

/**
 * The availability of the Assigner's service info at startup.
 */
private sealed trait ServiceInfoStatus

private object ServiceInfoStatus {

  /** The process app identifier was present and resolved into a valid [[AssignerServiceInfo]]. */
  case object Valid extends ServiceInfoStatus {
    override def toString: String = "valid"
  }

  /** No process app identifier was set, so the Assigner has no service info. */
  case object Absent extends ServiceInfoStatus {
    override def toString: String = "absent"
  }

  /**
   * The process app identifier was present but could not be resolved into a valid
   * [[AssignerServiceInfo]].
   */
  case object Invalid extends ServiceInfoStatus {
    override def toString: String = "invalid"
  }
}
