//! Utility functions for testing.

use std::collections::HashSet;
use std::fmt::Debug;
use std::hash::{DefaultHasher, Hash, Hasher};

use tonic::{Code, Status};

/// Asserts that `result` is an `Err` whose `Status` has code `expected_code` and whose message
/// contains `expected_error_message`.
pub fn assert_status<T>(
    result: Result<T, Status>,
    expected_code: Code,
    expected_error_message: &str,
) -> Status {
    let err: Status = result.err().expect("expected an error status");
    assert_eq!(err.code(), expected_code);
    assert!(
        err.message().contains(expected_error_message),
        "Expected error message missing: {expected_error_message}, Received: {err}"
    );
    err
}

/// Reads the contents of a Bazel runfile at `runfiles_path`, which must be the workspace-rooted
/// path used in `runfiles::rlocation!` (e.g. `"<main_repo>/path/to/fixture.textproto"`). The
/// caller must declare the underlying target as a `data` dep of the consuming `rust_test`. Panics
/// on any failure (missing handle, unresolved path, or read error) — intended for test setup where
/// a missing fixture is a hard failure.
pub fn read_from_runfiles(runfiles_path: &str) -> Vec<u8> {
    let runfiles = runfiles::Runfiles::create().expect("failed to create runfiles handle");
    let resolved = runfiles::rlocation!(runfiles, runfiles_path)
        .unwrap_or_else(|| panic!("runfile not found: {runfiles_path}"));
    std::fs::read(&resolved)
        .unwrap_or_else(|err| panic!("failed to read runfile {resolved:?}: {err}"))
}

/// Computes a hash of `value` using the default hasher.
pub fn calculate_hash(value: &impl Hash) -> u64 {
    let mut hasher = DefaultHasher::new();
    value.hash(&mut hasher);
    hasher.finish()
}

/// Asserts that the `Eq` and `Hash` implementations of `T` agree with a partition of test values
/// into equivalence classes. Each inner vector is one equivalence class. Values in the same vector
/// must compare and hash equal, and values in different vectors must compare unequal. Hash
/// collisions across groups are permitted, but at least 90% of the groups must have distinct
/// hashes.
///
/// Use this when a custom `Eq` (and/or derived `PartialEq`) is defined over a subset of fields,
/// or where logically distinct types share a wire representation, and you want to pin down which
/// inputs are intended to be equal and which are not. It is the Rust counterpart of
/// `caching/util/test/TestUtils.scala`'s `checkEquality`. Compared to a flat list of pairwise
/// `assert_eq!`/`assert_ne!` calls, expressing the intent as groups makes it easier to add a new
/// test value (just append it to the right group) and produces a more useful diagnostic when an
/// implementation regresses.
///
/// # Example
///
/// ```
/// use std::hash::{Hash, Hasher};
///
/// use caching_rust_util_testing::test_utils::check_equality_groups;
///
/// #[derive(Debug)]
/// struct EqualByTens(i32);
///
/// impl PartialEq for EqualByTens {
///     fn eq(&self, other: &Self) -> bool {
///         self.0 / 10 == other.0 / 10
///     }
/// }
/// impl Eq for EqualByTens {}
///
/// impl Hash for EqualByTens {
///     fn hash<H: Hasher>(&self, state: &mut H) {
///         (self.0 / 10).hash(state);
///     }
/// }
///
/// // Two equivalence classes: anything in [10, 19] and the singleton 20.
/// check_equality_groups(&[
///     vec![EqualByTens(10), EqualByTens(11)],
///     vec![EqualByTens(20)],
/// ]);
/// ```
pub fn check_equality_groups<T: Eq + Hash + Debug>(groups: &[Vec<T>]) {
    assert!(!groups.is_empty(), "expected at least one equality group");
    let mut distinct_hashes: HashSet<u64> = HashSet::new();
    // Within each group, every pair (including each value with itself) must be equal.
    for group in groups {
        assert!(!group.is_empty(), "expected equality groups to be non-empty");
        for left in group {
            distinct_hashes.insert(calculate_hash(left));
            for right in group {
                assert_eq!(left, right, "{left:?} == {right:?}");
                assert_eq!(
                    calculate_hash(left),
                    calculate_hash(right),
                    "equal values must hash equally: hash({left:?}) == hash({right:?})"
                );
            }
        }
    }

    assert!(
        distinct_hashes.len() as f64 / groups.len() as f64 >= 0.9,
        "insufficient distinct hashes: {} hashes for {} equality groups",
        distinct_hashes.len(),
        groups.len()
    );

    // Across distinct groups, every cross-pair must be unequal.
    for (left_index, left_group) in groups.iter().enumerate() {
        for (right_index, right_group) in groups.iter().enumerate() {
            if left_index != right_index {
                for left in left_group {
                    for right in right_group {
                        assert_ne!(left, right, "{left:?} != {right:?}");
                    }
                }
            }
        }
    }
}

/// Given an ordered sequence of objects, determines whether ordering and
/// equality ops are well-behaved.
pub fn check_rel_ops(ordered: &[impl Ord + Hash + Debug]) {
    let mut hash_collisions: usize = 0;
    for (i, x) in ordered.iter().enumerate() {
        for (j, y) in ordered.iter().enumerate() {
            assert_eq!(x.cmp(y), i.cmp(&j), "comparison of {:?} and {:?}", x, y);
            assert_eq!(x.eq(y), i.eq(&j), "equality of {:?} and {:?}", x, y);
            if i == j {
                assert_eq!(calculate_hash(x), calculate_hash(y), "hashes of equal values");
            } else if calculate_hash(x) == calculate_hash(y) {
                hash_collisions += 1;
            }
        }
        // We allow a small number of hash collisions. Since the hash function
        // is not stable, any number of collisions is technically possible, but
        // we expect very few.
        assert!(hash_collisions < 3, "many hash collisions: {}", hash_collisions);
    }
}
