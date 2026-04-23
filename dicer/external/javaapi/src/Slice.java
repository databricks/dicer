package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.Slice
 */
public final class Slice implements Comparable<Slice> {

  /** The inclusive lower bound for the Slice. */
  private final SliceKey lowInclusive;

  /** The exclusive upper bound for the Slice. */
  private final HighSliceKey highExclusive;

  /** The underlying Scala Slice instance. */
  private final com.databricks.dicer.external.Slice scalaSlice;

  /** This constructor is private to prevent external instantiation; use {@link #create} instead. */
  private Slice(SliceKey lowInclusive, HighSliceKey highExclusive) {
    this.lowInclusive = lowInclusive;
    this.highExclusive = highExclusive;
    com.databricks.dicer.external.SliceKey scalaLowInclusive = lowInclusive.toScala();
    this.scalaSlice =
        com.databricks.dicer.external.Slice.apply(
            scalaLowInclusive, highExclusive.scalaHighSliceKey());
  }

  /**
   * @see com.databricks.dicer.external.Slice#FULL()
   */
  public static final Slice FULL = new Slice(SliceKey.MIN, InfinitySliceKey.INSTANCE);

  /**
   * @see com.databricks.dicer.external.Slice#apply( com.databricks.dicer.external.SliceKey,
   *     com.databricks.dicer.external.HighSliceKey)
   */
  public static Slice create(SliceKey lowInclusive, HighSliceKey highExclusive)
      throws IllegalArgumentException {
    return new Slice(lowInclusive, highExclusive);
  }

  /** Returns the inclusive lower bound of this Slice. */
  public SliceKey lowInclusive() {
    return this.lowInclusive;
  }

  /** Returns the exclusive upper bound of this Slice. */
  public HighSliceKey highExclusive() {
    return this.highExclusive;
  }

  /**
   * @see com.databricks.dicer.external.Slice#atLeast(com.databricks.dicer.external.SliceKey)
   */
  public static Slice atLeast(SliceKey lowInclusive) {
    return new Slice(lowInclusive, InfinitySliceKey.INSTANCE);
  }

  /**
   * @see com.databricks.dicer.external.Slice#contains(com.databricks.dicer.external.SliceKey)
   */
  public boolean contains(SliceKey key) {
    return this.scalaSlice.contains(key.toScala());
  }

  /**
   * @see com.databricks.dicer.external.Slice#contains(com.databricks.dicer.external.Slice)
   */
  public boolean contains(Slice slice) {
    return this.scalaSlice.contains(slice.scalaSlice);
  }

  @Override
  public int compareTo(Slice other) {
    return this.scalaSlice.compare(other.scalaSlice);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    Slice that = (Slice) obj;
    return this.scalaSlice.equals(that.scalaSlice);
  }

  @Override
  public int hashCode() {
    return this.scalaSlice.hashCode();
  }

  @Override
  public String toString() {
    return this.scalaSlice.toString();
  }

  /** Package-private constructor from Scala Slice. */
  Slice(com.databricks.dicer.external.Slice scalaSlice) {
    this.scalaSlice = scalaSlice;
    this.lowInclusive = new SliceKey(scalaSlice.lowInclusive());
    com.databricks.dicer.external.HighSliceKey scalaSliceHighExclusive = scalaSlice.highExclusive();
    if (scalaSliceHighExclusive.isFinite()) {
      this.highExclusive = new SliceKey(scalaSliceHighExclusive.asFinite());
    } else {
      this.highExclusive = InfinitySliceKey.INSTANCE;
    }
  }

  /** Package-private accessor for the underlying Scala Slice. */
  com.databricks.dicer.external.Slice toScala() {
    return this.scalaSlice;
  }
}
