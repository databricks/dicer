//! Utility functions generating inputs for Dicer tests.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::fmt::Debug;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, SystemTime};

use rand::{Rng, random, rng};
use tonic::Status;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use caching_rust_util_testing::fake_clock::FakeClock;
use caching_rust_util_testing::test_utils::calculate_hash;
use dicer_common_test_proto_test_data_proto::dicer::test::simple_diff_assignment_p::{self, SimpleSliceAssignmentP};
use dicer_common_test_proto_test_data_proto::dicer::test::SimpleDiffAssignmentP;
use dicer_common_test_proto_test_data_proto::dicer_common_proto_common_proto::dicer::diff_assignment_p::{
    SliceAssignmentP,
    SubsliceAnnotationP,
    TransferP,
};
use dicer_common_test_proto_assignment_control_proto::dicer::test::{
    ProposedSliceAssignmentP,
    TestAssignmentP,
};
use dicer_rust_common::{
    AssignerServiceInfo,
    BuildSliceMap,
    DiffAssignment,
    DiffAssignmentSliceMap,
    Generation,
    InternalSliceAssignment,
    ResourceVec,
    SliceMap,
    SubsliceAnnotation,
    UnixTimeVersion,
    slice_to_proto,
};
use dicer_rust_external_types::{
    HighSliceKey,
    ResourceAddress,
    Slice,
    SliceKey,
    SliceKeyBuilder,
};
use dicer_rust_friend::Squid;
use uuid::Uuid;

use crate::ProposedSliceAssignment;

/// An arbitrary creation time used for all test SQUIDs. Since test squids have deterministic UUIDs,
/// the specific creation time doesn't matter: the creation time will not need to be used to break
/// ties.
static TEST_SQUID_CREATION_TIME: LazyLock<SystemTime> =
    LazyLock::new(|| SystemTime::UNIX_EPOCH + Duration::from_millis(1680279132000u64));

/// Creates a `Squid` deterministically derived from the given index.
///
/// # Examples
///
/// ```
/// use chrono::{DateTime, Utc};
/// use std::time::SystemTime;
/// use dicer_rust_friend::Squid;
/// use dicer_rust_testing::data_helpers::test_squid;
/// use uuid::Uuid;
///
/// let squid: Squid = test_squid(42);
/// assert_eq!(squid.resource_address().uri(), "http://localhost:42/");
/// assert_eq!(squid.resource_uuid(), &Uuid::from_u64_pair(42, 42));
/// let expected_creation_time: DateTime<Utc> = "1970-01-01T00:00:42Z".parse().unwrap();
/// let expected_creation_time: SystemTime = expected_creation_time.into();
/// assert_eq!(
///     squid.creation_time_millis(),
///     expected_creation_time.duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis() as i64
/// );
/// ```
pub fn test_squid(i: u32) -> Squid {
    let resource_address = ResourceAddress::new(&format!("http://localhost:{}/", i)).unwrap();
    let resource_uuid: Uuid = Uuid::from_u64_pair(i.into(), i.into());
    let creation_time = SystemTime::UNIX_EPOCH + Duration::from_secs(i as u64);

    let clock = FakeClock::create(creation_time).into_clock();
    Squid::create_for_new_incarnation(&clock, resource_address, resource_uuid)
}

/// Creates a Squid for the given URI. Produces a deterministic "UUID" by taking a fingerprint of
/// the URI.
pub fn test_squid_with_uri(uri: &str) -> Squid {
    test_squid_with_uri_and_creation_time_offset(uri, 0)
}

/// Similar to [`test_squid_with_uri`], but folds `creation_time_offset` (in milliseconds) into
/// both the hashed UUID and the creation time. Sets creation time to [`TEST_SQUID_CREATION_TIME`]
/// plus `creation_time_offset` milliseconds (negative offsets moves the time earlier).
pub fn test_squid_with_uri_and_creation_time_offset(uri: &str, creation_time_offset: i64) -> Squid {
    let resource_address = ResourceAddress::new(uri).unwrap();
    let hash: u64 = calculate_hash(&format!("{uri}:{creation_time_offset}"));
    let resource_uuid = Uuid::from_u64_pair(!hash, hash);

    let offset_millis: u64 = creation_time_offset.unsigned_abs();
    let offset_duration: Duration = Duration::from_millis(offset_millis);
    let creation_time: SystemTime = if creation_time_offset >= 0 {
        *TEST_SQUID_CREATION_TIME + offset_duration
    } else {
        *TEST_SQUID_CREATION_TIME - offset_duration
    };
    let clock = FakeClock::create(creation_time).into_clock();
    Squid::create_for_new_incarnation(&clock, resource_address, resource_uuid)
}

/// Converts proposed slice assignments to the AssignmentControl wire representation.
pub fn proposed_assignments_to_proto(
    proposed_assignments: &[ProposedSliceAssignment],
) -> TestAssignmentP {
    let slice_assignments: Vec<ProposedSliceAssignmentP> = proposed_assignments
        .iter()
        .map(|proposed| {
            let (slice, resources, primary_rate_load): (Slice, Vec<Squid>, Option<f64>) =
                proposed.clone().into_parts();
            ProposedSliceAssignmentP {
                slice: Some(slice_to_proto(slice.low_inclusive(), slice.high_exclusive())),
                resources: resources.iter().map(Squid::to_proto).collect(),
                primary_rate_load,
            }
        })
        .collect();

    TestAssignmentP { slice_assignments }
}

/// Parses a [`SimpleDiffAssignmentP`] from the test data.
pub fn parse_simple_diff_assignment(
    proto: &SimpleDiffAssignmentP,
) -> Result<DiffAssignment, Status> {
    let slice_map_entries: Vec<(Slice, InternalSliceAssignment)> = proto
        .slice_assignments
        .iter()
        .map(parse_simple_slice_assignment)
        .collect::<Result<_, _>>()?;

    let slice_map = match proto.partial_diff_generation.as_ref() {
        Some(partial_diff_generation) => {
            let mut builder = SliceMap::partial_builder();
            for (slice, slice_assignment) in slice_map_entries {
                let (low_key, high_key): (SliceKey, HighSliceKey) = slice.into_bounds();
                builder.add_slice(low_key, high_key, slice_assignment)?;
            }
            DiffAssignmentSliceMap::Partial {
                diff_generation: Generation::from_proto(partial_diff_generation)?,
                slice_map: builder.build()?,
            }
        }
        None => {
            let mut builder = SliceMap::builder();
            for (slice, slice_assignment) in slice_map_entries {
                let (low_key, high_key): (SliceKey, HighSliceKey) = slice.into_bounds();
                builder.add_slice(low_key, high_key, slice_assignment)?;
            }
            DiffAssignmentSliceMap::Full(builder.build()?)
        }
    };

    // Absent assigner service info is returned as None. Invalid assigner service info
    // returns an error as a warning to developers that they have constructed an invalid proto.
    let assigner_service_info_opt: Option<AssignerServiceInfo> =
        match proto.assigner_service_info.as_ref() {
            Some(info_proto) => Some(AssignerServiceInfo::from_proto(info_proto)?),
            None => None,
        };

    DiffAssignment::new(
        /*is_frozen=*/ false,
        Generation::from_proto(proto.generation.unwrap_ref_or_default())?,
        slice_map,
        assigner_service_info_opt,
    )
}

/// Parses a [`SimpleSliceAssignmentP`] from the test data.
pub fn parse_simple_slice_assignment(
    proto: &SimpleSliceAssignmentP,
) -> Result<(Slice, InternalSliceAssignment), Status> {
    let (translated, resources): (SliceAssignmentP, Vec<Arc<Squid>>) =
        translate_simple_slice_assignment(proto.clone());
    InternalSliceAssignment::from_proto(&translated, &resources)
}

/// Translates a [`SimpleSliceAssignmentP`] into a [`SliceAssignmentP`], returning the proto along
/// with a vector of the resources with indices corresponding to the resource indices in the proto.
///
/// This allows us to reuse the production parsing logic in [`parse_simple_slice_assignment`].
fn translate_simple_slice_assignment(
    proto: SimpleSliceAssignmentP,
) -> (SliceAssignmentP, Vec<Arc<Squid>>) {
    let mut resources: Vec<Arc<Squid>> = Vec::new();
    let mut generate_resource_index = |resource: &String| {
        let index: i32 = resources.len() as i32;
        resources.push(Arc::new(test_squid_with_uri(resource)));
        index
    };

    let resource_indices: Vec<i32> = proto
        .resources
        .iter()
        .map(&mut generate_resource_index)
        .collect();

    let translated = SliceAssignmentP {
        slice: proto.slice,
        generation: proto.generation,
        resource_indices,
        subslice_annotations: proto
            .subslice_annotations
            .into_iter()
            .map(|annotation: simple_diff_assignment_p::SubsliceAnnotationP| {
                let resource_id: i32 =
                    generate_resource_index(annotation.resource.as_ref().unwrap());
                SubsliceAnnotationP {
                    resource_id: Some(resource_id),
                    subslice: annotation.slice,
                    generation_number: annotation.generation_number,
                    state_transfer: annotation.state_transfer.map(
                        |transfer: simple_diff_assignment_p::TransferP| TransferP {
                            id: transfer.id,
                            from_resource_id: Some(generate_resource_index(
                                transfer.from_resource.as_ref().unwrap(),
                            )),
                        },
                    ),
                }
            })
            .collect(),
        primary_rate_load_opt: proto.primary_rate_load_opt,
    };

    (translated, resources)
}

/// Creates an ordered sequence of `n` slices with random 8-byte boundaries covering the full key
/// space ["" .. ∞).
pub fn create_complete_slices(n: usize) -> Vec<Slice> {
    assert!(n > 0);

    let mut lows = HashSet::<SliceKey>::new();
    lows.insert(SliceKey::MIN);
    while lows.len() < n {
        lows.insert(
            SliceKey::fingerprint_builder()
                .put_bytes(&random::<u64>().to_be_bytes())
                .build(),
        );
    }

    let mut sorted_lows: Vec<SliceKey> = lows.into_iter().collect();
    sorted_lows.sort();

    let mut slices: Vec<Slice> = sorted_lows
        .windows(2)
        .map(|pair: &[SliceKey]| {
            Slice::new(pair[0].clone(), pair[1].clone().into_high_key()).unwrap()
        })
        .collect();
    slices.push(Slice::new(sorted_lows.last().unwrap().clone(), HighSliceKey::Infinity).unwrap());
    slices
}

/// Creates a random `Slice` together with `num_subslices` ordered, disjoint, and complete random
/// subslices of the slice.
pub fn create_random_slice_with_subslices(num_subslices: usize) -> (Slice, Vec<Slice>) {
    assert!(num_subslices > 0);

    // Create some complete Slices, then choose `num_subslices` contiguous subslices from them, and
    // use the combination of them as the returned Slice. Choose from `3 * num_subslices` complete
    // Slices for more randomness.
    let complete_slices: Vec<Slice> = create_complete_slices(3 * num_subslices);
    let start_index: usize = rng().random_range(0..(complete_slices.len() - num_subslices));
    let subslices: Vec<Slice> =
        complete_slices[start_index..(start_index + num_subslices)].to_vec();
    let slice = Slice::new(
        subslices[0].low_inclusive().clone(),
        subslices.last().unwrap().high_exclusive().to_owned(),
    )
    .unwrap();
    (slice, subslices)
}

/// Creates an [`InternalSliceAssignment`] that assigns a random set of resources as of
/// `generation`, with a random primary rate load and random subslice annotations generated from
/// `subslices`.
///
/// REQUIRES: `subslices` are disjoint and ordered subslices of `slice`.
pub fn create_random_slice_assignment(
    subslices: &[Slice],
    generation: Generation,
) -> InternalSliceAssignment {
    // Choose the number of resources assigned. Cannot be zero.
    let num_resources: usize = rng().random_range(1..=10);

    // Generate random resources to assign.
    let resources: ResourceVec = (0..num_resources)
        .map(|_| Arc::new(test_squid_with_uri(&format!("Pod{}", rng().random_range(0..100)))))
        .collect::<HashSet<_>>() // Deduplicate.
        .into_iter()
        .collect();

    // Generate random subslice annotations for each assigned resource.
    let mut subslice_annotations_by_resource =
        HashMap::<Arc<Squid>, SliceMap<Option<SubsliceAnnotation>>>::new();
    if !subslices.is_empty() {
        for resource in &resources {
            // Create a subslice annotation for each subslice with a random generation number.
            let mut builder = SliceMap::partial_builder();
            for subslice in subslices {
                let random_generation_number: i64 =
                    rng().random_range(0..=generation.number().to_unix_millis());
                let annotation = SubsliceAnnotation {
                    continuous_generation_number: UnixTimeVersion::from_unix_millis(
                        random_generation_number,
                    )
                    .unwrap(),
                    state_transfer_opt: None,
                };
                builder
                    .add_slice(
                        subslice.low_inclusive().clone(),
                        subslice.high_exclusive().to_owned(),
                        annotation,
                    )
                    .unwrap();
            }
            subslice_annotations_by_resource.insert(resource.clone(), builder.build().unwrap());
        }
    }

    let primary_rate_load: f64 = random();
    InternalSliceAssignment::new(
        resources,
        generation,
        Some(primary_rate_load),
        subslice_annotations_by_resource,
    )
    .unwrap()
}

/// Checks the implementations of `PartialEq` and `Hash` against the given equality "groups", where
/// each group contains equivalent instances:
///
///  - For all pairs of elements within a single group, `eq` returns true and `hash` returns the
///    same value.
///  - For all pairs of elements across groups, `eq` returns false. Hash code collisions are
///    technically permitted, but we require the number of distinct hash codes to be at at least 90%
///    the number of groups.
pub fn check_equality<T: PartialEq + PartialOrd + Hash + Debug>(groups: &[Vec<T>]) {
    // Check the equality of elements within each group, and collect distinct hash codes.
    let mut hash_codes = HashSet::<u64>::new();
    for group in groups {
        for x in group {
            hash_codes.insert(hash(x));
            for y in group {
                assert_eq!(x, y, "{x:?} == {y:?}");
                assert_eq!(x.partial_cmp(y), Some(Ordering::Equal), "{x:?} == {y:?}");
                assert_eq!(hash(x), hash(y), "hash({x:?}) == hash({y:?})");
            }
        }
    }

    assert!(
        hash_codes.len() as f64 / groups.len() as f64 >= 0.9,
        "Insufficient distinct hash codes"
    );

    // Check inequality of elements across groups.
    for (i, xs) in groups.iter().enumerate() {
        for (j, ys) in groups.iter().enumerate() {
            if i != j {
                for x in xs {
                    for y in ys {
                        assert_ne!(x, y, "{x:?} != {y:?}");
                        assert_ne!(x.partial_cmp(y), Some(Ordering::Equal), "{x:?} != {y:?}");
                    }
                }
            }
        }
    }
}

/// Convenience wrapper around computing the hash code of `x`.
fn hash(x: &impl Hash) -> u64 {
    let mut hasher = DefaultHasher::new();
    x.hash(&mut hasher);
    hasher.finish()
}
