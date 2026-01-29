package com.databricks.dicer.assigner

import com.databricks.caching.util._
import com.databricks.dicer.assigner.HealthWatcher.HealthStatus.{
  NotReady,
  Running,
  Terminating,
  Unknown
}
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
 */
private abstract class HealthWatcherSuite(observeSliceletReadiness: Boolean)
    extends DatabricksTest
    with TestUtils.ParameterizedTestNameDecorator {

  private val GENERATION = Generation(Incarnation(42), number = 43)

  /** The target used in the tests. */
  private val target = Target("softstore")

  /** The default health config for the health watcher for tests. */
  private val config =
    HealthWatcher.StaticConfig(
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds
    )

  /** Determines whether the Assigner will mask the Slicelet's reported health status */
  private val healthWatcherTargetConfig: HealthWatcherTargetConfig =
    HealthWatcherTargetConfig(
      observeSliceletReadiness = observeSliceletReadiness
    )

  override def paramsForDebug: Map[String, Any] = Map(
    "observeSliceletReadiness" -> observeSliceletReadiness
  )

  /** Creates a [[Event.HealthStatusObserved]] event. */
  private def createHealthStatusEvent(resource: Squid, healthStatus: HealthStatus): Event = {
    Event.HealthStatusObserved(resource, healthStatus)
  }

  /** Creates a [[Event.HealthStatusObservedByUuid]] event. */
  private def createHealthStatusByUuidEvent(
      resourceUuid: UUID,
      healthStatus: HealthStatus): Event = {
    Event.HealthStatusObservedByUuid(resourceUuid, healthStatus)
  }

  /** Creates a health report state machine output. */
  private def createHealthReportOutput(
      report: Map[Squid, HealthStatusReport]): Seq[DriverAction] = {
    Seq(DriverAction.IncorporateHealthReport(report))
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

  // Some common health status reports.
  private val runningReport: HealthStatusReport = HealthStatusReport(Running, Running)
  private val unknownReport: HealthStatusReport = HealthStatusReport(Unknown, Unknown)
  private val terminatingReport: HealthStatusReport = HealthStatusReport(Terminating, Terminating)

  // If masking is enabled, a NotReady status will be masked to Running by the HealthWatcher.
  private val notReadyReport: HealthStatusReport =
    HealthStatusReport(NotReady, if (observeSliceletReadiness) NotReady else Running)

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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(6.seconds, createHealthStatusEvent(pod3, Running), Seq.empty, 30.seconds)
    harness.event(7.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)

    // Advance to the expected initial report time. Expect next event at 35 seconds for pod0 expiry.
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        Map(pod0 -> runningReport, pod1 -> runningReport, pod3 -> runningReport)
      ),
      35.seconds // expected expiry time for pod0
    )

    // Have the last Slicelet signal it's healthy.
    harness.event(
      32.seconds,
      createHealthStatusEvent(pod2, Running),
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> runningReport,
          pod2 -> runningReport,
          pod3 -> runningReport
        )
      ),
      35.seconds // expected expiry time for pod0
    )
  }

  test(
    "Pod automatically transitions to the Running state when readiness status masking is enabled"
  ) {
    // Test plan: verify that the HealthWatcher masks a Slicelet's status from NotReady to Running
    // if the HealthWatcher is configured to do so.

    val watcher =
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
      createHealthStatusEvent(pod0, NotReady),
      createHealthReportOutput(Map(pod0 -> notReadyReport)),
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)

    // Advance to the initial report time and verify the expected map is returned. Expect pod0 to
    // expire at 35 seconds.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Map(pod0 -> runningReport)),
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod3, Running), Seq.empty, 30.seconds)

    // Watcher should yield the same output until the initial report time.
    for (i: Int <- 6 to 29) {
      harness.advance(i.seconds, Seq.empty, 30.seconds)
    }
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        Map(pod0 -> runningReport, pod1 -> runningReport, pod3 -> runningReport)
      ),
      35.seconds // next expiry time
    )
  }

  test("Pod goes from Healthy to unhealthy and vice versa") {
    // Test plan: Create a watcher, inform it of a pod and have it change its health status a couple
    // of times. Make sure that the health watcher's state is as expected.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)

    // Advance to the initial report time.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Map(pod0 -> runningReport)),
      35.seconds // expected expiry time for pod0
    )

    // Make this pod unhealthy.
    harness.event(
      31.seconds,
      createHealthStatusEvent(pod0, Unknown),
      createHealthReportOutput(Map(pod0 -> unknownReport)),
      61.seconds // expected extended expiry time for pod0
    )

    // Make it healthy again.
    harness.event(
      32.seconds,
      createHealthStatusEvent(pod0, Running),
      createHealthReportOutput(Map(pod0 -> runningReport)),
      62.seconds // expected extended expiry time for pod0
    )
  }

  test("Pod goes from NotReady to Unknown and vice versa") {
    // Test plan: verify that a resource reporting NotReady is transitioned to the Unknown state
    // after receiving an Unknown health report, and verify that it moves back to the appropriate
    // status (NotReady if masking is disabled, otherwise Running) after receiving a NotReady health
    // report.
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, NotReady), Seq.empty, 30.seconds)

    // Advance to the initial report time.
    harness.advance(
      30.seconds,
      createHealthReportOutput(Map(pod0 -> notReadyReport)),
      35.seconds // expected expiry time for pod0
    )

    // Make this pod unhealthy.
    harness.event(
      31.seconds,
      createHealthStatusEvent(pod0, Unknown),
      createHealthReportOutput(Map(pod0 -> unknownReport)),
      61.seconds // expected extended expiry time for pod0
    )

    // Make it healthy again.
    harness.event(
      32.seconds,
      createHealthStatusEvent(pod0, NotReady),
      createHealthReportOutput(Map(pod0 -> notReadyReport)),
      62.seconds // expected extended expiry time for pod0
    )
  }

  test("Pods go from healthy to unhealthy via time and then some back") {
    // Test plan: Create a watcher, inform it about multiple pods and have them change their health
    // status a couple of times. Make sure that the health watcher's state is as expected.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod2, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod3, Running), Seq.empty, 30.seconds)

    // Advance to the initial report time.
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> runningReport,
          pod2 -> runningReport,
          pod3 -> runningReport
        )
      ),
      35.seconds
    )

    // Let time pass and only update 2 of them within 1 nanosecond of the unhealthy period. So the
    // last heartbeat time for these 2 pods will be updated.
    harness.event(
      35.second - 1.nanosecond,
      createHealthStatusEvent(pod0, Running),
      Seq.empty,
      35.seconds
    )
    harness.event(
      35.second - 1.nanosecond,
      createHealthStatusEvent(pod2, Running),
      Seq.empty,
      35.seconds
    )

    // The two pods have now reported their status. Advance the time and the other pods should be
    // removed.
    harness.advance(
      35.seconds,
      createHealthReportOutput(Map(pod0 -> runningReport, pod2 -> runningReport)),
      65.seconds - 1.nanosecond
    )

    // pod 1 goes healthy again. Check that the watcher's state has 3 pods.
    harness.event(
      35.seconds,
      createHealthStatusEvent(pod1, Running),
      createHealthReportOutput(
        Map(pod0 -> runningReport, pod1 -> runningReport, pod2 -> runningReport)
      ),
      65.seconds - 1.nanosecond
    )
  }

  test("Pods marked as terminating") {
    // Test plan: Create a watcher, inform it about the four pods, two in the NotReady and two in
    // Running states. Report one pod in each starting state as terminating and verify that the
    // HealthWatcher reflects the new terminating pods in a health report.
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod2, NotReady), Seq.empty, 30.seconds)
    harness.event(5.seconds, createHealthStatusEvent(pod3, NotReady), Seq.empty, 30.seconds)

    // Advance beyond the initial report time.
    harness.advance(
      31.seconds,
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> runningReport,
          pod2 -> notReadyReport,
          pod3 -> notReadyReport
        )
      ),
      35.seconds
    )

    // Let time pass and refresh the existing health statuses of all resources to prevent them
    // from being expired as we continue to advance the clock.
    harness.event(34.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 35.seconds)
    harness.event(34.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 35.seconds)
    harness.event(34.seconds, createHealthStatusEvent(pod2, NotReady), Seq.empty, 35.seconds)
    harness.event(34.seconds, createHealthStatusEvent(pod3, NotReady), Seq.empty, 64.seconds)

    harness.event(
      34.seconds,
      createHealthStatusByUuidEvent(pod1.resourceUuid, Terminating),
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> terminatingReport,
          pod2 -> notReadyReport,
          pod3 -> notReadyReport
        )
      ),
      64.seconds
    )
    harness.event(
      34.seconds,
      createHealthStatusByUuidEvent(pod3.resourceUuid, Terminating),
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> terminatingReport,
          pod2 -> notReadyReport,
          pod3 -> terminatingReport
        )
      ),
      64.seconds
    )

    // Advance time to just before pod0 and pod2 should expire.
    harness.advance(63.seconds, Seq.empty, 64.seconds)

    // pod0 and pod2 are now expired.
    harness.advance(
      64.seconds,
      createHealthReportOutput(Map(pod1 -> terminatingReport, pod3 -> terminatingReport)),
      94.seconds
    )

    // pod1 and pod3 are now terminated and should also be expired.
    harness.advance(94.seconds, createHealthReportOutput(Map()), Duration.Inf)
  }

  test("Pod without SQUID does not show up in health report") {
    // Test plan: Mark a pod as terminating without SQUID, and expect it not show up in
    // IncorporateHealthReport.
    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)

    // While the watcher learns about the initial health of pods, the output should be empty and
    // request us to callback after the initial report delay.
    harness.event(
      5.seconds,
      createHealthStatusByUuidEvent(pod0.resourceUuid, Terminating),
      Seq.empty,
      30.seconds
    )

    // pod0 does not show up in health report, since it does not have a SQUID.
    harness.advance(30.seconds, createHealthReportOutput(Map()), 65.seconds)
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
      createHealthStatusByUuidEvent(pod0.resourceUuid, Terminating),
      Seq.empty,
      30.seconds
    )

    // pod0 does not show up in health report, since it does not have a SQUID.
    harness.advance(30.seconds, createHealthReportOutput(Map()), 65.seconds)

    // pod0's Terminating status expiry does not change.
    harness.event(
      35.seconds,
      createHealthStatusByUuidEvent(pod0.resourceUuid, Terminating),
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Map(pod0 -> runningReport)), 35.seconds)

    // Create a new SQUID with same address and UUID but a more recent creation time. Do this before
    // the expiry time of the existing entry to verify that the existing entry is updated.
    val newPod0: Squid = pod0.copy(creationTimeMillis = pod0.creationTimeMillis + 100)
    harness.event(
      31.seconds,
      createHealthStatusEvent(newPod0, Running),
      createHealthReportOutput(Map(newPod0 -> runningReport)),
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Map(pod0 -> runningReport)), 35.seconds)

    // Create an older SQUID with same address, UUID but an older creation time.
    val oldPod0: Squid = pod0.copy(creationTimeMillis = pod0.creationTimeMillis - 100)
    harness.event(
      31.seconds,
      createHealthStatusEvent(oldPod0, Running),
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
      createHealthStatusByUuidEvent(pod0.resourceUuid, Terminating),
      Seq.empty,
      30.seconds
    )

    // Advance to the initial report time.
    harness.advance(30.seconds, createHealthReportOutput(Map()), 65.seconds)

    // Report the HealthStatus as Running with SQUID, status expiry remains same but we add SQUID
    // and update the report.
    harness.event(
      45.seconds,
      createHealthStatusEvent(pod0, Running),
      createHealthReportOutput(Map(pod0 -> HealthStatusReport(Running, Terminating))),
      65.seconds
    )
  }

  test("NOT_READY to RUNNING is one-way") {
    // Test plan: Verify that being informed of RUNNING for a NOT_READY resource transitions its
    // state to RUNNING, while receiving NOT_READY messages for a RUNNING resource only extends its
    // expiry time without transitioning its state.
    val harness =
      new TestHarness(
        DefaultFactory.create(
          target,
          config,
          healthWatcherTargetConfig
        )
      )
    harness.advance(Duration.Zero, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    harness.event(
      5.seconds,
      createHealthStatusEvent(pod0, NotReady),
      Seq.empty,
      30.seconds
    )

    harness.advance(
      30.seconds,
      createHealthReportOutput(Map(pod0 -> notReadyReport)),
      35.seconds
    )

    harness.event(
      33.seconds,
      createHealthStatusEvent(pod0, NotReady),
      Seq.empty,
      63.seconds
    )

    harness.event(
      34.seconds,
      createHealthStatusEvent(pod0, Running),
      if (observeSliceletReadiness) {
        createHealthReportOutput(Map(pod0 -> runningReport))
      } else {
        // The Slicelet has already been reporting Running; since there is no change in the
        // Slicelet's health status, we don't report anything.
        Seq.empty
      },
      64.seconds
    )

    harness.event(
      40.seconds,
      createHealthStatusEvent(pod0, NotReady),
      Seq.empty,
      70.seconds
    )
  }

  test("Bootstraps health by observed assignment from slicelets") {
    // Test plan: Verify that the watcher bootstraps the health of assigned resources for which it
    // has no authoritative signals using the assignment observed from slicelets. Verify this by
    // delivering health signals to the watcher for various pods (including running, terminating,
    // and unknown) and then telling the watcher about an assignment sync-ed from a slicelet that
    // includes other pods, but also excludes some pods that are known. Check that the resulting
    // health report includes the pods that were not previously known to the watcher,
    // as well as the correct status for all pods with authoritative health information. Also verify
    // that the bootstrapped health expires at the unhealthy timeout period past the watcher's start
    // time.

    val harness =
      new TestHarness(DefaultFactory.create(target, config, healthWatcherTargetConfig))
    // Advance the watcher to initialize its start time and enter the starting state.
    harness.advance(Duration.Zero, Seq.empty, Duration.Inf)
    // Tell the watcher about pods 0,1 (Running), 2 (Unknown), and 3 (Terminating).
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(6.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)
    harness.event(7.seconds, createHealthStatusEvent(pod2, Unknown), Seq.empty, 30.seconds)
    harness.event(8.seconds, createHealthStatusEvent(pod3, Terminating), Seq.empty, 30.seconds)
    // Tell the watcher about the latest set of assigned resources, which includes pods 1, 2, and 3,
    // as well as pods 4 and 5. The assignment notably excludes pod 0 so that we can verify that
    // pods not in the set of assigned resources also show up in the health report (i.e. aren't
    // eliminated due to the assignment).
    val pod4: Squid = createTestSquid("http://pod4")
    val pod5: Squid = createTestSquid("http://pod5")
    harness.event(
      9.seconds,
      createAssignmentObserved(
        GENERATION,
        AssignmentDistributionSource.Slicelet,
        Set(pod1, pod2, pod3, pod4, pod5)
      ),
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> runningReport,
          pod2 -> unknownReport,
          pod3 -> terminatingReport,
          pod4 -> runningReport,
          pod5 -> runningReport
        )
      ),
      30.seconds // expiry of pods 4 and 5 whose health was bootstrapped
    )
    // Verify that pods 4+5 expire at the unhealthy timeout period past the watcher's start time.
    harness.advance(
      30.seconds,
      createHealthReportOutput(
        Map(
          pod0 -> runningReport,
          pod1 -> runningReport,
          pod2 -> unknownReport,
          pod3 -> terminatingReport
        )
      ),
      35.seconds // expiry of pod 0 is next
    )
    harness.advance(
      35.seconds,
      createHealthReportOutput(
        Map(pod1 -> runningReport, pod2 -> unknownReport, pod3 -> terminatingReport)
      ),
      36.seconds // expiry of pod 1 is next
    )
    harness.advance(
      36.seconds,
      createHealthReportOutput(Map(pod2 -> unknownReport, pod3 -> terminatingReport)),
      37.seconds // expiry of pod2 is next
    )
    // expiry of pod3 is next
    harness.advance(
      37.seconds,
      createHealthReportOutput(Map(pod3 -> terminatingReport)),
      68.seconds
    )
    harness.advance(68.seconds, createHealthReportOutput(Map()), Duration.Inf)
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
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds
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
      createHealthStatusEvent(pod4, Running),
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
      createHealthStatusEvent(pod5, Running),
      actions = Seq.empty,
      nextTimeOffset = 30.seconds
    )

    // Advance to a time past the initial report delay. Verify only the pods with authoritative
    // health signals (pod4 and pod5) are used in the load report, and the assignments from
    // unreliable sources are ignord.
    harness.advance(
      30.seconds,
      actions = createHealthReportOutput(Map(pod4 -> runningReport, pod5 -> runningReport)),
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
        createHealthReportOutput(Map(pod0 -> runningReport, pod1 -> runningReport)),
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
        createHealthReportOutput(Map(pod0 -> runningReport, pod1 -> runningReport)),
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
        createHealthReportOutput(Map(pod0 -> runningReport, pod1 -> runningReport)),
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

      // G4 = 44 observed from reliable source. Bootstrapping health using G4 as G3 is the highest
      // reliable generation observed and we have G4 > G3.
      harness.event(
        15.seconds,
        createAssignmentObserved(
          generation4,
          unreliableSource,
          Set(pod2, pod3)
        ),
        // Bootstrapped with resources in G4.
        createHealthReportOutput(Map(pod2 -> runningReport, pod3 -> runningReport)),
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
        createHealthStatusEvent(pod3, Running),
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
        createHealthReportOutput(Map(pod3 -> runningReport)),
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
      unhealthyTimeoutPeriod = 30.seconds,
      terminatingTimeoutPeriod = 60.seconds
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
      actions = createHealthReportOutput(Map(pod0 -> runningReport, pod1 -> runningReport)),
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
      createHealthReportOutput(Map(pod0 -> runningReport)),
      30.seconds
    )
    // Deliver new heartbeat for pod0 and verify the watcher updates the health status.
    harness.event(6.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 36.seconds)
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
      createHealthStatusByUuidEvent(pod0.resourceUuid, Terminating),
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
      createHealthReportOutput(Map(pod1 -> runningReport)),
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
      createHealthReportOutput(
        Map(
          pod1 -> runningReport,
          pod2 -> runningReport,
          pod3 -> runningReport,
          pod4 -> runningReport
        )
      ),
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
    harness.event(5.seconds, createHealthStatusEvent(pod0, Running), Seq.empty, 30.seconds)
    harness.event(6.seconds, createHealthStatusEvent(pod3, Running), Seq.empty, 30.seconds)
    harness.event(7.seconds, createHealthStatusEvent(pod1, Running), Seq.empty, 30.seconds)

    // Verify: `toString` returns the expected representation.
    assert(
      healthWatcher.toString ==
      """Health summary :
    |┌──────────────────────────────────────┬─────────┬─────────────┐
    |│ Resource                             │ Health  │ Expiry Time │
    |├──────────────────────────────────────┼─────────┼─────────────┤
    |│ 15322af9-366d-3d09-b784-b8bb3dbf4890 │ Running │ 35.0 )      │
    |│ 9f84c9f1-caa8-33ab-8ac6-2a3a52df666e │ Running │ 36.0 )      │
    |│ 78dad640-7391-3916-833e-504e395c636c │ Running │ 37.0 )      │
    |└──────────────────────────────────────┴─────────┴─────────────┘
    |""".stripMargin
    )
  }
}

private class HealthWatcherWithStatusMaskingSuite
    extends HealthWatcherSuite(observeSliceletReadiness = false)

private class HealthWatcherWithoutStatusMaskingSuite
    extends HealthWatcherSuite(observeSliceletReadiness = true)
