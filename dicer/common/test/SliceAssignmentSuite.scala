package com.databricks.dicer.common

import scala.util.Random
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.test.{SimpleDiffAssignmentP, SliceAssignmentTestDataP}
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.Squid
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.test.SimpleDiffAssignmentP.TransferP
import com.databricks.testing.DatabricksTest

import java.time.Instant
import com.databricks.dicer.common.test.SliceAssignmentTestDataP.{
  InvariantTestCaseP,
  ProtoValidityTestCaseP
}

class SliceAssignmentSuite extends DatabricksTest {

  private val TEST_DATA: SliceAssignmentTestDataP =
    loadTestData[SliceAssignmentTestDataP](
      "dicer/common/test/data/slice_assignment_test_data.textproto"
    )

  /**
   * Converts `sliceAssignment` to a protobuf message and converts the protobuf message back to
   * a [[SliceAssignment]] scala class. Asserts that the re-constructed
   * [[SliceAssignment]] has the same values as the original one, and can be converted
   * to the same proto message.
   */
  private def testRoundTrip(sliceAssignment: SliceAssignment): Unit = {
    val resBuilder = new Assignment.ResourceProtoBuilder
    val sliceAssignmentP = sliceAssignment.toProto(resBuilder)
    val sliceAssignmentAfterRoundTrip =
      SliceAssignment.fromProto(
        sliceAssignmentP,
        Assignment.ResourceMap.fromProtos(resBuilder.toProtos)
      )

    assert(sliceAssignmentAfterRoundTrip == sliceAssignment)
    assert(sliceAssignmentAfterRoundTrip.toProto(resBuilder) == sliceAssignmentP)
  }

  /**
   * Asserts that [[SliceAssignment.checkAssignmentGeneration]] correctly checks
   * assignment generation for `sliceAssignment`.
   */
  private def testCheckAssignmentGeneration(sliceAssignment: SliceAssignment): Unit = {
    // Get the original generation in sliceAssignment, then tweak it into various values, and assert
    // sliceAssignment.checkAssignmentGeneration behaves correctly for each of those values.
    val incarnationValue: Long = sliceAssignment.generation.incarnation.value
    val generationNumber: Long = sliceAssignment.generation.number.value

    // Valid assignment generation (same incarnation with higher generation number). Should not
    // throw.
    sliceAssignment.checkAssignmentGeneration(incarnationValue ## generationNumber)
    sliceAssignment.checkAssignmentGeneration(incarnationValue ## (generationNumber + 1))
    sliceAssignment.checkAssignmentGeneration(incarnationValue ## (generationNumber + 10000))

    // Assignment generation is less than slice assignment generation.
    assertThrow[IllegalArgumentException](
      "must be less than or equal to the assignment generation"
    ) {
      sliceAssignment.checkAssignmentGeneration(incarnationValue ## (generationNumber - 1))
    }
    // Assignment generation has different incarnation from slice assignment.
    assertThrow[IllegalArgumentException](
      "must be in the same incarnation as the assignment generation"
    ) {
      sliceAssignment.checkAssignmentGeneration((incarnationValue + 1) ## generationNumber)
    }
  }

  test("SliceAssignment checkAssignmentGeneration") {
    // Test plan: Verify that SliceAssignment.checkAssignmentGeneration works correctly
    // by calling `testCheckAssignmentGeneration` with various slice assignment with different
    // generations.

    val sliceAssignment = SliceAssignment(
      SliceWithResources("Nori" -- "Ori", Set("Pod0", "Pod1", "Pod2")),
      generation = 20 ## 42,
      subsliceAnnotationsByResource = Map(
        "Pod0" -> Vector(
          SubsliceAnnotation("Nori" -- "Orb", 1, stateTransferOpt = None),
          SubsliceAnnotation("Orb" -- "Ori", 1, stateTransferOpt = None)
        )
      ),
      primaryRateLoadOpt = Some(42.2)
    )

    val testGenerations: Seq[Generation] = Seq(
      20 ## 42,
      20 ## 420,
      1 ## 2,
      (1 << 20) ## (1 << 20),
      (1 << 20 + 1) ## Instant.now().toEpochMilli
    )

    for (generation: Generation <- testGenerations) {
      testCheckAssignmentGeneration(sliceAssignment.copy(generation = generation))
    }
  }

  test("SliceAssignment apply and fromProto invariants validity") {
    // Test plan: Verify that SliceAssignment.apply and SliceAssignment.fromProto fail with correct
    // errors when trying to create SliceAssignment from invalid values or protos.

    // Verify `SliceAssignment.apply`.
    for (testCase: InvariantTestCaseP <- TEST_DATA.invariantTestCases) {
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        SliceAssignment(
          SliceWithResources(
            SliceHelper.fromProto(testCase.getSliceAssignment.getSlice),
            testCase.getSliceAssignment.resources
              .map((resource: String) => createTestSquid(resource))
              .toSet
          ),
          Generation.fromProto(testCase.getSliceAssignment.getGeneration),
          testCase.getSliceAssignment.subsliceAnnotations
            .map { annotation: SimpleDiffAssignmentP.SubsliceAnnotationP =>
              createTestSquid(annotation.getResource) ->
              SubsliceAnnotation(
                subslice = SliceHelper.fromProto(annotation.getSlice),
                continuousGenerationNumber = annotation.getGenerationNumber,
                stateTransferOpt = annotation.stateTransfer
                  .map((transfer: TransferP) => Transfer(transfer.getId, transfer.getFromResource))
              )
            }
            .groupBy { case (resource: Squid, _) => resource }
            .map {
              case (resource: Squid, annotations: Seq[(Squid, SubsliceAnnotation)]) =>
                resource -> annotations.map { case (_, annotation) => annotation }.toVector
            },
          testCase.getSliceAssignment.primaryRateLoadOpt
        )
      }
    }

    // Verify `SliceAssignment.fromProto`.
    val resourceMap = Assignment.ResourceMap.fromProtos(
      Seq(createTestSquid("Pod0").toProto, createTestSquid("Pod1").toProto)
    )
    for (testCase: ProtoValidityTestCaseP <- TEST_DATA.protoValidityTestCases) {
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        SliceAssignment.fromProto(testCase.getInvalidProto, resourceMap)
      }
    }
  }

  test("Empty subslice annotation vector is not allowed") {
    // Test plan: Verify that construction of a `SliceAssignment` with an empty vector of subslice
    // annotations for one of the resources fails.
    val resource: Squid = createTestSquid("Pod0")
    assertThrow[IllegalArgumentException](
      s"Subslice annotations for resource $resource should not be empty"
    ) {
      SliceAssignment(
        SliceWithResources("Nori" -- "Ori", Set(resource)),
        Generation(Incarnation(42), number = 90),
        subsliceAnnotationsByResource = Map(resource -> Vector.empty),
        primaryRateLoadOpt = None
      )
    }
  }

  test("SliceAssignment round tripping") {
    // Test plan: Verify that a valid SliceAssignment can be converted to
    // SliceAssignmentP and back. Verify both manual constructed SliceAssignment and
    // randomly created ones.

    // Manual test cases.
    for (testCase: SimpleDiffAssignmentP.SimpleSliceAssignmentP <- TEST_DATA.roundTripTestCases) {
      testRoundTrip(parseSimpleSliceAssignment(testCase))
    }

    // Random test cases.
    for (_ <- 0 until 100) { // 100 trials.
      val (slice, subslices): (Slice, Vector[Slice]) =
        createRandomSliceWithSubslices(numSubslices = 10, Random)
      val generation =
        Generation(Incarnation(value = Random.nextInt(100)), number = Instant.now().toEpochMilli)
      testRoundTrip(createRandomSliceAssignment(slice, subslices, generation, Random))
    }
  }
}
