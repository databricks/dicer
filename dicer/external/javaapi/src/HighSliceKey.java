// LINT.IfChange
package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.HighSliceKey
 */
public abstract sealed class HighSliceKey implements Comparable<HighSliceKey>
    permits InfinitySliceKey, SliceKey {

  /**
   * @see com.databricks.dicer.external.HighSliceKey#isFinite()
   */
  public abstract boolean isFinite();

  /**
   * @see com.databricks.dicer.external.HighSliceKey#asFinite()
   */
  public abstract SliceKey asFinite() throws ClassCastException;

  /** Internal bridge to the underlying Scala representation. */
  protected abstract com.databricks.dicer.external.HighSliceKey scalaHighSliceKey();
}
// LINT.ThenChange(dicer/external/src/SliceKey.scala)
// LINT.ThenChange(dicer/rust/external/types/src/slice_key.rs)
