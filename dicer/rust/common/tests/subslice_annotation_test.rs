use std::sync::Arc;

use tonic::{Code, Status};
use dicer_common_proto_common_proto::dicer::diff_assignment_p::SubsliceAnnotationP;
use dicer_friend_proto_friend_proto_library::dicer::friend::{SliceP, SquidP};
use dicer_rust_common::{
    ResourceProtoBuilder,
    SubsliceAnnotation,
    Transfer,
    UnixTimeVersion,
};
use dicer_rust_external_types::{HighSliceKey, SliceKey, SliceKeyBuilder};
use dicer_rust_friend::Squid;
use dicer_rust_testing::data_helpers::test_squid_with_uri;
#[test]
fn test_round_trip() {
    // Test plan: Verify that a `SubsliceAnnotation` instance can be converted to proto and back.

    // Create a valid SubsliceAnnotation.
    let owner_resource: Arc<Squid> = Arc::new(test_squid_with_uri("Pod0"));
    let low_key: SliceKey = SliceKey::fingerprint_builder().put_bytes(b"Fili").build();
    let high_key: HighSliceKey = SliceKey::fingerprint_builder()
        .put_bytes(b"Kili")
        .build()
        .into_high_key();
    let subslice_annotation = SubsliceAnnotation {
        continuous_generation_number: UnixTimeVersion::from_unix_millis(1234).unwrap(),
        state_transfer_opt: Some(Transfer {
            id: 42,
            from_resource: Arc::new(test_squid_with_uri("Pod1")),
        }),
    };

    // Convert the annotation to a proto.
    let mut resource_builder = ResourceProtoBuilder::new();
    let proto: SubsliceAnnotationP = subslice_annotation.to_proto(
        &low_key,
        high_key.as_ref(),
        &owner_resource,
        &mut resource_builder,
    );
    let resource_protos: Vec<SquidP> = resource_builder.to_protos();

    // Convert the proto back to the native representation, and verify that it matches the original.
    let resources: Vec<Arc<Squid>> = resource_protos
        .iter()
        .map(|proto| Arc::new(Squid::from_proto(proto).unwrap()))
        .collect();
    let (annotation_after_round_trip, owner_resource_after_round_trip) =
        SubsliceAnnotation::from_proto(&proto, &resources).unwrap();
    assert_eq!(annotation_after_round_trip, subslice_annotation);
    assert_eq!(owner_resource_after_round_trip, owner_resource);

    // Convert to a proto once again, and verify that it matches the previous proto.
    let mut new_resource_builder = ResourceProtoBuilder::new();
    let new_proto: SubsliceAnnotationP = annotation_after_round_trip.to_proto(
        &low_key,
        high_key.as_ref(),
        &owner_resource_after_round_trip,
        &mut new_resource_builder,
    );
    assert_eq!(new_proto, proto);
}

#[test]
fn test_proto_validity() {
    // Test plan: Verify that `from_proto` fails for invalid protos.
    let invalid_proto = SubsliceAnnotationP {
        resource_id: Some(2),
        subslice: Some(SliceP {
            low_inclusive: Some(b"Fili".to_vec()),
            high_exclusive: None,
        }),
        generation_number: Some(42),
        state_transfer: None,
    };
    let resources: Vec<Arc<Squid>> = vec![Arc::new(test_squid_with_uri("Pod0"))];

    let err: Status = SubsliceAnnotation::from_proto(&invalid_proto, &resources).unwrap_err();
    assert_eq!(err.code(), Code::InvalidArgument);
    assert!(err.message().contains("Resource index 2 out of range"));
}
