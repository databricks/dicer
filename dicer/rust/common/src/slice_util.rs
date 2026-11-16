//! Utilities for working with slices that are not exposed in the public API.

use tonic::Status;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_friend_proto_friend_proto_library::dicer::friend::SliceP;
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;

/// Parses a [`Slice`] from its proto representation.
pub fn slice_from_proto(proto: &SliceP) -> Result<Slice, Status> {
    let low_key: SliceKey = SliceKey::from_raw_bytes(proto.low_inclusive.unwrap_ref_or_default());
    let high_key: HighSliceKey = proto
        .high_exclusive
        .as_ref()
        .map(|bytes: &Vec<u8>| SliceKey::from_raw_bytes(bytes).into_high_key())
        .unwrap_or(HighSliceKey::Infinity);
    Slice::new(low_key, high_key)
}

/// Returns the proto representation of a [`Slice`].
///
/// We don't take a `Slice` reference as a parameter, because that would force callers to clone if
/// they just have references to slice bounds that are not already within a `Slice`.
pub fn slice_to_proto(low_inclusive: &SliceKey, high_exclusive: HighSliceKeyRef) -> SliceP {
    SliceP {
        low_inclusive: Some(low_inclusive.to_raw_bytes().to_vec()),
        high_exclusive: match high_exclusive {
            HighSliceKeyRef::Infinity => None,
            HighSliceKeyRef::Finite(key) => Some(key.to_raw_bytes().to_vec()),
        },
    }
}
