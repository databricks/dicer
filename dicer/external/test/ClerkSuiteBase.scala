package com.databricks.dicer.external

import java.net.URI

import scala.concurrent.duration.Duration

import com.databricks.caching.util.{AssertionWaiter, MetricUtils}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.{LocationConf, LocationConfTestUtils}
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentMetricsSource,
  ClientType,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment,
  TestAssigner
}
import com.databricks.dicer.friend.SliceMap
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry

import com.databricks.dicer.common.TargetHelper.TargetOps

/**
 * Enumeration for the source type of the Clerk (e.g., "Slicelet" for regular Clerks,
 * "Assigner" for direct Clerks).
 */
sealed trait WatchSourceType
object WatchSourceAssigner extends WatchSourceType {
  override def toString: String = "Assigner"
}
object WatchSourceSlicelet extends WatchSourceType {
  override def toString: String = "Slicelet"
}

/**
 * Abstract base class for Clerk tests. This class contains the test cases that apply to Rust
 * and Scala Clerks.
 */
abstract class ClerkSuiteBase extends DatabricksTest with TestName {

  /** The [[URI]] of dev-cloud1-region1. We configure Dicer assigners to run here in the test. */
  protected final val URI_DEV_AWS_US_WEST_2 =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

  /** The [[URI]] of dev-cloud1-region1-general1. */
  protected final val URI_DEV_AWS_US_WEST_2_GENERAL1 =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01")

  /** A location config map that includes [[URI_DEV_AWS_US_WEST_2_GENERAL1]]. */
  protected final val LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2_GENERAL1: Map[String, String] =
    Map(
      "LOCATION" -> DatabricksObjectMapper.toJson(
        Map(
          "cloud_provider" -> "AWS",
          "cloud_provider_region" -> "AWS_US_WEST_2",
          "environment" -> "DEV",
          "kubernetes_cluster_type" -> "GENERAL",
          "kubernetes_cluster_uri" -> URI_DEV_AWS_US_WEST_2_GENERAL1.toASCIIString,
          "region_uri" -> "region:dev/cloud1/public/region1",
          "regulatory_domain" -> "PUBLIC"
        )
      )
    )

  /** A location config map that includes [[URI_DEV_AWS_US_WEST_2]]. */
  protected final val LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2: Map[String, String] =
    Map(
      "LOCATION" -> DatabricksObjectMapper.toJson(
        Map(
          "cloud_provider" -> "AWS",
          "cloud_provider_region" -> "AWS_US_WEST_2",
          "environment" -> "DEV",
          "kubernetes_cluster_type" -> "GENERAL_CLASSIC",
          "kubernetes_cluster_uri" -> URI_DEV_AWS_US_WEST_2.toASCIIString,
          "region_uri" -> "region:dev/cloud1/public/region1",
          "regulatory_domain" -> "PUBLIC"
        )
      )
    )

  /** The test environment used for all the tests. */
  protected final val testEnv: InternalDicerTestEnvironment =
    InternalDicerTestEnvironment.create(assignerClusterUri = URI_DEV_AWS_US_WEST_2)

  /** The Assigner used for all the tests. */
  protected final val testAssigner: TestAssigner = testEnv.testAssigner

  /** A valid client branch name. */
  protected final val validClientBranch: String =
    "test_client_version_customer_2024-09-13_17.05.12Z_test-branch-name_ffff9654_1957847387"

  /** The language of the Clerk implementation for the metric label. */
  protected def clientLanguage: String

  /**
   * Creates a clerk for the given target, returning the clerk and the target that it is expected to
   * use.
   *
   * @param target The target to watch.
   * @param clerkLocationConfigMap The location config map for the Clerk's environment. If provided,
   *                               the Clerk will be created with this location configuration.
   * @param clientBranchOpt The optional branch name to use for client version metrics.
   */
  final protected def createClerk(
      target: Target,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): (ClerkDriver, Target) = {
    val clerk: ClerkDriver = createClerkInternal(target, clerkLocationConfigMap, clientBranchOpt)
    val locationConf: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = clerkLocationConfigMap.getOrElse(Map.empty)
    )
    val clusterUriOpt: Option[URI] = locationConf.location.kubernetesClusterUri.map(URI.create)
    val expectedTarget: Target = ClerkDriver.computeExpectedTargetIdentifier(target, clusterUriOpt)
    (clerk, expectedTarget)
  }

  /**
   * The core logic of creating a Clerk.
   *
   * See [[createClerk]] for full documentation of the parameters.
   */
  protected def createClerkInternal(
      target: Target,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): ClerkDriver

  /**
   * Creates a clerk using [[CrossClusterClerkAccessor]] for cross-cluster subscriptions.
   *
   * @param target The target to watch.
   * @param targetClusterUri The cluster URI of the target.
   * @param slicelet The Slicelet to connect to.
   * @param clerkLocationConfigMap The location config map for the Clerk's local cluster.
   * @param clientBranchOpt The optional branch name to use for client version metrics.
   * @return A [[ClerkDriver]] wrapping the created Clerk.
   */
  protected def createCrossClusterClerk(
      target: Target,
      targetClusterUri: URI,
      slicelet: Slicelet,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): ClerkDriver = {
    throw new UnsupportedOperationException(
      "createCrossClusterClerk is not supported by this ClerkSuiteBase implementation"
    )
  }

  /** Returns the watch source for the Clerk. */
  protected def getWatchSource: WatchSourceType

  /**
   * Reads and returns the value of the Prometheus metric with the given name and labels. Returns
   * zero if the metric has never been recorded with this set of label values.
   *
   * For histograms, append "_count" or "_sum" to the metric name as needed.
   *
   * @note labels a vector of (label name, label value) pairs. This is a vector because Rust
   *       test driver does a rudimentary string matching to get the metric values from the info
   *       service page. The order must match the Rust struct field declaration order.
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Note that LocationConfTestUtils.newTestLocationConfig() not only creates but also internally
    // sets the LocationConf. To avoid confusing behavior and ensure the isolation of
    // tests, clean up the LocationConf before each test case.
    LocationConf.restoreSingletonForTest()
  }

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /** Used to generate unique target names. */
  private var targetSequenceNumber: Int = 0

  /** Returns a unique target name each time the method is called. */
  protected def getUniqueTargetName: String = {
    targetSequenceNumber += 1
    "target" + targetSequenceNumber.toString
  }

  test("Clerk ready()") {
    // Test plan: Create a Clerk and test assigner. Verify that the Clerk's `ready` future
    // completes only after it receives an initial assignment. Also verify that the method
    // is idempotent. Also verify that getStubForKey returns None before ready and returns
    // the correct resource after ready.

    val target = Target(getUniqueTargetName)
    // Block assignment before creating the clerk to prevent the assigner generating an assignment;
    // racing with the assert below.
    testAssigner.blockAssignment(target)
    val (clerk, _): (ClerkDriver, _) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))

    // Verify that the Clerk is not ready. We add a short delay here, because the Rust Clerk driver
    // observes the readiness via RPC. If we immediately check the readiness after creating the
    // Clerk, there is a chance that the RPC is not sent yet, causing false positives.
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assert(!clerk.ready.isCompleted)

    // Verify that keys are not assigned before ready.
    assert(clerk.getStubForKey(fp("Fili")).isEmpty)

    testAssigner.unblockAssignment(target)
    // Now set an assignment in the test assigner that should propagate to the client.
    val proposal = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    testAssigner.setAndFreezeAssignment(target, proposal)

    // The Clerk's ready future should complete once it receives an assignment.
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    // Ensure that the clerk has some assignment after ready.
    assert(clerk.getStubForKey(fp("Fili")).isDefined)

    // Ensure it is eventually updated to the assignment defined by `proposal` above.
    AssertionWaiter("Await for latest assignment").await {
      val filiResource: Option[ResourceAddress] = clerk.getStubForKey(fp("Fili"))
      // Use `filiResource.contains` rather than `filiResource.get ==` for this assertion so that
      // the assertion prettifier gives a more useful error message when the resource is not
      // defined.
      assert(filiResource.contains(ResourceAddress(new URI("Pod1"))))
    }

    // Idempotency check: calling ready again and it should return a completed future.
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
  }

  test("getStubForKey") {
    // Test plan: Create a Clerk and assigner. Provide the assigner with one assignment and ensure
    // that the Clerk receives the assignment. Check some keys with getStubForKey calls.
    val target = Target(getUniqueTargetName)
    val (clerk, _): (ClerkDriver, _) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))

    val proposal = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )

    val assignment: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    val sliceKey: SliceKey = fp("Nori")
    // Use `lookupResource.contains` rather than `lookupResource.get ==` for this assertion so
    // that the assertion prettifier gives a more useful error message when the resource is not
    // defined.
    AssertionWaiter("await frozen assignment").await {
      val lookupResource: Option[ResourceAddress] = clerk.getStubForKey(sliceKey)
      val assignmentResource: ResourceAddress =
        assignment.sliceMap.entries(4).resources.head.resourceAddress
      assert(lookupResource.contains(assignmentResource))
    }

    // Now do a lookup synchronously since the assignment is now available.
    assert(
      clerk
        .getStubForKey(SliceKey.MIN)
        .contains(assignment.sliceMap.entries.head.resources.head.resourceAddress)
    )
  }

  test("Randomly and uniformly get resources from multiple Slice replicas") {
    // Test plan: Verify that when there are multiple resources assigned for a key, getStubForKey()
    // will randomly return any assigned resource in uniform distribution. Verify this by
    // supplying a test assignment with multiple replicas to the test assigner, then call
    // Clerk.getStubForKey() for a large number of times. Verify that for each key, all the
    // resources returned by getStubForKey() makes up exactly the set of assigned resources for that
    // key in the test assignment, and each resource appears in the result set with almost same
    // frequency.

    val target = Target(getUniqueTargetName)
    // Setup: Test assignment with multiple replicas for some Slices. Freeze the assignment before
    // slicelet and clerk are running to avoid flakiness.
    val proposal = createProposal(
      ("" -- fp("Fili")) -> Seq("Pod0", "Pod1"),
      (fp("Fili") -- fp("Nori")) -> Seq("Pod0", "Pod1", "Pod2", "Pod3", "Pod4"),
      (fp("Nori") -- ∞) -> Seq("Pod0")
    )
    TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)

    val (clerk, _): (ClerkDriver, _) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    // Setup: Hardcode the expected set of resources for some test key.
    val expectedResourcesByKeys: Map[SliceKey, Set[ResourceAddress]] = Map(
      fp("Dori") -> Set("Pod0", "Pod1"), // Within ["", Fili).
      fp("Fili") -> Set("Pod0", "Pod1", "Pod2", "Pod3", "Pod4"),
      fp("Kili") -> Set("Pod0", "Pod1", "Pod2", "Pod3", "Pod4"), // Within [Fili, Nori).
      fp("Ori") -> Set("Pod0") // Within [Nori, ∞).
    )

    for (sliceKey: SliceKey <- expectedResourcesByKeys.keys) {
      // Setup: Sample getStubForKey() for a large number of times, and record how many times each
      // resource is returned.
      val numTotalCalls: Int = 10000
      val hitCounts: Map[ResourceAddress, Int] = clerk.sampleStubForKey(sliceKey, numTotalCalls)

      // Verify: The result set of getStubForKey() is exactly the assigned resources.
      assert(hitCounts.keySet == expectedResourcesByKeys(sliceKey))

      // Sanity check.
      assert(hitCounts.values.sum == numTotalCalls)

      // Setup: Calculate the expectation and standard deviation of hit count per resource.
      val hitProbabilityPerResource: Double = 1.0 / expectedResourcesByKeys(sliceKey).size
      val hitCountExpectationPerResource: Double =
        numTotalCalls.toDouble * hitProbabilityPerResource
      val hitCountStandardDeviation: Double =
        Math.sqrt(numTotalCalls * hitProbabilityPerResource * (1 - hitProbabilityPerResource))

      // Verify: Each resource appears in returned results for similar frequency: deviating from
      // the expectation for no more than 5 times of standard deviation.
      // Note: Chi-square test may be more appropriate here because it focuses on the overall
      // distribution, but we look at each resource here for simplicity.
      for (hitCount: Int <- hitCounts.values) {
        assert(hitCount <= hitCountExpectationPerResource + 5 * hitCountStandardDeviation)
        assert(hitCount >= hitCountExpectationPerResource - 5 * hitCountStandardDeviation)
      }
    }
  }

  test("Clerk debug name") {
    // Test plan: Check that the Clerk's debug name contains the target name.
    val target = Target(getUniqueTargetName)
    val (clerk, _): (ClerkDriver, _) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))
    assert(clerk.getDebugName.contains(target.name))
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

  /**
   * Returns the number of times Clerk.getStubForKey was called for the given target.
   */
  private def getClerkGetStubForKeyCallCount(targetIdentifier: Target): Double = {
    readPrometheusMetric(
      "dicer_clerk_getstubforkey_call_count_total",
      Vector(
        "targetCluster" -> targetIdentifier.getTargetClusterLabel,
        "targetName" -> targetIdentifier.getTargetNameLabel,
        "targetInstanceId" -> targetIdentifier.getTargetInstanceIdLabel
      )
    )
  }

  test("Clerk records getStubForKey call count metric") {
    // Test plan: Verify that the Clerk increments the getStubForKey call count metric each time
    // getStubForKey is invoked, and verify that this works as expected independently of Clerk
    // readiness. Verify this by creating a Clerk, calling getStubForKey 10 times, and assert the
    // metric increased by 10. Then set an assignment, wait for the Clerk to become ready, call
    // getStubForKey 10 more times, and assert the metric increased by 20 total.
    //
    // Note that in test setups where creating a Clerk creates a Slicelet as well, the first part of
    // the test races with Clerk readiness, as the Slicelet to which the Clerk is connected will
    // induce assignment generation. However in some test setups the Clerk is a direct Clerk that
    // connects directly to the Assigner, in which case the first part of the test does not race
    // with clerk readiness, and we need to explicitly set and freeze an assignment in the second
    // half of the test in order for the Clerk to become ready. For the former case which races,
    // this is OK because erroneous metric behavior in the non-ready case will still surface as test
    // flakiness.
    val target = Target(getUniqueTargetName)
    val (clerk, expectedTarget): (ClerkDriver, Target) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))

    val callCountTracker: ChangeTracker[Double] =
      ChangeTracker(() => getClerkGetStubForKeyCallCount(expectedTarget))

    for (_ <- 0 until 10) {
      clerk.getStubForKey(fp("some-key"))
    }

    AssertionWaiter("Wait for getStubForKey call count metric to be updated").await {
      assert(
        callCountTracker.totalChange() == 10.0,
        "Expected getStubForKey call count to increase by 10, " +
        s"but totalChange was ${callCountTracker.totalChange()}"
      )
    }

    // Setup: In setups where the Clerk is a direct Clerk connecting directly to the assigner, we
    // need to explicitly set and freeze an assignment in order to get the Clerk to become ready,
    // and test that the metric works correctly for ready clerks.
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    for (_ <- 0 until 10) {
      clerk.getStubForKey(fp("some-key"))
    }

    AssertionWaiter("Wait for getStubForKey call count metric to be updated again").await {
      assert(
        callCountTracker.totalChange() == 20.0,
        "Expected getStubForKey call count to increase by 10, " +
        s"but totalChange was ${callCountTracker.totalChange()}"
      )
    }
  }

  test("Clerk tracks generation and incarnation") {
    // Test plan: Verify that the clerk tracks assignment metrics correctly. Verify this by creating
    // a clerk and an assigner, creating an initial assignment, verifying metrics are updated
    // correctly, creating a second, different assignment, waiting for the clerk to receive the
    // assignment, and verifying that the metrics are updated correctly.

    def getLatestGenerationNumber(
        source: AssignmentMetricsSource.AssignmentMetricsSource,
        target: Target): Double = {
      readPrometheusMetric(
        "dicer_assignment_latest_generation_number",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "source" -> source.toString
        )
      )
    }

    def getLatestIncarnationNumber(
        source: AssignmentMetricsSource.AssignmentMetricsSource,
        target: Target): Double = {
      readPrometheusMetric(
        "dicer_assignment_latest_store_incarnation",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "source" -> source.toString
        )
      )
    }

    def getNumNewGenerations(
        source: AssignmentMetricsSource.AssignmentMetricsSource,
        target: Target): Double = {
      readPrometheusMetric(
        "dicer_assignment_number_new_generations_total",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "source" -> source.toString
        )
      )
    }

    val target = Target(getUniqueTargetName)
    val (clerk, expectedTarget): (ClerkDriver, Target) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))
    val clerkSource: AssignmentMetricsSource.AssignmentMetricsSource = AssignmentMetricsSource.Clerk
    val proposal1: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )

    // Track changes in the number of new generations metric
    val newGenerationsTracker: ChangeTracker[Double] =
      ChangeTracker(() => getNumNewGenerations(clerkSource, expectedTarget))

    val assignment1: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal1), Duration.Inf)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    // Incarnation and generation metric should eventually be updated to the assignment just set
    AssertionWaiter("Awaiting metric update").await {
      assert(
        getLatestGenerationNumber(clerkSource, expectedTarget)
        == assignment1.generation.number.value
      )
      assert(
        getLatestIncarnationNumber(clerkSource, expectedTarget)
        == assignment1.generation.incarnation.value
      )
      // Don't check for an exact value, because multiple assignments can be delivered (e.g.,
      //creating a Slicelet in `createClerk` generates an assignment, and we also inject a
      // separate assignment).
      assert(getNumNewGenerations(clerkSource, expectedTarget) >= 1)
    }

    val proposal2: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- fp("Fili")) -> Seq("Pod2"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    val assignment2: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal2), Duration.Inf)
    AssertionWaiter("Awaiting new assignment").await {
      assert(
        getLatestGenerationNumber(clerkSource, expectedTarget)
        == assignment2.generation.number.value
      )
      assert(
        getLatestIncarnationNumber(clerkSource, expectedTarget)
        == assignment2.generation.incarnation.value
      )
      assert(newGenerationsTracker.totalChange() >= 1)
    }
  }

  test("Clerk records request size metrics when sending watch requests") {
    // Test plan: Verify that the Clerk records request size metrics when sending watch
    // requests to its assignment source (either Slicelet or Assigner directly).

    val target = Target(getUniqueTargetName)
    // Pre-compute the expected target identifier for metrics.
    val expectedTarget: Target =
      ClerkDriver.computeExpectedTargetIdentifier(target, Some(URI_DEV_AWS_US_WEST_2))

    // Set up an assignment before creating the Clerk to ensure it's the first assignment
    // received by the Clerk.
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- fp("Dori")) -> Seq("Pod2"),
      (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
      (fp("Fili") -- fp("Kili")) -> Seq("Pod1"),
      (fp("Kili") -- fp("Nori")) -> Seq("Pod0"),
      (fp("Nori") -- ∞) -> Seq("Pod3")
    )
    TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)

    // Trackers to verify Clerk records request size metrics.
    val countTracker = ChangeTracker[Double](
      () => getClientRequestSizeCount(expectedTarget, ClientType.Clerk)
    )
    val sumTracker = ChangeTracker[Double](
      () => getClientRequestSizeSum(expectedTarget, ClientType.Clerk)
    )

    val (clerk, _): (ClerkDriver, _) =
      createClerk(target, Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2))
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    val sourceDebugName: String = getWatchSource.toString

    // Verify that the Clerk recorded request size metrics when sending to the assignment source.
    assert(
      countTracker.totalChange() >= 1.0,
      s"Clerk should have recorded at least 1 request size when connecting to $sourceDebugName"
    )
    assert(
      sumTracker.totalChange() > 0.0,
      s"Clerk should have recorded positive request sizes when connecting to $sourceDebugName"
    )
  }

  gridTest("Clerk records valid client version")(
    Seq(Target(_: String), Target.createAppTarget(_: String, "instance-id"))
  ) { (targetFactory: String => Target) =>
    // Test plan: Verify that the client versions are reported for a Clerk with a valid branch.

    // Setup: create a Clerk with a conf that has a valid branch.
    val target: Target = targetFactory(getUniqueTargetName)
    val (clerk, expectedTarget): (ClerkDriver, Target) = createClerk(
      target,
      Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2),
      clientBranchOpt = Some(validClientBranch)
    )

    // Verify: the client version is reported correctly for a valid branch.
    AssertionWaiter("Dicer client build info is populated").await {
      // Wrapped in an AssertionWaiter to allow some time for Rust Clerk to emit metrics.
      assert(
        readPrometheusMetric(
          "dicer_client_build_info",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString,
            "commitTimestamp" -> "2024-09-13_17.05.12Z",
            "language" -> clientLanguage
          )
        ) == 1
      )
      assert(
        readPrometheusMetric(
          "dicer_client_commit_timestamp",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString
          )
        ) == 1726247112000L
      )
    }
  }

  /** An invalid client branch name (missing valid timestamp). */
  protected final val invalidClientBranch: String =
    "test_client_version_invalid_customer_2024-09-13_ff^f9654_1957847387"

  gridTest("Clerk records invalid client version")(
    Seq(Target(_: String), Target.createAppTarget(_: String, "instance-id"))
  ) { (targetFactory: String => Target) =>
    // Test plan: Verify that the client versions are reported for a Clerk with an invalid branch.

    // Setup: create a Clerk with a conf that has an invalid branch.
    val target: Target = targetFactory(getUniqueTargetName)
    val (clerk, expectedTarget): (ClerkDriver, Target) = createClerk(
      target,
      Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2),
      clientBranchOpt = Some(invalidClientBranch)
    )

    // Verify: the client version is reported correctly for an invalid branch.
    AssertionWaiter("Dicer client build info is populated").await {
      // Wrapped in an AssertionWaiter to allow some time for Rust Clerk to emit metrics.
      assert(
        readPrometheusMetric(
          "dicer_client_build_info",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString,
            "commitTimestamp" -> "unknown",
            "language" -> clientLanguage
          )
        ) == 1
      )
      assert(
        readPrometheusMetric(
          "dicer_client_commit_timestamp",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString
          )
        ) == 0L
      )
    }
  }

  test("Clerk picks up WhereAmI cluster URI if available") {
    // Test plan: Verify that Clerks created with cluster-unqualified target will pick up cluster
    // URI from environment variable. Verify this by simulating the case where the Clerk, Slicelet
    // and assigner are running in the same cluster with WhereAmI being
    // `LOCATION_CONFIG_DEV_AWS_US_WEST_2` (note that `testEnv` is created with `assignerClusterUri`
    // being `URI_DEV_AWS_US_WEST_2`), creating a clerk using a cluster-unqualified target, and
    // verifying the WhereAmI URI is picked up internally by the clerk and the metrics from the
    // clerk are correctly labeled with this URI.
    val clusterUnqualifiedTarget: Target = Target(getUniqueTargetName)
    // Setup: Set and freeze an arbitrary assignment so the clerk can observe it and emit metrics.
    testEnv.setAndFreezeAssignment(
      clusterUnqualifiedTarget,
      createProposal("" -- ∞ -> Seq("pod0"))
    )
    // Create clerk with location configuration to test that it picks up the cluster URI.
    val (clerk, expectedTarget): (ClerkDriver, Target) =
      createClerk(
        clusterUnqualifiedTarget,
        Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2),
        clientBranchOpt = Some(validClientBranch)
      )

    // Verify: Clerk can receive (the frozen) assignment.
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    assert(clerk.getStubForKey(SliceKey.MIN).get.toString == "pod0")

    AssertionWaiter("Verify: Metrics are emitted with targetCluster correctly labeled.").await {
      assert(
        readPrometheusMetric(
          "dicer_assignment_number_new_generations_total",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> "Clerk"
          )
        ).toLong == 1
      )
      assert(
        readPrometheusMetric(
          "dicer_client_build_info",
          Vector(
            "targetCluster" -> expectedTarget.getTargetClusterLabel,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString,
            "commitTimestamp" -> "2024-09-13_17.05.12Z",
            "language" -> clientLanguage
          )
        ) == 1
      )
    }
    clerk.stop()
  }

  test("CrossClusterClerk ignores the cluster URI from the environment variable") {
    // Test plan: Verify that Clerks created with cluster-qualified target will ignore the cluster
    // URI from environment variable. Verify this by simulating a case where a Cross-Cluster-Clerk
    // is in the general cluster (URI_DEV_AWS_US_WEST_2_GENERAL1) but subscribing to a target in an
    // HGCP cluster (URI_DEV_AWS_US_WEST_2), and the clerk is created with a Target qualified by
    // `URI_DEV_AWS_US_WEST_2`. Verify the clerk emits metrics labeled with `URI_DEV_AWS_US_WEST_2`
    // rather than `URI_DEV_AWS_US_WEST_2_GENERAL1`. Note that this test is only applicable to
    // Clerks that subscribe to assignments from a Slicelet, so it's skipped for direct Clerks.

    // Setup: A cluster-unqualified Target passed to slicelet factory method. Use unqualified
    // Target to create slicelet just to align with production.
    val clusterUnqualifiedTarget: Target = Target(getUniqueTargetName)

    // Setup: A Slicelet in the general cluster.
    val slicelet: Slicelet = withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConfig(
        envMap = LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2_GENERAL1
      )
    ) {
      testEnv.createSlicelet(clusterUnqualifiedTarget).start(selfPort = 1234, listenerOpt = None)
    }

    // Setup: A Clerk in general cluster using CrossClusterClerkAccessor.
    // Skip the test if CrossClusterClerkAccessor is not supported (e.g., direct clerks).
    val clerkOpt: Option[ClerkDriver] = try {
      Some(
        createCrossClusterClerk(
          clusterUnqualifiedTarget,
          URI_DEV_AWS_US_WEST_2,
          slicelet,
          clerkLocationConfigMap = Some(LOCATION_CONFIG_MAP_DEV_AWS_US_WEST_2_GENERAL1),
          clientBranchOpt = Some(validClientBranch)
        )
      )
    } catch {
      case _: UnsupportedOperationException =>
        // Skip the test if CrossClusterClerkAccessor is not supported.
        None
    }

    clerkOpt.map { clerk: ClerkDriver =>
      // For cross-cluster clerks, the target is expected to be fully qualified.
      val expectedTarget: Target =
        Target.createKubernetesTarget(URI_DEV_AWS_US_WEST_2, clusterUnqualifiedTarget.name)

      // Verify: Clerk can receive assignments.
      TestUtils.awaitResult(clerk.ready, Duration.Inf)
      assert(
        ClerkDriver.resourceAddressEquals(
          clerk.getStubForKey(SliceKey.MIN).get,
          slicelet.forTest.resourceAddress
        )
      )

      AssertionWaiter("Verify: Metrics are emitted with targetCluster correctly labeled.").await {
        assert(
          readPrometheusMetric(
            "dicer_assignment_number_new_generations_total",
            Vector(
              "targetCluster" -> expectedTarget.getTargetClusterLabel,
              "targetName" -> expectedTarget.getTargetNameLabel,
              "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
              "source" -> "Clerk"
            )
          ).toLong == 1
        )
        assert(
          readPrometheusMetric(
            "dicer_client_build_info",
            Vector(
              "targetCluster" -> expectedTarget.getTargetClusterLabel,
              "targetName" -> expectedTarget.getTargetNameLabel,
              "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
              "source" -> AssignmentMetricsSource.Clerk.toString,
              "commitTimestamp" -> "2024-09-13_17.05.12Z",
              "language" -> clientLanguage
            )
          ) == 1
        )
      }

      // Verify: There are no metrics labeled with the cluster URI in the WhereAmI env var.
      assert(
        readPrometheusMetric(
          "dicer_assignment_number_new_generations_total",
          Vector(
            "targetCluster" -> URI_DEV_AWS_US_WEST_2_GENERAL1.toString,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> "Clerk"
          )
        ).toLong == 0
      )
      assert(
        readPrometheusMetric(
          "dicer_client_build_info",
          Vector(
            "targetCluster" -> URI_DEV_AWS_US_WEST_2_GENERAL1.toString,
            "targetName" -> expectedTarget.getTargetNameLabel,
            "targetInstanceId" -> expectedTarget.getTargetInstanceIdLabel,
            "source" -> AssignmentMetricsSource.Clerk.toString,
            "commitTimestamp" -> "2024-09-13_17.05.12Z",
            "language" -> clientLanguage
          )
        ) == 0
      )
    }
    slicelet.forTest.stop()
    clerkOpt.map((_: ClerkDriver).stop())
  }

  test("Clerk can be created when WhereAmI isn't available") {
    // Test plan: Verify that Clerks created with cluster-unqualified target can proceed normally
    // even if the WhereAmI environment variable is not defined. Verify this by simulating the
    // case where the Clerk, Slicelet and assigner are running in the same cluster where WhereAmI
    // env var is not available, creating a clerk using a cluster-unqualified target, and verifying
    // the WhereAmI URI is not picked up by the clerk and the metrics from the clerk are not
    // labeled with any cluster URI, but the clerk is still working normally.
    val clusterUnqualifiedTarget: Target = Target(getUniqueTargetName)
    // Setup: Set and freeze an arbitrary assignment so the clerk can observe it and emit metrics.
    testEnv.setAndFreezeAssignment(
      clusterUnqualifiedTarget,
      createProposal("" -- ∞ -> Seq("pod1"))
    )
    // Setup and verify: Clerk can be successfully created even if WhereAmI env var is not
    // available. Pass None to indicate no location configuration.
    val (clerk, _): (ClerkDriver, _) =
      createClerk(clusterUnqualifiedTarget, clerkLocationConfigMap = None)

    // Verify: Clerk can receive (the frozen) assignment.
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    assert(clerk.getStubForKey(SliceKey.MIN).get.toString == "pod1")

    AssertionWaiter("Verify: Metrics are emitted with no targetCluster labeled.").await {
      assert(
        readPrometheusMetric(
          "dicer_assignment_number_new_generations_total",
          Vector(
            "targetCluster" -> "",
            "targetName" -> clusterUnqualifiedTarget.name,
            "targetInstanceId" -> clusterUnqualifiedTarget.getTargetInstanceIdLabel,
            "source" -> "Clerk"
          )
        ).toLong == 1
      )
      assert(
        readPrometheusMetric(
          "dicer_client_build_info",
          Vector(
            "targetCluster" -> "",
            "targetName" -> clusterUnqualifiedTarget.name,
            "targetInstanceId" -> clusterUnqualifiedTarget.getTargetInstanceIdLabel,
            "source" -> "Clerk"
          )
        ).toLong == 1
      )
    }
    clerk.stop()
  }
}

/**
 * Abstract base class for Scala Clerk tests. This class contains test cases that are specific to
 * the Scala implementation or those that do not yet work for the Rust implementation.
 */
abstract class ScalaClerkSuiteBase extends ClerkSuiteBase {

  override protected def clientLanguage: String = "Scala"

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }
}
