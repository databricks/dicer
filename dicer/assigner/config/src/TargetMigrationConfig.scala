package com.databricks.dicer.assigner.config

import scala.util.{Failure, Success, Try}

import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP
import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP.TargetMigrationTypeP
import com.databricks.dicer.common.TargetName
import com.databricks.rpc.DatabricksObjectMapper

/**
 * An Assigner's role in the target migration will be either be:
 * - The "SOURCE" where targets are being migrated away from
 * - The "DESTINATION" where targets are being migrated towards
 */
private[dicer] sealed trait TargetMigrationRole

private[dicer] object TargetMigrationRole {

  /** The Assigner where targets are being migrated away from. */
  case object Source extends TargetMigrationRole

  /** The Assigner where targets are being migrated towards. */
  case object Destination extends TargetMigrationRole
}

/**
 * The type of target migration being performed. Except for the no-op migration type, each type
 * will have a corresponding implementation that defines the migration specific behavior like
 * Assigner role resolution (i.e. who's the SOURCE and DESTINATION Assigner) and Assigner
 * endpoint discovery (e.g. how to discover the endpoints of the DESTINATION Assigners for this
 * migration type).
 */
private[dicer] sealed trait TargetMigrationType

private[dicer] object TargetMigrationType {

  /**
   * No-op migration type. An Assigner receiving a config with this type will not be migrating
   * any targets.
   */
  case object NoMigration extends TargetMigrationType

  /**
   * Migrating targets from being handled by the Assigner in the General cluster to the Assigner
   * in the SMK cluster.
   */
  case object GeneralToSmk extends TargetMigrationType

  /**
   * Converts the proto representation of [[TargetMigrationTypeP]] to a [[TargetMigrationType]].
   */
  @throws[IllegalArgumentException](
    "If the proto migration type is unspecified or unrecognized"
  )
  def fromProto(proto: TargetMigrationTypeP): TargetMigrationType = {
    proto match {
      case TargetMigrationTypeP.NO_MIGRATION => NoMigration
      case TargetMigrationTypeP.GENERAL_TO_SMK => GeneralToSmk
      case TargetMigrationTypeP.TARGET_MIGRATION_TYPE_P_UNSPECIFIED =>
        throw new IllegalArgumentException("Migration type is unspecified.")
    }
  }

  /** Converts a [[TargetMigrationType]] to its [[TargetMigrationTypeP]] proto representation. */
  def toProto(migrationType: TargetMigrationType): TargetMigrationTypeP = migrationType match {
    case NoMigration => TargetMigrationTypeP.NO_MIGRATION
    case GeneralToSmk => TargetMigrationTypeP.GENERAL_TO_SMK
  }
}

/**
 * Configuration for target migration - it determines whether a target should be handled by
 * the source Assigner or redirected to the destination Assigner.
 *
 * @param version           The version of the target migration config. Newer versions take
 *                          precedence. NOTE: We will have separate tooling that ensures the
 *                          version number should always be increased any time the configs are
 *                          updated.
 * @param migrationType                      The type of migration being performed. Determines
 *                                            how SOURCE and DESTINATION are resolved.
 * @param forceToSourceTargetNames            Set of [[TargetName]]s that must be handled by the
 *                                            source Assigner. Takes precedence over both
 *                                            `destinationTargetNameFraction` and
 *                                            `forceToDestinationTargetNames`.
 * @param forceToDestinationTargetNames       Set of [[TargetName]]s that must be handled by the
 *                                            destination Assigner. Takes precedence over
 *                                            `destinationTargetNameFraction`.
 * @param destinationTargetNameFraction   The fraction of target names (0.0 - 1.0) that
 *                                            should be handled by the destination Assigner.
 *
 * @throws IllegalArgumentException If `version` is negative.
 * @throws IllegalArgumentException If destinationTargetNameFraction is not in [0.0, 1.0].
 * @throws IllegalArgumentException If a target is present in both `forceToSourceTargetNames`
 *                                  and `forceToDestinationTargetNames`.
 * @throws IllegalArgumentException If `migrationType` is [[TargetMigrationType.NoMigration]]
 *                                  and any of the other fields are set to non-default values.
 *                                  `NoMigration` is a true no-op — the routing fields must be
 *                                  empty / zero in that case.
 */
case class TargetMigrationConfig @throws[IllegalArgumentException]()(
    version: Int,
    migrationType: TargetMigrationType,
    forceToSourceTargetNames: Set[TargetName],
    forceToDestinationTargetNames: Set[TargetName],
    destinationTargetNameFraction: Double) {
  require(version >= 0, s"version must be non-negative, but got: $version")
  // `NoMigration` is a true no-op: the routing fields have no meaning and must not be set.
  if (migrationType == TargetMigrationType.NoMigration) {
    require(
      forceToSourceTargetNames.isEmpty,
      s"forceToSourceTargetNames must be empty when migrationType is NoMigration, but got: " +
      s"${forceToSourceTargetNames.mkString(", ")}"
    )
    require(
      forceToDestinationTargetNames.isEmpty,
      s"forceToDestinationTargetNames must be empty when migrationType is NoMigration, but " +
      s"got: ${forceToDestinationTargetNames.mkString(", ")}"
    )
    require(
      destinationTargetNameFraction == 0.0,
      s"destinationTargetNameFraction must be 0.0 when migrationType is NoMigration, but got: " +
      s"$destinationTargetNameFraction"
    )
  } else {
    require(
      destinationTargetNameFraction >= 0.0,
      s"destinationTargetNameFraction: $destinationTargetNameFraction is less than 0.0."
    )
    require(
      destinationTargetNameFraction <= 1.0,
      s"destinationTargetNameFraction: $destinationTargetNameFraction is greater than 1.0."
    )
    val overlap: Set[TargetName] =
      forceToSourceTargetNames.intersect(forceToDestinationTargetNames)
    require(
      overlap.isEmpty,
      s"forceToSourceTargetNames and forceToDestinationTargetNames must be disjoint, but both " +
      s"contain: ${overlap.mkString(", ")}"
    )
  }
}

object TargetMigrationConfig {

  /**
   * Canonical no-op [[TargetMigrationConfig]]: an Assigner observing this config will not migrate
   * any targets. Use this anywhere an explicit "no migration" config is needed (e.g. tests,
   * placeholder watchers).
   *
   * Guarantees:
   *  - `version` is `0`.
   *  - `migrationType` is [[TargetMigrationType.NoMigration]].
   *  - `forceToSourceTargetNames` and `forceToDestinationTargetNames` are empty.
   *  - `destinationTargetNameFraction` is `0.0`.
   */
  val NO_MIGRATION: TargetMigrationConfig = TargetMigrationConfig(
    version = 0,
    migrationType = TargetMigrationType.NoMigration,
    forceToSourceTargetNames = Set.empty,
    forceToDestinationTargetNames = Set.empty,
    destinationTargetNameFraction = 0.0
  )

  /**
   * Validates and parses the proto representation of [[TargetMigrationConfig]].
   */
  @throws[IllegalArgumentException]("If proto is invalid")
  def fromProto(proto: TargetMigrationConfigP): TargetMigrationConfig = {
    require(proto.version.isDefined, "version is not defined in proto.")
    val migrationType: TargetMigrationType = TargetMigrationType.fromProto(proto.getMigrationType)
    val forceToSourceTargetNames: Set[TargetName] =
      proto.forceToSourceTargetNames.map { name: String =>
        TargetName(name)
      }.toSet
    val forceToDestinationTargetNames: Set[TargetName] =
      proto.forceToDestinationTargetNames.map { name: String =>
        TargetName(name)
      }.toSet
    val destinationTargetNameFraction: Double = proto.getDestinationTargetNameFraction
    TargetMigrationConfig(
      proto.getVersion,
      migrationType,
      forceToSourceTargetNames,
      forceToDestinationTargetNames,
      destinationTargetNameFraction
    )
  }

  /**
   * Factory method that creates a [[TargetMigrationConfig]] from a JSON string, typically coming
   * from a SAFE flag.
   */
  @throws[IllegalArgumentException]("If the JSON is unparseable or contains invalid values.")
  def fromJsonString(jsonString: String): TargetMigrationConfig = {
    // DatabricksObjectMapper requires adding //api/rpc:rpc_parser to the dependency list
    // for proto and JSON conversion.
    val proto: TargetMigrationConfigP = Try[TargetMigrationConfigP](
      DatabricksObjectMapper.fromJson[TargetMigrationConfigP](jsonString)
    ) match {
      case Failure(e) =>
        throw new IllegalArgumentException(
          "Cannot parse JSON into a valid TargetMigrationConfigP.",
          e
        )
      case Success(parsedProto) =>
        parsedProto
    }
    fromProto(proto)
  }

  /**
   * Converts a [[TargetMigrationConfig]] to its [[TargetMigrationConfigP]] proto representation.
   */
  def toProto(config: TargetMigrationConfig): TargetMigrationConfigP = {
    TargetMigrationConfigP(
      version = Some(config.version),
      migrationType = Some(TargetMigrationType.toProto(config.migrationType)),
      // `.sorted` gives a stable on-the-wire order. This is not strictly necessary but allows
      // for e.g. better diffs when writing out the JSON config.
      forceToSourceTargetNames = config.forceToSourceTargetNames
        .map { name: TargetName =>
          name.value
        }
        .toSeq
        .sorted,
      forceToDestinationTargetNames = config.forceToDestinationTargetNames
        .map { name: TargetName =>
          name.value
        }
        .toSeq
        .sorted,
      destinationTargetNameFraction = Some(config.destinationTargetNameFraction)
    )
  }

  /**
   * Converts a [[TargetMigrationConfig]] to its JSON string representation, in the same form that
   * SAFE delivers it via the dynamic target migration config flag.
   */
  def toJsonString(config: TargetMigrationConfig): String =
    DatabricksObjectMapper.toJson(toProto(config))
}
