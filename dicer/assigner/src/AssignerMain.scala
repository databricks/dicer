package com.databricks.dicer.assigner

import java.io.File
import java.net.URI
import java.util.UUID

import scala.util.{Failure, Success}

import io.prometheus.client.Gauge

import com.databricks.DatabricksMain
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{
  CachingErrorCode,
  EtcdClient,
  PrefixLogger,
  Severity,
  WhereAmIHelper
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.{StaticTargetConfigProvider, InternalTargetConfigMap}
import com.databricks.dicer.assigner.config.TargetConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
import com.databricks.dicer.common.{EtcdBootstrapper, Incarnation}

object AssignerMain extends DatabricksMain(Project.DicerAssigner) {

  private val prefixLogger = PrefixLogger.create(this.getClass, "")

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
  override def wrappedMain(args: Array[String]): Unit = {
    val conf: DicerAssignerConf = new DicerAssignerConf(rawConfig)
    wrappedMainInternal(conf) match {
      case Right(statusCode: Int) =>
        // Only exit eagerly when wrappedMainInternal returns a status code. Under normal
        // circumstances, we want the DatabricksMain.main implementation to handle the exit.
        sys.exit(statusCode)
      case _ =>
    }
  }

  /**
   * See [[wrappedMain]]. Extracted into its own method for testing. Reads the env-driven inputs
   * (NAMESPACE / APP_NAME), builds the production
   * [[KubernetesMembershipChecker.DefaultFactory]] from them, and dispatches to
   * [[wrappedMainInternalWithCheckerFactory]]. Tests that need to inject a fake/no-op checker
   * factory should call [[wrappedMainInternalWithCheckerFactory]] directly.
   */
  private def wrappedMainInternal(conf: DicerAssignerConf): Either[Assigner, Int] = {
    // Build the membership checker factory from environment variables. Always use DefaultFactory
    // so that missing env vars surface through the Assigner's error handling and monitoring
    // (initResultGauge) rather than silently disabling the checker via NoOpFactory.
    val membershipCheckerNamespace: String = Option(System.getenv("NAMESPACE")).getOrElse("")
    val membershipCheckerAppName: String = Option(System.getenv("APP_NAME")).getOrElse("")
    if (membershipCheckerNamespace.isEmpty || membershipCheckerAppName.isEmpty) {
      prefixLogger.warn(
        s"Membership checker env vars not set: NAMESPACE='$membershipCheckerNamespace', " +
        s"APP_NAME='$membershipCheckerAppName'. Checker creation will fail gracefully."
      )
    }
    val membershipCheckerFactory: KubernetesMembershipChecker.Factory =
      KubernetesMembershipChecker.DefaultFactory.create(
        membershipCheckerNamespace,
        membershipCheckerAppName,
        pollingInterval = KubernetesMembershipChecker.DEFAULT_POLLING_INTERVAL,
        rpcPort = conf.dicerAssignerRpcPort
      )
    wrappedMainInternalWithCheckerFactory(conf, membershipCheckerFactory)
  }

  /**
   * See [[wrappedMainInternal]]. Extracted into its own method for testing. Returns [[Left]]
   * with the [[Assigner]] when starting the assigner service, or [[Right]] with a status code
   * to indicate failure in bootstrapper mode, in which case the caller should [[sys.exit()]]
   * with that code.
   *
   * @param conf the assigner configuration.
   * @param membershipCheckerFactory factory for creating a [[KubernetesMembershipChecker]] that
   *                                 discovers assigner pods via the Kubernetes API. In production,
   *                                 this is a [[KubernetesMembershipChecker.DefaultFactory]]; in
   *                                 tests, a no-op or fake-backed factory.
   */
  private def wrappedMainInternalWithCheckerFactory(
      conf: DicerAssignerConf,
      membershipCheckerFactory: KubernetesMembershipChecker.Factory
  ): Either[Assigner, Int] = {
    conf.executionMode match {
      case DicerAssignerConf.ExecutionMode.ASSIGNER_SERVICE =>
        Left(startAssignerService(conf, membershipCheckerFactory))
      case DicerAssignerConf.ExecutionMode.ETCD_BOOTSTRAPPER =>
        Right(bootstrapPreferredAssignerEtcdNamespaceBlocking(conf).value)
    }
  }

  private def startAssignerService(
      conf: DicerAssignerConf,
      membershipCheckerFactory: KubernetesMembershipChecker.Factory
  ): Assigner = {
    // The main function factored in such a way that startServer can be called from here and tests.

    // Initialize and start the dynamic target config provider.
    val configProvider: StaticTargetConfigProvider = StaticTargetConfigProvider.create(
      staticTargetConfigMap = InternalTargetConfigMap.create(
        conf.getConfigScope,
        new File(conf.targetConfigDirectory),
        new File(conf.advancedTargetConfigDirectory)
      ),
      conf
    )

    // The static config is used when request times out.
    configProvider.startBlocking(DEFAULT_INITIAL_POLL_TIMEOUT)

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
      membershipCheckerFactory
    )
  }

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
      conf: DicerAssignerConf): EtcdBootstrapper.ExitCode = {
    val bootstrapRequest: EtcdBootstrapper.BootstrapRequest = EtcdBootstrapper.BootstrapRequest(
      client = EtcdClient.create(
        conf.preferredAssignerEtcdEndpoints,
        if (conf.preferredAssignerEtcdSslEnabled) conf.dicerTlsOptions else None,
        EtcdClient.Config(Assigner.getPreferredAssignerEtcdNamespace(conf))
      ),
      incarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
    )
    EtcdBootstrapper.bootstrapEtcdBlocking(Seq(bootstrapRequest))
  }

  private[assigner] object staticForTest {
    def wrappedMainInternal(conf: DicerAssignerConf): Either[Assigner, Int] = {
      AssignerMain.wrappedMainInternal(conf)
    }
    def wrappedMainInternalWithCheckerFactory(
        conf: DicerAssignerConf,
        membershipCheckerFactory: KubernetesMembershipChecker.Factory
    ): Either[Assigner, Int] = {
      AssignerMain.wrappedMainInternalWithCheckerFactory(conf, membershipCheckerFactory)
    }
  }
}
