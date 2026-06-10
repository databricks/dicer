package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.{
  DicerClientFeatureRolloutConfigP,
  DicerClientFeatureRolloutRuleOverrideP,
  DicerClientFeatureRolloutRuleP,
  DicerClientFeatureRolloutScopeP
}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutConfigSuite extends DatabricksTest {

  test("DicerClientFeatureRolloutConfig fromProto with no default rule and no overrides") {
    // Test plan: Verify that fromProto parses a minimal config (no default rule, no overrides)
    // correctly. Do this by calling fromProto with an empty proto and asserting both fields are
    // empty or absent.
    val config: DicerClientFeatureRolloutConfig =
      DicerClientFeatureRolloutConfig.fromProto(DicerClientFeatureRolloutConfigP())
    assert(config.defaultRuleOpt.isEmpty)
    assert(config.overrideRules.isEmpty)
  }

  test("DicerClientFeatureRolloutConfig fromProto with default rule only") {
    // Test plan: Verify that fromProto correctly parses a config that has only a default rule and
    // no overrides. Do this by constructing a proto with a default rule and asserting the parsed
    // config has the expected default rule and empty override list.
    val proto: DicerClientFeatureRolloutConfigP = DicerClientFeatureRolloutConfigP(
      defaultRule = Some(
        DicerClientFeatureRolloutRuleP(
          forceEnableTargetNames = Seq("target-a"),
          targetInstanceEnableFraction = Some(0.2)
        )
      )
    )
    val config: DicerClientFeatureRolloutConfig = DicerClientFeatureRolloutConfig.fromProto(proto)
    assert(config.defaultRuleOpt.isDefined)
    assert(config.defaultRuleOpt.get.forceEnableTargetNames == Set(TargetName("target-a")))
    assert(config.defaultRuleOpt.get.targetInstanceEnableFraction == 0.2)
    assert(config.overrideRules.isEmpty)
  }

  test("DicerClientFeatureRolloutConfig fromProto with default rule and overrides") {
    // Test plan: Verify that fromProto correctly parses a config with both a default rule and
    // region-specific overrides. Do this by constructing a proto matching the example-feature
    // textproto structure and asserting the parsed config matches the expected values.
    val proto: DicerClientFeatureRolloutConfigP = DicerClientFeatureRolloutConfigP(
      defaultRule = Some(
        DicerClientFeatureRolloutRuleP(
          forceEnableTargetNames = Seq("instance-manager"),
          forceDisableTargetNames = Seq("sql-warehouse"),
          targetInstanceEnableFraction = Some(0.2)
        )
      ),
      overrideRules = Seq(
        DicerClientFeatureRolloutRuleOverrideP(
          overrideScopes = Seq(
            DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region1")),
            DicerClientFeatureRolloutScopeP(
              regionUri = Some("region:prod/cloud2/public/region6")
            )
          ),
          overrideRule = Some(
            DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(1.0))
          )
        ),
        DicerClientFeatureRolloutRuleOverrideP(
          overrideScopes = Seq(
            DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region2"))
          ),
          overrideRule = Some(
            DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(0.5))
          )
        )
      )
    )
    val config: DicerClientFeatureRolloutConfig = DicerClientFeatureRolloutConfig.fromProto(proto)
    assert(config.defaultRuleOpt.isDefined)
    assert(
      config.defaultRuleOpt.get.forceEnableTargetNames == Set(TargetName("instance-manager"))
    )
    assert(
      config.defaultRuleOpt.get.forceDisableTargetNames == Set(TargetName("sql-warehouse"))
    )
    assert(config.defaultRuleOpt.get.targetInstanceEnableFraction == 0.2)
    assert(config.overrideRules.size == 2)
    val firstOverride: DicerClientFeatureRolloutRuleOverride = config.overrideRules(0)
    assert(firstOverride.overrideScopes.size == 2)
    assert(firstOverride.overrideRule.targetInstanceEnableFraction == 1.0)
    val secondOverride: DicerClientFeatureRolloutRuleOverride = config.overrideRules(1)
    assert(secondOverride.overrideScopes(0).regionUri == "region:prod/cloud1/public/region2")
    assert(secondOverride.overrideRule.targetInstanceEnableFraction == 0.5)
  }

  test(
    "DicerClientFeatureRolloutConfig fromProto throws when region URI appears in multiple overrides"
  ) {
    // Test plan: Verify that fromProto throws IllegalArgumentException when the same region URI
    // appears in more than one override entry. Do this by constructing a proto where one region
    // URI is listed in two separate override_rules entries and asserting on the exception message.
    val duplicateRegionUri: String = "region:prod/cloud1/public/region2"
    val proto: DicerClientFeatureRolloutConfigP = DicerClientFeatureRolloutConfigP(
      overrideRules = Seq(
        DicerClientFeatureRolloutRuleOverrideP(
          overrideScopes = Seq(
            DicerClientFeatureRolloutScopeP(regionUri = Some(duplicateRegionUri))
          ),
          overrideRule = Some(
            DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(1.0))
          )
        ),
        DicerClientFeatureRolloutRuleOverrideP(
          overrideScopes = Seq(
            DicerClientFeatureRolloutScopeP(regionUri = Some(duplicateRegionUri))
          ),
          overrideRule = Some(
            DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(0.5))
          )
        )
      )
    )
    assertThrow[IllegalArgumentException]("Each region URI may appear at most once") {
      DicerClientFeatureRolloutConfig.fromProto(proto)
    }
  }

  test("DicerClientFeatureRolloutConfig throws when region URI appears twice in same override") {
    // Test plan: Verify that fromProto throws IllegalArgumentException when the same region URI
    // appears twice within a single override entry's overrideScopes. This is caught by
    // DicerClientFeatureRolloutRuleOverride's own validation. Do this by constructing a proto
    // where one region URI is listed twice in the same overrideScopes and asserting on the
    // exception message.
    val duplicateRegionUri: String = "region:prod/cloud1/public/region2"
    val proto: DicerClientFeatureRolloutConfigP = DicerClientFeatureRolloutConfigP(
      overrideRules = Seq(
        DicerClientFeatureRolloutRuleOverrideP(
          overrideScopes = Seq(
            DicerClientFeatureRolloutScopeP(regionUri = Some(duplicateRegionUri)),
            DicerClientFeatureRolloutScopeP(regionUri = Some(duplicateRegionUri))
          ),
          overrideRule = Some(
            DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(1.0))
          )
        )
      )
    )
    assertThrow[IllegalArgumentException]("overrideScopes must not contain duplicate region URIs") {
      DicerClientFeatureRolloutConfig.fromProto(proto)
    }
  }
}
