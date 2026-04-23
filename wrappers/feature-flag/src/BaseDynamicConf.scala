package com.databricks.featureflag

import com.databricks.conf.DbConf
import com.databricks.featureflag.client.utils.RuntimeContext

/**
 * The base dynamic conf trait for dynamic feature flags. In OSS, dynamic configuration is not
 * supported, but static configuration values are respected.
 */
trait BaseDynamicConf extends DbConf {

  /**
   * Dynamic feature flag. In OSS, dynamic configuration is not supported, but static configuration
   * values (via [[DbConf]]) are respected. If no static config value is set, returns the default
   * value.
   */
  class FeatureFlag[T: Manifest](
      val flagName: String,
      defaultValue: T
  ) {

    /** The value of `flagName` in the static configuration. */
    private val staticValue: T = configure(flagName, defaultValue)

    /** Returns the static config value if set, otherwise the default value. */
    def getCurrentValue(): T = staticValue

    /** Returns the static config value if set, otherwise the default value. */
    def getCurrentValue(runtimeContext: RuntimeContext): T = staticValue
  }

  object FeatureFlag {
    def apply[T: Manifest](flagName: String, defaultValue: T): FeatureFlag[T] = {
      new FeatureFlag(flagName, defaultValue)
    }
  }

  /** A batch of feature flags, always returns empty map in OSS. */
  class BatchFeatureFlag(val flagName: String) {
    def getSubFlagValues(runtimeContext: Option[RuntimeContext] = None): Map[String, String] = {
      Map.empty
    }
  }

  object BatchFeatureFlag {
    def apply(flagName: String): BatchFeatureFlag = new BatchFeatureFlag(flagName)
  }
}
