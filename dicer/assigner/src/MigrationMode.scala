package com.databricks.dicer.assigner

/**
 * Sealed enumeration of the migration stages between the etcd-backed preferred-assigner driver
 * and the consistent-hashing preferred-assigner driver. See `MigrationPreferredAssignerDriver`
 * for the behavior of each stage.
 */
private[dicer] sealed trait MigrationMode {

  /**
   * Wire-format identifier used at the conf boundary. Stable across binary versions; do not
   * rename without a coordinated conf migration.
   */
  def name: String
}

private[dicer] object MigrationMode {

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
   * Consistent-hashing-primary mode: the new (consistent-hashing) driver is the authoritative
   * source of the elected preferred assigner for both writes and reads. The migration driver
   * forwards its pick to the old (etcd-backed) driver, which keeps writing that pick to etcd so the
   * elected identity stays durable for interop and rollback. The knowledge of the preferred
   * assigner between the consistent-hashing driver and etcd is eventually-consistent: the etcd
   * driver writes the forwarded pick on its next write decision, so etcd may briefly differ from
   * the consistent-hashing pick (a rollback during that window could resurrect a stale identity).
   * The migration driver no longer reads etcd back: external watchers see the consistent-hashing
   * pick as soon as it is elected, without waiting for the etcd write. The
   * `EtcdPreferredAssignerDriver` (the old driver)'s own election decisions are never published.
   */
  case object ConsistentHashingPrimaryEtcdWritesMode extends MigrationMode {
    override val name: String = "consistent_hashing_primary_etcd_write"
  }

  /**
   * All defined migration modes. The single source of truth for enumeration; everything else
   * (parser, tests) derives from this list.
   */
  val values: Seq[MigrationMode] =
    Seq(ShadowMode, ConsistentHashingNominatedEtcdReadMode, ConsistentHashingPrimaryEtcdWritesMode)

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
