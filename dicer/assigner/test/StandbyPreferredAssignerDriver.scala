package com.databricks.dicer.assigner

import scala.concurrent.Future

import com.databricks.caching.util.{Cancellable, ValueStreamCallback, WatchValueCell}
import com.databricks.dicer.common.Generation

/**
 * A [[PreferredAssignerDriver]] that always reports the current Assigner as a standby by
 * publishing a [[PreferredAssignerValue.NoAssigner]] value (which
 * [[PreferredAssignerConfig.create]] maps to [[AssignerRole.Standby]]). Lets a test exercise the
 * standby watch path without the etcd-backed election machinery the real drivers need.
 *
 * Unlike a standby elected against a live preferred assigner, `NoAssigner` leaves
 * `preferredAssignerUriOpt` empty, so the Assigner redirects to a random assigner via an empty
 * redirect rather than to a specific URI.
 */
private[dicer] class StandbyPreferredAssignerDriver extends PreferredAssignerDriver {

  /** Publishes the standby config to watchers once `start` seeds it with the Assigner identity. */
  private val cell: WatchValueCell[PreferredAssignerConfig] =
    new WatchValueCell[PreferredAssignerConfig]()

  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit = {
    cell.setValue(
      PreferredAssignerConfig.create(
        PreferredAssignerValue.NoAssigner(Generation.EMPTY),
        assignerInfo
      )
    )
  }

  override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable =
    cell.watch(callback)

  /** No-op: this fake has no state machine to notify of termination. */
  override def sendTerminationNotice(): Unit = ()

  /** No-op: the fake never acts as a preferred assigner, so it receives no heartbeats. */
  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] =
    Future.successful(HeartbeatResponse(request.opId, request.preferredAssignerValue))

  /** The fake runs no consistent-hashing election, so it has no snapshot. */
  override private[assigner] def consistentHashingStateView
      : Future[Option[ConsistentHashingState]] =
    Future.successful(None)
}
