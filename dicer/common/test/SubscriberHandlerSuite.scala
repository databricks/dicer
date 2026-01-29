package com.databricks.dicer.common

import java.net.URI
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.Random
import com.databricks.api.proto.dicer.common.{
  ClientRequestP,
  ClientResponseP,
  DiffAssignmentP,
  SyncAssignmentStateP
}
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext
import com.databricks.caching.util.{FakeSequentialExecutionContext, MetricUtils}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.google.protobuf.ByteString
import com.databricks.dicer.common.Assignment.AssignmentValueCell
import com.databricks.dicer.common.SubscriberHandler.{Location, MetricsKey}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.Version.LATEST_VERSION
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import io.grpc.{Status, StatusException}

/**
 * Fake time tests for [[SubscriberHandler]]. Note that the handler is also tested in a more
 * realistic end-to-end way via `SliceLookupSuite` and `AssignerSuite`.
 *
 * TODO(<internal bug>): Move tests to [[SubscriberHandlerSuiteBase]] or [[ScalaSubscriberHandlerSuite]].
 */
class SubscriberHandlerSuite extends DatabricksTest with TestName {

  /** Timeout to request for watch requests. */
  private val TIMEOUT = 1.minute

  /** RNG for test data. */
  private val random = new Random

  /** Target to use for the current test. */
  private def target: Target = Target(getSafeName)

  /**
   * State for an individual test case.
   *
   * @param sec Fake executor that can be used to manually advance time.
   * @param cell Read-write cell consumed by the handler under test.
   * @param handler The handler under test.
   */
  private case class TestState(
      sec: FakeSequentialExecutionContext,
      cell: AssignmentValueCell,
      handler: SubscriberHandler) {

    /** Blocks until all pending commands on [[sec]] have drained. */
    def drainSec(): Unit = TestUtils.awaitResult(sec.call {}, Duration.Inf)
  }
  private object TestState {

    /**
     * @param handlerTarget the [[Target]] for the handler, defaulted to [[target]].
     * @param handlerLocation the location of the handler, defaulted to Assigner. Tests that
     *                        care about the handler location should explicitly set this.
     */
    def apply(
        handlerTarget: Target = target,
        handlerLocation: Location = Location.Assigner
    ): TestState = {
      val sec = FakeSequentialExecutionContext.create(getSafeName)
      val cell = new AssignmentValueCell
      val handler = new SubscriberHandler(
        sec,
        handlerTarget,
        getSuggestedClerkRpcTimeoutFn = () => TIMEOUT,
        suggestedSliceletRpcTimeout = TIMEOUT,
        handlerLocation
      )
      TestState(sec, cell, handler)
    }
  }

  private def createRPCContext(): RPCContext = {
    JettyTestRPCContext
      .builder()
      .method("POST")
      .uri("/")
      .build()
  }

  /** Creates [[SliceletData]] for a Slicelet with the given address. */
  private def createSliceletData(uri: String): SliceletData = {
    SliceletData(
      createTestSquid(uri),
      ClientRequestP.SliceletDataP.State.RUNNING,
      "localhostNamespace",
      attributedLoads = Vector.empty,
      unattributedLoadOpt = None
    )
  }

  /** Creates a simulated watch request from a subscriber with the given data. */
  private def createClientRequest(
      knownGeneration: Generation,
      data: SubscriberData,
      debugName: String): ClientRequest = {
    ClientRequest(
      target,
      SyncAssignmentState.KnownGeneration(knownGeneration),
      debugName,
      TIMEOUT,
      data,
      supportsSerializedAssignment = true
    )
  }

  /**
   * Creates a random assignment to `uris` with `generation`. URIs map to Squids according to
   * [[createTestSquid()]].
   */
  private def createRandomAssignment(generation: Generation, uris: Seq[String]): Assignment = {
    ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        uris.map(uri => createTestSquid(uri)).toVector,
        numMaxReplicas = 1,
        random
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      generation
    )
  }

  /** Returns the assignment distribution latency histogram's current sum of all samples. */
  private def getAssignmentDistributionLatencySum: Double = {
    SubscriberHandler.forTestStatic
      .getAssignmentDistributionLatencyHistogram(target)
      .get()
      .sum
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
    val state = TestState(handlerLocation = handlerLocation)

    // Attach a first subscriber that has no knowledge of any assignments.
    val fut1: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(Generation.EMPTY, data1, "subscriber1"),
      state.cell
    )
    // Verify that metrics are updated after the `handleWatch` call above completes.
    state.drainSec()
    var expectedNumClerks: Long = 0
    var expectedNumSlicelets: Long = 0
    data1 match {
      case ClerkData => expectedNumClerks += 1
      case _: SliceletData => expectedNumSlicelets += 1
    }
    assert(
      SubscriberHandlerMetricUtils
        .getNumClerksByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumClerks
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumSliceletsByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumSlicelets
    )

    // Attach a second subscriber that already knows about the assignment we're about to inject.
    val fut2: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(knownGeneration = 42, data2, "subscriber2"),
      state.cell
    )
    state.drainSec()
    data2 match {
      case ClerkData => expectedNumClerks += 1
      case _: SliceletData => expectedNumSlicelets += 1
    }
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, LATEST_VERSION) == expectedNumClerks)
    assert(
      SubscriberHandlerMetricUtils.getNumSlicelets(target, LATEST_VERSION) == expectedNumSlicelets
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumClerksByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumClerks
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumSliceletsByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumSlicelets
    )

    // Supply an assignment that satisfies the first subscriber but not the second, since it is
    // waiting for a more recent assignment.
    val assignment1: Assignment = createRandomAssignment(42, Vector("pod0", "pod1"))
    state.cell.setValue(assignment1)
    val response1: ClientResponse =
      ClientResponse.fromProto(TestUtils.awaitResult(fut1, Duration.Inf))
    assert(response1.syncState == SyncAssignmentState.KnownAssignment(assignment1))
    assert(!fut2.isCompleted)

    // After the timeout is up, the second subscriber should receive a response indicating that the
    // known assignment has not advanced.
    state.sec.getClock.advanceBy(TIMEOUT)
    val response2: ClientResponse =
      ClientResponse.fromProto(TestUtils.awaitResult(fut2, Duration.Inf))
    assert(response2.syncState == SyncAssignmentState.KnownGeneration(42))

    // Keep the second Subscriber's metrics alive by repeatedly issuing watch requests, but allow
    // the first subscriber to lapse. We need to wait out EXPIRE_AFTER * 2 before the metrics are
    // guaranteed to be updated.
    for (_ <- 0 until 4) {
      state.sec.getClock.advanceBy(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      state.handler.handleWatch(
        createRPCContext(),
        createClientRequest(knownGeneration = 42, data2, "subscriber2"),
        state.cell
      )
      state.drainSec()
    }
    data1 match {
      case ClerkData => expectedNumClerks -= 1
      case _: SliceletData => expectedNumSlicelets -= 1
    }
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, LATEST_VERSION) == expectedNumClerks)
    assert(
      SubscriberHandlerMetricUtils.getNumSlicelets(target, LATEST_VERSION) == expectedNumSlicelets
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumClerksByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumClerks
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumSliceletsByHandler(handlerLocation, target, LATEST_VERSION) == expectedNumSlicelets
    )

    // Now cancel the handler and verify that the number of tracked subscribers drops to 0.
    state.handler.cancel()
    state.drainSec()
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, LATEST_VERSION) == 0)
    assert(SubscriberHandlerMetricUtils.getNumSlicelets(target, LATEST_VERSION) == 0)
    assert(
      SubscriberHandlerMetricUtils
        .getNumClerksByHandler(handlerLocation, target, LATEST_VERSION) == 0
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumSliceletsByHandler(handlerLocation, target, LATEST_VERSION) == 0
    )
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase to exercise both Scala and Rust
  //  subscriber handlers.
  test("Clerk to Slicelet lifetime test") {
    // Test plan: Validates watch lifetimes from two Clerks to Slicelets.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Slicelet, ClerkData, ClerkData)
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase. Even though Assigner handlers
  //  are only in Scala, the lifetime related implementation does not assume where the handler is
  //  located.
  test("Clerk to Assigner lifetime test") {
    // Test plan: Validates watch lifetimes from two Clerks to Assigner.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Assigner, ClerkData, ClerkData)
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase, see the comments on previous
  //  test for details.
  test("Slicelet to Assigner lifetime test") {
    // Test plan: Validates watch lifetimes for two Slicelets to Assigner.
    // See `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(
      Location.Assigner,
      createSliceletData("pod0"),
      createSliceletData("pod1")
    )
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase.
  test("Heterogeneous subscriber to Assigner lifetime test") {
    // Test plan: Validates watch lifetimes for a Slicelet and a Clerk to an Assigner. See
    // `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Assigner, createSliceletData("pod0"), ClerkData)
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase.
  test("Heterogeneous subscriber to Slicelet lifetime test") {
    // Test plan: Validates watch lifetimes for a Slicelet and a Clerk to a Slicelet. See
    // `runSubscriberLifetimeTest` for details.
    runSubscriberLifetimeTest(Location.Slicelet, createSliceletData("pod0"), ClerkData)
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase.
  test("Subscriber version metrics") {
    // Test plan: Verify that the subscriber version metrics are updated as expected. Send a clerk
    // watch with the default version, and verify that it is recorded in the metrics. Then change
    // the clerk to report version 0, and verify that the default version metric resets to 0 while
    // the version 0 metric is incremented.
    val state = TestState()
    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    // Verify that version metrics are updated after the `handleWatch` call, and the internal
    // `updateMetrics` task completes. We need to keep the metrics alive by repeatedly issuing watch
    // requests. We need to wait out EXPIRE_AFTER * 2 before the metrics are guaranteed to be
    // updated.
    for (_ <- 0 until 4) {
      state.handler.handleWatch(createRPCContext(), request, state.cell)
      state.sec.getClock.advanceBy(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      state.drainSec()
    }
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, LATEST_VERSION) == 1)

    // Update the request to report its version as 0. Ensure `updateMetrics` runs, and verify that
    // the metrics are updated as expected.
    val newRequest = request.copy(version = 0)
    for (_ <- 0 until 4) {
      state.handler.handleWatch(createRPCContext(), newRequest, state.cell)
      state.sec.getClock.advanceBy(SubscriberHandlerMetrics.EXPIRE_AFTER / 2)
      state.drainSec()
    }
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, LATEST_VERSION) == 0)
    assert(SubscriberHandlerMetricUtils.getNumClerks(target, 0) == 1)
  }

  // TODO(<internal bug>): Move this test to ScalaSubscriberHandlerSuite, because distribution tracking
  //  is on the Assigner, which is Scala-only.
  test("Assignment tracker measures distribution time to assigned resources") {
    // Test plan: Verify that the assignment distribution tracker correctly measures the time from
    // when the assignment is first distributed to when it is received by the last assigned
    // resource. Verify this by having three subscribers connect with known generation 42, then
    // distribute an assignment with generation 44 where only 2 of the subscribers are assigned in
    // the assignment, and checking that the tracker records the distribution time when the two
    // assigned resources report that they know assignment generation 44. Verify that knowing
    // intermediate generations (e.g. 43) does not count, and also that a second assignment
    // distribution with greater incarnation is also tracked correctly.
    val state = TestState()

    val pod0data = createSliceletData("pod0")
    val pod1data = createSliceletData("pod1")
    val pod2data = createSliceletData("pod2")

    // Setup: Create three subscribers, pod{0,1,2}, with known generation 42.
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod0data, s"pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod1data, s"pod1"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod2data, s"pod2"),
      state.cell
    )
    state.drainSec()

    // Setup: Create an assignment with generation 44 that only assigns pod0 and pod1.
    state.cell.setValue(createRandomAssignment(44, Vector("pod0", "pod1")))
    state.drainSec()

    // Setup: Have pod0 watch again 10 ms later and report that it knows gen 44.
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod0data, "pod0"),
      state.cell
    )
    state.drainSec()

    // Verify: The tracker should not yet have recorded the distribution time.
    assert(getAssignmentDistributionLatencySum == 0.0)

    // Setup: Now have pod1 watch again 20 ms later and report that it knows gen 43 < 44.
    state.sec.getClock.advanceBy(20.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(43, pod1data, "pod1"),
      state.cell
    )
    state.drainSec()

    // Verify: The tracker should not yet have recorded the distribution time, because pod1 has not
    // yet learned gen 44.
    assert(getAssignmentDistributionLatencySum == 0.0)

    // Setup: Now have pod1 watch again 30 ms later and report that it now knows gen 44.
    state.sec.getClock.advanceBy(30.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod1data, "pod1"),
      state.cell
    )
    state.drainSec()

    // Verify: The tracker should have recorded the distribution time to be 10ms + 20ms + 30ms =
    // 60ms (0.06s)
    assert(getAssignmentDistributionLatencySum == 0.06)

    // Setup: Now have pod2 come in after another 40ms reporting that it knows gen 44
    state.sec.getClock.advanceBy(40.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod2data, "pod2"),
      state.cell
    )
    state.drainSec()

    // Verify: Pod2 should not affect measurements since it was not assigned in the assignment.
    assert(getAssignmentDistributionLatencySum == 0.06)

    // Setup: Create an assignment with generation 45 that now includes all 3 pods.
    state.cell.setValue(createRandomAssignment(45, Vector("pod0", "pod1", "pod2")))
    state.drainSec()

    // Setup: Have pods 1 and 2 come back 50 ms later reporting that they know gen 45.
    state.sec.getClock.advanceBy(50.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod0data, "pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod1data, "pod1"),
      state.cell
    )
    state.drainSec()

    // Verify: No new distribution time should be recorded since pod2 has not yet reported back.
    assert(getAssignmentDistributionLatencySum == 0.06)

    // Setup: Now have pod2 come in after another 60ms reporting that it knows gen 45.
    state.sec.getClock.advanceBy(60.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod2data, "pod2"),
      state.cell
    )
    state.drainSec()

    // Verify: The tracker records a new measurement of 50ms + 60ms = 110ms (0.11s).
    assert(getAssignmentDistributionLatencySum == 0.06 + 0.11)
  }

  // TODO(<internal bug>): Move this test to ScalaSubscriberHandlerSuite, because distribution tracking
  //  is on the Assigner, which is Scala-only.
  test("Assignment tracker aborts tracking when it learns of a higher generation") {
    // Test plan: Verify that if the tracker learns that there is a higher generation assignment,
    // either from a subscriber or from a distributed assignment, it aborts tracking the current
    // assignment. Verify this by having three subscribers connect with with known generation 42,
    // distributing an assignment with generation 43 including these three subscribers, and then
    // having one of the subscribers report that they know generation 44, and checking that the
    // latency metric is not updated.
    val state = TestState()

    val pod0data = createSliceletData("pod0")
    val pod1data = createSliceletData("pod1")
    val pod2data = createSliceletData("pod2")

    // Setup: Create three subscribers, pod{0,1,2}, with known generation 42.
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod0data, s"pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod1data, s"pod1"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(42, pod2data, s"pod2"),
      state.cell
    )
    state.drainSec()

    // Setup: Create an assignment with generation 43 that assigns all pods.
    state.cell.setValue(createRandomAssignment(43, Vector("pod0", "pod1", "pod2")))
    state.drainSec()

    // Setup: Have all pods come back, where pods 0 and 1 report that they know gen 43, but pod 2
    // reports that it knows more advanced gen 44.
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(43, pod0data, "pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(43, pod1data, "pod1"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod2data, "pod2"),
      state.cell
    )
    state.drainSec()

    // Verify: No distribution time should be recorded since pod2 has reset the tracker.
    assert(getAssignmentDistributionLatencySum == 0.0)

    // Setup: Have pods 0 and 1 come back with gen 44 and confirm that the tracker is not tracking
    // gen 44 (tracking should only be triggered on assignment distribution).
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod0data, "pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(44, pod1data, "pod1"),
      state.cell
    )
    state.drainSec()

    // Verify: No distribution time should be recorded since there's no tracking going on.
    assert(getAssignmentDistributionLatencySum == 0.0)

    // Setup: Now distribute assignment generation 45, and then subsequently 46, and verify that
    // the tracker is not tracking gen 45.
    state.cell.setValue(createRandomAssignment(45, Vector("pod0", "pod1", "pod2")))
    state.drainSec()

    // Setup: Have pod 0 come back with gen 45.
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod0data, "pod0"),
      state.cell
    )
    state.drainSec()

    // Setup: Now distribute gen 46.
    state.cell.setValue(createRandomAssignment(46, Vector("pod0", "pod1", "pod2")))
    state.drainSec()

    // Verify: Even when all pods come back with gen 45, the tracker should not record anything for
    // gen 45.
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod1data, "pod1"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(45, pod2data, "pod2"),
      state.cell
    )
    state.drainSec()

    // Verify: No distribution time should be recorded since assignment gen 46 is being tracked and
    // not gen 45.
    assert(getAssignmentDistributionLatencySum == 0.0)

    // Verify: When all pods come back with gen 46, the tracker should record the distribution time
    // (10ms + 10ms = 20ms).
    state.sec.getClock.advanceBy(10.milliseconds)
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(46, pod0data, "pod0"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(46, pod1data, "pod1"),
      state.cell
    )
    state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(46, pod2data, "pod2"),
      state.cell
    )
    state.drainSec()
    assert(getAssignmentDistributionLatencySum == 0.02)
  }

  // TODO(<internal bug>): Move this test to ScalaSubscriberHandlerSuite, because distribution tracking
  //  is on the Assigner, which is Scala-only.
  test("Assignment tracker buckets meet expectations") {
    // Test plan: Verify that the assignment distribution tracker buckets use a growth factor of 1.4
    // in the range [1ms, 1s) (21 buckets), and a growth factor of 2 in the range [1s, 1024s] (11
    // buckets), for a total of 32 buckets plus 1 for the +Inf bucket.
    import SubscriberHandler.forTestStatic.getAssignmentDistributionLatencyHistogram

    assert(
      getAssignmentDistributionLatencyHistogram(target)
        .get()
        .buckets
        .length == 32 + 1
    )

    // Verify: Growth factor of 1.4 in [1ms, 1s).
    getAssignmentDistributionLatencyHistogram(target).observe(0.001)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(0) == 1)
    getAssignmentDistributionLatencyHistogram(target).observe(0.0014)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(1) == 2)
    getAssignmentDistributionLatencyHistogram(target)
      .observe(0.00196)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(2) == 3)

    // Verify: Growth factor of 2 in [1s, 1024s).
    getAssignmentDistributionLatencyHistogram(target).observe(1.0)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(21) == 4)
    getAssignmentDistributionLatencyHistogram(target).observe(2.0)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(22) == 5)
    getAssignmentDistributionLatencyHistogram(target)
      .observe(1024.0)
    assert(getAssignmentDistributionLatencyHistogram(target).get().buckets(31) == 6)
  }

  // Verify that metrics are recorded for both fatal and non-fatal mismatches by parameterizing the
  // test over targets with both types of mismatches.
  private case class TargetMismatchCase(target1: Target, target2: Target)
  private val targetMismatchCases: Seq[TargetMismatchCase] = {
    val uri1: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val uri2: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
    Seq(
      // Fatal.
      TargetMismatchCase(
        Target.createKubernetesTarget(uri1, "a"),
        Target.createKubernetesTarget(uri1, "b")
      ),
      // Non-fatal.
      TargetMismatchCase(
        Target.createKubernetesTarget(uri1, "a"),
        Target.createKubernetesTarget(uri2, "a")
      )
    )
  }
  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase to exercise both Scala and Rust
  //  subscriber handlers.
  gridTest("Target mismatch metrics")(targetMismatchCases) { testCase: TargetMismatchCase =>
    // Test plan: Verify that requests for a target which does not match that of the handler are
    // recorded in the metrics.

    val handlerLocation: Location = Location.Slicelet
    val state = TestState(testCase.target1, handlerLocation)
    val assignment1 = createRandomAssignment(9, Vector("pod0", "pod1"))
    state.cell.setValue(assignment1)

    // Setup: create a metric change tracker for the number of requests for the matched target.
    val numRequestsTrackerForMatchedTarget = MetricUtils.ChangeTracker(
      () =>
        SubscriberHandlerMetricUtils.getNumWatchRequests(
          handlerTarget = testCase.target1,
          requestTarget = testCase.target1,
          callerService = "unknown",
          metricsKey = MetricsKey(isClerk = true, LATEST_VERSION),
          handlerLocation = handlerLocation
        )
    )

    // Setup: create a metric change tracker for the number of requests for the mismatched target.
    val numRequestsTrackerForMismatchedTarget = MetricUtils.ChangeTracker(
      () =>
        SubscriberHandlerMetricUtils.getNumWatchRequests(
          handlerTarget = testCase.target1,
          requestTarget = testCase.target2,
          callerService = "unknown",
          metricsKey = MetricsKey(isClerk = true, LATEST_VERSION),
          handlerLocation = handlerLocation
        )
    )

    // Setup: create a metric change tracker for the number of Clerk subscribers for both the
    // handler and mismatched requester targets.
    val numClerksForHandlerTarget = MetricUtils.ChangeTracker(
      () => SubscriberHandlerMetricUtils.getNumClerks(testCase.target1, LATEST_VERSION)
    )

    val numClerksForRequesterTarget = MetricUtils.ChangeTracker(
      () => SubscriberHandlerMetricUtils.getNumClerks(testCase.target2, LATEST_VERSION)
    )

    // Verify: The number of mismatched target requests should be 0.
    assert(numRequestsTrackerForMatchedTarget.totalChange() == 0)
    assert(numRequestsTrackerForMismatchedTarget.totalChange() == 0)

    // Setup: Create a client request with a different target.
    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
      .copy(target = testCase.target2)

    // Send the request to the handler.
    val response: Future[ClientResponseP] =
      state.handler.handleWatch(createRPCContext(), request, state.cell)

    Await.ready(response, Duration.Inf)
    val fatalMismatch: Boolean =
      TargetHelper.isFatalTargetMismatch(testCase.target1, testCase.target2)
    assert(response.value.get.isFailure == fatalMismatch)

    state.drainSec()
    // Verify: A target mismatch error should be reported.
    assert(numRequestsTrackerForMismatchedTarget.totalChange() == 1)

    // Verify: The number of clerks should be updated for `target2` and not for `target1`.
    assert(numClerksForHandlerTarget.totalChange() == 0)
    assert(numClerksForRequesterTarget.totalChange() == 1)

    // Send a client request with the correct target.
    val request2 = createClientRequest(Generation.EMPTY, ClerkData, "subscriber2")
      .copy(target = testCase.target1)

    state.handler.handleWatch(createRPCContext(), request2, state.cell)
    state.drainSec()

    // Verify: metrics are correctly updated.
    assert(numRequestsTrackerForMatchedTarget.totalChange() == 1)
    assert(numRequestsTrackerForMismatchedTarget.totalChange() == 1)

    assert(numClerksForHandlerTarget.totalChange() == 1)
    assert(numClerksForRequesterTarget.totalChange() == 1)
  }

  // TODO(<internal bug>): Move this test to SubscriberHandlerSuiteBase to exercise both Scala and Rust
  //  subscriber handlers.
  test("Fatal target mismatch gets error") {
    // Test plan: verify that a fatal target mismatch between the request and handler targets
    // results in an error.
    val state = TestState(handlerLocation = Location.Slicelet)
    val otherTarget = Target("other-name")
    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
      .copy(target = otherTarget)

    val response: Future[ClientResponseP] =
      state.handler.handleWatch(createRPCContext(), request, state.cell)

    val exception: StatusException = assertThrow[StatusException]("misrouted to handler") {
      TestUtils.awaitResult(response, Duration.Inf)
    }

    assert(exception.getStatus.getCode == Status.NOT_FOUND.getCode)
  }

  // TODO(<internal bug>): Implement load shedding in Rust subscriber handler and move this test to
  //  SubscriberHandlerSuiteBase to exercise both Scala and Rust subscriber handlers.
  test("Load shedding when processing assignments after timeout") {
    // Test plan: Verify that when an assignment becomes available after the request timeout
    // has elapsed, the handler performs load shedding by returning just the known generation
    // instead of the full assignment. Also verify that the load shedding metric is incremented.
    val state = TestState()
    val loadShedTracker = MetricUtils.ChangeTracker { () =>
      SubscriberHandlerMetricUtils.getNumWatchRequestsLoadShed(target)
    }

    // Start the cell with a random assignment. This better matches what we expect in production.
    val assignment1 = createRandomAssignment(9, Vector("pod0", "pod1"))
    state.cell.setValue(assignment1)

    // Block the SEC so the handlers can't run.
    val secBlocker = Promise[Unit]()
    state.sec.run {
      TestUtils.awaitResult(secBlocker.future, Duration.Inf)
    }

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val fut: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      request,
      state.cell
    )

    // Load shedding metric should not be incremented yet.
    assert(loadShedTracker.totalChange() == 0)

    // Advance time past the request timeout to simulate delayed processing.
    state.sec.getClock.advanceBy(request.timeout + 1.milliseconds)

    // Now update the cell with a new assignment.
    val assignment2 = createRandomAssignment(42, Vector("pod0", "pod1"))
    state.cell.setValue(assignment2)

    // Unblock the SEC. Verify that the response contains only the known generation (due to load
    // shedding) and not the full assignment. We know this is from the `ValueStreamCallback` and not
    // the scheduled timer because the load shed metric is incremented.
    secBlocker.success(())
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

    assert(loadShedTracker.totalChange() == 1)
  }

  // TODO(<internal bug>): Move this test to ScalaSubscriberHandlerSuiteBase. We cannot support it in
  //  Rust because the Rust test driver uses the current thread tokio runtime with fake time, and
  //  blocking the executor will block the entire test.
  test("Processing timeout accounts for SEC delay") {
    // Test plan: Verify that the processing timeout calculation correctly accounts for the SEC
    // scheduling delay between when the handler is actually called, and when it runs on the SEC.
    val state = TestState()
    // Block the SEC so the handlers can't run.
    val secBlocker = Promise[Unit]()
    state.sec.run {
      TestUtils.awaitResult(secBlocker.future, Duration.Inf)
    }

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val fut: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      request,
      state.cell
    )

    // Simulate SEC queue delay by advancing time before the SEC can run.
    val queueDelay: FiniteDuration = 2.seconds
    state.sec.getClock.advanceBy(queueDelay)
    // Unblock the SEC and verify the future is not completed.
    secBlocker.success(())
    assertResult(false)(fut.isCompleted)
    // Advance the clock by less than the remaining processing timeout. The future should remain
    // uncompleted.
    val processingTimeout: FiniteDuration =
      WatchServerHelper.getWatchProcessingTimeout(request.timeout)
    val remainingTimeout: FiniteDuration = processingTimeout - queueDelay
    state.sec.getClock.advanceBy(remainingTimeout - 1.seconds)
    state.drainSec()
    assertResult(false)(fut.isCompleted)

    // Now advance by 1 second to exceed the expected processing timeout (assuming the queue delay
    // is taken into account). The future should now complete.
    state.sec.getClock.advanceBy(1.second)
    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    // The request hit the processing timeout and should return just the known generation.
    assert(response.syncState == SyncAssignmentState.KnownGeneration(Generation.EMPTY))
  }

  // TODO(<internal bug>): Implement caching serialized assignment proto in Rust subscriber handler and
  //  move this test to SubscriberHandlerSuiteBase.
  test("handleWatch never returns a stale assignment") {
    // Test plan: Verify that a stale full assignment is ignored if its generation is older than the
    // cached assignment in the subscriber handler. This is tested by first performing a handleWatch
    // call with an assignment to set the cache in subscriber handler, and then performing another
    // handleWatch with a stale assignment (older generation), and ensuring the response contains
    // the cached assignment's generation rather than the stale assignment. Then, create a new
    // assignment with a higher generation, perform a handleWatch, and ensure the new assignment is
    // returned.
    val state = TestState()

    // Create an assignment with generation 42.
    val initialGeneration: Generation = 42
    val initialAssignment: Assignment =
      createRandomAssignment(initialGeneration, Vector("pod0", "pod1"))
    state.cell.setValue(initialAssignment)

    // Call handleWatch so that `initialAssignment` is cached in the subscriber handler.
    val fut1: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(Generation.EMPTY, createSliceletData("pod0"), "subscriber1"),
      state.cell
    )
    // Verify that the response contains `initialAssignment`.
    val clientResponseP1: ClientResponseP = TestUtils.awaitResult(fut1, Duration.Inf)
    val clientResponse1 = ClientResponse.fromProto(clientResponseP1)
    clientResponse1.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(initialAssignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) =>
        fail()
    }

    // Create a stale assignment at generation 40, update the cell, and call handleWatch.
    val staleAssignment: Assignment = createRandomAssignment(40, Vector("pod0"))
    state.cell.setValue(staleAssignment)
    val fut2: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(39, createSliceletData("pod0"), "subscriber2"),
      state.cell
    )

    // Verify that the stale assignment is ignored, and that the response contains generation from
    // the cached assignment.
    val clientResponseP2: ClientResponseP = TestUtils.awaitResult(fut2, Duration.Inf)
    val clientResponse2 = ClientResponse.fromProto(clientResponseP2)
    clientResponse2.syncState match {
      case SyncAssignmentState.KnownAssignment(_: DiffAssignment) =>
        fail()
      case SyncAssignmentState.KnownGeneration(generation: Generation) =>
        assertResult(initialGeneration)(generation)
    }

    // Create a new assignment at generation 45, update the cell, and call handleWatch.
    val newAssignment: Assignment = createRandomAssignment(45, Vector("pod0"))
    state.cell.setValue(newAssignment)
    val fut3: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      createClientRequest(41, createSliceletData("pod0"), "subscriber3"),
      state.cell
    )
    // Verify that the new assignment is returned in the response.
    val clientResponseP3: ClientResponseP = TestUtils.awaitResult(fut3, Duration.Inf)
    val clientResponse3 = ClientResponse.fromProto(clientResponseP3)
    clientResponse3.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(newAssignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) =>
        fail()
    }
  }

  // TODO(<internal bug>): Implement caching serialized assignment proto in Rust subscriber handler and
  //  move this test to SubscriberHandlerSuiteBase.
  test("Server returns KnownSerializedAssignment when supportsSerializedAssignment is true") {
    // Test plan: Verify that when supportsSerializedAssignment=true, the server
    // returns a response with serialized assignment in SyncAssignmentStateP.
    val state = TestState()
    val assignment: Assignment = createRandomAssignment(100, Vector("pod0"))
    state.cell.setValue(assignment)

    val request: ClientRequest = createClientRequest(Generation.EMPTY, ClerkData, "subscriber")
    val futureResponse: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      request,
      state.cell
    )
    val responseProto: ClientResponseP = Await.result(futureResponse, Duration.Inf)

    // Verify that the response contains a serialized assignment.
    responseProto.getSyncAssignmentState.state match {
      case SyncAssignmentStateP.State.KnownSerializedAssignment(_: ByteString) =>
      // Expected.
      case _ => fail("Expected KnownSerializedAssignment")
    }

    // Validate ClientResponse.
    val response = ClientResponse.fromProto(responseProto)
    response.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(assignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) => fail("Expected KnownAssignment")
    }
  }

  // TODO(<internal bug>): Implement caching serialized assignment proto in Rust subscriber handler and
  //  move this test to SubscriberHandlerSuiteBase.
  test("Server returns KnownAssignment when supportsSerializedAssignment is false") {
    // Test plan: Verify that when supportsSerializedAssignment=false, the server
    // returns a regular DiffAssignmentP (not serialized) in the response.
    val state = TestState()
    val assignment: Assignment = createRandomAssignment(100, Vector("pod0"))
    state.cell.setValue(assignment)

    val request = ClientRequest(
      target,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "subscriber",
      TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = false
    )
    val futureResponse: Future[ClientResponseP] = state.handler.handleWatch(
      createRPCContext(),
      request,
      state.cell
    )
    val responseProto: ClientResponseP = Await.result(futureResponse, Duration.Inf)

    // Verify that the response contains a DiffAssignmentP.
    responseProto.getSyncAssignmentState.state match {
      case SyncAssignmentStateP.State.KnownAssignment(_: DiffAssignmentP) =>
      // Expected.
      case _ => fail("Expected KnownAssignment")
    }

    // Validate ClientResponse.
    val response = ClientResponse.fromProto(responseProto)
    response.syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        assertResult(assignment.toDiff(Generation.EMPTY))(diffAssignment)
      case SyncAssignmentState.KnownGeneration(_: Generation) => fail("Expected KnownAssignment")
    }
  }
}
