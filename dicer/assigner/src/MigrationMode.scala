package com.databricks.dicer.assigner

/**
 * Sealed enumeration of the migration stages between the etcd-backed preferred-assigner driver
 * and the consistent-hashing preferred-assigner driver. See `MigrationPreferredAssignerDriver`
 * for the behavior of each stage.
 */
private[assigner] sealed trait MigrationMode {

  /**
   * Wire-format identifier used at the conf boundary. Stable across binary versions; do not
   * rename without a coordinated conf migration.
   */
  def name: String
}

private[assigner] object MigrationMode {

  /**
   * Shadow mode: incoming signals are delivered to both drivers, but the old driver's responses
   * are returned to callers.
   */
  case object ShadowMode extends MigrationMode {
    override val name: String = "shadow"
  }

  /**
   * Consistent-hashing-nominates / etcd-reads mode: the new (consistent-hashing) driver
   * elects a preferred-assigner candidate, which the migration driver forwards to the old
   * (etcd-backed) driver via `oldDriver.updateExternalPick`. The old driver remains
   * authoritative for watch reads and heartbeat responses.
   */
  case object ConsistentHashingNominatedEtcdReadMode extends MigrationMode {
    override val name: String = "consistent_hashing_nominated_etcd_read"
  }

  /**
   * All defined migration modes. The single source of truth for enumeration; everything else
   * (parser, tests) derives from this list.
   */
  val values: Seq[MigrationMode] = Seq(ShadowMode, ConsistentHashingNominatedEtcdReadMode)

  /** Index of [[values]] by wire-format [[MigrationMode.name]]. */
  private val byName: Map[String, MigrationMode] = values.map(mode => mode.name -> mode).toMap

  /**
   * Parses a wire-format migration-mode string (the value of [[MigrationMode.name]]) into
   * the corresponding case object.
   */
  @throws[IllegalArgumentException]("if `name` is not a known migration mode")
  def fromName(name: String): MigrationMode = {
    byName.getOrElse(
      name,
      throw new IllegalArgumentException(
        s"Unknown migration mode '$name'. Known modes: ${byName.keys.toSeq.sorted.mkString(", ")}"
      )
    )
  }
}
