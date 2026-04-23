package com.databricks.dicer.assigner

import java.time.Instant
import java.util.UUID

import javax.annotation.concurrent.NotThreadSafe

import scala.concurrent.duration.DurationInt

import com.databricks.caching.util.AssertMacros.ifail
import com.databricks.caching.util.{PrefixLogger, StateMachine, StateMachineOutput, TickerTime}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.dicer.assigner.ConsistentHashingPreferredAssignerStateMachine.{
  DriverAction,
  Event,
  RunState
}
import com.databricks.dicer.assigner.PreferredAssignerMetrics.MonitoredAssignerRole
import com.databricks.dicer.common.{Generation, Incarnation}

/**
 * State machine for the consistent-hashing preferred assigner selection protocol.
 *
 * The state machine tracks the latest known set of assigners from [[Event.ResourceSetReceived]]
 * events (respecting the set with largest resource version) and uses a deterministic algorithm to
 * pick the preferred from amongst them. [[DriverAction.UsePreferredAssignerConfig]] is emitted to
 * signal changes to the selected preferred.
 *
 * The state machine also supports a suppression mechanism via [[Event.SuppressionNotice]].
 * When preferred assigner selection is suppressed, the state machine will indicate that there is
 * no known preferred assigner, but will continue to listen for resource set updates through
 * [[Event.ResourceSetReceived]] events. When suppression is lifted, the state machine
 * recomputes the preferred assigner from the latest known state.
 *
 * @param selfAssignerInfo The identifying information for this assigner.
 */
@NotThreadSafe
private[assigner] class ConsistentHashingPreferredAssignerStateMachine(
    selfAssignerInfo: AssignerInfo)
    extends StateMachine[Event, DriverAction] {

  private val logger: PrefixLogger =
    PrefixLogger.create(this.getClass, "consistent-hashing-preferred-assigner")

  /**
   * Current run state of the state machine. We start in the ineligible state because we don't have
   * initial knowledge of the running Assigners.
   */
  private var runState: RunState = RunState.Ineligible

  /** Whether preferred assigner selection is currently suppressed. */
  private var isSuppressed: Boolean = false

  /** Latest known resource set, updated during [[onResourceSetReceived]]. */
  private var latestResources: Map[UUID, AssignerInfo] = Map.empty

  /** Version of the latest accepted resource set, used to reject out-of-order updates. */
  private var latestResourceVersionOpt: Option[ResourceVersion] = None

  override def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: Event): StateMachineOutput[DriverAction] = {
    val outputBuilder: StateMachineOutput.Builder[DriverAction] =
      new StateMachineOutput.Builder[DriverAction]

    event match {
      case Event
            .ResourceSetReceived(version: ResourceVersion, resources: Map[UUID, AssignerInfo]) =>
        onResourceSetReceived(version, resources, outputBuilder)

      case Event.SuppressionNotice(shouldSuppress: Boolean) =>
        onSuppressionNotice(shouldSuppress, outputBuilder)
    }

    onAdvanceInternal(outputBuilder)
    outputBuilder.build()
  }

  override def onAdvance(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[DriverAction] = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    onAdvanceInternal(outputBuilder)
    outputBuilder.build()
  }

  /** Handles a new versioned resource set from the [[ResourceWatcher]]. */
  private def onResourceSetReceived(
      version: ResourceVersion,
      resources: Map[UUID, AssignerInfo],
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    // Reject out-of-order updates: only process resource sets with a version strictly newer
    // than the last accepted version.
    val isNewer: Boolean = latestResourceVersionOpt match {
      case Some(latestResourceVersion: ResourceVersion) =>
        version > latestResourceVersion
      case None =>
        // We don't know of any version yet, so anything is newer than what we have.
        true
    }

    if (isNewer) {
      latestResourceVersionOpt = Some(version)
      latestResources = resources
    } else {
      logger.info(
        s"Got resource set with version=$version (latest=$latestResourceVersionOpt), ignoring",
        every = 30.seconds
      )
    }
  }

  /** Handles a suppression mode change for preferred assigner selection.  */
  private def onSuppressionNotice(
      shouldSuppress: Boolean,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    if (shouldSuppress != isSuppressed) {
      logger.info(
        s"Preferred selection suppression changed from $isSuppressed to $shouldSuppress"
      )
      isSuppressed = shouldSuppress
    }
  }

  /**
   * Recomputes the preferred assigner from [[latestResources]] and emits the corresponding action
   * when knowledge of the preferred assigner changes.
   */
  private def onAdvanceInternal(outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    val newRunState: RunState = if (isSuppressed) {
      // The latest information we have from the resource watcher may be very stale, so we're more
      // likely to compute a different preferred assigner than if we had more up-to-date
      // information. Err on the side of caution and claim no knowledge:
      RunState.Ineligible
    } else {
      // Our information is relatively up-to-date. Compute the PA based on the latest resource set.
      val uuids: Set[UUID] = latestResources.keySet
      val preferredUuidOpt: Option[UUID] = PreferredAssignerSelector.selectPreferredAssigner(uuids)
      val preferredInfoOpt: Option[AssignerInfo] = preferredUuidOpt.map { uuid: UUID =>
        // The selected UUID must be in the set.
        latestResources.getOrElse(
          uuid,
          ifail(s"Selector returned UUID not in resource set: $uuid")
        )
      }

      preferredInfoOpt match {
        case Some(info: AssignerInfo) if info == selfAssignerInfo =>
          RunState.Preferred(info)
        case Some(info: AssignerInfo) =>
          RunState.Standby(info)
        case None =>
          RunState.Ineligible
      }
    }

    // Only emit a config update when the run state actually changes.
    if (newRunState != runState) {
      updateRunState(newRunState, outputBuilder)
    }
  }

  /**
   * Updates the internal run state and emits the corresponding
   *[[DriverAction.UsePreferredAssignerConfig]] action.
   */
  private def updateRunState(
      newRunState: RunState,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    runState = newRunState
    val preferredInfoOpt: Option[AssignerInfo] = runState match {
      case RunState.Preferred(preferredAssignerInfo: AssignerInfo) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.PREFERRED)
        Some(preferredAssignerInfo)
      case RunState.Standby(preferredAssignerInfo: AssignerInfo) =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.STANDBY)
        Some(preferredAssignerInfo)
      case RunState.Ineligible =>
        PreferredAssignerMetrics.setAssignerRoleGauge(MonitoredAssignerRole.INELIGIBLE)
        None
    }

    val config: PreferredAssignerConfig = preferredInfoOpt
      .map { preferredAssignerInfo: AssignerInfo =>
        PreferredAssignerConfig.create(
          PreferredAssignerValue.SomeAssigner(
            preferredAssignerInfo,
            ConsistentHashingPreferredAssignerStateMachine.DUMMY_GENERATION
          ),
          selfAssignerInfo
        )
      }
      .getOrElse(
        PreferredAssignerConfig.create(
          PreferredAssignerValue
            .NoAssigner(ConsistentHashingPreferredAssignerStateMachine.DUMMY_GENERATION),
          selfAssignerInfo
        )
      )
    outputBuilder.appendAction(DriverAction.UsePreferredAssignerConfig(config))
  }
}

object ConsistentHashingPreferredAssignerStateMachine {

  /**
   * Fixed generation used when constructing [[PreferredAssignerValue]] instances. The
   * consistent-hashing protocol does not use etcd generations for election — this is a placeholder
   * to satisfy the [[PreferredAssignerValue]] type contract.
   *
   * WARNING: This generation is a sentinel value. It MUST NOT be compared against etcd-backed
   * generations for freshness or ordering.
   *
   * TODO(<internal bug>): Remove once the etcd-based preferred assigner is fully decommissioned and
   * [[PreferredAssignerValue]] no longer requires a [[Generation]].
   */
  private val DUMMY_GENERATION: Generation =
    Generation(Incarnation.MIN, UnixTimeVersion.MIN)

  /** Input events to the consistent-hashing preferred assigner state machine. */
  sealed trait Event

  object Event {

    /**
     * The [[ResourceWatcher]] has delivered an updated resource set. The driver converts
     * [[VersionedResourceSet]] entries to [[AssignerInfo]] before delivering this event.
     *
     * @param version The version of this resource set, used to reject out-of-order updates.
     * @param resources The mapping from resource UUID to its [[AssignerInfo]].
     */
    case class ResourceSetReceived(version: ResourceVersion, resources: Map[UUID, AssignerInfo])
        extends Event

    /**
     * Changes whether preferred assigner selection is suppressed. When `true`, the state machine
     * transitions to [[RunState.Ineligible]]; when `false`, selection resumes.
     */
    case class SuppressionNotice(shouldSuppress: Boolean) extends Event
  }

  /** Actions requested by the state machine for the driver to perform. */
  sealed trait DriverAction

  object DriverAction {

    /** Updates watchers with a new [[PreferredAssignerConfig]]. */
    case class UsePreferredAssignerConfig(preferredAssignerConfig: PreferredAssignerConfig)
        extends DriverAction
  }

  /**
   * The run state of the consistent-hashing preferred assigner state machine. Each state captures
   * the "mode" and the state specific to that mode.
   */
  private sealed trait RunState

  private object RunState {

    /**
     * This pod is the preferred assigner (selected by the consistent hash ring).
     *
     * @param preferredAssignerInfo The [[AssignerInfo]] of the preferred assigner (self).
     */
    case class Preferred(preferredAssignerInfo: AssignerInfo) extends RunState

    /**
     * Another pod is the preferred assigner.
     *
     * @param preferredAssignerInfo The [[AssignerInfo]] of the preferred assigner (other pod).
     */
    case class Standby(preferredAssignerInfo: AssignerInfo) extends RunState

    /**
     * This Assigner doesn't know a preferred Assigner and cannot compute one because either it
     * knows no healthy resources or because the resource watcher information may be stale.
     */
    case object Ineligible extends RunState
  }
}
