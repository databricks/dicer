package com.databricks.dicer.common

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import com.databricks.api.proto.dicer.common.{
  ClientResponseP,
  DiffAssignmentP,
  SyncAssignmentStateP
}
import com.google.protobuf.ByteString
import com.databricks.caching.util.{AssertionWaiter, MetricUtils, StatusUtils, TestUtils}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.common.SubscriberHandler.{Location, MetricsKey}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import io.grpc.Status
import java.net.URI

import com.databricks.dicer.common.Version.LATEST_VERSION

/**
 * Abstract base class for SubscriberHandler tests that contains test logic that should work
 * across implementations.
 *
 * Concrete implementations (ScalaSubscriberHandlerSuite, RustSubscriberHandlerSuite) must
 * implement the abstract methods to provide language-specific behavior.
 */
abstract class SubscriberHandlerSuiteBase extends DatabricksTest with TestName {

  /** Timeout to request for watch requests. */
  protected val TIMEOUT: FiniteDuration = 1.minute

  /**
   * Poll interval to use when asserting metrics values. Longer than default to avoid frequent
   * polling metrics from Rust subprocess.
   */
  private val METRICS_ASSERTION_POLL_INTERVAL: FiniteDuration = 50.milliseconds

  /** Target to use for the current test. */
  protected def target: Target =
    Target.createKubernetesTarget(
      new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01"),
      getSafeName
    )

  /**
   * Creates a SubscriberHandlerDriver for testing. The driver manages the handler lifecycle
   * and provides methods to interact with it.
   *
   * @param handlerLocation The location of the handler.
   * @param handlerTarget The target for which the hanlder is serving.
   * @return A driver that can be used to interact with the handler.
   */
  protected def createDriver(
      handlerLocation: Location,
      handlerTarget: Target): SubscriberHandlerDriver

  /**
   * Reads a Prometheus metric from the test infrastructure.
   *
   * For histograms, append "_count" or "_sum" to the metric name as needed.
   *
   * @param metricName The name of the metric to read.
   * @param labels The labels to filter the metric by.
   * @return The value of the metric (0.0 if not found).
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  /** Creates [[SliceletData]] for a Slicelet with the given address. */
  protected def createSliceletData(uri: String): SliceletData = {
    SliceletData(
      createTestSquid(uri),
      SliceletState.Running,
      "localhostNamespace",
      attributedLoads = Vector.empty,
      unattributedLoadOpt = None
    )
  }

  /** Creates a simulated watch request from a subscriber with the given data. */
  protected final def createClientRequest(
      knownGeneration: Generation,
      data: SubscriberData,
      debugName: String,
      requestTarget: Target = target,
      version: Long = Version.LATEST_VERSION): ClientRequest = {
    ClientRequest(
      requestTarget,
      SyncAssignmentState.KnownGeneration(knownGeneration),
      debugName,
      TIMEOUT,
      data,
      supportsSerializedAssignment = true,
      version = version
    )
  }

  /**
   * Creates a random assignment to `uris` with `generation`. URIs map to Squids according to
   * [[createTestSquid()]].
   */
  protected final def createRandomAssignment(
      generation: Generation,
      uris: Seq[String]): Assignment = {
    ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        uris.map(uri => createTestSquid(uri)).toVector,
        numMaxReplicas = 1,
        new scala.util.Random
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      generation
    )
  }

  /** Gets the number of subscribers of the given type for the target and handler location. */
  private def getNumSubscribers(
      handlerTarget: Target,
      clientType: String,
      handlerLocation: Location,
      version: Long): Long = {
    readPrometheusMetric(
      "dicer_subscriber_num_subscribers",
      Vector(
        "targetCluster" -> handlerTarget.getTargetClusterLabel,
        "targetName" -> handlerTarget.name,
        "targetInstanceId" -> handlerTarget.getTargetInstanceIdLabel,
        "type" -> clientType,
        "version" -> version.toString,
        "handlerLocation" -> handlerLocation.toString
      )
    ).toLong
  }

  /** Gets the number of Clerks for the target, handler location, and version. */
  private def getNumClerks(
      handlerTarget: Target,
      handlerLocation: Location,
      version: Long = Version.LATEST_VERSION): Long =
    getNumSubscribers(handlerTarget, "Clerk", handlerLocation, version)

  /** Gets the number of Slicelets for the target, handler location, and version. */
  private def getNumSlicelets(
      handlerTarget: Target,
      handlerLocation: Location,
      version: Long = Version.LATEST_VERSION): Long =
    getNumSubscribers(handlerTarget, "Slicelet", handlerLocation, version)

  /** Helper to get the sum of serialized assignment sizes for the given target and location. */
  private def getSerializedAssignmentSizeSum(target: Target, location: Location): Double = {
    readPrometheusMetric(
      "dicer_serialized_assignment_size_bytes_histogram_sum",
      Vector(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "handlerLocation" -> location.toString
      )
    )
  }

  /** Helper to get the sum of client response sizes for the given target and location. */
  private def getClientResponseSizeSum(target: Target, location: Location): Double = {
    readPrometheusMetric(
      "dicer_client_response_size_bytes_histogram_sum",
      Vector(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "handlerLocation" -> location.toString
      )
    )
  }

  /** Gets the number of watch requests for the given handler and request targets. */
  private def getNumWatchRequests(
      handlerTarget: Target,
      requestTarget: Target,
      metricsKey: MetricsKey,
      handlerLocation: Location): Double = {
    readPrometheusMetric(
      "dicer_watch_requests_total",
      Vector(
        "handlerTargetCluster" -> handlerTarget.getTargetClusterLabel,
        "handlerTargetName" -> handlerTarget.name,
        "handlerTargetInstanceId" -> handlerTarget.getTargetInstanceIdLabel,
        "requestTargetCluster" -> requestTarget.getTargetClusterLabel,
        "requestTargetName" -> requestTarget.name,
        "requestTargetInstanceId" -> requestTarget.getTargetInstanceIdLabel,
        "type" -> metricsKey.typeLabel,
        "version" -> metricsKey.versionLabel,
        "handlerLocation" -> handlerLocation.toString
      )
    )
  }

  /** Gets the number of watch requests that were load shed. */
  private def getNumWatchRequestsLoadShed(target: Target): Double = {
    readPrometheusMetric(
      "dicer_watch_requests_load_shed_total",
      Vector(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /**
   * Tests two subscribers with overlapping lifetimes watching assignment via a
   * [[SubscriberHandler]]. Validates that metrics tracking recent subscribers are updated as
   * expected throughout, and that subscribers are informed when fresh assignments are received by
   * the handler:
   *
   *  - Attaches the first subscriber, which has knowledge of no prior assignment.
   *  - Attaches the second subscriber, which has knowledge of the assignment at generation 42.
   *  - Inform the handler of the assignment at generation 42, which should be conveyed to the
   *    first subscriber, but not the second (since it already has that assignment).
   *  - Advance time such that the second subscriber's request times out.
   *  - Have the second subscriber repeatedly poll for a long enough time that the first subscriber
   *    is no longer "recently seen" from the perspective of metrics.
   *  - Stop the handler and verify that recently-seen metrics drop to zero.
   *
   * @param handlerLocation The location of the handler to test.
   * @param data1 Data for the first subscriber.
   * @param data2 Data for the second subscriber.
   */
  private def runSubscriberLifetimeTest(
      handlerLocation: Location,
      data1: SubscriberData,
      data2: SubscriberData): Unit = {
    val testTarget: Target = target
    val driver: SubscriberHandlerDriver = createDriver(handlerLocation, testTarget)
    val generation: Generation = Generation(Incarnation(4), 42)

    // Track the number of handleWatch calls made for the target, used for draining to make sure
    // time advancement happens after all handleWatch calls have registered their timeout sleeps.
    var handleWatchCount: Int = 0

    // Attach a first subscriber that has no knowledge of any assignments.
    val fut1: Future[ClientResponseP] = driver.handleWatch(
      createClientRequest(Generation.EMPTY, data1, "subscriber1", testTarget),
      redirectOpt = None
    )
    handleWatchCount += 1

    // Verify that metrics are updated after the `handleWatch` call above completes.
    var expectedNumClerks: Long = 0
    var expectedNumSlicelets: Long = 0
    data1 match {
      case ClerkData => expectedNumClerks += 1
      case _: SliceletData => expectedNumSlicelets += 1
    }
    // Wrap the metrics assertion in an AssertionWaiter, because Rust handlers run in a sub-process
    // and metrics update asynchronously.
    AssertionWaiter(
      "Metrics updated after first subscriber",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation) == expectedNumClerks)
      assert(getNumSlicelets(testTarget, handlerLocation) == expectedNumSlicelets)
    }

    // Attach a second subscriber that already knows about the assignment we're about to inject.
    val fut2: Future[ClientResponseP] = driver.handleWatch(
      createClientRequest(generation, data2, "subscriber2", testTarget),
      redirectOpt = None
    )
    handleWatchCount += 1

    data2 match {
      case ClerkData => expectedNumClerks += 1
      case _: SliceletData => expectedNumSlicelets += 1
    }
    AssertionWaiter(
      "Metrics updated after second subscriber",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation) == expectedNumClerks)
      assert(getNumSlicelets(testTarget, handlerLocation) == expectedNumSlicelets)
    }

    // Supply an assignment that satisfies the first subscriber but not the second, since it is
    // waiting for a more recent assignment.
    val assignment: Assignment =
      createRandomAssignment(generation, Vector("pod0", "pod1"))
    driver.setAssignment(assignment)
    val response1: ClientResponse =
      ClientResponse.fromProto(TestUtils.awaitResult(fut1, Duration.Inf))
    assert(
      response1.syncState == SyncAssignmentState.KnownAssignment(
        assignment.toDiff(Generation.EMPTY)
      )
    )
    // Sleep a short period here, since Rust code is running in a sub-process and the response is
    // sent asynchronously.
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assert(!fut2.isCompleted)

    // After the timeout is up, the second subscriber should receive a response indicating that the
    // known assignment has not advanced. Drain to ensure fut2's timeout is registered before
    // advancing time.
    driver.waitForSubscriberHandler(testTarget, handleWatchCount)
    driver.advanceTime(TIMEOUT)
    val response2: ClientResponse =
      ClientResponse.fromProto(TestUtils.awaitResult(fut2, Duration.Inf))
    assert(response2.syncState == SyncAssignmentState.KnownGeneration(generation))

    // Keep the second Subscriber's metrics alive by repeatedly issuing watch requests, but allow
    // the first subscriber to lapse. We need to wait out EXPIRE_AFTER * 2 before the metrics are
    // guaranteed to be updated.
    for (_ <- 0 until 4) {
      val pollFut: Future[ClientResponseP] = driver.handleWatch(
        createClientRequest(generation, data2, "subscriber2", testTarget),
        redirectOpt = None
      )
      handleWatchCount += 1
      driver.waitForSubscriberHandler(testTarget, handleWatchCount)
      driver.advanceTime(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      TestUtils.awaitResult(pollFut, Duration.Inf)
    }
    data1 match {
      case ClerkData => expectedNumClerks -= 1
      case _: SliceletData => expectedNumSlicelets -= 1
    }
    AssertionWaiter(
      "First subscriber metrics expired",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation) == expectedNumClerks)
      assert(getNumSlicelets(testTarget, handlerLocation) == expectedNumSlicelets)
    }

    // Now cancel the handler and verify that the number of tracked subscribers drops to 0.
    driver.cancel()
    AssertionWaiter(
      "Metrics should be 0 after cancel",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation) == 0)
      assert(getNumSlicelets(testTarget, handlerLocation) == 0)
    }
  }

  test("Clerk to Slicelet lifetime") {
    // Test plan: Validates watch lifetimes from two Clerks to Slicelets.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Slicelet, ClerkData, ClerkData)
  }

  test("Clerk to Assigner lifetime") {
    // Test plan: Validates watch lifetimes from two Clerks to Assigner.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Assigner, ClerkData, ClerkData)
  }

  test("Slicelet to Assigner lifetime") {
    // Test plan: Validates watch lifetimes for two Slicelets to Assigner.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(
      Location.Assigner,
      createSliceletData("pod0"),
      createSliceletData("pod1")
    )
  }

  test("Heterogeneous subscriber to Assigner lifetime") {
    // Test plan: Validates watch lifetimes for a Slicelet and a Clerk to an Assigner.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Assigner, createSliceletData("pod0"), ClerkData)
  }

  test("Heterogeneous subscriber to Slicelet lifetime") {
    // Test plan: Validates watch lifetimes for a Slicelet and a Clerk to a Slicelet.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Slicelet, createSliceletData("pod0"), ClerkData)
  }

  test("Subscriber version metrics") {
    // Test plan: Verify that the subscriber version metrics are updated as expected. Send a clerk
    // watch with the default version, and verify that it is recorded in the metrics. Then change
    // the clerk to report version 0, and verify that the default version metric resets to 0 while
    // the version 0 metric is incremented.
    val testTarget: Target = target
    val handlerLocation: Location = Location.Slicelet
    val driver: SubscriberHandlerDriver = createDriver(handlerLocation, testTarget)

    // Track the number of handleWatch calls for drain synchronization.
    var handleWatchCount: Int = 0

    // Keep the metrics alive by repeatedly issuing watch requests. We need to wait out
    // EXPIRE_AFTER * 2 before the metrics are guaranteed to be updated.
    val request: ClientRequest =
      createClientRequest(Generation.EMPTY, ClerkData, "subscriber1", testTarget)
    for (_ <- 0 until 4) {
      val fut: Future[ClientResponseP] =
        driver.handleWatch(request, redirectOpt = None)
      handleWatchCount += 1
      driver.waitForSubscriberHandler(testTarget, handleWatchCount)
      driver.advanceTime(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      TestUtils.awaitResult(fut, Duration.Inf)
    }
    AssertionWaiter(
      "Version metrics updated after LATEST_VERSION watches",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation, Version.LATEST_VERSION) == 1)
    }

    // Update the request to report its version as 0. Ensure `updateMetrics` runs, and verify that
    // the metrics are updated as expected.
    val newRequest: ClientRequest =
      createClientRequest(Generation.EMPTY, ClerkData, "subscriber1", testTarget, version = 0)
    for (_ <- 0 until 4) {
      val fut: Future[ClientResponseP] =
        driver.handleWatch(newRequest, redirectOpt = None)
      handleWatchCount += 1
      driver.waitForSubscriberHandler(testTarget, handleWatchCount)
      driver.advanceTime(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      TestUtils.awaitResult(fut, Duration.Inf)
    }
    AssertionWaiter(
      "Version metrics updated after version 0 watches",
      pollInterval = METRICS_ASSERTION_POLL_INTERVAL
    ).await {
      assert(getNumClerks(testTarget, handlerLocation, Version.LATEST_VERSION) == 0)
      assert(getNumClerks(testTarget, handlerLocation, version = 0) == 1)
    }
  }

  test("Empty Redirect") {
    // Test plan: Verify that the server responds to watch requests with empty redirect if the
    // `handleWatch` is called with an empty Redirect, regardless of what request was sent.
    val driver = createDriver(Location.Assigner, target)

    // Set an assignment so there is something to watch.
    val assignment1: Assignment = createRandomAssignment(
      generation = Generation(Incarnation(1), 42),
      Vector("pod0", "pod1")
    )
    driver.setAssignment(assignment1)

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val fut: Future[ClientResponseP] = driver.handleWatch(request, redirectOpt = None)

    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    assert(response.redirect.addressOpt.isEmpty)
  }

  // Verify that metrics are recorded for both fatal and non-fatal mismatches by parameterizing the
  // test over targets with both types of mismatches.
  private case class TargetMismatchCase(target1: Target, target2: Target)
  private val targetMismatchCases: Seq[TargetMismatchCase] = {
    val uri1: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val uri2: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
    Seq(
      // Fatal: same cluster, different names.
      TargetMismatchCase(
        Target.createKubernetesTarget(uri1, "a"),
        Target.createKubernetesTarget(uri1, "b")
      ),
      // Non-fatal: different clusters, same name.
      TargetMismatchCase(
        Target.createKubernetesTarget(uri1, "a"),
        Target.createKubernetesTarget(uri2, "a")
      ),
      // Fatal: same app name, different instance IDs.
      TargetMismatchCase(
        Target.createAppTarget("app-target", "instance-1"),
        Target.createAppTarget("app-target", "instance-2")
      ),
      // Fatal: KubernetesTarget handler vs AppTarget request (different target types).
      TargetMismatchCase(
        Target.createKubernetesTarget(uri1, "a"),
        Target.createAppTarget("app-target", "instance-1")
      )
    )
  }

  gridTest("Target mismatch metrics")(targetMismatchCases) { testCase: TargetMismatchCase =>
    // Test plan: Verify that requests for a target which does not match that of the handler are
    // recorded in the metrics.
    val driver = createDriver(Location.Slicelet, testCase.target1)

    val handlerLocation: Location = Location.Slicelet
    val assignment1 = createRandomAssignment(9, Vector("pod0", "pod1"))
    driver.setAssignment(assignment1)

    // Setup: create a metric change tracker for the number of requests for the matched target.
    val numRequestsTrackerForMatchedTarget = MetricUtils.ChangeTracker(
      () =>
        getNumWatchRequests(
          handlerTarget = testCase.target1,
          requestTarget = testCase.target1,
          metricsKey = MetricsKey(isClerk = true, LATEST_VERSION),
          handlerLocation = handlerLocation
        )
    )

    // Setup: create a metric change tracker for the number of requests for the mismatched target.
    val numRequestsTrackerForMismatchedTarget = MetricUtils.ChangeTracker(
      () =>
        getNumWatchRequests(
          handlerTarget = testCase.target1,
          requestTarget = testCase.target2,
          metricsKey = MetricsKey(isClerk = true, LATEST_VERSION),
          handlerLocation = handlerLocation
        )
    )

    // Setup: create a metric change tracker for the number of Clerk subscribers for both the
    // handler and mismatched requester targets.
    val numClerksForHandlerTarget = MetricUtils.ChangeTracker(
      () => getNumClerks(testCase.target1, handlerLocation, LATEST_VERSION)
    )

    val numClerksForRequesterTarget = MetricUtils.ChangeTracker(
      () => getNumClerks(testCase.target2, handlerLocation, LATEST_VERSION)
    )

    // Verify: The number of mismatched target requests should be 0.
    assert(numRequestsTrackerForMatchedTarget.totalChange() == 0)
    assert(numRequestsTrackerForMismatchedTarget.totalChange() == 0)

    // Setup: Create a client request with a different target.
    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
      .copy(target = testCase.target2)

    // Send the request to the handler.
    val response: Future[ClientResponseP] =
      driver.handleWatch(request, redirectOpt = None)
    Await.ready(response, Duration.Inf)

    val fatalMismatch: Boolean =
      TargetHelper.isFatalTargetMismatch(testCase.target1, testCase.target2)
    assert(response.value.get.isFailure == fatalMismatch)
    AssertionWaiter("Mismatches incremented", pollInterval = METRICS_ASSERTION_POLL_INTERVAL)
      .await {
        assert(numRequestsTrackerForMismatchedTarget.totalChange() == 1)
      }

    // Verify: The number of clerks should be updated for `target2` and not for `target1`.
    AssertionWaiter("Subscriber tracked", pollInterval = METRICS_ASSERTION_POLL_INTERVAL).await {
      assert(numClerksForHandlerTarget.totalChange() == 0)
      assert(numClerksForRequesterTarget.totalChange() == 1)
    }

    // Send a client request with the correct target.
    val request2 = createClientRequest(Generation.EMPTY, ClerkData, "subscriber2")
      .copy(target = testCase.target1)

    val response2: Future[ClientResponseP] =
      driver.handleWatch(request2, redirectOpt = None)
    Await.ready(response2, Duration.Inf)

    // Verify: metrics are correctly updated.
    AssertionWaiter("metrics updated", pollInterval = METRICS_ASSERTION_POLL_INTERVAL).await {
      assert(numRequestsTrackerForMatchedTarget.totalChange() == 1)
      assert(numRequestsTrackerForMismatchedTarget.totalChange() == 1)

      assert(numClerksForHandlerTarget.totalChange() == 1)
      assert(numClerksForRequesterTarget.totalChange() == 1)
    }
  }

  test("Fatal target mismatch gets error") {
    // Test plan: verify that a fatal target mismatch between the request and handler targets
    // results in an error.
    val driver = createDriver(Location.Slicelet, target)
    val otherTarget = Target("other-name")
    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
      .copy(target = otherTarget)

    val response: Future[ClientResponseP] =
      driver.handleWatch(request, redirectOpt = None)

    Await.ready(response, Duration.Inf)

    assert(response.value.get.isFailure)
    val status: Status = StatusUtils.convertExceptionToStatus(response.value.get.failed.get)
    assert(status.getCode == Status.NOT_FOUND.getCode)
  }

  test("handleWatch never returns a stale assignment") {
    // Test plan: Verify that a stale full assignment is ignored if its generation is older than the
    // cached assignment in the subscriber handler. This is tested by first performing a handleWatch
    // call with an assignment to set the cache in subscriber handler, and then performing another
    // handleWatch with a stale assignment (older generation), and ensuring the response does not
    // return the stale assignment. Then, create a new assignment with a higher generation, perform
    // a handleWatch, and ensure the new assignment is returned.
    val driver = createDriver(Location.Slicelet, target)

    // Create an assignment with generation 42.
    val initialGeneration: Generation = Generation(Incarnation(1), 42)
    val initialAssignment: Assignment =
      createRandomAssignment(initialGeneration, Vector("pod0", "pod1"))
    driver.setAssignment(initialAssignment)

    // Call handleWatch so that `initialAssignment` is cached in the subscriber handler.
    val fut1: Future[ClientResponseP] = driver.handleWatch(
      createClientRequest(Generation.EMPTY, ClerkData, "subscriber1"),
      redirectOpt = None
    )
    // Verify that the response contains `initialAssignment`.
    val clientResponse1 = ClientResponse.fromProto(TestUtils.awaitResult(fut1, Duration.Inf))
    clientResponse1.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(initialAssignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) =>
        fail("Expected KnownAssignment")
    }

    // Create a stale assignment at generation 40, update the cell, and call handleWatch.
    val staleAssignment: Assignment =
      createRandomAssignment(Generation(Incarnation(1), 40), Vector("pod0"))
    driver.setAssignment(staleAssignment)
    val fut2: Future[ClientResponseP] = driver.handleWatch(
      createClientRequest(Generation(Incarnation(1), 39), ClerkData, "subscriber2"),
      redirectOpt = None
    )

    // Verify that the stale assignment is ignored, and that the response contains generation from
    // the cached assignment.
    val clientResponse2 = ClientResponse.fromProto(TestUtils.awaitResult(fut2, Duration.Inf))
    assertResult(clientResponse2.syncState.getKnownGeneration)(initialGeneration)

    // Create a new assignment at generation 45, update the cell, and call handleWatch.
    val newAssignment: Assignment =
      createRandomAssignment(Generation(Incarnation(1), 45), Vector("pod0"))
    driver.setAssignment(newAssignment)
    val fut3: Future[ClientResponseP] = driver.handleWatch(
      createClientRequest(Generation(Incarnation(1), 41), ClerkData, "subscriber3"),
      redirectOpt = None
    )
    // Verify that the new assignment is returned in the response.
    val clientResponse3 = ClientResponse.fromProto(TestUtils.awaitResult(fut3, Duration.Inf))
    clientResponse3.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(newAssignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) =>
        fail("Expected KnownAssignment")
    }
  }

  test("Server returns correct proto variant based on supportsSerializedAssignment") {
    // Test plan: Verify that when supportsSerializedAssignment=true, the server returns a
    // KnownSerializedAssignment proto, and when false, a KnownAssignment proto. Both responses
    // should decode to the same DiffAssignment.
    val driver = createDriver(Location.Slicelet, target)
    val assignment: Assignment = createRandomAssignment(
      generation = Generation(Incarnation(1), 100),
      Vector("pod0")
    )
    driver.setAssignment(assignment)

    // 1. Send a watch with supportsSerializedAssignment = true.
    val serializedRequest =
      createClientRequest(Generation.EMPTY, ClerkData, "subscriber-serialized")
    assert(serializedRequest.supportsSerializedAssignment)
    val serializedResponseP: ClientResponseP =
      TestUtils.awaitResult(driver.handleWatch(serializedRequest, redirectOpt = None), Duration.Inf)

    // Verify that the raw proto contains KnownSerializedAssignment.
    serializedResponseP.getSyncAssignmentState.state match {
      case SyncAssignmentStateP.State.KnownSerializedAssignment(_: ByteString) => // Expected.
      case other => fail(s"Expected KnownSerializedAssignment, got $other")
    }

    // 2. Send a watch with supportsSerializedAssignment = false.
    val structuredRequest = ClientRequest(
      target,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "subscriber-structured",
      TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = false
    )
    assert(!structuredRequest.supportsSerializedAssignment)
    val structuredResponseP: ClientResponseP =
      TestUtils.awaitResult(driver.handleWatch(structuredRequest, redirectOpt = None), Duration.Inf)

    // Verify that the raw proto contains KnownAssignment.
    structuredResponseP.getSyncAssignmentState.state match {
      case SyncAssignmentStateP.State.KnownAssignment(_: DiffAssignmentP) => // Expected.
      case other => fail(s"Expected KnownAssignment, got $other")
    }

    // 3. Both responses should decode to the same DiffAssignment.
    val serializedResponse = ClientResponse.fromProto(serializedResponseP)
    val structuredResponse = ClientResponse.fromProto(structuredResponseP)
    assert(
      serializedResponse.syncState == structuredResponse.syncState,
      "Serialized and structured responses should produce the same SyncAssignmentState"
    )
  }

  test("Response and Assignment size metrics") {
    // Test plan: Verify that SubscriberHandler emits both response size metrics:
    // 1. `dicer_client_response_size_bytes` - full `ClientResponseP` size.
    // 2. `dicer_serialized_assignment_size_bytes` - serialized assignment state size.
    // We test with two requests: one with no known generation, one with a known generation.
    val location = Location.Assigner
    val driver: SubscriberHandlerDriver = createDriver(location, target)

    // Track the known generation.
    var knownGeneration: Generation = Generation.EMPTY

    for (genNumber: Long <- Seq(42L, 43L)) {
      // Set an assignment with the current generation.
      val assignment: Assignment = createRandomAssignment(
        generation = Generation(Incarnation(1), genNumber),
        Vector("pod0", "pod1")
      )
      driver.setAssignment(assignment)

      // Create fresh trackers to measure the delta from this request.
      val assignmentSumTracker = MetricUtils.ChangeTracker(
        () => getSerializedAssignmentSizeSum(target, location)
      )
      val responseSumTracker = MetricUtils.ChangeTracker(
        () => getClientResponseSizeSum(target, location)
      )

      // Send a request with the current known generation.
      val request: ClientRequest =
        createClientRequest(knownGeneration, ClerkData, s"subscriber$genNumber")
      val responseP: ClientResponseP =
        TestUtils.awaitResult(driver.handleWatch(request, redirectOpt = None), Duration.Inf)

      // Sanity check: verify we got an assignment response (not a timeout).
      val response = ClientResponse.fromProto(responseP)
      assert(
        response.syncState.isInstanceOf[SyncAssignmentState.KnownAssignment],
        s"genNumber=$genNumber: expected KnownAssignment response"
      )

      // Verify that each metric recorded exactly this response's size.
      val expectedAssignmentSize: Double = responseP.syncAssignmentState.get.serializedSize.toDouble
      val expectedResponseSize: Double = responseP.serializedSize.toDouble
      assert(
        assignmentSumTracker.totalChange() == expectedAssignmentSize,
        s"genNumber=$genNumber: expected assignment size $expectedAssignmentSize bytes, " +
        s"but was ${assignmentSumTracker.totalChange()} bytes"
      )
      assert(
        responseSumTracker.totalChange() == expectedResponseSize,
        s"genNumber=$genNumber: expected response size $expectedResponseSize bytes, " +
        s"but was ${responseSumTracker.totalChange()} bytes"
      )

      // Update the known generation for the next request.
      knownGeneration = assignment.generation
    }
  }

  test("Load shedding when processing assignments after timeout") {
    // Test plan: Verify that when an assignment becomes available after the request timeout
    // has elapsed, the handler performs load shedding by returning just the known generation
    // instead of the full assignment. Also verify that the load shedding metric is incremented.
    val driver = createDriver(Location.Assigner, target)
    val loadShedTracker = MetricUtils.ChangeTracker { () =>
      getNumWatchRequestsLoadShed(target)
    }

    // Start the cell with a random assignment. This better matches what we expect in production.
    val assignment1 = createRandomAssignment(9, Vector("pod0", "pod1"))
    driver.setAssignment(assignment1)

    // Block the SEC so the handlers can't run.
    driver.blockSequentialExecutor()

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val fut: Future[ClientResponseP] = driver.handleWatch(request, redirectOpt = None)

    // Sleep briefly, because the actual implementation may be running in a separate process, and
    // we need sometime for the metric to be updated. The sleep also allows the handler to capture
    // its start time, which is needed before advancing the fake clock. We could add an explicit
    // event to the handler to signal when it has captured its start time, but the benefits are
    // marginal, so we use a sleep instead.
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    // Load shedding metric should not be incremented yet, and the future should not be completed.
    assert(loadShedTracker.totalChange() == 0)
    assertResult(false)(fut.isCompleted)

    // Advance time past the request timeout to simulate delayed processing.
    driver.advanceTime(request.timeout + 1.milliseconds)

    // Now update the cell with a new assignment.
    val assignment2 = createRandomAssignment(42, Vector("pod0", "pod1"))
    driver.setAssignment(assignment2)

    // Unblock the executor. Verify that the response contains only the known generation (due to
    // load shedding) and not the full assignment. Also verify that the load shedding metric was
    // incremented.
    driver.unblockSequentialExecutor()
    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    response.syncState match {
      case SyncAssignmentState.KnownGeneration(generation) =>
        assert(
          generation == assignment2.generation,
          s"Expected generation ${assignment2.generation}, got $generation"
        )
      case SyncAssignmentState.KnownAssignment(_) =>
        fail("Expected load shedding to occur, but got full assignment instead")
    }

    AssertionWaiter("Load shedding", pollInterval = METRICS_ASSERTION_POLL_INTERVAL).await {
      assert(loadShedTracker.totalChange() == 1)
    }
  }

  test("Processing timeout accounts for sequential executor delay") {
    // Test plan: Verify that the processing timeout calculation correctly accounts for the
    // sequential executor scheduling delay between when the handler is actually called, and when
    // it runs on the sequential executor.
    val driver = createDriver(Location.Slicelet, target)

    // Start the cell with an assignment. The request uses this generation as its known generation.
    val generation: Generation = 42
    val assignment = createRandomAssignment(generation, Vector("pod0", "pod1"))
    driver.setAssignment(assignment)

    // Block the sequential executor so the handlers can't run.
    driver.blockSequentialExecutor()

    // Call handle watch with the latest assignment's generation as known generation.
    val request = createClientRequest(generation, ClerkData, "subscriber1")
    val fut: Future[ClientResponseP] = driver.handleWatch(request, redirectOpt = None)

    // Sleep briefly, because the actual implementation may be running in a separate process, and
    // we need sometime for the metric to be updated. The sleep also allows the handler to capture
    // its start time, which is needed before advancing the fake clock. We could add an explicit
    // event to the handler to signal when it has captured its start time, but the benefits are
    // marginal, so we use a sleep instead.
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    // Verify that the future should not be completed.
    assertResult(false)(fut.isCompleted)

    // Simulate queue delay by advancing time before the sequential executor can run.
    val queueDelay: FiniteDuration = 2.seconds
    driver.advanceTime(queueDelay)
    // Unblock the sequential executor and verify the future is not completed.
    driver.unblockSequentialExecutor()

    // Wait until the handler's processing timeout sleep is registered with the fake clock.
    driver.waitForSubscriberHandler(target, expectedWatchCount = 1)

    // Sleep briefly, because the actual implementation may be running in a separate process, and
    // we need sometime for the handler to process the request and update the future.
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assertResult(false)(fut.isCompleted)

    // Advance the clock by less than the remaining processing timeout. The future should remain
    // uncompleted.
    val processingTimeout: FiniteDuration =
      WatchServerHelper.getWatchProcessingTimeout(request.timeout)
    val remainingTimeout: FiniteDuration = processingTimeout - queueDelay
    driver.advanceTime(remainingTimeout - 1.seconds)
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assertResult(false)(fut.isCompleted)

    // Now advance by 1 second to exceed the expected processing timeout (assuming the queue delay
    // is taken into account). The future should now complete.
    driver.advanceTime(1.second)
    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    // The request hit the processing timeout and should return just the known generation.
    assert(response.syncState == SyncAssignmentState.KnownGeneration(generation))
  }
}
