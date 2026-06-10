package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import io.prometheus.client.CollectorRegistry

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
    SequentialExecutionContext.createWithDedicatedPool(this.getClass.getName)

  /**
   * Reads the current Phase 2.1 consistent-hashing-vs-etcd agreement gauge value from the
   * default Prometheus registry, or `None` when the gauge is unset (e.g. cleared or never
   * published).
   */
  private def getAgreementGaugeValueOpt: Option[Double] = {
    MetricUtils.getMetricValueOpt(
      CollectorRegistry.defaultRegistry,
      "dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_agreement_gauge",
      labels = Map(
        "mode" -> PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
      )
    )
  }

  /**
   * Reads the current Phase 2.1 consistent-hashing-vs-etcd disagreement counter value from the
   * default Prometheus registry.
   */
  private def getDisagreementCounterValue: Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_disagreement_total",
      labels = Map(
        "mode" -> PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
      )
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
      assert(getAgreementGaugeValueOpt.contains(1.0))
    }

    // Disagreeing UUIDs: gauge transitions to 0.0 and the disagreement counter increments by 1.
    val disagreementTracker: ChangeTracker[Double] =
      ChangeTracker(() => getDisagreementCounterValue)
    oldDriver.publishValue(
      PreferredAssignerValue.SomeAssigner(disagreeingInfo, Generation(Incarnation(2L), 0L))
    )
    AssertionWaiter("agreement gauge reaches 0.0 after disagreement").await {
      assert(getAgreementGaugeValueOpt.contains(0.0))
      assert(disagreementTracker.totalChange() == 1.0)
    }

    // Both publish "no PA known": gauge returns to 1.0 (None == None).
    newDriver.publishValue(PreferredAssignerValue.NoAssigner(Generation(Incarnation(3L), 0L)))
    oldDriver.publishValue(PreferredAssignerValue.NoAssigner(Generation(Incarnation(3L), 0L)))
    AssertionWaiter("agreement gauge reaches 1.0 after both report no-PA").await {
      assert(getAgreementGaugeValueOpt.contains(1.0))
    }
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

  test(
    "ShadowMode selectionEligibilityWatchCell holds true unconditionally regardless of the " +
    "new driver's signal"
  ) {
    // Test plan: In ShadowMode the old driver is fully authoritative and the new driver does
    // not gate any decision the system acts on. Verify that the migration driver's
    // selectionEligibilityWatchCell holds true even when newDriver's eligibility cell holds
    // false.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )

    newDriver.setSelectionEligibilityForTest(isEligible = false)
    assertResult(Some(true))(migration.selectionEligibilityWatchCell.getLatestValueOpt)

    newDriver.setSelectionEligibilityForTest(isEligible = true)
    assertResult(Some(true))(migration.selectionEligibilityWatchCell.getLatestValueOpt)
  }

  test(
    "ConsistentHashingNominatedEtcdReadMode selectionEligibilityWatchCell delegates to the " +
    "new driver's cell"
  ) {
    // Test plan: From mode 2.1 onward the new driver nominates the preferred-assigner
    // candidate that the old driver writes, so its eligibility is load-bearing. Verify that
    // the migration driver's selectionEligibilityWatchCell tracks newDriver's cell (true ⇒
    // true, false ⇒ false), and is independent of oldDriver's cell.
    val oldDriver: TestDriver = new TestDriver(sec = sec)
    val newDriver: TestDriver = new TestDriver(sec = sec)
    val migration: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      sec = sec,
      migrationMode = MigrationMode.ConsistentHashingNominatedEtcdReadMode,
      oldDriver = oldDriver,
      newDriver = newDriver
    )

    // newDriver eligible, oldDriver not — migration is eligible.
    newDriver.setSelectionEligibilityForTest(isEligible = true)
    oldDriver.setSelectionEligibilityForTest(isEligible = false)
    assertResult(Some(true))(migration.selectionEligibilityWatchCell.getLatestValueOpt)

    // newDriver not eligible — migration is not eligible regardless of oldDriver.
    newDriver.setSelectionEligibilityForTest(isEligible = false)
    oldDriver.setSelectionEligibilityForTest(isEligible = true)
    assertResult(Some(false))(migration.selectionEligibilityWatchCell.getLatestValueOpt)
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
     * Test-only writable backing for [[selectionEligibilityWatchCell]]. Initialized to
     * `true` at construction; tests publish via [[setSelectionEligibilityForTest]] and
     * observers read through the override below.
     */
    private val selectionEligibilityWatchCellImpl: WatchValueCell[Boolean] = {
      val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
      cell.setValue(true)
      cell
    }

    /** Sets the value [[selectionEligibilityWatchCell]] will report on subsequent reads. */
    def setSelectionEligibilityForTest(isEligible: Boolean): Unit =
      selectionEligibilityWatchCellImpl.setValue(isEligible)

    override private[assigner] def selectionEligibilityWatchCell: WatchValueCell.Consumer[Boolean] =
      selectionEligibilityWatchCellImpl
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
