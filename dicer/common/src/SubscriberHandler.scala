package com.databricks.dicer.common

import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.{GuardedBy, NotThreadSafe, ThreadSafe}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import io.grpc.{Status, StatusException}
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import com.databricks.api.proto.dicer.common.{ClientResponseP, SyncAssignmentStateP}
import com.databricks.caching.util.CachingErrorCode.SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE
import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  Severity,
  TickerTime,
  ValueStreamCallback
}
import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter
import com.databricks.common.serviceidentity.ServiceIdentity
import com.databricks.dicer.common.Assignment.AssignmentValueCellConsumer
import com.databricks.dicer.common.SubscriberHandler.CachedSyncAssignmentState
import com.databricks.dicer.common.SubscriberHandler.{
  ClerkInfo,
  Location,
  MetricsKey,
  SliceletInfo,
  SubscriberInfo
}
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.rpc.RPCContext

/**
 * An abstraction that manages all the subscribers for a given sharded set of resources. It uses the
 * given `cell` to wait for changes to assignments and informs the pending RPC callers when the
 * assignment changes. `suggestedRpcTimeout` is the timeout that the subscriber handler informs the
 * RPC sender to use for its future RPCs. `handlerLocation` indicates where the handler is running.
 *
 * @param sec the sec that protects the internal state and schedules background tasks.
 * @param target the target for which this handler is serving.
 * @param getSuggestedClerkRpcTimeoutFn the function to dynamically get the suggested watch RPC
 *                                      timeout for [[Clerk]].
 * @param suggestedSliceletRpcTimeout the suggested watch RPC timeout for [[Slicelet]].
 * @param handlerLocation the location of this handler.
 */
@ThreadSafe
class SubscriberHandler(
    sec: SequentialExecutionContext,
    target: Target,
    getSuggestedClerkRpcTimeoutFn: () => FiniteDuration,
    suggestedSliceletRpcTimeout: FiniteDuration,
    handlerLocation: Location) {

  import SubscriberHandler.AssignmentDistributionLatencyTracker

  private val logger = PrefixLogger.create(this.getClass, target.getLoggerPrefix)

  /** Pre-computed metric children, cached to avoid repeated label hashing on every call. */
  private val loadShedCounterChild: Counter.Child =
    SubscriberHandlerMetrics.watchRequestsLoadShed.labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel
    )
  private val clientResponseSizeChild: Histogram.Child =
    SubscriberHandlerMetrics.clientResponseSizeBytes.labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel,
      handlerLocation.toString
    )
  private val serializedAssignmentSizeChild: Histogram.Child =
    SubscriberHandlerMetrics.serializedAssignmentSizeBytes.labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel,
      handlerLocation.toString
    )

  /**
   * Stores the assignment state proto and generation for the latest full assignment processed from
   * the assignment cell.
   * It is reused to avoid redundant proto conversions. The state is updated whenever a newer full
   * assignment is received with a higher generation number.
   */
  @GuardedBy("sec")
  private var cachedSyncAssignmentStateOpt: Option[CachedSyncAssignmentState] = None

  /**
   * Subscriber debug name -> SubscriberInfo for those that have recently connected with the
   * SubscriberHandler.
   */
  @GuardedBy("sec")
  private val recentSubscribers: Cache[String, SubscriberInfo] =
    SCaffeineCacheInfoExporter.registerCache(
      "recent_subscribers",
      Scaffeine()
        .expireAfterAccess(SubscriberHandlerMetrics.EXPIRE_AFTER)
        .ticker(() => sec.getClock.tickerTime().nanos)
        .build()
    )

  /**
   * Handle for a repeating task that periodically updates the metrics in
   * `SubscriberHandlerMetrics`.
   *
   * Note that we do NOT just register a callback to perform clean-up before Prometheus collects
   * metrics: while the Prometheus .NET client supports registering for callbacks before metrics are
   * collected, we have no such facility in the Java client. Even if that facility existed, it would
   * be inconvenient to use as we'd need to maintain a global registry of [[SubscriberHandler]]
   * instances to figure out the latest counts per target.
   */
  private val backgroundUpdateMetricsHandle: Cancellable =
    sec.scheduleRepeating(
      "updateSubscriberMetrics",
      interval = SubscriberHandlerMetrics.EXPIRE_AFTER,
      () => {
        updateSubscriberMetrics()
      }
    )

  /**
   * Track the metric labels that we published previously, for this target. We need to track this to
   * stop publishing metrics for the keys that get removed.
   */
  @GuardedBy("sec")
  private var lastPublishedMetrics: mutable.Set[MetricsKey] = mutable.Set.empty

  /**
   * Tracks the time it takes for a new assignment to reach Slicelets. See
   * [[AssignmentDistributionLatencyTracker]] for details.
   */
  @GuardedBy("sec")
  private val assignmentDistributionLatencyTracker: AssignmentDistributionLatencyTracker =
    new AssignmentDistributionLatencyTracker(target)

  /**
   * Handles the watch request `req` and returns the response if and when it has newer information
   * about an assignment, based on `cell`. We will attempt to send a response before
   * `request.timeout` - if a newer assignment is not available then
   * [[SyncAssignmentState.KnownGeneration]] will be returned.
   *
   * @param redirect a redirect for the response that tells the sender to what is the next endpoint
   *                 to send the request to. This is defaulted to `Redirect.EMPTY` which means that
   *                 the sender should send the next request to a random endpoint.
   */
  def handleWatch(
      rpcContext: RPCContext,
      request: ClientRequest,
      cell: AssignmentValueCellConsumer,
      redirect: Redirect = Redirect.EMPTY): Future[ClientResponseP] = {
    // Explicitly store `startTime` before calling into `sec`, because the `sec` may have a queue
    // built up and we want to account for its scheduling delay. This should be the only thing we do
    // that is not performed on `sec`.
    val startTime: TickerTime = sec.getClock.tickerTime()

    sec
      .flatCall {
        // Get the caller service identity, currently for monitoring purposes and eventually for ACL
        // checking.
        val serviceIdentityOpt: Option[ServiceIdentity] = rpcContext.getCallerIdentity

        logger.info(
          s"Watch request: $serviceIdentityOpt, ${request.subscriberDebugName}, " +
          s"${request.subscriberData}",
          30.seconds
        )

        updateWatchMetrics(request, serviceIdentityOpt)

        if (TargetHelper.isFatalTargetMismatch(target, request.target)) {
          // Implementation note: because of the way `SubscriberHandler` is used in the Assigner (a
          // `SubscriberHandler` is chosen to respond to a request based on a matching target), we
          // do not expect these errors to surface in the Assigner.
          Future.failed(
            new StatusException(
              Status.NOT_FOUND.withDescription(
                s"Request for target ${request.target} misrouted to handler for target $target. " +
                s"See 'Configuration: Clients' in <internal link>."
              )
            )
          )
        } else {
          replyFromCell(startTime, request, cell, redirect)
        }
      }
      .map { responseP: ClientResponseP =>
        // Record response size metrics using the cached histogram children.
        clientResponseSizeChild.observe(responseP.serializedSize.toDouble)
        for (syncState: SyncAssignmentStateP <- responseP.syncAssignmentState) {
          serializedAssignmentSizeChild.observe(syncState.serializedSize.toDouble)
        }
        responseP
      }(sec)
  }

  /** Returns the information about the connect Clerks and Slicelets for the slicez Zpage. */
  def getSlicezData: Future[(Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData])] =
    sec.call {
      // Simply extract the relevant data from recentSlicelets and recentClerks.
      val sliceletData = new ArrayBuffer[SliceletSubscriberSlicezData]
      val clerkData = new ArrayBuffer[ClerkSubscriberSlicezData]
      recentSubscribers
        .asMap()
        .map(entry => {
          val (name, info): (String, SubscriberInfo) = entry
          info match {
            case SliceletInfo(_: Long, address: String) =>
              sliceletData.append(SliceletSubscriberSlicezData(name, address))
            case ClerkInfo(_: Long) =>
              clerkData.append(ClerkSubscriberSlicezData(name))
          }
        })

      (sliceletData.toSeq, clerkData.toSeq)
    }

  /** Resets metrics and stops background clean-up task. */
  def cancel(): Unit = sec.run {
    // Discard all cached debug entries.
    recentSubscribers.invalidateAll()
    updateSubscriberMetrics()

    // Stop periodically updating metrics.
    backgroundUpdateMetricsHandle.cancel(Status.CANCELLED)
  }

  /** Updates various metrics based on receiving the watch request `request`. */
  private def updateWatchMetrics(
      request: ClientRequest,
      serviceIdentityOpt: Option[ServiceIdentity]): Unit = {
    val (subscriberInfo, isClerk): (SubscriberInfo, Boolean) = request.subscriberData match {
      case sliceletData: SliceletData =>
        // Slicelet-specific logic: update the assignment distribution latency tracker.
        assignmentDistributionLatencyTracker.onAssignmentStateReceived(
          sliceletData.squid,
          request.syncAssignmentState.getKnownGeneration,
          sec.getClock.tickerTime()
        )
        (SliceletInfo(request.version, sliceletData.squid.resourceAddress.toString), false)
      case ClerkData =>
        (ClerkInfo(request.version), true)
    }

    // Track this client in recent-subscribers collection.
    val previousValueOpt: Option[SubscriberInfo] = recentSubscribers
      .asMap()
      .put(request.subscriberDebugName, subscriberInfo)

    val metricsKey = MetricsKey(isClerk = isClerk, request.version)

    if (previousValueOpt.isEmpty) {
      // Newly inserted, eagerly update metric based on the target of the request.
      SubscriberHandlerMetrics
        .getNumSubscribersGauge(handlerLocation, request.target, metricsKey)
        .inc()
      lastPublishedMetrics.add(metricsKey)
    }

    SubscriberHandlerMetrics.incrementWatchRequestsReceived(
      handlerLocation,
      target,
      request.target,
      serviceIdentityOpt,
      metricsKey
    )
  }

  /**
   * Returns a Future which completes when an assignment useful to the `request` is available via
   * `cell`, or after the requested timeout elapses.
   */
  private def replyFromCell(
      startTime: TickerTime,
      request: ClientRequest,
      cell: AssignmentValueCellConsumer,
      redirect: Redirect): Future[ClientResponseP] = {
    // The promise that is used to reply to the caller. Only completed on `sec`.
    val promise = Promise[ClientResponseP]

    // Cancellable for the `watch` registered on `cell` below. Since we're running on the `sec`,
    // it will be initialized before being accessed in `streamCallback`.
    var streamCallbackCancellable: Cancellable = null

    // Watch the changes via the cell (e.g., the Assignment generator in the Assigner) and try to
    // complete the promise only when a higher generation assignment is available.
    val streamCallback = new ValueStreamCallback[Assignment](sec) {
      override def onSuccess(watchedAssignment: Assignment): Unit = {
        if (promise.isCompleted) {
          // If the promise is already completed, there is no point in doing anything. We don't
          // have to cancel `streamCallbackCancellable` here because it was already cancelled when
          // the promise was completed.
          logger.info(
            "Processing an assignment even though the watch response is already sent, this may " +
            "indicate the SEC is overloaded",
            every = 30.seconds
          )
        } else if (sec.getClock.tickerTime() > startTime + request.timeout) {
          // If we are past the request timeout, then it is possible the client has already timed
          // out its request (this is not always the case, since we leave some buffer in the
          // client-side RPC deadline, but we prefer to be conservative). Note that we are not
          // expected to reach here typically because we trigger a timeout below (and cancel the
          // watch) after `WatchServerHelper.getWatchProcessingTimeout(request.timeout)`, which is
          // shorter than `request.timeout`. But if the `sec` is overloaded with watch requests,
          // we could end up here because we can't process the timeout soon enough.
          //
          // Thus, we focus on load shedding and simply send back
          // `SyncAssignmentState.KnownGeneration`. Why do we do this instead of trying to use the
          // cached `SyncAssignmentStateP`?
          // 1. It would add more branches and complexity to try to handle the case where we don't
          //    have the proto cached (especially once we have persistent assignments and do diffs
          //    with different generations).
          // 2. Even with cached `SyncAssignmentStateP`, the RPC layer still has to do nontrivial
          //    work to convert the proto to bytes.
          logger.info(
            "Processing an assignment after the request timeout, this may indicate the SEC is " +
            "overloaded. Returning just the known generation.",
            every = 30.seconds
          )
          loadShedCounterChild.inc()
          val syncState = SyncAssignmentState.KnownGeneration(watchedAssignment.generation)
          val response = ClientResponse(syncState, getSuggestedRpcTimeout(request), redirect)
          // The promise is only modified on SEC, and we know above that `promise.isCompleted` was
          // false, so we can safely call `success` here.
          promise.success(response.toProto)
          streamCallbackCancellable.cancel(Status.CANCELLED)
        } else if (watchedAssignment.generation > request.getKnownGeneration) {
          // Note: The sync state is always derived from the full assignment. If we switch to
          // durable assignments in the future, the sync logic should be optimized to work with
          // partial assignment.
          val syncAssignmentStateP: SyncAssignmentStateP =
            getOrUpdateSyncAssignmentState(
              watchedAssignment,
              request.supportsSerializedAssignment
            )
          val responseP: ClientResponseP = ClientResponse
            .createProtoWithSyncStateP(
              syncAssignmentStateP,
              getSuggestedRpcTimeout(request),
              redirect
            )

          // Record the time just before the assignment is sent out on the wire, so that we can
          // report it to the tracker.
          val preAssignmentDistributionTime: TickerTime = sec.getClock.tickerTime()
          promise.success(responseP)
          logger.debug(s"New assignment from Watch cell: ${watchedAssignment.generation}")
          logger.info(
            s"Sending Watch reply with assignment generation ${watchedAssignment.generation} " +
            s"> request generation ${request.getKnownGeneration}",
            every = 30.seconds
          )
          // Notify the tracker if we're an Assigner distributing to a Slicelet.
          request.subscriberData match {
            case _: SliceletData =>
              assignmentDistributionLatencyTracker.onPreAssignmentDistribution(
                watchedAssignment,
                preAssignmentDistributionTime
              )
            case _ => ()
          }
          streamCallbackCancellable.cancel(Status.CANCELLED)
        }
      }
    }
    streamCallbackCancellable = cell.watch(streamCallback)

    // After starting to watch, schedule a function to cancel the watch if a new assignment is
    // not generated before the deadline expires (hence use a timeout lower than what the client
    // has indicated that it is using).
    //
    // Make sure we apply the timeout from `startTime` rather than the current time, since `sec`
    // may have a queue built up and delay the execution of this handler. But we still want to try
    // to respond to the client in time.
    val processingTimeout: FiniteDuration =
      WatchServerHelper.getWatchProcessingTimeout(request.timeout)
    val elapsedTime: FiniteDuration = sec.getClock.tickerTime() - startTime
    val remainingTimeout: FiniteDuration = (processingTimeout - elapsedTime).max(0.millis)
    // Note that if we respond from the `streamCallback` above, we essentially end up leaking this
    // scheduled task. This is not a correctness issue because we call `promise.trySuccess` below,
    // and this task is relatively cheap.
    sec.schedule(
      s"$target: Watch timer",
      remainingTimeout,
      () => {
        // When this work item runs, try to send a message to the client with just the generation
        // that this handler knows. We could first check if the promise completed and then perform
        // this work, but the work is inexpensive and hence we just perform it and then try to
        // complete the promise.
        val latestAssignmentOpt: Option[Assignment] = cell.getLatestValueOpt
        val knownGeneration: Generation = latestAssignmentOpt match {
          case Some(latestAssignment: Assignment) => latestAssignment.generation
          case None => Generation.EMPTY
        }
        val syncState = SyncAssignmentState.KnownGeneration(knownGeneration)
        val response = ClientResponse(syncState, getSuggestedRpcTimeout(request), redirect)

        if (promise.trySuccess(response.toProto)) {
          logger.info(s"Sending Watch reply before deadline with $syncState", every = 30.seconds)
          streamCallbackCancellable.cancel(Status.CANCELLED)
        }
      }
    )
    promise.future
  }

  /**
   * Returns the [[SyncAssignmentStateP]] for the given full `assignment`.
   * It uses the cached state in [[cachedSyncAssignmentStateOpt]] if possible, and updates the
   * state if needed.
   *
   * @param clientSupportsSerializedAssignment Indicates whether the client supports receiving a
   *                                           serialized assignment. If true, returns a
   *                                           SyncAssignmentStateP containing a serialized
   *                                           assignment; otherwise, returns one containing a
   *                                           [[DiffAssignmentP]].
   *
   */
  private def getOrUpdateSyncAssignmentState(
      assignment: Assignment,
      clientSupportsSerializedAssignment: Boolean): SyncAssignmentStateP = {
    sec.assertCurrentContext()
    // When a newer assignment is received, update the cached state as needed. Reuse it whenever
    // possible to avoid redundant proto conversions. This logic always assumes full assignments.
    // In the future, if we implement durable assignments (i.e., switch to a store backend that
    // uses non-loose incarnations), we could optimize by distributing partial diffs instead of
    // full assignments.

    // Update the cached state.
    val cachedState: CachedSyncAssignmentState = cachedSyncAssignmentStateOpt match {
      case None =>
        // First assignment received, update the cached state.
        updateCachedSyncAssignment(assignment)
      case Some(cachedState: CachedSyncAssignmentState) =>
        if (cachedState.generation < assignment.generation) {
          // Newer assignment received, update the cached state.
          updateCachedSyncAssignment(assignment)
        } else {
          // The cache is up-to-date, no update needed.
          // Note: `assignment` and the cached assignment may share the same
          // generation but theoretically have different content (e.g. if
          // different `cell`s are passed in different calls to `handleWatch`).
          // In such cases, `assignment` is ignored, which is fine - in various
          // places in the code we assume that equal generation means the
          // assignment is equal. In the worst case, subscribers will receive
          // the updated assignment when the next one is generated.
          cachedState
        }
    }
    if (cachedState.generation > assignment.generation) {
      // Stale assignment received, return only the generation. We don't expect this to ever
      // actually occur given how the code is currently structured, but we have added it to
      // be defensive.
      logger.alert(
        Severity.DEGRADED,
        SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE,
        s"getOrUpdateSyncAssignmentState got a stale assignment for $target (generation " +
        s"${assignment.generation} is less than cached generation " +
        s"${cachedState.generation}). Returning the newer cached generation instead.",
        every = 5.minutes
      )
      SyncAssignmentState.KnownGeneration(cachedState.generation).toProto
    } else {
      if (clientSupportsSerializedAssignment) {
        cachedState.syncSerializedAssignmentStateP
      } else {
        cachedState.syncAssignmentStateP
      }
    }
  }

  /** Creates and returns a [[CachedSyncAssignmentState]] from the given `assignment`. */
  private def updateCachedSyncAssignment(assignment: Assignment): CachedSyncAssignmentState = {
    val syncAssignmentState = CachedSyncAssignmentState.fromAssignment(assignment)
    cachedSyncAssignmentStateOpt = Some(syncAssignmentState)
    syncAssignmentState
  }

  /**
   * Update the gauge metrics tracking recent Clerks and Slicelets for [[target]]. Cleans up
   * subscribers that have not been recently seen from the [[recentSubscribers]] cache, since the
   * configured expiry time is otherwise only enforced lazily when individual keys are looked up.
   * Note that this is an expensive operation and should not be called too often.
   */
  private def updateSubscriberMetrics(): Unit = {
    sec.assertCurrentContext()
    recentSubscribers.cleanUp()

    // Count by (isClerk, version).
    val counts: mutable.Map[MetricsKey, Int] = mutable.Map.empty.withDefaultValue(0)
    val iterator: Iterator[(String, SubscriberInfo)] = recentSubscribers.asMap().iterator
    for (entry <- iterator) {
      val (_, info): (String, SubscriberInfo) = entry
      info match {
        case ClerkInfo(version: Long) =>
          counts(MetricsKey(isClerk = true, version)) += 1
        case SliceletInfo(version: Long, _: String) =>
          counts(MetricsKey(isClerk = false, version)) += 1
      }
    }

    // Update metrics based on `counts`.
    for (entry <- counts) {
      val (key, count): (MetricsKey, Int) = entry
      SubscriberHandlerMetrics.getNumSubscribersGauge(handlerLocation, target, key).set(count)
    }

    // Stop publishing metrics for labels that are no longer relevant.
    val currentMetrics: mutable.Set[MetricsKey] = mutable.Set(counts.keySet.toSeq: _*)
    val metricsToUnpublish: mutable.Set[MetricsKey] = lastPublishedMetrics.diff(currentMetrics)
    for (key: MetricsKey <- metricsToUnpublish) {
      SubscriberHandlerMetrics.removeNumSubscribersGauge(handlerLocation, target, key)
    }
    lastPublishedMetrics = currentMetrics
  }

  /** Returns the suggested RPC timeout. */
  private def getSuggestedRpcTimeout(clientRequest: ClientRequest): FiniteDuration = {
    clientRequest.getClientType match {
      case ClientType.Clerk => getSuggestedClerkRpcTimeoutFn()
      case ClientType.Slicelet => suggestedSliceletRpcTimeout
    }
  }
}

object SubscriberHandler {

  /** The location of the handler. */
  sealed trait Location
  object Location {
    case object Assigner extends Location { override def toString: String = "Assigner" }

    case object Slicelet extends Location { override def toString: String = "Slicelet" }

    case object Clerk extends Location { override def toString: String = "Clerk" }
  }

  /** Trait that stores information for either a Clerk or Slicelet, for metrics purposes. */
  sealed trait SubscriberInfo

  /** Track the client code version of Clerks. */
  case class ClerkInfo(version: Long) extends SubscriberInfo

  /** Track the code version and address of Slicelets. */
  case class SliceletInfo(version: Long, address: String) extends SubscriberInfo

  /**
   * Track the information relevant for metrics labels. If `isClerk` is false then it is a
   * Slicelet.
   */
  case class MetricsKey(isClerk: Boolean, version: Long) {

    /** The metric label value to use for the `type` label. */
    def typeLabel: String = if (isClerk) "Clerk" else "Slicelet"

    /** The metric label value to use for the `version` label. */
    def versionLabel: String = version.toString
  }

  /**
   * Attempts to provide a **rough** measure on assignment distribution latency from the Assigner to
   * Slicelets (the time it takes for all resources to sync on a new assignment). It is rough for
   * many reasons:
   *
   * 1. It is measured based only on what the Assigner observes. That is, a distribution time
   *    measurement begins when a new assignment with generation G is first sent to a subscriber,
   *    and ends when the Assigner has observed that all subscribers know generation G (note that
   *    reporting knowledge of a future generation G' > G does not prove receipt of G, since the
   *    subscriber may have skipped G, and therefore cancels the measurement). From this
   *    perspective, the estimate is conservative, since it includes delays due to network latency.
   * 2. Since the tracker cannot know if an unhealthy resource ever learns of an assignment, it only
   *    measures the distribution time to healthy resources (those in the assignment). From this
   *    perspective, the estimate can be optimistic, since in some cases an unhealthy (unassigned)
   *    resource may never learn of the new assignment.
   * 3. Since the tracker cannot know if the Assigner is split brained and another Assigner has sent
   *    out a given assignment first, the estimate can be optimistic in this case as well since the
   *    distribution began earlier than the tracker is aware of.
   * 4. At Assigner startup, the tracker may potentially track an assignment which is the existing
   *    assignment that is already distributed. A more complex implementation could minimize the
   *    probability of this happening, but the purpose of this tracker is provide rough estimates,
   *    so simplicity is preferred.
   */
  @NotThreadSafe
  private class AssignmentDistributionLatencyTracker(target: Target) {
    import AssignmentDistributionLatencyTracker.TrackerState._
    import AssignmentDistributionLatencyTracker._

    /** The latest assignment generation in existence that the tracker is aware of. */
    private var latestGeneration: Generation = Generation.EMPTY

    /** The current state of the tracker. */
    private var trackerState: TrackerState = AwaitingNewAssignmentDistribution

    /** The prometheus histogram for the given `target`. */
    private val distributionLatencyHistogram: Histogram.Child = {
      histogram.labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
    }

    /** Notifies the tracker that the resource with `squid` knows at least `knownGeneration`. */
    def onAssignmentStateReceived(
        squid: Squid,
        knownGeneration: Generation,
        tickerTime: TickerTime): Unit = {
      trackerState match {
        case AwaitingNewAssignmentDistribution => // Not tracking, no distribution to complete.
        case AwaitingDistributionCompletion(
            startTime: TickerTime,
            pendingResources: mutable.Set[Squid]
            ) =>
          if (knownGeneration == latestGeneration) {
            pendingResources.remove(squid)
            if (pendingResources.isEmpty) {
              val elapsedSeconds: Double = (tickerTime - startTime).toMicros / 1e6
              distributionLatencyHistogram.observe(elapsedSeconds)
              trackerState = AwaitingNewAssignmentDistribution
            }
          } else if (knownGeneration > latestGeneration) {
            // The resource is ahead of the assignment generation that we're tracking. This could be
            // because we're a new Assigner and not yet up to speed on the current assignment, or
            // maybe there's another Assigner that's advanced the assignment. Whatever the reason,
            // we can no longer track the distribution of `latestGeneration` since we don't know if
            // the resource ever received it (may have been skipped). Abort tracking.
            trackerState = AwaitingNewAssignmentDistribution
          }
      }

      // Advance `latestGeneration`, if needed.
      if (knownGeneration > latestGeneration) {
        latestGeneration = knownGeneration
      }
    }

    /**
     * Notifies the tracker about an assignment that is about to be distributed to a resource (`now`
     * should be a time just before the assignment is sent). The tracker detects if this marks the
     * beginning of a new assignment distribution (in its estimation, see next), and, if so, starts
     * tracking its progress. Note that even so, it's not necessarily the case that this Assigner is
     * the first to distribute the assignment to a subscriber, so in rare cases the tracker may
     * underestimate the distribution latency since the distribution began through another Assigner
     * before the tracking on this Assigner started.
     */
    def onPreAssignmentDistribution(assignment: Assignment, tickerTime: TickerTime): Unit = {
      // If `assignment` is newer than any the tracker is aware of, start tracking it. Note that
      // this may abort any outstanding tracking of an older assignment.
      if (assignment.generation > latestGeneration) {
        latestGeneration = assignment.generation
        trackerState = AwaitingDistributionCompletion(
          startTime = tickerTime,
          pendingResources = {
            mutable.HashSet[Squid]() ++= assignment.assignedResources
          }
        )
      }
    }
  }

  private object AssignmentDistributionLatencyTracker {

    private val histogram = Histogram
      .build()
      .name("dicer_assigner_assignment_distribution_latency_seconds")
      .help(
        "The time it takes for the Assigner to observe that all assigned resources " +
        "have received a newly distributed assignment."
      )
      .labelNames("targetCluster", "targetName", "targetInstanceId")
      .buckets({
        // Buckets are in seconds.
        val buckets = ArrayBuffer[Double]()
        // Initial measurements indicate that latencies are typically low 10s of milliseconds but
        // can reach up to low 100s of milliseconds. To get some reasonable visibility into this
        // range without excessive bucketing, we use a growth factor of 1.4 in the range [1ms, 1s)
        // (yielding 21 buckets), and then a growth factor of 2 beyond that in the range [1s,
        // 1024s(~17m)] (yielding 11 buckets), for a total of 33 buckets including +Inf.
        var bucket: FiniteDuration = 1.millisecond
        while (bucket < 1.second) {
          buckets.append(bucket.toUnit(TimeUnit.SECONDS))
          bucket = bucket.mul(1400).div(1000) // i.e. *= 1.4
        }
        bucket = 1.second
        while (bucket <= 1024.seconds) {
          buckets.append(bucket.toUnit(TimeUnit.SECONDS))
          bucket *= 2
        }
        buckets.toSeq
      }: _*)
      .register(CollectorRegistry.defaultRegistry)

    /** State of the tracker. */
    sealed trait TrackerState

    private object TrackerState {

      /**
       * The tracker is waiting for the start of a new assignment distribution, which begins when
       * the Assigner distributes an assignment generation that is higher than any that the tracker
       * has seen before.
       */
      object AwaitingNewAssignmentDistribution extends TrackerState

      /**
       * The tracker is waiting for the completion of an assignment distribution which began at
       * `startTime` and has `pendingResources` left before distribution is complete. Distribution
       * concludes when all assigned resources report that they know exactly the assignment
       * generation that triggered the distribution tracking (i.e. `latestGeneration`).
       *
       * Invariant: `pendingResources` is non-empty.
       */
      case class AwaitingDistributionCompletion(
          startTime: TickerTime,
          pendingResources: mutable.Set[Squid])
          extends TrackerState
    }

    object forTest {
      def getHistogram(target: Target): Histogram.Child =
        histogram.labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel
        )
    }
  }

  /**
   * Stores the proto representation of [[SyncAssignmentState]] along with its generation.
   * It is used to avoid redundant proto conversions of the same assignment when handling watch
   * requests from different clients.
   *
   * @param syncAssignmentStateP The proto representation of the state containing a *full*
   *                             assignment. This is used when the client does not support receiving
   *                             a serialized assignment.
   * @param syncSerializedAssignmentStateP The proto representation of the state containing a
   *                                       *serialized full* assignment. This is used when the
   *                                       client supports receiving a serialized assignment.
   * @param generation The generation of the assignment.
   */
  private class CachedSyncAssignmentState private (
      val syncAssignmentStateP: SyncAssignmentStateP,
      val syncSerializedAssignmentStateP: SyncAssignmentStateP,
      val generation: Generation
  )

  private object CachedSyncAssignmentState {

    /**
     * Creates a [[CachedSyncAssignmentState]] from the given full `assignment`.
     *
     * Note: This method takes in an Assignment rather than a DiffAssignment because we only cache
     * full assignments, not partial ones. Revisit this if the cache logic needs to support partial
     * assignments.
     */
    def fromAssignment(assignment: Assignment): CachedSyncAssignmentState = {
      val diffAssignment: DiffAssignment = assignment.toDiff(Generation.EMPTY)
      val (newState, newSerializedState): (SyncAssignmentStateP, SyncAssignmentStateP) =
        SyncAssignmentState.KnownAssignment.toCachedProtos(diffAssignment)
      new CachedSyncAssignmentState(newState, newSerializedState, assignment.generation)
    }
  }

  object forTestStatic {
    def getAssignmentDistributionLatencyHistogram(target: Target): Histogram.Child =
      AssignmentDistributionLatencyTracker.forTest.getHistogram(target)
  }
}

object SubscriberHandlerMetrics {

  /**
   * Histogram buckets for serialized assignment size in bytes. Uses fine granularity (128 KiB
   * steps) up to the gRPC limit (currently 4 MiB, see
   * [[WatchServerHelper.MAX_WATCH_MESSAGE_CONTENT_LENGTH]]) and coarser granularity (1 MiB steps)
   * beyond.
   */
  private val SERIALIZED_ASSIGNMENT_SIZE_BUCKETS: Array[Double] = {
    val buckets = scala.collection.mutable.ArrayBuffer[Double]()

    // Fine-grained buckets from 128 KiB to 4 MiB (128 KiB increments).
    var bucketBytes: Long = 128 * 1024 // 128 KiB.
    while (bucketBytes <= 4 * 1024 * 1024) { // Up to 4 MiB.
      buckets.append(bucketBytes.toDouble)
      bucketBytes += 128 * 1024
    }

    // Coarse-grained buckets from 5 MiB to 10 MiB (1 MiB increments).
    bucketBytes = 5 * 1024 * 1024 // 5 MiB.
    while (bucketBytes <= 10 * 1024 * 1024) { // Up to 10 MiB.
      buckets.append(bucketBytes.toDouble)
      bucketBytes += 1024 * 1024
    }

    buckets.toArray
  }

  private[common] val serializedAssignmentSizeBytes: Histogram = Histogram
    .build()
    .name("dicer_serialized_assignment_size_bytes_histogram")
    .help(
      "The size of serialized assignment state protos distributed by SubscriberHandler in bytes"
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "handlerLocation")
    .buckets(SERIALIZED_ASSIGNMENT_SIZE_BUCKETS: _*)
    .register()

  private[common] val clientResponseSizeBytes: Histogram = Histogram
    .build()
    .name("dicer_client_response_size_bytes_histogram")
    .help("Size of ClientResponseP protos distributed by SubscriberHandler in bytes")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "handlerLocation")
    .buckets(SERIALIZED_ASSIGNMENT_SIZE_BUCKETS: _*)
    .register()

  /**
   * Named tuple for SubscriberHandler "matched-" metric labels.
   *
   * @param matchedName stringified boolean representing whether the handler's target name matched
   *                    the watch request's target name.
   * @param matchedCluster stringified boolean representing whether the handler's target cluster
   *                       matched the watch request's target cluster. If the handler target or
   *                       request target does not have an associated cluster, this field should
   *                       still be "true" if handler and request targets are equal.
   * @param matchedInstanceId stringified boolean representing whether the handler's target instance
   *                          ID matched the watch request's target instance ID. If the handler
   *                          target or request target does not have an associated instance ID, this
   *                          field should still be "true" if handler and request targets are equal.
   */
  private[common] case class TargetMatchedLabels(
      matchedName: String,
      matchedCluster: String,
      matchedInstanceId: String)

  /**
   * Minimum time after a subscriber last watched that it remains in the recent Clerks or Slicelets
   * caches. We expect subscriber entries to be cleared up within twice this interval in the general
   * case, as the contents of caches are lazily maintained on access and periodically cleaned up at
   * this same interval as a backstop.
   */
  private[common] val EXPIRE_AFTER: FiniteDuration = 10.minutes

  private val numSubscribers: Gauge = Gauge
    .build()
    .name("dicer_subscriber_num_subscribers")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "type",
      "version",
      "handlerLocation"
    )
    .help(
      "The number of Clerks/Slicelets connected with the SubscriberHandler in the last few " +
      "minutes, along with their client code version and the location of the handler"
    )
    .register()

  /**
   * A counter tracking the number of watch requests received by the subscriber handler.
   *
   * @note the counter has two additional labels: `matchedName` and `matchedCluster`, to track
   *       whether the target of the handler and the target specified in the client request
   *       matches. This greatly simplifies the logic of computing mismatches in the dashboard /
   *       alerts (otherwise we would need complex query to check inequality on the labels).
   */
  private val watchRequestsReceived: Counter = Counter
    .build()
    .name("dicer_watch_requests_total")
    .help("The number of watch requests received by the SubscriberHandler")
    .labelNames(
      "handlerTargetCluster",
      "handlerTargetName",
      "handlerTargetInstanceId",
      "requestTargetCluster",
      "requestTargetName",
      "requestTargetInstanceId",
      "callerService",
      "type",
      "version",
      "handlerLocation",
      "matchedName",
      "matchedCluster",
      "matchedInstanceId"
    )
    .register(CollectorRegistry.defaultRegistry)

  /**
   * A counter tracking the number of watch requests where we processed an assignment after the
   * timeout in the request. In these cases, we shed load and always return
   * [[SyncAssignmentState.KnownGeneration]] rather than the actual assignment. This likely
   * indicates the SEC is overloaded.
   */
  private[common] val watchRequestsLoadShed: Counter = Counter
    .build()
    .name("dicer_watch_requests_load_shed_total")
    .help(
      "The number of watch requests that took too long to process on the server and were " +
      "load shed"
    )
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId"
    )
    .register(CollectorRegistry.defaultRegistry)

  def getNumSubscribersGauge(
      location: SubscriberHandler.Location,
      target: Target,
      metricsKey: MetricsKey): Gauge.Child = {
    numSubscribers.labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel,
      metricsKey.typeLabel,
      metricsKey.versionLabel,
      location.toString
    )
  }

  def removeNumSubscribersGauge(
      location: SubscriberHandler.Location,
      target: Target,
      metricsKey: MetricsKey): Unit = {
    numSubscribers.remove(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel,
      metricsKey.typeLabel,
      metricsKey.versionLabel,
      location.toString
    )
  }

  /** Increments the number of watch requests received by the handler. */
  def incrementWatchRequestsReceived(
      location: SubscriberHandler.Location,
      handlerTarget: Target,
      requestTarget: Target,
      serviceIdentityOpt: Option[ServiceIdentity],
      metricsKey: MetricsKey): Unit = {
    val callerService: String = serviceIdentityOpt
      .map { serviceIdentity: ServiceIdentity =>
        serviceIdentity.serviceName
      }
      .getOrElse("unknown")

    val matchedLabels: TargetMatchedLabels =
      getWatchRequestTargetMatchedLabels(handlerTarget, requestTarget)

    watchRequestsReceived
      .labels(
        handlerTarget.getTargetClusterLabel,
        handlerTarget.getTargetNameLabel,
        handlerTarget.getTargetInstanceIdLabel,
        requestTarget.getTargetClusterLabel,
        requestTarget.getTargetNameLabel,
        requestTarget.getTargetInstanceIdLabel,
        callerService,
        metricsKey.typeLabel,
        metricsKey.versionLabel,
        location.toString,
        matchedLabels.matchedName,
        matchedLabels.matchedCluster,
        matchedLabels.matchedInstanceId
      )
      .inc()
  }

  /**
   * Returns a [[TargetMatchedLabels]] for the given `handlerTarget` and `requestTarget`. If the
   * names of the targets are the same, the return value has `matchedName = "true"`. Additionally,
   * the return value of this method has the property: `handlerTarget` = `requestTarget` iff for all
   * `member` in [[TargetMatchedLabels]], `member` = "true". More colloquially, this method returns
   * a [[TargetMatchedLabels]] with every member set to "true" if and only if the `handlerTarget`
   * and `requestTarget` are the same.
   *
   * @param handlerTarget target for the handler that received a watch request.
   * @param requestTarget target contained in the received watch request.
   */
  private[common] def getWatchRequestTargetMatchedLabels(
      handlerTarget: Target,
      requestTarget: Target): TargetMatchedLabels = {
    val matchedName: String = if (handlerTarget.name == requestTarget.name) "true" else "false"
    val matchedCluster: String = (handlerTarget, requestTarget) match {
      case (handlerKubernetesTarget: KubernetesTarget, requestKubernetesTarget: KubernetesTarget)
          if handlerKubernetesTarget.clusterOpt == requestKubernetesTarget.clusterOpt =>
        "true"
      case (_: AppTarget, _: AppTarget) if handlerTarget == requestTarget =>
        // If both Targets are AppTargets and they are equal, set the "matchedCluster" label to
        // "true" even though there is no cluster for either target. This is to remain consistent
        // with the property that: `handlerTarget` = `requestTarget` iff `matchedCluster` = "true".
        "true"
      case (_: Target, _: Target) => "false"
    }
    val matchedInstanceId: String = (handlerTarget, requestTarget) match {
      case (handlerAppTarget: AppTarget, requestAppTarget: AppTarget)
          if handlerAppTarget.instanceId == requestAppTarget.instanceId =>
        "true"
      case (_: KubernetesTarget, _: KubernetesTarget) if handlerTarget == requestTarget =>
        // If both Targets are KubernetesTargets and they are equal, set the "matchedInstanceId"
        // label to "true" even though there is no instance ID for either target. This is to remain
        // consistent with the property that: `handlerTarget` = `requestTarget` iff
        // `matchedInstanceId` = "true".
        "true"
      case (_: Target, _: Target) => "false"
    }
    TargetMatchedLabels(matchedName, matchedCluster, matchedInstanceId)
  }
}
