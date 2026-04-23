package com.databricks.dicer.assigner

import com.databricks.caching.util._
import com.databricks.dicer.assigner.HealthWatcher._
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource.AssignmentDistributionSource
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils.{createRandomProposal, createTestSquid}
import com.databricks.dicer.common._
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration._
import scala.util.Random

/**
 * Test suite for the HealthWatcher.
 *
 * @param observeSliceletReadiness if true, the HealthWatcher faithfully reports the
 *                                 Slicelet-reported health status for a pod. If false, masks the
 *                                 health status so that NotReady is masked to Running.
 * @param permitRunningToNotReady if true, the HealthWatcher allows resources in the Running state
 *                                to transition to the NotReady state. If false, the HealthWatcher
 *                                ignores NOT_READY reports for resources in the Running state.
 */
private abstract class HealthWatcherSuite(
    observeSliceletReadiness: Boolean,
    permitRunningToNotReady: Boolean)
    extends DatabricksTest
    with TestUtils.ParameterizedTestNameDecorator {

  private val GENERATION = Generation(Incarnation(42), number = 43)

  /** The target used in the tests. */
  private val target = Target("softstore")

  /** The default health config for the health watcher for tests. */
  private val config =
    HealthWatcher.StaticConfig(
      initialHealthReportDelayPeriod = 30.seconds,
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds,
      notReadyTimeoutPeriod = 10.seconds
    )

  /** Determines whether the Assigner will mask the Slicelet's reported health status */
  private val healthWatcherTargetConfig: HealthWatcherTargetConfig =
    HealthWatcherTargetConfig(
      observeSliceletReadiness = observeSliceletReadiness,
      permitRunningToNotReady = permitRunningToNotReady
    )

  override def paramsForDebug: Map[String, Any] = Map(
    "observeSliceletReadiness" -> observeSliceletReadiness,
    "permitRunningToNotReady" -> permitRunningToNotReady
  )

  /** Creates an [[Event.SliceletStateFromSlicelet]] event. */
  private def createSliceletStateFromSliceletEvent(
      resource: Squid,
      sliceletState: SliceletState): Event = {
    Event.SliceletStateFromSlicelet(resource, sliceletState)
  }

  /** Creates an [[Event.SliceletStateFromKubernetes]] event. */
  private def createSliceletStateFromKubernetesEvent(
      resourceUuid: UUID,
      sliceletState: SliceletState): Event = {
    Event.SliceletStateFromKubernetes(resourceUuid, sliceletState)
  }

  /** Creates a health report state machine output. */
  private def createHealthReportOutput(
      healthy: Set[Squid],
      newlyCrashedCount: Int = 0): Seq[DriverAction] = {
    Seq(DriverAction.HealthReportReady(healthy = healthy, newlyCrashedCount = newlyCrashedCount))
  }

  /**
   * Creates an [[Event.AssignmentSyncObserved.AssignmentObserved]] event from `source` at
   * `generation`, and containing `resources` in the assignment.
   */
  @throws[IllegalArgumentException]("If resources are empty.")
  private def createAssignmentObserved(
      generation: Generation,
      source: AssignmentDistributionSource,
      resources: Set[Squid]): Event.AssignmentSyncObserved.AssignmentObserved = {
    val assignmentContainingResources: Assignment =
      ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(
          resources.size,
          resources.toIndexedSeq,
          numMaxReplicas = 1,
          Random
        )
      ).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        generation
      )
    Event.AssignmentSyncObserved.AssignmentObserved(
      source,
      assignmentContainingResources
    )
  }

  /**
   * Test harness for a [[HealthWatcher]] that allows for easy testing of the state machine by
   * simplifying the expression of time and expected outputs given inputs.
   */
  private class TestHarness(watcher: HealthWatcher) {

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
        case Some(event) => watcher.onEvent(tickerTime, instant, event)
        case None => watcher.onAdvance(tickerTime, instant)
      }
      val nextTickerTime: TickerTime = nextTimeOffset match {
        case offset: FiniteDuration => TickerTime.ofNanos(offset.toNanos)
        case _: Infinite => TickerTime.MAX
      }
      assert(output == StateMachineOutput(nextTickerTime, actions))
      watcher.forTest.checkInvariants(now = tickerTime)
    }
  }

  // The resource address in the pods used in tests.
  private val pod0: Squid = createTestSquid("http://pod0")
  private val pod1: Squid = createTestSquid("http://pod1")
  private val pod2: Squid = createTestSquid("http://pod2")
  private val pod3: Squid = createTestSquid("http://pod3")

  test("Test initial health report delay") {
    // Test plan: Create a watcher and supply it with health signals from 3/4 Slicelets over the
    // 30-second setup period. Verify that the first health report yielded by the watcher includes
    // the 3 Slicelets that reported within the window. Then verify that a late-signaling Slicelet
    // is included in a subsequent report.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // Signal Running for the first 3 Slicelets before the expected initial report time.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      6.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      7.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Advance to the expected initial report time. Expect next event at 35 seconds for pod0 expiry.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Set(pod0, pod1, pod3)),
      35.seconds // expected expiry time for pod0
    )

    // Have the last Slicelet signal it's healthy.
    harness.event(
      32.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.Running),
      createHealthReportOutput(Set(pod0, pod1, pod2, pod3)),
      35.seconds // expected expiry time for pod0
    )
  }

  test(
    "Pod automatically transitions to the Running state when readiness status masking is enabled"
  ) {
    // Test plan: verify that the HealthWatcher masks a Slicelet's status from NotReady to Running
    // if the HealthWatcher is configured to do so.

    val watcher: HealthWatcher =
      DefaultFactory.create(
        target,
        config,
        healthWatcherTargetConfig
      )
    val harness = new TestHarness(watcher)

    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // always go to the reported health status.
    harness.event(
      30.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.NotReady),
      createHealthReportOutput(if (observeSliceletReadiness) Set.empty else Set(pod0)),
      60.seconds // expected expiry time for pod0
    )
  }

  test("Detect single pod") {
    // Test plan: Create a watcher, inform it about a pod and make sure that the health watcher's
    // state is as expected.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // Initial output should ask us to call back after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial report time and verify the expected map is returned. Expect pod0 to
    // expire at 35 seconds.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Set(pod0)),
      35.seconds
    )
  }

  test("Detect multiple pods") {
    // Test plan: Create a watcher, inform it about multiple pods. Make sure that the health
    // watcher's state is as expected.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Watcher should yield the same output until the initial report time.
    for (i: Int <- 6 to 29) {
      harness.advance(i.seconds, Seq.empty, 30.seconds)
    }
    harness.advance(
      30.seconds,
      createHealthReportOutput(Set(pod0, pod1, pod3)),
      35.seconds // next expiry time
    )
  }

  test("Pods go from healthy to unhealthy by expiring and then come back") {
    // Test plan: Verify that repeated heartbeats (RUNNING or NOT_READY) reset the unhealthy expiry
    // timer, preventing resources from being expelled, while non-heartbeating pods expire. pod0
    // sends repeated Running heartbeats and pod2 sends repeated NotReady heartbeats at t=31 and
    // t=45; both reset their expiry past t=61 so no expiry fires there. pod1 sends no further
    // heartbeats after t=5 and is expelled at t=35. pod3 transitions Running→NotReady at t=5 and
    // then expires at t=35; when permitRunningToNotReady=true it is NOT counted as crashed (removed
    // from the healthy set on transition), and when permitRunningToNotReady=false it IS counted as
    // crashed (transition suppressed, stays Running). Also verifies that pod2 (whose last known
    // heartbeat was NOT_READY when permitRunningToNotReady=true) is not counted as crashed when it
    // expires at t=75, since it was already removed from the healthy set on transition.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // pod0-3 all enter Running at t=5. The next scheduled callback is the initial health report
    // at t=30.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    // pod3 immediately transitions Running→NotReady at t=5, resetting its expiry to t=5+30=35.
    // When permitRunningToNotReady=true, pod3 is removed from the healthy set; when false, the
    // transition is suppressed and pod3 stays Running. Still in startup → no output.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )

    // Initial health report at t=30. pod0, pod1, pod2, and pod3 all expire at t=5+30=35.
    // pod3 is omitted from healthy when permitRunningToNotReady=true (it is NotReady).
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        if (permitRunningToNotReady) Set(pod0, pod1, pod2) else Set(pod0, pod1, pod2, pod3)
      ),
      35.seconds
    )

    // pod0 sends a Running heartbeat at t=31, resetting its expiry from t=35 to t=31+30=61.
    // pod1, pod2, pod3 still expire at t=35, so the next callback remains t=35.
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      35.seconds
    )

    // pod2 sends a NotReady heartbeat at t=31, resetting its expiry from t=35 to t=31+30=61.
    // When permitRunningToNotReady=true, pod2 transitions Running→NotReady and a health report
    // is emitted. pod3 is already NotReady (since t=5) so it is not in healthy.
    val pod2FirstHeartbeatOutput: Seq[DriverAction] =
      if (permitRunningToNotReady)
        createHealthReportOutput(Set(pod0, pod1))
      else Seq.empty
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.NotReady),
      pod2FirstHeartbeatOutput,
      35.seconds
    )

    // At t=35, pod1 and pod3 expire. pod0 and pod2 survive with expiries at t=61.
    // pod2's membership in healthy depends on permitRunningToNotReady:
    // - false: pod2 stays Running (transition suppressed) → pod2 IS in healthy
    // - true: pod2 is NotReady → pod2 NOT in healthy
    // pod3's membership in crashed depends on permitRunningToNotReady:
    // - false: pod3 was Running/masked when it expired → pod3 IS in crashed
    // - true: pod3 was NotReady (removed from healthy at t=5) → pod3 NOT in crashed
    val pod2InHealthy: Boolean = !permitRunningToNotReady
    harness.advance(
      35.seconds,
      createHealthReportOutput(
        healthy = if (pod2InHealthy) Set(pod0, pod2) else Set(pod0),
        newlyCrashedCount = if (!permitRunningToNotReady) 2 else 1
      ),
      61.seconds
    )

    // pod1 comes back with a Running heartbeat at t=35. Its expiry is t=35+30=65. The next
    // callback is still t=61 (min of pod0=61, pod1=65, pod2=61).
    harness.event(
      35.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      createHealthReportOutput(if (pod2InHealthy) Set(pod0, pod1, pod2) else Set(pod0, pod1)),
      61.seconds
    )

    // pod0 sends a second Running heartbeat at t=45, resetting its expiry from t=61 to t=75.
    // pod2's expiry is still t=61, so the next callback remains t=61.
    harness.event(
      45.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      61.seconds
    )

    // pod2 sends a second NotReady heartbeat at t=45, resetting its expiry from t=61 to t=75.
    // No status change (pod2 is already NotReady or Running per permitRunningToNotReady). The next
    // callback is pod1's expiry at t=35+30=65, which is earlier than pod0's and pod2's t=75.
    harness.event(
      45.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.NotReady),
      Seq.empty,
      65.seconds
    )

    // Advance to t=61. Without the t=45 heartbeats, pod0 and pod2 would have expired here.
    // Because both heartbeats pushed the expiry to t=75, no expiry fires and no report is emitted.
    // The next callback is pod1's expiry at t=65.
    harness.advance(61.seconds, Seq.empty, 65.seconds)

    // pod1 expires at t=35+30=65. Was Running → crashed. pod0 and pod2 survive until t=75.
    harness.advance(
      65.seconds,
      createHealthReportOutput(
        healthy = if (!permitRunningToNotReady) Set(pod0, pod2) else Set(pod0),
        newlyCrashedCount = 1
      ),
      75.seconds
    )

    // pod0 and pod2 both expire at t=45+30=75. pod0 was Running → crashed.
    // pod2 was NotReady (when permitRunningToNotReady=true), so it was already removed from
    // previouslyHealthy when it first transitioned to NotReady. By the time it expires it is no
    // longer eligible and is not counted as crashed.
    // pod2 was Running/masked (when permitRunningToNotReady=false), so it IS counted as crashed.
    harness.advance(
      75.seconds,
      createHealthReportOutput(
        healthy = Set.empty,
        newlyCrashedCount = if (!permitRunningToNotReady) 2 else 1
      ),
      Duration.Inf
    )
  }

  test("Pods marked as terminating") {
    // Test plan: Create a watcher, inform it about the four pods, two (pod2, pod3) whose first
    // heartbeat is NOT_READY (→ Starting state if observeSliceletReadiness=true, else Running) and
    // two (pod0, pod1) in the Running state. Report one pod in each group as terminating and verify
    // that the HealthWatcher reflects the new terminating pods in a health report.
    val harness =
      new TestHarness(
        DefaultFactory.create(
          target,
          config,
          healthWatcherTargetConfig
        )
      )
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )

    // Advance beyond the initial report time. pod2 and pod3 are in Starting state (first
    // heartbeat was NOT_READY). With observeSliceletReadiness=false, Starting is masked to Running.
    harness.advance(
      31.seconds,
      createHealthReportOutput(
        if (observeSliceletReadiness) Set(pod0, pod1) else Set(pod0, pod1, pod2, pod3)
      ),
      35.seconds
    )

    // Let time pass and refresh the existing health statuses of all resources to prevent them
    // from being expired as we continue to advance the clock.
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      35.seconds
    )
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      35.seconds
    )
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.NotReady),
      Seq.empty,
      35.seconds
    )
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.NotReady),
      Seq.empty,
      64.seconds
    )

    harness.event(
      34.seconds,
      createSliceletStateFromKubernetesEvent(pod1.resourceUuid, SliceletState.Terminating),
      createHealthReportOutput(
        if (observeSliceletReadiness) Set(pod0) else Set(pod0, pod2, pod3)
      ),
      64.seconds
    )
    harness.event(
      34.seconds,
      createSliceletStateFromKubernetesEvent(pod3.resourceUuid, SliceletState.Terminating),
      createHealthReportOutput(if (observeSliceletReadiness) Set(pod0) else Set(pod0, pod2)),
      64.seconds
    )

    // Advance time to just before pod0 and pod2 should expire.
    harness.advance(63.seconds, Seq.empty, 64.seconds)

    // pod0 and pod2 are now expired. pod0 was Running → crashed. pod2 was Running only when
    // observeSliceletReadiness=false (Starting state masked to Running) → crashed if so.
    harness.advance(
      64.seconds,
      createHealthReportOutput(
        healthy = Set.empty,
        newlyCrashedCount = if (observeSliceletReadiness) 1 else 2
      ),
      94.seconds
    )

    // pod1 and pod3 are now terminated and should also be expired.
    harness.advance(94.seconds, createHealthReportOutput(Set.empty), Duration.Inf)
  }

  test("Pod without SQUID does not show up in health report") {
    // Test plan: Mark a pod as terminating without SQUID, and expect it not show up in
    // HealthReportReady.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromKubernetesEvent(pod0.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )

    // pod0 does not show up in health report, since it does not have a SQUID.
    harness.advance(30.seconds, createHealthReportOutput(Set.empty), 65.seconds)
  }

  test("Terminating after Terminating does not update expiry") {
    // Test plan: Report health of a pod as Terminating to the HealthWatcher and send another
    // Terminating status. The expiry should remain the same as the first Terminating state.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromKubernetesEvent(pod0.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )

    // pod0 does not show up in health report, since it does not have a SQUID.
    harness.advance(30.seconds, createHealthReportOutput(Set.empty), 65.seconds)

    // pod0's Terminating status expiry does not change.
    harness.event(
      35.seconds,
      createSliceletStateFromKubernetesEvent(pod0.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      65.seconds
    )
  }

  test("HealthStatus update for the same resource with a newer SQUID") {
    // Test plan: Report HealthStatus of a pod, report another HealthStatus for the same pod with a
    // newer SQUID and expect the status to be updated in watcher.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Set(pod0)), 35.seconds)

    // Create a new SQUID with same address and UUID but a more recent creation time. Do this before
    // the expiry time of the existing entry to verify that the existing entry is updated. A SQUID
    // replacement does not count as a crash — the existing entry is replaced in-place.
    val newPod0: Squid = pod0.copy(creationTimeMillis = pod0.creationTimeMillis + 100)
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(newPod0, SliceletState.Running),
      createHealthReportOutput(healthy = Set(newPod0), newlyCrashedCount = 0),
      61.seconds
    )
  }

  test("HealthStatus update for the same resource with an older SQUID") {
    // Test plan: Report HealthStatus of a pod, report another HealthStatus for the same pod with an
    // older SQUID and expect the status to be unchanged in watcher.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Set(pod0)), 35.seconds)

    // Create an older SQUID with same address, UUID but an older creation time.
    val oldPod0: Squid = pod0.copy(creationTimeMillis = pod0.creationTimeMillis - 100)
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(oldPod0, SliceletState.Running),
      Seq.empty,
      35.seconds
    )
  }

  test("Report status as Healthy after Terminating") {
    // Test plan: Report HealthStatus of a pod as Terminating, then report another HealthStatus for
    // the same pod as Healthy and expect the status to be unchanged in watcher.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createSliceletStateFromKubernetesEvent(pod0.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Set.empty), 65.seconds)

    // Report the HealthStatus as Running with SQUID, status expiry remains same but we add SQUID
    // and update the report.
    harness.event(
      45.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      // pod0's computed status is Terminating (stays Terminating once set). Not in healthy.
      createHealthReportOutput(Set.empty),
      65.seconds
    )
  }

  test("Starting state is handled correctly") {
    // Test plan: Verify that from Starting, if observeSliceletReadiness is true, a RUNNING
    // heartbeat transitions immediately (no flapping timer). Repeated NOT_READY heartbeats keep the
    // resource in Starting. If observeSliceletReadiness is false, the resource is masked to Running
    // and no status change is observed.
    val harness =
      new TestHarness(
        DefaultFactory.create(
          target,
          config,
          healthWatcherTargetConfig
        )
      )
    harness.advance(Duration.Zero, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Setup: first NOT_READY → Starting (brand-new resource, no flapping timer).
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )

    // Verify: initial report shows pod0 in Starting state.
    // With observeSliceletReadiness=false, Starting is masked to Running so pod0 IS in healthy.
    harness.advance(
      30.seconds,
      createHealthReportOutput(if (observeSliceletReadiness) Set.empty else Set(pod0)),
      35.seconds
    )

    // Verify: second NOT_READY stays in Starting, no status change.
    harness.event(
      33.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.NotReady),
      Seq.empty,
      63.seconds
    )

    // Verify: RUNNING from Starting transitions immediately (no flapping protection timer).
    // For observeSliceletReadiness=true: Starting → Running, report emitted.
    // For observeSliceletReadiness=false: pod was already masked to Running; no status change.
    // The Slicelet has already been reporting Running; since there is no change in the
    // Slicelet's health status, we don't report anything.
    val startingToRunningOutput: Seq[DriverAction] =
      if (observeSliceletReadiness) {
        createHealthReportOutput(Set(pod0))
      } else {
        Seq.empty
      }
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      startingToRunningOutput,
      64.seconds
    )
  }

  test("RUNNING to NOT_READY to RUNNING transition with flapping protection") {
    // Test plan: Verify that when permitRunningToNotReady is enabled, a Running pod that receives
    // a NOT_READY heartbeat transitions to NotReady with flapping protection set to expire at
    // 31s + 10s = 41s. Verify that a RUNNING heartbeat within the protection window (35s < 41s)
    // does not transition back to Running. Verify that a RUNNING heartbeat after the window elapses
    // (60s > 41s) does transition back to Running. When permitRunningToNotReady is disabled,
    // verify that NOT_READY heartbeats for Running pods are ignored throughout.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Setup: advance the watcher to initialize its start time, then put pod0 in Running state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Verify: advance to the initial report time.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Set(pod0)),
      35.seconds
    )

    // Verify: NOT_READY heartbeat. When permitRunningToNotReady is enabled, pod0 transitions to
    // NotReady (not in healthy). When disabled, pod0 stays Running and no report is issued.
    val notReadyOutput: Seq[DriverAction] =
      if (permitRunningToNotReady) {
        createHealthReportOutput(Set.empty)
      } else {
        Seq.empty
      }
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.NotReady),
      notReadyOutput,
      61.seconds
    )

    // Verify: RUNNING heartbeat within the flapping protection window. When permitRunningToNotReady
    // is enabled, pod0 stays NotReady (35s < 31s + 10s). Otherwise pod0 was already Running.
    harness.event(
      35.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      65.seconds
    )

    // Verify: RUNNING heartbeat after the flapping protection window has elapsed (60s > 31s + 10s).
    // When permitRunningToNotReady is enabled, pod0 transitions back to Running. Otherwise pod0
    // was already Running, no change.
    val backToRunningOutput: Seq[DriverAction] =
      if (permitRunningToNotReady) {
        createHealthReportOutput(Set(pod0))
      } else {
        Seq.empty
      }
    harness.event(
      60.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      backToRunningOutput,
      90.seconds
    )
  }

  test("Terminating heartbeat exits NotReady immediately, bypassing flapping protection") {
    // Test plan: Verify that a resource in NotReady (with active flapping protection) transitions
    // immediately to Terminating upon a TERMINATING heartbeat, regardless of whether the protection
    // window has elapsed. Do this by putting pod0 in Running, triggering a NOT_READY heartbeat to
    // enter NotReady (when permitRunningToNotReady is enabled), then sending a TERMINATING
    // heartbeat within the protection window and verifying pod0 becomes Terminating.
    val harness = new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // Setup: pod0 enters Running at t=5.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.advance(30.seconds, createHealthReportOutput(Set(pod0)), 35.seconds)

    // Setup: NOT_READY at t=31. When permitRunningToNotReady, pod0 enters NotReady (not in
    // healthy). Otherwise pod0 stays Running and no report is issued.
    val setupNotReadyOutput: Seq[DriverAction] =
      if (permitRunningToNotReady) {
        createHealthReportOutput(Set.empty)
      } else {
        Seq.empty
      }
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.NotReady),
      setupNotReadyOutput,
      61.seconds
    )

    // Verify: TERMINATING heartbeat at t=35, within the would-be protection window (35s < 41s).
    // pod0 transitions to Terminating immediately regardless of flapping protection or flag
    // combination. pod0 not in healthy (Terminating). Not in crashed (status change, not expiry).
    harness.event(
      35.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Terminating),
      createHealthReportOutput(Set.empty),
      95.seconds // 35s + terminatingTimeoutPeriod(60s)
    )
  }

  test("Bootstraps health by observed assignment from slicelets") {
    // Test plan: Verify that the watcher bootstraps the health of assigned resources for which it
    // has no authoritative signals using the assignment observed from slicelets. Verify this by
    // delivering health signals to the watcher for various pods (including Running, NotReady, and
    // Terminating) and then telling the watcher about an assignment sync-ed from a slicelet that
    // includes other pods, but also excludes some pods that are known. Check that the resulting
    // health report includes the pods that were not previously known to the watcher, as well as the
    // correct status for all pods with authoritative health information. Also verify that the
    // bootstrapped health expires at the unhealthy timeout period past the watcher's start time.

    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    // Tell the watcher about pods 0,1 (Running), 2 (NotReady), and 3 (Terminating).
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      6.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      7.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )
    harness.event(
      8.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )
    // Tell the watcher about the latest set of assigned resources, which includes pods 1, 2, and 3,
    // as well as pods 4 and 5. The assignment notably excludes pod 0 so that we can verify that
    // pods not in the set of assigned resources also show up in the health report (i.e. aren't
    // eliminated due to the assignment). Pod 2 is already known to the watcher (in Starting state,
    // because its first heartbeat was NOT_READY), so only pods 4 and 5 are bootstrapped to Running.
    val pod4: Squid = createTestSquid("http://pod4")
    val pod5: Squid = createTestSquid("http://pod5")
    harness.event(
      9.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod1, pod2, pod3, pod4, pod5)
      ),
      // pod0,pod1,pod4,pod5 are Running. pod2: Running if observeSliceletReadiness=false
      // (masked), else Starting.
      // pod3 Terminating. crashed=Set.empty (first report).
      createHealthReportOutput(
        if (observeSliceletReadiness) Set(pod0, pod1, pod4, pod5)
        else Set(pod0, pod1, pod2, pod4, pod5)
      ),
      30.seconds // expiry of pods 4 and 5 whose health was bootstrapped
    )
    // Verify that pods 4 and 5 expire at the unhealthy timeout period past watcher's start time.
    // pod4 and pod5 were Running → crashed.
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        healthy = if (observeSliceletReadiness) Set(pod0, pod1) else Set(pod0, pod1, pod2),
        newlyCrashedCount = 2
      ),
      35.seconds // expiry of pod 0 is next
    )
    // pod0 expires. Was Running → crashed.
    harness.advance(
      35.seconds,
      createHealthReportOutput(
        healthy = if (observeSliceletReadiness) Set(pod1) else Set(pod1, pod2),
        newlyCrashedCount = 1
      ),
      36.seconds // expiry of pod 1 is next
    )
    // pod1 expires. Was Running → crashed.
    harness.advance(
      36.seconds,
      createHealthReportOutput(
        healthy = if (observeSliceletReadiness) Set.empty else Set(pod2),
        newlyCrashedCount = 1
      ),
      37.seconds // expiry of pod 2 is next
    )
    // pod2 expires. Was Running only if observeSliceletReadiness=false (masked Starting)
    // → crashed if so.
    harness.advance(
      37.seconds,
      createHealthReportOutput(
        healthy = Set.empty,
        newlyCrashedCount = if (observeSliceletReadiness) 0 else 1
      ),
      68.seconds
    )
    // pod3 expires. Was Terminating → not in crashed.
    harness.advance(68.seconds, createHealthReportOutput(Set.empty), Duration.Inf)
  }

  test("Does not bootstrap health by assignment sync-ed from clerk or store") {
    // Test plan: Verify that the watcher doesn't elide its initial health delay based on an
    // observed assignment from clerk or store (if the load watcher doesn't observe any assignment
    // from slicelet). In addition, verify that the health watcher can correctly track authoritative
    // health signals during initial health delay and the first health report will not be polluted
    // by assignments sync-ed from clerk or store in this case (where initial health delay is not
    // bypassed).

    val incarnation = Incarnation(14)
    val config = HealthWatcher.StaticConfig(
      initialHealthReportDelayPeriod = 30.seconds,
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds,
      notReadyTimeoutPeriod = 10.seconds
    )
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    harness.advance(Duration.Zero, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Deliver the `AssignmentSyncObserved` event from different unreliable sources (store or clerk)
    // with various generations and on different time points (but within the initial health delay).
    // Verify health watcher doesn't generate health report or ask callback for those events.
    harness.event(
      5.seconds,
      createAssignmentObserved(
        Generation(incarnation, number = 42),
        AssignmentDistributionSource.Store,
        Set(pod0, pod1)
      ),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )
    harness.event(
      5.seconds,
      createAssignmentObserved(
        Generation(incarnation, number = 42),
        AssignmentDistributionSource.Clerk,
        Set(pod0, pod1)
      ),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Supply an authoritative health signal during initial health delay. The health signal of pod4
    // should be recorded, so health watcher will ask driver to callback after initial health delay
    // (30s)
    val pod4: Squid = createTestSquid("http://pod4")
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod4, SliceletState.Running),
      actions = Seq.empty,
      nextTimeOffset = 30.seconds
    )

    // Continue to deliver the `AssignmentSyncObserved` event from unreliable sources. No health
    // reports should be generated yet.
    harness.event(
      6.seconds,
      createAssignmentObserved(
        Generation(incarnation, number = 43),
        AssignmentDistributionSource.Store,
        Set(pod0, pod1, pod2)
      ),
      actions = Seq.empty,
      nextTimeOffset = 30.seconds
    )
    harness.event(
      20.seconds,
      createAssignmentObserved(
        Generation(incarnation, number = 114514),
        AssignmentDistributionSource.Store,
        Set(pod0, pod1, pod2)
      ),
      actions = Seq.empty,
      nextTimeOffset = 30.seconds
    )

    // Again, supply an authoritative health signal, which still happens during initial health
    // delay.
    val pod5: Squid = createTestSquid("http://pod5")
    harness.event(
      25.seconds,
      createSliceletStateFromSliceletEvent(pod5, SliceletState.Running),
      actions = Seq.empty,
      nextTimeOffset = 30.seconds
    )

    // Advance to a time past the initial report delay. Verify only the pods with authoritative
    // health signals (pod4 and pod5) are used in the load report, and the assignments from
    // unreliable sources are ignored.
    harness.advance(
      30.seconds,
      actions = createHealthReportOutput(Set(pod4, pod5)),
      nextTimeOffset = 35.seconds // Expiry time of pod4.
    )
  }

  test(
    "Assigned resources with highest generation known by watcher will be used to bootstrap health"
  ) {
    // Test plan: Verify that only the assigned resources with highest generation seen by the health
    // watcher will be used to boostrap health after the health watcher observes an assignment
    // sync from slicelets. That is to say, an assignment sync event from slicelet can trigger the
    // health watcher to bypass initial health delay, but the assigned resources actually
    // incorporated into the initial health report don't necessarily need to be the ones from this
    // slicelet, but should be the ones with highest generation seen by the health watcher. Verify
    // this by supplying an AssignmentSyncObserved event from unreliable source with higher
    // generation to the health watcher, then supplying an AssignmentSyncObserved event from
    // reliable source (slicelet) to the health watcher, and verifying only resources in the
    // assignment with higher generation are incorporated into the first health report.

    val higherGeneration = Generation(Incarnation(42), number = 42)
    val lowerGeneration = Generation(Incarnation(42), number = 41)

    val unreliableSources: Seq[AssignmentDistributionSource] =
      Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )

    for (unreliableSource: AssignmentDistributionSource <- unreliableSources;
      assignedResourcesLowerGeneration: Set[Squid] <- Seq(Set(pod2), Set(pod2, pod3))) {

      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      harness.event(
        5.seconds,
        createAssignmentObserved(
          higherGeneration,
          unreliableSource,
          Set(pod0, pod1)
        ),
        actions = Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      harness.event(
        10.seconds,
        createAssignmentObserved(
          lowerGeneration,
          AssignmentDistributionSource.Slicelet,
          assignedResourcesLowerGeneration
        ),
        createHealthReportOutput(Set(pod0, pod1)),
        nextTimeOffset = 30.seconds
      )
    }
  }

  test(
    "Bootstrap triggered by unreliable AssignmentObserved and then reliable GenerationObserved"
  ) {
    // Test plan: Verify that an unreliable AssignmentObserved event followed by a reliable
    // GenerationObserved event can trigger health bootstrapping. Verify this by supplying the
    // health watcher with AssignmentObserved events from unreliable sources, and then supplying it
    // an GenerationObserved event from Slicelet with lower generation. Verify that the
    // initial health delay is bypassed and the resources from the unreliable sources are
    // incorporated into the health report.

    val higherGeneration = Generation(Incarnation(42), number = 42)
    val lowerGeneration = Generation(Incarnation(42), number = 41)

    for (unreliableSource: AssignmentDistributionSource <- Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )) {

      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      harness.event(
        5.seconds,
        createAssignmentObserved(
          higherGeneration,
          unreliableSource,
          Set(pod0, pod1)
        ),
        // Still in initial health delay.
        actions = Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      harness.event(
        10.seconds,
        Event.AssignmentSyncObserved
          .GenerationObserved(AssignmentDistributionSource.Slicelet, lowerGeneration),
        // Bypassed health delay.
        createHealthReportOutput(Set(pod0, pod1)),
        nextTimeOffset = 30.seconds
      )
    }
  }

  test(
    "Bootstrap triggered by reliable GenerationObserved and then unreliable AssignmentObserved"
  ) {
    // Test plan: Verify that a reliable GenerationObserved event followed by an unreliable
    // GenerationObserved event can trigger health bootstrapping. Verify this by supplying the
    // health watcher with a GenerationObserved event from Slicelet with lower generation and then
    // an AssignmentObserved event from unreliable sources with higher generation. Verify that the
    // initial health delay is bypassed and the resources from the unreliable sources are
    // incorporated into the health report.

    val higherGeneration = Generation(Incarnation(42), number = 42)
    val lowerGeneration = Generation(Incarnation(42), number = 41)

    for (unreliableSource: AssignmentDistributionSource <- Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )) {
      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      harness.event(
        10.seconds,
        Event.AssignmentSyncObserved
          .GenerationObserved(AssignmentDistributionSource.Slicelet, lowerGeneration),
        // Still in initial health delay.
        actions = Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      harness.event(
        15.seconds,
        createAssignmentObserved(
          higherGeneration,
          unreliableSource,
          Set(pod0, pod1)
        ),
        // Bypassed health delay.
        createHealthReportOutput(Set(pod0, pod1)),
        nextTimeOffset = 30.seconds
      )
    }
  }

  test(
    "Bootstrap triggered only by assignment no older than highest observed reliable generation"
  ) {
    // Test plan: Verify that if multiple generations from reliable sources are observed, only the
    // highest reliable generation will be used to judge the freshness of the assignment.

    val generation1 = Generation(Incarnation(42), number = 41)
    val generation2 = Generation(Incarnation(42), number = 42)
    val generation3 = Generation(Incarnation(42), number = 43)
    val generation4 = Generation(Incarnation(42), number = 44)

    for (unreliableSource: AssignmentDistributionSource <- Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )) {
      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      // G3 = 43 observed from reliable source. Not bootstrapping.
      harness.event(
        5.seconds,
        Event.AssignmentSyncObserved
          .GenerationObserved(AssignmentDistributionSource.Slicelet, generation3),
        Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      // G2 = 42 observed from unreliable source. Not bootstrapping as G2 < G3.
      harness.event(
        15.seconds,
        createAssignmentObserved(
          generation2,
          unreliableSource,
          Set(pod0, pod1)
        ),
        Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      // G1 = 41 observed from reliable source. Not bootstrapping using G2 even if G1 < G2, as we
      // observed a higher generation G3 from reliable source before and G2 < G3.
      harness.event(
        5.seconds,
        Event.AssignmentSyncObserved
          .GenerationObserved(AssignmentDistributionSource.Slicelet, generation1),
        Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      // G4 = 44 observed from unreliable source. Bootstrapping health using G4 as G3 is the highest
      // reliable generation observed and we have G4 > G3.
      harness.event(
        15.seconds,
        createAssignmentObserved(
          generation4,
          unreliableSource,
          Set(pod2, pod3)
        ),
        // Bootstrapped with resources in G4.
        createHealthReportOutput(Set(pod2, pod3)),
        nextTimeOffset = 30.seconds
      )
    }
  }

  test("Bootstrap not triggered by GenerationObserved event with empty generation") {
    // Test plan: Verify that a GenerationObserved event with empty generation can not trigger
    // health bootstrapping. Verify this by supplying the health watcher with AssignmentObserved
    // events from unreliable sources, and then supplying it an GenerationObserved event from
    // Slicelet with an empty generation. Verify that the initial health delay is not bypassed.

    for (unreliableSource: AssignmentDistributionSource <- Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )) {

      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      harness.event(
        5.seconds,
        createAssignmentObserved(
          GENERATION,
          unreliableSource,
          Set(pod0, pod1)
        ),
        actions = Seq.empty,
        nextTimeOffset = Duration.Inf
      )

      harness.event(
        10.seconds,
        Event.AssignmentSyncObserved
          .GenerationObserved(AssignmentDistributionSource.Slicelet, Generation.EMPTY),
        Seq.empty,
        nextTimeOffset = Duration.Inf
      )
    }
  }

  test(
    "Does not bypass starting phase if not observed assignments no older than reliable source"
  ) {
    // Test plan: Verify that if the health watcher has not observed any assignments no older than
    // the reliable source's knowledge, the health watcher will just wait until the initial health
    // delay to pass.

    val higherGeneration = Generation(Incarnation(42), number = 42)
    val lowerGeneration = Generation(Incarnation(42), number = 41)

    for (unreliableSource: AssignmentDistributionSource <- Seq(
        AssignmentDistributionSource.Clerk,
        AssignmentDistributionSource.Store
      )) {
      val harness = new TestHarness(
        DefaultFactory.create(target, config, healthWatcherTargetConfig)
      )
      // Advance the watcher to initialize its start time and enter the starting state.
      harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

      harness.event(
        5.seconds,
        createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
        actions = Seq.empty,
        nextTimeOffset = 30.seconds
      )

      // Slicelet knows a generation without an assignment, and no other assignment observed. Delay
      // not passed.
      harness.event(
        10.seconds,
        Event.AssignmentSyncObserved.GenerationObserved(
          AssignmentDistributionSource.Slicelet,
          higherGeneration
        ),
        actions = Seq.empty,
        nextTimeOffset = 30.seconds
      )

      // Learns an assignment from an unreliable source.
      harness.event(
        5.seconds,
        createAssignmentObserved(
          lowerGeneration,
          unreliableSource,
          Set(pod0, pod1)
        ),
        actions = Seq.empty,
        nextTimeOffset = 30.seconds
      )

      // Slicelet knows a generation higher than the latest observed assignment. Delay not passed.
      harness.event(
        10.seconds,
        Event.AssignmentSyncObserved.GenerationObserved(
          AssignmentDistributionSource.Slicelet,
          higherGeneration
        ),
        actions = Seq.empty,
        nextTimeOffset = 30.seconds
      )

      harness.advance(
        30.seconds,
        createHealthReportOutput(Set(pod3)),
        nextTimeOffset = 35.seconds
      )
    }
  }

  test("Bootstraps health by cross-incarnation assignment sync events") {
    // Test plan: Verify that the watcher can successfully bootstrap the health information where
    // the assigned resources are from a higher incarnation and the sync event that triggers it
    // is from a lower incarnation.
    val higherIncarnation = Incarnation(14)
    val lowerIncarnation = Incarnation(12)
    val config = HealthWatcher.StaticConfig(
      initialHealthReportDelayPeriod = 30.seconds,
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds,
      notReadyTimeoutPeriod = 10.seconds
    )
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    harness.advance(Duration.Zero, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Observed assignment from unreliable resource from a higher incarnation. Not trigger
    // bootstrapping.
    harness.event(
      5.seconds,
      createAssignmentObserved(
        GENERATION.copy(incarnation = higherIncarnation),
        AssignmentDistributionSource.Store,
        Set(pod0, pod1)
      ),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Observed generation from reliable resource from a higher incarnation. Should trigger
    // bootstrapping.
    harness.event(
      10.seconds,
      Event.AssignmentSyncObserved.GenerationObserved(
        AssignmentDistributionSource.Slicelet,
        GENERATION.copy(incarnation = lowerIncarnation)
      ),
      actions = createHealthReportOutput(Set(pod0, pod1)),
      nextTimeOffset = 30.seconds
    )
  }

  test("Health updated even after bootstrap") {
    // Test plan: Verify that bootstrapping doesn't affect the ability of the watcher to update the
    // health of resources as it receives new health signals.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    // Bootstrap pod 0.
    harness.event(
      5.seconds,
      createAssignmentObserved(GENERATION, AssignmentDistributionSource.Slicelet, Set(pod0)),
      createHealthReportOutput(Set(pod0)),
      30.seconds
    )
    // Deliver new heartbeat for pod0 and verify the watcher updates the health status.
    harness.event(
      6.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      36.seconds
    )
  }

  test("Health bootstrapping observes resources terminating by UUID (no SQUID)") {
    // Test plan: Verify that the watcher does not bootstrap the health of a resource which is
    // terminating even when it only knows its UUID and not the full SQUID.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    // Tell the watcher about pod 0, which is terminating, but only by UUID.
    harness.event(
      5.seconds,
      createSliceletStateFromKubernetesEvent(pod0.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )
    // Verify that the watcher does not bootstrap the health of pod 0 when informed of an assignment
    // including pod0 and pod1.
    harness.event(
      14.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod0, pod1)
      ),
      createHealthReportOutput(Set(pod1)),
      30.seconds // expiry of pods 4 and 5 whose health was bootstrapped
    )
  }

  test("Assigned resources have no effect after initialization phase") {
    // Test plan: Verify that if the watcher receives a set of assigned resources right at the
    // end or after the initialization delay, it will not bootstrap the health of those resources.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    // Tell the watcher about assigned resources right at the end of the initialization delay and
    // verify that the watcher does not bootstrap the health (they are already expired).
    harness.event(
      30.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod0, pod1)
      ),
      Seq.empty,
      Duration.Inf
    )
    // Verify that the watcher still does not bootstrap at any later point.
    harness.event(
      42.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod0, pod1, pod2, pod3)
      ),
      Seq.empty,
      Duration.Inf
    )
  }

  test("Critical caching error recorded if duplicate UUIDs in assigned resources") {
    // Test plan: Verify that the watcher records a critical caching error if it observes a set of
    // assigned resources which have duplicate UUIDs, and that the watcher deduplicates by taking
    // the Squid with the latest creation time.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // pod4 has the same UUID as pod0, but greater timestamp, so it should be taken.
    val pod4: Squid = pod0.copy(creationTimeMillis = pod0.creationTimeMillis + 1L)
    // pod5 has the same UUID as pod1, but lesser timestamp, so it should be ignored.
    val pod5: Squid = pod1.copy(creationTimeMillis = pod1.creationTimeMillis - 1L)

    val loggerPrefix: String = s"${target.getLoggerPrefix}"
    val initialCount: Int = MetricUtils.getPrefixLoggerErrorCount(
      Severity.DEGRADED,
      CachingErrorCode.ASSIGNER_ASSIGNED_SQUIDS_WITH_SAME_UUID,
      loggerPrefix
    )

    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // Since pod4 > pod0, and pod1 > pod5, pod4 and pod1 should be taken, leaving pods 1,2,3,4 in
    // the health report.
    harness.event(
      7.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod0, pod1, pod2, pod3, pod4, pod5)
      ),
      createHealthReportOutput(Set(pod1, pod2, pod3, pod4)),
      30.seconds
    )

    // Verify that the error count metric was incremented.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_ASSIGNED_SQUIDS_WITH_SAME_UUID,
        loggerPrefix
      ) == initialCount + 1
    )
  }

  test("HealthWatcher.toString") {
    // Test plan: Verify that HealthWatcher.toString returns the expected representation.

    // Setup: Create watcher and initialize with health reports for 3 pods.
    val healthWatcher: HealthWatcher =
      DefaultFactory.create(target, config, healthWatcherTargetConfig)
    val harness = new TestHarness(healthWatcher)
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      6.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      7.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Verify: `toString` returns the expected representation.
    assert(
      healthWatcher.toString ==
      """Health summary :
    |┌──────────────────────────────────────┬─────────┬─────────────┐
    |│ Resource                             │ Health  │ Expiry Time │
    |├──────────────────────────────────────┼─────────┼─────────────┤
    |│ 15322af9-366d-3d09-b784-b8bb3dbf4890 │ Running │ 35.0        │
    |│ 9f84c9f1-caa8-33ab-8ac6-2a3a52df666e │ Running │ 36.0        │
    |│ 78dad640-7391-3916-833e-504e395c636c │ Running │ 37.0        │
    |└──────────────────────────────────────┴─────────┴─────────────┘
    |""".stripMargin
    )
  }

  test("Initial health report delay shorter than unhealthy timeout period") {
    // Test plan: Verify that when initialHealthReportDelayPeriod is shorter than
    // unhealthyTimeoutPeriod, the first health report is emitted after the shorter delay,
    // but resources expire based on the longer unhealthyTimeoutPeriod.

    // Choose an arbitrarily shorter initial health report delay period than the unhealthy timeout
    // period. In production, both the initialHealthReportDelayPeriod and unhealthyTimeoutPeriod
    // are typically set to 30 seconds.
    val shortDelayConfig = HealthWatcher.StaticConfig(
      initialHealthReportDelayPeriod = 10.seconds,
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds,
      notReadyTimeoutPeriod = 10.seconds
    )
    val harness =
      new TestHarness(DefaultFactory.create(target, shortDelayConfig, healthWatcherTargetConfig))

    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // Signal Running for a pod before the expected initial report time (10s).
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      10.seconds
    )

    // Advance to the initial report time (10s). The report should be emitted now.
    // Pod0's expiry should be at 5s + 30s = 35s (based on unhealthyTimeoutPeriod).
    harness.advance(
      10.seconds,
      createHealthReportOutput(Set(pod0)),
      35.seconds
    )

    // Verify pod0 doesn't expire until 35 seconds.
    harness.advance(34.seconds, Seq.empty, 35.seconds)

    // Pod0 should expire at 35 seconds. Was Running → in crashed.
    harness.advance(
      35.seconds,
      createHealthReportOutput(healthy = Set.empty, newlyCrashedCount = 1),
      Duration.Inf
    )
  }

  test("Resource expiration stats track termination signal sources") {
    // Test plan: Verify that when resources expire from the health watcher, the expiration counter
    // is incremented with the correct termination signal source labels, and the delay histogram
    // records the time difference when both sources reported termination. Do this by creating 5
    // pods that each expire under different conditions, with staggered termination times so that
    // each pod expires separately and metrics can be verified after each expiration:
    //   pod0: expires without any termination signal (Running -> timeout at 35s)
    //   pod1: expires after termination from Slicelet only (at 31s, expires at 91s)
    //   pod2: expires after termination from Kubernetes only (at 32s, expires at 92s)
    //   pod3: expires after termination from both, K8s first (K8s at 33s, Slicelet at 35s,
    //         expires at 93s, delay = +2.0s)
    //   pod4: expires after termination from both, Slicelet first (Slicelet at 34s, K8s at 37s,
    //         expires at 94s, delay = -3.0s)
    // Use a unique target name to isolate metrics from other tests.
    val pod4: Squid = createTestSquid("http://pod4")
    val target: Target = Target(s"expiration-stats-$observeSliceletReadiness")
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))

    // Track changes in metric values.
    val noSignalExpirations = MetricUtils.ChangeTracker[Long] { () =>
      TargetMetricsUtils.getHealthWatcherResourceExpirations(
        target,
        receivedFromSlicelet = false,
        receivedFromKubernetes = false
      )
    }
    val sliceletOnlyExpirations = MetricUtils.ChangeTracker[Long] { () =>
      TargetMetricsUtils.getHealthWatcherResourceExpirations(
        target,
        receivedFromSlicelet = true,
        receivedFromKubernetes = false
      )
    }
    val k8sOnlyExpirations = MetricUtils.ChangeTracker[Long] { () =>
      TargetMetricsUtils.getHealthWatcherResourceExpirations(
        target,
        receivedFromSlicelet = false,
        receivedFromKubernetes = true
      )
    }
    val bothExpirations = MetricUtils.ChangeTracker[Long] { () =>
      TargetMetricsUtils.getHealthWatcherResourceExpirations(
        target,
        receivedFromSlicelet = true,
        receivedFromKubernetes = true
      )
    }
    val delayCount = MetricUtils.ChangeTracker[Int] { () =>
      TargetMetricsUtils.getHealthWatcherSliceletVsK8sTerminationSignalDelayCount(target)
    }
    val delaySum = MetricUtils.ChangeTracker[Double] { () =>
      TargetMetricsUtils.getHealthWatcherSliceletVsK8sTerminationSignalDelaySum(target)
    }

    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // All 5 pods report Running at 5s.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod4, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Emit first health report at 30s. All pods Running. crashed=Set.empty.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Set(pod0, pod1, pod2, pod3, pod4)),
      35.seconds
    )

    // pod1: Slicelet reports Terminating at 31s (expires at 31s + 60s = 91s).
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.Terminating),
      createHealthReportOutput(Set(pod0, pod2, pod3, pod4)),
      35.seconds
    )

    // pod2: Kubernetes reports Terminating at 32s (expires at 32s + 60s = 92s).
    harness.event(
      32.seconds,
      createSliceletStateFromKubernetesEvent(pod2.resourceUuid, SliceletState.Terminating),
      createHealthReportOutput(Set(pod0, pod3, pod4)),
      35.seconds
    )

    // pod3: Kubernetes reports Terminating at 33s (expires at 33s + 60s = 93s).
    harness.event(
      33.seconds,
      createSliceletStateFromKubernetesEvent(pod3.resourceUuid, SliceletState.Terminating),
      createHealthReportOutput(Set(pod0, pod4)),
      35.seconds
    )

    // pod4: Slicelet reports Terminating at 34s (expires at 34s + 60s = 94s).
    harness.event(
      34.seconds,
      createSliceletStateFromSliceletEvent(pod4, SliceletState.Terminating),
      createHealthReportOutput(Set(pod0)),
      35.seconds
    )

    // pod3: Slicelet reports Terminating at 35s (2 seconds after Kubernetes).
    // pod0 also expires at 35s (no termination signal, Running -> timeout). pod0 was Running →
    // in crashed.
    harness.event(
      35.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Terminating),
      createHealthReportOutput(healthy = Set.empty, newlyCrashedCount = 1),
      91.seconds
    )

    // Verify: pod0 expired with no termination signal.
    assert(noSignalExpirations.totalChange() == 1)
    assert(sliceletOnlyExpirations.totalChange() == 0)
    assert(k8sOnlyExpirations.totalChange() == 0)
    assert(bothExpirations.totalChange() == 0)
    assert(delayCount.totalChange() == 0)

    // pod4: Kubernetes reports Terminating at 37s (3 seconds after Slicelet).
    // pod4's expiry remains at 94s since it was already Terminating.
    harness.event(
      37.seconds,
      createSliceletStateFromKubernetesEvent(pod4.resourceUuid, SliceletState.Terminating),
      Seq.empty,
      91.seconds
    )

    // pod1 expires at 91s (Slicelet-only termination). Was Terminating → not in crashed.
    harness.advance(
      91.seconds,
      createHealthReportOutput(Set.empty),
      92.seconds
    )

    // Verify: pod1 expired with Slicelet-only termination signal.
    assert(noSignalExpirations.totalChange() == 1)
    assert(sliceletOnlyExpirations.totalChange() == 1)
    assert(k8sOnlyExpirations.totalChange() == 0)
    assert(bothExpirations.totalChange() == 0)
    assert(delayCount.totalChange() == 0)

    // pod2 expires at 92s (Kubernetes-only termination). Was Terminating → not in crashed.
    harness.advance(
      92.seconds,
      createHealthReportOutput(Set.empty),
      93.seconds
    )

    // Verify: pod2 expired with Kubernetes-only termination signal.
    assert(noSignalExpirations.totalChange() == 1)
    assert(sliceletOnlyExpirations.totalChange() == 1)
    assert(k8sOnlyExpirations.totalChange() == 1)
    assert(bothExpirations.totalChange() == 0)
    assert(delayCount.totalChange() == 0)

    // pod3 expires at 93s (both termination signals, K8s first).
    // pod3 was Terminating → not crashed.
    harness.advance(
      93.seconds,
      createHealthReportOutput(Set.empty),
      94.seconds
    )

    // Verify: pod3 expired with both termination signals and delay histogram recorded.
    assert(noSignalExpirations.totalChange() == 1)
    assert(sliceletOnlyExpirations.totalChange() == 1)
    assert(k8sOnlyExpirations.totalChange() == 1)
    assert(bothExpirations.totalChange() == 1)
    assert(delayCount.totalChange() == 1)
    // Delay = sliceletTime - k8sTime = 35s - 33s = 2.0s
    assert(delaySum.totalChange() == 2.0)

    // pod4 expires at 94s (both termination signals, Slicelet first).
    harness.advance(94.seconds, createHealthReportOutput(Set.empty), Duration.Inf)

    // Verify: pod4 expired with both termination signals and negative delay recorded.
    assert(noSignalExpirations.totalChange() == 1)
    assert(sliceletOnlyExpirations.totalChange() == 1)
    assert(k8sOnlyExpirations.totalChange() == 1)
    assert(bothExpirations.totalChange() == 2)
    assert(delayCount.totalChange() == 2)
    // Cumulative delay = 2.0s (pod3) + (-3.0s) (pod4) = -1.0s
    // pod4 delay = sliceletTime - k8sTime = 34s - 37s = -3.0s
    assert(delaySum.totalChange() == -1.0)
  }

  test("Podset size and health status metrics are recorded on each health report") {
    // Test plan: Verify that the HealthWatcher correctly records computed and Slicelet-reported
    // podset size metrics, and the computed-differs-from-reported counter, when it emits a health
    // report. Also verifies that the Terminating podset size resets to 0 once a Terminating pod
    // expires. Do this using a unique target to isolate metrics from other tests. Supply four
    // pods: pod0 (Running), pod1 (first heartbeat NOT_READY), pod2 (Terminating), and pod3
    // (Running, then NOT_READY at t=32). After the initial health report at t=30, verify:
    //   - Computed podset sizes reflect the HealthWatcher's computed statuses. pod1's computed
    //     status is Starting when observeSliceletReadiness=true and Running when false (masked).
    //     pod2 contributes Terminating=1. pod3 is Running.
    //   - Reported podset sizes reflect the raw Slicelet-reported statuses (pod0=Running,
    //     pod1=NotReady, pod2=Terminating, pod3=Running) regardless of masking.
    //   - The computed-differs-from-reported counter is incremented once for pod1, whose
    //     reported status (NotReady) diverges from its computed status (Starting or Running).
    // At t=31, heartbeats are sent for pod0, pod1, and pod3 to push their expiry past pod2's.
    // At t=32, pod3 receives a NOT_READY heartbeat (expiry=62), exercising the Running→NotReady
    // ignored case when !permitRunningToNotReady. After t=61 (pod0, pod1 expire), verify the
    // diff("NotReady", "Running") counter reflects pod3's ignored NotReady. At t=62 pod3 expires,
    // and at t=65 pod2 expires. Verify that Terminating resets to 0 after pod2 expires.
    val metricsTarget: Target =
      Target(s"podset-metrics-$observeSliceletReadiness-$permitRunningToNotReady")
    val harness: TestHarness =
      new TestHarness(DefaultFactory.create(metricsTarget, config, healthWatcherTargetConfig))
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // pod0: Running; pod1: first heartbeat NotReady (Starting or masked Running);
    // pod2: Terminating;
    // pod3: Running (will receive NotReady at t=32). All heartbeats at t=5; pod0/pod1/pod3 expire
    // at t=35, pod2 at t=5+60=65.
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.NotReady),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod2, SliceletState.Terminating),
      Seq.empty,
      30.seconds
    )
    harness.event(
      5.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial health report at t=30. Next expiry is pod0/pod1/pod3 at t=35.
    val expectedHealthy: Set[Squid] =
      if (observeSliceletReadiness) Set(pod0, pod3) else Set(pod0, pod1, pod3)
    harness.advance(
      30.seconds,
      createHealthReportOutput(expectedHealthy),
      35.seconds
    )

    // Verify computed podset sizes after first report.
    assert(
      TargetMetricsUtils.getPodSetSize(metricsTarget, "Running") ==
      (if (observeSliceletReadiness) 2 else 3) // pod0+pod3=2, or pod0+pod1+pod3=3 when masked
    )
    assert(
      TargetMetricsUtils.getPodSetSize(metricsTarget, "Starting") ==
      (if (observeSliceletReadiness) 1 else 0)
    )
    assert(TargetMetricsUtils.getPodSetSize(metricsTarget, "NotReady") == 0)
    assert(TargetMetricsUtils.getPodSetSize(metricsTarget, "Terminating") == 1)

    // Verify Slicelet-reported podset sizes (pod0=Running, pod1=NotReady, pod2=Terminating,
    // pod3=Running).
    assert(TargetMetricsUtils.getReportedPodSetSize(metricsTarget, "Running") == 2) // pod0, pod3
    assert(TargetMetricsUtils.getReportedPodSetSize(metricsTarget, "NotReady") == 1)
    assert(TargetMetricsUtils.getReportedPodSetSize(metricsTarget, "Terminating") == 1)

    // Verify the computed-differs-from-reported counter. pod1 reported NotReady but was computed
    // as Starting (observeSliceletReadiness=true) or Running (observeSliceletReadiness=false).
    val computedStatus: String = if (observeSliceletReadiness) "Starting" else "Running"
    assert(
      TargetMetricsUtils.getComputedStatusDiffers(metricsTarget, "NotReady", computedStatus) == 1
    )

    // Push pod0, pod1, and pod3 expiry past pod2's t=65. No status changes so no new report.
    // NotReady on a Starting pod stays Starting (line 533 of HealthWatcher.scala).
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod0, SliceletState.Running),
      Seq.empty,
      35.seconds // pod1 and pod3 still expire at t=35 until their own heartbeats
    )
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod1, SliceletState.NotReady),
      Seq.empty,
      35.seconds // pod3 still expires at t=35 until its own heartbeat
    )
    harness.event(
      31.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.Running),
      Seq.empty,
      61.seconds // pod0=61, pod1=61, pod3=61, pod2=65; next expiry is t=61
    )

    // Send pod3 NOT_READY at t=32, extending its expiry to t=62 (past pod0/pod1's t=61).
    // For permitRunningToNotReady=true: pod3 transitions to NotReady, dirty=true → report emitted.
    // For !permitRunningToNotReady: pod3's computed status stays Running (ignored), expiry extends.
    // For !observeSliceletReadiness: pod3's NotReady is masked to Running (no change),
    // expiry extends.
    val pod3NotReadyOutput: Seq[DriverAction] =
      if (permitRunningToNotReady)
        // pod3 just became NotReady; pod0 still Running; pod1 still Starting; pod3 still tracked
        // so not in crashed.
        createHealthReportOutput(healthy = Set(pod0))
      else
        Seq.empty
    harness.event(
      32.seconds,
      createSliceletStateFromSliceletEvent(pod3, SliceletState.NotReady),
      pod3NotReadyOutput,
      61.seconds // min(pod0=61, pod1=61, pod3=62, pod2=65) = 61
    )

    // At t=35 no expiry fires since pod0, pod1, and pod3 were all extended past t=35.
    harness.advance(35.seconds, Seq.empty, 61.seconds)

    // At t=61, pod0 and pod1 expire. pod2 (Terminating) and pod3 (expiry=62) still alive.
    // pod3 is Running (computed) for !permitRunningToNotReady, NotReady for
    // permitRunningToNotReady.
    harness.advance(
      61.seconds,
      createHealthReportOutput(
        healthy = if (permitRunningToNotReady) Set.empty else Set(pod3),
        newlyCrashedCount = if (observeSliceletReadiness) 1 else 2
      ),
      62.seconds // pod3 expires next
    )

    // Verify the Running→NotReady ignored diff case. Pod3's last heartbeat was NOT_READY but its
    // computed status is Running (ignored) when !permitRunningToNotReady, so the report at t=61
    // counts it as diff(reportedStatus=NotReady, computedStatus=Running).
    // For !observeSliceletReadiness,
    // pod3's NOT_READY was masked to Running at t=32 (same diff path), adding to pod1's count
    // from t=30. For permitRunningToNotReady=true, pod3 is NotReady/NotReady so no diff.
    assert(
      TargetMetricsUtils.getComputedStatusDiffers(metricsTarget, "NotReady", "Running") ==
      (if (!observeSliceletReadiness) 2 else if (!permitRunningToNotReady) 1 else 0)
    )

    // Terminating is still 1 while pod2 is alive.
    assert(TargetMetricsUtils.getPodSetSize(metricsTarget, "Terminating") == 1)

    // At t=62, pod3 expires. For permitRunningToNotReady=true, pod3 was NotReady and not in
    // previouslyHealthy (removed at t=32 intermediate report) → not in crashed. For other
    // configs, pod3 was Running and in previouslyHealthy → crashed.
    harness.advance(
      62.seconds,
      createHealthReportOutput(
        healthy = Set.empty,
        newlyCrashedCount = if (permitRunningToNotReady) 0 else 1
      ),
      65.seconds // pod2 (Terminating) expires next
    )

    // At t=65, pod2 (Terminating) expires. It was never healthy so it is not in crashed.
    harness.advance(65.seconds, createHealthReportOutput(Set.empty), Duration.Inf)

    // Terminating resets to 0 once pod2 expires.
    assert(TargetMetricsUtils.getPodSetSize(metricsTarget, "Terminating") == 0)
  }
}

// Note: (observeSliceletReadiness = false, permitRunningToNotReady = true) is not a meaningful
// configuration to test. When observeSliceletReadiness is false, all NotReady outcomes are masked
// to Running, so the Running -> NotReady transition can never occur and permitRunningToNotReady is
// a no-op. Its behavior is identical to (observeSliceletReadiness = false,
// permitRunningToNotReady = false).
private class HealthWatcherWithStatusMaskingSuite
    extends HealthWatcherSuite(observeSliceletReadiness = false, permitRunningToNotReady = false)

private class HealthWatcherWithoutStatusMaskingSuite
    extends HealthWatcherSuite(observeSliceletReadiness = true, permitRunningToNotReady = false)

private class HealthWatcherPermitRunningToNotReadySuite
    extends HealthWatcherSuite(observeSliceletReadiness = true, permitRunningToNotReady = true)
