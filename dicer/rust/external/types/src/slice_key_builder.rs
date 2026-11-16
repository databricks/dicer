use crate::{SliceKey, SliceKeyError};

/// A trait for building [`SliceKey`] instances incrementally by adding key parts.
pub trait SliceKeyBuilder: Sized {
    /// Adds a long (i64) value to the key being built.
    fn put_long(self, value: i64) -> Self;

    /// Adds a string value to the key being built (UTF-8 encoded).
    fn put_string(self, value: &str) -> Self;

    /// Adds bytes to the key being built.
    fn put_bytes(self, value: &[u8]) -> Self;

    /// Builds the final [`SliceKey`].
    ///
    /// # Errors
    /// May return [`SliceKeyError::ExceedsMaxLength`] if the resulting key is too long. Note:
    /// [`SliceKey::fingerprint_builder`] never produces overly long keys (always returns an
    /// 8-byte [`SliceKey`]). [`SliceKeySegmentedFingerprintBuilder<N>`] never produces overly long
    /// keys (always returns an N * 8-byte [`SliceKey`], and N is never greater than 8).
    fn build(self) -> Result<SliceKey, SliceKeyError>;
}

/// A builder for creating [`SliceKey`] instances using the Farmhash fingerprint64
/// algorithm. All key parts added to the builder are concatenated and then hashed
/// to produce an 8-byte fingerprint.
pub type SliceKeyFingerprintBuilder = SliceKeySegmentedFingerprintBuilder<1>;

/// A builder for creating [`SliceKey`] instances composed of one or more key segments. Each key
/// segment is calculated as the fingerprint64 of a byte sequence, where the byte sequence is
/// accumulated by calling [`put_long`], [`put_string`] or [`put_bytes`].
///
/// [`next_fingerprint`] closes the current segment and starts a new one, and [`build`] closes the
/// last segment and builds the final SliceKey. The resulting key is the concatenation of the
/// fingerprint64 values in each segment, in the order the segments were closed.
///
/// Each [`SliceKeySegmentedFingerprintBuilder<N>`] always builds SliceKeys with exactly `N`
/// segments (i.e. containing 8 * N bytes). Users call [`next_fingerprint`] to create the next
/// [`SliceKeySegmentedFingerprintBuilder<N+1>`].
///
/// `N` must be no more than 8 (that is, [`next_fingerprint`] can be called no more than 7 times
/// from SliceKeySegmentedFingerprintBuilder<1>). Otherwise, there will be a compile error.
///
/// For example, the following code
///
/// ```text
/// use dicer_rust_external::{SliceKey, SliceKeyBuilder};
/// use dicer_rust_friend_external_slice_key_segmented_fingerprint_builder::{
///     NextFingerprint,
/// };
///
/// SliceKey::fingerprint_builder()
/// .put_bytes().put_long().put_string()  // First segment
/// .next_fingerprint()
/// .put_string().put_long().put_long()   // Second segment
/// .next_fingerprint()
/// .put_bytes()    // Third segment
/// .build()
/// ```
///
/// will result in the following SliceKey:
///
/// ```text
/// |----bytes---|long|--string--|------string------|long|long|---------bytes----------|
///                              ↓                            ↓                        |
///                     next_fingerprint()              next_fingerprint()             |
///                              |                            |                        ↓
///                              |                            |                      build()
/// |      fingerprint1          |      fingerprint2          |    fingerprint3        |
/// |----------------------------------------------------------------------------------|
/// |                               The final SliceKey                                 |
/// ```
///
/// Note that two consecutive `next_fingerprint` calls also append a new 8-byte fingerprint segment
/// into the SliceKey.
///
/// Note that a builder on which `next_fingerprint` is never called produces a single 8-byte
/// fingerprint of all bytes that are put into the builder.
///
/// # Type parameters
/// - `N`: The number of fingerprint64 segments that the SliceKey built from this builder will
///        contain.
pub struct SliceKeySegmentedFingerprintBuilder<const N: usize> {
    /// The buffer of the raw bytes that is being accumulated for the current open segment.
    opened_segment_buffer: Vec<u8>,
    // The fingerprint64 values of the `N - 1` closed segments.
    segmented_fingerprints: Vec<u64>,
}

/// The maximum number of segments in a [`SliceKey`] built by
/// [`SliceKeySegmentedFingerprintBuilder`]. Only used for compile-time sanity checking.
const MAX_SEGMENTS: usize = 8;

impl SliceKeySegmentedFingerprintBuilder<1> {
    /// Creates a new builder with a single open segment.
    pub(crate) fn new() -> Self {
        Self {
            opened_segment_buffer: Vec::new(),
            segmented_fingerprints: Vec::with_capacity(MAX_SEGMENTS),
        }
    }
}

impl<const N: usize> SliceKeySegmentedFingerprintBuilder<N> {
    /// Builds the final [`SliceKey`]. Note that this builder never returns an error, because the
    /// compiler ensures the builder builds SliceKeys of exactly `N` fingerprint64 segments, and `N`
    /// is never greater than 8.
    pub fn build(mut self) -> SliceKey {
        // Compile-time sanity check for the number of segments.
        const { assert!(N >= 1 && N <= MAX_SEGMENTS, "N must be in [1, MAX_SEGMENTS]") };

        self.segmented_fingerprints
            .push(farmhash::fingerprint64(&self.opened_segment_buffer));
        let mut bytes: Vec<u8> = Vec::with_capacity(N * size_of::<u64>());
        for fingerprint64 in self.segmented_fingerprints {
            bytes.extend_from_slice(&fingerprint64.to_le_bytes());
        }
        SliceKey::from_bytes(&bytes)
    }

    /// Closes the open segment and opens a new one, while returning
    /// SliceKeySegmentedFingerprintBuilder<M> with the updated segments. Internal helper used to
    /// simplify the `impl_next_fingerprint` macro.
    fn next_fingerprint_internal<const M: usize>(
        mut self,
    ) -> SliceKeySegmentedFingerprintBuilder<M> {
        // Even if opened_segment_buffer is empty, the closed segment contributes a
        // fingerprint.
        self.segmented_fingerprints
            .push(farmhash::fingerprint64(&self.opened_segment_buffer));
        self.opened_segment_buffer.clear();
        SliceKeySegmentedFingerprintBuilder::<M> {
            opened_segment_buffer: self.opened_segment_buffer,
            segmented_fingerprints: self.segmented_fingerprints,
        }
    }
}

/// A trait that provides the [next_fingerprint] method for [SliceKeySegmentedFingerprintBuilder].
/// This trait is used to restrict visibility of the [next_fingerprint] method. As of Oct 2026, this
/// trait is only reexported in dicer/rust/friend/external/slice_key_segmented_fingerprint_builder
/// and not accessible by arbitrary customers.
/// TODO(<internal bug>): After load-testing Dicer Assigner for its capacity to handle 64-byte keys for all
///                targets, reexported this in dicer/rust/external.
pub trait NextFingerprint {
    /// The builder with one more segment.
    type Next;

    /// Closes the open segment and opens a new one. See the main doc of
    /// [`SliceKeySegmentedFingerprintBuilder`] for its meaning and usage.
    fn next_fingerprint(self) -> Self::Next;
}

/// impl_next_fingerprint!(n) implements [`NextFingerprint`] for
/// SliceKeySegmentedFingerprintBuilder<$n>.
macro_rules! impl_next_fingerprint {
    ($n:literal) => {
        impl NextFingerprint for SliceKeySegmentedFingerprintBuilder<$n> {
            type Next = SliceKeySegmentedFingerprintBuilder<{ $n + 1 }>;

            fn next_fingerprint(self) -> Self::Next {
                self.next_fingerprint_internal::<{ $n + 1 }>()
            }
        }
    };
}

// Implement NextFingerprint for SliceKeySegmentedFingerprintBuilder<N> for 1 <= N <= 7, so the
// number of fingerprint segments can never be more than [`MAX_SEGMENTS`].
impl_next_fingerprint!(1);
impl_next_fingerprint!(2);
impl_next_fingerprint!(3);
impl_next_fingerprint!(4);
impl_next_fingerprint!(5);
impl_next_fingerprint!(6);
impl_next_fingerprint!(7);

impl<const N: usize> SliceKeyBuilder for SliceKeySegmentedFingerprintBuilder<N> {
    fn put_long(mut self, value: i64) -> Self {
        // Use little-endian byte order to match Guava's behavior.
        self.opened_segment_buffer
            .extend_from_slice(&value.to_le_bytes());
        self
    }

    fn put_string(mut self, value: &str) -> Self {
        self.opened_segment_buffer
            .extend_from_slice(value.as_bytes());
        self
    }

    fn put_bytes(mut self, value: &[u8]) -> Self {
        self.opened_segment_buffer.extend_from_slice(value);
        self
    }

    fn build(self) -> Result<SliceKey, SliceKeyError> {
        // For the segment builder, we use the infallible build() method.
        Ok(SliceKeySegmentedFingerprintBuilder::<N>::build(self))
    }
}
