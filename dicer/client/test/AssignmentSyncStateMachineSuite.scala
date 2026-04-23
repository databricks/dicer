package com.databricks.dicer.client

import java.net.URI
import java.time.Instant
import java.util.Random

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

import io.grpc.Status
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContext,
  FakeTypedClock,
  SequentialExecutionContext,
  StateMachineDriver,
  StateMachineOutput,
  TestStateMachineDriver,
  TickerTime
}
import com.databricks.caching.util.TestUtils.{TestName, shamefullyAwaitForNonEventInAsyncTest}
import com.databricks.dicer.client.AssignmentSyncStateMachine.{DriverAction, Event}
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  ClerkData,
  ClientRequest,
  ClientResponse,
  ClientType,
  Generation,
  ProposedAssignment,
  Redirect,
  SyncAssignmentState,
  TestSliceUtils
}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.rpc.tls.TLSOptionsMigration
import com.databricks.rpc.testing.TestSslArguments
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class AssignmentSyncStateMachineSuite extends DatabricksTest with TestName {

  /** A type representing the assignment sync state machine driver for readability. */
  private type AssignmentSyncStateMachineDriver =
    StateMachineDriver[Event, DriverAction, AssignmentSyncStateMachine]

  /** SEC used for the AssignmentSyncStateMachine. */
  private val sec = FakeSequentialExecutionContext.create("AssignmentSyncStateMachineSuite")

  /**
   * A wrapper around an [[AssignmentSyncStateMachineDriver]] that records every action received by
   * the driver.
   */
  @ThreadSafe
  private class AssignmentSyncStateMachineDriverWrapper(
      sec: SequentialExecutionContext,
      config: SliceLookupConfig) {

    /** The driver for an [[AssignmentSyncStateMachine]]. */
    private val driver: AssignmentSyncStateMachineDriver = new AssignmentSyncStateMachineDriver(
      sec,
      new AssignmentSyncStateMachine(config, new Random, subscriberDebugName = "test-clerk"),
      recordAction
    )

    /** Received actions for the `driver`. */
    @GuardedBy("sec")
    private val receivedActions = ArrayBuffer[DriverAction]()

    /** Starts the `driver`. */
    def start(): Unit = sec.run { driver.start() }

    /** Supplies the given event to the `driver`. */
    def handleEvent(event: Event): Unit = sec.run { driver.handleEvent(event) }

    /** Invokes `onAdvance` on the state machine in the `driver`. */
    def onAdvance(tickerTime: TickerTime, instant: Instant): StateMachineOutput[DriverAction] =
      Await.result(
        sec.call { driver.forTest.getStateMachine.onAdvance(tickerTime, instant) },
        Duration.Inf
      )

    /** Returns the received actions for the `driver` */
    def getReceivedActions: Vector[DriverAction] =
      Await.result(sec.call { receivedActions.toVector }, Duration.Inf)

    /** Returns the number of received [[SendRequest]] actions. */
    def getNumSendRequests: Int = {
      Await.result(sec.call {
        receivedActions.count {
          case _: DriverAction.SendRequest => true
          case _ => false
        }
      }, Duration.Inf)
    }

    /** Returns whether the state machine is currently in backoff mode. */
    def isInBackoff: Boolean =
      Await.result(sec.call { driver.forTest.getStateMachine.forTest.isInBackoff }, Duration.Inf)

    /** Records all the actions received by the `driver`. */
    private def recordAction(action: DriverAction): Unit = {
      sec.assertCurrentContext()
      receivedActions += action
    }
  }

  /** The target to use for the current test, derived from the test name. */
  private def target: Target = Target(getSafeName)

  /**
   * Creates the client configuration for a AssignmentSyncStateMachine, with test SSL parameters.
   */
  private def createSliceLookupConfig(): SliceLookupConfig = {
    SliceLookupConfig(
      ClientType.Clerk,
      watchAddress = URI.create("fake-address"),
      tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
      target,
      clientIdOpt = Some(TEST_CLIENT_UUID),
      watchStubCacheTime = 10.seconds,
      watchFromDataPlane = false,
      enableRateLimiting = false
    )
  }

  test("StateMachine deadline exceeded") {
    // Test plan: Verify that if the AssignmentSyncStateMachine doesn't get a response by the
    // deadline, it will hedge and retry with backoff delay. Do this by first setting up a redirect,
    // verifying that a request is sent to the redirected address, and then simulate a timeout of
    // the redirected request. At the deadline there should be no immediate retry; after backoff
    // delay, we should retry using the default address. Then, if we later get back a successful
    // response to the original request, it will be incorporated into the state machine.

    val config: SliceLookupConfig = createSliceLookupConfig()
    val driver = new AssignmentSyncStateMachineDriverWrapper(sec, config)

    driver.start()

    var expectedNumActions = 1
    // Get a SendRequest action on driver start.
    val firstRequest: DriverAction.SendRequest =
      AssertionWaiter("Initial SendRequest action").await {
        val actions: Vector[DriverAction] = driver.getReceivedActions
        assert(actions.size == expectedNumActions)
        actions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }

    // Inject a response with a redirect. We will test later that after the request times out, we
    // send the next request to the default address.
    val uri = URI.create("fake-redirect")
    val redirect = Redirect(Some(uri))
    val response = ClientResponse(
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      config.watchRpcTimeout,
      redirect
    )
    driver.handleEvent(Event.ReadSuccess(None, firstRequest.opId, response))

    // The state machine should send a request to the redirected address.
    expectedNumActions += 1
    val redirectedRequest: DriverAction.SendRequest =
      AssertionWaiter("Second SendRequest action").await {
        val actions: Vector[DriverAction] = driver.getReceivedActions
        assert(actions.size == expectedNumActions)
        actions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    assert(redirectedRequest.addressOpt.contains(uri))
    assert(
      redirectedRequest.syncState ==
      SyncAssignmentState.KnownGeneration(Generation.EMPTY)
    )

    // The request deadline is `watchRpcTimeout`. Advancing the clock less
    // than that shouldn't trigger retry.
    sec.getClock.advanceBy(config.watchRpcTimeout - 100.millis)
    assert(driver.getReceivedActions.size == expectedNumActions)

    // Advancing to the deadline should clear outstanding state and enter backoff, but should not
    // send a new request immediately.
    assert(!driver.isInBackoff)
    sec.getClock.advanceBy(100.millis)
    AssertionWaiter("Backoff entered after deadline").await {
      assert(driver.isInBackoff)
    }
    assert(driver.getReceivedActions.size == expectedNumActions)

    // Advance enough for the backoff retry to be scheduled and emitted.
    sec.getClock.advanceBy(config.minRetryDelay * 2)
    expectedNumActions += 1
    val fallbackRequest: DriverAction.SendRequest =
      AssertionWaiter("Retry SendRequest action").await {
        val actions: Vector[DriverAction] = driver.getReceivedActions
        assert(actions.size == expectedNumActions)
        actions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    // The fallback request should go to the default address.
    assert(fallbackRequest.addressOpt.isEmpty)

    // Now, try to send a successful response to an old request. A successful response with
    // `KnownAssignment` with a newer generation, and a redirect with the same generation, should be
    // incorporated. The new assignment will trigger `DriverAction.UseAssignment`, but we shouldn't
    // try to send another request. When we send the next request though, the redirect should be
    // used.
    val asnGeneration: Generation = 2 ## 6
    val assignment = createAssignment(
      asnGeneration,
      AssignmentConsistencyMode.Affinity,
      Slice.FULL @@ asnGeneration -> Seq("pod0")
    )
    val response2 = ClientResponse(
      SyncAssignmentState.KnownAssignment(assignment),
      config.watchRpcTimeout,
      redirect
    )
    driver.handleEvent(Event.ReadSuccess(Some(uri), redirectedRequest.opId, response2))
    expectedNumActions += 1
    val useAssignment: DriverAction.UseAssignment =
      AssertionWaiter("UseAssignment action").await {
        val actions: Vector[DriverAction] = driver.getReceivedActions
        assert(actions.size == expectedNumActions)
        actions.last match {
          case useAssignmentAction: DriverAction.UseAssignment => useAssignmentAction
          case otherAction => fail(s"Expected UseAssignment action, but got $otherAction")
        }
      }
    assert(useAssignment.assignment == assignment)

    // Send failed responses: one with invalid, newer `opId`, the other with an older request. A
    // failed response that doesn't correspond to the last request should be ignored.
    driver.handleEvent(Event.ReadFailure(fallbackRequest.opId + 10, Status.DEADLINE_EXCEEDED))
    driver.handleEvent(Event.ReadFailure(redirectedRequest.opId, Status.DEADLINE_EXCEEDED))
    assert(driver.getReceivedActions.size == expectedNumActions)

    // Inject a response to the latest request. Verify that the new request that is sent uses
    // `asnGeneration` and `redirect.addressOpt`.
    driver.handleEvent(Event.ReadSuccess(None, fallbackRequest.opId, response))
    expectedNumActions += 1
    val finalRequest: DriverAction.SendRequest =
      AssertionWaiter("Final SendRequest action").await {
        val actions: Vector[DriverAction] = driver.getReceivedActions
        assert(actions.size == expectedNumActions)
        actions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    assert(
      finalRequest.syncState ==
      SyncAssignmentState.KnownGeneration(asnGeneration)
    )
    assert(finalRequest.addressOpt.contains(uri))
  }

  test("AssignmentSyncStateMachine Event.Cancel") {
    // Test plan: Verify that when the state machine receives a `Cancel` event, it stops generating
    // any actions, as follows:
    // - Use the same SEC to create another `activeDriver` which will not be cancelled, start both
    //   drivers, and wait for both to send the initial watch requests.
    // - Cancel the `driver`.
    // - Trigger a `ReadSuccess` with an empty generation and a redirect for `driver` and then
    //   `activeDriver`. Verify that only the latter generates a new redirect request.
    // - Advance the clock to the redirected request deadline. Verify that only the active driver
    //   enters backoff and does not retry immediately.
    // - Advance the clock past backoff delay. Verify that only the active driver generates a retry
    //   request.
    // - Trigger a `ReadSuccess` with a valid assignment. Verify that only the active driver
    //   generates a `UseAssignment` action.
    // - Trigger a `ReadFailure` and invoke `onAdvance` explicitly after backoff. Verify that
    //   only the active driver generates a retry request.

    val config: SliceLookupConfig = createSliceLookupConfig()
    val driver = new AssignmentSyncStateMachineDriverWrapper(sec, config)

    // Setup: Create another driver which will not be cancelled and will be always active. All its
    // received actions are captured in `receivedActionsForActiveDriver`.
    val activeDriver = new AssignmentSyncStateMachineDriverWrapper(sec, config)

    // Setup: Create a seq to support iterating over both drivers. Since they share the same SEC,
    // `driver` is placed before `activeDriver` to ensure that when the active driver completes an
    // action, the `driver` has already completed the corresponding action. So to test a no-op for
    // the stopped `driver`, we don’t need to call Thread.sleep. (We just need to wait for
    // `activeDriver` to receive the corresponding action.)
    val drivers: Seq[AssignmentSyncStateMachineDriverWrapper] = Seq(driver, activeDriver)

    // Setup: Start 2 drivers, and wait for the first watch requests from both.
    for (driver: AssignmentSyncStateMachineDriverWrapper <- drivers) {
      driver.start()
    }
    val firstRequest: DriverAction.SendRequest =
      AssertionWaiter("Initial SendRequest action").await {
        val receivedActions: Vector[DriverAction] = driver.getReceivedActions
        val receivedActionsForActiveDriver: Vector[DriverAction] = activeDriver.getReceivedActions
        assert(receivedActions.size == 1)
        assert(receivedActionsForActiveDriver.size == 1)
        // The initial watch request should be the same for both drivers, so we randomly pick one.
        receivedActions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }

    // Setup: Cancel the `driver`.
    driver.handleEvent(Event.Cancel)

    // Setup: For both drivers, trigger a `ReadSuccess` with a response with an empty assignment and
    // a redirect.
    val redirect = Redirect(Some(URI.create("fake-redirect")))
    val response = ClientResponse(
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      config.watchRpcTimeout,
      redirect
    )
    for (driver: AssignmentSyncStateMachineDriverWrapper <- drivers) {
      driver.handleEvent(Event.ReadSuccess(None, firstRequest.opId, response))
    }

    // Verify: `driver` should not send any new requests while `activeDriver` should send a second
    // request to the redirected address.
    val redirectedRequest: DriverAction.SendRequest =
      AssertionWaiter("Second SendRequest action").await {
        val receivedActionsForActiveDriver: Vector[DriverAction] = activeDriver.getReceivedActions
        assert(receivedActionsForActiveDriver.size == 2)
        receivedActionsForActiveDriver.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    assert(driver.getReceivedActions == Seq(firstRequest))

    // Setup: Advance to the request deadline. This should enter backoff for the active driver,
    // but should not send a retry immediately.
    assert(!driver.isInBackoff)
    assert(!activeDriver.isInBackoff)
    sec.getClock.advanceBy(config.watchRpcTimeout)
    AssertionWaiter("Active driver entered backoff after deadline").await {
      assert(!driver.isInBackoff)
      assert(activeDriver.isInBackoff)
    }
    assert(driver.getReceivedActions == Seq(firstRequest))
    assert(activeDriver.getReceivedActions.size == 2)

    // Setup: Advance past the backoff delay so the active driver's retry is emitted.
    sec.getClock.advanceBy(config.minRetryDelay * 2)

    // Verify: `driver` should not send any new requests while `activeDriver` should send a retry
    // request.
    val retryRequest: DriverAction.SendRequest =
      AssertionWaiter("Third SendRequest action").await {
        val receivedActionsForActiveDriver: Vector[DriverAction] = activeDriver.getReceivedActions
        assert(receivedActionsForActiveDriver.size == 3)
        receivedActionsForActiveDriver.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    assert(driver.getReceivedActions == Seq(firstRequest))

    // Setup: For both drivers, trigger a `ReadSuccess` with a response which has a valid
    // assignment.
    val asnGeneration: Generation = 2 ## 6
    val assignment = createAssignment(
      asnGeneration,
      AssignmentConsistencyMode.Affinity,
      Slice.FULL @@ asnGeneration -> Seq("pod0")
    )
    val response2 = ClientResponse(
      SyncAssignmentState.KnownAssignment(assignment),
      config.watchRpcTimeout,
      Redirect.EMPTY
    )
    for (driver: AssignmentSyncStateMachineDriverWrapper <- drivers) {
      driver.handleEvent(Event.ReadSuccess(None, redirectedRequest.opId, response2))
    }

    // Verify: `driver` should not generate any actions while `activeDriver` should generate a
    // `UseAssignment` action.
    AssertionWaiter("UseAssignment action").await {
      val receivedActionsForActiveDriver: Vector[DriverAction] = activeDriver.getReceivedActions
      assert(receivedActionsForActiveDriver.size == 4)
      assert(receivedActionsForActiveDriver.last.isInstanceOf[DriverAction.UseAssignment])
    }
    assert(driver.getReceivedActions == Seq(firstRequest))

    // Setup: For both drivers, trigger a `ReadFailure`.
    for (driver: AssignmentSyncStateMachineDriverWrapper <- drivers) {
      driver.handleEvent(
        Event.ReadFailure(retryRequest.opId, Status.DEADLINE_EXCEEDED)
      )
    }

    // Verify: `driver` should not send any more requests while `activeDriver` should send a retry
    // request, if we explicitly call `onAdvance` after the backoff period has elapsed.
    assert(
      driver
        .onAdvance(sec.getClock.tickerTime() + 1.hour, sec.getClock.instant().plusSeconds(60 * 60))
        .actions
        .isEmpty
    )
    assert(
      activeDriver
        .onAdvance(sec.getClock.tickerTime() + 1.hour, sec.getClock.instant().plusSeconds(60 * 60))
        .actions
        .head
        .isInstanceOf[DriverAction.SendRequest]
    )
  }

  test("Request containing assignment for different target ignored") {
    // Test plan: verify that a new sync state machine does not erroneously incorporate the
    // assignment for a target which it does not own, even if it has no assignment itself.

    // Use fixed timestamps generated by a fake clock to avoid non-deterministic actions requested
    // by the state machine (will hedge requests if too much time elapses before a response).
    val uri1: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val target = Target.createKubernetesTarget(uri1, getSafeName)
    val clock = new FakeTypedClock()
    val tickerTime: TickerTime = clock.tickerTime()
    val instant: Instant = clock.instant()

    val config: SliceLookupConfig = createSliceLookupConfig()
    val testDriver: TestStateMachineDriver[Event, DriverAction] =
      new TestStateMachineDriver(
        new AssignmentSyncStateMachine(config, new Random(), "test-clerk")
      )

    // Send the initial `onAdvance` call to initialize the state machine.
    testDriver.onAdvance(tickerTime, instant)

    // Send a request containing an assignment for a different target name. Despite not having any
    // assignment, the state machine should not request that the driver incorporate the
    // assignment.
    val fatallyMismatchedTarget = Target(getSuffixedSafeName("other"))
    val assignment1: Assignment = ProposedAssignment(
      predecessorOpt = None,
      TestSliceUtils.createProposal(
        ("" -- ∞) -> Seq("Pod2")
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      3 ## 42
    )

    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.WatchRequest(
            ClientRequest(
              fatallyMismatchedTarget,
              SyncAssignmentState.KnownAssignment(assignment1),
              "another-client",
              5.seconds,
              ClerkData,
              supportsSerializedAssignment = true
            )
          )
        )
        .actions
        .isEmpty
    )

    // On the other hand, sending the same assignment with a matching target should be incorporated.
    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.WatchRequest(
            ClientRequest(
              target,
              SyncAssignmentState.KnownAssignment(assignment1),
              "another-client",
              5.seconds,
              ClerkData,
              supportsSerializedAssignment = true
            )
          )
        )
        .actions == Seq(DriverAction.UseAssignment(assignment1))
    )

    // Sending a newer assignment for a target with the same name but different cluster a (non-fatal
    // mismatch) should also be incorporated.
    val assignment2: Assignment = ProposedAssignment(
      predecessorOpt = None,
      TestSliceUtils.createProposal(
        ("" -- ∞) -> Seq("Pod3")
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      3 ## 43
    )

    val uri2: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
    val nonFatalMismatchedTarget = Target.createKubernetesTarget(uri2, getSafeName)
    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.WatchRequest(
            ClientRequest(
              nonFatalMismatchedTarget,
              SyncAssignmentState.KnownAssignment(assignment2),
              "another-client",
              5.seconds,
              ClerkData,
              supportsSerializedAssignment = true
            )
          )
        )
        .actions == Seq(DriverAction.UseAssignment(assignment2))
    )
  }

  test("Watch requests rate limit") {
    // Test plan: Verify that the watch request rate limiting allows a burst of 2 requests whenever
    // the bucket is full, then refills at a rate of 1 token per second:
    // - At time 0s: Send 2 rapid requests (exhausts the initial 2 tokens).
    // - Verify that a 3rd request at time 0s is rate-limited.
    // - At time 0.5s: Only 0.5 tokens have refilled, so a 3rd request is still rate-limited.
    // - At time 1s: 1 token has refilled, so a 3rd request is allowed.
    // - Verify that a 4th request immediately after the 3rd is rate-limited.
    // - At time 2s: Another token has refilled, so a 4th request is allowed.
    // - At time 4s: After 2 seconds of no activity, verify that a burst of 2 requests is allowed.

    // Setup: Create a config with rate limiting enabled, a state machine, and a
    // `TestStateMachineDriver`.
    val config: SliceLookupConfig = SliceLookupConfig(
      ClientType.Clerk,
      watchAddress = URI.create("fake-address"),
      tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
      target,
      clientIdOpt = Some(TEST_CLIENT_UUID),
      watchStubCacheTime = 10.seconds,
      watchFromDataPlane = false,
      enableRateLimiting = true
    )
    val testDriver: TestStateMachineDriver[Event, DriverAction] =
      new TestStateMachineDriver(
        new AssignmentSyncStateMachine(config, new Random(), subscriberDebugName = "test-clerk")
      )
    val tickerTime: TickerTime = sec.getClock.tickerTime()
    val instant: Instant = sec.getClock.instant()

    // Setup: Create an assignment to use in responses.
    val asnGeneration: Generation = 1 ## 0
    val assignment: Assignment = createAssignment(
      asnGeneration,
      AssignmentConsistencyMode.Affinity,
      Slice.FULL @@ asnGeneration -> Seq("pod1")
    )
    // Setup: First response includes the full assignment.
    val firstResponse: ClientResponse = ClientResponse(
      SyncAssignmentState.KnownAssignment(assignment),
      config.watchRpcTimeout,
      Redirect(None)
    )
    // Setup: Subsequent responses only include the generation (no assignment changes).
    val subsequentResponse: ClientResponse = ClientResponse(
      SyncAssignmentState.KnownGeneration(assignment.generation),
      config.watchRpcTimeout,
      Redirect(None)
    )

    // Verify: Send the initial `onAdvance` call to initialize the state machine, and verify that
    // state machine sends the first request at time 0.
    assert(
      testDriver.onAdvance(tickerTime, instant).actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 1,
          syncState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: The state machine sends the second requests after receiving a successful response at
    // time 0.
    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.ReadSuccess(addressOpt = None, opId = 1, response = firstResponse)
        )
        .actions ==
      Seq(
        DriverAction.UseAssignment(assignment),
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 2,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: After receiving the second successful response, the state machine doesn't send
    // another request immediately because the bucket is empty (2 tokens exhausted).
    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.ReadSuccess(addressOpt = None, opId = 2, response = subsequentResponse)
        )
        .actions
        .isEmpty
    )

    // Setup: Advance the clock by 0.5s to refill 0.5 tokens.
    sec.advanceBySync(500.millis)
    // Verify: At 0.5s, the bucket has only 0.5 tokens (need 1 token), so no 3rd request yet.
    assert(
      testDriver
        .onAdvance(
          sec.getClock.tickerTime(),
          sec.getClock.instant()
        )
        .actions
        .isEmpty
    )

    // Setup: Advance the clock by another 0.5s to refill 1 full token total.
    sec.advanceBySync(500.millis)
    // Verify: At 1s total, the bucket has 1 token, so a 3rd request can be sent.
    assert(
      testDriver
        .onAdvance(
          sec.getClock.tickerTime(),
          sec.getClock.instant()
        )
        .actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 3,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: After the 3rd request, receiving a response should not trigger a 4th request
    // immediately (bucket is empty again).
    assert(
      testDriver
        .onEvent(
          sec.getClock.tickerTime(),
          sec.getClock.instant(),
          Event.ReadSuccess(addressOpt = None, opId = 3, response = subsequentResponse)
        )
        .actions
        .isEmpty
    )

    // Setup: Advance the clock by 1s to refill 1 token.
    sec.advanceBySync(1.second)
    // Verify: At time 2s, the bucket has 1 token again, so a 4th request can be sent.
    assert(
      testDriver
        .onAdvance(
          sec.getClock.tickerTime(),
          sec.getClock.instant()
        )
        .actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 4,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: After the 4th request, receiving a response should not trigger a 5th request
    // immediately (bucket is empty again).
    assert(
      testDriver
        .onEvent(
          sec.getClock.tickerTime(),
          sec.getClock.instant(),
          Event.ReadSuccess(addressOpt = None, opId = 4, response = subsequentResponse)
        )
        .actions
        .isEmpty
    )

    // Setup: Advance the clock by 2 seconds to refill 2 tokens (bucket is now full).
    sec.advanceBySync(2.seconds)

    // Verify: At 4s total, the bucket has refilled to capacity, so we can send request 5.
    assert(
      testDriver
        .onAdvance(
          sec.getClock.tickerTime(),
          sec.getClock.instant()
        )
        .actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 5,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: Immediately after request 5, respond and verify we can send request 6.
    assert(
      testDriver
        .onEvent(
          sec.getClock.tickerTime(),
          sec.getClock.instant(),
          Event.ReadSuccess(addressOpt = None, opId = 5, response = subsequentResponse)
        )
        .actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 6,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: After exhausting the burst again, no 7th request should be sent immediately.
    assert(
      testDriver
        .onEvent(
          sec.getClock.tickerTime(),
          sec.getClock.instant(),
          Event.ReadSuccess(addressOpt = None, opId = 6, response = subsequentResponse)
        )
        .actions
        .isEmpty
    )
  }

  test("Next attempt is scheduled correctly if rate-limited") {
    // Test plan: Verify that when the TokenBucket is exhausted, the state machine properly
    // schedules the next attempt using timeWhenRefilled():
    // - At time 0s: Send 2 rapid requests (exhausts the initial 2 tokens).
    // - After the 2nd response: Verify no 3rd request is sent immediately.
    // - Verify that the state machine schedules the next attempt correctly and sends request 3
    //   after 1 second (when 1 token has refilled at rate=1 token/sec).

    // Setup: Create a config with rate limiting enabled, a state machine, and an
    // `AssignmentSyncStateMachineDriver`.
    val config: SliceLookupConfig = SliceLookupConfig(
      ClientType.Clerk,
      watchAddress = URI.create("fake-address"),
      tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
      target,
      clientIdOpt = Some(TEST_CLIENT_UUID),
      watchStubCacheTime = 10.seconds,
      watchFromDataPlane = false,
      enableRateLimiting = true
    )
    val driver = new AssignmentSyncStateMachineDriverWrapper(sec, config)

    // Setup: Create an assignment to use in responses.
    val asnGeneration: Generation = 1 ## 0
    val assignment: Assignment = createAssignment(
      asnGeneration,
      AssignmentConsistencyMode.Affinity,
      Slice.FULL @@ asnGeneration -> Seq("pod1")
    )
    val response: ClientResponse = ClientResponse(
      SyncAssignmentState.KnownAssignment(assignment),
      config.watchRpcTimeout,
      Redirect(None)
    )

    // Setup: Start the driver and wait for the first request.
    driver.start()
    val req: DriverAction.SendRequest =
      AssertionWaiter("Wait for the SendRequest").await {
        assert(driver.getNumSendRequests == 1)
        driver.getReceivedActions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }
    // Setup: Respond immediately to trigger a 2nd request.
    driver.handleEvent(Event.ReadSuccess(None, req.opId, response))
    // Setup: Wait for the second request.
    val lastRequest: DriverAction.SendRequest =
      AssertionWaiter("Wait for the SendRequest").await {
        assert(driver.getNumSendRequests == 2)
        driver.getReceivedActions.last match {
          case sendRequestAction: DriverAction.SendRequest => sendRequestAction
          case otherAction => fail(s"Expected SendRequest action, but got $otherAction")
        }
      }

    // Setup: Respond to the 2nd request immediately to trigger an attempt for 3rd request. The
    // bucket should be empty, so no 3rd request should be sent yet.
    driver.handleEvent(Event.ReadSuccess(None, lastRequest.opId, response))

    // Verify: The attempt for 3rd request should fail due to rate limiting. Note that sleeping is
    // highly discouraged in tests, but here we have no other way because we are testing a no-op (no
    // new SendRequest action is generated).
    shamefullyAwaitForNonEventInAsyncTest()
    assert(driver.getNumSendRequests == 2)

    // Setup: Advance the clock by 1s (enough time for 1 token to refill at rate 1 token/sec).
    sec.advanceBySync(1.second)

    // Verify: After 1s, the bucket has refilled 1 token, so the 3rd SendRequest should be
    // generated.
    AssertionWaiter("Wait for the SendRequest").await {
      assert(driver.getNumSendRequests == 3)
    }
  }

  test("Watch requests not rate-limited when disabled") {
    // Test plan: Verify that when rate limiting is disabled, multiple requests can be sent rapidly
    // without any delays:
    // - Create a config with enableRateLimiting = false.
    // - Try to trigger 1k rapid requests (more than the normal burst capacity of 2).
    // - Verify all 1k requests are sent immediately without rate limiting delays.

    // Setup: Create a config with rate limiting disabled, a state machine, and a
    // `TestStateMachineDriver`.
    val config: SliceLookupConfig = SliceLookupConfig(
      ClientType.Clerk,
      watchAddress = URI.create("fake-address"),
      tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
      target,
      clientIdOpt = Some(TEST_CLIENT_UUID),
      watchStubCacheTime = 10.seconds,
      watchFromDataPlane = false,
      enableRateLimiting = false
    )
    val testDriver: TestStateMachineDriver[Event, DriverAction] =
      new TestStateMachineDriver(
        new AssignmentSyncStateMachine(config, new Random(), subscriberDebugName = "test-clerk")
      )
    val tickerTime: TickerTime = sec.getClock.tickerTime()
    val instant: Instant = sec.getClock.instant()

    // Setup: Create an assignment to use in responses.
    val asnGeneration: Generation = 1 ## 0
    val assignment: Assignment = createAssignment(
      asnGeneration,
      AssignmentConsistencyMode.Affinity,
      Slice.FULL @@ asnGeneration -> Seq("pod1")
    )
    val response: ClientResponse = ClientResponse(
      SyncAssignmentState.KnownAssignment(assignment),
      config.watchRpcTimeout,
      Redirect(None)
    )

    // Verify: Send the initial `onAdvance` call to initialize the state machine, and verify that
    // state machine sends the first request at time 0.
    assert(
      testDriver.onAdvance(tickerTime, instant).actions == Seq(
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 1,
          syncState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: Respond to the first request and verify UseAssignment + SendRequest for request 2.
    assert(
      testDriver
        .onEvent(
          tickerTime,
          instant,
          Event.ReadSuccess(addressOpt = None, opId = 1, response = response)
        )
        .actions ==
      Seq(
        DriverAction.UseAssignment(assignment),
        DriverAction.SendRequest(
          addressOpt = None,
          opId = 2,
          syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
          watchRpcTimeout = config.watchRpcTimeout
        )
      )
    )

    // Verify: Send 998 more rapid requests (1k total) and verify they are all sent immediately
    // without any rate limiting delays. This exceeds the normal burst capacity of 2 that would
    // apply if rate limiting were enabled. After the first UseAssignment, subsequent responses
    // with the same assignment won't produce UseAssignment actions.
    for (opId <- 3 to 1000) {
      assert(
        testDriver
          .onEvent(
            tickerTime,
            instant,
            Event.ReadSuccess(addressOpt = None, opId = opId - 1, response = response)
          )
          .actions ==
        Seq(
          DriverAction.SendRequest(
            addressOpt = None,
            opId = opId,
            syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
            watchRpcTimeout = config.watchRpcTimeout
          )
        )
      )
    }
  }
}
