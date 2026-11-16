use chrono::{DateTime, Duration, Utc};
use rand::Rng;
use tonic::Status;
use dicer_common_proto_common_proto::dicer::client_request_p::slicelet_data_p::*;
use dicer_common_proto_common_proto::dicer::client_request_p::ClientFeatureSupportP;
use dicer_common_proto_common_proto::dicer::ClientRequestP;
use dicer_rust_common::{
    key_load_from_proto_for_test,
    key_load_to_proto_for_test,
    slice_load_from_proto_for_test,
    slice_load_to_proto_for_test,
    ClientRequest,
    KeyLoad,
    SliceLoad,
};
use dicer_rust_external_types::{Slice, SliceKey, SliceKeyBuilder};
use dicer_rust_testing::test_data::{CLIENT_REQUEST_TEST_DATA, SLICE_LOAD_TEST_DATA};
const CLUSTER_URI1: &str = "kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01";
const CLUSTER_URI2: &str = "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01";

#[test]
fn test_invalid_client_request() {
    // Test plan: Verify that invalid client request protos result in parsing errors.
    let test_cases = &CLIENT_REQUEST_TEST_DATA.invalid_cases;

    for test_case in test_cases {
        let test_name = test_case
            .case_name
            .as_ref()
            .expect("expect a name for each test case");
        let expected_error_msg = test_case
            .expected_error_message
            .as_ref()
            .expect("expect a non-empty error message");
        let client_request_proto: &ClientRequestP = test_case
            .client_request
            .as_ref()
            .expect("expect a client request proto");

        match ClientRequest::from_proto(client_request_proto) {
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
fn test_client_request_roundtripping() {
    // Test plan: Ensure that the client request class and the proto roundtrip for valid objects.
    let test_cases = &CLIENT_REQUEST_TEST_DATA.round_tripping_cases;
    let cluster_uris = [CLUSTER_URI1, CLUSTER_URI2];

    for cluster_uri in &cluster_uris {
        for request_proto in test_cases {
            // Test the roundtrip: proto -> ClientRequest -> proto -> ClientRequest
            let request: ClientRequest = ClientRequest::from_proto(request_proto)
                .expect("Failed to parse ClientRequest from proto");
            let roundtrip_proto: ClientRequestP = request.to_proto();
            let final_roundtrip: ClientRequest = ClientRequest::from_proto(&roundtrip_proto)
                .expect("Failed to parse ClientRequest from roundtrip proto");

            assert_eq!(
                request, final_roundtrip,
                "ClientRequest roundtrip failed for cluster URI: {}",
                cluster_uri
            );
        }
    }
}

#[test]
fn test_dropped_cluster_uri_cases_parse_to_none() {
    // Test plan: Verify that deserializing drops a cluster_uri that is unset or malformed to None,
    // parsing successfully rather than rejecting the request. Verify this by parsing each dropped
    // cluster uri test case and asserting the cluster URI option is empty.
    for request_proto in &CLIENT_REQUEST_TEST_DATA.dropped_cluster_uri_cases {
        let request: ClientRequest =
            ClientRequest::from_proto(request_proto).expect("Failed to parse ClientRequest");
        assert!(request.cluster_uri_opt().is_none());
    }
}

#[test]
fn test_dropped_region_uri_cases_parse_to_none() {
    // Test plan: Verify that deserializing drops a region_uri that is unset, malformed, or
    // well-formed but absent from IDM to None, parsing successfully rather than rejecting the
    // request. Verify this by parsing each dropped region URI test case and asserting the region
    // URI option is empty.
    for request_proto in &CLIENT_REQUEST_TEST_DATA.dropped_region_uri_cases {
        let request: ClientRequest =
            ClientRequest::from_proto(request_proto).expect("Failed to parse ClientRequest");
        assert!(request.region_uri_opt().is_none());
    }
}

#[test]
fn test_supports_serialized_assignment_defaults_to_false() {
    // Test plan: Verify that supports_serialized_assignment defaults to false when:
    // 1. client_feature_support is omitted entirely
    // 2. client_feature_support is present but empty (default instance)

    // Select a random client_request from the test data, then modify the `client_feature_support`
    // field.
    let cases = &CLIENT_REQUEST_TEST_DATA.round_tripping_cases;
    let idx = rand::rng().random_range(0..cases.len());

    // Case 1: client_feature_support is None.
    let mut proto_without = cases[idx].clone();
    proto_without.client_feature_support = None;
    let request1 = ClientRequest::from_proto(&proto_without).unwrap();
    assert!(
        !request1.supports_serialized_assignment(),
        "Expected supports_serialized_assignment to default to false when client_feature_support \
         is omitted"
    );

    // Case 2: client_feature_support is present but empty.
    let mut proto_with_empty = cases[idx].clone();
    proto_with_empty.client_feature_support = Some(ClientFeatureSupportP::default());
    let request2 = ClientRequest::from_proto(&proto_with_empty).unwrap();
    assert!(
        !request2.supports_serialized_assignment(),
        "Expected supports_serialized_assignment to default to false when client_feature_support \
         is present but empty"
    );
}

// Returns a sorted vector of SliceKeys for testing purposes.
fn create_sorted_slice_keys() -> Vec<SliceKey> {
    let mut keys: Vec<SliceKey> = vec![
        SliceKey::fingerprint_builder()
            .put_bytes(&(123_u64).to_be_bytes())
            .build(),
        SliceKey::fingerprint_builder()
            .put_bytes(&(456_u64).to_be_bytes())
            .build(),
        SliceKey::fingerprint_builder()
            .put_bytes(&(789_u64).to_be_bytes())
            .build(),
    ];
    keys.sort();
    keys
}

#[test]
fn test_slice_load_creation_from_new() {
    // Test plan: verify that the `new` method correctly validates the inputs and creates a
    // SliceLoad instance.
    let low: DateTime<Utc> = Utc::now();
    let high: DateTime<Utc> = low + Duration::seconds(10);
    let slice_keys: Vec<SliceKey> = create_sorted_slice_keys();

    let slice: Slice =
        Slice::new(slice_keys[0].clone(), slice_keys[1].clone().into_high_key()).unwrap();

    // Verify that creating a SliceLoad with a negative primary rate load fails.
    assert!(SliceLoad::new(-1.0, low, high, slice.clone(), vec![], 1, None).is_err());

    // Verify that creating a SliceLoad with window less than window low fails.
    assert!(SliceLoad::new(1.0, high, low, slice.clone(), vec![], 1, None).is_err());

    // Verify that creating a SliceLoad with a top key outside the slice fails.
    let keys_outside_slice: Vec<KeyLoad> = vec![KeyLoad::new(slice_keys[2].clone(), 1.0).unwrap()];
    assert!(SliceLoad::new(1.0, low, high, slice.clone(), keys_outside_slice, 1, None).is_err());

    // Verify that creating a SliceLoad with a negative number of replicas fails.
    assert!(SliceLoad::new(1.0, low, high, slice.clone(), vec![], -1, None).is_err());

    // Verify that a window covering the whole allowed range is accepted, and that a window
    // starting one second too early or ending one second too late is rejected.
    let min_timestamp: DateTime<Utc> =
        DateTime::from_timestamp(SliceLoad::MIN_WINDOW_EPOCH_SECONDS, 0).unwrap();
    let max_timestamp: DateTime<Utc> =
        DateTime::from_timestamp(SliceLoad::MAX_WINDOW_EPOCH_SECONDS, 0).unwrap();
    SliceLoad::new(1.0, min_timestamp, max_timestamp, slice.clone(), vec![], 1, None).unwrap();
    let too_early: DateTime<Utc> = min_timestamp - Duration::seconds(1);
    assert!(SliceLoad::new(1.0, too_early, max_timestamp, slice.clone(), vec![], 1, None).is_err());
    let too_late: DateTime<Utc> = max_timestamp + Duration::seconds(1);
    assert!(SliceLoad::new(1.0, min_timestamp, too_late, slice.clone(), vec![], 1, None).is_err());

    // Verify that creating a SliceLoad with valid inputs succeeds.
    let top_key: KeyLoad = KeyLoad::new(slice_keys[0].clone(), 1.0).unwrap();

    SliceLoad::new(1.0, low, high, slice.clone(), vec![top_key], 1, None).unwrap();
}

#[test]
fn test_invalid_slice_load_proto() {
    // Test plan: verify that the `SliceLoad.from_proto` method throws an exception when
    // given an invalid proto.
    for case in &SLICE_LOAD_TEST_DATA.invalid_cases {
        let slice_load_proto: &SliceLoadP = case
            .slice_load
            .as_ref()
            .expect("expect non empty SliceLoadP");
        let expected_error_msg = case
            .expected_error
            .as_ref()
            .expect("expect non empty error message");
        let debug_case_name = case
            .debug_name
            .as_ref()
            .expect("expect non empty debug name");

        // Verify that the result is an invalid argument error.
        let result: Result<SliceLoad, Status> = slice_load_from_proto_for_test(slice_load_proto);

        // Verify that the parsing fails. Otherwise fail with the case name to aid debugging.
        assert!(result.is_err(), "Failed to parse SliceLoad from proto: {}", debug_case_name);
        let error: Status = result.unwrap_err();
        assert_eq!(error.code(), tonic::Code::InvalidArgument);

        // Verify that the error message contains the expected error message.
        let error_message: String = error.message().to_string();
        assert!(
            error_message.contains(expected_error_msg),
            "Expected error message '{}' to contain '{}'",
            error_message,
            expected_error_msg
        );
    }
}

#[test]
fn test_slice_load_slice_default_value() {
    // Test plan: Verify that when `slice` field is missing in the SliceLoadP proto message,
    // the SliceLoad can still be successfully parsed from it and the value of `slice` will
    // be a full slice.
    let slice_load_proto: &SliceLoadP = SLICE_LOAD_TEST_DATA
        .empty_slice_case
        .as_ref()
        .expect("expect non empty SliceLoadP");

    // Assert that the slice field is not set in the proto.
    assert!(slice_load_proto.slice.is_none());
    let slice_load: SliceLoad = slice_load_from_proto_for_test(slice_load_proto).unwrap();

    // Assert that the slice is a full slice.
    assert_eq!(slice_load.slice(), Slice::full());
}

#[test]
fn test_slice_load_num_replicas_default_value() {
    // Test plan: Verify that when `numReplicas` field is missing in the SliceLoadP proto message,
    // the SliceLoad can still be successfully parsed from it and the value of `numReplicas` will
    // be 1.
    let slice_load_proto: &SliceLoadP = SLICE_LOAD_TEST_DATA
        .empty_num_replicas_case
        .as_ref()
        .expect("expect non empty SliceLoadP");

    // Assert that the num_replicas field is not set in the proto.
    assert!(slice_load_proto.num_replicas.is_none());
    let slice_load: SliceLoad = slice_load_from_proto_for_test(slice_load_proto).unwrap();

    // Assert that the num_replicas is 1.
    assert_eq!(slice_load.num_replicas(), 1);
}

#[test]
fn test_slice_load_proto_round_tripping() {
    // Test plan: Verify that SliceLoad.from_proto does not throw an exception when given a
    // valid proto, and that the SliceLoad class and the proto round-trip correctly.
    // Note: Strictly speaking, SliceLoad.from_proto and SliceLoad.to_proto are not fully
    // symmetric, since an empty slice is represented as a full slice in the proto, and the
    // unspecified numReplicas field defaults to 1. This test is intended to ensure that
    // round-tripping works for the case where the slice is not empty and numReplicas is specified.
    // See `test_slice_load_slice_default_value` and `test_slice_load_num_replicas_default_value`
    // for the special cases.
    for slice_load_proto in &SLICE_LOAD_TEST_DATA.round_tripping_cases {
        // Verify that result is a valid SliceLoad.
        let slice_load: SliceLoad = slice_load_from_proto_for_test(slice_load_proto).unwrap();

        // Verify the `to_proto` method produces the same proto.
        assert_eq!(slice_load_to_proto_for_test(&slice_load), *slice_load_proto);
        // Verify the from_proto method produces the same SliceLoad.
        assert_eq!(
            slice_load_from_proto_for_test(&slice_load_to_proto_for_test(&slice_load)).unwrap(),
            slice_load
        );
    }
}

#[test]
fn test_key_load_creation_from_new() {
    // Test plan: verify that the `new` method correctly validates the inputs and creates a
    // KeyLoad instance.
    let key: SliceKey = SliceKey::fingerprint_builder()
        .put_bytes(&(1234567890_u64).to_be_bytes())
        .build();

    // Verify that creating a KeyLoad with a negative load fails.
    assert!(KeyLoad::new(key.clone(), -1.0).is_err());

    // Verify that creating a KeyLoad with a positive, finite load succeeds.
    KeyLoad::new(key, 5.67).unwrap();
}

#[test]
fn test_key_load_creation_from_proto() {
    // Test plan: verify that the `from_proto` method correctly parses and validates the proto
    // representation of a KeyLoad.

    // Verify that creating a KeyLoad from a proto with missing slice_key fails.
    let proto_missing_key = KeyLoadP {
        slice_key: None,
        underestimated_primary_rate_load: Some(42.0),
    };
    assert!(key_load_from_proto_for_test(&proto_missing_key).is_err());

    let expected_key: SliceKey = SliceKey::fingerprint_builder()
        .put_bytes("hello".as_bytes())
        .build();

    // Verify that creating a KeyLoad from a proto with negative load fails.
    let proto_negative_load = KeyLoadP {
        slice_key: Some(expected_key.to_raw_bytes().to_vec()),
        underestimated_primary_rate_load: Some(-1.0),
    };
    assert!(key_load_from_proto_for_test(&proto_negative_load).is_err());

    // Verify that creating a KeyLoad from a valid proto succeeds.
    let proto_valid: KeyLoadP = KeyLoadP {
        slice_key: Some(expected_key.to_raw_bytes().to_vec()),
        underestimated_primary_rate_load: Some(42.0),
    };

    let key_load: KeyLoad = key_load_from_proto_for_test(&proto_valid).unwrap();

    // Verify that the KeyLoad has the expected fields.
    assert_eq!(key_load.key(), &expected_key);
    assert_eq!(key_load.underestimated_primary_rate_load(), 42.0);
}

#[test]
fn test_key_load_proto_round_tripping() {
    // Test plan: Ensure that the KeyLoad class and the proto roundtrip for a valid object.
    for key in create_sorted_slice_keys() {
        for load in [0.0, 1.23, 1024.56, 2048.89] {
            let key_load: KeyLoad = KeyLoad::new(key.clone(), load).unwrap();
            let key_load_proto: KeyLoadP = key_load_to_proto_for_test(&key_load);
            let key_load_from_proto: KeyLoad =
                key_load_from_proto_for_test(&key_load_proto).unwrap();

            assert_eq!(key_load, key_load_from_proto);

            // Check the fields of the KeyLoad.
            assert_eq!(key_load.key(), &key);
            assert_eq!(key_load.underestimated_primary_rate_load(), load);
        }
    }
}
