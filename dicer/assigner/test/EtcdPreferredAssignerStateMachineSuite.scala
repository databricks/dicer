package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeTypedClock,
  MetricUtils,
  StateMachineOutput,
  TickerTime,
  TypedClock
}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStateMachine.{DriverAction, Event}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.assigner.PreferredAssignerMetrics.ValueSource
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.testing.DatabricksTest

/** Unit tests for the PreferredAssignerStateMachine. */
class EtcdPreferredAssignerStateMachineSuite extends DatabricksTest {

  /** An arbitrary store incarnation for tests. */
  private val STORE_INCARNATION: Incarnation = Incarnation(42)

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** AssignerInfo for the state machine's assigner. */
  private val selfAssignerInfo = AssignerInfo(
    UUID.fromString("11111111-1234-5678-abcd-68454e98b111"),
    new URI("http://this-assigner:1234")
  )

  /** AssignerInfo for some other assigner. */
  private val otherAssignerInfo = AssignerInfo(
    UUID.fromString("22222222-1234-5678-abcd-68454e98b222"),
    new URI("http://other-assigner:4567")
  )

  /** AssignerInfo for a third assigner. */
  private val thirdAssignerInfo = AssignerInfo(
    UUID.fromString("33333333-1234-5678-abcd-68454e98b222"),
    new URI("http://other-assigner:5678")
  )

  private val clock = new FakeTypedClock

  /**
   * The state machine config for tests. The preferred assigner is considered to be unhealthy once
   * three heartbeats in a row fail to respond in time.
   */
  private val config = EtcdPreferredAssignerDriver.Config(
    heartbeatInterval = 5.seconds,
    writeRetryInterval = 10.seconds,
    heartbeatFailureThreshold = 3
  )

  /** Creates a state machine using a default configuration. */
  private def createStateMachine(): EtcdPreferredAssignerStateMachine = {
    new EtcdPreferredAssignerStateMachine(selfAssignerInfo, STORE_INCARNATION, config)
  }

  test("State machine heartbeats as standby while preferred assigners are healthy") {
    // Test plan: Verify that the state machine waits up to for the initialPreferredAssignerTimeout
    // to learn about the preferred assigner, that it continues heartbeating against the preferred
    // assigner while it is healthy (even with intermittent failures), and that it heartbeats
    // against a new preferred assigner when the preferred assigner changes.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()
    var opId = 0L

    // The initial onAdvance should schedule an `onAdvance` callback after the initial preferred
    // assigner delay to check for timeout.
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    val otherPreferredAssignerConfig = PreferredAssignerConfig.create(
      otherPreferredAssigner,
      selfAssignerInfo
    )
    val expectedPreferredAssignerDeadline: TickerTime =
      testEpochTime + config.initialPreferredAssignerTimeout
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedPreferredAssignerDeadline, Seq.empty)
    )

    // Spurious wakeups should do nothing.
    clock.advanceBy(1.second)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedPreferredAssignerDeadline, Seq.empty)
    )

    // If the state machine discovers a preferred assigner, it should output a new assigner config
    // and send an initial heartbeat to that assigner and schedules an onAdvance call after the
    // heartbeat interval.
    var heartbeatTime: TickerTime = clock.tickerTime() + config.heartbeatInterval

    opId += 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner)
      ) ==
      StateMachineOutput(
        heartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner))
        )
      )
    )
    awaitPreferredAssignerGenerationMetric(otherPreferredAssigner.generation)

    clock.advanceBy(1.second)
    triggerHeartbeatSuccessEvent(stateMachine, opId, otherPreferredAssigner, heartbeatTime)

    // The state machine should continue to heartbeat against the preferred assigner while it
    // is healthy, even with varying patterns of sporadic failures.
    def verifyContinuedHeartbeatsAgainstAssigner(
        preferredAssignerValue: PreferredAssignerValue.SomeAssigner,
        shouldSucceedSeq: Seq[Boolean]): Unit = {
      for (shouldSucceed <- shouldSucceedSeq) {
        // Advance by the heartbeat interval and verify that the state machine sent another
        // heartbeat.
        clock.advanceBy(config.heartbeatInterval)
        heartbeatTime += config.heartbeatInterval
        opId += 1
        assert(
          stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
          StateMachineOutput(
            heartbeatTime,
            Seq(DriverAction.SendHeartbeat(HeartbeatRequest(opId, preferredAssignerValue)))
          )
        )
        if (shouldSucceed) {
          clock.advanceBy(200.milliseconds)
          triggerHeartbeatSuccessEvent(stateMachine, opId, otherPreferredAssigner, heartbeatTime)
        }
      }
    }
    verifyContinuedHeartbeatsAgainstAssigner(
      otherPreferredAssigner,
      Seq(
        Seq(true, true, true),
        Seq(true, false, true),
        Seq(false, false, true),
        Seq(false, true, false)
      ).flatten
    )

    // Inform the state machine of a different preferred assigner and make sure it now acts as
    // standby for that assigner.
    val thirdPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(thirdAssignerInfo, generation(clock))
    val thirdPreferredAssignerConfig = PreferredAssignerConfig.create(
      thirdPreferredAssigner,
      selfAssignerInfo
    )
    heartbeatTime = clock.tickerTime() + config.heartbeatInterval
    opId += 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(thirdPreferredAssigner)
      ) == StateMachineOutput(
        heartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(thirdPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, thirdPreferredAssigner))
        )
      )
    )
    clock.advanceBy(1.second)
    triggerHeartbeatSuccessEvent(stateMachine, opId, thirdPreferredAssigner, heartbeatTime)
    verifyContinuedHeartbeatsAgainstAssigner(thirdPreferredAssigner, Seq(false, false, true))
    awaitPreferredAssignerGenerationMetric(thirdPreferredAssigner.generation)
  }

  test("State machine writes itself as preferred if initial value takes too long") {
    // Test plan: Verify that when the state machine does not learn about a preferred assigner
    // within the initial preferred assigner delay, it writes itself as preferred and retries the
    // write until it learns that either it or some either assigner is preferred.
    val stateMachine = createStateMachine()

    // Do the initial onAdvance call at startup.
    val testEpochTime = clock.tickerTime()
    val expectedPreferredAssignerDeadline: TickerTime =
      testEpochTime + config.initialPreferredAssignerTimeout
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedPreferredAssignerDeadline, Seq.empty)
    )

    // Advance to the initialPreferredAssignerTimeout so that the state machine gives up on waiting
    // for the preferred assigner value. Verify that the state machine tries to write itself as
    // preferred assigner and retries every write interval until it learns about a preferred
    // assigner.
    clock.advanceBy(config.initialPreferredAssignerTimeout + 1.second)
    var expectedNextTickerTime = clock.tickerTime() + config.writeRetryInterval
    for (_ <- 0 until 2) {
      assert(
        stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
        StateMachineOutput(
          expectedNextTickerTime,
          Seq(
            DriverAction.Write(
              clock.tickerTime(),
              PreferredAssignerProposal(
                predecessorGenerationOpt = None,
                newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
              )
            )
          )
        )
      )
      clock.advanceBy(config.writeRetryInterval)
      expectedNextTickerTime += config.writeRetryInterval
    }

    // Now pretend that the write has succeeded and verify that the state machine is now acting
    // as primary without heartbeating.
    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))
    val selfPreferredAssignerConfig = PreferredAssignerConfig.create(
      selfPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WriteResultReceived(
          clock.tickerTime(),
          Success(WriteResult.Committed(selfPreferredAssigner))
        )
      ) == StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(selfPreferredAssignerConfig))
      )
    )
    awaitPreferredAssignerGenerationMetric(selfPreferredAssigner.generation)
  }

  test("State machines tries to take over if preferred assigner becomes unhealthy") {
    // Test plan: Verify that the state machine attempts to take over as preferred if it is acting
    // as a standby for an assigner becomes unhealthy and that it continues to retry if its writes
    // to become preferred assigner fail and the other assigner remains unhealthy. Verify that if a
    // different assigner becomes preferred that the state machine acts as standby and now
    // heartbeats against that assigner. Finally, verify that when the third assigner in turn
    // becomes unhealthy, the state machine takes over and acts as preferred assigner when informed
    // its write has successfully completed.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()
    var opId = 0L
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    val otherPreferredAssignerConfig = PreferredAssignerConfig.create(
      otherPreferredAssigner,
      selfAssignerInfo
    )

    val expectedStartupDeadline: TickerTime = testEpochTime + config.initialPreferredAssignerTimeout
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedStartupDeadline, Seq.empty)
    )

    // Verify that the state machine says to act as standby for that assigner and sends a heartbeat
    // to it.
    var nextHeartbeatTime = clock.tickerTime() + config.heartbeatInterval
    opId += 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner)
      ) ==
      StateMachineOutput(
        nextHeartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner))
        )
      )
    )

    // Simulate a successful heartbeat response from that preferred assigner.
    clock.advanceBy(1.second)
    triggerHeartbeatSuccessEvent(stateMachine, opId, otherPreferredAssigner, nextHeartbeatTime)

    // Now advance time until heartbeatFailureThreshold heartbeats have been sent and failed because
    // they never respond. Send late responses from past heartbeats to make sure they are not
    // incorrectly counted as successes.
    def assertStateMachineHeartbeatsAfterInterval(
        interval: FiniteDuration,
        preferredAssignerValue: PreferredAssignerValue.SomeAssigner): Unit = {
      clock.advanceBy(interval)
      nextHeartbeatTime += interval
      opId += 1
      assert(
        stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
        StateMachineOutput(
          nextHeartbeatTime,
          Seq(DriverAction.SendHeartbeat(HeartbeatRequest(opId, preferredAssignerValue)))
        )
      )
    }

    val initialFailureCount = getHeartbeatFailureCount

    for (_ <- 0 until config.heartbeatFailureThreshold) {
      // Advance by the heartbeat interval and verify that the state machine sent another heartbeat.
      assertStateMachineHeartbeatsAfterInterval(config.heartbeatInterval, otherPreferredAssigner)
    }

    // Advance time and allow the final heartbeat to time out. The state machine should then
    // attempt to take over as preferred assigner but also continue to heartbeat against the
    // preferred assigner in case it becomes healthy again and the write doesn't succeed.
    def assertStateMachineTakesOverAfterFailingHeartbeat(
        predecessor: PreferredAssignerValue.SomeAssigner): Unit = {
      nextHeartbeatTime += config.heartbeatInterval
      opId += 1
      clock.advanceBy(config.heartbeatInterval)
      assert(
        stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
        StateMachineOutput(
          nextHeartbeatTime,
          Seq(
            DriverAction.SendHeartbeat(HeartbeatRequest(opId, predecessor)),
            DriverAction.Write(
              clock.tickerTime(),
              PreferredAssignerProposal(
                predecessorGenerationOpt = Some(predecessor.generation),
                newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
              )
            )
          )
        )
      )
    }
    assertStateMachineTakesOverAfterFailingHeartbeat(otherPreferredAssigner)

    // Verify that the heartbeat failure count metric was updated.
    AssertionWaiter("Wait for heartbeat failure count to increment").await {
      assert(getHeartbeatFailureCount == initialFailureCount + config.heartbeatFailureThreshold)
    }

    // Make sure that the write is retried if the preferred assigner doesn't change.
    // (We need an extra intermediate heartbeat to trigger the write because the write retry
    // interval in this test is equal to two heartbeats.)
    assertStateMachineHeartbeatsAfterInterval(config.heartbeatInterval, otherPreferredAssigner)
    assertStateMachineTakesOverAfterFailingHeartbeat(otherPreferredAssigner)

    clock.advanceBy(1.second)
    val thirdPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(thirdAssignerInfo, generation(clock))
    val thirdPreferredAssignerConfig = PreferredAssignerConfig.create(
      thirdPreferredAssigner,
      selfAssignerInfo
    )
    nextHeartbeatTime = clock.tickerTime() + config.heartbeatInterval
    opId += 1

    // Send an event indicating that a different assigner took over. Verify that we send a
    // heartbeat, but don't respond with success.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(thirdPreferredAssigner)
      ) ==
      StateMachineOutput(
        nextHeartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(thirdPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, thirdPreferredAssigner))
        )
      )
    )

    // Send two more failing heartbeats to the third assigner without success and verify that
    // the state machine attempts to take over after the third consecutive failing heartbeat.
    assertStateMachineHeartbeatsAfterInterval(config.heartbeatInterval, thirdPreferredAssigner)
    assertStateMachineHeartbeatsAfterInterval(config.heartbeatInterval, thirdPreferredAssigner)
    assertStateMachineTakesOverAfterFailingHeartbeat(thirdPreferredAssigner)

    // Now pretend that the write has succeeded and verify that the state machine is now acting
    // as primary without heartbeating.
    clock.advanceBy(1.second)
    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))
    val selfPreferredAssignerConfig = PreferredAssignerConfig.create(
      selfPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WriteResultReceived(
          clock.tickerTime(),
          Success(WriteResult.Committed(selfPreferredAssigner))
        )
      ) == StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(selfPreferredAssignerConfig))
      )
    )

    // Spurious wakeup while this assigner is preferred does nothing.
    clock.advanceBy(1.hour)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
  }

  test("State machines tries to become preferred if preferred assigner becomes empty") {
    // Test plan: Verify that the state machine attempts to write its assigner as preferred if
    // the preferred assigner value becomes empty.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    val otherPreferredAssignerConfig = PreferredAssignerConfig.create(
      otherPreferredAssigner,
      selfAssignerInfo
    )

    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Verify that the state machine says to act as standby for that assigner and sends a heartbeat
    // to it.
    val heartbeatTime = clock.tickerTime() + config.heartbeatInterval
    val opId = 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner)
      ) ==
      StateMachineOutput(
        heartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner))
        )
      )
    )

    // Now tell the state machine that the preferred assigner is empty and verify the write.
    clock.advanceBy(1.second)
    val emptyPreferredAssigner = PreferredAssignerValue.NoAssigner(generation(clock))
    val emptyPreferredAssignerConfig = PreferredAssignerConfig.create(
      emptyPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(emptyPreferredAssigner)
      ) ==
      StateMachineOutput(
        clock.tickerTime() + config.writeRetryInterval,
        Seq(
          DriverAction.UsePreferredAssignerConfig(emptyPreferredAssignerConfig),
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = Some(emptyPreferredAssigner.generation),
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )
    awaitPreferredAssignerGenerationMetric(emptyPreferredAssigner.generation)
  }

  /**
   * Drives `config.heartbeatFailureThreshold - 1` `onAdvance` calls forward, advancing the
   * clock by `heartbeatInterval` between each. Each call enqueues a new heartbeat which (in
   * `withNewHeartbeatRequest`) marks the previous in-flight one as failed; on return, the
   * state machine's heartbeat-failure count is at `threshold - 1`, primed to issue a takeover
   * write on the next `onAdvance` past the heartbeat interval. Encapsulates both the loop and
   * the caller's `nextHeartbeatTime` / `opId` bookkeeping so test bodies don't re-derive the
   * threshold-1 math.
   *
   * @return the updated `(nextHeartbeatTime, opId)` pair after the drive.
   */
  private def driveHeartbeatFailuresPrimingTakeover(
      stateMachine: EtcdPreferredAssignerStateMachine,
      nextHeartbeatTime: TickerTime,
      opId: Long): (TickerTime, Long) = {
    val iterations: Int = config.heartbeatFailureThreshold - 1
    for (_ <- 0 until iterations) {
      clock.advanceBy(config.heartbeatInterval)
      stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    }
    (nextHeartbeatTime + config.heartbeatInterval * iterations, opId + iterations)
  }

  /**
   * Advances past the initial-preferred-assigner timeout and asserts that the state machine's
   * resulting `onAdvance` emits a single `DriverAction.Write` proposing `expectedAssignerInfo`.
   *
   * The asserted write carries `predecessorGenerationOpt = None`, so this helper is only valid
   * for tests where the state machine has not yet observed any predecessor — i.e. the
   * `StandbyWithoutPreferred`-from-`Startup` path. The asserted next-advance time is one
   * `writeRetryInterval` after the write.
   *
   * Note this helper performs an assertion, not just a state advancement: a mismatch on the
   * write's contents will fail the calling test directly.
   */
  private def driveStartupToInitialWrite(
      stateMachine: EtcdPreferredAssignerStateMachine,
      expectedAssignerInfo: AssignerInfo): Unit = {
    clock.advanceBy(config.initialPreferredAssignerTimeout + 1.second)
    val expectedNextTickerTime = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        expectedNextTickerTime,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(expectedAssignerInfo)
            )
          )
        )
      )
    )
  }

  /**
   * Invoke the `HeartbeatSuccess` event on state machine and verify that it returns no actions
   * and schedules the onAdvance call at `expectedNextTickerTime`.  Also verify that the heartbeat
   * success count metric is incremented.
   */
  private def triggerHeartbeatSuccessEvent(
      stateMachine: EtcdPreferredAssignerStateMachine,
      opId: Long,
      preferredAssigner: PreferredAssignerValue,
      expectedNextTickerTime: TickerTime): Unit = {
    val heartbeatSuccessCount = getHeartbeatSuccessCount
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.HeartbeatSuccess(opId, preferredAssigner)
      ) ==
      StateMachineOutput(expectedNextTickerTime, Seq.empty[DriverAction])
    )
    AssertionWaiter("Wait for heartbeat success count metric to increment").await {
      assert(getHeartbeatSuccessCount == heartbeatSuccessCount + 1)
    }
  }

  test("State machine takes over if initial preferred assigner is None@gen") {
    // Test plan: Verify that the state machine attempts to write itself as preferred assigner when
    // it learns the initial preferred assigner value is empty for some generation greater than
    // Generation.EMPTY.
    val stateMachine = createStateMachine()
    val emptyPreferredAssignerAtGen = PreferredAssignerValue.NoAssigner(generation(clock))
    val emptyPreferredAssignerConfig = PreferredAssignerConfig.create(
      emptyPreferredAssignerAtGen,
      selfAssignerInfo
    )

    // Advance time to shortly before the initial timeout.
    clock.advanceBy(config.initialPreferredAssignerTimeout - 1.second)
    stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(emptyPreferredAssignerAtGen)
      ) ==
      StateMachineOutput(
        clock.tickerTime() + config.writeRetryInterval,
        Seq(
          DriverAction.UsePreferredAssignerConfig(emptyPreferredAssignerConfig),
          DriverAction
            .Write(
              clock.tickerTime(),
              PreferredAssignerProposal(
                predecessorGenerationOpt = Some(emptyPreferredAssignerAtGen.generation),
                newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
              )
            )
        )
      )
    )
  }

  test("State machine abdicates if termination signal received while preferred") {
    // Test plan: Verify that the state machine abdicates if it receive a termination signal while
    // it as acting as preferred assigner, that it retries the write periodically until another
    // assigner becomes preferred, and that it does not attempt to reacquire preferred status even
    // if the preferred assigner value becomes empty again.

    // Receive the initial preferred assigner value (this assigner).
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()

    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))
    val selfPreferredAssignerConfig = PreferredAssignerConfig.create(
      selfPreferredAssigner,
      selfAssignerInfo
    )

    val expectedPreferredAssignerDeadline: TickerTime =
      testEpochTime + config.initialPreferredAssignerTimeout

    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedPreferredAssignerDeadline, Seq.empty)
    )

    // Inform the state machine that this assigner is the preferred assigner.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(selfPreferredAssigner)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(selfPreferredAssignerConfig))
      )
    )
    awaitPreferredAssignerGenerationMetric(selfPreferredAssigner.generation)

    // Inform it of pending termination and make sure that it attempts to abdicate.
    val abdicationWriteAction =
      DriverAction.Write(
        clock.tickerTime(),
        PreferredAssignerProposal(
          predecessorGenerationOpt = Some(selfPreferredAssigner.generation),
          newPreferredAssignerInfoOpt = None
        )
      )
    var writeDeadline = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onEvent(clock.tickerTime(), clock.instant(), Event.TerminationNoticeReceived) ==
      StateMachineOutput(writeDeadline, Seq(abdicationWriteAction))
    )

    // Make sure that the state machine retries the write if it isn't informed of a new preferred
    // assigner.
    clock.advanceBy(config.writeRetryInterval)
    writeDeadline += config.writeRetryInterval
    // Note: The retried write check needs to use the current ticker time (clock has advanced).
    val abdicationWriteActionRetry = DriverAction.Write(
      clock.tickerTime(),
      PreferredAssignerProposal(
        predecessorGenerationOpt = Some(selfPreferredAssigner.generation),
        newPreferredAssignerInfoOpt = None
      )
    )
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(writeDeadline, Seq(abdicationWriteActionRetry))
    )

    // Inform the state machine of a different preferred assigner and make sure it now acts as
    // standby for that assigner.
    val thirdPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(thirdAssignerInfo, generation(clock))
    val thirdPreferredAssignerConfig = PreferredAssignerConfig.create(
      thirdPreferredAssigner,
      selfAssignerInfo
    )
    val opId = 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(thirdPreferredAssigner)
      ) == StateMachineOutput(
        clock.tickerTime() + config.heartbeatInterval,
        Seq(
          DriverAction.UsePreferredAssignerConfig(thirdPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, thirdPreferredAssigner))
        )
      )
    )

    // Inform the state machine that there is no preferred assigner value and make sure it doesn't
    // try to become preferred.
    clock.advanceBy(500.milliseconds)
    val emptyPreferredAssigner = PreferredAssignerValue.NoAssigner(generation(clock))
    val emptyPreferredAssignerConfig = PreferredAssignerConfig.create(
      emptyPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(emptyPreferredAssigner)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(emptyPreferredAssignerConfig))
      )
    )
  }

  test("State machine will not leave standby after termination signal") {
    // Test plan: Verify that the state machine keeps heartbeating and does not attempt to become
    // preferred if it receives a termination signal while acting as standby.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Inform the state machine that otherAssigner is the preferred assigner.
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    val otherPreferredAssignerConfig = PreferredAssignerConfig.create(
      otherPreferredAssigner,
      selfAssignerInfo
    )
    var heartbeatTime = clock.tickerTime() + config.heartbeatInterval
    var opId = 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner)
      ) ==
      StateMachineOutput(
        heartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner))
        )
      )
    )
    clock.advanceBy(2.seconds)
    triggerHeartbeatSuccessEvent(stateMachine, opId, otherPreferredAssigner, heartbeatTime)
    clock.advanceBy(1.seconds)
    // Inform it of pending termination.
    assert(
      stateMachine.onEvent(clock.tickerTime(), clock.instant(), Event.TerminationNoticeReceived) ==
      StateMachineOutput(heartbeatTime, Seq.empty)
    )

    // It should keep sending heartbeats to the preferred assigner.
    clock.advanceBy(config.heartbeatInterval - 3.seconds)
    heartbeatTime += config.heartbeatInterval
    opId += 1
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        heartbeatTime,
        Seq(DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner)))
      )
    )
    clock.advanceBy(1.second)
    triggerHeartbeatSuccessEvent(stateMachine, opId, otherPreferredAssigner, heartbeatTime)

    // Inform the state machine that there is no preferred assigner value and make sure it doesn't
    // try to become preferred.
    val emptyPreferredAssigner = PreferredAssignerValue.NoAssigner(generation(clock))
    val emptyPreferredAssignerConfig = PreferredAssignerConfig.create(
      emptyPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(emptyPreferredAssigner)
      ) ==
      // The nextTickerTime is TickerTime.MAX because there are no write retries scheduled and, with
      // no preferred assigner, no heartbeats scheduled.
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(emptyPreferredAssignerConfig))
      )
    )
  }

  test("State machine overwrites a previous disabled preferred assigner value") {
    // Test plan: Verify that if the state machine learns that the preferred assigner value has
    // become disabled, it will attempt to write itself as the preferred assigner and take over.
    val stateMachine = createStateMachine()
    val previousGeneration = Generation(Incarnation(STORE_INCARNATION.value - 1), 0)
    val disabledPreferredAssignerValue: PreferredAssignerValue =
      PreferredAssignerValue.ModeDisabled(previousGeneration)
    val disabledPreferredAssignerConfig = PreferredAssignerConfig.create(
      disabledPreferredAssignerValue,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(disabledPreferredAssignerValue)
      ) == StateMachineOutput(
        clock.tickerTime() + config.writeRetryInterval,
        Seq(
          DriverAction.UsePreferredAssignerConfig(disabledPreferredAssignerConfig),
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None, // Earlier incarnation will be normalized to None.
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )
  }

  test("Store incarnation validation") {
    assertThrow[IllegalArgumentException]("Store incarnation must be non-loose") {
      new EtcdPreferredAssignerStateMachine(selfAssignerInfo, Incarnation.MIN, config)
    }
  }

  test("Config validation") {
    assertThrow[IllegalArgumentException]("") {
      EtcdPreferredAssignerDriver.Config(heartbeatFailureThreshold = 0)
    }
    assertThrow[IllegalArgumentException]("") {
      EtcdPreferredAssignerDriver.Config(heartbeatFailureThreshold = -1)
    }
    for (badDuration <- Seq(0.seconds, -1.second)) {
      assertThrow[IllegalArgumentException]("") {
        EtcdPreferredAssignerDriver.Config(initialPreferredAssignerTimeout = badDuration)
      }
      assertThrow[IllegalArgumentException]("") {
        EtcdPreferredAssignerDriver.Config(heartbeatInterval = badDuration)
      }
      assertThrow[IllegalArgumentException]("") {
        EtcdPreferredAssignerDriver.Config(writeRetryInterval = badDuration)
      }
    }
  }

  test("State machine lag does not cause excessive heartbeats") {
    // Test plan: Verify that the state machine only sends one heartbeat evein if an advance call is
    // so far past the desired advance time that multiple heartbeat intervals have passed.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )
    // Inform the state machine that otherAssigner is the preferred assigner.
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    val otherPreferredAssignerConfig = PreferredAssignerConfig.create(
      otherPreferredAssigner,
      selfAssignerInfo
    )
    val heartbeatTime = clock.tickerTime() + config.heartbeatInterval
    var opId = 1
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner)
      ) ==
      StateMachineOutput(
        heartbeatTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssignerConfig),
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner))
        )
      )
    )
    // Advance by more than one heartbeat interval and verify that the state machine only sends one
    // heartbeat.
    clock.advanceBy(5.minutes)
    opId += 1
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        clock.tickerTime() + config.heartbeatInterval,
        Seq(DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner)))
      )
    )
  }

  test("State machine eagerly takes over if PA is from a past store incarnation") {
    // Test plan: Verify that the state machine eagerly takes over as preferred assigner if it's
    // informed of a preferred assigner from a past store incarnation.
    val stateMachine = createStateMachine()

    val testEpoch: TickerTime = clock.tickerTime()
    val expectedNextOutputTime: TickerTime = testEpoch + config.initialPreferredAssignerTimeout
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedNextOutputTime, Seq.empty)
    )

    // Inform the state machine that otherAssigner is the preferred assigner, but from a prior store
    // incarnation. Verify that it immediately writes itself as the preferred assigner.
    val otherPreferredAssigner1 = PreferredAssignerValue.SomeAssigner(
      otherAssignerInfo,
      Generation(Incarnation(STORE_INCARNATION.value - 2), clock.tickerTime().nanos)
    )
    val otherPreferredAssigner1Config = PreferredAssignerConfig.create(
      otherPreferredAssigner1,
      selfAssignerInfo
    )
    val nextWriteTime = clock.tickerTime() + config.writeRetryInterval
    val expectedProposal = PreferredAssignerProposal(
      predecessorGenerationOpt = None, // Earlier incarnation will be normalized to None.
      newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
    )

    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(otherPreferredAssigner1)
      ) ==
      StateMachineOutput(
        nextWriteTime,
        Seq(
          DriverAction.UsePreferredAssignerConfig(otherPreferredAssigner1Config),
          DriverAction.Write(clock.tickerTime(), expectedProposal)
        )
      )
    )

    clock.advanceBy(5.seconds)
    // Inform the state machine that thirdAssigner has become the new preferred assigner, but it is
    // still from a past store incarnation. Verify that it immediately writes itself as the
    // preferred assigner.
    val thirdPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      thirdAssignerInfo,
      Generation(Incarnation(STORE_INCARNATION.value - 2), clock.tickerTime().nanos)
    )
    val thirdPreferredAssignerConfig = PreferredAssignerConfig.create(
      thirdPreferredAssigner,
      selfAssignerInfo
    )
    val nextWriteTime2 = clock.tickerTime() + config.writeRetryInterval
    val expectedProposal2 = PreferredAssignerProposal(
      predecessorGenerationOpt = None, // Earlier incarnation will be normalized to None.
      newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(thirdPreferredAssigner)
      ) ==
      StateMachineOutput(
        nextWriteTime2,
        Seq(
          DriverAction.UsePreferredAssignerConfig(thirdPreferredAssignerConfig),
          DriverAction.Write(clock.tickerTime(), expectedProposal2)
        )
      )
    )

    clock.advanceBy(5.seconds)
    // The current assigner has now become the preferred assigner. Verify that no further actions
    // are taken.
    val selfPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      selfAssignerInfo,
      Generation(STORE_INCARNATION, clock.tickerTime().nanos)
    )
    val selfPreferredAssignerConfig = PreferredAssignerConfig.create(
      selfPreferredAssigner,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(selfPreferredAssigner)
      ) == StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UsePreferredAssignerConfig(selfPreferredAssignerConfig))
      )
    )
  }

  test("State machine incorporates information from future store incarnations") {
    // Test plan: Verify that the state machine incorporates the preferred assigner if the
    // preferred assigner is from a future store incarnation, but it doesn't heartbeat against
    // it.
    val stateMachine = createStateMachine()

    val testEpoch: TickerTime = clock.tickerTime()
    val expectedNextOutputTime: TickerTime = testEpoch + config.initialPreferredAssignerTimeout
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(expectedNextOutputTime, Seq.empty)
    )

    // Inform the state machine that otherAssigner is the preferred assigner, but from a future
    // incarnation.
    val preferredAssignerAtLaterStoreIncarnation = PreferredAssignerValue.SomeAssigner(
      otherAssignerInfo,
      Generation(STORE_INCARNATION.getNextNonLooseIncarnation, clock.tickerTime().nanos)
    )
    val preferredAssignerAtLaterStoreIncarnationConfig = PreferredAssignerConfig.create(
      preferredAssignerAtLaterStoreIncarnation,
      selfAssignerInfo
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerReceived(preferredAssignerAtLaterStoreIncarnation)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.UsePreferredAssignerConfig(
            preferredAssignerAtLaterStoreIncarnationConfig
          )
        )
      )
    )
  }

  test("State machine handles OCC failure on write") {
    // Test plan: Verify that when the state machine receives an OCC failure on a write attempt,
    // it doesn't crash and continues to retry the write at the appropriate interval.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()

    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Advance past the initial timeout so the state machine tries to take over.
    clock.advanceBy(config.initialPreferredAssignerTimeout + 1.second)
    val expectedNextTickerTime = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        expectedNextTickerTime,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )

    // Simulate an OCC failure response from the driver.
    // The state machine logs the failure but doesn't immediately retry (waits for retry interval).
    val occFailureGeneration = generation(clock)
    val occFailureResult = WriteResult.OccFailure(occFailureGeneration)
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WriteResultReceived(clock.tickerTime(), Success(occFailureResult))
      ) == StateMachineOutput(
        expectedNextTickerTime,
        Seq.empty // No immediate retry; the write retry timer is still active
      )
    )

    // Verify that the state machine continues to retry the write after the retry interval
    // if the OCC failure persists.
    clock.advanceBy(config.writeRetryInterval)
    val nextRetryTime = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        nextRetryTime,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )
  }

  test("State machine handles exception on write") {
    // Test plan: Verify that when the state machine receives an exception on a write attempt,
    // it doesn't crash and continues to retry the write at the appropriate interval.
    val stateMachine = createStateMachine()
    val testEpochTime = clock.tickerTime()

    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Advance past the initial timeout so the state machine tries to take over.
    clock.advanceBy(config.initialPreferredAssignerTimeout + 1.second)
    val expectedNextTickerTime = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        expectedNextTickerTime,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )

    // Simulate an exception response from the driver.
    // The state machine logs the error but doesn't immediately retry (waits for retry interval).
    val exception = new RuntimeException("Test exception: write failed")
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WriteResultReceived(clock.tickerTime(), Failure(exception))
      ) == StateMachineOutput(
        expectedNextTickerTime,
        Seq.empty // No immediate retry; the write retry timer is still active
      )
    )

    // Verify that the state machine continues to retry the write after the retry interval
    // if exceptions persist.
    clock.advanceBy(config.writeRetryInterval)
    val nextRetryTime = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        nextRetryTime,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )
  }

  test("StandbyWithoutPreferred writes ExternalPick when a pick is known") {
    // Test plan: Verify that when an external pick has been delivered to the state
    // machine before the startup timeout elapses, the resulting "no preferred assigner" write
    // proposes the pick rather than self. Verify that the
    // `dicer_assigner_preferred_assigner_writes_total{valueSource="externalPick"}`
    // counter is incremented exactly once for this write.
    val stateMachine = createStateMachine()
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val externalPick: AssignerInfo = otherAssignerInfo

    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Deliver the external pick before the startup timeout fires. This must not
    // trigger a write on its own; only recording the next write's preferred-assigner candidate.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.ExternalPickReceived(Some(externalPick))
      ) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Advance past the startup timeout: the state machine writes the pick, not self.
    driveStartupToInitialWrite(stateMachine, expectedAssignerInfo = externalPick)
    assert(externalPickTracker.totalChange() == 1)
  }

  test("StandbyWithoutPreferred falls back to self when no ExternalPick is known") {
    // Test plan: Verify that without any external pick, the "no preferred assigner"
    // write still proposes selfAssignerInfo and that the
    // `dicer_assigner_preferred_assigner_writes_total{valueSource="self"}` counter is incremented
    // exactly once. When no external pick has been delivered, the state machine writes
    // itself as preferred — preserving the pre-migration behavior.
    val stateMachine = createStateMachine()
    val selfTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.Self))

    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    driveStartupToInitialWrite(stateMachine, expectedAssignerInfo = selfAssignerInfo)
    assert(selfTracker.totalChange() == 1)
  }

  test("Standby takeover writes ExternalPick when preferred assigner becomes unhealthy") {
    // Test plan: Drive the state machine into Standby with an external pick recorded, time out
    // enough heartbeats to trigger a takeover, and verify the takeover write proposes the pick
    // (not self). Verify the ExternalPick write-counter increment.
    val stateMachine = createStateMachine()
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val externalPick: AssignerInfo = thirdAssignerInfo
    val incumbentPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))

    // Drive Startup, record the pick, then become Standby against the incumbent assigner.
    stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(Some(externalPick))
    )
    var nextHeartbeatTime = clock.tickerTime() + config.heartbeatInterval
    var opId = 1L
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerReceived(incumbentPreferredAssigner)
    )

    // Drive the heartbeat-failure loop and the final advance that triggers the takeover write.
    val (advancedNextHeartbeatTime, advancedOpId): (TickerTime, Long) =
      driveHeartbeatFailuresPrimingTakeover(stateMachine, nextHeartbeatTime, opId)
    nextHeartbeatTime = advancedNextHeartbeatTime + config.heartbeatInterval
    opId = advancedOpId + 1
    clock.advanceBy(config.heartbeatInterval)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        nextHeartbeatTime,
        Seq(
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, incumbentPreferredAssigner)),
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = Some(incumbentPreferredAssigner.generation),
              newPreferredAssignerInfoOpt = Some(externalPick)
            )
          )
        )
      )
    )
    assert(externalPickTracker.totalChange() == 1)
  }

  test("Preferred + terminating still abdicates regardless of ExternalPick") {
    // Test plan: Verify that abdication takes precedence over the Phase 2.1 handoff. A preferred,
    // terminating assigner writes `None` (an abdication), never the pick, even when a pick is set.
    // We terminate first (which emits the abdication write), then deliver a differing pick, and
    // confirm it triggers no handoff write.
    val stateMachine = createStateMachine()
    val noAssignerTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.NoAssigner))
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))
    val externalPick: AssignerInfo = otherAssignerInfo

    // Drive Startup -> Preferred.
    stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerReceived(selfPreferredAssigner)
    )

    // Receive termination first. The abdication write should carry `None`, not the pick.
    val expectedWriteDeadline = clock.tickerTime() + config.writeRetryInterval
    assert(
      stateMachine.onEvent(clock.tickerTime(), clock.instant(), Event.TerminationNoticeReceived) ==
      StateMachineOutput(
        expectedWriteDeadline,
        Seq(
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = Some(selfPreferredAssigner.generation),
              newPreferredAssignerInfoOpt = None
            )
          )
        )
      )
    )

    // Deliver a differing pick while preferred and terminating. Abdication takes precedence, so
    // no handoff write is emitted -- only the pending abdication retry remains scheduled.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.ExternalPickReceived(Some(externalPick))
      ) == StateMachineOutput(expectedWriteDeadline, Seq.empty[DriverAction])
    )
    assert(noAssignerTracker.totalChange() == 1)
    assert(externalPickTracker.totalChange() == 0)
  }

  test("Preferred hands off to a differing ExternalPick") {
    // Test plan: Verify that a healthy, non-terminating preferred assigner installs a differing
    // external (consistent-hashing) pick as the new preferred via a single direct-handoff write.
    // The write CASes against the assigner's own current generation and proposes the pick. A pick
    // equal to self must produce no write.
    val stateMachine = createStateMachine()
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))
    val selfTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.Self))

    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))
    val externalPick: AssignerInfo = otherAssignerInfo

    // Drive Startup -> Preferred(self).
    stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerReceived(selfPreferredAssigner)
    )

    // A differing pick triggers an immediate handoff write CASing against our own generation.
    val handoffWriteTime = clock.tickerTime()
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.ExternalPickReceived(Some(externalPick))
      ) ==
      StateMachineOutput(
        handoffWriteTime + config.writeRetryInterval,
        Seq(
          DriverAction.Write(
            handoffWriteTime,
            PreferredAssignerProposal(
              predecessorGenerationOpt = Some(selfPreferredAssigner.generation),
              newPreferredAssignerInfoOpt = Some(externalPick)
            )
          )
        )
      )
    )
    assert(externalPickTracker.totalChange() == 1)

    // A pick equal to self is a no-op: no handoff write and no scheduled advance.
    clock.advanceBy(config.writeRetryInterval)
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.ExternalPickReceived(Some(selfAssignerInfo))
      ) == StateMachineOutput(TickerTime.MAX, Seq.empty[DriverAction])
    )
    assert(externalPickTracker.totalChange() == 1)
    assert(selfTracker.totalChange() == 0)
  }

  test("Clearing the ExternalPick restores Self as the next write's preferred-assigner candidate") {
    // Test plan: Make the pick the active preferred-assigner candidate by letting the state
    // machine actually emit a write proposing it (proving the pick was processed, not just
    // set-and-reset in a single SEC turn). Then deliver `ExternalPickReceived(None)` to clear
    // the pick, advance past the next write-retry interval, and verify the resulting write
    // proposes `selfAssignerInfo`.
    val stateMachine = createStateMachine()
    val selfTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.Self))
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val externalPick: AssignerInfo = otherAssignerInfo

    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Deliver the pick and let the state machine emit a write proposing it. This forces the SM
    // to have observed and recorded the pick; we then know the subsequent clear can only be
    // observed against that recorded state.
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(Some(externalPick))
    )
    driveStartupToInitialWrite(stateMachine, expectedAssignerInfo = externalPick)
    assert(externalPickTracker.totalChange() == 1)

    // Clear the pick; advance past the write-retry interval; the next write must propose self.
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(None)
    )
    clock.advanceBy(config.writeRetryInterval)
    val secondWriteTickerTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(secondWriteTickerTime, clock.instant()) ==
      StateMachineOutput(
        secondWriteTickerTime + config.writeRetryInterval,
        Seq(
          DriverAction.Write(
            secondWriteTickerTime,
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(selfAssignerInfo)
            )
          )
        )
      )
    )
    assert(selfTracker.totalChange() == 1)
    assert(externalPickTracker.totalChange() == 1)
  }

  test("Updating the ExternalPick from one assigner to another retargets the next write") {
    // Test plan: First make the original pick (otherAssignerInfo) the active
    // preferred-assigner candidate by letting the state machine emit a write proposing it.
    // Then update the pick to the replacement pick (thirdAssignerInfo) and let the state
    // machine emit another write — that second write must propose the replacement pick,
    // demonstrating the pick is NOT sticky to the original after being changed.
    val stateMachine = createStateMachine()
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val originalPick: AssignerInfo = otherAssignerInfo
    val replacementPick: AssignerInfo = thirdAssignerInfo

    val testEpochTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(testEpochTime + config.initialPreferredAssignerTimeout, Seq.empty)
    )

    // Deliver the original pick, advance past the startup timeout, and verify the first write
    // proposes it.
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(Some(originalPick))
    )
    clock.advanceBy(config.initialPreferredAssignerTimeout + 1.second)
    val firstWriteTickerTime = clock.tickerTime()
    val secondWriteAdvanceTime = firstWriteTickerTime + config.writeRetryInterval
    assert(
      stateMachine.onAdvance(firstWriteTickerTime, clock.instant()) ==
      StateMachineOutput(
        secondWriteAdvanceTime,
        Seq(
          DriverAction.Write(
            firstWriteTickerTime,
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(originalPick)
            )
          )
        )
      )
    )

    // Update to the replacement pick and advance past the write-retry interval. The next write
    // must propose the replacement pick, proving the pick was replaced rather than sticky to
    // the original.
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(Some(replacementPick))
    )
    clock.advanceBy(config.writeRetryInterval)
    val secondWriteTickerTime = clock.tickerTime()
    assert(
      stateMachine.onAdvance(secondWriteTickerTime, clock.instant()) ==
      StateMachineOutput(
        secondWriteTickerTime + config.writeRetryInterval,
        Seq(
          DriverAction.Write(
            secondWriteTickerTime,
            PreferredAssignerProposal(
              predecessorGenerationOpt = None,
              newPreferredAssignerInfoOpt = Some(replacementPick)
            )
          )
        )
      )
    )
    assert(externalPickTracker.totalChange() == 2)
  }

  test("ExternalPick is preserved across Preferred -> Standby transitions") {
    // Test plan: Become Preferred, record a pick while Preferred, transition to Standby, then
    // drive a takeover. The takeover write must propose the pick recorded while Preferred,
    // confirming that the pick is not cleared by run-state transitions. Recording the pick while
    // Preferred also emits an immediate handoff write (see the dedicated handoff test), so two
    // ExternalPick writes are expected in total.
    val stateMachine = createStateMachine()
    val externalPickTracker: MetricUtils.ChangeTracker[Int] =
      MetricUtils.ChangeTracker(() => getWriteCount(ValueSource.ExternalPick))

    val externalPick: AssignerInfo = thirdAssignerInfo
    val selfPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(selfAssignerInfo, generation(clock))

    // Drive Startup -> Preferred -> record pick (emits a handoff write while Preferred).
    stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerReceived(selfPreferredAssigner)
    )
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.ExternalPickReceived(Some(externalPick))
    )

    // Transition Preferred -> Standby by learning a different preferred assigner.
    clock.advanceBy(1.second)
    val otherPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, generation(clock))
    var nextHeartbeatTime = clock.tickerTime() + config.heartbeatInterval
    var opId = 1L
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerReceived(otherPreferredAssigner)
    )

    // Drive the heartbeat-failure loop and the final advance that triggers the takeover write.
    val (advancedNextHeartbeatTime, advancedOpId): (TickerTime, Long) =
      driveHeartbeatFailuresPrimingTakeover(stateMachine, nextHeartbeatTime, opId)
    nextHeartbeatTime = advancedNextHeartbeatTime + config.heartbeatInterval
    opId = advancedOpId + 1
    clock.advanceBy(config.heartbeatInterval)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        nextHeartbeatTime,
        Seq(
          DriverAction.SendHeartbeat(HeartbeatRequest(opId, otherPreferredAssigner)),
          DriverAction.Write(
            clock.tickerTime(),
            PreferredAssignerProposal(
              predecessorGenerationOpt = Some(otherPreferredAssigner.generation),
              newPreferredAssignerInfoOpt = Some(externalPick)
            )
          )
        )
      )
    )
    assert(externalPickTracker.totalChange() == 2)
  }

  /** Creates a generation for the current time on clock. */
  private def generation(clock: TypedClock): Generation =
    Generation(STORE_INCARNATION, clock.tickerTime().nanos)

  private def getWriteCount(valueSource: ValueSource): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_preferred_assigner_writes_total",
        Map("valueSource" -> valueSource.labelValue)
      )
      .toInt
  }

  private def getHeartbeatSuccessCount: Int = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_preferred_assigner_standby_heartbeat_total",
        Map("outcome" -> "SUCCESS")
      )
      .toInt
  }

  private def getHeartbeatFailureCount: Int = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_preferred_assigner_standby_heartbeat_total",
        Map("outcome" -> "FAILURE")
      )
      .toInt
  }

  /**
   * Waits for the preferred assigner generation metrics to (almost) match the specified generation.
   *
   * Because metrics are doubles, they can't precisely represent generations, therefore we have
   * to check for a close though not necessarily exact match.
   */
  private def awaitPreferredAssignerGenerationMetric(generation: Generation): Unit = {
    AssertionWaiter("Wait for preferred assigner generation metric to update").await {
      val incarnationMetric: Double = MetricUtils
        .getMetricValue(
          registry,
          "dicer_assigner_preferred_assigner_latest_known_incarnation_gauge",
          Map.empty[String, String]
        )
      val generationMetric: Double = MetricUtils
        .getMetricValue(
          registry,
          "dicer_assigner_preferred_assigner_latest_known_generation_gauge",
          Map.empty[String, String]
        )

      // Check for a match within a close tolerance for both incarnation and generation
      val tolerance = 0.00001
      assert(Math.abs(incarnationMetric - generation.incarnation.value) < tolerance)
      assert(Math.abs(generationMetric - generation.number.value.toDouble) < tolerance)
    }
  }
}
