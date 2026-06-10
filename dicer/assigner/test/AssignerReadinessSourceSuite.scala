package com.databricks.dicer.assigner

import java.util.concurrent.atomic.AtomicBoolean

import com.google.common.base.Supplier
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.{MetricUtils, WatchValueCell}
import com.databricks.common.status.{ProbeStatus, ProbeStatuses}
import com.databricks.featureflag.client.utils.FlagValueProvider
import com.databricks.testing.DatabricksTest

/**
 * Unit tests for [[AssignerReadinessSource]].
 *
 * Tests cover:
 *  - Pre-init source behaviour — OK regardless of probe.
 *  - The four corners of the gating matrix (enabled flag × eligibility cell).
 *  - `None` in the eligibility cell (driver has no signal yet) — fail-open to OK.
 *  - Live re-read of both inputs so cell updates and flag flips take effect.
 *  - [[AssignerReadinessSource.init]] rejects a second call.
 *  - Per-(state, enabled) counter records observations correctly across probes.
 */
class AssignerReadinessSourceSuite extends DatabricksTest {

  /** Service name embedded in probe status messages (mirrors the source's hardcoded value). */
  private val EXPECTED_SERVICE_NAME: String = "dicer-assigner"

  /** The OK status that the source emits for [[EXPECTED_SERVICE_NAME]]. */
  private val EXPECTED_OK: ProbeStatus = ProbeStatuses.ok(EXPECTED_SERVICE_NAME)

  /** The not-ready status code that [[ProbeStatuses.notYetReady]] uses (418). */
  private val NOT_YET_READY_CODE: Int = ProbeStatuses.notYetReady(EXPECTED_SERVICE_NAME).code

  /** Prometheus registry the source's counter is registered against. */
  private val REGISTRY: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** Counter name materialized by [[AssignerReadinessSource]]. */
  private val STATE_COUNTER: String = "dicer_assigner_readiness_source_state_total"

  /** Convenience: a pre-set [[WatchValueCell.Consumer]] wrapping `value`. */
  private def cellOf(value: Option[Boolean]): WatchValueCell.Consumer[Boolean] = {
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    value.foreach(cell.setValue)
    cell
  }

  /**
   * Convenience: a [[FlagValueProvider]] whose `getCurrentValue` returns the current value of
   * `valueRef`. Tests flip `valueRef` to exercise live re-read behaviour.
   */
  private def flagProviderOf(name: String, valueRef: AtomicBoolean): FlagValueProvider[Boolean] =
    new FlagValueProvider[Boolean] {
      override def flagName: String = name
      override def valueSupplier: Supplier[Boolean] = new Supplier[Boolean] {
        override def get(): Boolean = valueRef.get()
      }
    }

  /** Convenience: a [[FlagValueProvider]] that always returns `value`. */
  private def flagProviderOf(name: String, value: Boolean): FlagValueProvider[Boolean] =
    flagProviderOf(name, new AtomicBoolean(value))

  test("getStatus returns OK when source is uninitialized") {
    // Test plan: a freshly-constructed source must return OK before `init` is called; this
    // is the boot-time state between the framework's newReadinessSource hook and
    // wrappedMain wiring the source.
    val source: AssignerReadinessSource = new AssignerReadinessSource()

    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("getStatus returns OK when enabled flag reports false regardless of eligibility cell") {
    // Test plan: with the enabled flag reporting false, both Some(true) and Some(false)
    // eligibility values must yield OK.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    cell.setValue(true)
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cell, flagProviderOf("test.enabled", value = false))

    assertResult(EXPECTED_OK)(source.getStatus)

    cell.setValue(false)
    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("getStatus returns OK when enabled is true and eligibility cell is Some(true)") {
    // Test plan: enabled alone must not flip the probe; the eligibility cell must report
    // Some(false) for the probe to flip.
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cellOf(Some(true)), flagProviderOf("test.enabled", value = true))

    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("getStatus returns 418 when enabled is true and eligibility cell is Some(false)") {
    // Test plan: with the enabled flag reporting true and a Some(false) eligibility cell, the
    // probe must return notYetReady (418) so the kubelet pulls the pod from Endpoints. The
    // message embeds the hardcoded service name.
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cellOf(Some(false)), flagProviderOf("test.enabled", value = true))

    val status: ProbeStatus = source.getStatus
    assertResult(NOT_YET_READY_CODE)(status.code)
    assert(status.content.contains(EXPECTED_SERVICE_NAME))
  }

  test("getStatus returns OK when eligibility cell is unset (None) — no signal yet") {
    // Test plan: a bound eligibility cell that has never been published to (None) represents
    // the driver-has-not-published-yet state; the probe must fall back to OK so the pod
    // doesn't fence itself before the first observation.
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cellOf(None), flagProviderOf("test.enabled", value = true))

    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("eligibility cell is re-read on every getStatus call so transitions take effect live") {
    // Test plan: flip the eligibility cell's published value between getStatus calls and
    // confirm the probe follows it without re-initialising the source.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    cell.setValue(true)
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cell, flagProviderOf("test.enabled", value = true))

    // Cell holds true: OK.
    assertResult(EXPECTED_OK)(source.getStatus)

    // Cell flips to false: 418.
    cell.setValue(false)
    assertResult(NOT_YET_READY_CODE)(source.getStatus.code)

    // Cell flips back to true: OK.
    cell.setValue(true)
    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("enabled flag is re-read on every getStatus call so flag flips take effect live") {
    // Test plan: flip the flag's value between getStatus calls and confirm the probe follows
    // it. This pins down the live-rollback contract callers depend on when wiring the source
    // against a SAFE flag (whose getCurrentValue() reflects live updates).
    val enabled: AtomicBoolean = new AtomicBoolean(false)
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cellOf(Some(false)), flagProviderOf("test.enabled", enabled))

    // Flag false: OK despite Some(false) eligibility.
    assertResult(EXPECTED_OK)(source.getStatus)

    // Flag true: 418.
    enabled.set(true)
    assertResult(NOT_YET_READY_CODE)(source.getStatus.code)

    // Flag false again: OK.
    enabled.set(false)
    assertResult(EXPECTED_OK)(source.getStatus)
  }

  test("init rejects a second call") {
    // Test plan: calling init more than once must throw -- inputs are bound exactly once
    // at wiring time.
    val source: AssignerReadinessSource = new AssignerReadinessSource()
    source.init(cellOf(Some(true)), flagProviderOf("test.enabled", value = true))

    intercept[IllegalStateException] {
      source.init(cellOf(Some(false)), flagProviderOf("test.enabled", value = false))
    }
  }

  test("counter records (state, enabled) across probes") {
    // Test plan: drive the source through unbound, then ready+enabled, then
    // not_ready+enabled, and confirm each (state, enabled) label pair sees the expected
    // increments using MetricUtils.ChangeTracker.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    val enabled: AtomicBoolean = new AtomicBoolean(true)
    val source: AssignerReadinessSource = new AssignerReadinessSource()

    val unboundFalseTracker: MetricUtils.ChangeTracker[Double] = MetricUtils.ChangeTracker(
      () =>
        MetricUtils.getMetricValue(
          REGISTRY,
          STATE_COUNTER,
          Map("state" -> "unbound", "enabled" -> "false")
        )
    )
    val readyTrueTracker: MetricUtils.ChangeTracker[Double] = MetricUtils.ChangeTracker(
      () =>
        MetricUtils.getMetricValue(
          REGISTRY,
          STATE_COUNTER,
          Map("state" -> "ready", "enabled" -> "true")
        )
    )
    val notReadyTrueTracker: MetricUtils.ChangeTracker[Double] = MetricUtils.ChangeTracker(
      () =>
        MetricUtils.getMetricValue(
          REGISTRY,
          STATE_COUNTER,
          Map("state" -> "not_ready", "enabled" -> "true")
        )
    )

    // Pre-init probe: state="unbound", enabled="false" (no flag bound yet).
    source.getStatus
    assertResult(1.0)(unboundFalseTracker.totalChange())
    assertResult(0.0)(readyTrueTracker.totalChange())
    assertResult(0.0)(notReadyTrueTracker.totalChange())

    source.init(cell, flagProviderOf("test.enabled", enabled))

    // Cell publishes true, flag true -> state="ready", enabled="true".
    cell.setValue(true)
    source.getStatus
    assertResult(1.0)(unboundFalseTracker.totalChange())
    assertResult(1.0)(readyTrueTracker.totalChange())
    assertResult(0.0)(notReadyTrueTracker.totalChange())

    // Cell publishes false, flag still true -> state="not_ready", enabled="true".
    cell.setValue(false)
    source.getStatus
    assertResult(1.0)(unboundFalseTracker.totalChange())
    assertResult(1.0)(readyTrueTracker.totalChange())
    assertResult(1.0)(notReadyTrueTracker.totalChange())
  }
}
