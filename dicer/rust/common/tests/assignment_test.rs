use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use rand::RngCore;
use tokio::time::Instant;
use tonic::{Code, Status};
use dicer_common_proto_common_proto::dicer::DiffAssignmentP;
use dicer_common_test_proto_test_data_proto::dicer::test::assignment_test_data_p::{
    DiffUnusedP,
    FromDiffWithLooseIncarnationDataP,
    ToDiffWithHigherDiffGenerationTestCaseP,
};
use dicer_common_test_proto_test_data_proto::dicer::test::simple_diff_assignment_p::SimpleSliceAssignmentP;
use dicer_common_test_proto_test_data_proto::dicer::test::SimpleDiffAssignmentP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SliceP;
use dicer_rust_common::{
    slice_from_proto,
    Assignment,
    AssignmentBuilder,
    DiffAssignment,
    Generation,
    Incarnation,
    SliceAssignment,
    UnixTimeVersion,
};
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers::{
    parse_simple_diff_assignment,
    test_squid,
    test_squid_with_uri,
    test_squid_with_uri_and_creation_time_offset,
};
use dicer_rust_testing::test_data::ASSIGNMENT_TEST_DATA;
use dicer_rust_testing::test_slice_utils::{identity, identity_bytes, identity_u64};

/// A concise wrapper around `Assignment::builder` for test cases where the frozen status is
/// irrelevant.
fn new_builder(generation: Generation) -> Result<AssignmentBuilder, Status> {
    Assignment::builder(/*is_frozen=*/ false, generation)
}

/// A wrapper around [`parse_simple_diff_assignment`] that converts the diff assignment to an actual
/// assignment.
fn parse_simple_assignment(proto: &SimpleDiffAssignmentP) -> Result<Assignment, Status> {
    Assignment::from_diff(/*known_assignment_opt=*/ None, parse_simple_diff_assignment(proto)?)
}

/// Asserts that `slice_assignment` has the given contents.
fn assert_slice_assignment_has(
    slice_assignment: SliceAssignment,
    low_inclusive_key: &SliceKey,
    high_exclusive_key: HighSliceKeyRef,
    resources: &[&Squid],
    generation: Generation,
    primary_rate_load: Option<f64>,
) {
    assert_eq!(slice_assignment.low_inclusive_key(), low_inclusive_key);
    assert_eq!(slice_assignment.high_exclusive_key(), high_exclusive_key);

    // Verify that to_slice() returns a Slice with the same bounds
    let slice: Slice = slice_assignment.to_slice();
    let expected_slice: Slice =
        Slice::new(low_inclusive_key.to_owned(), high_exclusive_key.to_owned()).unwrap();
    assert_eq!(slice, expected_slice);

    assert_eq!(slice_assignment.len(), resources.len());
    assert_eq!(slice_assignment.is_empty(), resources.is_empty());
    for (i, &squid) in resources.iter().enumerate() {
        assert_eq!(slice_assignment.get(i), Some(squid));
    }
    assert_eq!(slice_assignment.generation(), generation);
    assert_eq!(slice_assignment.primary_rate_load(), primary_rate_load);
}

#[test]
fn test_assignment() -> Result<(), Status> {
    // Test plan: Verify that assignment lookups return the expected resources and that
    // slice_assignments() yields slices in increasing key order, disjoint and covering the full
    // key space, by comparing the complete iteration with the expected slice boundaries.
    let resources: [Arc<Squid>; 3] =
        [Arc::new(test_squid(0)), Arc::new(test_squid(1)), Arc::new(test_squid(2))];
    let incarnation = Incarnation::new(2)?;
    let generation = Generation::new(incarnation, UnixTimeVersion::from_unix_millis(57)?);
    let assignment: Assignment = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Bifur").into_high_key(),
            &[resources[0].clone(), resources[1].clone()],
            Generation::new(incarnation, UnixTimeVersion::from_unix_millis(42)?),
            /*primary_rate_load_opt=*/ Some(15.7),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Bifur"),
            identity("Nori").into_high_key(),
            &[resources[1].clone(), resources[2].clone()],
            Generation::new(incarnation, UnixTimeVersion::from_unix_millis(47)?),
            /*primary_rate_load_opt=*/ Some(35.2),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Nori"),
            identity("Ori").into_high_key(),
            &[resources[2].clone(), resources[0].clone()],
            Generation::new(incarnation, UnixTimeVersion::from_unix_millis(51)?),
            /*primary_rate_load_opt=*/ Some(25.9),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Ori"),
            HighSliceKey::Infinity,
            &[resources[2].clone()],
            Generation::new(incarnation, UnixTimeVersion::from_unix_millis(55)?),
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .build()?;

    // Test the assignment's properties.
    assert!(!assignment.is_frozen());
    assert_eq!(assignment.generation(), generation);
    assert_eq!(assignment.len(), 4);
    assert!(!assignment.is_empty());

    let slices: Vec<Slice> = assignment
        .slice_assignments()
        .map(|slice_assignment: SliceAssignment| slice_assignment.to_slice())
        .collect();
    assert_eq!(
        slices,
        vec![
            Slice::new(SliceKey::MIN, identity("Bifur").into_high_key())?,
            Slice::new(identity("Bifur"), identity("Nori").into_high_key())?,
            Slice::new(identity("Nori"), identity("Ori").into_high_key())?,
            Slice::new(identity("Ori"), HighSliceKey::Infinity)?,
        ]
    );

    // Look up some keys.
    assert_slice_assignment_has(
        assignment.look_up(&SliceKey::MIN),
        &SliceKey::MIN,
        identity("Bifur").as_high_key_ref(),
        &[&resources[0], &resources[1]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(42)?),
        /*primary_rate_load=*/ Some(15.7),
    );
    assert_slice_assignment_has(
        assignment.look_up(&identity("Balin")),
        &SliceKey::MIN,
        identity("Bifur").as_high_key_ref(),
        &[&resources[0], &resources[1]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(42)?),
        /*primary_rate_load=*/ Some(15.7),
    );
    assert_slice_assignment_has(
        assignment.look_up(&identity("Bifur")),
        &identity("Bifur"),
        identity("Nori").as_high_key_ref(),
        &[&resources[1], &resources[2]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(47)?),
        /*primary_rate_load=*/ Some(35.2),
    );
    assert_slice_assignment_has(
        assignment.look_up(&identity("Bombur")),
        &identity("Bifur"),
        identity("Nori").as_high_key_ref(),
        &[&resources[1], &resources[2]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(47)?),
        /*primary_rate_load=*/ Some(35.2),
    );
    assert_slice_assignment_has(
        assignment.look_up(&identity("Nori")),
        &identity("Nori"),
        identity("Ori").as_high_key_ref(),
        &[&resources[2], &resources[0]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(51)?),
        /*primary_rate_load=*/ Some(25.9),
    );
    assert_slice_assignment_has(
        assignment.look_up(&identity("Ori")),
        &identity("Ori"),
        HighSliceKeyRef::Infinity,
        &[&resources[2]],
        Generation::new(incarnation, UnixTimeVersion::from_unix_millis(55)?),
        /*primary_rate_load=*/ None,
    );

    Ok(())
}

#[test]
fn test_assignment_invalid() -> Result<(), Status> {
    // Test plan: verify that the expected errors are returned for various
    // invalid assignments.
    let generation = Generation::new(Incarnation::new(2)?, UnixTimeVersion::from_unix_millis(42)?);
    let resources = [Arc::new(test_squid(0))];

    // Empty assignment generation.
    let empty_generation_error: Status = new_builder(Generation::EMPTY).unwrap_err();
    assert_eq!(empty_generation_error.code(), Code::InvalidArgument);
    assert!(empty_generation_error
        .message()
        .contains("Assignment must have non-empty generation"));

    // Start the first Slice with something other than SliceKey::MIN.
    let start_error: Status = new_builder(generation)?
        .add_slice_assignment(
            identity("Bifur"),
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(start_error.code(), Code::InvalidArgument);
    assert!(start_error
        .message()
        .contains("First entry must start at \"\""));

    // Empty assignment.
    let empty_error: Status = new_builder(generation)?.build().unwrap_err();
    assert_eq!(empty_error.code(), Code::InvalidArgument);
    assert!(empty_error.message().contains("Must not be empty"));

    // Supply empty slice.
    let empty_slice_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Ori").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Ori"),
            identity("Ori").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(empty_slice_error.code(), Code::InvalidArgument);
    assert!(empty_slice_error
        .message()
        .contains("Slice must not be empty"));

    // Supply slice with high key less than low key.
    let empty_slice_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Nori").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Ori"),
            identity("Nori").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(empty_slice_error.code(), Code::InvalidArgument);
    assert!(empty_slice_error
        .message()
        .contains("Slice must not be empty"));

    // Supply consecutive slices with a gap between them.
    let disordered_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Balin").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Bifur"),
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(disordered_error.code(), Code::InvalidArgument);
    assert!(disordered_error
        .message()
        .contains("Gap between successive entries"));

    // Supply overlapping slices.
    let disordered_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Bifur").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .add_slice_assignment(
            identity("Balin"),
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(disordered_error.code(), Code::InvalidArgument);
    assert!(disordered_error
        .message()
        .contains("Overlap between successive entries"));

    // Supply slices that don't extend all the way to the end of the key space.
    let disordered_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            identity("Bifur").into_high_key(),
            &resources,
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?
        .build()
        .unwrap_err();
    assert_eq!(disordered_error.code(), Code::InvalidArgument);
    assert!(disordered_error
        .message()
        .contains("Last entry must have Infinity upper bound"));

    // Supply an empty list of resources for a slice.
    let no_resources_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &[],
            generation,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(no_resources_error.code(), Code::InvalidArgument);
    assert!(no_resources_error
        .message()
        .contains("The assigned resources must be non-empty"));

    // Supply an empty generation for a slice.
    let empty_generation_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            Generation::EMPTY,
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(empty_generation_error.code(), Code::InvalidArgument);
    assert!(empty_generation_error
        .message()
        .contains("Slice assignment must have non-empty generation"));

    // Different incarnation for slice.
    let different_incarnation_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            Generation::new(Incarnation::new(3)?, UnixTimeVersion::from_unix_millis(42)?),
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(different_incarnation_error.code(), Code::InvalidArgument);
    assert!(different_incarnation_error
        .message()
        .contains("must be in the same incarnation as the assignment generation"));

    // Slice generation greater than assignment generation.
    let greater_generation_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            // A generation greater than the assignment generation.
            Generation::new(Incarnation::new(2)?, UnixTimeVersion::from_unix_millis(43)?),
            /*primary_rate_load_opt=*/ None,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(greater_generation_error.code(), Code::InvalidArgument);
    assert!(greater_generation_error
        .message()
        .contains("must be less than or equal to the assignment generation"));

    // Supply NaN as a load measurement.
    let nan_load_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ Some(f64::NAN),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(nan_load_error.code(), Code::InvalidArgument);
    assert!(nan_load_error.message().contains("must be a number"));

    // Supply a negative load measurement.
    let negative_load_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ Some(-1.0),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(negative_load_error.code(), Code::InvalidArgument);
    assert!(negative_load_error
        .message()
        .contains("must be non-negative"));

    // Supply an infinite load measurement.
    let infinite_load_error: Status = new_builder(generation)?
        .add_slice_assignment(
            SliceKey::MIN,
            HighSliceKey::Infinity,
            &resources,
            generation,
            /*primary_rate_load_opt=*/ Some(f64::INFINITY),
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )
        .unwrap_err();
    assert_eq!(infinite_load_error.code(), Code::InvalidArgument);
    assert!(infinite_load_error.message().contains("must be finite"));

    Ok(())
}

#[test]
fn test_randomized_lookups() -> Result<(), Status> {
    // Test plan: create assignments with various lengths resources and look up
    // a bunch of random keys in each. Make sure the expected resources are
    // returned, which we determine by producing assignments deterministically:
    // the ith Slice starts at key `i * 1024` and contains the resource
    // `test_squid(i % 32)`.
    let start_time = Instant::now();
    let lens = [1, 2, 15, 33, 64, 1023, 2049, 4096];
    let incarnation = Incarnation::new(2)?;
    let assignments: Vec<Assignment> = lens
        .map(|len| {
            let mut builder = new_builder(Generation::new(
                incarnation,
                UnixTimeVersion::from_unix_millis(409600)?,
            ))?;
            for i in 0..len {
                let low_key: SliceKey = if i == 0 {
                    SliceKey::MIN.clone()
                } else {
                    identity_u64(i as u64 * 1024)
                };
                let high_key: HighSliceKey = if i == len - 1 {
                    HighSliceKey::Infinity
                } else {
                    identity_u64((i + 1) as u64 * 1024).into_high_key()
                };
                let resource: Squid = test_squid(i % 32);
                let generation = Generation::new(
                    incarnation,
                    UnixTimeVersion::from_unix_millis(i as i64 * 100)?,
                );
                let primary_rate_load = if i.is_multiple_of(2) {
                    Some(i as f64 * 10.5)
                } else {
                    None
                };
                builder = builder.add_slice_assignment(
                    low_key,
                    high_key,
                    &[Arc::new(resource)],
                    generation,
                    primary_rate_load,
                    /*subslice_annotations_by_resource=*/ HashMap::new(),
                )?
            }
            builder.build()
        })
        .into_iter()
        .collect::<Result<Vec<Assignment>, Status>>()?;
    let creation_time = Instant::now();
    for assignment in assignments {
        let rng = &mut rand::rng();
        for _ in 0..1000 {
            // Pick a Slice which we will look up, and determine the expected
            // contents of the corresponding `SliceAssignment`.
            let len: u32 = assignment.len() as u32;
            let i: u32 = rng.next_u32() % len;
            let low_inclusive_key: SliceKey = if i == 0 {
                SliceKey::MIN.clone()
            } else {
                identity_u64(i as u64 * 1024)
            };
            let high_exclusive_key: HighSliceKey = if i == len - 1 {
                HighSliceKey::Infinity
            } else {
                identity_u64((i + 1) as u64 * 1024).into_high_key()
            };
            let resource: Squid = test_squid(i % 32);
            let expected_generation =
                Generation::new(incarnation, UnixTimeVersion::from_unix_millis(i as i64 * 100)?);
            let expected_primary_rate_load = if i.is_multiple_of(2) {
                Some(i as f64 * 10.5)
            } else {
                None
            };

            // Look up the Slice using its low_inclusive_key, and a random key in
            // the range of the Slice.
            let slice_assignment1: SliceAssignment = assignment.look_up(&low_inclusive_key);
            let slice_assignment2: SliceAssignment =
                assignment.look_up(&identity_u64(i as u64 * 1024 + rng.next_u64() % 1024));
            assert_slice_assignment_has(
                slice_assignment1,
                &low_inclusive_key,
                high_exclusive_key.as_ref(),
                &[&resource],
                expected_generation,
                expected_primary_rate_load,
            );
            assert_slice_assignment_has(
                slice_assignment2,
                &low_inclusive_key,
                high_exclusive_key.as_ref(),
                &[&resource],
                expected_generation,
                expected_primary_rate_load,
            );
        }
    }
    let end_time = Instant::now();
    println!(
        "Creation time: {:?}, look-up time: {:?}",
        creation_time - start_time,
        end_time - creation_time
    );
    Ok(())
}

#[test]
fn test_round_trip_with_full_diff() {
    // Test plan: Verify that an Assignment can be converted to a DiffAssignmentP and back without
    // loss in fidelity, where the diff is a full diff.
    for test_case in &ASSIGNMENT_TEST_DATA.full_diff_round_trip_test_cases {
        let assignment: Assignment = parse_simple_assignment(test_case).unwrap();
        let diff_assignment: DiffAssignment = assignment.to_diff(Generation::EMPTY).unwrap();
        let diff_assignment_p: DiffAssignmentP = diff_assignment.to_proto();
        let diff_assignment_from_proto = DiffAssignment::from_proto(&diff_assignment_p).unwrap();
        let assignment_after_round_trip = Assignment::from_diff(
            /*known_assignment_opt=*/ None,
            diff_assignment_from_proto.clone(),
        )
        .unwrap();

        assert_eq!(assignment_after_round_trip, assignment);
        assert_eq!(diff_assignment_from_proto, diff_assignment);
        assert_eq!(diff_assignment_from_proto.to_proto(), diff_assignment_p);
    }
}

#[test]
fn test_round_trip_with_partial_diff() {
    // Test plan: Verify that an Assignment can be converted to a DiffAssignmentP and back without
    // loss in fidelity, where the diff is a partial diff.
    let test_assignments: Vec<Assignment> = ASSIGNMENT_TEST_DATA
        .partial_diff_round_trip_test_assignments
        .iter()
        .map(|assignment: &SimpleDiffAssignmentP| parse_simple_assignment(assignment).unwrap())
        .collect();

    // Test round trips for every possible combination of the test assignments.
    for known_assignment in &test_assignments {
        for assignment in &test_assignments {
            if known_assignment.generation() < assignment.generation() {
                // If `known_assignment` has a generation less than that of `assignment`, then
                // verify that `assignment` can be serialized and deserialized correctly based on
                // the difference with `known_assignment`.

                // Test round-trip via diff.
                let diff_assignment: DiffAssignment =
                    Assignment::to_diff(assignment, known_assignment.generation()).unwrap();
                let assignment_after_diff_round_trip =
                    Assignment::from_diff(Some(known_assignment), diff_assignment.clone()).unwrap();
                assert_eq!(assignment_after_diff_round_trip, *assignment);

                // Test round-trip via diff proto.
                let diff_assignment_p: DiffAssignmentP = diff_assignment.to_proto();
                let diff_assignment_from_proto =
                    DiffAssignment::from_proto(&diff_assignment_p).unwrap();
                let assignment_after_proto_round_trip =
                    Assignment::from_diff(Some(known_assignment), diff_assignment_from_proto)
                        .unwrap();
                assert_eq!(assignment_after_proto_round_trip, *assignment);
            } else {
                // When the known assignment has a generation greater than or equal to that of the
                // diff assignment, `from_diff` should report that the diff is unused and report the
                // difference in generation.
                let diff: DiffAssignment = assignment.to_diff(Generation::EMPTY).unwrap();
                let error: Status =
                    Assignment::from_diff(Some(known_assignment), diff).unwrap_err();

                let expected_error: &str =
                    if known_assignment.generation() == assignment.generation() {
                        "The diff assignment is already known"
                    } else {
                        "Diff is too stale"
                    };
                assert_eq!(error.message(), expected_error);
            }
        }
    }
}

#[test]
fn test_from_diff_loose_incarnation() {
    // Test plan: Verify that `Assignment::from_diff` works correctly in an unusual case that
    // arises when the supplier of the diffs believes that an assignment is in a non-loose
    // incarnation but the recipient believes that it is loose. The recipient can accept the diff,
    // but only if it contains all Slices. See <internal bug> for motivation of this scenario.
    let test_data: &FromDiffWithLooseIncarnationDataP = ASSIGNMENT_TEST_DATA
        .from_diff_with_loose_incarnation_data
        .as_ref()
        .unwrap();
    for loose_incarnation in &test_data.loose_incarnations {
        // Sets the incarnation in all generations within the given assignment proto to
        // `loose_incarnation`.
        let populate_incarnation =
            |mut assignment: SimpleDiffAssignmentP| -> SimpleDiffAssignmentP {
                assignment.generation.as_mut().unwrap().incarnation = Some(*loose_incarnation);
                for slice_assignment in &mut assignment.slice_assignments {
                    slice_assignment.generation.as_mut().unwrap().incarnation =
                        Some(*loose_incarnation);
                }
                assignment
            };

        // Parse the initial and successor assignments.
        let initial_assignment: Assignment = parse_simple_assignment(&populate_incarnation(
            test_data.initial_assignment.clone().unwrap(),
        ))
        .unwrap();
        let next_assignment: Assignment = parse_simple_assignment(&populate_incarnation(
            test_data.next_assignment.clone().unwrap(),
        ))
        .unwrap();

        // Simulate an assignment supplier that believes the assignment is non-loose and would
        // produce an actual diff by modifying the resulting proto (which doesn't resemble a diff
        // because it does not define the `diff_generation` field) to resemble a diff (by setting a
        // `diff_generation` field).
        let diff_proto: DiffAssignmentP = next_assignment
            .to_diff(initial_assignment.generation())
            .unwrap()
            .to_proto();
        assert_eq!(diff_proto.diff_generation, None);
        let mut hacked_diff_proto: DiffAssignmentP = diff_proto.clone();
        hacked_diff_proto.diff_generation = Some(initial_assignment.generation().to_proto());

        // Now attempt to round trip, using both the original diff proto and the modified diff
        // proto.
        let roundtrip = Assignment::from_diff(
            Some(&initial_assignment),
            DiffAssignment::from_proto(&diff_proto).unwrap(),
        )
        .unwrap();
        let modified_roundtrip = Assignment::from_diff(
            Some(&initial_assignment),
            DiffAssignment::from_proto(&hacked_diff_proto).unwrap(),
        )
        .unwrap();
        assert_eq!(roundtrip, next_assignment);
        assert_eq!(modified_roundtrip, next_assignment);
    }
}

#[test]
fn test_from_diff_failure() {
    // Test plan: Verify the failure cases for `Assignment::from_diff`.
    for test_case in &ASSIGNMENT_TEST_DATA.from_diff_failure_test_cases {
        let known_assignment: Option<Assignment> =
            test_case
                .known_assignment
                .as_ref()
                .map(|known_assignment: &SimpleDiffAssignmentP| {
                    parse_simple_assignment(known_assignment).unwrap()
                });
        let diff: DiffAssignment =
            parse_simple_diff_assignment(test_case.diff.as_ref().unwrap()).unwrap();

        let expected_error: &str = match test_case.expected_diff_unused() {
            DiffUnusedP::NoKnown => "Can't use diff because there is no known assignment",
            DiffUnusedP::TooStaleDiff => "Diff is too stale",
            DiffUnusedP::DiffMatchesKnown => "The diff assignment is already known",
            DiffUnusedP::TooStaleKnown => "Known assignment too stale to make use of diff",
            DiffUnusedP::Inconsistency => "Inconsistency",
            DiffUnusedP::Unspecified => panic!("Invalid DiffUnusedP"),
        };

        let error: Status =
            Assignment::from_diff(known_assignment.as_ref(), diff.clone()).unwrap_err();
        assert!(
            error.message().contains(expected_error),
            "Expected error message {:?} to contain {:?}",
            error.message(),
            expected_error
        );
    }
}

#[test]
fn test_to_diff_with_higher_diff_generation() {
    // Test plan: Verify that `Assignment::to_diff` fails when the diff generation is higher than
    // the assignment generation.
    let test_case: &ToDiffWithHigherDiffGenerationTestCaseP = ASSIGNMENT_TEST_DATA
        .to_diff_with_higher_diff_generation_test_case
        .as_ref()
        .unwrap();
    let assignment: Assignment =
        parse_simple_assignment(test_case.assignment.as_ref().unwrap()).unwrap();
    let diff_generation: Generation =
        Generation::from_proto(&test_case.diff_generation.unwrap()).unwrap();

    let error: Status = assignment.to_diff(diff_generation).unwrap_err();
    assert_eq!(error.code(), Code::InvalidArgument);
    assert!(error
        .message()
        .contains(test_case.expected_error.as_ref().unwrap()));
}

#[test]
fn test_assigned_resources() {
    // Test plan: Verify that `assigned_resources` returns the expected values for shared test-data
    // assignments.
    for test_case in &ASSIGNMENT_TEST_DATA.assigned_resources_test_cases {
        let assignment: Assignment =
            parse_simple_assignment(test_case.assignment.as_ref().unwrap()).unwrap();
        let expected_assigned_resources: HashSet<Arc<Squid>> = test_case
            .expected_assigned_resources
            .iter()
            .map(|resource| Arc::new(test_squid_with_uri(resource)))
            .collect();
        assert_eq!(assignment.assigned_resources(), expected_assigned_resources);
    }
}

#[test]
fn test_is_assigned_key() {
    // Test plan: Verify that `is_assigned_key` returns the correct results.
    for test_case in &ASSIGNMENT_TEST_DATA.is_assigned_key_test_cases {
        let assignment: Assignment =
            parse_simple_assignment(test_case.assignment.as_ref().unwrap()).unwrap();
        for call in &test_case.calls {
            let slice_key: SliceKey = identity_bytes(call.slice_key.as_ref().unwrap().as_bytes());
            let resource: Squid = test_squid_with_uri_and_creation_time_offset(
                call.resource.as_ref().unwrap(),
                call.resource_creation_time_offset.unwrap_or_default(),
            );

            assert_eq!(
                assignment.is_assigned_key(&slice_key, &resource),
                call.expected_result.unwrap()
            );
        }
    }
}

/// A helper function that creates an `Assignment` given a generation and a non-empty, disjoint
/// and ordered vector of `SliceAssignmentP`s.
fn create_assignment(
    generation: Generation,
    entries: &[SimpleSliceAssignmentP],
) -> Result<Assignment, Status> {
    let mut builder: AssignmentBuilder =
        Assignment::builder(/*is_frozen=*/ false, generation)?;

    if entries.is_empty() {
        return Err(Status::invalid_argument("Entries must not be empty"));
    }

    for slice_assignment_proto in entries {
        let slice = slice_from_proto(
            slice_assignment_proto
                .slice
                .as_ref()
                .ok_or_else(|| Status::invalid_argument("Missing slice in slice assignment"))?,
        )?;

        // Preallocate the vector
        let mut resources: Vec<Arc<Squid>> =
            Vec::with_capacity(slice_assignment_proto.resources.len());
        for resource in &slice_assignment_proto.resources {
            resources.push(Arc::new(test_squid_with_uri(resource)));
        }

        let slice_gen =
            Generation::from_proto(slice_assignment_proto.generation.as_ref().ok_or_else(
                || Status::invalid_argument("Missing generation in slice assignment"),
            )?)?;

        builder = builder.add_slice_assignment(
            slice.low_inclusive().clone(),
            slice.high_exclusive().to_owned(),
            &resources,
            slice_gen,
            slice_assignment_proto.primary_rate_load_opt,
            /*subslice_annotations_by_resource=*/ HashMap::new(),
        )?;
    }

    builder.build()
}

#[test]
fn test_get_assigned_slice_assignments() {
    // Test plan: Verify that Assignment.get_assigned_slice_assignments returns the
    // expected values for various assignments and resources.
    for test_case in &ASSIGNMENT_TEST_DATA.get_assigned_slice_assignments_test_cases {
        let test_assignment = test_case
            .assignment
            .as_ref()
            .expect("Missing assignment in test case");
        let generation = Generation::from_proto(
            test_assignment
                .generation
                .as_ref()
                .expect("Missing generation in test assignment"),
        )
        .unwrap();

        let assignment: Assignment =
            create_assignment(generation, &test_assignment.slice_assignments)
                .expect("Failed to create assignment from test proto");

        for call in &test_case.calls {
            let resource: Squid = test_squid_with_uri(call.resource.as_ref().unwrap());

            let slice_assignments: Vec<SliceAssignment> =
                assignment.get_assigned_slice_assignments(&resource);

            // Verify the number of returned assignments matches expected
            assert_eq!(
                slice_assignments.len(),
                call.expected_slice_assignments.len(),
                "Resource {} expected {} slice assignments, but got {}",
                call.resource.as_ref().unwrap(),
                call.expected_slice_assignments.len(),
                slice_assignments.len()
            );

            // Compare each slice assignment in order
            for (i, (actual, expected_proto)) in slice_assignments
                .iter()
                .zip(call.expected_slice_assignments.iter())
                .enumerate()
            {
                let expected_slice: Slice =
                    slice_from_proto(expected_proto.slice.as_ref().unwrap()).unwrap();
                let expected_generation: Generation =
                    Generation::from_proto(expected_proto.generation.as_ref().unwrap()).unwrap();
                let expected_primary_rate_load: Option<f64> = expected_proto.primary_rate_load_opt;

                let actual_slice: Slice = Slice::new(
                    actual.low_inclusive_key().clone(),
                    actual.high_exclusive_key().to_owned(),
                )
                .unwrap();

                // Compare slice bounds
                assert_eq!(
                    actual_slice,
                    expected_slice,
                    "Test assignment {:?}\nresource {}, slice #{} has incorrect bounds",
                    assignment,
                    call.resource.as_ref().unwrap(),
                    i
                );

                // Compare generation
                assert_eq!(
                    actual.generation(),
                    expected_generation,
                    "Test assignment {:?}\nresource {}, slice #{} has incorrect generation",
                    assignment,
                    call.resource.as_ref().unwrap(),
                    i
                );

                // Compare primary rate load
                assert_eq!(
                    actual.primary_rate_load(),
                    expected_primary_rate_load,
                    "Test assignment {:?}\nresource {}, slice #{} has incorrect primary rate load",
                    assignment,
                    call.resource.as_ref().unwrap(),
                    i
                );

                // Verify resources contain the expected resource names
                let expected_resources: Vec<Squid> = expected_proto
                    .resources
                    .iter()
                    .map(|resource: &String| test_squid_with_uri(resource))
                    .collect();

                let actual_resources: Vec<Squid> = actual
                    .resources()
                    .iter()
                    .map(|resource: &Arc<Squid>| resource.as_ref().clone())
                    .collect();

                assert_eq!(
                    actual_resources,
                    expected_resources,
                    "Test assignment {:?}\nresource {}, slice #{} has incorrect resources: 
                    expected {:?}, got {:?}",
                    assignment,
                    call.resource.as_ref().unwrap(),
                    i,
                    expected_resources,
                    actual_resources
                );
            }
        }
    }
}

#[test]
fn test_get_slice_set_for_resource() {
    // Test plan: Verify that `get_slice_set_for_resource` returns the expected slices.
    for test_case in &ASSIGNMENT_TEST_DATA.get_slice_set_for_resource_test_cases {
        let assignment: Assignment =
            parse_simple_assignment(test_case.assignment.as_ref().unwrap()).unwrap();
        for call in &test_case.calls {
            let resource: Squid = test_squid_with_uri_and_creation_time_offset(
                call.resource.as_ref().unwrap(),
                call.resource_creation_time_offset.unwrap_or_default(),
            );
            let expected_slices: Vec<Slice> = call
                .expected_slices
                .iter()
                .map(|slice_proto: &SliceP| slice_from_proto(slice_proto).unwrap())
                .collect();
            let actual_slices: Vec<Slice> = assignment
                .get_slice_set_for_resource(&resource)
                .into_iter()
                .collect();
            assert_eq!(actual_slices, expected_slices);
        }
    }
}
