package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.{
  DicerClientFeatureRolloutRuleOverrideP,
  DicerClientFeatureRolloutRuleP,
  DicerClientFeatureRolloutScopeP
}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutRuleOverrideSuite extends DatabricksTest {

  test("DicerClientFeatureRolloutRuleOverride fromProto with valid proto") {
    // Test plan: Verify that fromProto correctly parses an override proto with scopes and a rule.
    // Do this by constructing a proto with two scopes and a rule, then asserting the resulting
    // override matches the proto values.
    val proto: DicerClientFeatureRolloutRuleOverrideP = DicerClientFeatureRolloutRuleOverrideP(
      overrideScopes = Seq(
        DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region1")),
        DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud2/public/region10"))
      ),
      overrideRule = Some(
        DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(1.0))
      )
    )
    val ruleOverride: DicerClientFeatureRolloutRuleOverride =
      DicerClientFeatureRolloutRuleOverride.fromProto(proto)
    assert(ruleOverride.overrideScopes.size == 2)
    assert(ruleOverride.overrideScopes(0).regionUri == "region:prod/cloud1/public/region1")
    assert(ruleOverride.overrideScopes(1).regionUri == "region:prod/cloud2/public/region10")
    assert(ruleOverride.overrideRule.targetInstanceEnableFraction == 1.0)
  }

  test("DicerClientFeatureRolloutRuleOverride throws when overrideScopes is empty") {
    // Test plan: Verify that constructing a DicerClientFeatureRolloutRuleOverride with an empty
    // overrideScopes list throws IllegalArgumentException. Do this by calling fromProto with no
    // scopes and asserting on the exception message.
    val proto: DicerClientFeatureRolloutRuleOverrideP = DicerClientFeatureRolloutRuleOverrideP(
      overrideScopes = Seq.empty,
      overrideRule = Some(
        DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(0.5))
      )
    )
    assertThrow[IllegalArgumentException]("overrideScopes must be non-empty") {
      DicerClientFeatureRolloutRuleOverride.fromProto(proto)
    }
  }

  test("DicerClientFeatureRolloutRuleOverride throws when overrideScopes contains duplicate URIs") {
    // Test plan: Verify that constructing a DicerClientFeatureRolloutRuleOverride with duplicate
    // region URIs in overrideScopes throws IllegalArgumentException. Do this by calling fromProto
    // with two scopes sharing the same region URI and asserting on the exception message.
    val proto: DicerClientFeatureRolloutRuleOverrideP = DicerClientFeatureRolloutRuleOverrideP(
      overrideScopes = Seq(
        DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region2")),
        DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region2"))
      ),
      overrideRule = Some(
        DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(0.5))
      )
    )
    assertThrow[IllegalArgumentException]("overrideScopes must not contain duplicate region URIs") {
      DicerClientFeatureRolloutRuleOverride.fromProto(proto)
    }
  }

  test("DicerClientFeatureRolloutRuleOverride throws when overrideRule is absent") {
    // Test plan: Verify that fromProto throws IllegalArgumentException when overrideRule is not
    // set. Do this by calling fromProto with a proto that has scopes but no rule and asserting
    // on the exception message.
    val proto: DicerClientFeatureRolloutRuleOverrideP = DicerClientFeatureRolloutRuleOverrideP(
      overrideScopes = Seq(
        DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region2"))
      )
    )
    assertThrow[IllegalArgumentException]("overrideRule must be set") {
      DicerClientFeatureRolloutRuleOverride.fromProto(proto)
    }
  }
}
