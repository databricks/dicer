package com.databricks.dicer.assigner.algorithm

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.ImbalanceToleranceHintP
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.testing.{ConfigTestUtils}
import com.databricks.dicer.assigner.config.InternalTargetConfig.KeyReplicationConfig
import com.databricks.dicer.common.{
  Assignment,
  Generation,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceAssignment
}
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveLongFluent,
  LowInclusiveStringFluent,
  ProposedSliceAssignmentFluent,
  SliceAssignmentSliceFluent,
  createAssignment,
  createLooseGeneration,
  createProposal,
  createResources,
  toProposedAssignmentEntry,
  toSliceKey,
  toSquid,
  toSquidWithValue,
  `∞`
}
import com.databricks.dicer.external.{InfinitySliceKey, Slice, SliceKey, Target}
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

  test("Merge only combines Slices with the minimum number of replicas") {
    // Test plan: Verify that the merge phase never merges a Slice with more than the minimum
    // number of replicas, even when it is the coldest. Do this by running the merge phase on an
    // assignment with too many slice replicas whose two coldest Slices are replicated, and checking
    // that those Slices survive with their replica counts while other Slices are merged.
    //
    // This test calls `MergePhase.merge` directly rather than `Algorithm.generateAssignment`: the
    // de-replication phase that runs before merge would de-replicate Slices this cold, so they
    // would never reach the merge phase replicated.
    val resources: Resources = createResources("pod-0", "pod-1")
    val numSingleReplicaSlices: Int = 127
    val replicatedSlices: Vector[Slice] = Vector("" -- 10L, 10L -- 20L)
    val singleReplicaSlices: Vector[Slice] = (2 until numSingleReplicaSlices + 2).map { i: Int =>
      if (i < numSingleReplicaSlices + 1) (i * 10L) -- ((i + 1) * 10L) else (i * 10L) -- ∞
    }.toVector
    val generation: Generation = createLooseGeneration(42)
    val predecessor: Assignment = createAssignment(
      generation,
      assignerServiceInfoOpt = None,
      replicatedSlices.map { slice: Slice =>
        slice @@ generation -> Seq("pod-0", "pod-1")
      } ++ singleReplicaSlices.zipWithIndex.map {
        case (slice: Slice, i: Int) => slice @@ generation -> Seq(s"pod-${i % 2}")
      }
    )
    // The replicated Slices are the coldest.
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(replicatedSlices.map { slice: Slice =>
        LoadMap.Entry(slice, 1.0)
      } ++ singleReplicaSlices.map { slice: Slice =>
        LoadMap.Entry(slice, 10.0)
      }: _*)
      .build()
    val assignment: MutableAssignment = createMutableAssignment(predecessor, resources, loadMap)
    val config: Algorithm.Config = createAlgorithmConfig(
      createConfigForLoadBalancing(ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG, maxLoadHint = 1000)
        .copy(keyReplicationConfig = KeyReplicationConfig(minReplicas = 1, maxReplicas = 2)),
      resources,
      loadMap
    )
    // 2 * 2 + 127 = 131 slice replicas, above the maximum of 64 * 2 = 128.
    assert(assignment.currentNumTotalSliceReplicas == 131)

    MergePhase.merge(config, resources, assignment)

    assert(assignment.currentNumTotalSliceReplicas == 128)
    val replicaCountBySlice: Map[Slice, Int] = assignment.sliceAssignmentsIterator.map {
      sliceAssignment: assignment.MutableSliceAssignment =>
        (sliceAssignment.slice, sliceAssignment.currentNumReplicas)
    }.toMap
    for (slice: Slice <- replicatedSlices) {
      assert(replicaCountBySlice.get(slice).contains(2), s"$slice should not have been merged")
    }
  }

  test("Merge does not link candidates across a replicated Slice") {
    // Test plan: Verify that a replicated Slice in the middle of the key space breaks the chain of
    // merge candidates, so no merge spans it. Do this by calling `MergePhase.merge` directly (see
    // the previous test) on an assignment with a replicated Slice whose neighbors on each side are
    // the coldest pair, and checking that each pair merges on its own side while the replicated
    // Slice keeps its bounds and replicas.
    val resources: Resources = createResources("pod-0", "pod-1")
    val numSlices: Int = 129
    val replicatedIndex: Int = 61
    val slices: Vector[Slice] = (0 until numSlices).map { i: Int =>
      if (i == 0) "" -- 10L
      else if (i == numSlices - 1) (i * 10L) -- ∞
      else (i * 10L) -- ((i + 1) * 10L)
    }.toVector
    val generation: Generation = createLooseGeneration(42)
    val predecessor: Assignment = createAssignment(
      generation,
      assignerServiceInfoOpt = None,
      slices.zipWithIndex.map {
        case (slice: Slice, i: Int) =>
          val pods: Seq[Squid] =
            if (i == replicatedIndex) Seq("pod-0", "pod-1") else Seq(s"pod-${i % 2}")
          slice @@ generation -> pods
      }
    )
    // The two Slices on each side of the replicated Slice, and the replicated Slice itself, are the
    // coldest.
    val coldIndices: Set[Int] = (replicatedIndex - 2 to replicatedIndex + 2).toSet
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(slices.zipWithIndex.map {
        case (slice: Slice, i: Int) => LoadMap.Entry(slice, if (coldIndices(i)) 1.0 else 10.0)
      }: _*)
      .build()
    val assignment: MutableAssignment = createMutableAssignment(predecessor, resources, loadMap)
    val config: Algorithm.Config = createAlgorithmConfig(
      createConfigForLoadBalancing(ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG, maxLoadHint = 1000)
        .copy(keyReplicationConfig = KeyReplicationConfig(minReplicas = 1, maxReplicas = 2)),
      resources,
      loadMap
    )
    // 128 + 2 = 130 slice replicas, so two merges bring the total to the maximum of 64 * 2 = 128.
    assert(assignment.currentNumTotalSliceReplicas == 130)

    MergePhase.merge(config, resources, assignment)

    assert(assignment.currentNumTotalSliceReplicas == 128)
    val replicaCountBySlice: Vector[(Slice, Int)] = assignment.sliceAssignmentsIterator.map {
      sliceAssignment: assignment.MutableSliceAssignment =>
        (sliceAssignment.slice, sliceAssignment.currentNumReplicas)
    }.toVector
    val replicatedSlice: Slice = slices(replicatedIndex)
    val leftPair =
      Slice(slices(replicatedIndex - 2).lowInclusive, slices(replicatedIndex - 1).highExclusive)
    val rightPair =
      Slice(slices(replicatedIndex + 1).lowInclusive, slices(replicatedIndex + 2).highExclusive)
    assert(replicaCountBySlice.contains((replicatedSlice, 2)))
    assert(replicaCountBySlice.contains((leftPair, 1)))
    assert(replicaCountBySlice.contains((rightPair, 1)))
    val resultSlices: Vector[Slice] = replicaCountBySlice.map {
      case (slice: Slice, _: Int) => slice
    }
    assert(resultSlices.head.lowInclusive == SliceKey.MIN)
    assert(resultSlices.last.highExclusive == InfinitySliceKey)
    for (i: Int <- 1 until resultSlices.size) {
      assert(resultSlices(i - 1).highExclusive == resultSlices(i).lowInclusive)
    }
  }
}
