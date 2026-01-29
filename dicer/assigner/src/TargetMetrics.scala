package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.util.Try

import com.google.common.math.IntMath

import io.grpc.Status.Code
import io.prometheus.client.{Counter, Gauge}

import com.databricks.caching.util.CachingLatencyHistogram
import com.databricks.dicer.assigner.AssignmentGenerator.AssignmentGenerationDecision
import com.databricks.dicer.assigner.AssignmentStats.{
  AssignmentChangeStats,
  AssignmentLoadStats,
  ReassignmentChurnAndLoadStats
}
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap, Resources}
import com.databricks.dicer.common.Assignment.DiffUnused
import com.databricks.dicer.common.{Assignment, Generation, SliceAssignment}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.dicer.friend.Squid

/** Metrics for each sharded target/sharded resource name. */
object TargetMetrics {

  /** An error describing why a watch failed. */
  object WatchError extends Enumeration {
    type WatchError = Value

    /**
     * There was no config known for the target.
     */
    val NO_CONFIG: Value = Value

    /**
     * The target was invalid because the Assigner was configured to validate the request with
     * App Identifier headers and they were not present.
     */
    val INVALID_TARGET_NO_HEADERS: Value = Value

    /**
     * The target was invalid because the Assigner was configured to validate the request with
     * App Identifier headers and the target was not an [[AppTarget]], which is the only
     * [[Target]] type that can be validated against headers.
     */
    val INVALID_TARGET_NOT_APP: Value = Value

    /**
     * The target was invalid because the Assigner was configured to validate the request with
     * App Identifier headers and the App Name header did not match the target name.
     */
    val INVALID_TARGET_NAME_MISMATCH: Value = Value

    /**
     * The target was invalid because the Assigner was configured to validate the request with
     * App Identifier headers and the App Instance ID header did not match the target
     * instance ID.
     */
    val INVALID_TARGET_INSTANCE_ID_MISMATCH: Value = Value
  }

  private val assignmentGenerationDecisions: Counter = Counter
    .build()
    .name("dicer_assigner_assignment_generation_decisions_total")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "decision", "reason")
    .help("Number of assignment generation decisions made for a target")
    .register()

  def incrementAssignmentGenerationDecisionCount(
      target: Target,
      decision: AssignmentGenerationDecision): Unit = {
    val (decisionStr, reason): (String, String) = decision match {
      case AssignmentGenerationDecision
            .Generate(_, generateReason: AssignmentGenerationDecision.GenerateReason) =>
        ("Generate", generateReason.toString)
      case AssignmentGenerationDecision
            .Skip(_, skipReason: AssignmentGenerationDecision.SkipReason) =>
        ("Skip", skipReason.toString)
    }
    assignmentGenerationDecisions
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        decisionStr,
        reason
      )
      .inc()
  }

  object AssignmentDistributionSource extends Enumeration {
    type AssignmentDistributionSource = Value
    val Store, Clerk, Slicelet = Value
  }

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numDistributedAssignments: Counter = Counter
    .build()
    .name("dicer_assigner_num_distributed_assignments")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source")
    .help("Number of assignments that have been distributed (pre-fanout) for a target, by source")
    .register()

  def incrementNumDistributedAssignments(
      target: Target,
      source: AssignmentDistributionSource.AssignmentDistributionSource): Unit = {
    numDistributedAssignments
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString
      )
      .inc()
  }

  private val numAssignmentSlices: Gauge = Gauge
    .build()
    .name("dicer_assigner_num_assignment_slices")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .help("Number of slices in an assignment that has been distributed")
    .register()

  def setNumAssignmentSlices(target: Target, numSlices: Int): Unit = {
    numAssignmentSlices
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(numSlices)
  }

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numUnusedAssignmentDiffs: Counter = Counter
    .build()
    .name("dicer_assigner_num_unused_assignment_diffs")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source", "reason")
    .help(
      "Number of assignments received from some source that are unused for some reason."
    )
    .register()

  def incrementNumUnusedAssignmentDiffs(
      target: Target,
      source: AssignmentDistributionSource.AssignmentDistributionSource,
      reason: DiffUnused.DiffUnused): Unit = {
    numUnusedAssignmentDiffs
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString,
        reason.toString
      )
      .inc()
  }

  /**
   *  An indicator for the direction of mismatch when the generator encounters an assignment outside
   * of its configured Incarnation.
   */
  object IncarnationMismatchType extends Enumeration {

    /** The encountered assignment has a higher incarnation than the generator. */
    val ASSIGNMENT_HIGHER: Value = Value

    /** The encountered assignment has a lower incarnation than the generator. */
    val ASSIGNMENT_LOWER: Value = Value
  }

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numAssignmentStoreIncarnationMismatch: Counter = Counter
    .build()
    .name("dicer_assigner_num_store_incarnation_mismatch")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "mismatchType")
    .help(
      "Number of assignments received from some source that belong to a different store " +
      "incarnation."
    )
    .register()

  def incrementNumAssignmentStoreIncarnationMismatch(
      target: Target,
      mismatchType: IncarnationMismatchType.Value): Unit = {
    numAssignmentStoreIncarnationMismatch
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        mismatchType.toString
      )
      .inc()
  }

  // Note: the new isMeaningfulAssignmentChange label will cause metrics before this change to not
  // have this label.
  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numAssignmentWrites: Counter = Counter
    .build()
    .name("dicer_assigner_num_assignment_writes")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "canonicalCode",
      "isMeaningfulAssignmentChange"
    )
    .help(
      "Number of assignment writes that have completed for a target by canonical status code" +
      " and if the new assignment changes the slicemap from the last known assignment. " +
      "OCC check failures are recorded as FAILED_PRECONDITION."
    )
    .register()

  def incrementNumAssignmentWrites(
      target: Target,
      code: Code,
      isMeaningfulAssignmentChange: Boolean): Unit = {
    numAssignmentWrites
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        code.toString,
        isMeaningfulAssignmentChange.toString
      )
      .inc()
  }

  private val podSetSizeReported: Gauge = Gauge
    .build()
    .name("dicer_assigner_reported_health_status_podset_size")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "healthStatus")
    .help("Number of resources for the target whose Slicelets reported healthStatus status")
    .register()

  private val podSetSizeComputed: Gauge = Gauge
    .build()
    .name("dicer_assigner_podset_size")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "healthStatus")
    .help("Number of resources for the target with the given healthStatus")
    .register()

  /** Sets the counts of pods whose status was reported and computed as [[healthStatus]] */
  def setPodSetSize(
      target: Target,
      healthStatus: HealthWatcher.HealthStatus,
      reportedCount: Int,
      computedCount: Int): Unit = {

    podSetSizeReported
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        healthStatus.toString
      )
      .set(reportedCount)

    podSetSizeComputed
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        healthStatus.toString
      )
      .set(computedCount)
  }

  private val healthStatusComputedDiffersFromReported: Counter = Counter
    .build()
    .name("dicer_assigner_health_status_computed_differs_from_reported_total")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "reportedStatus",
      "computedStatus"
    )
    .help("Number of times the Slicelet-reported vs Assigner-computed health statuses differ")
    .register()

  def incrementHealthStatusComputedDiffersFromReported(
      target: Target,
      healthStatusReport: HealthWatcher.HealthStatusReport): Unit = {
    healthStatusComputedDiffersFromReported
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        healthStatusReport.reportedBySlicelet.toString,
        healthStatusReport.computed.toString
      )
      .inc()
  }

  private val numActiveGenerators: Gauge = Gauge
    .build()
    .help("The number of active generators")
    .name("dicer_assigner_num_active_generators")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  def incrementNumActiveGenerators(target: Target): Unit = {
    numActiveGenerators
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .inc()
  }

  /** Counter tracking the total number of generators removed (across all targets). */
  private val generatorsRemovedTotal: Counter = Counter
    .build()
    .help("Total number of assignment generators removed")
    .name("dicer_assigner_generators_removed_total")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "reason")
    .register()

  def incrementGeneratorsRemoved(target: Target, removalReason: GeneratorShutdownReason): Unit = {
    generatorsRemovedTotal
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        removalReason.toString
      )
      .inc()
  }

  /** Gauge tracking the current number of targets with active generators. */
  private val targetsWithActiveGenerators: Gauge = Gauge
    .build()
    .help("Current number of targets with active generators (size of generatorMap)")
    .name("dicer_assigner_targets_with_active_generators")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * Updates the active targets with generators gauge after a generator is added or removed.
   *
   * @param target The target whose active generator count is being updated.
   * @param activeCount The current number of active generators for the target.
   */
  def updateTargetsWithActiveGenerators(target: Target, activeCount: Int): Unit = {
    targetsWithActiveGenerators
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(activeCount)
  }

  /**
   * 10 buckets chosen to be exponentially spaced from 1ms to 30s for measuring
   * assignment generator latency.
   *
   * 1, 3.14, 9.88, 31.07, 97.68, 307.11, 965.49 (milliseconds)
   * 3.035, 9.543, 30 (seconds)
   */
  private val assignmentGeneratorLatencyHistogramBuckets: Vector[Double] = {
    val minSeconds = 0.001 // 1 ms
    val maxSeconds = 30.0 // 30 seconds
    val numBuckets = 10
    (0 until numBuckets).map { i: Int =>
      minSeconds * math.pow(maxSeconds / minSeconds, i.toDouble / (numBuckets - 1))
    }.toVector
  }

  /**
   * Histogram tracking the latency of assignment generator's operations. Note the
   * operations recorded are not the actions of the assignment generator driver, but
   * rather the critical function calls or steps executed inside of the assignment generator
   * state machine.
   */
  private val assignmentGeneratorLatencyHistogram: CachingLatencyHistogram =
    CachingLatencyHistogram(
      "dicer_assigner_generator_latency_secs",
      Seq("targetCluster", "targetName", "targetInstanceId"),
      customBucketsSecs = assignmentGeneratorLatencyHistogramBuckets
    )

  /**
   * The assignment generator operations whose latencies are described in the
   * assignmentGeneratorLatencyHistogram.
   */
  object AssignmentGeneratorOpType extends Enumeration {

    /** An operation that generates an assignment from scratch. */
    val GENERATE_INITIAL_ASSIGNMENT: AssignmentGeneratorOpType.Value = Value(
      "generateInitialAssignment"
    )

    /** An operation that generates an assignment from a pre-existing one. */
    val GENERATE_ASSIGNMENT: AssignmentGeneratorOpType.Value = Value("generateAssignment")

    /** An operation that gets the primary rate load map per slice. */
    val GET_PRIMARY_RATE_LOAD_MAP: AssignmentGeneratorOpType.Value = Value("getPrimaryRateLoadMap")

    /** An operation that records and updates the load reports from a watch request. */
    val REPORT_LOAD: AssignmentGeneratorOpType.Value = Value("reportLoad")
  }

  /**
   * Invokes the given `thunk` synchronously on the calling thread, and records the latency of the
   * operation in the `assignmentGeneratorLatencyHistogram` with the corresponding `operation` and
   * `target` labels. Returns the result of the `thunk`.
   */
  def recordAssignmentGeneratorLatencySync[T](
      operation: AssignmentGeneratorOpType.Value,
      target: Target)(thunk: => T): T = {
    val computeExtraLabels: Try[T] => Seq[String] = { _: Try[T] =>
      Seq(target.getTargetClusterLabel, target.getTargetNameLabel, target.getTargetInstanceIdLabel)
    }
    assignmentGeneratorLatencyHistogram.recordLatencySync(operation.toString, computeExtraLabels)(
      thunk
    )
  }

  // Metrics for the key of death detector.

  /**
   * The per-target key of death heuristic value. Refer to [[KeyOfDeathDetector]]
   * for more details.
   */
  private val keyOfDeathHeuristic: Gauge = Gauge
    .build()
    .name("dicer_assigner_key_of_death_heuristic_gauge")
    .help("Key of death heuristic value for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * The per-target number of crashed resources over the past
   * KeyOfDeathDetector.config.crashRecordRetention` period. Refer to [[KeyOfDeathDetector.Config]]
   * for more details.
   */
  private val numCrashedResourcesGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_num_crashed_resources_gauge")
    .help("Current number of crashed resources tracked for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  private val numCrashedResourcesTotal: Counter = Counter
    .build()
    .name("dicer_assigner_num_crashed_resources_total")
    .help("Total number of crashed resources tracked for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * The per-target estimated resource workload size tracked by the key of death detector.
   * Refer to [[KeyOfDeathDetector]] for more details.
   */
  private val estimatedResourceWorkloadSize: Gauge = Gauge
    .build()
    .name("dicer_assigner_estimated_resource_workload_size_gauge")
    .help("Estimated resource workload size for each target tracked by the key of death detector.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * The number of times the key of death detector has transitioned between its states for each
   * target, indicating the starts and stops of key of death scenarios.
   */
  private val keyOfDeathStateTransitions: Counter = Counter
    .build()
    .name("dicer_assigner_key_of_death_state_transitions_total")
    .help("Number of times the key of death state has transitioned for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "transitionType")
    .register()

  /** The type of transition that occurred in the key of death state. */
  sealed trait KeyOfDeathTransitionType
  object KeyOfDeathTransitionType {

    /**
     * The key of death state transitioned from Stable to Endangered, signaling that a crash
     * has occurred, potentially leading to a key of death scenario.
     */
    case object STABLE_TO_ENDANGERED extends KeyOfDeathTransitionType

    /**
     * The key of death state transitioned from Endangered to Stable, signaling that no new crashes
     * have occurred for the past `crashRecordRetention` period.
     */
    case object ENDANGERED_TO_STABLE extends KeyOfDeathTransitionType

    /**
     * The key of death state transitioned from Endangered to Poisoned, signaling that a key of
     * death scenario has started.
     */
    case object ENDANGERED_TO_POISONED extends KeyOfDeathTransitionType

    /**
     * The key of death state transitioned from Poisoned to Stable, signaling the end of
     * a key of death scenario.
     */
    case object POISONED_TO_STABLE extends KeyOfDeathTransitionType
  }

  /**
   * Updates the metrics related to the key of death detector for the given target when
   * a heuristic check is done to see whether a key of death scenario has started or stopped.
   * This includes:
   *  - the key of death heuristic value,
   *  - the estimated resource workload size, and
   *  - the current number of crashed resources.
   * The total number of crashed resources is updated separately, as it is not a direct result
   * of a key of death scenario check, but rather the result of a health report update.
   */
  def onKeyOfDeathCheck(
      target: Target,
      heuristicValue: Double,
      estimatedWorkloadSize: Int,
      numCrashedResources: Int): Unit = {
    keyOfDeathHeuristic
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(heuristicValue)
    estimatedResourceWorkloadSize
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(estimatedWorkloadSize.toDouble)
    numCrashedResourcesGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(numCrashedResources.toDouble)
  }

  def incrementNumCrashedResourcesTotal(target: Target, numCrashed: Int): Unit = {
    numCrashedResourcesTotal
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .inc(numCrashed.toDouble)
  }

  def incrementKeyOfDeathStateTransitions(
      target: Target,
      transitionType: KeyOfDeathTransitionType): Unit = {
    keyOfDeathStateTransitions
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        transitionType.toString
      )
      .inc()
  }

  // Metrics for generation info.

  private val latestWrittenAssignmentIncarnationGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_latest_written_assignment_incarnation_gauge")
    .help("Generation incarnation of the latest written assignment for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  private val latestWrittenGenerationNumberGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_latest_written_assignment_generation_gauge")
    .help(
      "Generation number of the latest written assignment for each target and if the new " +
      "assignment changes the slicemap from the last known assignment."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "isMeaningfulAssignmentChange")
    .register()

  def setLatestWrittenGeneration(
      target: Target,
      generation: Generation,
      isMeaningfulAssignmentChange: Boolean): Unit = {
    latestWrittenAssignmentIncarnationGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(generation.incarnation.value)
    latestWrittenGenerationNumberGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        isMeaningfulAssignmentChange.toString
      )
      .set(generation.number.value)
  }

  // Note: This can diverge from the latest written generation when the Assigner is not the
  // preferred Assigner or the Assigner is starting up and is notified of an assignment through the
  // store or a slicelet.
  private val latestKnownGenerationIncarnationGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_latest_known_assignment_incarnation_gauge")
    .help("Generation incarnation of the latest known assignment for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  // Note: This can diverge from the latest written generation when the Assigner is not the
  // preferred Assigner or the Assigner is starting up and is notified of an assignment through the
  // store or a slicelet.
  private val latestKnownGenerationGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_latest_known_assignment_generation_gauge")
    .help("Generation number of the latest known assignment for each target.")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  def setLatestKnownGeneration(target: Target, generation: Generation): Unit = {
    latestKnownGenerationIncarnationGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(generation.incarnation.value)
    latestKnownGenerationGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(generation.number.value)
  }

  // Metrics for churn info.

  /**
   * Metrics useful for churn ratio: a gauge for the current value, as well as a counter for rate.
   */
  private case class ChurnRatioMetric(gauge: Gauge, counter: Counter) {

    /**
     * Report churn for the given target's most recent assignment. Updates [[gauge]] and
     * [[counter]].
     */
    def report(target: Target, churn: Double): Unit = {
      gauge
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel
        )
        .set(churn)
      counter
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel
        )
        .inc(churn)
    }
  }

  private val removalChurnRatio: ChurnRatioMetric = ChurnRatioMetric(
    Gauge
      .build()
      .name("dicer_assigner_churn_removal_gauge")
      .help("Churn ratio due to removed nodes for the most recent assignment for each target")
      .labelNames("targetCluster", "targetName", "targetInstanceId")
      .register(), {
      @SuppressWarnings(
        Array(
          "BadMethodCall-PrometheusCounterNamingConvention",
          "reason: Renaming existing prod metric would break dashboards and alerts"
        )
      )
      val counter = Counter
        .build()
        .name("dicer_assigner_churn_removal_counter")
        .help("Cumulative removed nodes churn ratio for a target's assignments")
        .labelNames("targetCluster", "targetName", "targetInstanceId")
        .register()
      counter
    }
  )

  private val additionChurnRatio: ChurnRatioMetric = ChurnRatioMetric(
    Gauge
      .build()
      .name("dicer_assigner_churn_addition_gauge")
      .help("Churn ratio due to added nodes for the most recent assignment for each target")
      .labelNames("targetCluster", "targetName", "targetInstanceId")
      .register(), {
      @SuppressWarnings(
        Array(
          "BadMethodCall-PrometheusCounterNamingConvention",
          "reason: Renaming existing prod metric would break dashboards and alerts"
        )
      )
      val counter = Counter
        .build()
        .name("dicer_assigner_churn_addition_counter")
        .help("Cumulative added nodes churn ratio for a target's assignments")
        .labelNames("targetCluster", "targetName", "targetInstanceId")
        .register()
      counter
    }
  )

  private val loadBalancingChurnRatio: ChurnRatioMetric = ChurnRatioMetric(
    Gauge
      .build()
      .name("dicer_assigner_churn_load_balancing_gauge")
      .help("Churn ratio due to load balancing for the most recent assignment for each target")
      .labelNames("targetCluster", "targetName", "targetInstanceId")
      .register(), {
      @SuppressWarnings(
        Array(
          "BadMethodCall-PrometheusCounterNamingConvention",
          "reason: Renaming existing prod metric would break dashboards and alerts"
        )
      )
      val counter = Counter
        .build()
        .name("dicer_assigner_churn_load_balancing_counter")
        .help("Cumulative load balancing churn ratio for a target's assignments")
        .labelNames("targetCluster", "targetName", "targetInstanceId")
        .register()
      counter
    }
  )

  /*
   * Metrics for per-resource load distribution.
   * We export the following per-resource load metrics:
   *  - the per-resource load stats at the moment a new assignment is generated.
   *  - the per-resource load stats that is periodically updated while the assignment is in use.
   * Note that the difference between [[perResourceLoadAfterGeneration]] and
   * [[realTimePerResourceLoad]], as well as [[staticLoadPerResourceAtGeneration]] and
   * [[realTimeStaticPerResourceLoad]] is that the former contains stats at the time of new
   * assignment generation, while the latter is periodically updated regardless of assignment
   * changes.
   *
   * To avoid overly high cardinality of metrics, we create an integer `resourceHash` for each
   * resource.
   */
  private val perResourceLoadAfterGeneration =
    Gauge
      .build()
      .name("dicer_assigner_per_resource_load_after_generation")
      .help(
        "Per resource load from the new assignment the moment it is generated."
      )
      .labelNames("targetCluster", "targetName", "targetInstanceId", "resourceHash")
      .register()

  private val staticPerResourceLoadAtGeneration =
    Gauge
      .build()
      .name("dicer_assigner_static_per_resource_load_at_generation")
      .help(
        "Hypothetical load of an assignment that assigns same number of keys to each " +
        "resource the moment a new assignment is generated."
      )
      .labelNames("targetCluster", "targetName", "targetInstanceId", "resourceHash")
      .register()

  // Track the maximum and minimum desired load for the current assignment at the time it is
  // generated.
  private val maxDesiredLoadAtGeneration = Gauge
    .build()
    .name("dicer_assigner_max_desired_load_at_generation")
    .help(
      "Maximum desired load for the current assignment at the time it is generated."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  private val minDesiredLoadAtGeneration = Gauge
    .build()
    .name("dicer_assigner_min_desired_load_at_generation")
    .help(
      "Minimum desired load for the current assignment at the time it is generated."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /** Tracks the number of slices assigned to each resource. */
  private val numAssignedSlicesPerResource = Gauge
    .build()
    .name("dicer_assigner_num_assigned_slices_per_resource")
    .help("The number of slices assigned to a resource.")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "resourceHash")
    .register()

  /**
   * Reports Prometheus metrics for the statistics regarding an assignment change. The metrics are:
   *  - Churn ratios (percentage of load shift because of the reassignment), indicated by
   *    `churnAndLoadStats.churnStats`.
   *  - The per-resource load measurement based on the previous assignment and the new load
   *    distribution, indicated by `churnAndLoadStats.loadStatsBefore`.
   *  - The per-resource load measurement based on the new assignment and load distribution,
   *    indicated by `churnAndLoadStats.loadStatsAfter`.
   *  - The hypothetical per-resource load measurements assuming that the same number of SliceKeys
   *    are assigned to each resource in the new assignment, indicated by
   *    `churnAndLoadStats.loadStatsUniformKeyAssignment`.
   *  - Per-resource min and max desired loads that were used to generate the new assignment,
   *    indicated by `desiredLoad`.
   *  - The number of assigned slices per resource in the new assignment, included in
   *    `churnAndLoadStats.loadStatsAfter`.
   */
  def reportReassignmentStats(
      target: Target,
      churnAndLoadStats: ReassignmentChurnAndLoadStats,
      desiredLoad: Algorithm.DesiredLoadRange): Unit = {

    // Export churn ratios.
    val churnStats: AssignmentChangeStats = churnAndLoadStats.churnStats
    removalChurnRatio.report(target, churnStats.removalChurnRatio)
    additionChurnRatio.report(target, churnStats.additionChurnRatio)
    loadBalancingChurnRatio.report(target, churnStats.loadBalancingChurnRatio)

    // Export per-resource load metrics for the current assignment and the hypothetical assignment,
    // which contains the same resources as the current assignment.
    val loadStatsAfter: AssignmentLoadStats = churnAndLoadStats.loadStatsAfter
    val loadStatsUniformKeyAssignment: AssignmentLoadStats =
      churnAndLoadStats.loadStatsUniformKeyAssignment
    val currentResourceMapping: Map[Squid, Int] =
      computeResourceHashes(loadStatsAfter.loadByResource.keySet)
    for (entry: (Squid, Double) <- loadStatsAfter.loadByResource) {
      val (resourceAfter, loadAfter): (Squid, Double) = entry
      perResourceLoadAfterGeneration
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel,
          currentResourceMapping(resourceAfter).toString
        )
        .set(loadAfter)
      staticPerResourceLoadAtGeneration
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel,
          currentResourceMapping(resourceAfter).toString
        )
        .set(loadStatsUniformKeyAssignment.loadByResource(resourceAfter))
      numAssignedSlicesPerResource
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel,
          currentResourceMapping(resourceAfter).toString
        )
        .set(loadStatsAfter.numOfAssignedSlicesByResource(resourceAfter))
    }

    // We need to stop publishing metrics for removed resources.
    val loadStatsBefore: AssignmentLoadStats = churnAndLoadStats.loadStatsBefore
    val previousResourceMapping: Map[Squid, Int] =
      computeResourceHashes(loadStatsBefore.loadByResource.keySet)
    val removedHashes: Set[Int] =
      previousResourceMapping.values.toSet.diff(currentResourceMapping.values.toSet)
    clearRemovedResourcesFromMetrics(target, removedHashes)

    // Export min/max desired load metrics.
    maxDesiredLoadAtGeneration
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(desiredLoad.maxDesiredLoad)
    minDesiredLoadAtGeneration
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(desiredLoad.minDesiredLoadExistingResource)
  }

  /**
   * The two types of load that are tracked for each resource: reported load and reserved load.
   * Reported load is the load that is reported by the resource itself, while reserved load is the
   * maximum load uniformly distributed across all keys. The sum of these two loads is the adjusted
   * load for the resource, which is used by the Assigner to make decisions.
   */
  object LoadType extends Enumeration {
    type LoadType = Value
    val Reported, Reserved = Value
  }

  /**
   * A metric that tracks the per-resource real-time load for the current assignment. The metric
   * contains two types of load: reported load and reserved load. These metrics are combined into a
   * single metric to ensure that they are updated together.
   */
  private val realTimeLoadPerResource = Gauge
    .build()
    .name("dicer_assigner_real_time_load_per_resource")
    .help(
      "Per resource load for the assignment that is periodically updated when this" +
      "assignment is in use."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "resourceHash", "loadType")
    .register()

  /** The Slice "buckets" over which load is measured. */
  private val SLICE_LOAD_BUCKETS: Vector[LoadDistributionHistogramBucket] = {
    val BUCKET_COUNT: Int = 64
    val slices: Vector[Slice] = LoadMap.getUniformPartitioning(BUCKET_COUNT)
    val buckets = Vector.newBuilder[LoadDistributionHistogramBucket]
    buckets.sizeHint(BUCKET_COUNT)
    for (i: Int <- 0 until BUCKET_COUNT) {
      val slice: Slice = slices(i)

      // Map the upper bound for the current Slice to a number in (0.0, 1.0], since the "le" label
      // values for the histogram we are exporting will be in that range. The final bucket is
      // labeled "+Inf" in Prometheus.
      val leLabelValue: String =
        if (i == BUCKET_COUNT - 1) "+Inf" else ((i + 1).toDouble / BUCKET_COUNT).toString
      buckets += new LoadDistributionHistogramBucket(slice, leLabelValue)
    }
    buckets.result()
  }

  /**
   * A bucket for [[assignmentLoadDistributionHistogram]] over which load is measured.
   *
   * @param slice        the Slice corresponding to the current bucket.
   * @param leLabelValue the value to use for the corresponding Prometheus histogram bucket label.
   *                     "le" means less than or equal to, because bucket in these histograms
   *                     accumulates all values that are less than or equal to the bucket's boundary
   *                     value.
   */
  private class LoadDistributionHistogramBucket(val slice: Slice, val leLabelValue: String)

  /**
   * A metric that shows an overview of the load distribution across the
   * [[com.databricks.dicer.external.SliceKey]] key space as recorded in the latest assignment. Note
   * that it is a gauge metric, because even though Dicer's current load metric is a rate, this
   * metric is only tracks what's in the latest assignment at any given moment in time.
   */
  private val assignmentLoadDistributionHistogram: Gauge = Gauge
    .build()
    // We use the "_bucket" suffix because we are mimicking a Prometheus histogram.
    .name("dicer_assigner_assignment_load_distribution_bucket")
    .help("Distribution of load across the assignment.")
    // The first label "le" represents the histogram bucket. A bucket contains the total value of
    // all load attributed to keys that are less than the specified label value, when we map
    // SliceKeys to values in [0, 1.0]. For example, when le=0.25, the value indicates the total
    // load attributed to keys in ["" -- 0x4000000000000000].
    .labelNames("le", "targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * Number of top keys for a target. Note that in addition to the top keys being reported by the
   * Slicelet, the config(s) for top keys must be enabled on the Assigner, for this metric to be
   * non-zero.
   */
  private val numTopKeys = Gauge
    .build()
    .name("dicer_assigner_num_top_keys")
    .help("Number of top keys in each target")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /**
   * Reports Prometheus metrics for statistics regarding the latest-known assignment and the load
   * distribution snapshot at a given time. The metrics are:
   *  - The per-resource load measurements at the moment the assignment is in use, which includes
   *    the reported load and the reserved load.
   *  - The load distribution of the assignment.
   *  - The number of top keys in the assignment.
   *
   * @param reportedLoadStats the per-resource load measurements at the moment the assignment is in
   *                          use, which includes only the reported load.
   * @param adjustedLoadStats the per-resource adjusted load measurements at the moment the
   *                          assignment is in use, which is the sum of the reported load and the
   *                          reserved load.
   * @param loadMap the load map from which the load measurements were calculated and will be used
   *                to report the load distribution.
   * @param numberOfTopKeys the number of top keys reported at the time of the snapshot.
   */
  def reportAssignmentSnapshotStats(
      target: Target,
      reportedLoadStats: AssignmentLoadStats,
      adjustedLoadStats: AssignmentLoadStats,
      loadMap: LoadMap,
      numberOfTopKeys: Int): Unit = {

    // Export per-resource load metrics.
    val resourceMapping: Map[Squid, Int] =
      computeResourceHashes(reportedLoadStats.loadByResource.keySet)
    for (entry: (Squid, Double) <- reportedLoadStats.loadByResource) {
      val (resource, load): (Squid, Double) = entry
      val resourceHash: Int = resourceMapping(resource)
      realTimeLoadPerResource
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel,
          resourceHash.toString,
          LoadType.Reported.toString
        )
        .set(load)

      val reservedLoad: Double = adjustedLoadStats.loadByResource(resource) - load
      realTimeLoadPerResource
        .labels(
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel,
          resourceHash.toString,
          LoadType.Reserved.toString
        )
        .set(reservedLoad)
    }

    // Export load distribution metrics.
    updateLoadDistributionHistogram(target, loadMap)

    // Export hot key metrics.
    numTopKeys
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(numberOfTopKeys)
  }

  /** Buckets we use for tracking number of assigned slices by replica count. */
  private val REPLICA_COUNT_BUCKETS = Set[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64, 128)

  /**
   * Histogram tracking the number of slices in the assignment by replica count. Note that we don't
   * use a Prometheus histogram here, but rather a gauge that mimics a histogram. This is because
   * this metric tracks the slice replica count information in an assignment at any given time (i.e.
   * a snapshot) and requires the buckets to be gauges, while the Prometheus histogram's buckets are
   * actually counters.
   */
  private val sliceReplicaCountHistogram: Gauge = Gauge
    .build()
    // We use the "_bucket" suffix because we are mimicking a Prometheus histogram.
    .name("dicer_assigner_slice_replica_count_distribution_bucket")
    .help("Distribution of slices by replica count in the current assignment.")
    // The first label "le" represents the histogram bucket. A bucket contains the total count of
    // all slices whose replica counts are less than or equal to the specified label value.
    .labelNames("le", "targetCluster", "targetName", "targetInstanceId")
    .register()

  /** Sets the replica count distribution in [[sliceReplicaCountHistogram]]. */
  def setSliceReplicaCountDistribution(target: Target, assignment: Assignment): Unit = {
    // Created a sorted map of replica counts to the number of slices with that replica count. We'll
    // traverse this in replica count order to populate the histogram with cumulative slice counts
    // for each bucket.
    val replicaCounts = mutable.SortedMap[Int, Int](
      assignment.sliceAssignments
        .groupBy((_: SliceAssignment).resources.size)
        .mapValues((_: Iterable[SliceAssignment]).size)
        .toSeq: _*
    )
    // Go through all entries in the map in order and keep a running total of the number of slices.
    // Whenever we hit a defined bucket for the histogram, we record the current total in the
    // histogram (first make sure that all defined histogram buckets are included in
    // `replicaCounts`).
    //
    // For example:
    //
    //  replicaCounts(K):      1     3           7                12                      18
    //  replicaCounts(V):      3     2           5                 4                       7
    //  histogram buckets:     1  2  3  4           8                             16
    //
    //  ... merge histogram buckets into replicaCounts(K) ...
    //
    //  replicaCounts(K)':     1  2  3  4        7  8             12              16      18
    //  replicaCounts(V)':     3  0  2  0        5  0              4              0        7
    //
    //  ... go through replicaCounts(K)', keep a running sum of replicaCounts(V)'
    //  ... (cumulativeSliceCount), and if we encounter a histogram bucket,
    //  ... emit the bucket's value as cumulativeSliceCount ...
    //
    //  replicaCounts(K)':     1  2  3  4        7  8             12              16      18
    //  cumulativeSliceCount:  3  3  5  5       10 10             14              14      21
    //  histogram buckets:     1  2  3  4           8                             16           +Inf
    //  histogram values:      3  3  5  5          10                             14            21
    for (bucket: Int <- REPLICA_COUNT_BUCKETS) {
      replicaCounts.getOrElseUpdate(bucket, 0)
    }
    var cumulativeSliceCount: Int = 0
    for (entry <- replicaCounts) {
      val (replicaCount, sliceCount): (Int, Int) = entry
      cumulativeSliceCount += sliceCount
      if (REPLICA_COUNT_BUCKETS.contains(replicaCount)) {
        sliceReplicaCountHistogram
          .labels(
            replicaCount.toDouble.toString,
            target.getTargetClusterLabel,
            target.getTargetNameLabel,
            target.getTargetInstanceIdLabel
          )
          .set(cumulativeSliceCount)
      }
    }
    // Set the +Inf bucket to the total number of slices
    sliceReplicaCountHistogram
      .labels(
        "+Inf",
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .set(cumulativeSliceCount)
  }

  private val numTargetWatchErrors: Counter = Counter
    .build()
    .name("dicer_assigner_num_watch_errors_total")
    .help("Number of target watch errors due to the labelled reason")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "reason")
    .register()

  def incrementNumTargetWatchErrors(target: Target, reason: WatchError.Value): Unit = {
    numTargetWatchErrors
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        reason.toString
      )
      .inc()
  }

  /**
   * Clears gauge metrics tied to the lifecycle of the assignment generator for the given `target`
   * and the currently known `resourcesOpt` to prevent stale metrics from being retained after the
   * generator shuts down.
   */
  def clearTerminatedGeneratorFromMetrics(target: Target, resources: Resources): Unit = {
    val targetClusterLabel: String = target.getTargetClusterLabel
    val targetNameLabel: String = target.getTargetNameLabel
    val targetInstanceIdLabel: String = target.getTargetInstanceIdLabel
    // Decrement the number of active generators.
    decrementNumActiveGenerators(target)

    // Clear the metrics that are updated when an assignment is generated.
    numAssignmentSlices.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    for (healthStatus <- HealthWatcher.HealthStatus.values) {
      podSetSizeComputed.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        healthStatus.toString
      )
      podSetSizeReported.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        healthStatus.toString
      )

      for (maskedStatus <- HealthWatcher.HealthStatus.values) {
        healthStatusComputedDiffersFromReported.remove(
          targetClusterLabel,
          targetNameLabel,
          targetInstanceIdLabel,
          healthStatus.toString,
          maskedStatus.toString
        )
      }
    }

    // Clean up the metrics that reflect the latest written assignment. Theoretically, we could
    // keep these metrics around, but it might be confusing because people might think that the
    // latest written assignment does not have the generation/incarnation number updated.
    latestWrittenAssignmentIncarnationGauge.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel
    )
    for (isMeaningfulAssignmentChange <- Seq(true, false)) {
      val meaningfulAssignmentChange: Boolean = isMeaningfulAssignmentChange
      latestWrittenGenerationNumberGauge.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        meaningfulAssignmentChange.toString
      )
    }

    // Clean up the metrics that reflect the latest known assignment.
    latestKnownGenerationIncarnationGauge.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel
    )
    latestKnownGenerationGauge.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)

    // Clean up the churn related gauges.
    removalChurnRatio.gauge.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    additionChurnRatio.gauge.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    loadBalancingChurnRatio.gauge.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)

    // Clean up per-resource load metrics.
    val resourceHashes: Seq[Int] = computeResourceHashes(resources.availableResources).values.toSeq
    for (resourceHash: Int <- resourceHashes) {
      perResourceLoadAfterGeneration.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        resourceHash.toString
      )
      staticPerResourceLoadAtGeneration.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        resourceHash.toString
      )
      realTimeLoadPerResource.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        resourceHash.toString,
        LoadType.Reported.toString
      )
      realTimeLoadPerResource.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        resourceHash.toString,
        LoadType.Reserved.toString
      )
      numAssignedSlicesPerResource.remove(
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel,
        resourceHash.toString
      )
    }

    // Clean up load distribution metrics.
    for (bucket <- SLICE_LOAD_BUCKETS) {
      assignmentLoadDistributionHistogram.remove(
        bucket.leLabelValue,
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel
      )
    }
    numTopKeys.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    maxDesiredLoadAtGeneration.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    minDesiredLoadAtGeneration.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)

    // Clean up replica count distribution metrics.
    for (bucketLabel: String <- REPLICA_COUNT_BUCKETS.map(_.toDouble.toString) ++ Seq("+Inf")) {
      sliceReplicaCountHistogram.remove(
        bucketLabel,
        targetClusterLabel,
        targetNameLabel,
        targetInstanceIdLabel
      )
    }

    // Clean up key of death detector metrics.
    keyOfDeathHeuristic.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    numCrashedResourcesGauge.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    numCrashedResourcesTotal.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    estimatedResourceWorkloadSize.remove(targetClusterLabel, targetNameLabel, targetInstanceIdLabel)
    keyOfDeathStateTransitions.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel,
      KeyOfDeathTransitionType.STABLE_TO_ENDANGERED.toString
    )
    keyOfDeathStateTransitions.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel,
      KeyOfDeathTransitionType.ENDANGERED_TO_STABLE.toString
    )
    keyOfDeathStateTransitions.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel,
      KeyOfDeathTransitionType.ENDANGERED_TO_POISONED.toString
    )
    keyOfDeathStateTransitions.remove(
      targetClusterLabel,
      targetNameLabel,
      targetInstanceIdLabel,
      KeyOfDeathTransitionType.POISONED_TO_STABLE.toString
    )
  }

  private def decrementNumActiveGenerators(target: Target): Unit = {
    numActiveGenerators
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .dec()
  }

  /** Clears metrics associated with resources that have been removed from the target.  */
  private def clearRemovedResourcesFromMetrics(target: Target, removedHashes: Set[Int]): Unit = {
    val clusterLabel: String = target.getTargetClusterLabel
    val nameLabel: String = target.getTargetNameLabel
    val instanceIdLabel: String = target.getTargetInstanceIdLabel
    for (hash <- removedHashes) {
      perResourceLoadAfterGeneration.remove(clusterLabel, nameLabel, instanceIdLabel, hash.toString)
      staticPerResourceLoadAtGeneration.remove(
        clusterLabel,
        nameLabel,
        instanceIdLabel,
        hash.toString
      )
      realTimeLoadPerResource.remove(
        clusterLabel,
        nameLabel,
        instanceIdLabel,
        hash.toString,
        LoadType.Reported.toString
      )
      realTimeLoadPerResource.remove(
        clusterLabel,
        nameLabel,
        instanceIdLabel,
        hash.toString,
        LoadType.Reserved.toString
      )
      numAssignedSlicesPerResource.remove(clusterLabel, nameLabel, instanceIdLabel, hash.toString)
    }
  }

  /**
   * Computes a semi-stable resource hash for each pod, to try to avoid metric cardinality
   * explosion. This is done by hashing the `resourceAddress` and modding by twice the number of
   * resources rounded up to nearest power of two. Collisions are handled by sorting pod names and
   * simply taking the next available number. This is not perfect, but mostly still allows for
   * tracking trends as long as the pod addresses don't change too much.
   */
  private def computeResourceHashes(resources: Iterable[Squid]): Map[Squid, Int] = {
    if (resources.isEmpty) {
      return Map.empty
    }
    val resourceIds = mutable.Map[Squid, Int]()
    val usedIds = mutable.Set[Int]()
    // We use twice the next power of two as the modulus. But we impose a minimum of 128 to avoid
    // reshuffling/too much variation in IDs if there are a small number of pods getting
    // added/removed.
    val nextPowerOfTwo = IntMath.ceilingPowerOfTwo(2 * resources.size)
    val modulus = Math.max(nextPowerOfTwo, 128)
    for (resource: Squid <- resources.toArray.sorted) {
      var id: Int = Math.abs(resource.resourceAddress.hashCode()) % modulus
      while (usedIds.contains(id)) {
        id = (id + 1) % modulus
      }
      usedIds.add(id)
      resourceIds(resource) = id
    }
    resourceIds.toMap
  }

  /**
   * Sets this load distribution to the [[assignmentLoadDistributionHistogram]] gauge. Overwrites
   * any values previously written for the given `target`.
   */
  private def updateLoadDistributionHistogram(target: Target, loadMap: LoadMap): Unit = {
    // Update a load distribution histogram from the given load map. It works by:
    // - Evenly partitioning the key space into `BUCKETS.size` hypothetical slices and computing
    //   the load for each slice.
    // - Mapping the load to the histogram bucket or buckets that corresponds to the slice.
    // For example, if we partition the assignment into 4 buckets:
    //
    // Histogram buckets:
    // |            |             |             |             |
    // 0 --------- 0.25 -------- 0.5 -------- 0.75 --------- +Inf
    //
    // and have a load map with the following entries:
    //
    // |                  |                     |             |
    // "" --- load=3 ---- 0x60 ---- load=6 --- 0xC0 - load=1 - ∞
    //
    // Then the output of this function is a histogram with the following measurements:
    //  - [0, 0.25] -> 2 (3 * 2/3 apportioning ratio)
    //  - (0.25, 0.5] -> 3 (3 * 1/3 + 6 * 1/3)
    //  - (0.5, 0.75] -> 4 (6 * 2/3)
    //  - (0.75, +Inf) -> 1 (1 * 1)
    //
    // See [[LoadMap]] for more information on the apportioning behavior.
    val measurements: Vector[Double] = SLICE_LOAD_BUCKETS.map {
      bucket: LoadDistributionHistogramBucket =>
        loadMap.getLoad(bucket.slice)
    }
    var cumulativeLoad: Double = 0
    for (entry <- SLICE_LOAD_BUCKETS.zip(measurements)) {
      val (bucket, measurement): (LoadDistributionHistogramBucket, Double) = entry
      cumulativeLoad += measurement
      assignmentLoadDistributionHistogram
        .labels(
          bucket.leLabelValue,
          target.getTargetClusterLabel,
          target.getTargetNameLabel,
          target.getTargetInstanceIdLabel
        )
        .set(cumulativeLoad)
    }
  }

  /** The reason for shutting down a generator. */
  sealed trait GeneratorShutdownReason {
    def toString: String
  }

  object GeneratorShutdownReason {
    case object PREFERRED_ASSIGNER_CHANGE extends GeneratorShutdownReason {
      override def toString: String = "PREFERRED_ASSIGNER_CHANGE"
    }

    case object TARGET_CONFIG_CHANGE extends GeneratorShutdownReason {
      override def toString: String = "TARGET_CONFIG_CHANGE"
    }

    case object GENERATOR_INACTIVITY extends GeneratorShutdownReason {
      override def toString: String = "GENERATOR_INACTIVITY"
    }
  }

  object forTest {

    /** Get the semi-stable resource hashes for the given resources. */
    def getResourceHashes(resources: Iterable[Squid]): Map[Squid, Int] = {
      computeResourceHashes(resources)
    }

    /** Resets all metrics to their initial state for use in Unit Tests. */
    def resetAllMetrics(): Unit = {
      // Clear all counters
      assignmentGenerationDecisions.clear()
      healthStatusComputedDiffersFromReported.clear()
      numDistributedAssignments.clear()
      numUnusedAssignmentDiffs.clear()
      numAssignmentStoreIncarnationMismatch.clear()
      numAssignmentWrites.clear()
      numTargetWatchErrors.clear()
      generatorsRemovedTotal.clear()
      numCrashedResourcesGauge.clear()
      numCrashedResourcesTotal.clear()
      keyOfDeathStateTransitions.clear()

      // Clear all gauges
      numAssignmentSlices.clear()
      podSetSizeComputed.clear()
      podSetSizeReported.clear()
      numActiveGenerators.clear()
      targetsWithActiveGenerators.clear()
      latestWrittenAssignmentIncarnationGauge.clear()
      latestWrittenGenerationNumberGauge.clear()
      latestKnownGenerationIncarnationGauge.clear()
      latestKnownGenerationGauge.clear()
      perResourceLoadAfterGeneration.clear()
      staticPerResourceLoadAtGeneration.clear()
      realTimeLoadPerResource.clear()
      numAssignedSlicesPerResource.clear()
      numTopKeys.clear()
      maxDesiredLoadAtGeneration.clear()
      minDesiredLoadAtGeneration.clear()
      assignmentLoadDistributionHistogram.clear()
      sliceReplicaCountHistogram.clear()
      keyOfDeathHeuristic.clear()
      estimatedResourceWorkloadSize.clear()

      // Clear churn ratio metrics (each has both gauge and counter components)
      removalChurnRatio.gauge.clear()
      removalChurnRatio.counter.clear()
      additionChurnRatio.gauge.clear()
      additionChurnRatio.counter.clear()
      loadBalancingChurnRatio.gauge.clear()
      loadBalancingChurnRatio.counter.clear()

      // Note: CachingLatencyHistogram doesn't have a clear() method, so we skip it
    }
  }

}
