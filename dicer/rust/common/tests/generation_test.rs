use std::sync::LazyLock;

use tonic::{Code, Status};
use caching_rust_util_testing::test_utils::check_rel_ops;
use dicer_common_proto_common_proto::dicer::GenerationP;
use dicer_rust_common::{Generation, Incarnation, UnixTimeVersion};
use dicer_rust_testing::test_data::GENERATION_TEST_DATA;

/// A list of generations in expected sort order.
static ORDERED_GENERATIONS: LazyLock<Vec<Generation>> = LazyLock::new(|| {
    GENERATION_TEST_DATA
        .ordered_generations
        .iter()
        .map(|proto| Generation::from_proto(proto).unwrap())
        .collect()
});

#[test]
fn test_rel_ops() {
    // Test plan: verify that generations implement the expected relational
    // operators.
    check_rel_ops(&ORDERED_GENERATIONS);
}

#[test]
fn test_roundtrip() {
    // Test plan: verify that generations round-trip via protos.
    for generation in ORDERED_GENERATIONS.iter() {
        let proto = generation.to_proto();
        let round_trip_generation = Generation::from_proto(&proto).unwrap();
        assert_eq!(generation, &round_trip_generation);
    }
}

#[test]
fn test_canonicalization() {
    // Test plan: verify that parsing and reserializing a generation proto
    // results in the expected canonical representation.
    for test_case in GENERATION_TEST_DATA.canonical_generation_test_cases.iter() {
        let generation = Generation::from_proto(test_case.generation.as_ref().unwrap()).unwrap();
        let proto: GenerationP = generation.to_proto();
        assert_eq!(&proto, test_case.expected_canonical_generation.as_ref().unwrap(),);
    }
}

#[test]
fn test_validation() {
    // Test plan: verify that when parsing invalid generation protos, the
    // expected error is returned.
    for test_case in GENERATION_TEST_DATA.invalid_generation_test_cases.iter() {
        let proto: &GenerationP = test_case.generation.as_ref().unwrap();
        let error: Status = Generation::from_proto(proto).unwrap_err();
        let expected_error: &String = test_case.expected_error.as_ref().unwrap();
        assert_eq!(error.code(), Code::InvalidArgument);
        assert!(error.message().contains(expected_error.as_str()));
    }
}

#[test]
fn test_looseness() {
    // Test plan: verify that incarnations have the expected looseness.
    for &loose_incarnation_number in GENERATION_TEST_DATA.loose_incarnation_numbers.iter() {
        let incarnation = Incarnation::new(loose_incarnation_number).unwrap();
        assert!(incarnation.is_loose());
        assert!(!incarnation.is_strict());
    }
    for &strict_incarnation_number in GENERATION_TEST_DATA.strict_incarnation_numbers.iter() {
        let incarnation = Incarnation::new(strict_incarnation_number).unwrap();
        assert!(!incarnation.is_loose());
        assert!(incarnation.is_strict());
    }
}

#[test]
fn test_to_string() {
    // Test plan: verify that generations can be converted to strings with the
    // expected (human-readable) format.
    for test_case in GENERATION_TEST_DATA.to_string_test_cases.iter() {
        let generation = Generation::from_proto(test_case.generation.as_ref().unwrap()).unwrap();
        let expected_string: &String = test_case.expected_string.as_ref().unwrap();
        assert_eq!(format!("{:?}", generation), expected_string.as_str());
    }
}

#[test]
fn test_unix_time_version_to_unix_millis() {
    // Test plan: verify that `UnixTimeVersion::to_unix_millis` returns the correct value.
    let version = UnixTimeVersion::from_unix_millis(1234).unwrap();
    assert_eq!(version.to_unix_millis(), 1234);
}
