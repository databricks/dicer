package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutRuleP
import com.databricks.dicer.common.TargetName

/**
 * The rollout rule defining which target instances a Dicer client feature is enabled for, and at
 * what fraction.
 *
 * @param forceEnableTargetNames       Force enable list of target names. Targets whose NAMES are in
 *                                     this list will have the feature unconditionally enabled,
 *                                     regardless of `targetInstanceEnableFraction`.
 * @param forceDisableTargetNames      Force disable list of target names. Targets whose NAMES are
 *                                     in this list will have the feature unconditionally disabled,
 *                                     regardless of `targetInstanceEnableFraction`.
 * @param targetInstanceEnableFraction The fraction to enable target instances. For target
 *                                     INSTANCES that are not in either of the force lists, a
 *                                     portion of `targetInstanceEnableFraction` of them will have
 *                                     the feature enabled.
 * @throws IllegalArgumentException    If forceEnableTargetNames and forceDisableTargetNames
 *                                     overlap.
 * @throws IllegalArgumentException    If targetInstanceEnableFraction is not in [0.0, 1.0].
 */
case class DicerClientFeatureRolloutRule private (
    forceEnableTargetNames: Set[TargetName],
    forceDisableTargetNames: Set[TargetName],
    targetInstanceEnableFraction: Double) {
  require(
    targetInstanceEnableFraction >= 0.0 && targetInstanceEnableFraction <= 1.0,
    s"targetInstanceEnableFraction must be in [0.0, 1.0], got: $targetInstanceEnableFraction"
  )
  locally {
    val overlap: Set[TargetName] = forceEnableTargetNames.intersect(forceDisableTargetNames)
    require(
      overlap.isEmpty,
      s"forceEnableTargetNames and forceDisableTargetNames must not overlap, overlap: $overlap"
    )
  }
}

object DicerClientFeatureRolloutRule {

  // No toProto method is provided because we don't need to serialize a
  // DicerClientFeatureRolloutRule instance to a proto message in any scenario: The Dicer client
  // reads feature rollout config from a textproto at startup and never writes it back to proto
  // format.

  /** Returns a [[DicerClientFeatureRolloutRule]] from `proto`. */
  @throws[IllegalArgumentException]("if targetInstanceEnableFraction is not set in proto")
  @throws[IllegalArgumentException]("if targetInstanceEnableFraction is not in [0.0, 1.0]")
  @throws[IllegalArgumentException]("if forceEnableTargetNames and forceDisableTargetNames overlap")
  @throws[IllegalArgumentException](
    "if any target name in either force list is not a valid RFC 1123 name"
  )
  def fromProto(proto: DicerClientFeatureRolloutRuleP): DicerClientFeatureRolloutRule = {
    require(
      proto.targetInstanceEnableFraction.isDefined,
      "targetInstanceEnableFraction must be set in DicerClientFeatureRolloutRuleP"
    )
    DicerClientFeatureRolloutRule(
      forceEnableTargetNames = proto.forceEnableTargetNames.map(TargetName(_: String)).toSet,
      forceDisableTargetNames = proto.forceDisableTargetNames.map(TargetName(_: String)).toSet,
      targetInstanceEnableFraction = proto.targetInstanceEnableFraction.get
    )
  }
}
