package com.databricks.dicer.assigner

import java.time.Instant
import java.util.UUID

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import io.grpc.Status
import io.grpc.Status.Code

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  CachingErrorCode,
  PrefixLogger,
  StateMachine,
  StateMachineOutput,
  StatusUtils,
  TickerTime
}
import com.databricks.dicer.assigner.config.InternalTargetConfig
import com.databricks.dicer.assigner.AssignmentGenerator.AssignmentGenerationDecision.{
  GenerateReason,
  SkipReason
}
import com.databricks.dicer.assigner.AssignmentGenerator.{
  AssignmentGenerationContext,
  AssignmentGenerationDecision,
  Config,
  DriverAction,
  Event,
  GeneratorTargetSlicezData,
  Output,
  RunState
}
import com.databricks.dicer.assigner.AssignmentStats.{
  AssignmentChangeStats,
  AssignmentLoadStats,
  ReassignmentChurnAndLoadStats
}
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap, Resources}
import com.databricks.dicer.assigner.algorithm.LoadMap.KeyLoadMap
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.assigner.TargetMetrics.{
  AssignmentDistributionSource,
  IncarnationMismatchType
}
import com.databricks.dicer.assigner.conf.LoadWatcherConf
import com.databricks.dicer.common.Assignment.DiffUnused
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.{
  Assignment,
  ClerkData,
  ClientRequest,
  DiffAssignment,
  Generation,
  Incarnation,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceletData,
  SliceletState,
  SyncAssignmentState
}
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Slice, SliceKey, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import java.net.URI

/**
 * Generates assignments for a target based on incoming signals, e.g. for resource health.
 * Implemented as a passive state machine that is "driven" by an [[AssignmentGeneratorDriver]]. See
 * [[StateMachine]] documentation for additional background on the driver/state machine pattern.
 *
 * The state machine is exercised only via [[onEvent()]] and [[onAdvance()]]. The driver, when
 * started, calls [[onAdvance()]] with the initial time.
 */
class AssignmentGenerator(
    config: Config,
    target: Target,
    targetConfig: InternalTargetConfig,
    healthWatcher: HealthWatcher,
    keyOfDeathDetector: KeyOfDeathDetector
) extends StateMachine[Event, DriverAction] {

  private val logger =
    PrefixLogger.create(
      getClass,
      s"${target.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
    )

  /** The component that aggregates load reports from Slicelets. */
  private val loadWatcher =
    new LoadWatcher(targetConfig.loadWatcherConfig, config.loadWatcherStaticConfig)

  /** Whether an assignment is currently being written. */
  private var assignmentWriteInProgress = false

  /** The latest resources reported by [[healthWatcher]], if any. */
  private var resources: Resources = Resources.empty

  /**
   * If the KubernetesTargetWatcher has been started. We keep the namespace, to log errors if we see
   * instances watch requests with different namespaces within a target.
   */
  private var watchedKubernetesNamespaceOpt: Option[String] = None

  /**
   * The latest time at which an assignment was successfully written by the current generator. Used
   * to determine when to perform a load balancing reassignment based on the
   * [[LoadBalancingConfig.loadBalancingInterval]] value.
   */
  private var latestAssignmentWriteTimeOpt: Option[TickerTime] = None

  /**
   * The latest time when the generator attempts to generate a new assignment (regardless of the
   * reason for the generation, or whether the generated assignment has been successfully written or
   * distributed). Used to limit the rate of assignment generation.
   *
   * Note we cannot use latestAssignmentWriteTimeOpt to limit assignment generating rate: that
   * variable only tracks the successful write time, So if the writing to store constantly fails,
   * the generating rate won't be limited.
   */
  private var latestAssignmentGenerationAttemptTimeOpt: Option[TickerTime] = None

  /** Current [[RunState]] of the generator. */
  private var runState: RunState = RunState.Startup

  /**
   * The latest assignment (by Generation) known to the generator, if one exists. The generator
   * combines this assignment with the latest signals to create a new assignment.
   *
   * If this assignment's Incarnation is higher than that of the generator, the generator will stop
   * generating new assignments as it's unable to produce one with a higher Generation. At that
   * point, the generator just acts as another node in the assignment sync network. In that state,
   * we also expect the generator should be restarted soon, as there should already exist another
   * Assigner with higher Incarnation.
   */
  private var latestKnownAssignmentOpt: Option[Assignment] = None

  /**
   * The latest statistics information to be aggregated into [[AssignerTargetSlicezData]] and
   * displayed on the Assigner ZPage.
   *
   * Since [[GeneratorTargetSlicezData]] can be updated by the state machine under different
   * conditions and with different fields, we use this variable to store the latest values of
   * [[GeneratorTargetSlicezData]], so the external driver can learn about the most up-to-date
   * information. See [[GeneratorTargetSlicezData]] for more details on the encompassed data.
   */
  private var generatorTargetSlicezData: GeneratorTargetSlicezData = GeneratorTargetSlicezData.EMPTY

  /**
   * The latest time at which the load information's availability was checked in order to update
   * `generatorTargetSlicezData` (see [[maybeExportAssignmentSnapshot]]) and export the new value
   * to Prometheus and the driver. Used to determine when to perform the next "check-and-update"
   * operation based on the [[LoadBalancingConfig.loadBalancingInterval]] value.
   *
   * We check load information and update TargetMetrics and SlicezData on this interval basis
   * because those operations are expensive.
   */
  private var latestExportAssignmentSnapshotTimeOpt: Option[TickerTime] = None

  protected override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {

    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    // Check if the generator has been shutdown. If so, process nothing.
    // This is necessary to ensure that the generator shutdown is enforced.
    if (runState == RunState.Shutdown) {
      return outputBuilder.build()
    }

    event match {
      case Event.TerminationSignalFromKubernetes(resourceUuid) =>
        handleTerminationSignalFromKubernetes(tickerTime, instant, outputBuilder, resourceUuid)
      case Event.WatchRequest(request) =>
        handleWatchRequest(tickerTime, instant, outputBuilder, request)
      case Event.AssignmentWriteComplete(result, contextOpt) =>
        handleWriteComplete(tickerTime, instant, outputBuilder, result, contextOpt)
      case Event.AssignmentReceived(assignment: Assignment) =>
        val syncState = SyncAssignmentState(assignment)
        handleAssignmentSync(
          tickerTime,
          instant,
          syncState,
          AssignmentDistributionSource.Store,
          outputBuilder
        )
      case Event.Shutdown() =>
        handleShutdown(outputBuilder)
        // Upon shutdown return immediately -- do not advance state machine.
        return outputBuilder.build()
    }

    advanceStateMachine(tickerTime, instant, outputBuilder)
  }

  protected override def onAdvance(tickerTime: TickerTime, instant: Instant): Output = {
    val outputBuilder = new StateMachineOutput.Builder[DriverAction]
    // Check if the generator has been shutdown. If so, process nothing.
    // This is necessary to ensure that the generator shutdown is atomic.
    if (runState == RunState.Shutdown) {
      return outputBuilder.build()
    }

    advanceStateMachine(tickerTime, instant, outputBuilder)
  }

  /**
   * PRECONDITION: The generator must not be in Shutdown state when advanceStateMachine is called.
   * (I.e. the state machine need (and should) advance no further after shutdown.)
   *
   * Advances the generator state machine to `now` and returns the output of the machine, including
   * all actions accumulated so far in the given `outputBuilder`.
   *
   *  - Advances signal accumulation sub-machines (currently only `healthWatcher` and
   *    `keyOfDeathDetector`).
   *  - Determines whether a new assignment should be generated at this time, and if so, generates
   *    the assignments and adds a `DriverAction.WriteAssignment` action to `outputBuilder`.
   *  - Builds the output.
   */
  private def advanceStateMachine(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Output = {

    // If the generator is in startup state, start the store watcher and set the run state
    // to running.
    if (runState == RunState.Startup) {
      TargetMetrics.incrementNumActiveGenerators(target)
      outputBuilder.appendAction(DriverAction.StartStoreWatcher())
      runState = RunState.Running
    }

    iassert(runState == RunState.Running, s"advanceStateMachine called with runState $runState")

    // Note: The ordering of stats update and assignment generation doesn't matter: even if
    // `maybeGenerateAssignment` decides to generate a new assignment, the
    // `latestKnownAssignment` will not be updated until the next AssignmentWriteSuccess
    // event returns (or another assignment is learned from other sources). Regardless of
    // ordering, the stats will be computed based on the old (current) assignment.
    maybeExportAssignmentSnapshot(tickerTime, outputBuilder)

    // In steady state, both the starting point for the assignment and the store predecessor
    // should both just be the most recent assignment.
    maybeGenerateAssignment(
      tickerTime,
      instant,
      outputBuilder
    )

    outputBuilder.build()
  }

  /**
   * Generate a new assignment if necessary based on the current state. If invoked, the algorithm
   * will attempt to minimize churn with respect to [[latestAssignmentWriteTimeOpt]].
   */
  private def maybeGenerateAssignment(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    // Advance the health watcher before deciding if a new assignment should be generated. In
    // the future, other signals will also be incorporated (e.g., from load reports).
    handleHealthWatcherOutput(
      tickerTime,
      instant,
      outputBuilder,
      healthWatcher.onAdvance(tickerTime, instant)
    )
    // Advance the key of death detector.
    handleKeyOfDeathDetectorOutput(outputBuilder, keyOfDeathDetector.onAdvance(tickerTime, instant))
    val assignmentGenerationDecision: AssignmentGenerationDecision =
      shouldGenerateNewAssignment(tickerTime)
    TargetMetrics.incrementAssignmentGenerationDecisionCount(target, assignmentGenerationDecision)
    logger.trace(s"Assignment generation decision: $assignmentGenerationDecision")
    assignmentGenerationDecision match {
      case AssignmentGenerationDecision.Generate(healthyResources: Resources, _) =>
        generateAssignment(tickerTime, instant, outputBuilder, healthyResources)
      case AssignmentGenerationDecision.Skip(ensureAdvanceByTimeOpt: Option[TickerTime], _) =>
        for (ensureAdvanceByTime: TickerTime <- ensureAdvanceByTimeOpt) {
          outputBuilder.ensureAdvanceBy(ensureAdvanceByTime)
        }
    }
  }

  /**
   * Returns an [[AssignmentGenerationDecision]] describing whether or not to generate a new
   * assignment `now`.
   */
  private def shouldGenerateNewAssignment(now: TickerTime): AssignmentGenerationDecision = {
    val isFrozen: Boolean = latestKnownAssignmentOpt.exists { assignment: Assignment =>
      assignment.isFrozen
    }

    // The "ideal" generation decision without rate limiting consideration.
    val decisionIgnoringRateLimit: AssignmentGenerationDecision =
      if (isFrozen) {
        AssignmentGenerationDecision.Skip(
          ensureAdvanceByTimeOpt = None,
          SkipReason.FrozenAssignment
        )
      } else if (resources.availableResources.isEmpty) {
        AssignmentGenerationDecision.Skip(
          ensureAdvanceByTimeOpt = None,
          SkipReason.IncompleteResources
        )
      } else if (assignmentWriteInProgress) {
        AssignmentGenerationDecision.Skip(ensureAdvanceByTimeOpt = None, SkipReason.InFlightWrite)
      } else {

        // We know which resources are available, no assignment write is currently in progress, and
        // the assignment is not frozen. Generate a new assignment immediately* if:
        // 1. There is no known assignment for the target, or
        // 2. There have been health changes (available resources are not the same as assigned
        //    resources), or
        // 3. The load balancing interval has elapsed.
        //
        // *Unless there's a known assignment with a higher incarnation. In that case, the Generator
        // goes inert because it may only generate assignments within its configured incarnation and
        // thus has no hope of superseding the known assignment.
        //
        // NOTE: These checks are performed every time the state machine advances. Even if there's
        // currently a write in progress (or the assignment is currently frozen), there will be an
        // opportunity to generate a new assignment when the write completes (or the assignment is
        // unfrozen).
        latestKnownAssignmentOpt match {
          // We've got healthy resources but no assignment. Generate now.
          case None =>
            AssignmentGenerationDecision.Generate(resources, GenerateReason.FirstAssignment)
          case Some(latestKnownAssignment: Assignment) =>
            val latestKnownIncarnation: Incarnation = latestKnownAssignment.generation.incarnation
            if (latestKnownIncarnation > config.storeIncarnation) {
              // We can only generate assignments in our configured incarnation and thus have no
              // hope of generating an assignment which can supersede the latest known.
              logger.warn(
                s"Skipping assignment generation because latest known incarnation" +
                s" $latestKnownIncarnation is higher than store incarnation" +
                s" ${config.storeIncarnation}",
                every = 1.minute
              )
              AssignmentGenerationDecision.Skip(
                ensureAdvanceByTimeOpt = None,
                skipReason = SkipReason.KnownHigherIncarnationAssignment
              )
            } else {
              val availableResources: Set[Squid] = resources.availableResources
              val availableResourcesChanged: Boolean =
                availableResources != latestKnownAssignment.assignedResources
              val nextLoadBalancingTime: TickerTime = getNextLoadBalancingTime(now)
              if (availableResourcesChanged) {
                AssignmentGenerationDecision.Generate(resources, GenerateReason.HealthChange)
              } else if (nextLoadBalancingTime <= now) {
                AssignmentGenerationDecision.Generate(resources, GenerateReason.LoadBalancing)
              } else {
                AssignmentGenerationDecision.Skip(
                  ensureAdvanceByTimeOpt = Some(nextLoadBalancingTime),
                  skipReason = SkipReason.TooSoonToLoadBalance
                )
              }
            }
        }
      }

    // Apply rate limit check on top of the ideal generation decision. If the ideal generation
    // decision is to "generate", and `now` is within the rate limitation, we override it to
    // AssignmentGenerationDecision.Skip.
    decisionIgnoringRateLimit match {
      case generate: AssignmentGenerationDecision.Generate =>
        val nextAllowedGenerationTime: TickerTime = latestAssignmentGenerationAttemptTimeOpt
          .map { latestAttemptTime: TickerTime =>
            latestAttemptTime + config.minAssignmentGenerationInterval
          }
          // No assignment generating attempt before. The earliest allowed generating time is
          // TickerTime.MIN.
          .getOrElse(TickerTime.MIN)

        if (now >= nextAllowedGenerationTime) {
          generate
        } else {
          AssignmentGenerationDecision.Skip(
            ensureAdvanceByTimeOpt = Some(nextAllowedGenerationTime),
            SkipReason.RateLimited(generate.generateReason)
          )
        }
      case skip: AssignmentGenerationDecision.Skip =>
        // We are not going to generate new assignment anyways, so it's unnecessary to check rate
        // limitation.
        skip
    }
  }

  /**
   * If it is time to check and update based on [[latestExportAssignmentSnapshotTimeOpt]], checks
   * the availability of load information. If the load information is available, updates the
   * Prometheus metrics and the [[GeneratorTargetSlicezData]] statistics information in the
   * driver with the latest load information.
   */
  private def maybeExportAssignmentSnapshot(
      tickerTime: TickerTime,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    val nextExportAssignmentSnapshotTime: TickerTime = getNextExportAssignmentSnapshotTime(
      tickerTime
    )
    if (nextExportAssignmentSnapshotTime <= tickerTime) {
      for {
        latestKnownAssignment: Assignment <- latestKnownAssignmentOpt
        getLoadMapResult: (LoadMap, KeyLoadMap) <- TargetMetrics
          .recordAssignmentGeneratorLatencySync[
            Option[(LoadMap, KeyLoadMap)]
          ](
            TargetMetrics.AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP,
            target
          ) {
            loadWatcher.getPrimaryRateLoadMap(
              tickerTime,
              latestKnownAssignment
            )
          }
      } yield {
        val (assignmentLoadMap, keyLoadMap): (LoadMap, KeyLoadMap) = getLoadMapResult

        // Calculate the latest load statistics.
        val reportedLoadStats: AssignmentLoadStats =
          AssignmentLoadStats.calculate(latestKnownAssignment, assignmentLoadMap)
        val adjustedLoadStats: AssignmentLoadStats =
          AssignmentLoadStats.calculateAdjustedLoadStats(
            latestKnownAssignment,
            assignmentLoadMap,
            targetConfig.loadBalancingConfig
          )

        // Export the metrics to Prometheus.
        TargetMetrics.reportAssignmentSnapshotStats(
          target,
          reportedLoadStats,
          adjustedLoadStats,
          assignmentLoadMap,
          keyLoadMap.size
        )

        // Update the generator Slicez data.
        // Since no assignment change has happened, `assignmentChangeStatsOpt` should remain
        // the same as its last known value.
        generatorTargetSlicezData = generatorTargetSlicezData.copy(
          reportedLoadPerResourceOpt = Some(reportedLoadStats.loadByResource),
          reportedLoadPerSliceOpt = Some(reportedLoadStats.loadBySlice),
          topKeysOpt = Some(keyLoadMap),
          adjustedLoadPerResourceOpt = Some(adjustedLoadStats.loadByResource)
        )
        outputBuilder.appendAction(
          DriverAction.UpdateAssignmentGeneratorTargetSlicezData(
            generatorTargetSlicezData
          )
        )
      }
      latestExportAssignmentSnapshotTimeOpt = Some(tickerTime)
    }
    outputBuilder.ensureAdvanceBy(getNextExportAssignmentSnapshotTime(tickerTime))
  }

  /**
   * Returns the next time at which load balancing should be performed ([[TickerTime.MAX]] indicates
   * "never").
   */
  private def getNextLoadBalancingTime(now: TickerTime): TickerTime = {
    latestAssignmentWriteTimeOpt match {
      case Some(latestAssignmentWriteTime: TickerTime) =>
        latestAssignmentWriteTime + targetConfig.loadBalancingConfig.loadBalancingInterval
      case None =>
        // We've never written an assignment, so we should perform load balancing as soon as
        // possible.
        now
    }
  }

  /**
   * Returns the next time at which the load information should be checked and TargetMetrics and
   * `generatorTargetSlicezData` updated. The stats update interval is computed based on the
   * load balancing interval.
   */
  private def getNextExportAssignmentSnapshotTime(now: TickerTime): TickerTime = {
    latestExportAssignmentSnapshotTimeOpt match {
      case Some(latestSlicezDataUpdateTime: TickerTime) =>
        // The stats update interval is set to half the load balancing interval.
        latestSlicezDataUpdateTime + targetConfig.loadBalancingConfig.loadBalancingInterval / 2
      case None =>
        // We've never updated `generatorTargetSlicezData`, so we should update it as soon as
        // possible.
        now
    }
  }

  /** Handles output from the health watcher state machine. */
  private def handleHealthWatcherOutput(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      healthWatcherOutput: StateMachineOutput[HealthWatcher.DriverAction]): Unit = {
    // Ensure that this generator is advanced when the health watcher wants to be advanced.
    outputBuilder.ensureAdvanceBy(healthWatcherOutput.nextTickerTime)

    // Perform actions requested by the health watcher.
    for (action <- healthWatcherOutput.actions) {
      action match {
        case HealthWatcher.DriverAction
              .HealthReportReady(healthy: Set[Squid], newlyCrashedCount: Int) =>
          // Inform the key of death detector of the current healthy count and any newly crashed
          // resources so it can track the key of death heuristic.
          handleKeyOfDeathDetectorOutput(
            outputBuilder,
            keyOfDeathDetector.onEvent(
              tickerTime,
              instant,
              KeyOfDeathDetector.Event.ResourcesUpdated(
                healthyCount = healthy.size,
                newlyCrashedCount = newlyCrashedCount
              )
            )
          )

          // Remember the latest health report so that we can use it to decide whether we need to
          // generate a new assignment.
          this.resources = Resources.create(healthy)
      }
    }
  }

  /**
   * Handles output from the key of death detector state machine. This currently has no impact
   * on assignment generation decisions nor the subsequent assignments generated, as the detector
   * purely reports metrics related to key of death scenarios. Its driver actions are ignored, for
   * now, by the AssignmentGenerator.
   */
  private def handleKeyOfDeathDetectorOutput(
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      keyOfDeathDetectorOutput: StateMachineOutput[KeyOfDeathDetector.DriverAction]): Unit = {
    // Ensure that this generator is advanced when the key of death detector wants to be advanced.
    outputBuilder.ensureAdvanceBy(keyOfDeathDetectorOutput.nextTickerTime)

    // TODO(<internal bug>): Currently, the AssignmentGenerator does not react to the key of death
    // detector's actions. In the future, the AssignmentGenerator must react to the
    // `TransitionToPoisoned` and `RestoreStability` actions emitted by the key of death detector,
    // which declare the start and end of a key of death scenario, respectively.
  }

  /** Handles termination signal from Kubernetes. */
  private def handleTerminationSignalFromKubernetes(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      resourceUuid: UUID): Unit = {
    val event = HealthWatcher.Event
      .SliceletStateFromKubernetes(
        resourceUuid,
        SliceletState.Terminating
      )
    handleHealthWatcherOutput(
      tickerTime,
      instant,
      outputBuilder,
      healthWatcher.onEvent(tickerTime, instant, event)
    )
  }

  /**
   * Handles a watch request from a Clerk or Slicelet. The [[HealthWatcher]] state machine uses
   * requests from Slicelets as heartbeats to infer that the Slicelet is still healthy. Requests
   * optionally include the latest assignment known to the Clerk or Slicelet, which the generator
   * can use to recover its state after an Assigner crash.
   */
  private def handleWatchRequest(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      request: ClientRequest): Unit = {
    val source: AssignmentDistributionSource.AssignmentDistributionSource =
      request.subscriberData match {
        case sliceletData: SliceletData =>
          val resource: Squid = sliceletData.squid

          // Slicelet watch requests are also heartbeats. Tell the health watcher about the
          // heartbeat and incorporate any actions requested by the health watcher.
          val event = HealthWatcher.Event
            .SliceletStateFromSlicelet(
              resource,
              sliceletData.state
            )
          handleHealthWatcherOutput(
            tickerTime,
            instant,
            outputBuilder,
            healthWatcher.onEvent(tickerTime, instant, event)
          )

          // Inform the load watcher of the latest load measurements from the Slicelet.
          val primaryRateMeasurements = Seq.newBuilder[LoadWatcher.Measurement]
          for (sliceLoad: SliceletData.SliceLoad <- sliceletData.attributedLoads) {
            // `LoadWatcher` expects the time at which the measurement was taken as a `TickerTime`
            // value rather than an `Instant`. Determine the relative time at which the measurement
            // was taken to perform the conversion. Since the clock on the server where the
            // measurement was taken may be significantly off (e.g. running far ahead of our clock,
            // making the load report appear to be from the future), we restrict the age of the load
            // report to be non-negative.
            val loadReportAge: FiniteDuration = Duration
              .fromNanos(
                java.time.Duration.between(sliceLoad.windowHighExclusive, instant).toNanos
              )
              .max(Duration.Zero)
            val measurementTime: TickerTime = tickerTime - loadReportAge
            primaryRateMeasurements += loadWatcher.createMeasurement(
              measurementTime,
              sliceLoad.windowDuration,
              sliceLoad.slice,
              resource.resourceAddress,
              numReplicas = sliceLoad.numReplicas,
              sliceLoad.primaryRateLoad,
              sliceLoad.topKeys
            )
          }
          TargetMetrics.recordAssignmentGeneratorLatencySync(
            TargetMetrics.AssignmentGeneratorOpType.REPORT_LOAD,
            target
          ) {
            loadWatcher.reportLoad(tickerTime, primaryRateMeasurements.result())
          }
          AssignmentDistributionSource.Slicelet
        case ClerkData =>
          AssignmentDistributionSource.Clerk
      }
    // Incorporate the assignment received from the subscriber (if any).
    handleAssignmentSync(
      tickerTime,
      instant,
      request.syncAssignmentState,
      source,
      outputBuilder
    )

    target match {
      case kubernetesTarget: KubernetesTarget =>
        // Since we're only able to watch the k8s API server in the local cluster, we only create a
        // watcher when the target is local (note that if the target's cluster URI is absent, it is
        // implicitly in the local cluster.)
        //
        // Note also that we (intentionally) avoid checking or alerting on empty/mismatched k8s
        // namespaces for remote cluster Slicelets, since otherwise we'd potentially generate alerts
        // for functionality which is unsupported and have no plans to support (there currently
        // exists no way to watch k8s API servers in remote clusters.)
        if (kubernetesTarget.clusterOpt.getOrElse(config.clusterUri) == config.clusterUri) {
          (request.subscriberData, watchedKubernetesNamespaceOpt) match {
            case (sliceletData: SliceletData, None) =>
              logger.expect(
                sliceletData.kubernetesNamespace.nonEmpty,
                CachingErrorCode.SLICELET_EMPTY_NAMESPACE,
                s"Received empty namespace from Slicelet ${sliceletData.squid}"
              )
              if (sliceletData.kubernetesNamespace.nonEmpty) {
                // Add an action to start the KubernetesTargetWatcher if we haven't started a watch
                // yet.
                val watchTarget = KubernetesWatchTarget(
                  appName = target.getKubernetesAppName,
                  namespace = sliceletData.kubernetesNamespace
                )
                outputBuilder.appendAction(DriverAction.StartKubernetesTargetWatcher(watchTarget))
                watchedKubernetesNamespaceOpt = Some(sliceletData.kubernetesNamespace)
              }

            case (sliceletData: SliceletData, Some(watchedKubernetesNamespace: String)) =>
              logger.expect(
                watchedKubernetesNamespace == sliceletData.kubernetesNamespace,
                CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
                s"Mismatch in namespace reported by Slicelet and the namespace we are watching. " +
                s"Slicelet(${sliceletData.squid}) namespace: " +
                s"${sliceletData.kubernetesNamespace}, Watched namespace: " +
                watchedKubernetesNamespace,
                30.seconds
              )
            case (ClerkData, _) =>
            // We don't watch the health of Clerks, nothing to do.
          }
        }
      case _: AppTarget =>
        // AppTargets are currently only used by targets in the data-plane, so we cannot watch the
        // k8s API server for them. Once AppTargets are used in the same cluster as the Assigner,
        // we should watch the k8s API server for them.
        // TODO(<internal bug>): Watch k8s API server for AppTargets where possible
        ()
    }
  }

  /**
   * Unpacks the result of an assignment write and routes to the appropriate handler.
   */
  private def handleWriteComplete(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      result: Try[Store.WriteAssignmentResult],
      contextOpt: Option[AssignmentGenerationContext]): Unit = {

    // The write is no longer in progress, which frees us up to produce a new assignment based on
    // the latest signals if needed.
    assignmentWriteInProgress = false

    result match {
      case Success(writeResult: WriteAssignmentResult) =>
        handleAssignmentWriteSuccess(tickerTime, instant, outputBuilder, writeResult, contextOpt)
      case Failure(exception: Throwable) =>
        handleAssignmentWriteFailure(StatusUtils.convertExceptionToStatus(exception))
    }
  }

  /**
   * Informs the generator that an assignment write requested via [[DriverAction.WriteAssignment]]
   * has completed successfully. If the write was committed successfully and `contextOpt` is
   * defined, it will be published using the final assignment. This method also updates and outputs
   * the Prometheus metrics and the assignment change information in `generatorTargetSlicezData`
   * using the statistics information calculated from the successful assignment change.
   */
  private def handleAssignmentWriteSuccess(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      result: Store.WriteAssignmentResult,
      contextOpt: Option[AssignmentGenerationContext]): Unit = {

    // Record the write time so that we can use it to decide when to run the next load balancing
    // reassignment.
    latestAssignmentWriteTimeOpt = Some(tickerTime)

    result match {
      case WriteAssignmentResult.Committed(committedAssignment: Assignment) =>
        logger.info(
          s"Write committed. Based on: ${contextOpt.map(_.baseAssignment.generation)}, " +
          s"committed: ${committedAssignment.generation}"
        )
        val isMeaningfulAssignmentChange: Boolean = contextOpt match {
          case Some(context: AssignmentGenerationContext) =>
            // If the AssignmentGenerationContext is known (i.e. the written assignment was
            // generated based on a predecessor assignment), calculate and export the statistics
            // information for the assignment change.
            val churnAndLoadStats = ReassignmentChurnAndLoadStats.calculate(
              context.baseAssignment,
              committedAssignment,
              context.primaryRateLoadMap
            )
            val totalAdjustedLoad: Double = Algorithm
              .computeAdjustedLoadMap(
                targetConfig.loadBalancingConfig,
                committedAssignment.assignedResources.size,
                context.primaryRateLoadMap
              )
              .getLoad(Slice.FULL)
            val desiredLoad: Algorithm.DesiredLoadRange = Algorithm.calculateDesiredLoadRange(
              targetConfig.loadBalancingConfig,
              committedAssignment.assignedResources.size,
              totalAdjustedLoad
            )

            // Export the metrics to Prometheus.
            TargetMetrics.reportReassignmentStats(
              target,
              churnAndLoadStats,
              desiredLoad
            )

            // Update the generator Slicez data.
            // Since an assignment change has happened, `assignmentChangeStatsOpt` should be
            // updated. We don't update the `GeneratorTagetSlicezData`'s load statistics (e.g.
            // `reportedLoadPerResourceOpt`) here using the LoadMap tracked in
            // `AssignmentGenerationContext` because that LoadMap was the one used to generate the
            // written assignment, and it may be either more stale or more up-to-date compared to
            // the load stats tracked in `generatorTargetSlicezData`. To reduce confusion and
            // prevent the load stats from being moved back in time, we instead just let it be
            // updated periodically by `maybeExportAssignmentSnapshot`.
            generatorTargetSlicezData = generatorTargetSlicezData.copy(
              assignmentChangeStatsOpt = Some(churnAndLoadStats.churnStats)
            )
            outputBuilder.appendAction(
              DriverAction.UpdateAssignmentGeneratorTargetSlicezData(
                generatorTargetSlicezData
              )
            )

            churnAndLoadStats.churnStats.isMeaningfulAssignmentChange
          // The only time `contextOpt` is not defined is when there is no prior assignment which
          // always results in a meaningful assignment change.
          case None => true
        }
        TargetMetrics.incrementNumAssignmentWrites(
          target,
          Code.OK,
          isMeaningfulAssignmentChange
        )
        TargetMetrics.setLatestWrittenGeneration(
          target,
          committedAssignment.generation,
          isMeaningfulAssignmentChange
        )

        // The write succeeded, so we incorporate this latest assignment. Note that while the write
        // was in progress, the state of the target may have meaningfully changes (e.g., fewer
        // resources are now available) and a new assignment may need to be generated in
        // `advanceStateMachine`.
        val syncState =
          SyncAssignmentState.KnownAssignment(committedAssignment)
        handleAssignmentSync(
          tickerTime,
          instant,
          syncState,
          AssignmentDistributionSource.Store,
          outputBuilder
        )

        // Log the assignment update.
        outputBuilder.appendAction(DriverAction.LogAssignment(committedAssignment, contextOpt))
      case WriteAssignmentResult.OccFailure(existingAssignmentGeneration: Generation) =>
        // If the OCC check failed, there is a more recent assignment available in store. We expect
        // the assignment generator will eventually receive the latest assignment and can decide
        // whether we like it or not based on the usual criteria (e.g., does the assignment reflect
        // the currently available resources?) in `advanceStateMachine`.

        logger.info(
          s"Write completed with OCC failure: " +
          s"existingAssignmentGeneration=$existingAssignmentGeneration"
        )
        TargetMetrics.incrementNumAssignmentWrites(
          target,
          Code.FAILED_PRECONDITION,
          isMeaningfulAssignmentChange = false
        )
    }
  }

  /**
   * Informs the generator that an assignment write requested via [[DriverAction.WriteAssignment]]
   * has failed.
   */
  private def handleAssignmentWriteFailure(error: Status): Unit = {
    logger.info(s"Write failed: $error")
    TargetMetrics.incrementNumAssignmentWrites(
      target,
      error.getCode,
      isMeaningfulAssignmentChange = false
    )
    // TODO(<internal bug>) backoff on write failures
  }

  /**
   * Informs the generator that an assignment sync was received from a trusted source. This syncs
   * the in-memory knowledge of the latest assignment using the latest message; in particular, if
   * the assignment is newer than the known latest assignment, distribution is requested.
   */
  private def handleAssignmentSync(
      tickerTime: TickerTime,
      instant: Instant,
      syncState: SyncAssignmentState,
      source: AssignmentDistributionSource.AssignmentDistributionSource,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    val healthWatcherEvent: HealthWatcher.Event.AssignmentSyncObserved = syncState match {
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        val diffIncarnation: Incarnation = diffAssignment.generation.incarnation
        if (diffIncarnation != config.storeIncarnation) {
          val mismatchType: IncarnationMismatchType.Value =
            if (diffIncarnation < config.storeIncarnation) {
              logger.info(s"Got assignment from lower incarnation $diffIncarnation")
              IncarnationMismatchType.ASSIGNMENT_LOWER
            } else {
              logger.warn(s"Got assignment from higher incarnation $diffIncarnation")
              IncarnationMismatchType.ASSIGNMENT_HIGHER
            }
          TargetMetrics.incrementNumAssignmentStoreIncarnationMismatch(
            target,
            mismatchType
          )
        }
        Assignment.fromDiff(
          latestKnownAssignmentOpt,
          diffAssignment
        ) match {
          case Left(newerAssignment: Assignment) =>
            logger.info(s"Distributing from $source: $newerAssignment")
            // Update the metrics that are deterministic only by the new assignment itself. Metrics
            // for stats related to churn (need a previous assignment) are exported in
            // `handleAssignmentWriteSuccess` during slicez data computation, and ones related to
            // load (may shift with time and are thus checked periodically) are exported in
            // `maybeExportAssignmentSnapshot` during slicez data computation.
            TargetMetrics.incrementNumDistributedAssignments(target, source)
            TargetMetrics.setNumAssignmentSlices(target, newerAssignment.sliceMap.entries.size)
            TargetMetrics.setSliceReplicaCountDistribution(target, newerAssignment)
            latestKnownAssignmentOpt = Some(newerAssignment)
            TargetMetrics.setLatestKnownGeneration(target, newerAssignment.generation)
            outputBuilder.appendAction(DriverAction.DistributeAssignment(newerAssignment))
            HealthWatcher.Event.AssignmentSyncObserved
              .AssignmentObserved(source, newerAssignment)
          case Right(reason: DiffUnused.DiffUnused) =>
            // We'll find DIFF_MATCHES_KNOWN from the Store as a matter of course when we write
            // assignments because we learn about the written assignment from the ack to the write
            // _and_ on the Store's watch stream. Avoid polluting the logs in this case.
            if (!(reason == DiffUnused.DIFF_MATCHES_KNOWN
              && source == AssignmentDistributionSource.Store)) {
              logger.info(
                s"Not distributing an assignment from $source because $reason",
                every = 10.seconds
              )
            }
            TargetMetrics.incrementNumUnusedAssignmentDiffs(target, source, reason)
            HealthWatcher.Event.AssignmentSyncObserved
              .GenerationObserved(source, syncState.getKnownGeneration)
        }
      case SyncAssignmentState.KnownGeneration(_) =>
        logger.debug(s"Getting empty assignment from $source", every = 10.seconds)
        HealthWatcher.Event.AssignmentSyncObserved
          .GenerationObserved(source, syncState.getKnownGeneration)
    }

    // Informs the health watcher about the assignment sync.
    handleHealthWatcherOutput(
      tickerTime,
      instant,
      outputBuilder,
      healthWatcher.onEvent(
        tickerTime,
        instant,
        healthWatcherEvent
      )
    )
  }

  /** Informs the generator that it should shut down. */
  private def handleShutdown(outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    iassert(runState == RunState.Running, s"advanceStateMachine called with runState $runState")
    TargetMetrics.clearTerminatedGeneratorFromMetrics(target, resources)
    runState = RunState.Shutdown
    // Stop the watchers because there is no longer a need for these signals.
    outputBuilder.appendAction(DriverAction.StopKubernetesTargetWatcher())
    outputBuilder.appendAction(DriverAction.StopStoreWatcher())

  }

  /**
   * REQUIRES: The assignment generator is in [[RunState.Running]].
   *
   * Given the fact that a signal change has occurred, generates a new assignment and adds an action
   * writing the assignment to `outputBuilder`. The algorithm attempts to minimize churn from the
   * new assignment with respect to [[latestKnownAssignmentOpt]].
   */
  private def generateAssignment(
      tickerTime: TickerTime,
      instant: Instant,
      outputBuilder: StateMachineOutput.Builder[DriverAction],
      resources: Resources): Unit = {
    iassert(runState == RunState.Running)

    latestAssignmentGenerationAttemptTimeOpt = Some(tickerTime)

    val (proposal, contextOpt): (ProposedAssignment, Option[AssignmentGenerationContext]) =
      latestKnownAssignmentOpt match {
        case None =>
          logger.info(s"Generating initial assignment for resources $resources.")
          // No prior assignment; generate from scratch.
          val initialAssignment: SliceMap[ProposedSliceAssignment] =
            TargetMetrics.recordAssignmentGeneratorLatencySync[SliceMap[ProposedSliceAssignment]](
              TargetMetrics.AssignmentGeneratorOpType.GENERATE_INITIAL_ASSIGNMENT,
              target
            ) {
              Algorithm
                .generateInitialAssignment(
                  target,
                  resources,
                  targetConfig.keyReplicationConfig
                )
            }
          (
            ProposedAssignment(
              predecessorOpt = None,
              initialAssignment
            ),
            None
          )
        case Some(latestKnownAssignment: Assignment) =>
          val primaryRateLoadMap: Option[(LoadMap, KeyLoadMap)] =
            TargetMetrics.recordAssignmentGeneratorLatencySync[
              Option[(LoadMap, KeyLoadMap)]
            ](
              TargetMetrics.AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP,
              target
            ) {
              loadWatcher.getPrimaryRateLoadMap(tickerTime, latestKnownAssignment)
            }
          val loadMapOpt: Option[LoadMap] =
            primaryRateLoadMap
              .map {
                case (loadMap: LoadMap, _: KeyLoadMap) => loadMap
              }

          // If we do not have complete load information, we fall back on uniform load.
          val loadMap: LoadMap = loadMapOpt.getOrElse(LoadMap.UNIFORM_LOAD_MAP)
          val proposedSliceMap: SliceMap[ProposedSliceAssignment] =
            TargetMetrics.recordAssignmentGeneratorLatencySync[SliceMap[ProposedSliceAssignment]](
              TargetMetrics.AssignmentGeneratorOpType.GENERATE_ASSIGNMENT,
              target
            ) {
              Algorithm
                .generateAssignment(
                  instant,
                  target,
                  targetConfig,
                  resources,
                  latestKnownAssignment.sliceMap,
                  loadMap
                )
            }

          // If the latest known assignment isn't from this incarnation, don't attempt to claim
          // continuity from the existing assignment in the store, as we can only have continuity
          // between assignments in the same incarnation.
          val proposedAssignment = ProposedAssignment(
            predecessorOpt = latestKnownAssignmentOpt.filter { latestKnownAssignment: Assignment =>
              latestKnownAssignment.generation.incarnation == config.storeIncarnation
            },
            proposedSliceMap
          )

          (
            proposedAssignment,
            Some(
              AssignmentGenerationContext(target, loadMap, baseAssignment = latestKnownAssignment)
            )
          )
      }

    // We might have generated the exact same assignment and metadata which already exists, but we
    // write anyway just for the purpose of having regular, periodic assignment updates.
    assignmentWriteInProgress = true
    outputBuilder.appendAction(
      DriverAction.WriteAssignment(
        proposal,
        contextOpt
      )
    )
  }

  object forTest {
    def onEvent(tickerTime: TickerTime, instant: Instant, event: Event): Output = {
      AssignmentGenerator.this.onEvent(tickerTime, instant, event)
    }

    def onAdvance(tickerTime: TickerTime, instant: Instant): Output = {
      AssignmentGenerator.this.onAdvance(tickerTime, instant)
    }

    /** Returns whether the generator is currently awaiting the outcome of an assignment write. */
    private[assigner] def isAssignmentWriteInProgress: Boolean = {
      assignmentWriteInProgress
    }
  }
}

object AssignmentGenerator {
  type Output = StateMachineOutput[DriverAction]

  /**
   * Generator configuration.
   *
   * @param storeIncarnation                Store incarnation for assignments understood by the
   *                                        generator and distributed for the target.
   * @param loadWatcherStaticConfig         Configuration for Load Watcher.
   * @param clusterUri                      Cluster where this generator is running.
   * @param minAssignmentGenerationInterval The minimum time that must be passed before the
   *        generator tries to generate a new assignment since its last attempt. When the minimum
   *        interval is not satisfied, the assignment generator will skip the assignment generation
   *        unconditionally, and check the generation condition again at the next time satisfying
   *        this interval constrain. Used for rate-limiting the assignment generation.
   *
   *        We want to limit the rate of assignment generation, because too frequent assignment
   *        distribution can harm the downstream store or slicelets. Historical note: we had seen
   *        that frequent and large number of resources' health change led to frequent assignment
   *        change, and the high write rate to etcd store melted down the etcd cluster (see
   *        https://docs.google.com/document/d/1pDlurc6o8ugEREnL8fZb8Yv5VuakxjkZhx36zyV7VC0). We'd
   *        also seen a bug from assignment generation algorithm led to frequent re-assignment, and
   *        the frequent assignment update made the slicelets send excessive watch requests,
   *        resulting in CPU throttling (see
   *        https://docs.google.com/document/d/1N5Ru07me9xyVvztXjKD7q5JM9ZcNTRLgYrKLib2z8AQ).
   */
  case class Config(
      storeIncarnation: Incarnation,
      loadWatcherStaticConfig: LoadWatcher.StaticConfig,
      clusterUri: URI,
      minAssignmentGenerationInterval: FiniteDuration)

  /** Factory methods. */
  object Config {

    /**
     * Creates a [[Config]] from the given store incarnation, load watcher conf, cluster URI,
     * and minimum assignment generation interval.
     */
    def fromConfs(
        storeIncarnation: Incarnation,
        loadWatcherConf: LoadWatcherConf,
        clusterUri: URI,
        minAssignmentGenerationInterval: FiniteDuration): Config = {
      Config(
        storeIncarnation,
        LoadWatcher.StaticConfig.fromConf(loadWatcherConf),
        clusterUri,
        minAssignmentGenerationInterval
      )
    }
  }

  /**
   * The statistics information tracked by the generator for the `target` it is associated with,
   * which is aggregated into [[AssignerTargetSlicezData]] and displayed on the Assigner ZPage.
   * This includes both load statistics, such as reported load, load adjusted with the uniform
   * reserved load, and top keys, and assignment change statistics, such as churn ratios between
   * assignment changes and whether or not an assignment change is meaningful.
   *
   * Stats regarding load information (i.e. `reportedLoadPerResourceOpt`, `reportedLoadPerSliceOpt`,
   * `topKeysOpt`, and `adjustedLoadPerResourceOpt`) are updated periodically based on the
   * generator's latest known LoadMap. Stats regarding assignment change statistics
   * (i.e. `assignmentChangeStatsOpt`) are updated upon a successful write with a predecessor.
   *
   * The fields are declared as separate options because these stats may be updated at different
   * intervals and upon different conditions, and they each need `None` as an initial value.
   *
   * @param reportedLoadPerResourceOpt the reported load per resource aggregated by the assigner
   *                                   for the current assignment.
   * @param reportedLoadPerSliceOpt the reported load per slice aggregated by the assigner for the
   *                                current assignment.
   * @param topKeysOpt the map of top keys associated with the current assignment.
   * @param adjustedLoadPerResourceOpt the load per resource of the current assignment after
   *                                   adjustment for the uniform reserved load.
   * @param assignmentChangeStatsOpt churn ratios for the most recent assignment change and
   *                                 whether the most recent assignment change is meaningful in
   *                                 terms of a change in the resources assigned to any SliceKey.
   */
  case class GeneratorTargetSlicezData(
      reportedLoadPerResourceOpt: Option[Map[Squid, Double]],
      reportedLoadPerSliceOpt: Option[Map[Slice, Double]],
      topKeysOpt: Option[SortedMap[SliceKey, Double]],
      adjustedLoadPerResourceOpt: Option[Map[Squid, Double]],
      assignmentChangeStatsOpt: Option[AssignmentChangeStats]
  )

  object GeneratorTargetSlicezData {

    /** The empty instance of [[GeneratorTargetSlicezData]]. */
    val EMPTY: GeneratorTargetSlicezData = GeneratorTargetSlicezData(
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None,
      adjustedLoadPerResourceOpt = None,
      assignmentChangeStatsOpt = None
    )
  }

  /** Input events to the generator state machine. */
  sealed trait Event
  object Event {

    /** Informs the generator that a watch request was received from a client. */
    case class WatchRequest(request: ClientRequest) extends Event

    /**
     * Informs the generator that an assignment write has completed (either successfully or with
     * an error). The generator unpacks the result and routes it to the appropriate handler.
     * `contextOpt` is propagated from the [[DriverAction.WriteAssignment]] that initiated this
     * write.
     */
    case class AssignmentWriteComplete(
        result: Try[Store.WriteAssignmentResult],
        contextOpt: Option[AssignmentGenerationContext])
        extends Event

    /** Informs the generator of an assignment that was received from some trusted source. */
    case class AssignmentReceived(assignment: Assignment) extends Event

    /**
     * Informs the generator that a termination signal was received from Kubernetes for a given
     * resourceUuid.
     */
    case class TerminationSignalFromKubernetes(resourceUuid: UUID) extends Event

    /** Informs the generator that it should stop generating assignments. */
    case class Shutdown() extends Event

  }

  /**
   * Actions that the generator state machine outputs to the driver in response to incoming signals
   * or the passage of time.
   */
  sealed trait DriverAction
  object DriverAction {

    /**
     * Asks the driver to distribute the given assignment (because it has a higher generation than
     * any assignment previously distributed by the generator). The driver must incorporate the
     * assignment into the storage layer, which maintains a latest-assignment cache.
     *
     * @param assignment The assignment to distribute.
     */
    case class DistributeAssignment(assignment: Assignment) extends DriverAction

    /**
     * Asks the driver to emit a structured log for an assignment update event. This should only be
     * emitted when an assignment generated by this generator is successfully committed to the
     * store. Assignments received from other sources (e.g., store watch) are not logged here, as
     * the originating assigner is responsible for logging them.
     *
     * @param assignment The assignment that was committed.
     * @param contextOpt The context used to generate this assignment.
     */
    case class LogAssignment(
        assignment: Assignment,
        contextOpt: Option[AssignmentGenerationContext])
        extends DriverAction

    /**
     * Asks the driver to write an assignment to the store. The driver must signal
     * [[Event.AssignmentWriteComplete()]] when the write completes. Note that the generator will
     * not attempt concurrent writes, so the generator is effectively frozen until it receives the
     * completion signal. If this is not the
     * initial assignment, `contextOpt` must be defined and is used to export the assignment change
     * metrics to Prometheus and update the [[GeneratorTargetSlicezData]], which is eventually
     * aggregated into the Assigner ZPage, once the write succeeds.
     */
    case class WriteAssignment(
        proposal: ProposedAssignment,
        contextOpt: Option[AssignmentGenerationContext])
        extends DriverAction

    /**
     * Asks the driver to start the a k8s watcher for the given watch target.
     *
     * After outputting this action, the state machine must output [[StopKubernetesTargetWatcher]]
     * before it outputs this event again.
     */
    case class StartKubernetesTargetWatcher(watchTarget: KubernetesWatchTarget) extends DriverAction

    /** Asks the driver to stop the previously started [[KubernetesWatchTarget]], if any. */
    case class StopKubernetesTargetWatcher() extends DriverAction

    /**
     * Asks the driver to start the store watcher.
     *
     * After outputting this action, the state machine must output [[StopStoreWatcher]] before it
     * outputs this event again.
     */
    case class StartStoreWatcher() extends DriverAction

    /** Asks the driver to stop the previously started store watcher, if any. */
    case class StopStoreWatcher() extends DriverAction

    /** Asks the driver to update its internal [[GeneratorTargetSlicezData]]. */
    case class UpdateAssignmentGeneratorTargetSlicezData(
        generatorTargetSlicezData: GeneratorTargetSlicezData
    ) extends DriverAction
  }

  /**
   * Stores different states when an assignment is generated. This allows the updates of
   * [[GeneratorTargetSlicezData]] to use the exact loadMap when the assignment is
   * generated.
   *
   * @param target             the sharded service.
   * @param primaryRateLoadMap latest measurements of the primary rate load.
   */
  case class AssignmentGenerationContext(
      target: Target,
      primaryRateLoadMap: LoadMap,
      baseAssignment: Assignment)

  /** The state of assignment generation. */
  sealed trait RunState
  object RunState {

    /** The generator has not yet started. */
    case object Startup extends RunState

    /**
     * The generator is in its steady-state, generating and distributing assignments from the
     * current store incarnation.
     */
    case object Running extends RunState

    /** The generator has shut down and will not generate further assignments. */
    case object Shutdown extends RunState
  }

  /**
   * An enum encapsulating the generator's decision about whether or not to generate a new
   * assignment.
   */
  sealed trait AssignmentGenerationDecision
  object AssignmentGenerationDecision {

    /**
     * The generator should generate a new assignment using `healthyResources`.
     *
     * `generateReason` is provided for debugging/monitoring.
     */
    case class Generate(healthyResources: Resources, generateReason: GenerateReason)
        extends AssignmentGenerationDecision

    /**
     * The generator should not generate a new assignment, but it may ask for a callback to generate
     * later using `ensureAdvanceByTimeOpt`.
     *
     * `skipReason` is provided for debugging/monitoring.
     */
    case class Skip(ensureAdvanceByTimeOpt: Option[TickerTime], skipReason: SkipReason)
        extends AssignmentGenerationDecision

    /** Reasons to generate an assignment. */
    sealed trait GenerateReason
    object GenerateReason {
      case object FirstAssignment extends GenerateReason
      case object HealthChange extends GenerateReason

      case object LoadBalancing extends GenerateReason
    }

    /** Reasons to skip generating an assignment. */
    sealed trait SkipReason
    object SkipReason {
      case object IncompleteResources extends SkipReason
      case object FrozenAssignment extends SkipReason

      case object InFlightWrite extends SkipReason

      case object KnownHigherIncarnationAssignment extends SkipReason
      case object TooSoonToLoadBalance extends SkipReason
      case class RateLimited(originalGenerationReason: AssignmentGenerationDecision.GenerateReason)
          extends SkipReason {
        override def toString: String = {
          s"RateLimited-$originalGenerationReason"
        }
      }
    }
  }
}
