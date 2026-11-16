//! Pure helper functions that convert Dicer domain objects to DPage view protos.
//!
//! These conversions are shared across Dicer components (assigner, slicelet, etc.) and are
//! intentionally kept free of component-specific logic.

use std::collections::{BTreeMap, HashMap};
use std::ops::Bound;
use std::ops::Bound::{Excluded, Included, Unbounded};
use std::sync::Arc;
use std::time::SystemTime;

use chrono::{DateTime, SecondsFormat, Utc};
use caching_rust_util::system_time_from_unix_millis;
use dicer_common_proto_dpage_proto_library::dicer::{
    AssignmentViewP,
    GenerationViewP,
    ResourceViewP,
    SliceViewP,
    SubsliceAnnotationViewP,
    TopKeyViewP,
};
use dicer_rust_external_types::{HighSliceKeyRef, Slice, SliceKey};
use dicer_rust_friend::Squid;

use crate::{Assignment, Generation, SliceAssignment, SubsliceAnnotation};

/// Converts a [`SystemTime`] to a ISO-8601 timestamp.
pub fn system_time_to_string(time: SystemTime) -> String {
    let datetime: DateTime<Utc> = time.into();
    datetime.to_rfc3339_opts(SecondsFormat::AutoSi, true)
}

/// Converts a [`Generation`] to a [`GenerationViewP`] view proto.
pub fn generation_to_view_proto(generation: Generation) -> GenerationViewP {
    GenerationViewP {
        incarnation: Some(generation.incarnation().value()),
        number: Some(generation.number().to_unix_millis()),
        timestamp_str: Some(system_time_to_string(generation.number().to_time())),
    }
}

/// Converts an optional [`Assignment`] to an [`AssignmentViewP`] view proto.
///
/// When no assignment is present, returns an empty [`AssignmentViewP`].
///
/// # Parameters
///
/// * `assignment_opt` - The assignment to convert, or [`None`] if no assignment exists.
/// * `reported_load_per_resource_opt` - The reported load per resource to include in the resource
///   table.
/// * `reported_load_per_slice_opt` - Local per-slice load values to prefer over the assignment's
///   recorded primary rate load.
/// * `top_keys_opt` - The top keys to include in each slice's `top_keys` list.
pub fn get_assignment_view_proto(
    assignment_opt: Option<&Assignment>,
    reported_load_per_resource_opt: Option<&HashMap<Squid, f64>>,
    reported_load_per_slice_opt: Option<&HashMap<Slice, f64>>,
    top_keys_opt: Option<&BTreeMap<SliceKey, f64>>,
) -> AssignmentViewP {
    let Some(assignment) = assignment_opt else {
        return AssignmentViewP::default();
    };

    let resources: Vec<ResourceViewP> = assignment
        .assigned_resources()
        .into_iter()
        .map(|squid: Arc<Squid>| ResourceViewP {
            address: Some(squid.resource_address().as_str().to_string()),
            uuid: Some(squid.resource_uuid().to_string()),
            creation_time_str: system_time_from_unix_millis(squid.creation_time_millis())
                .map(system_time_to_string),
            attributed_load: reported_load_per_resource_opt
                .and_then(|load_map| load_map.get(squid.as_ref()).copied()),
        })
        .collect();

    let slices: Vec<SliceViewP> = assignment
        .slice_assignments()
        .map(|slice_assignment: SliceAssignment| {
            let slice: Slice = slice_assignment.to_slice();
            let load: Option<f64> = reported_load_per_slice_opt
                .and_then(|load_map| load_map.get(&slice).copied())
                .or_else(|| slice_assignment.primary_rate_load());

            let resources: Vec<String> = slice_assignment
                .resources()
                .iter()
                .map(|resource: &Arc<Squid>| resource.resource_address().as_str().to_string())
                .collect();

            // Top keys within this slice's key range.
            let top_keys: Vec<TopKeyViewP> = match top_keys_opt {
                Some(top_keys) => {
                    let lower_bound: Bound<&SliceKey> = Included(slice.low_inclusive());
                    let upper_bound: Bound<&SliceKey> = match slice.high_exclusive() {
                        HighSliceKeyRef::Finite(high_key) => Excluded(high_key),
                        HighSliceKeyRef::Infinity => Unbounded,
                    };
                    top_keys
                        .range((lower_bound, upper_bound))
                        .map(|(key, load)| TopKeyViewP {
                            key: Some(format!("{key:?}")),
                            load: Some(*load),
                        })
                        .collect()
                }
                None => Vec::new(),
            };

            // Subslice annotations as a flat list grouped by resource.
            let subslice_annotations: Vec<SubsliceAnnotationViewP> = slice_assignment
                .subslice_annotations_by_resource()
                .iter()
                .flat_map(|(resource, annotations)| {
                    annotations.non_empty_entries().map(move |entry| {
                        let annotation: &SubsliceAnnotation = entry.value();
                        SubsliceAnnotationViewP {
                            resource: Some(resource.resource_address().as_str().to_string()),
                            low_key: Some(format!("{:?}", entry.low_inclusive_key())),
                            high_key: Some(format!("{:?}", entry.high_exclusive_key())),
                            continuous_generation_number: Some(
                                annotation.continuous_generation_number.to_unix_millis(),
                            ),
                            state_provider_address: annotation.state_transfer_opt.as_ref().map(
                                |transfer| {
                                    transfer
                                        .from_resource
                                        .resource_address()
                                        .as_str()
                                        .to_string()
                                },
                            ),
                        }
                    })
                })
                .collect();

            SliceViewP {
                low_key: Some(format!("{:?}", slice_assignment.low_inclusive_key())),
                high_key: Some(format!("{:?}", slice_assignment.high_exclusive_key())),
                generation: Some(generation_to_view_proto(slice_assignment.generation())),
                resources,
                load,
                top_keys,
                subslice_annotations,
            }
        })
        .collect();

    AssignmentViewP {
        generation: Some(generation_to_view_proto(assignment.generation())),
        is_frozen: Some(assignment.is_frozen()),
        resources,
        slices,
    }
}
