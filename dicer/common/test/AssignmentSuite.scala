package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.Assignment.DiffUnused
import com.databricks.dicer.common.Assignment.DiffUnused.DiffUnused
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.SliceKey
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.test.AssignmentTestDataP
import com.databricks.dicer.common.test.AssignmentTestDataP.DiffUnusedP.DIFF_UNUSED_P_UNSPECIFIED
import com.databricks.dicer.common.test.AssignmentTestDataP.FromDiffFailureTestCaseP.ExpectedFailure
import com.databricks.dicer.common.test.AssignmentTestDataP.{
  DiffUnusedP,
  FromDiffFailureTestCaseP,
  FromDiffWithLooseIncarnationDataP,
  GetAssignedSliceAssignmentsTestCaseP,
  GetSliceSetForResourceTestCaseP,
  IsAssignedKeyTestCaseP,
  ToDiffWithHigherDiffGenerationTestCaseP
}
import com.databricks.dicer.common.test.SimpleDiffAssignmentP
import com.databricks.dicer.common.test.SimpleDiffAssignmentP.SimpleSliceAssignmentP
import com.databricks.api.proto.dicer.friend.SliceP

class AssignmentSuite extends DatabricksTest {
  private val TEST_DATA: AssignmentTestDataP =
    loadTestData[AssignmentTestDataP]("dicer/common/test/data/assignment_test_data.textproto")

  /**
   * A wrapper around [[parseSimpleDiffAssignment]] that converts the diff assignment to
   * an actual assignment.
   */
  private def parseSimpleAssignment(assignment: SimpleDiffAssignmentP): Assignment = {
    Assignment
      .fromDiff(
        knownAssignmentOpt = None,
        // `fromDiff` forbids diffs with strong consistency, so we convert to affinity mode here
        // and then convert back to strong mode if needed below.
        parseSimpleDiffAssignment(assignment)
          .copy(consistencyMode = AssignmentConsistencyMode.Affinity)
      )
      .left
      .toOption
      .get
      .copy(consistencyMode = if (assignment.getIsConsistent) {
        AssignmentConsistencyMode.Strong
      } else {
        AssignmentConsistencyMode.Affinity
      })
  }

  test("Assignment round-tripping with full diff") {
    // Test plan: Verify that an Assignment can be converted to a DiffAssignmentP and
    // back without loss in fidelity, where the diff is a full diff.

    // Converts `Assignment` to a [[DiffAssignment]] and then to a [[DiffAssignmentP]],
    // and reconstructed the [[DiffAssignment]] and [[Assignment]] back from the
    // protobuf message. Asserts that the re-constructed [[Assignment]] and
    // [[DiffAssignment]] have the same values as before.
    def testRoundTrip(assignment: Assignment): Unit = {
      // Check round-tripping via proto.
      val diffAssignment: DiffAssignment =
        assignment.toDiff(diffGeneration = Generation.EMPTY)
      val diffAssignmentP: DiffAssignmentP = diffAssignment.toProto
      val diffAssignmentFromProto = DiffAssignment.fromProto(diffAssignmentP)
      val assignmentAfterRoundTrip: Either[Assignment, DiffUnused.DiffUnused] =
        Assignment.fromDiff(knownAssignmentOpt = None, diffAssignmentFromProto)

      assert(assignmentAfterRoundTrip == Left(assignment))
      assert(diffAssignmentFromProto == diffAssignment)
      assert(diffAssignmentFromProto.toProto == diffAssignmentP)
    }

    for (testCase: SimpleDiffAssignmentP <- TEST_DATA.fullDiffRoundTripTestCases) {
      val assignment: Assignment = parseSimpleAssignment(testCase)
      testRoundTrip(assignment)
    }
  }

  test("Assignment round-tripping with partial diff") {
    // Test plan: Verify that an Assignment can be converted to a
    // DiffAssignmentP and back without loss in fidelity, where the diff is a partial diff.

    def testRoundTripWithDiff(knownAssignment: Assignment, assignment: Assignment): Unit = {
      if (knownAssignment.generation < assignment.generation) {
        // If `knownAssignment` has less generation than `assignment`, asserts that `assignment` can
        // be serialized and de-serialized correctly based on the difference with `knownAssignment`.

        // Test round-trip via diff.
        val diffAssignment: DiffAssignment =
          assignment.toDiff(diffGeneration = knownAssignment.generation)
        val assignmentAfterDiffRoundTrip: Either[Assignment, DiffUnused.DiffUnused] =
          Assignment.fromDiff(Some(knownAssignment), diffAssignment)
        assert(assignmentAfterDiffRoundTrip == Left(assignment))

        // Test round-trip via diff proto.
        val diffAssignmentP: DiffAssignmentP = diffAssignment.toProto
        val diffAssignmentFromProto = DiffAssignment.fromProto(diffAssignmentP)
        val assignmentAfterProtoRoundTrip: Either[Assignment, DiffUnused.DiffUnused] =
          Assignment.fromDiff(Some(knownAssignment), diffAssignmentFromProto)
        assert(assignmentAfterProtoRoundTrip == Left(assignment))
      } else {
        // When the known assignment has a generation greater than or equal to that of the diff
        // assignment, `fromDiff` should report that the diff is unused and report the
        // difference in generation.
        val diff: DiffAssignment = assignment.toDiff(Generation.EMPTY)
        val roundtrip: Either[Assignment, DiffUnused.DiffUnused] =
          Assignment.fromDiff(Some(knownAssignment), diff)

        val expectedReason: DiffUnused =
          if (knownAssignment.generation == assignment.generation) DiffUnused.DIFF_MATCHES_KNOWN
          else DiffUnused.TOO_STALE_DIFF
        assert(roundtrip == Right(expectedReason))
      }
    }

    val testAssignments: Seq[Assignment] =
      TEST_DATA.partialDiffRoundTripTestAssignments.map(parseSimpleAssignment)

    // Test round-trips for every 2 combination of the test assignments.
    for (knownAssignment: Assignment <- testAssignments) {
      for (assignment: Assignment <- testAssignments) {
        testRoundTripWithDiff(knownAssignment, assignment)
      }
    }
  }

  test("Assignment fromDiff loose incarnation") {
    // Test plan: Verify that Assignment.fromDiff works correctly in an unusual case
    // that arises when the supplier of the diffs believes that an assignment is in a non-loose
    // incarnation but the recipient believes that it is loose. The recipient can accept the diff,
    // but only if it contains all Slices. See <internal bug> for motivation of this scenario.

    val testData: FromDiffWithLooseIncarnationDataP = TEST_DATA.getFromDiffWithLooseIncarnationData
    for (looseIncarnation: Long <- testData.looseIncarnations) {
      // Sets the incarnation in all generations within the given assignment proto to
      // `looseIncarnation`.
      def populateIncarnation(assignment: SimpleDiffAssignmentP): SimpleDiffAssignmentP = {
        assignment
          .withGeneration(assignment.getGeneration.withIncarnation(looseIncarnation))
          .withSliceAssignments(assignment.sliceAssignments.map {
            sliceAssignment: SimpleSliceAssignmentP =>
              sliceAssignment.withGeneration(
                sliceAssignment.getGeneration.withIncarnation(looseIncarnation)
              )
          })
      }

      // Define an initial assignment.
      val initialAssignment: Assignment = parseSimpleAssignment(
        populateIncarnation(testData.getInitialAssignment)
      )

      // Define a successor assignment.
      val nextAssignment: Assignment =
        parseSimpleAssignment(populateIncarnation(testData.getNextAssignment))

      // Simulate an assignment supplier that believes the assignment is non-loose and would
      // produce an actual diff by modifying the resulting proto (which doesn't resemble a diff
      // because it does not define the `diff_generation` field) to resemble a diff (by setting a
      // `diff_generation` field).
      val diffProto: DiffAssignmentP = nextAssignment.toDiff(initialAssignment.generation).toProto
      assert(diffProto.diffGeneration.isEmpty)
      val hackedDiffProto: DiffAssignmentP =
        diffProto.withDiffGeneration(initialAssignment.generation.toProto)

      // Now attempt to round trip, using both the original diff proto and the modified diff
      // proto.
      val roundtrip: Either[Assignment, DiffUnused] =
        Assignment.fromDiff(
          Some(initialAssignment),
          DiffAssignment.fromProto(diffProto)
        )
      val modifiedRoundtrip: Either[Assignment, DiffUnused] =
        Assignment.fromDiff(
          Some(initialAssignment),
          DiffAssignment.fromProto(hackedDiffProto)
        )
      assert(roundtrip == Left(nextAssignment))
      assert(modifiedRoundtrip == Left(nextAssignment))
    }
  }

  test("Assignment fromDiff failure") {
    // Test plan: Verify the failure cases for Assignment.fromDiff.

    for (testCase: FromDiffFailureTestCaseP <- TEST_DATA.fromDiffFailureTestCases) {
      val knownAssignmentOpt: Option[Assignment] =
        testCase.knownAssignment.map(parseSimpleAssignment)
      val diff: DiffAssignment = parseSimpleDiffAssignment(testCase.getDiff)
      testCase.expectedFailure match {
        case ExpectedFailure.ExpectedDiffUnused(expectedDiffUnusedProto: DiffUnusedP) =>
          val expectedDiffUnused: DiffUnused = expectedDiffUnusedProto match {
            case DiffUnusedP.INCONSISTENCY => DiffUnused.INCONSISTENCY
            case DiffUnusedP.TOO_STALE_DIFF => DiffUnused.TOO_STALE_DIFF
            case DiffUnusedP.DIFF_MATCHES_KNOWN => DiffUnused.DIFF_MATCHES_KNOWN
            case DiffUnusedP.NO_KNOWN => DiffUnused.NO_KNOWN
            case DiffUnusedP.TOO_STALE_KNOWN => DiffUnused.TOO_STALE_KNOWN
            case DIFF_UNUSED_P_UNSPECIFIED =>
              throw new IllegalArgumentException("Invalid DiffUnusedP")
          }
          assert(
            Assignment.fromDiff(knownAssignmentOpt, diff) == Right(expectedDiffUnused)
          )
        case ExpectedFailure.ExpectedError(expectedError: String) =>
          assertThrow[NotImplementedError](expectedError) {
            Assignment.fromDiff(knownAssignmentOpt, diff)
          }
        case ExpectedFailure.Empty =>
          throw new IllegalArgumentException("Empty expected failure")
      }
    }
  }

  test("Assignment.toDiff higher diff generation") {
    // Test plan: Verify that Assignment.toDiff fails when the diff generation is
    // higher than assignment generation.

    val testCase: ToDiffWithHigherDiffGenerationTestCaseP =
      TEST_DATA.getToDiffWithHigherDiffGenerationTestCase
    val assignment: Assignment =
      parseSimpleAssignment(testCase.getAssignment)
    val diffGeneration: Generation = Generation.fromProto(testCase.getDiffGeneration)

    assertThrow[IllegalArgumentException](testCase.getExpectedError) {
      assignment.toDiff(diffGeneration = diffGeneration)
    }
  }

  test("Assignment.assignedResources") {
    // Test plan: Verify that assignedResources returns the expected values for
    // Assignment.

    // Test data maps test assignments to expected set of assigned resources.
    val testAssignmentsWithExpectedResources = Seq[(Assignment, Set[String])](
      (
        createAssignment(
          generation = 67,
          AssignmentConsistencyMode.Affinity,
          ("" -- fp("Dori")) @@ 34 -> Set("Pod2", "Pod3", "Pod101", "Pod102"),
          (fp("Dori") -- fp("Fili")) @@ 24 -> Set("Pod0", "Pod1"),
          (fp("Fili") -- fp("Kili")) @@ 45 -> Set("Pod1", "Pod103"),
          (fp("Kili") -- fp("Nori")) @@ 67 -> Set("Pod2"),
          (fp("Nori") -- ∞) @@ 34 -> Set("Pod3", "Pod100")
        ),
        Set("Pod0", "Pod1", "Pod2", "Pod3", "Pod100", "Pod101", "Pod102", "Pod103")
      ),
      (
        createAssignment(
          generation = 67,
          AssignmentConsistencyMode.Affinity,
          ("" -- fp("Dori")) @@ 34 -> Set("Pod2"),
          (fp("Dori") -- fp("Kili")) @@ 45 -> Set("Pod1"),
          (fp("Kili") -- fp("Nori")) @@ 67 -> Set("Pod2"),
          (fp("Nori") -- ∞) @@ 34 -> Set("Pod3", "Pod4", "Pod5", "Pod6")
        ),
        Set("Pod1", "Pod2", "Pod3", "Pod4", "Pod5", "Pod6")
      ),
      (
        createAssignment(
          generation = 2 ## 81,
          AssignmentConsistencyMode.Strong,
          ("" -- fp("Dori")) @@ (2 ## 55) -> Set("Pod2023"),
          (fp("Dori") -- fp("Kili")) @@ (2 ## 45) -> Set("Pod2024", "Pod2025"),
          (fp("Kili") -- ∞) @@ (2 ## 67) -> Set("Pod2000", "Pod2027")
        ),
        Set("Pod2000", "Pod2023", "Pod2024", "Pod2025", "Pod2027")
      )
    )
    for (assignmentWithExpectedResources <- testAssignmentsWithExpectedResources) {
      val (assignment, expectedAssignedResourceUris): (Assignment, Set[String]) =
        assignmentWithExpectedResources
      val expectedAssignedResources: Set[Squid] = expectedAssignedResourceUris.map { uri: String =>
        createTestSquid(uri)
      }
      assert(assignment.assignedResources == expectedAssignedResources)
    }
  }

  test("Assignment isAssignedKey") {
    // Test plan: Verify `isAssignedKey` returns correct results for Assignment.

    for (testCase: IsAssignedKeyTestCaseP <- TEST_DATA.isAssignedKeyTestCases) {
      val assignment: Assignment =
        parseSimpleAssignment(testCase.getAssignment)

      for (call: IsAssignedKeyTestCaseP.CallP <- testCase.calls) {
        val sliceKey: SliceKey = identityKey(call.getSliceKey)
        val resource: Squid = createTestSquid(call.getResource, call.getResourceSalt)
        assert(assignment.isAssignedKey(sliceKey, resource) == call.getExpectedResult)
      }
    }
  }

  test("Assignment getSliceSetForResource") {
    // Test plan: Verify that getSliceSetForResource returns the expected values for various
    // assignments and resources.

    for (testCase: GetSliceSetForResourceTestCaseP <- TEST_DATA.getSliceSetForResourceTestCases) {
      val assignment: Assignment =
        parseSimpleAssignment(testCase.getAssignment)
      for (call: GetSliceSetForResourceTestCaseP.CallP <- testCase.calls) {
        val resource: Squid = createTestSquid(call.getResource, call.getResourceSalt)
        val expectedAssignedSlices: SliceSetImpl = SliceSetImpl(
          call.expectedSlices.map((slice: SliceP) => SliceHelper.fromProto(slice))
        )
        assert(assignment.getSliceSetForResource(resource) == expectedAssignedSlices)
      }
    }
  }

  test("Assignment.getAssignedSliceAssignments") {
    // Test plan: Verify that Assignment.getAssignedSliceAssignments returns the
    // expected values for various assignments and resources.

    for (testCase <- TEST_DATA.getAssignedSliceAssignmentsTestCases) {
      // Create the input assignment by parsing the test proto data.
      val assignment: Assignment = createAssignment(
        generation = Generation.fromProto(testCase.getAssignment.getGeneration),
        consistencyMode = AssignmentConsistencyMode.Affinity,
        entries = testCase.getAssignment.sliceAssignments.map((proto: SimpleSliceAssignmentP) => {
          val sliceWithResources: SliceWithResources = SliceWithResources(
            slice = SliceHelper.fromProto(proto.getSlice),
            resources = proto.resources.map(createTestSquid(_)).toSet
          )
          SliceAssignment(
            sliceWithResources,
            Generation.fromProto(proto.getGeneration),
            subsliceAnnotationsByResource = Map.empty,
            primaryRateLoadOpt = proto.primaryRateLoadOpt
          )
        })
      )

      for (call: GetAssignedSliceAssignmentsTestCaseP.CallP <- testCase.calls) {
        val resource: Squid = createTestSquid(call.getResource)

        val expectedSliceAssignments: Vector[SliceAssignment] =
          call.expectedSliceAssignments.map { proto =>
            val generation: Generation = Generation.fromProto(proto.getGeneration)
            val sliceWithResources = SliceWithResources(
              slice = SliceHelper.fromProto(proto.getSlice),
              resources = proto.resources.map(createTestSquid(_)).toSet
            )
            val primaryRateLoad: Double = proto.getPrimaryRateLoadOpt

            SliceAssignment(
              sliceWithResources,
              generation,
              subsliceAnnotationsByResource = Map.empty,
              primaryRateLoadOpt = Some(primaryRateLoad)
            )
          }.toVector

        val actualSliceAssignments: Vector[SliceAssignment] =
          assignment.getAssignedSliceAssignments(resource)

        // Compare assignments - note that order matters for this method
        assert(
          actualSliceAssignments == expectedSliceAssignments,
          s"For resource ${call.getResource} -- " +
          s"expected: $expectedSliceAssignments, got: $actualSliceAssignments"
        )
      }
    }
  }

  test("Some edge case assignments") {
    // Test plan: Create some assignments, e.g., full range, no SliceMap and check that the
    // assignment and SliceMap are the same. Perform some lookups as well.

    // Full range.
    val assignment: Assignment = createAssignment(
      35 ## 64,
      AssignmentConsistencyMode.Affinity,
      ("" -- ∞) @@ (35 ## 54) -> Seq("Pod4")
    )
    // Check some keys.
    val emptyKeyResources: Set[Squid] = assignment.sliceMap.lookUp(SliceKey.MIN).resources
    assert(emptyKeyResources == assignment.sliceAssignments.head.resources)
    val gandalfResources: Set[Squid] = assignment.sliceMap.lookUp(fp("Gandalf")).resources
    assert(gandalfResources == assignment.sliceAssignments.head.resources)
  }

  test("Assignment rejects invalid assignments") {
    // Test plan: Verify that `Assignment` rejects invalid assignments. Verify this by
    // creating an assignment with an empty generation number and an assignment with strong
    // consistency mode and a loose generation number and checking that the constructor throws an
    // exception.

    // Empty generation number.
    assertThrow[IllegalArgumentException]("Assignment must have non-empty generation.") {
      createAssignment(
        generation = Generation.EMPTY,
        AssignmentConsistencyMode.Affinity,
        ("" -- ∞) @@ 1 -> Seq("pod0")
      )
    }

    // Strong consistency mode with loose generation number.
    assertThrow[IllegalArgumentException](
      "Consistent assignment cannot be in the loose incarnation."
    ) {
      createAssignment(
        generation = 10,
        AssignmentConsistencyMode.Strong,
        ("" -- ∞) @@ 10 -> Seq("pod0")
      )
    }
  }
}
