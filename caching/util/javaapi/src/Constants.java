package com.databricks.caching.util.javaapi;

import java.time.Duration;

public final class Constants {
  /** Private constructor that prevents external instantiation. */
  private Constants() {}

  /**
   * The largest maximum duration representable using Scala's FiniteDuration type.
   *
   * <p>The conversion between a Java and Scala Duration is nontrivial. Although both Duration's
   * have a nanoseconds resolution, the Scala Duration has a max range of ~292 years compared to the
   * Java Duration which spans billions of years. Scala Duration also has sentinel values like
   * Duration.Inf which the Java Duration does not support.
   *
   * <p>As such, we define a MAX_DURATION sentinel in Java to represent Scala's Duration.Inf. Any
   * Caching services which take in a Java Duration that are equal to or exceeds MAX_DURATION will
   * be interpreted as infinite duration as well internally.
   */
  public static Duration MAX_DURATION() {
    return Duration.ofNanos(Long.MAX_VALUE);
  }
}
