package com.databricks.dicer.common

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.dicer.external.Target

/**
 * A wrapper around a [[SubscriberHandler]] to provide a common interface to different
 * implementations (e.g., running in the main test process or in a subprocess).
 */
trait SubscriberHandlerDriver {

  /** Sets the assignment for the SubscriberHandler. */
  def setAssignment(assignment: Assignment): Unit

  /**
   * Handles a watch request.
   *
   * @param request The client request to handle.
   * @param redirectOpt Optional redirect to include in the response.
   */
  def handleWatch(request: ClientRequest, redirectOpt: Option[Redirect]): Future[ClientResponseP]

  /** Advances fake time by the specified duration. */
  def advanceTime(duration: FiniteDuration): Unit

  /**
   * Waits for the specified number of `handleWatch` calls (for the given target) to have their
   * timeout sleeps registered with the clock. Call this before [[advanceTime]] to avoid race
   * conditions where time is advanced before a handleWatch RPC has registered its timeout.
   *
   * @param target The target for which to wait for sleep registrations.
   * @param expectedWatchCount The number of handleWatch calls that should have registered sleeps.
   */
  def waitForSubscriberHandler(target: Target, expectedWatchCount: Int): Unit

  /**
   * Blocks the handler's sequential executor so that subsequent tasks queue behind it.
   * Call [[unblockSequentialExecutor]] to release.
   */
  def blockSequentialExecutor(): Unit

  /** Unblocks a previously blocked sequential executor, allowing queued tasks to proceed. */
  def unblockSequentialExecutor(): Unit

  /** Cancels the handler. */
  def cancel(): Unit
}
