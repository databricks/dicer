use prometheus_client::encoding::EncodeLabelSet;
use dicer_rust_external_types::target::{get_instance_id, get_target_cluster};
use dicer_rust_external_types::Target;

/// Prometheus labels for metrics that are broken down by target.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
pub struct TargetMetricLabels {
    // These need to be camel case to be consistent with the label names used in Scala.
    pub targetCluster: String,
    pub targetName: String,
    pub targetInstanceId: String,
}

impl TargetMetricLabels {
    pub fn from_target(target: &Target) -> TargetMetricLabels {
        let target_cluster = match get_target_cluster(target) {
            Some(uri) => uri.to_string(),
            None => "".to_string(),
        };

        let target_instance_id = get_instance_id(target)
            .map(|id| id.to_string())
            .unwrap_or_default();

        TargetMetricLabels {
            targetCluster: target_cluster,
            targetName: target.name().to_string(),
            targetInstanceId: target_instance_id,
        }
    }
}
