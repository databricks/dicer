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
 * The type of target migration being performed. Each type has a corresponding
 * [[com.databricks.dicer.assigner.TargetMigrationStrategy]] implementation that defines
 * the migration specific behavior (i.e. Assigner role resolution, endpoint discovery).
 */
private[dicer] sealed trait TargetMigrationType

private[dicer] object TargetMigrationType {

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
      case TargetMigrationTypeP.GENERAL_TO_SMK => GeneralToSmk
      case TargetMigrationTypeP.TARGET_MIGRATION_TYPE_P_UNSPECIFIED =>
        throw new IllegalArgumentException("Migration type is unspecified.")
    }
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
 * @throws IllegalArgumentException If destinationTargetNameFraction is not in [0.0, 1.0].
 * @throws IllegalArgumentException If a target is present in both `forceToSourceTargetNames`
 *                                  and `forceToDestinationTargetNames`.
 */
case class TargetMigrationConfig(
    version: Int,
    migrationType: TargetMigrationType,
    forceToSourceTargetNames: Set[TargetName],
    forceToDestinationTargetNames: Set[TargetName],
    destinationTargetNameFraction: Double) {
  require(
    destinationTargetNameFraction >= 0.0,
    s"destinationTargetNameFraction: $destinationTargetNameFraction is less than 0.0."
  )
  require(
    destinationTargetNameFraction <= 1.0,
    s"destinationTargetNameFraction: $destinationTargetNameFraction is greater than 1.0."
  )
  require(
    forceToSourceTargetNames.intersect(forceToDestinationTargetNames).isEmpty,
    s"forceToSourceTargetNames and forceToDestinationTargetNames must be disjoint, but both " +
    s"contain: ${forceToSourceTargetNames.intersect(forceToDestinationTargetNames).mkString(", ")}"
  )
}

object TargetMigrationConfig {

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
}
