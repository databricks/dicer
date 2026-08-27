package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP.AssignerServiceInfoP
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.test.AssignerServiceInfoTestDataP
import com.databricks.dicer.common.test.AssignerServiceInfoTestDataP.ConstructorValidationTestCaseP
import com.databricks.dicer.common.test.AssignerServiceInfoTestDataP.ValidInfoTestCaseP
import com.databricks.testing.DatabricksTest

class AssignerServiceInfoSuite extends DatabricksTest {
  private val TEST_DATA: AssignerServiceInfoTestDataP =
    loadTestData[AssignerServiceInfoTestDataP](
      "dicer/common/test/data/assigner_service_info_test_data.textproto"
    )

  test("AssignerServiceInfo proto round-tripping") {
    // Test Plan: Verify that a valid AssignerServiceInfo proto round-trips through deserialization
    // and serialization.
    val proto: AssignerServiceInfoP = TEST_DATA.getRoundTripProto
    val info: AssignerServiceInfo = AssignerServiceInfo.fromProto(proto)
    val roundTripProto: AssignerServiceInfoP = info.toProto
    assert(roundTripProto == proto, s"Expected $proto, but got $roundTripProto")
    assert(
      AssignerServiceInfo.fromProto(roundTripProto) == info,
      s"Expected $info, but got ${AssignerServiceInfo.fromProto(roundTripProto)}"
    )
  }

  gridTest("AssignerServiceInfo constructor validation")(TEST_DATA.constructorValidationTestCases) {
    // Test Plan: Verify that the AssignerServiceInfo constructor rejects invalid name and/or
    // instance ID values.
    testCase: ConstructorValidationTestCaseP =>
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        AssignerServiceInfo(name = testCase.getName, instanceId = testCase.getInstanceId)
      }
  }

  gridTest("AssignerServiceInfo toString")(TEST_DATA.validInfoTestCases) {
    // Test Plan: Verify that toString renders the name and instance ID fields.
    testCase: ValidInfoTestCaseP =>
      val info: AssignerServiceInfo = AssignerServiceInfo.fromProto(testCase.getProto)
      assert(
        info.toString == testCase.getExpectedToString,
        s"Expected ${testCase.getExpectedToString}, but got ${info.toString}"
      )
  }
}
