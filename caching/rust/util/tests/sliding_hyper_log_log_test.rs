use std::ops::Range;
use std::time::{Duration, Instant};

use caching_rust_util::hyper_log_log::HyperLogLog;
use caching_rust_util::sliding_hyper_log_log::SlidingHyperLogLog;

const RETENTION: Duration = Duration::from_secs(60);

// Adds integers in the range converted to keys to the given SlidingHyperLogLog.
fn add_range(now: Instant, shll: &mut SlidingHyperLogLog, range: Range<usize>) {
    for i in range {
        shll.add(now, &key_of(i)[..])
    }
}

fn key_of(x: usize) -> [u8; 8] {
    (x as u64).to_be_bytes()
}

fn assert_approximate(actual: u64, expected: u64) {
    if expected == 0 {
        assert_eq!(actual, 0);
        return;
    }

    // HyperLogLog produces estimates, so allow a tolerance. For the default precision this class
    // uses, a few percent is expected; we allow a bit more headroom to keep the test non-flaky.
    const THRESHOLD: f64 = 0.18;

    let error = ((expected as f64) - (actual as f64)).abs() / (expected as f64);
    assert!(
        error < THRESHOLD,
        "actual={}, expected={}, error {:.2}% is over threshold {:.2}%",
        actual,
        expected,
        error * 100.0,
        THRESHOLD * 100.0,
    )
}

#[test]
fn empty_zero() {
    // Test plan: verify that a fresh SlidingHyperLogLog that has observed no keys reports a
    // cardinality estimate of zero.

    let now = Instant::now();
    let shll = SlidingHyperLogLog::new(RETENTION);
    assert_eq!(shll.recent(now).estimate(), 0);
}

#[test]
fn estimate_single_bucket() {
    // Test plan: verify that keys observed without advancing the clock all land in the same bucket
    // and are counted as distinct. Observe 1000 distinct keys and assert the estimate is ~1000.
    let now = Instant::now();
    let mut shll = SlidingHyperLogLog::new(RETENTION);

    add_range(now, &mut shll, 0..1000);

    assert_approximate(shll.recent(now).estimate(), 1000);
}

#[test]
fn unique_single_bucket() {
    // Test plan: verify that observing the same set of keys more than once does not inflate the
    // estimate. Observe the same 1000 keys twice and assert the estimate is still ~1000.
    let now = Instant::now();
    let mut shll = SlidingHyperLogLog::new(RETENTION);

    add_range(now, &mut shll, 0..1000);
    add_range(now, &mut shll, 0..1000);

    assert_approximate(shll.recent(now).estimate(), 1000);
}

#[test]
fn unique_many_buckets() {
    // Test plan: verify that observing overlapping sets of keys in different time buckets are not
    // double-counted in the estimate.

    let mut now = Instant::now();
    let mut shll = SlidingHyperLogLog::new(RETENTION);

    add_range(now, &mut shll, 0..1000);
    now += RETENTION / 2;
    add_range(now, &mut shll, 500..1500);

    assert_approximate(shll.recent(now).estimate(), 1500);
}

#[test]
fn merge_single_bucket() {
    // Test plan: verify that merging a full HLL into the sliding HLL is counted appropriately.

    let now = Instant::now();
    let mut shll = SlidingHyperLogLog::new(RETENTION);

    add_range(now, &mut shll, 0..1000);
    let mut hll = HyperLogLog::new();
    for i in 500..1500 {
        hll.add(&key_of(i)[..]);
    }
    shll.merge_from(now, &hll);

    assert_approximate(shll.recent(now).estimate(), 1500);
}

#[test]
fn drop_old_observations() {
    // Test plan: verify that time buckets older than the retention window stop contributing to the
    // estimate. Add keys over time, continue advancing the clock, and observe the estimate no
    // longer include them once they become too old.

    let mut now = Instant::now();
    let mut shll = SlidingHyperLogLog::new(RETENTION);

    add_range(now, &mut shll, 0..1000);
    assert_approximate(shll.recent(now).estimate(), 1000);

    // t= Times are shown relative to RETENTION.
    //
    //             [0, 1000)
    //             |
    // t =         0
    // now =       ^
    // retention = ]
    now += RETENTION / 2;
    assert_approximate(shll.recent(now).estimate(), 1000);
    add_range(now, &mut shll, 500..1500);
    assert_approximate(shll.recent(now).estimate(), 1500);

    now += RETENTION / 4;
    //             [0, 1000)       [500, 1500)
    //             |       '       |       '
    // t =         0               0.5
    // now =                               ^
    // retention = ------------------------]
    assert_approximate(shll.recent(now).estimate(), 1500);
    add_range(now, &mut shll, 1250..1750);
    assert_approximate(shll.recent(now).estimate(), 1750);

    now += RETENTION / 2;
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '
    // t =         0               0.5             1
    // now =                                               ^
    // retention =         [-------------------------------]

    // [0, 1000) fallen off
    assert_approximate(shll.recent(now).estimate(), 1250);

    now += 3 * RETENTION / 8;
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '       |       '       |
    // t =         0               0.5             1               1.5             2
    // now =                                                            ^
    // retention =                      [-------------------------------]

    // [500, 1500) fallen off
    assert_approximate(shll.recent(now).estimate(), 500);

    now += RETENTION / 2;
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '       |       '       |
    // t =         0               0.5             1               1.5             2
    // now =                                                                            ^
    // retention =                                      [-------------------------------]

    // [1250, 1750) fallen off
    assert_approximate(shll.recent(now).estimate(), 0)
}
