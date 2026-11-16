//! Tests reading the location in a process without `LOCATION`. These live in their own test binary
//! because the location is read once per process.

use common_conf_rust_whereami::{WhereAmI, WhereAmIError, WhereAmIExt};

#[test]
fn test_get_ref_fails_without_location_env_var() {
    // Test plan: Verify that reading the location fails when `LOCATION` is unset. Validate this by
    // calling `get_ref` in a test binary without `LOCATION` and checking the error variant.
    assert!(matches!(WhereAmI::get_ref(), Err(WhereAmIError::LocationEnvNotSet)));
}

#[test]
fn test_get_deployment_environment_fails_without_location_env_var() {
    // Test plan: Verify that reading the deployment environment fails when `LOCATION` is unset.
    // Validate this by calling `get_deployment_environment` in a test binary without `LOCATION` and
    // checking the error variant.
    assert!(matches!(
        WhereAmI::get_deployment_environment(),
        Err(WhereAmIError::LocationEnvNotSet)
    ));
}
