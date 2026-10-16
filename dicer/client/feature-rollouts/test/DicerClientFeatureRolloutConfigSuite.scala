package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.{
  DicerClientFeatureRolloutConfigP,
  DicerClientFeatureRolloutRuleOverrideP,
  DicerClientFeatureRolloutScopeP
}
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutConfigTestDataP
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutConfigTestDataP.{
  InvalidConfigTestCaseP,
  ValidConfigTestCaseP
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutConfigSuite extends DatabricksTest {

  private val TEST_DATA: DicerClientFeatureRolloutConfigTestDataP =
    TestUtils.loadTestData[DicerClientFeatureRolloutConfigTestDataP](
      "dicer/client/feature-rollouts/test/data/config_test_data.textproto"
    )

  namedGridTest("DicerClientFeatureRolloutConfig fromProto accepts valid config protos")(
    TEST_DATA.validConfigTestCases
      .map((testCase: ValidConfigTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: ValidConfigTestCaseP =>
    // Test plan: Verify that fromProto accepts every valid config proto from the shared
    // textproto. Do this by iterating over each `valid_config_test_cases` entry, calling
    // fromProto, and asserting the parsed config's override scopes match the input proto's, and
    // its default rule and override rules equal the rules parsed from the input proto.
    val proto: DicerClientFeatureRolloutConfigP = testCase.getConfigProto
    val config: DicerClientFeatureRolloutConfig = DicerClientFeatureRolloutConfig.fromProto(proto)
    assert(config.defaultRuleOpt == proto.defaultRule.map(DicerClientFeatureRolloutRule.fromProto))
    assert(config.overrideRules.size == proto.overrideRules.size)
    for (pair <- config.overrideRules.zip(proto.overrideRules)) {
      val (ruleOverride, overrideProto): (
          DicerClientFeatureRolloutRuleOverride,
          DicerClientFeatureRolloutRuleOverrideP) = pair
      val actualRegionUris: Seq[String] =
        ruleOverride.overrideScopes.map((_: DicerClientFeatureRolloutScope).regionUri)
      val expectedRegionUris: Seq[String] =
        overrideProto.overrideScopes.map((_: DicerClientFeatureRolloutScopeP).getRegionUri)
      assert(actualRegionUris == expectedRegionUris)
      assert(
        ruleOverride.overrideRule ==
        DicerClientFeatureRolloutRule.fromProto(overrideProto.getOverrideRule)
      )
    }
  }

  namedGridTest("DicerClientFeatureRolloutConfig fromProto rejects invalid config protos")(
    TEST_DATA.invalidConfigTestCases
      .map((testCase: InvalidConfigTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: InvalidConfigTestCaseP =>
    // Test plan: Verify that fromProto rejects every invalid config proto from the shared
    // textproto. Do this by iterating over each `invalid_config_test_cases` entry and asserting
    // fromProto throws IllegalArgumentException whose message contains the expected substring.
    assertThrow[IllegalArgumentException](testCase.getExpectedErrorSubstring) {
      DicerClientFeatureRolloutConfig.fromProto(testCase.getConfigProto)
    }
  }
}
