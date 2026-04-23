package com.databricks.dicer.external

import com.databricks.dicer.friend.SliceMap

import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Using
import scala.util.matching.Regex
import io.prometheus.client.CollectorRegistry
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.TestUtils.{TestName, assertThrow, loadTestData}
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContext,
  MetricUtils,
  SequentialExecutionContextPool,
  TestUtils,
  UnixTimeVersion
}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.conf.Config
import com.databricks.dicer.common.ClientType
import com.databricks.rpc.RequestHeaders
import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP
import com.databricks.common.web.InfoService
import com.databricks.conf.Configs
import com.databricks.conf.trusted.ProjectConf
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.dicer.client.{ClerkImpl, SliceletImpl, TestClientUtils, WatchAddressHelper}
import com.databricks.dicer.client.TestClientUtils.FakeBlockingReadinessProvider
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentMetricsSource,
  ClientRequest,
  Generation,
  Incarnation,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment,
  SliceSetImpl,
  SliceletData,
  SliceletState,
  TestAssigner,
  TestSliceUtils
}
import com.databricks.caching.util.Lock.withLock
import com.databricks.common.status.{ProbeStatus, ProbeStatusSource, ProbeStatuses}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.config.{InternalTargetConfig, InternalTargetConfigMap}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.common.test.SliceletTestDataP
import com.databricks.dicer.common.test.SliceletTestDataP.SliceletDebugNameTestCaseP
import com.databricks.dicer.external.SliceletSuite.ASSIGNER_CLUSTER_URI
import com.databricks.dicer.friend.Squid
import com.databricks.web.SimpleWebClient
import com.databricks.rpc.tls.TLSOptions
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest

/**
 * Abstract class for Slicelet tests. This class contains the test cases that apply to both the
 * Scala and Rust implementations. It should be extended with a trait for cluster location and
 * location information (see, e.g., [[SliceletSuiteWithLocalKnownCluster]]), and with the class for
 * the implementation type.
 *
 * See also [[com.databricks.dicer.assigner.AssignerSuite]] for tests involving multiple Slicelets
 * with and without location info.
 */
abstract class SliceletSuiteBase extends DatabricksTest with TestName {

  private val TEST_DATA: SliceletTestDataP =
    loadTestData[SliceletTestDataP]("dicer/common/test/data/slicelet_test_data.textproto")

  /** A UUID unique to this test case in this test process. */
  private var testCaseUuid: UUID = _

  /**
   * Whether [[SliceletConf.watchFromDataPlane]] should be set to true or false for the Slicelets.
   */
  protected val watchFromDataPlane: Boolean

  protected val expectedWhereAmIClusterUri: String

  /** The value to set for the `LOCATION` environment variable to indicate the cluster location. */
  protected def locationEnvVarJson: String

  /** The test environment used for all the tests. */
  protected val testEnv: InternalDicerTestEnvironment =
    InternalDicerTestEnvironment.create(
      config = TestAssigner.Config.create(
        assignerConf = new DicerAssignerConf(
          Configs.parseMap(
            // Short watch timeout to more quickly observe Slicelet changes.
            "databricks.dicer.internal.cachingteamonly.watchServerSuggestedRpcTimeoutMillis" -> 500
          )
        )
      ),
      assignerClusterUri = ASSIGNER_CLUSTER_URI
    )

  /** The number of Slicelets that have been created in the current test case. */
  private var numSlicelets: Int = 0

  override def beforeEach(): Unit = {
    testCaseUuid = UUID.randomUUID()
    numSlicelets = 0
  }

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /**
   * The default collection of extra environment variables (in addition to the current process's
   * environment variables) to provide to the Slicelet.
   */
  protected def defaultExtraEnvVars: Map[String, String] = {
    Map("LOCATION" -> locationEnvVarJson)
  }

  /**
   * Returns a unique and safe Target name for each test case.
   */
  override def getSafeName: String = {
    // We append a random suffix to avoid interference between runs of the same test case in
    // different processes. See <internal bug> for more detail.
    getSuffixedSafeName(s"${testCaseUuid.toString.take(5)}")
  }

  /**
   * Creates and returns a Slicelet in the given `internalTestEnv` with the given configuration
   * parameters.
   */
  // The test environment is in a separate parameter list so that default values in the second
  // list (e.g. extraDbConfFlags) can reference it. Scala does not allow default expressions of
  // an abstract method to reference earlier parameters in the same parameter list.
  protected def createSlicelet(internalTestEnv: InternalDicerTestEnvironment)(
      sliceletHostname: Option[String] = Some(generateSliceletHostname()),
      sliceletUuid: Option[String] = Some(UUID.randomUUID().toString),
      sliceletKubernetesNamespace: Option[String] = Some("localhost-namespace"),
      branch: Option[String] = None,
      extraDbConfFlags: Map[String, Any] = Map(
        "databricks.dicer.assigner.host" -> "localhost",
        "databricks.dicer.assigner.rpc.port" -> internalTestEnv.getAssignerPort
      ),
      extraEnvVars: Map[String, String] = defaultExtraEnvVars,
      useFakeReadinessProvider: Boolean = false): SliceletDriver

  /** Generates a unique hostname to use for a Slicelet. */
  private def generateSliceletHostname(): String = {
    // Replace ':' with '-' in the slicelet host name since URI cannot parse names with ':' in them.
    val hostname: String = s"$defaultTarget-$numSlicelets".replace(':', '-')
    numSlicelets += 1
    hostname
  }

  /** Returns the expected resource address for the given port. */
  protected def createTestSquid(selfPort: Int): Squid = {
    TestSliceUtils.createTestSquid(s"https://localhost:$selfPort")
  }

  /** Returns whether `slicelet` believes `key` is currently assigned to it. */
  protected def isAssigned(slicelet: SliceletDriver, key: SliceKey): Boolean = {
    Using.resource(slicelet.createHandle(key))(
      (_: SliceletDriver.SliceKeyHandle).isAssignedContinuously
    )
  }

  /**
   * Factory that creates a target with the given optional cluster URI and name. Used in this suite
   * to create a [[defaultTarget]] for each test case.
   */
  protected def targetFactory: (Option[URI], String) => Target

  /**
   * (Default) Target identifier to use for Slicelets for each test. This is the Target identifier
   * that would be created via the public API (and thus, not have any cluster URI information).
   */
  protected def defaultTarget: Target = targetFactory(None, getSafeName)

  /**
   * Returns the expected Target identifier that the Slicelet uses internally for the
   * [[defaultTarget]].
   *
   * This is useful for tests which e.g. check Slicelet metrics and need to supply the expected
   * Target identifier that the Slicelet is using for itself.
   */
  protected def expectedSliceletTargetIdentifier: Target

  /**
   * Returns the expected Target identifier that the Assigner uses internally to reference the
   * [[defaultTarget]]. Assigners canonicalize target identifiers to omit the cluster URI if the
   * cluster URI is the same as the Assigner's, and include the cluster URI otherwise.
   *
   * This is useful when performing operations like freezing assignments in the Assigner, where
   * tests need to refer to the target in the same way that the Assigner does.
   */
  protected def expectedAssignerCanonicalizedTargetIdentifier: Target

  /**
   * Sets the readiness status reported by the probe status source and triggers a readiness
   * endpoint update so the [[ReadinessProbeTracker]] picks up the change.
   */
  protected def setReadinessProbeStatusSource(status: ProbeStatus): Unit

  /**
   * Helper to set and freeze the assignment at the Assigner in the given `internalTestEnv` for the
   * [[defaultTarget]] which uses the correct Assigner canonicalized Target identifier.
   */
  protected def setAndFreezeAssignment(
      internalTestEnv: InternalDicerTestEnvironment,
      proposal: SliceMap[ProposedSliceAssignment]): Future[Assignment] = {
    internalTestEnv.testAssigner
      .setAndFreezeAssignment(expectedAssignerCanonicalizedTargetIdentifier, proposal)
  }

  /**
   * Helper to get the latest slicelet watch request of the Assigner in the given `internalTestEnv`
   * for the [[defaultTarget]].
   */
  protected def getLatestSliceletWatchRequest(
      internalTestEnv: InternalDicerTestEnvironment): Option[ClientRequest] = {
    internalTestEnv.testAssigner
      .getLatestSliceletWatchRequest(expectedAssignerCanonicalizedTargetIdentifier)
      .map {
        case (_: RequestHeaders, req: ClientRequest) => req
      }
  }

  /**
   * Helper to get the latest slicelet watch request of the Assigner in the given `internalTestEnv`
   * for the [[defaultTarget]] and `squid`.
   */
  protected def getLatestSliceletWatchRequest(
      internalTestEnv: InternalDicerTestEnvironment,
      squid: Squid): Option[ClientRequest] = {
    internalTestEnv.testAssigner
      .getLatestSliceletWatchRequest(expectedAssignerCanonicalizedTargetIdentifier, squid)
      .map {
        case (_: RequestHeaders, req: ClientRequest) => req
      }
  }

  /**
   * Reads and returns the value of the Prometheus metric with the given name and labels.
   *
   * For histograms, append "_count" or "_sum" to the metric name as needed.
   *
   * Returns zero if the metric has never been recorded with this set of label values.
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  /** Reads the value of the `dicer_slicelet_slicekeyhandles_created_total` metric for `target`. */
  def getSliceKeyHandlesCreatedMetric: Double = {
    readPrometheusMetric("dicer_slicelet_slicekeyhandles_created_total", targetMetricLabels)
  }

  /** Reads the value of the `dicer_slicelet_slicekeyhandles_outstanding` metric for `target`. */
  def getSliceKeyHandlesOutstandingMetric: Double = {
    readPrometheusMetric("dicer_slicelet_slicekeyhandles_outstanding", targetMetricLabels)
  }

  /** Returns the expected labels for Prometheus metrics broken down by target. */
  protected def targetMetricLabels: Vector[(String, String)] = {
    Vector(
      "targetCluster" -> expectedSliceletTargetIdentifier.getTargetClusterLabel,
      "targetName" -> expectedSliceletTargetIdentifier.getTargetNameLabel,
      "targetInstanceId" -> expectedSliceletTargetIdentifier.getTargetInstanceIdLabel
    )
  }

  /** Returns the expected labels for Prometheus metrics broken down by target and source. */
  protected def targetAndSourceMetricLabels: Vector[(String, String)] = {
    targetMetricLabels :+ ("source" -> AssignmentMetricsSource.Slicelet.toString)
  }

  /** Returns the counter value for this `state`. */
  protected def getSliceletStateCount(state: SliceletDataP.State): Double = {
    readPrometheusMetric(
      "dicer_slicelet_current_state_total",
      targetMetricLabels :+ ("state" -> state.toString)
    )
  }

  /**
   * Returns the histogram count for client request proto sizes for the given target
   * and client type.
   */
  protected def getClientRequestSizeCount(
      targetIdentifier: Target,
      clientType: ClientType): Double = {
    readPrometheusMetric(
      "dicer_client_request_proto_size_bytes_histogram_count",
      Vector(
        "targetCluster" -> targetIdentifier.getTargetClusterLabel,
        "targetName" -> targetIdentifier.getTargetNameLabel,
        "targetInstanceId" -> targetIdentifier.getTargetInstanceIdLabel,
        "clientType" -> clientType.toString
      )
    )
  }

  /**
   * Returns the histogram sum for client request proto sizes for the given target
   * and client type.
   */
  protected def getClientRequestSizeSum(
      targetIdentifier: Target,
      clientType: ClientType): Double = {
    readPrometheusMetric(
      "dicer_client_request_proto_size_bytes_histogram_sum",
      Vector(
        "targetCluster" -> targetIdentifier.getTargetClusterLabel,
        "targetName" -> targetIdentifier.getTargetNameLabel,
        "targetInstanceId" -> targetIdentifier.getTargetInstanceIdLabel,
        "clientType" -> clientType.toString
      )
    )
  }

  /** Waits for an assignment to be delivered to `slicelet` in which it is assigned `key`. */
  def waitForAssignment(slicelet: SliceletDriver, key: SliceKey): Unit = {
    AssertionWaiter("Waiting for assignment").await {
      assert(slicelet.assignedSlices.exists(_.contains(key)))
    }
  }

  /** Waits for an assignment to be delivered to `slicelet` in which it is not assigned `key`. */
  def waitForUnassigned(slicelet: SliceletDriver, key: SliceKey): Unit = {
    AssertionWaiter(s"Waiting for key $key being unassigned on $slicelet").await {
      assert(!slicelet.assignedSlices.exists(_.contains(key)))
    }
  }

  /**
   * Waits for an assignment with generation at least `generation` to be delivered to `slicelet`.
   */
  def waitForGenerationAtLeast(slicelet: SliceletDriver, generation: Generation): Unit = {
    AssertionWaiter(s"Waiting for an assignment with generation at least $generation").await {
      val assignmentOpt: Option[Assignment] = slicelet.latestAssignmentOpt
      assert(assignmentOpt.exists { assignment: Assignment =>
        assignment.generation >= generation
      })
    }
  }

  /**
   * Gets the [[Squid]] with the given port if one exists in the assignment for the
   * [[defaultTarget]] generated by the Assigner in the given `internalTestEnv`.
   */
  private def getSquidWithPort(internalTestEnv: InternalDicerTestEnvironment, port: Int): Squid = {
    val squids: Set[Squid] = TestUtils
      .awaitResult(
        internalTestEnv.testAssigner
          .getAssignment(expectedAssignerCanonicalizedTargetIdentifier),
        Duration.Inf
      )
      .get
      .assignedResources
    val squidOpt = squids.find(_.resourceAddress.uri.getPort == port)
    assert(squidOpt.nonEmpty, s"Could not find Squid with port $port")
    squidOpt.get
  }

  /**
   * Waits for a SQUID with the given port number to exist in the assignment for the
   * [[defaultTarget]] generated by the Assigner in the given `internalTestEnv`, and returns it.
   */
  protected def waitForSquidWithPort(
      internalTestEnv: InternalDicerTestEnvironment,
      port: Int): Squid = {
    AssertionWaiter(s"Wait for assignment to contain SQUID with port $port").await {
      getSquidWithPort(internalTestEnv, port)
    }
  }

  /**
   * Waits for any SQUID to exist in the assignment for the [[defaultTarget]] generated by the
   * Assigner in the given `internalTestEnv`, and returns it.
   */
  protected def waitForAnySquid(internalTestEnv: InternalDicerTestEnvironment): Squid = {
    AssertionWaiter(s"Wait for assignment to contain any SQUID").await {
      val squidOpt: Option[Squid] = allSquidsInAssignment(internalTestEnv).headOption
      assert(squidOpt.nonEmpty)
      squidOpt.get
    }
  }

  /**
   * Returns all SQUIDs in the current assignment for the [[defaultTarget]] generated by the
   * Assigner in the given `internalTestEnv`.
   */
  private def allSquidsInAssignment(internalTestEnv: InternalDicerTestEnvironment): Set[Squid] = {
    TestUtils
      .awaitResult(
        internalTestEnv.testAssigner
          .getAssignment(expectedAssignerCanonicalizedTargetIdentifier),
        Duration.Inf
      )
      .get
      .assignedResources
  }

  /**
   * Waits for the Slicelet with the given [[Squid]] in the given `internalTestEnv` to report the
   * expected state.
   */
  private def awaitSliceletReported(
      internalTestEnv: InternalDicerTestEnvironment,
      squid: Squid,
      expectedState: SliceletState): Unit = {
    AssertionWaiter(s"Wait for $squid to report $expectedState state").await {
      val requestOpt: Option[ClientRequest] =
        getLatestSliceletWatchRequest(internalTestEnv, squid)
      assert(requestOpt.nonEmpty)
      requestOpt.get.subscriberData match {
        case sliceletData: SliceletData =>
          assert(sliceletData.state == expectedState)
        case other =>
          fail(s"Expected SliceletData but got ${other.getClass.getSimpleName}")
      }
    }
  }

  /**
   * Waits for the Slicelets identified by the given `squids` in the given `internalTestEnv` to be
   * part of an assignment.
   */
  private def assertAndWaitForSliceletsToBeAssigned(
      internalTestEnv: InternalDicerTestEnvironment,
      squids: Set[Squid]): Unit = {
    AssertionWaiter(s"Wait for Slicelets in $squids to be assigned").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(
          internalTestEnv.testAssigner
            .getAssignment(expectedAssignerCanonicalizedTargetIdentifier),
          Duration.Inf
        )
      assert(assignmentOpt.nonEmpty)
      assert(
        assignmentOpt.get.assignedResources == squids,
        s"Expected all Slicelets in $squids to be assigned, but got " +
        s"${assignmentOpt.get.assignedResources}"
      )
    }
  }

  test("Test debug name") {
    // Test plan: Check that the slicelet debug name is extracted as expected with a few examples.

    // Figure out the current Slicelet number, since the debug name for every Slicelet is prefixed
    // with "S$num-" for a sequential `num` value.
    val debugNamePattern: Regex = """^S(\d+)-.*$""".r
    val debugName: String = SliceletImpl.getDebugName(Target("foo"), hostName = "bar")
    var num: Long = debugName match {
      case debugNamePattern(num: String) => num.toLong
    }

    val target = Target("test-target")
    for (testCase: SliceletDebugNameTestCaseP <- TEST_DATA.sliceletDebugNameTestCases) {
      num += 1
      assert(
        SliceletImpl.getDebugName(target, hostName = testCase.getHostname)
        == s"S$num${testCase.getExpectedDebugNameSuffix}"
      )
    }
  }

  test("SliceletConf without host name") {
    // Test plan: Verify that an IllegalArgumentException is thrown when attempting to create a
    // Slicelet using a configuration without a host name.
    assertThrow[IllegalArgumentException]("Unable to determine Slicelet host name.") {
      createSlicelet(testEnv)(sliceletHostname = None)
    }
  }

  test("SliceletConf without UUID") {
    // Test plan: Verify that an IllegalArgumentException is thrown when attempting to create a
    // Slicelet using a configuration without a UID.

    // Create Slicelet without UID.
    assertThrow[IllegalArgumentException]("Unable to determine Slicelet UUID") {
      createSlicelet(testEnv)(sliceletUuid = None)
    }
  }

  test("SliceletConf without Kubernetes namespace") {
    // Test plan: Verify that an IllegalArgumentException is thrown when attempting to create a
    // Slicelet using a configuration without a Kubernetes namespace.

    // Create Slicelet without kubernetes namespace.
    assertThrow[IllegalArgumentException](
      "Unable to determine Kubernetes namespace"
    ) {
      createSlicelet(testEnv)(sliceletKubernetesNamespace = None)
    }
  }

  test("SliceletConf with hostname from POD_IP environment variable") {
    // Test plan: Verify that the hostname is taken from the POD_IP environment variable if not
    // explicitly specified.
    val slicelet: SliceletDriver = createSlicelet(testEnv)(
      sliceletHostname = None,
      extraEnvVars = defaultExtraEnvVars ++ Map("POD_IP" -> "12.34.56.78")
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    val squid: Squid = waitForAnySquid(testEnv)
    assert(squid.resourceAddress.uri.getHost == "12.34.56.78")

    slicelet.stop()
  }

  test("SliceletConf with UUID from POD_UID environment variable") {
    // Test plan: Verify that the UUID is taken from the POD_UID environment variable if not
    // explicitly specified.
    val uuid: UUID = UUID.randomUUID()
    val slicelet: SliceletDriver = createSlicelet(testEnv)(
      sliceletUuid = None,
      extraEnvVars = defaultExtraEnvVars ++ Map("POD_UID" -> uuid.toString)
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    val squid: Squid = waitForAnySquid(testEnv)
    assert(squid.resourceUuid == uuid)

    slicelet.stop()
  }

  test("SliceletConf with Kubernetes namespace from NAMESPACE environment variable") {
    // Test plan: Verify that the Kubernetes namespace is taken from the NAMESPACE environment
    // variable if not explicitly specified.
    val slicelet: SliceletDriver = createSlicelet(testEnv)(
      sliceletKubernetesNamespace = None,
      extraEnvVars = defaultExtraEnvVars ++ Map("NAMESPACE" -> "namespace-from-env-var")
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    waitForAnySquid(testEnv)
    assert(
      getLatestSliceletWatchRequest(testEnv).get.subscriberData
        .asInstanceOf[SliceletData]
        .kubernetesNamespace ==
      "namespace-from-env-var"
    )

    slicelet.stop()
  }

  test("SliceletConf with SSL argument overrides") {
    // Test plan: Verify that the SliceletConf correctly overrides the SSL arguments. Verify this
    // by creating a SliceletConf with overridden SSL arguments and checking that the overridden
    // SSL arguments are used.

    val rawConf: Config = TestClientUtils.createAllowMultipleClientsConfig()
    val sliceletConf = new ProjectConf(Project.TestProject, rawConf) with SliceletConf {
      override protected def dicerTlsOptions: Option[TLSOptions] =
        TestTLSOptions.clientTlsOptionsOpt

      // Override the ssl arguments.
      override protected def dicerClientTlsOptions: Option[TLSOptions] = Some(
        TestTLSOptions.clientWithServiceIdentityTlsOptions
      )

      override protected def dicerServerTlsOptions: Option[TLSOptions] = Some(
        TestTLSOptions.serverWithServiceIdentityTlsOptions
      )
    }
    assertResult(Some(TestTLSOptions.clientWithServiceIdentityTlsOptions))(
      sliceletConf.getDicerClientTlsOptions
    )
    assertResult(Some(TestTLSOptions.serverWithServiceIdentityTlsOptions))(
      sliceletConf.getDicerServerTlsOptions
    )
  }

  test("Slicelet reports terminating on shutdown") {
    // Test plan: Create two Slicelets. Provide the assigner with an assignment and ensure that both
    // the Slicelet receives the assignment. Simulate a shutdown trigger ie. set TERMINATING state
    // in slicelet1 and verify eventually slicelet2 has the assignment for a key previously assigned
    // to slicelet1.
    val slicelet1: SliceletDriver = createSlicelet(testEnv)()
    slicelet1.start(selfPort = 1111, listenerOpt = None)
    val slicelet2: SliceletDriver = createSlicelet(testEnv)()
    slicelet2.start(selfPort = 2222, listenerOpt = None)

    // Look up the SQUIDs for the two Slicelets.
    val squid1: Squid = waitForSquidWithPort(testEnv, 1111)
    val squid2: Squid = waitForSquidWithPort(testEnv, 2222)

    // Wait for both slicelets to receive an assignment.
    AssertionWaiter("Wait for both slicelets to receive an assignment").await {
      assert(slicelet1.hasReceivedAssignment)
      assert(slicelet2.hasReceivedAssignment)
    }

    // Simulate a shutdown on slicelet1.
    logger.info(s"Terminating $slicelet1")
    slicelet1.setTerminatingState()

    // Eventually the status reported in slicelet1 should be TERMINATING.
    AssertionWaiter("Wait for the termination of slicelet1").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid1)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]

      assert(sliceletData.squid == squid1)
      assert(sliceletData.state == SliceletState.Terminating)
    }

    // slicelet2 should now own all the assignments.
    AssertionWaiter("Wait for slicelet2 to be assigned all Slices").await {
      val assignmentOpt1: Option[Assignment] =
        TestUtils.awaitResult(
          testEnv.testAssigner.getAssignment(expectedAssignerCanonicalizedTargetIdentifier),
          Duration.Inf
        )
      assert(assignmentOpt1.nonEmpty)
      assert(assignmentOpt1.get.assignedResources == Set(squid2))

      val assignmentOpt2: Option[Assignment] = slicelet2.latestAssignmentOpt
      assert(assignmentOpt2.nonEmpty)
      assert(assignmentOpt2.get.assignedResources == Set(squid2))
    }

    slicelet1.stop()
    slicelet2.stop()
  }

  test("Slicelet tracks generation and incarnation") {
    // Test plan: Verify that the slicelet tracks latest generation and incarnation correctly.
    // Verify this by creating an initial assignment, verifying the initial values, updating the
    // assignment, and verifying the metrics are updated correctly.

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze).
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    val initialAssignment =
      TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Slicelet has recorded generation of initial assignment").await {
      assert(
        Incarnation(
          readPrometheusMetric(
            "dicer_assignment_latest_store_incarnation",
            targetAndSourceMetricLabels
          ).toLong
        ) ==
        initialAssignment.generation.incarnation
      )
      assert(
        UnixTimeVersion(
          readPrometheusMetric(
            "dicer_assignment_latest_generation_number",
            targetAndSourceMetricLabels
          ).toLong
        ) ==
        initialAssignment.generation.number
      )
      assert(
        readPrometheusMetric(
          "dicer_assignment_number_new_generations_total",
          targetAndSourceMetricLabels
        ) == 1
      )
    }

    // Look up the SQUID for the Slicelet.
    val squid: Squid = waitForAnySquid(testEnv)

    // Create a second proposal to ensure the metrics are updated correctly.
    val secondProposal = createProposal(
      ("" -- "Dori") -> Seq("Pod2"),
      ("Dori" -- "Kili") -> Seq("Pod0"),
      ("Kili" -- "Nori") -> Seq(squid),
      ("Nori" -- "Ori") -> Seq(squid),
      ("Ori" -- ∞) -> Seq("Pod3")
    )
    val waitForAssignment =
      TestUtils.awaitResult(setAndFreezeAssignment(testEnv, secondProposal), Duration.Inf)

    AssertionWaiter("Slicelet has recorded generation of new assignment").await {
      assert(
        UnixTimeVersion(
          readPrometheusMetric(
            "dicer_assignment_latest_generation_number",
            targetAndSourceMetricLabels
          ).toLong
        ) ==
        waitForAssignment.generation.number
      )
      assert(
        readPrometheusMetric(
          "dicer_assignment_number_new_generations_total",
          targetAndSourceMetricLabels
        ) == 2
      )
    }
  }

  test("Calling start multiple times fails") {
    // Test plan: Verify that the Slicelet can only be started once.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    assertThrow[IllegalStateException]("must be stopped to start") {
      try {
        slicelet.start(selfPort = 1234, listenerOpt = None)
      } catch {
        case e: CompletionException => throw e.getCause
      }
    }
  }

  test("First assignment with Slicelet") {
    // Test plan: Create a Slicelet and assigner. Provide the assigner with a single assignment
    // and ensure that the Slicelet receives the assignment. Check a key with isAffinitizedKey.
    // Also, check that onChangedSlices was correctly called.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()

    val listener = new LoggingListener(slicelet)
    slicelet.start(selfPort = 1234, Some(listener))
    val address: Squid = waitForSquidWithPort(testEnv, 1234)
    val proposal = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq(address),
      (fp("Kili") -- fp("Nori")) -> Seq(address),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    val assignment: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(testEnv, proposal),
      Duration.Inf
    )

    // Wait for the assignment to show up.
    waitForAssignment(slicelet, fp("Kili"))
    logger.info(s"Received assignment $slicelet")

    // Check the log.
    listener.waitForEntry(SliceSetImpl(fp("Fili") -- fp("Nori")))

    // Check the state.
    val currentSlices: Seq[Slice] = slicelet.assignedSlices
    logger.info(s"Current slices = $currentSlices")
    assert(slicelet.latestAssignmentOpt.contains(assignment))
    assert(
      SliceSetImpl(currentSlices) == SliceSetImpl(
        assignment.sliceMap.entries(2).slice,
        assignment.sliceMap.entries(3).slice
      )
    )
    slicelet.stop()
  }

  test("Determine changed slices") {
    // Test plan: Create some existing and new Slices and check that the determination of added and
    // removed slices is done correctly.

    val existingSlices = SliceSetImpl(20 -- 40, 60 -- 80)

    /**
     * Checks that given `existingSlices` and `newSlices`, `determineChangedSlices` computes
     * the added and removed ranges correctly as `expectedAddedSlices` and `expectedRemovedSlices`,
     * respectively.
     */
    def checkChangedSlices(
        newSlices: SliceSetImpl,
        expectedAddedSlices: SliceSetImpl,
        expectedRemovedSlices: SliceSetImpl): Unit = {
      val (removed, added): (SliceSetImpl, SliceSetImpl) =
        SliceSetImpl.diff(existingSlices, newSlices)
      logger.info(s"Added: $added, Removed: $removed")
      assert(expectedAddedSlices == added)
      assert(expectedRemovedSlices == removed)
    }

    // Same slices.
    val newSlices1 = SliceSetImpl(20 -- 40, 60 -- 80)
    checkChangedSlices(newSlices1, SliceSetImpl.empty, SliceSetImpl.empty)

    // New slices are not intersecting on high or low.
    val newSlices2 = SliceSetImpl(10 -- 15, 85 -- 90)
    checkChangedSlices(
      newSlices2,
      SliceSetImpl(10 -- 15, 85 -- 90),
      SliceSetImpl(20 -- 40, 60 -- 80)
    )

    // Intersects at low and high end.
    val newSlices3 = SliceSetImpl(15 -- 25, 70 -- 85)
    checkChangedSlices(
      newSlices3,
      SliceSetImpl(15 -- 20, 80 -- 85),
      SliceSetImpl(25 -- 40, 60 -- 70)
    )

    // Subsumed or subsumes slice.
    val newSlices4 = SliceSetImpl(25 -- 35, 55 -- 85)
    checkChangedSlices(
      newSlices4,
      SliceSetImpl(55 -- 60, 80 -- 85),
      SliceSetImpl(20 -- 25, 35 -- 40)
    )
  }

  test("Multiple assignments") {
    // Test plan: Create a Slicelet and assigner. Provide the assigner with a single assignment
    // and ensure that the Slicelet receives the assignment. Check a key with isAffinitizedKey.
    // Also, check that onChangedSlices was correctly called. Then change the assignment and check
    // again.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    val listener = new LoggingListener(slicelet)
    slicelet.start(selfPort = 1234, Some(listener))
    val squid: Squid = slicelet.squid
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- fp("Ori")) -> Seq(squid),
      (fp("Ori") -- ∞) -> Seq("Pod3")
    )
    val assignment1: Assignment =
      TestUtils.awaitResult(setAndFreezeAssignment(testEnv, proposal1), Duration.Inf)

    // Wait for the assignment to show up.
    waitForGenerationAtLeast(slicelet, assignment1.generation)
    logger.info(s"Received assignment $slicelet")

    // Now do some isAssigned checks synchronously since the assignment is now available.
    assert(isAssigned(slicelet, fp("Kili")))
    assert(!isAssigned(slicelet, fp("Ori")))

    // Check the log.
    listener.waitForEntry(SliceSetImpl(fp("Kili") -- fp("Ori")))

    // Change the assignment.
    val proposal2 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq(squid),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    val assignment2: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(testEnv, proposal2),
      Duration.Inf
    )

    // Wait for the assignment to show up.
    waitForAssignment(slicelet, fp("Fili"))
    listener.waitForEntry(SliceSetImpl(fp("Fili") -- fp("Nori")))

    // Check the internal state.
    val currentSlices: Seq[Slice] = slicelet.assignedSlices
    logger.info(s"Current slices = $currentSlices")
    assert(slicelet.latestAssignmentOpt.contains(assignment2))
    assert(
      SliceSetImpl(currentSlices) == SliceSetImpl(
        assignment2.sliceMap.entries(2).slice,
        assignment2.sliceMap.entries(3).slice
      )
    )
    slicelet.stop()
  }

  test("Assignments with multiple replicas") {
    // Test plan: Verify that the Slicelet can accept assignments where each Slice has more than 1
    // replica, and its public APIs behave correctly based on the multi-replica assignment. In
    // addition, verify this with 2 different multi-replica assignments.

    // Setup: 3 Slicelets to involve in the test.
    val slicelet0: SliceletDriver = createSlicelet(testEnv)()
    val slicelet1: SliceletDriver = createSlicelet(testEnv)()
    val slicelet2: SliceletDriver = createSlicelet(testEnv)()

    // Setup: 3 LoggingListeners for the 3 Slicelets respectively.
    val listener0 = new LoggingListener(slicelet0)
    val listener1 = new LoggingListener(slicelet1)
    val listener2 = new LoggingListener(slicelet2)

    slicelet0.start(selfPort = 2345, Some(listener0))
    slicelet1.start(selfPort = 1234, Some(listener1))
    slicelet2.start(selfPort = 3456, Some(listener2))

    val squid0: Squid = waitForSquidWithPort(testEnv, 2345)
    val otherSquid: Squid = waitForSquidWithPort(testEnv, 1234)
    val squid2: Squid = waitForSquidWithPort(testEnv, 3456)

    // Setup: Create and freeze an assignment that contains Slices with multiple replicas.
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq(squid0, otherSquid, squid2),
      (fp("Dori") -- fp("Kili")) -> Seq(squid0, otherSquid),
      (fp("Kili") -- fp("Nori")) -> Seq(otherSquid, squid2),
      (fp("Nori") -- fp("Ori")) -> Seq(squid2, squid0),
      (fp("Ori") -- ∞) -> Seq(squid0)
    )
    setAndFreezeAssignment(testEnv, proposal1)

    // Verify: Wait for all the Slicelets to receive the assignment with replicas. Check whether the
    // Slicelets received the assignments by checking `isAssigned()` for individual keys.
    AssertionWaiter("All Slicelets received assignment with replicas").await {
      assert(isAssigned(slicelet0, ""))
      assert(isAssigned(slicelet1, ""))
      assert(isAssigned(slicelet2, ""))

      assert(isAssigned(slicelet0, fp("Dori")))
      assert(isAssigned(slicelet1, fp("Dori")))
      assert(!isAssigned(slicelet2, fp("Dori")))

      assert(isAssigned(slicelet0, fp("Fili")))
      assert(isAssigned(slicelet1, fp("Fili")))
      assert(!isAssigned(slicelet2, fp("Fili")))

      assert(!isAssigned(slicelet0, fp("Kili")))
      assert(isAssigned(slicelet1, fp("Kili")))
      assert(isAssigned(slicelet2, fp("Kili")))

      assert(isAssigned(slicelet0, fp("Ori")))
      assert(!isAssigned(slicelet1, fp("Ori")))
      assert(!isAssigned(slicelet2, fp("Ori")))
    }

    // Verify: Check the log.
    listener0.waitForEntry(SliceSetImpl("" -- fp("Kili"), fp("Nori") -- ∞))
    listener1.waitForEntry(SliceSetImpl("" -- fp("Nori")))
    listener2.waitForEntry(SliceSetImpl("" -- fp("Dori"), fp("Kili") -- fp("Ori")))

    // Setup: Change the assignment.
    val proposal2 = createProposal(
      ("" -- ∞) -> Seq(squid0, otherSquid, squid2)
    )
    setAndFreezeAssignment(testEnv, proposal2)

    // Verify: Check the new log.
    listener0.waitForEntry(SliceSetImpl("" -- ∞))
    listener1.waitForEntry(SliceSetImpl("" -- ∞))
    listener2.waitForEntry(SliceSetImpl("" -- ∞))

    slicelet0.stop()
    slicelet1.stop()
    slicelet2.stop()
  }

  test("isAssignedContinuously simple") {
    // Test plan: Create an assigner and block assignments. Create a Slicelet and verify that
    // `isAssignedContinuously` returns false with no assignment. Provide the assigner with a single
    // assignment and ensure that the Slicelet receives it. Verify that isAssignedContinuously
    // returns true for a key that is assigned to Slicelet, and false for a key that isn't. This
    // test doesn't really exercise the "continuously" check.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze). Using `blockAssignment` is insufficient in this
    // case because the initial assignment will be queued and can still assign the entire key range
    // to the slicelet. This assignment will pass all `waitForAssignment(slicelet, key)` calls and
    // fail any `!handle.isAssignedContinuously` check before the next assignment arrives.
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    slicelet.start(selfPort = 1234, listenerOpt = None)
    Using.resource(slicelet.createHandle(fp("Nori"))) { handle =>
      assert(!handle.isAssignedContinuously)
    }

    val squid: Squid = slicelet.squid
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- fp("Ori")) -> Seq(squid),
      (fp("Ori") -- ∞) -> Seq("Pod3")
    )
    setAndFreezeAssignment(testEnv, proposal1)
    waitForAssignment(slicelet, fp("Kili"))
    Using.resource(slicelet.createHandle(fp("Nori"))) { handle =>
      assert(handle.isAssignedContinuously)
    }
    Using.resource(slicelet.createHandle(fp(""))) { handle =>
      assert(!handle.isAssignedContinuously)
    }
    slicelet.stop()
  }

  test("isAssignedContinuously true and false") {
    // Test plan:
    // 1. Create a Slicelet and assigner.
    // 2. Provide the assigner with a single assignment and ensure that the Slicelet receives the
    // assignment. Get a handle for a key assigned to Slicelet.
    // 3. Update the assignment but not for the handle's key. Ensure the Slicelet receives the
    // assignment and that `isAssignedContinuously` returns true.
    // 4. Update the assignment, this time for the handle's key. Ensure the Slicelet receives the
    // assignment and that `isAssignedContinuously` now returns false.

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze).
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)

    val squid: Squid = slicelet.squid
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- fp("Ori")) -> Seq(squid),
      (fp("Ori") -- ∞) -> Seq("Pod3")
    )
    setAndFreezeAssignment(testEnv, proposal1)
    waitForAssignment(slicelet, fp("Kili"))
    Using.resource(slicelet.createHandle(fp("Kili"))) { handle =>
      assert(handle.isAssignedContinuously)

      // Dori-Kili reassigned to `resource`, Nori-Ori reassigned to Pod0.
      val proposal2 = createProposal(
        ("" -- fp("Dori")) -> Seq("Pod2"),
        (fp("Dori") -- fp("Kili")) -> Seq(squid),
        (fp("Kili") -- fp("Nori")) -> Seq(squid),
        (fp("Nori") -- fp("Ori")) -> Seq("Pod0"),
        (fp("Ori") -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal2)
      waitForAssignment(slicelet, fp("Dori"))
      assert(handle.isAssignedContinuously)

      // Kili-Nori reassigned to Pod1, Nori-Ori reassigned to resource (the latter is just so that
      // we can use it in `waitForAssignment`).
      val proposal3 = createProposal(
        ("" -- fp("Dori")) -> Seq("Pod2"),
        (fp("Dori") -- fp("Kili")) -> Seq(squid),
        (fp("Kili") -- fp("Nori")) -> Seq("Pod1"),
        (fp("Nori") -- fp("Ori")) -> Seq(squid),
        (fp("Ori") -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal3)
      waitForAssignment(slicelet, fp("Nori"))
      assert(!handle.isAssignedContinuously)
    }
    slicelet.stop()
  }

  test("isAssignedContinuously is false even with assigning back") {
    // Test plan: Create a Slicelet and assigner. Provide the assigner with a single assignment and
    // ensure that the Slicelet receives the assignment. Get a handle for a key assigned to
    // Slicelet. Update the assignment for handle's key, then update it again to assign back to the
    // Slicelet. Verify that `isAssignedContinuously` returns false.

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze).
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    val squid: Squid = slicelet.squid
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- fp("Ori")) -> Seq(squid),
      (fp("Ori") -- ∞) -> Seq("Pod3")
    )
    setAndFreezeAssignment(testEnv, proposal1)
    waitForAssignment(slicelet, fp("Kili"))
    Using.resource(slicelet.createHandle(fp("Nori"))) { handle =>
      assert(handle.isAssignedContinuously)

      // Nori-Ori reassigned to Pod0.
      val proposal2 = createProposal(
        ("" -- fp("Dori")) -> Seq("Pod2"),
        (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
        (fp("Kili") -- fp("Nori")) -> Seq(squid),
        (fp("Nori") -- fp("Ori")) -> Seq("Pod0"),
        (fp("Ori") -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal2)
      // Nori-Ori assigned back to resource, Dori-Kili reassigned to resource (the latter is just so
      // that we can use it in `waitForAssignment`).
      val proposal3 = createProposal(
        ("" -- fp("Dori")) -> Seq("Pod2"),
        (fp("Dori") -- fp("Kili")) -> Seq(squid),
        (fp("Kili") -- fp("Nori")) -> Seq(squid),
        (fp("Nori") -- fp("Ori")) -> Seq(squid),
        (fp("Ori") -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal3)
      waitForAssignment(slicelet, "Dori")
      assert(!handle.isAssignedContinuously)
    }
    slicelet.stop()
  }

  test("isAssignedContinuously assignment splits") {
    // Test plan: Create a Slicelet and assigner. Provide the assigner with a single assignment and
    // ensure that the Slicelet receives the assignment. Get two handles for two keys assigned to
    // Slicelet. Split the assignment, with one of the keys kept on the same Slicelet, and another
    // moved to a different one. Verify that `isAssignedContinuously` returns true for the former
    // and false for the latter.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    val squid: Squid = waitForSquidWithPort(testEnv, 1234)
    val proposal1 = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
      (fp("Kili") -- ∞) -> Seq(squid)
    )
    setAndFreezeAssignment(testEnv, proposal1)
    waitForAssignment(slicelet, fp("Kili"))
    Using.Manager { use =>
      val noriHandle = use(slicelet.createHandle(fp("Nori")))
      val oriHandle = use(slicelet.createHandle(fp("Ori")))
      assert(noriHandle.isAssignedContinuously)
      assert(oriHandle.isAssignedContinuously)

      // [Kili, ∞) split into [Kili, Ori) with same resource, and [Ori, ∞) with
      // different resource.
      val proposal2 = createProposal(
        ("" -- fp("Dori")) -> Seq("Pod2"),
        (fp("Dori") -- fp("Kili")) -> Seq("Pod0"),
        (fp("Kili") -- fp("Ori")) -> Seq(squid),
        (fp("Ori") -- ∞) -> Seq("Pod0")
      )
      setAndFreezeAssignment(testEnv, proposal2)
      waitForUnassigned(slicelet, fp("Ori"))
      assert(noriHandle.isAssignedContinuously)
      assert(!oriHandle.isAssignedContinuously)
    }.get
    slicelet.stop()
  }

  test("isAssignedContinuously with assignments containing multiple replicas") {
    // Test plan: Verify that isAssignedContinuously() returns correct results for assignments
    // that map each Slice to multiple resources.

    // Setup: Create the Slicelet being tested.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    val squid: Squid = slicelet.squid

    // Setup: Initial assignment.
    val proposal1 = createProposal(
      ("" -- fp("Fili")) -> Seq("OtherPod0"), // Unassigned.
      (fp("Fili") -- fp("Nori")) -> Seq(squid, "OtherPod0", "OtherPod1"), // Assigned
      (fp("Nori") -- ∞) -> Seq(squid, "OtherPod0") // Assigned.
    )
    val assignment1: Assignment =
      TestUtils.awaitResult(setAndFreezeAssignment(testEnv, proposal1), Duration.Inf)
    waitForGenerationAtLeast(slicelet, assignment1.generation)

    Using.Manager { use =>
      val doriHandle: SliceletDriver.SliceKeyHandle =
        use(slicelet.createHandle(fp("Dori"))) // In ["", Fili).
      val kiliHandle: SliceletDriver.SliceKeyHandle =
        use(slicelet.createHandle(fp("Kili"))) // In [Fili, Nori).
      val oriHandle: SliceletDriver.SliceKeyHandle =
        use(slicelet.createHandle(fp("Ori"))) // In [Nori, ∞).

      // Verify: based on proposal1.
      assert(!doriHandle.isAssignedContinuously)
      assert(kiliHandle.isAssignedContinuously)
      assert(oriHandle.isAssignedContinuously)

      // Setup: Second assignment.
      val proposal2 = createProposal(
        ("" -- fp("Fili")) -> Seq("OtherPod1"), // Unassigned.
        (fp("Fili") -- fp("Nori")) -> Seq(squid, "OtherPod3"), // Continuously Assigned.
        (fp("Nori") -- ∞) -> Seq("OtherPod0", "OtherPod1") // Become unassigned.
      )
      setAndFreezeAssignment(testEnv, proposal2)
      waitForUnassigned(slicelet, fp("Nori"))

      assert(!doriHandle.isAssignedContinuously) // Continuously unassigned.
      assert(kiliHandle.isAssignedContinuously) // Continuously assigned.
      assert(!oriHandle.isAssignedContinuously) // Become unassigned.

      // Setup: Third assignment.
      val proposal3 = createProposal(
        ("" -- fp("Fili")) -> Seq("OtherPod2"), // Unassigned.
        (fp("Fili") -- fp("Nori")) -> Seq(squid), // Continuously Assigned.
        (fp("Nori") -- ∞) -> Seq(squid, "OtherPod1") // Assigned back.
      )
      setAndFreezeAssignment(testEnv, proposal3)
      waitForAssignment(slicelet, fp("Nori"))

      assert(!doriHandle.isAssignedContinuously) // Continuously unassigned.
      assert(kiliHandle.isAssignedContinuously) // Continuously assigned.
      assert(!oriHandle.isAssignedContinuously) // Though assigned back, not assigned continuously.
    }.get

    slicelet.stop()
  }

  test("Slicelet correctly tracks SliceKeyHandle metrics") {
    // Test plan: Verify that the Slicelet maintains handle metrics correctly. Verify this by
    // creating two slicelets with the same target name, creating and closing SliceKeyHandles, and
    // verifying the counts.
    val slicelet1: SliceletDriver = createSlicelet(testEnv)()
    val slicelet2: SliceletDriver = createSlicelet(testEnv)()
    slicelet1.start(selfPort = 1234, listenerOpt = None)
    slicelet2.start(selfPort = 1234, listenerOpt = None)
    val initialCreatedCount: Double = getSliceKeyHandlesCreatedMetric
    val initialOutstandingCount: Double = getSliceKeyHandlesOutstandingMetric
    Using.Manager { use =>
      use(slicelet1.createHandle("Dori"))
      use(slicelet1.createHandle("Kili"))
      use(slicelet1.createHandle("Ori"))
      use(slicelet2.createHandle("Nori"))
      assert(getSliceKeyHandlesCreatedMetric == initialCreatedCount + 4)
      // Ensure the number of handles outstanding is the number of created -- no handles were closed
      assert(getSliceKeyHandlesOutstandingMetric == initialOutstandingCount + 4)
      assert(getSliceKeyHandlesOutstandingMetric == initialCreatedCount + 4)
    }.get
    assert(getSliceKeyHandlesOutstandingMetric == initialOutstandingCount)

    // Verify that outstanding is negative if a handle is closed twice
    val noriHandle = slicelet2.createHandle("Nori")
    noriHandle.close()
    if (noriHandle.canBeClosedMultipleTimes) {
      noriHandle.close()
      assert(getSliceKeyHandlesCreatedMetric == initialCreatedCount + 5)
      assert(getSliceKeyHandlesOutstandingMetric == initialOutstandingCount - 1)
    }
  }

  test("SliceKeyHandle.incrementLoadBy") {
    // Test plan: supply positive incremental load to SliceKeyHandle for both assigned and
    // unassigned Slices. Verify that watch requests include the expected load measurements. Note
    // that because of decay from the EWMA counters used internally by the Slicelet, the load
    // measurements received by the Assigner will be less than the supplied load.

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze).
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    val squid: Squid = slicelet.squid
    val proposal1 = createProposal(
      ("" -- "Kili") -> Seq(squid),
      ("Kili" -- "Nori") -> Seq("Pod0"),
      ("Nori" -- ∞) -> Seq(squid)
    )
    setAndFreezeAssignment(testEnv, proposal1)
    waitForAssignment(slicelet, "")
    Using.Manager { use =>
      // Create handles for two assigned keys (Fili and Ori) and one unassigned key (Kili).
      val doriHandle = use(slicelet.createHandle("Dori"))
      val kiliHandle = use(slicelet.createHandle("Kili"))
      val oriHandle = use(slicelet.createHandle("Ori"))

      assert(doriHandle.key == toSliceKey("Dori"))
      assert(kiliHandle.key == toSliceKey("Kili"))
      assert(oriHandle.key == toSliceKey("Ori"))

      assert(doriHandle.isAssignedContinuously)
      assert(!kiliHandle.isAssignedContinuously)
      assert(oriHandle.isAssignedContinuously)

      // Report load for each handle.
      doriHandle.incrementLoadBy(10)
      kiliHandle.incrementLoadBy(20)
      oriHandle.incrementLoadBy(30)
    }.get
    // Wait for a load report containing non-zero load for both the assigned slices and unassigned
    // load.
    AssertionWaiter("Wait for load report").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv)
      assert(requestOpt.isDefined)
      val request: ClientRequest = requestOpt.get
      assert(request.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

      // Verify that the SliceletData includes load attributed to the two Slices assigned to the
      // Slicelet.
      assert(sliceletData.attributedLoads.size == 2)
      val attributed1: SliceletData.SliceLoad = sliceletData.attributedLoads.head
      val attributed2: SliceletData.SliceLoad = sliceletData.attributedLoads.last
      assert(attributed1.primaryRateLoad > 0)
      assert(attributed1.slice == ("" -- "Kili"))
      assert(attributed2.primaryRateLoad > 0)
      assert(attributed2.slice == ("Nori" -- ∞))

      // Verify that the SliceletData includes unattributed load.
      assert(sliceletData.unattributedLoadOpt.isDefined)
      assert(sliceletData.unattributedLoadOpt.get.primaryRateLoad > 0)
    }
    slicelet.stop()
  }

  test("Slicelet load reporting for multiple replicas") {
    // Test plan: Verify that Slicelets can report load correctly for multi-replica assignments.
    // Verify this by injecting a multi-replica assignment to the Slicelet that contains some
    // historical load, and then calling incrementLoadBy() to supply some new load, verifying that
    // the Slicelet will send watch request containing expected load values and number of replicas.

    // Setup: Create an initial proposal to freeze the assignment before starting the Slicelet
    // (otherwise starting the Slicelet first would trigger initial assignment generation, which
    // would then race with any subsequent assignment freeze).
    val otherSquid: Squid = createTestSquid(11451)
    val initialProposal = createProposal(("" -- ∞) -> Seq(otherSquid))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)
    val squid: Squid = slicelet.squid

    // Setup: Create and freeze a multi-replica assignment with historical load.
    val proposal = createProposal(
      (("" -- "Fili") -> Seq(squid, otherSquid)).copy(primaryRateLoadOpt = Some(200.0)),
      (("Fili" -- "Kili") -> Seq(squid)).copy(primaryRateLoadOpt = Some(100.0)),
      (("Kili" -- "Nori") -> Seq(otherSquid)).copy(primaryRateLoadOpt = Some(100.0)),
      (("Nori" -- ∞) -> Seq(squid, otherSquid)).copy(primaryRateLoadOpt = Some(200.0))
    )
    setAndFreezeAssignment(testEnv, proposal)
    waitForAssignment(slicelet, "")

    Using.Manager { use =>
      // Key handlers to test.
      val doriHandle = use(slicelet.createHandle("Dori"))
      val filiHandle = use(slicelet.createHandle("Fili"))
      val kiliHandle = use(slicelet.createHandle("Kili"))
      val oriHandle = use(slicelet.createHandle("Ori"))

      // Sanity checks.
      assert(doriHandle.key == toSliceKey("Dori"))
      assert(filiHandle.key == toSliceKey("Fili"))
      assert(kiliHandle.key == toSliceKey("Kili"))
      assert(oriHandle.key == toSliceKey("Ori"))
      assert(doriHandle.isAssignedContinuously)
      assert(filiHandle.isAssignedContinuously)
      assert(!kiliHandle.isAssignedContinuously)
      assert(oriHandle.isAssignedContinuously)

      // Setup: In addition, report some load for Ori.
      oriHandle.incrementLoadBy(100)
    }.get

    AssertionWaiter("Wait for expected load report").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv)
      assert(requestOpt.isDefined)
      val request: ClientRequest = requestOpt.get
      assert(request.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

      // Verify: the SliceletData includes load attributed to the three Slices assigned to the
      // Slicelet.
      assert(sliceletData.attributedLoads.size == 3)
      val attributedMinToFili: SliceletData.SliceLoad = sliceletData.attributedLoads.head
      val attributedFiliToKili: SliceletData.SliceLoad = sliceletData.attributedLoads(1)
      val attributedNoriToInf: SliceletData.SliceLoad = sliceletData.attributedLoads.last

      // Verify: Load values and number of replicas in the watch reqeust is as expected.

      assert(attributedMinToFili.slice == ("" -- "Fili"))
      assert(attributedMinToFili.numReplicas == 2)
      val loadMinToFili: Double = attributedMinToFili.primaryRateLoad
      // ["", Fili) has historical load 200 and replicas of 2, and there is no new load of it. With
      // the load counter decaying with time, the reported load should be close to 200 / 2 = 100.
      assert(loadMinToFili < 100.0)
      assert(loadMinToFili > 80.0)

      assert(attributedFiliToKili.slice == ("Fili" -- "Kili"))
      assert(attributedFiliToKili.numReplicas == 1)
      // [Fili, Kili) has historical load of 100 and replica of 1, and there is no new load for it.
      // The load counter of [Fili, Kili) should be seeded with 100, which is the same as
      // ["", Fili), so its reported load shold also be the same as ["", Fili).
      assert(attributedFiliToKili.primaryRateLoad == loadMinToFili)

      assert(attributedNoriToInf.slice == ("Nori" -- ∞))
      assert(attributedNoriToInf.numReplicas == 2)
      // [Nori, ∞)'s load counter should be seeded with 200 / 2 = 100. It has some additional new
      // load (100) than ["", Fili), so its reported load should be less than 100 (because of time
      // decaying) but more than ["", Fili).
      assert(attributedNoriToInf.primaryRateLoad < 100.0)
      assert(attributedNoriToInf.primaryRateLoad > loadMinToFili)
    }
    slicelet.stop()
  }

  test("SliceKeyHandle: increment non-positive load") {
    // Test plan: supply non-positive incremental load to SliceKeyHandle and verify that the
    // operation doesn't fail, and has no effect on the attributed / unattributed load.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, listenerOpt = None)

    Using.resource(slicelet.createHandle(fp("Nori"))) { handle =>
      handle.incrementLoadBy(0)
      handle.incrementLoadBy(-1)
      handle.incrementLoadBy(-42)
    }

    // Verify that the both attributed loads and unattributed load are 0.
    AssertionWaiter("Wait for expected load report").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv)
      assert(requestOpt.isDefined)
      val request: ClientRequest = requestOpt.get
      assert(request.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

      val totalAttributedLoad: Double =
        sliceletData.attributedLoads.map((_: SliceletData.SliceLoad).primaryRateLoad).sum
      assert(totalAttributedLoad == 0.0)
      assert(sliceletData.unattributedLoadOpt.get.primaryRateLoad == 0.0)
    }

    slicelet.stop()
  }

  test("Slicelet records request size metrics when sending to Assigner") {
    // Test plan: Verify that the Slicelet records request size metrics when sending watch
    // requests to the Assigner.

    // Trackers to verify that the Slicelet records request size metrics when sending to Assigner.
    val countTracker = ChangeTracker[Double](
      () => getClientRequestSizeCount(expectedSliceletTargetIdentifier, ClientType.Slicelet)
    )
    val sumTracker = ChangeTracker[Double](
      () => getClientRequestSizeSum(expectedSliceletTargetIdentifier, ClientType.Slicelet)
    )

    // Create the Slicelet and start it. When the Slicelet connects to the Assigner, it will
    // automatically be assigned all slices.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, None)

    // Wait for the Slicelet to receive the automatic assignment.
    waitForAssignment(slicelet, SliceKey.MIN)

    // Verify that the Slicelet recorded request size metrics when sending to the Assigner.
    assert(
      countTracker.totalChange() >= 1.0,
      "Slicelet should have recorded at least 1 request size when sending to Assigner"
    )
    assert(
      sumTracker.totalChange() > 0.0,
      "Slicelet should have recorded positive request sizes when sending to Assigner"
    )

    slicelet.stop()
  }

  test("Get Assignment via Slicelet") {
    // Test plan: Create a Slicelet and assigner. Provide the assigner with a single assignment
    // and ensure that the Slicelet receives the assignment. Have another stub that communicates
    // with the Slicelet - make sure that the assignment is received from the Slicelet.

    // Create the Slicelet and start a server on that Slicelet.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, None)
    val squid: Squid = waitForSquidWithPort(testEnv, 1234)

    // Set up the assignment at the Assigner.
    val proposal = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq(squid),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    val assignment: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(testEnv, proposal),
      Duration.Inf
    )

    // Verify that a Clerk ultimately receives the assignment from the Slicelet.
    val clerkConf: ClerkConf = TestClientUtils.createTestClerkConf(
      sliceletPort = slicelet.activePort,
      clientTlsFilePathsOpt = None
    )
    // Use explicit http:// scheme to construct the watch URI because:
    // - Without a scheme, Armeria gRPC client defaults to HTTPS even when empty TLS options are
    //   configured.
    // - The Rust Slicelet in tests runs a plain HTTP server (no TLS options are configured).
    // - Explicit http:// ensures the Clerk connects via plain HTTP to the Rust Slicelet server.
    val watchUri = URI.create(s"http://localhost:${slicelet.activePort}")

    val clerk = ClerkImpl.create(
      clerkConf,
      expectedSliceletTargetIdentifier,
      watchUri, { resourceAddress: ResourceAddress =>
        resourceAddress
      }
    )

    AssertionWaiter("Wait for assignment").await {
      val clerkAssignmentOpt: Option[Assignment] =
        clerk.forTest.getLatestAssignmentOpt
      assert(clerkAssignmentOpt.contains(assignment))
    }

    // Stop the Slicelet and Clerk created for the test.
    slicelet.stop()
    clerk.stop()
  }

  test("Assigner sees a NOT_READY Slicelet when Slicelet's server reports it is not ready") {
    // Test plan: Verify that the Assigner sees a NOT_READY Slicelet when the Slicelet's server is
    // not ready. Verify this by setting the readiness provider of the Slicelet to report
    // that the server is not ready, waiting for the Assigner to get a watch request for that
    // Slicelet, and checking that the Slicelet's status is NOT_READY.

    val slicelet: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    // Set readiness provider to report not ready.
    slicelet.setReadinessStatus(isReady = false)

    val port: Int = 1111
    slicelet.start(selfPort = port, listenerOpt = None)
    val squid: Squid = waitForSquidWithPort(testEnv, port)

    // Check that the Assigner got a NOT_READY status from the Slicelet
    AssertionWaiter("Wait for Assigner to receive SliceletData from this Slicelet").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.NotReady)
    }

    // TODO(<internal bug>): verify this Squid is NOT in the assignment when Dicer stops routing traffic
    //  to pods with Slicelets in NOT_READY state
    // NOT_READY Slicelet is still in assignment.
    assert(waitForSquidWithPort(testEnv, port) == squid)

    // Verify that eventually the number of observations for the NOT_READY state exceed the number
    // for all other states.
    AssertionWaiter("Wait for metrics to be updated").await {
      val notReadyCount: Double = getSliceletStateCount(SliceletDataP.State.NOT_READY)
      for (state: SliceletDataP.State <- SliceletDataP.State.values) {
        if (state != SliceletDataP.State.NOT_READY) {
          assert(getSliceletStateCount(state) < notReadyCount)
        }
      }
    }

    slicelet.stop()
  }

  gridTest("Slicelet always reports TERMINATING when terminating regardless of readiness")(
    // Test plan: Create a Slicelet that is ready, then set it to terminating state and verify
    // it reports TERMINATING. Also test with a not-ready Slicelet to ensure TERMINATING
    // takes precedence over readiness state.
    Seq(true, false)
  ) { isReady: Boolean =>
    val slicelet: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    // Set readiness provider to report the desired initial state.
    slicelet.setReadinessStatus(isReady)
    val port: Int = 1111
    slicelet.start(selfPort = port, listenerOpt = None)
    val squid: Squid = waitForSquidWithPort(testEnv, port)

    val expectedInitialState: SliceletState =
      if (isReady) SliceletState.Running else SliceletState.NotReady
    AssertionWaiter(s"Wait for initial state $expectedInitialState").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == expectedInitialState)
    }

    // Set terminating state
    slicelet.setTerminatingState()

    // Should now report TERMINATING regardless of readiness
    AssertionWaiter("Wait for TERMINATING state").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.Terminating)
    }

    // Verify that eventually the number of observations for the TERMINATING state exceed the number
    // for all other states, indicating that the Slicelet is reporting TERMINATING regardless of
    // readiness.
    AssertionWaiter("Wait for metrics to be updated").await {
      val terminatingCount: Double = getSliceletStateCount(SliceletDataP.State.TERMINATING)
      for (state: SliceletDataP.State <- SliceletDataP.State.values) {
        if (state != SliceletDataP.State.TERMINATING) {
          assert(getSliceletStateCount(state) < terminatingCount)
        }
      }
    }

    slicelet.stop()
  }

  test("Slicelet transitions from NOT_READY to RUNNING when readiness changes") {
    // Test plan: Create a Slicelet that starts in NOT_READY state, verify the Assigner sees
    // NOT_READY, then transition to ready, and verify the Assigner sees RUNNING.

    val slicelet: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    val port: Int = 1111
    slicelet.start(selfPort = port, listenerOpt = None)
    val squid: Squid = waitForSquidWithPort(testEnv, port)

    // Verify initial NOT_READY state
    AssertionWaiter("Wait for initial NOT_READY state").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.NotReady)
    }

    // Transition to READY
    slicelet.setReadinessStatus(true)

    // Verify transition to RUNNING state
    AssertionWaiter("Wait for transition to RUNNING state").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.Running)
    }

    // Verify that metrics contain counts for both NOT_READY and RUNNING states
    AssertionWaiter("Wait for metrics to be updated").await {
      for (state: SliceletDataP.State <- SliceletDataP.State.values) {
        if (state == SliceletDataP.State.NOT_READY || state == SliceletDataP.State.RUNNING) {
          assert(getSliceletStateCount(state) > 0)
        } else {
          assert(getSliceletStateCount(state) == 0)
        }
      }
    }

    slicelet.stop()
  }

  test("Slicelet starts successfully when readiness check blocks indefinitely") {
    // Test plan: Verify that the Slicelet starts successfully even when the readiness provider's
    // isReady call blocks indefinitely, using the BLOCKED_READINESS_CHECK_START_DELAY
    // fallback mechanism. Verify the Slicelet starts up and reports NOT_READY initially, then
    // transitions to RUNNING after unblocking the readiness provider.

    val slicelet: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    // Setup: Block the readiness provider.
    slicelet.setReadinessProviderBlocked(true)
    slicelet.setReadinessStatus(true)
    val port: Int = 12121
    slicelet.start(selfPort = port, listenerOpt = None)

    // Verify: The Slicelet should still start successfully (signalled by being included in the
    // assignment) despite the blocked readiness check.
    val squid: Squid = waitForSquidWithPort(testEnv, port)

    // Verify: The Slicelet should report NOT_READY, the default readiness starting state.
    // Use AssertionWaiter since the readiness poller runs in its own execution context.
    AssertionWaiter("The Slicelet is reporting NOT_READY").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.NotReady)
    }

    // Now unblock the readiness provider. It is already set to report ready.
    slicelet.setReadinessProviderBlocked(false)

    // Verify: The Slicelet should transition to RUNNING.
    // AssertionWaiter will retry until the readiness poller (which runs in real time) detects the
    // change.
    AssertionWaiter("The Slicelet is reporting RUNNING").await {
      val requestOpt: Option[ClientRequest] = getLatestSliceletWatchRequest(testEnv, squid)
      assert(requestOpt.nonEmpty)
      assert(requestOpt.get.subscriberData.isInstanceOf[SliceletData])
      val sliceletData = requestOpt.get.subscriberData.asInstanceOf[SliceletData]
      assert(sliceletData.state == SliceletState.Running)
    }

    slicelet.stop()
  }

  test("Assigner observes Slicelet readiness state transitions via full probe stack") {
    // Test plan: Verify that the Assigner correctly observes Slicelet state transitions (NOT_READY
    // -> RUNNING -> NOT_READY -> TERMINATING) using the full readiness probe stack
    // (InfoService with HTTP probes). This validates end-to-end behavior from the Assigner's
    // perspective, ensuring that readiness changes propagate correctly and that TERMINATING state
    // takes precedence over readiness.

    // Set readiness source to report `notYetReady`.
    setReadinessProbeStatusSource(ProbeStatuses.notYetReady("test"))

    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    val port: Int = 1111
    slicelet.start(selfPort = port, listenerOpt = None)
    val squid: Squid = waitForSquidWithPort(testEnv, port)

    // Check that the Assigner got a NOT_READY status from the Slicelet.
    awaitSliceletReported(testEnv, squid, SliceletState.NotReady)

    // Slicelet transitions to RUNNING from NOT_READY when readiness source reports readiness.
    setReadinessProbeStatusSource(ProbeStatuses.ok("test"))
    awaitSliceletReported(testEnv, squid, SliceletState.Running)

    // Slicelet transitions back if readiness status changes.
    setReadinessProbeStatusSource(ProbeStatuses.notYetReady("test"))
    awaitSliceletReported(testEnv, squid, SliceletState.NotReady)

    // Test that Slicelet reports TERMINATING after transitioning to the terminating state
    // regardless of what the readiness source reports.
    slicelet.setTerminatingState()

    // Slicelet should now report TERMINATING.
    awaitSliceletReported(testEnv, squid, SliceletState.Terminating)

    slicelet.stop()
  }

  test(
    "Assigner handles multiple Slicelets with different readiness states " +
    "(observeSliceletReadiness=false)"
  ) {
    // Test plan: Validate that distinct Slicelets are independently assigned by the Assigner.
    // Verify this by creating two Slicelets with independently-controllable readiness states. Set
    // one to NOT_READY and the other to RUNNING.  This test sets observeSliceletReadiness= false;
    // verify that the Assigner assigns slices to both Slicelets regardless of readiness state. Then
    // swap their states and verify that the previously-assigned Slicelet is still assigned and the
    // newly-RUNNING Slicelet is assigned.

    // Create slicelets: slicelet1 starts NOT_READY, slicelet2 starts RUNNING.
    val slicelet1: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    val port1: Int = 1111
    slicelet1.start(selfPort = port1, listenerOpt = None)
    val slicelet2: SliceletDriver = createSlicelet(testEnv)(useFakeReadinessProvider = true)
    slicelet2.setReadinessStatus(true)
    val port2: Int = 1112
    slicelet2.start(selfPort = port2, listenerOpt = None)

    // Get Squids for Slicelets that should be assigned.
    val squid1: Squid = waitForSquidWithPort(testEnv, port1)
    val squid2: Squid = waitForSquidWithPort(testEnv, port2)

    // Wait for Slicelets to report their states.
    awaitSliceletReported(testEnv, squid1, SliceletState.NotReady)
    awaitSliceletReported(testEnv, squid2, SliceletState.Running)

    assertAndWaitForSliceletsToBeAssigned(testEnv, squids = Set(squid1, squid2))

    // Now swap the readiness states: slicelet1 becomes RUNNING, slicelet2 becomes NOT_READY.
    slicelet1.setReadinessStatus(true)
    slicelet2.setReadinessStatus(false)

    // Wait for state changes to be reported.
    awaitSliceletReported(testEnv, squid1, SliceletState.Running)
    awaitSliceletReported(testEnv, squid2, SliceletState.NotReady)

    // When not observing readiness, both Slicelets should still be assigned.
    assertAndWaitForSliceletsToBeAssigned(testEnv, squids = Set(squid1, squid2))

    slicelet1.stop()
    slicelet2.stop()
  }

  test(
    "Assigner handles multiple Slicelets with different readiness states " +
    "(observeSliceletReadiness=true)"
  ) {
    // Test plan: Same as previous test, but with observeSliceletReadiness=true. Since the Assigner
    // respects the NOT_READY -> RUNNING transition, verify that only the RUNNING Slicelet is
    // assigned at first. Then, swap the readiness states and verify that both Slicelets are
    // assigned.

    val testEnvObserveSliceletReadiness = InternalDicerTestEnvironment.create(
      targetConfigMap = InternalTargetConfigMap.create(
        configScopeOpt = None,
        targetConfigMap = Map(
          TargetName(expectedAssignerCanonicalizedTargetIdentifier.name) ->
          InternalTargetConfig.forTest.DEFAULT
            .copy(
              healthWatcherConfig = HealthWatcherTargetConfig(
                observeSliceletReadiness = true,
                permitRunningToNotReady = false
              )
            )
        )
      ),
      assignerClusterUri = ASSIGNER_CLUSTER_URI
    )

    try {
      val slicelet1: SliceletDriver =
        createSlicelet(testEnvObserveSliceletReadiness)(useFakeReadinessProvider = true)
      val port1: Int = 1111
      slicelet1.start(selfPort = port1, listenerOpt = None)
      val slicelet2: SliceletDriver =
        createSlicelet(testEnvObserveSliceletReadiness)(useFakeReadinessProvider = true)
      slicelet2.setReadinessStatus(true)
      val port2: Int = 1112
      slicelet2.start(selfPort = port2, listenerOpt = None)

      // Get Squid for Slicelet that should be assigned.
      val squid2: Squid = waitForSquidWithPort(testEnvObserveSliceletReadiness, port2)

      awaitSliceletReported(testEnvObserveSliceletReadiness, squid2, SliceletState.Running)

      // Check assignment contains only slicelet2.
      assertAndWaitForSliceletsToBeAssigned(testEnvObserveSliceletReadiness, squids = Set(squid2))

      // Now swap the readiness states: slicelet1 becomes RUNNING, slicelet2 becomes NOT_READY.
      slicelet1.setReadinessStatus(true)
      slicelet2.setReadinessStatus(false)

      // Now we can get squid1 because the Slicelet is RUNNING.
      val squid1: Squid = AssertionWaiter("Wait for slicelet1 to be part of Assignment").await {
        getSquidWithPort(testEnvObserveSliceletReadiness, port1)
      }

      // Wait for state changes to be reported.
      awaitSliceletReported(testEnvObserveSliceletReadiness, squid1, SliceletState.Running)
      awaitSliceletReported(testEnvObserveSliceletReadiness, squid2, SliceletState.NotReady)

      // Even when observing readiness, both Slicelets should still be assigned, since the Assigner
      // only respects the NOT_READY -> RUNNING transition.
      assertAndWaitForSliceletsToBeAssigned(
        testEnvObserveSliceletReadiness,
        squids = Set(squid1, squid2)
      )

      slicelet1.stop()
      slicelet2.stop()
    } finally {
      testEnvObserveSliceletReadiness.stop()
    }
  }

  test(
    "Assigner handles multiple Slicelets with different readiness states " +
    "(observeSliceletReadiness=true, permitRunningToNotReady=true)"
  ) {
    // Test plan: Same as the observeSliceletReadiness=true test, but with
    // permitRunningToNotReady=true. Since the Assigner now respects both the NOT_READY -> RUNNING
    // and RUNNING -> NOT_READY transitions, verify that only the RUNNING Slicelet is assigned at
    // first. Then swap the readiness states and verify that only the newly-RUNNING Slicelet is
    // assigned: the formerly-RUNNING Slicelet is de-assigned upon transitioning to NOT_READY.
    val testEnvPermitRunningToNotReady = InternalDicerTestEnvironment.create(
      targetConfigMap = InternalTargetConfigMap.create(
        configScopeOpt = None,
        targetConfigMap = Map(
          TargetName(expectedAssignerCanonicalizedTargetIdentifier.name) ->
          InternalTargetConfig.forTest.DEFAULT
            .copy(
              healthWatcherConfig = HealthWatcherTargetConfig(
                observeSliceletReadiness = true,
                permitRunningToNotReady = true
              )
            )
        )
      ),
      assignerClusterUri = ASSIGNER_CLUSTER_URI
    )

    try {
      val slicelet1: SliceletDriver =
        createSlicelet(testEnvPermitRunningToNotReady)(useFakeReadinessProvider = true)
      val port1: Int = 1113
      slicelet1.start(selfPort = port1, listenerOpt = None)
      val slicelet2: SliceletDriver =
        createSlicelet(testEnvPermitRunningToNotReady)(useFakeReadinessProvider = true)
      slicelet2.setReadinessStatus(true)
      val port2: Int = 1114
      slicelet2.start(selfPort = port2, listenerOpt = None)

      // Get Squid for the RUNNING Slicelet.
      val squid2: Squid = waitForSquidWithPort(testEnvPermitRunningToNotReady, port2)

      // Check assignment contains only slicelet2.
      assertAndWaitForSliceletsToBeAssigned(testEnvPermitRunningToNotReady, squids = Set(squid2))

      // Now swap the readiness states: slicelet1 becomes RUNNING, slicelet2 becomes NOT_READY.
      slicelet1.setReadinessStatus(true)
      slicelet2.setReadinessStatus(false)

      // Now we can get squid1 because the Slicelet is RUNNING.
      val squid1: Squid = AssertionWaiter("Wait for slicelet1 to be part of Assignment").await {
        getSquidWithPort(testEnvPermitRunningToNotReady, port1)
      }

      // When permitRunningToNotReady=true, slicelet2 (now NOT_READY) should be de-assigned.
      // Only slicelet1 (now RUNNING) should remain assigned.
      assertAndWaitForSliceletsToBeAssigned(testEnvPermitRunningToNotReady, squids = Set(squid1))

      slicelet1.stop()
      slicelet2.stop()
    } finally {
      testEnvPermitRunningToNotReady.stop()
    }
  }

  test(
    "Assigner de-assigns and re-assigns a Slicelet that transitions" +
    " NOT_READY then back to RUNNING (observeSliceletReadiness=true, permitRunningToNotReady=true)"
  ) {
    // Test plan: Verify that with permitRunningToNotReady=true, the Assigner de-assigns a Slicelet
    // that transitions from RUNNING to NOT_READY and re-assigns it when it returns to RUNNING.
    // Schedule:
    // 1. Both slicelets start RUNNING — both assigned.
    // 2. Set slicelet1 NOT_READY — slicelet1 de-assigned; slicelet2 remains.
    // 3. Set slicelet1 RUNNING again — slicelet1 re-assigned.
    val testEnv = InternalDicerTestEnvironment.create(
      targetConfigMap = InternalTargetConfigMap.create(
        configScopeOpt = None,
        targetConfigMap = Map(
          TargetName(expectedAssignerCanonicalizedTargetIdentifier.name) ->
          InternalTargetConfig.forTest.DEFAULT
            .copy(
              healthWatcherConfig = HealthWatcherTargetConfig(
                observeSliceletReadiness = true,
                permitRunningToNotReady = true
              )
            )
        )
      ),
      assignerClusterUri = ASSIGNER_CLUSTER_URI
    )

    try {
      val slicelet1: SliceletDriver =
        createSlicelet(testEnv)(useFakeReadinessProvider = true)
      slicelet1.setReadinessStatus(true)
      val port1: Int = 1115
      slicelet1.start(selfPort = port1, listenerOpt = None)
      val slicelet2: SliceletDriver =
        createSlicelet(testEnv)(useFakeReadinessProvider = true)
      slicelet2.setReadinessStatus(true)
      val port2: Int = 1116
      slicelet2.start(selfPort = port2, listenerOpt = None)

      // Step 1: both slicelets are RUNNING — both should be assigned.
      val squid1: Squid = waitForSquidWithPort(testEnv, port1)
      val squid2: Squid = waitForSquidWithPort(testEnv, port2)
      assertAndWaitForSliceletsToBeAssigned(testEnv, squids = Set(squid1, squid2))

      // Step 2: set slicelet1 NOT_READY. With permitRunningToNotReady=true, slicelet1 should be
      // de-assigned. Only slicelet2 (still RUNNING) should remain assigned.
      slicelet1.setReadinessStatus(false)
      assertAndWaitForSliceletsToBeAssigned(testEnv, squids = Set(squid2))

      // Step 3: set slicelet1 RUNNING again. slicelet1 should be re-assigned.
      slicelet1.setReadinessStatus(true)
      assertAndWaitForSliceletsToBeAssigned(testEnv, squids = Set(squid1, squid2))

      slicelet1.stop()
      slicelet2.stop()
    } finally {
      testEnv.stop()
    }
  }
}

/**
 * Includes the common test cases from [[SliceletSuite]] plus test cases that apply to
 * the Scala implementation but not the Rust one.
 */
abstract class ScalaSliceletSuite extends SliceletSuiteBase {

  /**
   * The custom [[ProbeStatusSource]] used by the [[InfoService]] to get the pod's readiness.
   */
  private val readinessSource = new TestProbeStatusSource()

  /**
   * A [[ProbeStatusSource]] for testing that allows dynamically changing the readiness status. It
   * is queried by the [[InfoService]] to update the [[ReadinessProbeTracker]] underlying the
   * [[ReadinessReporter]].
   */
  private class TestProbeStatusSource extends ProbeStatusSource {
    private val status: AtomicReference[ProbeStatus] =
      new AtomicReference[ProbeStatus](ProbeStatuses.notYetReady("test"))

    override def getStatus: ProbeStatus = status.get()

    /** Set the status reported by this probe source to `isReady`. */
    def setReady(isReady: Boolean): Unit = {
      val newStatus: ProbeStatus =
        if (isReady) ProbeStatuses.ok("test") else ProbeStatuses.notYetReady("test")
      status.set(newStatus)
    }
  }

  /** Gets the assigner address for sending watch requests from the slicelet conf. */
  private def getAssignerAddress(sliceletConf: SliceletConf): URI = {
    WatchAddressHelper.getAssignerURI(sliceletConf.assignerHost, sliceletConf.dicerAssignerRpcPort)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Set up the InfoService to be used by the ReadinessReporter.
    InfoService.start(
      port = 0,
      startupStatusSourceOpt = None,
      livenessStatusSourceOpt = None,
      readinessStatusSourceOpt = Some(readinessSource),
      branchOpt = None
    )
  }

  override def afterAll(): Unit = {
    InfoService.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
  }

  override def afterEach(): Unit = {
    LocationConf.restoreSingletonForTest()
    super.afterEach()
  }

  override protected def createSlicelet(internalTestEnv: InternalDicerTestEnvironment)(
      sliceletHostname: Option[String],
      sliceletUuid: Option[String],
      sliceletKubernetesNamespace: Option[String],
      branch: Option[String],
      extraDbConfFlags: Map[String, Any],
      extraEnvVars: Map[String, String],
      useFakeReadinessProvider: Boolean): ScalaSliceletDriver = {
    // Configure the location information.
    val locationConf: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = extraEnvVars
    )
    LocationConf.testSingleton(locationConf)

    var rawConf = Map[String, Any](
      "databricks.dicer.slicelet.rpc.port" -> 0,
      "databricks.dicer.assigner.rpc.port" -> internalTestEnv.getAssignerPort,
      "databricks.dicer.assigner.host" -> "localhost",
      "databricks.dicer.slicelet.statetransfer.port" -> 0,
      "databricks.dicer.client.watchFromDataPlane" -> watchFromDataPlane,
      "databricks.dicer.internal.cachingteamonly.allowMultipleSliceletInstances" -> true
    )
    for (sliceletHostname: String <- sliceletHostname) {
      rawConf += "databricks.dicer.slicelet.hostname" -> sliceletHostname
    }
    for (sliceletUuid: String <- sliceletUuid) {
      rawConf += "databricks.dicer.slicelet.uuid" -> sliceletUuid
    }
    for (sliceletKubernetesNamespace: String <- sliceletKubernetesNamespace) {
      rawConf += "databricks.dicer.slicelet.kubernetesNamespace" -> sliceletKubernetesNamespace
    }
    for (branch: String <- branch) {
      rawConf += "databricks.branch.name" -> branch
    }

    val conf = new ProjectConf(Project.TestProject, Configs.parseMap(rawConf)) with SliceletConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
      override def dicerClientTlsOptions: Option[TLSOptions] =
        TestTLSOptions.clientTlsOptionsOpt
      override def dicerServerTlsOptions: Option[TLSOptions] =
        TestTLSOptions.serverTlsOptionsOpt
      override def envVars: Map[String, String] = super.envVars ++ extraEnvVars
    }

    if (useFakeReadinessProvider) {
      val fakeReadinessProvider = new FakeBlockingReadinessProvider()
      new ScalaSliceletDriver(
        Slicelet.forTestStatic.createFromImpl(
          SliceletImpl
            .createForExternalWithReadinessProvider(conf, defaultTarget, fakeReadinessProvider)
        ),
        fakeReadinessProviderOpt = Some(fakeReadinessProvider)
      )
    } else {
      new ScalaSliceletDriver(Slicelet(conf, defaultTarget), fakeReadinessProviderOpt = None)
    }
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }

  override protected def setReadinessProbeStatusSource(status: ProbeStatus): Unit = {
    readinessSource.setReady(status.code == ProbeStatuses.OK_STATUS)
    pokeReadinessSource()
  }

  /**
   * Sends an HTTP request to the [[InfoService]] to trigger a status update. Used with the
   * [[TestProbeStatusSource]] to update the readiness status of the pod.
   */
  private def pokeReadinessSource(): Unit = {
    val port = InfoService.getActivePort()
    assert(port != 0) // Ensure the server is started.

    // Make HTTP request to the readiness endpoint. We don't care about the response, since we check
    // that the readiness source is updated by way of verifying the Slicelet state later.
    val _ = SimpleWebClient.webClient().get(s"http://localhost:$port/ready").aggregate()
  }

  // This test case only makes sense for Scala, because the Rust Slicelet notifies the application
  // of assignment changes through a stream rather than a listener.
  test("Slicelet Listener Upcalls are Serial") {
    // Test plan: Create a Slicelet with a very slow (blocked by test) listener. Supply three
    // consecutive assignments and verify that the Slicelet observes the first assignment, and then
    // then the third assignment.

    // Create a listener with a lock we can use in the test to block upcalls.
    val listenerLock = new ReentrantLock()
    val listenerEvents = new mutable.ArrayBuffer[SliceSetImpl]

    // Create an initial proposal to freeze the assignment before starting the Slicelet (otherwise
    // starting the Slicelet first would trigger initial assignment generation, which would then
    // race with any subsequent assignment freeze).
    val initialProposal = createProposal(("" -- ∞) -> Seq("other_pod"))
    TestUtils.awaitResult(setAndFreezeAssignment(testEnv, initialProposal), Duration.Inf)

    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    val listener = new SliceletListener {
      override def onAssignmentUpdated(): Unit = {
        // Get the latest assignment from the Slicelet before acquiring the lock so that we can
        // figure out what assignment we had when the Slicelet called us, rather than what
        // assignment we had by the time the lock could be acquired.
        val assignedSlices: Seq[Slice] =
          try slicelet.assignedSlices
          catch {
            case _: IllegalStateException =>
              // It's possible that the Slicelet has already been stopped and we're receiving a
              // delayed upcall, so we couldn't call back into it.
              return
          }
        withLock(listenerLock) {
          listenerEvents.append(SliceSetImpl(assignedSlices))
        }
      }
    }

    // Start the slicelet with the blockable listener.
    slicelet.start(selfPort = 1234, Some(listener))
    val squid: Squid = slicelet.squid

    // Wait for the initial assignment to be received by the listener to prevent a race between the
    // first real assignment and the initial assignment.
    AssertionWaiter("Wait for initial empty assignment").await {
      withLock(listenerLock) {
        assert(listenerEvents == Seq[SliceSetImpl](SliceSetImpl()))
      }
    }

    // Block listener upcalls by holding its lock while three assignments are delivered. The
    // Slicelet's state should be updated after each assignment (verified by calling
    // `isAffinitized`), but only two assignments will be received by the listener.
    withLock(listenerLock) {
      // First assignment adds [Kili, Ori)
      val proposal1 = createProposal(
        ("" -- "Dori") -> Seq("Pod2"),
        ("Dori" -- "Kili") -> Seq("Pod0"),
        ("Kili" -- "Nori") -> Seq(squid),
        ("Nori" -- "Ori") -> Seq(squid),
        ("Ori" -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal1)
      AssertionWaiter("Wait for proposal1").await {
        assert(isAssigned(slicelet, "Kili"))
        // Ensure the Slicelet has already tried to execute the listener for the assignment
        // committed from proposal1 (but the execution is blocked by the lock).
        assert(listenerLock.hasQueuedThreads)
      }

      // Second assignment adds [Fili, Kili)
      val proposal2 = createProposal(
        ("" -- "Dori") -> Seq("Pod2"),
        ("Dori" -- "Fili") -> Seq("Pod0"),
        ("Fili" -- "Nori") -> Seq(squid),
        ("Nori" -- "Ori") -> Seq(squid),
        ("Ori" -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal2)
      AssertionWaiter("Wait for proposal2").await {
        assert(isAssigned(slicelet, "Fili"))
      }

      // Third assignments removes [Nori, Ori)
      val proposal3 = createProposal(
        ("" -- "Dori") -> Seq("Pod2"),
        ("Dori" -- "Fili") -> Seq("Pod0"),
        ("Fili" -- "Nori") -> Seq(squid),
        ("Nori" -- "Ori") -> Seq("Pod0"),
        ("Ori" -- ∞) -> Seq("Pod3")
      )
      setAndFreezeAssignment(testEnv, proposal3)
      AssertionWaiter("Wait for proposal3").await {
        assert(!isAssigned(slicelet, identityKey("Nori")))
      }
    } // Release listenerLock, unblocking the listener upcalls.
    // Await the two expected deltas.
    val expected = Seq[SliceSetImpl](
      SliceSetImpl(),
      SliceSetImpl("Kili" -- "Ori"),
      SliceSetImpl("Fili" -- "Nori")
    )
    AssertionWaiter("Wait for deltas").await {
      withLock(listenerLock) {
        assert(listenerEvents == expected)
      }
    }
    slicelet.stop()
  }

  // TODO(<internal bug>): Match the behavior for the Rust Slicelet and move this test to
  //  SliceletSuiteBase.
  test("No assignment for mismatched target") {
    // Test plan: verify that a Slicelet receiving assignment watch requests for a target which it
    // does not own does not distribute an assignment to the requester.
    val slicelet: ScalaSliceletDriver = createSlicelet(testEnv)()
    slicelet.start(selfPort = 1234, None)
    val squid: Squid = waitForSquidWithPort(testEnv, 1234)

    // Set up the assignment at the Assigner.
    val proposal = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq(squid),
      (fp("Kili") -- fp("Nori")) -> Seq(squid),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )

    val sliceletTarget: Target = targetFactory(None, getSafeName)
    TestUtils.awaitResult(
      testEnv.setAndFreezeAssignment(sliceletTarget, proposal),
      Duration.Inf
    )
    // Sanity check: the Slicelet should be able to receive the assignment.
    AssertionWaiter("slicelet receives assignment").await {
      assert(slicelet.hasReceivedAssignment)
    }

    val clerkTarget: Target = targetFactory(None, s"$getSafeName-other")
    val clerkConf: ClerkConf =
      TestClientUtils
        .createTestClerkConf(
          slicelet.slicelet.impl.forTest.sliceletPort,
          clientTlsFilePathsOpt = None
        )
    val clerk: Clerk[ResourceAddress] = TestClientUtils.createClerk(clerkTarget, clerkConf)

    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assert(!clerk.ready.isCompleted)

    slicelet.stop()
    clerk.forTest.stop()
  }

  // This test case currently does not work for Rust, because the Rust Slicelet driver uses a real
  // clock. Using a real clock in this test will increase the test runtime because the metrics are
  // updated every 5 seconds. Since we have test coverage for SliceKey load distribution histogram
  // population in slicelet_load_accumulator_test.rs, it should be okay to skip this test in Rust.
  test("Reports SliceKey load distribution to Prometheus") {
    // Test plan: Verify that the Slicelet maintains the SliceKey load distribution histogram
    // metric. Verify this by creating our own Slicelet (with a fake sec), starting it, reporting
    // load on SliceKeys, then advancing the clock by 5s (the interval for updating metrics), and
    // checking that the Prometheus metric is updated.

    // Create a fake sec and a fake pool with context propagation disabled to match the behavior of
    // the real Slicelet.
    val pool = SequentialExecutionContextPool.create(
      "FakePool",
      numThreads = 2,
      enableContextPropagation = false
    )
    val fakeSec =
      FakeSequentialExecutionContext.create(s"SliceletExecutor-$getSafeName", pool = pool)

    // Create a fake blocking readiness provider and set it to ready.
    val fakeBlockingReadinessProvider = new FakeBlockingReadinessProvider()
    fakeBlockingReadinessProvider.setReady(true)

    val externalConf: SliceletConf =
      TestClientUtils.createTestSliceletConf(
        testEnv.getAssignerPort,
        "some-host-name",
        clientTlsFilePathsOpt = None,
        serverTlsFilePathsOpt = None,
        watchFromDataPlane
      )

    // Note: Since this test requires SliceletImpl (and thus bypasses the logic in the Slicelet
    // constructor to populate cluster location information), we must pass in the expected Slicelet
    // Target for this test
    val slicelet = SliceletImpl.create(
      fakeSec,
      getAssignerAddress(externalConf),
      externalConf,
      expectedSliceletTargetIdentifier,
      fakeBlockingReadinessProvider
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)
    Using.Manager { use =>
      val doriHandle = use(slicelet.createHandle("Dori"))
      val kiliHandle = use(slicelet.createHandle("Kili"))
      val oriHandle = use(slicelet.createHandle("Ori"))

      // Report load for each handle.
      doriHandle.incrementLoadBy(10)
      kiliHandle.incrementLoadBy(20)
      oriHandle.incrementLoadBy(30)
    }.get

    // Setup: Advance the clock to just before the update metrics interval.
    fakeSec.getClock.advanceBy(4.seconds)

    // Verify: Metrics are not updated yet. Unfortunately since we're testing that something does
    // *not* happen, we must use a thread sleep here (limited to 100ms) to give the test enough of a
    // chance to reveal unexpected behavior.
    Thread.sleep(100)
    assert(
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_slicelet_slicekey_load_distribution_bucket_total",
        Map(
          "le" -> "+Inf",
          "targetCluster" -> expectedSliceletTargetIdentifier.getTargetClusterLabel,
          "targetName" -> expectedSliceletTargetIdentifier.getTargetNameLabel
        )
      ) == 0
    )

    // Setup: Advance the clock now 1 more second, so that the update metrics interval has elapsed.
    fakeSec.getClock.advanceBy(1.seconds)

    // Verify: The SliceKey load distribution metric gets updated.
    AssertionWaiter("Waiting for the SliceKey load distribution metric to be updated")
      .await {
        assert(
          MetricUtils.getMetricValue(
            CollectorRegistry.defaultRegistry,
            "dicer_slicelet_slicekey_load_distribution_bucket_total",
            Map(
              "le" -> "+Inf",
              "targetCluster" -> expectedSliceletTargetIdentifier.getTargetClusterLabel,
              "targetName" -> expectedSliceletTargetIdentifier.getTargetNameLabel
            )
          ) == 60
        )
      }
  }

  // This test case only makes sense for Scala, because the Rust Slicelet notifies the application
  // of assignment changes through a stream rather than a listener. This means that in Rust, the
  // application's processing of an assignment change is in a call stack that does not involve any
  // Dicer code.
  test("Slow listener doesn't block assignment distribution") {
    // Test plan: Verify that a listener which blocks or is otherwise slow does not block the
    // Slicelet itself (i.e. it can still receive assignments). Verify this by creating a Slicelet
    // whose listener blocks on upcalls, then distribute three assignments. After each assignment
    // distribution, verify that the Slicelet incorporates the assignment even while the listener
    // is blocked. Finally, verify that the listener code observes only the latest assignment in
    // each callback: in this test case, the listener observes only the first assignment (after
    // which it blocks) and the last assignment (which it observes after unblocking).
    val slicelet: SliceletDriver = createSlicelet(testEnv)()

    // Before starting the Slicelet, set an assignment excluding the Slicelet.
    val assignment1: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(
        testEnv,
        createProposal(("" -- ∞) -> Seq("other_pod"))
      ),
      Duration.Inf
    )

    // Set-up the listener that will block on its first upcall.
    val promise1 = Promise[Seq[Slice]]
    val block1 = Promise[Unit]
    val promise2 = Promise[Seq[Slice]]
    val listener = new SliceletListener {
      override def onAssignmentUpdated(): Unit = {
        logger.info(s"onAssignmentUpdated called")
        val assignedSlices: Seq[Slice] = slicelet.assignedSlices
        if (promise1.isCompleted) {
          promise2.trySuccess(assignedSlices)
        } else {
          promise1.success(assignedSlices)
          TestUtils.awaitResult(block1.future, Duration.Inf)
        }
      }
    }
    // Start the Slicelet and wait for it to receive the first assignment.
    slicelet.start(selfPort = 1234, Some(listener))
    val squid: Squid = slicelet.squid
    assert(
      TestUtils.awaitResult(promise1.future, Duration.Inf) == assignment1
        .getSliceSetForResource(squid)
        .slices
    )

    // While the listener is blocked, deliver a second and third assignment. The Slicelet should
    // learn about these assignments even though the listener is blocked.
    val assignment2: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(
        testEnv,
        createProposal(("" -- ∞) -> Seq(squid))
      ),
      Duration.Inf
    )
    AssertionWaiter("Wait for assignment2").await {
      assert(slicelet.latestAssignmentOpt.contains(assignment2))
    }
    val assignment3: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(
        testEnv,
        createProposal(
          ("" -- "m") -> Seq("other_pod"),
          ("m" -- ∞) -> Seq(squid)
        )
      ),
      Duration.Inf
    )
    AssertionWaiter("Wait for assignment3").await {
      assert(slicelet.latestAssignmentOpt.contains(assignment3))
    }

    // Now unblock the listener, and verify that the next assignment it learns about is assignment3.
    block1.success(())
    AssertionWaiter("Wait for promise2").await {
      assert(
        TestUtils.awaitResult(promise2.future, Duration.Inf) == assignment3
          .getSliceSetForResource(squid)
          .slices
      )
    }
  }

  // This test case only makes sense for Scala, for the same reason mentioned on the "Slow listener
  // doesn't block assignment distribution" test case above.
  test("Listener exceptions don't prevent future assignment notifications") {
    // Test plan: verify that exceptions thrown by a buggy listener are caught and do not prevent
    // notification of future assignments.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()

    // Before starting the Slicelet, bootstrap an assignment.
    val assignment1: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(
        testEnv,
        createProposal(("" -- ∞) -> Seq("other_pod"))
      ),
      Duration.Inf
    )
    // Start the Slicelet with a listener that throws when it receives the first notification.
    val promise1 = Promise[Seq[Slice]]
    val promise2 = Promise[Seq[Slice]]
    val listener = new SliceletListener {
      override def onAssignmentUpdated(): Unit = {
        logger.info(s"onAssignmentUpdated called")
        val assignedSlices: Seq[Slice] = slicelet.assignedSlices
        if (promise1.isCompleted) {
          promise2.success(assignedSlices)
        } else {
          promise1.success(assignedSlices)
          throw new RuntimeException("buggy listener")
        }
      }
    }
    slicelet.start(selfPort = 1234, Some(listener))

    // Await the first assignment notification.
    AssertionWaiter("Wait for assignment1").await {
      assert(slicelet.latestAssignmentOpt.contains(assignment1))
    }
    val squid: Squid = slicelet.squid
    assert(
      TestUtils.awaitResult(promise1.future, Duration.Inf) == assignment1
        .getSliceSetForResource(squid)
        .slices
    )

    // Supply a second assignment and verify that the listener observes it.
    val assignment2: Assignment = TestUtils.awaitResult(
      setAndFreezeAssignment(
        testEnv,
        createProposal(("" -- ∞) -> Seq(squid))
      ),
      Duration.Inf
    )
    AssertionWaiter("Wait for assignment2").await {
      assert(slicelet.latestAssignmentOpt.contains(assignment2))
    }
    assert(
      TestUtils.awaitResult(promise2.future, Duration.Inf) == assignment2
        .getSliceSetForResource(squid)
        .slices
    )
  }

  test("Usable before start") {
    // Test plan: Verify that a Slicelet can be used before it is started, and it will behave as if
    // the Slicelet is not assigned any slices. First we create a Slicelet, and check it doesn't own
    // anything based on `createHandle` and `assignedSlices`. We also verify that `resourceAddress`
    // throws. Then we start the Slicelet, assign the full Slice to it, and verify its ownership
    // with the same methods.
    val slicelet: SliceletDriver = createSlicelet(testEnv)()
    val testKeys: Seq[SliceKey] = Seq("", fp("Dori"))

    assert(slicelet.assignedSlices.isEmpty)
    for (key: SliceKey <- testKeys) {
      Using.resource(slicelet.createHandle(key)) { handle =>
        assert(!handle.isAssignedContinuously)
        handle.incrementLoadBy(1)
      }
    }
    // Load gets reported as unattributed.
    val attributedLoad: Double =
      TestClientUtils.getAttributedLoadMetric(expectedSliceletTargetIdentifier)
    assert(attributedLoad == 0)
    val unattributedLoad: Double =
      TestClientUtils.getUnattributedLoadMetric(expectedSliceletTargetIdentifier)
    assert(unattributedLoad == testKeys.size)

    assertThrow[IllegalStateException]("Slicelet must be started before accessing squid") {
      slicelet.resourceAddress
    }

    slicelet.start(selfPort = 1234, listenerOpt = None)

    // Assign all keys to the Slicelet, and then verify ownership.
    val proposal = createProposal(("" -- ∞) -> Seq(waitForSquidWithPort(testEnv, 1234)))
    setAndFreezeAssignment(testEnv, proposal)
    waitForAssignment(slicelet, "")

    assert(slicelet.assignedSlices == Seq("" -- ∞))
    for (key: SliceKey <- testKeys) {
      Using.resource(slicelet.createHandle(key)) { handle =>
        assert(handle.isAssignedContinuously)
        handle.incrementLoadBy(1)
      }
    }
    // Load gets reported as attributed.
    assert(
      TestClientUtils
        .getAttributedLoadMetric(expectedSliceletTargetIdentifier) == attributedLoad + testKeys.size
    )
    assert(
      TestClientUtils
        .getUnattributedLoadMetric(expectedSliceletTargetIdentifier) == unattributedLoad
    )

    // `resourceAddress` should no longer throw.
    assert(slicelet.resourceAddress.uri.getPort == 1234)

    slicelet.stop()
  }

  // TODO(<internal bug>): Move to the parent test suite once branch information is available in Rust.
  test("Slicelet records valid client version") {
    // Test plan: Verify that the client versions are reported for a Slicelet.

    // Setup: create a Slicelet with a conf that has a valid branch.
    createSlicelet(testEnv)(
      branch = Some(
        "test_client_version_customer_2024-09-13_17.05.12Z_test-branch-name_ffff9654_1957847387"
      )
    )

    val buildInfoMetricLabels: Vector[(String, String)] =
      targetAndSourceMetricLabels :+ ("language" -> "Scala")

    // Verify: the client version is reported correctly for a valid branch.
    assert(readPrometheusMetric("dicer_client_build_info", buildInfoMetricLabels) == 1)
    assert(
      readPrometheusMetric("dicer_client_commit_timestamp", targetAndSourceMetricLabels) ==
      1726247112000L
    )
  }

  // TODO(<internal bug>): Move to the parent test suite once branch information is available in Rust.
  test("Slicelet records invalid client version") {
    // Test plan: Verify that the client versions are reported for a Slicelet.

    // Setup: create a Slicelet with a conf that has an invalid branch.
    createSlicelet(testEnv)(
      branch = Some("test_client_version_invalid_customer_2024-09-13_ff^f9654_1957847387")
    )

    val buildInfoMetricLabels: Vector[(String, String)] =
      targetAndSourceMetricLabels :+ ("language" -> "Scala")

    // Verify: the client version is reported correctly for an invalid branch.
    assert(readPrometheusMetric("dicer_client_build_info", buildInfoMetricLabels) == 1)
    assert(readPrometheusMetric("dicer_client_commit_timestamp", targetAndSourceMetricLabels) == 0L)
  }

  // The metric that this test case verifies is only there temporarily to help us determine if it's
  // safe to start making Scala clients populate their k8s cluster URIs, so there's no point in
  // adding it to the Rust Slicelet.
  test("Records location info in location info metric") {
    // Test plan: Verify that the location information is recorded in the location info metric.
    // Verify this by creating a Slicelet (intentionally do not start it, to check that the metric
    // is recorded at construction time), and checking that the metric is set to 1 for the expected
    // labels values.
    createSlicelet(testEnv)()
    assertResult(1)(
      MetricUtils
        .getMetricValue(
          CollectorRegistry.defaultRegistry,
          metric = "dicer_slicelet_location_info",
          labels = Map(
            "targetCluster" -> expectedSliceletTargetIdentifier.getTargetClusterLabel,
            "targetName" -> expectedSliceletTargetIdentifier.getTargetNameLabel,
            "whereAmIClusterUri" -> expectedWhereAmIClusterUri
          )
        )
        .toInt
    )
  }
}

object SliceletSuite {

  /** The Assigner's cluster location. */
  val ASSIGNER_CLUSTER_URI: URI =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01")

  /**
   * Data plane cluster Slicelet cluster location (local Slicelets are in [[ASSIGNER_CLUSTER_URI]]).
   */
  val DATA_PLANE_SLICELET_CLUSTER_URI: URI =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
}

/** A Slicelet listener that logs all its calls for analysis later. */
private class LoggingListener(slicelet: SliceletDriver) extends SliceletListener {

  /** All the events received by onAssignmentUpdated. */
  private val events = new ArrayBuffer[SliceSetImpl]
  private val lock = new ReentrantLock()

  /**
   * Waits for an entry in the log that `assigned` set of Slices that are being newly assigned and
   * `unassigned` set of Slices that are being removed.
   */
  def waitForEntry(assigned: SliceSetImpl): Unit = {
    AssertionWaiter(s"Waiting for upcall ($assigned)").await {
      withLock(lock) {
        assert(events.contains(assigned))
      }
    }
  }

  override def onAssignmentUpdated(): Unit = {
    val assignedSlices: Seq[Slice] =
      try slicelet.assignedSlices
      catch {
        case _: IllegalStateException =>
          // It's possible that the Slicelet has already been stopped and we're receiving a
          // delayed upcall, so we couldn't call back into it.
          return
      }
    withLock(lock) {
      events.append(SliceSetImpl(assignedSlices))
    }
  }
}

private[external] trait SliceletSuiteWithKubernetesTargetFactory extends SliceletSuiteBase {
  override protected def targetFactory: (Option[URI], String) => Target =
    (clusterUriOpt: Option[URI], name: String) =>
      clusterUriOpt match {
        case Some(clusterUri: URI) => Target.createKubernetesTarget(clusterUri, name)
        case None => Target(name)
      }
}

private[external] trait SliceletSuiteWithAppTargetFactory extends SliceletSuiteBase {
  override protected def targetFactory: (Option[URI], String) => Target =
    (_, name: String) => Target.createAppTarget(transformToSafeAppTargetName(name), "instance-id")
}
