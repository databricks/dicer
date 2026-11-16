//! Tests reading the location from a `LOCATION` that the test sets. This lives in its own test
//! binary because the location is read once per process.

use std::env;

use common_conf_rust_whereami::{EnvironmentKind, WhereAmI, WhereAmIExt};

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
fn test_get_ref_reads_location_env_var() {
    // Test plan: Verify that the location and its deployment environment are read from the
    // `LOCATION` environment variable. Validate this by setting `LOCATION` to `EXAMPLE_LOCATION`,
    // then calling `get_ref` and `get_deployment_environment`.

    // Setting `LOCATION` here takes effect because the location is read lazily: the `LazyLock`
    // behind `get_ref` reads `LOCATION` on its first call, not when the process starts.
    //
    // SAFETY: `set_var` is unsafe because another thread may read the environment at the same
    // time. This is the only test in this binary, so no other thread does.
    unsafe { env::set_var("LOCATION", EXAMPLE_LOCATION) };

    let expected: WhereAmI = WhereAmI {
        kubernetes_location: Some(
            WhereAmI::from_json(EXAMPLE_LOCATION).expect("example location should parse"),
        ),
    };
    assert_eq!(WhereAmI::get_ref().expect("LOCATION should be read"), &expected);
    assert_eq!(
        WhereAmI::get_deployment_environment().expect("LOCATION should have an environment"),
        EnvironmentKind::Dev
    );
}
