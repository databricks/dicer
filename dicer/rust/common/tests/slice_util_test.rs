use tonic::{Code, Status};
use dicer_friend_proto_friend_proto_library::dicer::friend::SliceP;
use dicer_rust_common::{slice_from_proto, slice_to_proto};
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_testing::test_slice_utils::identity;
#[test]
fn test_slice_from_proto_finite() {
    // Test Plan: Verify that finite Slices can be parsed from well-formed protos.
    let proto = SliceP {
        low_inclusive: Some(b"Bifur".to_vec()),
        high_exclusive: Some(b"Nori".to_vec()),
    };
    let slice: Slice = slice_from_proto(&proto).unwrap();
    assert_eq!(slice.low_inclusive(), &identity("Bifur"));
    assert_eq!(slice.high_exclusive(), HighSliceKeyRef::Finite(&identity("Nori")));
}

#[test]
fn test_slice_from_proto_infinite() {
    // Test Plan: Verify that infinite Slices can be parsed from well-formed protos.
    let proto = SliceP {
        low_inclusive: Some(b"Bifur".to_vec()),
        high_exclusive: None,
    };
    let slice: Slice = slice_from_proto(&proto).unwrap();
    assert_eq!(slice.low_inclusive(), &identity("Bifur"));
    assert_eq!(slice.high_exclusive(), HighSliceKeyRef::Infinity);
}

#[test]
fn test_slice_from_proto_with_missing_low() {
    // Test Plan: Verify that when low_inclusive is missing from the proto, it's treated as an empty
    // slice key.
    let proto = SliceP {
        low_inclusive: None,
        high_exclusive: Some(b"Nori".to_vec()),
    };
    let slice: Slice = slice_from_proto(&proto).unwrap();
    assert_eq!(slice.low_inclusive(), &SliceKey::MIN);
    assert_eq!(slice.high_exclusive(), HighSliceKeyRef::Finite(&identity("Nori")));
}

#[test]
fn test_slice_from_proto_invalid() {
    // Test Plan: Verify that invalid slice protos are rejected.
    let proto = SliceP {
        low_inclusive: Some(b"Nori".to_vec()),
        high_exclusive: Some(b"Bifur".to_vec()),
    };
    let error: Status = slice_from_proto(&proto).unwrap_err();
    assert_eq!(error.code(), Code::InvalidArgument);
    assert!(error
        .message()
        .contains("Low key must be less than high key"));
}

#[test]
fn test_slice_to_proto_finite() {
    // Test Plan: Verify that finite slices can be converted to protos correctly.
    let low: SliceKey = identity("Bifur");
    let high = HighSliceKey::Finite(identity("Nori"));
    assert_eq!(
        slice_to_proto(&low, high.as_ref()),
        SliceP {
            low_inclusive: Some(b"Bifur".to_vec()),
            high_exclusive: Some(b"Nori".to_vec()),
        }
    );
}

#[test]
fn test_slice_to_proto_infinite() {
    // Test Plan: Verify that infinite slices can be converted to protos correctly.
    let low: SliceKey = identity("Bifur");
    let high = HighSliceKey::Infinity;
    assert_eq!(
        slice_to_proto(&low, high.as_ref()),
        SliceP {
            low_inclusive: Some(b"Bifur".to_vec()),
            high_exclusive: None,
        }
    );
}
