//! AssignmentCellValue type for wrapping assignments in watch cells.

use std::collections::HashSet;
use std::sync::Arc;

use derive_more::Debug;
use tonic::Status;
use caching_rust_util::consistent_hash_ring::{ConsistentHashRing, TypeMapper};
use caching_rust_util::watch_cell;
use dicer_rust_external_types::{Slice, SliceKey};
use dicer_rust_friend::Squid;

use crate::{
    Assignment,
    BuildSliceMap,
    Generation,
    SliceMap,
    SliceMapBuilder,
    SliceMapEntryRef,
    SliceSet,
};

/// Default number of virtual nodes per physical node on the two-level sharding ring.
const VNODES_PER_RESOURCE: i32 = 128;

/// A wrapper around Option<Assignment> that implements the `Versioned` trait and
/// may be enriched with additional state that is specific to the client type
/// (see [`SliceletAssignmentCellValue`] and [`ClerkAssignmentCellValue`]).
pub trait AssignmentCellValue:
    watch_cell::Versioned<Version = Generation> + Default + Send + Sync + 'static
{
    /// Returns an optional reference to the assignment.
    fn assignment_opt(&self) -> Option<&Assignment>;

    /// Returns the generation of the assignment, or `Generation::EMPTY` if there is no assignment.
    fn generation(&self) -> Generation {
        match self.assignment_opt() {
            Some(assignment) => assignment.generation(),
            None => Generation::EMPTY,
        }
    }
}

/// The client-side routing information for a [`Slice`] used to serve `ResourceRouter` two-level
/// sharding `get_stub_for_key_two_level` and `get_next_stub_for_key`.
#[derive(Debug)]
pub struct SliceInfo {
    /// Precomputed ring for two-level sharding.
    #[debug("{:?}", two_level_hash_ring_opt.as_ref().map(|ring| ring.nodes().collect::<Vec<_>>()))]
    two_level_hash_ring_opt: Option<ConsistentHashRing<ClerkAssignmentRingMapper>>,

    /// The fallback squid for the slice, or `None` if the assignment has no resource unassigned to
    /// the slice. One fallback squid is constructed per slice to bound the blast radius of fallback
    /// traffic: for example, in the key-of-death scenario, a key-of-death can overload at most one
    /// other slicelet.
    fallback_squid_opt: Option<Arc<Squid>>,
}

impl SliceInfo {
    /// The precomputed consistent hash ring for two-level sharding, if present.
    pub fn two_level_hash_ring_opt(
        &self,
    ) -> Option<&ConsistentHashRing<ClerkAssignmentRingMapper>> {
        self.two_level_hash_ring_opt.as_ref()
    }

    /// The fallback squid for the slice, if present.
    pub fn fallback_squid_opt(&self) -> Option<&Arc<Squid>> {
        self.fallback_squid_opt.as_ref()
    }
}

/// An assignment augmented with precomputed per-slice routing information for two-level sharding:
/// the primary key selects the slice, and the secondary key selects a resource from that slice.
/// Each slice's routing information contains a hash ring if and only if the slice has more than
/// one assigned resource in [`Self::assignment`].
/// Each slice with at least one unassigned resource also gets a fallback squid for retry-aware
/// lookups.
#[derive(Debug)]
pub struct ClerkAssignment {
    /// The underlying Dicer assignment.
    assignment: Assignment,

    /// Routing information for every slice in `assignment`, with matching slice boundaries.
    slice_info_map: SliceMap<SliceInfo>,
}

impl ClerkAssignment {
    /// Builds a [`ClerkAssignment`] wrapping `assignment` together with the per-slice
    /// routing information ([`SliceInfo`]) precomputed for each slice.
    fn new(assignment: Assignment) -> Self {
        let slice_info_map: SliceMap<SliceInfo> = Self::build_slice_info_map(&assignment);
        Self {
            assignment,
            slice_info_map,
        }
    }

    /// Builds the per-slice [`SliceInfo`] map for `assignment`, with a two-level sharding hash ring
    /// if and only if the corresponding slice has more than one assigned resource.
    /// Each slice gets a fallback squid if the assignment has a resource unassigned to that slice.
    fn build_slice_info_map(assignment: &Assignment) -> SliceMap<SliceInfo> {
        let mut assignment_resources: Vec<Arc<Squid>> =
            assignment.assigned_resources().into_iter().collect();
        let assignment_resource_count: usize = assignment_resources.len();

        // All Clerks use the same assignment-wide ring so fallback traffic for a slice stays
        // affinitized to one resource, limiting the blast radius of a key-of-death.
        // One vnode per resource suffices because the ring only defines a deterministic fallback;
        // it does not balance keys. Sorting ensures colliding ring positions are resolved
        // identically regardless of the order `assigned_resources` iterates in.
        assignment_resources.sort();
        let fallback_ring: ConsistentHashRing<ClerkAssignmentRingMapper> =
            ConsistentHashRing::new(assignment_resources, 1, ClerkAssignmentRingMapper)
                .expect("assigned resources are non-empty and vnodes_per_node > 0");

        let mut builder: SliceMapBuilder<SliceInfo> = SliceMap::builder();
        for slice_assignment in assignment.slice_assignments() {
            let slice_resources: &[Arc<Squid>] = slice_assignment.resources();
            let two_level_hash_ring_opt: Option<ConsistentHashRing<ClerkAssignmentRingMapper>> =
                if slice_resources.len() > 1 {
                    // Sort by the Squid ordering so every callsite builds an identical ring across
                    // pods/processes, even if there are hash collisions. This matches the Scala
                    // Clerk ring because `Squid`'s ordering is identical across languages.
                    let mut sorted_resources: Vec<Arc<Squid>> = slice_resources.to_vec();
                    sorted_resources.sort();
                    Some(
                        ConsistentHashRing::new(
                            sorted_resources,
                            VNODES_PER_RESOURCE,
                            ClerkAssignmentRingMapper,
                        )
                        .unwrap_or_else(|error: Status| {
                            panic!(
                                "Failed to build two-level hash ring with {VNODES_PER_RESOURCE} \
                                 vnodes per resource for {slice_assignment:?}: {error}"
                            )
                        }),
                    )
                } else {
                    None
                };
            let slice_resources_set: &HashSet<Arc<Squid>> = slice_assignment.resources_set();
            let fallback_squid_opt: Option<Arc<Squid>> =
                if slice_resources_set.len() == assignment_resource_count {
                    // Skip slices that are assigned to all of the assignment's resources, so no
                    // fallback traversal is needed for those slices.
                    None
                } else {
                    // Walk the ring from the resource owning the `slice.low_inclusive` key and
                    // take the first resource that is not one of the slice's own resources.
                    fallback_ring
                        .iterator_from(slice_assignment.low_inclusive_key())
                        .find(|&squid: &&Arc<Squid>| !slice_resources_set.contains(squid))
                        .map(Arc::clone)
                };
            builder
                .add_slice(
                    slice_assignment.low_inclusive_key().clone(),
                    slice_assignment.high_exclusive_key().to_owned(),
                    SliceInfo {
                        two_level_hash_ring_opt,
                        fallback_squid_opt,
                    },
                )
                .unwrap_or_else(|error: Status| {
                    panic!(
                        "Assignment slices unexpectedly unordered or overlapping: failed to add \
                         {slice_assignment:?} from {assignment:?}: {error}"
                    )
                });
        }
        builder.build().unwrap_or_else(|error: Status| {
            panic!(
                "Assignment slices unexpectedly do not cover the full key space: \
                 {assignment:?}: {error}"
            )
        })
    }

    /// The underlying Dicer assignment.
    pub fn assignment(&self) -> &Assignment {
        &self.assignment
    }

    /// Routing information for every slice in the assignment.
    pub fn slice_info_map(&self) -> &SliceMap<SliceInfo> {
        &self.slice_info_map
    }

    /// Test-only: checks that
    /// 1. [`Self::slice_info_map`] has exactly one entry per slice in the assignment, with matching
    ///    slice boundaries.
    /// 2. A slice has a hash ring if and only if it has more than one assigned resource, and the
    ///    ring's nodes are exactly the slice's assigned resources.
    /// 3. A slice's [`SliceInfo`] has a fallback squid if and only if the assignment has at least
    ///    one resource that is unassigned to the slice.
    /// 4. Each slice's fallback squid, if present, is one of the assignment's resources and is not
    ///    assigned to the slice itself.
    ///
    /// Returns an `INTERNAL` status if an invariant is violated.
    fn check_invariants_for_test(&self) -> Result<(), Status> {
        let assignment: &Assignment = &self.assignment;
        let slice_info_map: &SliceMap<SliceInfo> = &self.slice_info_map;

        // The slice map has exactly one entry per slice in the assignment.
        let assignment_slice_count: usize = assignment.slice_assignments().count();
        if slice_info_map.len() != assignment_slice_count {
            return Err(Status::internal(format!(
                "slice info map has {} entries but the assignment has {assignment_slice_count} \
                 slices",
                slice_info_map.len()
            )));
        }

        let assigned_resources: HashSet<Arc<Squid>> = assignment.assigned_resources();
        for slice_assignment in assignment.slice_assignments() {
            let slice: Slice = slice_assignment.to_slice();
            let slice_info: SliceMapEntryRef<SliceInfo> =
                slice_info_map.look_up(slice_assignment.low_inclusive_key());
            // The slice map's slice boundaries match the assignment's.
            if slice_info.to_slice() != slice {
                return Err(Status::internal(format!(
                    "slice info map slice {:?} does not match assignment slice {slice:?}",
                    slice_info.to_slice()
                )));
            }

            let is_multi_replica: bool = slice_assignment.resources_set().len() > 1;
            let ring_opt: Option<&ConsistentHashRing<ClerkAssignmentRingMapper>> =
                slice_info.value().two_level_hash_ring_opt();
            if ring_opt.is_some() != is_multi_replica {
                return Err(Status::internal(format!(
                    "slice {slice:?} hash ring presence {} doesn't match multi-replica status \
                     {is_multi_replica}",
                    ring_opt.is_some()
                )));
            }
            if let Some(ring) = ring_opt {
                let mut sorted_resources: Vec<Arc<Squid>> = slice_assignment.resources().to_vec();
                sorted_resources.sort();
                if !ring.nodes().eq(sorted_resources.iter()) {
                    return Err(Status::internal(format!(
                        "ring nodes {:?} do not match sorted resources {sorted_resources:?}",
                        ring.nodes().collect::<Vec<_>>(),
                    )));
                }
            }

            // A slice has a fallback squid if and only if the assignment has at least one resource
            // that is unassigned to the slice. When present, the fallback squid must be one of the
            // assignment's resources and must not be assigned to the slice itself.
            let slice_resources: &HashSet<Arc<Squid>> = slice_assignment.resources_set();
            let has_unassigned_resource: bool = assigned_resources
                .iter()
                .any(|resource: &Arc<Squid>| !slice_resources.contains(resource));
            let fallback_squid_opt: Option<&Arc<Squid>> = slice_info.value().fallback_squid_opt();
            if fallback_squid_opt.is_some() != has_unassigned_resource {
                return Err(Status::internal(format!(
                    "slice {slice:?} fallback squid presence ({}) does not match whether the \
                     assignment has a resource unassigned to the slice ({has_unassigned_resource})",
                    fallback_squid_opt.is_some()
                )));
            }
            if let Some(fallback_squid) = fallback_squid_opt {
                if !assigned_resources.contains(fallback_squid) {
                    return Err(Status::internal(format!(
                        "fallback squid {fallback_squid:?} for slice {slice:?} is not one of the \
                         assignment's resources"
                    )));
                }
                if slice_resources.contains(fallback_squid) {
                    return Err(Status::internal(format!(
                        "fallback squid {fallback_squid:?} for slice {slice:?} is assigned to the \
                         slice itself"
                    )));
                }
            }
        }
        Ok(())
    }
}

/// The Clerk's assignment state, guaranteed by the watch cell to advance only to newer assignment
/// generations. Empty until the first assignment is received.
#[derive(Debug, Default)]
pub struct ClerkAssignmentCellValue {
    /// The assignment and its routing metadata are either both present or both absent.
    clerk_assignment_opt: Option<ClerkAssignment>,
}

impl ClerkAssignmentCellValue {
    /// Builds a populated cell value with routing metadata precomputed for `assignment`.
    pub fn new(assignment: Assignment) -> Self {
        Self {
            clerk_assignment_opt: Some(ClerkAssignment::new(assignment)),
        }
    }

    /// The assignment and its routing metadata, or `None` before an assignment is received.
    pub fn clerk_assignment_opt(&self) -> Option<&ClerkAssignment> {
        self.clerk_assignment_opt.as_ref()
    }

    /// Test-only: checks the routing metadata invariants, passing when no assignment is present.
    ///
    /// Returns an `INTERNAL` status if an invariant is violated.
    pub fn check_invariants_for_test(&self) -> Result<(), Status> {
        match &self.clerk_assignment_opt {
            Some(clerk_assignment) => clerk_assignment.check_invariants_for_test(),
            None => Ok(()),
        }
    }
}

/// Maps [`Squid`] and [`SliceKey`] to the respective byte representations that the
/// [`ConsistentHashRing`] hashes.
pub struct ClerkAssignmentRingMapper;

impl TypeMapper for ClerkAssignmentRingMapper {
    type Node = Arc<Squid>;
    type Key = SliceKey;

    /// Maps a [`Squid`] to the 16-byte big-endian encoding of its resource UUID.
    fn map_node<'a>(&self, node: &'a Arc<Squid>) -> &'a [u8] {
        node.resource_uuid().as_bytes()
    }

    /// Maps a [`SliceKey`] to its raw bytes.
    fn map_key<'a>(&self, key: &'a SliceKey) -> &'a [u8] {
        key.to_raw_bytes()
    }
}

impl AssignmentCellValue for ClerkAssignmentCellValue {
    fn assignment_opt(&self) -> Option<&Assignment> {
        self.clerk_assignment_opt
            .as_ref()
            .map(ClerkAssignment::assignment)
    }
}

impl watch_cell::Versioned for ClerkAssignmentCellValue {
    type Version = Generation;
    fn version(&self) -> Self::Version {
        self.generation()
    }
}

/// Cell value for a Slicelet assignment, which include the assignment and
/// slices assigned to the current Slicelet.
#[derive(Debug, Default)]
pub struct SliceletAssignmentCellValue {
    /// The assignment and Squid for the Slicelet, or `None` if the assignment
    /// is not present (stored as a pair since we either know both or neither).
    pub assignment_and_squid_opt: Option<(Assignment, Squid)>,

    /// The slices assigned to the current Slicelet, or [`SliceSet::empty()`] if
    /// the assignment is not present.
    pub slice_set: SliceSet,
}

impl SliceletAssignmentCellValue {
    pub fn new(assignment: Assignment, squid: Squid) -> Self {
        let slice_set = assignment.get_slice_set_for_resource(&squid);
        Self {
            assignment_and_squid_opt: Some((assignment, squid)),
            slice_set,
        }
    }
}

impl AssignmentCellValue for SliceletAssignmentCellValue {
    fn assignment_opt(&self) -> Option<&Assignment> {
        self.assignment_and_squid_opt
            .as_ref()
            .map(|(assignment, _)| assignment)
    }
}

impl watch_cell::Versioned for SliceletAssignmentCellValue {
    type Version = Generation;
    fn version(&self) -> Self::Version {
        self.generation()
    }
}
