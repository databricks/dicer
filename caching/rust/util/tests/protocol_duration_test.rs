use std::time::Duration;

use caching_rust_util::protocol_duration::ProtocolDuration;
use caching_rust_util_testing::test_data::PROTOCOL_DURATION_TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::protocol_duration_test_data_p::test_case_p::Duration as ProtoDuration;

const MAX_DURATION: Duration = ProtocolDuration::MAX_DURATION;

// Valid cases: valid protocol durations in increasing order.
const VALID_CASES: &[Duration] = &[
    Duration::from_nanos(0),                                      // Zero duration
    Duration::from_nanos(1),                                      // Minimum non-zero duration
    Duration::from_millis(1),                                     // One millisecond
    Duration::from_millis(60_000),                                // One minute
    Duration::from_millis((MAX_DURATION.as_millis() - 1) as u64), // Just below max
    MAX_DURATION,                                                 // Maximum duration
];

// Invalid cases: Durations that exceed the maximum supported range.
const INVALID_CASES: &[Duration] = &[
    Duration::from_nanos(i64::MAX as u64 + 1), // Just above max
    Duration::from_millis((MAX_DURATION.as_millis() + 1) as u64), // Just above max
    Duration::from_millis(u64::MAX),
];

#[test]
fn test_max_duration_in_millis() {
    // Test plan: verify that the [`max_duration_millis`] is positive and
    // corresponds to the maximum duration supported.
    let max_millis: i64 = ProtocolDuration::max_duration_millis();
    assert!(max_millis > 0);
    assert_eq!(max_millis, MAX_DURATION.as_millis() as i64);
    ProtocolDuration::try_from(Duration::from_millis(max_millis as u64)).unwrap();
}

#[test]
fn test_valid_durations() {
    // Test plan: verify that all valid durations can be converted to ProtocolDuration.
    for &duration in VALID_CASES {
        let result = ProtocolDuration::try_from(duration).unwrap();
        assert_eq!(result.as_nanos(), duration.as_nanos());
    }
}

#[test]
fn test_invalid_durations() {
    // Test plan: verify that all invalid durations return an error.
    for &duration in INVALID_CASES {
        let result = ProtocolDuration::try_from(duration);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().code(), tonic::Code::InvalidArgument);
    }
}

#[test]
fn test_valid_durations_ordering() {
    // Test plan: verify that valid durations maintain the correct ordering.
    let mut durations: Vec<_> = VALID_CASES
        .iter()
        .map(|&d| ProtocolDuration::try_from(d).unwrap())
        .collect();
    durations.sort();
    assert_eq!(
        durations,
        VALID_CASES
            .iter()
            .map(|&d| ProtocolDuration::try_from(d).unwrap())
            .collect::<Vec<_>>()
    );
}

#[test]
fn test_into_duration() {
    // Test plan: verify that converting ProtocolDuration back to Duration works
    // correctly.
    for &duration in VALID_CASES {
        let protocol_duration = ProtocolDuration::try_from(duration).unwrap();
        assert_eq!(protocol_duration.into_duration(), duration);
    }
}

#[test]
fn test_deref_impl() {
    // Test plan: verify that ProtocolDuration can be automatically dereferenced to
    // Duration, by calling Duration methods directly on it and checking the results.
    for &duration in VALID_CASES {
        let protocol_duration = ProtocolDuration::try_from(duration).unwrap();
        assert_eq!(protocol_duration.as_secs(), duration.as_secs());
        assert_eq!(protocol_duration.as_secs_f32(), duration.as_secs_f32());
        assert_eq!(protocol_duration.as_secs_f64(), duration.as_secs_f64());
        assert_eq!(protocol_duration.as_millis(), duration.as_millis());
        assert_eq!(protocol_duration.as_micros(), duration.as_micros());
        assert_eq!(protocol_duration.as_nanos(), duration.as_nanos());
        assert_eq!(protocol_duration.is_zero(), duration.is_zero());
        assert_eq!(protocol_duration.subsec_millis(), duration.subsec_millis());
        assert_eq!(protocol_duration.subsec_micros(), duration.subsec_micros());
        assert_eq!(protocol_duration.subsec_nanos(), duration.subsec_nanos());
    }
}

#[test]
fn test_valid_durations_from_proto_data() {
    // Test plan: Verify that all valid durations from proto data are accepted.
    for test_case in &PROTOCOL_DURATION_TEST_DATA.valid_cases {
        let test_name = test_case
            .name
            .as_ref()
            .expect("expect a name for each case");

        let duration = match &test_case.duration {
            Some(ProtoDuration::Nanos(value)) => Duration::from_nanos(*value as u64),
            Some(ProtoDuration::Micros(value)) => Duration::from_micros(*value as u64),
            Some(ProtoDuration::Millis(value)) => Duration::from_millis(*value as u64),
            Some(ProtoDuration::Seconds(value)) => Duration::from_secs(*value as u64),
            None => panic!("expect a non-empty duration for {}", test_name),
        };

        let result = ProtocolDuration::try_from(duration);
        assert!(
            result.is_ok(),
            "expect {} to succeed, but got error: {:?}",
            test_name,
            result.err()
        );
        assert_eq!(result.unwrap().as_nanos(), duration.as_nanos());
    }
}

#[test]
fn test_invalid_durations_from_proto_data() {
    // Test plan: Verify that all invalid durations from proto data are rejected with invalid
    // argument errors.
    for test_case in &PROTOCOL_DURATION_TEST_DATA.invalid_cases {
        let test_name = test_case
            .name
            .as_ref()
            .expect("expect a name for each case");

        let duration = match &test_case.duration {
            Some(ProtoDuration::Nanos(value)) => Duration::from_nanos(*value as u64),
            Some(ProtoDuration::Micros(value)) => Duration::from_micros(*value as u64),
            Some(ProtoDuration::Millis(value)) => Duration::from_millis(*value as u64),
            Some(ProtoDuration::Seconds(value)) => Duration::from_secs(*value as u64),
            None => panic!("expect a non-empty duration for {}", test_name),
        };

        let result = ProtocolDuration::try_from(duration);
        assert!(result.is_err(), "expect {} to fail, but it succeeded", test_name);
    }
}
