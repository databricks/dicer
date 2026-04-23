package com.databricks.dicer.assigner

import com.databricks.dicer.assigner.AssignmentGenerator.AssignmentGenerationDecision
import com.databricks.dicer.assigner.TargetMetrics.GeneratorShutdownReason
import com.databricks.caching.util.MetricUtils
import com.databricks.dicer.assigner.TargetMetrics.KeyOfDeathTransitionType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import io.grpc.Status.Code
import io.prometheus.client.Collector.MetricFamilySamples
import io.prometheus.client.CollectorRegistry

/** Methods that help tests get [[TargetMetrics]] values. */
object TargetMetricsUtils {

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  protected val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /**
   * Gets the number of `generate` assignment generation decisions for `target` with
   * `generateReason`.
   */
  def getAssignmentGenerationGenerateDecisions(
      target: Target,
      generateReason: AssignmentGenerationDecision.GenerateReason): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_assignment_generation_decisions_total",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "decision" -> "Generate",
          "reason" -> s"$generateReason"
        )
      )
      .toLong
  }

  /** Gets the total number of `generate` assignment generation decisions for `target`. */
  def getAllAssignmentGenerationGenerateDecisions(
      target: Target
  ): Long = {
    getAssignmentGenerationGenerateDecisions(
      target,
      AssignmentGenerationDecision.GenerateReason.FirstAssignment
    ) +
    getAssignmentGenerationGenerateDecisions(
      target,
      AssignmentGenerationDecision.GenerateReason.HealthChange
    ) +
    getAssignmentGenerationGenerateDecisions(
      target,
      AssignmentGenerationDecision.GenerateReason.LoadBalancing
    )
  }

  /** Gets the number of `skip` assignment generation decisions for `target` with `skipReason`. */
  def getAssignmentGenerationSkipDecisions(
      target: Target,
      skipReason: AssignmentGenerationDecision.SkipReason
  ): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_assignment_generation_decisions_total",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "decision" -> "Skip",
          "reason" -> s"$skipReason"
        )
      )
      .toLong
  }

  /** Gets number of resources in the assignment for `target` and healthStatus `healthStatus`. */
  def getPodSetSize(target: Target, healthStatus: String): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_assigner_podset_size",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "healthStatus" -> healthStatus
        )
      )
      .toInt
  }

  /** Gets number of reported resources for `target` with `healthStatus`. */
  def getReportedPodSetSize(target: Target, healthStatus: String): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_assigner_reported_health_status_podset_size",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "healthStatus" -> healthStatus
        )
      )
      .toInt
  }

  /** Gets the number of slices in an assignment that has been distributed for `target`. */
  def getNumAssignmentSlices(target: Target): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_assigner_num_assignment_slices",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toInt
  }

  /**
   * Gets the number of assignment writes for `target`.
   *
   * @param code The canonical Grpc [[Code]] of the assignment write.
   * @param isMeaningful Whether the assignment write is meaningful.
   */
  def getNumAssignmentWrites(target: Target, code: Code, isMeaningful: Boolean): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_num_assignment_writes",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "canonicalCode" -> code.toString,
          "isMeaningfulAssignmentChange" -> isMeaningful.toString
        )
      )
      .toLong
  }

  /** Gets the number of active generators for `target`. */
  def getNumActiveGenerators(target: Target): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_num_active_generators",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toInt
  }

  /**
   * Gets the number of distributed assignments for `source` of `target`.
   *
   * @param source The source of the assignment distribution, can be either "Store", "Clerk", or
   *               "Slicelet", see [[TargetMetrics.AssignmentDistributionSource]]. The type is
   *               set to be String to avoid circular dependency.
   */
  def getNumDistributedAssignments(target: Target, source: String): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_num_distributed_assignments",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "source" -> source
        )
      )
      .toLong
  }

  /**
   * Gets the replica count distribution for `target`. Returned map is from distribution bucket
   * ("le" label value representing a double-valued inclusive upper bound on replica count) to the
   * bucket's value (the number of slices in the assignment that had replica counts less than or
   * equal to the "le" label value).
   */
  def getSliceReplicaCountDistribution(target: Target): Map[String, Double] = {
    // Get all the samples for the given `target`.
    val samplesForTarget: Seq[MetricFamilySamples.Sample] = MetricUtils
      .getMetricSamples(
        registry,
        metric = "dicer_assigner_slice_replica_count_distribution_bucket"
      )
      .filter { sample: MetricFamilySamples.Sample =>
        sample.labelNames.get(0) == "le" &&
        sample.labelNames.get(1) == "targetCluster" &&
        sample.labelNames.get(2) == "targetName" &&
        sample.labelNames.get(3) == "targetInstanceId" &&
        sample.labelValues.get(1) == target.getTargetClusterLabel &&
        sample.labelValues.get(2) == target.getTargetNameLabel &&
        sample.labelValues.get(3) == target.getTargetInstanceIdLabel
      }
    // Collected the total set of buckets for the distribution.
    val sampleBuckets: Set[String] = samplesForTarget.map { sample: MetricFamilySamples.Sample =>
      sample.labelValues.get(0)
    }.toSet
    // Return a map from bucket to the bucket's value.
    sampleBuckets.map { bucket: String =>
      bucket -> MetricUtils.getMetricValue(
        registry,
        metric = "dicer_assigner_slice_replica_count_distribution_bucket",
        Map(
          "le" -> bucket,
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
    }.toMap
  }

  /**
   * Gets the number of times the Slicelet chose [[reportedStatus]] while the HealthWatcher chose
   * [[computedStatus]].
   */
  def getComputedStatusDiffers(
      target: Target,
      reportedStatus: String,
      computedStatus: String): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_health_status_computed_differs_from_reported_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "reportedStatus" -> reportedStatus,
          "computedStatus" -> computedStatus
        )
      )
      .toLong
  }

  /**
   * Gets the total number of generators removed for a specific reason.
   *
   * @param reason The removal reason
   */
  def getGeneratorsRemovedTotal(target: Target, reason: GeneratorShutdownReason): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_assigner_generators_removed_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "reason" -> reason.toString
        )
      )
      .toLong
  }

  /** Gets the total number of generators removed (all reasons combined). */
  def getGeneratorsRemovedTotalAllReasons(target: Target): Long = {
    getGeneratorsRemovedTotal(target, GeneratorShutdownReason.GENERATOR_INACTIVITY) +
    getGeneratorsRemovedTotal(target, GeneratorShutdownReason.PREFERRED_ASSIGNER_CHANGE) +
    getGeneratorsRemovedTotal(target, GeneratorShutdownReason.TARGET_CONFIG_CHANGE)
  }

  /** Gets the current number of targets with active generators. */
  def getTargetsWithActiveGenerators(target: Target): Int = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_assigner_targets_with_active_generators",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toInt
  }

  /** Gets the key of death heuristic value for `target`. */
  def getKeyOfDeathHeuristic(target: Target): Double = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_key_of_death_heuristic_gauge",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }

  /**
   * Gets the current number of crashed resources the [[KeyOfDeathDetector]] has tracked for
   * `target`.
   */
  def getNumCrashedResourcesGauge(target: Target): Double = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_num_crashed_resources_gauge",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }

  /**
   * Gets the total number of crashed resources the [[KeyOfDeathDetector]] has tracked for
   * `target`.
   */
  def getNumCrashedResourcesTotal(target: Target): Double = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_num_crashed_resources_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }

  /** Gets the resource workload size estimated by the [[KeyOfDeathDetector]] for `target`. */
  def getEstimatedResourceWorkloadSize(target: Target): Double = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_estimated_resource_workload_size_gauge",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }

  /**
   * Gets the number of times the key of death state has transitioned for `target` with
   * `transitionType`.
   */
  def getKeyOfDeathStateTransitions(
      target: Target,
      transitionType: KeyOfDeathTransitionType): Double = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_key_of_death_state_transitions_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "transitionType" -> transitionType.toString
        )
      )
  }

  /**
   * Gets the number of health watcher resource expirations for `target` with the given
   * termination signal source labels.
   */
  def getHealthWatcherResourceExpirations(
      target: Target,
      receivedFromSlicelet: Boolean,
      receivedFromKubernetes: Boolean): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_healthwatcher_resource_expirations_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "receivedFromSlicelet" -> receivedFromSlicelet.toString,
          "receivedFromKubernetes" -> receivedFromKubernetes.toString
        )
      )
      .toLong
  }

  /**
   * Gets the count of observations in the health watcher termination signal delay histogram
   * for `target`.
   */
  def getHealthWatcherSliceletVsK8sTerminationSignalDelayCount(target: Target): Int = {
    MetricUtils
      .getHistogramCount(
        registry,
        "dicer_assigner_healthwatcher_slicelet_vs_k8s_termination_signal_delay_seconds",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }

  /**
   * Gets the sum of observations in the health watcher termination signal delay histogram
   * for `target`.
   */
  def getHealthWatcherSliceletVsK8sTerminationSignalDelaySum(target: Target): Double = {
    MetricUtils
      .getHistogramSum(
        registry,
        "dicer_assigner_healthwatcher_slicelet_vs_k8s_termination_signal_delay_seconds",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
  }
}
