use std::collections::{BTreeMap, HashMap};

use dicer_common_proto_dpage_proto_library::dicer::{
    AssignmentViewP,
    GenerationViewP,
    ResourceViewP,
};
use dicer_common_test_proto_test_data_proto::dicer::test::SimpleDiffAssignmentP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SliceP;
use dicer_rust_common::{
    generation_to_view_proto,
    get_assignment_view_proto,
    slice_from_proto,
    Assignment,
    Generation,
};
use dicer_rust_external_types::{Slice, SliceKey};
use dicer_rust_friend::Squid;
use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;
use dicer_rust_testing::data_helpers::{
    parse_simple_diff_assignment,
    test_squid_with_uri,
};
use dicer_rust_testing::test_data::DPAGE_VIEW_HELPERS_TEST_DATA;
use uuid::Uuid;

/// A wrapper around [`parse_simple_assignment`] that converts the diff assignment to an actual
/// assignment.
fn parse_simple_assignment(proto: &SimpleDiffAssignmentP) -> Assignment {
    Assignment::from_diff(
        /*known_assignment_opt=*/ None,
        parse_simple_diff_assignment(proto).unwrap(),
    )
    .unwrap()
}

/// Asserts that the assignment view proto matches the expected output.
fn assert_assignment_view_matches(
    result: AssignmentViewP,
    expected: &AssignmentViewP,
    description: &str,
) {
    for resource in &result.resources {
        // We intentionally do not compare UUID and creation time for SQUIDs: the shared
        // test fixture names resources by address rather than specifying full SQUID identity.
        // Therefore, we only ensure that UUID and creation time are present and valid.
        let uuid = resource
            .uuid
            .as_ref()
            .unwrap_or_else(|| panic!("missing resource uuid: {description}"));
        uuid.parse::<Uuid>()
            .map_err(|error| format!("invalid resource uuid: {description}: {error}"))
            .expect("failed to parse resource uuid");

        let creation_time_str = resource
            .creation_time_str
            .as_ref()
            .unwrap_or_else(|| panic!("missing resource creation_time_str: {description}"));
        chrono::DateTime::parse_from_rfc3339(creation_time_str)
            .map_err(|error| format!("invalid resource creation_time_str: {description}: {error}"))
            .expect("failed to parse resource creation_time_str");
    }

    let comparable_result = AssignmentViewP {
        generation: result.generation,
        is_frozen: result.is_frozen,
        resources: result
            .resources
            .into_iter()
            .map(|resource: ResourceViewP| ResourceViewP {
                address: resource.address,
                attributed_load: resource.attributed_load,
                ..Default::default()
            })
            .collect(),
        slices: result.slices,
    };

    assert_eq!(comparable_result, *expected, "failed: {description}");
}

#[test]
fn test_generation_to_view_proto() {
    // Test plan: Verify that generation_to_view_proto converts a Generation to a GenerationViewP
    // with the correct incarnation value, number (epoch millis), and human-readable timestamp.
    for test_case in &DPAGE_VIEW_HELPERS_TEST_DATA.generation_to_view_proto_test_cases {
        let description: &str = test_case
            .description
            .as_deref()
            .expect("expected generation_to_view_proto description");
        let generation: Generation =
            Generation::from_proto(test_case.input.as_ref().unwrap()).unwrap();

        let result: GenerationViewP = generation_to_view_proto(generation);
        let expected_output: &GenerationViewP = test_case.expected_output.as_ref().unwrap();

        assert_eq!(&result, expected_output, "failed: {description}");
    }
}

#[test]
fn test_get_assignment_view_proto() {
    // Test plan: Verify the get_assignment_view_proto function converts an assignment to a proto
    // with the expected fields populated.
    for test_case in &DPAGE_VIEW_HELPERS_TEST_DATA.get_assignment_view_proto_test_cases {
        let description: &str = test_case
            .description
            .as_deref()
            .expect("expected get_assignment_view_proto description");

        // Parse the input assignment.
        let assignment_opt: Option<Assignment> = test_case
            .input
            .as_ref()
            .and_then(|input| input.assignment.as_ref())
            .map(parse_simple_assignment);

        // Parse the input reported load per resource.
        let reported_load_per_resource: HashMap<Squid, f64> = test_case
            .input
            .iter()
            .flat_map(|input| &input.reported_load_per_resource)
            .map(|reported_load| {
                let resource: Squid = test_squid_with_uri(
                    reported_load.resource.as_ref().expect("non-empty resource"),
                );
                let load: f64 = reported_load.load.expect("non-empty load");
                (resource, load)
            })
            .collect();

        // Parse the input reported load per slice.
        let reported_load_per_slice: HashMap<Slice, f64> = test_case
            .input
            .iter()
            .flat_map(|input| &input.reported_load_per_slice)
            .map(|reported_load| {
                let slice_proto: &SliceP = reported_load.slice.as_ref().expect("non-empty slice");
                let slice: Slice = slice_from_proto(slice_proto).expect("valid slice");
                let load: f64 = reported_load.load.expect("non-empty load");
                (slice, load)
            })
            .collect();

        // Parse the input top keys.
        let top_keys: Option<BTreeMap<SliceKey, f64>> = match test_case.input.as_ref() {
            Some(input) if !input.top_keys.is_empty() => Some(
                input
                    .top_keys
                    .iter()
                    .map(|top_key| {
                        let key: SliceKey =
                            SliceKey::from_raw_bytes(top_key.key.as_ref().expect("non-empty key"));
                        let load: f64 = top_key.load.expect("non-empty load");
                        (key, load)
                    })
                    .collect(),
            ),
            _ => None,
        };

        let result: AssignmentViewP = get_assignment_view_proto(
            assignment_opt.as_ref(),
            Some(&reported_load_per_resource),
            Some(&reported_load_per_slice),
            top_keys.as_ref(),
        );
        let expected_output: &AssignmentViewP = test_case.expected_output.as_ref().unwrap();

        assert_assignment_view_matches(result, expected_output, description);
    }
}
