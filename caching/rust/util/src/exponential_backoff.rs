use std::time::Duration;

use backoff::{ExponentialBackoff, ExponentialBackoffBuilder};

// LINT.IfChange
/// Per-step multiplier matching [`caching.util.ExponentialBackoff`] (always doubles).
const BACKOFF_MULTIPLIER: f64 = 2.0;

/// Randomization factor for the [`backoff`] crate.
///
/// Scala's `jitterRatio = 0.4` applies `(random - 0.5) * jitterRatio`, which is ±20% — use
/// `0.2` here. See `caching/util/src/ExponentialBackoff.scala` and
/// `caching/util/test/ExponentialBackoffSuite.scala`.
const BACKOFF_RANDOMIZATION_FACTOR: f64 = 0.2;
// LINT.ThenChange(caching/util/src/ExponentialBackoff.scala)

/// Creates exponential backoff matching Scala `new ExponentialBackoff(random, min, max)`.
///
/// Rust ports of caching-team retry logic should use this instead of
/// [`ExponentialBackoffBuilder::new`] directly.
pub fn create_exponential_backoff(
    min_retry_delay: Duration,
    max_retry_delay: Duration,
) -> ExponentialBackoff {
    ExponentialBackoffBuilder::new()
        .with_initial_interval(min_retry_delay)
        .with_max_interval(max_retry_delay)
        .with_multiplier(BACKOFF_MULTIPLIER)
        .with_randomization_factor(BACKOFF_RANDOMIZATION_FACTOR)
        .with_max_elapsed_time(None) // Never stops based on total elapsed retry time.
        .build()
}
