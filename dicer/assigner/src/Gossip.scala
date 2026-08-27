package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.{GossipRequestP, GossipResponseP, GossipValueP}
import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP
import com.databricks.dicer.assigner.config.TargetMigrationConfig

/**
 * A gossip round initiated by a peer Assigner, acting as the parsed form of [[GossipRequestP]].
 *
 * Note that the gossip protocol's `GossipValueP` is an extensible `oneof`, but the target
 * migration config is the only value gossiped today, so this works directly with
 * [[TargetMigrationConfig]]. If more gossip values are added in the future, this class should be
 * updated to handle the new value types.
 *
 * @param self the identity of the calling Assigner.
 * @param targetMigrationConfigOpt the target migration config the caller is gossiping, or `None`
 *                                 if the caller has none to gossip.
 */
case class GossipRequest(
    self: AssignerInfo,
    targetMigrationConfigOpt: Option[TargetMigrationConfig]) {

  /**
   * Converts this [[GossipRequest]] to a [[GossipRequestP]] containing a singular
   * [[GossipValueP]], or an empty sequence if there is no config to gossip.
   */
  def toProto: GossipRequestP =
    GossipRequestP(
      self = Some(self.toProto),
      values = GossipValues.toGossipValueProtos(targetMigrationConfigOpt)
    )
}

object GossipRequest {

  /**
   * Parses a [[GossipRequestP]] into a [[GossipRequest]], taking the gossiped target migration
   * config (if any).
   */
  @throws[IllegalArgumentException](
    "if the request does not identify the calling Assigner or gossips more than one value"
  )
  def fromProto(proto: GossipRequestP): GossipRequest = {
    val assignerInfo: AssignerInfo = AssignerInfo.fromProto(proto.getSelf)
    val targetMigrationConfigOpt: Option[TargetMigrationConfig] =
      GossipValues.toTargetMigrationConfigOpt(proto.values)
    GossipRequest(assignerInfo, targetMigrationConfigOpt)
  }
}

/**
 * The responder's reply to a gossip round, acting as the parsed form of [[GossipResponseP]].
 *
 * See [[GossipRequest]] for why this works directly with [[TargetMigrationConfig]].
 *
 * @param self the identity of the responding Assigner.
 * @param targetMigrationConfigOpt the target migration config the responder is gossiping back, or
 *                                 `None` if the caller is already up-to-date.
 */
case class GossipResponse(
    self: AssignerInfo,
    targetMigrationConfigOpt: Option[TargetMigrationConfig]) {

  /**
   * Converts this [[GossipResponse]] to a [[GossipResponseP]] containing a singular
   * [[GossipValueP]], or an empty sequence if there is no config to gossip.
   */
  def toProto: GossipResponseP =
    GossipResponseP(
      self = Some(self.toProto),
      values = GossipValues.toGossipValueProtos(targetMigrationConfigOpt)
    )
}

object GossipResponse {

  /**
   * Parses a [[GossipResponseP]] into a [[GossipResponse]], taking the gossiped target migration
   * config (if any).
   */
  @throws[IllegalArgumentException](
    "if the response does not identify the responding Assigner or gossips more than one value"
  )
  def fromProto(proto: GossipResponseP): GossipResponse = {
    val assignerInfo: AssignerInfo = AssignerInfo.fromProto(proto.getSelf)
    val targetMigrationConfigOpt: Option[TargetMigrationConfig] =
      GossipValues.toTargetMigrationConfigOpt(proto.values)
    GossipResponse(assignerInfo, targetMigrationConfigOpt)
  }
}

/**
 * Helpers for converting between gossiped sequences of [[GossipValueP]] and the optional
 * [[TargetMigrationConfig]] payload they carry.
 */
private object GossipValues {

  /**
   * Wraps `targetMigrationConfigOpt` into a sequence of gossiped [[GossipValueP]]s: a single value
   * carrying the config, or an empty sequence if there is no config to gossip.
   */
  def toGossipValueProtos(
      targetMigrationConfigOpt: Option[TargetMigrationConfig]): Seq[GossipValueP] =
    targetMigrationConfigOpt.map { config: TargetMigrationConfig =>
      GossipValueP(
        GossipValueP.Value.TargetMigrationConfig(TargetMigrationConfig.toProto(config))
      )
    }.toSeq

  /**
   * Parses the target migration config gossiped in `values` (if any), ignoring a value that
   * carries no recognized config. A gossip message carries at most one value today.
   */
  @throws[IllegalArgumentException]("if values holds more than one value")
  def toTargetMigrationConfigOpt(values: Seq[GossipValueP]): Option[TargetMigrationConfig] = {
    require(values.size <= 1, s"A gossip message must carry at most one value, got ${values.size}")
    values.headOption.flatMap { value: GossipValueP =>
      value.value match {
        case GossipValueP.Value.TargetMigrationConfig(configP: TargetMigrationConfigP) =>
          Some(TargetMigrationConfig.fromProto(configP))
        case GossipValueP.Value.Empty =>
          None
      }
    }
  }
}
