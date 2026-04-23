package com.databricks.caching.util.javaapi;


import java.time.Duration;
import java.time.Instant;
import scala.compat.java8.DurationConverters;
import scala.concurrent.duration.FiniteDuration;

/**
 * A fake clock for unit tests that does not advance automatically.
 *
 * <p>See {@link com.databricks.caching.util.FakeTypedClock} for the underlying Scala
 * implementation.
 *
 * <p>Note: This Java shim only provides a subset of the functionality available in {@link
 * com.databricks.caching.util.FakeTypedClock}. Additional functionality should be added as needed.
 */
public final class JFakeTypedClock {
  /** The underlying Scala `FakeTypedClock` instance. */
  private final com.databricks.caching.util.FakeTypedClock scalaFakeTypedClock;

  public JFakeTypedClock() {
    this.scalaFakeTypedClock = new com.databricks.caching.util.FakeTypedClock();
  }

  /**
   * @see com.databricks.caching.util.FakeTypedClock#instant()
   */
  public Instant instant() {
    return this.scalaFakeTypedClock.instant();
  }

  /**
   * @see com.databricks.caching.util.FakeTypedClock#advanceBy(FiniteDuration)
   */
  public void advanceBy(Duration duration) {
    // If a Java Duration is within the max range of a Scala Duration, convert it directly to a
    // FiniteDuration. Otherwise, convert it to the max FiniteDuration. Java's Duration object has a
    // significantly greater range than Scala's Duration, thus we leverage a Java-side sentinel to
    // represent numbers in Java which exceeds Scala's Duration range.
    if (duration.compareTo(Constants.MAX_DURATION()) < 0) {
      this.scalaFakeTypedClock.advanceBy(DurationConverters.toScala(duration));
    } else {
      this.scalaFakeTypedClock.advanceBy(
          FiniteDuration.apply(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS));
    }
  }

  /** Returns the underlying Scala `FakeTypedClock` instance. */
  public com.databricks.caching.util.FakeTypedClock toScala() {
    return this.scalaFakeTypedClock;
  }

  @Override
  public String toString() {
    return this.scalaFakeTypedClock.toString();
  }

  @Override
  public int hashCode() {
    return this.scalaFakeTypedClock.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    JFakeTypedClock other = (JFakeTypedClock) obj;
    return this.scalaFakeTypedClock.equals(other.scalaFakeTypedClock);
  }
}
