package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future

import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.rpc.RPCContext
import com.databricks.caching.util.SequentialExecutionContextPool
import com.databricks.dicer.common.Assignment.AssignmentValueCellConsumer
import com.databricks.dicer.common.{
  ClerkSubscriberSlicezData,
  ClientRequest,
  Redirect,
  SliceletSubscriberSlicezData,
  SubscriberHandler
}
import com.databricks.dicer.external.Target
import javax.annotation.concurrent.NotThreadSafe

/**
 * This class manages the subscribers that connect to the Assigner for watching assignments. It is
 * NOT threadsafe.
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

  /** Keep track of a handler for each sharded set of resources. */
  private val subscriberMap = new mutable.HashMap[Target, SubscriberHandler]

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
      redirect: Redirect): Future[ClientResponseP] = {
    val handler: SubscriberHandler = fetchHandler(request.target)
    handler.handleWatch(rpcContext, request, cell, redirect)
  }

  /**
   * Returns a tuple containing the debugging information about Slicelets and Clerks for the given
   * target - this information is then displayed on the slicez Zpage.
   */
  def getSlicezData(target: Target)
      : Future[(Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData])] = {
    subscriberMap.get(target) match {
      case Some(subscriberHandler) => subscriberHandler.getSlicezData
      case None => Future.successful((Seq.empty, Seq.empty))
    }
  }

  /**
   * For a given target, returns the subscriber handler if one exists. If there is none,
   * creates one and returns it.
   */
  private def fetchHandler(target: Target): SubscriberHandler = {
    subscriberMap
      .getOrElseUpdate(
        target,
        new SubscriberHandler(
          targetSecPool.createExecutionContext(s"subscriber-handler-$target"),
          target,
          getSuggestedClerkRpcTimeoutFn,
          suggestedSliceletRpcTimeout,
          SubscriberHandler.Location.Assigner
        )
      )
  }
}
