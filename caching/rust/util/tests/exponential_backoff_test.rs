use std::time::Duration;

use backoff::backoff::Backoff;
use caching_rust_util::exponential_backoff::create_exponential_backoff;
use caching_rust_util_testing::test_data::EXPONENTIAL_BACKOFF_TEST_DATA;

/// Asserts `delay` is within ±`jitter_ratio`/2 of `pre_jitter`.
fn assert_delay_within_jitter(delay: Duration, pre_jitter: Duration, jitter_ratio: f64) {
    let pre_jitter_ms = pre_jitter.as_millis() as f64;
    let low = (pre_jitter_ms * (1.0 - jitter_ratio / 2.0)).round() as u128;
    let high = (pre_jitter_ms * (1.0 + jitter_ratio / 2.0)).round() as u128;
    let delay_ms = delay.as_millis();
    assert!(
        delay_ms >= low && delay_ms <= high,
        "delay {delay:?} not within ±{}% of {pre_jitter:?}",
        jitter_ratio / 2.0 * 100.0,
    );
}

#[test]
fn exponential_backoff_matches_scala() {
    // Test plan: Verify that `create_exponential_backoff` doubles, caps, jitters, and never stops
    // within the shared textproto parameters.
    let test_data = &*EXPONENTIAL_BACKOFF_TEST_DATA;
    let multiplier = test_data.multiplier.expect("multiplier");
    let jitter_ratio = test_data.jitter_ratio.expect("jitter_ratio");
    let min_retry_delay = Duration::from_millis(
        test_data
            .min_retry_delay_millis
            .expect("min_retry_delay_millis") as u64,
    );
    let max_retry_delay = Duration::from_millis(
        test_data
            .max_retry_delay_millis
            .expect("max_retry_delay_millis") as u64,
    );

    let mut backoff = create_exponential_backoff(min_retry_delay, max_retry_delay);
    assert_eq!(backoff.max_elapsed_time, None);

    for step in 0..test_data
        .initial_doubling_steps_to_verify
        .expect("initial_doubling_steps_to_verify")
    {
        let pre_jitter = backoff.current_interval;
        let delay = backoff.next_backoff().unwrap();
        assert_delay_within_jitter(delay, pre_jitter, jitter_ratio);
        assert_eq!(
            backoff.current_interval,
            Duration::from_millis(
                (min_retry_delay.as_millis() as f64 * multiplier.powi(step + 1)).round() as u64,
            ),
        );
    }

    while backoff.current_interval < max_retry_delay {
        let pre_jitter = backoff.current_interval;
        let delay = backoff.next_backoff().unwrap();
        assert_delay_within_jitter(delay, pre_jitter, jitter_ratio);
    }
    assert_eq!(backoff.current_interval, max_retry_delay);

    for _ in 0..test_data
        .iterations_at_max_interval
        .expect("iterations_at_max_interval")
    {
        let pre_jitter = backoff.current_interval;
        let delay = backoff.next_backoff().unwrap();
        assert_delay_within_jitter(delay, pre_jitter, jitter_ratio);
        assert_eq!(backoff.current_interval, max_retry_delay);
    }

    for _ in 0..test_data
        .max_elapsed_none_iterations
        .expect("max_elapsed_none_iterations")
    {
        // Sanity check only: `max_elapsed_time` is wall-clock based, so this loop does not
        // exercise the `backoff` crate's default 15-minute cutoff.
        assert!(
            backoff.next_backoff().is_some(),
            "backoff should not stop due to max elapsed time"
        );
    }
}
