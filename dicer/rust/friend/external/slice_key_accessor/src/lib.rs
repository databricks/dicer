//! Friend APIs for SliceKey deserialization.
//!
//! This module provides friend access to low-level SliceKey deserialization methods that require
//! review from the Dicer team. These methods should only be used in consultation with the Caching
//! team at the maintainers.
//!
//! See //dicer/friend/external/src/SliceKeyAccessor.scala for design notes explaining why there
//! are two distinct methods for creating SliceKeys from bytes: the public
//! [`SliceKey::from_trusted_fingerprint`] for creation with validation, and the
//! friend [`from_raw_bytes`] for deserialization without validation.
use dicer_rust_external_types::slice_key::slice_key_from_raw_bytes;
use dicer_rust_external_types::{HighSliceKey, Slice, SliceKey};

// Define a private module with a sealed marker trait to prevent external implementations of the
// SliceKeyAccessor trait, since that would make it hard for us to add new associated functions or
// methods to the trait in the future.
mod private {
    pub trait Sealed {}
}

/// Trait for accessing SliceKey deserialization methods.
pub trait SliceKeyAccessor: private::Sealed {
    /// Creates a [`SliceKey`] from raw bytes. Used exclusively to unmarshal bytes returned from
    /// [`SliceKey::to_raw_bytes`].
    ///
    /// This method performs no validation and accepts bytes of any length. It is intended only for
    /// deserializing previously serialized [`SliceKey`] instances, e.g., [`SliceKey`] bytes written
    /// to storage, to a header, or a protobuf. Do not use this to create new keys exceeding 32
    /// bytes; the call will not return an error but the behavior is undefined and may change in
    /// future versions.
    ///
    /// Note that validation is not performed on the bytes with this method, so it is critical that
    /// keys are initially constructed using a safe method, e.g., [`SliceKey::fingerprint_builder`]
    /// or [`SliceKey::from_trusted_fingerprint`].
    ///
    /// External applications may be allowlisted for this API by contacting the maintainers.
    ///
    /// # Examples
    ///
    /// ```
    /// use dicer_rust_external_types::{SliceKey, SliceKeyBuilder};
    /// use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;
    ///
    /// // Create a slice key and serialize it.
    /// let original = SliceKey::fingerprint_builder()
    ///     .put_string("user-")
    ///     .put_long(12345)
    ///     .build();
    /// let bytes = original.to_raw_bytes();
    ///
    /// // Deserialize it back.
    /// let deserialized = SliceKey::from_raw_bytes(bytes);
    /// assert_eq!(deserialized.to_raw_bytes(), bytes);
    /// ```
    fn from_raw_bytes(bytes: &[u8]) -> SliceKey {
        slice_key_from_raw_bytes(bytes)
    }

    /// Returns a [`Slice`] containing only `key`, i.e. `[key .. key+\0)`.
    ///
    /// # Examples
    ///
    /// ```
    /// use dicer_rust_external_types::{SliceKey, SliceKeyBuilder};
    /// use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;
    ///
    /// let key = SliceKey::fingerprint_builder().put_string("Balin").build();
    /// let slice = SliceKey::create_single_key_slice(&key);
    /// assert_eq!(slice.low_inclusive(), &key);
    /// assert!(slice.contains_key(&key));
    /// ```
    fn create_single_key_slice(key: &SliceKey) -> Slice {
        // The lowest-sorting key that is greater than `key` is `key` with a trailing zero byte
        // appended.
        let mut successor_bytes: Vec<u8> = key.to_raw_bytes().to_vec();
        successor_bytes.push(0);
        let successor: HighSliceKey =
            HighSliceKey::Finite(slice_key_from_raw_bytes(&successor_bytes));
        Slice::new(key.clone(), successor)
            .expect("appending a zero byte must produce a key that sorts after the original")
    }
}

// To allow callers to use the `SliceKeyAccessor` extensions with the `SliceKey` type (e.g.,
// `SliceKey::from_raw_bytes(bytes)`), we implement the `SliceKeyAccessor` trait for `SliceKey`.
impl SliceKeyAccessor for SliceKey {}

// Required for any implementation of `SliceKeyAccessor`.
impl private::Sealed for SliceKey {}
