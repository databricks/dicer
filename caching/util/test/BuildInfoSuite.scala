package com.databricks.caching.util

import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.test.BuildInfoTestDataP
import com.databricks.caching.util.test.BuildInfoTestDataP.{
  BuildInfoP,
  BuildInfoTestCaseP,
  messageCompanion
}

class BuildInfoSuite extends DatabricksTest {

  private val TEST_CASES: Seq[BuildInfoTestCaseP] =
    TestUtils
      .loadTestData[BuildInfoTestDataP](
        "caching/util/test/data/build_info_test_data.textproto"
      )
      .testCases

  test("Parse branch names") {
    // Test plan: Verify that `BuildInfo.parseClientVersion` correctly parses the branch name,
    // commit time, and commit hash from the input string. Verify this by iterating through
    // all test cases specified in the textproto and checking that the returned build info matches
    // the expected result.
    assert(TEST_CASES.nonEmpty, "Test cases should not be empty")

    // Iterate through all test cases and validate the output.
    for (testCase: BuildInfoTestCaseP <- TEST_CASES) {
      val inputBranchName: String = testCase.getInputBranchName
      val expectedOutput: BuildInfoP = testCase.getExpectedBuildInfo
      val buildInfo: BuildInfo =
        BuildInfo.parseClientVersion(inputBranchName)

      assert(buildInfo.getBranchNameOpt == expectedOutput.branchName)
      assert(buildInfo.getBranchNameLabelValue == expectedOutput.getBranchNameLabelValue)
      assert(buildInfo.getCommitTimeLabelValue == expectedOutput.getCommitTimeLabelValue)
      assert(buildInfo.getCommitTimeEpochMillis == expectedOutput.getCommitTimeEpochMillis)
      assert(buildInfo.getCommitHashLabelValue == expectedOutput.getCommitHashLabelValue)
    }
  }
}
