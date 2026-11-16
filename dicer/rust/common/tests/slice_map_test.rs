use std::cmp::{max, min};
use std::collections::BTreeSet;

use rand::{rng, RngCore};
use tonic::Status;
use dicer_common_test_proto_test_data_proto::dicer::test::slice_map_test_data_p::intersect_test_case_p::{
    self,
    LeftEntryP,
    RightEntryP,
};
use dicer_common_test_proto_test_data_proto::dicer::test::slice_map_test_data_p::partial_slice_map_test_case_p::ExpectedEntryP;
use dicer_common_test_proto_test_data_proto::dicer::test::slice_map_test_data_p::partial_slice_map_test_case_p::ValueTypeP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SliceP;
use dicer_rust_common::{BuildSliceMap, IntersectionEntry, PartialSliceMapBuilder, SliceMap, SliceMapBuilder, SliceMapEntryRef};
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_testing::test_data::SLICE_MAP_TEST_DATA;
use dicer_rust_testing::test_slice_utils::{identity, identity_bytes};

/// Randomly generates a [`SliceMap`] containing the given number of slices.
///
/// The value of the `SliceMap` is the lower bound of the given entry's slice.
///
/// # Panics
///
/// Panics if `size` is zero.
fn create_random_slice_map(size: usize) -> SliceMap<SliceKey> {
    assert_ne!(size, 0);

    // Choose the lower bounds of the slices.
    let mut lows: BTreeSet<SliceKey> = BTreeSet::new();
    lows.insert(SliceKey::MIN);
    while lows.len() < size {
        lows.insert(random_slice_key());
    }

    // Build the SliceMap.
    let mut builder: SliceMapBuilder<SliceKey> = SliceMap::builder();
    let mut iter = lows.iter().peekable();
    while let Some(low) = iter.next() {
        let high: HighSliceKey = match iter.peek() {
            Some(high) => HighSliceKey::Finite((*high).clone()),
            None => HighSliceKey::Infinity,
        };
        builder.add_slice(low.clone(), high, low.clone()).unwrap();
    }
    builder.build().unwrap()
}

/// Randomly generates an 8-byte slice key.
fn random_slice_key() -> SliceKey {
    let mut bytes = [0; 8];
    rng().fill_bytes(&mut bytes);
    identity_bytes(&bytes)
}

/// Creates a [`SliceMap`], passing each of the given slices into the builder in the given order.
fn create_slice_map_from_slices(slices: &[SliceP]) -> Result<SliceMap<()>, Status> {
    create_slice_map(slices.iter().map(|slice: &SliceP| (slice, ())))
}

/// Creates a partial [`SliceMap`], passing each of the given slices into the builder in the given
/// order.
///
/// The value for each entry is the low key of the slice.
fn create_partial_slice_map_from_slices(
    slices: &[SliceP],
) -> Result<SliceMap<Option<SliceKey>>, Status> {
    let mut builder: PartialSliceMapBuilder<SliceKey> = SliceMap::partial_builder();
    for slice in slices {
        let low_key: SliceKey = identity_bytes(slice.low_inclusive.as_ref().unwrap());
        let high_key: HighSliceKey = slice
            .high_exclusive
            .as_ref()
            .map(|key: &Vec<u8>| identity_bytes(key).into_high_key())
            .unwrap_or(HighSliceKey::Infinity);
        builder.add_slice(low_key.clone(), high_key, low_key.clone())?;
    }
    builder.build()
}

/// Creates a [`SliceMap`] from the given `(slice, value)` pairs.
fn create_slice_map<'a, T>(
    iter: impl Iterator<Item = (&'a SliceP, T)>,
) -> Result<SliceMap<T>, Status> {
    let mut builder: SliceMapBuilder<T> = SliceMap::builder();
    for (slice, value) in iter {
        let low_key: SliceKey = identity_bytes(slice.low_inclusive.as_ref().unwrap());
        let high_key: HighSliceKey = slice
            .high_exclusive
            .as_ref()
            .map(|key: &Vec<u8>| identity_bytes(key).into_high_key())
            .unwrap_or(HighSliceKey::Infinity);
        builder.add_slice(low_key, high_key, value)?;
    }
    builder.build()
}

/// A simple but inefficient reference implementation of [`SliceMap::intersect`] that works by
/// exhaustively finding intersections between all pairings of entries in `left` and `right` and
/// sorting those intersections.
///
/// Returns a representation of the intersection slice map as a vector of (low key, high key, value)
/// tuples.
fn intersect_slice_maps_reference<'a>(
    left: &'a SliceMap<SliceKey>,
    right: &'a SliceMap<SliceKey>,
) -> Vec<(&'a SliceKey, HighSliceKeyRef<'a>, Slice, IntersectionEntry<'a, SliceKey, SliceKey>)> {
    let mut intersection_entries: Vec<(
        &SliceKey,
        HighSliceKeyRef,
        Slice,
        IntersectionEntry<SliceKey, SliceKey>,
    )> = Vec::new();
    for left_entry in left {
        for right_entry in right {
            let intersection_low: &SliceKey =
                max(left_entry.low_inclusive_key(), right_entry.low_inclusive_key());
            let intersection_high: HighSliceKeyRef =
                min(left_entry.high_exclusive_key(), right_entry.high_exclusive_key());
            if *intersection_low < intersection_high {
                let intersection_slice: Slice =
                    Slice::new(intersection_low.clone(), intersection_high.to_owned()).unwrap();

                intersection_entries.push((
                    intersection_low,
                    intersection_high,
                    intersection_slice,
                    IntersectionEntry {
                        left: left_entry,
                        right: right_entry,
                    },
                ));
            }
        }
    }
    intersection_entries.sort_by_key(|(low_key, _, _, _)| *low_key);
    intersection_entries
}

#[test]
fn test_look_up() {
    // Test Plan: Populate a SliceMap with random slices and then verify that lookups work as
    // expected for all boundary values and for random keys.

    // 100 trials.
    for _ in 0..100 {
        // Choose a random map size. (The map cannot be empty).
        let size: usize = rng().next_u32() as usize % 1000 + 1;
        let slice_map: SliceMap<SliceKey> = create_random_slice_map(size);

        // Try looking up all boundary keys.
        for expected_entry in &slice_map {
            let actual_entry = slice_map.look_up(expected_entry.low_inclusive_key());
            assert_eq!(actual_entry.low_inclusive_key(), expected_entry.low_inclusive_key());
            assert_eq!(actual_entry.high_exclusive_key(), expected_entry.high_exclusive_key());
            assert_eq!(actual_entry.value(), expected_entry.low_inclusive_key());
        }

        // Look up 1000 random keys.
        for _ in 0..1000 {
            let key: SliceKey = random_slice_key();
            let entry = slice_map.look_up(&key);
            assert!(*entry.low_inclusive_key() <= key);
            assert!(key < entry.high_exclusive_key());
            assert_eq!(entry.value(), entry.low_inclusive_key());
        }
    }
}

#[test]
fn test_build_slice_map() {
    // Test Plan: Verify that the builder returns the expected error (or not) for a variety of slice
    // sequences, including disjoint slices supplied out of order and empty or reversed slices.
    for test_case in &SLICE_MAP_TEST_DATA.validate_complete_slices_test_cases {
        let result: Result<SliceMap<()>, Status> = create_slice_map_from_slices(&test_case.slices);
        if let Some(expected_error) = &test_case.expected_error {
            let error: Status = result.unwrap_err();
            let message: &str = error.message();
            assert!(
                message.contains(expected_error),
                "Expected error message containing {expected_error:?}, but was {message:?}"
            );
        } else {
            result.unwrap();
        }
    }

    // The next slice must start at "b", so neither case fails because of a gap or overlap.
    let mut builder: SliceMapBuilder<()> = SliceMap::builder();
    builder
        .add_slice(SliceKey::MIN, identity("b").into_high_key(), ())
        .unwrap();

    // [b, b) is empty: its bounds are equal.
    let empty_slice_error: Status = builder
        .add_slice(identity("b"), identity("b").into_high_key(), ())
        .unwrap_err();
    assert!(empty_slice_error
        .message()
        .contains("Slice must not be empty"));

    // [b, a) is reversed: its upper bound precedes its lower bound.
    let reversed_slice_error: Status = builder
        .add_slice(identity("b"), identity("a").into_high_key(), ())
        .unwrap_err();
    assert!(reversed_slice_error
        .message()
        .contains("Slice must not be empty"));
}

#[test]
fn test_len_and_is_empty() {
    // Test Plan: Verify that `len` and `is_empty` return the expected values for slice maps of
    // various sizes.
    for size in 1..=10 {
        let slice_map: SliceMap<SliceKey> = create_random_slice_map(size);
        assert_eq!(slice_map.len(), size);
        assert!(!slice_map.is_empty());
    }
}

#[test]
fn test_iter_single_slice() {
    // Test Plan: Verify that iteration works as expected for a single-slice map.
    let mut builder: SliceMapBuilder<&str> = SliceMap::builder();
    builder
        .add_slice(SliceKey::MIN, HighSliceKey::Infinity, "slice1")
        .unwrap();
    let slice_map: SliceMap<&str> = builder.build().unwrap();

    let mut iter = slice_map.iter();
    let curr = iter.next().unwrap();
    assert_eq!(*curr.low_inclusive_key(), SliceKey::MIN);
    assert_eq!(curr.high_exclusive_key(), HighSliceKey::Infinity);
    assert_eq!(*curr.value(), "slice1");
    assert_eq!(curr.to_slice(), Slice::full().clone());
    assert!(iter.next().is_none());
}

#[test]
fn test_iter_multiple_slices() {
    // Test Plan: Verify that iteration works as expected for a three-slice map.
    let mut builder: SliceMapBuilder<&str> = SliceMap::builder();
    builder
        .add_slice(SliceKey::MIN, identity("Balin").into_high_key(), "slice1")
        .unwrap();
    builder
        .add_slice(identity("Balin"), identity("Fili").into_high_key(), "slice2")
        .unwrap();
    builder
        .add_slice(identity("Fili"), HighSliceKey::Infinity, "slice3")
        .unwrap();
    let slice_map: SliceMap<&str> = builder.build().unwrap();

    let expected_slices = &[
        Slice::new(SliceKey::MIN, identity("Balin").into_high_key()).unwrap(),
        Slice::new(identity("Balin"), identity("Fili").into_high_key()).unwrap(),
        Slice::new(identity("Fili"), HighSliceKey::Infinity).unwrap(),
    ];

    let mut iter = slice_map.iter();

    let mut curr = iter.next().unwrap();
    assert_eq!(*curr.low_inclusive_key(), SliceKey::MIN);
    assert_eq!(curr.high_exclusive_key(), identity("Balin").as_high_key_ref());
    assert_eq!(*curr.value(), "slice1");
    assert_eq!(curr.to_slice(), expected_slices[0]);

    curr = iter.next().unwrap();
    assert_eq!(*curr.low_inclusive_key(), identity("Balin"));
    assert_eq!(curr.high_exclusive_key(), identity("Fili").as_high_key_ref());
    assert_eq!(*curr.value(), "slice2");
    assert_eq!(curr.to_slice(), expected_slices[1]);

    curr = iter.next().unwrap();
    assert_eq!(*curr.low_inclusive_key(), identity("Fili"));
    assert_eq!(curr.high_exclusive_key(), HighSliceKey::Infinity);
    assert_eq!(*curr.value(), "slice3");
    assert_eq!(curr.to_slice(), expected_slices[2]);

    assert!(iter.next().is_none());
}

#[test]
fn test_intersect() {
    // Test Plan: Verify the expected output for various inputs to `SliceMap::intersect`.
    for test_case in &SLICE_MAP_TEST_DATA.intersect_test_cases {
        // Parse the input SliceMaps.
        let left: SliceMap<&str> =
            create_slice_map(test_case.left_entries.iter().map(|entry: &LeftEntryP| {
                (entry.slice.as_ref().unwrap(), entry.value.as_ref().unwrap() as &str)
            }))
            .unwrap();
        let right: SliceMap<i32> = create_slice_map(
            test_case
                .right_entries
                .iter()
                .map(|entry: &RightEntryP| (entry.slice.as_ref().unwrap(), entry.value.unwrap())),
        )
        .unwrap();

        // Parse the expected output SliceMap.
        let expected: SliceMap<IntersectionEntry<&str, i32>> =
            create_slice_map(test_case.expected_intersection_entries.iter().map(
                |entry: &intersect_test_case_p::ExpectedEntryP| {
                    (
                        entry.slice.as_ref().unwrap(),
                        IntersectionEntry {
                            left: left.iter().nth(entry.left_index.unwrap() as usize).unwrap(),
                            right: right
                                .iter()
                                .nth(entry.right_index.unwrap() as usize)
                                .unwrap(),
                        },
                    )
                },
            ))
            .unwrap();

        assert_eq!(
            SliceMap::intersect(&left, &right)
                .iter()
                .collect::<Vec<_>>(),
            expected.iter().collect::<Vec<_>>()
        );
    }
}

#[test]
fn test_intersect_randomized() {
    // Test Plan: Validate the behavior of `SliceMap::intersect` against a reference implementation
    // that exhaustively checks for intersections between all pairs of slices in the left and right
    // inputs.
    for _ in 0..100 {
        let left_size: usize = rng().next_u32() as usize % 10 + 1;
        let right_size: usize = rng().next_u32() as usize % 10 + 1;
        let left: SliceMap<SliceKey> = create_random_slice_map(left_size);
        let right: SliceMap<SliceKey> = create_random_slice_map(right_size);

        let expected: Vec<(
            &SliceKey,
            HighSliceKeyRef,
            Slice,
            IntersectionEntry<SliceKey, SliceKey>,
        )> = intersect_slice_maps_reference(&left, &right);

        assert_eq!(
            SliceMap::intersect(&left, &right)
                .iter()
                .map(|entry: SliceMapEntryRef<IntersectionEntry<SliceKey, SliceKey>>| {
                    (
                        entry.low_inclusive_key(),
                        entry.high_exclusive_key(),
                        entry.to_slice(),
                        *entry.value(),
                    )
                })
                .collect::<Vec<_>>(),
            expected
        );
    }
}

#[test]
fn test_partial_slice_map() {
    // Test Plan: Verify for various sets of input slices that the expected partial slice map is
    // built. This is done by parsing the expected entries from the test data and comparing the
    // actual entries in the slice map.
    for test_case in &SLICE_MAP_TEST_DATA.partial_slice_map_test_cases {
        let slice_map: SliceMap<Option<SliceKey>> =
            create_partial_slice_map_from_slices(&test_case.input_slices).unwrap();

        // Parse the expected entries into a vector of tuples containing the low key, high key,
        // slice, and value.
        let expected_entries: Vec<(SliceKey, HighSliceKey, Slice, Option<SliceKey>)> = test_case
            .expected_entries
            .iter()
            .map(|entry: &ExpectedEntryP| {
                let slice: &SliceP = entry.slice.as_ref().unwrap();
                let low_key: SliceKey = identity_bytes(slice.low_inclusive.as_ref().unwrap());
                let high_key: HighSliceKey = slice
                    .high_exclusive
                    .as_ref()
                    .map(|bytes: &Vec<u8>| identity_bytes(bytes).into_high_key())
                    .unwrap_or(HighSliceKey::Infinity);
                let slice: Slice = Slice::new(low_key.clone(), high_key.to_owned()).unwrap();
                let value: Option<SliceKey> =
                    match ValueTypeP::try_from(entry.value_type.unwrap()).unwrap() {
                        ValueTypeP::Some => Some(low_key.clone()),
                        ValueTypeP::Gap => None,
                        ValueTypeP::Unspecified => panic!("Invalid value type"),
                    };
                (low_key, high_key, slice, value)
            })
            .collect();

        // Collect the actual entries from the slice map, and convert them to the same
        // representation as the expected entries using public API on entries.
        let actual_entries: Vec<(SliceKey, HighSliceKey, Slice, Option<SliceKey>)> = slice_map
            .iter()
            .map(|entry: SliceMapEntryRef<Option<SliceKey>>| {
                (
                    entry.low_inclusive_key().clone(),
                    entry.high_exclusive_key().to_owned(),
                    entry.to_slice(),
                    entry.value().clone(),
                )
            })
            .collect();

        assert_eq!(actual_entries, expected_entries);
    }
}

#[test]
fn test_invalid_partial_slice_map() {
    // Test Plan: Verify that the partial slice map builder rejects overlaps, disjoint slices
    // supplied out of order, entries after infinity, and empty or reversed slices.
    for test_case in &SLICE_MAP_TEST_DATA.partial_slice_map_error_test_cases {
        let error: Status =
            create_partial_slice_map_from_slices(&test_case.input_slices).unwrap_err();
        assert!(error
            .message()
            .contains(test_case.expected_error.as_ref().unwrap()));
    }

    // Start each invalid slice at "b" so it doesn't overlap the existing slice.
    let mut builder: PartialSliceMapBuilder<()> = SliceMap::partial_builder();
    builder
        .add_slice(SliceKey::MIN, identity("b").into_high_key(), ())
        .unwrap();

    // [b, b) is empty: its bounds are equal.
    let empty_slice_error: Status = builder
        .add_slice(identity("b"), identity("b").into_high_key(), ())
        .unwrap_err();
    assert!(empty_slice_error
        .message()
        .contains("Slice must not be empty"));

    // [b, a) is reversed: its upper bound precedes its lower bound.
    let reversed_slice_error: Status = builder
        .add_slice(identity("b"), identity("a").into_high_key(), ())
        .unwrap_err();
    assert!(reversed_slice_error
        .message()
        .contains("Slice must not be empty"));
}

#[test]
fn test_non_empty_entries() {
    // Test Plan: Verify that `non_empty_entries` returns only the `Some` entries in a partial slice
    // map.

    // Create a slice map containing two slices with a gap between them.
    let mut builder: PartialSliceMapBuilder<&str> = SliceMap::partial_builder();
    builder
        .add_slice(identity("Balin"), identity("Fili").into_high_key(), "slice1")
        .unwrap();
    builder
        .add_slice(identity("Nori"), identity("Thorin").into_high_key(), "slice2")
        .unwrap();
    let slice_map: SliceMap<Option<&str>> = builder.build().unwrap();

    // Collect the non-empty entries.
    let entries: Vec<SliceMapEntryRef<&str>> = slice_map.non_empty_entries().collect();

    // Verify that we got exactly 2 entries.
    assert_eq!(entries.len(), 2);

    // Verify that the entries are in the correct order with the correct values.
    assert_eq!(*entries[0].low_inclusive_key(), identity("Balin"));
    assert_eq!(entries[0].high_exclusive_key(), identity("Fili").as_high_key_ref());
    assert_eq!(*entries[0].value(), "slice1");

    assert_eq!(*entries[1].low_inclusive_key(), identity("Nori"));
    assert_eq!(entries[1].high_exclusive_key(), identity("Thorin").as_high_key_ref());
    assert_eq!(*entries[1].value(), "slice2");
}
