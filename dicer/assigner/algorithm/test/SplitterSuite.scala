package com.databricks.dicer.assigner.algorithm

import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  SliceAssignment
}
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveLongFluent,
  LowInclusiveStringFluent,
  SliceAssignmentSliceFluent,
  createAssignment,
  createLooseGeneration,
  createResources,
  toSliceKey,
  toSquid,
  `∞`
}
import com.databricks.dicer.external.{Slice, Target}

/** Tests for the split phases of the algorithm (see [[Splitter]]). */
class SplitterSuite extends AlgorithmPhaseSuiteBase {

  test("Splits hottest slices until slices are no longer splittable") {
    // Test plan: Verify that the algorithm splits the hottest splittable Slices (where a
    // non-splittable Slice is a Slice that contains a single key). Verify this by running the
    // algorithm where the hottest Slice in the existing assignment is unsplittable (because it
    // contains only a single key) and where another hot Slice can only be split once, and checking
    // in the resulting assignment that other cooler Slices are split to get above the minimum
    // required count.

    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint = 100)
    val predecessor: Assignment = createAssignment(
      generation = 42,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      assignerServiceInfoOpt = None,
      ("" -- 10) @@ 42 -> Seq("resource0"),
      (10 -- 11) @@ 42 -> Seq("resource1"), // unsplittable
      (11 -- 50) @@ 42 -> Seq("resource2"),
      (50 -- 52) @@ 42 -> Seq("resource3"), // can only be split once!
      (52 -- ∞) @@ 42 -> Seq("resource4")
    )
    val loadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 1.0),
        LoadMap.Entry(10 -- 11, 200.0),
        LoadMap.Entry(11 -- 50, 1.0),
        LoadMap.Entry(50 -- 52, 150.0),
        LoadMap.Entry(52 -- ∞, 1.0)
      )
      .build()

    // Run the algorithm and verify that the expected Slices are split.
    val resources: Resources =
      createResources("resource0", "resource1", "resource2", "resource3", "resource4")
    val assignment: Assignment =
      generateAssignment(target, targetConfig, resources, predecessor, loadMap)
    assert(assignment.sliceMap.entries.size == 160) // 32 Slices per resource
    val slices: Seq[Slice] = assignment.sliceMap.entries.map { sliceAssignment: SliceAssignment =>
      sliceAssignment.slice
    }
    // Verify that 50 -- 52 was split once.
    assert(slices.contains(50 -- 51))
    assert(slices.contains(51 -- 52))

    // Verify that the unsplittable 10 -- 11 was not split.
    assert(slices.contains(10 -- 11))
  }

  test("Splits only splittable slices") {
    // Test plan: Verify that only splittable slices get split, otherwise the algorithm does not
    // attempt to split them.

    val target = Target(getSafeName)
    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint = 1.0)
    val generation: Generation = createGeneration()
    val predecessor: Assignment = createAssignment(
      generation,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      assignerServiceInfoOpt = None,
      ("" -- 10) @@ generation -> Seq("resource0"),
      (10 -- 11) @@ generation -> Seq("resource1"), // unsplittable
      (11 -- 50) @@ generation -> Seq("resource2"),
      (50 -- 52) @@ generation -> Seq("resource3"), // splittable only once
      (52 -- ∞) @@ generation -> Seq("resource4")
    )
    val loadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 0.0),
        LoadMap.Entry(10 -- 11, 1.0), // non-zero load but unsplittable
        LoadMap.Entry(11 -- 50, 0.0),
        LoadMap.Entry(50 -- 52, 1.0), // non-zero load but splittable only once
        LoadMap.Entry(52 -- ∞, 0.0)
      )
      .build()

    // Run the algorithm and verify that the resulting assignment has the expected Slices.
    val resources: Resources =
      createResources("resource0", "resource1", "resource2", "resource3", "resource4")
    val assignment: Assignment =
      generateAssignment(target, targetConfig, resources, predecessor, loadMap)
    val slicesWithLoad: Seq[(Slice, Double)] = assignment.sliceMap.entries.map {
      sliceAssignment: SliceAssignment =>
        (sliceAssignment.slice, sliceAssignment.primaryRateLoadOpt.get)
    }
    // Due to uniform load reservations, the Slices with "zero" load will nonetheless be split,
    // because they will have non-zero reserved load. In this test, check the split behavior only
    // for the Slices with non-zero load.

    // 10 -- 11 cannot be split any further.
    assert(slicesWithLoad.contains((10 -- 11, 1.0)))

    // 50 -- 52 is split in two.
    assert(slicesWithLoad.contains((50 -- 51, 0.5)))
    assert(slicesWithLoad.contains((51 -- 52, 0.5)))
  }
}
