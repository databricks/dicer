package com.databricks.dicer.common

import java.time.Instant
import java.util.UUID

import scala.collection.immutable.SortedMap
import scala.util.Try

import com.databricks.api.proto.dicer.dpage.{AssignmentViewP, GenerationViewP, ResourceViewP}
import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.test.DpageViewHelpersTestDataP
import com.databricks.dicer.common.test.DpageViewHelpersTestDataP.{
  GenerationToViewProtoTestCaseP,
  GetAssignmentViewProtoTestCaseP
}
import com.databricks.dicer.common.test.SimpleDiffAssignmentP
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

class DPageViewHelpersSuite extends DatabricksTest {

  private val TEST_DATA: DpageViewHelpersTestDataP =
    loadTestData[DpageViewHelpersTestDataP](
      "dicer/common/test/data/dpage_view_helpers_test_data.textproto"
    )

  /**
   * A wrapper around [[parseSimpleDiffAssignment]] that converts the diff assignment to
   * an actual assignment.
   */
  private def parseSimpleAssignment(assignment: SimpleDiffAssignmentP): Assignment = {
    Assignment
      .fromDiff(
        knownAssignmentOpt = None,
        parseSimpleDiffAssignment(assignment)
      )
      .left
      .toOption
      .get
  }

  /** Asserts that the assignment view proto matches the expected output. */
  private def assertAssignmentViewMatches(
      result: AssignmentViewP,
      expected: AssignmentViewP,
      description: String): Unit = {
    val comparableResult: AssignmentViewP = AssignmentViewP(
      generation = result.generation,
      isFrozen = result.isFrozen,
      consistencyMode = result.consistencyMode,
      resources = result.resources.map { resource: ResourceViewP =>
        // We intentionally do not compare UUID and creation time for SQUIDs: the shared
        // test fixture names resources by address rather than specifying full SQUID identity.
        // Therefore, we only ensure that UUID and creation time are present and valid.
        assert(resource.uuid.isDefined, s"missing resource uuid: $description")
        assert(
          Try(UUID.fromString(resource.getUuid)).isSuccess,
          s"invalid resource uuid: $description"
        )
        assert(
          resource.creationTimeStr.isDefined,
          s"missing resource creationTimeStr: $description"
        )
        assert(
          Try(Instant.parse(resource.getCreationTimeStr)).isSuccess,
          s"invalid resource creationTimeStr: $description"
        )

        ResourceViewP(
          address = resource.address,
          attributedLoad = resource.attributedLoad
        )
      },
      slices = result.slices
    )

    assert(comparableResult == expected, s"failed: $description")
  }

  // -- generationToViewProto --

  test("generationToViewProto") {
    // Test plan: Verify that generationToViewProto converts a Generation to a GenerationViewP with
    // the correct incarnation value, number (epoch millis), and human-readable timestamp string.
    for (testCase: GenerationToViewProtoTestCaseP <- TEST_DATA.generationToViewProtoTestCases) {
      val description: String = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )
      val generation: Generation = Generation.fromProto(testCase.getInput)
      val result: GenerationViewP = DPageViewHelpers.generationToViewProto(generation)
      assert(result == testCase.getExpectedOutput, s"failed: $description")
    }
  }

  // -- getAssignmentViewProto --

  test("getAssignmentViewProto") {
    // Test plan: Verify the getAssignmentViewProto function converts an assignment to a proto
    // with the expected fields populated.
    for (testCase: GetAssignmentViewProtoTestCaseP <- TEST_DATA.getAssignmentViewProtoTestCases) {
      val description: String = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )

      // Parse the input assignment.
      val assignmentOpt: Option[Assignment] =
        testCase.input.flatMap(_.assignment).map(parseSimpleAssignment)

      // Parse the input reported load per resource.
      val reportedLoadPerResourceOpt: Option[Map[Squid, Double]] =
        testCase.input
          .map { input: GetAssignmentViewProtoTestCaseP.InputP =>
            input.reportedLoadPerResource.map { reportedLoad =>
              (createTestSquid(reportedLoad.getResource), reportedLoad.getLoad)
            }.toMap
          }

      // Parse the input reported load per slice.
      val reportedLoadPerSliceOpt: Option[Map[Slice, Double]] =
        testCase.input.map { input: GetAssignmentViewProtoTestCaseP.InputP =>
          input.reportedLoadPerSlice.map { reportedLoad =>
            (SliceHelper.fromProto(reportedLoad.getSlice), reportedLoad.getLoad)
          }.toMap
        }
      val topKeysOpt: Option[SortedMap[SliceKey, Double]] =
        testCase.input.map { input: GetAssignmentViewProtoTestCaseP.InputP =>
          SortedMap(
            input.topKeys.map { topKey =>
              (SliceKey.fromRawBytes(topKey.getKey), topKey.getLoad)
            }: _*
          )
        }

      val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
        assignmentOpt = assignmentOpt,
        reportedLoadPerResourceOpt = reportedLoadPerResourceOpt,
        reportedLoadPerSliceOpt = reportedLoadPerSliceOpt,
        topKeysOpt = topKeysOpt
      )

      assertAssignmentViewMatches(result, testCase.getExpectedOutput, description)
    }
  }
}
