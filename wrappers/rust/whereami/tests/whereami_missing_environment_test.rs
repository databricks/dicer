//! Tests reading the deployment environment from a `LOCATION` that the test sets without an
//! environment. This lives in its own test binary because the location is read once per process.

use std::env;

use common_conf_rust_whereami::{WhereAmI, WhereAmIError, WhereAmIExt};

/// A sample Kubernetes cluster location without an environment.
const LOCATION_WITHOUT_ENVIRONMENT: &str = r#"{
    "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01",
    "region_uri": "region:dev/cloud1/public/region1"
}"#;

#[test]
fn test_get_deployment_environment_fails_without_environment() {
    // Test plan: Verify that reading the deployment environment fails when `LOCATION` has no
    // environment. Validate this by setting `LOCATION` to `LOCATION_WITHOUT_ENVIRONMENT`, then
    // calling `get_deployment_environment` and checking the error variant.

    // Setting `LOCATION` here takes effect because the location is read lazily: the `LazyLock`
    // behind `get_ref` reads `LOCATION` on its first call, not when the process starts.
    //
    // SAFETY: `set_var` is unsafe because another thread may read the environment at the same
    // time. This is the only test in this binary, so no other thread does.
    unsafe { env::set_var("LOCATION", LOCATION_WITHOUT_ENVIRONMENT) };

    assert!(matches!(
        WhereAmI::get_deployment_environment(),
        Err(WhereAmIError::EnvironmentMissing)
    ));
}
