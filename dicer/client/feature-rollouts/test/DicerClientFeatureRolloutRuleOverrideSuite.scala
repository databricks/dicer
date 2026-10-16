package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.{
  DicerClientFeatureRolloutRuleOverrideP,
  DicerClientFeatureRolloutScopeP
}
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutRuleOverrideTestDataP
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutRuleOverrideTestDataP.{
  InvalidRuleOverrideTestCaseP,
  ValidRuleOverrideTestCaseP
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutRuleOverrideSuite extends DatabricksTest {

  private val TEST_DATA: DicerClientFeatureRolloutRuleOverrideTestDataP =
    TestUtils.loadTestData[DicerClientFeatureRolloutRuleOverrideTestDataP](
      "dicer/client/feature-rollouts/test/data/rule_override_test_data.textproto"
    )

  namedGridTest(
    "DicerClientFeatureRolloutRuleOverride fromProto accepts valid override protos"
  )(
    TEST_DATA.validRuleOverrideTestCases
      .map((testCase: ValidRuleOverrideTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: ValidRuleOverrideTestCaseP =>
    // Test plan: Verify that fromProto accepts every valid override proto from the shared
    // textproto. Do this by iterating over each `valid_rule_override_test_cases` entry, calling
    // fromProto, and asserting the parsed override's scopes match the input proto's
    // `override_scopes` and its rule equals the rule parsed from the input proto's `override_rule`.
    val proto: DicerClientFeatureRolloutRuleOverrideP = testCase.getOverrideProto
    val ruleOverride: DicerClientFeatureRolloutRuleOverride =
      DicerClientFeatureRolloutRuleOverride.fromProto(proto)
    val actualRegionUris: Seq[String] =
      ruleOverride.overrideScopes.map((scope: DicerClientFeatureRolloutScope) => scope.regionUri)
    val expectedRegionUris: Seq[String] =
      proto.overrideScopes.map((scope: DicerClientFeatureRolloutScopeP) => scope.getRegionUri)
    assert(actualRegionUris == expectedRegionUris)
    assert(
      ruleOverride.overrideRule == DicerClientFeatureRolloutRule.fromProto(proto.getOverrideRule)
    )
  }

  namedGridTest(
    "DicerClientFeatureRolloutRuleOverride fromProto rejects invalid override protos"
  )(
    TEST_DATA.invalidRuleOverrideTestCases
      .map((testCase: InvalidRuleOverrideTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: InvalidRuleOverrideTestCaseP =>
    // Test plan: Verify that fromProto rejects every invalid override proto from the shared
    // textproto. Do this by iterating over each `invalid_rule_override_test_cases` entry and
    // asserting fromProto throws IllegalArgumentException whose message contains the expected
    // substring.
    assertThrow[IllegalArgumentException](testCase.getExpectedErrorSubstring) {
      DicerClientFeatureRolloutRuleOverride.fromProto(testCase.getOverrideProto)
    }
  }
}
