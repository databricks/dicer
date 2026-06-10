package com.databricks.caching.util

/**
 * Minimal OSS implementation of [[SafeBatchFlagHelper]]. In OSS,
 * [[com.databricks.featureflag.BaseDynamicConf.BatchFeatureFlag]] always returns an empty map,
 * so this helper is never invoked with real sub-flag data and unconditionally returns `None`.
 */
object SafeBatchFlagHelper {

  def parseSubFlag[T: Manifest](
      batchFlag: String,
      subFlag: String,
      consumerSite: String,
      rawJson: String): Option[T] = None
}
