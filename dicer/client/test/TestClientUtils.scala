package com.databricks.dicer.client

import java.util.UUID
import java.io.File
import io.prometheus.client.CollectorRegistry
import com.databricks.backend.common.util.Project
import com.typesafe.config.ConfigFactory
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{AssertionWaiter, MetricUtils, NonReentrantLock}
import com.databricks.caching.util.Lock.withLock
import com.databricks.conf.trusted.ProjectConf
import com.databricks.conf.trusted.RPCPortConf
import com.databricks.conf.Config
import com.databricks.conf.Configs
import com.databricks.conf.RichConfig
import com.databricks.dicer.common.{Assignment, Generation, InternalClientConf}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.{
  Clerk,
  ClerkConf,
  ResourceAddress,
  SliceKey,
  Slicelet,
  SliceletConf,
  Target
}
import com.databricks.rpc.tls.TLSOptions
import com.databricks.rpc.testing.TestTLSOptions

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

/** Paths to the keystore and truststore files for constructing a [[TlsOptions]]. */
case class TlsFilePaths(keystorePath: String, truststorePath: String)

object TestClientUtils {

  /** A fixed client UUID for use in unit tests where the client UUID is not important. */
  val TEST_CLIENT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

  /** Wait for an assignment to be delivered to `slicelet` in which it is assigned `key`. */
  def waitForAssignment(slicelet: Slicelet, key: SliceKey): Unit = {
    AssertionWaiter("Waiting for assignment").await {
      iassert(slicelet.impl.assignedSlicesSet.contains(key))
    }
  }

  def waitForUnassigned(slicelet: Slicelet, key: SliceKey): Unit = {
    AssertionWaiter(s"Waiting for key $key being unassigned on ${slicelet.impl.squid}").await {
      iassert(!slicelet.impl.assignedSlicesSet.contains(key))
    }
  }

  /** Waits for an assignment with generation at least `generation` to be delivered to `slicelet`.
   */
  def waitForGenerationAtLeast(slicelet: Slicelet, generation: Generation): Unit = {
    AssertionWaiter(s"Waiting for an assignment with generation at least $generation").await {
      val assignmentOpt: Option[Assignment] =
        slicelet.impl.forTest.getLatestAssignmentOpt
      iassert(assignmentOpt.exists { assignment: Assignment =>
        assignment.generation >= generation
      })
    }
  }

  /**
   * Creates the configuration for a Slicelet that communicates with an Assigner listening on
   * `localhost:$assignerPort`. Configured to use port 0 for the Slicelet RPC service so that Jetty
   * will choose an unused port when the Slicelet is started. Advertises to the Assigner that the
   * Slicelet's address is `$sliceletHost:$selfPort`, where `selfPort` is given when the Slicelet is
   * started. See [[SliceletConf.watchFromDataPlane]] for `watchFromDataPlane` parameter.
   */
  def createSliceletConfig(
      assignerPort: Int,
      sliceletHost: String,
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      serverTlsFilePathsOpt: Option[TlsFilePaths],
      watchFromDataPlane: Boolean): Config = {
    val sliceletConfig = {
      Configs.parseMap(
        "databricks.dicer.slicelet.rpc.port" -> 0,
        "databricks.dicer.slicelet.hostname" -> sliceletHost,
        "databricks.dicer.slicelet.uuid" -> UUID.randomUUID().toString,
        "databricks.dicer.slicelet.kubernetesNamespace" -> "localhostNamespace",
        "databricks.dicer.assigner.rpc.port" -> assignerPort,
        "databricks.dicer.assigner.host" -> "localhost",
        "databricks.dicer.slicelet.statetransfer.port" -> 0,
        "databricks.dicer.client.watchFromDataPlane" -> watchFromDataPlane,
        "databricks.dicer.internal.cachingteamonly.clientUuid" -> UUID.randomUUID().toString
      )
    }
    sliceletConfig
      .merge(
        getSslConfig(clientTlsFilePathsOpt, serverTlsFilePathsOpt)
      )
      .merge(createAllowMultipleClientsConfig())
  }

  /**
   * Creates an external [[SliceletConf]] configuration. See [[createSliceletConfig()]] for
   * details.
   */
  def createTestSliceletConf(
      assignerPort: Int,
      sliceletHost: String,
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      serverTlsFilePathsOpt: Option[TlsFilePaths],
      watchFromDataPlane: Boolean): SliceletConf = {
    val config: Config =
      createSliceletConfig(
        assignerPort,
        sliceletHost,
        clientTlsFilePathsOpt,
        serverTlsFilePathsOpt,
        watchFromDataPlane
      )
    new ProjectConf(Project.TestProject, config) with SliceletConf with RPCPortConf {
      // See createTestClerkConfInternal on why this value is set for dicerSslArgs.
      override def dicerTlsOptions: Option[TLSOptions] = None

      override def dicerClientTlsOptions: Option[TLSOptions] =
        buildTlsOptions(clientTlsFilePathsOpt)

      override def dicerServerTlsOptions: Option[TLSOptions] =
        buildTlsOptions(serverTlsFilePathsOpt)

      override val branch: String =
        "dicer_customer_slicelet_2024-09-04_14.57.01Z_master_164f18b3_1957847387"
    }
  }

  /**
   * Creates a Slicelet for the given `target`, i.e., the set of pods that need to be sharded as
   * named by `target`. The Assigner is listening on `assignerPort`.
   *
   * The Slicelet must be started using [[Slicelet.start()]] before it is used.
   */
  def createSlicelet(
      assignerPort: Int,
      target: Target,
      sliceletHost: String,
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      serverTlsFilePathsOpt: Option[TlsFilePaths],
      watchFromDataPlane: Boolean): Slicelet = {
    val externalConf: SliceletConf =
      createTestSliceletConf(
        assignerPort,
        sliceletHost,
        clientTlsFilePathsOpt,
        serverTlsFilePathsOpt,
        watchFromDataPlane
      )
    Slicelet(externalConf, target)
  }

  /**
   * Creates the configuration for a Clerk that communicates with the given Slicelet at
   * `localhost:sliceletPort`.
   */
  def createClerkConfig(sliceletPort: Int, clientTlsFilePathsOpt: Option[TlsFilePaths]): Config = {
    val sslConfig = getSslConfig(
      clientTlsFilePathsOpt,
      serverTlsFilePathsOpt = None
    )
    val sliceletEndpointConfig: Config = Configs.parseMap(
      "databricks.dicer.slicelet.rpc.port" -> sliceletPort,
      "databricks.dicer.internal.cachingteamonly.clientUuid" -> UUID.randomUUID().toString
    )
    sslConfig.merge(sliceletEndpointConfig).merge(createAllowMultipleClientsConfig())
  }

  /** Creates the configuration for a data plane Clerk that watches the given Assigner directly. */
  def createDataPlaneDirectClerkConfig(
      assignerPort: Int,
      clientTlsFilePathsOpt: Option[TlsFilePaths]): Config = {
    val sslConfig = getSslConfig(
      clientTlsFilePathsOpt,
      serverTlsFilePathsOpt = None
    )
    val assignerEndpointConfig: Config = Configs.parseMap(
      "databricks.dicer.assigner.rpc.port" -> assignerPort,
      "databricks.dicer.internal.cachingteamonly.clientUuid" -> UUID.randomUUID().toString
    )
    sslConfig.merge(assignerEndpointConfig).merge(createAllowMultipleClientsConfig())
  }

  /** Returns a [[Config]] with a bit set to allow multiple Slicelets within this process. */
  def createAllowMultipleClientsConfig(): Config = {
    Configs.parseMap(
      InternalClientConf.allowMultipleSliceletInstancesPropertyName -> true,
      // Tests which create multiple Clerks for the same target should not reuse lookups to
      // simulate multiple pods between which there may be assignment skew.
      InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> false
    )
  }

  /** Creates an external [[ClerkConf]] configuration. See [[createClerkConfig()]] for details. */
  def createTestClerkConf(
      sliceletPort: Int,
      clientTlsFilePathsOpt: Option[TlsFilePaths]): ClerkConf = {
    createTestClerkConfInternal(createClerkConfig(sliceletPort, clientTlsFilePathsOpt))
  }

  /**
   * Creates a [[TestableDicerClientProtoLoggerConf]], allowing tests to control the logging sample
   * fraction via [[MockFeatureFlagReaderProvider]].
   *
   * @param sampleFraction The sample fraction to set.
   */
  def createTestProtoLoggerConf(sampleFraction: Double): TestableDicerClientProtoLoggerConf = {
    val conf = new ProjectConf(Project.TestProject, ConfigFactory.empty())
    with TestableDicerClientProtoLoggerConf
    conf.setLoggingSampleFraction(sampleFraction)
    conf
  }

  /** Creates a `Clerk[ResourceAddress]` whose stub factory just forwards the resource address. */
  def createClerk(target: Target, clerkConf: ClerkConf): Clerk[ResourceAddress] = {
    Clerk.create(clerkConf, target, sliceletHostName = "localhost", {
      resourceAddress: ResourceAddress =>
        resourceAddress
    })
  }

  /**
   * Creates an external [[ClerkConf]] configuration that directly connects to the Assigner.
   *
   * @param assignerPort The port of the Assigner to connect to.
   * @param clientTlsFilePathsOpt Optional TLS file paths for the client.
   * @param branchOpt Optional branch name for version metrics. If None, uses a default test branch.
   */
  def createTestDirectClerkConf(
      assignerPort: Int,
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      branchOpt: Option[String] = None): ClerkConf = {
    createTestDirectClerkConfInternal(assignerPort, clientTlsFilePathsOpt, branchOpt)
  }

  /** Default branch name for test clerks. */
  private[dicer] val DEFAULT_TEST_CLERK_BRANCH: String =
    "dicer_customer_clerk_2024-09-04_14.57.01Z_master_164f18b3_1957847387"

  /** Default branch name for test slicelets. */
  private[dicer] val DEFAULT_TEST_SLICELET_BRANCH: String =
    "dicer_customer_slicelet_2024-09-04_14.57.01Z_master_164f18b3_1957847387"

  /** Reads the value of the dicer_slicelet_slicekeyhandles_created_total metric for `target`. */
  def getSliceKeyHandlesCreatedMetric(target: Target): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_slicelet_slicekeyhandles_created_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /** Reads the value of the dicer_slicelet_slicekeyhandles_outstanding metric for `target`. */
  def getSliceKeyHandlesOutstandingMetric(target: Target): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_slicelet_slicekeyhandles_outstanding",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /** Reads the value of the dicer_slicelet_attributed_load_total metric for `target`. */
  def getAttributedLoadMetric(target: Target): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_slicelet_attributed_load_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /** Reads the value of the dicer_slicelet_unattributed_load_total for `target`. */
  def getUnattributedLoadMetric(target: Target): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_slicelet_unattributed_load_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /** Creates an external [[ClerkConf]] using the given raw `config`. */
  private def createTestClerkConfInternal(config: Config): ClerkConf = {
    new ProjectConf(Project.TestProject, config) with ClerkConf with RPCPortConf {
      // Provide some definition of `dicerSslArgs` since it is required by DicerClientConf.
      // Can be any value since getConfig defines the relevant parts of SslArguments (e.g.,
      // keyStore) that allows the relevant dicerClientSslArgs and dicerServerSslArgs to be created.
      override def dicerTlsOptions: Option[TLSOptions] = None

      override val branch: String = DEFAULT_TEST_CLERK_BRANCH
    }
  }

  /**
   * Creates a [[ClerkConf]] that directly connects to the Assigner by overriding the well-known
   * slicelet port to use the Assigner port. This hack allows the Clerk to connect directly to the
   * Assigner by setting the Slicelet hostname to the Assigner hostname, without modifying the
   * production code. We avoid modifying the production code because this solution is temporary,
   * intended to support the internal-system use case until a Rust Slicelet with full functionality
   * becomes available.
   */
  private def createTestDirectClerkConfInternal(
      assignerPort: Int,
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      branchOpt: Option[String]): ClerkConf = {
    val sslConfig = getSslConfig(
      clientTlsFilePathsOpt,
      serverTlsFilePathsOpt = None
    )
    val config: Config = sslConfig.merge(createAllowMultipleClientsConfig())

    new ProjectConf(Project.TestProject, config) with ClerkConf with RPCPortConf {
      // Provide some definition of `dicerSslArgs` since it is required by DicerClientConf.
      // Can be any value since getConfig defines the relevant parts of SslArguments (e.g.,
      // keyStore) that allows the relevant dicerClientSslArgs and dicerServerSslArgs to be created.
      override def dicerTlsOptions: Option[TLSOptions] = None

      override val branch: String = branchOpt.getOrElse(DEFAULT_TEST_CLERK_BRANCH)

      // Overrides the default slicelet port to be the Assigner port.
      override val dicerSliceletRpcPort: Int = assignerPort
    }
  }

  /** Returns a [[Config]] with SSL conf keys set for test. */
  private[client] def getSslConfig(
      clientTlsFilePathsOpt: Option[TlsFilePaths],
      serverTlsFilePathsOpt: Option[TlsFilePaths]): Config = {
    val (clientKeystorePath, clientTruststorePath) =
      clientTlsFilePathsOpt match {
        case Some(TlsFilePaths(keystorePath, truststorePath)) =>
          (keystorePath, truststorePath)
        case None =>
          (TestTLSOptions.clientKeystorePath, TestTLSOptions.clientTruststorePath)
      }
    val (serverKeystorePath, serverTruststorePath) =
      serverTlsFilePathsOpt match {
        case Some(TlsFilePaths(keystorePath, truststorePath)) =>
          (keystorePath, truststorePath)
        case None =>
          (TestTLSOptions.serverKeystorePath, TestTLSOptions.serverTruststorePath)
      }
    Configs.parseMap(
      "databricks.dicer.library.client.keystore" -> clientKeystorePath,
      "databricks.dicer.library.client.truststore" -> clientTruststorePath,
      "databricks.dicer.library.server.keystore" -> serverKeystorePath,
      "databricks.dicer.library.server.truststore" -> serverTruststorePath
    )
  }

  private[client] def buildTlsOptions(tlsFilePathsOpt: Option[TlsFilePaths]): Option[TLSOptions] = {
    (tlsFilePathsOpt) match {
      case (Some(TlsFilePaths(keystorePath, truststorePath))) =>
        Some(
          TLSOptions.builder
            .addKeyManager(new File(keystorePath), new File(keystorePath))
            .addTrustManager(new File(truststorePath))
            .build()
        )
      case _ => None
    }
  }

  /**
   * A fake [[BlockingReadinessProvider]] for testing that allows dynamically changing the readiness
   * status and optionally blocking the [[isReady]] call.
   */
  final class FakeBlockingReadinessProvider extends BlockingReadinessProvider {
    private val lock = new NonReentrantLock()

    private var isPodReady: Boolean = false
    private var blockingPromiseOpt: Option[Promise[Unit]] = None

    /** Used to block the [[isReady]] call until [[unblock]] is called. */
    def blockForever(): Unit = withLock(lock) {
      blockingPromiseOpt = Some(Promise[Unit]())
    }

    /** Completes the promise; if [[isReady]] was called and is blocking, it will unblock. */
    def unblock(): Unit = withLock(lock) {
      for (promise <- blockingPromiseOpt) {
        promise.success(())
      }
      blockingPromiseOpt = None
    }

    /** Sets the readiness status to the given value. */
    def setReady(isReady: Boolean): Unit = withLock(lock) {
      isPodReady = isReady
    }

    /** Returns the current readiness status. The call will block until the source is unblocked. */
    override def isReady: Boolean = {
      val promiseOpt = withLock(lock) { blockingPromiseOpt }

      for (promise <- promiseOpt) {
        Await.result(promise.future, Duration.Inf)
      }

      withLock(lock) {
        isPodReady
      }
    }
  }
}
