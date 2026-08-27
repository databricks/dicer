package com.databricks.dicer.assigner.config

import scala.concurrent.duration.FiniteDuration

import com.databricks.caching.util.{Cancellable, ValueStreamCallback}
import com.databricks.dicer.assigner.conf.DicerAssignerConf

/**
 * A provider that serves static configuration for each sharded target.
 *
 * This provider always returns the same static configuration map that was provided during
 * construction. It does not poll for dynamic updates; `watch` delivers the current value once and
 * never fires again. Dynamic configuration is always disabled for this provider.
 *
 * @param staticTargetConfigMap the static config map constructed on textprotos that will
 *                              be returned by all calls to getLatestTargetConfigMap.
 */
class StaticTargetConfigProvider(staticTargetConfigMap: InternalTargetConfigMap)
    extends TargetConfigProvider {

  /** Returns false: dynamic config is always disabled for the static config provider. */
  override def isDynamicConfigEnabled: Boolean = false

  /**
   * Returns the static configs for all targets. This always returns the same configuration
   * that was provided during construction.
   */
  override def getLatestTargetConfigMap: InternalTargetConfigMap = staticTargetConfigMap

  /**
   * Delivers the current [[InternalTargetConfigMap]] to `callback` once and returns a no-op
   * cancellable. The configuration never changes for the static provider, so there are no further
   * updates.
   */
  override def watch(callback: ValueStreamCallback[InternalTargetConfigMap]): Cancellable = {
    callback.executeOnSuccess(staticTargetConfigMap)
    Cancellable.NO_OP_CANCELLABLE
  }
}

object StaticTargetConfigProvider {

  /**
   * Creates a [[StaticTargetConfigProvider]]. This method does not block, but intentionally matches
   * the signature of the internal code.
   *
   * @param staticTargetConfigMap the static config map constructed on textprotos that will
   *                              be served by this provider.
   * @param assignerConf the assigner configuration (not used but kept for API compatibility).
   * @param initialPollTimeout the initial configuration poll timeout (not used but kept for API
   *                           compatibility).
   */
  def createBlocking(
      staticTargetConfigMap: InternalTargetConfigMap,
      assignerConf: DicerAssignerConf,
      initialPollTimeout: FiniteDuration): StaticTargetConfigProvider = {
    new StaticTargetConfigProvider(staticTargetConfigMap)
  }
}
