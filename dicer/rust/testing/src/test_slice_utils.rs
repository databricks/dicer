//! Test utilities for creating and manipulating SliceKeys and Slices.
//!
//! This module provides utilities similar to SliceTestUtils in Java and Scala,
//! offering convenient factory methods for creating test SliceKeys and related types.

use dicer_common_test_proto_test_data_proto::dicer::test::slice_key_test_data_p::{
    HighSliceKeyP,
    high_slice_key_p::{Key, SliceKeyFunctionP, SliceKeyP},
};
use dicer_rust_external_types::{HighSliceKey, SliceKey, SliceKeyBuilder};
use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;

/// Creates a SliceKey using FarmHash Fingerprint64 from a string.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::fp;
///
/// let key = fp("Fili");
/// ```
pub fn fp(application_key: &str) -> SliceKey {
    SliceKey::fingerprint_builder()
        .put_string(application_key)
        .build()
}

/// Creates a SliceKey using FarmHash Fingerprint64 from raw bytes.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::fp_bytes;
///
/// let key = fp_bytes(&[1, 2, 3, 4]);
/// ```
pub fn fp_bytes(application_key: &[u8]) -> SliceKey {
    SliceKey::fingerprint_builder()
        .put_bytes(application_key)
        .build()
}

/// Creates a SliceKey with the identity function from a UTF-8 encoded string.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::identity;
///
/// let key = identity("Fili");
/// ```
pub fn identity(application_key: &str) -> SliceKey {
    SliceKey::from_raw_bytes(application_key.as_bytes())
}

/// Creates a SliceKey with the identity function from raw bytes.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::identity_bytes;
///
/// let key = identity_bytes(&[1, 2, 3, 4]);
/// ```
pub fn identity_bytes(application_key: &[u8]) -> SliceKey {
    SliceKey::from_raw_bytes(application_key)
}

/// Creates a SliceKey with the identity function from a u64 value (as big-endian bytes).
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::identity_u64;
///
/// let key = identity_u64(0x0316C733C33E6980);
/// ```
pub fn identity_u64(application_key: u64) -> SliceKey {
    SliceKey::from_raw_bytes(&application_key.to_be_bytes())
}

/// Creates a SliceKey with the identity function from a sequence of byte values.
///
/// This is useful for constructing keys from integer literals that represent bytes.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::identity_ints;
///
/// let key = identity_ints(&[1, 2, 3, 4]);
/// ```
pub fn identity_ints(bytes: &[i32]) -> SliceKey {
    let byte_vec: Vec<u8> = bytes.iter().map(|&b| b as u8).collect();
    SliceKey::from_raw_bytes(&byte_vec)
}

/// Returns the successor of the given SliceKey.
///
/// The successor is defined as the key with a 0 byte appended to the end of the original
/// key's bytes.
///
/// # Examples
///
/// ```
/// use dicer_rust_testing::test_slice_utils::{identity_bytes, successor};
///
/// let key = identity_bytes(&[1, 2, 3]);
/// let succ = successor(&key);
/// assert_eq!(succ.to_raw_bytes(), &[1, 2, 3, 0]);
/// ```
pub fn successor(key: &SliceKey) -> SliceKey {
    let mut bytes = key.to_raw_bytes().to_vec();
    bytes.push(0);
    SliceKey::from_raw_bytes(&bytes)
}

/// Converts a `SliceKeyP` proto message to a [`SliceKey`].
///
/// Applies the appropriate slice key function (identity or FarmHash Fingerprint64).
///
/// # Panics
///
/// Panics if the slice key function is unspecified.
pub fn slice_key_from_proto(slice_key_proto: &SliceKeyP) -> SliceKey {
    // Convert i32 values to u8 bytes
    let application_key: Vec<u8> = slice_key_proto
        .application_key
        .iter()
        .map(|&i| i as u8)
        .collect();

    match slice_key_proto.slice_key_function() {
        SliceKeyFunctionP::Identity => SliceKey::from_raw_bytes(&application_key),
        SliceKeyFunctionP::FarmHashFingerprint64 => SliceKey::fingerprint_builder()
            .put_bytes(&application_key)
            .build(),
        SliceKeyFunctionP::Unspecified => {
            panic!("SliceKeyFunction unspecified")
        }
    }
}

/// Converts a `HighSliceKeyP` proto message to a [`HighSliceKey`].
///
/// # Panics
///
/// Panics if the proto has no key set.
pub fn high_slice_key_from_proto(high_slice_key_proto: &HighSliceKeyP) -> HighSliceKey {
    match &high_slice_key_proto.key {
        Some(Key::SliceKey(slice_key_proto)) => {
            // For ordered keys, we use identity function to create the SliceKey directly from bytes
            let application_key: Vec<u8> = slice_key_proto
                .application_key
                .iter()
                .map(|&i| i as u8)
                .collect();
            SliceKey::from_raw_bytes(&application_key).into_high_key()
        }
        Some(Key::InfinitySliceKey(_)) => HighSliceKey::Infinity,
        None => panic!("HighSliceKeyP has no key set"),
    }
}
