package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP.TransferP
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.test.TransferTestDataP
import com.databricks.dicer.common.testing.SliceTestUtils.{createTestSquid, toSquid}
import com.databricks.dicer.common.test.TransferTestDataP.ProtoValidityTestCaseP

class TransferSuite extends DatabricksTest {
  private val TEST_DATA: TransferTestDataP =
    loadTestData[TransferTestDataP]("dicer/common/test/data/transfer_test_data.textproto")

  test("Transfer proto round-tripping") {
    // Test Plan: Verify that a Transfer instance can be converted to proto and back.

    // Create a valid Transfer instance and proto.
    val transfer = Transfer(42, "Pod0")
    val resourceBuilder = new Assignment.ResourceProtoBuilder
    val transferP: TransferP = transfer.toProto(resourceBuilder)
    val resourceMap = Assignment.ResourceMap.fromProtos(resourceBuilder.toProtos)

    // Verify round-tripping.
    val transferAfterRoundTrip: Transfer = Transfer.fromProto(transferP, resourceMap)
    assert(transferAfterRoundTrip == transfer)
    assert(transferAfterRoundTrip.toProto(resourceBuilder) == transferP)
  }

  test("Transfer proto validity") {
    // Test Plan: Verify that `Transfer.fromProto` throws exceptions for invalid protos.
    val resourceMap = Assignment.ResourceMap.fromProtos(Seq(createTestSquid("Pod0").toProto))
    for (testCase: ProtoValidityTestCaseP <- TEST_DATA.protoValidityTestCases) {
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        Transfer.fromProto(testCase.getTransfer, resourceMap)
      }
    }
  }
}
