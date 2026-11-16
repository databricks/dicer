use common_conf_rust_whereami::{
    EnvironmentKind,
    KubernetesLocation,
    WhereAmI,
    WhereAmIError,
    WhereAmIExt,
};

/// A sample Kubernetes cluster location.
const EXAMPLE_LOCATION: &str = r#"{
    "cloud_provider": "cloud1",
    "environment": "DEV",
    "kubernetes_cluster_type": "GENERAL_CLASSIC",
    "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01",
    "region_uri": "region:dev/cloud1/public/region1",
    "regulatory_domain": "PUBLIC"
}"#;

#[test]
fn test_from_json_reads_location() {
    // Test plan: Verify that JSON parsing reads the cluster URI, region URI and environment,
    // ignoring fields it does not model. Validate this by calling `from_json` on a full location
    // and comparing it against the expected `KubernetesLocation`.
    let location: KubernetesLocation =
        WhereAmI::from_json(EXAMPLE_LOCATION).expect("example location should parse");
    assert_eq!(
        location,
        KubernetesLocation {
            kubernetes_cluster_uri: Some(
                "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01".to_string()
            ),
            region_uri: Some("region:dev/cloud1/public/region1".to_string()),
            environment: Some(EnvironmentKind::Dev),
        }
    );
}

#[test]
fn test_from_json_reads_each_environment() {
    // Test plan: Verify that JSON parsing reads environment strings and that the correct
    // `EnvironmentKind` is returned. Validate this by calling `from_json` on a location containing
    // different environment strings and checking it matches the expected `EnvironmentKind` or
    // `None`. If set then validate that the roundtrip is successful by comparing
    // `EnvironmentKind::as_str_name` against the original environment string.
    let cases: [(&str, Option<EnvironmentKind>); 5] = [
        ("KIND_UNSPECIFIED", Some(EnvironmentKind::Unspecified)),
        ("DEV", Some(EnvironmentKind::Dev)),
        ("STAGING", Some(EnvironmentKind::Staging)),
        ("PROD", Some(EnvironmentKind::Prod)),
        ("FOO", None),
    ];
    for (name, expected) in cases {
        let location: KubernetesLocation =
            WhereAmI::from_json(&format!(r#"{{"environment": "{name}"}}"#))
                .expect("location with an environment name should parse");
        assert_eq!(location.environment, expected, "environment {name}");
        if let Some(kind) = expected {
            assert_eq!(kind.as_str_name(), name);
        }
    }
}

#[test]
fn test_from_json_rejects_malformed_json() {
    // Test plan: Verify that JSON parsing rejects input that is not a JSON object. Validate this by
    // calling `from_json` on a non-JSON string and checking the error variant.
    assert!(matches!(
        WhereAmI::from_json("not valid json"),
        Err(WhereAmIError::JsonParseFailed(_))
    ));
}
