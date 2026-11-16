use tonic::{Code, Status};
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_testing::test_slice_utils::identity;
#[test]
fn test_finite_slice_creation() {
    // Test Plan: Verify that well-formed finite slices can be created successfully.
    let low: SliceKey = identity("Bifur");
    let high: HighSliceKey = identity("Nori").into_high_key();
    let finite_slice = Slice::new(low.clone(), high.clone()).unwrap();
    assert_eq!(finite_slice.low_inclusive(), &low);
    assert_eq!(finite_slice.high_exclusive(), high.as_ref());
}

#[test]
fn test_infinite_slice_creation() {
    // Test Plan: Verify that well-formed infinite slices can be created successfully.
    let low: SliceKey = identity("Bifur");
    let infinite_slice = Slice::new(low.clone(), HighSliceKey::Infinity).unwrap();
    assert_eq!(infinite_slice.low_inclusive(), &low);
    assert_eq!(infinite_slice.high_exclusive(), HighSliceKeyRef::Infinity);
}

#[test]
fn test_invalid_slice_creation() {
    // Test Plan: Verify that invalid slices are rejected.

    // low == high:
    let error: Status = Slice::new(identity("key"), identity("key").into_high_key()).unwrap_err();
    assert_eq!(error.code(), Code::InvalidArgument);
    assert!(error
        .message()
        .contains("Low key must be less than high key"));

    // low > high:
    let error: Status =
        Slice::new(identity("Nori"), identity("Bifur").into_high_key()).unwrap_err();
    assert_eq!(error.code(), Code::InvalidArgument);
    assert!(error
        .message()
        .contains("Low key must be less than high key"));
}

#[test]
fn test_full_slice() {
    // Test Plan: Verify that `Slice::full()` returns a slice covering the entire key space.
    assert_eq!(Slice::full().low_inclusive(), &SliceKey::MIN);
    assert_eq!(Slice::full().high_exclusive(), HighSliceKeyRef::Infinity);
}

#[test]
fn test_into_bounds() {
    // Test Plan: Verify that `into_bounds` correctly returns the slice bounds.
    let low: SliceKey = identity("Bifur");
    let high: HighSliceKey = identity("Nori").into_high_key();
    let slice = Slice::new(low.clone(), high.clone()).unwrap();

    let (returned_low, returned_high): (SliceKey, HighSliceKey) = slice.into_bounds();
    assert_eq!(returned_low, low);
    assert_eq!(returned_high, high);
}

#[test]
fn test_debug_repr() {
    // Test Plan: Verify the formatting of Slice's Debug representation.

    // Finite slice:
    let finite_slice = Slice::new(identity("Bifur"), identity("Nori").into_high_key()).unwrap();
    assert_eq!(format!("{finite_slice:?}"), r#"[Bifur .. Nori)"#);

    // Infinite slice:
    let infinite_slice = Slice::new(identity("Bifur"), HighSliceKey::Infinity).unwrap();
    assert_eq!(format!("{infinite_slice:?}"), r#"[Bifur .. ∞)"#);

    // Full slice:
    let full_slice = Slice::full();
    assert_eq!(format!("{full_slice:?}"), r#"["" .. ∞)"#);
}

#[test]
fn test_slice_contains_key() {
    // Test Plan: Verify that `contains_key` correctly identifies keys within the slice.
    let some_key: SliceKey = identity("Hello");

    let slice1: Slice = Slice::new(SliceKey::MIN, some_key.clone().into_high_key()).unwrap();
    let slice2: Slice = Slice::new(some_key.clone(), HighSliceKey::Infinity).unwrap();
    let slice3: Slice = Slice::full().clone();

    // Check for existence in Slice.
    assert!(slice1.contains_key(&SliceKey::MIN));
    assert!(slice2.contains_key(&identity("MustExist")));
    assert!(!slice2.contains_key(&identity("Bye")));
    assert!(slice3.contains_key(&SliceKey::MIN));
    assert!(slice3.contains_key(&identity("MustExist")));
}
