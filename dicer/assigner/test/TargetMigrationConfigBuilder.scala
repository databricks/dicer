package com.databricks.dicer.assigner

import com.databricks.dicer.assigner.config.{TargetMigrationConfig, TargetMigrationType}
import com.databricks.dicer.common.TargetName
import javax.annotation.concurrent.NotThreadSafe

/**
 * Builder for [[TargetMigrationConfig]] for tests. Makes it easier for callers to accumulate
 * `forceToSource` / `forceToDestination` targets.
 */
@NotThreadSafe
final class TargetMigrationConfigBuilder(
    version: Int,
    destinationTargetNameFraction: Double
) {

  /** Accumulated targets for [[TargetMigrationConfig.forceToSourceTargetNames]]. */
  private var forceToSourceNames: Set[TargetName] = Set.empty

  /** Accumulated targets for [[TargetMigrationConfig.forceToDestinationTargetNames]]. */
  private var forceToDestinationNames: Set[TargetName] = Set.empty

  /**
   * Adds `name` to the set of targets that the built [[TargetMigrationConfig]] will force onto the
   * source. Returns this builder for chaining.
   */
  def forceToSource(name: TargetName): TargetMigrationConfigBuilder = {
    forceToSourceNames += name
    this
  }

  /**
   * Adds `name` to the set of targets that the built [[TargetMigrationConfig]] will force onto the
   * destination. Returns this builder for chaining.
   */
  def forceToDestination(name: TargetName): TargetMigrationConfigBuilder = {
    forceToDestinationNames += name
    this
  }

  /**
   * Builds a [[TargetMigrationConfig]] with the accumulated [[forceToSource]] and
   * [[forceToDestination]] targets, using the `version` and `destinationTargetNameFraction`
   * supplied at construction time. The migration type is always
   * [[TargetMigrationType.GeneralToSmk]].
   */
  def build(): TargetMigrationConfig = TargetMigrationConfig(
    version = version,
    migrationType = TargetMigrationType.GeneralToSmk,
    forceToSourceTargetNames = forceToSourceNames,
    forceToDestinationTargetNames = forceToDestinationNames,
    destinationTargetNameFraction = destinationTargetNameFraction
  )
}
