package com.databricks.dicer.common

import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.caching.util.{FakeSequentialExecutionContext, Lock, MetricUtils, TestUtils}
import com.databricks.dicer.common.Assignment.AssignmentValueCell
import com.databricks.dicer.common.SubscriberHandler.Location
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.Target
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext
import io.prometheus.client.CollectorRegistry

/**
 * Tests for the Scala implementation of SubscriberHandler, including both shared tests
 * (from [[SubscriberHandlerSuiteBase]]) and Scala-specific tests.
 */
class ScalaSubscriberHandlerSuite extends SubscriberHandlerSuiteBase {

  /** Scala-specific driver that wraps a FakeSequentialExecutionContext and SubscriberHandler. */
  private class ScalaDriver(
      sec: FakeSequentialExecutionContext,
      handler: SubscriberHandler,
      cell: AssignmentValueCell)
      extends SubscriberHandlerDriver {

    private val lock = new ReentrantLock()

    /** Holds the promise used to block the handler's SEC. */
    @GuardedBy("lock")
    private var unblockPromise: Option[Promise[Unit]] = None

    override def setAssignment(assignment: Assignment): Unit = {
      cell.setValue(assignment)
    }

    override def handleWatch(
        request: ClientRequest,
        redirectOpt: Option[Redirect]): Future[ClientResponseP] = {
      val redirect = redirectOpt.getOrElse(Redirect.EMPTY)
      handler.handleWatch(createRPCContext(), request, cell, redirect)
    }

    override def advanceTime(duration: FiniteDuration): Unit = {
      sec.getClock.advanceBy(duration)
    }

    override def waitForSubscriberHandler(target: Target, expectedWatchCount: Int): Unit = {
      // In Scala, draining the SEC is sufficient because `handleWatch` schedules its timeout
      // synchronously within the SEC call.
      drainSec()
    }

    override def blockSequentialExecutor(): Unit = Lock.withLock(lock) {
      val promise = Promise[Unit]()
      require(unblockPromise.isEmpty, "Executor is already blocked")
      unblockPromise = Some(promise)
      sec.call {
        TestUtils.awaitResult(promise.future, Duration.Inf)
      }
    }

    override def unblockSequentialExecutor(): Unit = Lock.withLock(lock) {
      val promise: Promise[Unit] =
        unblockPromise.getOrElse(throw new IllegalStateException("Executor is not blocked"))
      unblockPromise = None
      promise.success(())
      drainSec()
    }

    override def cancel(): Unit = {
      handler.cancel()
    }

    /** Blocks until all pending commands on [[sec]] have drained. */
    private def drainSec(): Unit = TestUtils.awaitResult(sec.call {}, Duration.Inf)

    /** Creates a dummy RPC for use in tests. */
    private def createRPCContext(): RPCContext = {
      JettyTestRPCContext
        .builder()
        .method("POST")
        .uri("/")
        .build()
    }
  }

  override protected def createDriver(
      handlerLocation: Location,
      handlerTarget: Target): SubscriberHandlerDriver = {
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val cell = new AssignmentValueCell
    val handler = new SubscriberHandler(
      sec,
      handlerTarget,
      getSuggestedClerkRpcTimeoutFn = () => TIMEOUT,
      suggestedSliceletRpcTimeout = TIMEOUT,
      handlerLocation
    )
    new ScalaDriver(sec, handler, cell)
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }

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

  /** Returns the assignment distribution latency histogram's current sum of all samples. */
  private def getAssignmentDistributionLatencySum: Double = {
    SubscriberHandler.forTestStatic
      .getAssignmentDistributionLatencyHistogram(target)
      .get()
      .sum
  }

  // This test is Scala-only because distribution tracking is the Assigner behavior.
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

  // This test is Scala-only because distribution tracking is the Assigner behavior.
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

  // This test is Scala-only because distribution tracking is the Assigner behavior.
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

  test("Non-empty Redirect") {
    // Test plan: Verify that the subscriber handler responds to watch requests with the same
    // redirect address that was passed to `handleWatch`, regardless of what request was sent.
    // NOTE: this behavior is Assigner-specific and used to support preferred Assigner redirection.
    val driver = createDriver(Location.Assigner, target)

    // Set an assignment so there is something to watch.
    val assignment1: Assignment = createRandomAssignment(
      generation = Generation(Incarnation(1), 42),
      Vector("pod0", "pod1")
    )
    driver.setAssignment(assignment1)

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val redirectURI = URI.create("example-uri")
    val fut: Future[ClientResponseP] =
      driver.handleWatch(request, redirectOpt = Some(Redirect(Some(redirectURI))))

    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    assert(response.redirect.addressOpt.get == redirectURI)
  }
}
