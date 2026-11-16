use dicer_rust_external_types::{HighSliceKey, Slice, SliceKey};
use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;

#[test]
fn test_create_single_key_slice() {
    // Test plan: Verify that `create_single_key_slice` returns a slice from the given key
    // (inclusive) to the minimum representable greater key (exclusive).
    let key: SliceKey = SliceKey::from_raw_bytes(&[0x11, 0x22, 0x33, 0x44]);
    let single_key_slice: Slice = SliceKey::create_single_key_slice(&key);

    assert_eq!(single_key_slice.low_inclusive(), &key);
    let expected_successor: SliceKey = SliceKey::from_raw_bytes(&[0x11, 0x22, 0x33, 0x44, 0x00]);
    assert_eq!(
        single_key_slice.high_exclusive().to_owned(),
        HighSliceKey::Finite(expected_successor)
    );
}
