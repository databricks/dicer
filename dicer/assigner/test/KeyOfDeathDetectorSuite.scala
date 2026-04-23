package com.databricks.dicer.assigner

import java.time.Instant

import scala.concurrent.duration._
import scala.concurrent.duration.Duration.Infinite

import com.databricks.caching.util.{StateMachineOutput, TickerTime}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.assigner.KeyOfDeathDetector.{DriverAction, Event}
import com.databricks.dicer.assigner.TargetMetrics.KeyOfDeathTransitionType
import com.databricks.dicer.external.{Target}
import com.databricks.testing.DatabricksTest

class KeyOfDeathDetectorSuite extends DatabricksTest with TestName {

  /** The default health config for the detector for tests. */
  private val config = KeyOfDeathDetector.Config(
    crashRecordRetention = 2.minutes,
    heuristicThreshold = 0.25
  )

  /**
   * Test harness for a [[KeyOfDeathDetector]] that allows for easy testing of the state machine
   * by simplifying the expression of time and expected outputs given inputs.
   */
  private class TestHarness(detector: KeyOfDeathDetector) {

    /**
     * Tests that the state machine produces `actions` when given `event` at offset `timeOffset`,
     * and that the earliest next time to call the state machine is `nextTimeOffset`. Time offsets
     * are relative to test start time.
     */
    def event(
        timeOffset: FiniteDuration,
        event: Event,
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      test(timeOffset, Some(event), actions, nextTimeOffset)
    }

    /**
     * Tests that the state machine produces `actions` when advanced at offset `timeOffset`, and
     * that the earliest next time to call the state machine is `nextTimeOffset`. Time offsets are
     * relative to test start time.
     */
    def advance(
        timeOffset: FiniteDuration,
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      test(timeOffset, None, actions, nextTimeOffset)
    }

    /** See [[event()]] and [[advance()]]. */
    private def test(
        timeOffset: FiniteDuration,
        eventOpt: Option[Event],
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      val tickerTime: TickerTime = TickerTime.ofNanos(timeOffset.toNanos)
      val instant = Instant.ofEpochSecond(timeOffset.toSeconds, timeOffset.toNanos % 1000000000)
      val output: StateMachineOutput[DriverAction] = eventOpt match {
        case Some(event) => detector.onEvent(tickerTime, instant, event)
        case None => detector.onAdvance(tickerTime, instant)
      }
      val nextTickerTime: TickerTime = nextTimeOffset match {
        case offset: FiniteDuration => TickerTime.ofNanos(offset.toNanos)
        case _: Infinite => TickerTime.MAX
      }
      assert(output == StateMachineOutput(nextTickerTime, actions))
    }
  }

  private def verifyKodTargetMetrics(
      target: Target,
      expectedHeuristicValue: Double,
      expectedNumCrashedResourcesGauge: Int,
      expectedNumCrashedResourcesTotal: Int,
      expectedEstimatedResourceWorkloadSize: Int,
      expectedTransitions: Map[KeyOfDeathTransitionType, Int]): Unit = {
    assert(TargetMetricsUtils.getKeyOfDeathHeuristic(target) == expectedHeuristicValue)
    assert(
      TargetMetricsUtils.getNumCrashedResourcesGauge(target) == expectedNumCrashedResourcesGauge
    )
    assert(
      TargetMetricsUtils.getNumCrashedResourcesTotal(target) == expectedNumCrashedResourcesTotal
    )
    assert(
      TargetMetricsUtils
        .getEstimatedResourceWorkloadSize(target) == expectedEstimatedResourceWorkloadSize
    )
    for (expectedTransition: (KeyOfDeathTransitionType, Int) <- expectedTransitions) {
      val (transitionType, expectedCount): (KeyOfDeathTransitionType, Int) = expectedTransition
      assert(
        TargetMetricsUtils.getKeyOfDeathStateTransitions(target, transitionType) == expectedCount
      )
    }
  }

  test("Simple healthy scenario remains stable") {
    // Test plan: Verify that in a stable scenario with only the addition of new resources,
    // the key of death detector remains in the Stable state and produces a heuristic
    // value of 0 after receiving the initial health report and for the remaining duration
    // of the test.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Advance the detector and verify that it is in the starting stable state.
    harness.advance(0.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 0,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Provide an initial health report, verifying that the detector has reached the stable
    // state and produces a heuristic value of 0.
    harness.event(
      35.seconds,
      Event.ResourcesUpdated(
        healthyCount = 1,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 1,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Without any changes, the detector should remain stable and produce the same heuristic
    // value.
    harness.advance(65.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 1,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Provide a second health report where a new resource has been added, verifying that the
    // detector still produces a heuristic value of 0.
    harness.event(
      66.seconds,
      Event.ResourcesUpdated(
        healthyCount = 2,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 2,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    harness.advance(96.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 2,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test("Crashed resources are correctly counted and reported over their lifetime") {
    // Test plan: Verify that crashed resources are correctly counted and persisted over the
    // amount of time specified by the congifuration's crash record duration. During this time,
    // the heuristic value should be non-zero regardless of other resource health changes,
    // while it should return to 0 after the crash record duration has expired.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Advance the detector and verify that it is in the starting stable state.
    harness.advance(0.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 0,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Provide an initial health report, verifying that the detector has reached the stable
    // state and produces a heuristic value of 0.
    harness.event(
      35.seconds,
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Simulate a crash of pod0 in the next health report, verifying that the detector is still
    // in the stable state but produces a heuristic value of 1/5.
    harness.event(
      70.seconds,
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 1
      ),
      Seq.empty,
      190.seconds // The time at which pod0's crash record expires.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the heuristic does not change if no new resources are added or crashed resources
    // expire.
    harness.advance(180.seconds, Seq.empty, 190.seconds)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Add in another health report that includes a resurrected pod0, verifying that the heuristic
    // value should still be non-zero.
    harness.event(
      185.seconds,
      // pod0 is resurrected. No new crashes (pod0 reappears, was already recorded as crashed).
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      190.seconds // The time at which pod0's crash record expires.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // After the crash record expires, the heuristic should return to 0. The number of total
    // crashes should remain at 1. The estimated resource workload size should remain at 5, which
    // is the current number of healthy resources.
    harness.advance(191.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test("Non-crash resource health changes are ignored by the crash heuristic") {
    // Test plan: Verify that non-crash resource health changes (such as terminations of resources
    // or transitions in between the Running and NotReady states) do not increase the key of death
    // heuristic value. They should, however, contribute to the estimated resource workload size.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Advance the detector and verify that it is in the starting stable state.
    harness.advance(0.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 0,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Provide an initial health report, verifying that the detector has reached the stable
    // state and produces a heuristic value of 0.
    harness.event(
      35.seconds,
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the heuristic does not change if a resource is now terminating, though the
    // estimated resource workload size should decrease by 1.
    harness.event(
      40.seconds,
      // pod4 transitions to Terminating (not a crash). healthyCount=4.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 4,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the heuristic does not change if the resource is fully terminated and
    // no longer present in the health report.
    harness.event(
      45.seconds,
      // pod4 fully gone. Was Terminating when it left → not a crash. healthyCount=4.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 4,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the heuristic does not change if the resource is now NotReady, though the
    // estimated resource workload size should decrease by 1.
    harness.event(
      50.seconds,
      // pod3 transitions to NotReady (not a crash). healthyCount=3.
      Event.ResourcesUpdated(
        healthyCount = 3,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 3,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the heuristic does not change if a second Running resource transitions to
    // NotReady, though the estimated resource workload size should decrease by 1 again.
    harness.event(
      55.seconds,
      // pod2 also transitions to NotReady (not a crash). healthyCount=2.
      Event.ResourcesUpdated(
        healthyCount = 2,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 2,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test("New non-Running resources are ignored as both crashed and healthy resources") {
    // Test plan: Verify that new Terminating/NotReady resources are ignored as both
    // crashed and healthy resources and do not change the heuristic value or the estimated
    // resource workload size.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report, verifying that the detector has reached the stable
    // state and produces a heuristic value of 0.
    harness.event(
      35.seconds,
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash a resource by removing it from the report.
    harness.event(
      55.seconds,
      // pod4 absent and was Running → 1 crash at t=55. healthyCount=4.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 1
      ),
      Seq.empty,
      175.seconds // The time at which pod4's crash record expires.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Resurrect the resource, so that there are 5 currently healthy resources.
    harness.event(
      58.seconds,
      // pod4 reappears. No new crashes. healthyCount=5.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      175.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Add in a new resource in the NotReady state, verifying that the heuristic does not change.
    // This resource should neither be counted as a new crashed resource (the total number of
    // crashed resources should remain at 1) nor as a healthy resource (the estimated resource
    // workload size should remain at 5).
    harness.event(
      60.seconds,
      // pod5 is new and NotReady (not Running) → not counted as crashed or healthy. healthyCount=5.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      175.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Add in a new resource in the Terminating state, verifying that the heuristic does not change.
    harness.event(
      65.seconds,
      // pod6 is new and Terminating → not counted. healthyCount=5 (pod0-pod4 Running).
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      175.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test("Multiple crashed resources will trigger immediate key of death scenario declaration") {
    // Test plan: Verify that when multiple resources crash at once, the key of death detector
    // should report both a transition to the Endangered state and a transition to the Poisoned
    // state, as these actions should both happen immediately when the new health report is
    // processed.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report, verifying that the detector has reached the stable
    // state and produces a heuristic value of 0.
    harness.event(
      35.seconds,
      // 5 Running pods → healthyCount=5, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash two resources at once.
    harness.event(
      55.seconds,
      // pod3 and pod4 crashed (were Running, now absent) → 2 crashes, 3 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 3,
        newlyCrashedCount = 2
      ),
      Seq(DriverAction.TransitionToPoisoned),
      175.seconds // The time at which pod0 and pod1's crash records expire.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test(
    "Having crashed resources with no healthy resources should trigger a key of death scenario"
  ) {
    // Test plan: Verify that having no healthy resources should trigger a key of death scenario,
    // and that the heuristic value should be the number of crashes. After the crash record
    // expires, the heuristic value should return to 0, ensuring that we handle a zero denominator
    // correctly.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report with one resource in the Running state.
    harness.event(
      35.seconds,
      // 1 Running pod → healthyCount=1, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 1,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 1,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Remove the resource, which should trigger a key of death scenario.
    harness.event(
      40.seconds,
      // pod0 crashed (was Running, now absent) → 1 crash, 0 healthy.
      Event.ResourcesUpdated(
        healthyCount = 0,
        newlyCrashedCount = 1
      ),
      Seq(DriverAction.TransitionToPoisoned),
      160.seconds // The time at which pod0's crash record expires.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 1,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that the detector is in the poisoned state and continues to produce the correct
    // heuristic value.
    harness.advance(
      45.seconds,
      Seq.empty,
      160.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 1,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Verify that when the crash record expires, the heuristic value should return to 0,
    // and we should return to the Stable state. The estimated resource workload size should
    // go back to 0, as we have no healthy resources.
    harness.advance(
      165.seconds,
      Seq(DriverAction.RestoreStability),
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 0,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 1
      )
    )
  }

  test("A full key of death scenario is correctly detected and reported") {
    // Test plan: Verify that a full key of death scenario is correctly detected and reported
    // in the heuristic value, accompanied by the correct driver actions and state transitions.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report with 5 healthy resources.
    harness.event(
      35.seconds,
      // 5 Running pods → healthyCount=5, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash 1 resource, which should not yet trigger a key of death scenario.
    harness.event(
      50.seconds,
      // pod4 crashed (was Running, now absent) → 1 crash, 4 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 1
      ),
      Seq.empty,
      170.seconds // The time at which pod4's crash record expires.
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash another resource by removing it from the report, which should trigger a key of death
    // scenario. The new crashed resource should time out at 200 seconds.
    harness.event(
      80.seconds,
      // pod3 crashed (was Running, now absent) → 1 crash, 3 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 3,
        newlyCrashedCount = 1
      ),
      Seq(DriverAction.TransitionToPoisoned),
      170.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Advance the detector and verify that it is in the poisoned state and produces the
    // same heuristic value and number of crashes as before.
    harness.advance(
      90.seconds,
      Seq.empty,
      170.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Timeout the first crashed resource, which should not mark the key of death scenario
    // as ended.
    harness.advance(
      175.seconds,
      Seq.empty,
      200.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Timeout the second crashed resource, which should trigger the end of the key of death
    // scenario, as the heuristic value should return to 0.
    harness.advance(
      205.seconds,
      Seq(DriverAction.RestoreStability),
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 3,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 1
      )
    )

    // With no resource changes, the detector should remain in the stable state and produce a
    // heuristic value of 0.
    harness.advance(235.seconds, Seq.empty, Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 3,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 1
      )
    )
  }

  test("The workload estimation is sensitive to scale-ups even when frozen") {
    // Test plan: Verify that the workload estimation is sensitive to scale-ups in all three
    // states: Stable, Endangered, and Poisoned.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report with 3 healthy resources.
    harness.event(
      0.seconds,
      // 3 Running pods → healthyCount=3, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 3,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 3,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Adding two resources should update the estimated resource workload size to 5.
    harness.event(
      10.seconds,
      // 5 Running pods → healthyCount=5, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash a resource to have the detector transition to the Endangered state.
    harness.event(
      20.seconds,
      // pod4 crashed (was Running, now absent) → 1 crash, 4 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 1
      ),
      Seq.empty,
      140.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Add in two healthy resources, verifying that the estimated resource workload size
    // updates to 6.
    harness.event(
      30.seconds,
      // pod4 resurrected + pod5 new → 6 healthy, no new crashes.
      Event.ResourcesUpdated(
        healthyCount = 6,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      140.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 6.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 6,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash another resource to have the detector transition to the Poisoned state. Since the
    // current number of healthy resources is now equal to the frozen workload size (5), the
    // estimated resource workload size should decrease to 5.
    harness.event(
      40.seconds,
      // pod5 crashed (was Running, now absent) → 1 crash, 5 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 1
      ),
      Seq(DriverAction.TransitionToPoisoned),
      140.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Adding a healthy resource should update the estimated resource workload size to 6, even
    // though the frozen workload size is still 5.
    harness.event(
      50.seconds,
      // pod5 resurrected → 6 healthy, no new crashes.
      Event.ResourcesUpdated(
        healthyCount = 6,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      140.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 6.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 6,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
  }

  test("The default configuration for the detector is correct") {
    // Test plan: Verify that the default configuration for the detector works correctly,
    // using 1 hour as the crash record retention and 0.25 as the heuristic threshold.
    val defaultConfig = KeyOfDeathDetector.Config.defaultConfig()
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, defaultConfig)
    val harness = new TestHarness(detector)

    // Provide an initial health report with 5 healthy resources.
    harness.event(
      10.seconds,
      // 5 Running pods → healthyCount=5, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Crash 2 resources, triggering a key of death scenario.
    val crashExpiryTime: FiniteDuration = 1.hours + 20.seconds
    harness.event(
      20.seconds,
      // pod3 and pod4 crashed (were Running, now absent) → 2 crashes, 3 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 3,
        newlyCrashedCount = 2
      ),
      Seq(DriverAction.TransitionToPoisoned),
      crashExpiryTime
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Advance the detector and verify that it is in the poisoned state and produces the
    // same heuristic value and number of crashes as before.
    harness.advance(30.seconds, Seq.empty, crashExpiryTime)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Revive the two crashed resources, which do not end the key of death scenario.
    harness.event(
      40.seconds,
      // pod3 and pod4 resurrected → 5 healthy, no new crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      crashExpiryTime
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // At 1 hour, the heuristic value should still be the same.
    harness.advance(1.hour, Seq.empty, crashExpiryTime)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 2.0 / 5.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // One hour after the crash, the heuristic value should return to 0.
    harness.advance(crashExpiryTime, Seq(DriverAction.RestoreStability), Duration.Inf)
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 1
      )
    )
  }

  test("KeyOfDeathDetector.toString") {
    // Test plan: Verify that KeyOfDeathDetector.toString returns the expected representation.
    val target = Target(getSafeName)
    val detector = new KeyOfDeathDetector(target, config)
    val harness = new TestHarness(detector)

    // Provide an initial health report with 5 healthy resources.
    harness.event(
      35.seconds,
      // 5 Running pods → healthyCount=5, no crashes.
      Event.ResourcesUpdated(
        healthyCount = 5,
        newlyCrashedCount = 0
      ),
      Seq.empty,
      Duration.Inf
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
    assert(
      detector.toString ==
      "KeyOfDeathDetector(state=Stable, numCrashed=0, " +
      "estimatedResourceWorkloadSize=5)"
    )

    // Crash 1 resource.
    harness.event(
      50.seconds,
      // pod4 crashed (was Running, now absent) → 1 crash, 4 healthy remaining.
      Event.ResourcesUpdated(
        healthyCount = 4,
        newlyCrashedCount = 1
      ),
      Seq.empty,
      170.seconds
    )
    verifyKodTargetMetrics(
      target,
      expectedHeuristicValue = 1.0 / 5.0,
      expectedNumCrashedResourcesGauge = 1,
      expectedNumCrashedResourcesTotal = 1,
      expectedEstimatedResourceWorkloadSize = 5,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )
    assert(
      detector.toString ==
      "KeyOfDeathDetector(state=Endangered, numCrashed=1, " +
      "estimatedResourceWorkloadSize=5)"
    )
  }
}
