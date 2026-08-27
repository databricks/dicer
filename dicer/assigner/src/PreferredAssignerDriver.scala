package com.databricks.dicer.assigner

import com.databricks.caching.util.{Cancellable, ValueStreamCallback}

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
   * Consistent-hashing election snapshot for the Assigner debug page, or `None` for drivers that
   * do not run a consistent-hashing election (the etcd-backed and disabled drivers). TODO(<internal bug>):
   * remove once the migration to consistent-hashing PA is complete and the debug page no longer
   * needs a split view with the etcd preferred-assigner.
   */
  private[assigner] def consistentHashingStateView: Future[Option[ConsistentHashingState]]
}
