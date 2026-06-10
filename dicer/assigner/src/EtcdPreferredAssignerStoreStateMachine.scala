package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.PreferredAssignerSpecP
import com.databricks.caching.util.{
  CachingErrorCode,
  ExponentialBackoff,
  PrefixLogger,
  Severity,
  StateMachine,
  StateMachineOutput,
  TickerTime
}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  Config,
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.common.{EtcdClientHelper, Generation, Incarnation}
import com.databricks.caching.util.EtcdClient.{Version, WatchEvent, WriteResponse}
import com.databricks.caching.util.EtcdClient.WriteResponse.KeyState
import io.grpc.{Status, StatusException}

import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}
import com.google.protobuf.ByteString
import com.databricks.caching.util.Bytes
import com.databricks.caching.util.UnixTimeVersion

/**
 * State machine for the preferred assigner store, which caches the latest known preferred assigner
 * learned from etcd or from trusted sources.
 *
 * The state machine is exercised only via [[onEvent]] and [[onAdvance]].
 *
 * Invariant: At most one watch is in progress at any given time, since we only need to monitor a
 * single entry storing the preferred assigner.
 *
 * @param storeIncarnation Store incarnation of the PreferredAssignerStore.
 * @param random           Random number generator for exponential backoff.
 * @param config           [[EtcdPreferredAssignerStoreStateMachine]] configuration.
 */
@NotThreadSafe
class EtcdPreferredAssignerStoreStateMachine(
    storeIncarnation: Incarnation,
    random: Random,
    config: Config
) extends StateMachine[
      EtcdPreferredAssignerStoreStateMachine.Event,
      EtcdPreferredAssignerStoreStateMachine.DriverAction] {
  import EtcdPreferredAssignerStoreStateMachine._

  private val logger = PrefixLogger.create(getClass, "dicer-preferred-assigner")

  /**
   * The next time an Etcd watch action should be added to the driver. [[TickerTime.MAX]] indicates
   * the [[DriverAction.PerformEtcdWatch]] has been sent to the driver so the watch is waiting to
   * be established.
   */
  private var nextWatchTime = TickerTime.MIN

  /** The locally cached, latest known preferred assigner. */
  private var latestPreferredAssigner: PreferredAssignerValue =
    PreferredAssignerValue.NoAssigner(Generation.EMPTY)

  /**
   * Exponential backoff for watch retries. If a watch on Etcd fails with Status, we request the
   * driver to establish another watch with backoff.
   *
   * @note The watch failure isn't include the test-only shutdown. In production, the preferred
   *       assigner store will always either be watching the Etcd or waiting to re-establish the
   *       watch.
   */
  private val watchBackoff =
    new ExponentialBackoff(random, config.minWatchRetryDelay, config.maxWatchRetryDelay)

  /**
   * An exclusive lower bound on the Generation to use for the initial preferred assigner.
   * Updated when an initial preferred assigner write fails due to use of an insufficiently high
   * version (in which case [[com.databricks.dicer.util.EtcdClient]] informs us of the next safe
   * version to use).
   *
   * Since the store may only issue writes for its store incarnation, this is initialized to the
   * minimum value within this `storeIncarnation`, and writes maintain the invariant that this
   * Generation always increases and stays within the store incarnation.
   */
  private var initialPreferredAssignerGenerationLowerBoundExclusive: Generation =
    Generation(storeIncarnation, number = UnixTimeVersion.MIN)

  override def onAdvance(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[DriverAction] = {
    onAdvanceInternal(tickerTime, new StateMachineOutput.Builder[DriverAction]())
  }

  override def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: Event): StateMachineOutput[DriverAction] = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]

    event match {
      case Event.EtcdWatchEvent(event: WatchEvent) =>
        onEtcdWatchEvent(event, outputBuilder)
      case Event.EtcdWatchFailure(status: Status) =>
        onEtcdWatchFailure(tickerTime, status)
      case Event.WriteRequest(
          promise: Promise[WriteResult],
          preferredAssignerProposal: PreferredAssignerProposal
          ) =>
        onWriteRequest(instant, promise, preferredAssignerProposal, outputBuilder)
      case Event.EtcdWriteResponse(
          promise: Promise[WriteResult],
          proposedPredecessorVersionOpt: Option[Version],
          proposedPreferredAssigner: PreferredAssignerValue,
          response: Try[WriteResponse]
          ) =>
        onEtcdWriteResponse(
          promise,
          proposedPredecessorVersionOpt,
          proposedPreferredAssigner,
          response,
          outputBuilder
        )
      case Event.PreferredAssignerInformed(preferredAssigner: PreferredAssignerValue) =>
        updateLatestPreferredAssigner(preferredAssigner, outputBuilder)
    }

    onAdvanceInternal(tickerTime, outputBuilder)
  }

  /** Requests a watch, if time is greater than or equal to the next watch time. */
  private def onAdvanceInternal(
      tickerTime: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Output = {
    // TODO(<internal bug>): make this more robust by applying a timeout while waiting for a result
    //  from the driver. This also requires the write and watch actions to be idempotent.
    if (tickerTime >= nextWatchTime) {
      logger.info(s"Requesting watch")
      nextWatchTime = TickerTime.MAX
      outputBuilder.appendAction(DriverAction.PerformEtcdWatch(WATCH_DURATION))
    } else {
      outputBuilder.ensureAdvanceBy(nextWatchTime)
    }
    outputBuilder.build()
  }

  /** Handles an Etcd watch event. */
  private def onEtcdWatchEvent(event: WatchEvent, outputBuilder: OutputBuilder): Unit = {
    event match {
      case WatchEvent.VersionedValue(version: Version, value: ByteString) =>
        val generation: Generation =
          EtcdClientHelper.createGenerationFromVersion(version)
        try {
          val preferredAssigner: PreferredAssignerValue =
            PreferredAssignerValue.fromProto(
              PreferredAssignerSpecP.parseFrom(value.toByteArray),
              generation
            )
          logger.info(s"Watch event: Observed preferred assigner $preferredAssigner")
          updateLatestPreferredAssigner(preferredAssigner, outputBuilder)
        } catch {
          case NonFatal(e: Throwable) =>
            // There is a malformed value in Etcd. This is a CRITICAL error and pager must go off.
            // The preferred assigner store could cancel the watch and re-establish it with a
            // backoff, but it's highly likely that the malformed value would persist and the
            // subsequent watch would still observe the same value.
            // We don't request the driver to write the cached preferred assigner to Etcd, as there
            // is likely a serious bug when writing the preferred assigner to Etcd.
            // If we exclude the possibility of someone maliciously writing a malformed value,
            // it's also possible that the [[PreferredAssignerSpecP]] has evolved into a format
            // that is not compatible with previous versions. In all these cases, the state machine
            // would be stuck in a loop of attempting to write the value to Etcd.
            // Instead, we mark the store as corrupted and log the error.
            logger.alert(
              Severity.CRITICAL,
              CachingErrorCode.PREFERRED_ASSIGNER_STORE_CORRUPTED,
              s"Failed to parse preferred assigner value ${Bytes.toString(value)}, " +
              s" version $version: $e. Retrying watch."
            )
            outputBuilder.appendAction(
              DriverAction.CancelEtcdWatch(
                StoreErrorReason(Status.DATA_LOSS, StoreErrorCode.WATCH_VALUE_PARSE_ERROR)
              )
            )
        }

      case WatchEvent.Causal =>
        // As we don't do incremental writes and the preferred assigner is updated every time we
        // observe a version and value, there is no need to do anything other than resetting the
        // backoff when we receive a causal signal.
        logger.debug(s"Watch event: Observed causal signal. Resetting watch backoff.")
        watchBackoff.reset()
    }
  }

  /** Handles an Etcd watch failure event. */
  private def onEtcdWatchFailure(now: TickerTime, status: Status): Unit = {
    // Update the backoff and log the error.
    nextWatchTime = now + watchBackoff.nextDelay()
    logger.warn(s"Retrying because watch failed with error: $status")
  }

  /**
   * REQUIRES: `preferredAssignerProposal.predecessorGenerationOpt` is empty or belongs to the
   * same incarnation as `storeIncarnation`.
   *
   * Writes the `preferredAssignerProposal` to etcd conditioned on the current value in the store
   * being `predecessor`.
   *
   * @note If the predecessor generation is empty but a preferred assigner value exists in the
   *       durable store, the state machine will internally retry the write: It will specify the
   *       generation as the latest value in the durable store and attempt to overwrite it.
   *       See [[onEtcdWriteResponse]] for more details.
   */
  private def onWriteRequest(
      instant: Instant,
      promise: Promise[WriteResult],
      preferredAssignerProposal: PreferredAssignerProposal,
      outputBuilder: OutputBuilder): Unit = {
    require(
      preferredAssignerProposal.predecessorGenerationOpt.isEmpty ||
      preferredAssignerProposal.predecessorGenerationOpt.get.incarnation == storeIncarnation,
      "Predecessor generation must empty or belong to the same store incarnation."
    )
    val predecessorGenerationOpt: Option[Generation] =
      preferredAssignerProposal.predecessorGenerationOpt

    // Choose a generation for the preferred assigner. If this is the initial preferred assigner,
    // choose a generation equal to the lower bound (which is updated whenever writes fail due to
    // use of an insufficiently high generation).
    // TODO(<internal bug>): Support recovering from bad clock readings that cause generation numbers
    // to permanently become too far ahead of the current time (for a given store incarnation).
    val lowerBoundGenerationExclusive: Generation = predecessorGenerationOpt match {
      case Some(predecessorGeneration: Generation) => predecessorGeneration
      case None => initialPreferredAssignerGenerationLowerBoundExclusive
    }
    val predecessorVersionOpt: Option[Version] = predecessorGenerationOpt match {
      case Some(predecessorGeneration: Generation) =>
        Some(EtcdClientHelper.getVersionFromNonLooseGeneration(predecessorGeneration))
      case None => None
    }

    val generation: Generation = Generation.createForCurrentTime(
      storeIncarnation,
      instant,
      lowerBoundGenerationExclusive
    )
    val proposedPreferredAssigner: PreferredAssignerValue =
      preferredAssignerProposal.newPreferredAssignerInfoOpt match {
        case Some(proposal: AssignerInfo) =>
          PreferredAssignerValue.SomeAssigner(proposal, generation)
        case None =>
          PreferredAssignerValue.NoAssigner(generation)
      }

    outputBuilder.appendAction(
      DriverAction
        .WritePreferredAssignerToEtcd(promise, predecessorVersionOpt, proposedPreferredAssigner)
    )
  }

  /** Handles the write response from Etcd. */
  private def onEtcdWriteResponse(
      promise: Promise[WriteResult],
      proposedPredecessorVersionOpt: Option[Version],
      proposedPreferredAssigner: PreferredAssignerValue,
      response: Try[WriteResponse],
      outputBuilder: OutputBuilder): Unit = {
    val driverAction: DriverAction = response match {
      case Failure(throwable: Throwable) =>
        // Ask the driver to complete write operation with failure.
        logger.warn(s"Write for preferred assigner $proposedPreferredAssigner failed: $throwable")
        DriverAction.CompleteWrite(promise, Failure(throwable))

      case Success(WriteResponse.OccFailure(keyState: KeyState)) =>
        keyState match {
          case KeyState.Present(version: Version, newKeyVersionLowerBoundExclusive: Version) =>
            updateInitialPreferredAssignerGenerationLowerBound(newKeyVersionLowerBoundExclusive)
            val actualGeneration: Generation =
              EtcdClientHelper.createGenerationFromVersion(version)
            val assignerKnownGeneration: Generation = proposedPredecessorVersionOpt match {
              case Some(version) => EtcdClientHelper.createGenerationFromVersion(version)
              case None => Generation.EMPTY
            }
            expectStoreAheadOfAssignerKnownGeneration(actualGeneration, assignerKnownGeneration)
            if (proposedPredecessorVersionOpt.isEmpty &&
              actualGeneration.incarnation < storeIncarnation) {
              // Writing with no predecessor and the actual generation is from an earlier store
              // incarnation. We can retry internally the write with the correct predecessor to
              // overwrite the existing lower incarnation preferred assigner.
              //
              // If this is the very first write of the new store incarnation, this retry will
              // fail because the high watermark is from the previous incarnation and needed to be
              // bumped up, but a subsequent write will succeed.
              //
              // This could also fail the same way if a concurrent write with an old incarnation
              // succeeds before this retry, but we should win quickly here since we're retrying
              // frequently, which will fence off future writes from that lower incarnation.
              DriverAction.WritePreferredAssignerToEtcd(
                promise,
                Some(version),
                proposedPreferredAssigner
              )
            } else {
              logger.info(
                s"Write for preferred assigner $proposedPreferredAssigner failed OCC checks. " +
                s"Current preferred assigner in the store has generation $actualGeneration."
              )
              DriverAction.CompleteWrite(promise, Success(WriteResult.OccFailure(actualGeneration)))
            }

          case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
            // The preferred assigner is absent from the store. The write may have failed due to
            // either an incorrect expectation about the presence of the preferred assigner in the
            // store, or when the write was indeed for an initial preferred assigner, use of an
            // insufficiently high version to guarantee monotonicity. In either case,
            // `newKeyVersionLowerBoundExclusive` is provided by [[EtcdClient]] to help us in
            // choosing a safe incarnation to use for subsequent writes.
            updateInitialPreferredAssignerGenerationLowerBound(newKeyVersionLowerBoundExclusive)
            val assignerKnownGeneration: Generation = proposedPredecessorVersionOpt match {
              case Some(version) => EtcdClientHelper.createGenerationFromVersion(version)
              case None => Generation.EMPTY
            }
            expectStoreAheadOfAssignerKnownGeneration(Generation.EMPTY, assignerKnownGeneration)
            if (proposedPredecessorVersionOpt.isEmpty) {
              // The write was for an initial preferred assigner, and there is indeed no preferred
              // assigner key in the store. The write must have failed due to an insufficiently
              // high key version.
              DriverAction.CompleteWrite(
                promise,
                Failure(
                  new StatusException(
                    Status.INTERNAL.withDescription(
                      s"Write for the initial preferred assigner $proposedPreferredAssigner " +
                      s"failed due to use of an insufficiently high version to guarantee " +
                      s"monotonicity. Initial PA writes must currently have a generation of " +
                      s"at least $initialPreferredAssignerGenerationLowerBoundExclusive."
                    )
                  )
                )
              )
            } else {
              // This is unexpected; we've supplied a non-empty predecessor but found nothing in
              // the store. The call to `expectStoreAheadOfAssignerKnownGeneration` will have
              // fired an alert.
              DriverAction.CompleteWrite(promise, Success(WriteResult.OccFailure(Generation.EMPTY)))
            }
        }

      case Success(WriteResponse.Committed(newKeyVersionLowerBoundExclusiveOpt: Option[Version])) =>
        // Ask the driver to complete write operation with committed preferred assigner.
        logger.info(s"Write for preferred assigner $proposedPreferredAssigner committed")
        for (newKeyVersionLowerBoundExclusive: Version <- newKeyVersionLowerBoundExclusiveOpt) {
          updateInitialPreferredAssignerGenerationLowerBound(newKeyVersionLowerBoundExclusive)
        }
        updateLatestPreferredAssigner(proposedPreferredAssigner, outputBuilder)
        DriverAction.CompleteWrite(
          promise,
          Success(WriteResult.Committed(proposedPreferredAssigner))
        )
    }

    outputBuilder.appendAction(driverAction)
  }

  /**
   * Updates cached knowledge about the lower generation bound for a new preferred Assigner record.
   */
  private def updateInitialPreferredAssignerGenerationLowerBound(
      newInitialAssignmentLowerBoundExclusive: Version): Unit = {
    val newLowerBoundExclusive: Generation = EtcdClientHelper
      .createGenerationFromVersion(newInitialAssignmentLowerBoundExclusive)
    if (newLowerBoundExclusive.incarnation == storeIncarnation) {
      initialPreferredAssignerGenerationLowerBoundExclusive = Ordering
        .ordered[Generation]
        .max(initialPreferredAssignerGenerationLowerBoundExclusive, newLowerBoundExclusive)
    }
  }

  /**
   * Checks that `storeGeneration` is at least `assignerKnownGeneration`, logging at alert
   * level otherwise.
   */
  private def expectStoreAheadOfAssignerKnownGeneration(
      storeGeneration: Generation,
      assignerKnownGeneration: Generation): Unit = {
    if (storeGeneration < assignerKnownGeneration) {
      logger.alert(
        Severity.CRITICAL,
        CachingErrorCode.ASSIGNER_KNOWS_LATER_PREFERRED_ASSIGNER_VALUE_THAN_DURABLE_STORE,
        s"Assigner knows of a later preferred assigner ($assignerKnownGeneration) than the " +
        s"latest preferred assigner in etcd ($storeGeneration). This indicates that either the " +
        s"preferred assigner has been lost in etcd, or a preferred assigner was externalized by " +
        s"EtcdPreferredAssignerStore before it was made durable."
      )
    }
  }

  /**
   * Updates the latest known preferred assigner if the supplied `preferredAssigner` has a newer
   * generation than the latest known preferred assigner.
   *
   * @note This method should be called only on preferred assigners that are known to be durable
   *       in etcd, i.e., in any of the following three cases:
   *       1. The preferred assigner is received through the etcd watch.
   *       2. The preferred assigner is committed in etcd.
   *       3. The preferred assigner is informed by a trusted authority.
   */
  private def updateLatestPreferredAssigner(
      preferredAssigner: PreferredAssignerValue,
      outputBuilder: OutputBuilder): Unit = {
    if (preferredAssigner.generation.compare(latestPreferredAssigner.generation) > 0) {
      logger.info(
        s"Updating latest preferred assigner from $latestPreferredAssigner to $preferredAssigner"
      )
      latestPreferredAssigner = preferredAssigner
      outputBuilder.appendAction(DriverAction.ApplyPreferredAssigner(preferredAssigner))
    }
  }

  private[assigner] object forTest {
    def getLatestPreferredAssigner: PreferredAssignerValue = latestPreferredAssigner
  }
}

private[assigner] object EtcdPreferredAssignerStoreStateMachine {
  type Output = StateMachineOutput[DriverAction]
  type OutputBuilder = StateMachineOutput.Builder[DriverAction]

  /** The default configuration for [[EtcdPreferredAssignerStore]]. */
  val DEFAULT_CONFIG: Config = Config(5.seconds, 30.seconds)

  /**
   * The duration of each watch of [[KEY]] in etcd.
   *
   * The preferred assigner store limits the watch duration to defend against the possible case
   * where the etcd client watch wedges. The store state machine automatically re-establishes the
   * watch after it is cancelled and the cancellation case is well covered by automated tests.
   */
  private val WATCH_DURATION: FiniteDuration = 5.minutes

  /**
   * Actions that the state machine outputs to the driver in response to incoming signals
   * or the passage of time.
   */
  sealed trait DriverAction

  object DriverAction {

    /** Asks the driver to update the latest preferred assigner to be the given one. */
    case class ApplyPreferredAssigner(preferredAssigner: PreferredAssignerValue)
        extends DriverAction

    /** Asks the driver to write the `preferredAssigner` with OCC checks to Etcd. */
    case class WritePreferredAssignerToEtcd(
        promise: Promise[WriteResult],
        predecessorVersionOpt: Option[Version],
        preferredAssigner: PreferredAssignerValue
    ) extends DriverAction

    /** Asks the driver to complete the write operation based on Etcd write response. */
    case class CompleteWrite(promise: Promise[WriteResult], response: Try[WriteResult])
        extends DriverAction

    /** Asks the driver to perform a watch on Etcd. */
    case class PerformEtcdWatch(watchDuration: Duration) extends DriverAction

    /** Asks the driver to cancel the watch on Etcd. */
    case class CancelEtcdWatch(reason: StoreErrorReason) extends DriverAction
  }

  /** Input events to the [[EtcdPreferredAssignerStoreStateMachine]]. */
  sealed trait Event

  object Event {

    /** Informs the state machine with a [[WatchEvent]] reported from the etcd client. */
    case class EtcdWatchEvent(event: WatchEvent) extends Event

    /** Informs the state machine about failure from Etcd watch. */
    case class EtcdWatchFailure(status: Status) extends Event

    /** Informs the state machine about a write request. */
    case class WriteRequest(
        promise: Promise[WriteResult],
        preferredAssignerProposal: PreferredAssignerProposal)
        extends Event

    /** Informs the state machine about a write response from the etcd client. */
    case class EtcdWriteResponse(
        promise: Promise[WriteResult],
        predecessorVersionOpt: Option[Version],
        proposedPreferredAssigner: PreferredAssignerValue,
        response: Try[WriteResponse])
        extends Event

    /** Informs the state machine about a preferred assigner from trusted authority. */
    case class PreferredAssignerInformed(preferredAssigner: PreferredAssignerValue) extends Event
  }

  /**
   * Errors encountered when interacting with etcd. Each of these indicates a bug in
   * [[EtcdPreferredAssignerStore]] or data corruption in Etcd and are considered a
   * [[Severity.CRITICAL]] error. Use with caution.
   */
  sealed trait StoreErrorCode

  object StoreErrorCode {

    /**
     * The watch event received from Etcd client is not parse-able as a [[PreferredAssignerValue]].
     */
    case object WATCH_VALUE_PARSE_ERROR extends StoreErrorCode
  }

  /** Encapsulates reason for store error with [[Status]]. */
  case class StoreErrorReason(status: Status, code: StoreErrorCode)

  // Cannot just be named `forTest` due to conflict with the instance-level `forTest`.
  private[assigner] object companionForTest {
    val WATCH_DURATION: FiniteDuration = EtcdPreferredAssignerStoreStateMachine.WATCH_DURATION
  }
}
