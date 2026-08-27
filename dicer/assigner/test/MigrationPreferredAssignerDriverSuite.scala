package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.{
  AssertionWaiter,
  Cancellable,
  SequentialExecutionContext,
  TestUtils,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.dicer.assigner.MigrationPreferredAssignerDriverSuite.{
  RecordingWatchCallback,
  TestDriver
}
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.testing.DatabricksTest

class MigrationPreferredAssignerDriverSuite extends DatabricksTest {

  private val ASSIGNER_INFO: AssignerInfo = AssignerInfo(
    uuid = UUID.randomUUID(),
    uri = new URI("http://localhost:1234")
  )

  private val sec: SequentialExecutionContext =
    SequentialExecutionContext.createWithDedicatedPool(
      name = this.getClass.getName,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )

  /**
   * Reads the current consistent-hashing-vs-etcd agreement gauge value for the given `mode` label
   * from the default Prometheus registry, or `None` when the gauge is unset (e.g. cleared or never
   * published).
   */
  private def getAgreementGaugeValueOpt(modeLabel: String): Option[Double] = {
    MetricUtils.getMetricValueOpt(
      CollectorRegistry.defaultRegistry,
      "dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_agreement_gauge",
      labels = Map("mode" -> modeLabel)
    )
  }

  /**
   * Reads the current consistent-hashing-vs-etcd disagreement counter value for the given `mode`
   * label from the default Prometheus registry.
   */
  private def getDisagreementCounterValue(modeLabel: String): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_disagreement_total",
      labels = Map("mode" -> modeLabel)
    )
  }

  override def afterEach(): Unit = {
    try {
      PreferredAssignerMetrics.forTest.clearConsistentHashingVsEtcdAgreement()
    } finally {
      super.afterEach()
    }
  }

  test("ShadowMode watch forwards only to the old driver") {
    // Test plan: Verify that in ShadowMode, watch() subscribes only to the old driver. Publish
    // several distinguishable values on each driver, interleaved, and confirm that the callback
    // receives exactly the sequence published by the old driver -- the new driver's values
    // must never reach the callback. Odd-incarnation values originate from the old driver and
    // even-incarnation values originate from the new driver, so any leakage from the new
    // driver into the callback would be immediately visible in the recorded sequence.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val callback: RecordingWatchCallback = new RecordingWatchCallback(sec)
    val cancellable: Cancellable = migration.watch(callback)

    val oldUpdates: Seq[PreferredAssignerValue] = Seq(
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(1L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(3L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(5L), 0L))
    )
    for (value: PreferredAssignerValue <- oldUpdates) {
      oldDriver.publishValue(value)
    }

    val newUpdates: Seq[PreferredAssignerValue] = Seq(
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(2L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(4L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(6L), 0L))
    )
    for (value: PreferredAssignerValue <- newUpdates) {
      newDriver.publishValue(value)
    }

    // The published values flow asynchronously from the TestDrivers through `watchCell` and
    // back onto `sec` before landing in `callback`, so the callback's view may lag behind the
    // scheduling of the publishes above. Wait for the full expected sequence to land.
    AssertionWaiter("waiting for the old driver's values to reach the callback").await {
      assert(callback.valuesSnapshot.map(_.knownPreferredAssigner) == oldUpdates)
    }
    cancellable.cancel()
  }

  test(
    "ShadowMode handleHeartbeatRequest forwards to both drivers but returns the old driver's " +
    "response"
  ) {
    // Test plan: Verify that in ShadowMode, handleHeartbeatRequest() is forwarded to both
    // drivers, but only the old (authoritative) driver's response is returned. Publish a
    // distinguishable value on each driver so the response's embedded
    // [[PreferredAssignerValue]] pins down its origin: a response carrying the old driver's
    // value confirms the authoritative response came from the old driver. Per-driver call
    // counters also confirm the new driver was invoked as a shadow signal.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val oldLatestPaValue: PreferredAssignerValue =
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(1L), 0L))
    val newLatestPaValue: PreferredAssignerValue =
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(2L), 0L))
    oldDriver.publishValue(oldLatestPaValue)
    newDriver.publishValue(newLatestPaValue)

    val arbitraryPaValue: PreferredAssignerValue.SomeAssigner =
      PreferredAssignerValue.SomeAssigner(
        AssignerInfo(uuid = UUID.randomUUID(), uri = new URI("http://localhost:5678")),
        Generation(Incarnation(7L), number = 1L)
      )
    val requestOpId: Long = 42L
    val response: HeartbeatResponse = TestUtils.awaitResult(
      migration.handleHeartbeatRequest(HeartbeatRequest(opId = requestOpId, arbitraryPaValue)),
      Duration.Inf
    )
    // The returned response carries the old driver's echoed opId and its latest published
    // value, confirming the authoritative response came from the old driver.
    assert(response.preferredAssignerValue == oldLatestPaValue)

    // The authoritative response came from the old driver, but the new driver must also have
    // received the heartbeat as a shadow signal.
    assert(oldDriver.getHeartbeatRequestCount == 1)
    assert(newDriver.getHeartbeatRequestCount == 1)
  }

  test("ShadowMode sendTerminationNotice forwards to both drivers") {
    // Test plan: Verify that in ShadowMode, sendTerminationNotice() is forwarded to both
    // drivers. The method returns Unit, so observe delivery via per-driver counters.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    migration.sendTerminationNotice()
    assert(oldDriver.getTerminationNoticeCount == 1)
    assert(newDriver.getTerminationNoticeCount == 1)
  }

  test(
    "ConsistentHashingNominatedEtcdReadMode forwards consistent-hashing picks from newDriver " +
    "to oldDriver"
  ) {
    // Test plan: Verify that in ConsistentHashingNominatedEtcdReadMode the migration driver
    // subscribes to the new driver's watch stream on start() and forwards each elected
    // AssignerInfo to the old driver via updateExternalPick. Publish a `SomeAssigner`
    // then a `NoAssigner` on the new driver and verify the old driver receives `Some(info)`
    // then `None` in order.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingNominatedEtcdReadMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val externalPick: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://elected:9999")
    )
    val newDriverGen: Generation = Generation(Incarnation(1L), 0L)
    newDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(externalPick, newDriverGen)
    )
    newDriver.publishValue(PreferredAssignerValue.NoAssigner(newDriverGen))

    AssertionWaiter("waiting for both forwarded picks to reach the old driver").await {
      assert(oldDriver.getPickUpdates == Vector(Some(externalPick), None))
    }
    // Symmetric invariant to the ShadowMode regression guard: in
    // ConsistentHashingNominatedEtcdReadMode the migration driver MUST subscribe exactly once to
    // `newDriver`'s watch stream.
    assert(newDriver.getWatchSubscriptionCount == 1)
  }

  test(
    "ConsistentHashingNominatedEtcdReadMode tracks consistent-hashing-vs-etcd agreement gauge " +
    "across picks"
  ) {
    // Test plan: Verify the agreement gauge reflects whether the consistent-hashing pick
    // matches the etcd pick. After both drivers have published, the gauge reads 1.0 when their
    // picks match, 0.0 when they disagree, and 1.0 again when both transition to "no PA known"
    // (matching None UUIDs).
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingNominatedEtcdReadMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )

    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val agreeingInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://agree:1111")
    )
    val disagreeingInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://disagree:2222")
    )

    // Both publish the same UUID: gauge transitions to 1.0 (agree).
    newDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(agreeingInfo, Generation(Incarnation(1L), 0L))
    )
    oldDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(agreeingInfo, Generation(Incarnation(1L), 0L))
    )
    AssertionWaiter("agreement gauge reaches 1.0 after matching picks").await {
      assert(
        getAgreementGaugeValueOpt(
          PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
        ).contains(1.0)
      )
    }

    // Disagreeing UUIDs: gauge transitions to 0.0 and the disagreement counter increments by 1.
    val disagreementTracker: ChangeTracker[Double] =
      ChangeTracker(
        () =>
          getDisagreementCounterValue(
            PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
          )
      )
    oldDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(disagreeingInfo, Generation(Incarnation(2L), 0L))
    )
    AssertionWaiter("agreement gauge reaches 0.0 after disagreement").await {
      assert(
        getAgreementGaugeValueOpt(
          PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
        ).contains(0.0)
      )
      assert(disagreementTracker.totalChange() == 1.0)
    }

    // Both publish "no PA known": gauge returns to 1.0 (None == None).
    newDriver.publishValue(PreferredAssignerValue.NoAssigner(Generation(Incarnation(3L), 0L)))
    oldDriver.publishValue(PreferredAssignerValue.NoAssigner(Generation(Incarnation(3L), 0L)))
    AssertionWaiter("agreement gauge reaches 1.0 after both report no-PA").await {
      assert(
        getAgreementGaugeValueOpt(
          PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
        ).contains(1.0)
      )
    }
  }

  test("ConsistentHashingPrimaryEtcdWritesMode tracks agreement under the phase22 label") {
    // Test plan: In ConsistentHashingPrimaryEtcdWritesMode the agreement gauge/counter must be
    // emitted under the phase22 label, because Phase 2.2 and Phase 2.1 carry different semantics
    // and the Phase 2.1 disagreement alert filters on mode="phase21". Verify this by publishing
    // consistent preferred-assigner info to the old and new driver, then publishing inconsistent
    // info, and asserting the phase22 gauge moves 1.0 -> 0.0 (counter increments) while the phase21
    // gauge stays unset.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingPrimaryEtcdWritesMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )

    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val phase22Label: String =
      PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_2
    val phase21Label: String =
      PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1

    val agreeingInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://agree:1111")
    )
    val disagreeingInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://disagree:2222")
    )

    // Both drivers publish the same UUID: the phase22 gauge transitions to 1.0 (agree).
    newDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(agreeingInfo, Generation(Incarnation(1L), 0L))
    )
    oldDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(agreeingInfo, Generation(Incarnation(1L), 0L))
    )
    AssertionWaiter("phase22 agreement gauge reaches 1.0 after matching picks").await {
      assert(getAgreementGaugeValueOpt(phase22Label).contains(1.0))
    }

    // Disagreeing UUIDs: the phase22 gauge transitions to 0.0 and its counter increments by 1.
    val disagreementTracker: ChangeTracker[Double] =
      ChangeTracker(() => getDisagreementCounterValue(phase22Label))
    oldDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(disagreeingInfo, Generation(Incarnation(2L), 0L))
    )
    AssertionWaiter("phase22 agreement gauge reaches 0.0 after disagreement").await {
      assert(getAgreementGaugeValueOpt(phase22Label).contains(0.0))
      assert(disagreementTracker.totalChange() == 1.0)
    }

    // The phase21-labeled gauge must never have been touched in this mode.
    assert(
      getAgreementGaugeValueOpt(phase21Label).isEmpty,
      "phase21 gauge must stay unset in ConsistentHashingPrimaryEtcdWritesMode"
    )
  }

  test("ShadowMode does not forward consistent-hashing picks") {
    // Test plan: Regression guard. ShadowMode must not subscribe to the new driver for pick
    // forwarding. Assert structurally that `newDriver` received zero watch subscriptions from
    // the migration driver, and additionally that publishing a value on `newDriver` does not
    // produce a forwarded pick on `oldDriver`.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val externalPick: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://elected:9999")
    )
    newDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(externalPick, Generation(Incarnation(1L), 0L))
    )

    assert(newDriver.getWatchSubscriptionCount == 0)
    assert(oldDriver.getPickUpdates.isEmpty)
  }

  test("forwards the new driver's consistent-hashing state") {
    // Test plan: Verify the migration driver surfaces the consistent-hashing snapshot from the new
    // (CH) driver -- not the old driver -- so the Assigner debug page shows shadow-mode operation.
    // Publish distinct CH snapshots on both inner drivers and confirm the migration driver reports
    // the new driver's snapshot.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )

    val oldState: ConsistentHashingState = ConsistentHashingState(
      localAssignerInfo = ASSIGNER_INFO,
      preferredAssignerInfoOpt = None,
      eligiblePods = Seq(ASSIGNER_INFO),
      k8sConnectionHealth = ConsistentHashingState.K8sConnectionHealth.Init
    )
    val newState: ConsistentHashingState = ConsistentHashingState(
      localAssignerInfo = ASSIGNER_INFO,
      preferredAssignerInfoOpt = Some(ASSIGNER_INFO),
      eligiblePods = Seq(ASSIGNER_INFO, ASSIGNER_INFO, ASSIGNER_INFO),
      k8sConnectionHealth = ConsistentHashingState.K8sConnectionHealth.Healthy
    )
    oldDriver.setConsistentHashingStateForTest(oldState)
    newDriver.setConsistentHashingStateForTest(newState)

    // The migration driver forwards the new driver's snapshot.
    val stateOpt: Option[ConsistentHashingState] =
      TestUtils.awaitResult(migration.consistentHashingStateView, Duration.Inf)
    assertResult(Some(newState))(stateOpt)
  }

  test("ConsistentHashingPrimaryEtcdWritesMode watch exposes the new driver's stream") {
    // Test plan: In ConsistentHashingPrimaryEtcdWritesMode the consistent-hashing driver is
    // authoritative for reads, so watch() must expose the NEW driver's stream (not the old
    // etcd driver's, as in the other modes). Publish several distinguishable values on each
    // driver, interleaved, and confirm the callback receives exactly the new driver's
    // sequence -- the old driver's values must never reach it. Odd-incarnation values
    // originate from the old driver and even-incarnation values from the new driver, so any
    // leakage from the old driver into the callback would be immediately visible.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingPrimaryEtcdWritesMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val callback: RecordingWatchCallback = new RecordingWatchCallback(sec)
    val cancellable: Cancellable = migration.watch(callback)

    val oldUpdates: Seq[PreferredAssignerValue] = Seq(
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(1L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(3L), 0L))
    )
    for (value: PreferredAssignerValue <- oldUpdates) {
      oldDriver.publishValue(value)
    }

    val newUpdates: Seq[PreferredAssignerValue] = Seq(
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(2L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(4L), 0L)),
      PreferredAssignerValue.ModeDisabled(Generation(Incarnation(6L), 0L))
    )
    for (value: PreferredAssignerValue <- newUpdates) {
      newDriver.publishValue(value)
    }

    // The published values flow asynchronously through `watchCell` and back onto `sec` before
    // landing in `callback`, so wait for the full expected sequence from the new driver.
    AssertionWaiter("waiting for the new driver's values to reach the callback").await {
      assert(callback.valuesSnapshot.map(_.knownPreferredAssigner) == newUpdates)
    }
    cancellable.cancel()
  }

  test(
    "ConsistentHashingPrimaryEtcdWritesMode still forwards picks to the old driver for writing"
  ) {
    // Test plan: Even though etcd is no longer read back in ConsistentHashingPrimaryEtcdWritesMode,
    // its pick is still WRITTEN: the migration driver must forward each elected AssignerInfo to
    // the old driver via updateExternalPick (which the old driver writes to etcd for durability
    // and rollback). Publish a `SomeAssigner` then a `NoAssigner` on the new driver and verify
    // the old driver receives `Some(info)` then `None` in order, and that the migration driver
    // subscribed exactly once to the new driver's watch stream.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingPrimaryEtcdWritesMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )
    migration.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val externalPick: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://elected:9999")
    )
    val newDriverGen: Generation = Generation(Incarnation(1L), 0L)
    newDriver.publishValue(PreferredAssignerValue.SomeAssigner(externalPick, newDriverGen))
    newDriver.publishValue(PreferredAssignerValue.NoAssigner(newDriverGen))

    AssertionWaiter("waiting for both forwarded picks to reach the old driver").await {
      assert(oldDriver.getPickUpdates == Vector(Some(externalPick), None))
    }
    assert(newDriver.getWatchSubscriptionCount == 1)
  }
}

object MigrationPreferredAssignerDriverSuite {

  /**
   * A [[PreferredAssignerDriver]] that lets tests publish values through [[publishValue]] and
   * counts signals received. Heartbeat responses echo the request's `opId` and embed the
   * driver's latest published [[PreferredAssignerValue]] (or a placeholder if none has been
   * published yet), so tests can distinguish responses from two different drivers by the
   * embedded value.
   *
   * State-mutating methods schedule their work on [[sec]] and return immediately; observer
   * methods block only until their result can be read. Callers that need to observe effects of
   * scheduled work should do so through the observer methods or, for side effects delivered
   * via the watch stream, by waiting on the subscribed callback.
   */
  private[assigner] class TestDriver(sec: SequentialExecutionContext)
      extends PreferredAssignerDriver {

    // Placeholder value used for heartbeat responses before the first value is published.
    private val initialPreferredAssignerValue: PreferredAssignerValue =
      PreferredAssignerValue.NoAssigner(Generation.EMPTY)

    // Access to the mutable state below is serialized on `sec`. `watchCell` is thread-safe, but
    // writes are still serialized on `sec` to order them w.r.t. heartbeats.
    private val watchCell: WatchValueCell[PreferredAssignerConfig] =
      new WatchValueCell[PreferredAssignerConfig]()
    private var currentAssignerInfoOpt: Option[AssignerInfo] = None
    private var terminationNoticeCount: Int = 0
    private var heartbeatRequestCount: Int = 0
    private var watchSubscriptionCount: Int = 0
    private val pickUpdates: mutable.ArrayBuffer[Option[AssignerInfo]] =
      mutable.ArrayBuffer.empty

    /** The number of times [[sendTerminationNotice]] has been called. */
    def getTerminationNoticeCount: Int = {
      TestUtils.awaitResult(
        sec.call {
          sec.assertCurrentContext()
          terminationNoticeCount
        },
        Duration.Inf
      )
    }

    /** The number of times [[handleHeartbeatRequest]] has been called. */
    def getHeartbeatRequestCount: Int = {
      TestUtils.awaitResult(
        sec.call {
          sec.assertCurrentContext()
          heartbeatRequestCount
        },
        Duration.Inf
      )
    }

    /** The number of times [[watch]] has been called. */
    def getWatchSubscriptionCount: Int = {
      TestUtils.awaitResult(
        sec.call {
          sec.assertCurrentContext()
          watchSubscriptionCount
        },
        Duration.Inf
      )
    }

    /** Snapshot of every [[updateExternalPick]] invocation, in order. */
    def getPickUpdates: Vector[Option[AssignerInfo]] = {
      TestUtils.awaitResult(
        sec.call {
          sec.assertCurrentContext()
          pickUpdates.toVector
        },
        Duration.Inf
      )
    }

    /**
     * Publishes a new [[PreferredAssignerValue]] to this driver's watch stream, reusing the
     * [[AssignerInfo]] provided to [[start]]. The publish is performed asynchronously on
     * [[sec]].
     *
     * @throws IllegalStateException (asynchronously, on [[sec]]) if [[start]] has not been called.
     */
    def publishValue(value: PreferredAssignerValue): Unit = {
      sec.run {
        sec.assertCurrentContext()
        val currentAssignerInfo: AssignerInfo = currentAssignerInfoOpt.getOrElse(
          throw new IllegalStateException("publishValue called before start()")
        )
        watchCell.setValue(
          PreferredAssignerConfig.create(
            preferredAssignerValue = value,
            currentAssignerInfo = currentAssignerInfo
          )
        )
      }
    }

    override def start(
        assignerInfo: AssignerInfo,
        assignerProtoLogger: AssignerProtoLogger): Unit = {
      sec.run {
        sec.assertCurrentContext()
        currentAssignerInfoOpt = Some(assignerInfo)
      }
    }

    override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable = {
      sec.run {
        sec.assertCurrentContext()
        watchSubscriptionCount += 1
      }
      watchCell.watch(callback)
    }

    override def sendTerminationNotice(): Unit = {
      sec.run {
        sec.assertCurrentContext()
        terminationNoticeCount += 1
      }
    }

    override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] = {
      sec.call {
        sec.assertCurrentContext()
        heartbeatRequestCount += 1
        val preferredAssignerValue: PreferredAssignerValue =
          watchCell.getLatestValueOpt
            .map((_: PreferredAssignerConfig).knownPreferredAssigner)
            .getOrElse(initialPreferredAssignerValue)
        HeartbeatResponse(request.opId, preferredAssignerValue)
      }
    }

    /**
     * Test-only sink that records every pick forwarded by the migration driver via the
     * [[PreferredAssignerDriver.updateExternalPick]] override.
     */
    override private[assigner] def updateExternalPick(
        externalPickOpt: Option[AssignerInfo]): Unit = {
      sec.run {
        sec.assertCurrentContext()
        pickUpdates += externalPickOpt
      }
    }

    /**
     * Test-only writable backing for [[consistentHashingStateView]]. Empty until a test publishes
     * a snapshot via [[setConsistentHashingStateForTest]].
     */
    private val consistentHashingStateWatchCellImpl: WatchValueCell[ConsistentHashingState] =
      new WatchValueCell[ConsistentHashingState]()

    /** Publishes a [[ConsistentHashingState]] snapshot for this driver's later reads. */
    def setConsistentHashingStateForTest(state: ConsistentHashingState): Unit =
      consistentHashingStateWatchCellImpl.setValue(state)

    // Mirrors a standalone CH driver: reports its snapshot, or `None` until one is published.
    override private[assigner] def consistentHashingStateView
        : Future[Option[ConsistentHashingState]] =
      Future.successful(consistentHashingStateWatchCellImpl.getLatestValueOpt)
  }

  /**
   * A [[ValueStreamCallback]] that records every value it receives on [[sec]] and exposes an
   * immutable snapshot through [[valuesSnapshot]]. Access to recorded values is serialized on
   * [[sec]].
   */
  private[assigner] class RecordingWatchCallback(sec: SequentialExecutionContext)
      extends ValueStreamCallback[PreferredAssignerConfig](sec) {

    // Access to `values` is serialized on `sec`.
    private val values: mutable.ArrayBuffer[PreferredAssignerConfig] = mutable.ArrayBuffer.empty

    override def onSuccess(value: PreferredAssignerConfig): Unit = {
      sec.assertCurrentContext()
      values += value
    }

    /** Returns an immutable snapshot of received values, in order. */
    def valuesSnapshot: Vector[PreferredAssignerConfig] = {
      TestUtils.awaitResult(
        sec.call {
          sec.assertCurrentContext()
          values.toVector
        },
        Duration.Inf
      )
    }
  }
}
