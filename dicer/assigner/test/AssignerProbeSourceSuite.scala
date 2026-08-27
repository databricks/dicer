package com.databricks.dicer.assigner

import java.util.concurrent.atomic.AtomicBoolean

import com.google.common.base.Supplier
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.{MetricUtils, WatchValueCell}
import com.databricks.common.status.{ProbeStatus, ProbeStatuses, ProbeStatusSource}
import com.databricks.common.status.liveness.LivenessStatusSource
import com.databricks.featureflag.client.utils.FlagValueProvider
import com.databricks.testing.DatabricksTest

/**
 * Unit tests for [[AssignerProbeSource]], covering both the readiness
 * ([[AssignerProbeSource.forReadiness]]) and liveness ([[AssignerProbeSource.forLiveness]])
 * adapters. Each test binds one shared connection-health cell via [[AssignerProbeSource.init]] and
 * asserts both adapters, since they observe the same cell and differ only in how they interpret the
 * unknown state and what they emit when they fail.
 */
class AssignerProbeSourceSuite extends DatabricksTest {

  /** Service name embedded in probe status messages. */
  private val EXPECTED_SERVICE_NAME: String = "dicer-assigner"

  /** The OK status that the source emits for [[EXPECTED_SERVICE_NAME]]. */
  private val EXPECTED_OK: ProbeStatus = ProbeStatuses.ok(EXPECTED_SERVICE_NAME)

  /** The not-ready status code that [[ProbeStatuses.notYetReady]] uses (418). */
  private val NOT_YET_READY_CODE: Int = ProbeStatuses.notYetReady(EXPECTED_SERVICE_NAME).code

  /** The need-restart status code that [[ProbeStatuses.needRestart]] uses (503). */
  private val NEED_RESTART_CODE: Int = ProbeStatuses.needRestart(EXPECTED_SERVICE_NAME).code

  /** Prometheus registry the source's counters are registered against. */
  private val REGISTRY: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** Shared probe-state counter name materialized by [[AssignerProbeSource]]. */
  private val STATE_COUNTER: String = "dicer_assigner_probe_source_state_total"

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

  /** A readiness adapter over `source` gated on a flag fixed to `enabled`. */
  private def readiness(source: AssignerProbeSource, enabled: Boolean): ProbeStatusSource =
    source.forReadiness(flagProviderOf("test.readiness.enabled", enabled))

  /** A liveness adapter over `source` gated on a flag fixed to `enabled`. */
  private def liveness(source: AssignerProbeSource, enabled: Boolean): LivenessStatusSource =
    source.forLiveness(flagProviderOf("test.liveness.enabled", enabled))

  test("uninitialized source: enabled adapters follow failOnUnknown, disabled adapters are OK") {
    // Test plan: before `init` binds the cell the health value is absent, so an enabled adapter
    // behaves the same as for a bound-but-unpublished cell -- it fails iff failOnUnknown (readiness
    // not-ready, liveness alive). A disabled adapter is always OK. This is the boot-time state
    // between the framework's newReadinessSource/newLivenessSource hooks and wrappedMain binding
    // the shared cell.
    val source: AssignerProbeSource = new AssignerProbeSource()

    // Enabled + unbound: readiness fails closed (failOnUnknown=true), liveness stays alive.
    assertResult(NOT_YET_READY_CODE)(readiness(source, enabled = true).getStatus.code)
    assertResult(EXPECTED_OK)(liveness(source, enabled = true).getStatus)

    // Disabled: both OK regardless of the unbound cell.
    assertResult(EXPECTED_OK)(readiness(source, enabled = false).getStatus)
    assertResult(EXPECTED_OK)(liveness(source, enabled = false).getStatus)
  }

  test("both adapters return OK when their enabled flag is false regardless of connection health") {
    // Test plan: with the enabled flag off, both adapters preserve the historical
    // ready/alive-by-default behavior even when the connection is unhealthy.
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(Some(false)))

    assertResult(EXPECTED_OK)(readiness(source, enabled = false).getStatus)
    assertResult(EXPECTED_OK)(liveness(source, enabled = false).getStatus)
  }

  test("both adapters return OK when enabled and the connection is healthy") {
    // Test plan: enabled alone must not trip either probe; a healthy cell is ready and alive.
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(Some(true)))

    assertResult(EXPECTED_OK)(readiness(source, enabled = true).getStatus)
    assertResult(EXPECTED_OK)(liveness(source, enabled = true).getStatus)
  }

  test("enabled + unhealthy connection: readiness 418, liveness 503") {
    // Test plan: with an unhealthy (Some(false)) cell and both flags on, readiness must return
    // notYetReady (418) so the kubelet pulls the pod from Endpoints, and liveness must return
    // needRestart (503) so the kubelet restarts the pod. Both messages embed the service name.
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(Some(false)))

    val readinessStatus: ProbeStatus = readiness(source, enabled = true).getStatus
    assertResult(NOT_YET_READY_CODE)(readinessStatus.code)
    assert(readinessStatus.content.contains(EXPECTED_SERVICE_NAME))

    val livenessStatus: ProbeStatus = liveness(source, enabled = true).getStatus
    assertResult(NEED_RESTART_CODE)(livenessStatus.code)
    assert(livenessStatus.content.contains(EXPECTED_SERVICE_NAME))
  }

  test("enabled + unset cell (None, unknown): readiness 418, liveness OK") {
    // Test plan: a bound health cell that has never been published to (None) means the connection
    // health is unknown -- the pod has never confirmed Kubernetes connectivity. This is where the
    // two probes diverge: readiness fences the never-connected pod out of Endpoints (418), while
    // liveness keeps a still-booting pod alive so the kubelet does not kill it before it connects.
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(None))

    assertResult(NOT_YET_READY_CODE)(readiness(source, enabled = true).getStatus.code)
    assertResult(EXPECTED_OK)(liveness(source, enabled = true).getStatus)
  }

  test("unset cell (None, unknown) with the gate off: both adapters OK") {
    // Test plan: unknown health only fences readiness when its gate is on; with the flag off the
    // historical ready-by-default holds even before the first poll (liveness is alive on unknown
    // regardless of the flag).
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(None))

    assertResult(EXPECTED_OK)(readiness(source, enabled = false).getStatus)
    assertResult(EXPECTED_OK)(liveness(source, enabled = false).getStatus)
  }

  test("shared cell is re-read on every getStatus call so transitions take effect live") {
    // Test plan: flip the health cell's published value between getStatus calls and confirm both
    // adapters follow it without re-initialising the source.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    cell.setValue(true)
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cell)
    val readinessSource: ProbeStatusSource = readiness(source, enabled = true)
    val livenessSource: LivenessStatusSource = liveness(source, enabled = true)

    // Cell holds healthy: both OK.
    assertResult(EXPECTED_OK)(readinessSource.getStatus)
    assertResult(EXPECTED_OK)(livenessSource.getStatus)

    // Cell flips to unhealthy: readiness 418, liveness 503.
    cell.setValue(false)
    assertResult(NOT_YET_READY_CODE)(readinessSource.getStatus.code)
    assertResult(NEED_RESTART_CODE)(livenessSource.getStatus.code)

    // Cell flips back to healthy: both OK.
    cell.setValue(true)
    assertResult(EXPECTED_OK)(readinessSource.getStatus)
    assertResult(EXPECTED_OK)(livenessSource.getStatus)
  }

  test("each adapter's flag is re-read on every getStatus call so flag flips take effect live") {
    // Test plan: flip each adapter's flag between getStatus calls and confirm the probe follows it.
    // This pins down the live rollback / kill-switch contract callers depend on when wiring the
    // adapters against SAFE flags (whose getCurrentValue() reflects live updates). Bind the shared
    // cell unhealthy so only the flag decides each outcome.
    val readinessEnabled: AtomicBoolean = new AtomicBoolean(false)
    val livenessEnabled: AtomicBoolean = new AtomicBoolean(false)
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(Some(false)))
    val readinessSource: ProbeStatusSource =
      source.forReadiness(flagProviderOf("test.readiness.enabled", readinessEnabled))
    val livenessSource: LivenessStatusSource =
      source.forLiveness(flagProviderOf("test.liveness.enabled", livenessEnabled))

    // Both flags false: OK despite the unhealthy connection.
    assertResult(EXPECTED_OK)(readinessSource.getStatus)
    assertResult(EXPECTED_OK)(livenessSource.getStatus)

    // Both flags true: readiness 418, liveness 503.
    readinessEnabled.set(true)
    livenessEnabled.set(true)
    assertResult(NOT_YET_READY_CODE)(readinessSource.getStatus.code)
    assertResult(NEED_RESTART_CODE)(livenessSource.getStatus.code)

    // Both flags false again: OK.
    readinessEnabled.set(false)
    livenessEnabled.set(false)
    assertResult(EXPECTED_OK)(readinessSource.getStatus)
    assertResult(EXPECTED_OK)(livenessSource.getStatus)
  }

  test("init rejects a second call") {
    // Test plan: calling init more than once must throw -- the shared cell is bound exactly once at
    // wiring time.
    val source: AssignerProbeSource = new AssignerProbeSource()
    source.init(cellOf(Some(true)))

    intercept[IllegalStateException] {
      source.init(cellOf(Some(false)))
    }
  }

  test("readiness counter records (state, enabled) across probes") {
    // Test plan: drive the readiness adapter through unbound, then bound-but-unpublished
    // (unknown_status), then healthy, then failed (a drop), and confirm each (state, enabled)
    // label pair sees the expected increments using MetricUtils.ChangeTracker.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    val enabled: AtomicBoolean = new AtomicBoolean(true)
    val source: AssignerProbeSource = new AssignerProbeSource()
    val readinessSource: ProbeStatusSource =
      source.forReadiness(flagProviderOf("test.readiness.enabled", enabled))

    def tracker(state: String, enabledLabel: String): MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker(
        () =>
          MetricUtils.getMetricValue(
            REGISTRY,
            STATE_COUNTER,
            Map("probe" -> "readiness", "state" -> state, "enabled" -> enabledLabel)
          )
      )

    val unboundTracker: MetricUtils.ChangeTracker[Double] = tracker("unbound", "true")
    val unknownStatusTracker: MetricUtils.ChangeTracker[Double] = tracker("unknown_status", "true")
    val healthyTracker: MetricUtils.ChangeTracker[Double] = tracker("healthy", "true")
    val failedTracker: MetricUtils.ChangeTracker[Double] = tracker("failed", "true")

    // Pre-init probe: state="unbound" (cell not bound yet); the flag is on so enabled="true".
    readinessSource.getStatus
    assertResult(1.0)(unboundTracker.totalChange())

    source.init(cell)

    // Bound but the cell has published nothing yet -> state="unknown_status".
    readinessSource.getStatus
    assertResult(1.0)(unknownStatusTracker.totalChange())

    // Cell publishes healthy -> state="healthy".
    cell.setValue(true)
    readinessSource.getStatus
    assertResult(1.0)(healthyTracker.totalChange())

    // Cell publishes unhealthy (a drop after being healthy) -> state="failed".
    cell.setValue(false)
    readinessSource.getStatus
    assertResult(1.0)(failedTracker.totalChange())
  }

  test("liveness counter records (state, enabled) across probes") {
    // Test plan: drive the liveness adapter through unbound (pre-init, flag on), then
    // bound-but-unpublished (unknown_status), then unhealthy+enabled (the would-restart state),
    // then healthy, and confirm each (state, enabled) label pair sees the expected increments via
    // MetricUtils.ChangeTracker. The counter records the connection-health state, not the restart
    // verdict, so an unhealthy cell records state="failed" regardless of the enabled flag.
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    val enabled: AtomicBoolean = new AtomicBoolean(true)
    val source: AssignerProbeSource = new AssignerProbeSource()
    val livenessSource: LivenessStatusSource =
      source.forLiveness(flagProviderOf("test.liveness.enabled", enabled))

    def tracker(state: String, enabledLabel: String): MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker(
        () =>
          MetricUtils.getMetricValue(
            REGISTRY,
            STATE_COUNTER,
            Map("probe" -> "liveness", "state" -> state, "enabled" -> enabledLabel)
          )
      )

    val unboundTracker: MetricUtils.ChangeTracker[Double] = tracker("unbound", "true")
    val unknownStatusTracker: MetricUtils.ChangeTracker[Double] = tracker("unknown_status", "true")
    val failedTracker: MetricUtils.ChangeTracker[Double] = tracker("failed", "true")
    val healthyTracker: MetricUtils.ChangeTracker[Double] = tracker("healthy", "true")

    // Pre-init probe: state="unbound" (cell not bound yet); the flag is on so enabled="true".
    livenessSource.getStatus
    assertResult(1.0)(unboundTracker.totalChange())

    source.init(cell)

    // Bound but the cell has published nothing yet -> state="unknown_status".
    livenessSource.getStatus
    assertResult(1.0)(unknownStatusTracker.totalChange())

    // Cell publishes unhealthy, flag true -> state="failed" (the would-restart state).
    cell.setValue(false)
    livenessSource.getStatus
    assertResult(1.0)(failedTracker.totalChange())

    // Cell publishes healthy -> state="healthy".
    cell.setValue(true)
    livenessSource.getStatus
    assertResult(1.0)(healthyTracker.totalChange())
  }
}
