package com.databricks.dicer.common

import scala.util.Random
import com.databricks.api.proto.dicer.common.DiffAssignmentP
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.Slice
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.testing.DatabricksTest

import java.time.Instant
import com.databricks.dicer.common.test.{DiffAssignmentTestDataP, SimpleDiffAssignmentP}
import com.databricks.dicer.common.test.DiffAssignmentTestDataP.{
  AssignedResourcesTestCaseP,
  FromProtoValidityTestCaseP,
  InvariantValidityTestCaseP
}
import com.databricks.dicer.common.SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR

class DiffAssignmentSuite extends DatabricksTest {

  private val TEST_DATA: DiffAssignmentTestDataP =
    loadTestData[DiffAssignmentTestDataP](
      "dicer/common/test/data/diff_assignment_test_data.textproto"
    )

  /**
   * Creates a [[DiffAssignmentSliceMap]] that assigns complete slices with random
   * boundaries to a random set of resources as of `generation`. When `useFullDiff` is true, creates
   * a [[DiffAssignmentSliceMap.Full]]; otherwise creates a
   * [[DiffAssignmentSliceMap.Partial]] with a random diff generation.
   *
   * The result also guarantees that no two adjacent SliceMap entries will contain same value. This
   * is useful in verifying DiffAssignment's round trips, as adjacent GapEntries will be
   * automatically coalesced during round-tripping.
   */
  private def createRandomDiffAssignmentSliceMap(
      useFullDiff: Boolean,
      generation: Generation): DiffAssignmentSliceMap = {
    // Ordered, disjoint, and complete Slices with random boundaries.
    val slices: Vector[Slice] = createCompleteSlices(n = Random.nextInt(100) + 1, Random)
    if (useFullDiff) {
      // When `useFullDiff` is true, create random `SliceAssignment` for each slice.
      val diffEntries: Vector[SliceAssignment] =
        slices.map { slice: Slice =>
          val sliceAssignmentGeneration = Generation(
            incarnation = generation.incarnation,
            number = randomInRange(low = 0, high = generation.number.value, Random)
          )
          createRandomSliceAssignment(
            slice,
            subslices = Vector(slice),
            sliceAssignmentGeneration,
            Random
          )
        }
      // Coalesce the SliceMap entries.
      val coalescedSliceMap: SliceMap[SliceAssignment] =
        SliceMap.coalesceSlices(
          SliceMapHelper.ofSliceAssignments(diffEntries),
          (sliceAssignment: SliceAssignment, newSlice: Slice) => {
            val newSliceWithResources: SliceWithResources = sliceAssignment.sliceWithResources
            sliceAssignment.copy(sliceWithResources = newSliceWithResources)
          }
        )
      DiffAssignmentSliceMap.Full(coalescedSliceMap)
    } else {
      // When `useFullDiff` is false, randomly create gap entries of `SliceAssignment` for each
      // slice.
      val diffGapEntries: Vector[GapEntry[SliceAssignment]] =
        slices.map { slice: Slice =>
          // Random trial to decide whether creating `GapEntry.Gap` or `GapEntry.Some` for `slice`.
          if (Random.nextBoolean()) {
            GapEntry.Gap(slice)
          } else {
            val sliceAssignmentGeneration = Generation(
              incarnation = generation.incarnation,
              number = randomInRange(low = 0, high = generation.number.value, Random)
            )
            GapEntry.Some(
              createRandomSliceAssignment(
                slice,
                subslices = Vector(slice),
                sliceAssignmentGeneration,
                Random
              )
            )
          }
        }
      // Coalesce the SliceMap entries.
      val coalescedSliceMap: SliceMap[GapEntry[SliceAssignment]] =
        SliceMap.coalesceSlices(
          SliceMap.ofGapEntries(diffGapEntries, SLICE_ASSIGNMENT_ACCESSOR),
          (sliceAssignmentGap: GapEntry[SliceAssignment], newSlice: Slice) =>
            sliceAssignmentGap match {
              case GapEntry.Gap(_: Slice) => GapEntry.Gap(newSlice)
              case GapEntry.Some(sliceAssignment: SliceAssignment) =>
                val newSliceWithResources: SliceWithResources = sliceAssignment.sliceWithResources
                GapEntry.Some(
                  sliceAssignment.copy(sliceWithResources = newSliceWithResources)
                )
            }
        )
      DiffAssignmentSliceMap.Partial(
        diffGeneration = Generation(
          incarnation = generation.incarnation,
          number = randomInRange(low = 0, high = generation.number.value, Random)
        ),
        coalescedSliceMap
      )
    }
  }

  test("DiffAssignment.apply invariants validity") {
    // Test plan: Verify that DiffAssignmentWithReplica throws correct exceptions when being
    // constructed with invalid arguments.

    for (testCase: InvariantValidityTestCaseP <- TEST_DATA.invariantValidityTestCases) {
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        parseSimpleDiffAssignment(testCase.getDiffAssignment)
      }
    }
  }

  test("DiffAssignment.fromProto validity") {
    // Test plan: Create invalid DiffAssignmentP protos and check that
    // DiffAssignment.fromProto will fail with them.

    // Get the base valid proto. Then we will generate invalid protos from it and validate. We
    // start with a valid proto so that we can then only have one violation and check for it.
    // Otherwise, we run the risk of having validation fail on one of the many violations, i.e.
    // not be able to ascertain that a particular violation happened.
    val validDiffAssignment: DiffAssignment =
      parseSimpleDiffAssignment(TEST_DATA.getValidDiffAssignment)
    val validProto: DiffAssignmentP = validDiffAssignment.toProto

    // validate the proto.
    assert(
      DiffAssignment
        .fromProto(validProto) == validDiffAssignment
    )

    for (testCase: FromProtoValidityTestCaseP <- TEST_DATA.fromProtoValidityTestCases) {
      // Apply the transformation to create an invalid proto.
      val invalidProto: DiffAssignmentP = testCase.transformation match {
        case FromProtoValidityTestCaseP.Transformation.ClearGeneration(_) =>
          validProto.clearGeneration
        case FromProtoValidityTestCaseP.Transformation.SelectResources(indexList) =>
          validProto.withResources(
            indexList.indexes.map(validProto.resources)
          )
        case FromProtoValidityTestCaseP.Transformation.SelectSliceAssignments(indexList) =>
          validProto.withSliceAssignments(
            indexList.indexes.map(validProto.sliceAssignments)
          )
        case FromProtoValidityTestCaseP.Transformation.AlterSliceGeneration(change) =>
          validProto.withSliceAssignments(
            validProto.sliceAssignments.updated(
              change.getSliceIndex,
              validProto
                .sliceAssignments(change.getSliceIndex)
                .withGeneration(change.getNewGeneration)
            )
          )
        case FromProtoValidityTestCaseP.Transformation.AlterSliceResources(change) =>
          validProto.withSliceAssignments(
            validProto.sliceAssignments.updated(
              change.getSliceIndex,
              validProto
                .sliceAssignments(change.getSliceIndex)
                .withResourceIndices(change.newResourceIndices)
            )
          )
        case FromProtoValidityTestCaseP.Transformation.Empty =>
          throw new IllegalArgumentException("Invalid transformation")
      }

      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        DiffAssignment.fromProto(invalidProto)
      }
    }
  }

  test("DiffAssignment round-tripping") {
    // Test plan: Verify that a valid DiffAssignment can be converted to DiffAssignmentP
    // and back.

    // Converts `diffAssignment` to a protobuf message and converts the protobuf message back to
    // a `DiffAssignment` scala class. Asserts that the re-constructed
    // `DiffAssignment` has the same values as the original one, and it can be
    // converted to the same proto message as before.
    def testRoundTrip(diffAssignment: DiffAssignment): Unit = {
      val diffAssignmentP: DiffAssignmentP = diffAssignment.toProto
      val diffAssignmentAfterRoundTrip = DiffAssignment.fromProto(diffAssignmentP)

      assert(diffAssignmentAfterRoundTrip == diffAssignment)
      assert(diffAssignmentAfterRoundTrip.toProto == diffAssignmentP)
    }

    // Manual test cases.
    for (testCase: SimpleDiffAssignmentP <- TEST_DATA.roundTripTestCases) {
      testRoundTrip(parseSimpleDiffAssignment(testCase))
    }

    // Random test cases.
    for (_ <- 0 until 100) { // 100 trials.
      // Randomly create the data fields for `DiffAssignment`.
      val isFrozen: Boolean = Random.nextBoolean()
      val consistencyMode = AssignmentConsistencyMode.Affinity
      val useFullDiff: Boolean = Random.nextBoolean()
      val incarnationValue: Long =
        if (useFullDiff) {
          Random.nextInt(100)
        } else {
          // Create a non-loose (non-zero even value) incarnation when using partial diff.
          (Random.nextInt(100) + 1) * 2
        }
      val generation =
        Generation(Incarnation(incarnationValue), number = Instant.now().toEpochMilli)
      val sliceMap: DiffAssignmentSliceMap =
        createRandomDiffAssignmentSliceMap(useFullDiff, generation)
      val diffAssignment =
        DiffAssignment(isFrozen, consistencyMode, generation, sliceMap)
      testRoundTrip(diffAssignment)
    }
  }

  test("DiffAssignment assignedResources") {
    // Test plan: verify that DiffAssignment.assignedResources returns the expected
    // values for various diff assignments.

    for (testCase: AssignedResourcesTestCaseP <- TEST_DATA.assignedResourcesTestCases) {
      val diff: DiffAssignment =
        parseSimpleDiffAssignment(testCase.getDiffAssignment)
      val expectedAssignedResources: Set[Squid] = testCase.expectedAssignedResources.map {
        uri: String =>
          createTestSquid(uri)
      }.toSet
      assert(diff.assignedResources == expectedAssignedResources)
    }
  }
}
