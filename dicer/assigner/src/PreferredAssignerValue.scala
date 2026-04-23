package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.PreferredAssignerSpecP.{NoneP, Value}
import com.databricks.api.proto.dicer.assigner.{
  AssignerInfoP,
  HeartbeatRequestP,
  HeartbeatResponseP,
  PreferredAssignerSpecP,
  PreferredAssignerValueP
}
import com.databricks.caching.util.WatchValueCell
import com.databricks.dicer.common.{Generation, Redirect}

/**
 * The preferred assigner value, which can be [[SomeAssigner]], [[NoAssigner]], or [[Disabled]].
 *
 * Note that it is possible for no assigner to be preferred, for example when the previous preferred
 * assigner "abdicated" after receiving a termination signal, or for the preferred assigner
 * mechanism to be disabled entirely. Even in those cases, we still require the generation to
 * compare preferred assigner values for freshness and to do OCC checks against the store.
 *
 * For more information on the preferred assigner design, see <internal link>.
 */
sealed trait PreferredAssignerValue {
  def generation: Generation

  def toProto: PreferredAssignerValueP

  /**
   * Returns the PreferredAssignerSpecP for persistence in storage. The spec proto does not include
   * the generation because the generation is stored separately in the store.
   */
  def toSpecProto: PreferredAssignerSpecP = toProto.getPreferredAssigner
}
object PreferredAssignerValue {

  type PreferredAssignerWatchCell = WatchValueCell[PreferredAssignerValue]
  type PreferredAssignerWatchCellConsumer = WatchValueCell.Consumer[PreferredAssignerValue]

  /** Indicates the specified assigner is the preferred assigner. */
  case class SomeAssigner(assignerInfo: AssignerInfo, generation: Generation)
      extends PreferredAssignerValue {
    override def toProto: PreferredAssignerValueP = {
      val spec = PreferredAssignerSpecP(Value.AssignerInfo(assignerInfo.toProto))
      PreferredAssignerValueP(Some(spec), Some(generation.toProto))
    }
  }

  /** Indicates *no* assigner is preferred, for example after an assigner abdicates. */
  case class NoAssigner(generation: Generation) extends PreferredAssignerValue {
    override def toProto: PreferredAssignerValueP = {
      val spec = PreferredAssignerSpecP(Value.NoPreferredAssigner(NoneP()))
      PreferredAssignerValueP(Some(spec), Some(generation.toProto))
    }
  }

  /** Indicates that another assigner with preferred assigner mode disabled has taken over. */
  case class ModeDisabled(generation: Generation) extends PreferredAssignerValue {
    override def toProto: PreferredAssignerValueP = {
      // An empty oneof value is interpreted to mean "disabled".
      val spec = PreferredAssignerSpecP(Value.Empty)
      PreferredAssignerValueP(Some(spec), Some(generation.toProto))
    }
  }

  /**
   * Creates a [[PreferredAssignerValue]] instance from the given [[PreferredAssignerValueP]].
   *
   * @throws IllegalArgumentException if one of the following conditions is encountered:
   *                                  - The proto is empty.
   *                                  - The proto doesn't have all the fields set.
   *                                  - The URI in the proto is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: PreferredAssignerValueP): PreferredAssignerValue = {
    val generation = Generation.fromProto(proto.getGeneration)
    fromProto(proto.getPreferredAssigner, generation)
  }

  /**
   * Creates a [[PreferredAssignerValue]] instance from the given spec proto and generation.
   *
   * @throws IllegalArgumentException if one of the following conditions is encountered:
   *                                  - The proto is empty.
   *                                  - The proto doesn't have all the fields set.
   *                                  - The URI in the proto is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(spec: PreferredAssignerSpecP, generation: Generation): PreferredAssignerValue = {
    spec.value match {
      case Value.Empty =>
        PreferredAssignerValue.ModeDisabled(generation)

      case Value.NoPreferredAssigner(NoneP(_)) =>
        PreferredAssignerValue.NoAssigner(generation)

      case Value.AssignerInfo(infoP: AssignerInfoP) =>
        PreferredAssignerValue.SomeAssigner(AssignerInfo.fromProto(infoP), generation)
    }
  }
}

/**
 * REQUIRES: `opId` must be greater than 0.
 *
 * The heartbeat request sent by an assigner identifying itself as the standby, to another assigner
 * it recognizes as the preferred one.
 */
case class HeartbeatRequest(
    opId: Long,
    preferredAssignerValue: PreferredAssignerValue.SomeAssigner) {
  require(opId > 0, "The opId must be greater than 0.")

  def toProto: HeartbeatRequestP = {
    HeartbeatRequestP.of(
      opId = Some(opId),
      preferredAssignerValue = Some(preferredAssignerValue.toProto)
    )
  }
}
object HeartbeatRequest {

  /**
   * REQUIRES: The preferred assigner value in the proto must be `SomeAssigner`.
   *
   * Creates a [[HeartbeatRequest]] instance from the given [[HeartbeatRequestP]].
   */
  def fromProto(requestP: HeartbeatRequestP): HeartbeatRequest = {
    val preferredAssignerValue: PreferredAssignerValue.SomeAssigner =
      PreferredAssignerValue.fromProto(requestP.getPreferredAssignerValue) match {
        case someAssigner: PreferredAssignerValue.SomeAssigner =>
          someAssigner
        case _ =>
          throw new IllegalArgumentException(
            "The preferred assigner value in the proto must be `SomeAssigner`."
          )
      }

    HeartbeatRequest(requestP.getOpId, preferredAssignerValue)
  }
}

/**
 * REQUIRES: `opId` must be greater than 0.
 *
 * The response to `HeartbeatRequest`.
 */
case class HeartbeatResponse(opId: Long, preferredAssignerValue: PreferredAssignerValue) {
  require(opId > 0, "The opId must be greater than 0.")

  def toProto: HeartbeatResponseP = {
    HeartbeatResponseP
      .of(opId = Some(opId), preferredAssignerValue = Some(preferredAssignerValue.toProto))
  }
}
object HeartbeatResponse {
  def fromProto(responseP: HeartbeatResponseP): HeartbeatResponse = {
    val preferredAssignerValue =
      PreferredAssignerValue.fromProto(responseP.getPreferredAssignerValue)
    HeartbeatResponse(responseP.getOpId, preferredAssignerValue)
  }
}

/** The role of an assigner in the preferred assigner mechanism. */
sealed trait AssignerRole

object AssignerRole {

  /** A preferred assigner, which serves watch requests and generates assignments. */
  case object Preferred extends AssignerRole

  /**
   * A standby assigner, which redirects watch request to the preferred assigner if there is one,
   * or a random assigner if there is no preferred assigner, and does not generate assignments.
   */
  case object Standby extends AssignerRole
}

/**
 * Encapsulates whether the current assigner is preferred and the routing hint for clients to
 * use.
 *
 * When the assigner is preferred, it always generates assignments. When the assigner is not
 * preferred, it redirects the client to the preferred assigner if there is one, or a random
 * assigner if there is no preferred assigner.
 */
// TODO(<internal bug>): Consider renaming this to reduce confusion with other Config types which
// typically encapsulate bundles of human-configurable parameters supplied at service creation time.
case class PreferredAssignerConfig private (
    role: AssignerRole,
    redirect: Redirect,
    knownPreferredAssigner: PreferredAssignerValue) {}

object PreferredAssignerConfig {

  def create(
      preferredAssignerValue: PreferredAssignerValue,
      currentAssignerInfo: AssignerInfo): PreferredAssignerConfig = {

    // If the preferred assigner mode is enabled, the current assigner is preferred only when it
    // is the same as the preferred assigner (same `AssignerInfo`), and it always redirects the
    // clients to the preferred assigner.
    preferredAssignerValue match {
      case PreferredAssignerValue.SomeAssigner(assignerInfo: AssignerInfo, _) =>
        val role: AssignerRole =
          if (assignerInfo == currentAssignerInfo) AssignerRole.Preferred
          else AssignerRole.Standby
        PreferredAssignerConfig(
          role,
          Redirect(Some(assignerInfo.uri)),
          preferredAssignerValue
        )
      case PreferredAssignerValue.NoAssigner(_) =>
        PreferredAssignerConfig(
          AssignerRole.Standby,
          Redirect.EMPTY,
          preferredAssignerValue
        )
      case PreferredAssignerValue.ModeDisabled(_) =>
        // When PA is disabled, we always act as the preferred and redirect clients to a random
        // Assigner via Redirect.EMPTY.
        PreferredAssignerConfig(
          AssignerRole.Preferred,
          Redirect.EMPTY,
          preferredAssignerValue
        )
    }
  }
}
