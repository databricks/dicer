package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.Future

import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.rpc.RPCContext
import com.databricks.caching.util.{PrefixLogger, SequentialExecutionContextPool, TickerTime}
import javax.annotation.concurrent.NotThreadSafe
import com.databricks.dicer.common.Assignment.AssignmentValueCellConsumer
import com.databricks.dicer.common.{
  ClerkSubscriberSlicezData,
  ClientRequest,
  Redirect,
  SliceletSubscriberSlicezData,
  SubscriberHandler
}
import com.databricks.dicer.external.Target

/**
 * This class manages the subscribers that connect to the Assigner for watching assignments.
 *
 * Threading contract: All methods must be called from the Assigner's SEC. Thread safety is
 * enforced by the caller ([[Assigner]]), which holds a [[SequentialExecutionContext]] that
 * serializes all access to internal mutable state.
 *
 * @param targetSecPool                    shared pool on which [[SubscriberHandler]]s run. Each
 *                                         target's [[SubscriberHandler]] will create its own SEC
 *                                         to allow them to run concurrently.
 * @param getSuggestedClerkRpcTimeoutFn    the function to dynamically get the suggested timeout
 *                                         to inform the [[Clerk]]s to use.
 * @param suggestedSliceletRpcTimeout      the suggested timeout to inform the [[Slicelet]]s to
 *                                         use.
 */
@NotThreadSafe
private[assigner] class SubscriberManager(
    targetSecPool: SequentialExecutionContextPool,
    getSuggestedClerkRpcTimeoutFn: () => FiniteDuration,
    suggestedSliceletRpcTimeout: FiniteDuration) {

  private val logger: PrefixLogger = PrefixLogger.create(this.getClass, "")

  /**
   * Maps each target to its [[SubscriberHandler]] and the last time the handler was accessed via a
   * watch request. Keeping both in a single map eliminates the need for invariant checking between
   * separate collections.
   */
  private val handlerEntries: mutable.HashMap[Target, SubscriberManager.HandlerEntry] =
    new mutable.HashMap[Target, SubscriberManager.HandlerEntry]

  /**
   * Given a watch request, responds to it if and when it has newer information about an
   * assignment, based on `cell`.
   *
   * @param redirect a redirect for the response that tells the sender to what is the next endpoint
   *                 to send the request to.
   */
  def handleWatchRequest(
      rpcContext: RPCContext,
      request: ClientRequest,
      cell: AssignmentValueCellConsumer,
      redirect: Redirect,
      currentTime: TickerTime): Future[ClientResponseP] = {
    val handler: SubscriberHandler = fetchHandler(request.target, currentTime)
    handler.handleWatch(rpcContext, request, cell, redirect)
  }

  /**
   * Returns a tuple containing the debugging information about Slicelets and Clerks for the given
   * target - this information is then displayed on the slicez Zpage.
   */
  def getSlicezData(target: Target)
      : Future[(Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData])] = {
    handlerEntries.get(target) match {
      case Some(entry: SubscriberManager.HandlerEntry) => entry.handler.getSlicezData
      case None => Future.successful((Seq.empty, Seq.empty))
    }
  }

  /**
   * Removes [[SubscriberHandler]]s that have not been accessed within `inactivityThreshold` of
   * `currentTime`. Handlers that have been inactive for longer than the threshold are considered
   * drained and are cancelled and removed.
   *
   * The threshold is accepted as a parameter (rather than a constructor field) because it is a
   * policy decision owned by the caller ([[Assigner]]), which may derive it from dynamically
   * configurable values.
   *
   * @param currentTime the current ticker time, provided by the Assigner.
   * @param inactivityThreshold handlers inactive for at least this duration are removed.
   */
  @throws[IllegalArgumentException]("if inactivityThreshold is not positive")
  def removeInactiveHandlers(currentTime: TickerTime, inactivityThreshold: FiniteDuration): Unit = {
    require(inactivityThreshold > Duration.Zero, "inactivityThreshold must be positive")

    // Collect inactive entries first to avoid modifying the map while iterating over it.
    val inactiveEntries: Seq[(Target, FiniteDuration)] = handlerEntries.flatMap { mapEntry =>
      val (target, handlerEntry): (Target, SubscriberManager.HandlerEntry) = mapEntry
      val inactivityDuration: FiniteDuration = currentTime - handlerEntry.lastAccessedTime
      if (inactivityDuration >= inactivityThreshold) {
        Some((target, inactivityDuration))
      } else {
        None
      }
    }.toSeq

    for (inactiveEntry <- inactiveEntries) {
      val (target, inactivityDuration): (Target, FiniteDuration) = inactiveEntry
      logger.info(
        s"Removing inactive subscriber handler for target $target " +
        s"after $inactivityDuration of inactivity."
      )
      handlerEntries.remove(target).foreach(_.handler.cancel())
    }
  }

  /**
   * For a given target, returns the subscriber handler if one exists. If there is none,
   * creates one and returns it. Updates the last accessed time for the target.
   */
  private def fetchHandler(target: Target, currentTime: TickerTime): SubscriberHandler = {
    handlerEntries.get(target) match {
      case Some(entry: SubscriberManager.HandlerEntry) =>
        entry.lastAccessedTime = currentTime
        entry.handler
      case None =>
        val entry: SubscriberManager.HandlerEntry = new SubscriberManager.HandlerEntry(
          handler = new SubscriberHandler(
            targetSecPool.createExecutionContext(s"subscriber-handler-$target"),
            target,
            getSuggestedClerkRpcTimeoutFn,
            suggestedSliceletRpcTimeout,
            SubscriberHandler.Location.Assigner
          ),
          lastAccessedTime = currentTime
        )
        handlerEntries.put(target, entry)
        entry.handler
    }
  }

  private[assigner] object forTest {

    /** Returns whether the given target has a handler in the handler entries. */
    def hasHandler(target: Target): Boolean = handlerEntries.contains(target)

    /** Returns the number of handlers in the handler entries. */
    def handlerCount: Int = handlerEntries.size
  }
}

private[assigner] object SubscriberManager {

  /**
   * Pairs a [[SubscriberHandler]] with the last time it was accessed via a watch request.
   *
   * Threading: instances are only accessed from the Assigner's SEC, which serializes all
   * mutation of [[lastAccessedTime]].
   *
   * @param handler the subscriber handler for a target.
   * @param lastAccessedTime the ticker time of the most recent watch request for this target.
   */
  private class HandlerEntry(val handler: SubscriberHandler, var lastAccessedTime: TickerTime)
}
