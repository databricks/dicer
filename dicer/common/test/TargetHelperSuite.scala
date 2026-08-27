package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.dicer.common.test.FatalTargetMismatchTestDataP
import com.databricks.testing.DatabricksTest

class TargetHelperSuite extends DatabricksTest {

  private val TEST_DATA: FatalTargetMismatchTestDataP =
    loadTestData[FatalTargetMismatchTestDataP](
      "dicer/common/test/data/fatal_target_mismatch_test_data.textproto"
    )

  test("isFatalTargetMismatch") {
    // Test plan: Verify that isFatalTargetMismatch returns the expected result for all
    // test cases defined in TEST_DATA.

    // Test fatally mismatched cases.
    for (testCase <- TEST_DATA.fatallyMismatchedTestCases) {
      val target1 = TargetHelper.fromProto(testCase.getTarget1)
      val target2 = TargetHelper.fromProto(testCase.getTarget2)
      val description = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )

      val actualFatal = TargetHelper.isFatalTargetMismatch(target1, target2)
      assert(
        actualFatal,
        s"Test case '$description': Expected isFatalTargetMismatch($target1, $target2) " +
        s"to be true, but got false"
      )

      // Verify symmetry.
      val actualFatalSymmetric = TargetHelper.isFatalTargetMismatch(target2, target1)
      assert(
        actualFatalSymmetric,
        s"Test case '$description': Expected isFatalTargetMismatch($target2, $target1) " +
        s"to be true (symmetric), but got false"
      )
    }

    // Test non-fatal or matched cases.
    for (testCase <- TEST_DATA.matchedOrNonFatalMismatchedTestCases) {
      val target1 = TargetHelper.fromProto(testCase.getTarget1)
      val target2 = TargetHelper.fromProto(testCase.getTarget2)
      val description = testCase.description.getOrElse(
        throw new IllegalArgumentException("Require a description for test case")
      )

      val actualFatal = TargetHelper.isFatalTargetMismatch(target1, target2)
      assert(
        !actualFatal,
        s"Test case '$description': Expected isFatalTargetMismatch($target1, $target2) " +
        s"to be false, but got true"
      )

      // Verify symmetry.
      val actualFatalSymmetric = TargetHelper.isFatalTargetMismatch(target2, target1)
      assert(
        !actualFatalSymmetric,
        s"Test case '$description': Expected isFatalTargetMismatch($target2, $target1) " +
        s"to be false (symmetric), but got true"
      )
    }
  }
}
