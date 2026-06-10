package com.databricks.dicer.assigner

import java.net.URI
import java.nio.charset.StandardCharsets
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
    uuid = UUID.fromString("11111111-1234-5678-0000-000000000001"),
    uri = new URI("https://self-assigner:8080")
  )

  /** AssignerInfo for another assigner. */
  private val otherAssignerInfo: AssignerInfo = AssignerInfo(
    uuid = UUID.fromString("22222222-1234-5678-0000-000000000001"),
    uri = new URI("https://other-assigner:8080")
  )

  /** AssignerInfo for a third assigner. */
  private val thirdAssignerInfo: AssignerInfo = AssignerInfo(
    uuid = UUID.fromString("33333333-1234-5678-0000-000000000002"),
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
     * requested. If `eventOpt` is None, the state machine is advanced instead. At each step,
     * validate the state machine's invariants.
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
      stateMachine.forTest.checkInvariants()
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("ResourceSetReceived with self selected but different URI transitions to Preferred") {
    // Test plan: Verify that when the resource set contains an AssignerInfo with the same
    // UUID as self but a different URI (e.g. different scheme), the SM still recognizes
    // self and transitions to Preferred. The resource watcher may construct AssignerInfo
    // with a URI scheme that differs from the locally-constructed selfAssignerInfo, so
    // self-recognition must rely on UUID alone.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

    val selfWithUriMismatch: AssignerInfo = AssignerInfo(
      uuid = selfAssignerInfo.uuid,
      uri = new URI("//self-assigner:8080")
    )
    assert(selfWithUriMismatch != selfAssignerInfo) // Make sure the values don't match.

    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)
    harness.event(
      1.second,
      Event.ResourceSetReceived(
        ResourceVersion("1"),
        toResourceMap(selfWithUriMismatch)
      ),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue
              .SomeAssigner(selfWithUriMismatch, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          ),
          eligibleAssigners = Seq(selfWithUriMismatch)
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("ResourceSetReceived with same UUIDs but different URIs does not emit config") {
    // Test plan: Verify that a resource set update with unchanged UUIDs but a different URI
    // for a non-preferred assigner does not emit a config (the selected AssignerInfo is
    // unchanged), and that [[latestResources]] is nonetheless updated with the new URI.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)

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
          ),
          eligibleAssigners = Seq(selfAssignerInfo, otherAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )

    // Same UUIDs, new URI for self. Selected preferred remains other, so no config is emitted.
    val selfWithNewUri: AssignerInfo = AssignerInfo(
      uuid = selfAssignerInfo.uuid,
      uri = new URI("https://self-assigner-new:9090")
    )
    harness.event(
      2.seconds,
      Event.ResourceSetReceived(
        ResourceVersion("2"),
        toResourceMap(selfWithNewUri, otherAssignerInfo)
      ),
      actions = Seq.empty,
      nextTimeOffset = Duration.Inf
    )

    // Remove other, so that preferred = self. Use this to verify that the URI change took place.
    harness.event(
      3.seconds,
      Event.ResourceSetReceived(ResourceVersion("3"), toResourceMap(selfWithNewUri)),
      actions = Seq(
        DriverAction.UsePreferredAssignerConfig(
          PreferredAssignerConfig.create(
            PreferredAssignerValue.SomeAssigner(selfWithNewUri, EXPECTED_DUMMY_GENERATION),
            selfAssignerInfo
          ),
          eligibleAssigners = Seq(selfWithNewUri)
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
          ),
          eligibleAssigners = Seq(otherAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo),
          eligibleAssigners = Seq.empty
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo, otherAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo, otherAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo, otherAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo),
          eligibleAssigners = Seq.empty
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo),
          eligibleAssigners = Seq.empty
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo),
          eligibleAssigners = Seq.empty
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
          ),
          eligibleAssigners = Seq(otherAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
            .create(PreferredAssignerValue.NoAssigner(EXPECTED_DUMMY_GENERATION), selfAssignerInfo),
          eligibleAssigners = Seq.empty
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
          ),
          eligibleAssigners = Seq(selfAssignerInfo)
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
          ),
          eligibleAssigners = Seq(otherAssignerInfo)
        )
      ),
      nextTimeOffset = Duration.Inf
    )
  }

  test("preferred assigner selection is stable across code changes") {
    // Test plan: Verify with a golden test that the state machine picks the same preferred
    // assigner across code versions, using 1000 iterations to increase confidence. This matters
    // because during rolling deploys, pods running different binaries must agree on the same
    // preferred assigner to avoid split brain and thrashing.
    val stateMachine: ConsistentHashingPreferredAssignerStateMachine = createStateMachine()
    val harness: TestHarness = new TestHarness(stateMachine)
    harness.advance(0.seconds, actions = Seq.empty, nextTimeOffset = Duration.Inf)

    for (i: Int <- 0 until 1000) {
      // Each iteration uses a distinct set of 7 assigners to ensure that we have 1000 independent
      // runs.
      val assigners: Seq[AssignerInfo] = (0 until 7).map { j: Int =>
        val assignerIndex: Int = i * 7 + j
        AssignerInfo(
          uuid = UUID.nameUUIDFromBytes(
            s"assigner-$assignerIndex".getBytes(StandardCharsets.UTF_8)
          ),
          uri = new URI(s"https://assigner-$j:8080")
        )
      }
      val expectedAssigner: AssignerInfo = assigners(
        ConsistentHashingPreferredAssignerStateMachineGoldenData.EXPECTED_PREFERRED_INDICES(i)
      )

      harness.event(
        (i + 1).seconds,
        Event.ResourceSetReceived(ResourceVersion(s"$i"), toResourceMap(assigners: _*)),
        actions = Seq(
          DriverAction.UsePreferredAssignerConfig(
            PreferredAssignerConfig.create(
              PreferredAssignerValue.SomeAssigner(expectedAssigner, EXPECTED_DUMMY_GENERATION),
              selfAssignerInfo
            ),
            eligibleAssigners = assigners.sortBy((a: AssignerInfo) => a.uuid)
          )
        ),
        nextTimeOffset = Duration.Inf
      )
    }
  }
}
