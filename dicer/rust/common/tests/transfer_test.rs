use std::sync::Arc;

use tonic::Status;
use dicer_friend_proto_friend_proto_library::dicer::friend::SquidP;
use dicer_rust_common::{ResourceProtoBuilder, Transfer};
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers::test_squid;
use dicer_rust_testing::test_data::TRANSFER_TEST_DATA;
#[test]
fn test_proto_round_tripping() {
    // Test Plan: Verify that a Transfer instance can be converted to proto and back.
    let transfer = Transfer {
        id: 42,
        from_resource: Arc::new(test_squid(0)),
    };

    // Convert the transfer to a proto.
    let mut resource_builder = ResourceProtoBuilder::new();
    let proto = transfer.to_proto(&mut resource_builder);
    let resource_protos: Vec<SquidP> = resource_builder.to_protos();

    // Parse the proto, and verify that it matches the original transfer.
    let resources: Vec<Arc<Squid>> = resource_protos
        .iter()
        .map(|proto: &SquidP| Arc::new(Squid::from_proto(proto).unwrap()))
        .collect();
    let transfer_after_round_trip = Transfer::from_proto(&proto, &resources).unwrap();
    assert_eq!(transfer_after_round_trip, transfer);

    // Convert the transfer back to a proto, and verify that it matches the proto from above.
    let mut resource_builder = ResourceProtoBuilder::new();
    assert_eq!(transfer_after_round_trip.to_proto(&mut resource_builder), proto);
    assert_eq!(resource_builder.to_protos(), resource_protos);
}

#[test]
fn test_proto_validity() {
    // Test Plan: Verify that `Transfer::from_proto` fails for invalid protos.
    let resources = [Arc::new(test_squid(0))];
    for test_case in &TRANSFER_TEST_DATA.proto_validity_test_cases {
        let expected_error: &str = test_case.expected_error.as_ref().unwrap();
        let error: Status =
            Transfer::from_proto(test_case.transfer.as_ref().unwrap(), &resources).unwrap_err();
        assert!(
            error.message().contains(expected_error),
            "Expected error message containing {:?}, but was {:?}",
            expected_error,
            error.message()
        );
    }
}
