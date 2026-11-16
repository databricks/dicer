package com.databricks.dicer.client.featurerollouts

import scala.util.matching.Regex

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutScopeP

/**
 * The region scope to which a [[DicerClientFeatureRolloutRuleOverride]] applies.
 *
 * @param regionUri The URI of the region to which the override applies.
 *
 * @throws IllegalArgumentException if regionUri does not match the expected format for a region as
 *                                  defined in <internal link>.
 */
case class DicerClientFeatureRolloutScope private (regionUri: String) {
  require(
    DicerClientFeatureRolloutScope.REGION_URI_PATTERN.pattern.matcher(regionUri).matches(),
    "regionUri must match format " +
    "'region:{environment}/{cloud_provider}/{regulatory_domain}/{cloud_provider_region}', " +
    s"got: $regionUri. See the 'region' concept in <internal link> for more details."
  )
}

object DicerClientFeatureRolloutScope {

  // No toProto method is provided because we don't need to serialize a
  // DicerClientFeatureRolloutScope instance to a proto message in any scenario: The Dicer client
  // reads feature rollout config from a textproto at startup and never writes it back to proto
  // format.

  // Matches "region:{environment}/{cloud_provider}/{regulatory_domain}/{cloud_provider_region}".
  // Each segment is non-empty and contains no slashes. See <internal link> for the authoritative
  // format. Used with Matcher.matches() which anchors to the full input; do not use with find().
  private val REGION_URI_PATTERN: Regex = "region:[a-z0-9-]+/[a-z0-9-]+/[a-z0-9-]+/[a-z0-9-]+".r

  /** Returns a [[DicerClientFeatureRolloutScope]] from `proto`. */
  @throws[IllegalArgumentException]("if regionUri is not set in proto")
  @throws[IllegalArgumentException]("See DicerClientFeatureRolloutScope's requirements")
  def fromProto(proto: DicerClientFeatureRolloutScopeP): DicerClientFeatureRolloutScope = {
    require(proto.regionUri.isDefined, "regionUri must be set in DicerClientFeatureRolloutScopeP")
    DicerClientFeatureRolloutScope(regionUri = proto.regionUri.get)
  }
}
