package com.databricks.dicer.assigner

import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._
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
import com.databricks.dicer.assigner.HealthWatcher.{
  ALL_STATUS_NAMES,
  DriverAction,
  Event,
  HealthStatus,
  ResourceIncarnationHealth,
  State,
  StaticConfig,
  isReliableSource
}
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.assigner.conf.HealthConf
import com.databricks.dicer.common.{Assignment, Generation, SliceletState}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.Squid

/**
 * The health watcher is a state machine which tracks the health status of resources based on
 * various environmental signals and informs the caller of the current set of healthy resources
 * whenever they change.
 *
 * Upon being supplied with an event or advancement, the health watcher will inform the caller about
 * a health report (see [[DriverAction]]) of currently tracked resources if there's any change
 * (resource status transition, resource removed, resource added) caused by the event. The caller
 * will also be told the next time when there will be a change of resource status, and the caller is
 * expected to query the health watcher again no later than that time so it can promptly observe any
 * resource status change. The HealthWatcher is implemented as a state machine so that it can easily
 * compose with its parent state machine, AssignmentGenerator, and is effectively driven by the
 * AssignmentGeneratorDriver. See [[StateMachine]] documentation for additional background on the
 * driver/state machine pattern.
 *
 * Initial health delay: Typically when the health watcher is informed about a resource in Running
 * state (by [[Event.SliceletStateFromSlicelet]]), the resource will be incorporated in the health
 * report immediately. However during an initial delay of
 * [[HealthWatcher.StaticConfig.initialHealthReportDelayPeriod]] after the health watcher is
 * started, it will just silently track the resources without generating any health report. A health
 * report containing all resources tracked during this time will be generated upon the initial
 * health delay period is passed. This is because in practice when an assigner has just started, we
 * would like to collect a sufficient set of resources before generating the first assignment.
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
 * [[Event.SliceletStateFromSlicelet]]). Here "reliable" means that we believe the source is highly
 * likely to know an assignment fresh enough such that it doesn't contain any dead resources, so
 * that the initial health report (and initial assignment) will not be polluted by the stale
 * resources. Intuitively, if we know an assignment generation from a reliable source, we can safely
 * assume assignments with even higher generations are also reliable and fresh (or even fresher), so
 * the health watcher will actually incorporate the assigned resources with the highest generation
 * it observes when bootstrapping the health.
 *
 * Currently we only consider slicelets as reliable sources of assignment, as slicelets are
 * frequently synchronizing assignments with both clerks and assigners so there's a high chance that
 * they know assignments fresh enough. Other sources, e.g. assignments from an in-memory store, are
 * not always a reliable source. For example, when a preferred assigner using in-memory store
 * becomes a stand-by assigner, the assignment in its in-memory store will remain. And if this
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

  import com.databricks.dicer.assigner.HealthWatcher.HealthStatus.IncarnationStatus

  /**
   * REQUIRES: `initialHealthReportDelayPeriod` is positive
   * REQUIRES: `unhealthyTimeoutPeriod` is positive
   * REQUIRES: `terminatingRetentionWindow` is positive
   * REQUIRES: `notReadyTimeoutPeriod` is positive
   *
   * Static configuration (i.e., based on DbConf; does not vary per target) for the HealthWatcher.
   * Per-target configuration is in [[HealthWatcherTargetConfig]].
   *
   * @param initialHealthReportDelayPeriod the delay before the first health report is emitted.
   *                                       Note that in the absence of observing an initial set
   *                                       of assigned resources with which to bootstrap health
   *                                       status, this delay is used to ensure the watcher has
   *                                       collected sufficient signals before emitting its first
   *                                       health report.
   *                                       TODO(<internal bug>): When Dicer has an authoritative
   *                                       assignment store, this timer-based initial delay period
   *                                       can be removed, since the watcher can always learn a
   *                                       recent assignment state (at least no staler than its
   *                                       start time), most importantly including the case of "no
   *                                       assignment". Without an authoritative store, the initial
   *                                       delay must be preserved, since the watcher cannot
   *                                       distinguish between "no assignment" and "no assignment
   *                                       yet observed".
   * @param unhealthyTimeoutPeriod   when a heartbeat is not received from a resource within this
   *                                 period, it is assumed to be unhealthy.
   * @param terminatingRetentionWindow we remove the incarnation health for a pod whose Slicelet
   *                                 reports Terminating after this period, since the pod is no
   *                                 longer active. This does not expire cluster-manager termination
   *                                 information, which lasts until the manager reports its removal.
   *                                 See the documentation
   *                                 [[HealthConf.terminatingRetentionWindow]] for more details.
   * @param notReadyTimeoutPeriod    flapping protection timeout for the NotReady state. A resource
   *                                 in NotReady will not be allowed to transition to Running until
   *                                 it has not received a NotReady heartbeat for at least this
   *                                 duration; each NOT_READY heartbeat while in NotReady status
   *                                 resets this timer.
   */
  case class StaticConfig(
      initialHealthReportDelayPeriod: FiniteDuration,
      unhealthyTimeoutPeriod: FiniteDuration,
      terminatingRetentionWindow: FiniteDuration,
      notReadyTimeoutPeriod: FiniteDuration) {
    require(initialHealthReportDelayPeriod > Duration.Zero)
    require(unhealthyTimeoutPeriod > Duration.Zero)
    require(terminatingRetentionWindow > Duration.Zero)
    require(notReadyTimeoutPeriod > Duration.Zero)
  }

  /** Input events to the health watcher state machine. */
  sealed trait Event
  object Event {

    /**
     * Informs the health watcher of a state update from a Slicelet. Slicelet state updates include
     * the Slicelet's full Squid.
     */
    case class SliceletStateFromSlicelet(resource: Squid, sliceletState: SliceletState)
        extends Event

    /**
     * Informs the health watcher of the UUIDs of all resources which the cluster manager is
     * terminating.
     */
    case class InformClusterManagerResources(terminatingUuids: Set[UUID]) extends Event

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

    /**
     * Informs the driver of the current set of assignable resources and the number of resources
     * that have crashed since the last report.
     *
     * Because health reports may be delayed or dropped, consumers of health reports cannot
     * reliably compute [[newlyCrashedCount]] by diffing consecutive health reports. Thus we
     * compute the number of crashed resources in the [[HealthWatcher]].
     *
     * @param healthy       the current set of [[Squid]]s with health status computed as `Running`,
     *                      eligible for assignment.
     * @param newlyCrashedCount the number of [[Squid]]s that were healthy in the previous report
     *                          but whose heartbeat has since expired.
     */
    case class HealthReportReady(healthy: Set[Squid], newlyCrashedCount: Int) extends DriverAction
  }

  /** Factory methods for [[StaticConfig]]. */
  object StaticConfig {

    /** Creates a [[StaticConfig]] derived from [[HealthConf]]. */
    def fromConf(conf: HealthConf): StaticConfig = {
      StaticConfig(
        conf.initialHealthReportDelayPeriod,
        conf.unhealthyTimeoutPeriod,
        conf.terminatingRetentionWindow,
        conf.notReadyTimeoutPeriod
      )
    }
  }

  /**
   * Resource health statuses perceived by the HealthWatcher.
   *
   * In our model, resources have "incarnations". An incarnation is an instance of the resource, and
   * while a resource may have multiple incarnations, only one may exist at any time.
   *
   * Slicelet reports determine the latest incarnation's [[IncarnationStatus]].
   * A cluster-manager termination report overrides that status with
   * [[HealthStatus.ResourceTerminating]] for every incarnation of the resource.
   */
  private sealed trait HealthStatus {

    /** Canonical name used for metrics labels and logging. */
    def name: String

    /** Returns whether `other` is of the same type as this status. */
    final def isSameType(other: HealthStatus): Boolean = (this, other) match {
      case (
          _: IncarnationStatus.NotReady,
          _: IncarnationStatus.NotReady
          ) =>
        // Disregard `lastReportTime`.
        true
      case _ => this == other
    }

    override def toString: String = name
  }

  private object HealthStatus {

    /** Health status of a resource incarnation as computed from Slicelet reports. */
    sealed trait IncarnationStatus extends HealthStatus

    object IncarnationStatus {

      /**
       * Assigner-computed state for a brand-new resource whose first heartbeat was NOT_READY.
       * Unlike [[NotReady]], Starting carries no flapping protection timer: a RUNNING heartbeat
       * from a Starting resource transitions it immediately to Running.
       */
      case object Starting extends IncarnationStatus {
        override def name: String = "Starting"
      }

      /**
       * NotReady status, with flapping protection timeout.
       *
       * @param lastReportTime The time at which the resource last reported a NotReady heartbeat.
       */
      class NotReady(private[HealthWatcher] val lastReportTime: TickerTime)
          extends IncarnationStatus {
        override def name: String = "NotReady"

        override def toString: String = s"NotReady(lastReportTime=$lastReportTime)"
      }

      /** The resource is up and capable of doing work. */
      case object Running extends IncarnationStatus {
        override def name: String = "Running"
      }

      /**
       * The current resource incarnation is shutting down. Considered unhealthy, but the same
       * resource may become healthy again later with a new incarnation.
       */
      case object Terminating extends IncarnationStatus {
        override def name: String = "ResourceIncarnationTerminating"
      }
    }

    /** The cluster manager reports that the entire resource is terminating. */
    case object ResourceTerminating extends HealthStatus {
      override def name: String = "ResourceTerminating"
    }
  }

  /** Canonical names of all effective health statuses, used for metric label enumeration. */
  val ALL_STATUS_NAMES: Seq[String] = Seq(
    IncarnationStatus.Starting,
    new IncarnationStatus.NotReady(TickerTime.ofNanos(0)),
    IncarnationStatus.Running,
    IncarnationStatus.Terminating,
    HealthStatus.ResourceTerminating
  ).map((_: HealthStatus).name)

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

  /**
   * Tracks Slicelet-derived health for one resource incarnation.
   *
   * @param resource                 The resource incarnation tracked by this entry.
   * @param observeSliceletReadiness whether to use the Slicelet's reported readiness state to set
   *                                 its status in health reports, or mask its reported state to
   *                                 Running from the NOT_READY state. When true, a Running resource
   *                                 that reports NOT_READY moves to NotReady; when false it stays
   *                                 Running (masked).
   * @param config                   static configuration for the HealthWatcher.
   */
  private class ResourceIncarnationHealth(
      val resource: Squid,
      observeSliceletReadiness: Boolean,
      config: StaticConfig)
      extends IntrusiveMinHeapElement[TickerTime] {

    /** See [[getIncarnationStatus]]. See [[update]] for the state transition rules. */
    private var incarnationStatus: IncarnationStatus =
      IncarnationStatus.Starting

    /** The latest Slicelet report, if one has been received, retained for debugging. */
    private var latestSliceletStateOpt: Option[SliceletState] = None

    /** The first time we learned this resource is terminating from a Slicelet. */
    private var learnedTerminatingFromSliceletTimeOpt: Option[TickerTime] = None

    /**
     * Updates this resource incarnation from a Slicelet report.
     *
     * @param now           the current time.
     * @param sliceletState the state reported by the Slicelet.
     * @return true if there is a change in health status of the resource.
     */
    def update(now: TickerTime, sliceletState: SliceletState): Boolean = {
      if (sliceletState == SliceletState.Terminating &&
        this.learnedTerminatingFromSliceletTimeOpt.isEmpty) {
        this.learnedTerminatingFromSliceletTimeOpt = Some(now)
      }

      this.latestSliceletStateOpt = Some(sliceletState)

      this.incarnationStatus match {
        case IncarnationStatus.Running | IncarnationStatus.Starting |
            _: IncarnationStatus.NotReady =>
          val expiryTime: TickerTime = sliceletState match {
            case SliceletState.Terminating =>
              now + this.config.terminatingRetentionWindow
            case _ => now + this.config.unhealthyTimeoutPeriod
          }
          setPriority(expiryTime)

        // Avoid calling `setPriority` if the resource is already terminating, which would cause the
        // expiry time to go backwards if we got an out-of-order non-terminating signal.
        case IncarnationStatus.Terminating =>
      }

      val newStatus: IncarnationStatus =
        (this.incarnationStatus, sliceletState) match {
          case (_, SliceletState.Terminating) =>
            IncarnationStatus.Terminating

          // `Starting` is the initial state for every new resource incarnation.
          // `SliceletState.NotReady` reports keep the resource in `Starting`, while a
          // `SliceletState.Running` report transitions immediately to `Running`.
          case (IncarnationStatus.Starting, SliceletState.NotReady) =>
            IncarnationStatus.Starting

          case (IncarnationStatus.Starting, SliceletState.Running) =>
            IncarnationStatus.Running

          case (_: IncarnationStatus.NotReady, SliceletState.NotReady) =>
            // Heartbeat while in NotReady: extend the flapping protection timeout.
            new IncarnationStatus.NotReady(now)
          case (notReadyStatus: IncarnationStatus.NotReady, SliceletState.Running) =>
            // Only allow transition to Running if the NotReady flapping protection period has
            // elapsed. This prevents rapid Running <-> NotReady flapping when the Slicelet's
            // readiness is observed.
            if (now >= notReadyStatus.lastReportTime + this.config.notReadyTimeoutPeriod) {
              IncarnationStatus.Running
            } else {
              notReadyStatus
            }

          case (IncarnationStatus.Running, SliceletState.NotReady) =>
            // Transition to NotReady in response to a NOT_READY report. When the Slicelet's
            // readiness is not observed, this outcome is masked back to Running below.
            new IncarnationStatus.NotReady(now)
          case (IncarnationStatus.Running, SliceletState.Running) =>
            IncarnationStatus.Running

          // If the resource is already terminating, no signal about the resource incarnation
          // state changes our belief about its status. Note that this does not indicate that the
          // resource itself is terminating.
          case (IncarnationStatus.Terminating, _) =>
            IncarnationStatus.Terminating
        }

      // If configured, the HealthWatcher will mask the health status outcome of the state machine
      // with a different status. Namely, a NotReady or Starting outcome from the state machine is
      // masked to Running. This masking can be enabled for targets where the Slicelet readiness
      // probe may be unreliable (e.g., where a Slicelet never transitions out of NotReady). When
      // masking is active, the NotReady flapping protection is a no-op because
      // maskedNewStatus is never NotReady or Starting.
      val maskedNewStatus: IncarnationStatus = newStatus match {
        case (_: IncarnationStatus.NotReady) | IncarnationStatus.Starting
            if !this.observeSliceletReadiness =>
          IncarnationStatus.Running
        case _ =>
          newStatus
      }

      val changed: Boolean = !maskedNewStatus.isSameType(this.incarnationStatus)
      this.incarnationStatus = maskedNewStatus
      changed
    }

    /** Returns the incarnation health status computed by the [[HealthWatcher]]. */
    def getIncarnationStatus: IncarnationStatus = this.incarnationStatus

    /** Returns the latest Slicelet report, if one has been received, for debugging. */
    def getLatestSliceletStateOpt: Option[SliceletState] = this.latestSliceletStateOpt

    /** Returns the first time we learned this resource is terminating from a Slicelet, if any. */
    def getLearnedTerminatingFromSliceletTimeOpt: Option[TickerTime] =
      this.learnedTerminatingFromSliceletTimeOpt

    def expiryTime: TickerTime = getPriority

    /** Removes this entry before replacing it with a newer incarnation. */
    def removeFromHeap(): Unit = remove()

    override def toString: String =
      s"${this.resource} => ${this.incarnationStatus}, expiryTime=$expiryTime"
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

  import com.databricks.dicer.assigner.HealthWatcher.HealthStatus.IncarnationStatus

  type Output = StateMachineOutput[DriverAction]

  private val logger = PrefixLogger.create(
    this.getClass,
    s"${target.getLoggerPrefix}"
  )

  private val observeSliceletReadiness: Boolean =
    healthWatcherTargetConfig.observeSliceletReadiness

  /**
   * The health status of the latest known incarnation of each resource, keyed by the resource UUID.
   */
  private val incarnationHealthByUuid = new mutable.HashMap[UUID, ResourceIncarnationHealth]

  /** The health status of each resource, prioritized by minimum `expiryTime`. */
  private val incarnationHealthByExpiry =
    new IntrusiveMinHeap[TickerTime, ResourceIncarnationHealth]

  /**
   * The resource UUIDs the cluster manager is attempt to terminate. The value for each entry
   * is the time at which we were first informed of this from the cluster manager, retained just for
   * monitoring and debugging.
   *
   * There are no invariants between the resources tracked here and the incarnations tracked above.
   * Information from the cluster manager is treated as an optimization, so a HealthWatcher will
   * emit health reports even if it hasn't received anything from the cluster manager. This means
   * it's possible for a resource to be excluded from the assignment due to a cluster manager
   * report, and then become re-added if a new Assigner takes over the target but fails to hear from
   * the cluster manager while that resource is alive and its Slicelet is delivering heartbeats.
   */
  private var clusterManagerTerminatingResources: Map[UUID, TickerTime] = Map.empty

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
  private var dirty: Boolean = false

  override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {
    event match {
      case Event.SliceletStateFromSlicelet(resource, sliceletState) =>
        informHealthStatus(tickerTime, resource, sliceletState)
      case Event.InformClusterManagerResources(terminatingUuids: Set[UUID]) =>
        setClusterManagerTerminatingResources(tickerTime, terminatingUuids)
      case assignmentSyncObserved: Event.AssignmentSyncObserved =>
        handleAssignmentSyncObserved(assignmentSyncObserved)
    }
    onAdvance(tickerTime, instant)
  }

  override def onAdvance(tickerTime: TickerTime, instant: Instant): Output = {
    // Count Running resources whose heartbeats expired in this round. Reset each call since
    // crashes are consumed in the same onAdvance invocation that detects them.
    var newlyCrashedCount: Int = 0

    // Remove any expired resources from the health map. Note: onAdvance is the only place
    // where resources are expired, by design, so that expiry events are reliably turned into
    // crash events without races with status updates.
    while (incarnationHealthByExpiry.peek.exists { health: ResourceIncarnationHealth =>
        health.expiryTime <= tickerTime
      }) {
      dirty = true
      val resourceHealth: ResourceIncarnationHealth = incarnationHealthByExpiry.pop()
      incarnationHealthByUuid.remove(resourceHealth.resource.resourceUuid)
      // A Running resource whose heartbeat expired is considered crashed (i.e. the last thing we
      // knew about this resource was that it was running, but we haven't received any heartbeats
      // in so long that it is now considered expired). A resource that expires from the NotReady
      // state is not considered crashed: such a resource has already been removed from the
      // assignment and is no longer manipulating keys, so its expiry is unlikely to be caused by
      // a key of death.
      if (getHealthStatus(resourceHealth) == IncarnationStatus.Running) {
        newlyCrashedCount += 1
      }
      TargetMetrics.recordHealthWatcherResourceExpirationStats(
        target,
        resourceHealth.getLearnedTerminatingFromSliceletTimeOpt,
        clusterManagerTerminatingResources.get(resourceHealth.resource.resourceUuid)
      )
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
        val initialHealthReportDelay: TickerTime =
          startTime + config.initialHealthReportDelayPeriod
        if (tickerTime >= initialHealthReportDelay) {
          // We've waited out the initial health report delay period without learning a set of
          // assigned resources. By this point we've collected sufficient signals anyway to be
          // reasonably confident in a comprehensive health report to emit.
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
              // into `incarnationHealthByUuid` and transitioning the State into Started, so the
              // HealthWatcher
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
              logger.info(
                s"Bootstrapping health from assignment generation $highestReliableGeneration."
              )
              for (entry <- assignedResourcesByUuid) {
                val (uuid, squid): (UUID, Squid) = entry
                if (!incarnationHealthByUuid.contains(uuid)) {
                  // Treat each bootstrapped assignment resource as a Slicelet reporting Running:
                  // these resources are considered last known healthy as of the watcher start time.
                  informHealthStatus(startTime, squid, SliceletState.Running)
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
        val nextExpiryTime =
          incarnationHealthByExpiry.peek.map(_.expiryTime).getOrElse(TickerTime.MAX)
        if (dirty) {
          dirty = false

          val healthy: Set[Squid] = computeHealthySet()

          StateMachineOutput(
            nextExpiryTime,
            Seq(
              DriverAction
                .HealthReportReady(healthy = healthy, newlyCrashedCount = newlyCrashedCount)
            )
          )
        } else {
          StateMachineOutput(nextExpiryTime, Seq.empty)
        }
    }
  }

  override def toString: String = makeHealthSummary(nowOpt = None)

  /**
   * Iterates over all tracked resources, records per-status metrics, and computes the set of
   * [[Squid]]s that are currently healthy (Running).
   *
   * Cluster-manager termination is applied here as a final mask over incarnation health.
   */
  private def computeHealthySet(): Set[Squid] = {
    // Store the resource counts per status name for metrics.
    val resourcesPerStatusReported: mutable.Map[String, Int] = mutable.Map.empty
    val resourcesPerStatusComputed: mutable.Map[String, Int] = mutable.Map.empty

    // Collect the resources whose computed status differs from its last reported incarnation
    // state. Not all types of mismatches indicate a problem. For example, we expect a mismatch
    // if the cluster manager informs us that the entire resource is terminating (which we cannot
    // discover from a Slicelet alone). We also expect a mismatch if a Slicelet's reports flap
    // between Running and NotReady while the computed status remains NotReady.
    // Each tuple contains the resource, reported state name, and computed status name.
    val mismatches: mutable.Buffer[(Squid, String, String)] = mutable.Buffer.empty

    val healthy: mutable.Set[Squid] = mutable.Set.empty
    for (entry <- incarnationHealthByUuid) {
      val (_, resourceHealth): (UUID, ResourceIncarnationHealth) = entry
      val reportedStatusName: String = resourceHealth.getLatestSliceletStateOpt match {
        case Some(SliceletState.NotReady) => "NotReady"
        case Some(SliceletState.Running) => IncarnationStatus.Running.name
        case Some(SliceletState.Terminating) =>
          IncarnationStatus.Terminating.name
        case None => IncarnationStatus.Starting.name
      }
      val status: HealthStatus = getHealthStatus(resourceHealth)
      val statusName: String = status.name

      // Count states last reported by Slicelets and HealthWatcher-computed statuses.
      resourcesPerStatusReported(reportedStatusName) =
        resourcesPerStatusReported.getOrElse(reportedStatusName, 0) + 1
      resourcesPerStatusComputed(statusName) =
        resourcesPerStatusComputed.getOrElse(statusName, 0) + 1

      // Record if the unmasked state reported by Slicelet differs from the computed
      // status.
      // Note: a resource with Starting status will always have a mismatch, since Starting
      // is a HealthWatcher-assigned status with no corresponding SliceletState.
      val squid: Squid = resourceHealth.resource
      if (reportedStatusName != statusName) {
        mismatches += ((squid, reportedStatusName, statusName))
        logger.info(
          s"HealthStatus mask applied: reported $reportedStatusName, " +
          s"computed as $statusName",
          every = 10.seconds
        )
      }

      if (status == IncarnationStatus.Running) {
        healthy += squid
      }
    }
    val allResources: Iterable[Squid] =
      incarnationHealthByUuid.values.map((_: ResourceIncarnationHealth).resource)
    TargetMetrics.incrementHealthStatusComputedDiffersFromReported(
      target,
      allResources = allResources,
      mismatches = mismatches
    )

    // Report every canonical label so statuses absent from this report are reset to zero.
    for (statusName: String <- ALL_STATUS_NAMES) {
      TargetMetrics.setPodSetSize(
        target,
        statusName = statusName,
        reportedCount = resourcesPerStatusReported.getOrElse(statusName, 0),
        computedCount = resourcesPerStatusComputed.getOrElse(statusName, 0)
      )
    }

    healthy.toSet
  }

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
   * Updates the terminating resources known by the cluster manager to `terminatingUuids`, retaining
   * the time each resource was first reported terminating while it remains in the set.
   */
  private def setClusterManagerTerminatingResources(
      now: TickerTime,
      terminatingUuids: Set[UUID]): Unit = {
    if (terminatingUuids == this.clusterManagerTerminatingResources.keySet) {
      return
    }
    val removedUuids: Set[UUID] =
      clusterManagerTerminatingResources.keySet -- terminatingUuids

    for (resourceUuid: UUID <- removedUuids) {
      logger.info(
        s"Cluster manager no longer reports resource '$resourceUuid' as terminating at $now"
      )
    }

    // Retain the timestamp at which we first learned about the terminating resource for
    // monitoring. The state machine does not guarantee monotonic ticker readings, so `now` may be
    // earlier than a retained timestamp, which is fine.
    val newTerminatingResources = Map.newBuilder[UUID, TickerTime]
    for (resourceUuid: UUID <- terminatingUuids) {
      val knownTerminatingTimeOpt: Option[TickerTime] =
        clusterManagerTerminatingResources.get(resourceUuid)
      val terminatingTime: TickerTime = knownTerminatingTimeOpt match {
        case Some(knownTerminatingTime: TickerTime) =>
          knownTerminatingTime
        case None =>
          logger.info(s"Cluster manager reported resource '$resourceUuid' as terminating at $now")
          now
      }
      newTerminatingResources += (resourceUuid -> terminatingTime)
    }
    clusterManagerTerminatingResources = newTerminatingResources.result()

    // Trigger a recomputation of the healthy set if it may have changed.
    this.dirty = true
  }

  /** Returns the health status after applying the cluster-manager resource-level mask. */
  private def getHealthStatus(resourceHealth: ResourceIncarnationHealth): HealthStatus = {
    if (clusterManagerTerminatingResources.contains(resourceHealth.resource.resourceUuid)) {
      HealthStatus.ResourceTerminating
    } else {
      resourceHealth.getIncarnationStatus
    }
  }

  /**
   * Informs the watcher of a Slicelet state report. Caller should subsequently call `onAdvance`.
   *
   * @param now          the current time.
   * @param resource     the resource incarnation reporting state.
   * @param sliceletState the state reported by the Slicelet.
   */
  private def informHealthStatus(
      now: TickerTime,
      resource: Squid,
      sliceletState: SliceletState
  ): Unit = {
    val resourceUuid: UUID = resource.resourceUuid

    // Just update the known incarnation health if one exists and the report is for the same
    // incarnation.
    val existingHealthOpt: Option[ResourceIncarnationHealth] =
      incarnationHealthByUuid.get(resourceUuid) match {
        case Some(existingHealth: ResourceIncarnationHealth) =>
          // We order incarnations of the same resource by their creation time, which is
          // wall-clock based. This is unideal, but container restarts take so long in practice that
          // newer incarnations will usually have higher creation times.
          val incarnationComparison: Int =
            resource.creationTime.compareTo(existingHealth.resource.creationTime)
          if (incarnationComparison == 0) {
            // A pod keeps its address for its lifetime, and a container restart changes the
            // creation time. Retain the original address for the same UUID and creation time.
            Some(existingHealth)
          } else if (incarnationComparison > 0) {
            // New incarnation exists for this resource, so discard the old one. We'll create a new
            // one below.
            incarnationHealthByUuid.remove(resourceUuid)
            existingHealth.removeFromHeap()
            None
          } else {
            // This is a signal for an old incarnation, so just ignore it.
            logger.info(
              s"Ignoring $sliceletState signal from old incarnation (current incarnation: " +
              s"${existingHealth.resource}, old incarnation: $resource)"
            )
            return
          }
        case None =>
          // Unknown resource, create a new one.
          None
      }

    // Get the incarnation health to update and whether the incarnation changed (which is
    // trivially true when the entire resource is new).
    val (incarnationHealth, incarnationChanged): (ResourceIncarnationHealth, Boolean) =
      existingHealthOpt match {
        case Some(existingHealth: ResourceIncarnationHealth) =>
          (existingHealth, false)
        case None =>
          val newIncarnationHealth =
            new ResourceIncarnationHealth(resource, observeSliceletReadiness, config)
          (newIncarnationHealth, true)
      }

    val previousStatus: IncarnationStatus = incarnationHealth.getIncarnationStatus
    val incarnationStatusChanged: Boolean = incarnationHealth.update(now, sliceletState)
    val changed: Boolean = incarnationChanged || incarnationStatusChanged
    dirty |= changed

    if (incarnationChanged) {
      logger.info(
        s"Health status for new resource incarnation '$resource' initialized to " +
        s"${incarnationHealth.getIncarnationStatus}"
      )

      // Add the new incarnation to our tracking data structures. The `update` above sets the heap
      // priority before adding it to the heap (reversing the order would be safe but cost an
      // extra heapify).
      incarnationHealthByExpiry.push(incarnationHealth)
      incarnationHealthByUuid.put(resourceUuid, incarnationHealth)
    } else if (incarnationStatusChanged) {
      logger.info(
        s"Health status for resource '$resource' changed from $previousStatus to " +
        s"${incarnationHealth.getIncarnationStatus}"
      )
    }

    if (changed) {
      logger.debug(makeHealthSummary(Some(now)))
    }
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
    for (expiryEntry: ResourceIncarnationHealth <- incarnationHealthByExpiry.unorderedIterator()) {
      val expiryTime: TickerTime = expiryEntry.expiryTime
      val healthStatusName: String = getHealthStatus(expiryEntry).name
      val uuid: UUID = expiryEntry.resource.resourceUuid
      val formattedOffset: String = nowOpt match {
        case Some(now: TickerTime) =>
          val expiryOffset: FiniteDuration = expiryTime - now
          // Seconds with a single decimal point.
          f"(now + ${expiryOffset.toMillis / 1000.0}%.1f)"
        case None => ""
      }
      table.appendRow(
        uuid.toString,
        healthStatusName,
        s"$expiryTime $formattedOffset"
      )
    }
    table.appendTo(builder)
    builder.toString
  }

  private[assigner] object forTest {

    /** Checks the invariants of HealthWatcher. */
    @throws[AssertionError]("If HealthWatcher has any invariant broken.")
    def checkInvariants(now: TickerTime): Unit = {
      incarnationHealthByExpiry.forTest.checkInvariants()

      // `incarnationHealthByUuid` is well-formed.
      var resourceCountInMap: Int = 0
      for (uuidWithHealth <- incarnationHealthByUuid) {
        val (uuid, resourceIncarnationHealth): (UUID, ResourceIncarnationHealth) = uuidWithHealth
        iassert(resourceIncarnationHealth.resource.resourceUuid == uuid)
        resourceCountInMap += 1
      }

      // `incarnationHealthByUuid` and `incarnationHealthByExpiry` are kept in sync.
      iassert(resourceCountInMap == incarnationHealthByExpiry.size)
      for (resourceHealthInHeap: ResourceIncarnationHealth <- incarnationHealthByExpiry.forTest
          .copyContents()) {
        val resourceHealthInMap: ResourceIncarnationHealth =
          incarnationHealthByUuid(resourceHealthInHeap.resource.resourceUuid)
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
