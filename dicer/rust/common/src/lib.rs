//! Types and utilities that are shared across all of Dicer.

mod assigner_service_info;
mod assignment;
mod assignment_cell_value;
mod client_request;
mod client_response;
mod client_type;
mod common_slicez;
mod dicer_internal_headers;
mod diff_assignment;
mod dpage_view_helpers;
mod generation;
mod metric_measurement;
mod recent_subscribers_tracker;
mod redirect;
mod slice_map;
mod slice_set;
mod slice_util;
mod subscriber_handler;
mod subslice_annotation;
mod sync_assignment_state;
mod target_metric_labels;
mod target_name;
mod target_util;
mod transfer;
mod version;
mod watch_server_util;

pub use assigner_service_info::AssignerServiceInfo;
pub use assignment::{
    slice_assignment_to_proto,
    Assignment,
    AssignmentBuilder,
    InternalSliceAssignment,
    ResourceProtoBuilder,
    ResourceVec,
    SliceAssignment,
};
pub use assignment_cell_value::{
    AssignmentCellValue,
    ClerkAssignment,
    ClerkAssignmentCellValue,
    ClerkAssignmentRingMapper,
    SliceInfo,
    SliceletAssignmentCellValue,
};
pub use client_request::{
    key_load_from_proto_for_test,
    key_load_to_proto_for_test,
    load_distribution_from_proto_for_test,
    slice_load_from_proto_for_test,
    slice_load_to_proto_for_test,
    CdfPoint,
    ClientRequest,
    KeyLoad,
    LoadDistribution,
    SliceLoad,
    SliceletData,
    SubscriberData,
};
pub use client_response::ClientResponse;
pub use client_type::*;
pub use common_slicez::{ClerkSubscriberSlicezData, SliceletSubscriberSlicezData};
pub use dicer_internal_headers::{
    HEADER_DICER_INTERNAL_CLIENT_ID,
    HEADER_DICER_INTERNAL_TARGET_CLUSTER_URI,
    HEADER_DICER_INTERNAL_TARGET_INSTANCE_ID,
    HEADER_DICER_INTERNAL_TARGET_NAME,
};
pub use diff_assignment::{DiffAssignment, DiffAssignmentSliceMap};
pub use dpage_view_helpers::{
    generation_to_view_proto,
    get_assignment_view_proto,
    system_time_to_string,
};
pub use generation::{Generation, Incarnation, UnixTimeVersion};
pub use metric_measurement::{
    validate_measurement,
    validate_reported_measurement,
    MAX_REPORTED_MEASUREMENT,
};
pub use recent_subscribers_tracker::{MetricsKey, RecentSubscribersTracker, SubscriberInfo};
pub use redirect::Redirect;
pub use slice_map::{
    BuildSliceMap,
    IntersectionEntry,
    PartialSliceMapBuilder,
    SliceMap,
    SliceMapBuilder,
    SliceMapEntryRef,
    SliceMapIter,
};
pub use slice_set::SliceSet;
pub use slice_util::{slice_from_proto, slice_to_proto};
pub use subscriber_handler::{
    ExecutorBlockerForTest,
    HandlerLocation,
    SliceletAssignmentServer,
    SubscriberHandler,
};
pub use subslice_annotation::SubsliceAnnotation;
pub use sync_assignment_state::SyncAssignmentState;
pub use target_metric_labels::TargetMetricLabels;
pub use target_name::TargetName;
pub use target_util::{
    should_serve_request_target,
    target_from_parseable_description,
    target_from_proto,
    target_to_parseable_description,
    target_to_proto,
};
pub use transfer::Transfer;
pub use version::{record_build_info, LATEST_VERSION};
pub use watch_server_util::{
    configure_watch_server_builder,
    get_default_watch_rpc_timeout,
    register_assignment_service,
    WatchServerConfig,
    DEFAULT_WATCH_RPC_TIMEOUT,
    MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES,
    MIN_WATCH_RPC_TIMEOUT,
};
