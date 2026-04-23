package com.databricks.dicer.assigner

import java.net.URI
import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._

import com.databricks.caching.util.{StateMachineOutput, TickerTime, UnixTimeVersion}
import com.databricks.dicer.assigner.ConsistentHashingPreferredAssignerStateMachine.{
  DriverAction,
  Event
}
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.testing.DatabricksTest

/** Unit tests for the [[ConsistentHashingPreferredAssignerStateMachine]]. */
class ConsistentHashingPreferredAssignerStateMachineSuite extends DatabricksTest {

  /**
   * Expected sentinel [[Generation]] that the state machine stamps onto every emitted
   * [[PreferredAssignerValue]]. Mirrors the private `DUMMY_GENERATION` constant in
   * [[ConsistentHashingPreferredAssignerStateMachine]].
   */
  private val EXPECTED_DUMMY_GENERATION: Generation =
    Generation(Incarnation.MIN, UnixTimeVersion.MIN)

  /** AssignerInfo for the state machine's own assigner. */
  private val selfAssignerInfo: AssignerInfo = AssignerInfo(
    uuid = UUID.fromString("11111111-1234-5678-abcd-000000000001"),
    uri = new URI("https://self-assigner:8080")
  )

  /** AssignerInfo for another assigner. */
  private val otherAssignerInfo: AssignerInfo = AssignerInfo(
    uuid = UUID.fromString("22222222-1234-5678-abcd-000000000002"),
    uri = new URI("https://other-assigner:8080")
  )

  /** AssignerInfo for a third assigner. */
  private val thirdAssignerInfo: AssignerInfo = AssignerInfo(
    uuid = UUID.fromString("33333333-1234-5678-abcd-000000000003"),
    uri = new URI("https://third-assigner:8080")
  )

  /** Creates a state machine with default test configuration. */
  private def createStateMachine(): ConsistentHashingPreferredAssignerStateMachine = {
    new ConsistentHashingPreferredAssignerStateMachine(selfAssignerInfo)
  }

  /** Converts assigner infos to the resource map the driver would create. */
  private def toResourceMap(assigners: AssignerInfo*): Map[UUID, AssignerInfo] = {
    assigners.map { info: AssignerInfo =>
      info.uuid -> info
    }.toMap
  }

  /**
   * A test harness that delivers events and advances to a state machine and asserts the exact
   * [[StateMachineOutput]] (actions and next advance time). Follows the pattern from
   * [[HealthWatcherSuite]].
   *
   * @param stateMachine The state machine under test.
   */
  private class TestHarness(stateMachine: ConsistentHashingPreferredAssignerStateMachine) {

    /**
     * Delivers `event` at `timeOffset` and asserts `actions` and `nextTimeOffset` were requested.
     */
    def event(
        timeOffset: FiniteDuration,
        event: Event,
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      deliver(timeOffset, Some(event), actions, nextTimeOffset)
    }

    /**
     * Advances the state machine at `timeOffset` and asserts `actions` and `nextTimeOffset`
     * were requested.
     */
    def advance(
        timeOffset: FiniteDuration,
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      deliver(timeOffset, None, actions, nextTimeOffset)
    }

    /**
     * Delivers `eventOpt` at `timeOffset` and asserts `actions` and `nextTimeOffset` were
     * requested. If `eventOpt` is None, the state machine is advanced instead.
     */
    private def deliver(
        timeOffset: FiniteDuration,
        eventOpt: Option[Event],
        actions: Seq[DriverAction],
        nextTimeOffset: Duration): Unit = {
      val tickerTime: TickerTime = TickerTime.ofNanos(timeOffset.toNanos)
      val instant: Instant =
        Instant.ofEpochSecond(timeOffset.toSeconds, timeOffset.toNanos % 1000000000)
      val output: StateMachineOutput[DriverAction] = eventOpt match {
        case Some(e: Event) => stateMachine.onEvent(tickerTime, instant, e)
        case None => stateMachine.onAdvance(tickerTime, instant)
      }
      val expectedNextTickerTime: TickerTime = nextTimeOffset match {
        case offset: FiniteDuration => TickerTime.ofNanos(offset.toNanos)
        case _ => TickerTime.MAX
      }
      assert(
        output == StateMachineOutput(expectedNextTickerTime, actions),
        s"Output mismatch at t=$timeOffset. Expected actions=$actions, nextTime=$nextTimeOffset. " +
        s"Got actions=${output.actions}, nextTime=${output.nextTickerTime}"
      )
    }
  }

  test("ResourceSetReceived with self selected transitions to Preferred") {
    // Test plan: Verify that when only self is in the resource set, the SM transitions to
    // Preferred with a redirect to self.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("ResourceSetReceived with other selected transitions to Standby") {
    // Test plan: Verify that when only another assigner is in the resource set, the SM
    // transitions to Standby with a redirect to that assigner.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(otherAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("ResourceSetReceived with empty set after Preferred transitions to Ineligible") {
    // Test plan: Verify that when the resource set becomes empty after being Preferred, the SM
    // transitions to Ineligible with a NoAssigner config.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Establish Preferred state.
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Empty resource set transitions to Ineligible.
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(ResourceVersion("2"), Map.empty),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("pod set changes cause correct state transitions") {
    // Test plan: Verify Standby → Preferred → Standby transitions as the resource set changes.
    // The hash ring deterministically picks otherAssignerInfo when both are present.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    // Confirm our assumption about the hash ring ordering for these UUIDs.
    val selectedOpt: Option[UUID] = PreferredAssignerSelector.selectPreferredAssigner(
      Set(selfAssignerInfo.uuid, otherAssignerInfo.uuid)
    )
    assert(
      selectedOpt.contains(otherAssignerInfo.uuid),
      "Test assumes hash ring selects otherAssignerInfo; update UUIDs if this fails"
    )

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Both present — hash ring picks other → Standby.
    harness.event(
      1.second,
      Event.ResourceSetReceived(
        ResourceVersion("1"),
        toResourceMap(selfAssignerInfo, otherAssignerInfo)
      ),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Remove other — only self remains → Preferred.
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(ResourceVersion("2"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Add other back → Standby.
    harness.event(
      3.seconds,
      Event.ResourceSetReceived(
        ResourceVersion("3"),
        toResourceMap(selfAssignerInfo, otherAssignerInfo)
      ),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("pod set changes without changing preferred does not emit config") {
    // Test plan: Verify that when the resource set changes but the hash ring still selects the
    // same preferred, no config is emitted. With {self, other} the ring picks other. Adding
    // third should still pick other.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    val selectedWithThird: Option[UUID] = PreferredAssignerSelector.selectPreferredAssigner(
      Set(selfAssignerInfo.uuid, otherAssignerInfo.uuid, thirdAssignerInfo.uuid)
    )
    assert(
      selectedWithThird.contains(otherAssignerInfo.uuid),
      "Test assumes hash ring still selects otherAssignerInfo with all three; update UUIDs if fails"
    )

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Establish preferred = other.
    harness.event(
      1.second,
      Event.ResourceSetReceived(
        ResourceVersion("1"),
        toResourceMap(selfAssignerInfo, otherAssignerInfo)
      ),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Add third — preferred doesn't change, no actions.
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(
        ResourceVersion("2"),
        toResourceMap(selfAssignerInfo, otherAssignerInfo, thirdAssignerInfo)
      ),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )
  }

  test("SuppressionNotice(suppress=true) with no resources stays Ineligible") {
    // Test plan: Verify that if selection is suppressed when no resources have been received,
    // the SM stays Ineligible and emits no actions. On unsuppression, it also stays Ineligible.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Suppressed with no resources — stays Ineligible, no action.
    harness.event(
      1.second,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Unsuppress — still Ineligible (no resources), no action.
    harness.event(
      2.seconds,
      Event.SuppressionNotice(shouldSuppress = false),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )
  }

  test("SuppressionNotice(suppress=true) transitions to Ineligible") {
    // Test plan: Verify that when selection is suppressed after being Preferred, the SM transitions
    // to Ineligible and emits a NoAssigner config.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    harness.event(
      2.seconds,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("SuppressionNotice(suppress=false) after Ineligible resumes from retained resources") {
    // Test plan: Verify that recovery from Ineligible re-evaluates the retained resource set.
    // The SM does not clear latestResources when suppressed, so it resumes the previous
    // selection when suppression is lifted.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
    harness.event(
      2.seconds,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Unsuppress — latestResources still contains self, so transitions back to Preferred.
    harness.event(
      3.seconds,
      Event.SuppressionNotice(shouldSuppress = false),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("ResourceSetReceived while Ineligible is stored and used on recovery") {
    // Test plan: Verify that resources arriving while suppressed are stored (version-checked) but
    // not acted upon until suppression is lifted. On unsuppression, the latest stored resources
    // determine the new state.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
    harness.event(
      2.seconds,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Resource set while suppressed — stored but SM stays Ineligible.
    harness.event(
      3.seconds,
      Event.ResourceSetReceived(ResourceVersion("2"), toResourceMap(otherAssignerInfo)),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Unsuppress — uses the resources received while suppressed.
    harness.event(
      4.seconds,
      Event.SuppressionNotice(shouldSuppress = false),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("duplicate resource set does not emit config") {
    // Test plan: Verify that sending the same resource set twice does not emit a second config.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Same version and resources — no actions.
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )
  }

  test("duplicate SuppressionNotice does not cause re-transition") {
    // Test plan: Verify that duplicate mode change signals are deduplicated. The SM tracks
    // isSuppressed and only acts on transitions (unsuppressed→suppressed or vice versa).
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // First suppress — emits config.
    harness.event(
      2.seconds,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Second suppress — no actions.
    harness.event(
      3.seconds,
      Event.SuppressionNotice(shouldSuppress = true),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )
  }

  test("out-of-order resource set is rejected") {
    // Test plan: Verify that an older-versioned resource set is rejected after a newer one has
    // been accepted. ResourceVersion compares by length first, then lexicographically.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    // Accept version "2".
    harness.event(
      1.second,
      Event.ResourceSetReceived(ResourceVersion("2"), toResourceMap(selfAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Version "1" is older — should be rejected (no actions).
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(ResourceVersion("1"), toResourceMap(otherAssignerInfo)),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Version "3" is newer — should be accepted.
    harness.event(
      3.seconds,
      Event.ResourceSetReceived(ResourceVersion("3"), toResourceMap(otherAssignerInfo)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(otherAssignerInfo, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          )
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }
}
