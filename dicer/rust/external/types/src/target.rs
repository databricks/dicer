use std::fmt::{self, Debug, Display, Formatter};

use anyhow::anyhow;
use caching_rust_util::kubernetes_cluster_uri::KubernetesClusterUri;
use caching_rust_util::rfc1123;

/// Identifies a Dicer-sharded service.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct Target {
    variant: TargetVariant,
}

/// Internal enum representing the type of target.
///
/// Note: This type is public for use within the dicer/rust codebase but is not re-exported
/// by `//dicer/rust/external:external_lib` and therefore not accessible by customers.
#[derive(Clone, PartialEq, Eq, Hash)]
pub enum TargetVariant {
    /// A Dicer-sharded service that has at most one instance per cluster.
    Kubernetes(KubernetesTarget),
    /// A Dicer-sharded service that may have multiple instances per cluster.
    App(AppTarget),
}

/// Identifier for a Kubernetes target.
///
/// Note: This type is public for use within the dicer/rust codebase but is not re-exported
/// by `//dicer/rust/external:external_lib` and therefore not accessible by customers.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct KubernetesTarget {
    target_name: String,

    /// The URI of the Kubernetes cluster where the Dicer-sharded service is running.
    ///
    /// When this is not defined, Dicer assumes that the sharded service is running in the same
    /// cluster as the Dicer backend. If multiple instances of a sharded service, each running in a
    /// different cluster, are connecting to the same Dicer backend (e.g., a GENERAL cluster Dicer
    /// backend), then the cluster URI is required to differentiate between those instances.
    cluster_opt: Option<KubernetesClusterUri>,
}

impl Debug for KubernetesTarget {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        if let Some(uri) = &self.cluster_opt {
            write!(f, "{uri}:")?;
        }
        write!(f, "{}", self.target_name)
    }
}

impl Target {
    /// Constructs a new [`Target`] with the given name.
    ///
    /// Returns an error if `target_name` is malformed. See [`validate_target_name`] for
    /// the constraints on `target_name`.
    pub fn new(target_name: impl Into<String>) -> anyhow::Result<Target> {
        let target_name: String = target_name.into();
        validate_target_name(&target_name)?;
        let k8s_target = KubernetesTarget {
            target_name,
            cluster_opt: None,
        };
        let variant = TargetVariant::Kubernetes(k8s_target);
        Ok(Target { variant })
    }

    /// The name of the Dicer-sharded service as defined in <internal link>.
    pub fn name(&self) -> &str {
        match &self.variant {
            TargetVariant::Kubernetes(k8s_target) => &k8s_target.target_name,
            TargetVariant::App(app_target) => app_target.name(),
        }
    }
}

/// Canonical string representation of the [`Target`]. This is a STABLE format used as a
/// Prometheus label value and matches Scala's `Target.toString` byte-for-byte:
///   - Kubernetes target: `<clusterUri>:<name>`, or `<name>` if no cluster URI is set.
///   - App target:        `appTarget:<name>:<instanceId>`.
///
/// Dashboards and alerts key off this format -- do not change without coordinating with the
/// Scala side.
impl Display for Target {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match &self.variant {
            TargetVariant::Kubernetes(k8s_target) => Debug::fmt(k8s_target, f),
            TargetVariant::App(app_target) => Debug::fmt(app_target, f),
        }
    }
}

/// `Debug` produces the same canonical string as [`Display`].
impl Debug for Target {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        Display::fmt(self, f)
    }
}

/// Identifier for a Dicer-sharded service that is globally uniquely identified by a `name` and
/// `instance_id` pair. See [`new_app_target`] for the validity requirements (any RFC 1123 label).
///
/// Note: unlike Kubernetes targets, AppTargets do not need to encode location information (e.g.,
/// cluster URI) about the Dicer-sharded service because the `instance_id` is globally unique
/// across all instances and clusters.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct AppTarget {
    name: String,
    instance_id: String,
}

impl AppTarget {
    /// Returns the instance ID that distinguishes this instance from other instances with the same
    /// target name.
    ///
    /// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and
    /// therefore is not accessible by customers.
    pub fn instance_id(&self) -> &str {
        &self.instance_id
    }

    /// Returns the app name for this target as a string.
    ///
    /// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and
    /// therefore is not accessible by customers.
    pub fn name(&self) -> &str {
        &self.name
    }
}

impl Debug for AppTarget {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "appTarget:{}:{}", self.name, self.instance_id)
    }
}

/// Returns a reference to the variant of the given [`Target`].
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and therefore
/// is not accessible by customers.
pub fn target_variant(target: &Target) -> &TargetVariant {
    &target.variant
}

/// Returns the URI of the Kubernetes cluster where the Dicer-sharded service specified by the given
/// [`Target`] is running.
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and therefore
/// is not accessible by customers.
///
/// Returns `None` for `AppTarget` variants since they are not identified by cluster location.
pub fn get_target_cluster(target: &Target) -> Option<&KubernetesClusterUri> {
    match &target.variant {
        TargetVariant::Kubernetes(k8s_target) => k8s_target.cluster_opt.as_ref(),
        TargetVariant::App(_) => None,
    }
}

/// Returns the instance ID for an `AppTarget`.
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and therefore
/// is not accessible by customers.
///
/// Returns `None` for Kubernetes target variants.
pub fn get_instance_id(target: &Target) -> Option<&str> {
    match &target.variant {
        TargetVariant::App(app_target) => Some(app_target.instance_id()),
        TargetVariant::Kubernetes(_) => None,
    }
}

/// Validates that a target name meets RFC 1123 label name requirements.
///
/// Requirements:
/// - contain at most 63 characters
/// - contain only lowercase alphanumeric characters or '-'
/// - start with an alphanumeric character
/// - end with an alphanumeric character
///
/// See https://kubernetes.io/docs/concepts/overview/working-with-objects/names for details.
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that the function is not re-exported by `//dicer/rust/external:external_lib` and therefore
/// is not accessible by customers.
pub fn validate_target_name(target_name: &str) -> anyhow::Result<()> {
    if !rfc1123::is_valid(target_name) {
        return Err(anyhow!("Target name must match regex {}: {}", *rfc1123::REGEX, target_name));
    }
    Ok(())
}

/// Constructs a `Target` that explicitly specifies the Kubernetes cluster where the Dicer-
/// sharded service is running. This factory is internal to Dicer.
///
/// # Arguments
///
/// * `cluster_uri` - A Kubernetes cluster URI as specified at <internal link> (e.g.
///   "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01").
///
/// * `target_name` - The name of the Dicer-sharded service as defined in
///   <internal link> or <internal link>, e.g. "softstore-storelet" for the Softstore cache.
///   It should match the name defined in Dicer config (dicer/external/config). Since these names
///   are used to identify Kubernetes resources, they must meet the requirements for RFC 1123
///   label names:
///   - contain at most 63 characters
///   - contain only lowercase alphanumeric characters or '-'
///   - start with an alphanumeric character
///   - end with an alphanumeric character
///
///   See https://kubernetes.io/docs/concepts/overview/working-with-objects/names for details.
///
/// # Returns
///
/// Returns an error if `cluster_uri` or `target_name` is malformed.
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that the function is not re-exported by `//dicer/rust/external:external_lib` and therefore
/// is not accessible by customers. External customers should use [`Target::new`] instead.
pub fn new_kubernetes_target(
    cluster_uri: impl Into<String>,
    target_name: impl Into<String>,
) -> anyhow::Result<Target> {
    let target_name = target_name.into();
    validate_target_name(&target_name)?;
    let k8s_cluster_uri = KubernetesClusterUri::from_uri(cluster_uri.into())?;
    let k8s_target = KubernetesTarget {
        target_name,
        cluster_opt: Some(k8s_cluster_uri),
    };
    let variant = TargetVariant::Kubernetes(k8s_target);
    Ok(Target { variant })
}

/// Creates a new [`Target`] for a Dicer-sharded service that is identified by the given `name`
/// and `instance_id`.
///
/// * `name` - The name of the Dicer-sharded service. Must meet the requirements for RFC 1123
///   label names:
///   - contain at most 63 characters
///   - contain only lowercase alphanumeric characters or '-'
///   - start with an alphanumeric character
///   - end with an alphanumeric character
///
/// * `instance_id` - The identifier of the running instance of a Dicer-sharded service. A running
///   instance is a deployment of the service whose pods should be sharded together by Dicer as a
///   single group. Must satisfy the same RFC 1123 label name requirements as `name`.
///
/// Returns an error if `name` or `instance_id` is not a valid RFC 1123 label.
///
/// # Examples
///
/// ```no_run
/// use dicer_rust_external_types::target::new_app_target;
///
/// let target = new_app_target("my-service", "instance-1").expect("valid RFC 1123 labels");
/// ```
///
/// This is a free-standing function rather than a method on `Target`, because we don't want it to
/// be callable by customers.
///
/// Note that this function is not re-exported by `//dicer/rust/external:external_lib` and
/// therefore is not accessible by customers. External customers should use the public
/// wrapper in `//dicer/rust/friend/external:external_lib`.
// TODO(<internal bug>): "App" is a legacy concept; rename AppTarget to Target once KubernetesTarget is
// removed and only one Target type remains.
pub fn new_app_target(
    name: impl Into<String>,
    instance_id: impl Into<String>,
) -> anyhow::Result<Target> {
    let name = name.into();
    let instance_id = instance_id.into();
    validate_target_name(&name)?;
    if !rfc1123::is_valid(&instance_id) {
        return Err(anyhow!("AppTarget instanceId must be a valid RFC 1123 label: {instance_id}"));
    }
    let app_target = AppTarget { name, instance_id };
    Ok(Target {
        variant: TargetVariant::App(app_target),
    })
}
