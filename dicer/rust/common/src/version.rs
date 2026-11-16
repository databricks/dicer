use prometheus_client::encoding::EncodeLabelSet;
use caching_rust_util::build_info::BuildInfo;
use dicer_rust_external_types::Target;
use raf_metrics::gauge;

use crate::{ClientType, TargetMetricLabels};

/// Used to report the version of the code that the client is running, which allows us to
/// figure out when changes are rolled out and determine when we can make backwards-incompatible
/// changes on the server side.
///
/// Version Changelog:
///
/// 1740454863 (2025-02-24)
/// * The initial implementation of the Rust Slicelet.
///
/// 1752596075 (2025-07-15)
/// * Load reporting supported in the Rust Slicelet.
///
/// 1766011808 (2025-12-17)
/// * State machine-based slice lookup with request hedging support.
/// * Sequential executor-based subscriber handler.
/// * Rust Clerk available for use.
///
/// 1770414740 (2026-02-06)
/// * Rust Slicelets and Clerks set supports_serialized_assignment=true in watch requests.
pub const LATEST_VERSION: i64 = 1770414740;

pub const UNKNOWN_VERSION: i64 = 0;

/// Prometheus labels for the build info metric.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
pub struct BuildInfoLabels {
    // These need to be camel case to be consistent with the label names used in Scala.
    targetCluster: String,
    targetName: String,
    targetInstanceId: String,
    source: ClientType,
    commitTimestamp: String,
    language: &'static str,
}

impl BuildInfoLabels {
    pub fn new(target: &Target, client_type: ClientType, commit_timestamp: String) -> Self {
        let target_metric_labels = TargetMetricLabels::from_target(target);

        BuildInfoLabels {
            targetCluster: target_metric_labels.targetCluster,
            targetName: target_metric_labels.targetName,
            targetInstanceId: target_metric_labels.targetInstanceId,
            source: client_type,
            commitTimestamp: commit_timestamp,
            language: "Rust",
        }
    }
}

gauge!(
    DICER_CLIENT_BUILD_INFO,
    "The build info of the service running the Dicer client, value is always 1.",
    BuildInfoLabels
);

/// Prometheus labels for the commit timestamp metric.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
pub struct CommitTimestampLabels {
    // These need to be camel case to be consistent with the label names used in Scala.
    targetCluster: String,
    targetName: String,
    targetInstanceId: String,
    source: ClientType,
}

impl CommitTimestampLabels {
    pub fn new(target: &Target, client_type: ClientType) -> Self {
        let target_metric_labels = TargetMetricLabels::from_target(target);

        CommitTimestampLabels {
            targetCluster: target_metric_labels.targetCluster,
            targetName: target_metric_labels.targetName,
            targetInstanceId: target_metric_labels.targetInstanceId,
            source: client_type,
        }
    }
}

gauge!(
    DICER_CLIENT_COMMIT_TIMESTAMP,
    "The latest commit timestamp of the service running the Dicer client. \
    The commit timestamp is recorded in epoch milliseconds as value. \
    It's more convenient for aggregations.",
    CommitTimestampLabels
);

/// Records the build info metric for the Dicer client.
///
/// # Arguments
/// * `target`      - The target of the service whose client version is being recorded.
/// * `client_type` - The type of the client.
/// * `branch`      - The branch name of the build.
pub fn record_build_info(target: &Target, client_type: ClientType, branch: &str) {
    let build_info = BuildInfo::from_client_version(branch);

    // Record dicer_client_build_info with formatted timestamp as label
    let commit_timestamp: String = build_info.get_commit_time_label_value();
    let build_info_labels = BuildInfoLabels::new(target, client_type, commit_timestamp);

    DICER_CLIENT_BUILD_INFO
        .get_or_create_owned(&build_info_labels)
        .set(1);

    // Record dicer_client_commit_timestamp with epoch millis as value
    let commit_timestamp_labels = CommitTimestampLabels::new(target, client_type);
    DICER_CLIENT_COMMIT_TIMESTAMP
        .get_or_create_owned(&commit_timestamp_labels)
        .set(build_info.get_commit_time_epoch_millis());
}
