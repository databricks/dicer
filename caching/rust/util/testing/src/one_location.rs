//! Test constants for WhereAmI environment variables.

/// Example JSON string for a control plane cluster (GENERAL cluster).
///
/// # Example
///
/// ```ignore
/// use std::env;
/// use caching_rust_util_testing::one_location::EXAMPLE_CONTROL_PLANE_LOCATION;
///
/// // Set the Location environment variable.
/// env::set_var("LOCATION", EXAMPLE_CONTROL_PLANE_LOCATION);
///
/// // Doing something that requires the WhereAmI environment variable.
/// // ...
///
/// // Clear the Location environment variable.
/// env::remove_var("LOCATION");
/// ```
pub const EXAMPLE_CONTROL_PLANE_LOCATION: &str = r#"{
    "cloud_provider": "AWS",
    "cloud_provider_region": "AWS_US_WEST_2",
    "environment": "DEV",
    "kubernetes_cluster_type": "GENERAL",
    "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01",
    "region_uri": "region:dev/cloud1/public/region1",
    "regulatory_domain": "PUBLIC"
}"#;

/// Example JSON string for a data plane cluster (SERVERLESSONE cluster).
///
/// # Example
///
/// ```ignore
/// use std::env;
/// use caching_rust_util_testing::one_location::EXAMPLE_DATA_PLANE_LOCATION;
///
/// // Set the Location environment variable.
/// env::set_var("LOCATION", EXAMPLE_DATA_PLANE_LOCATION);
///
/// // Doing something that requires the WhereAmI environment variable.
/// // ...
///
/// // Clear the Location environment variable.
/// env::remove_var("LOCATION");
/// ```
pub const EXAMPLE_DATA_PLANE_LOCATION: &str = r#"{
    "cloud_provider": "AZURE",
    "cloud_provider_region": "AZURE_WESTUS",
    "environment": "DEV",
    "kubernetes_cluster_type": "SERVERLESSONE",
    "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud2/public/region4/s1/5bxgyk",
    "region_uri": "region:dev/cloud2/public/region4",
    "regulatory_domain": "PUBLIC"
}"#;

/// The Kubernetes cluster URI corresponding to [`EXAMPLE_CONTROL_PLANE_LOCATION`].
pub const EXAMPLE_CONTROL_PLANE_CLUSTER_URI: &str =
    "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01";

/// The region URI corresponding to [`EXAMPLE_CONTROL_PLANE_LOCATION`].
pub const EXAMPLE_CONTROL_PLANE_REGION_URI: &str = "region:dev/cloud1/public/region1";

/// The Kubernetes cluster URI corresponding to [`EXAMPLE_DATA_PLANE_LOCATION`].
pub const EXAMPLE_DATA_PLANE_CLUSTER_URI: &str =
    "kubernetes-cluster:test-env/cloud2/public/region4/s1/5bxgyk";
