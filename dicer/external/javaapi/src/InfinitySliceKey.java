package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.InfinitySliceKey
 */
public final class InfinitySliceKey extends HighSliceKey {

  /** The singleton instance. */
  public static final InfinitySliceKey INSTANCE = new InfinitySliceKey();

  /**
   * This constructor is private to prevent external instantiation; use the singleton instance
   * {@link #INSTANCE} instead.
   */
  private InfinitySliceKey() {}

  @Override
  public boolean isFinite() {
    return com.databricks.dicer.external.InfinitySliceKey.isFinite();
  }

  @Override
  public SliceKey asFinite() throws ClassCastException {
    com.databricks.dicer.external.SliceKey sliceKey =
        com.databricks.dicer.external.InfinitySliceKey.asFinite();
    return new SliceKey(sliceKey);
  }

  @Override
  public String toString() {
    return com.databricks.dicer.external.InfinitySliceKey.toString();
  }

  @Override
  public int compareTo(HighSliceKey highSliceKey) {
    return com.databricks.dicer.external.InfinitySliceKey.compare(highSliceKey.scalaHighSliceKey());
  }

  @Override
  protected com.databricks.dicer.external.HighSliceKey scalaHighSliceKey() {
    return com.databricks.dicer.external.InfinitySliceKeyInterop.INFINITY_SLICE_KEY();
  }
}
