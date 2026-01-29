package com.databricks.dicer.client

import java.net.URI
import java.time.Instant
import java.util.Random

import scala.concurrent.duration._

import io.grpc.Status

import com.databricks.caching.util.{
  CachingErrorCode,
  ExponentialBackoff,
  PrefixLogger,
  Severity,
  StateMachine,
  StateMachineOutput,
  TickerTime,
  TokenBucket
}
import com.databricks.dicer.client.AssignmentSyncStateMachine.{
  DriverAction,
  Event,
  ReadState,
  RemoteKnownGeneration
}
import com.databricks.dicer.common.Assignment.DiffUnused
import com.databricks.dicer.common.{
  Assignment,
  ClientRequest,
  ClientResponse,
  DiffAssignment,
  Generation,
  SyncAssignmentState,
  TargetHelper
}

/**
 * The passive state machine controlling assignment syncing.
 *
 * If the watch RPC response is slower than `config.watchRpcTimeout`, we add hedging - assume the
 * request failed and retry, but still incorporate the response from the old request if we hear back
 * and it is newer. At a high level the behavior is:
 *  - If the response corresponds to the last request sent, initiate a new request immediately.
 *  - Otherwise send a new request if the last request has been inflight for more than
 *    `watchRpcTimeout` (at the RPC layer we have an additional buffer during which we will receive
 *    and process responses).
 *  - On watch RPC errors or timeout, we retry with exponential backoff, except when there is a
 *    specific redirect (in which case we don't backoff since we are likely changing the server).
 *  - Incorporate assignment state from any response as long as it has a higher generation than the
 *    one we already know about.
 *  - Apply the redirect to the next request if we receive one. This happens when the preferred
 *    assigner has changed, and the new assigner may not have a newer assignment yet, so we apply
 *    the redirect even if the response doesn't have a higher generation.
 *
 * @param config The internal configuration parameters used by the Clerk/Slicelet.
 * @param random An rng to add jitter to exponential backoff.
 * @param subscriberDebugName The debug name shown in the log and string representation of the
 *                            Clerk/Slicelet.
 */
class AssignmentSyncStateMachine(
    config: InternalClientConfig,
    random: Random,
    subscriberDebugName: String)
    extends StateMachine[Event, DriverAction] {

  private val logger = PrefixLogger.create(this.getClass, subscriberDebugName)

  /**
   * Whether the state machine has been cancelled. In this state, the state machine is effectively
   * "inert" and no longer emits actions in response to events or advances (e.g. stops requesting
   * that watch requests be sent).
   */
  private var isCancelled: Boolean = false

  /** The current assignment that this class is aware of. */
  private var assignmentOpt: Option[Assignment] = None

  /**
   * Incrementing counter used to correlate responses with requests. Note that this is simply passed
   * around in-memory between SliceLookup and AssignmentSyncStateMachine (see the descriptions of
   * [[Event]]s and [[DriverAction]]s). If we add an `opId` at the protocol level, we should be able
   * to use that directly rather than needing a separate in-memory version here.
   */
  private var lastOpId: Long = 0

  /**
   * The known assignment generation and corresponding address known by the last contacted remote
   * server. Used to determine whether we should include `assignment` in our next request to the
   * remote server. If we are contacting a different address in the next request, we don't try to
   * send a diff assignment as we have no idea what generation the server knows about.
   */
  private var remoteServerKnownGeneration = RemoteKnownGeneration(None, Generation.EMPTY)

  /**
   * Tracks state corresponding to the latest read RPC outstanding from the driver to the remote
   * server. Set to None on initialization and during failure backoff.
   */
  private var latestReadStateOpt: Option[ReadState] = None

  /**
   * Timeout for the Watch RPC. Initialized from the client config and updated every time a response
   * is received from the remote server.
   */
  private var watchRpcTimeout: FiniteDuration = config.watchRpcTimeout

  /**
   * The address to which the next request should be sent. If empty, we fall back to the default
   * behavior of sending to a random server. This field is set based on `ClientResponse.redirect`
   * and set to None if the request fails.
   */
  private var redirectAddressOpt: Option[URI] = None

  /**
   * Scheduler that determines when the next read request can occur, considering both backoff delays
   * and rate limiting.
   */
  private val readScheduler =
    new AssignmentSyncStateMachine.ReadScheduler(
      config.enableRateLimiting,
      random,
      config.minRetryDelay,
      config.maxRetryDelay,
      logger
    )

  override def onAdvance(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[DriverAction] = {
    if (isCancelled) {
      // Immediately return an empty output if this state machine is cancelled.
      return StateMachineOutput.empty
    }
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    onAdvanceInternal(tickerTime, outputBuilder)
    outputBuilder.build()
  }

  override protected def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: Event): StateMachineOutput[DriverAction] = {
    if (isCancelled) {
      // Immediately return an empty output if this state machine is cancelled.
      return StateMachineOutput.empty
    }
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    event match {
      case Event.ReadSuccess(addressOpt: Option[URI], opId: Long, response: ClientResponse) =>
        onReadSuccess(tickerTime, addressOpt, opId, response, outputBuilder)
      case Event.WatchRequest(watchRequest: ClientRequest) =>
        onWatchRequest(watchRequest, outputBuilder)
      case Event.ReadFailure(opId: Long, error: Status) =>
        onReadFailure(tickerTime, opId, error)
      case Event.Cancel => onCancel()
    }
    // Always advance the state machine after handling events.
    onAdvanceInternal(tickerTime, outputBuilder)
    outputBuilder.build()
  }

  /**
   * Called with the successful result of a read call to the remote server. See the specs for
   * [[StateMachineOutput]] for the return information.
   */
  private def onReadSuccess(
      now: TickerTime,
      responseAddressOpt: Option[URI],
      opId: Long,
      response: ClientResponse,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    if (response.syncState.isInstanceOf[SyncAssignmentState.KnownGeneration]) {
      ClientMetrics.incrementEmptyWatchResponses(config.target)
    }
    val syncSourceDebugName: String = responseAddressOpt
      .map { responseAddress: URI =>
        responseAddress.toString
      }
      .getOrElse(config.watchAddress.toString)
    val isNewAssignment: Boolean =
      incorporateSyncState(response.syncState, syncSourceDebugName, outputBuilder)

    // Incorporate the redirect to be applied to the next request.
    redirectAddressOpt = response.redirect.addressOpt

    val responseGeneration: Generation = response.syncState.getKnownGeneration

    val isLatest = isLatestOpId(opId, "read success")
    if (!isLatest) {
      // See the `AssignmentSyncStateMachine` class comment - we want to incorporate new state, but
      // not send a new request immediately.
      if (isNewAssignment) {
        // If the server is persistently slow, we still want to try to incorporate additional state.
        // Our heuristic is that if we received a new assignment, we should update
        // `remoteServerKnownGeneration` so we don't send the same `DiffAssignment` on every
        // request, and also update the watch timeout.
        remoteServerKnownGeneration = RemoteKnownGeneration(responseAddressOpt, responseGeneration)
        watchRpcTimeout = response.suggestedRpcTimeout
      }
    } else {
      // The latest read succeeded, so clear the read state, reset the failure backoff, and update
      // the suggested RPC timeout.
      latestReadStateOpt = None
      readScheduler.resetBackoff()
      watchRpcTimeout = response.suggestedRpcTimeout
      // Also keep track of the latest generation known to the remote server.
      remoteServerKnownGeneration = RemoteKnownGeneration(responseAddressOpt, responseGeneration)
      // Trigger the next read immediately.
      readScheduler.scheduleNextRead(now = now, useBackoff = false)

    }
  }

  /**
   * Return whether the given `opId` is the latest one, adding appropriate logging in cases that it
   * is not. This can happen if the RPC is slow - we hit the `watchRpcTimeout` and sent a new
   * request, then received a response to the old request. See the class comment for more details.
   */
  private def isLatestOpId(opId: Long, logDescription: String): Boolean = {
    latestReadStateOpt match {
      case None =>
        logger.info(
          s"Got $logDescription response for previous opId $opId, this may " +
          s"indicate the server is slow",
          every = 30.seconds
        )
        false
      case Some(latestReadState: ReadState) =>
        if (opId > latestReadState.opId) {
          logger.error(
            s"Got unexpected newer opId on $logDescription, opId: $opId",
            every = 30.seconds
          )
          false
        } else if (opId == latestReadState.opId) {
          true
        } else {
          logger.info(
            s"Got $logDescription response for previous opId $opId, " +
            s"latestReadState: $latestReadState, this may indicate the server is slow",
            every = 30.seconds
          )
          false
        }
    }
  }

  /**
   * Called when a watch request is received. The watch request may include sync state including an
   * assignment that is newer than the one currently known to the machine, in which case it is
   * incorporated.
   */
  private def onWatchRequest(
      request: ClientRequest,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    if (TargetHelper.isFatalTargetMismatch(config.target, request.target)) {
      logger.warn(
        s"Assignment sync node for target ${config.target} received request for target " +
        s"${request.target}. Ignoring."
      )
    } else {
      incorporateSyncState(request.syncAssignmentState, request.subscriberDebugName, outputBuilder)
    }
  }

  /**
   * Incorporates the given sync state. If it contains a new assignment, remembers it, requests that
   * the driver use it by adding [[DriverAction.UseAssignment]] to the given builder and returns
   * true. Otherwise just returns false.
   */
  private def incorporateSyncState(
      syncState: SyncAssignmentState,
      syncSourceDebugName: String,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Boolean = {
    syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        Assignment.fromDiff(this.assignmentOpt, diffAssignment) match {
          case Left(newAssignment: Assignment) =>
            logger.info(
              s"Using new assignment ${newAssignment.generation} from $syncSourceDebugName.",
              every = 10.seconds
            )
            this.assignmentOpt = Some(newAssignment)
            outputBuilder.appendAction(DriverAction.UseAssignment(newAssignment))
            true
          case Right(unused: DiffUnused.DiffUnused) =>
            logger.debug(s"Unable to use diff $unused", every = 10.seconds)
            false
        }
      case _ =>
        // We haven't learned anything new, so there's nothing more to do.
        false
    }
  }

  /**
   * Updates internal state such that the state machine will retry the watch request. Backoff is
   * added as appropriate.
   */
  private def setupRetry(now: TickerTime): Unit = {
    latestReadStateOpt = None
    if (redirectAddressOpt.isDefined) {
      redirectAddressOpt = None
      // We are (likely) changing the server that we talk to, don't backoff in this case.
      readScheduler.scheduleNextRead(now = now, useBackoff = false)
    } else {
      readScheduler.scheduleNextRead(now = now, useBackoff = true)
    }
  }

  /** Handles failure of a watch request. */
  private def onReadFailure(now: TickerTime, opId: Long, error: Status): Unit = {
    if (!isLatestOpId(opId, s"read failure: $error")) {
      // This is a failure response to an old request. Ignore it.
    } else {
      logger.info(s"Watch assignment failed: $error", every = 30.seconds)
      setupRetry(now)
    }
  }

  /** Cancels this state machine. */
  private def onCancel(): StateMachineOutput[DriverAction] = {
    isCancelled = true
    StateMachineOutput.empty
  }

  /**
   * Determines if any actions are required based on the passage of time, adding those actions to
   * the supplied output builder.
   *
   * Responsible for ensuring that there is always an outstanding watch request, except when backing
   * off due to previous errors.
   */
  private def onAdvanceInternal(
      now: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    for (latestReadState <- latestReadStateOpt) {
      // There is an outstanding request.
      if (now >= latestReadState.deadline) {
        // The outstanding request is at or past its deadline. Clear `latestReadStateOpt` by calling
        // `setupRetry` to ensure that a new watch request is initiated or scheduled below.
        logger.warn("Watch assignment hit internal deadline, will retry", every = 30.seconds)
        setupRetry(now)
      }
    }
    if (latestReadStateOpt.isDefined) {
      // There is an outstanding read already, make sure the state machine is advanced when it's at
      // its deadline. (Note that `latestReadStateOpt` would have been cleared above were we already
      // at or past the deadline.)
      val deadline: TickerTime = latestReadStateOpt.get.deadline
      outputBuilder.ensureAdvanceBy(deadline)
    } else if (readScheduler.tryAcquireReadPermission(now)) {
      // No read is outstanding, we've reached the scheduled read time, and a token is acquired.
      // We're due to send a watch request.
      lastOpId += 1
      val opId: Long = lastOpId
      val deadline: TickerTime = now + watchRpcTimeout
      latestReadStateOpt = Some(ReadState(opId, deadline))
      val syncState: SyncAssignmentState = this.assignmentOpt match {
        case Some(assignment: Assignment)
            if assignment.generation > this.remoteServerKnownGeneration.generation &&
            redirectAddressOpt == this.remoteServerKnownGeneration.addressOpt =>
          // Include an assignment diff if the remote server is lagging, and we are sending this
          // request to the same server. If `redirectAddressOpt` and
          // `remoteServerKnownGeneration.addressOpt` are both None, we are sending the request to a
          // random server, but there is some stickiness because we maintain an open stub, so we
          // still want to send an assignment diff.
          logger.info(
            s"Sending assignment with generation ${assignment.generation} > " +
            s"${this.remoteServerKnownGeneration} to remote server"
          )
          val diff: DiffAssignment =
            assignment.toDiff(this.remoteServerKnownGeneration.generation)
          SyncAssignmentState.KnownAssignment(diff)
        case _ =>
          val knownGeneration: Generation = this.assignmentOpt match {
            case Some(assignment: Assignment) => assignment.generation
            case None => Generation.EMPTY
          }
          SyncAssignmentState.KnownGeneration(knownGeneration)
      }
      outputBuilder.appendAction(
        DriverAction.SendRequest(redirectAddressOpt, opId, syncState, watchRpcTimeout)
      )
      outputBuilder.ensureAdvanceBy(deadline)
    } else {
      // No request is outstanding. Too early to schedule. Call back at the scheduled read time.
      outputBuilder.ensureAdvanceBy(readScheduler.getScheduledTime)
    }
    outputBuilder.build()
  }

  object forTest {

    /**
     * Returns whether the syncer has received some errors and hence is in exponential backoff mode
     * w.r.t. communicating with the remote server.
     */
    private[client] def isInBackoff: Boolean = readScheduler.forTest.isInBackoff
  }
}

/**
 * Syncs assignments between this client and a remote server. Implemented as a passive
 * [[StateMachine]] that is driven by the active [[SliceLookup]]. See [[StateMachine]]
 * documentation for additional background on the driver/state machine pattern.
 */
object AssignmentSyncStateMachine {

  /** Input events to the syncer state machine. */
  sealed trait Event
  object Event {

    /**
     * Signals to the syncer that a read with the given `addressOpt` and `opId` passed to
     * [[DriverAction.SendRequest]] has successfully completed with the given response.
     */
    case class ReadSuccess(addressOpt: Option[URI], opId: Long, response: ClientResponse)
        extends Event

    /**
     * Signals to the syncer that a read with the given `opId` passed to
     * [[DriverAction.SendRequest]] has failed with the given error.
     */
    case class ReadFailure(opId: Long, error: Status) extends Event

    /** Informs the generator that a watch request was received from a client. */
    case class WatchRequest(request: ClientRequest) extends Event

    /** Cancels this state machine. Any subsequently received events or advances will be ignored. */
    case object Cancel extends Event
  }

  /**
   * Actions that the sync state machine outputs to the driver in response to incoming signals or
   * the passage of time.
   */
  sealed trait DriverAction
  object DriverAction {

    /** Informs the driver of an assignment that should be used locally. */
    case class UseAssignment(assignment: Assignment) extends DriverAction

    /**
     * REQUIRES: `syncState.diffAssignment.generation` is less than or equal to the sync state's
     * known assignment generation (if any).
     *
     * Asks the driver to sync with the remote server, using the given sync state and optionally to
     * the given address. If `addressOpt` is defined, it indicates that the request should be sent
     * to that address. If it is empty, we should fall back to the default behavior, which sends it
     * to a random server.
     *
     * The supplied `opId` (and `addressOpt` for [[Event.ReadSuccess]]) should be passed back in the
     * [[Event.ReadSuccess]] or [[Event.ReadFailure]] event.
     *
     * The supplied `syncState` indicates the generation of the assignment currently known to the
     * syncer (so that the remote server can return something newer) and optionally the contents of
     * that assignment (if this syncer has reason to believe the remote server may not know about
     * it).
     *
     * If the sync state includes a known assignment, only Slices that have changed since
     * `diffGeneration` will be included in the serialized representation of the sync message. This
     * generation is the generation the sync state machine believe the remote server knows about
     * (see `remoteServerKnownGeneration`).
     *
     * The `watchRpcTimeout` should be used as the timeout for the watch RPC.
     */
    case class SendRequest(
        addressOpt: Option[URI],
        opId: Long,
        syncState: SyncAssignmentState,
        watchRpcTimeout: FiniteDuration)
        extends DriverAction
  }

  /**
   * Manages the scheduling of watch requests, taking into account both backoff delays and rate
   * limiting via the token bucket.
   */
  private class ReadScheduler(
      enableRateLimiting: Boolean,
      random: Random,
      minRetryDelay: FiniteDuration,
      maxRetryDelay: FiniteDuration,
      logger: PrefixLogger) {

    /**
     * Token bucket for rate limiting watch requests. When rate limiting is enabled, the bucket is
     * configured with a specific rate and capacity. When disabled, the bucket is configured with an
     * unlimited rate (Long.MaxValue) so that `tryAcquire` always succeeds.
     *
     * Our intent in rate-limiting requests is to protect the server in case of unexpected issues
     * (<internal bug>) but also the client; if we were to otherwise ensure an outstanding hanging get
     * without any rate limiting, a malicious server could try to induce high load on a client by
     * completing requests immediately.
     */
    private val tokenBucket: TokenBucket = if (enableRateLimiting) {
      TokenBucket.create(
        // We allow a burst of up to 2 requests because when the PA changes, a successful response
        // containing a redirect address can trigger two requests within the same second. The bucket
        // then refills at a rate of 1 token per second. Since watch requests are typically sent
        // every 2.5 seconds, this allows the bucket to regain 2 tokens in that interval, ensuring
        // it can always handle an immediate redirect response. In practice, PA changes rarely occur
        // more than once per second, so this rate should be sufficient.
        capacityInSecondsOfRate = 2,
        rate = 1,
        // `initTime` is used to set the initial refill time for the token bucket. It's safe to set
        // it to TickerTime.MIN here because we always perform a refill before calling `tryAcquire`.
        initTime = TickerTime.MIN
      )
    } else {
      // When rate limiting is disabled, create a token bucket with an unlimited rate so that
      // `tryAcquire` always succeeds.
      TokenBucket.create(
        capacityInSecondsOfRate = TokenBucket.MAX_CAPACITY_IN_SECONDS_OF_RATE,
        rate = TokenBucket.MAX_RATE,
        initTime = TickerTime.MIN
      )
    }

    /** Exponential backoff for retrying watch requests on failures. */
    private val backoff = new ExponentialBackoff(random, minRetryDelay, maxRetryDelay)

    /** The next time a read/watch should be executed by the driver to the remote server. */
    private var nextReadScheduleTime: TickerTime = TickerTime.MIN

    /** Gets the scheduled time for the next read. */
    def getScheduledTime: TickerTime = nextReadScheduleTime

    /**
     * Checks if a read is allowed and acquires a token if it is. Returns `false` if the caller is
     * still in the backoff period or if no token is available in the token bucket. (i.e., returns
     * false if `now` < [[getScheduledTime]]). Otherwise, refills the token bucket and acquires a
     * token, allowing the read to proceed.
     */
    def tryAcquireReadPermission(now: TickerTime): Boolean = {
      if (now < nextReadScheduleTime) {
        // Either still in backoff period or token bucket not yet refilled — read is not allowed.
        false
      } else {
        // Otherwise, we refill the bucket and acquire one token. Since `scheduleNextRead`
        // guarantees that the token bucket has at least one token when `nextReadScheduleTime` is
        // reached, `tryAcquire` should always succeed.
        tokenBucket.refill(now)
        if (!tokenBucket.tryAcquire(count = 1)) {
          // $COVERAGE-OFF$: This alert is used to indicate potential future bugs in
          // `scheduleNextRead`, and it cannot be triggered by the current code in tests.
          logger.alert(
            Severity.DEGRADED,
            CachingErrorCode.DICER_CLIENT_READ_SCHEDULER_ERROR,
            s"Token not available at $now (read scheduled time: $nextReadScheduleTime). " +
            s"This indicates a bug in ReadScheduler.scheduleNextRead.",
            every = 30.seconds
          )
          // $COVERAGE-ON$
        }
        // Even if `tryAcquire` fails (probably due to a bug), we still allow sending watch requests
        // to ensure dicer clients don’t fall behind.
        true
      }
    }

    /**
     * Schedules the next read request, taking into account both backoff delays (if requested) and
     * token bucket rate limiting. This method ensures that `getScheduledTime` is always set to a
     * time when a token will be available.
     *
     * @param now The current time, used as the base for computing the desired schedule time and for
     *            refilling the token bucket.
     * @param useBackoff Whether to apply exponential backoff delay, and increase the backoff for
     *                   next time. Use [[resetBackoff]] to reset the backoff.
     */
    def scheduleNextRead(now: TickerTime, useBackoff: Boolean): Unit = {
      val backoffDelay: FiniteDuration = if (useBackoff) {
        backoff.nextDelay()
      } else {
        Duration.Zero
      }
      val desiredTime: TickerTime = now + backoffDelay
      val tokenAvailableTime: TickerTime = tokenBucket.timeWhenRefilled(desired = 1)
      // Schedule the next read no earlier than when a token is available.
      nextReadScheduleTime = if (desiredTime >= tokenAvailableTime) {
        desiredTime
      } else {
        tokenAvailableTime
      }
    }

    /** Resets the exponential backoff state to its initial values. */
    def resetBackoff(): Unit = {
      backoff.reset()
    }

    object forTest {

      /** Returns whether the scheduler is currently in exponential backoff mode. */
      def isInBackoff: Boolean = backoff.forTest.isInBackoff
    }
  }

  /**
   * Indicates the assignment generation known at the given address (with the same semantics for
   * `addressOpt` as in [[DriverAction.SendRequest]]).
   */
  case class RemoteKnownGeneration(addressOpt: Option[URI], generation: Generation)

  /**
   * Tracks metadata corresponding to the outstanding read. See the [[AssignmentSyncStateMachine]]
   * class comment for how this affects behavior.
   */
  case class ReadState(opId: Long, deadline: TickerTime)
}
