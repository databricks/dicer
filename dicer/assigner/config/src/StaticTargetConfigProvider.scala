package com.databricks.dicer.assigner.config

import scala.concurrent.duration.FiniteDuration
import com.databricks.caching.util.{Cancellable, ValueStreamCallback}
import com.databricks.dicer.assigner.conf.DicerAssignerConf

/**
 * A provider that serves static configuration for each sharded target.
 *
 * This provider always returns the same static configuration map that was provided during
 * construction. It does not poll for dynamic updates and the `watch` method is a no-op.
 * Dynamic configuration is always disabled for this provider.
 *
 * @param staticTargetConfigMap the static config map constructed on textprotos that will
 *                              be returned by all calls to getLatestTargetConfigMap.
 */
class StaticTargetConfigProvider(staticTargetConfigMap: InternalTargetConfigMap)
    extends TargetConfigProvider {

  /**
   * Initializes the static target config provider. For static providers, this is essentially
   * a no-op except for marking the provider as started.
   */
  override def startBlocking(initialPollTimeout: FiniteDuration): Unit = {}

  /** Returns false: dynamic config is always disabled for the static config provider. */
  override def isDynamicConfigEnabled: Boolean = false

  /**
   * Returns the static configs for all targets. This always returns the same configuration
   * that was provided during construction.
   */
  override def getLatestTargetConfigMap: InternalTargetConfigMap = staticTargetConfigMap

  /**
   * Returns a no-op cancellable: this is a no-op since the configuration never changes for the
   * static config provider.
   */
  override def watch(callback: ValueStreamCallback[InternalTargetConfigMap]): Cancellable =
    Cancellable.NO_OP_CANCELLABLE
}

object StaticTargetConfigProvider {

  /**
   * Creates a [[StaticTargetConfigProvider]] instance.
   *
   * @param staticTargetConfigMap the static config map constructed on textprotos that will
   *                              be served by this provider.
   * @param assignerConf the assigner configuration (not used but kept for API compatibility).
   */
  def create(
      staticTargetConfigMap: InternalTargetConfigMap,
      assignerConf: DicerAssignerConf): StaticTargetConfigProvider = {
    new StaticTargetConfigProvider(staticTargetConfigMap)
  }
}
