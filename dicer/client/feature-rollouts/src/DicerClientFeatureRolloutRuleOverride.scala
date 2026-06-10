package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutRuleOverrideP

/**
 * An override of a Dicer client feature rollout rule that applies to specific regions.
 *
 * @param overrideScopes The regions to which this override applies.
 * @param overrideRule   The rollout rule applied to the `overrideScopes`, replacing the default
 *                       rule for those regions.
 * @throws IllegalArgumentException If overrideScopes is empty.
 * @throws IllegalArgumentException If overrideScopes contains duplicate region URIs.
 */
case class DicerClientFeatureRolloutRuleOverride private (
    overrideScopes: Vector[DicerClientFeatureRolloutScope],
    overrideRule: DicerClientFeatureRolloutRule) {
  require(overrideScopes.nonEmpty, "overrideScopes must be non-empty")
  requireNoDuplicateRegionUris()

  /** Throws [[IllegalArgumentException]] if [[overrideScopes]] contains duplicate region URIs. */
  private def requireNoDuplicateRegionUris(): Unit = {
    val regionUris: Seq[String] = overrideScopes.map((_: DicerClientFeatureRolloutScope).regionUri)
    require(
      regionUris.distinct.size == regionUris.size,
      s"overrideScopes must not contain duplicate region URIs, got: $regionUris"
    )
  }
}

object DicerClientFeatureRolloutRuleOverride {

  // No toProto method is provided because we don't need to serialize a
  // DicerClientFeatureRolloutRuleOverride instance to a proto message in any scenario: The Dicer
  // client reads feature rollout config from a textproto at startup and never writes it back to
  // proto format.

  /** Returns a [[DicerClientFeatureRolloutRuleOverride]] from `proto`. */
  @throws[IllegalArgumentException]("if overrideRule is not set in proto")
  @throws[IllegalArgumentException]("See DicerClientFeatureRolloutRuleOverride's requirements")
  def fromProto(
      proto: DicerClientFeatureRolloutRuleOverrideP): DicerClientFeatureRolloutRuleOverride = {
    require(
      proto.overrideRule.isDefined,
      "overrideRule must be set in DicerClientFeatureRolloutRuleOverrideP"
    )
    DicerClientFeatureRolloutRuleOverride(
      overrideScopes = proto.overrideScopes.map(DicerClientFeatureRolloutScope.fromProto).toVector,
      overrideRule = DicerClientFeatureRolloutRule.fromProto(proto.overrideRule.get)
    )
  }
}
