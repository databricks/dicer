package com.databricks.dicer.assigner

import scala.concurrent.duration._
import scala.concurrent.Future

import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.caching.util.{
  FakeSequentialExecutionContextPool,
  FakeTypedClock,
  TestUtils,
  TickerTime
}
import com.databricks.dicer.common.{
  ClerkSubscriberSlicezData,
  ClientRequest,
  Generation,
  Redirect,
  SliceletData,
  SliceletState,
  SliceletSubscriberSlicezData,
  SyncAssignmentState
}
import com.databricks.dicer.common.Assignment.AssignmentValueCell
import com.databricks.dicer.common.TestSliceUtils
import com.databricks.dicer.external.Target
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext
import com.databricks.testing.DatabricksTest

class SubscriberManagerSuite extends DatabricksTest {

  /** Inactivity threshold used across tests. */
  private val inactivityThreshold: FiniteDuration = 5.minutes

  /** Creates a SubscriberManager with its own clock for testing. Returns both. */
  private def createManager(): (SubscriberManager, FakeTypedClock) = {
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool.create("subscriber-manager-suite", 1, fakeClock)
    val manager: SubscriberManager = new SubscriberManager(
      secPool,
      getSuggestedClerkRpcTimeoutFn = () => 1.second,
      suggestedSliceletRpcTimeout = 1.second
    )
    (manager, fakeClock)
  }

  /**
   * Issues a watch request for the given target to create/refresh a handler entry. The returned
   * Future is intentionally discarded — we only need the side effect of registering the handler.
   */
  private def issueWatch(
      manager: SubscriberManager,
      target: Target,
      currentTime: TickerTime): Unit = {
    val rpcContext: RPCContext = JettyTestRPCContext.builder().method("POST").uri("/").build()
    val sliceletData: SliceletData = SliceletData(
      squid = TestSliceUtils.createTestSquid("pod0"),
      state = SliceletState.Running,
      kubernetesNamespace = "test-namespace",
      attributedLoads = Vector.empty,
      unattributedLoadOpt = None
    )
    val request: ClientRequest = ClientRequest(
      target = target,
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      subscriberDebugName = s"test-subscriber-$target",
      timeout = 1.second,
      subscriberData = sliceletData,
      supportsSerializedAssignment = true
    )
    val cell: AssignmentValueCell = new AssignmentValueCell
    // The Future will not complete because no assignment is published to the cell. Failures
    // would manifest as missing handler entries in subsequent assertions.
    val _: Future[ClientResponseP] =
      manager.handleWatchRequest(rpcContext, request, cell, Redirect.EMPTY, currentTime)
  }

  test("The slicez data is empty when there is no subscriber") {
    // Test plan: Verify that `getSlicezData` returns empty data when no subscribers exist by
    // creating a `SubscriberManager` without issuing any watch requests and asserting the result
    // is empty. This case can only be tested by directly calling `getSlicezData` because, in the
    // assigner, it is invoked only after a watch request is received. Cases involving subscribers
    // are in `SlicezSuite.scala`.
    val (manager, _): (SubscriberManager, FakeTypedClock) = createManager()

    val sliceDataFut: Future[(Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData])] =
      manager.getSlicezData(Target("test-target"))

    val sliceData: (Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData]) =
      TestUtils.awaitResult(sliceDataFut, 1.second)
    assertResult((Seq.empty, Seq.empty))(sliceData)
  }

  test("Handler is removed after inactivity threshold") {
    // Test plan: Verify that an inactive handler is removed by creating a handler via a watch
    // request, advancing time past the inactivity threshold, and asserting the handler no longer
    // exists after calling `removeInactiveHandlers`.
    val (manager, fakeClock): (SubscriberManager, FakeTypedClock) = createManager()
    val target: Target = Target("cleanup-target")

    issueWatch(manager, target, fakeClock.tickerTime())
    assert(manager.forTest.hasHandler(target), "Handler should exist after watch request")

    // Advance time past the threshold and clean up.
    fakeClock.advanceBy(inactivityThreshold)
    manager.removeInactiveHandlers(fakeClock.tickerTime(), inactivityThreshold)
    assert(!manager.forTest.hasHandler(target), "Handler should be removed after inactivity")
  }

  test("Handler is kept when accessed within inactivity threshold") {
    // Test plan: Verify that a handler survives cleanup when re-accessed within the inactivity
    // threshold by creating a handler, advancing time to just below the threshold, re-accessing
    // it via another watch, advancing time again, and asserting the handler still exists after
    // calling `removeInactiveHandlers`.
    val (manager, fakeClock): (SubscriberManager, FakeTypedClock) = createManager()
    val target: Target = Target("active-target")

    issueWatch(manager, target, fakeClock.tickerTime())

    // Advance time to just below the threshold and re-access the handler.
    fakeClock.advanceBy(inactivityThreshold - 1.second)
    issueWatch(manager, target, fakeClock.tickerTime())

    // Advance time by another interval less than the threshold from the last access.
    fakeClock.advanceBy(inactivityThreshold - 1.second)
    manager.removeInactiveHandlers(fakeClock.tickerTime(), inactivityThreshold)
    assert(manager.forTest.hasHandler(target), "Handler should survive when recently accessed")
  }

  test("Multiple targets: cleanup only affects inactive targets") {
    // Test plan: Verify that cleanup only removes inactive targets by creating handlers for two
    // targets, keeping one active via a new watch while letting the other go inactive, and
    // asserting that only the inactive target is removed after calling `removeInactiveHandlers`.
    val (manager, fakeClock): (SubscriberManager, FakeTypedClock) = createManager()
    val activeTarget: Target = Target("active-target")
    val inactiveTarget: Target = Target("inactive-target")

    issueWatch(manager, activeTarget, fakeClock.tickerTime())
    issueWatch(manager, inactiveTarget, fakeClock.tickerTime())
    assert(manager.forTest.handlerCount == 2)

    // Advance time past the threshold, but re-access activeTarget.
    fakeClock.advanceBy(inactivityThreshold)
    issueWatch(manager, activeTarget, fakeClock.tickerTime())

    manager.removeInactiveHandlers(fakeClock.tickerTime(), inactivityThreshold)
    assert(manager.forTest.hasHandler(activeTarget), "Active handler should survive")
    assert(!manager.forTest.hasHandler(inactiveTarget), "Inactive handler should be removed")
    assert(manager.forTest.handlerCount == 1)
  }

  test("removeInactiveHandlers rejects non-positive inactivityThreshold") {
    // Test plan: Verify that removeInactiveHandlers throws IllegalArgumentException when called
    // with Duration.Zero or a negative duration.
    val (manager, fakeClock): (SubscriberManager, FakeTypedClock) = createManager()

    assertThrows[IllegalArgumentException] {
      manager.removeInactiveHandlers(fakeClock.tickerTime(), Duration.Zero)
    }
    assertThrows[IllegalArgumentException] {
      manager.removeInactiveHandlers(fakeClock.tickerTime(), -1.second)
    }
  }

  test("Cleanup on empty manager is a no-op") {
    // Test plan: Verify that calling `removeInactiveHandlers` on a manager with no handlers is
    // a no-op by creating an empty manager, invoking cleanup, and asserting handler count is 0.
    val (manager, fakeClock): (SubscriberManager, FakeTypedClock) = createManager()

    manager.removeInactiveHandlers(fakeClock.tickerTime(), inactivityThreshold)
    assert(manager.forTest.handlerCount == 0, "Empty manager should remain empty after cleanup")
  }
}
