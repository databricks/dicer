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
    val statusCodeOpt: Option[Int] = wrappedMainInternal(new DicerAssignerConf(rawConfig))
    for (statusCode: Int <- statusCodeOpt) {
      // Only exit eagerly when wrappedMainInternal returns a non-zero value. Under normal
      // circumstances, we want the DatabricksMain.main implementation to handle the exit.
      sys.exit(statusCode)
    }
  }

  /**
   * See [[wrappedMain]]. Extracted into its own method for testing. Optionally returns a status
   * code to indicate failure, in which case the caller should [[sys.exit()]] with that status code.
   */
  private def wrappedMainInternal(conf: DicerAssignerConf): Option[Int] = {
    conf.executionMode match {
      case DicerAssignerConf.ExecutionMode.ASSIGNER_SERVICE =>
        startAssignerService(conf)
        None
      case DicerAssignerConf.ExecutionMode.ETCD_BOOTSTRAPPER =>
        Some(bootstrapAllEtcdNamespacesBlocking(conf).id)
    }
  }

  private def startAssignerService(conf: DicerAssignerConf): Unit = {
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
   * Runs the task to initialize version high watermark to the etcd cluster. The etcd cluster is
   * specified in config "databricks.dicer.assigner.preferredAssigner.etcd.endpoints", and the
   * high bits of the version high watermark is specified by `conf.storeIncarnation`.
   *
   * After trying to write the initial metadata to the etcd cluster, this function will make the
   * scala application exit with different exit code, based on the result of the initialization,
   * so that the bootstrap kubernetes job can act properly (succeed, fail, or retry) based on the
   * exit code. See specs for [[EtcdBootstrapper.ExitCode]] for more details.
   */
  private def bootstrapAllEtcdNamespacesBlocking(
      conf: DicerAssignerConf): EtcdBootstrapper.ExitCode.ExitCode = {
    val preferredAssignerBootstrapRequest = EtcdBootstrapper.BootstrapRequest(
      client = EtcdClient.create(
        conf.preferredAssignerEtcdEndpoints,
        if (conf.preferredAssignerEtcdSslEnabled) conf.dicerTlsOptions else None,
        EtcdClient.Config(Assigner.getPreferredAssignerEtcdNamespace(conf))
      ),
      incarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
    )
    val durableAssignmentsBootstrapRequest = EtcdBootstrapper.BootstrapRequest(
      client = EtcdClient.create(
        conf.preferredAssignerEtcdEndpoints,
        if (conf.preferredAssignerEtcdSslEnabled) conf.dicerTlsOptions else None,
        EtcdClient.Config(Assigner.getAssignmentsEtcdNamespace(conf))
      ),
      incarnation = conf.storeIncarnation
    )

    EtcdBootstrapper.bootstrapEtcdBlocking(
      Seq(preferredAssignerBootstrapRequest, durableAssignmentsBootstrapRequest)
    )
  }

  private[assigner] object staticForTest {
    def wrappedMainInternal(conf: DicerAssignerConf): Option[Int] = {
      AssignerMain.wrappedMainInternal(conf)
    }
  }
}
