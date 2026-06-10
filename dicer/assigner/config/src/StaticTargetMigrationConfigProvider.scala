package com.databricks.dicer.assigner.config

import scala.concurrent.duration.FiniteDuration
import com.databricks.caching.util.{Cancellable, ValueStreamCallback}
import com.databricks.dicer.assigner.conf.DicerAssignerConf

/**
 * A provider that serves a single, static [[TargetMigrationConfig]].
 *
 * This provider always returns the same static [[TargetMigrationConfig]] that was provided during
 * construction. It does not poll for dynamic updates; `watch` delivers the current value once and
 * never fires again. Dynamic configuration is always disabled for this provider.
 *
 * @param targetMigrationConfig the static config returned by all calls to
 *                              `getLatestTargetMigrationConfig`.
 */
class StaticTargetMigrationConfigProvider(targetMigrationConfig: TargetMigrationConfig) {

  /** Returns the static config provided during construction. */
  def getLatestTargetMigrationConfig: TargetMigrationConfig = targetMigrationConfig

  /**
   * Delivers the current [[TargetMigrationConfig]] to `callback` once and returns a no-op
   * cancellable. The config never changes for the static provider, so there are no further updates.
   */
  def watch(callback: ValueStreamCallback[TargetMigrationConfig]): Cancellable = {
    callback.executeOnSuccess(targetMigrationConfig)
    Cancellable.NO_OP_CANCELLABLE
  }
}

object StaticTargetMigrationConfigProvider {

  /** A default timeout for the initial poll, kept for code compatibility but unused. */
  val DEFAULT_INITIAL_POLL_TIMEOUT: FiniteDuration = FiniteDuration(10, "seconds")

  /**
   * Creates a [[StaticTargetMigrationConfigProvider]] seeded with
   * [[TargetMigrationConfig.NO_MIGRATION]].
   *
   * Currently does not support the target migration, so the [[TargetMigrationConfig]] is fixed to
   * [[TargetMigrationConfig.NO_MIGRATION]].
   *
   * @param assignerConf the assigner configuration (not used but kept for API compatibility).
   * @param initialPollTimeout the initial poll timeout (not used but kept for API compatibility).
   */
  def create(
      assignerConf: DicerAssignerConf,
      initialPollTimeout: FiniteDuration): StaticTargetMigrationConfigProvider = {
    new StaticTargetMigrationConfigProvider(TargetMigrationConfig.NO_MIGRATION)
  }
}
