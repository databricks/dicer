package com.databricks.dicer.assigner.config

import com.databricks.caching.util.{Cancellable, ValueStreamCallback}

import scala.concurrent.duration._

/**
 * A trait for a provider of per-target configs. The interface allows watching for dynamic config
 * updates, but the implementation can also be static, where config stays the same during the
 * lifetime of the provider.
 */
trait TargetConfigProvider {

  /** Returns whether dynamic config is enabled. */
  def isDynamicConfigEnabled: Boolean

  /** Returns the latest configs for all targets. */
  def getLatestTargetConfigMap: InternalTargetConfigMap

  /**
   * Watches the config changes, and invoke the `callback` when the config gets updated.
   *
   * For static config providers, this delivers the config once and returns a no-op cancellable.
   */
  def watch(callback: ValueStreamCallback[InternalTargetConfigMap]): Cancellable
}

object TargetConfigProvider {

  /** A short, default timeout for the initial dynamic config value poll at startup. */
  val DEFAULT_INITIAL_POLL_TIMEOUT: FiniteDuration = 5.seconds
}
