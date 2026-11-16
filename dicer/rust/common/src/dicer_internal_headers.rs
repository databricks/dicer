//! Header constants used in Dicer's internal RPC protocols.
//!
//! Must be kept in sync with the Scala counterpart at
//! `dicer/common/src/DicerInternalHeaders.scala`.

/// Header containing the Dicer target name, set by Dicer clients (Slicelets, Clerks) on watch
/// requests to facilitate target-based rate limiting on the Assigner.
pub const HEADER_DICER_INTERNAL_TARGET_NAME: &str =
    "x-databricks-internal-dicer-internal-target-name";

/// Header containing the Kubernetes cluster URI, set by Dicer clients on watch requests for
/// per-target rate limiting. Only present for KubernetesTargets with a cluster URI.
pub const HEADER_DICER_INTERNAL_TARGET_CLUSTER_URI: &str =
    "x-databricks-internal-dicer-internal-target-cluster-uri";

/// Header containing the app instance ID, set by Dicer clients on watch requests for per-target
/// rate limiting. Only present for AppTargets.
pub const HEADER_DICER_INTERNAL_TARGET_INSTANCE_ID: &str =
    "x-databricks-internal-dicer-internal-target-instance-id";

/// Header containing the Dicer client UUID (typically the Kubernetes pod UID), set by Dicer
/// clients on watch requests for target-based per-client rate limiting on the Assigner.
pub const HEADER_DICER_INTERNAL_CLIENT_ID: &str = "x-databricks-internal-dicer-internal-client-id";
