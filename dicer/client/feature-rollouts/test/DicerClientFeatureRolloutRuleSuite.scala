package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutRuleP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutRuleSuite extends DatabricksTest {

  test("DicerClientFeatureRolloutRule fromProto with all fields set and valid") {
    // Test plan: Verify that fromProto correctly parses a fully specified rule proto. Do this by
    // constructing a proto with force enable/disable lists and a fraction, then asserting all
    // fields in the result match the proto values, including that the target names are wrapped in
    // TargetName instances.
    val proto: DicerClientFeatureRolloutRuleP = DicerClientFeatureRolloutRuleP(
      forceEnableTargetNames = Seq("target-a", "target-b"),
      forceDisableTargetNames = Seq("target-c"),
      targetInstanceEnableFraction = Some(0.5)
    )
    val rule: DicerClientFeatureRolloutRule = DicerClientFeatureRolloutRule.fromProto(proto)
    assert(rule.forceEnableTargetNames == Set(TargetName("target-a"), TargetName("target-b")))
    assert(rule.forceDisableTargetNames == Set(TargetName("target-c")))
    assert(rule.targetInstanceEnableFraction == 0.5)
  }

  test("DicerClientFeatureRolloutRule fromProto with empty force lists and fraction 0.0") {
    // Test plan: Verify that fromProto correctly handles a rule with empty force lists and a zero
    // fraction, which disables the feature for all targets. Do this by constructing such a proto
    // and asserting the resulting rule has empty sets and fraction 0.0.
    val proto: DicerClientFeatureRolloutRuleP =
      DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(0.0))
    val rule: DicerClientFeatureRolloutRule = DicerClientFeatureRolloutRule.fromProto(proto)
    assert(rule.forceEnableTargetNames.isEmpty)
    assert(rule.forceDisableTargetNames.isEmpty)
    assert(rule.targetInstanceEnableFraction == 0.0)
  }

  test("DicerClientFeatureRolloutRule fromProto with fraction 1.0 does not throw") {
    // Test plan: Verify that fraction 1.0 (the upper boundary of the valid range) is accepted.
    // Do this by calling fromProto with fraction 1.0 and asserting no exception is thrown.
    val proto: DicerClientFeatureRolloutRuleP =
      DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(1.0))
    val rule: DicerClientFeatureRolloutRule = DicerClientFeatureRolloutRule.fromProto(proto)
    assert(rule.targetInstanceEnableFraction == 1.0)
  }

  test("DicerClientFeatureRolloutRule throws when targetInstanceEnableFraction is absent") {
    // Test plan: Verify that fromProto throws IllegalArgumentException when the required
    // targetInstanceEnableFraction field is missing. Do this by calling fromProto with a proto
    // lacking that field and asserting on the exception message.
    assertThrow[IllegalArgumentException]("targetInstanceEnableFraction must be set") {
      DicerClientFeatureRolloutRule.fromProto(DicerClientFeatureRolloutRuleP())
    }
  }

  gridTest(
    "DicerClientFeatureRolloutRule throws when targetInstanceEnableFraction is out of range"
  )(
    Seq(-0.1, -1.0, 1.1, 2.0)
  ) { fraction: Double =>
    // Test plan: Verify that constructing a DicerClientFeatureRolloutRule with a fraction outside
    // [0.0, 1.0] throws IllegalArgumentException. Do this by calling fromProto with each invalid
    // fraction value and asserting on the exception message.
    val proto: DicerClientFeatureRolloutRuleP =
      DicerClientFeatureRolloutRuleP(targetInstanceEnableFraction = Some(fraction))
    assertThrow[IllegalArgumentException]("targetInstanceEnableFraction must be in [0.0, 1.0]") {
      DicerClientFeatureRolloutRule.fromProto(proto)
    }
  }

  test("DicerClientFeatureRolloutRule throws when force lists overlap") {
    // Test plan: Verify that constructing a DicerClientFeatureRolloutRule where
    // forceEnableTargetNames and forceDisableTargetNames share a target name throws
    // IllegalArgumentException. Do this by calling fromProto with overlapping lists and asserting
    // on the exception message.
    val proto: DicerClientFeatureRolloutRuleP = DicerClientFeatureRolloutRuleP(
      forceEnableTargetNames = Seq("target-a", "target-b"),
      forceDisableTargetNames = Seq("target-b", "target-c"),
      targetInstanceEnableFraction = Some(0.5)
    )
    assertThrow[IllegalArgumentException](
      "forceEnableTargetNames and forceDisableTargetNames must not overlap"
    ) {
      DicerClientFeatureRolloutRule.fromProto(proto)
    }
  }

  namedGridTest("DicerClientFeatureRolloutRule throws when force list contains invalid name")(
    Map(
      "in forceEnableTargetNames" -> DicerClientFeatureRolloutRuleP(
        forceEnableTargetNames = Seq("bad_name.bad"),
        targetInstanceEnableFraction = Some(0.5)
      ),
      "in forceDisableTargetNames" -> DicerClientFeatureRolloutRuleP(
        forceDisableTargetNames = Seq("bad_name.bad"),
        targetInstanceEnableFraction = Some(0.5)
      )
    )
  ) { proto: DicerClientFeatureRolloutRuleP =>
    // Test plan: Verify that fromProto throws IllegalArgumentException when either force list
    // contains a string that is not a valid RFC 1123 target name. Do this by supplying an invalid
    // name (containing dots) in forceEnableTargetNames and forceDisableTargetNames respectively,
    // and asserting that TargetName validation rejects it.
    assertThrow[IllegalArgumentException]("Target name must match regex") {
      DicerClientFeatureRolloutRule.fromProto(proto)
    }
  }
}
