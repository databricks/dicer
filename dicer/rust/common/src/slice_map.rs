use std::cmp::{max, min};
use std::iter::Peekable;

use tonic::Status;
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};

/// An immutable data structure representing a mapping from slices to arbitrary values that supports
/// efficient lookup of the value for a particular [`SliceKey`].
///
/// # Invariants
///
/// A `SliceMap` is immutable, and the builder ensures the invariant that `entries` are ordered by
/// `low_inclusive_key` and start at `SliceKey::MIN`. This means that every key is contained in
/// exactly one slice.
///
/// # Implementation Notes
///
/// We choose to store the entries in a vector rather than a BTreeMap to simplify the code somewhat
/// and to get modestly better performance, since the contents are immutable. In a microbenchmark,
/// the vector-based implementation is roughly 30% faster than the BTreeMap-based implementation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SliceMap<T> {
    /// The ordered, non-overlapping slice entries covering the full key space.
    entries: Vec<Entry<T>>,
}

impl<T> SliceMap<T> {
    /// Returns a builder for a new `SliceMap`.
    pub fn builder() -> SliceMapBuilder<T> {
        SliceMapBuilder::default()
    }

    /// Returns a builder for a `SliceMap` that doesn't cover the entire key space, using
    /// `Option<T>` as the value type and `None` as the value for the gaps.
    pub fn partial_builder() -> PartialSliceMapBuilder<T> {
        PartialSliceMapBuilder {
            builder: SliceMap::builder(),
        }
    }

    /// The number of slices in the map.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether the map is empty. Never true, but this method is required when `len` is defined.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Looks up the entry containing `key`.
    pub fn look_up(&self, key: &SliceKey) -> SliceMapEntryRef<'_, T> {
        // Binary search for the Slice with the greatest low_inclusive_key that is less than or
        // equal to `key`. Relies on invariant that `entries` contains disjoint slices covering all
        // possible keys. `binary_search_by_key` returns either an Ok result with an exact match
        // (meaning some slice has exactly the same `low_inclusive_key` as `key`), or an Err result
        // with the index at which `key` would be inserted to maintain the ordering, meaning that
        // the slice containing that key is the one immediately before the insertion index.
        let index: Result<usize, usize> = self
            .entries
            .binary_search_by_key(&key, |entry: &Entry<T>| &entry.low_inclusive_key);
        let index: usize = index.unwrap_or_else(|insertion_index: usize| {
            assert!(
                insertion_index > 0,
                "Inserting before the first slice which starts at SliceKey::MIN?"
            );
            insertion_index - 1
        });
        let entry: &Entry<T> = &self.entries[index];
        let high_exclusive_key: HighSliceKeyRef = self
            .entries
            .get(index + 1) // None if this is the last slice
            .map(|next: &Entry<T>| next.low_inclusive_key.as_high_key_ref())
            .unwrap_or(HighSliceKeyRef::Infinity);
        SliceMapEntryRef {
            value: &entry.value,
            low_inclusive_key: &entry.low_inclusive_key,
            high_exclusive_key,
        }
    }

    /// Returns an ordered iterator over the slice map's entries.
    pub fn iter(&self) -> SliceMapIter<'_, T> {
        SliceMapIter {
            inner: self.entries.iter().peekable(),
        }
    }
}

impl<T> SliceMap<Option<T>> {
    /// Returns an iterator over the `Some` entries in a partial slice map.
    pub fn non_empty_entries(&self) -> impl Iterator<Item = SliceMapEntryRef<'_, T>> {
        self.iter()
            .filter_map(|entry: SliceMapEntryRef<Option<T>>| {
                entry.value().as_ref().map(|value: &T| SliceMapEntryRef {
                    value,
                    low_inclusive_key: entry.low_inclusive_key(),
                    high_exclusive_key: entry.high_exclusive_key(),
                })
            })
    }
}

impl<'a, L, R> SliceMap<IntersectionEntry<'a, L, R>> {
    /// Returns intersections between entries in `left` and `right`. We illustrate sample `left` and
    /// `right` inputs and the expected `result` below:
    ///
    /// ```ignore
    /// left:     | A            | B       | C       | D                    |
    /// right:    | 1    | 2                               | 3    | 4       |
    /// result:   | A,1  | A,2   | B,2     | C,2     | D,2 | D,3  | D,4     |
    /// ```
    ///
    /// As you can see, the entry `A` on the left intersects entries `1` and `2` on the right, so
    /// the result includes intersection entries `A,1` and `A,2`.
    ///
    /// The lifetime of the entries in the returned slice map is tied to the lifetime of the input
    /// slice maps. Intersection slice maps are only used transiently, so there's no point in
    /// cloning.
    pub fn intersect(
        left: &'a SliceMap<L>,
        right: &'a SliceMap<R>,
    ) -> SliceMap<IntersectionEntry<'a, L, R>> {
        let mut builder = SliceMap::builder();

        // Recall that both of the maps are ordered, complete, and disjoint (i.e. ordered partitions
        // of the key space). We exploit this feature of the maps, advancing through the key space
        // from "" to ∞, and preserving the invariant that the Slices on the left and the right are
        // always intersecting.
        let mut left_it = left.iter().peekable();
        let mut right_it = right.iter().peekable();
        while let Some(left_entry) = left_it.peek() {
            let right_entry: SliceMapEntryRef<R> = *right_it.peek().expect(
                "Left and right must advance past their last ∞ Slices during the same iteration",
            );

            // Find the intersection of the left- and right-hand entries, which must be defined
            // because of the invariant described above.
            let intersection_low: &SliceKey =
                max(left_entry.low_inclusive_key(), right_entry.low_inclusive_key());
            let intersection_high: HighSliceKeyRef =
                min(left_entry.high_exclusive_key(), right_entry.high_exclusive_key());

            // `expect` is safe here because both input slice maps inherently cover the entire key
            // space, so we know that there will not be gaps between the intersection slices we add
            // to the builder.
            builder
                .add_slice(
                    intersection_low.clone(),
                    intersection_high.to_owned(),
                    IntersectionEntry {
                        left: *left_entry,
                        right: right_entry,
                    },
                )
                .expect("Unexpectedly failed to add intersection slice");

            // Advance left and right iterators if the current entries have been consumed so that we
            // preserve the invariant that the Slices at `left_it` and `right_it` are intersecting and so
            // that we make progress.
            if left_entry.high_exclusive_key() == intersection_high {
                left_it.next();
            }
            if right_entry.high_exclusive_key() == intersection_high {
                right_it.next();
            }
        }

        // Sanity check that we reached the end of both iterators.
        assert!(left_it.next().is_none(), "Left SliceMap not fully consumed");
        assert!(right_it.next().is_none(), "Right SliceMap not fully consumed");

        // `expect` is safe here because it can only fail if we did not add slices to the builder
        // extending all the way to the end of the key space, but we are guaranteed to have done so
        // above because the input slice maps both extend to the end of the key space (as this is an
        // invariant of `SliceMap`) and we add a slice corresponding to each intersection between
        // them.
        builder
            .build()
            .expect("Unexpectedly failed to build intersection SliceMap")
    }
}

impl<'a, T> IntoIterator for &'a SliceMap<T> {
    type Item = SliceMapEntryRef<'a, T>;
    type IntoIter = SliceMapIter<'a, T>;

    fn into_iter(self) -> Self::IntoIter {
        self.iter()
    }
}

/// An iterator over the entries of a [`SliceMap`].
pub struct SliceMapIter<'a, T> {
    inner: Peekable<std::slice::Iter<'a, Entry<T>>>,
}

impl<'a, T> Iterator for SliceMapIter<'a, T> {
    type Item = SliceMapEntryRef<'a, T>;

    fn next(&mut self) -> Option<Self::Item> {
        let entry: &Entry<T> = self.inner.next()?;
        let high_exclusive_key: HighSliceKeyRef = self
            .inner
            .peek()
            .map(|next: &&Entry<T>| next.low_inclusive_key.as_high_key_ref())
            .unwrap_or(HighSliceKeyRef::Infinity);
        Some(SliceMapEntryRef {
            value: &entry.value,
            low_inclusive_key: &entry.low_inclusive_key,
            high_exclusive_key,
        })
    }
}

/// A view of the entry for a slice, returned by [`SliceMap::look_up`].
///
/// Its lifetime is tied to the `SliceMap` from which the lookup was made.
#[derive(Debug, Eq, PartialEq)]
pub struct SliceMapEntryRef<'a, T> {
    value: &'a T,
    low_inclusive_key: &'a SliceKey,
    high_exclusive_key: HighSliceKeyRef<'a>,
}

impl<'a, T> SliceMapEntryRef<'a, T> {
    /// The low inclusive key of the slice.
    pub fn low_inclusive_key(&self) -> &'a SliceKey {
        self.low_inclusive_key
    }

    /// The high exclusive key of the slice.
    pub fn high_exclusive_key(&self) -> HighSliceKeyRef<'a> {
        self.high_exclusive_key
    }

    /// The value for the slice.
    pub fn value(&self) -> &'a T {
        self.value
    }

    /// Gets a cloned value for the slice.
    pub fn to_slice(&self) -> Slice {
        // Since we guarantee that the `low_inclusive_key` is less than the `high_exclusive_key`
        // in the `SliceMap`, we can safely unwrap the result of `Slice::new`.
        Slice::new(self.low_inclusive_key().to_owned(), self.high_exclusive_key().to_owned())
            .unwrap()
    }
}

// We need to manually implement Clone and Copy for this type, because the auto-derived versions
// would only be implemented when T implements Clone, but since T is behind a reference, we don't
// actually need that constraint.
impl<T> Clone for SliceMapEntryRef<'_, T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T> Copy for SliceMapEntryRef<'_, T> {}

/// A trait for [`SliceMap`] builders, implemented by both [`SliceMapBuilder`] and
/// [`PartialSliceMapBuilder`].
///
/// This allows common code (e.g. proto parsing logic) to be written for building both full and
/// partial slice maps.
///
/// The reason for the `Sized` constraint is that a method in a trait can only return `Self` if the
/// size of `Self` is known.
pub trait BuildSliceMap: Sized {
    /// The types of values that are passed to [`BuildSliceMap::add_slice`].
    type InputValue;

    /// The types of values that are stored in the resulting [`SliceMap`].
    ///
    /// For full slice maps, this is the same as `InputValue`. For partial slice maps, it is
    /// `Option<InputValue>`.
    type OutputValue;

    /// Sets the value for the next slice.
    ///
    /// See the documentation on the implementations of this method for details on how its semantics
    /// differ between full and partial slice map builders.
    fn add_slice(
        &mut self,
        low_inclusive_key: SliceKey,
        high_exclusive_key: HighSliceKey,
        value: Self::InputValue,
    ) -> Result<(), Status>;

    /// Builds the `SliceMap` from the slices and values that have been added to the builder.
    ///
    /// See the documentation on the implementations of this method for details on how its semantics
    /// differ between full and partial slice map builders.
    fn build(self) -> Result<SliceMap<Self::OutputValue>, Status>;
}

/// A builder for new [`SliceMap`] instances.
#[derive(Debug)]
pub struct SliceMapBuilder<T> {
    entries: Vec<Entry<T>>,

    /// The required low key for the next slice, or `Infinity` if the slices already cover the
    /// entire key space and no more slices can be added.
    cursor: HighSliceKey,
}

impl<T> Default for SliceMapBuilder<T> {
    fn default() -> Self {
        SliceMapBuilder {
            entries: Vec::new(),
            cursor: HighSliceKey::Finite(SliceKey::MIN),
        }
    }
}

impl<T> BuildSliceMap for SliceMapBuilder<T> {
    type InputValue = T;
    type OutputValue = T;

    /// Sets the value for the next slice.
    ///
    /// We take the high slice key explicitly (as opposed to implicitly using the low key of the
    /// next slice) because we use this builder when parsing assignment protos, which explicitly
    /// specify the high key of slices. We need to validate those high keys somewhere, and this
    /// builder is a convenient place to do so.
    ///
    /// VALIDATES:
    /// - `low_inclusive_key` must be less than `high_exclusive_key`.
    /// - `low_inclusive_key` must equal the `high_exclusive_key` of the previous slice, or
    ///   `SliceKey::MIN` if this is the first slice.
    fn add_slice(
        &mut self,
        low_inclusive_key: SliceKey,
        high_exclusive_key: HighSliceKey,
        value: T,
    ) -> Result<(), Status> {
        if high_exclusive_key <= low_inclusive_key {
            return Err(Status::invalid_argument(format!(
                "Slice must not be empty: [{low_inclusive_key:?}, {high_exclusive_key:?})"
            )));
        }

        if self.cursor == HighSliceKey::Infinity {
            return Err(Status::failed_precondition(
                "Only last entry may have Infinity upper bound",
            ));
        };

        if low_inclusive_key != self.cursor {
            if self.entries.is_empty() {
                return Err(Status::invalid_argument(format!(
                    "First entry must start at \"\": actual=[{low_inclusive_key:?} .. \
                     {high_exclusive_key:?})",
                )));
            } else {
                return Err(Status::invalid_argument(format!(
                    "{}: slice ending at {:?} -> slice starting at {:?}",
                    if low_inclusive_key > self.cursor {
                        "Gap between successive entries"
                    } else {
                        "Overlap between successive entries (must be ordered and disjoint)"
                    },
                    self.cursor,
                    low_inclusive_key
                )));
            }
        }

        self.entries.push(Entry {
            low_inclusive_key,
            value,
        });
        self.cursor = high_exclusive_key;
        Ok(())
    }

    /// Builds the `SliceMap` from the slices and values that have been added to the builder.
    ///
    /// VALIDATES: The added slices cover the entire key space.
    fn build(self) -> Result<SliceMap<T>, Status> {
        if self.entries.is_empty() {
            return Err(Status::invalid_argument("Must not be empty"));
        } else if self.cursor != HighSliceKey::Infinity {
            return Err(Status::invalid_argument(format!(
                "Last entry must have Infinity upper bound, but ends at {:?}",
                self.cursor
            )));
        }

        Ok(SliceMap {
            entries: self.entries,
        })
    }
}

/// A builder for a `SliceMap<Option<T>>` that doesn't cover the entire key space.
#[derive(Debug)]
pub struct PartialSliceMapBuilder<T> {
    builder: SliceMapBuilder<Option<T>>,
}

// We manually implement `Default` because the auto-derived version would require `T` to implement
// `Default`.
impl<T> Default for PartialSliceMapBuilder<T> {
    fn default() -> Self {
        PartialSliceMapBuilder {
            builder: SliceMapBuilder::default(),
        }
    }
}

impl<T> BuildSliceMap for PartialSliceMapBuilder<T> {
    type InputValue = T;
    type OutputValue = Option<T>;

    /// Sets the value for the next slice.
    ///
    /// It's permissible to have a gap in the key space between consecutive slices, in which case
    /// that missing slice will map to `None` as the value in the resulting `SliceMap`.
    ///
    /// VALIDATES:
    /// - `low_inclusive_key` must be less than `high_exclusive_key`.
    /// - `low_inclusive_key` must be greater than or equal to the `high_exclusive_key` of the
    ///   previous slice (if there is a previous slice).
    fn add_slice(
        &mut self,
        low_inclusive_key: SliceKey,
        high_exclusive_key: HighSliceKey,
        value: T,
    ) -> Result<(), Status> {
        if let HighSliceKey::Finite(cursor) = &self.builder.cursor
            && *cursor < low_inclusive_key
        {
            // There's a gap between the previous slice and this slice, so map the gap to `None`
            // in the underlying builder.
            let cursor: SliceKey = cursor.clone();
            self.builder.add_slice(
                /*low_inclusive_key=*/ cursor,
                /*high_exclusive_key=*/ HighSliceKey::Finite(low_inclusive_key.clone()),
                /*value=*/ None,
            )?;
        }

        self.builder
            .add_slice(low_inclusive_key, high_exclusive_key, Some(value))
    }

    /// Builds the `SliceMap` from the slices and values that have been added to the builder.
    fn build(mut self) -> Result<SliceMap<Option<T>>, Status> {
        if let HighSliceKey::Finite(cursor) = &self.builder.cursor {
            // The last slice did not extend to the end of the key space, so add a `None` entry for
            // the remaining portion of the key space.
            let cursor: SliceKey = cursor.clone();
            self.builder.add_slice(
                /*low_inclusive_key=*/ cursor,
                /*high_exclusive_key=*/ HighSliceKey::Infinity,
                /*value=*/ None,
            )?;
        }

        self.builder.build()
    }
}

/// The entry for a single slice in the [`SliceMap`].
#[derive(Clone, Debug, Eq, PartialEq)]
struct Entry<T> {
    /// The lower bound of the slice. The upper bound is implicitly the lower bound of the next
    /// slice, or the end of the key space if this is the last entry.
    low_inclusive_key: SliceKey,

    /// The value that this slice is mapped to.
    value: T,
}

/// An entry in a Slice map created using [`SliceMap::intersect`].
#[derive(Debug, Eq, PartialEq)]
pub struct IntersectionEntry<'a, L, R> {
    /// The zipped entry from the left-hand slice map.
    pub left: SliceMapEntryRef<'a, L>,

    /// The zipped entry from the right-hand slice map.
    pub right: SliceMapEntryRef<'a, R>,
}

// We need to manually implement Clone and Copy for this type, because the auto-derived versions
// would only be implemented when L and R implement Clone, but since L and R are behind references,
// we don't actually need that constraint.
impl<L, R> Clone for IntersectionEntry<'_, L, R> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<L, R> Copy for IntersectionEntry<'_, L, R> {}
