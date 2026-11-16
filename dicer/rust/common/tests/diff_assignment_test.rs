use tonic::Status;
use caching_rust_util_testing::change_tracker::ChangeTracker;
use dicer_common_proto_common_proto::dicer::DiffAssignmentP;
use dicer_common_test_proto_test_data_proto::dicer::test::diff_assignment_test_data_p::from_proto_validity_test_case_p::Transformation;
use dicer_rust_common::DiffAssignment;
use dicer_rust_testing::data_helpers::parse_simple_diff_assignment;
use dicer_rust_testing::test_data::DIFF_ASSIGNMENT_TEST_DATA;
use raf_metrics_testing::get_counter_value;

/// Returns a [`ChangeTracker`] over the `dicer_assignment_service_info_parse_total` counter for
/// the given label values.
fn create_assigner_info_parse_outcome_tracker<'a>(
    outcome: &'a str,
    assigner_name: &'a str,
    assigner_instance_id: &'a str,
) -> ChangeTracker<u64, impl Fn() -> u64 + 'a> {
    ChangeTracker::new(move || {
        get_counter_value(
            "dicer_assignment_service_info_parse_total",
            &[
                ("outcome", outcome),
                ("assignerName", assigner_name),
                ("assignerInstanceId", assigner_instance_id),
            ],
        )
        .unwrap_or(0)
    })
}

#[test]
fn test_invariant_validity() {
    // Test plan: Verify that DiffAssignment returns an error when created with invalid arguments.
    for test_case in &DIFF_ASSIGNMENT_TEST_DATA.invariant_validity_test_cases {
        let error: Status =
            parse_simple_diff_assignment(test_case.diff_assignment.as_ref().unwrap()).unwrap_err();
        assert!(
            error
                .message()
                .contains(test_case.expected_error.as_ref().unwrap()),
            "Expected error message containing {:?}, but was {:?}",
            test_case.expected_error.as_ref().unwrap(),
            error.message()
        );
    }
}

#[test]
fn test_from_proto_validity() {
    // Test plan: Create invalid DiffAssignmentP protos and check that `DiffAssignment::from_proto`
    // will reject them.

    // Parse the base valid diff assignment.
    let valid_diff_assignment: DiffAssignment = parse_simple_diff_assignment(
        DIFF_ASSIGNMENT_TEST_DATA
            .valid_diff_assignment
            .as_ref()
            .unwrap(),
    )
    .unwrap();
    let valid_proto: DiffAssignmentP = valid_diff_assignment.to_proto();

    for test_case in &DIFF_ASSIGNMENT_TEST_DATA.from_proto_validity_test_cases {
        // Apply the transformation to create an invalid proto.
        let mut invalid_proto: DiffAssignmentP = valid_proto.clone();
        match &test_case.transformation {
            Some(Transformation::ClearGeneration(_)) => {
                invalid_proto.generation = None;
            }
            Some(Transformation::SelectResources(index_list)) => {
                invalid_proto.resources = index_list
                    .indexes
                    .iter()
                    .map(|&i| valid_proto.resources[i as usize].clone())
                    .collect();
            }
            Some(Transformation::SelectSliceAssignments(index_list)) => {
                invalid_proto.slice_assignments = index_list
                    .indexes
                    .iter()
                    .map(|&i| valid_proto.slice_assignments[i as usize].clone())
                    .collect();
            }
            Some(Transformation::AlterSliceGeneration(change)) => {
                invalid_proto.slice_assignments[change.slice_index.unwrap() as usize].generation =
                    change.new_generation;
            }
            Some(Transformation::AlterSliceResources(change)) => {
                invalid_proto.slice_assignments[change.slice_index.unwrap() as usize]
                    .resource_indices = change.new_resource_indices.clone();
            }
            None => panic!("Invalid transformation"),
        };

        // Verify that `DiffAssignment::from_proto` returns an error.
        let error: Status = DiffAssignment::from_proto(&invalid_proto).unwrap_err();
        assert!(
            error
                .message()
                .contains(test_case.expected_error.as_ref().unwrap()),
            "Expected error message containing {:?}, but was {:?}",
            test_case.expected_error.as_ref().unwrap(),
            error.message()
        );
    }
}

#[test]
fn test_from_proto_ignores_invalid_assigner_service_info() {
    // Test plan: Verify that `DiffAssignment::from_proto` returns None when assigner service info
    // is invalid, and records the parse outcome metric as "invalid" with the name and instance id
    // carried by the rejected proto.

    let valid_diff_assignment: DiffAssignment = parse_simple_diff_assignment(
        DIFF_ASSIGNMENT_TEST_DATA
            .valid_diff_assignment
            .as_ref()
            .unwrap(),
    )
    .unwrap();
    let valid_proto: DiffAssignmentP = valid_diff_assignment.to_proto();

    for invalid_info in &DIFF_ASSIGNMENT_TEST_DATA.invalid_assigner_service_infos {
        let mut proto_with_invalid_info: DiffAssignmentP = valid_proto.clone();
        proto_with_invalid_info.assigner_service_info = Some(invalid_info.clone());
        let invalid_parses = create_assigner_info_parse_outcome_tracker(
            "invalid",
            invalid_info.name(),
            invalid_info.instance_id(),
        );

        let parsed: DiffAssignment = DiffAssignment::from_proto(&proto_with_invalid_info).unwrap();

        assert_eq!(parsed.assigner_service_info_opt(), None);
        // Assert that the parse outcome metric was recorded as "invalid" with the name and instance
        // id carried by the rejected proto.
        assert_eq!(invalid_parses.total_change(), 1);
    }
}

#[test]
fn test_round_trip() {
    // Test plan: Verify that a valid DiffAssignment can be converted to DiffAssignmentP and back.
    // Also verify that each round trip records the parse outcome metric: "valid" with the service
    // info's name and instance id when it is present, and "absent" with empty labels when it is
    // not.

    for test_case in &DIFF_ASSIGNMENT_TEST_DATA.round_trip_test_cases {
        // A case that omits the service info parses as "absent" with empty labels.
        let (outcome, name, instance_id) = match &test_case.assigner_service_info {
            Some(info) => ("valid", info.name(), info.instance_id()),
            None => ("absent", "", ""),
        };
        let parses = create_assigner_info_parse_outcome_tracker(outcome, name, instance_id);

        let diff_assignment: DiffAssignment = parse_simple_diff_assignment(test_case).unwrap();
        let proto: DiffAssignmentP = diff_assignment.to_proto();
        let diff_assignment_after_round_trip = DiffAssignment::from_proto(&proto).unwrap();

        assert_eq!(diff_assignment_after_round_trip, diff_assignment);
        assert_eq!(diff_assignment_after_round_trip.to_proto(), proto);
        assert_eq!(parses.total_change(), 1);
    }
}
