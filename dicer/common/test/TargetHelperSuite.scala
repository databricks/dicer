package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.dicer.common.test.ShouldServeRequestTargetTestDataP
import com.databricks.testing.DatabricksTest

class TargetHelperSuite extends DatabricksTest {

  private val TEST_DATA: ShouldServeRequestTargetTestDataP =
    loadTestData[ShouldServeRequestTargetTestDataP](
      "dicer/common/test/data/should_serve_request_target_test_data.textproto"
    )

  test("shouldServeRequestTarget") {
    // Test plan: Verify that shouldServeRequestTarget returns the expected result for all
    // test cases defined in TEST_DATA.

    // Cases that should not be served.
    for (testCase <- TEST_DATA.shouldNotServeTestCases) {
      val localTarget = TargetHelper.fromProto(testCase.getLocalTarget)
      val requestTarget = TargetHelper.fromProto(testCase.getRequestTarget)
      val description = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )

      val shouldServe = TargetHelper.shouldServeRequestTarget(localTarget, requestTarget)
      assert(
        !shouldServe,
        s"Test case '$description': Expected shouldServeRequestTarget(" +
        s"$localTarget, $requestTarget) to be false, but got true"
      )
    }

    // Cases that should be served.
    for (testCase <- TEST_DATA.shouldServeTestCases) {
      val localTarget = TargetHelper.fromProto(testCase.getLocalTarget)
      val requestTarget = TargetHelper.fromProto(testCase.getRequestTarget)
      val description = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )

      val shouldServe = TargetHelper.shouldServeRequestTarget(localTarget, requestTarget)
      assert(
        shouldServe,
        s"Test case '$description': Expected shouldServeRequestTarget(" +
        s"$localTarget, $requestTarget) to be true, but got false"
      )
    }
  }
}
