package com.databricks.dicer.assigner

import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe
import scala.concurrent.duration._
import io.grpc.Status
import com.databricks.caching.util.{PrefixLogger, StateMachine, StateMachineOutput, TickerTime}
import com.databricks.caching.util.StatusUtils
import com.databricks.dicer.assigner.EtcdPreferredAssignerStateMachine.Event.{
  ExternalPickReceived,
  HeartbeatRequestReceived,
  HeartbeatSuccess,
  PreferredAssignerReceived,
  TerminationNoticeReceived,
  WriteResultReceived
}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStateMachine.{
  DriverAction,
  Event,
  HeartbeatState,
  RunState
}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.assigner.PreferredAssignerMetrics.{MonitoredAssignerRole, ValueSource}
import com.databricks.dicer.common.{Generation, Incarnation}

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

/**
 * REQUIRES: `storeIncarnation` is non-loose, see [[Incarnation.isNonLoose]].
 *
 * The preferred assigner state machine, used to help make assignment generation highly available.
 * For full details, see <internal link>.
 *
 * To improve on the availability of our earlier single pod assigner, the preferred assigner design
 * uses a set of assigner replicas, each of which can each potentially generate assignments.
 *
 * For operational simplicity, the design attempts to make it very likely that only a single
 * assigner, the preferred assigner, is generating assignments at any given time. This is
 * accomplished by having an etcd store which acts as the source of truth about the identity of the
 * preferred assigner, and by propagating knowledge of the preferred assigner via store watchers and
 * heartbeats between assigners.
 *
 * Only an assigner which believes itself to be the preferred assigner (typically exactly one) will
 * actually generate and write assignments. If another assigner transiently and incorrectly believes
 * it is the preferred assigner due to network partitions or other issues, no serious harm results
 * because of the OCC checks that prevent interleaved writes to the assignment store.
 *
 * The other assigners act as standby assigners and monitor the health of the preferred assigner via
 * heartbeats. If a standby finds that the preferred assigner is from a past store incarnation, or
 * that is unhealthy, or if there is no preferred assigner, it will attempt to become the preferred
 * assigner by writing its identity to the preferred assigner store with an OCC check.
 *
 * This state machine update its knowledge of the preferred assigner via events from by the driver,
 * which watches the preferred assigner store and receives heartbeat requests from other assigners.
 * Heartbeat requests include the requester's latest knowledge of the preferred assigner. The driver
 * likewise responds to heartbeats with its latest knowledge of the preferred assigner. In this way
 * news typically spreads quickly regarding changes to the preferred assigner.
 *
 * The state machine tracks the current preferred assigner and determines when some other assigner
 * is preferred and it should act as standby. As standby, it sends periodic heartbeats to that
 * preferred assigner to monitor its health. A heartbeat is considered to have failed if its
 * response does not arrive before the next heartbeat is due to be sent. If too many heartbeats fail
 * consecutively, the state machine attempts to make its assigner preferred by writing its identity
 * to the store. (Note that an assigner can therefore lose its preferred status by responding too
 * slowly or not at all.) The state machine retries the write as needed if the current preferred
 * assigner remains unhealthy and this or some other assigner does not become preferred.
 *
 * If there is no preferred assigner, the state machine likewise writes its identity to the store to
 * become preferred, retrying as needed.
 *
 * The driver may also deliver a pending termination event to indicate that the assigner is
 * scheduled to be shutdown. In this case, and this assigner is acting as preferred, the state
 * machine will attempt to abdicate by writing "None" to the preferred assigner store so that some
 * other assigner will take over.
 *
 * @param selfAssignerInfo the identifying information for this assigner.
 * @param storeIncarnation Store incarnation for preferred assigners understood by the state
 *                         machine (the state machine operates entirely in the scope of a single
 *                         store incarnation, and is unable to observe any state outside this
 *                         store incarnation).
 */

@NotThreadSafe
class EtcdPreferredAssignerStateMachine(
    selfAssignerInfo: AssignerInfo,
    storeIncarnation: Incarnation,
    config: EtcdPreferredAssignerDriver.Config)
    extends StateMachine[Event, DriverAction] {
  require(storeIncarnation.isNonLoose, "Store incarnation must be non-loose")

  private val logger = PrefixLogger.create(this.getClass, "dicer-preferred-assigner")

  /** Current [[RunState]]; see that class for details on what each of the run states mean. */
  private var runState: RunState = RunState.Startup(deadlineOpt = None)

  /**
   * Whether this assigner has received a termination signal and should attempt to abdicate
   * (whenever preferred) and refrain from becoming preferred (when standby).
   */
  private var isTerminating: Boolean = false

  /**
   * The earliest time a write is allowed (to become preferred or abdicate as appropriate), used to
   * limit the rate of write retries.
   */
  private var earliestWriteTime: TickerTime = TickerTime.MIN

  /** Incrementing operation id to identify heartbeat requests. */
  private var heartbeatOpId: Long = 0

  /**
   * The latest externally-supplied preferred-assigner pick, or `None` if no pick is currently
   * known.
   *
   * All reads and writes occur from `onEvent` / `onAdvance`, which the enclosing
   * `StateMachineDriver` dispatches on its SEC; no other synchronization is required.
   *
   * When set, every non-abdication preferred-assigner write proposes this pick rather than
   * `selfAssignerInfo`. Abdication writes (`Preferred + isTerminating`) are the sole exception:
   * they always propose no preferred assigner regardless of this field.
   *
   * Not cleared by run-state transitions — this is a deliberate invariant: the
   * most-recently-received pick is preserved across all transitions (verified by the
   * "ExternalPick is preserved across Preferred -> Standby transitions" test).
   */
  private var externalPickOpt: Option[AssignerInfo] = None

  override def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: Event): StateMachineOutput[DriverAction] = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    event match {
      case PreferredAssignerReceived(preferredAssigner: PreferredAssignerValue) =>
        onPreferredAssignerReceived(tickerTime, preferredAssigner, outputBuilder)

      case HeartbeatRequestReceived(
          request: HeartbeatRequest,
          opaqueContext: Promise[HeartbeatResponse]
          ) =>
        onHeartbeatRequest(request, opaqueContext, outputBuilder)

      case HeartbeatSuccess(opId: Long, preferredAssigner: PreferredAssignerValue) =>
        onHeartbeatSuccess(tickerTime, opId, preferredAssigner, outputBuilder)

      case TerminationNoticeReceived =>
        onTerminationNoticeReceived(tickerTime)

      case WriteResultReceived(startTime: TickerTime, result: Try[WriteResult]) =>
        onWriteResultReceived(tickerTime, startTime, result, outputBuilder)

      case ExternalPickReceived(newPickOpt: Option[AssignerInfo]) =>
        onExternalPickReceived(newPickOpt)
    }
    onAdvanceInternal(tickerTime, outputBuilder)
  }

  override def onAdvance(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[DriverAction] = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    onAdvanceInternal(tickerTime, outputBuilder)
    outputBuilder.build()
  }

  private def onAdvanceInternal(
      tickerTime: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]
  ): StateMachineOutput[DriverAction] = {
    // The onAdvanceInternal method implements the following logic for heartbeats and writes:
    //
    // 1. When starting up, enforce a deadline to learn the initial identity of the preferred
    //    assigner, after which the state machine will assume there is no preferred assigner and
    //    attempt to write itself as preferred.
    //
    // 2. When acting as primary, if a terminal signal has been received then write None to the
    //    store to abdicate, retrying as needed.
    //
    // 3. When acting as standby against some preferred assigner, send repeated heartbeats
    //    against that assigner. If too many heartbeats fail consecutively, assume the assigner
    //    is unhealthy and attempt to write this assigner to the preferred assigner store to take
    //    over, retrying if the unhealthiness persists.
    //
    // 4. When acting as standby with no preferred assigner, immediately attempt to write this
    //    assigner as the preferred assigner, retrying periodically while the condition persists.

    // Decide which onAdvance logic to perform based on the run state.
    runState match {

      case RunState.Startup(None) =>
        // Initial onAdvance call: compute a deadline to find out the identity of the preferred
        // assigner.
        val deadline = tickerTime + config.initialPreferredAssignerTimeout
        updateRunState(RunState.Startup(Some(deadline)))
        // Following our standard pattern, we advance again because our run state has evolved. In
        // this case, this will ensure another advance call at the deadline.
        onAdvanceInternal(tickerTime, outputBuilder)

      case RunState.Startup(Some(startupDeadline)) =>
        // If we haven't hit the deadline yet, schedule another advance call at the deadline.
        // Otherwise enter StandbyWithoutPreferred mode and advance immediately so that we'll
        // attempt to write ourselves as the preferred assigner.
        if (tickerTime < startupDeadline) {
          // Ignore spurious wakeup.
          outputBuilder.ensureAdvanceBy(startupDeadline)
        } else {
          logger.warn("Startup timed out without learning the preferred assigner, assuming none")
          updateRunState(RunState.StandbyWithoutPreferred(generationOpt = None))
          onAdvanceInternal(tickerTime, outputBuilder)
        }

      case RunState.Preferred(_: Generation) =>
        //  If we're acting as preferred assigner and terminating, attempt to write None as the
        //  preferred assigner and schedule another advance call to retry as necessary.
        if (isTerminating) {
          if (tickerTime >= earliestWriteTime) {
            logger.info("Attempting to write NoAssigner as the preferred assigner to abdicate")
            val preferredAssignerProposal =
              PreferredAssignerProposal(runState.generationOpt, newPreferredAssignerInfoOpt = None)
            emitWrite(tickerTime, ValueSource.NoAssigner, preferredAssignerProposal, outputBuilder)
            earliestWriteTime = tickerTime + config.writeRetryInterval
          }
          outputBuilder.ensureAdvanceBy(earliestWriteTime)
        }

      case standbyState: RunState.Standby =>
        // If we're acting as a standby:
        //  - If the current preferred assigner is from a past store incarnation, it should be
        //    terminating or pending termination. We attempt to write ourselves as preferred
        //    immediately, because a faster takeover would help the newly started assigner to
        //    serve requests sooner.
        //  - If the current preferred assigner is from a future store incarnation, we don't
        //    heartbeat against it and will never attempt to take over;
        //  - otherwise, we heartbeat against the preferred assigner if the heartbeat time has
        //    arrived, and write ourselves as preferred if there have been too many recent
        //    consecutive heartbeat failures.
        if (standbyState.generation.incarnation < storeIncarnation) {
          // Attempt to write the preferred-assigner candidate (external pick if known, else
          // self).
          earliestWriteTime = tickerTime // Allow the write to happen immediately.
          writePreferredAssignerCandidateUnlessTerminating(tickerTime, outputBuilder)
        } else if (standbyState.generation.incarnation > storeIncarnation) {
          // The preferred assigner is from a future store incarnation, so we don't heartbeat
          // against it and will never attempt to take over.
          updateRunState(
            standbyState.withHeartbeatState(
              standbyState.heartbeatState.copy(nextHeartbeatTime = TickerTime.MAX)
            )
          )
        } else if (tickerTime < standbyState.heartbeatState.nextHeartbeatTime) {
          // Spurious wakeup, ensure we advance again by the next heartbeat time.
          outputBuilder.ensureAdvanceBy(standbyState.heartbeatState.nextHeartbeatTime)
        } else {
          var heartbeatState = standbyState.heartbeatState
          heartbeatOpId += 1
          val preferredAssignerValue: PreferredAssignerValue.SomeAssigner =
            PreferredAssignerValue.SomeAssigner(standbyState.assignerInfo, standbyState.generation)
          heartbeatState = heartbeatState.withNewHeartbeatRequest(
            tickerTime,
            HeartbeatRequest(heartbeatOpId, preferredAssignerValue)
          )
          outputBuilder.appendAction(
            // The get below is safe because we just added a request in flight to the state.
            DriverAction.SendHeartbeat(heartbeatState.requestOpt.get)
          )

          // The preferred assigner has missed too many heartbeats; attempt to take over by
          // writing the preferred-assigner candidate (external pick if known, else self).
          // We won't actually transition to the preferred state until we learn that the write
          // succeeded via a PreferredAssignerReceived event.
          if (heartbeatState.failureCount >= config.heartbeatFailureThreshold) {
            logger.info(
              s"The preferred assigner ${standbyState.assignerInfo.uuid} failed to respond " +
              s"${heartbeatState.failureCount} heartbeats."
            )
            writePreferredAssignerCandidateUnlessTerminating(tickerTime, outputBuilder)
          }
          // Update the run state with the new heartbeat state.
          updateRunState(standbyState.withHeartbeatState(heartbeatState))
          outputBuilder.ensureAdvanceBy(heartbeatState.nextHeartbeatTime)
          onAdvanceInternal(tickerTime, outputBuilder)
        }
      case RunState.StandbyWithoutPreferred(_) =>
        // If there is no preferred assigner, attempt to write the preferred-assigner
        // candidate (external pick if known, else self) and ensure another advance call at the
        // next write time to retry if necessary.
        writePreferredAssignerCandidateUnlessTerminating(tickerTime, outputBuilder)
    }
    outputBuilder.build()
  }

  /** Updates the `runState` to `newRunState` and sets the [[MonitoredAssignerRole]] metric. */
  private def updateRunState(newRunState: RunState): Unit = {
    runState = newRunState

    // Update metrics depending on the new runState
    runState match {
      case RunState.Startup(_) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.STARTUP)
      case RunState.Preferred(_) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.PREFERRED)
      case RunState.Standby(_, _, _) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.STANDBY)
      case RunState.StandbyWithoutPreferred(_) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(
          MonitoredAssignerRole.STANDBY_WITHOUT_PREFERRED
        )
    }
  }

  /**
   * Responds to [[Event.PreferredAssignerReceived]] by incorporating any new preferred assigner
   * knowledge.
   */
  private def onPreferredAssignerReceived(
      tickerTime: TickerTime,
      info: PreferredAssignerValue,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    incorporatePreferredAssigner(tickerTime, info, outputBuilder)
  }

  /**
   * Responds to [[Event.ExternalPickReceived]] by recording the new pick. The pick takes
   * effect on the next preferred-assigner write — no write is triggered here.
   */
  private def onExternalPickReceived(newExternalPickOpt: Option[AssignerInfo]): Unit = {
    if (externalPickOpt != newExternalPickOpt) {
      val oldUuid: String = externalPickOpt.map(_.uuid.toString).getOrElse("none")
      val newUuid: String = newExternalPickOpt.map(_.uuid.toString).getOrElse("none")
      logger.info(s"External preferred-assigner pick updated: $oldUuid -> $newUuid")
      externalPickOpt = newExternalPickOpt
    }
  }

  /**
   * Responds to [[Event.HeartbeatSuccess]] by resetting our count of failed heartbeats (as
   * appropriate) and incorporating knowledge of the preferred assigner from the response.
   */
  private def onHeartbeatSuccess(
      tickerTime: TickerTime,
      opId: Long,
      preferredAssigner: PreferredAssignerValue,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    runState match {
      case standbyState: RunState.Standby =>
        // If running as standby, and the heartbeat is for the current request, reset the failure
        // count and ensure an advance call at the next scheduled heartbeat time.
        val heartbeatState = standbyState.heartbeatState.withHeartbeatSuccess(opId)
        updateRunState(standbyState.withHeartbeatState(heartbeatState))
        PreferredAssignerMetrics.incrementHeartbeatSuccessCount()
      case _ =>
      // Nothing to do
    }
    incorporatePreferredAssigner(tickerTime, preferredAssigner, outputBuilder)
  }

  private def onHeartbeatRequest(
      request: HeartbeatRequest,
      opaqueContext: Promise[HeartbeatResponse],
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    val knownPreferredAssignerGeneration: Generation =
      runState.generationOpt.getOrElse(Generation.EMPTY)
    val knownPreferredAssignerValue: PreferredAssignerValue = runState match {
      case RunState.Startup(_) | RunState.StandbyWithoutPreferred(_) =>
        PreferredAssignerValue.NoAssigner(knownPreferredAssignerGeneration)
      case RunState.Preferred(_) =>
        PreferredAssignerValue.SomeAssigner(selfAssignerInfo, knownPreferredAssignerGeneration)
      case RunState.Standby(assignerInfo: AssignerInfo, _, _) =>
        PreferredAssignerValue.SomeAssigner(assignerInfo, knownPreferredAssignerGeneration)
    }
    val response: Try[HeartbeatResponse] = Success(
      HeartbeatResponse(request.opId, knownPreferredAssignerValue)
    )
    outputBuilder.appendAction(DriverAction.RespondToHeartbeat(response, opaqueContext))
  }

  /**
   * Responds to [[Event.TerminationNoticeReceived]] by recording the termination and potentially
   * triggering an abdication write.
   */
  private def onTerminationNoticeReceived(tickerTime: TickerTime): Unit = {
    if (!isTerminating) {
      logger.info("Termination notice received, setting isTerminating to true.")
      isTerminating = true
      // Allow the potential abdication write to happen immediately, even if a preferred assigner
      // write has happened recently.
      earliestWriteTime = tickerTime
    }
  }

  /**
   * Handles the completion of a write attempt. Unwraps the result and takes appropriate action
   * based on the outcome: committed writes update the preferred assigner state, while OCC failures
   * and exceptions are logged (the state machine will automatically retry if needed).
   */
  private def onWriteResultReceived(
      tickerTime: TickerTime,
      startTime: TickerTime,
      result: Try[WriteResult],
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    // Determine the outcome, handle it, and extract the label and status code for metrics
    val (outcomeLabel, statusCode): (String, Status.Code) = result match {
      case Success(writeResult: WriteResult) =>
        writeResult match {
          case WriteResult.Committed(committedPreferredAssigner: PreferredAssignerValue) =>
            logger.info(s"committed preferred assigner: $committedPreferredAssigner")
            incorporatePreferredAssigner(tickerTime, committedPreferredAssigner, outputBuilder)
            ("committed", Status.Code.OK)
          case WriteResult.OccFailure(existingPreferredAssignerGeneration: Generation) =>
            // OccFailure indicates that some other assigner has written a more recent
            // preferred assigner value. Log this and do nothing; the state machine will
            // retry if appropriate.
            logger.info(
              s"Write failed OCC check; existing preferred assigner generation: " +
              s"$existingPreferredAssignerGeneration"
            )
            ("occ_failure", Status.Code.OK)
        }
      case Failure(exception: Throwable) =>
        // The write failed with an exception. Log it and do nothing; the state machine will
        // retry the write after an interval if needed.
        logger.error(s"Write failed with an exception: ${exception.getMessage}")
        val exceptionStatusCode: Status.Code =
          StatusUtils.convertExceptionToStatus(exception).getCode
        ("exception", exceptionStatusCode)
    }

    // Record the latency with the determined outcome
    val writeLatency: FiniteDuration = tickerTime - startTime
    PreferredAssignerMetrics.recordWriteLatency(writeLatency, statusCode, outcomeLabel)
  }

  /**
   * Incorporates potentially new preferred assigner info learned from some trusted source and
   * updates the run state as appropriate. Ignores any preferred assigner information with a
   * generation older than the one we know about.
   */
  private def incorporatePreferredAssigner(
      tickerTime: TickerTime,
      preferredAssigner: PreferredAssignerValue,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {

    // Update our knowledge of the preferred assigner if the generation is newer than the one we
    // know about.
    if (runState.generationOpt.isEmpty ||
      runState.generationOpt.get < preferredAssigner.generation) {

      logger.info(s"Preferred assigner value updated to $preferredAssigner")
      PreferredAssignerMetrics.setLatestKnownGeneration(preferredAssigner.generation)

      preferredAssigner match {
        case PreferredAssignerValue.SomeAssigner(assignerInfo: AssignerInfo, generation: Generation)
            if selfAssignerInfo == assignerInfo =>
          // We are preferred.
          updateRunState(RunState.Preferred(generation))

        case PreferredAssignerValue
              .SomeAssigner(assignerInfo: AssignerInfo, generation: Generation) =>
          // Some other assigner is preferred.
          val heartbeatState = getHeartbeatState(assignerInfo, tickerTime)
          updateRunState(RunState.Standby(assignerInfo, generation, heartbeatState))

        case PreferredAssignerValue.NoAssigner(generation: Generation) =>
          // No assigner is preferred.
          earliestWriteTime = tickerTime // Allow the takeover write to happen immediately.
          updateRunState(RunState.StandbyWithoutPreferred(Some(generation)))

        case PreferredAssignerValue.ModeDisabled(disabledGeneration: Generation) =>
          // The preferred assigner mode was disabled, so enter the StandbyWithoutPreferred run
          // state so that we immediately attempt to write ourselves as the preferred assigner.
          // (Note that if the preferred assigner disabled value is at a future store incarnation,
          // the store will automatically fail the write, which is the desired behavior.)
          updateRunState(RunState.StandbyWithoutPreferred(Some(disabledGeneration)))
      }

      // Update the `PreferredAssignerConfig` based on the new preferred assigner value.
      val updatedConfig = PreferredAssignerConfig.create(
        preferredAssigner,
        selfAssignerInfo
      )
      outputBuilder.appendAction(DriverAction.UsePreferredAssignerConfig(updatedConfig))
    }
  }

  /**
   * Returns the heartbeat state from the previous preferred assigner if the new preferred assigner
   * is the same assigner, otherwise create new state.
   */
  private def getHeartbeatState(
      newAssignerInfo: AssignerInfo,
      tickerTime: TickerTime): HeartbeatState = {
    runState match {
      // If the new preferred assigner is the same as the old, keep the same heartbeat state.
      case standbyState: RunState.Standby if standbyState.assignerInfo == newAssignerInfo =>
        standbyState.heartbeatState

      // It's a different assigner, create new heartbeat state.
      case _ =>
        HeartbeatState(tickerTime, config.heartbeatInterval, requestOpt = None, failureCount = 0)
    }
  }

  /**
   * Appends a [[DriverAction.Write]] for the given `proposal` and records the proposal's
   * [[PreferredAssignerMetrics.ValueSource]]. All preferred-assigner writes proposed by this
   * state machine go through this helper, so write-counter increments stay in lockstep with the
   * `DriverAction.Write` actions appended to the output builder. (Whether the driver
   * subsequently executes every appended write is outside the state machine's contract — see
   * `EtcdPreferredAssignerDriver.performAction`.)
   */
  private def emitWrite(
      tickerTime: TickerTime,
      valueSource: ValueSource,
      proposal: PreferredAssignerProposal,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    outputBuilder.appendAction(DriverAction.Write(tickerTime, proposal))
    PreferredAssignerMetrics.recordWrite(valueSource)
  }

  /**
   * Writes a candidate as the preferred assigner, unless the assigner has received a termination
   * signal. The candidate is the latest external pick if one is known, or
   * `selfAssignerInfo` otherwise. Used for every "make someone preferred" path — abdication is
   * handled separately in the `RunState.Preferred` advance branch.
   */
  private def writePreferredAssignerCandidateUnlessTerminating(
      tickerTime: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    if (!isTerminating) {
      // If we're allowed to write now, write immediately.
      if (tickerTime >= earliestWriteTime) {
        // If the latest known preferred assigner is not from the current store incarnation, we
        // consider the predecessor as empty and let the [[EtcdPreferredAssignerStore]]'s internal
        // retry logic to figure out the correct predecessor and write the assigner proposal.
        val predecessorGenerationOpt: Option[Generation] = runState.generationOpt.filter {
          predecessorGeneration: Generation =>
            predecessorGeneration.incarnation == storeIncarnation
        }
        // Defer to the external pick when one has been wired in; otherwise write self,
        // preserving pre-migration behavior.
        externalPickOpt match {
          case Some(externalPick) =>
            emitWrite(
              tickerTime,
              ValueSource.ExternalPick,
              PreferredAssignerProposal(predecessorGenerationOpt, Some(externalPick)),
              outputBuilder
            )
          case None =>
            emitWrite(
              tickerTime,
              ValueSource.Self,
              PreferredAssignerProposal(predecessorGenerationOpt, Some(selfAssignerInfo)),
              outputBuilder
            )
        }
        earliestWriteTime = tickerTime + config.writeRetryInterval
      }
      // Ensure an advance call at the next write time to retry if necessary. This depends on the
      // fact that onAdvanceInternal retries writes to become preferred whenever the preferred
      // assigner is unhealthy or absent.
      outputBuilder.ensureAdvanceBy(earliestWriteTime)
    }
  }
}
object EtcdPreferredAssignerStateMachine {

  /** Input events to the preferred assigner state machine. */
  sealed trait Event
  object Event {

    /**
     * The specified preferred assigner value has been received from a store watcher, standby
     * assigner heartbeat, write response, or other trusted source by the driver.
     */
    case class PreferredAssignerReceived(preferredAssigner: PreferredAssignerValue) extends Event

    /**
     * This Assigner has received the given heartbeat `request`. The state machine should propagate
     * `opaqueContext` when requesting an action of the driver in response.
     */
    case class HeartbeatRequestReceived(
        request: HeartbeatRequest,
        opaqueContext: Promise[HeartbeatResponse])
        extends Event

    /**
     * The heartbeat with the given `opId` succeeded with the specified knowledge of the preferred
     * assigner.
     */
    case class HeartbeatSuccess(opId: Long, preferredAssigner: PreferredAssignerValue) extends Event

    /** This Assigner's pod has received a termination notice from Kubernetes. */
    case object TerminationNoticeReceived extends Event

    /** A write attempt has completed with the given result. */
    case class WriteResultReceived(startTime: TickerTime, result: Try[WriteResult]) extends Event

    /**
     * Updates [[externalPickOpt]]. Pass `None` to clear any previously known pick. See the
     * field's scaladoc for how the recorded pick influences subsequent writes.
     */
    case class ExternalPickReceived(newPickOpt: Option[AssignerInfo]) extends Event

    // We do not need an event on heartbeat failure, the state machine will fail the heartbeat if
    // exceeds the deadline.
  }

  sealed trait DriverAction
  object DriverAction {

    /** Sends a heartbeat to the specified assigner. */
    case class SendHeartbeat(heartbeatRequest: HeartbeatRequest) extends DriverAction

    /**
     * Responds to the heartbeat request captured by `context` with the given `heartbeatResponse`.
     */
    case class RespondToHeartbeat(
        heartbeatResponse: Try[HeartbeatResponse],
        opaqueContext: Promise[HeartbeatResponse])
        extends DriverAction

    /**
     * Attempts to write the specified preferred assigner proposal. `PreferredAssignerReceived` will
     * be called if the write succeeds by either committing the proposal or returning the true value
     * of the preferred assigner after a write conflict.
     */
    case class Write(
        startTime: TickerTime,
        preferredAssignerProposal: PreferredAssignerProposal
    ) extends DriverAction

    /** Uses a new PreferredAssignerConfig. */
    case class UsePreferredAssignerConfig(preferredAssignerConfig: PreferredAssignerConfig)
        extends DriverAction
  }

  /**
   * The run state of the preferred assigner state machine.
   *
   * Each run state captures not only the "mode" but also the state specific to that mode, in a
   * "normalized" fashion which attempts to allow only valid states to be encoded. For example,
   * only the `Standby` state includes heartbeat state, and the `Preferred` state includes
   * generation but not the assigner info, which by definition is the state machine's assigner.
   */
  sealed trait RunState {
    def generationOpt: Option[Generation]
  }
  object RunState {

    /**
     * Startup state, waiting to learn the initial value of the preferred assigner.
     *
     * @param deadlineOpt deadline to learn the initial value of the preferred, or None if we
     *                    haven't yet computed the deadline.
     */
    case class Startup(deadlineOpt: Option[TickerTime]) extends RunState {
      override def generationOpt: Option[Generation] = None
    }

    /** Acting as preferred assigner. */
    case class Preferred(generation: Generation) extends RunState {
      override def generationOpt: Option[Generation] = Some(generation)
    }

    /** Acting as standby for the specified assigner. */
    case class Standby(
        assignerInfo: AssignerInfo,
        generation: Generation,
        heartbeatState: HeartbeatState)
        extends RunState {

      override def generationOpt: Option[Generation] = Some(generation)

      def withHeartbeatState(newHeartbeatState: HeartbeatState): Standby = {
        Standby(assignerInfo, generation, newHeartbeatState)
      }
    }

    /** Acting as standby and there is no preferred assigner. */
    case class StandbyWithoutPreferred(generationOpt: Option[Generation]) extends RunState
  }

  /**
   * Heartbeat state when acting as standby.
   *
   * This is written in a functional style; the object is immutable but includes methods to derive
   * new states from the current one with new heartbeat requests or heartbeat successes.
   *
   * @param nextHeartbeatTime the time at which the next heartbeat should be sent.
   * @param interval the interval between heartbeats.
   * @param requestOpt the current heartbeat request, if any.
   * @param failureCount the number of consecutive failed heartbeats.
   */
  case class HeartbeatState(
      nextHeartbeatTime: TickerTime,
      interval: FiniteDuration,
      requestOpt: Option[HeartbeatRequest],
      failureCount: Int) {

    /**
     * Returns the updated state given that the specified new request has been issued at or after
     * heartbeat time.
     */
    def withNewHeartbeatRequest(
        tickerTime: TickerTime,
        heartbeatRequest: HeartbeatRequest): HeartbeatState = {
      var newNextHeartbeatTime = nextHeartbeatTime + interval
      if (tickerTime > newNextHeartbeatTime) {
        // If we're so laggy that even the next heartbeat is already late, compute the next
        // heartbeat time relative to now.
        newNextHeartbeatTime = tickerTime + interval
      }

      // If there was a previous request in flight, treat it as a failure.
      val newFailureCount: Int = if (requestOpt.isDefined) {
        PreferredAssignerMetrics.incrementHeartbeatFailureCount()
        failureCount + 1
      } else {
        failureCount
      }

      HeartbeatState(
        newNextHeartbeatTime,
        interval,
        Some(heartbeatRequest),
        newFailureCount
      )
    }

    /** Returns the updated state given that the specified opId has succeeded. */
    def withHeartbeatSuccess(opId: Long): HeartbeatState = {
      requestOpt match {
        case Some(heartbeatRequest) if heartbeatRequest.opId == opId =>
          // If the current heartbeat successfully completed, reset the failure count.
          HeartbeatState(nextHeartbeatTime, interval, requestOpt = None, failureCount = 0)
        case _ =>
          // Ignore the success, it's some stale result.
          this
      }
    }
  }
}
