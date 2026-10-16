package com.databricks.dicer.assigner.algorithm

import scala.concurrent.duration._

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.ImbalanceToleranceHintP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.testing.{ConfigTestUtils}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  ReplicationThresholdOverride
}
import com.databricks.dicer.common.{Assignment, ProposedAssignment, SliceAssignment}
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveSliceKeyFluent,
  LowInclusiveStringFluent,
  ProposedAssignmentEntry,
  ProposedSliceAssignmentFluent,
  SliceAssignmentSliceFluent,
  assertDesirableAssignmentProperties,
  createProposal,
  createResources,
  toProposedAssignmentEntry,
  toSliceKey,
  toSquid,
  `∞`
}
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.Squid

/** Tests which are not parameterized over different key replication configs. */
class NonParameterizedAlgorithmSuite extends AlgorithmSuiteBase {

  test("Reassigns slices from unhealthy to healthy resources: multiple asn rounds") {
    // Test plan: Verify that the algorithm reassigns slices from unhealthy to healthy resources
    // across multiple rounds of assignment generation.
    //
    // Verify this with the following schedule of available resources:
    //
    // - resource0, resource1, resource2             (start with 3 healthy resources)
    // -            resource1,          , resource3  (two resources become unhealthy + new resource)
    // - resource0, resource1,          , resource3  (unhealthy resource comes back)
    // - resource0, resource1                        (new resource becomes unhealthy)
    //
    // Verify at each step that the set of slices is assigned to the current set of healthy
    // resources (and generally satisfies desirable properties).
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    var resources: Resources = createResources("resource0", "resource1", "resource2")
    val assignment1: Assignment =
      generateInitialAssignment(target, resources, targetConfig.keyReplicationConfig)
    assertDesirableAssignmentProperties(targetConfig, assignment1, resources)

    // Remove (i.e. make unhealthy) resource1, resource2, add resource3.
    resources = createResources("resource1", "resource3")
    // Load doesn't matter for this test.
    val loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP
    val assignment2: Assignment =
      generateAssignment(target, targetConfig, resources, assignment1, loadMap)
    assertDesirableAssignmentProperties(targetConfig, assignment2, resources)

    // Add back resource0.
    resources = createResources("resource0", "resource1", "resource3")
    val assignment3: Assignment =
      generateAssignment(target, targetConfig, resources, assignment2, loadMap)
    assertDesirableAssignmentProperties(targetConfig, assignment3, resources)

    // Remove resource3.
    resources = createResources("resource0", "resource1")
    val assignment4: Assignment =
      generateAssignment(target, targetConfig, resources, assignment2, loadMap)
    assertDesirableAssignmentProperties(targetConfig, assignment4, resources)
  }

  test("Resource effective load is zero when all slices are removed") {
    // Test plan: Verify that the effective load tracked for a resource is computed to be zero when
    // all of the slices assigned to it are removed. Verify this by creating an assignment where
    // slices are assigned to pod0 and pod1 in an alternating fashion, the slices assigned to pod0
    // are very slightly hotter than the slices assigned to pod1, and where the total number of
    // slices exceeds the max so that the algo merges all pairs of slices away from pod1. Check that
    // even when the min desired load is Double.MinPositiveValue (the algo uses
    // Double.MinPositiveValue to ensure it always assigns all resources at least once slice when
    // the min desired load is effectively zero) the algo still assigns slices to pod1, confirming
    // that pod1's load was zero after all slices were removed, causing the algorithm to assign
    // slices to it.
    //
    // This is a regression test for a bug where the load of a resource after all slices were
    // removed would be non-zero and greater than Double.MinPositiveValue due to floating point
    // errors, causing the algorithm to think it didn't need to migrate anything to it in the
    // migration phase when min desired load was effectively zero.

    val proposedAsnBuilder = Vector.newBuilder[ProposedAssignmentEntry]
    var prevKey: SliceKey = ""
    val resource1: Squid = "pod0"
    val resource2: Squid = "pod1"
    val resources: Resources = Resources.create(Seq(resource1, resource2))
    // Algo allows up to 64 slices per resource. Creating 256 over 2 resources will cause it to
    // merge until there are 128 slices.
    for (index <- 0 until 256) {
      val slice: Slice = (prevKey -- index)
      if (index % 2 == 0) {
        proposedAsnBuilder += ((prevKey -- index) -> Seq(resource1)).withPrimaryRateLoad(10.00001)
      } else {
        proposedAsnBuilder += ((prevKey -- index) -> Seq(resource2)).withPrimaryRateLoad(9.99999)
      }
      prevKey = slice.highExclusive.asFinite
    }
    proposedAsnBuilder += ((prevKey -- ∞) -> Seq(resource1)).withPrimaryRateLoad(10.00001)
    val proposedAsn = proposedAsnBuilder.result()
    val predecessor: Assignment =
      commitProposal(
        ProposedAssignment(
          predecessorOpt = None,
          sliceMap = createProposal(proposedAsn.head, proposedAsn.tail: _*),
          assignerServiceInfoOpt = None
        )
      )
    // Setting maxLoadHint to Int.MaxValue here to ensure that min desired load gets computed as
    // effectively zero (Double.MinPositiveValue to ensure that at least something gets assigned to
    // all resources).
    val config: InternalTargetConfig =
      createConfigForLoadBalancing(
        ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG,
        maxLoadHint = Int.MaxValue
      )

    val loadMap = LoadMap
      .newBuilder()
      .putLoad(
        predecessor.sliceAssignments.map { sliceAssignment: SliceAssignment =>
          LoadMap.Entry(sliceAssignment.slice, sliceAssignment.primaryRateLoadOpt.getOrElse(0))
        }: _*
      )
      .build()
    val result: Assignment =
      generateAssignment(target, config, resources, predecessor, loadMap)
    assert(result.assignedResources == resources.availableResources)
  }

  test("Ramp up with no load reported and default load balancing configuration") {
    // Test plan: Verify that the algorithm produces valid assignments that are well-balanced and
    // doesn't crash when no load is reported. Verify this by simulating ramping up then ramping
    // down the number of pods in an assignment to give the code some exercise.
    //
    // This test models a default configured application which is ramping up but not yet reporting
    // any load. In this case the application will have the default load balancing configuration
    // which applies a uniform load reservation amounting to 1% of the configured maxLoadHint.

    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint = 100)
    val loadMap = LoadMap.newBuilder().build()
    assert(loadMap.getLoad(Slice.FULL) == 0.0)

    // Create an initial assignment with 2 resources. We use `assertDesirableAssignmentProperties`,
    // which accounts for the uniform-load reservation, to verify that the assignments generated in
    // this test are well balanced.
    val assignment1: Assignment =
      generateInitialAssignment(target, createNResources(2), targetConfig.keyReplicationConfig)
    assertDesirableAssignmentProperties(targetConfig, assignment1, createNResources(2))

    // Update the assignment to include 4 resources.
    val assignment2: Assignment =
      generateAssignment(target, targetConfig, createNResources(4), assignment1, loadMap)
    assertDesirableAssignmentProperties(targetConfig, assignment2, createNResources(4))

    // Update the assignment to include 3 resources.
    val assignment3: Assignment =
      generateAssignment(target, targetConfig, createNResources(3), assignment2, loadMap)
    assertDesirableAssignmentProperties(targetConfig, assignment3, createNResources(3))
  }

  test("Algorithm.Config computes the expected split threshold") {
    // Test plan: Verify that Algorithm.Config computes the appropriate split threshold based on the
    // target's maxLoadHint, imbalanceToleranceHint and the average load per resource. Verify this
    // by constructing several such configs and checking that the computed split threshold meets
    // expectations.

    case class TestCase(
        maxLoadHint: Double,
        imbalanceToleranceHintP: ImbalanceToleranceHintP,
        totalLoad: Double,
        numResources: Int,
        expectedSplitThreshold: Double)
    val testCases = Seq[TestCase](
      // Total load is well below maxLoadHint * numResources. Using the default imbalance tolerance
      // hint, the split threshold should be 10% of maxLoadHint.
      TestCase(
        maxLoadHint = 100.0,
        imbalanceToleranceHintP = ImbalanceToleranceHintP.DEFAULT,
        totalLoad = 1000.0,
        numResources = 20,
        expectedSplitThreshold = 10.0
      ),
      // As above, but with TIGHT tolerance. The split threshold should be 2.5% of maxLoadHint.
      TestCase(
        maxLoadHint = 100.0,
        imbalanceToleranceHintP = ImbalanceToleranceHintP.TIGHT,
        totalLoad = 1000.0,
        numResources = 20,
        expectedSplitThreshold = 2.5
      ),
      // As above, but with LOOSE tolerance. The split threshold should be 40% of maxLoadHint.
      TestCase(
        maxLoadHint = 100.0,
        imbalanceToleranceHintP = ImbalanceToleranceHintP.LOOSE,
        totalLoad = 1000.0,
        numResources = 20,
        expectedSplitThreshold = 40.0
      ),
      // When the total load exceeds maxLoadHint * numResources, the split threshold should be
      // relative to the actual average load per resource. Using the default imbalance tolerance,
      // the split threshold should be 10% of the average load per resource.
      TestCase(
        maxLoadHint = 10.0,
        imbalanceToleranceHintP = ImbalanceToleranceHintP.DEFAULT,
        totalLoad = 1000.0,
        numResources = 20,
        expectedSplitThreshold = 5.0
      )
    )
    for (testCase <- testCases) {
      val loadBalancingMetricConfig = LoadBalancingMetricConfig(
        imbalanceToleranceHint = testCase.imbalanceToleranceHintP,
        maxLoadHint = testCase.maxLoadHint
      )
      val loadBalancingConfig = LoadBalancingConfig(
        loadBalancingInterval = 1.minute, // does not affect the current test case
        ChurnConfig.DEFAULT,
        primaryRateMetric = loadBalancingMetricConfig
      )
      val config = Algorithm.Config.create(
        target = Target(getSafeName),
        loadBalancingConfig,
        KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
        testCase.numResources,
        testCase.totalLoad
      )
      assert(
        config.desiredLoadRange.splitThreshold == testCase.expectedSplitThreshold,
        s"unexpected split threshold for test case: $testCase"
      )
    }
  }

  test("calculateDesiredLoadRange requires number of resources to be positive") {
    // Test plan: Verify that `calculateDesiredLoadRange` checks that the number of resources is
    // positive.
    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG, maxLoadHint = 1)

    assertThrow[IllegalArgumentException]("numResources must be positive") {
      Algorithm.calculateDesiredLoadRange(
        targetConfig.loadBalancingConfig,
        numResources = 0,
        totalAdjustedLoad = 1.0
      )
    }
    assertThrow[IllegalArgumentException]("numResources must be positive") {
      Algorithm.calculateDesiredLoadRange(
        targetConfig.loadBalancingConfig,
        numResources = -1,
        totalAdjustedLoad = 1.0
      )
    }

    Algorithm.calculateDesiredLoadRange(
      targetConfig.loadBalancingConfig,
      numResources = 1,
      totalAdjustedLoad = 1.0
    )
  }

  test("DesiredLoadRange validation") {
    // Test plan: Verify that Algorithm.DesiredLoadRange rejects invalid parameters. Verify this by
    // creating a valid DesiredLoadRange and tweaking its fields and verifying proper exceptions
    // are thrown.

    val valid = Algorithm.DesiredLoadRange(
      splitThreshold = 100.0,
      minDesiredLoadExistingResource = 90.0,
      minDesiredLoadNewResource = 200.0,
      maxDesiredLoad = 500.0
    )
    assertThrow[IllegalArgumentException]("splitThreshold must be positive") {
      valid.copy(splitThreshold = -100.0)
    }
    assertThrow[IllegalArgumentException]("splitThreshold must be positive") {
      valid.copy(splitThreshold = 0.0)
    }
    assertThrow[IllegalArgumentException]("minDesiredLoadNewResource must be positive") {
      valid.copy(minDesiredLoadNewResource = -100.0)
    }
    assertThrow[IllegalArgumentException]("minDesiredLoadNewResource must be positive") {
      valid.copy(minDesiredLoadNewResource = 0.0)
    }
    assertThrow[IllegalArgumentException]("minDesiredLoadExistingResource must be positive") {
      valid.copy(minDesiredLoadExistingResource = -100.0)
    }
    assertThrow[IllegalArgumentException]("minDesiredLoadExistingResource must be positive") {
      valid.copy(minDesiredLoadExistingResource = 0.0)
    }
    assertThrow[IllegalArgumentException](
      "maxDesiredLoad must be greater than or equal to minDesiredLoadExistingResource"
    ) {
      valid.copy(minDesiredLoadExistingResource = 100.0, maxDesiredLoad = 50.0)
    }
    assertThrow[IllegalArgumentException](
      "maxDesiredLoad must be greater than or equal to minDesiredLoadNewResource"
    ) {
      valid.copy(
        minDesiredLoadNewResource = 100.0,
        minDesiredLoadExistingResource = 50,
        maxDesiredLoad = 50.0
      )
    }
  }

}
