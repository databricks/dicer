package com.databricks.caching.util.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.testing.DatabricksJavaTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JFakeTypedClockTest extends DatabricksJavaTest {

  @Test
  void testInstantNotNull() {
    // Test plan: Verify that instant() after construction is not null.
    JFakeTypedClock clock = new JFakeTypedClock();
    Instant instant = clock.instant();
    assertThat(instant).isNotNull();
  }

  @Test
  void testInstantIsConsistentAtCreation() {
    // Test plan: Verify that a newly created JFakeTypedClock reports a consistent instant.
    JFakeTypedClock clock = new JFakeTypedClock();
    Instant first = clock.instant();
    Instant second = clock.instant();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void testAdvanceBy() {
    // Test plan: Verify that advanceBy advances the clock's instant by the given duration.
    JFakeTypedClock clock = new JFakeTypedClock();
    Instant before = clock.instant();
    clock.advanceBy(Duration.ofSeconds(5));
    assertThat(clock.instant()).isEqualTo(before.plusSeconds(5));
  }

  @Test
  void testNullAdvanceByThrows() {
    // Test plan: Verify that passing a null duration to advanceBy throws NullPointerException.
    JFakeTypedClock clock = new JFakeTypedClock();
    assertThatThrownBy(() -> clock.advanceBy(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void testToString() {
    // Test plan: Verify that toString returns the expected string representation.
    JFakeTypedClock clock = new JFakeTypedClock();
    assertThat(clock.toString()).contains("FakeTypedClock");
  }

  @Test
  void testEquals() {
    // Test plan: Use TestUtils.checkEquality to verify that JFakeTypedClocks within the same
    // equality group are equal and JFakeTypedClocks in different groups are not equal.
    JFakeTypedClock clock1 = new JFakeTypedClock();
    JFakeTypedClock clock2 = new JFakeTypedClock();
    TestUtils.checkEquality(List.of(List.of(clock1, clock1), List.of(clock2, clock2)));
  }

  @Test
  void testToScala() {
    // Test plan: Verify that toScala() returns a valid Scala FakeTypedClock instance.
    JFakeTypedClock javaClock = new JFakeTypedClock();
    com.databricks.caching.util.FakeTypedClock scalaClock = javaClock.toScala();

    assertThat(scalaClock).isInstanceOf(com.databricks.caching.util.FakeTypedClock.class);
    assertThat(scalaClock.instant()).isEqualTo(javaClock.instant());

    javaClock.advanceBy(Duration.ofSeconds(10));
    assertThat(scalaClock.instant()).isEqualTo(javaClock.instant());
  }
}
