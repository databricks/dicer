use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use caching_rust_util::{
    saturated_system_time_to_unix_micros,
    saturated_system_time_to_unix_millis,
    system_clock,
    system_time_from_unix_millis,
    Clock,
    Elapsed,
    PrettySystemTime,
};

#[test]
fn verify_system_clock() {
    // Test plan: test that the system clock returns values within
    // reasonable bounds.
    let clock: Clock = system_clock();
    let start_wall_clock_time = clock.now();

    // Sleep for a bit to ensure that the clock is ticking.
    std::thread::sleep(Duration::from_millis(100));

    // Verify that the time measurement has increased by (very
    // approximately) the expected amount (the sleep is 100ms, but we only
    // require that the measurement has increased by between 10ms and
    // 10sec).
    let end_wall_clock_time = clock.now();
    assert!(end_wall_clock_time > start_wall_clock_time + Duration::from_millis(10));
    assert!(end_wall_clock_time < start_wall_clock_time + Duration::from_secs(10));
}

#[test]
fn test_fake_clock_clone() {
    // Test plan: verify that cloned system clocks expose roughly the same
    // time.
    const TOLERANCE: Duration = Duration::from_millis(100);
    let clock: Clock = system_clock();
    let clone: Clock = clock.clone();
    assert!(clock.now() < clone.now() + TOLERANCE);
    assert!(clock.now() > clone.now() - TOLERANCE);
}

#[test]
fn test_pretty_system_time() {
    use std::sync::LazyLock;

    use chrono::{DateTime, Utc};

    /// The minimum `SystemTime` that can be prettily formatted using `chrono`.
    static MIN_PRETTY_SYSTEM_TIME: LazyLock<SystemTime> =
        LazyLock::new(|| DateTime::<Utc>::MIN_UTC.into());

    /// The maximum `SystemTime` that can be prettily formatted using `chrono`.
    static MAX_PRETTY_SYSTEM_TIME: LazyLock<SystemTime> =
        LazyLock::new(|| DateTime::<Utc>::MAX_UTC.into());

    // Test plan: verify that `SystemTime` values are prettily formatted
    // (when feasible) by `PrettySystemTime`.

    let test_cases: &[(SystemTime, &str)] = &[
        // Examples in expected range.
        (SystemTime::UNIX_EPOCH, "1970-01-01T00:00:00Z"),
        (SystemTime::UNIX_EPOCH + Duration::from_secs(1733269574), "2024-12-03T23:46:14Z"),
        // Extreme cases for SystemTime, which cannot be prettily printed
        // due to limitations in chrono::DateTime
        (
            *MIN_PRETTY_SYSTEM_TIME - Duration::from_nanos(1),
            "SystemTime { tv_sec: -8334601228801, tv_nsec: 999999999 }",
        ),
        (
            *MAX_PRETTY_SYSTEM_TIME + Duration::from_nanos(1),
            "SystemTime { tv_sec: 8210266876800, tv_nsec: 0 }",
        ),
        // Extreme cases for chrono::DateTime, which should have a standard
        // ISO output.
        (*MIN_PRETTY_SYSTEM_TIME, "-262143-01-01T00:00:00Z"),
        (*MAX_PRETTY_SYSTEM_TIME, "+262142-12-31T23:59:59.999999999Z"),
    ];
    for (time, expected_str) in test_cases {
        assert_eq!(format!("{:?}", PrettySystemTime(*time)), expected_str.to_string(),);
    }
}

#[test]
fn test_system_time_from_unix_millis() {
    // Test plan: verify conversion from signed Unix milliseconds to SystemTime handles the epoch,
    // timestamps after the epoch, timestamps before the epoch, and boundary values that may be
    // unrepresentable on some platforms.
    const SAMPLE_UNIX_MILLIS: i64 = 1_733_269_574_123;

    struct TestCase {
        description: &'static str,
        unix_millis: i64,
        expected_time: Option<SystemTime>,
    }

    let test_cases: &[TestCase] = &[
        TestCase {
            description: "epoch",
            unix_millis: 0,
            expected_time: Some(SystemTime::UNIX_EPOCH),
        },
        TestCase {
            description: "one millisecond after epoch",
            unix_millis: 1,
            expected_time: Some(SystemTime::UNIX_EPOCH + Duration::from_millis(1)),
        },
        TestCase {
            description: "one millisecond before epoch",
            unix_millis: -1,
            expected_time: Some(SystemTime::UNIX_EPOCH - Duration::from_millis(1)),
        },
        TestCase {
            description: "sample timestamp after epoch",
            unix_millis: SAMPLE_UNIX_MILLIS,
            expected_time: Some(
                SystemTime::UNIX_EPOCH + Duration::from_millis(SAMPLE_UNIX_MILLIS as u64),
            ),
        },
        TestCase {
            description: "sample timestamp before epoch",
            unix_millis: -SAMPLE_UNIX_MILLIS,
            expected_time: Some(
                SystemTime::UNIX_EPOCH - Duration::from_millis(SAMPLE_UNIX_MILLIS as u64),
            ),
        },
        TestCase {
            description: "maximum signed Unix millisecond timestamp",
            unix_millis: i64::MAX,
            expected_time: SystemTime::UNIX_EPOCH
                .checked_add(Duration::from_millis(i64::MAX as u64)),
        },
        TestCase {
            description: "minimum signed Unix millisecond timestamp",
            unix_millis: i64::MIN,
            expected_time: SystemTime::UNIX_EPOCH
                .checked_sub(Duration::from_millis(i64::MIN.unsigned_abs())),
        },
    ];

    for test_case in test_cases {
        assert_eq!(
            system_time_from_unix_millis(test_case.unix_millis),
            test_case.expected_time,
            "test case '{}' failed",
            test_case.description,
        );
    }
}

#[test]
fn test_saturated_system_time_to_unix_millis() {
    // Test plan: verify conversion from SystemTime to signed Unix milliseconds handles the epoch,
    // timestamps after the epoch, timestamps before the epoch, truncation from nanoseconds to
    // milliseconds, and saturation at both i64 boundaries. The boundaries are covered from both
    // sides: the last exactly-representable magnitude, and one millisecond past it.
    const SAMPLE_UNIX_MILLIS: i64 = 1_733_269_574_123;

    /// Distance from the epoch, in milliseconds, of the largest representable positive timestamp.
    const MAX_MILLIS_FROM_EPOCH: u64 = i64::MAX as u64;

    /// Distance from the epoch, in milliseconds, of the most negative representable timestamp. One
    /// greater than `MAX_MILLIS_FROM_EPOCH`, so `i64::MIN` is only ever reached by clamping.
    const MIN_MILLIS_FROM_EPOCH: u64 = i64::MIN.unsigned_abs();

    struct TestCase {
        description: &'static str,
        time: SystemTime,
        expected_unix_millis: i64,
    }

    let test_cases: &[TestCase] = &[
        TestCase {
            description: "epoch",
            time: SystemTime::UNIX_EPOCH,
            expected_unix_millis: 0,
        },
        TestCase {
            description: "one millisecond after epoch",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(1),
            expected_unix_millis: 1,
        },
        TestCase {
            description: "one millisecond before epoch",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(1),
            expected_unix_millis: -1,
        },
        TestCase {
            description: "sample timestamp after epoch",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(SAMPLE_UNIX_MILLIS as u64),
            expected_unix_millis: SAMPLE_UNIX_MILLIS,
        },
        TestCase {
            description: "sample timestamp before epoch",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(SAMPLE_UNIX_MILLIS as u64),
            expected_unix_millis: -SAMPLE_UNIX_MILLIS,
        },
        TestCase {
            description: "submillisecond timestamp after epoch truncates toward zero",
            time: SystemTime::UNIX_EPOCH + Duration::from_nanos(1_999_999),
            expected_unix_millis: 1,
        },
        TestCase {
            description: "submillisecond timestamp before epoch truncates toward zero",
            time: SystemTime::UNIX_EPOCH - Duration::from_nanos(1_999_999),
            expected_unix_millis: -1,
        },
        TestCase {
            description: "one millisecond inside the positive boundary",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(MAX_MILLIS_FROM_EPOCH - 1),
            expected_unix_millis: i64::MAX - 1,
        },
        TestCase {
            description: "positive boundary converts exactly",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(MAX_MILLIS_FROM_EPOCH),
            expected_unix_millis: i64::MAX,
        },
        TestCase {
            description: "one millisecond past the positive boundary saturates",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(MAX_MILLIS_FROM_EPOCH + 1),
            expected_unix_millis: i64::MAX,
        },
        TestCase {
            description: "far past the positive boundary saturates",
            time: SystemTime::UNIX_EPOCH + Duration::from_millis(u64::MAX),
            expected_unix_millis: i64::MAX,
        },
        TestCase {
            description: "one millisecond inside the negative boundary",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(MIN_MILLIS_FROM_EPOCH - 1),
            expected_unix_millis: i64::MIN + 1,
        },
        TestCase {
            description: "negative boundary converts exactly",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(MIN_MILLIS_FROM_EPOCH),
            expected_unix_millis: i64::MIN,
        },
        TestCase {
            description: "one millisecond past the negative boundary saturates",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(MIN_MILLIS_FROM_EPOCH + 1),
            expected_unix_millis: i64::MIN,
        },
        TestCase {
            description: "far past the negative boundary saturates",
            time: SystemTime::UNIX_EPOCH - Duration::from_millis(u64::MAX),
            expected_unix_millis: i64::MIN,
        },
    ];

    for test_case in test_cases {
        assert_eq!(
            saturated_system_time_to_unix_millis(test_case.time),
            test_case.expected_unix_millis,
            "test case '{}' failed",
            test_case.description,
        );
    }
}

#[test]
fn test_saturated_system_time_to_unix_micros() {
    // Test plan: verify conversion from SystemTime to signed Unix microseconds handles the epoch,
    // timestamps after the epoch, timestamps before the epoch, truncation from nanoseconds to
    // microseconds, and saturation at both i64 boundaries. The boundaries are covered from both
    // sides: the last exactly-representable magnitude, and one microsecond past it.
    const SAMPLE_UNIX_MICROS: i64 = 1_733_269_574_123_456;

    /// Distance from the epoch, in microseconds, of the largest representable positive timestamp.
    const MAX_MICROS_FROM_EPOCH: u64 = i64::MAX as u64;

    /// Distance from the epoch, in microseconds, of the most negative representable timestamp. One
    /// greater than `MAX_MICROS_FROM_EPOCH`, so `i64::MIN` is only ever reached by clamping.
    const MIN_MICROS_FROM_EPOCH: u64 = i64::MIN.unsigned_abs();

    struct TestCase {
        description: &'static str,
        time: SystemTime,
        expected_unix_micros: i64,
    }

    let test_cases: &[TestCase] = &[
        TestCase {
            description: "epoch",
            time: SystemTime::UNIX_EPOCH,
            expected_unix_micros: 0,
        },
        TestCase {
            description: "one microsecond after epoch",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(1),
            expected_unix_micros: 1,
        },
        TestCase {
            description: "one microsecond before epoch",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(1),
            expected_unix_micros: -1,
        },
        TestCase {
            description: "sample timestamp after epoch",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(SAMPLE_UNIX_MICROS as u64),
            expected_unix_micros: SAMPLE_UNIX_MICROS,
        },
        TestCase {
            description: "sample timestamp before epoch",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(SAMPLE_UNIX_MICROS as u64),
            expected_unix_micros: -SAMPLE_UNIX_MICROS,
        },
        TestCase {
            description: "submicrosecond timestamp after epoch truncates toward zero",
            time: SystemTime::UNIX_EPOCH + Duration::from_nanos(1_999),
            expected_unix_micros: 1,
        },
        TestCase {
            description: "submicrosecond timestamp before epoch truncates toward zero",
            time: SystemTime::UNIX_EPOCH - Duration::from_nanos(1_999),
            expected_unix_micros: -1,
        },
        TestCase {
            description: "one microsecond inside the positive boundary",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(MAX_MICROS_FROM_EPOCH - 1),
            expected_unix_micros: i64::MAX - 1,
        },
        TestCase {
            description: "positive boundary converts exactly",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(MAX_MICROS_FROM_EPOCH),
            expected_unix_micros: i64::MAX,
        },
        TestCase {
            description: "one microsecond past the positive boundary saturates",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(MAX_MICROS_FROM_EPOCH + 1),
            expected_unix_micros: i64::MAX,
        },
        TestCase {
            description: "far past the positive boundary saturates",
            time: SystemTime::UNIX_EPOCH + Duration::from_micros(u64::MAX),
            expected_unix_micros: i64::MAX,
        },
        TestCase {
            description: "one microsecond inside the negative boundary",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(MIN_MICROS_FROM_EPOCH - 1),
            expected_unix_micros: i64::MIN + 1,
        },
        TestCase {
            description: "negative boundary converts exactly",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(MIN_MICROS_FROM_EPOCH),
            expected_unix_micros: i64::MIN,
        },
        TestCase {
            description: "one microsecond past the negative boundary saturates",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(MIN_MICROS_FROM_EPOCH + 1),
            expected_unix_micros: i64::MIN,
        },
        TestCase {
            description: "far past the negative boundary saturates",
            time: SystemTime::UNIX_EPOCH - Duration::from_micros(u64::MAX),
            expected_unix_micros: i64::MIN,
        },
    ];

    for test_case in test_cases {
        assert_eq!(
            saturated_system_time_to_unix_micros(test_case.time),
            test_case.expected_unix_micros,
            "test case '{}' failed",
            test_case.description,
        );
    }
}

#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn test_instant_and_sleep() {
    // Test plan: Verify that system_clock().instant() returns monotonic time and that sleep()
    // completes after the specified duration when time is advanced.
    let clock = system_clock();

    // Verify instant() returns a valid instant (monotonic).
    let t1 = clock.instant();
    let t2 = clock.instant();
    assert!(t2 >= t1, "instant() should be monotonic");

    // Verify sleep completes after advancing time.
    let completed = Arc::new(AtomicBool::new(false));
    let completed_clone = completed.clone();
    let clock_clone = clock.clone();

    let handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_secs(5)).await;
        completed_clone.store(true, Ordering::SeqCst);
    });

    // Sleep should not complete yet.
    tokio::task::yield_now().await;
    assert!(!completed.load(Ordering::SeqCst), "Sleep should not complete before advance");

    // Advance time and verify sleep completes.
    tokio::time::advance(Duration::from_secs(5)).await;
    handle.await.unwrap();
    assert!(completed.load(Ordering::SeqCst), "Sleep should complete after advance");
}

#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn test_timeout_success_and_error() {
    // Test plan: Verify that timeout succeeds when the inner future completes before the deadline,
    // and fails when the deadline elapses before the inner future completes.
    let clock: Clock = system_clock();

    // Test success: inner future completes immediately.
    let result: Result<i32, Elapsed> = clock.timeout(Duration::from_secs(10), async { 42 }).await;
    assert!(result.is_ok(), "Timeout should succeed for instant task");
    assert_eq!(result.unwrap(), 42);

    // Test failure: inner future takes longer than the timeout.
    let clock_clone = clock.clone();
    let timeout_fut = clock.timeout(Duration::from_secs(5), async move {
        clock_clone.sleep(Duration::from_secs(10)).await;
        42
    });

    // Advance past the timeout but before the inner sleep completes.
    tokio::time::advance(Duration::from_secs(6)).await;

    let result: Result<i32, Elapsed> = timeout_fut.await;
    assert!(result.is_err(), "Timeout should fail when deadline elapses");

    // Verify Elapsed's Display implementation.
    assert_eq!(result.unwrap_err().to_string(), "deadline has elapsed");
}

#[tokio::test]
async fn test_timeout_bias_zero_duration() {
    // Test plan: Verify that the timeout function prefers the future completion over the sleep,
    // this is done by using a zero duration timeout with a future that completes immediately, and
    // verifying that the result is always Ok.
    let clock: Clock = system_clock();

    // Run many times to ensure deterministic behavior.
    for _ in 0..1000 {
        let result = clock.timeout(Duration::ZERO, async { 42 }).await;
        assert_eq!(result, Ok(42), "Zero duration timeout should prefer task completion");
    }
}
