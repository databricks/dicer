package com.databricks.dicer.demo.common

import com.databricks.dicer.external.SliceKey

/** Shared constants for the OSS Dicer demo client and server. */
object DemoCommon {

  /** The target name used by the demo service. */
  val TARGET_NAME: String = "demo-cache-app"

  /** Converts an integer key to a [[SliceKey]] using the standard fingerprint builder. */
  def toSliceKey(key: Long): SliceKey =
    SliceKey.newFingerprintBuilder().putLong(key).build()
}
