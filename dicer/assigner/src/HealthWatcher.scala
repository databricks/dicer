package com.databricks.dicer.assigner

import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._
import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP
import com.databricks.caching.util.AsciiTable.Header
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  AsciiTable,
  CachingErrorCode,
  IntrusiveMinHeap,
  IntrusiveMinHeapElement,
  PrefixLogger,
  StateMachine,
  StateMachineOutput,
  TickerTime
}
import com.databricks.dicer.assigner.HealthWatcher.Event.AssignmentSyncObserved
import com.databricks.dicer.assigner.HealthWatcher.Event.AssignmentSyncObserved.{
  AssignmentObserved,
  GenerationObserved
}
import com.databricks.dicer.assigner.HealthWatcher.HealthStatus.{
  NotReady,
  Running,
  Terminating,
  Unknown
}
import com.databricks.dicer.assigner.HealthWatcher.{
  DriverAction,
  Event,
  HealthStatus,
  HealthStatusReport,
  ResourceHealth,
  State,
  StaticConfig,
  isReliableSource
}
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource.AssignmentDistributionSource
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.assigner.conf.HealthConf
import com.databricks.dicer.common.{Assignment, Generation}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.Squid

/**
 * The health watcher is a state machine which tracks the health status of resources based on
 * various environmental signals and informs the caller of the current set of healthy resources
 * whenever they change.
 *
 * Upon being supplied with an event or advancement, the health watcher will inform the caller
 * about a health report (see [[DriverAction]]) of currently tracked resources if there's any change
 * (resource status transition, resource removed, resource added) caused by the event. The caller
 * will also be told the next time when there will be a change of resource status, and the caller is
 * expected to query the health watcher again no later than that time so it can promptly observe any
 * resource status change. The HealthWatcher is implemented as a state machine so that it can easily
 * compose with its parent state machine, AssignmentGenerator, and is effectively driven by the
 * AssignmentGeneratorDriver. See [[StateMachine]] documentation for additional background on the
 * driver/state machine pattern.
 *
 * Initial health delay: Typically when the health watcher is informed about a resource in Running
 * state (by [[Event.HealthStatusObserved{ByUuid}]]), the resource will be incorporated in the
 * health report immediately. However during an initial delay of
 * [[HealthWatcher.StaticConfig.unhealthyTimeoutPeriod]] after the health watcher is started, it
 * will just silently track the resources without generating any health report. A health report
 * containing all resources tracked during this time will be generated upon the initial health delay
 * is passed. This is because in practice when an assigner has just started, we would like to
 * collect a sufficient set of resources before generating the first assignment.
 *
 * Health bootstrap: Since the initial health delay introduces a period of unavailability for
 * generating new assignments, the delay can be short circuited by bootstrapping health information
 * from recent assignments, which works as follows: The health watcher should be kept informed about
 * the assignment sync events, and it will keep track of the highest assignment it learns as a
 * potential candidate whose assigned resources can be used to bootstrap health information. In
 * order to decide whether the assignments are fresh enough (i.e. do not contain resources no longer
 * living), the health watcher also observes and tracks the highest assignment generation sync-ed
 * from "reliable" sources during the initial health delay. If it finds the latest assignment's
 * generation is no older than the highest generation sync-ed from reliable sources, the health
 * delay will be immediately bypassed, and the resources assigned in the latest assignment will be
 * incorporated into the first health report (of course, among with other resources observed by
 * [[Event.HealthStatusObserved{ByUuid}]]). Here "reliable" means that we believe the source is
 * highly likely to know an assignment fresh enough such that it doesn't contain any dead resources,
 * so that the initial health report (and initial assignment) will not be polluted by the stale
 * resources. Intuitively, if we know an assignment generation from a reliable source, we can safely
 * assume assignments with even higher generations are also reliable and fresh (or even fresher), so
 * the health watcher will actually incorporate the assigned resources with the highest generation
 * it observes when bootstrapping the health.
 *
 * Currently we only consider slicelets as reliable sources of assignment, as slicelets are
 * frequently synchronizing assignments with both clerks and assigners so there's a high chance that
 * they know assignments fresh enough. Other sources, e.g. assignments from an in-memory
 * store, are not always a reliable source. For example, when a preferred assigner using in-memory
 * store becomes a stand-by assigner, the assignment in its in-memory store will remain. And if this
 * assigner becomes preferred again after a long time, the assignment from its in-memory store can
 * be rather stale.
 *
 * TODO(<internal bug>): Distinguish [[AssignmentDistributionSource.Store]] with in-memory store and etcd
 *                  store, and mark assignments from etcd store as "reliable". Or we can completely
 *                  delete initial health delay process after etcd store is living everywhere.
 *
 * Not thread-safe. In practice, access is protected by the [[AssignmentGeneratorDriver]]'s sec.
 */
object HealthWatcher {

  /**
   * REQUIRES: `unhealthyTimeoutPeriod` is positive
   * REQUIRES: `terminatingTimeoutPeriod` is positive
   *
   * Static configuration (i.e., based on DbConf; does not vary per target) for the HealthWatcher.
   * Per-target configuration is in [[HealthWatcherTargetConfig]].
   *
   * @param unhealthyTimeoutPeriod   when a heartbeat is not received from a resource within this
   *                                 period, it is assumed to be unhealthy. Note that in the absence
   *                                 of observing an initial set of assigned resources with which to
   *                                 bootstrap health status, this doubles as an initial health
   *                                 report delay during startup to ensure the watcher has collected
   *                                 sufficient signals before emitting its first health report.
   *                                 TODO(<internal bug>): When Dicer has an authoritative assignment
   *                                 store, this timer-based initial delay period can be removed,
   *                                 since the watcher can always learn a recent assignment state
   *                                 (at least no staler than its start time), most importantly
   *                                 including the case of "no assignment". Without an authoritative
   *                                 store, the initial delay must be preserved, since the watcher
   *                                 cannot distinguish between "no assignment" and "no assignment
   *                                 yet observed".
   * @param terminatingTimeoutPeriod we remove the HealthStatus for a pod in Terminating state after
   *                                 this period, since the pod is no longer active. See the
   *                                 documentation [[HealthConf.terminatingTimeoutPeriod]] for more
   *                                 details.
   */
  case class StaticConfig(
      unhealthyTimeoutPeriod: FiniteDuration,
      terminatingTimeoutPeriod: FiniteDuration) {
    require(unhealthyTimeoutPeriod > Duration.Zero)
    require(terminatingTimeoutPeriod > Duration.Zero)
  }

  /** Input events to the health watcher state machine. */
  sealed trait Event
  object Event {

    /**
     * Informs the health watcher that the given resource (fully identified by Squid) has the given
     * health status.
     */
    case class HealthStatusObserved(resource: Squid, healthStatus: HealthStatus) extends Event

    /**
     * Informs the health watcher that the given resource (identified only by UUID, e.g. in the case
     * we received this information from Kubernetes) has the given health status.
     */
    case class HealthStatusObservedByUuid(resourceUUID: UUID, healthStatus: HealthStatus)
        extends Event

    /**
     * Informs the health watcher that we've observed an assignment synchronization. The
     * synchronization may or may not contain a newer assignment than the assigner's knowledge
     * (see [[AssignmentObserved]], see [[GenerationObserved]]), but the health watcher should be
     * always informed about the event.
     */
    sealed trait AssignmentSyncObserved extends Event {

      /** Source of the assignment synchronization. */
      def source: AssignmentDistributionSource

      /** Generation known by the assignment synchronization. */
      def generation: Generation
    }

    object AssignmentSyncObserved {

      /**
       * Informs the health watcher about an observed assignment synchronization, where we don't
       * recover a newer assignment but only know that `generation` is known by `source`.
       */
      case class GenerationObserved(
          override val source: AssignmentDistributionSource,
          override val generation: Generation)
          extends AssignmentSyncObserved

      /** Informs the health watcher that an `assignment` is synchronized from `source`. */
      case class AssignmentObserved(
          override val source: AssignmentDistributionSource,
          assignment: Assignment)
          extends AssignmentSyncObserved {
        override def generation: Generation = assignment.generation
      }
    }
  }

  /**
   * Actions the health watcher state machine requests from the driver in response to incoming
   * signals or the passage of time.
   */
  sealed trait DriverAction
  object DriverAction {
    case class IncorporateHealthReport(healthReport: Map[Squid, HealthStatusReport])
        extends DriverAction
  }

  /** Factory methods for [[StaticConfig]]. */
  object StaticConfig {

    /** Creates a [[StaticConfig]] derived from [[HealthConf]]. */
    def fromConf(conf: HealthConf): StaticConfig = {
      StaticConfig(
        conf.unhealthyTimeoutPeriod,
        conf.terminatingTimeoutPeriod
      )
    }
  }

  /** The health status of a resource. */
  sealed trait HealthStatus
  object HealthStatus {
    case object Unknown extends HealthStatus
    case object NotReady extends HealthStatus
    case object Running extends HealthStatus
    case object Terminating extends HealthStatus

    /** All [[HealthStatus]]es. */
    val values: Set[HealthStatus] = Set(Unknown, NotReady, Running, Terminating)

    def fromProto(state: SliceletDataP.State): HealthStatus = {
      state match {
        case SliceletDataP.State.UNKNOWN => Unknown
        case SliceletDataP.State.NOT_READY => NotReady
        case SliceletDataP.State.RUNNING => Running
        case SliceletDataP.State.TERMINATING => Terminating
      }
    }
  }

  /**
   * The HealthStatuses to report to the AssignmentGenerator. The HealthWatcher may compute a
   * different status for a Slicelet than what the Slicelet reported. E.g., a Slicelet that reported
   * Terminating once should always be considered Terminating by the Assigner, since transitions
   * from Terminating to any other state are not allowed in the Slicelet state machine. See
   * [[updateHealthStatus]] for allowed transitions.
   *
   * We store the Slicelet's reported status only for visibility into the HealthWatcher's decisions.
   * Callers should always use the computed health status.
   *
   * @param reportedBySlicelet the HealthStatus reported by the Slicelet
   * @param computed           the HealthStatus computed by the HealthWatcher (may be the same as
   *                           reported)
   */
  case class HealthStatusReport(reportedBySlicelet: HealthStatus, computed: HealthStatus)

  /**
   * Factory for [[HealthWatcher]]s. Used to do dependency injection of a health watcher in tests
   * (e.g. to create a health watcher that bypasses the startup phase).
   */
  trait Factory {
    def create(
        target: Target,
        config: HealthWatcher.StaticConfig,
        healthWatcherTargetConfig: HealthWatcherTargetConfig): HealthWatcher
  }

  object DefaultFactory extends Factory {

    override def create(
        target: Target,
        config: HealthWatcher.StaticConfig,
        healthWatcherTargetConfig: HealthWatcherTargetConfig): HealthWatcher = {
      new HealthWatcher(target, config, healthWatcherTargetConfig)
    }
  }

  /** High-level state for the watcher. */
  private sealed trait State
  private object State {

    /** Initial state. */
    case object Init extends State

    /**
     * Starting: we're waiting out the unhealthy timeout period to get either a set of assigned
     * resources or sufficient signals before emitting the first health report.
     *
     * @param startTime The time we entered this state.
     */
    case class Starting(startTime: TickerTime) extends State

    /** Started: first health report emitted. */
    case object Started extends State
  }

  // TODO(<internal bug>): support the Running -> NotReady transition.
  /**
   * An object to keep track of health status related information for a resource.
   * `healthStatus` is the health status of the resource, e.g., running. `expiryTime` is the time
   * at which the HealthStatus will be removed.
   *
   * We currently do not handle the Running -> NotReady transition.
   *
   * @param resource                 A SQUID or UUID identifying the resource.
   * @param observeSliceletReadiness whether to use the Slicelet's reported readiness state to set
   *                                 its status in health reports, or mask its reported state to
   *                                 Running from the NOT_READY state. Note that even when observing
   *                                 the Slicelet's state, certain status transitions are disallowed
   *                                 (e.g., Running -> NotReady).
   */
  private class ResourceHealth(
      private var resource: Either[Squid, UUID],
      observeSliceletReadiness: Boolean)
      extends IntrusiveMinHeapElement[TickerTime] {

    // See [[HealthStatusReport]].
    private var sliceletReportedHealthStatus: HealthStatus = HealthStatus.Unknown
    private var computedHealthStatus: HealthStatus = HealthStatus.Unknown

    /** Return SQUID for the resource. */
    def getResourceSquidOpt: Option[Squid] = resource match {
      case Left(squid) => Option(squid)
      case _ => None
    }

    /** Returns resource UUID. */
    def getResourceUuid: UUID = resource match {
      case Right(uuid: UUID) => uuid
      case Left(squid: Squid) => squid.resourceUuid
    }

    /**
     * Update the resource with new health status and expiry.
     *
     * Returns true if there is a change in health status of the resource.
     */
    def update(
        expiryTime: TickerTime,
        newResource: Either[Squid, UUID],
        informedHealthStatus: HealthStatus): Boolean = {
      (resource, newResource) match {
        case (Left(oldSquid), Left(newSquid)) =>
          // We already have a SQUID, we should only update the health status if the SQUID is the
          // same or more recent.
          if (newSquid.creationTime.compareTo(oldSquid.creationTime) >= 0) {
            resource = Left(newSquid)
            // If the SQUID has changed, even if health status has not, we should report change in
            // health.
            updateHealthStatus(informedHealthStatus, expiryTime) || newSquid != oldSquid
          } else {
            false
          }

        case (Left(_), Right(_)) =>
          // We already have a SQUID, the new update does not have a SQUID, so the resource remains
          // the same.
          updateHealthStatus(informedHealthStatus, expiryTime)

        case (Right(_), Left(squid)) =>
          // The HealthStatus now has a SQUID, we assume the SQUID represents the most recent
          // incarnation of the resource.
          resource = Left(squid)
          updateHealthStatus(informedHealthStatus, expiryTime)

          // We unconditionally report a change here, since we have a new SQUID.
          true
        case (Right(_), Right(_)) =>
          // The HealthStatus still does not have a SQUID. The resource remains same.
          updateHealthStatus(informedHealthStatus, expiryTime)
      }
    }

    def getComputedHealthStatus: HealthStatus = computedHealthStatus

    def getReportedHealthStatus: HealthStatus = sliceletReportedHealthStatus

    def expiryTime: TickerTime = getPriority

    override def toString: String = s"$resource => $computedHealthStatus, expiryTime=$expiryTime"

    /**
     * Update the HealthStatus of the resource with expiry after being informed that the state is
     * `informedHealthStatus`. Updates to a Terminating resource are ignored.
     *
     * Return true if the status has been changed.
     */
    private def updateHealthStatus(
        informedHealthStatus: HealthStatus,
        expiryTime: TickerTime): Boolean = {
      sliceletReportedHealthStatus = informedHealthStatus

      // If the current status is terminating, we don't need to update the expiry.
      if (computedHealthStatus != Terminating) {
        setPriority(expiryTime)
      }

      // We don't expect well-behaved clients to ever explicitly report `Unknown`, though
      // the proto deserializer will normalize enum values not contained in the Assigner's
      // compiled proto definition to Unknown. For robustness, we should consider treating
      // `Unknown` reports as no-ops rather than updating their state here.
      val newHealthStatus: HealthStatus = (computedHealthStatus, informedHealthStatus) match {
        case (Unknown, other: HealthStatus) => other

        case (NotReady, NotReady) => NotReady
        case (NotReady, Running) => Running
        case (NotReady, Terminating) => Terminating
        case (NotReady, Unknown) => Unknown

        case (Running, NotReady) =>
          // For simplicity, disallow transitions from `Running` to `NotReady`. Eventually, we may
          // consider supporting this transition (with some hysteresis) to handle resources that are
          // temporarily unable to serve. We wouldn't often expect to get `NotReady` messages for a
          // resource in the `Running` state (e.g., it could conceivably happen due to messages
          // being reordered by the network).
          Running

        case (Running, Running) => Running
        case (Running, Terminating) => Terminating
        case (Running, Unknown) => Unknown

        case (Terminating, NotReady) | (Terminating, Running) | (Terminating, Terminating) |
            (Terminating, Unknown) =>
          // No transitions out of `Terminating`.
          Terminating
      }

      // If configured, the HealthWatcher may mask the Slicelet's reported health status with a
      // different status, namely, NotReady gets masked to Running. Whereas the (Running, NotReady)
      // branch above prevents an as-yet-unhandled state transition, this masking can be enabled for
      // targets where the Slicelet readiness probe may be unreliable (e.g., where a Slicelet never
      // transitions out of NotReady). It is meant to be applied at the Slicelet's first status
      // report.
      val maskedNewHealthStatus =
        if (!observeSliceletReadiness && newHealthStatus == NotReady) {
          Running
        } else {
          newHealthStatus
        }
      val changed: Boolean = maskedNewHealthStatus != computedHealthStatus
      computedHealthStatus = maskedNewHealthStatus
      changed
    }
  }

  /**
   * Whether the assigned resources from `source` can be considered reliable enough to bootstrap the
   * HealthWatcher. See "Health bootstrap" section of the main class doc for more information.
   */
  private def isReliableSource(source: AssignmentDistributionSource): Boolean = {
    source == AssignmentDistributionSource.Slicelet
  }
}

// TODO(<internal bug>) revisit observeSliceletReadiness default when readiness API is
// stable.
class HealthWatcher(
    private val target: Target,
    config: StaticConfig,
    healthWatcherTargetConfig: HealthWatcherTargetConfig)
    extends StateMachine[Event, DriverAction] {
  type Output = StateMachineOutput[DriverAction]

  private val logger = PrefixLogger.create(
    this.getClass,
    s"${target.getLoggerPrefix}"
  )

  private val observeSliceletReadiness =
    healthWatcherTargetConfig.observeSliceletReadiness

  /** The health status of each resource, keyed by resource. */
  private val healthByUuid = new mutable.HashMap[UUID, ResourceHealth]

  /** The health status of each resource, prioritized by minimum `expiryTime`. */
  private val healthByExpiry = new IntrusiveMinHeap[TickerTime, ResourceHealth]

  /**
   * The highest Generation observed from a reliable source. Used for judging whether an assignment
   * is fresh enough to bootstrap health. If defined, must contain a non-empty Generation because
   * an empty Generation implies the source actually has no knowledge about any assignments and
   * doesn't help in assignment freshness judgement.
   */
  private var highestReliableGenerationOpt: Option[Generation] = None

  /**
   * The latest (in terms of generation) Assignment that the LoadWatcher has seen. Used as a
   * candidate whose assigned resources can be used to bootstrap health.
   */
  private var latestAssignmentObservedOpt: Option[Assignment] = None

  /** High-level state of the watcher (init, starting, started). */
  private var state: State = State.Init

  /** Whether the health watcher is aware of unreported health status changes. */
  private var dirty = false

  override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {
    event match {
      case Event.HealthStatusObserved(resource, healthStatus) =>
        informHealthStatus(tickerTime, Left(resource), healthStatus)
      case Event.HealthStatusObservedByUuid(resourceUuid, healthStatus) =>
        informHealthStatus(tickerTime, Right(resourceUuid), healthStatus)
      case assignmentSyncObserved: Event.AssignmentSyncObserved =>
        handleAssignmentSyncObserved(assignmentSyncObserved)
    }
    onAdvance(tickerTime, instant)
  }

  override def onAdvance(tickerTime: TickerTime, instant: Instant): Output = {
    // Remove any expired resources from the health map.
    while (healthByExpiry.peek.exists { health: ResourceHealth =>
        health.expiryTime <= tickerTime
      }) {
      dirty = true
      val resourceHealth: ResourceHealth = healthByExpiry.pop()
      healthByUuid.remove(resourceHealth.getResourceUuid)
      logger.info(s"Removed expired resource: $resourceHealth")
    }
    logger.trace(makeHealthSummary(Some(tickerTime)))

    // Log a full summary once every `unhealthyTimeoutPeriod` so that any resource which expires
    // should be included in at least one summary.
    logger.info(makeHealthSummary(Some(tickerTime)), every = config.unhealthyTimeoutPeriod)

    this.state match {
      case State.Init =>
        // Advance high-level `state` and call `onAdvance` again.
        this.state = State.Starting(startTime = tickerTime)
        onAdvance(tickerTime, instant)
      case State.Starting(startTime: TickerTime) =>
        val initialHealthReportDelay: TickerTime = startTime + config.unhealthyTimeoutPeriod
        if (tickerTime >= initialHealthReportDelay) {
          // We've waited out one unhealthy timeout period without learning a set of assigned
          // resources. By this point we've collected sufficient signals anyway to be reasonably
          // confident in a comprehensive health report to emit.
          this.state = State.Started
          onAdvance(tickerTime, instant)
        } else {
          // We are still within the initial health delay. Check whether we satisfy the requirement
          // for health bootstrapping.
          (latestAssignmentObservedOpt, highestReliableGenerationOpt) match {
            case (
                Some(latestAssignment: Assignment),
                Some(highestReliableGeneration: Generation)
                ) if latestAssignment.generation >= highestReliableGeneration =>
              // Bootstraps the HealthWatcher's current set of healthy resources from the resources
              // in a latest known assignment, as we believe that its latest known assignment has a
              // high likelihood of being recent. We determine that an assignment with generation G
              // has a high likelihood of being recent if it knows that G is greater than or equal
              // to the latest assignment generation known by a reliably fresh source (see
              // `isReliableSource`).

              // Bootstrap the health by incorporating assigned resources in the latest assignment
              // into `healthByUuid` and transitioning the State into Started, so the HealthWatcher
              // will start to generate health reports in the following recursive onAdvance() call
              // and the assigned resources can possibly be used in the health report.
              val assignedResources: Set[Squid] = latestAssignment.assignedResources
              val assignedResourcesByUuid: Map[UUID, Squid] = assignedResources
                .groupBy((_: Squid).resourceUuid)
                .map {
                  case (uuid: UUID, squids: Set[Squid]) =>
                    uuid -> squids.maxBy((_: Squid).creationTime)
                }
              logger.expect(
                assignedResourcesByUuid.size == assignedResources.size,
                CachingErrorCode.ASSIGNER_ASSIGNED_SQUIDS_WITH_SAME_UUID,
                "Assigned resources contained multiple Squids with the same UUID. This is " +
                "unexpected as the health watcher monitors resource health by UUID and includes " +
                "only the Squid with the latest creation time per UUID in its health report. " +
                s"Observed assigned resources: $assignedResources"
              )
              // We've learned a set of reliably fresh assigned resources. Any explicit health
              // signals we've received are treated as authoritative, and the rest of the assigned
              // resources are considered last known healthy as of the watcher start time.
              val expiryTime: TickerTime = startTime + config.unhealthyTimeoutPeriod
              for (entry <- assignedResourcesByUuid) {
                val (uuid, squid): (UUID, Squid) = entry
                if (!healthByUuid.contains(uuid)) {
                  val resourceHealth =
                    new ResourceHealth(
                      Left(squid),
                      observeSliceletReadiness
                    )
                  resourceHealth.update(expiryTime, Left(squid), Running)
                  healthByUuid.put(uuid, resourceHealth)
                  healthByExpiry.push(resourceHealth)
                  dirty = true
                  logger.info(
                    s"Health status for '$squid' bootstrapped to $Running, " +
                    s"status expires at: $expiryTime"
                  )
                  logger.debug(makeHealthSummary(Some(tickerTime)))
                }
              }
              this.state = State.Started
              onAdvance(tickerTime, instant)
            case _ =>
              // We don't satisfy the requirement for health bootstrapping and it's too early to
              // emit a report. If we have something we'd like to report, ask for a callback when
              // the first report is due. Otherwise, request a callback never.
              val nextTime = if (dirty) initialHealthReportDelay else TickerTime.MAX
              StateMachineOutput(nextTime, Seq.empty)
          }
        }
      case State.Started =>
        // Whether or not we're emitting a report in this round, we want to be called back the next
        // time a resource expires.
        val nextExpiryTime = healthByExpiry.peek.map(_.expiryTime).getOrElse(TickerTime.MAX)
        if (dirty) {
          dirty = false

          // Build a report with SQUID and HealthStatus. We expect all Health resources to have
          // SQUID so we filter out entries without SQUID.
          val healthReport: Map[Squid, HealthStatusReport] = healthByUuid
            .filter {
              case (_, resourceHealth: ResourceHealth) =>
                resourceHealth.getResourceSquidOpt.nonEmpty
            }
            .map {
              case (_, resourceHealth: ResourceHealth) =>
                resourceHealth.getResourceSquidOpt.get -> HealthStatusReport(
                  reportedBySlicelet = resourceHealth.getReportedHealthStatus,
                  computed = resourceHealth.getComputedHealthStatus
                )
            }
            .toMap
          StateMachineOutput(
            nextExpiryTime,
            Seq(DriverAction.IncorporateHealthReport(healthReport))
          )
        } else {
          StateMachineOutput(nextExpiryTime, Seq.empty)
        }
    }
  }

  override def toString: String = makeHealthSummary(nowOpt = None)

  /**
   * Maintains `highestReliableGenerationOpt` and `latestAssignmentObservedOpt` based on the new
   * `assignmentSyncObserved` event. If the condition for health bootstrapping is satisfied by the
   * new `assignmentSyncObserved` event, the next onAdvance() call will bootstrap health based on
   * the updated `highestReliableGenerationOpt` and `latestAssignmentObservedOpt`.
   */
  private def handleAssignmentSyncObserved(assignmentSyncObserved: AssignmentSyncObserved): Unit = {
    // The latest reliable generation observed so far.
    val updatedHighestReliableGenerationOpt: Option[Generation] =
      if (isReliableSource(assignmentSyncObserved.source) &&
        // Ignore all empty generations, because an empty Generation implies the source actually has
        // no knowledge about any assignments and doesn't help in assignment freshness judgement.
        assignmentSyncObserved.generation != Generation.EMPTY &&
        highestReliableGenerationOpt
          .forall((_: Generation) < assignmentSyncObserved.generation)) {
        Some(assignmentSyncObserved.generation)
      } else {
        highestReliableGenerationOpt
      }
    // The latest assignment observed so far (from any source).
    val updatedLatestAssignmentOpt: Option[Assignment] = assignmentSyncObserved match {
      case _: GenerationObserved =>
        latestAssignmentObservedOpt
      case AssignmentObserved(_, newAssignment: Assignment) =>
        latestAssignmentObservedOpt match {
          case Some(latestAssignmentBefore: Assignment) =>
            if (newAssignment.generation > latestAssignmentBefore.generation) {
              Some(newAssignment)
            } else {
              Some(latestAssignmentBefore)
            }
          case None => Some(newAssignment)
        }
    }

    highestReliableGenerationOpt = updatedHighestReliableGenerationOpt
    latestAssignmentObservedOpt = updatedLatestAssignmentOpt
  }

  /**
   * Informs the watcher that the health status of `resource` is `healthStatus`. Caller should
   * subsequently call `onAdvance` to determine if any action is required.
   */
  private def informHealthStatus(
      now: TickerTime,
      resource: Either[Squid, UUID],
      healthStatus: HealthStatus): Unit = {
    val resourceUuid = resource match {
      case Left(squid: Squid) => squid.resourceUuid
      case Right(uuid: UUID) => uuid
    }

    val expiryTime = healthStatus match {
      case Terminating =>
        // We set the expiry to more than default termination grace period in Kubernetes ie.
        // 30 seconds. The expiry here is set so that we do not keep terminated pods forever in
        // HealthWatcher.
        now + config.terminatingTimeoutPeriod
      case _ => now + config.unhealthyTimeoutPeriod
    }

    val existingHealthStatusOpt: Option[ResourceHealth] = healthByUuid.get(resourceUuid)
    logger.info(
      s"Informed of health status for '$resourceUuid': " +
      s"$healthStatus (existing $existingHealthStatusOpt)",
      30.seconds
    )

    // Determine whether the status has changed for `resource`.
    dirty |= (existingHealthStatusOpt match {
      case Some(existingHealthStatus: ResourceHealth) =>
        if (existingHealthStatus.update(expiryTime, resource, healthStatus)) {
          logger.info(
            s"Health status changed for '$resourceUuid' from $existingHealthStatus to $healthStatus"
          )
          logger.debug(makeHealthSummary(Some(now)))
          true
        } else {
          false
        }
      case None =>
        val resourceHealth =
          new ResourceHealth(resource, observeSliceletReadiness)
        resourceHealth.update(expiryTime, resource, healthStatus)
        healthByUuid.put(resourceUuid, resourceHealth)
        healthByExpiry.push(resourceHealth)
        logger.info(s"Health status for '$resourceUuid' initialized to $healthStatus")
        logger.debug(makeHealthSummary(Some(now)))
        true
    })
  }

  /**
   * Returns a human-readable summary of the health of each resource known to this watcher.
   *
   * If `nowOpt` is defined, then the summary includes the current time and the relative offset
   * for each expiry time.
   */
  private def makeHealthSummary(nowOpt: Option[TickerTime]): String = {
    val builder: StringBuilder = new StringBuilder
    val nowString: String = nowOpt match {
      case Some(now: TickerTime) => s"(now: $now)"
      case None => ""
    }
    builder.append(s"Health summary $nowString:\n")
    val table = new AsciiTable(Header("Resource"), Header("Health"), Header("Expiry Time"))
    // We expect generally few resources in each managed target and include each one in the
    // summary. If that's no longer true one day, we can reduce the bulk by filtering the table to
    // show only some subset of the resources (perhaps the X most near expiry, and the Y furthest
    // from expiry).
    for (expiryEntry: ResourceHealth <- healthByExpiry.unorderedIterator()) {
      val expiryTime: TickerTime = expiryEntry.expiryTime
      val healthStatus: HealthStatus = expiryEntry.getComputedHealthStatus
      val uuid: UUID = expiryEntry.getResourceUuid
      val formattedOffset: String = nowOpt match {
        case Some(now: TickerTime) =>
          val expiryOffset: FiniteDuration = expiryTime - now
          // Seconds with a single decimal point.
          f"(now + ${expiryOffset.toMillis / 1000.0}%.1f)"
        case None => ""
      }
      table.appendRow(
        uuid.toString,
        healthStatus.toString,
        s"$expiryTime $formattedOffset)"
      )
    }
    table.appendTo(builder)
    builder.toString
  }

  private[assigner] object forTest {

    /** Checks the invariants of HealthWatcher. */
    @throws[AssertionError]("If HealthWatcher has any invariant broken.")
    def checkInvariants(now: TickerTime): Unit = {
      healthByExpiry.forTest.checkInvariants()

      // `healthByUuid` is well-formed.
      var resourceCountInMap: Int = 0
      for (uuidWithHealth <- healthByUuid) {
        val (uuid, resourceHealth): (UUID, ResourceHealth) = uuidWithHealth
        iassert(resourceHealth.getResourceUuid == uuid)
        resourceCountInMap += 1
      }

      // `healthByUuid` and `healthByExpiry` are kept in sync.
      iassert(resourceCountInMap == healthByExpiry.size)
      for (resourceHealthInHeap: ResourceHealth <- healthByExpiry.forTest.copyContents()) {
        val resourceHealthInMap: ResourceHealth = healthByUuid(resourceHealthInHeap.getResourceUuid)
        iassert(resourceHealthInHeap.eq(resourceHealthInMap))
        iassert(resourceHealthInHeap.expiryTime > now)
      }

      state match {
        case starting: State.Starting =>
          // Initial health delay not passed.
          iassert(starting.startTime + config.unhealthyTimeoutPeriod > now)
        case _ =>
          ()
      }
    }
  }
}
