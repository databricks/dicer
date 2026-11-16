use std::fmt::{self, Write};
use std::sync::{Arc, LazyLock};

use prometheus_client::encoding::{EncodeLabelSet, EncodeLabelValue, LabelValueEncoder};
use tonic::Status;
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::prefix_warn;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::diff_assignment_p::SliceAssignmentP;
use dicer_common_proto_common_proto::dicer::{DiffAssignmentP, GenerationP};
use dicer_friend_proto_friend_proto_library::dicer::friend::SquidP;
use dicer_rust_external_types::{HighSliceKey, Slice, SliceKey};
use dicer_rust_friend::Squid;
use raf_metrics::counter;

use crate::assigner_service_info::AssignerServiceInfo;
use crate::assignment::{slice_assignment_to_proto, InternalSliceAssignment, ResourceProtoBuilder};
use crate::slice_map::{BuildSliceMap, SliceMap};
use crate::{Generation, SliceMapEntryRef};

static LOGGER: LazyLock<PrefixLogger> = LazyLock::new(|| PrefixLogger::new("DiffAssignment", ""));

// Counter tracking the number of attempts to parse an assigner service.
// TODO(<internal bug>): Temporary metric until all assignments have a valid assigner service info.
counter!(
    DICER_ASSIGNMENT_SERVICE_INFO_PARSE_TOTAL,
    "Count of assigner service info parse attempts in DiffAssignment::from_proto, labeled by \
     outcome (valid/absent/invalid). assignerName and assignerInstanceId are set if available.",
    AssignerServiceInfoParseOutcomeLabels
);

/// A representation of an [`Assignment`] that may cover the full key space, or may contain only
/// those Slice assignments with generations greater than some diff generation. Used as an
/// optimization in protocols and storage so that only changed Slices need to be conveyed or stored.
///
/// # Invariants
///
///  - For assignments in a "loose" incarnation, the map must be full (diffs not permitted).
///  - For partial maps, the diff generation ([`DiffAssignmentSliceMap::Partial::diff_generation`])
///    must be in the same incarnation as `generation`, which is the generation of the assignment,
///    and must also be less than or equal to `generation`.
///  - All Slice assignments must have generations that are in the same incarnation as `generation`,
///    and must be less than or equal to `generation`.
#[derive(Clone, Debug, PartialEq)]
pub struct DiffAssignment {
    is_frozen: bool,
    generation: Generation,
    slice_map: DiffAssignmentSliceMap,

    /// The service info of the Assigner that generated the assignment this diff is based on. It
    /// should be populated by the generating assigner, but may be absent if the Assigner cannot
    /// determine the service info or if an outdated Assigner binary is deployed.
    assigner_service_info_opt: Option<AssignerServiceInfo>,
}

impl DiffAssignment {
    /// Creates a new [`DiffAssignment`].
    ///
    /// Returns an error if any of the invariants documented above are violated.
    pub fn new(
        is_frozen: bool,
        generation: Generation,
        slice_map: DiffAssignmentSliceMap,
        assigner_service_info_opt: Option<AssignerServiceInfo>,
    ) -> Result<DiffAssignment, Status> {
        if generation == Generation::EMPTY {
            return Err(Status::invalid_argument("Assignment must have non-empty generation."));
        }

        match &slice_map {
            DiffAssignmentSliceMap::Partial {
                diff_generation,
                slice_map,
            } => {
                if diff_generation.incarnation().is_loose() {
                    return Err(Status::invalid_argument(
                        "Assignments in the loose incarnation cannot have diffs.",
                    ));
                }
                if diff_generation.incarnation() != generation.incarnation() {
                    return Err(Status::invalid_argument(format!(
                        "Diff generation {diff_generation:?} must be in the same incarnation as \
                         the assignment generation {generation:?}."
                    )));
                }
                if *diff_generation > generation {
                    return Err(Status::invalid_argument(format!(
                        "Diff generation {diff_generation:?} must be less than or equal to the \
                         assignment generation {generation:?}."
                    )));
                }

                for entry in slice_map {
                    if let Some(slice_assignment) = entry.value() {
                        slice_assignment.check_external_invariants(
                            entry.low_inclusive_key(),
                            entry.high_exclusive_key(),
                            generation,
                        )?;
                    }
                }
            }

            DiffAssignmentSliceMap::Full(slice_map) => {
                for slice_assignment in slice_map {
                    slice_assignment.value().check_external_invariants(
                        slice_assignment.low_inclusive_key(),
                        slice_assignment.high_exclusive_key(),
                        generation,
                    )?;
                }
            }
        }

        Ok(DiffAssignment {
            is_frozen,
            generation,
            slice_map,
            assigner_service_info_opt,
        })
    }

    /// Whether the assignment is frozen (see [`Assignment::is_frozen`]).
    pub fn is_frozen(&self) -> bool {
        self.is_frozen
    }

    /// The generation of the assignment.
    pub fn generation(&self) -> Generation {
        self.generation
    }

    /// Either a "full" or "partial" mapping from Slices to assigned resources.
    pub fn slice_map(&self) -> &DiffAssignmentSliceMap {
        &self.slice_map
    }

    /// The service info of the Assigner that generated the assignment this diff is based on. It
    /// should be populated by the generating assigner but could be absent if the assigner was
    /// unable to determine its service info.
    pub fn assigner_service_info_opt(&self) -> Option<&AssignerServiceInfo> {
        self.assigner_service_info_opt.as_ref()
    }

    /// Consumes the `DiffAssignment` and returns its slice map by value.
    pub fn into_slice_map(self) -> DiffAssignmentSliceMap {
        self.slice_map
    }

    /// Returns the proto representation of the `DiffAssignment`.
    pub fn to_proto(&self) -> DiffAssignmentP {
        let mut resource_proto_builder = ResourceProtoBuilder::new();
        let slice_assignments: Vec<SliceAssignmentP> =
            self.slice_map.to_proto(&mut resource_proto_builder);
        let diff_generation: Option<GenerationP> = match &self.slice_map {
            DiffAssignmentSliceMap::Partial {
                diff_generation, ..
            } => Some(diff_generation.to_proto()),
            DiffAssignmentSliceMap::Full(_) => None,
        };

        DiffAssignmentP {
            generation: Some(self.generation.to_proto()),
            slice_assignments,
            resources: resource_proto_builder.to_protos(),
            is_frozen: if self.is_frozen { Some(true) } else { None },
            diff_generation,
            assigner_service_info: self
                .assigner_service_info_opt
                .as_ref()
                .map(AssignerServiceInfo::to_proto),
        }
    }

    /// Parses a `DiffAssignment` from its proto representation.
    pub fn from_proto(proto: &DiffAssignmentP) -> Result<DiffAssignment, Status> {
        DiffAssignment::from_proto_internal(proto).map_err(|e: Status| {
            // Every 5 minutes, perform a complete dump of the proto that did not parse to aid
            // debugging.
            prefix_warn!(
                *LOGGER,
                every_n_sec = 300,
                "Failed to parse DiffAssignment proto: {}; Proto: {:?}",
                e,
                proto
            );
            e
        })
    }

    /// Private implementation of `from_proto` to permit detailed logging on failure.
    fn from_proto_internal(proto: &DiffAssignmentP) -> Result<DiffAssignment, Status> {
        let generation = Generation::from_proto(proto.generation.unwrap_ref_or_default())?;
        let slice_map = DiffAssignmentSliceMap::from_proto(proto)?;
        // Parses `DiffAssignmentP::AssignerServiceInfoP` and handles logging/metrics based on the
        // outcome.
        let assigner_service_info_opt: Option<AssignerServiceInfo> =
            match &proto.assigner_service_info {
                None => {
                    // The service info is absent, so an "absent" label is recorded.
                    DiffAssignment::record_parse_outcome(
                        AssignerServiceInfoParseOutcome::Absent,
                        None,
                        None,
                    );
                    None
                }
                Some(info_proto) => match AssignerServiceInfo::from_proto(info_proto) {
                    Ok(info) => {
                        // The service info is valid, a "valid" label is recorded with the `name` and
                        // `instance_id`.
                        DiffAssignment::record_parse_outcome(
                            AssignerServiceInfoParseOutcome::Valid,
                            Some(info.name()),
                            Some(info.instance_id()),
                        );
                        Some(info)
                    }
                    Err(e) => {
                        // The service info is invalid, an "invalid" label is recorded with the
                        // proto's `name` and `instance_id` if available.
                        DiffAssignment::record_parse_outcome(
                            AssignerServiceInfoParseOutcome::Invalid,
                            info_proto.name.as_deref(),
                            info_proto.instance_id.as_deref(),
                        );
                        // Every 5 minutes, log the invalid assigner service info that was dropped,
                        // so invalid assigner service info can be debugged.
                        prefix_warn!(
                            *LOGGER,
                            every_n_sec = 300,
                            "Dropping invalid AssignerServiceInfo: {}; Proto: {:?}",
                            e,
                            info_proto
                        );
                        None
                    }
                },
            };

        DiffAssignment::new(
            proto.is_frozen.unwrap_or_default(),
            generation,
            slice_map,
            assigner_service_info_opt,
        )
    }

    /// Records the outcome of an [`AssignerServiceInfo`] parse attempt. Absent names and instance
    /// ids are recorded as empty string labels.
    ///
    /// # Arguments
    ///
    /// * `outcome` - The parse outcome as defined in [`AssignerServiceInfoParseOutcome`] (e.g.,
    ///   "valid", "absent", "invalid").
    /// * `assigner_name_opt` - The name of the Assigner the outcome was recorded for if known.
    /// * `assigner_instance_id_opt` - The instance id of the Assigner the outcome was recorded for
    ///   if known.
    fn record_parse_outcome(
        outcome: AssignerServiceInfoParseOutcome,
        assigner_name_opt: Option<&str>,
        assigner_instance_id_opt: Option<&str>,
    ) {
        let labels = AssignerServiceInfoParseOutcomeLabels {
            outcome,
            assignerName: assigner_name_opt.unwrap_or_default().to_string(),
            assignerInstanceId: assigner_instance_id_opt.unwrap_or_default().to_string(),
        };
        DICER_ASSIGNMENT_SERVICE_INFO_PARSE_TOTAL
            .get_or_create_owned(&labels)
            .inc();
    }
}

/// Encapsulates the types of slice maps that can be used as the internal representation of a
/// [`DiffAssignment`].
#[derive(Clone, Debug, PartialEq)]
pub enum DiffAssignmentSliceMap {
    /// A map containing only the [`InternalSliceAssignment`]s with generations greater than
    /// `diff_generation`.
    Partial {
        diff_generation: Generation,
        slice_map: SliceMap<Option<InternalSliceAssignment>>,
    },

    /// A map containing all [`InternalSliceAssignment`]s.
    Full(SliceMap<InternalSliceAssignment>),
}

/// The outcome of attempting to parse an [`AssignerServiceInfo`] out of a [`DiffAssignmentP`] in
/// [`DiffAssignment::from_proto`]. Used as the `outcome` label value on the
/// `dicer_assignment_service_info_parse_total` metric.
#[derive(Clone, Debug, Hash, PartialEq, Eq)]
enum AssignerServiceInfoParseOutcome {
    /// The `assigner_service_info` field was present and parsed into a valid
    /// [`AssignerServiceInfo`].
    Valid,

    /// The `assigner_service_info` field was absent from the proto.
    Absent,

    /// The `assigner_service_info` field was present but could not be parsed into a valid
    /// [`AssignerServiceInfo`] (e.g. a missing or non-RFC-1123 name or instance id).
    Invalid,
}

impl EncodeLabelValue for AssignerServiceInfoParseOutcome {
    fn encode(&self, encoder: &mut LabelValueEncoder) -> Result<(), fmt::Error> {
        let value: &str = match self {
            AssignerServiceInfoParseOutcome::Valid => "valid",
            AssignerServiceInfoParseOutcome::Absent => "absent",
            AssignerServiceInfoParseOutcome::Invalid => "invalid",
        };
        encoder.write_str(value)
    }
}

impl DiffAssignmentSliceMap {
    /// Returns the proto representation of this `DiffAssignmentSliceMap`.
    fn to_proto<'a>(
        &'a self,
        resource_builder: &mut ResourceProtoBuilder<'a>,
    ) -> Vec<SliceAssignmentP> {
        match self {
            DiffAssignmentSliceMap::Partial { slice_map, .. } => slice_map_to_proto(
                slice_map,
                |slice_assignment: &Option<InternalSliceAssignment>| slice_assignment.as_ref(),
                resource_builder,
            ),

            DiffAssignmentSliceMap::Full(slice_map) => slice_map_to_proto(
                slice_map,
                |slice_assignment: &InternalSliceAssignment| Some(slice_assignment),
                resource_builder,
            ),
        }
    }

    /// Parses a `DiffAssignmentSliceMap` from its proto representation.
    fn from_proto(proto: &DiffAssignmentP) -> Result<DiffAssignmentSliceMap, Status> {
        let diff_generation =
            Generation::from_proto(proto.diff_generation.unwrap_ref_or_default())?;
        if diff_generation.incarnation().is_loose() {
            // When `diff_generation` is empty or in a loose incarnation, a full assignment must be
            // contained in the proto.
            Ok(DiffAssignmentSliceMap::Full(slice_map_from_proto(proto, SliceMap::builder())?))
        } else {
            Ok(DiffAssignmentSliceMap::Partial {
                diff_generation,
                slice_map: slice_map_from_proto(proto, SliceMap::partial_builder())?,
            })
        }
    }
}

/// Prometheus labels for the `dicer_assignment_service_info_parse_total` counter.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
struct AssignerServiceInfoParseOutcomeLabels {
    // These need to be camel case to be consistent with the label names used in Scala.
    outcome: AssignerServiceInfoParseOutcome,
    assignerName: String,
    assignerInstanceId: String,
}

/// Returns the proto representation of the given slice map.
///
/// `get_slice_assignment` should return the slice assignment stored in the given slice map entry,
/// returning `None` if there is no slice assignment in that entry.
fn slice_map_to_proto<'a, T>(
    slice_map: &'a SliceMap<T>,
    get_slice_assignment: impl Fn(&T) -> Option<&InternalSliceAssignment>,
    resource_builder: &mut ResourceProtoBuilder<'a>,
) -> Vec<SliceAssignmentP> {
    slice_map
        .iter()
        // Gaps are not serialized.
        .flat_map(|entry: SliceMapEntryRef<T>| {
            get_slice_assignment(entry.value()).map(|slice_assignment: &InternalSliceAssignment| {
                slice_assignment_to_proto(
                    entry.low_inclusive_key(),
                    entry.high_exclusive_key(),
                    slice_assignment,
                    resource_builder,
                )
            })
        })
        .collect()
}

/// Parses a [`SliceMap`] from its proto representation.
fn slice_map_from_proto<Builder: BuildSliceMap<InputValue = InternalSliceAssignment>>(
    proto: &DiffAssignmentP,
    mut slice_map_builder: Builder,
) -> Result<SliceMap<Builder::OutputValue>, Status> {
    let resources: Vec<Arc<Squid>> = proto
        .resources
        .iter()
        .map(|squid_proto: &SquidP| Squid::from_proto(squid_proto).map(Arc::new))
        .collect::<Result<_, _>>()?;

    for slice_assignment in &proto.slice_assignments {
        let (slice, slice_assignment): (Slice, InternalSliceAssignment) =
            InternalSliceAssignment::from_proto(slice_assignment, &resources)?;
        let (low_key, high_key): (SliceKey, HighSliceKey) = slice.into_bounds();
        slice_map_builder.add_slice(low_key, high_key, slice_assignment)?;
    }

    slice_map_builder.build()
}
