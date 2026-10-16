use universe_infra_lib_rust::{KubernetesCluster, Region, FROM_EMBEDDED};

#[test]
fn test_get_kubernetes_cluster_by_uri_returns_known_cluster() {
    // Test plan: Verify that the embedded model resolves a known Kubernetes cluster. Validate this
    // by looking up an example cluster URI and checking its identifier.
    let cluster_uri = "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01";
    let cluster: &KubernetesCluster = FROM_EMBEDDED
        .get_kubernetes_cluster_by_uri(cluster_uri)
        .expect("example Kubernetes cluster should exist");
    assert_eq!(cluster.uri.as_deref(), Some(cluster_uri));
}

#[test]
fn test_get_kubernetes_cluster_by_uri_rejects_unknown_cluster() {
    // Test plan: Verify that the embedded model rejects an unknown Kubernetes cluster. Validate
    // this by looking up an absent cluster URI and checking that the result is `None`.
    assert!(FROM_EMBEDDED
        .get_kubernetes_cluster_by_uri(
            "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/99"
        )
        .is_none());
}

#[test]
fn test_get_region_by_uri_returns_known_region() {
    // Test plan: Verify that the embedded model resolves a known region. Validate this by looking
    // up an example region URI and checking its identifier.
    let region_uri = "region:prod/cloud1/public/region1";
    let region: &Region = FROM_EMBEDDED
        .get_region_by_uri(region_uri)
        .expect("example region should exist");
    assert_eq!(region.uri.as_deref(), Some(region_uri));
}

#[test]
fn test_get_region_by_uri_rejects_unknown_region() {
    // Test plan: Verify that the embedded model rejects an unknown region. Validate this by looking
    // up an absent region URI and checking that the result is `None`.
    assert!(FROM_EMBEDDED
        .get_region_by_uri("region:prod/cloud1/public/unknown")
        .is_none());
}
