package com.databricks.dicer.assigner

import scala.concurrent.duration._

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.ReservationHintP
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.assigner.AssignmentStats.{
  AssignmentLoadStats,
  ReassignmentChurnAndLoadStats
}
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  LoadBalancingConfig,
  LoadBalancingMetricConfig
}
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap, Resources}
import com.databricks.dicer.assigner.algorithm.LoadMap.Entry
import com.databricks.dicer.assigner.TargetMetrics.LoadType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{Assignment, AssignmentConsistencyMode}
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry

/**
 * Base test suite that validates functionality for TargetMetrics. Subclasses need to implement the
 * [[defaultTarget]] method, which is used throughout the test cases to create a [[Target]] used to
 * test metrics.
 */
abstract class TargetMetricsSuiteBase extends DatabricksTest with TestName {

  /** Returns a [[Target]] unique to the current test case to use for testing metrics. */
  protected def defaultTarget: Target

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  private def getChurnAdditionRatioCounter(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_addition_counter",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getChurnAdditionRatioGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_addition_gauge",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getChurnRemovalRatioCounter(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_removal_counter",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getChurnRemovalRatioGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_removal_gauge",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getChurnLoadBalancingRatioCounter(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_load_balancing_counter",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getChurnLoadBalancingRatioGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_churn_load_balancing_gauge",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getNumAssignedSlicesPerResourceGauge(target: Target, resourceHash: Int): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_num_assigned_slices_per_resource",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "resourceHash" -> resourceHash.toString
      )
    )
  }

  private def getMinDesiredLoadAtGenerationGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_min_desired_load_at_generation",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getMaxDesiredLoadAtGenerationGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_max_desired_load_at_generation",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  private def getPerResourceLoadAfterGenerationGauge(target: Target, resourceHash: Int): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_per_resource_load_after_generation",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "resourceHash" -> resourceHash.toString
      )
    )
  }

  private def getStaticPerResourceLoadAtGenerationGauge(
      target: Target,
      resourceHash: Int): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_static_per_resource_load_at_generation",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "resourceHash" -> resourceHash.toString
      )
    )
  }

  private def getRealTimePerResourceLoadGauge(
      target: Target,
      resourceHash: Int,
      loadType: LoadType.Value): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_real_time_load_per_resource",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "resourceHash" -> resourceHash.toString,
        "loadType" -> loadType.toString
      )
    )
  }

  private def getNumTopKeysGauge(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_num_top_keys",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /**
   * Validates the load distribution histogram by checking that every bucket has a cumulative
   * value greater than or equal to the previous bucket, and ensures that the final cumulative
   * value is equal to `totalLoad`.
   */
  private def verifyLoadDistributionHistogram(target: Target, totalLoad: Double): Unit = {
    val bucketCount: Int = 64
    var previousCumulativeValue: Double = 0.0

    for (i: Int <- 0 until bucketCount - 1) {
      // Look up the value based on the expected label.
      val leLabelValue: String = s"${(i.toDouble + 1) / bucketCount}"
      val cumulativeLoad: Double = MetricUtils.getMetricValue(
        registry,
        metric = "dicer_assigner_assignment_load_distribution_bucket",
        Map(
          "le" -> leLabelValue,
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      assert(cumulativeLoad >= previousCumulativeValue)
      previousCumulativeValue = cumulativeLoad
    }

    // Verify that the final cumulative value is equal to `totalLoad`.
    val finalCumulativeValue: Double = MetricUtils.getMetricValue(
      registry,
      metric = "dicer_assigner_assignment_load_distribution_bucket",
      Map(
        "le" -> "+Inf",
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
    // Round to 2 decimal places to avoid floating point precision issues.
    assertResult(totalLoad)(Math.round(finalCumulativeValue * 100) / 100.0)
  }

  test("reportReassignmentStats exports assignment change Prometheus metrics correctly") {
    // Test plan: Call reportReassignmentStats with test data and verify that the
    // Prometheus metrics for addition, removal, and load balancing churn ratios are set
    // correctly. Despite that `reportReassignmentStats` also updates load stats metrics,
    // this test case only focuses on the verification of churn metrics for clearness of
    // test logic.

    // All churn metrics should have an initial counter value of 0.0.
    assertResult(0.0)(getChurnAdditionRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnAdditionRatioGauge(defaultTarget))
    assertResult(0.0)(getChurnRemovalRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnRemovalRatioGauge(defaultTarget))
    assertResult(0.0)(getChurnLoadBalancingRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnLoadBalancingRatioGauge(defaultTarget))

    val defaultTargetConfig: InternalTargetConfig = InternalTargetConfig.forTest.DEFAULT

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- HALF_POINT, 2.0 / 3.0),
          Entry(HALF_POINT -- ∞, 1.0 / 3.0)
        )
        .build()
    val asn0: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )

    // Create the first assignment.
    // Split [0, HALF_POINT) into [0, HALF_POINT / 2) and [HALF_POINT / 2, HALF_POINT),
    // with the former being reassigned to pod2.
    val asn1: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod2"),
      (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )

    // Calculate all necessary stats to be exported to Prometheus.
    // This should result in the following churn ratios:
    // - Addition churn ratio: 1/3
    // - Removal churn ratio: 0
    // - Load balancing churn ratio: 0
    val reassignmentStats: ReassignmentChurnAndLoadStats =
      ReassignmentChurnAndLoadStats.calculate(asn0, asn1, loadMap)
    val totalAdjustedLoad: Double = Algorithm
      .computeAdjustedLoadMap(
        defaultTargetConfig.loadBalancingConfig,
        asn1.assignedResources.size,
        loadMap
      )
      .getLoad(Slice.FULL)
    val desiredLoad: Algorithm.DesiredLoadRange = Algorithm.calculateDesiredLoadRange(
      defaultTargetConfig.loadBalancingConfig,
      asn1.assignedResources.size,
      totalAdjustedLoad
    )

    // Call the method to export the Prometheus metrics
    TargetMetrics.reportReassignmentStats(
      defaultTarget,
      reassignmentStats,
      desiredLoad
    )

    // All churn metrics -- both counters and gauges -- should match the assignment change stats.
    assertResult(1.0 / 3.0)(getChurnAdditionRatioCounter(defaultTarget))
    assertResult(1.0 / 3.0)(getChurnAdditionRatioGauge(defaultTarget))
    assertResult(0.0)(getChurnRemovalRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnRemovalRatioGauge(defaultTarget))
    assertResult(0.0)(getChurnLoadBalancingRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnLoadBalancingRatioGauge(defaultTarget))

    // Create the second assignment.
    // Slice [0, HALF_POINT / 2) and [HALF_POINT / 2, HALF_POINT) switches pods.
    // pod1 is removed and Slice [HALF_POINT -- ∞) is reassigned to pod0.
    val asn2: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod0"),
      (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod2"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod0")
    )

    // Recalculate the stats to be exported to Prometheus.
    val finalReassignmentStats: ReassignmentChurnAndLoadStats =
      ReassignmentChurnAndLoadStats.calculate(asn1, asn2, loadMap)
    val finalTotalAdjustedLoad: Double = Algorithm
      .computeAdjustedLoadMap(
        defaultTargetConfig.loadBalancingConfig,
        asn2.assignedResources.size,
        loadMap
      )
      .getLoad(Slice.FULL)
    val finalDesiredLoad: Algorithm.DesiredLoadRange = Algorithm.calculateDesiredLoadRange(
      defaultTargetConfig.loadBalancingConfig,
      asn2.assignedResources.size,
      finalTotalAdjustedLoad
    )

    // Call the method to export the Prometheus metrics.
    // This should result in the following churn ratios:
    // - Addition churn ratio: 0
    // - Removal churn ratio: 1/3
    // - Load balancing churn ratio: 2/3
    TargetMetrics.reportReassignmentStats(
      defaultTarget,
      finalReassignmentStats,
      finalDesiredLoad
    )

    // All churn metrics counters should match the sum of the two assignment change stats, while
    // the churn metrics gauges should match the last assignment change stats.
    assertResult(1.0 / 3.0)(getChurnAdditionRatioCounter(defaultTarget))
    assertResult(0.0)(getChurnAdditionRatioGauge(defaultTarget))
    assertResult(1.0 / 3.0)(getChurnRemovalRatioCounter(defaultTarget))
    assertResult(1.0 / 3.0)(getChurnRemovalRatioGauge(defaultTarget))
    assertResult(2.0 / 3.0)(getChurnLoadBalancingRatioCounter(defaultTarget))
    assertResult(2.0 / 3.0)(getChurnLoadBalancingRatioGauge(defaultTarget))
  }

  test("reportReassignmentStats exports load metrics correctly") {
    // Test plan: Call reportReassignmentStats with test data and verify that the desired
    // load metrics are set correctly in Prometheus. We report the load metrics for two
    // assignment changes:
    // 1. Changing from a two-resource assignment to a three-resource assignment.
    // 2. Changing from a three-resource assignment to a two-resource assignment, which
    //    should clear the stats associated with the removed resource.
    // Despite that `reportReassignmentStats` also exports churn metrics, this test case
    // only focuses on the verification of load metrics for clearness of test logic.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- HALF_POINT, 1.0 / 3.0),
          Entry(HALF_POINT -- ∞, 2.0 / 3.0)
        )
        .build()
    val twoResourceAssignment: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )
    val threeResourceAssignment: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 2) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 42) -> Seq("pod2"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )

    // Sort the resources, so that we can create a load sequence for resources in ascending order.
    // (e.g. Seq(load for pod0, load for pod1, load for pod2 ....))
    // This allows a direct comparison with the predefined sequence, and verify that the load for
    // each resource is correct.
    val subsetResources: Seq[Squid] = twoResourceAssignment.assignedResources.toSeq.sorted
    val allResources: Seq[Squid] = threeResourceAssignment.assignedResources.toSeq.sorted

    // Compute the hashes of the resources.
    val allResourceHashes: Map[Squid, Int] = TargetMetrics.forTest.getResourceHashes(allResources)

    // Case 1: Changing from a two-resource assignment to a three-resource assignment.
    {
      // All load metrics should have an initial value of 0.0.
      for (resource: Squid <- allResources) {
        assertResult(0.0)(
          getPerResourceLoadAfterGenerationGauge(defaultTarget, allResourceHashes(resource))
        )
        assertResult(0.0)(
          getStaticPerResourceLoadAtGenerationGauge(defaultTarget, allResourceHashes(resource))
        )
        assertResult(0.0)(
          getNumAssignedSlicesPerResourceGauge(defaultTarget, allResourceHashes(resource))
        )
        assertResult(0.0)(getMinDesiredLoadAtGenerationGauge(defaultTarget))
        assertResult(0.0)(getMaxDesiredLoadAtGenerationGauge(defaultTarget))
      }

      // Calculate all necessary stats to be exported to Prometheus.
      val reassignmentStats: ReassignmentChurnAndLoadStats =
        ReassignmentChurnAndLoadStats.calculate(
          twoResourceAssignment,
          threeResourceAssignment,
          loadMap
        )
      val totalAdjustedLoad: Double = Algorithm
        .computeAdjustedLoadMap(
          loadBalancingConfig,
          threeResourceAssignment.assignedResources.size,
          loadMap
        )
        .getLoad(Slice.FULL)
      val desiredLoad: Algorithm.DesiredLoadRange = Algorithm.calculateDesiredLoadRange(
        loadBalancingConfig,
        threeResourceAssignment.assignedResources.size,
        totalAdjustedLoad
      )

      // Call the method to export the Prometheus metrics.
      TargetMetrics.reportReassignmentStats(
        defaultTarget,
        reassignmentStats,
        desiredLoad
      )

      // Verify that the per-resource load metrics for the current (three-resource) assignment are
      // set correctly.
      // Load:
      // - pod0: 1/2 * 1/3 = 1/6
      // - pod1: 2/3
      // - pod2: 1/2 * 1/3 = 1/6
      // Number of assigned slices:
      // - pod0: 1
      // - pod1: 1
      // - pod2: 1
      val expectedPerResourceLoadAfterGeneration: Seq[Double] = Seq(1.0 / 6.0, 2.0 / 3.0, 1.0 / 6.0)
      val expectedNumAssignedSlicesPerResource: Seq[Int] = Seq(1, 1, 1)
      val perResourceLoadAfterGeneration: Seq[Double] = allResources.map(
        (resource: Squid) =>
          getPerResourceLoadAfterGenerationGauge(defaultTarget, allResourceHashes(resource))
      )
      val numAssignedSlicesPerResource: Seq[Double] = allResources.map(
        (resource: Squid) =>
          getNumAssignedSlicesPerResourceGauge(defaultTarget, allResourceHashes(resource))
      )
      assertResult(expectedPerResourceLoadAfterGeneration)(perResourceLoadAfterGeneration)
      assertResult(expectedNumAssignedSlicesPerResource)(numAssignedSlicesPerResource)

      // Verify that the static per-resource load metrics for the current assignment resources are
      // set correctly.
      // - pod0: 2/3 * 1/3 = 2/9
      // - pod1: 1/3 * 1/3 + 1/3 * 2/3 = 1/3
      // - pod2: 2/3 * 2/3 = 4/9
      val expectedStaticPerResourceLoadAtGeneration: Seq[Double] =
        Seq(2.0 / 9.0, 1.0 / 3.0, 4.0 / 9.0)
      val staticPerResourceLoadAtGeneration: Seq[Double] = allResources.map(
        (resource: Squid) =>
          getStaticPerResourceLoadAtGenerationGauge(defaultTarget, allResourceHashes(resource))
      )
      assertResult(expectedStaticPerResourceLoadAtGeneration)(staticPerResourceLoadAtGeneration)

      // Verify that the min/max desired load metrics are set correctly and match what
      // was passed into reportReassignmentStats.
      assertResult(expected = desiredLoad.minDesiredLoadExistingResource)(
        getMinDesiredLoadAtGenerationGauge(defaultTarget)
      )
      assertResult(expected = desiredLoad.maxDesiredLoad)(
        getMaxDesiredLoadAtGenerationGauge(defaultTarget)
      )
    }

    // Case 2: Changing from a three-resource assignment to a two-resource assignment.
    {
      // Recalculate the stats to be exported to Prometheus.
      val reassignmentStats: ReassignmentChurnAndLoadStats =
        ReassignmentChurnAndLoadStats.calculate(
          threeResourceAssignment,
          twoResourceAssignment,
          loadMap
        )
      val totalAdjustedLoad: Double = Algorithm
        .computeAdjustedLoadMap(
          loadBalancingConfig,
          twoResourceAssignment.assignedResources.size,
          loadMap
        )
        .getLoad(Slice.FULL)
      val desiredLoad: Algorithm.DesiredLoadRange = Algorithm.calculateDesiredLoadRange(
        loadBalancingConfig,
        twoResourceAssignment.assignedResources.size,
        totalAdjustedLoad
      )

      // Call the method to export the Prometheus metrics.
      TargetMetrics.reportReassignmentStats(
        defaultTarget,
        reassignmentStats,
        desiredLoad
      )

      // Verify that the per-resource load metrics for the current (two-resource) assignment is set
      // correctly.
      // Load:
      // - pod0: 1/3
      // - pod1: 2/3
      // Number of assigned slices:
      // - pod0: 1
      // - pod1: 1
      val expectedPerResourceLoadAfterGeneration: Seq[Double] = Seq(1.0 / 3.0, 2.0 / 3.0)
      val expectedNumAssignedSlicesPerResource: Seq[Int] = Seq(1, 1)
      val perResourceLoadAfterGeneration: Seq[Double] = subsetResources.map(
        (resource: Squid) =>
          getPerResourceLoadAfterGenerationGauge(defaultTarget, allResourceHashes(resource))
      )
      val numAssignedSlicesPerResource: Seq[Double] = subsetResources.map(
        (resource: Squid) =>
          getNumAssignedSlicesPerResourceGauge(defaultTarget, allResourceHashes(resource))
      )
      assertResult(expectedPerResourceLoadAfterGeneration)(perResourceLoadAfterGeneration)
      assertResult(expectedNumAssignedSlicesPerResource)(numAssignedSlicesPerResource)

      // Verify that the static per-resource load metrics for the current assignment resources are
      // set correctly.
      // - pod0: 1/3
      // - pod2: 2/3
      val expectedStaticPerResourceLoadAtGeneration: Seq[Double] = Seq(1.0 / 3.0, 2.0 / 3.0)
      val staticPerResourceLoadAtGeneration: Seq[Double] = subsetResources.map(
        (resource: Squid) =>
          getStaticPerResourceLoadAtGenerationGauge(defaultTarget, allResourceHashes(resource))
      )
      assertResult(expectedStaticPerResourceLoadAtGeneration)(staticPerResourceLoadAtGeneration)

      // Verify that the min/max desired load metrics are set correctly and match what
      // was passed into reportReassignmentStats.
      assertResult(expected = desiredLoad.minDesiredLoadExistingResource)(
        getMinDesiredLoadAtGenerationGauge(defaultTarget)
      )
      assertResult(expected = desiredLoad.maxDesiredLoad)(
        getMaxDesiredLoadAtGenerationGauge(defaultTarget)
      )

      // Since pod2 was removed, all of its load metrics (except per-resource load
      // before generation) should be set to 0.0.
      val pod2Hash: Int = allResourceHashes(allResources(2))
      assertResult(0.0)(getPerResourceLoadAfterGenerationGauge(defaultTarget, pod2Hash))
      assertResult(0.0)(getStaticPerResourceLoadAtGenerationGauge(defaultTarget, pod2Hash))
      assertResult(0.0)(getRealTimePerResourceLoadGauge(defaultTarget, pod2Hash, LoadType.Reported))
      assertResult(0.0)(getRealTimePerResourceLoadGauge(defaultTarget, pod2Hash, LoadType.Reserved))
      assertResult(0.0)(getNumAssignedSlicesPerResourceGauge(defaultTarget, pod2Hash))
    }
  }

  test("reportAssignmentSnapshotStats exports load metrics correctly") {
    // Test plan: Call reportAssignmentSnapshotStats with test data and verify that the load metrics
    // are set correctly in Prometheus.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val loadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 1.0 / 3.0))
        .putLoad(Entry(HALF_POINT -- ∞, 2.0 / 3.0))
        .build()
    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2"),
      (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")
    )

    // Sort the resources, so that we can create a load sequence for resources in ascending order.
    // (e.g. Seq(load for pod0, load for pod1, load for pod2 ....))
    // This allows a direct comparison with the predefined sequence, and verify that the load for
    // each resource is correct.
    val resources: Seq[Squid] = asn.assignedResources.toSeq.sorted

    // Compute the hashes of the resources.
    val resourceHashes: Map[Squid, Int] = TargetMetrics.forTest.getResourceHashes(resources)

    // Verify that the initial values of the real-time per-resource load metrics, load distribution
    // histogram total load, and numTopKeys are 0.0.
    for (resource: Squid <- resources) {
      assertResult(0.0)(
        getRealTimePerResourceLoadGauge(
          defaultTarget,
          resourceHashes(resource),
          TargetMetrics.LoadType.Reported
        )
      )
      assertResult(0.0)(
        getRealTimePerResourceLoadGauge(
          defaultTarget,
          resourceHashes(resource),
          TargetMetrics.LoadType.Reserved
        )
      )
    }
    verifyLoadDistributionHistogram(defaultTarget, totalLoad = 0.0)
    assertResult(0.0)(getNumTopKeysGauge(defaultTarget))

    // Calculate the load stats.
    val reportedLoadStats = AssignmentLoadStats.calculate(asn, loadMap)
    val adjustedLoadStats = AssignmentLoadStats
      .calculateAdjustedLoadStats(asn, loadMap, loadBalancingConfig)

    // Dummy entry to make sure the numTopKeys statistics is also exported correctly.
    val numTopKeys = 5

    // Call the method to export the Prometheus metrics.
    TargetMetrics.reportAssignmentSnapshotStats(
      defaultTarget,
      reportedLoadStats,
      adjustedLoadStats,
      loadMap,
      numTopKeys
    )

    // Verify that the per-resource real-time load metrics are set correctly.
    // Reported Load:
    // - r1: 1/2 * 1/3 = 1/6
    // - r2: 1/2 * 1/3 + 1/2 * 2/3 = 1/2
    // - r3: 1/2 * 2/3 = 1/3
    // Reserved Load:
    // Total reserved uniform load
    // = maxLoadHint * uniformLoadReservationHint * numResource
    // = 10 * 0.1 * 3 = 3
    // - r1: 3/4
    // - r2: 3/4 + 3/4 = 3/2
    // - r3: 3/4
    val expectedReportedPerResourceLoad: Seq[Double] = Seq(1.0 / 6.0, 1.0 / 2.0, 1.0 / 3.0)
    val expectedReservedPerResourceLoad: Seq[Double] = Seq(3.0 / 4.0, 3.0 / 2.0, 3.0 / 4.0)
    val reportedPerResourceLoad: Seq[Double] = resources.map(
      (resource: Squid) =>
        getRealTimePerResourceLoadGauge(defaultTarget, resourceHashes(resource), LoadType.Reported)
    )
    val reservedPerResourceLoad: Seq[Double] = resources.map(
      (resource: Squid) =>
        getRealTimePerResourceLoadGauge(defaultTarget, resourceHashes(resource), LoadType.Reserved)
    )
    assertResult(expectedReportedPerResourceLoad)(reportedPerResourceLoad)
    assertResult(expectedReservedPerResourceLoad)(reservedPerResourceLoad)

    // Verify that the load distribution is recorded.
    // Total load: 1/3 + 2/3 = 1.0
    verifyLoadDistributionHistogram(defaultTarget, totalLoad = 1.0)

    // Verify that the numTopKeys metrics are set correctly.
    val observedNumTopKeys: Double = getNumTopKeysGauge(defaultTarget)
    assertResult(expected = numTopKeys)(observedNumTopKeys)
  }

  test("clearTerminatedGeneratorFromMetrics does not throw for empty resources") {
    // Test plan: verify that `clearTerminatedGeneratorFromMetrics` does not throw if the input
    // resources are empty. We are testing this method directly because its existing callers do
    // not pass empty resources to this method.
    val emptyResources: Resources = Resources.empty
    TargetMetrics.clearTerminatedGeneratorFromMetrics(defaultTarget, emptyResources)
  }
}

/** Test suites that validates metric functionality for targets created with [[Target.apply]]. */
class TargetMetricsSuite extends TargetMetricsSuiteBase {
  override protected def defaultTarget: Target = Target(getSafeName)
}

/** Test suites that validates metric functionality for [[AppTarget]]s. */
class AppTargetMetricsSuite extends TargetMetricsSuiteBase {
  override protected def defaultTarget: Target =
    Target.createAppTarget(getSafeAppTargetName, "instance-id")
}
