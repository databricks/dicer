use caching_rust_util::kubernetes_cluster_uri::KubernetesClusterUri;
use caching_rust_util_testing::test_data::KUBERNETES_CLUSTER_URI_TEST_DATA;

/// An example of a valid Kubernetes cluster URI (present in the embedded InfraDataModel).
const EXAMPLE_CLUSTER_URI: &str = "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01";

#[test]
fn test_from_uri_accepts_valid() {
    // Test plan: Verify that from_uri accepts every cluster URI in the shared test data (present in
    // the embedded InfraDataModel) and round-trips it back to the same string.
    for uri in &KUBERNETES_CLUSTER_URI_TEST_DATA.valid_uris {
        let parsed = KubernetesClusterUri::from_uri(uri.clone())
            .unwrap_or_else(|err| panic!("{uri:?} should parse, but got {err:?}"));
        assert_eq!(parsed.to_string(), *uri);
        assert_eq!(format!("{parsed:?}"), format!("{uri:?}"));
    }
}

#[test]
fn test_from_uri_rejects_invalid() {
    // Test plan: Verify that from_uri rejects every value in the shared invalid set (wrong scheme,
    // malformed, or absent from the embedded InfraDataModel).
    for uri in &KUBERNETES_CLUSTER_URI_TEST_DATA.invalid_uris {
        KubernetesClusterUri::from_uri(uri.clone())
            .expect_err(&format!("{uri:?} should be rejected, but parsed"));
    }
}

#[test]
fn test_new_accepts_valid() {
    // Test plan: Verify that `new` (structural validation, Rust-only) accepts a well-formed cluster
    // URI and produces the expected `Display` and `Debug` representations.
    let parsed = KubernetesClusterUri::new(EXAMPLE_CLUSTER_URI.to_string()).unwrap();
    assert_eq!(parsed.to_string(), EXAMPLE_CLUSTER_URI);
    assert_eq!(format!("{parsed:?}"), format!("{EXAMPLE_CLUSTER_URI:?}"));
}

#[test]
fn test_new_rejects_malformed() {
    // Test plan: Verify that `new` (structural validation, Rust-only) rejects malformed URIs. This
    // is Rust-specific: `new` validates URI shape without consulting the model.
    let invalid_uris = [
        "http:prod/cloud1/public/region1/clustertype2/01", // Wrong scheme.
        "prod/cloud1/public/region1/clustertype2/01",      // Missing scheme.
        "kubernetes-cluster:prod/cloud1/public/region1/gc", // Too few path parts.
        "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01/bogus", // Too many path parts.
        "kubernetes-cluster://prod/cloud1/public/region1/clustertype2/01", // Not opaque.
    ];

    for invalid_uri in invalid_uris {
        KubernetesClusterUri::new(invalid_uri.to_string()).unwrap_err();
    }
}

