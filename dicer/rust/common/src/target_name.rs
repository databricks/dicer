use std::fmt::{self, Display, Formatter};

use tonic::Status;
use dicer_rust_external_types::target::validate_target_name;
use dicer_rust_external_types::Target;

/// A strongly-typed, validated wrapper around a target name. Used when a verified target name is
/// needed, e.g., as a key in config maps, since configurations in Dicer are scoped to target names.
/// Similar to [`Target`], but unlike [`Target`] it is not scoped to a cluster.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct TargetName {
    value: String,
}

impl TargetName {
    /// Constructs a new [`TargetName`].
    ///
    /// # Arguments
    ///
    /// * `value` - The target name string, which must be conformant with RFC 1123. See
    ///   [`Target::name`].
    ///
    /// Returns [`Status::invalid_argument`] if `value` is not a valid target name conformant with
    /// RFC 1123.
    pub fn new(value: impl Into<String>) -> Result<TargetName, Status> {
        let value: String = value.into();
        validate_target_name(&value).map_err(|err| Status::invalid_argument(err.to_string()))?;
        Ok(TargetName { value })
    }

    /// Creates the target name for the given target. Never fails, since [`Target::name`] is
    /// guaranteed to be a valid target name by [`Target`]'s own validation.
    pub fn for_target(target: &Target) -> TargetName {
        TargetName {
            value: target.name().to_string(),
        }
    }

    /// Returns whether the given target has this name.
    pub fn matches(&self, target: &Target) -> bool {
        self.value == target.name()
    }
}

impl Display for TargetName {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        write!(f, "{}", self.value)
    }
}
