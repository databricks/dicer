use dicer_common_proto_common_proto::dicer::diff_assignment_p::AssignerServiceInfoP;
use dicer_rust_common::AssignerServiceInfo;
use dicer_rust_testing::test_data::ASSIGNER_SERVICE_INFO_TEST_DATA;

#[test]
fn test_proto_round_trip() {
    // Test Plan: Verify that a valid AssignerServiceInfo proto round-trips through deserialization
    // and serialization.
    let proto: &AssignerServiceInfoP = ASSIGNER_SERVICE_INFO_TEST_DATA
        .round_trip_proto
        .as_ref()
        .expect("round_trip_proto must be set");
    let info: AssignerServiceInfo = AssignerServiceInfo::from_proto(proto).unwrap();
    let round_trip_proto: AssignerServiceInfoP = info.to_proto();
    assert_eq!(&round_trip_proto, proto, "Expected {proto:?}, but got {round_trip_proto:?}");
    assert_eq!(
        AssignerServiceInfo::from_proto(&round_trip_proto).unwrap(),
        info,
        "Expected {info:?}, but got {:?}",
        AssignerServiceInfo::from_proto(&round_trip_proto)
    );
}

#[test]
fn test_constructor_validation() {
    // Test Plan: Verify that the AssignerServiceInfo constructor rejects invalid name and/or
    // instance ID values.
    for test_case in &ASSIGNER_SERVICE_INFO_TEST_DATA.constructor_validation_test_cases {
        let name: String = test_case.name.clone().unwrap_or_default();
        let instance_id: String = test_case.instance_id.clone().unwrap_or_default();
        let expected_error: &str = test_case.expected_error.as_ref().unwrap();
        match AssignerServiceInfo::new(name, instance_id) {
            Ok(info) => panic!("Expected an error, but got {info:?}"),
            Err(error) => {
                let error_message: String = error.message().to_string();
                assert!(
                    error_message.contains(expected_error),
                    "Expected error containing {expected_error:?}, but got {error_message:?}"
                );
            }
        }
    }
}

#[test]
fn test_field_getters() {
    // Test Plan: Verify that the name and instance ID accessors return the values from the proto.
    // Rust-only: Scala exposes these as case class fields and does not test getters separately.
    for test_case in &ASSIGNER_SERVICE_INFO_TEST_DATA.valid_info_test_cases {
        let info: AssignerServiceInfo =
            AssignerServiceInfo::from_proto(test_case.proto.as_ref().unwrap()).unwrap();
        assert_eq!(info.name(), test_case.expected_name.as_ref().unwrap());
        assert_eq!(info.instance_id(), test_case.expected_instance_id.as_ref().unwrap());
    }
}

#[test]
fn test_debug_string() {
    // Test Plan: Verify that the debug representation renders the name and instance ID fields.
    for test_case in &ASSIGNER_SERVICE_INFO_TEST_DATA.valid_info_test_cases {
        let info: AssignerServiceInfo =
            AssignerServiceInfo::from_proto(test_case.proto.as_ref().unwrap()).unwrap();
        let expected_to_string: &str = test_case.expected_to_string.as_ref().unwrap();
        assert_eq!(format!("{info:?}"), expected_to_string, "Expected {expected_to_string}");
    }
}
