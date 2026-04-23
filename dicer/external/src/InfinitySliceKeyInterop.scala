package com.databricks.dicer.external

/**
 * Java interop helper that exposes the sentinel high slice key.
 * This avoids referencing InfinitySliceKey via InfinitySliceKey$.MODULE$ in Java.
 */
object InfinitySliceKeyInterop {

  /** Provides Java friendly accessor to [[InfinitySliceKey]] as a [[HighSliceKey]]. */
  val INFINITY_SLICE_KEY: HighSliceKey = InfinitySliceKey
}
