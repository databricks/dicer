package com.databricks.dicer.common

import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.testing.DatabricksTest

class TestSliceUtilsSuite extends DatabricksTest {

  test("Slice examples") {
    // Test plan: illustrate shorthands supporting the creation of `Slice` instances and validate
    // they have the expected expansions.
    assert(
      ("" -- "b")
      == Slice(SliceKey.MIN, identityKey("b"))
    )
    assert(
      ("a" -- "b")
      == Slice(identityKey("a"), identityKey("b"))
    )
    assert(
      ("a" -- ∞)
      == Slice.atLeast(identityKey("a"))
    )
    assert(
      (fp("Fili") -- fp("Kili"))
      == Slice(fp("Fili"), fp("Kili"))
    )
  }

  test("Generation example") {
    // Test plan: illustrate shorthands supporting the creation of `Generation` instances and
    // validate they have the expected expansions.
    assert(
      42 ## 47
      == Generation(Incarnation(42), 47)
    )
    assert(
      (42: Generation) == Generation(TestSliceUtils.LOOSE_INCARNATION, 42)
    )
  }

  test("ProposedSliceAssignmentexamples") {
    // Test plan: illustrate shorthands supporting the creation of `ProposedSliceAssignment`
    // instances and validate they have the expected expansions.
    assert(
      ("" -- "b") -> Seq("resource1", "resource2")
      ==
      createProposedSliceAssignmentAssumingUniformLoad(
        "" -- "b",
        Set("resource1", "resource2")
      )
    )

    assert(
      ((10 -- 20) -> Seq("pod-0")).withPrimaryRateLoad(40)
      ==
      ProposedSliceAssignment(
        10 -- 20,
        Set("pod-0"),
        primaryRateLoadOpt = Some(40)
      )
    )
  }

  test("SliceAssignment examples") {
    // Test plan: illustrate shorthands supporting the creation of `SliceAssignment` instances and
    // validate their expected long-form equivalents.
    assert(
      ("Fili" -- ∞) @@ 42 -> Seq("resource1", "resource2")
      == createSliceAssignmentAssumingUniformLoad(
        "Fili" -- ∞,
        generation = 42,
        Set("resource1", "resource2"),
        subsliceAnnotationsByResource = Map.empty
      )
    )

    assert(
      (fp("Fili") -- ∞) @@ 42 -> Seq("resource2")
      == createSliceAssignmentAssumingUniformLoad(
        fp("Fili") -- ∞,
        generation = 42,
        Set("resource2"),
        subsliceAnnotationsByResource = Map.empty
      )
    )
    assert(
      ("a" -- "b") @@ (42 ## 47) -> Seq("resource1")
      == createSliceAssignmentAssumingUniformLoad(
        "a" -- "b",
        generation = 42 ## 47,
        Set("resource1"),
        subsliceAnnotationsByResource = Map.empty
      )
    )
  }

  test("SliceMap[ProposedSliceAssignment] examples") {
    // Test plan: illustrate `createProposal` helper and document the expected expansion of various
    // shorthand notations for proposed (Slice) assignments.
    assert(
      createProposal(
        ("" -- 42) -> Seq("resource1"),
        ((42 -- 52) -> Seq("resource3")).subdivide(2),
        (52 -- ∞) -> Seq("resource2", "resource3")
      )
      == SliceMapHelper.ofProposedSliceAssignments(
        Vector(
          createProposedSliceAssignmentAssumingUniformLoad(
            Slice(SliceKey.MIN, toSliceKey(42)),
            Set("resource1")
          ),
          createProposedSliceAssignmentAssumingUniformLoad(
            Slice(toSliceKey(42), toSliceKey(47)),
            Set("resource3")
          ),
          createProposedSliceAssignmentAssumingUniformLoad(
            Slice(toSliceKey(47), toSliceKey(52)),
            Set("resource3")
          ),
          createProposedSliceAssignmentAssumingUniformLoad(
            Slice.atLeast(toSliceKey(52)),
            Set("resource2", "resource3")
          )
        )
      )
    )
  }

  test("ProposedSliceAssignment subdivide example") {
    // Test plan: illustrate `subdivide` helper and test expected longform equivalents.

    // Example 1: with load
    {
      val sliceAssignment: ProposedSliceAssignment =
        (("" -- 9) -> Seq("resource0")).withPrimaryRateLoad(9)
      val subdividedSliceAssignment: TestSliceUtils.ProposedAssignmentEntry.Subdivided =
        sliceAssignment.subdivide(3)
      assert(
        subdividedSliceAssignment.toSliceAssignments == Seq(
          (("" -- 3) -> Seq("resource0")).withPrimaryRateLoad(3),
          ((3 -- 6) -> Seq("resource0")).withPrimaryRateLoad(3),
          ((6 -- 9) -> Seq("resource0")).withPrimaryRateLoad(3)
        )
      )
    }
    // Example 2: without load
    {
      val sliceAssignment: ProposedSliceAssignment = (256 -- 512) -> Seq("resource0")
      val subdividedSliceAssignment: ProposedAssignmentEntry.Subdivided =
        sliceAssignment.subdivide(4)
      assert(
        subdividedSliceAssignment.toSliceAssignments == Seq(
          (256 -- 320) -> Seq("resource0"),
          (320 -- 384) -> Seq("resource0"),
          (384 -- 448) -> Seq("resource0"),
          (448 -- 512) -> Seq("resource0")
        )
      )
    }
    // Example 3: with load per slice
    {
      val sliceAssignment: ProposedSliceAssignment = ("" -- 9) -> Seq("resource0")
      val subdividedSliceAssignment: ProposedAssignmentEntry.Subdivided =
        sliceAssignment.subdivide(3, primaryRateLoadPerSlice = 10)
      assert(
        subdividedSliceAssignment.toSliceAssignments == Seq(
          (("" -- 3) -> Seq("resource0")).withPrimaryRateLoad(10),
          ((3 -- 6) -> Seq("resource0")).withPrimaryRateLoad(10),
          ((6 -- 9) -> Seq("resource0")).withPrimaryRateLoad(10)
        )
      )
    }
    // Example 3: with multiple resources per slice.
    {
      val sliceAssignment: ProposedSliceAssignment = ("" -- 9) -> Seq(
          "resource0",
          "resource1"
        )
      val subdividedSliceAssignment: ProposedAssignmentEntry.Subdivided =
        sliceAssignment.subdivide(3, primaryRateLoadPerSlice = 10)
      assert(
        subdividedSliceAssignment.toSliceAssignments == Seq(
          (("" -- 3) -> Seq("resource0", "resource1")).withPrimaryRateLoad(10),
          ((3 -- 6) -> Seq("resource0", "resource1")).withPrimaryRateLoad(10),
          ((6 -- 9) -> Seq("resource0", "resource1")).withPrimaryRateLoad(10)
        )
      )
    }
  }

  test("createAssignment") {
    // Test plan: illustrate use of the `createAssignment` helper and related shorthand notations
    // for Slice assignments. Validate they have the expected expansions.

    // Test case 1: sequential slice assignments.
    assert(
      createAssignment(
        42 ## 47,
        AssignmentConsistencyMode.Strong,
        assignerServiceInfoOpt = None,
        ("" -- fp("Balin")) @@ (42 ## 47) -> Seq("resource1"),
        (fp("Balin") -- ∞) @@ (42 ## 42) -> Seq("resource2", "resource3")
      )
      == Assignment(
        isFrozen = false,
        AssignmentConsistencyMode.Strong,
        Generation(Incarnation(42), 47),
        SliceMapHelper.ofSliceAssignments(
          Vector(
            createSliceAssignmentAssumingUniformLoad(
              Slice(SliceKey.MIN, fp("Balin")),
              Generation(Incarnation(42), 47),
              Set("resource1"),
              subsliceAnnotationsByResource = Map.empty
            ),
            createSliceAssignmentAssumingUniformLoad(
              Slice.atLeast(fp("Balin")),
              Generation(Incarnation(42), 42),
              Set("resource2", "resource3"),
              subsliceAnnotationsByResource = Map.empty
            )
          )
        ),
        assignerServiceInfoOpt = None
      )
    )

    // Test case 2: sequence of slice assignments + assigner service info.
    assert(
      createAssignment(
        42 ## 47,
        AssignmentConsistencyMode.Strong,
        assignerServiceInfoOpt =
          Some(AssignerServiceInfo(name = "test-assigner", instanceId = "test-instance")),
        Seq(
          ("" -- fp("Balin")) @@ (42 ## 47) -> Seq("resource1"),
          (fp("Balin") -- ∞) @@ (42 ## 42) -> Seq("resource2", "resource3")
        )
      )
      == Assignment(
        isFrozen = false,
        AssignmentConsistencyMode.Strong,
        Generation(Incarnation(42), 47),
        SliceMapHelper.ofSliceAssignments(
          Vector(
            createSliceAssignmentAssumingUniformLoad(
              Slice(SliceKey.MIN, fp("Balin")),
              Generation(Incarnation(42), 47),
              Set("resource1"),
              subsliceAnnotationsByResource = Map.empty
            ),
            createSliceAssignmentAssumingUniformLoad(
              Slice.atLeast(fp("Balin")),
              Generation(Incarnation(42), 42),
              Set("resource2", "resource3"),
              subsliceAnnotationsByResource = Map.empty
            )
          )
        ),
        Some(AssignerServiceInfo(name = "test-assigner", instanceId = "test-instance"))
      )
    )
  }
}
