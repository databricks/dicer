use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use linked_hash_map::LinkedHashMap;
use smallvec::SmallVec;
use tonic::Status;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::diff_assignment_p::SliceAssignmentP;
use dicer_friend_proto_friend_proto_library::dicer::friend::SquidP;
use dicer_rust_external_types::{HighSliceKey, HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_friend::Squid;

use crate::assigner_service_info::AssignerServiceInfo;
use crate::diff_assignment::DiffAssignmentSliceMap;
use crate::metric_measurement::validate_measurement;
use crate::slice_map::{
    BuildSliceMap,
    IntersectionEntry,
    SliceMap,
    SliceMapBuilder,
    SliceMapEntryRef,
};
use crate::slice_util::{slice_from_proto, slice_to_proto};
use crate::subslice_annotation::SubsliceAnnotation;
use crate::{DiffAssignment, Generation, PartialSliceMapBuilder, SliceSet, UnixTimeVersion};

/// Represents the assignment of Slices (range of `SliceKey`s) to sets of
/// resources (identified by their `Squid`s.)
///
/// For example, the `Assignment`:
///
///    ["" ... "Bifur") => [squid0, squid1]
///    ["Bifur" ... "Nori") => [squid1, squid2]
///    ["Nori" ... ) => [squid2, squid0]
///
/// indicates that keys in the closed-open interval ["", "Bifur") are assigned
/// to the Slicelets identified by `squid0` and `squid1`, keys in ["Bifur",
/// "Nori") are assigned to `squid1` and `squid2`, and so on.
#[derive(Clone, Debug, PartialEq)]
pub struct Assignment {
    is_frozen: bool,
    generation: Generation,
    slice_map: SliceMap<InternalSliceAssignment>,

    /// The service info of the Assigner that generated this assignment. It should be populated by
    /// the generating assigner, but may be absent if the Assigner cannot determine the service info
    /// or if an outdated Assigner binary is deployed.
    assigner_service_info_opt: Option<AssignerServiceInfo>,
}

// Ensure that `Assignment` is `Send` and `Sync`.
static_assertions::assert_impl_all!(Assignment: Send, Sync);

impl Assignment {
    /// Creates a new [`AssignmentBuilder`] used to build an assignment in Slice
    /// order.
    ///
    /// # Example
    ///
    /// ```
    /// use std::collections::HashMap;
    /// use std::sync::Arc;
    /// use dicer_rust_common::{
    ///     Assignment,
    ///     Generation,
    ///     Incarnation,
    ///     SliceAssignment,
    ///     UnixTimeVersion,
    /// };
    /// use dicer_rust_external_types::{HighSliceKey, SliceKey};
    /// use dicer_rust_friend::Squid;
    /// use dicer_rust_testing::data_helpers::test_squid;
    /// use dicer_rust_testing::test_slice_utils::identity;
    ///
    /// // Define resources.
    /// let res0: Arc<Squid> = Arc::new(test_squid(0));
    /// let res1: Arc<Squid> = Arc::new(test_squid(1));
    /// let res2: Arc<Squid> = Arc::new(test_squid(2));
    ///
    /// let incarnation = Incarnation::new(2).unwrap();
    /// let assignment: Assignment = Assignment::builder(
    ///     /*is_frozen=*/ false,
    ///     Generation::new(incarnation, UnixTimeVersion::from_unix_millis(51).unwrap()),
    /// ).unwrap()
    ///     // Assign the first Slice, which must start at `SliceKey::MIN`, to
    ///     // res0 and res1.
    ///     .add_slice_assignment(
    ///         SliceKey::MIN,
    ///         identity("Bifur").into_high_key(),
    ///         &[res0.clone(), res1.clone()],
    ///         Generation::new(incarnation, UnixTimeVersion::from_unix_millis(42).unwrap()),
    ///         /*primary_rate_load_opt=*/ Some(15.7),
    ///         /*subslice_annotations_by_resource=*/ HashMap::new()
    ///     ).unwrap()
    ///     // Assign the second Slice to res1 and res2.
    ///     .add_slice_assignment(
    ///         identity("Bifur"),
    ///         identity("Nori").into_high_key(),
    ///         &[res1.clone(), res2.clone()],
    ///         Generation::new(incarnation, UnixTimeVersion::from_unix_millis(47).unwrap()),
    ///         /*primary_rate_load_opt=*/ Some(35.2),
    ///         /*subslice_annotations_by_resource=*/ HashMap::new()
    ///     ).unwrap()
    ///     // Assign the third Slice to res2 and res0.
    ///     .add_slice_assignment(
    ///         identity("Nori"),
    ///         HighSliceKey::Infinity,
    ///         &[res2.clone(), res0.clone()],
    ///         Generation::new(incarnation, UnixTimeVersion::from_unix_millis(51).unwrap()),
    ///         /*primary_rate_load_opt=*/ Some(25.9),
    ///         /*subslice_annotations_by_resource=*/ HashMap::new()
    ///     ).unwrap()
    ///     .build().unwrap();
    ///
    /// // Verify that "Bombur", which is in ["Bifur", "Nori") is assigned to
    /// // res1 and res2.
    /// let slice_assignment: SliceAssignment =
    ///     assignment.look_up(&identity("Bombur"));
    /// assert_eq!(slice_assignment.len(), 2);
    /// ```
    pub fn builder(is_frozen: bool, generation: Generation) -> Result<AssignmentBuilder, Status> {
        if generation == Generation::EMPTY {
            return Err(Status::invalid_argument("Assignment must have non-empty generation."));
        }

        Ok(AssignmentBuilder {
            is_frozen,
            generation,
            slice_map_builder: SliceMap::builder(),
            assigner_service_info_opt: None,
        })
    }

    /// Whether the assignment is "frozen". When an assignment is frozen, the Assigner's
    /// `AssignmentGenerator` will not update the assignment.
    ///
    /// Assignments can be frozen and unfrozen via the control surface in the test environment, and
    /// ultimately we plan to support control via a production API as well.
    pub fn is_frozen(&self) -> bool {
        self.is_frozen
    }

    /// The generation of this assignment. Assignments have monotonically increasing (but not
    /// necessarily sequential) values generated by the Assigner.
    pub fn generation(&self) -> Generation {
        self.generation
    }

    pub fn assigner_service_info_opt(&self) -> Option<&AssignerServiceInfo> {
        self.assigner_service_info_opt.as_ref()
    }

    /// The mappings of Slices to assigned resources, in increasing `low_inclusive_key` order.
    /// The slices are disjoint and cover the full key space.
    pub fn slice_assignments(&self) -> impl Iterator<Item = SliceAssignment<'_>> {
        self.slice_map.iter().map(|entry| SliceAssignment { entry })
    }

    /// The number of Slices in the assignment.
    pub fn len(&self) -> usize {
        self.slice_map.len()
    }

    /// Whether the assignment is empty. Never true, but this function is
    /// required when `len` is defined.
    pub fn is_empty(&self) -> bool {
        self.slice_map.is_empty()
    }

    /// Looks up the [`SliceAssignment`] containing `key`.
    pub fn look_up(&self, key: &SliceKey) -> SliceAssignment<'_> {
        SliceAssignment {
            entry: self.slice_map.look_up(key),
        }
    }

    /// Returns whether the given `key` is assigned to the given `resource`.
    pub fn is_assigned_key(&self, key: &SliceKey, resource: &Squid) -> bool {
        self.is_resource_assigned(&self.look_up(key).entry.value().resources_set, resource)
    }

    /// Returns the first (known) generation number at which the given key was assigned to the given
    /// resource, or `None` if the key is not assigned to the resource.
    pub fn continuously_assigned_generation(
        &self,
        key: &SliceKey,
        resource: &Squid,
    ) -> Option<UnixTimeVersion> {
        let slice_assignment: SliceAssignment = self.look_up(key);
        slice_assignment
            .subslice_annotations_by_resource()
            .get(resource)
            .and_then(|subslice_annotations: &SliceMap<Option<SubsliceAnnotation>>| {
                subslice_annotations
                    .look_up(key)
                    .value()
                    .as_ref()
                    .map(|annotation: &SubsliceAnnotation| annotation.continuous_generation_number)
            })
            // Fall back to the slice assignment's generation if there is no subslice annotation for
            // this key. This behavior is equivalent to the Scala `SubsliceAnnotationsMap` class's
            // behavior of filling in gaps between annotations with the slice assignment's
            // generation.
            .or_else(|| {
                if slice_assignment.resources_set().contains(resource) {
                    Some(slice_assignment.generation().number())
                } else {
                    None
                }
            })
    }

    /// Returns a vector of [`SliceAssignment`] for the assigned [`Slice`]s on `resource`.
    pub fn get_assigned_slice_assignments(&self, resource: &Squid) -> Vec<SliceAssignment<'_>> {
        self.slice_assignments()
            .filter(|slice_assignment: &SliceAssignment| {
                self.is_resource_assigned(slice_assignment.resources_set(), resource)
            })
            .collect()
    }

    /// Returns the slices assigned to the given `resource`.
    pub fn get_slice_set_for_resource(&self, resource: &Squid) -> SliceSet {
        self.slice_map
            .iter()
            .filter(|entry: &SliceMapEntryRef<InternalSliceAssignment>| {
                self.is_resource_assigned(&entry.value().resources_set, resource)
            })
            .map(|entry: SliceMapEntryRef<_>| entry.to_slice())
            .collect()
    }

    /// Returns all resources that are assigned to some slice in the assignment.
    ///
    /// # Implementation Notes
    ///
    /// This computes the set on each call to keep assignment free of cached derived state.
    /// If this becomes a hot path, consider storing the assigned resources at construction
    /// time instead.
    pub fn assigned_resources(&self) -> HashSet<Arc<Squid>> {
        let mut resources: HashSet<Arc<Squid>> = HashSet::new();
        for slice_assignment in self.slice_assignments() {
            resources.extend(slice_assignment.resources_set().iter().cloned());
        }
        resources
    }

    /// Returns whether `lookup_resource` is considered to be assigned a slice given the slice's
    /// currently assigned resources (see `is_resource_assignment_compatible` for how resources
    /// are matched).
    fn is_resource_assigned(
        &self,
        assigned_resources: &HashSet<Arc<Squid>>,
        lookup_resource: &Squid,
    ) -> bool {
        assigned_resources
            .iter()
            .any(|assigned_resource: &Arc<Squid>| {
                self.is_resource_assignment_compatible(assigned_resource, lookup_resource)
            })
    }

    /// Determines whether `lookup_resource` is considered equivalent to `assigned_resource` for an
    /// assignment lookup. Only the resource address is compared, not the full SQUID, so that
    /// resource restarts (which change the SQUID but not the address) do not invalidate assignments
    /// -- keeping the Assigner's availability less critical to the sharded service's availability.
    fn is_resource_assignment_compatible(
        &self,
        assigned_resource: &Squid,
        lookup_resource: &Squid,
    ) -> bool {
        assigned_resource.resource_address() == lookup_resource.resource_address()
    }

    /// Creates a diff including only Slices that have changed since `diff_generation`. The diff is
    /// full (i.e., contains all Slice assignments) in several cases:
    ///
    /// * When `diff_generation` is in a different incarnation from the assignment generation. We
    ///   must assume divergent histories across incarnations since incarnations are bumped only due
    ///   to disasters or breaking changes to the storage layer.
    /// * When the assignment generation is in a loose incarnation (see discussion in
    ///   [`crate::Incarnation`] of why diffs are not viable).
    ///
    /// See [`Assignment::from_diff`] for an example.
    ///
    /// VALIDATES: `diff_generation` is less than or equal to [`Self::generation`].
    pub fn to_diff(&self, diff_generation: Generation) -> Result<DiffAssignment, Status> {
        if diff_generation > self.generation {
            return Err(Status::invalid_argument(format!(
                "Diff generation {:?} must be less than or equal to assignment generation {:?}",
                diff_generation, self.generation
            )));
        }

        let full_assignment_required: bool = self.generation.incarnation().is_loose()
            || diff_generation.incarnation() != self.generation.incarnation();

        let diff_slice_map: DiffAssignmentSliceMap = if full_assignment_required {
            DiffAssignmentSliceMap::Full(self.slice_map.clone())
        } else {
            // Filter slice assignments with generations greater than the diff generation.
            let mut slice_map_builder = SliceMap::partial_builder();
            for slice_assignment in &self.slice_map {
                if slice_assignment.value().generation > diff_generation {
                    slice_map_builder.add_slice(
                        slice_assignment.low_inclusive_key().clone(),
                        slice_assignment.high_exclusive_key().to_owned(),
                        slice_assignment.value().clone(),
                    )?;
                }
            }
            DiffAssignmentSliceMap::Partial {
                diff_generation,
                slice_map: slice_map_builder.build()?,
            }
        };
        DiffAssignment::new(
            self.is_frozen,
            self.generation,
            diff_slice_map,
            self.assigner_service_info_opt.clone(),
        )
    }

    /// Creates an [`Assignment`] given a potentially partial `diff` and an optional known
    /// assignment to fill in gaps in the diff. Consider for example the following inputs and
    /// expected output of this function, where the known assignment has generation 47, and the
    /// proto contains diffs between generation 42 and 50 (there are gaps for Slices with
    /// generations less than 42):
    ///
    /// ```ignore
    /// known_assignment @47:  | pod0 @42 | pod1 @47 | pod2 @42 | pod3 @30 | {pod3, pod4} @42     |
    /// proto @50, diff @42:   | gap      | pod1 @47 | {pod2, pod3} @50    | pod3 @50 | pod4 @50  |
    /// result @50:            | pod0 @42 | pod1 @47 | {pod2, pod3} @50    | pod3 @50 | pod4 @50  |
    /// ```
    ///
    /// Returns either the full assignment or an error if the diff cannot be used.
    pub fn from_diff(
        known_assignment_opt: Option<&Assignment>,
        diff: DiffAssignment,
    ) -> Result<Assignment, Status> {
        if let Some(known_assignment) = known_assignment_opt {
            #[allow(clippy::comparison_chain)] // Basic comparison operators are more readable here.
            if known_assignment.generation > diff.generation() {
                // The known assignment is fresher than the diff assignment. Nothing to learn here.
                return Err(Status::failed_precondition("Diff is too stale"));
            } else if known_assignment.generation == diff.generation() {
                // The diff assignment is already known. Also nothing to learn.
                return Err(Status::already_exists("The diff assignment is already known"));
            }
        }

        // Capture the fields from the diff before we consume the diff to get its slice map by
        // value.
        let is_frozen: bool = diff.is_frozen();
        let generation: Generation = diff.generation();
        let assigner_service_info_opt: Option<AssignerServiceInfo> =
            diff.assigner_service_info_opt().cloned();

        let slice_map: SliceMap<InternalSliceAssignment> = match diff.into_slice_map() {
            DiffAssignmentSliceMap::Partial {
                diff_generation,
                slice_map,
            } => {
                let known_assignment: &Assignment = known_assignment_opt.ok_or_else(|| {
                    // We can't create an assignment from a diff when there is no known assignment.
                    Status::invalid_argument("Can't use diff because there is no known assignment")
                })?;
                full_slice_map_from_partial_diff(known_assignment, diff_generation, slice_map)?
            }

            // If we have a full assignment, pass it through.
            DiffAssignmentSliceMap::Full(slice_map) => slice_map,
        };

        Ok(Assignment {
            is_frozen,
            generation,
            slice_map,
            assigner_service_info_opt,
        })
    }
}

/// Applies the partial diff `diff_slice_map` (which is relative to the generation
/// `diff_generation`) to the given known assignment to produce the slice map for a full assignment.
///
/// Returns an error if the diff cannot be applied to the known assignment.
fn full_slice_map_from_partial_diff(
    known_assignment: &Assignment,
    diff_generation: Generation,
    diff_slice_map: SliceMap<Option<InternalSliceAssignment>>,
) -> Result<SliceMap<InternalSliceAssignment>, Status> {
    if known_assignment.generation < diff_generation {
        // We don't have enough information to decode the assignment.
        return Err(Status::failed_precondition("Known assignment too stale to make use of diff"));
    }

    // Intersect the diff map (which may include gaps) with the known assignment.
    type IntersectionSliceMap<'a> =
        SliceMap<IntersectionEntry<'a, InternalSliceAssignment, Option<InternalSliceAssignment>>>;
    let intersected: IntersectionSliceMap =
        SliceMap::intersect(&known_assignment.slice_map, &diff_slice_map);

    let mut new_slice_map_builder: SliceMapBuilder<InternalSliceAssignment> = SliceMap::builder();
    for entry in &intersected {
        let existing_entry: SliceMapEntryRef<'_, InternalSliceAssignment> = entry.value().left;
        let existing_slice_assignment: &InternalSliceAssignment = existing_entry.value();
        let new_entry: SliceMapEntryRef<'_, Option<InternalSliceAssignment>> = entry.value().right;
        match new_entry.value() {
            Some(new_slice_assignment) => {
                if existing_slice_assignment.generation > new_slice_assignment.generation {
                    return Err(Status::invalid_argument(format!(
                        "Inconsistency: existing slice assignment has greater generation than new \
                         one: {:?} vs. {:?}",
                        existing_slice_assignment.generation, new_slice_assignment.generation
                    )));
                }

                // A new slice assignment may overwrite multiple existing slice assignments in the
                // case of a merge-like modification. Only emit for the last intersecting entry.
                if new_entry.high_exclusive_key() == entry.high_exclusive_key() {
                    new_slice_map_builder.add_slice(
                        new_entry.low_inclusive_key().clone(),
                        new_entry.high_exclusive_key().to_owned(),
                        new_slice_assignment.clone(),
                    )?;
                }
            }

            None => {
                // The slice is not overwritten in the new assignment.
                new_slice_map_builder.add_slice(
                    existing_entry.low_inclusive_key().clone(),
                    existing_entry.high_exclusive_key().to_owned(),
                    existing_slice_assignment.clone(),
                )?;
            }
        }
    }
    new_slice_map_builder.build()
}

/// A view of the resources assigned to a Slice, returned by
/// [`Assignment::look_up`]. It has a lifetime tied to the `Assignment` from
/// which the lookup was made.
#[derive(Debug)]
pub struct SliceAssignment<'a> {
    entry: SliceMapEntryRef<'a, InternalSliceAssignment>,
}

impl SliceAssignment<'_> {
    /// The low inclusive key of the Slice.
    pub fn low_inclusive_key(&self) -> &SliceKey {
        self.entry.low_inclusive_key()
    }

    /// The high exclusive key of the Slice.
    pub fn high_exclusive_key(&self) -> HighSliceKeyRef<'_> {
        self.entry.high_exclusive_key()
    }

    /// Gets the cloned Slice for the slice assignment.
    pub fn to_slice(&self) -> Slice {
        self.entry.to_slice()
    }

    /// A Set of resources to which the Slice is assigned.
    pub fn resources_set(&self) -> &HashSet<Arc<Squid>> {
        &self.entry.value().resources_set
    }

    /// The resources assigned to the Slice. Useful when the caller needs to efficiently pick one
    /// assigned resource.
    pub fn resources(&self) -> &[Arc<Squid>] {
        &self.entry.value().resources
    }

    /// The number of resources assigned to the Slice.
    pub fn len(&self) -> usize {
        self.entry.value().resources.len()
    }

    /// Returns true if the Slice has no resources assigned to it.
    pub fn is_empty(&self) -> bool {
        self.entry.value().resources.is_empty()
    }

    /// The resource at the given index in the Slice assignment.
    pub fn get(&self, index: usize) -> Option<&Squid> {
        self.entry
            .value()
            .resources
            .get(index)
            .map(|resource: &Arc<Squid>| &**resource)
    }

    /// The generation number for this slice assignment.
    pub fn generation(&self) -> Generation {
        self.entry.value().generation
    }

    /// The primary rate load measurement for this slice as of its generation.
    pub fn primary_rate_load(&self) -> Option<f64> {
        self.entry.value().primary_rate_load_opt
    }

    /// See [`InternalSliceAssignment::subslice_annotations_by_resource`].
    pub fn subslice_annotations_by_resource(
        &self,
    ) -> &HashMap<Arc<Squid>, SliceMap<Option<SubsliceAnnotation>>> {
        &self.entry.value().subslice_annotations_by_resource
    }
}

/// Builder constructing an `Assignment`. The assignment must be built in Slice
/// order, starting with `SliceKey::MIN`. See [`Assignment::builder`] for usage.
#[derive(Debug)]
pub struct AssignmentBuilder {
    is_frozen: bool,
    generation: Generation,

    /// The builder for the slice map of slice assignments.
    slice_map_builder: SliceMapBuilder<InternalSliceAssignment>,

    /// The service identity of the Assigner, or `None` when no valid service info is available.
    assigner_service_info_opt: Option<AssignerServiceInfo>,
}

impl AssignmentBuilder {
    /// Sets the Assigner service identity for the assignment being built.
    pub fn with_assigner_service_info_opt(
        mut self,
        assigner_service_info_opt: Option<AssignerServiceInfo>,
    ) -> Self {
        self.assigner_service_info_opt = assigner_service_info_opt;
        self
    }

    /// Sets the value for the next slice.
    ///
    /// We take the high slice key explicitly (as opposed to implicitly using
    /// the low key of the next slice) for consistency with
    /// [`SliceMapBuilder::add_slice`]. See the documentation on that method
    /// for more detail.
    ///
    /// VALIDATES:
    /// - `low_inclusive_key` must be less than `high_exclusive_key`.
    /// - `low_inclusive_key` must equal the `high_exclusive_key` of the
    ///   previous slice, or `SliceKey::MIN` if this is the first slice in the
    ///   assignment.
    pub fn add_slice_assignment(
        mut self,
        low_inclusive_key: SliceKey,
        high_exclusive_key: HighSliceKey,
        resources: &[Arc<Squid>],
        generation: Generation,
        primary_rate_load_opt: Option<f64>,
        subslice_annotations_by_resource: HashMap<Arc<Squid>, SliceMap<Option<SubsliceAnnotation>>>,
    ) -> Result<Self, Status> {
        let slice_assignment = InternalSliceAssignment::new(
            resources.into(),
            generation,
            primary_rate_load_opt,
            subslice_annotations_by_resource,
        )?;
        slice_assignment.check_external_invariants(
            &low_inclusive_key,
            high_exclusive_key.as_ref(),
            self.generation,
        )?;
        self.slice_map_builder.add_slice(
            low_inclusive_key,
            high_exclusive_key,
            slice_assignment,
        )?;
        Ok(self)
    }

    /// Builds the `Assignment` from the slices and squids that have been added
    /// to the builder.
    pub fn build(self) -> Result<Assignment, Status> {
        Ok(Assignment {
            is_frozen: self.is_frozen,
            generation: self.generation,
            slice_map: self.slice_map_builder.build()?,
            assigner_service_info_opt: self.assigner_service_info_opt,
        })
    }
}

/// Internal representation of a Slice in an `Assignment`, indicating that the
/// Slice is assigned to the given squids.
///
/// A simple example of a Slice assignment is:
///
/// `[Balin .. Nori): 2##45 => {Pod1, Pod2}`
///
/// which says that keys from Balin to Nori are assigned to Pod1 and Pod2 and this Slice was last
/// created or modified when the assignment generation number was 2##45. For a detailed description
/// of the generation value and the other values stored, see the documentation on the fields below.
#[derive(Clone, Debug, PartialEq)]
pub struct InternalSliceAssignment {
    /// A Set of resources to which the Slice is assigned.
    resources_set: HashSet<Arc<Squid>>,

    /// The assigned resources in construction (proto) order, used for serialization and for callers
    /// that pick a resource by index.
    resources: ResourceVec,

    /// A slice assignment is guaranteed to have been unchanged between `generation` and the
    /// generation of the assignment containing this slice. For example, if an
    /// `InternalSliceAssignment` has generation 2##42 and it is in an assignment with generation
    /// 2##47, it means that all aspects of the Slice assignment -- Slice boundaries, set of
    /// assigned resources, and load measurements -- have been unchanged between assignment
    /// generations 2##42 and 2##47, inclusive.
    ///
    /// For assignments with a generation in a loose incarnation, `generation` is always the same as
    /// the assignment generation, since Dicer is not guaranteed to be aware of all assignments that
    /// have been distributed for loose incarnations. Note that we still track best-guess subslice
    /// annotation information, but the `generation` value is strict. This strictness is important
    /// because the `generation` value is used to determine which slices need to be included in
    /// `DiffAssignment`, used to synchronize assignments between Clerks, Slicelets and Assigners
    /// that may have observed divergent assignment histories.
    generation: Generation,

    /// The primary rate load measurement for this slice as of `generation`. (See
    /// `SliceLoad::primary_rate_load` for more details on this measurement.) If this measurement
    /// changes significantly between assignments, `ProposedAssignment.commit()` may choose to
    /// update this value and advance `generation`, even if nothing else has changed in the Slice
    /// assignment. In this case, the subslice annotations still capture assignment continuity, but
    /// the generation bump for the slice assignment as a whole ensures that the roughly accurate
    /// load measurements are disseminated and/or persisted. This measurement is used by the
    /// Assigner when either there are no recent load measurements for a slice (e.g. the assigned
    /// Slicelet is unhealthy), or if there isn't sufficient history (i.e. the latest Slicelet only
    /// recently started receiving traffic for the corresponding slice).
    ///
    /// Invariants: See [`validate_measurement`].
    primary_rate_load_opt: Option<f64>,

    /// The map from each assigned resource to a collection of [`SubsliceAnnotation`]s containing
    /// specific meta-information about disjoint subslices that have been assigned to this resource.
    /// It is used for:
    ///
    /// * Fine-grained handling of `is_assigned_continuously`, e.g. in cases of splits/merges but
    ///   where part of the slice is still assigned to the same resource as before.
    /// * Indicating that state for the subslice can be fetched from another resource, represented
    ///   as a `Transfer`.
    ///
    /// For example, if `subslice_annotations_by_resource["Pod0"]` contains
    /// `SubsliceAnnotation("Fili" -- "Nori", 42, Some(Transfer(11), "Pod1"))`, it means the
    /// subslice `["Fili", "Nori")` has been continuously assigned to Pod0 since generation 42, and
    /// Pod0 should acquire state for `["Fili", "Nori")` from Pod1 if state transfer is enabled.
    ///
    /// # Invariants
    ///
    /// * Each subslice annotation's `continuous_generation_number` must be less than or equal to
    ///   `self.generation.number()`. (The incarnation number is the same by construction, since if
    ///   the incarnation changes, we cannot make any guarantees about continuous assignment.)
    /// * Each subslice must be contained in the assigned slice. But the combined subslices do not
    ///   need to cover the entire assigned slice.
    /// * If a transfer is present, its `from_resource` must be different from the resource that
    ///   owns this annotation in `subslice_annotations_by_resource`.
    subslice_annotations_by_resource: HashMap<Arc<Squid>, SliceMap<Option<SubsliceAnnotation>>>,
}

impl InternalSliceAssignment {
    /// Validates the given arguments and returns a new slice assignment instance.
    pub fn new(
        resources: ResourceVec,
        generation: Generation,
        primary_rate_load_opt: Option<f64>,
        subslice_annotations_by_resource: HashMap<Arc<Squid>, SliceMap<Option<SubsliceAnnotation>>>,
    ) -> Result<Self, Status> {
        if generation == Generation::EMPTY {
            return Err(Status::invalid_argument(
                "Slice assignment must have non-empty generation.",
            ));
        } else if resources.is_empty() {
            return Err(Status::invalid_argument("The assigned resources must be non-empty"));
        }

        let resources_set: HashSet<Arc<Squid>> = resources.iter().cloned().collect();

        // For each resource in `subslice_annotations_by_resource`, verify that the resource itself
        // is valid, and then verify that the annotations for the resource contain valid generation
        // numbers.
        for (resource, annotations) in &subslice_annotations_by_resource {
            // Verify that the resource actually owns the slice.
            if !resources_set.contains(resource) {
                return Err(Status::invalid_argument(format!(
                    "Subslice annotations must be to one of the resources that own the slice: \
                     expected one of {resources_set:?}, got {resource:?}"
                )));
            }

            // A resource must only be present in the map of annotations if it actually has
            // annotations.
            let has_annotations: bool = annotations
                .iter()
                .any(|entry: SliceMapEntryRef<Option<SubsliceAnnotation>>| entry.value().is_some());
            if !has_annotations {
                return Err(Status::invalid_argument(format!(
                    "Subslice annotations for resource {resource:?} should not be empty"
                )));
            }

            // Validate the annotations themselves.
            for annotation_entry in annotations.non_empty_entries() {
                let annotation: &SubsliceAnnotation = annotation_entry.value();

                // Validate the generation number.
                if annotation.continuous_generation_number > generation.number() {
                    return Err(Status::invalid_argument(format!(
                        "Subslice annotations cannot have a newer generation: {:?} is not <= {:?}",
                        annotation.continuous_generation_number,
                        generation.number()
                    )));
                }

                // Validate the state transfer metadata.
                if let Some(state_transfer) = &annotation.state_transfer_opt
                    && state_transfer.from_resource == *resource
                {
                    return Err(Status::invalid_argument(format!(
                        "State transfer cannot be from and to the same resource {resource:?}"
                    )));
                }
            }
        }

        if let Some(load) = primary_rate_load_opt {
            validate_measurement(load)?;
        }

        Ok(InternalSliceAssignment {
            resources_set,
            resources,
            generation,
            primary_rate_load_opt,
            subslice_annotations_by_resource,
        })
    }

    /// Validates the following invariants that are relative to state outside the scope of the
    /// `InternalSliceAssignment` itself, given that it applies to the given slice in an assignment
    /// with the given `assignment_generation`:
    ///
    /// * All subslice annotations must be for subslices that are contained in the given slice.
    /// * The slice assignment's generation must be in the same incarnation as and be less than or
    ///   equal to the overall assignment's generation.
    pub fn check_external_invariants(
        &self,
        low_inclusive_key: &SliceKey,
        high_exclusive_key: HighSliceKeyRef,
        assignment_generation: Generation,
    ) -> Result<(), Status> {
        self.check_assignment_generation(assignment_generation)?;

        for annotations_map in self.subslice_annotations_by_resource.values() {
            for annotation_entry in annotations_map {
                let is_contained: bool = low_inclusive_key <= annotation_entry.low_inclusive_key()
                    && annotation_entry.high_exclusive_key() <= high_exclusive_key;
                if annotation_entry.value().is_some() && !is_contained {
                    return Err(Status::invalid_argument(format!(
                        "Subslice annotations must be contained in slice: [{:?} .. {:?}) is not \
                         contained in [{:?} .. {:?})",
                        annotation_entry.low_inclusive_key(),
                        annotation_entry.high_exclusive_key(),
                        low_inclusive_key,
                        high_exclusive_key
                    )));
                }
            }
        }

        Ok(())
    }

    /// Validates the generation number requirements for this slice assignment in the context of an
    /// assignment with the given generation.
    fn check_assignment_generation(&self, assignment_generation: Generation) -> Result<(), Status> {
        if self.generation.incarnation() != assignment_generation.incarnation() {
            return Err(Status::invalid_argument(format!(
                "Slice generation {:?} must be in the same incarnation as the assignment \
                 generation {:?}.",
                self.generation, assignment_generation
            )));
        }

        if self.generation > assignment_generation {
            return Err(Status::invalid_argument(format!(
                "Slice generation {:?} must be less than or equal to the assignment generation \
                 {:?}.",
                self.generation, assignment_generation
            )));
        }

        Ok(())
    }

    /// Parses the given slice assignment proto and returns the details of the slice assignment as
    /// well as the slice that it applies to.
    ///
    /// # Arguments
    ///
    /// * `resources` - The list of resources for the overall assignment that the slice assignment
    ///                 proto's resource indices refer to.
    pub fn from_proto(
        proto: &SliceAssignmentP,
        resources: &[Arc<Squid>],
    ) -> Result<(Slice, InternalSliceAssignment), Status> {
        // Parse the slice.
        let slice: Slice = slice_from_proto(proto.slice.unwrap_ref_or_default())?;

        // Parse the resources.
        let slice_resources: ResourceVec = proto
            .resource_indices
            .iter()
            .map(|index: &i32| get_resource_by_index(resources, *index))
            .collect::<Result<_, _>>()?;

        let generation = Generation::from_proto(proto.generation.unwrap_ref_or_default())?;

        // Parse the subslice annotations.
        let mut subslice_annotations_by_resource =
            HashMap::<Arc<Squid>, PartialSliceMapBuilder<SubsliceAnnotation>>::new();
        for annotation_proto in &proto.subslice_annotations {
            let slice: Slice = slice_from_proto(annotation_proto.subslice.unwrap_ref_or_default())?;
            let (low_key, high_key): (SliceKey, HighSliceKey) = slice.into_bounds();
            let (annotation, resource): (SubsliceAnnotation, Arc<Squid>) =
                SubsliceAnnotation::from_proto(annotation_proto, resources)?;
            subslice_annotations_by_resource
                .entry(resource)
                .or_default()
                .add_slice(low_key, high_key, annotation)?;
        }

        let slice_assignment = InternalSliceAssignment::new(
            slice_resources,
            generation,
            proto.primary_rate_load_opt,
            subslice_annotations_by_resource
                .into_iter()
                .map(|(resource, builder)| Ok::<_, Status>((resource, builder.build()?)))
                .collect::<Result<_, _>>()?,
        )?;
        Ok((slice, slice_assignment))
    }
}

/// Converts an [`InternalSliceAssignment`] for a given slice to its proto representation.
pub fn slice_assignment_to_proto<'a>(
    low_inclusive_key: &SliceKey,
    high_exclusive_key: HighSliceKeyRef,
    slice_assignment: &'a InternalSliceAssignment,
    resource_builder: &mut ResourceProtoBuilder<'a>,
) -> SliceAssignmentP {
    // Convert the resources in this slice assignment to indices in the proto.
    let resource_indices: Vec<i32> = slice_assignment
        .resources
        .iter()
        .map(|squid: &Arc<Squid>| resource_builder.get_index(squid))
        .collect();

    // We serialize the resources in the subslice annotations in order when constructing proto
    // messages so that there will be only one possible proto representation of each slice
    // assignment. This is not a required invariant of the proto, but can be useful in debugging.
    let mut sorted_subslice_annotations_by_resource: Vec<(
        &Arc<Squid>,
        &SliceMap<Option<SubsliceAnnotation>>,
    )> = slice_assignment
        .subslice_annotations_by_resource
        .iter()
        .collect::<Vec<_>>();
    // sort_by_key would clone Arcs; compare by pointer identity instead.
    #[allow(clippy::unnecessary_sort_by)]
    sorted_subslice_annotations_by_resource
        .sort_by(|(resource1, _), (resource2, _)| resource1.cmp(resource2));

    SliceAssignmentP {
        slice: Some(slice_to_proto(low_inclusive_key, high_exclusive_key)),
        generation: Some(slice_assignment.generation.to_proto()),
        resource_indices,
        subslice_annotations: sorted_subslice_annotations_by_resource
            .iter()
            .flat_map(|(resource, subslice_annotations)| {
                subslice_annotations
                    .non_empty_entries()
                    .map(|annotation_entry: SliceMapEntryRef<SubsliceAnnotation>| {
                        annotation_entry.value().to_proto(
                            annotation_entry.low_inclusive_key(),
                            annotation_entry.high_exclusive_key(),
                            resource,
                            resource_builder,
                        )
                    })
                    .collect::<Vec<_>>()
            })
            .collect(),
        primary_rate_load_opt: slice_assignment.primary_rate_load_opt,
    }
}

/// Vector of SQUIDs. We use an inline representation to avoid heap allocations
/// in the common case.
///
/// We store the SQUIDs behind `Arc`s instead of directly, because a given SQUID
/// is typically repeated many times throughout an assignment, so this saves a
/// lot of memory overhead.
pub type ResourceVec = SmallVec<[Arc<Squid>; INLINE_RESOURCE_SIZE]>;

// Choose an inline capacity such that the size of `ResourceVec` is 32 bytes.
// If the assertion below fails, please revisit assumptions and consider tuning
// the inline capacity differently per feature set or architecture (as
// appropriate).
const INLINE_RESOURCE_SIZE: usize = 2;
static_assertions::assert_eq_size!(ResourceVec, [u8; 32]);

/// A helper for assigning indices to resources when serializing an [`Assignment`].
#[derive(Default)]
pub struct ResourceProtoBuilder<'a> {
    /// A map from each resource to what its index will be in the resulting vector.
    ///
    /// It's important for this to be a linked hash map to preserve the insertion order.
    resource_index_map: LinkedHashMap<&'a Squid, i32>,
}

impl<'a> ResourceProtoBuilder<'a> {
    pub fn new() -> ResourceProtoBuilder<'a> {
        ResourceProtoBuilder {
            resource_index_map: LinkedHashMap::new(),
        }
    }

    /// Returns the index for the given resource, choosing a new index if one has not yet been
    /// chosen.
    pub fn get_index(&mut self, resource: &'a Squid) -> i32 {
        // We call `expect` here because it can only fail if there are over 2 billion SQUIDs in
        // the assignment. At tens of bytes per SQUID, this would mean that they collectively occupy
        // somewhere in the ballpark of 200 GB of memory, at which point we have probably already
        // OOMed. Therefore, it seems preferable to just panic rather than complicate the code by
        // making `to_proto` methods return a `Result`.
        let next_index: i32 = self
            .resource_index_map
            .len()
            .try_into()
            .expect("Resource index overflow");
        *self
            .resource_index_map
            .entry(resource)
            .or_insert(next_index)
    }

    /// Serializes the resources identified by this builder as protos.
    pub fn to_protos(&self) -> Vec<SquidP> {
        self.resource_index_map
            .keys()
            .map(|squid: &&Squid| squid.to_proto())
            .collect()
    }
}

/// Returns the element of the given list of indices at the specified index, or returns a
/// descriptive error message if the index is out of range.
pub(crate) fn get_resource_by_index(
    resources: &[Arc<Squid>],
    index: i32,
) -> Result<Arc<Squid>, Status> {
    usize::try_from(index)
        .ok()
        .and_then(|index: usize| resources.get(index))
        .cloned()
        .ok_or_else(|| {
            Status::invalid_argument(format!(
                "Resource index {} out of range. Proto contains {} resources.",
                index,
                resources.len()
            ))
        })
}
