package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutConfigP

/**
 * The configuration controlling the rollout of a Dicer client feature across all regions and all
 * target names.
 *
 * @param defaultRuleOpt The default rollout rule for regions not covered by any entry in
 *                       `overrideRules`. When absent, the feature is disabled for all uncovered
 *                       regions.
 * @param overrideRules  Region-specific rule overrides. Each region URI may appear at most once
 *                       across all `overrideScopes` in all entries.
 * @throws IllegalArgumentException If any region URI appears more than once across all
 *                                  overrideScopes in all overrideRules entries.
 */
case class DicerClientFeatureRolloutConfig(
    defaultRuleOpt: Option[DicerClientFeatureRolloutRule],
    overrideRules: Vector[DicerClientFeatureRolloutRuleOverride]) {
  // Validates that no region URI appears in more than one override entry's scopes. Duplicates
  // within a single entry are caught by DicerClientFeatureRolloutRuleOverride's constructor.
  val allRegionUris: Seq[String] =
    overrideRules.flatMap(
      (_: DicerClientFeatureRolloutRuleOverride).overrideScopes
        .map((_: DicerClientFeatureRolloutScope).regionUri)
    )
  require(
    allRegionUris.distinct.size == allRegionUris.size,
    "Each region URI may appear at most once across all overrideScopes in all overrideRules"
  )
}

object DicerClientFeatureRolloutConfig {

  /** Returns a [[DicerClientFeatureRolloutConfig]] from `proto`. */
  @throws[IllegalArgumentException]("if any nested proto field fails validation")
  def fromProto(proto: DicerClientFeatureRolloutConfigP): DicerClientFeatureRolloutConfig = {
    DicerClientFeatureRolloutConfig(
      defaultRuleOpt = proto.defaultRule.map(DicerClientFeatureRolloutRule.fromProto),
      overrideRules =
        proto.overrideRules.map(DicerClientFeatureRolloutRuleOverride.fromProto).toVector
    )
  }
}
