package com.databricks.testing;

import java.time.Duration;
import java.util.Optional;

/** A base class for pure Java tests. Provides async test helpers. */
public abstract class DatabricksJavaTest {

  private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

  /** Waits until the given assertion passes without throwing. */
  protected void waitUntilAsserted(Runnable assertion) {
    long deadline = System.nanoTime() + DEFAULT_WAIT_TIMEOUT.toNanos();
    Optional<AssertionError> lastError = Optional.empty();
    while (System.nanoTime() < deadline) {
      try {
        assertion.run();
        return; // Assertion passed.
      } catch (AssertionError e) {
        lastError = Optional.of(e);
      }

      try {
        Thread.sleep(DEFAULT_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        // Restore interrupt flag.
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting", e);
      }
    }

    throw new AssertionError(
        "Assertion did not pass within " + DEFAULT_WAIT_TIMEOUT, lastError.orElse(null));
  }
}
