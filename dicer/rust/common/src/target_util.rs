//! Utilities for working with targets that are not exposed in the public API.

use tonic::Status;
use dicer_common_proto_common_proto::dicer::target_p::Type;
use dicer_common_proto_common_proto::dicer::TargetP;
use dicer_rust_external_types::target::TargetVariant::*;
use dicer_rust_external_types::target::{
    get_target_cluster,
    new_app_target,
    new_kubernetes_target,
    target_variant,
    AppTarget,
    TargetVariant,
};
use dicer_rust_external_types::Target;

/// Parses a [`Target`] from its proto representation.
pub fn target_from_proto(proto: &TargetP) -> Result<Target, Status> {
    let name = proto.name();

    match proto.target_type() {
        Type::Unspecified | Type::Kubernetes => {
            let result = match &proto.kubernetes_cluster_uri {
                Some(cluster_uri) => new_kubernetes_target(cluster_uri, name),
                None => Target::new(name),
            };
            result.map_err(|e| Status::invalid_argument(format!("Invalid target proto: {e}")))
        }
        Type::App => {
            let instance_id = proto.instance_id.as_ref().ok_or_else(|| {
                Status::invalid_argument("instanceId must be defined for AppTarget")
            })?;
            new_app_target(name, instance_id.as_str())
                .map_err(|e| Status::invalid_argument(e.to_string()))
        }
    }
}

/// Returns the proto representation of a [`Target`].
pub fn target_to_proto(target: &Target) -> TargetP {
    match target_variant(target) {
        TargetVariant::App(app_target) => app_target_to_proto(app_target),
        TargetVariant::Kubernetes(_k8s_target) => {
            let kubernetes_cluster_uri = get_target_cluster(target).map(|uri| uri.to_string());
            TargetP {
                name: Some(target.name().to_string()),
                kubernetes_cluster_uri,
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            }
        }
    }
}

/// Returns the proto representation of an [`AppTarget`].
pub(crate) fn app_target_to_proto(app_target: &AppTarget) -> TargetP {
    TargetP {
        name: Some(app_target.name().to_string()),
        kubernetes_cluster_uri: None,
        target_type: Some(Type::App.into()),
        instance_id: Some(app_target.instance_id().to_string()),
    }
}

/// Returns a human-readable and parseable representation of the target that roundtrips via
/// [`target_from_parseable_description`]. Used for dpage parameters.
pub fn target_to_parseable_description(target: &Target) -> String {
    format!("{target:?}")
}

/// Parses the [`target_to_parseable_description`] representation of a target.
///
/// Supports parsing both Kubernetes and App targets formats:
///  - For App targets: expects format `appTarget:targetName:instanceId`
///  - For Kubernetes targets with cluster: expects format `clusterUri:targetName`
///  - For Kubernetes targets without cluster: expects just `targetName`
pub fn target_from_parseable_description(description: &str) -> Result<Target, Status> {
    if let Some(app_description) = description.strip_prefix("appTarget:") {
        // Parse AppTarget description.
        let (name, instance_id): (&str, &str) = app_description
            .split_once(':')
            .ok_or_else(|| Status::invalid_argument("Invalid AppTarget description"))?;
        new_app_target(name, instance_id).map_err(|e| Status::invalid_argument(e.to_string()))
    } else {
        // Parse KubernetesTarget description.
        match description.rsplit_once(':') {
            Some((cluster_uri, target_name)) => new_kubernetes_target(cluster_uri, target_name)
                .map_err(|e| Status::invalid_argument(format!("Invalid target description: {e}"))),
            None => Target::new(description)
                .map_err(|e| Status::invalid_argument(format!("Invalid target description: {e}"))),
        }
    }
}

/// Returns whether the component handling `local_target` should serve an incoming request from
/// `request_target`.
///
/// For two Kubernetes targets whose names match but whose cluster identifiers differ, the
/// assignment is still served. This is used for our handling of SMK migration, where a target
/// being migrated may straddle multiple clusters.
///
/// An App `local_target` should serve an assignment to a same-name Kubernetes `request_target`.
/// This allows Kubernetes-target Clerks to successfully watch for assignments from App-target
/// Slicelets, which de-couples the migration of Clerks from Slicelets. This relationship is
/// asymmetric: a Kubernetes `local_target` should not serve an assignment to a request with an
/// App `request_target`. We will migrate Slicelets to App targets completely before Clerks begin
/// migrating, so the reverse direction is never a valid migration state.
///
/// While this special case is allowed, the correct distribution of assignments from Slicelets to
/// Clerks depends on the network configuration of the Clerk (i.e., the `slicelet_hostname` of the
/// Clerk). This dependency already exists for Kubernetes-target Clerks talking to Kubernetes-target
/// Slicelets due to the SMK migration special case described above.
///
/// Other types of mismatches other than these special cases are not served (e.g., two App
/// targets with the same name but different instance_ids).
///
/// TODO(<internal bug>): Tighten this check up after SMK migration finishes.
pub fn should_serve_request_target(local_target: &Target, request_target: &Target) -> bool {
    match (target_variant(local_target), target_variant(request_target)) {
        // Both App: serve only when identical.
        (App(_), App(_)) => local_target == request_target,

        // Both Kubernetes: serve when names match, tolerating a cluster mismatch during SMK
        // migration.
        // App local and Kubernetes request: serve when names match, tolerating the type mismatch
        // during target migration.
        (Kubernetes(_), Kubernetes(_)) | (App(_), Kubernetes(_)) => {
            local_target.name() == request_target.name()
        }

        // Kubernetes local and App request: never serve.
        (Kubernetes(_), App(_)) => false,
    }
}
