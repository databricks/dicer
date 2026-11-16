use std::time::{Duration, SystemTime};

use lazy_static::lazy_static;
use caching_rust_util::system_clock;
use caching_rust_util_testing::fake_clock::FakeClock;
use caching_rust_util_testing::test_utils::check_rel_ops;
use dicer_common_test_proto_test_data_proto::dicer::test::squid_test_data_p::EqualityGroupP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SquidP;
use dicer_rust_external_types::ResourceAddress;
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers;
use dicer_rust_testing::test_data::SQUID_TEST_DATA;
use uuid::Uuid;

lazy_static! {
    /// A list of squids in expected sort order.
    static ref ORDERED_SQUIDS: Vec<Squid> = SQUID_TEST_DATA
        .ordered_squids
        .iter()
        .map(|proto| Squid::from_proto(proto).unwrap())
        .collect();
}

/// Truncates a `SystemTime` to millisecond precision.
fn truncate_to_millis(time: SystemTime) -> SystemTime {
    let duration_since_epoch: Duration = time.duration_since(SystemTime::UNIX_EPOCH).unwrap();
    SystemTime::UNIX_EPOCH + Duration::from_millis(duration_since_epoch.as_millis() as u64)
}

#[test]
fn test_rel_ops() {
    // Test plan: verify that Squids implement comparison operators correctly.
    check_rel_ops(&ORDERED_SQUIDS);
}

#[test]
fn test_equality() {
    // Test plan: verify that Squids implement equality operators and `Hash` correctly.
    data_helpers::check_equality(
        &SQUID_TEST_DATA
            .squid_equality_groups
            .iter()
            .map(|group: &EqualityGroupP| {
                group
                    .squids
                    .iter()
                    .map(|squid: &SquidP| Squid::from_proto(squid).unwrap())
                    .collect()
            })
            .collect::<Vec<_>>(),
    );
}

#[test]
fn create_for_new_incarnation() {
    // Test plan: verify accessors return the values passed to the constructor.
    let clock = system_clock();
    let start_time: SystemTime = clock.now();
    let resource_address: ResourceAddress = ResourceAddress::new("http://localhost:8080").unwrap();
    let uuid: Uuid = Uuid::new_v4();
    let squid: Squid = Squid::create_for_new_incarnation(&clock, resource_address.clone(), uuid);
    assert_eq!(squid.resource_uuid(), &uuid);
    assert_eq!(squid.resource_address().uri(), resource_address.uri());

    let end_time: SystemTime = clock.now();
    let squid_creation_time: SystemTime =
        SystemTime::UNIX_EPOCH + Duration::from_millis(squid.creation_time_millis() as u64);
    assert!(truncate_to_millis(start_time) <= squid_creation_time);
    assert!(squid_creation_time <= end_time);
}

#[test]
fn test_roundtrip() {
    // Test plan: verify that squids round-trip via protos.
    for squid in &*ORDERED_SQUIDS {
        let proto = squid.to_proto();
        let round_trip_squid = Squid::from_proto(&proto).unwrap();
        assert_eq!(squid, &round_trip_squid);
    }
}

#[test]
fn test_roundtrip_with_nanosecond_precision_from_clock() {
    // Test plan: verify that Squid instances roundtrip correctly even when the creation time
    // indicated by the clock has a non-zero sub-millisecond portion. (The creation time should be
    // truncated to millisecond precision to ensure this.)
    let clock = FakeClock::create(SystemTime::UNIX_EPOCH + Duration::new(1234567890, 123456789))
        .into_clock();
    let squid: Squid = Squid::create_for_new_incarnation(
        &clock,
        ResourceAddress::new("http://localhost:8080").unwrap(),
        Uuid::new_v4(),
    );

    // Convert to proto and back.
    let proto: SquidP = squid.to_proto();
    let round_trip_squid = Squid::from_proto(&proto).unwrap();

    // Verify that the pre- and post-serialization Squids are the same.
    assert_eq!(round_trip_squid, squid);
    assert_eq!(round_trip_squid.creation_time_millis(), squid.creation_time_millis());
}

#[test]
fn test_creation_time_after_from_proto() {
    // Test plan: verify that `creation_time_millis()` returns the expected value after conversion
    // from a proto, even if the creation time was negative.
    for proto in &SQUID_TEST_DATA.ordered_squids {
        let squid = Squid::from_proto(proto).unwrap();
        let creation_time_millis = proto.creation_time_millis.unwrap();
        assert_eq!(squid.creation_time_millis(), creation_time_millis);
    }
}

#[test]
fn test_create_with_unrepresentable_time() {
    // Test plan: verify that a creation time too far from the Unix epoch to represent as an i64
    // number of milliseconds saturates instead of failing. Do this by creating Squids from clocks
    // set far before and far after the epoch.
    let test_cases = [
        (
            SystemTime::UNIX_EPOCH - Duration::from_millis(u64::MAX) - Duration::from_secs(1),
            i64::MIN,
        ),
        (
            SystemTime::UNIX_EPOCH + Duration::from_millis(u64::MAX) + Duration::from_secs(1),
            i64::MAX,
        ),
    ];

    for (time, expected_creation_time_millis) in test_cases {
        let clock = FakeClock::create(time).into_clock();
        let squid: Squid = Squid::create_for_new_incarnation(
            &clock,
            ResourceAddress::new("http://localhost:8080").unwrap(),
            Uuid::new_v4(),
        );
        assert_eq!(squid.creation_time_millis(), expected_creation_time_millis);
    }
}

#[test]
fn test_invalid_proto() {
    // Test plan: verify the expected errors are returned when deserializing invalid SquidP protos.
    for test_case in &SQUID_TEST_DATA.invalid_squid_test_cases {
        let proto = test_case.squid.as_ref().unwrap();
        match Squid::from_proto(proto) {
            Ok(_) => panic!("Expected {proto:?} to fail to parse, but it successfully parsed"),
            Err(e) => {
                let expected_error = test_case.expected_error.as_ref().unwrap();
                assert!(
                    e.message().contains(expected_error),
                    "Expected error message to contain {expected_error:?}, but it was {:?}",
                    e.message()
                );
            }
        }
    }
}
