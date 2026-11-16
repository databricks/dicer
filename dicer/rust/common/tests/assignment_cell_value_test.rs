use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use caching_rust_util::watch_cell::Versioned;
use dicer_rust_common::{
    Assignment,
    AssignmentCellValue,
    ClerkAssignment,
    ClerkAssignmentCellValue,
    Generation,
    Incarnation,
    SliceAssignment,
    SliceInfo,
    SliceMap,
    SliceSet,
    SliceletAssignmentCellValue,
    UnixTimeVersion,
};
use dicer_rust_external_types::{HighSliceKey, SliceKey};
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers::test_squid;
use dicer_rust_testing::test_slice_utils::identity;
/// A concise wrapper around `Assignment::builder` for test cases where the frozen status is
/// irrelevant.
fn new_builder(
    generation: Generation,
) -> Result<dicer_rust_common::AssignmentBuilder, tonic::Status> {
    Assignment::builder(/*is_frozen=*/ false, generation)
}

/// Creates a simple assignment with actual slice assignments.
fn create_simple_test_assignment() -> Assignment {
    build_assignment(vec![
        (
            SliceKey::MIN,
            identity("Bifur").into_high_key(),
            vec![test_squid(0).into(), test_squid(1).into()],
        ),
        (
            identity("Bifur"),
            HighSliceKey::Infinity,
            vec![test_squid(1).into(), test_squid(2).into()],
        ),
    ])
}

/// Builds an assignment from `(low, high, resources)` slice specs, in slice order, at a fixed
/// generation. Each slice's generation is set to the assignment generation.
fn build_assignment(slices: Vec<(SliceKey, HighSliceKey, Vec<Arc<Squid>>)>) -> Assignment {
    let incarnation = Incarnation::new(2).unwrap();
    let generation = Generation::new(incarnation, UnixTimeVersion::from_unix_millis(100).unwrap());
    let mut builder = new_builder(generation).unwrap();
    for (low_inclusive_key, high_exclusive_key, resources) in slices {
        builder = builder
            .add_slice_assignment(
                low_inclusive_key,
                high_exclusive_key,
                &resources,
                generation,
                /*primary_rate_load_opt=*/ None,
                /*subslice_annotations_by_resource=*/ HashMap::new(),
            )
            .unwrap();
    }
    builder.build().unwrap()
}

#[test]
fn test_clerk_assignment_cell_value_default() {
    // Test plan: verify the expected (empty) contents of a default
    // ClerkAssignmentCellValue.
    let cell_value = ClerkAssignmentCellValue::default();

    // Should have no assignment
    assert!(cell_value.assignment_opt().is_none());

    // Generation should be empty
    assert_eq!(cell_value.generation(), Generation::EMPTY);

    // Version should match generation (from Versioned trait)
    assert_eq!(cell_value.version(), Generation::EMPTY);
}

#[test]
fn test_slicelet_assignment_cell_value_default() {
    // Test plan: verify the expected (empty) contents of a default
    // SliceletAssignmentCellValue.
    let cell_value = SliceletAssignmentCellValue::default();

    // Should have no assignment
    assert!(cell_value.assignment_opt().is_none());

    // Generation should be empty
    assert_eq!(cell_value.generation(), Generation::EMPTY);

    // Version should match generation (from Versioned trait)
    assert_eq!(cell_value.version(), Generation::EMPTY);

    // Should have no assignment and Squid
    assert!(cell_value.assignment_and_squid_opt.is_none());

    // Should have no slices assigned to the Slicelet
    assert_eq!(&cell_value.slice_set, &SliceSet::empty());
}

#[test]
fn test_clerk_assignment_cell_value_with_assignment() {
    // Test plan: Verify the expected contents and routing metadata invariants of a
    // ClerkAssignmentCellValue with an assignment.
    let assignment = create_simple_test_assignment();
    let cell_value = ClerkAssignmentCellValue::new(assignment.clone());

    // Should have an assignment
    assert!(cell_value.assignment_opt().is_some());

    // Generation should match the assignment's generation
    assert_eq!(cell_value.generation(), assignment.generation());

    // Version should match generation (from Versioned trait)
    assert_eq!(cell_value.version(), assignment.generation());

    // The assignment should be the same
    let stored_assignment = cell_value.assignment_opt().unwrap();
    assert_eq!(*stored_assignment, create_simple_test_assignment());
    cell_value.check_invariants_for_test().unwrap();
}

#[test]
fn test_slice_info_debug() {
    // Test plan: Verify that SliceInfo debug output includes the physical nodes of each slice's
    // hash ring and its fallback squid by comparing it with the expected assignment resources.
    let cell_value = ClerkAssignmentCellValue::new(create_simple_test_assignment());
    let slice_info_map: &SliceMap<SliceInfo> =
        cell_value.clerk_assignment_opt().unwrap().slice_info_map();
    assert_eq!(
        format!("{:?}", slice_info_map.look_up(&SliceKey::MIN).value()),
        format!(
            "SliceInfo {{ two_level_hash_ring_opt: Some({:?}), fallback_squid_opt: Some({:?}) }}",
            vec![test_squid(0), test_squid(1)],
            test_squid(2)
        )
    );
    assert_eq!(
        format!("{:?}", slice_info_map.look_up(&identity("Bifur")).value()),
        format!(
            "SliceInfo {{ two_level_hash_ring_opt: Some({:?}), fallback_squid_opt: Some({:?}) }}",
            vec![test_squid(1), test_squid(2)],
            test_squid(0)
        )
    );
}

#[test]
fn test_slicelet_assignment_cell_value_with_assignment() {
    // Test plan: create a SliceletAssignmentCellValue with a simple assignment and a Squid,
    // and verify the expected contents.
    let assignment = create_simple_test_assignment();
    let squid = test_squid(0);
    let cell_value = SliceletAssignmentCellValue::new(assignment.clone(), squid.clone());
    assert!(cell_value.assignment_opt().is_some());
    assert!(cell_value.assignment_and_squid_opt.is_some());
    assert_eq!(&cell_value.slice_set, &assignment.get_slice_set_for_resource(&squid));

    // Generation should match the assignment's generation
    assert_eq!(cell_value.generation(), assignment.generation());

    // Version should match generation (from Versioned trait)
    assert_eq!(cell_value.version(), assignment.generation());

    // The assignment should be the same
    let stored_assignment = cell_value.assignment_opt().unwrap();
    assert_eq!(*stored_assignment, assignment);
}

#[test]
fn test_clerk_assignment_cell_value_computes_fallback_squid_for_slices_with_unassigned_resources() {
    // Test plan: Verify that two cell values built from the same assignment choose the same fallback
    // squid for each slice. Check that each fallback belongs to the assignment but is not assigned
    // to that slice, and that both cell values satisfy their invariants.
    let squid1: Arc<Squid> = Arc::new(test_squid(1));
    let squid2: Arc<Squid> = Arc::new(test_squid(2));
    let squid3: Arc<Squid> = Arc::new(test_squid(3));
    let squid4: Arc<Squid> = Arc::new(test_squid(4));
    let slices = || {
        vec![
            (
                SliceKey::MIN,
                identity("Bifur").into_high_key(),
                vec![Arc::clone(&squid1), Arc::clone(&squid2)],
            ),
            (
                identity("Bifur"),
                identity("Kili").into_high_key(),
                vec![Arc::clone(&squid3), Arc::clone(&squid4)],
            ),
            (
                identity("Kili"),
                identity("Nori").into_high_key(),
                vec![Arc::clone(&squid1), Arc::clone(&squid3)],
            ),
            (identity("Nori"), HighSliceKey::Infinity, vec![Arc::clone(&squid4)]),
        ]
    };
    let cell_value: ClerkAssignmentCellValue =
        ClerkAssignmentCellValue::new(build_assignment(slices()));
    let other_cell_value: ClerkAssignmentCellValue =
        ClerkAssignmentCellValue::new(build_assignment(slices()));
    let clerk_assignment: &ClerkAssignment = cell_value.clerk_assignment_opt().unwrap();
    let other_clerk_assignment: &ClerkAssignment = other_cell_value.clerk_assignment_opt().unwrap();

    let assignment_resources: HashSet<Arc<Squid>> =
        [Arc::clone(&squid1), Arc::clone(&squid2), Arc::clone(&squid3), Arc::clone(&squid4)]
            .into_iter()
            .collect();
    for key in [SliceKey::MIN, identity("Bifur"), identity("Kili"), identity("Nori")] {
        let slice_assignment: SliceAssignment = clerk_assignment.assignment().look_up(&key);
        let slice_resources: HashSet<&Squid> = slice_assignment
            .resources_set()
            .iter()
            .map(|squid: &Arc<Squid>| &**squid)
            .collect();

        // Every slice here has an unassigned resource, so it has a fallback squid.
        let slice_info: &SliceInfo = clerk_assignment.slice_info_map().look_up(&key).value();
        let fallback_squid: Arc<Squid> = slice_info
            .fallback_squid_opt()
            .cloned()
            .expect("slice with an unassigned resource has a fallback squid");
        // The fallback squid is one of the assignment's resources, not assigned to the slice.
        assert!(assignment_resources.contains(&fallback_squid));
        assert!(!slice_resources.contains(&*fallback_squid));
        // The fallback squid is chosen deterministically: a second cell value picks the same one.
        let other_slice_info: &SliceInfo = other_clerk_assignment
            .slice_info_map()
            .look_up(&key)
            .value();
        assert_eq!(other_slice_info.fallback_squid_opt().cloned(), Some(fallback_squid));
    }

    cell_value.check_invariants_for_test().unwrap();
    other_cell_value.check_invariants_for_test().unwrap();
}

#[test]
fn test_clerk_assignment_cell_value_no_fallback_squid_when_no_unassigned_resource() {
    // Test plan: Verify that ClerkAssignmentCellValue computes no fallback squid for any slice when
    // every slice is assigned to the same single resource, so the assignment has no resource that
    // is unassigned to a slice. Verify this by building such an assignment, asserting every slice
    // has no fallback squid, and that the invariants hold.
    let squid1: Arc<Squid> = Arc::new(test_squid(1));
    let assignment: Assignment = build_assignment(vec![
        (SliceKey::MIN, identity("Bifur").into_high_key(), vec![Arc::clone(&squid1)]),
        (identity("Bifur"), identity("Nori").into_high_key(), vec![Arc::clone(&squid1)]),
        (identity("Nori"), HighSliceKey::Infinity, vec![Arc::clone(&squid1)]),
    ]);
    let cell_value: ClerkAssignmentCellValue = ClerkAssignmentCellValue::new(assignment);
    let clerk_assignment: &ClerkAssignment = cell_value.clerk_assignment_opt().unwrap();

    for key in [SliceKey::MIN, identity("Bifur"), identity("Nori")] {
        let slice_info: &SliceInfo = clerk_assignment.slice_info_map().look_up(&key).value();
        assert!(slice_info.fallback_squid_opt().is_none());
    }

    cell_value.check_invariants_for_test().unwrap();
}
