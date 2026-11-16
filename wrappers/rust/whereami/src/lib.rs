//! Cluster-location accessor.
//!
//! Reads the cluster `WhereAmI` location from the JSON-encoded `LOCATION` environment variable
//! once per process, and provides typed getters.

use std::env;
use std::sync::{Arc, LazyLock};

use serde::Deserialize;
use serde_json::Error as JsonError;
use thiserror::Error;

/// The location in the `LOCATION` environment variable, or the error reading it, as of first
/// access.
static WHEREAMI: LazyLock<Result<WhereAmI, WhereAmIError>> = LazyLock::new(|| {
    let location: String = env::var("LOCATION").map_err(|_| WhereAmIError::LocationEnvNotSet)?;
    let kubernetes_location: KubernetesLocation = WhereAmI::from_json(&location)?;
    Ok(WhereAmI {
        kubernetes_location: Some(kubernetes_location),
    })
});

/// Where the current process is running.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct WhereAmI {
    /// The Kubernetes location of the process.
    pub kubernetes_location: Option<KubernetesLocation>,
}

/// The Kubernetes cluster the current process is running in.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct KubernetesLocation {
    /// The cluster URI, e.g. "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01".
    pub kubernetes_cluster_uri: Option<String>,
    /// The region URI, e.g. "region:prod/cloud1/public/region1".
    pub region_uri: Option<String>,
    /// The environment of the cluster.
    pub environment: Option<EnvironmentKind>,
}

/// The environment a process is deployed in.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum EnvironmentKind {
    /// The environment is unspecified.
    #[default]
    Unspecified,
    /// The environment for development and testing ad-hoc changes.
    Dev,
    /// The environment to stage and finally verify a code version before it deploys to production.
    Staging,
    /// The production environment facing real customer traffic.
    Prod,
}

impl EnvironmentKind {
    /// Returns the kind with the given value name, or `None` if it is not modeled.
    fn from_str_name(value: &str) -> Option<Self> {
        match value {
            "KIND_UNSPECIFIED" => Some(EnvironmentKind::Unspecified),
            "DEV" => Some(EnvironmentKind::Dev),
            "STAGING" => Some(EnvironmentKind::Staging),
            "PROD" => Some(EnvironmentKind::Prod),
            _ => None,
        }
    }

    /// Returns the value name of this environment kind, e.g. `"DEV"`.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            EnvironmentKind::Unspecified => "KIND_UNSPECIFIED",
            EnvironmentKind::Dev => "DEV",
            EnvironmentKind::Staging => "STAGING",
            EnvironmentKind::Prod => "PROD",
        }
    }
}

/// Error type for WhereAmI operations. It is `Clone` so that [`WhereAmIExt::get_ref`] can return
/// the failure it caches.
#[derive(Clone, Debug, Error)]
pub enum WhereAmIError {
    #[error("LOCATION environment variable not set")]
    LocationEnvNotSet,

    #[error("Failed to parse JSON: {0}")]
    JsonParseFailed(#[source] Arc<JsonError>),

    #[error("Environment field is missing in WhereAmI")]
    EnvironmentMissing,
}

/// Extension trait to add functionality to the WhereAmI data type.
pub trait WhereAmIExt {
    /// Returns the location in the `LOCATION` environment variable, read on the first call. Every
    /// later call returns the first call's result, including a failure, so `LOCATION` must be set
    /// before the first call.
    ///
    /// # Errors
    ///
    /// Returns [`WhereAmIError::LocationEnvNotSet`] if `LOCATION` is unset, or
    /// [`WhereAmIError::JsonParseFailed`] if it is not a JSON [`KubernetesLocation`].
    fn get_ref() -> Result<&'static WhereAmI, WhereAmIError>;

    /// Parses a [`KubernetesLocation`] from a JSON object, with the environment given by its
    /// value name (e.g. `"DEV"`). Unknown fields are ignored, and a missing field or an
    /// environment name that is not modeled is left `None`.
    ///
    /// # Errors
    ///
    /// Returns [`WhereAmIError::JsonParseFailed`] if `json` is not a JSON [`KubernetesLocation`].
    fn from_json(json: &str) -> Result<KubernetesLocation, WhereAmIError>;

    /// Returns the deployment environment of the current process.
    ///
    /// # Errors
    ///
    /// Returns [`WhereAmIExt::get_ref`]'s error unchanged, or [`WhereAmIError::EnvironmentMissing`]
    /// if the location has no environment or names one that is not modeled.
    fn get_deployment_environment() -> Result<EnvironmentKind, WhereAmIError> {
        let whereami: &WhereAmI = Self::get_ref()?;
        whereami
            .kubernetes_location
            .as_ref()
            .and_then(|location: &KubernetesLocation| location.environment)
            .ok_or(WhereAmIError::EnvironmentMissing)
    }
}

impl WhereAmIExt for WhereAmI {
    fn get_ref() -> Result<&'static WhereAmI, WhereAmIError> {
        WHEREAMI.as_ref().map_err(WhereAmIError::clone)
    }

    fn from_json(json: &str) -> Result<KubernetesLocation, WhereAmIError> {
        let location: KubernetesLocationJson = serde_json::from_str(json)
            .map_err(|error: JsonError| WhereAmIError::JsonParseFailed(Arc::new(error)))?;
        Ok(KubernetesLocation {
            kubernetes_cluster_uri: location.kubernetes_cluster_uri,
            region_uri: location.region_uri,
            environment: location
                .environment
                .as_deref()
                .and_then(EnvironmentKind::from_str_name),
        })
    }
}

/// The JSON form of [`KubernetesLocation`].
#[derive(Deserialize)]
struct KubernetesLocationJson {
    kubernetes_cluster_uri: Option<String>,
    region_uri: Option<String>,
    environment: Option<String>,
}
