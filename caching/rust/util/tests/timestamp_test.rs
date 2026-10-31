use chrono::{DateTime, Utc};
use caching_rust_util::timestamp::Timestamp;

#[test]
fn test_timestamp_creation() {
    // Test Plan: Verify that Timestamp can be created.
    let timestamp = Timestamp::from_epoch_micros(1716153600000000);
    assert_eq!(timestamp.epoch_micros(), 1716153600000000);
}

#[test]
fn test_timestamp_equality() {
    // Test Plan: Verify that Timestamp implements equality operators correctly.
    let timestamp1 = Timestamp::from_epoch_micros(1716153600000000);
    let timestamp2 = Timestamp::from_epoch_micros(1716153600000000);
    let timestamp3 = timestamp1;
    assert_eq!(timestamp1, timestamp2);
    assert_eq!(timestamp1, timestamp3);
    assert_eq!(timestamp2, timestamp3);

    let timestamp4 = Timestamp::from_epoch_micros(1716153600000001);
    assert_ne!(timestamp1, timestamp4);
    assert_ne!(timestamp2, timestamp4);
    assert_ne!(timestamp3, timestamp4);
}

#[test]
fn test_timestamp_debug() {
    // Test Plan: Verify that Timestamp implements Debug correctly.
    assert_eq!(format!("{:?}", Timestamp::from_epoch_micros(0)), "1970-01-01T00:00:00Z");
    assert_eq!(
        format!("{:?}", Timestamp::from_epoch_micros(1715123446927394)),
        "2024-05-07T23:10:46.927394Z"
    );

    // Test values near the chrono limits.
    // Min chrono DateTime
    assert_eq!(
        format!("{:?}", Timestamp::from_epoch_micros(DateTime::<Utc>::MIN_UTC.timestamp_micros())),
        "-262143-01-01T00:00:00Z".to_string()
    );
    // Max chrono DateTime
    assert_eq!(
        format!("{:?}", Timestamp::from_epoch_micros(DateTime::<Utc>::MAX_UTC.timestamp_micros())),
        "+262142-12-31T23:59:59.999999Z".to_string()
    );

    // Test min and max i64 values
    assert_eq!(
        format!("{:?}", Timestamp::from_epoch_micros(i64::MIN)),
        "Timestamp(Microseconds since epoch: -9223372036854775808μs)".to_string()
    );
    assert_eq!(
        format!("{:?}", Timestamp::from_epoch_micros(i64::MAX)),
        "Timestamp(Microseconds since epoch: 9223372036854775807μs)".to_string()
    );
}
