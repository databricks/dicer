use std::cmp::Ordering;
use std::fmt::Formatter;
use std::hash::Hash;

use derive_more::Debug;
use smallvec::SmallVec;
use thiserror::Error;

use crate::slice_key_builder::SliceKeyFingerprintBuilder;

/// Errors that can occur when building or creating a [`SliceKey`].
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum SliceKeyError {
    /// The slice key exceeds the maximum allowed length.
    #[error("must not exceed 32 bytes or 33 bytes with a trailing zero")]
    ExceedsMaxLength,
}

/// Dicer assigns ranges of Slice keys, known as Slices, to resources, like
/// pods. [`SliceKey`]s are derived from the application keys and routed by
/// Dicer `Clerk`s (clients of the affinitized service) to Dicer `Slicelet`s
/// (servers, e.g., individual pods, making up the affinitized service).
#[derive(Clone, Hash, Eq, Ord, PartialEq, PartialOrd)]
pub struct SliceKey {
    repr: SliceKeyRepr,
}

impl SliceKey {
    /// The minimum value that sorts before any other [`SliceKey`].
    pub const MIN: SliceKey = SliceKey {
        repr: SliceKeyRepr::new_const(),
    };

    /// Creates a new [`SliceKey`] by applying the Farmhash fingerprint64
    /// algorithm to the given application key. This is the recommended way to
    /// create a [`SliceKey`] from an application key as it provides somewhat
    /// uniformly distributed keys and is fast.
    ///
    /// # Deprecated
    /// Use [`SliceKey::fingerprint_builder`] instead for a more
    /// flexible API that allows incremental construction of keys.
    pub fn new_farmhash_fingerprint64(application_key: &[u8]) -> SliceKey {
        // For Java and Scala applications, we generally use the Guava hashing
        // library to compute the fingerprint, which emits little-endian bytes:
        // https://github.com/google/guava/blob/master/guava/src/com/google/common/hash/HashCode.java
        // For consistency, we emit the fingerprint in little-endian order here
        // as well.
        let fingerprint64: u64 = farmhash::fingerprint64(application_key);
        let bytes: [u8; 8] = fingerprint64.to_le_bytes();
        SliceKey::from_bytes(&bytes) // This converts the little-endian bytes to a SliceKey.
    }

    /// Creates a new builder for constructing a [`SliceKey`] using the Farmhash
    /// fingerprint64 algorithm. The builder allows incrementally adding key parts
    /// (longs, strings, bytes) which are then hashed together to produce an 8-byte
    /// fingerprint.
    ///
    /// If no values are put in the builder, returns the fingerprint for the empty
    /// string, _not_ [`SliceKey::MIN`].
    ///
    /// # Example
    /// ```
    /// use dicer_rust_external_types::{SliceKey, SliceKeyBuilder};
    ///
    /// let key = SliceKey::fingerprint_builder()
    ///     .put_string("user-")
    ///     .put_long(12345)
    ///     .build();
    /// ```
    pub fn fingerprint_builder() -> SliceKeyFingerprintBuilder {
        SliceKeyFingerprintBuilder::new()
    }

    /// Creates a [`SliceKey`] from uniformly distributed bytes.
    ///
    /// **IMPORTANT: This method is for ADVANCED USE ONLY.** For most applications, use
    /// [`SliceKey::fingerprint_builder`] instead, which automatically ensures
    /// uniform distribution and proper key construction.
    ///
    /// This method is intended for applications that need to generate custom slice keys
    /// with specific distribution properties (e.g., range-based sharding schemes).
    ///
    /// # Requirements
    /// - The input bytes must be uniformly distributed across the keyspace
    /// - Maximum length: 32 bytes
    /// - Bytes should have sufficient entropy to avoid hotspots
    ///
    /// # When to use this method
    /// Use this method when you are creating NEW slice keys from scratch with custom
    /// byte sequences that you have verified to be uniformly distributed.
    ///
    /// # When to use fromRawBytes instead
    /// If you need to deserialize a [`SliceKey`] that was previously serialized (e.g., from
    /// storage, a protobuf, or returned from [`SliceKey::to_raw_bytes`]), you should use
    /// `SliceKeyAccessor::from_raw_bytes` from the friend crate instead. Contact the maintainers
    /// to get allowlisted for friend accessor access.
    ///
    /// # Errors
    /// Returns [`SliceKeyError::ExceedsMaxLength`] if bytes exceeds 32 bytes. For this method the
    /// limit is 32 bytes (the "33 bytes with a trailing zero" wording on the error applies to the
    /// builder).
    ///
    /// # Examples
    /// ```
    /// use dicer_rust_external_types::SliceKey;
    ///
    /// // Advanced use: Creating a custom slice key from uniformly distributed bytes
    /// let bytes = [1, 2, 3, 4, 5, 6, 7, 8];
    /// let key = SliceKey::from_trusted_fingerprint(&bytes)
    ///     .expect("valid bytes");
    /// ```
    pub fn from_trusted_fingerprint(bytes: &[u8]) -> Result<SliceKey, SliceKeyError> {
        if bytes.len() > 32 {
            return Err(SliceKeyError::ExceedsMaxLength);
        }
        Ok(SliceKey::from_bytes(bytes))
    }

    /// Returns the raw bytes of this key. These bytes can be used as prefixes for storage keys
    /// to efficiently identify rows belonging to a given Slice.
    ///
    /// The returned bytes can be deserialized back to a [`SliceKey`] using
    /// `SliceKeyAccessor::from_raw_bytes` from the friend crate.
    ///
    /// # Example
    /// ```
    /// use dicer_rust_external_types::{SliceKey, SliceKeyBuilder};
    ///
    /// let key = SliceKey::fingerprint_builder()
    ///     .put_bytes(&[1, 2, 3, 4, 5, 6, 7, 8])
    ///     .build();
    /// assert_eq!(key.to_raw_bytes().len(), 8);
    /// ```
    pub fn to_raw_bytes(&self) -> &[u8] {
        &self.repr
    }

    /// Returns the high slice key representation of this key by reference.
    pub fn as_high_key_ref(&self) -> HighSliceKeyRef<'_> {
        HighSliceKeyRef::Finite(self)
    }

    /// Returns the high slice key representation of this key.
    pub fn into_high_key(self) -> HighSliceKey {
        HighSliceKey::Finite(self)
    }

    /// Formats this key as a human-readable string. Most bytes that are
    /// alphanumeric or punctuation ASCII characters are printed as characters.
    /// Backslashes, double-quotes, and all other bytes are printed as hex-
    /// encoded bytes.
    ///
    /// Empty keys are formatted as `""` (with quotes), while non-empty keys
    /// are formatted without surrounding quotes to match Scala's Bytes.toString behavior.
    fn fmt_bytes(&self, f: &mut Formatter<'_>) -> Result<(), std::fmt::Error> {
        if self.repr.is_empty() {
            return write!(f, "\"\"");
        }

        for b in &self.repr {
            if b.is_ascii_alphanumeric() || (b.is_ascii_punctuation() && *b != b'\\' && *b != b'"')
            {
                write!(f, "{}", *b as char)?;
            } else {
                write!(f, "\\x{:02X}", *b)?;
            }
        }
        Ok(())
    }

    /// REQUIRES: `self.len() >= 8`
    ///
    /// Formats the first 8 bytes of this key as a fixed-width, hex-encoded,
    /// unsigned 64-bit integer. This is useful to visualize Slice boundaries in
    /// a typical Dicer assignment.
    fn fmt_fingerprint(&self, f: &mut Formatter<'_>) -> Result<(), std::fmt::Error> {
        let fingerprint = u64::from_be_bytes(*self.repr.first_chunk().unwrap());
        write!(f, "0x{:016x}", fingerprint)
    }

    /// Converts the given slice of bytes to a [`SliceKey`].
    #[inline]
    pub(crate) fn from_bytes(bytes: &[u8]) -> SliceKey {
        SliceKey {
            repr: SliceKeyRepr::from(bytes),
        }
    }
}

impl Debug for SliceKey {
    /// Formats a human-readable version of the bytes in the [`SliceKey`] where
    /// we print most ASCII characters normally and all others are printed as
    /// hex codes.
    ///
    /// Keys of length 8 are assumed to be 64-bit fingerprints (which we
    /// recommend, see `new_farmhash_fingerprint64`) and are printed as fixed-
    /// width (leading zeroes), hex-encoded, and unsigned 64-bit integers to
    /// make assignment debug strings easier to interpret. Keys of length 9 that
    /// end in a 0 byte are printed as 8-byte keys with the suffix "\0" - these
    /// can appear due to the Assigner isolating hot keys.
    fn fmt(&self, f: &mut Formatter<'_>) -> Result<(), std::fmt::Error> {
        match &self.repr.len() {
            8 => self.fmt_fingerprint(f),
            9 => {
                if self.repr[8] == 0u8 {
                    self.fmt_fingerprint(f)?;
                    write!(f, "\\0")
                } else {
                    self.fmt_bytes(f)
                }
            }
            _ => self.fmt_bytes(f),
        }
    }
}

/// Represents the exclusive upper bound of a slice.
///
/// The reason for having an explicit representation of this rather than using
/// `Option<SliceKey>` is that `None` would sort before `Some` of a finite slice
/// key, whereas we need `Infinity` to sort after all finite slice keys.
pub type HighSliceKey = internal::HighSliceKeyRepr<SliceKey>;

impl HighSliceKey {
    /// Returns a [`HighSliceKeyRef`] that refers to this instance.
    pub fn as_ref(&self) -> HighSliceKeyRef<'_> {
        self.as_ref_internal()
    }
}

/// The by-reference equivalent of [`HighSliceKey`].
///
/// The purpose of this is that it allows you to turn a finite [`SliceKey`] that
/// you only have access to by reference into a `HighSliceKey` without cloning.
pub type HighSliceKeyRef<'a> = internal::HighSliceKeyRepr<&'a SliceKey>;

impl HighSliceKeyRef<'_> {
    /// Clones the underlying [`SliceKey`] (if any) to turn this into an owned
    /// [`HighSliceKey`].
    pub fn to_owned(self) -> HighSliceKey {
        self.map(|key: &SliceKey| key.clone())
    }
}

impl Copy for HighSliceKeyRef<'_> {}

/// A private module containing types that we want to reference in public types
/// but that should not be public themselves.
///
/// This is needed because Rust does not allow you to publicly export a type
/// alias for a private type, but it does allow you to export a type alias for
/// a public type in a private module, which achieves the same goal.
mod internal {
    use derive_more::Debug;

    use super::*;

    /// The internal representation of [`super::HighSliceKey`] and
    /// [`super::HighSliceKeyRef`].
    ///
    /// We use a single common underlying representation with a type parameter
    /// to avoid duplicating all the logic. The `SliceKeyT` generic type is
    /// always either `SliceKey` or `&SliceKey`.
    #[derive(Debug, Clone, Hash, Eq, PartialEq, Ord, PartialOrd)]
    pub enum HighSliceKeyRepr<SliceKeyT: AsSliceKey> {
        /// An actual slice key.
        #[debug("{_0:?}")]
        Finite(SliceKeyT),

        /// A sentinel that sorts after all [`SliceKey`] values to represent the
        /// exclusive upper bound of the key space.
        ///
        /// It's important that this is defined after `Finite` so that the
        /// default `Ord` implementation makes it sort after `Finite`.
        #[debug("∞")]
        Infinity,
    }

    impl<'a, SliceKeyT: AsSliceKey> HighSliceKeyRepr<SliceKeyT>
    where
        Self: 'a,
    {
        /// Returns a new `HighSliceKeyRepr` containing a transformation of the
        /// finite slice key (if there is one) as defined by the given function.
        pub(crate) fn map<NewSliceKeyT: AsSliceKey>(
            &'a self,
            f: impl FnOnce(&'a SliceKey) -> NewSliceKeyT,
        ) -> HighSliceKeyRepr<NewSliceKeyT> {
            match self {
                HighSliceKeyRepr::Finite(key) => HighSliceKeyRepr::Finite(f(key.as_slice_key())),
                HighSliceKeyRepr::Infinity => HighSliceKeyRepr::Infinity,
            }
        }

        /// Transforms this high slice key into a [`HighSliceKeyRef`] if it is
        /// not already one.
        ///
        /// This is separate from the publicly exposed `as_ref` method above,
        /// because the trait implementations below need to make use of this
        /// helper regardless of the specific parameterized type, but we only
        /// want to publicly expose `as_ref` when the parameter type is
        /// `&SliceKey`
        pub(crate) fn as_ref_internal(&'a self) -> HighSliceKeyRef<'a> {
            self.map(move |key: &SliceKey| key)
        }
    }

    // Equality comparison between `HighSliceKey` and `HighSliceKeyRef`:
    impl PartialEq<HighSliceKeyRef<'_>> for HighSliceKey {
        fn eq(&self, other: &HighSliceKeyRef) -> bool {
            self.as_ref() == *other
        }
    }
    impl PartialEq<HighSliceKey> for HighSliceKeyRef<'_> {
        fn eq(&self, other: &HighSliceKey) -> bool {
            *self == other.as_ref()
        }
    }

    // Equality comparison between slice keys and high slice keys:
    impl<SliceKeyT: AsSliceKey> PartialEq<SliceKey> for HighSliceKeyRepr<SliceKeyT> {
        fn eq(&self, other: &SliceKey) -> bool {
            self.as_ref_internal() == other.as_slice_key().as_high_key_ref()
        }
    }
    impl<SliceKeyT: AsSliceKey> PartialEq<HighSliceKeyRepr<SliceKeyT>> for SliceKey {
        fn eq(&self, other: &HighSliceKeyRepr<SliceKeyT>) -> bool {
            self.as_slice_key().as_high_key_ref() == other.as_ref_internal()
        }
    }

    // Ordering between slice keys and high slice keys:
    impl<SliceKeyT: AsSliceKey> PartialOrd<SliceKey> for HighSliceKeyRepr<SliceKeyT> {
        fn partial_cmp(&self, other: &SliceKey) -> Option<Ordering> {
            Some(
                self.as_ref_internal()
                    .cmp(&other.as_slice_key().as_high_key_ref()),
            )
        }
    }
    impl<SliceKeyT: AsSliceKey> PartialOrd<HighSliceKeyRepr<SliceKeyT>> for SliceKey {
        fn partial_cmp(&self, other: &HighSliceKeyRepr<SliceKeyT>) -> Option<Ordering> {
            Some(
                self.as_slice_key()
                    .as_high_key_ref()
                    .cmp(&other.as_ref_internal()),
            )
        }
    }

    /// A trait representing an underlying type that is either `SliceKey` or
    /// `&SliceKey`.
    pub trait AsSliceKey: Ord {
        /// Returns a reference to the underlying slice key.
        fn as_slice_key(&self) -> &SliceKey;
    }

    impl AsSliceKey for SliceKey {
        fn as_slice_key(&self) -> &SliceKey {
            self
        }
    }

    impl AsSliceKey for &SliceKey {
        fn as_slice_key(&self) -> &SliceKey {
            self
        }
    }
}

/// Internal representation for a [`SliceKey`]. Consider that most keys in Dicer
/// are either:
///  - 64-bit fingerprints that fit in 8 bytes.
///  - 9-byte keys to represent the exclusive upper limit for Slices containing
///    a single hot key, e.g., ["12345678", "12345678\0").
///
/// So we want a `SmallVec` with an inline capacity of at least 9 bytes. We
/// round up to 22 bytes because it makes no difference to the size of the
/// `SmallVec` instance (see assertion below).
type SliceKeyRepr = SmallVec<[u8; 22]>;

// Illustrate that `SliceKeyRepr` occupies 32 bytes, and that
// `SmallVec<[u8; 9]>` would occupy 32 bytes as well. Including as an assertion
// in the code in case we ever build with an architecture or features (e.g.,
// smallvec "union") that might warrant retuning the inline size.
static_assertions::assert_eq_size!([u8; 32], SliceKeyRepr, SmallVec<[u8; 9]>);

// ====================================================================================
// Friend API functions
//
// These functions are exposed for friend access and should only be used in consultation
// with the Caching team at the maintainers. They are kept at the module level (not as
// SliceKey methods) and are not exported in the public API crate.
// ====================================================================================

/// See [`dicer_rust_friend_external_slice_key_accessor::from_raw_bytes`].
pub fn slice_key_from_raw_bytes(bytes: &[u8]) -> SliceKey {
    SliceKey::from_bytes(bytes)
}
