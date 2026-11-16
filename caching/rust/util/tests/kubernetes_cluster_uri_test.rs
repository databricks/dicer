use caching_rust_util::kubernetes_cluster_uri::KubernetesClusterUri;
use caching_rust_util_testing::test_data::KUBERNETES_CLUSTER_URI_TEST_DATA;

#[test]
fn test_from_uri_accepts_valid() {
    // Test plan: Verify that from_uri accepts every well-formed cluster URI in the shared test
    // data and round-trips it back to the same string.
    for uri in &KUBERNETES_CLUSTER_URI_TEST_DATA.valid_uris {
        let parsed = KubernetesClusterUri::from_uri(uri.clone())
            .unwrap_or_else(|err| panic!("{uri:?} should parse, but got {err:?}"));
        assert_eq!(parsed.to_string(), *uri);
        assert_eq!(format!("{parsed:?}"), format!("{uri:?}"));
    }
}

#[test]
fn test_from_uri_rejects_invalid() {
    // Test plan: Verify that from_uri rejects every value in the shared invalid set (wrong/missing
    // scheme, not opaque, or the wrong number of path parts).
    for uri in &KUBERNETES_CLUSTER_URI_TEST_DATA.invalid_uris {
        KubernetesClusterUri::from_uri(uri.clone())
            .expect_err(&format!("{uri:?} should be rejected, but parsed"));
    }
}
