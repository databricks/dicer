package com.databricks.dicer.assigner

import java.net.URI

import com.databricks.dicer.assigner.config.{TargetMigrationRole, TargetMigrationType}

/**
 * Resolves this Assigner's [[TargetMigrationRole]] in an active target migration.
 *
 * NOTE: Active target migration are not currently supported, so this resolver is a no-op.
 *
 * @param assignerClusterUri the URI of the cluster this Assigner runs in.
 */
class TargetMigrationRoleResolver(assignerClusterUri: URI) {

  /**
   * Resolves this Assigner's role for an active target migration of `migrationType`.
   *
   * @param migrationType the type of the active target migration to resolve the role for.
   *
   */
  @throws[NotImplementedError]("Role resolution for target migrations is not implemented yet.")
  def resolveTargetMigrationRole(migrationType: TargetMigrationType): TargetMigrationRole =
    throw new NotImplementedError("Role resolution for target migrations is not implemented yet.")
}
