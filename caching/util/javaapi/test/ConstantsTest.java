package com.databricks.caching.util.javaapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import scala.compat.java8.DurationConverters;

final class ConstantsTest {
  @Test
  void testMaxDuration() {
    // Test plan: verify that MAX_DURATION can be successfully converted to a Scala duration when
    // within range and throws otherwise.
    assertDoesNotThrow(() -> DurationConverters.toScala(Constants.MAX_DURATION()));

    assertThatThrownBy(
            () -> DurationConverters.toScala(Constants.MAX_DURATION().plus(Duration.ofNanos(1))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
