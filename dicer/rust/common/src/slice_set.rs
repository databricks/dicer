use derive_more::Debug;
use rangemap::RangeSet;
use dicer_rust_external_types::{HighSliceKey, Slice, SliceKey};

/// An immutable set of disconnected Slices.
///
/// The internal representation is canonical: any two sets containing the same Slice keys are
/// represented as the same sequence of ordered, disconnected Slices.
///
/// Note that when building a `SliceSet` from an iterator of [`Slice`]s using `Iterator::collect`,
/// the input slices are not required to be ordered, disjoint, or non-overlapping. That
/// canonicalization occurs automatically.
#[derive(Debug, Default, PartialEq, Eq)]
#[debug("{slices:?}")]
pub struct SliceSet {
    /// An ordered, disconnected sequence of Slices.
    slices: Vec<Slice>,
}

impl SliceSet {
    /// Returns an empty `SliceSet`.
    pub fn empty() -> SliceSet {
        SliceSet::default()
    }

    /// Returns an iterator over the slices in the set.
    pub fn iter(&self) -> std::slice::Iter<'_, Slice> {
        self.slices.iter()
    }
}

impl FromIterator<Slice> for SliceSet {
    fn from_iter<T: IntoIterator<Item = Slice>>(iter: T) -> Self {
        // Build a `RangeSet` of the slices to conveniently merge overlapping and adjacent ones.
        //
        // We can't write this using `map` and `collect` calls on the iterator, because that results
        // in the compiler failing with "error: reached the recursion limit while instantiating" for
        // some reason.
        let mut range_set: RangeSet<HighSliceKey> = RangeSet::new();
        for slice in iter.into_iter() {
            let (low_inclusive, high_exclusive): (SliceKey, HighSliceKey) = slice.into_bounds();
            range_set.insert(low_inclusive.into_high_key()..high_exclusive);
        }

        // Convert the `RangeSet` entries back into Slices.
        let mut vec: Vec<Slice> = Vec::with_capacity(range_set.len());
        for range in range_set {
            let low_key: SliceKey = match range.start {
                HighSliceKey::Finite(key) => key,
                HighSliceKey::Infinity => {
                    // This is unreachable, because we never use `Infinity` as a lower bound for a
                    // range.
                    panic!("RangeSet should not have contained infinity as a start key")
                }
            };
            // This `unwrap` is safe, because `RangeSet` ensures that we will never try to construct
            // an empty slice.
            vec.push(Slice::new(low_key, range.end).unwrap());
        }
        SliceSet { slices: vec }
    }
}

impl IntoIterator for SliceSet {
    type Item = Slice;
    type IntoIter = std::vec::IntoIter<Slice>;

    fn into_iter(self) -> Self::IntoIter {
        self.slices.into_iter()
    }
}

impl<'a> IntoIterator for &'a SliceSet {
    type Item = &'a Slice;
    type IntoIter = std::slice::Iter<'a, Slice>;

    fn into_iter(self) -> Self::IntoIter {
        self.slices.iter()
    }
}
