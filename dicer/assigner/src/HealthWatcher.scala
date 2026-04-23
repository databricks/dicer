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
  DriverAction,
  Event,
  HealthStatus,
  ResourceHealth,
  HealthStatusSource,
  State,
  StaticConfig,
  isReliableSource
}
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource.AssignmentDistributionSource
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

  /**
   * REQUIRES: `initialHealthReportDelayPeriod` is positive
   * REQUIRES: `unhealthyTimeoutPeriod` is positive
   * REQUIRES: `terminatingTimeoutPeriod` is positive
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
   * @param terminatingTimeoutPeriod we remove the HealthStatus for a pod in Terminating state after
   *                                 this period, since the pod is no longer active. See the
   *                                 documentation [[HealthConf.terminatingTimeoutPeriod]] for more
   *                                 details.
   * @param notReadyTimeoutPeriod    flapping protection timeout for the NotReady state. A resource
   *                                 in NotReady will not be allowed to transition to Running until
   *                                 it has not received a NotReady heartbeat for at least this
   *                                 duration; each NOT_READY heartbeat while in NotReady status
   *                                 resets this timer.
   */
  case class StaticConfig(
      initialHealthReportDelayPeriod: FiniteDuration,
      unhealthyTimeoutPeriod: FiniteDuration,
      terminatingTimeoutPeriod: FiniteDuration,
      notReadyTimeoutPeriod: FiniteDuration) {
    require(initialHealthReportDelayPeriod > Duration.Zero)
    require(unhealthyTimeoutPeriod > Duration.Zero)
    require(terminatingTimeoutPeriod > Duration.Zero)
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
     * Informs the health watcher of a Slicelet state update from Kubernetes. Kubernetes state
     * updates only include the resource's UUID.
     */
    case class SliceletStateFromKubernetes(resourceUUID: UUID, sliceletState: SliceletState)
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
        conf.terminatingTimeoutPeriod,
        conf.notReadyTimeoutPeriod
      )
    }
  }

  /**
   * The health status of a resource, as computed by the [[HealthWatcher]].
   *
   * [[NotReady]] embeds the flapping-protection timeout; all other states are singletons.
   */
  private sealed trait HealthStatus {

    /** Canonical name used for metrics labels and logging. */
    def statusName: String

  }

  private object HealthStatus {

    /**
     * Assigner-computed state for a brand-new resource whose first heartbeat was NOT_READY.
     * Unlike [[NotReady]], Starting carries no flapping protection timer: a RUNNING heartbeat
     * from a Starting resource transitions it immediately to Running.
     */
    case object Starting extends HealthStatus {
      override def statusName: String = "Starting"
    }

    /**
     * NotReady status, with flapping protection timeout.
     *
     * Two [[NotReady]] instances compare equal (via [[equals]]) regardless of their
     * [[lastReportTime]] values. This makes it possible to test whether a status is [[NotReady]]
     * using `==` without caring about the specific timeout value. All [[NotReady]] instances
     * share the same [[hashCode]] for consistency with [[equals]].
     *
     * @param lastReportTime The time at which the resource last reported a NotReady heartbeat.
     */
    class NotReady(private[HealthWatcher] val lastReportTime: TickerTime) extends HealthStatus {
      override def statusName: String = "NotReady"

      /** Returns true if `other` is a [[NotReady]] instance, regardless of [[lastReportTime]]. */
      override def equals(other: Any): Boolean = other match {
        case _: NotReady => true
        case _ => false
      }

      /** Hash on the class so all instances return the same hashCode. */
      override def hashCode(): Int = classOf[NotReady].hashCode()

      override def toString: String = s"NotReady(lastReportTime=$lastReportTime)"
    }

    case object Running extends HealthStatus {
      override def statusName: String = "Running"
    }

    case object Terminating extends HealthStatus {
      override def statusName: String = "Terminating"
    }

    /**
     * Canonical instances of all [[HealthStatus]] kinds, used for metric label enumeration.
     * A [[NotReady]] sentinel represents the NotReady status. Map lookups against this set work
     * correctly because [[NotReady.equals]] ignores [[NotReady.lastReportTime]].
     */
    val values: Set[HealthStatus] =
      Set(Starting, new NotReady(TickerTime.ofNanos(0)), Running, Terminating)

  }

  /** Canonical names of all [[HealthStatus]] variants, used for metric label enumeration. */
  val ALL_STATUS_NAMES: Seq[String] = HealthStatus.values.toSeq.map(_.statusName)

  /** The source of a health status signal. Used for metrics. */
  sealed trait HealthStatusSource
  object HealthStatusSource {

    /** Health status bootstrapped from a previously observed assignment during startup. */
    case object BootstrappingAssignment extends HealthStatusSource

    /** Health status reported directly by a Slicelet via its heartbeat. */
    case object Slicelet extends HealthStatusSource

    /** Health status reported by the Kubernetes pod watcher. */
    case object Kubernetes extends HealthStatusSource
  }

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
   * An object to keep track of health status related information for a resource. `expiryTime` is
   * the time at which the HealthStatus will be removed.
   *
   * The [[HealthWatcher]] may compute a different health status than what the resource
   * reported. For example, a resource that reported Terminating once should always be
   * considered Terminating by the Assigner, since transitions from Terminating to any other
   * state are not allowed in the Slicelet state machine. The resource's reported status is
   * stored only for visibility into the [[HealthWatcher]]'s decisions. Callers should always
   * use the computed health status.
   *
   * @param resource                 A SQUID or UUID identifying the resource.
   * @param observeSliceletReadiness whether to use the Slicelet's reported readiness state to set
   *                                 its status in health reports, or mask its reported state to
   *                                 Running from the NOT_READY state.
   * @param permitRunningToNotReady  whether to allow the Running -> NotReady transition when the
   *                                 Slicelet reports NOT_READY. When false, NOT_READY reports from
   *                                 Running resources are ignored.
   * @param config                   static configuration for the HealthWatcher.
   */
  private class ResourceHealth(
      private var resource: Either[Squid, UUID],
      observeSliceletReadiness: Boolean,
      permitRunningToNotReady: Boolean,
      config: StaticConfig)
      extends IntrusiveMinHeapElement[TickerTime] {

    // The HealthWatcher may compute a different status than what the Slicelet reported. E.g., a
    // Slicelet that reported Terminating once should always be considered Terminating by the
    // Assigner, since transitions from Terminating to any other state are not allowed in the
    // Slicelet state machine. See [[updateHealthStatus]] for allowed transitions. We store the
    // Slicelet's reported status only for visibility into the HealthWatcher's decisions. Callers
    // should always use the computed health status.
    private var sliceletReportedHealthStatus: HealthStatus = HealthStatus.Starting
    private var computedStatus: HealthStatus = HealthStatus.Starting

    /** The first time we learned this resource is terminating from a Slicelet. */
    private var learnedTerminatingFromSliceletTimeOpt: Option[TickerTime] = None

    /** The first time we learned this resource is terminating from Kubernetes. */
    private var learnedTerminatingFromKubernetesTimeOpt: Option[TickerTime] = None

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
     * Update the resource with a potentially new health status. This method also updates the
     * resource's expiry time.
     *
     * @param now          the current time.
     * @param newResource  the SQUID or UUID identifying the resource in this update.
     * @param sliceletState the health state reported by the source.
     * @param source       where this health status update originated from.
     * @return true if there is a change in health status of the resource.
     */
    def update(
        now: TickerTime,
        newResource: Either[Squid, UUID],
        sliceletState: SliceletState,
        source: HealthStatusSource): Boolean = {
      // Record the first time we learn this resource is terminating from either Slicelet or
      // Kubernetes (we don't expect to hear a terminating signal due to a health bootstrapping
      // assignment, but in any case we just ignore it).
      //
      // Note that health signals (including termination signals) apply to resources by UUID in the
      // HealthWatcher, and thus, updates to the SQUID for a resource identified by UUID does not
      // affect termination signal tracking.
      if (sliceletState == SliceletState.Terminating) {
        source match {
          case HealthStatusSource.Slicelet =>
            if (learnedTerminatingFromSliceletTimeOpt.isEmpty) {
              learnedTerminatingFromSliceletTimeOpt = Some(now)
            }
          case HealthStatusSource.Kubernetes =>
            if (learnedTerminatingFromKubernetesTimeOpt.isEmpty) {
              learnedTerminatingFromKubernetesTimeOpt = Some(now)
            }
          case HealthStatusSource.BootstrappingAssignment => ()
        }
      }

      (resource, newResource) match {
        case (Left(oldSquid), Left(newSquid)) =>
          // We already have a SQUID, we should only update the health status if the SQUID is the
          // same or more recent.
          if (newSquid.creationTime.compareTo(oldSquid.creationTime) >= 0) {
            resource = Left(newSquid)
            // If the SQUID has changed, even if health status has not, we should report change in
            // health.
            updateHealthStatus(sliceletState, now) || newSquid != oldSquid
          } else {
            false
          }

        case (Left(_), Right(_)) =>
          // We already have a SQUID, the new update does not have a SQUID, so the resource remains
          // the same.
          updateHealthStatus(sliceletState, now)

        case (Right(_), Left(squid)) =>
          // The HealthStatus now has a SQUID, we assume the SQUID represents the most recent
          // incarnation of the resource.
          resource = Left(squid)
          updateHealthStatus(sliceletState, now)

          // We unconditionally report a change here, since we have a new SQUID.
          true
        case (Right(_), Right(_)) =>
          // The HealthStatus still does not have a SQUID. The resource remains same.
          updateHealthStatus(sliceletState, now)
      }
    }

    /**
     * Returns the [[HealthWatcher]]-computed health status. See class doc for why the computed
     * health status may differ from the Slicelet's reported state.
     */
    def getComputedHealthStatus: HealthStatus = computedStatus

    def getReportedSliceletHealthStatus: HealthStatus = sliceletReportedHealthStatus

    /** Returns the first time we learned this resource is terminating from a Slicelet, if any. */
    def getLearnedTerminatingFromSliceletTimeOpt: Option[TickerTime] =
      learnedTerminatingFromSliceletTimeOpt

    /** Returns the first time we learned this resource is terminating from Kubernetes, if any. */
    def getLearnedTerminatingFromKubernetesTimeOpt: Option[TickerTime] =
      learnedTerminatingFromKubernetesTimeOpt

    def expiryTime: TickerTime = getPriority

    override def toString: String =
      s"$resource => ${computedStatus.statusName}, expiryTime=$expiryTime"

    /**
     * Update the HealthStatus of the resource after being informed that the Slicelet is in
     * `sliceletState`. Updates to a Terminating resource are ignored.
     *
     * Return true if the status has been changed.
     */
    private def updateHealthStatus(sliceletState: SliceletState, now: TickerTime): Boolean = {
      sliceletReportedHealthStatus = sliceletState match {
        case SliceletState.NotReady => new HealthStatus.NotReady(now)
        case SliceletState.Running => HealthStatus.Running
        case SliceletState.Terminating => HealthStatus.Terminating
      }

      val expiryTime: TickerTime = sliceletState match {
        // We set the expiry to more than default termination grace period in Kubernetes, i.e., 30
        // seconds. The expiry here is set so that we do not keep terminated pods forever in
        // HealthWatcher.
        case SliceletState.Terminating => now + config.terminatingTimeoutPeriod
        case _ => now + config.unhealthyTimeoutPeriod
      }

      // If the current status is terminating, we don't need to update the expiry.
      if (computedStatus != HealthStatus.Terminating) {
        setPriority(expiryTime)
      }

      val newComputedStatus: HealthStatus =
        (computedStatus, sliceletState) match {
          // Starting is the initial state for every new resource. NOT_READY keeps the resource in
          // Starting (no flapping protection timer), so a subsequent RUNNING heartbeat transitions
          // immediately to Running.
          case (HealthStatus.Starting, SliceletState.NotReady) => HealthStatus.Starting
          case (HealthStatus.Starting, SliceletState.Running) => HealthStatus.Running
          case (HealthStatus.Starting, SliceletState.Terminating) => HealthStatus.Terminating

          case (_: HealthStatus.NotReady, SliceletState.NotReady) =>
            // Heartbeat while in NotReady: extend the flapping protection timeout.
            new HealthStatus.NotReady(now)
          case (notReadyStatus: HealthStatus.NotReady, SliceletState.Running) =>
            // Only allow transition to Running if the NotReady flapping protection period has
            // elapsed. This prevents rapid Running <-> NotReady flapping when
            // permitRunningToNotReady is enabled.
            if (now >= notReadyStatus.lastReportTime + config.notReadyTimeoutPeriod) {
              HealthStatus.Running
            } else {
              computedStatus
            }
          case (_: HealthStatus.NotReady, SliceletState.Terminating) => HealthStatus.Terminating

          case (HealthStatus.Running, SliceletState.NotReady) =>
            // Allow transition to NotReady only when `permitRunningToNotReady` is enabled.
            // When disabled, keep the existing behavior of ignoring NOT_READY reports for Running
            // resources.
            if (permitRunningToNotReady) {
              new HealthStatus.NotReady(now)
            } else {
              HealthStatus.Running
            }
          case (HealthStatus.Running, SliceletState.Running) => HealthStatus.Running
          case (HealthStatus.Running, SliceletState.Terminating) => HealthStatus.Terminating

          case (HealthStatus.Terminating, _) =>
            // No transitions out of `Terminating`.
            HealthStatus.Terminating

        }

      // If configured, the HealthWatcher will mask the health status outcome of the state machine
      // with a different status. Namely, a NotReady or Starting outcome from the state machine is
      // masked to Running. This masking can be enabled for targets where the Slicelet readiness
      // probe may be unreliable (e.g., where a Slicelet never transitions out of NotReady). When
      // masking is active, the NotReady flapping protection is a no-op because
      // maskedNewComputedStatus is never NotReady or Starting.
      val maskedNewComputedStatus: HealthStatus = newComputedStatus match {
        case (_: HealthStatus.NotReady) | HealthStatus.Starting if !observeSliceletReadiness =>
          HealthStatus.Running
        case _ =>
          newComputedStatus
      }

      val changed: Boolean = maskedNewComputedStatus != computedStatus
      computedStatus = maskedNewComputedStatus
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

  private val observeSliceletReadiness: Boolean =
    healthWatcherTargetConfig.observeSliceletReadiness

  private val permitRunningToNotReady: Boolean =
    healthWatcherTargetConfig.permitRunningToNotReady

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
  private var dirty: Boolean = false

  override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {
    event match {
      case Event.SliceletStateFromSlicelet(resource, sliceletState) =>
        informHealthStatus(tickerTime, Left(resource), sliceletState, HealthStatusSource.Slicelet)
      case Event.SliceletStateFromKubernetes(resourceUuid, sliceletState) =>
        informHealthStatus(
          tickerTime,
          Right(resourceUuid),
          sliceletState,
          HealthStatusSource.Kubernetes
        )
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
    while (healthByExpiry.peek.exists { health: ResourceHealth =>
        health.expiryTime <= tickerTime
      }) {
      dirty = true
      val resourceHealth: ResourceHealth = healthByExpiry.pop()
      healthByUuid.remove(resourceHealth.getResourceUuid)
      // A Running resource whose heartbeat expired is considered crashed (i.e. the last thing we
      // knew about this resource was that it was running, but we haven't received any heartbeats
      // in so long that it is now considered expired). A resource that expires from the NotReady
      // state is not considered crashed: such a resource has already been removed from the
      // assignment and is no longer manipulating keys, so its expiry is unlikely to be caused by
      // a key of death.
      if (resourceHealth.getComputedHealthStatus == HealthStatus.Running) {
        newlyCrashedCount += 1
      }
      TargetMetrics.recordHealthWatcherResourceExpirationStats(
        target,
        resourceHealth.getLearnedTerminatingFromSliceletTimeOpt,
        resourceHealth.getLearnedTerminatingFromKubernetesTimeOpt
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
              for (entry <- assignedResourcesByUuid) {
                val (uuid, squid): (UUID, Squid) = entry
                if (!healthByUuid.contains(uuid)) {
                  val resourceHealth =
                    new ResourceHealth(
                      Left(squid),
                      observeSliceletReadiness,
                      permitRunningToNotReady,
                      config
                    )
                  resourceHealth.update(
                    startTime,
                    Left(squid),
                    SliceletState.Running,
                    HealthStatusSource.BootstrappingAssignment
                  )
                  healthByUuid.put(uuid, resourceHealth)
                  healthByExpiry.push(resourceHealth)
                  dirty = true
                  logger.info(
                    s"Health status for '$squid' bootstrapped to Running, " +
                    s"status expires at: ${resourceHealth.expiryTime}"
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
   * We expect all [[ResourceHealth]] entries to have a [[Squid]], so entries without one are
   * excluded from the returned set (but still counted in metrics).
   */
  private def computeHealthySet(): Set[Squid] = {
    val resourcesPerStatusReported: mutable.Map[HealthStatus, Int] = mutable.Map.empty
    val resourcesPerStatusComputed: mutable.Map[HealthStatus, Int] = mutable.Map.empty
    val healthy: mutable.Set[Squid] = mutable.Set.empty

    for (entry <- healthByUuid) {
      val (_, resourceHealth): (UUID, ResourceHealth) = entry
      val reportedStatus: HealthStatus =
        resourceHealth.getReportedSliceletHealthStatus
      val computedStatus: HealthStatus = resourceHealth.getComputedHealthStatus

      // Count health statuses reported by Slicelets and HealthWatcher-computed statuses.
      resourcesPerStatusReported(reportedStatus) =
        resourcesPerStatusReported.getOrElse(reportedStatus, 0) + 1
      resourcesPerStatusComputed(computedStatus) =
        resourcesPerStatusComputed.getOrElse(computedStatus, 0) + 1

      // Record if the unmasked health status reported by Slicelet differs from the computed
      // status. Note: a resource with Starting status will always appear here, since Starting
      // is a HealthWatcher-assigned status with no corresponding SliceletState.
      if (reportedStatus != computedStatus) {
        TargetMetrics.incrementHealthStatusComputedDiffersFromReported(
          target,
          reportedStatusName = reportedStatus.statusName,
          computedStatusName = computedStatus.statusName
        )
        logger.info(
          s"HealthStatus mask applied: reported ${reportedStatus.statusName}, " +
          s"computed as ${computedStatus.statusName}",
          every = 10.seconds
        )
      }

      for (squid: Squid <- resourceHealth.getResourceSquidOpt) {
        if (computedStatus == HealthStatus.Running) {
          healthy += squid
        }
      }
    }

    // Iterate through all HealthStatus instances and report the podset size metrics, so that
    // any HealthStatus not present in a report will be set to 0.
    for (status: HealthStatus <- HealthStatus.values) {
      TargetMetrics.setPodSetSize(
        target,
        statusName = status.statusName,
        reportedCount = resourcesPerStatusReported.getOrElse(status, 0),
        computedCount = resourcesPerStatusComputed.getOrElse(status, 0)
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
   * Informs the watcher that `resource` is in `sliceletState`. Caller should subsequently call
   * `onAdvance` to determine if any action is required.
   *
   * @param now           the current time.
   * @param resource      the SQUID or UUID identifying the resource.
   * @param sliceletState the reported state of the resource.
   * @param source        where this health status update originated from.
   */
  private def informHealthStatus(
      now: TickerTime,
      resource: Either[Squid, UUID],
      sliceletState: SliceletState,
      source: HealthStatusSource): Unit = {
    val resourceUuid = resource match {
      case Left(squid: Squid) => squid.resourceUuid
      case Right(uuid: UUID) => uuid
    }

    val existingHealthStatusOpt: Option[ResourceHealth] = healthByUuid.get(resourceUuid)
    logger.info(
      s"Informed of Slicelet state for '$resourceUuid': " +
      s"$sliceletState (existing $existingHealthStatusOpt)",
      30.seconds
    )

    // Determine whether the status has changed for `resource`.
    dirty |= (existingHealthStatusOpt match {
      case Some(existingHealthStatus: ResourceHealth) =>
        val previousStatus: HealthStatus =
          existingHealthStatus.getComputedHealthStatus
        if (existingHealthStatus.update(now, resource, sliceletState, source)) {
          logger.info(
            s"Health status changed for '$resourceUuid' from ${previousStatus.statusName} to " +
            s"${existingHealthStatus.getComputedHealthStatus.statusName}"
          )
          logger.debug(makeHealthSummary(Some(now)))
          true
        } else {
          false
        }
      case None =>
        val resourceHealth =
          new ResourceHealth(
            resource,
            observeSliceletReadiness,
            permitRunningToNotReady,
            config
          )
        resourceHealth.update(now, resource, sliceletState, source)
        healthByUuid.put(resourceUuid, resourceHealth)
        healthByExpiry.push(resourceHealth)
        logger.info(
          s"Health status for '$resourceUuid' initialized to " +
          s"${resourceHealth.getComputedHealthStatus.statusName}"
        )
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
        healthStatus.statusName,
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
