package com.databricks.dicer.assigner

import scala.concurrent.duration._

import org.scalatest.exceptions.TestFailedException

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.assigner.MigrationTestAssignment._
import com.databricks.dicer.assigner.MigrationTestAssignment.TestSliceReplica._
import com.databricks.dicer.common.AssignmentConsistencyMode.Affinity
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{Assignment, Generation, SliceAssignment, SubsliceAnnotation}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.testing.DatabricksTest

class MigrationTestAssignmentSuite extends DatabricksTest {

  test("MigrationTestAssignment.assignment") {
    // Test plan: Verify that MigrationTestAssignment.assignment returns assignments with slice
    // replicas assigned to expected resources, containing expected load and continuously assigned
    // duration as specified in MigrationTestAssignment.create().

    val assignment: Assignment =
      MigrationTestAssignment
        .create(
          "pod0" --> Seq(10.0 @@ 0.minute, 20.0 @@ 2.minutes),
          "pod1" --> Seq(10.0 @@ 1.minute, --.--.--.--.--.--)
        )
        .assignment
    val generation: Generation = assignment.generation
    assert(
      assignment == createAssignment(
        generation,
        Affinity,
        assignerServiceInfoOpt = None,
        // Placeholder slice:
        (("" -- 0) @@ generation -> Seq("pod0")).withPrimaryRateLoad(0.0),
        // Interesting slices:
        ((0 -- 1) @@ generation -> Seq("pod0", "pod1")).withPrimaryRateLoad(20.0) | Map(
          "pod0" -> Seq(SubsliceAnnotation(0 -- 1, generation.number, stateTransferOpt = None)),
          "pod1" -> Seq(
            SubsliceAnnotation(
              0 -- 1,
              UnixTimeVersion(generation.number.value - 1.minute.toMillis),
              stateTransferOpt = None
            )
          )
        ),
        ((1 -- 2) @@ generation -> Seq("pod0")).withPrimaryRateLoad(20.0) | Map(
          "pod0" -> Seq(
            SubsliceAnnotation(
              1 -- 2,
              UnixTimeVersion(generation.number.value - 2.minutes.toMillis),
              stateTransferOpt = None
            )
          )
        ),
        // Placeholder slice:
        ((2 -- ∞) @@ generation -> Seq("pod0")).withPrimaryRateLoad(0.0)
      )
    )
  }

  test("MigrationTestAssignment fills up extra load") {
    // Test plan: Verify that MigrationTestAssignment automatically fills up the extra slices with
    // 1.0 load to satisfy the specified total load.

    val migrationTestAssignment = MigrationTestAssignment
      .create(
        "pod0" --> Seq(10.0) withTotal 100.0,
        "pod1" --> Seq(10.0) withTotal 200.0
      )
    val assignment: Assignment = migrationTestAssignment.assignment
    assert(
      assignment.sliceAssignments.exists { sliceAssignment: SliceAssignment =>
        sliceAssignment.resources == Set(createTestSquid("pod0"), createTestSquid("pod1")) &&
        sliceAssignment.primaryRateLoadOpt.contains(20.0)
      }
    )
    assert(migrationTestAssignment.loadMap.getLoad("" -- ∞) == 300.0)
    assert(
      assignment.sliceAssignments
        .map((_: SliceAssignment).primaryRateLoadOpt.get)
        .sum == 300.0
    )
    assert(assignment.sliceAssignments.count { sliceAssignment: SliceAssignment =>
      sliceAssignment.primaryRateLoadOpt.contains(1.0)
    } == 280)
  }

  test("MigrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs()") {
    // Test plan: Verify that MigrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs
    // passes with real assignments containing slice replicas and total loads that are consistent
    // with the MigrationTestAssignment, and throws for assignments that are not.

    val migrationTestAssignment =
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, 20.0) withTotal 100.0,
        "pod1" --> Seq(10.0, --.-) withTotal 200.0
      )
    val assignment1: Assignment = createAssignment(
      42,
      Affinity,
      assignerServiceInfoOpt = None,
      (("" -- 0) @@ 42 -> Seq("pod0", "pod1")).withPrimaryRateLoad(20.0),
      ((0 -- 1) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(20.0),
      ((1 -- 2) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(70.0),
      ((2 -- ∞) @@ 42 -> Seq("pod1")).withPrimaryRateLoad(190.0)
    )
    migrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs(
      assignment1,
      description = "Good assignment!"
    )

    val assignment2: Assignment = createAssignment(
      42,
      Affinity,
      assignerServiceInfoOpt = None,
      (("" -- 0) @@ 42 -> Seq("pod0", "pod1")).withPrimaryRateLoad(20.0),
      // Miss the slice with load 20.0 assigned to pod0.
      ((0 -- 1) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(90.0),
      ((1 -- ∞) @@ 42 -> Seq("pod1")).withPrimaryRateLoad(190.0)
    )
    assertThrow[TestFailedException]("Cannot find expected slice assignment") {
      migrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs(
        assignment2,
        description = "Bad assignment with expected slice missing!"
      )
    }

    val assignment3: Assignment = createAssignment(
      42,
      Affinity,
      assignerServiceInfoOpt = None,
      (("" -- 0) @@ 42 -> Seq("pod0", "pod1")).withPrimaryRateLoad(20.0),
      ((0 -- 1) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(20.0),
      ((1 -- 2) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(70.0),
      ((2 -- ∞) @@ 42 -> Seq("pod1")).withPrimaryRateLoad(189.0) // Total load for pod1 not match.
    )
    assertThrow[TestFailedException]("to be 200.0, but got 199.0") {
      migrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs(
        assignment3,
        description = "Bad assignment with total load mismatch!"
      )
    }

    val assignment4: Assignment = createAssignment(
      42,
      Affinity,
      assignerServiceInfoOpt = None,
      (("" -- 0) @@ 42 -> Seq("pod0", "pod1")).withPrimaryRateLoad(20.0),
      ((0 -- 1) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(20.0) | Map(
        "pod0" -> Seq(SubsliceAnnotation(0 -- 1, UnixTimeVersion(41), stateTransferOpt = None))
      ),
      ((1 -- 2) @@ 42 -> Seq("pod0")).withPrimaryRateLoad(70.0),
      ((2 -- ∞) @@ 42 -> Seq("pod1")).withPrimaryRateLoad(190.0)
    )
    migrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs(
      assignment4,
      description = "Good assignment with only a subslice annotation mismatching!"
    )
  }

  test("MigrationTestAssignment rejects invalid data") {
    // Test plan: Verify that MigrationTestAssignment rejects invalid data.

    assertThrow[IllegalArgumentException](
      "All assigned slice replicas for the same slice should have the same load"
    ) {
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, 20.0) withTotal 100.0,
        "pod1" --> Seq(11.0, --.-) withTotal 200.0
      )
    }

    assertThrow[IllegalArgumentException](
      "Each resource should have the same number of interesting slices."
    ) {
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, 20.0, 30.0) withTotal 100.0,
        "pod1" --> Seq(10.0, --.-) withTotal 200.0
      )
    }

    assertThrow[IllegalArgumentException](
      "Total load must be no less than existing interesting load."
    ) {
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, 20.0) withTotal 29.0,
        "pod1" --> Seq(10.0, --.-) withTotal 200.0
      )
    }

    assertThrow[IllegalArgumentException]("load must be positive") {
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, -10.0) withTotal 100.0,
        "pod1" --> Seq(10.0, --.-) withTotal 200.0
      )
    }

    assertThrow[IllegalArgumentException]("continuouslyAssignedDuration must be non-negative") {
      MigrationTestAssignment.create(
        "pod0" --> Seq(10.0, 10.0 @@ -1.minute) withTotal 100.0,
        "pod1" --> Seq(10.0, --.-) withTotal 200.0
      )
    }
  }

  test("TestSliceReplica.Unassigned shorthands") {
    // Test plan: Verify that the combinations of "--" and "-" shorthands can create
    // literal representations of TestSliceReplica.Unassigned with various lengths.

    assert(-- == TestSliceReplica.Unassigned)
    assert(--- == TestSliceReplica.Unassigned)
    assert(--.- == TestSliceReplica.Unassigned)
    assert(--.-- == TestSliceReplica.Unassigned)
    assert(--.--.- == TestSliceReplica.Unassigned)
    assert(--.--.-- == TestSliceReplica.Unassigned)
    assert(--.--.--.- == TestSliceReplica.Unassigned)
    assert(--.--.--.-- == TestSliceReplica.Unassigned)
  }
}
