package com.databricks.dicer.assigner

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import io.prometheus.client.Counter

import com.databricks.backend.common.util.Project
import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.WatchValueCell
import com.databricks.common.status.{ProbeStatus, ProbeStatusSource, ProbeStatuses}
import com.databricks.featureflag.client.utils.FlagValueProvider

/**
 * A [[ProbeStatusSource]] that returns [[ProbeStatuses.notYetReady]] when the bound enabled
 * flag is `true` AND the bound eligibility cell reports `Some(false)`. The kubelet pulls
 * the pod from `Service` endpoints on a non-2xx response, fencing it from peer traffic.
 *
 * Late-bound because the framework's `newReadinessSource()` hook fires before
 * `wrappedMain` runs: the source must exist and answer probes before the Assigner and its
 * SAFE flag accessor are constructed. Once `wrappedMain` builds those, it calls [[init]]
 * once with both inputs. While unbound, [[getStatus]] returns OK so the framework's
 * startup gate (composed via `enableMultiReadinessProbe`) controls early-boot readiness.
 *
 * [[getStatus]] reads the bound inputs by calling
 * [[WatchValueCell.Consumer#getLatestValueOpt]] and [[FlagValueProvider#getCurrentValue()]]
 * on every probe so cell updates and flag flips take effect live.
 *
 * Increments the labelled counter `dicer_assigner_readiness_source_state_total`
 * (`state` ∈ `{unbound, ready, not_ready}`, `enabled` ∈ `{true, false}`) once per
 * [[getStatus]] call so each kubelet probe leaves a trace; this captures rapid transitions
 * that a single-sample gauge would miss between scrapes (~1m).
 */
private[assigner] final class AssignerReadinessSource extends ProbeStatusSource {

  /** Guards [[inputsOpt]]. */
  private val stateLock: ReentrantLock = new ReentrantLock()

  /**
   * `None` until [[init]] is called; probe returns OK while `None`. Holds both inputs
   * together so the source has no half-initialized state.
   */
  @GuardedBy("stateLock")
  private var inputsOpt: Option[(WatchValueCell.Consumer[Boolean], FlagValueProvider[Boolean])] =
    None

  override def getStatus: ProbeStatus = {
    val snap: Option[(WatchValueCell.Consumer[Boolean], FlagValueProvider[Boolean])] =
      snapshotInputs()
    val readyOpt: Option[Boolean] = snap.flatMap {
      case (cell: WatchValueCell.Consumer[Boolean], _) => cell.getLatestValueOpt
    }
    val enabled: Boolean = snap.exists {
      case (_, flag: FlagValueProvider[Boolean]) => flag.getCurrentValue()
    }
    incrementStateCounter(readyOpt, enabled)
    if (enabled && readyOpt.contains(false)) {
      ProbeStatuses.notYetReady(AssignerReadinessSource.SERVICE_NAME)
    } else {
      ProbeStatuses.ok(AssignerReadinessSource.SERVICE_NAME)
    }
  }

  /**
   * Snapshots the bound inputs under [[stateLock]] so the cell/flag reads outside the
   * critical section don't run while the lock is held (the cell and flag take their own
   * locks internally on read).
   */
  private def snapshotInputs()
      : Option[(WatchValueCell.Consumer[Boolean], FlagValueProvider[Boolean])] =
    withLock(stateLock) {
      inputsOpt
    }

  /**
   * Binds the eligibility cell and the enabled SAFE flag. Both are re-read on every
   * [[getStatus]] call. The cell may publish `None` (driver has no signal yet); the
   * source records that under the `unbound` counter label and falls back to ready/OK.
   */
  @throws[IllegalStateException]("if called more than once")
  private[assigner] def init(
      eligibilityCell: WatchValueCell.Consumer[Boolean],
      enabledFlag: FlagValueProvider[Boolean]): Unit = withLock(stateLock) {
    if (inputsOpt.isDefined) {
      throw new IllegalStateException("init must be called exactly once")
    }
    inputsOpt = Some((eligibilityCell, enabledFlag))
  }

  /** Increments the labelled state counter to record `(readyOpt, enabled)`. */
  private def incrementStateCounter(readyOpt: Option[Boolean], enabled: Boolean): Unit = {
    val state: String = readyOpt match {
      case None => "unbound"
      case Some(true) => "ready"
      case Some(false) => "not_ready"
    }
    AssignerReadinessSource.stateCounter.labels(state, enabled.toString).inc()
  }
}

private[assigner] object AssignerReadinessSource {

  /** Service name embedded in probe status messages. */
  private val SERVICE_NAME: String = Project.DicerAssigner.name

  /**
   * Counter with `state` (`unbound`/`ready`/`not_ready`) and `enabled`
   * (`true`/`false`) labels. Operators can `rate(...)` over either axis to observe
   * probe-observation behaviour distinct from gate state.
   */
  private val stateCounter: Counter = Counter
    .build()
    .name("dicer_assigner_readiness_source_state_total")
    .help(
      "Per-(state, enabled) probe-observation count from AssignerReadinessSource. " +
      "Incremented once per getStatus call; state ∈ {unbound, ready, not_ready}, " +
      "enabled is the SAFE flag value at probe time."
    )
    .labelNames("state", "enabled")
    .register()
}
