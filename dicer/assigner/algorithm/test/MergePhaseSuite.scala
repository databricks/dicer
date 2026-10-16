package com.databricks.dicer.assigner.algorithm

import com.databricks.dicer.assigner.config.InternalTargetConfig
import com.databricks.dicer.assigner.config.testing.{ConfigTestUtils}
import com.databricks.dicer.common.{
  Assignment,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceAssignment
}
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveLongFluent,
  LowInclusiveStringFluent,
  ProposedSliceAssignmentFluent,
  SliceAssignmentSliceFluent,
  createProposal,
  toProposedAssignmentEntry,
  toSliceKey,
  toSquid,
  `∞`
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.{SliceMap, Squid}

/** Tests for the merge phase of the algorithm (see [[MergePhase]]). */
class MergePhaseSuite extends AlgorithmPhaseSuiteBase {

  test("Merges coldest slices to within MIN and MAX avg slice replicas per resource") {
    // Test plan: Verify that coldest slices are merged until the total slice replicas are within
    // `MIN_AVG_SLICE_REPLICAS` and `MAX_AVG_SLICE_REPLICAS` average slice replicas per resource,
    // and that the resource chosen for the merged slice is chosen to maximize availability while
    // also minimizing churn. Verify this by creating assignments with more than 64*|resources|
    // Slices, and verify that the expected Slices are merged. Examples are structured to test
    // various internal edge cases, which are discussed in the test case descriptions/comments.

    case class TestCase(
        description: String,
        predecessor: SliceMap[ProposedSliceAssignment],
        expected: SliceMap[ProposedSliceAssignment],
        resourcesOpt: Option[Seq[Squid]] = None)

    // Placeholder to use as a wildcard in expected assignments, indicating that the assigned
    // resource is not relevant to the test outcome. Used in test cases where available resources
    // are changing and its difficult to predict which resource will be assigned by the allocation
    // phase (which is not the focus of the current test).
    val wildcardResources: Seq[Squid] = Seq("wildcard-pod")

    val testCases: Seq[TestCase] = Seq(
      // Initial assignment has 65 Slices and one resource. Expected the first two Slices (which
      // have less load than other Slices) to be merged to get down to 64 Slices.
      TestCase(
        description = "First two Slices merged",
        predecessor = createProposal(
          (("" -- 10) -> Seq("pod-0")).withPrimaryRateLoad(5),
          ((10 -- 20) -> Seq("pod-0")).withPrimaryRateLoad(5),
          ((20 -- ∞) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 10)
        ),
        expected = createProposal(
          (("" -- 20) -> Seq("pod-0")).withPrimaryRateLoad(10),
          ((20 -- ∞) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 10)
        )
      ),
      // Like the previous case, but the last two Slices are merged instead of the first two.
      TestCase(
        description = "Last two Slices merged",
        predecessor = createProposal(
          (("" -- 6300) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 10),
          ((6300 -- 7000) -> Seq("pod-0")).withPrimaryRateLoad(5),
          ((7000 -- ∞) -> Seq("pod-0")).withPrimaryRateLoad(5)
        ),
        expected = createProposal(
          (("" -- 6300) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 10),
          ((6300 -- ∞) -> Seq("pod-0")).withPrimaryRateLoad(10)
        )
      ),
      // Like the previous case, but two Slices in the middle are merged.
      TestCase(
        description = "Middle Slices merged",
        predecessor = createProposal(
          (("" -- 10000) -> Seq("pod-0")).subdivide(32, primaryRateLoadPerSlice = 10),
          ((10000 -- 11000) -> Seq("pod-0")).withPrimaryRateLoad(5),
          ((11000 -- 12000) -> Seq("pod-0")).withPrimaryRateLoad(5),
          ((12000 -- ∞) -> Seq("pod-0")).subdivide(31, primaryRateLoadPerSlice = 10)
        ),
        expected = createProposal(
          (("" -- 10000) -> Seq("pod-0")).subdivide(32, primaryRateLoadPerSlice = 10),
          ((10000 -- 12000) -> Seq("pod-0")).withPrimaryRateLoad(10),
          ((12000 -- ∞) -> Seq("pod-0")).subdivide(31, primaryRateLoadPerSlice = 10)
        )
      ),
      // When merging two Slices that belong to different resources, the merged Slice should be
      // assigned to the resource that has the larger share of the load (this heuristic minimizes
      // churn).
      TestCase(
        description = "Middle Slices merged, first resource has more load",
        predecessor = createProposal(
          (("" -- 0x10000) -> Seq("pod-0")).subdivide(64, primaryRateLoadPerSlice = 10),
          ((0x10000 -- 0x11000) -> Seq("pod-1")).withPrimaryRateLoad(8),
          ((0x11000 -- 0x12000) -> Seq("pod-0")).withPrimaryRateLoad(4),
          ((0x12000 -- ∞) -> Seq("pod-1")).subdivide(63, primaryRateLoadPerSlice = 10)
        ),
        expected = createProposal(
          (("" -- 0x10000) -> Seq("pod-0")).subdivide(64, primaryRateLoadPerSlice = 10),
          ((0x10000 -- 0x12000) -> Seq("pod-1")).withPrimaryRateLoad(12),
          ((0x12000 -- ∞) -> Seq("pod-1")).subdivide(63, primaryRateLoadPerSlice = 10)
        )
      ),
      TestCase(
        description = "Middle Slices merged, second resource has more load",
        predecessor = createProposal(
          (("" -- 0x10000) -> Seq("pod-0")).subdivide(64, primaryRateLoadPerSlice = 10),
          ((0x10000 -- 0x11000) -> Seq("pod-1")).withPrimaryRateLoad(4),
          ((0x11000 -- 0x12000) -> Seq("pod-0")).withPrimaryRateLoad(8),
          ((0x12000 -- ∞) -> Seq("pod-1")).subdivide(63, primaryRateLoadPerSlice = 10)
        ),
        expected = createProposal(
          (("" -- 0x10000) -> Seq("pod-0")).subdivide(64, primaryRateLoadPerSlice = 10),
          ((0x10000 -- 0x12000) -> Seq("pod-0")).withPrimaryRateLoad(12),
          ((0x12000 -- ∞) -> Seq("pod-1")).subdivide(63, primaryRateLoadPerSlice = 10)
        )
      ),
      // Repeatedly merging Slices should not cause any problems.
      TestCase(
        description = "Repeatedly merging Slices",
        predecessor = createProposal(
          (("" -- 0x40000) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 100),
          ((0x40000 -- 0x50000) -> Seq("pod-0")).withPrimaryRateLoad(20),
          ((0x50000 -- 0x60000) -> Seq("pod-0")).withPrimaryRateLoad(10),
          ((0x60000 -- ∞) -> Seq("pod-0")).withPrimaryRateLoad(30)
        ),
        expected = createProposal(
          (("" -- 0x40000) -> Seq("pod-0")).subdivide(63, primaryRateLoadPerSlice = 100),
          ((0x40000 -- ∞) -> Seq("pod-0")).withPrimaryRateLoad(60) // 0x50000 then 0x60000 removed
        )
      )
    )
    for (testCase <- testCases) {
      val predecessor: Assignment =
        commitProposal(
          ProposedAssignment(
            predecessorOpt = None,
            sliceMap = testCase.predecessor,
            assignerServiceInfoOpt = None
          )
        )
      // Disable churn penalties, since they make reasoning about the load more challenging.
      val config: InternalTargetConfig =
        createConfigForLoadBalancing(ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG, maxLoadHint = 1)

      // Assume that all resources in the predecessor are available if the test case does not
      // provide them.
      val resources =
        Resources.create(testCase.resourcesOpt.getOrElse(predecessor.assignedResources))

      // Create a load map from the predecessor, since it is convenient to indicate load inline
      // in our test cases.
      val loadMap = LoadMap
        .newBuilder()
        .putLoad(
          predecessor.sliceMap.entries.map { sliceAssignment: SliceAssignment =>
            LoadMap.Entry(sliceAssignment.slice, sliceAssignment.primaryRateLoadOpt.getOrElse(0))
          }: _*
        )
        .build()

      // Run the algorithm!
      val actual: Assignment =
        generateAssignment(Target(getSafeName), config, resources, predecessor, loadMap)
      for (pair <- actual.sliceMap.entries.zip(testCase.expected.entries)) {
        val (actualSlice, expectedSlice): (SliceAssignment, ProposedSliceAssignment) =
          pair
        if (expectedSlice.resources != wildcardResources.toSet) {
          assert(actualSlice.resourcesSet == expectedSlice.resources, testCase.description)
        }
        assert(actualSlice.slice == expectedSlice.slice, testCase.description)
      }
    }
  }
}
