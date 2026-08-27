package com.databricks.dicer.assigner

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import io.prometheus.client.Counter

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.WatchValueCell
import com.databricks.common.status.{ProbeStatus, ProbeStatuses, ProbeStatusSource}
import com.databricks.common.status.liveness.LivenessStatusSource
import com.databricks.featureflag.client.utils.FlagValueProvider

/**
 * Backs both of an Assigner's Kubernetes probes from a single membership-checker connection-health
 * signal. AssignerProbeSource learns the Kubernetes connection health from a [[WatchValueCell]]
 * bound via [[init]], and its [[forReadiness]] and [[forLiveness]] adapters expose the
 * [[ProbeStatusSource]] and [[LivenessStatusSource]] that the Kubernetes framework calls for
 * readiness and liveness probing.
 *
 * The two adapters sharing the connection-health cell behave differently based on their parameters:
 *  - `failOnUnknown` decides the verdict when the health value is absent -- the cell is unbound
 *    (before [[init]]) or bound but not yet published. Readiness sets it true to fence a
 *    never-connected pod out of the Service endpoints; liveness sets it false so a booting pod is
 *    not restarted in that case.
 *  - `unhealthyStatus` is what the probe returns when it fails: [[ProbeStatuses.notYetReady]] for
 *    readiness, [[ProbeStatuses.needRestart]] for liveness.
 *  - Each adapter dynamically reads its own enable SAFE flag; while the flag is false the adapter
 *    always reports OK.
 *
 * A published healthy value passes the probe; an unhealthy value fails it (recorded on the metric
 * as `state="failed"` for both probes).
 *
 * The instance is created before the WatchValueCell for Kubernetes health exists. The Assigner
 * always has a membership checker, so `init` is always called and gating is governed by the bound
 * enabled flag (OK-always while it is false). Because an enabled readiness adapter reports
 * not-ready during the pre-first-poll window, it MUST be composed with the framework's default
 * readiness source via `enableMultiReadinessProbe` rather than replacing it, so the framework's own
 * startup gating still applies.
 */
private[assigner] final class AssignerProbeSource {

  /** Guards [[cellOpt]]. */
  private val stateLock: ReentrantLock = new ReentrantLock()

  /**
   * The shared connection-health cell that both probes observe; `None` until [[init]] binds it.
   */
  @GuardedBy("stateLock")
  private var cellOpt: Option[WatchValueCell.Consumer[Boolean]] = None

  /**
   * Binds the connection-health cell shared by both probes. It is re-read on every probe. The cell
   * returns `None` (meaning the Kubernetes connection health is unknown) until the checker's first
   * poll resolves the connection-health verdict.
   */
  @throws[IllegalStateException]("if called more than once")
  def init(healthCell: WatchValueCell.Consumer[Boolean]): Unit =
    withLock(stateLock) {
      if (cellOpt.isDefined) {
        throw new IllegalStateException("init must be called exactly once")
      }
      cellOpt = Some(healthCell)
    }

  /**
   * Returns a readiness adapter that returns not-ready when the Kubernetes connection is unhealthy
   * or its status is unknown. `enabledFlag` gates whether the probe enforces at all.
   */
  def forReadiness(enabledFlag: FlagValueProvider[Boolean]): ProbeStatusSource =
    withLock(stateLock) {
      new Adapter(
        enabledFlag = enabledFlag,
        probeLabel = "readiness",
        failOnUnknown = true,
        unhealthyStatus = ProbeStatuses.notYetReady(AssignerProbeSource.SERVICE_NAME)
      )
    }

  /**
   * Returns a liveness adapter that returns need-restart when the Kubernetes connection is
   * unhealthy. Before the connection is established the pod stays alive. `enabledFlag` gates
   * whether the probe enforces at all.
   */
  def forLiveness(enabledFlag: FlagValueProvider[Boolean]): LivenessStatusSource =
    withLock(stateLock) {
      new Adapter(
        enabledFlag = enabledFlag,
        probeLabel = "liveness",
        failOnUnknown = false,
        unhealthyStatus = ProbeStatuses.needRestart(AssignerProbeSource.SERVICE_NAME)
      )
    }

  /**
   * Adapts the shared connection-health cell to a single framework probe. Implements
   * [[LivenessStatusSource]] (a subtype of [[ProbeStatusSource]]) so one class satisfies both the
   * readiness and liveness hooks; [[forReadiness]] returns it typed as [[ProbeStatusSource]].
   * `probeLabel` (`readiness`/`liveness`) tags the shared [[AssignerProbeSource.stateCounter]].
   */
  private final class Adapter(
      enabledFlag: FlagValueProvider[Boolean],
      probeLabel: String,
      failOnUnknown: Boolean,
      unhealthyStatus: ProbeStatus)
      extends LivenessStatusSource {

    override def getStatus: ProbeStatus = {
      val boundCellOpt: Option[WatchValueCell.Consumer[Boolean]] = withLock(stateLock)(cellOpt)
      val healthOpt: Option[Boolean] =
        boundCellOpt.flatMap { cell: WatchValueCell.Consumer[Boolean] =>
          cell.getLatestValueOpt
        }
      val enabled: Boolean = enabledFlag.getCurrentValue()
      incrementStateCounter(healthOpt, enabled, bound = boundCellOpt.isDefined)
      if (enabled && shouldFail(healthOpt)) {
        unhealthyStatus
      } else {
        ProbeStatuses.ok(AssignerProbeSource.SERVICE_NAME)
      }
    }

    /**
     * Whether the current connection health should fail this probe. An absent value -- the cell is
     * unbound (before [[init]]) or bound but not yet published -- fails iff [[failOnUnknown]]; a
     * published value fails iff the connection is unhealthy (`Some(false)`).
     */
    private def shouldFail(healthOpt: Option[Boolean]): Boolean = healthOpt match {
      case None => failOnUnknown
      case Some(connectionHealthy: Boolean) => !connectionHealthy
    }

    /**
     * Increments [[AssignerProbeSource.stateCounter]] for this probe to record `(probe, state,
     * enabled)`. `state` is `unbound` before [[init]], `unknown_status` once bound but before the
     * first published value (the pod has never confirmed connectivity), then `healthy` for
     * `Some(true)` and `failed` for `Some(false)`.
     */
    private def incrementStateCounter(
        healthOpt: Option[Boolean],
        enabled: Boolean,
        bound: Boolean): Unit = {
      val state: String = healthOpt match {
        case Some(true) => "healthy"
        case Some(false) => "failed"
        case None if bound => "unknown_status"
        case None => "unbound"
      }
      AssignerProbeSource.stateCounter.labels(probeLabel, state, enabled.toString).inc()
    }
  }
}

private[assigner] object AssignerProbeSource {

  /** Service name embedded in probe status messages. */
  private val SERVICE_NAME: String = "dicer-assigner"

  /**
   * Shared probe-observation counter for both probes, incremented once per `getStatus` call and
   * labelled by `probe` (`readiness`/`liveness`), `state`
   * (`unbound`/`unknown_status`/`healthy`/`failed`), and `enabled` (`true`/`false`).
   */
  private val stateCounter: Counter = Counter
    .build()
    .name("dicer_assigner_probe_source_state_total")
    .help(
      "Per-(probe, state, enabled) probe-observation count. Incremented once per getStatus call; " +
      "probe in {readiness, liveness}, state in {unbound, unknown_status, healthy, failed}, " +
      "enabled is the SAFE flag value at probe time. unknown_status means bound but no signal yet."
    )
    .labelNames("probe", "state", "enabled")
    .register()
}
