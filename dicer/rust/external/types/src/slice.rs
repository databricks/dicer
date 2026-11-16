use std::sync::LazyLock;

use derive_more::Debug;
use tonic::Status;

use crate::{HighSliceKey, HighSliceKeyRef, SliceKey};

/// A Slice represents a range of [`SliceKey`]s of the form [low_inclusive .. high_exclusive). For
/// example, the Slice ["A" .. "B") contains all keys that are greater than or equal to "A" and
/// strictly less than "B".
///
/// The high key value may be the [`HighSliceKey::Infinity`] sentinel, which sorts after any other
/// key. For example, the Slice ["M" .. ∞) contains all keys that are greater than or equal to "M".
///
/// There is no representation of an empty Slice, as per the requirement of the type, `low_inclusive`
/// must be less than `high_exclusive`.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[debug("[{low_inclusive:?} .. {high_exclusive:?})")]
pub struct Slice {
    /// The inclusive lower bound for the Slice.
    low_inclusive: SliceKey,

    /// The exclusive upper bound for the Slice.
    high_exclusive: HighSliceKey,
}

impl Slice {
    /// Creates a new Slice with the given inclusive lower bound and exclusive upper bound.
    ///
    /// # Errors
    ///
    /// Returns an error if the `low_inclusive` key is not less than the `high_exclusive` key.
    pub fn new(low_inclusive: SliceKey, high_exclusive: HighSliceKey) -> Result<Slice, Status> {
        if low_inclusive >= high_exclusive {
            return Err(Status::invalid_argument(format!(
                "Low key must be less than high key: {low_inclusive:?} >= {high_exclusive:?}"
            )));
        }

        Ok(Slice {
            low_inclusive,
            high_exclusive,
        })
    }

    /// A Slice that covers the entire key space.
    pub fn full() -> &'static Slice {
        static FULL: LazyLock<Slice> =
            LazyLock::new(|| Slice::new(SliceKey::MIN, HighSliceKey::Infinity).unwrap());
        &FULL
    }

    /// The inclusive lower bound for the Slice.
    pub fn low_inclusive(&self) -> &SliceKey {
        &self.low_inclusive
    }

    /// The exclusive upper bound for the Slice.
    pub fn high_exclusive(&self) -> HighSliceKeyRef<'_> {
        self.high_exclusive.as_ref()
    }

    /// Consumes the Slice and returns its lower and upper bounds by value.
    pub fn into_bounds(self) -> (SliceKey, HighSliceKey) {
        (self.low_inclusive, self.high_exclusive)
    }

    /// Returns whether `key` is contained in this Slice.
    pub fn contains_key(&self, key: &SliceKey) -> bool {
        self.low_inclusive <= *key && *key < self.high_exclusive
    }
}
