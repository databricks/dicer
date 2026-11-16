use std::collections::HashMap;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use rand::random;
use tonic::{Code, Status};
use dicer_common_proto_common_proto::dicer::diff_assignment_p::SliceAssignmentP;
use dicer_common_test_proto_test_data_proto::dicer::test::simple_diff_assignment_p::SimpleSliceAssignmentP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SquidP;
use dicer_rust_common::{
    slice_assignment_to_proto,
    BuildSliceMap,
    Generation,
    Incarnation,
    InternalSliceAssignment,
    ResourceProtoBuilder,
    SliceMap,
    UnixTimeVersion,
};
use dicer_rust_external_types::Slice;
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers::{
    create_random_slice_assignment,
    create_random_slice_with_subslices,
    parse_simple_slice_assignment,
    test_squid_with_uri,
};
use dicer_rust_testing::test_data::SLICE_ASSIGNMENT_TEST_DATA;

fn parse_and_validate_simple_slice_assignment(
    proto: &SimpleSliceAssignmentP,
) -> Result<InternalSliceAssignment, Status> {
    let (slice, slice_assignment): (Slice, InternalSliceAssignment) =
        parse_simple_slice_assignment(proto)?;
    slice_assignment.check_external_invariants(
        slice.low_inclusive(),
        slice.high_exclusive(),
        // An arbitrary generation that is greater than all subslice annotation generations:
        Generation::new(
            Incarnation::new(42).unwrap(),
            UnixTimeVersion::from_unix_millis(9999).unwrap(),
        ),
    )?;
    Ok(slice_assignment)
}

#[test]
fn test_new_slice_assignment_invariant_enforcement() {
    // Test plan: Verify that `InternalSliceAssignment::new` fails with the correct errors when
    // called with malformed inputs.
    for test_case in &SLICE_ASSIGNMENT_TEST_DATA.invariant_test_cases {
        let expected_error: &str = test_case.expected_error.as_ref().unwrap();
        let err: Status = parse_and_validate_simple_slice_assignment(
            test_case.slice_assignment.as_ref().unwrap(),
        )
        .unwrap_err();
        assert_eq!(err.code(), Code::InvalidArgument);
        assert!(
            err.message().contains(expected_error),
            "Expected error message containing {:?}, but was {:?}",
            expected_error,
            err.message()
        );
    }
}

#[test]
fn test_from_proto_invariant_enforcement() {
    // Test plan: Verify that `InternalSliceAssignment::from_proto` fails with the correct errors
    // when called with malformed inputs.
    let resources = [Arc::new(test_squid_with_uri("Pod0")), Arc::new(test_squid_with_uri("Pod1"))];
    for test_case in &SLICE_ASSIGNMENT_TEST_DATA.proto_validity_test_cases {
        let expected_error: &str = test_case.expected_error.as_ref().unwrap();
        let err: Status = InternalSliceAssignment::from_proto(
            test_case.invalid_proto.as_ref().unwrap(),
            &resources,
        )
        .unwrap_err();
        assert_eq!(err.code(), Code::InvalidArgument);
        assert!(
            err.message().contains(expected_error),
            "Expected error message containing {:?}, but was {:?}",
            expected_error,
            err.message()
        );
    }
}

#[test]
fn test_empty_subslice_annotations_map_is_not_allowed() {
    // Test plan: Verify that construction of an `InternalSliceAssignment` with an empty map of
    // subslice annotations for one of the resources fails.
    let resource = Arc::new(test_squid_with_uri("Pod0"));
    let err: Status = InternalSliceAssignment::new(
        vec![resource.clone()].into(),
        Generation::new(
            Incarnation::new(42).unwrap(),
            UnixTimeVersion::from_unix_millis(90).unwrap(),
        ),
        /*primary_rate_load_opt=*/ None,
        HashMap::from([(resource.clone(), SliceMap::partial_builder().build().unwrap())]),
    )
    .unwrap_err();

    let expected_error: String =
        format!("Subslice annotations for resource {resource:?} should not be empty");
    assert_eq!(err.code(), Code::InvalidArgument);
    assert!(
        err.message().contains(&expected_error),
        "Expected error message containing {:?}, but was {:?}",
        expected_error,
        err.message()
    );
}

#[test]
fn test_round_trip() {
    // Test plan: Verify that an `InternalSliceAssignment` can be converted to its proto
    // representation and back. Verify for both manually constructed and randomly constructed
    // instances.

    // Manual test cases:
    for test_case in &SLICE_ASSIGNMENT_TEST_DATA.round_trip_test_cases {
        let (slice, slice_assignment): (Slice, InternalSliceAssignment) =
            parse_simple_slice_assignment(test_case).unwrap();
        verify_round_trip(&slice, &slice_assignment);
    }

    // Random test cases:
    for _ in 0..100 {
        // 100 trials.
        let (slice, subslices) = create_random_slice_with_subslices(10);
        let generation = Generation::new(
            Incarnation::new((random::<u32>() % 100) as i64).unwrap(),
            UnixTimeVersion::from_unix_millis(
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_millis() as i64,
            )
            .unwrap(),
        );
        verify_round_trip(&slice, &create_random_slice_assignment(&subslices, generation));
    }
}

/// Converts `slice_assignment` to its proto representation and converts the proto back to the
/// `InternalSliceAssignment` native type. Asserts that the re-constructed instance is equal to the
/// original one, and can be converted to the same proto message again.
fn verify_round_trip(slice: &Slice, slice_assignment: &InternalSliceAssignment) {
    let mut resource_builder = ResourceProtoBuilder::new();
    let slice_assignment_p: SliceAssignmentP = slice_assignment_to_proto(
        slice.low_inclusive(),
        slice.high_exclusive(),
        slice_assignment,
        &mut resource_builder,
    );
    let resources: Vec<Arc<Squid>> = resource_builder
        .to_protos()
        .into_iter()
        .map(|resource: SquidP| Arc::new(Squid::from_proto(&resource).unwrap()))
        .collect();
    let (slice_after_round_trip, slice_assignment_after_round_trip): (
        Slice,
        InternalSliceAssignment,
    ) = InternalSliceAssignment::from_proto(&slice_assignment_p, &resources).unwrap();

    assert_eq!(slice_after_round_trip, *slice);
    assert_eq!(&slice_assignment_after_round_trip, slice_assignment);
    assert_eq!(
        slice_assignment_to_proto(
            slice_after_round_trip.low_inclusive(),
            slice_after_round_trip.high_exclusive(),
            &slice_assignment_after_round_trip,
            &mut ResourceProtoBuilder::new()
        ),
        slice_assignment_p
    );
}
