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
}
