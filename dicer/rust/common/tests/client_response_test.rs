use rand::Rng;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::sync_assignment_state_p::State;
use dicer_common_proto_common_proto::dicer::{ClientResponseP, SyncAssignmentStateP};
use dicer_rust_common::{ClientResponse, DiffAssignment, SyncAssignmentState};
use dicer_rust_testing::test_data::CLIENT_RESPONSE_TEST_DATA;

#[test]
fn test_invalid_client_response() {
    // Test plan: Verify that invalid client response protos result in parsing errors.
    let test_cases = &CLIENT_RESPONSE_TEST_DATA.invalid_cases;

    for test_case in test_cases {
        let test_name = test_case
            .case_name
            .as_ref()
            .expect("expect a name for each test case");
        let expected_error_msg = test_case
            .expected_error_message
            .as_ref()
            .expect("expect a non-empty error message");
        let client_response_proto: &ClientResponseP =
            test_case.client_response.unwrap_ref_or_default();

        match ClientResponse::from_proto(client_response_proto) {
            Ok(_) => {
                panic!("Expected test case '{}' to fail, but it succeeded.", test_name);
            }
            Err(error) => {
                let error_message = error.to_string();
                assert!(
                    error_message.contains(expected_error_msg),
                    "Test case '{}' expected error message '{}', but got: '{}'",
                    test_name,
                    expected_error_msg,
                    error_message
                );
            }
        }
    }
}

#[test]
fn test_client_response_roundtripping() {
    // Test plan: Ensure that the client response class and the proto roundtrip for valid objects.
    let test_cases = &CLIENT_RESPONSE_TEST_DATA.round_tripping_cases;

    for test_case in test_cases {
        let test_name = test_case
            .case_name
            .as_ref()
            .expect("expect a name for each test case");
        let response_proto: &ClientResponseP = test_case.client_response.unwrap_ref_or_default();

        // Test the roundtrip: proto -> ClientResponse -> proto -> ClientResponse
        let response: ClientResponse = ClientResponse::from_proto(response_proto)
            .expect("Failed to parse ClientResponse from proto");
        let roundtrip_proto: ClientResponseP = response.to_proto();
        let final_roundtrip: ClientResponse = ClientResponse::from_proto(&roundtrip_proto)
            .expect("Failed to parse ClientResponse from roundtrip proto");

        assert_eq!(
            response, final_roundtrip,
            "Test case '{}' failed: original and round-tripped ClientResponse do not match.",
            test_name,
        );
    }
}

#[test]
fn test_client_response_parses_known_serialized_assignment() {
    // Test plan: Verify that a ClientResponse with a valid KnownSerializedAssignment can be
    // parsed correctly. We pick a random roundtripping case that contains a KnownAssignment,
    // encode the DiffAssignmentP to bytes, and swap the state to KnownSerializedAssignment to
    // verify that the deserialization produces the same result as KnownAssignment.

    // Filter to only the cases with KnownAssignment, then pick a random one.
    let assignment_cases: Vec<&ClientResponseP> = CLIENT_RESPONSE_TEST_DATA
        .round_tripping_cases
        .iter()
        .filter_map(|tc| {
            let proto = tc.client_response.as_ref()?;
            let state = proto.sync_assignment_state.as_ref()?.state.as_ref()?;
            matches!(state, State::KnownAssignment(_)).then_some(proto)
        })
        .collect();
    assert!(
        !assignment_cases.is_empty(),
        "Expected at least one roundtripping case with KnownAssignment"
    );
    let idx = rand::rng().random_range(0..assignment_cases.len());
    let response_proto = assignment_cases[idx];

    // Extract the diff assignment from the client response and use it to build the serialized
    // proto representation.
    let expected_response = ClientResponse::from_proto(response_proto)
        .expect("Failed to parse ClientResponse with KnownAssignment");
    let diff_assignment: DiffAssignment = match expected_response.sync_state() {
        SyncAssignmentState::KnownAssignment(diff) => diff.clone(),
        _ => unreachable!("We only pick cases with KnownAssignment"),
    };
    let (_structured, serialized_sync_state_p) =
        SyncAssignmentState::known_assignment_to_cached_protos(diff_assignment);

    // Clone the response and swap in the serialized variant.
    let mut serialized_response_proto: ClientResponseP = response_proto.clone();
    serialized_response_proto.sync_assignment_state = Some(serialized_sync_state_p);

    // Parse both and verify they produce the same SyncAssignmentState.
    let serialized_response = ClientResponse::from_proto(&serialized_response_proto)
        .expect("Failed to parse ClientResponse with KnownSerializedAssignment");

    assert_eq!(
        expected_response.sync_state(),
        serialized_response.sync_state(),
        "KnownSerializedAssignment should produce the same result as KnownAssignment"
    );
}

#[test]
fn test_client_response_rejects_malformed_serialized_assignment() {
    // Test plan: Verify that a ClientResponse with malformed bytes in KnownSerializedAssignment
    // returns a parse error.
    let client_response_proto = ClientResponseP {
        sync_assignment_state: Some(SyncAssignmentStateP {
            state: Some(State::KnownSerializedAssignment(vec![1, 2, 3])),
        }),
        suggested_rpc_timeout_millis: Some(5000),
        redirect: None,
    };

    let result = ClientResponse::from_proto(&client_response_proto);
    assert!(result.is_err(), "Expected parsing to fail for malformed KnownSerializedAssignment");
    let error = result.unwrap_err();
    assert_eq!(error.code(), tonic::Code::InvalidArgument);
    assert!(error
        .message()
        .contains("Failed to parse DiffAssignmentP from KnownSerializedAssignment"));
}
