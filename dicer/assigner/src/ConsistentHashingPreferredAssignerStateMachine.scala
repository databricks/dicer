package com.databricks.dicer.assigner

import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

import javax.annotation.concurrent.NotThreadSafe

import scala.concurrent.duration.DurationInt

import com.databricks.caching.util.AssertMacros.{iassert, ifail}
import com.databricks.caching.util.{
  ConsistentHashRing,
  PrefixLogger,
  StateMachine,
  StateMachineOutput,
  TickerTime
}
import com.databricks.caching.util.UnixTimeVersion
import com.google.protobuf.ByteString
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
 * IMPORTANT: Though implementation details may change, the result of the preferred assigner
 * selection must not change. This is necessary for preventing split brain scenarios and thrashing
 * during assigner rolling restarts and deployments. Otherwise, during the deployment, the old pods
 * may select a different preferred assigner than the new pods, and both preferred assigners may
 * attempt to generate assignments for the same targets and resources.
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

  /**
   * Consistent hash ring over the assigner UUIDs in [[latestResources]]. `None` whenever the
   * latest resource set is empty.
   */
  private var latestHashRingOpt: Option[ConsistentHashRing[UUID, ByteString]] = None

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
      // Rebuild the consistent hash ring only when the UUID keyset changes to avoid unnecessary
      // hash computations.
      if (resources.keySet != latestResources.keySet) {
        logger.info(s"Got updated resource set with version=$version ($resources)")
        if (resources.keySet.isEmpty) {
          latestHashRingOpt = None
        } else {
          latestHashRingOpt = Some(
            ConsistentHashRing.create(
              nodes = resources.keySet.toVector,
              vnodesPerNode = ConsistentHashingPreferredAssignerStateMachine.VNODES_PER_ASSIGNER,
              typeMapper = ConsistentHashingPreferredAssignerStateMachine.TYPE_MAPPER
            )
          )
        }
      }
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
    // Compute the new run state and the eligible assigners that were considered for selection.
    // When suppressed, no selection is made and eligible assigners is empty.
    val (newRunState, eligibleAssigners): (RunState, Seq[AssignerInfo]) = if (isSuppressed) {
      // The latest information we have from the resource watcher may be very stale, so we're more
      // likely to compute a different preferred assigner than if we had more up-to-date
      // information. Err on the side of caution and claim no knowledge:
      (RunState.Ineligible, Seq.empty)
    } else {
      // Our information is relatively up-to-date. Compute the PA based on the latest resource set.
      val preferredUuidOpt: Option[UUID] = latestHashRingOpt.map {
        ring: ConsistentHashRing[UUID, ByteString] =>
          ring.lookup(key = ConsistentHashingPreferredAssignerStateMachine.LOOKUP_KEY)
      }
      val preferredInfoOpt: Option[AssignerInfo] = preferredUuidOpt.map { uuid: UUID =>
        // The selected UUID must be in the set.
        latestResources.getOrElse(
          uuid,
          ifail(s"Selector returned UUID not in resource set: $uuid")
        )
      }

      val state: RunState = preferredInfoOpt match {
        case Some(info: AssignerInfo) if info.uuid == selfAssignerInfo.uuid =>
          // Here we compare the UUIDs and not the entire AssignerInfo to avoid potential mismatch
          // caused by same URI with differing formatting.
          RunState.Preferred(info)
        case Some(info: AssignerInfo) =>
          RunState.Standby(info)
        case None =>
          RunState.Ineligible
      }
      // Sort by UUID for deterministic ordering in logs.
      val sortedEligible: Seq[AssignerInfo] =
        latestResources.values.toSeq.sortBy(_.uuid)
      (state, sortedEligible)
    }

    // Only emit a config update when the run state actually changes.
    if (newRunState != runState) {
      updateRunState(newRunState, eligibleAssigners, outputBuilder)
    }
  }

  /**
   * Updates the internal run state and emits the corresponding
   * [[DriverAction.UsePreferredAssignerConfig]] action.
   *
   * @param newRunState the new run state to transition to.
   * @param eligibleAssigners the assigners that were considered for selection (empty when
   *                          suppressed).
   * @param outputBuilder the output builder to append the action to.
   */
  private def updateRunState(
      newRunState: RunState,
      eligibleAssigners: Seq[AssignerInfo],
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    logger.info(s"RunState changed from $runState to $newRunState")
    runState = newRunState
    val preferredInfoOpt: Option[AssignerInfo] = runState match {
      case RunState.Preferred(preferredAssignerInfo: AssignerInfo) =>
        PreferredAssignerMetrics.setChAssignerRoleGauge(MonitoredAssignerRole.PREFERRED)
        Some(preferredAssignerInfo)
      case RunState.Standby(preferredAssignerInfo: AssignerInfo) =>
        PreferredAssignerMetrics.setChAssignerRoleGauge(MonitoredAssignerRole.STANDBY)
        Some(preferredAssignerInfo)
      case RunState.Ineligible =>
        PreferredAssignerMetrics.setChAssignerRoleGauge(MonitoredAssignerRole.INELIGIBLE)
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
    outputBuilder.appendAction(
      DriverAction.UsePreferredAssignerConfig(config, eligibleAssigners)
    )
  }

  private[assigner] object forTest {

    /**
     * Checks the invariants of the state machine:
     * - [[latestHashRingOpt]] is defined iff [[latestResources]] is non-empty;
     * - when defined, the ring's nodes are exactly the UUIDs in [[latestResources]].
     */
    def checkInvariants(): Unit = {
      iassert(
        latestHashRingOpt.isDefined == latestResources.nonEmpty,
        s"latestHashRingOpt.isDefined=${latestHashRingOpt.isDefined} but " +
        s"latestResources.nonEmpty=${latestResources.nonEmpty}"
      )
      for (ring: ConsistentHashRing[UUID, ByteString] <- latestHashRingOpt) {
        iassert(
          ring.nodes.toSet == latestResources.keySet,
          s"ring.nodes=${ring.nodes} do not match latestResources.keySet=${latestResources.keySet}"
        )
      }
    }
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

  /**
   * Number of virtual node positions per assigner on the consistent hash ring used to select the
   * preferred assigner. Higher values improve distribution uniformity at the cost of more entries
   * in the ring.
   */
  private val VNODES_PER_ASSIGNER: Int = 100

  /**
   * Fixed lookup key used to select a single owner on the ring. The choice of bytes is arbitrary;
   * the same bytes yield the same hash position on every call, which is what makes the selection
   * deterministic.
   */
  private val LOOKUP_KEY: ByteString = ByteString.copyFromUtf8("PreferredAssigner")

  /** Maps UUID nodes and ByteString lookup keys to the bytes the ring hashes. */
  private object TYPE_MAPPER extends ConsistentHashRing.TypeMapper[UUID, ByteString] {

    /** Maps a UUID node to bytes using its 16-byte big-endian encoding. */
    override def mapNode(node: UUID): ByteString = ByteString.copyFrom(
      ByteBuffer
        .allocate(16)
        .putLong(node.getMostSignificantBits)
        .putLong(node.getLeastSignificantBits)
        .array()
    )

    /** The lookup key is already a ByteString, so no conversion is needed. */
    override def mapKey(key: ByteString): ByteString = key
  }

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

    /**
     * Updates watchers with a new [[PreferredAssignerConfig]].
     *
     * @param preferredAssignerConfig the new preferred assigner configuration.
     * @param eligibleAssigners the assigners that were considered for selection (empty when
     *                          selection was suppressed).
     */
    case class UsePreferredAssignerConfig(
        preferredAssignerConfig: PreferredAssignerConfig,
        eligibleAssigners: Seq[AssignerInfo])
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
