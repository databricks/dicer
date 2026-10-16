package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutRuleP
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutRuleTestDataP
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutRuleTestDataP.{
  InvalidRuleTestCaseP,
  ValidRuleTestCaseP
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutRuleSuite extends DatabricksTest {

  private val TEST_DATA: DicerClientFeatureRolloutRuleTestDataP =
    TestUtils.loadTestData[DicerClientFeatureRolloutRuleTestDataP](
      "dicer/client/feature-rollouts/test/data/rule_test_data.textproto"
    )

  namedGridTest("DicerClientFeatureRolloutRule fromProto accepts valid rule protos")(
    TEST_DATA.validRuleTestCases
      .map((testCase: ValidRuleTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: ValidRuleTestCaseP =>
    // Test plan: Verify that fromProto accepts every valid rule proto from the shared textproto.
    // Do this by iterating over each `valid_rule_test_cases` entry, calling fromProto, and
    // asserting the parsed rule's accessors return the same values that were in the input proto.
    val proto: DicerClientFeatureRolloutRuleP = testCase.getRuleProto
    val rule: DicerClientFeatureRolloutRule = DicerClientFeatureRolloutRule.fromProto(proto)
    val expectedForceEnable: Set[TargetName] =
      proto.forceEnableTargetNames.map(TargetName(_: String)).toSet
    val expectedForceDisable: Set[TargetName] =
      proto.forceDisableTargetNames.map(TargetName(_: String)).toSet
    assert(rule.forceEnableTargetNames == expectedForceEnable)
    assert(rule.forceDisableTargetNames == expectedForceDisable)
    assert(rule.targetInstanceEnableFraction == proto.getTargetInstanceEnableFraction)
  }

  namedGridTest("DicerClientFeatureRolloutRule fromProto rejects invalid rule protos")(
    TEST_DATA.invalidRuleTestCases
      .map((testCase: InvalidRuleTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: InvalidRuleTestCaseP =>
    // Test plan: Verify that fromProto rejects every invalid rule proto from the shared textproto.
    // Do this by iterating over each `invalid_rule_test_cases` entry and asserting fromProto
    // throws IllegalArgumentException whose message contains the expected substring.
    assertThrow[IllegalArgumentException](testCase.getExpectedErrorSubstring) {
      DicerClientFeatureRolloutRule.fromProto(testCase.getRuleProto)
    }
  }
}
