package com.databricks.dicer.assigner

import com.databricks.caching.util.{Cancellable, ValueStreamCallback, WatchValueCell}

import scala.concurrent.Future

/**
 * Maintains a single preferred assigner based on incoming signals to make assignment generation
 * highly available.
 *
 * For full details, see <internal link>.
 */
trait PreferredAssignerDriver {

  /**
   * Starts the driver with this Assigner identified as `assignerInfo`.
   *
   * Must be called before any other methods.
   */
  def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit

  /** Watches for updates to the [[PreferredAssignerConfig]] in the preferred assigner driver. */
  def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable

  /** Forwards a termination notice to the driver. */
  def sendTerminationNotice(): Unit

  /** Handles the heartbeat request from an assigner who identifies itself as a standby. */
  def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse]

  /**
   * Updates the externally-supplied preferred-assigner pick. Pass `None` to clear the pick.
   * Drivers that model an external-pick input override this; the default is a no-op.
   *
   * Implementations must accept calls from any thread and must not perform blocking I/O on
   * the caller's thread; the typical implementation hands off the pick to its own
   * `SequentialExecutionContext` for processing.
   *
   * The pick stream is not dedup'd; implementations must tolerate identical re-deliveries
   * (the upstream watch stream may re-fire on subscribe or on internal state changes that
   * don't affect the elected assigner identity).
   */
  private[assigner] def updateExternalPick(externalPickOpt: Option[AssignerInfo]): Unit = ()

  /**
   * Whether this process is currently eligible to be selected as the preferred assigner:
   * `Some(true)` if eligible, `Some(false)` if disqualified by an eligibility factor (e.g.
   * K8s membership-checker connection health, in-flight migration phase), and `None` when
   * no signal has been observed yet -- callers decide how to interpret absence of a
   * signal. Drivers with no factors publish `Some(true)` via
   * [[PreferredAssignerDriver.ALWAYS_ELIGIBLE]]. The cell handles cross-thread reads;
   * consumers can call `getLatestValueOpt` from any thread.
   */
  private[assigner] def selectionEligibilityWatchCell: WatchValueCell.Consumer[Boolean]
}

private[assigner] object PreferredAssignerDriver {

  /**
   * Shared selection-eligibility cell for drivers with no eligibility factors -- always
   * publishes `Some(true)`. Safe to share because [[WatchValueCell.Consumer]] is read-only.
   */
  val ALWAYS_ELIGIBLE: WatchValueCell.Consumer[Boolean] = {
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    cell.setValue(true)
    cell
  }
}
