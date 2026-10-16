use std::collections::HashMap;
use std::sync::LazyLock;

/// Specification for a region.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Region {
    /// The region's identifier URI.
    pub uri: Option<String>,
}

/// Specification for a Kubernetes cluster.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct KubernetesCluster {
    /// The cluster's identifier URI.
    pub uri: Option<String>,
}

/// Container for infrastructure definitions.
struct ComputeInfraDefinition {
    /// Cluster metadata keyed by cluster URI.
    kubernetes_clusters: HashMap<String, KubernetesCluster>,
    /// Region metadata keyed by region URI.
    regions: HashMap<String, Region>,
}

/// Provides the infrastructure metadata needed to validate cluster and region URIs.
pub struct InfraDataModel {
    /// The embedded infrastructure definitions.
    infra_def: ComputeInfraDefinition,
}

impl InfraDataModel {
    /// Creates an infrastructure data model containing the OSS example resources.
    fn from_embedded() -> Self {
        // Keep these resources aligned with the Scala InfraDataModel wrapper.
        let kubernetes_clusters: HashMap<String, KubernetesCluster> = HashMap::from([
            (
                "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01".to_string(),
                KubernetesCluster {
                    uri: Some(
                        "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01".to_string(),
                    ),
                },
            ),
            (
                "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01".to_string(),
                KubernetesCluster {
                    uri: Some(
                        "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
                            .to_string(),
                    ),
                },
            ),
        ]);
        let regions: HashMap<String, Region> = HashMap::from([
            (
                "region:prod/cloud1/public/region1".to_string(),
                Region {
                    uri: Some("region:prod/cloud1/public/region1".to_string()),
                },
            ),
            (
                "region:dev/cloud1/public/region1".to_string(),
                Region {
                    uri: Some("region:dev/cloud1/public/region1".to_string()),
                },
            ),
        ]);
        Self {
            infra_def: ComputeInfraDefinition {
                kubernetes_clusters,
                regions,
            },
        }
    }

    /// Returns the Kubernetes cluster with the given URI, or `None` if not found.
    pub fn get_kubernetes_cluster_by_uri(&self, uri: &str) -> Option<&KubernetesCluster> {
        self.infra_def.kubernetes_clusters.get(uri)
    }

    /// Returns the region for the given URI, or `None` if not found.
    pub fn get_region_by_uri(&self, uri: &str) -> Option<&Region> {
        self.infra_def.regions.get(uri)
    }
}

/// Embedded infrastructure metadata used to validate Kubernetes cluster and region URIs.
pub static FROM_EMBEDDED: LazyLock<InfraDataModel> = LazyLock::new(InfraDataModel::from_embedded);
