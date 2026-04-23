package com.databricks.dicer.assigner

import java.time.Instant
import scala.concurrent.duration._
import scala.collection.mutable

import com.databricks.dicer.assigner.KeyOfDeathDetector.{
  Config,
  CrashedResource,
  DriverAction,
  Event,
  State
}
import com.databricks.dicer.assigner.TargetMetrics.KeyOfDeathTransitionType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.caching.util.{TickerTime, StateMachine, StateMachineOutput, PrefixLogger}
import com.databricks.dicer.external.Target

/**
 * This detector is a state machine responsible for tracking and declaring the start and
 * stop of a key of death scenario.
 *
 * A "key of death"/"poisonous key" refers to a special SliceKey in a Dicer-sharded system with
 * the potential to crash the resource it resides on whenever requests associated with it are
 * served. It could be because the key introduces high load, or because it hits a buggy
 * path in the customer application. As Dicer guarantees that any key in the full SliceKey
 * space must be assigned to a healthy resource (if key of death protection is not enabled),
 * when the original resource crashes, the key of death will be reassigned to a new healthy
 * resource. This new resource may be crashed again, and this iteration continues until the
 * entire service is brought down. We refer to this sequence of events as a key of death scenario.
 *
 * The heuristic used to detect a key of scenario is defined as the number of
 * crashes tracked over the past configured `crashRecordRetention` divided by the current
 * estimated resource workload size. The estimated resource workload size is calculated as
 * follows:
 * 1. In a stable state, where no crashes have occurred, the estimated resource workload size is
 *    the number of healthy resources, defined as the number of resources in the `Running` state.
 * 2. When a crash first occurs, we define a new "frozen" workload size to be the number of healthy
 *    resources just prior to the crash (i.e., it includes the newly crashed resource).
 * 3. As long as a crash exists within the `crashRecordRetention` window, the estimated resource
 *    workload size is defined as the maximum of the frozen workload size, which is persisted until
 *    we return to a stable state, and the current number of healthy resources, to account for
 *    scale-ups during this period of time.
 *
 * If the heuristic's value exceeds the configured `heuristicThreshold`, we declare a
 * key of death scenario. Once this ratio returns to 0, which occurs only when no crashes are
 * observed over the past `crashRecordRetention` window, we declare the key of death scenario as
 * ended. Note that we consider only resources with the `Running` health status as healthy -- a
 * health status of `NotReady` indicates the the resource cannot yet serve requests and is not yet
 * part of the assignment.
 *
 * This heuristic acts as an upper bound on the fraction of unhealthy resources (it will
 * overestimate when resource restarts occur). We can mitigate the possibility of false positives
 * through configuration choices, setting a short-enough `crashRecordRetention` and a high-enough
 * `heuristicThreshold` to limit the impact of infrequent, non-key-of-death-related crashes. To
 * illustrate the heuristic's behavior, consider a service with 40 resources, a `
 * crashRecordRetention` of 1 hour, and a `heuristicThreshold` of 0.25. In a key of death scenario
 * with continuous traffic for the key of death, one resource will crash around every 30 seconds.
 * We examine the heuristic's behavior under two scenarios:
 * 1. Assume that the restart time of the resource is long enough so that none of them can restart
 *    before the detection of the key of death scenario. Then, we have:
 *    - At time 0, the heuristic value is 0, and all 40 resources are healthy.
 *    - At time 30 seconds, one resource crashes. The frozen workload size is 40, and the heuristic
 *      value is 1/40 = 0.025.
 *    - By time 300 seconds, 10 resources have crashed, and the heuristic value is 10/40 = 0.25,
 *      which triggers the detection of the key of death scenario.
 *    In the above case, the heuristic value is exactly the fraction of unhealthy resources.
 * 2. Assume that the restart time of the resource is short enough so that some of them can restart
 *    before the detection of the key of death scenario. If the restart time is 2 minutes, we have:
 *    - At time 0, the heuristic value is 0, and all 40 resources are healthy.
 *    - At time 30 seconds, one resource crashes. The frozen workload size is 40, and the heuristic
 *      value is 1/40 = 0.025.
 *    - At time 120 seconds, 5 resources have crashed. However, the first resource has already
 *      restarted. The heuristic value is now 5/40 = 0.125, though there are actually only 4
 *      unhealthy resources.
 *    - By time 300 seconds, 10 crashes have occurred, and the heuristic value is 10/40 = 0.25,
 *      which triggers the detection of the key of death scenario. There are still only 4 unhealthy
 *      resources at a given time.
 *    In this case, the heuristic overestimates the fraction of unhealthy resources and still
 *    enters the key of death scenario. This behavior is desirable though, as the continuous
 *    crashing is still a sign of a key of death scenario.
 *
 * Upon being supplied with an event or advancement, the key of death detector will inform the
 * caller of the next time a currently tracked crashed resource expires, with the crash having
 * happened at least `crashRecordRetention` ago. The caller is expected to query the detector
 * again no later than that time so it can promptly observe any change in the key of death status.
 * Driver actions will be emitted to signal the start and stop of a key of death scenario, if one
 * is triggered by the supplied event or advancement. This state machine is designed to compose
 * easily with its parent state machine, [[AssignmentGenerator]], and is effectively driven by the
 * [[AssignmentGeneratorDriver]]. See [[StateMachine]] documentation for additional background on
 * the driver/state machine pattern.
 *
 * Note that this class only expires the records of crashed resources. It does not track the expiry
 * of healthy resources. As a result, the caller must inform the [[KeyOfDeathDetector]] of any
 * changes to the healthy count and newly crashed resources in a timely manner. In practice, the
 * [[HealthWatcher]] is responsible for expiring healthy resources and generating the subsequent
 * health report, which is propagated to the [[AssignmentGenerator]]. The
 * [[AssignmentGenerator]] will then inform the [[KeyOfDeathDetector]] of the latest healthy
 * count and crash events.
 *
 * Not thread-safe. In practice, access is protected by the [[AssignmentGeneratorDriver]]'s sec.
 *
 * @param target The target for which to detect key of death scenarios.
 * @param config The configuration for the key of death detector, determining the values for
 *               the `heuristicThreshold` and the `crashRecordRetention`.
 */
class KeyOfDeathDetector(
    private val target: Target,
    private val config: Config
) extends StateMachine[Event, DriverAction] {
  type Output = StateMachineOutput[DriverAction]

  private val logger = PrefixLogger.create(
    this.getClass,
    target.getLoggerPrefix
  )

  /** The number of resources currently known by the key of death detector to be running. */
  private var numHealthyResources: Int = 0

  /**
   * The priority queue of crashed resources, sorted by earliest expiry time.
   *
   * Note that this queue may track multiple crash records for the same resource. For example,
   * if a resource crashes, restarts, becomes healthy, and crashes again during the
   * `crashRecordRetention` period, the queue will track both crashes as separate records.
   * This is desired, as repeated crashing of the same resource is a strong indicator
   * that the key of death scenario is ongoing, and this repeated crashing should be factored
   * into the detection heuristic.
   */
  private val crashedResources: mutable.PriorityQueue[CrashedResource] =
    mutable.PriorityQueue.empty

  /**
   * The frozen workload size, as recorded upon the transition from the Stable state to the
   * Endangered state when the first crash occurs. This value is set to 0 in the Stable state.
   *
   * See class documentation for more details.
   */
  private var frozenWorkloadSize: Int = 0

  /** High-level state of the key of death detector (stable, endangered, or poisoned). */
  private var state: State = State.Stable

  override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {
    event match {
      case Event.ResourcesUpdated(healthyCount: Int, newlyCrashedCount: Int) =>
        handleResourcesUpdated(tickerTime, healthyCount, newlyCrashedCount)
    }
    onAdvance(tickerTime, instant)
  }

  override def onAdvance(tickerTime: TickerTime, instant: Instant): Output = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    onAdvanceInternal(tickerTime, outputBuilder)
    outputBuilder.build()
  }

  override def toString: String = {
    s"KeyOfDeathDetector(state=$state, numCrashed=${crashedResources.size}, " +
    s"estimatedResourceWorkloadSize=${Math.max(frozenWorkloadSize, numHealthyResources)})"
  }

  /**
   * Handles the event indicating that resources have been updated.
   *
   * @param now               the current ticker time, used as the crash detection time for each
   *                          newly crashed resource.
   * @param healthyCount      the number of resources currently in the Running state.
   * @param newlyCrashedCount the number of newly crashed resources.
   */
  private def handleResourcesUpdated(
      now: TickerTime,
      healthyCount: Int,
      newlyCrashedCount: Int): Unit = {
    // Add a crash record for each newly crashed resource, using `now` as the crash time.
    for (_ <- 0 until newlyCrashedCount) {
      crashedResources.enqueue(CrashedResource(now + config.crashRecordRetention))
    }

    // Report to Prometheus the number of newly crashed resources.
    TargetMetrics.incrementNumCrashedResourcesTotal(target, newlyCrashedCount)

    // Update the count of healthy resources.
    this.numHealthyResources = healthyCount
  }

  /**
   * This method implements the core logic of the key of death detector, including:
   * 1. Removing any expired crashed resources.
   * 2. Reporting Prometheus metrics related to the key of death detector.
   * 3. State-specific logic:
   *    - In the Stable state, checks if any crashes have occurred, and if so, transitions
   *      to the Endangered state.
   *    - In the Endangered state, checks if the heuristic value has exceeded the threshold or
   *      returned to 0, and if so, transitions to the Poisoned state or back to the Stable state
   *      respectively, declaring a key of death scenario if needed.
   *    - In the Poisoned state, checks if no more crashes have occurred, and if so, transitions
   *      to the Stable state, declaring the end of the key of death scenario.
   * 4. Recursively invoking onAdvanceInternal if a state transition occurs, ensuring timely
   *    metric updates.
   */
  private def onAdvanceInternal(
      tickerTime: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    // Remove any expired crashed resources.
    while (crashedResources.nonEmpty && crashedResources.head.expiryTime <= tickerTime) {
      logger.info(
        s"A crash record expired at time $tickerTime; " +
        s"${crashedResources.size} crash records remaining"
      )
      crashedResources.dequeue()
    }

    // Compute the metrics related to the key of death detector heuristic. If the estimated
    // resource workload size is 0, we return the number of crashed resources as the heuristic
    // value, which will surpass the configured `config.heuristicThreshold` if and only if any
    // exist.
    val numCrashedResources: Int = crashedResources.size
    val estimatedResourceWorkloadSize: Int = Math.max(frozenWorkloadSize, numHealthyResources)
    val heuristicValue: Double = if (estimatedResourceWorkloadSize > 0) {
      numCrashedResources.toDouble / estimatedResourceWorkloadSize.toDouble
    } else {
      numCrashedResources.toDouble
    }

    // Report the relevant Prometheus metrics.
    TargetMetrics.onKeyOfDeathCheck(
      target,
      heuristicValue,
      estimatedResourceWorkloadSize,
      numCrashedResources
    )

    // Carry out state-specific logic, recording any state transitions that occur.
    val transitionTypeOpt: Option[KeyOfDeathTransitionType] =
      this.state match {
        case State.Stable =>
          if (numCrashedResources > 0) {
            // If any crashes have occurred, freeze the workload size and transition to the
            // Endangered state.
            this.frozenWorkloadSize = numHealthyResources + numCrashedResources

            this.state = State.Endangered
            Some(KeyOfDeathTransitionType.STABLE_TO_ENDANGERED)
          } else {
            // No state transition occurs.
            None
          }
        case State.Endangered =>
          if (heuristicValue >= config.heuristicThreshold) {
            // If the heuristic value has exceeded the threshold, emit a driver action declaring
            // the start of a key of death scenario.
            outputBuilder.appendAction(DriverAction.TransitionToPoisoned)
            logger.info(
              s"Key of death threshold exceeded at time $tickerTime with value $heuristicValue"
            )

            // Transition to the Poisoned state.
            this.state = State.Poisoned
            Some(KeyOfDeathTransitionType.ENDANGERED_TO_POISONED)
          } else if (heuristicValue == 0) {
            // If the heuristic value has returned to 0, transition to the Stable state, unfreezing
            // the workload size.
            this.frozenWorkloadSize = 0

            this.state = State.Stable
            Some(KeyOfDeathTransitionType.ENDANGERED_TO_STABLE)
          } else {
            // No state transition occurs.
            None
          }
        case State.Poisoned =>
          if (numCrashedResources == 0) {
            // If there are no more recorded crashes, transition to the Stable state, unfreezing
            // the workload size.
            this.frozenWorkloadSize = 0

            // Emit a driver action declaring the end of a key of death scenario.
            outputBuilder.appendAction(DriverAction.RestoreStability)
            logger.info(s"Key of death scenario ended at time $tickerTime")

            this.state = State.Stable
            Some(KeyOfDeathTransitionType.POISONED_TO_STABLE)
          } else {
            // No state transition occurs.
            None
          }
      }

    transitionTypeOpt match {
      case Some(transitionType: KeyOfDeathTransitionType) =>
        // For any transitions, report the transition type to Prometheus metrics.
        TargetMetrics.incrementKeyOfDeathStateTransitions(target, transitionType)
        // Recursively invoke to update Prometheus metrics in case the workload size has been
        // frozen or unfrozen. The recursive call may also advance to a new state, potentially
        // appending to the existing output actions.
        onAdvanceInternal(tickerTime, outputBuilder)
      case None =>
        // In all other cases, we maintain the current output. We want to be called back the next
        // time a crashed resource expires, if any.
        val nextExpiryTime: TickerTime =
          crashedResources.headOption.map((_: CrashedResource).expiryTime).getOrElse(TickerTime.MAX)
        outputBuilder.ensureAdvanceBy(nextExpiryTime)
    }
  }
}

object KeyOfDeathDetector {

  /**
   * REQUIRES: `crashRecordRetention` is positive.
   * REQUIRES: `heuristicThreshold` is positive and less than or equal to 1.0.
   *
   * Configuration for the KeyOfDeathDetector.
   *
   * @param crashRecordRetention the period of time over which to track the number of crashes
   *                             for the heuristic calculation.
   * @param heuristicThreshold When the heuristic value is greater than this threshold, a key of
   *                           death scenario is declared.
   */
  case class Config(
      crashRecordRetention: FiniteDuration,
      heuristicThreshold: Double
  ) {
    require(crashRecordRetention > Duration.Zero)
    require(heuristicThreshold > 0.0 && heuristicThreshold <= 1.0)
  }

  /** Factory methods and constants for [[Config]]. */
  object Config {

    /**
     * The default value for [[crashRecordRetention]] to be used in production.
     *
     * This retention was chosen by considering the following factors:
     * 1. The retention period must be long enough to count a sufficient number of crashes,
     *    given that crashes in a key of death scenario are expected to occur once every
     *    30 seconds. For example, if the retention period was only 2 minutes, at most
     *    4 crashes would be recorded at once, which is not sufficient to declare a key of
     *    death scenario for large services.
     * 2. The retention period must be long enough to maintain records over a resource's
     *    restart time. For example, if the retention period was only 2 minutes, while the
     *    resource's restart time was 5 minutes, we may enter a key of death scenario, only to
     *    soonafter forget that the crash occurred and declare the key of death scenario as ended
     *    without ample evidence that the desired fraction of the service has remained protected.
     * 3. The retention period must be short enough to avoid unnecessarily remaining in the
     *    poisoned state after the key of death scenario has ended. For example, if the retention
     *    period was 5 hours, it'd take 5 hours after the last observed crash to declare the key
     *    of death scenario as ended.
     */
    private val CRASH_RECORD_RETENTION: FiniteDuration = 1.hours

    /** The default value for [[heuristicThreshold]] to be used in production. */
    private val HEURISTIC_THRESHOLD: Double = 0.25

    /**
     * Creates a new [[Config]] with default values for [[crashRecordRetention]] and
     * [[heuristicThreshold]].
     */
    def defaultConfig(): Config = {
      Config(CRASH_RECORD_RETENTION, HEURISTIC_THRESHOLD)
    }
  }

  /** Input events to the key of death detector state machine. */
  sealed trait Event
  object Event {

    /**
     * Informs the key of death detector of the current resource health status.
     *
     * @param healthyCount      the number of resources currently in the Running state.
     * @param newlyCrashedCount the number of newly crashed resources. A resource is considered
     *                          crashed when its heartbeat expires and it is removed from tracking.
     */
    case class ResourcesUpdated(healthyCount: Int, newlyCrashedCount: Int) extends Event
  }

  /**
   * Actions the key of death detector state machine requests from the driver in
   * response to incoming signals or the passage of time.
   */
  sealed trait DriverAction
  object DriverAction {

    /**
     * Informs the driver that the key of death threshold has been exceeded, and a key of
     * death scenario has been declared.
     */
    case object TransitionToPoisoned extends DriverAction

    /** Informs the driver that the ongoing key of death scenario has ended. */
    case object RestoreStability extends DriverAction
  }

  /** High-level state for the key of death detector. */
  private sealed trait State
  private object State {

    /**
     * The key of death detector is actively tracking and computing the key of death heuristic.
     * No key of death scenario is currently ongoing.
     */
    case object Stable extends State

    /**
     * The key of death detector has detected a crash, and is actively tracking the number of
     * crashes to determine if the key of death scenario has started. We recognize, however,
     * that the initial crash may not be due to a key of death scenario, and retain the possibility
     * of transitioning back to the Stable state if no subsequent crashes are observed.
     */
    case object Endangered extends State

    /**
     * The key of death detector has detected a key of death scenario, and is actively
     * tracking the number of crashes and computing the key of death heuristic to determine if
     * the key of death scenario has ended, as evidenced by a lack of crashes over the past
     * `crashRecordRetention` period.
     */
    case object Poisoned extends State
  }

  /**
   * Tracks the expiry of a single crash record. `expiryTime` is the time at which the record will
   * be removed from the tracked set of crashed resources, having crashed more than the
   * `crashRecordRetention` period ago.
   */
  private case class CrashedResource(expiryTime: TickerTime) extends Ordered[CrashedResource] {

    /**
     * Compares this CrashedResource with another CrashedResource based on their expiry times,
     * in ascending order (i.e. the earliest expiry time is the smallest).
     */
    override def compare(that: CrashedResource): Int = {
      that.expiryTime.compare(this.expiryTime)
    }
  }
}
