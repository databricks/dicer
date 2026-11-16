use std::fmt::Debug;

use tonic::Status;
use caching_rust_util::rfc1123;
use dicer_common_proto_common_proto::dicer::diff_assignment_p::AssignerServiceInfoP;

/// Globally unique identifier for an assigner service instance (a group of Assigner processes
/// which shard a set of targets). A pair of (`name`, `instanceId`) uniquely identifies a single
/// assigner service instance.
#[derive(Clone, Eq, PartialEq)]
pub struct AssignerServiceInfo {
    /// The name of the assigner service instance (e.g., "dicer-assigner" or
    /// "dicer-assigner-untrusted"). Must meet the requirements for RFC 1123 label names:
    ///   * contain at most 63 characters
    ///   * contain only lowercase alphanumeric characters or '-'
    ///   * start with an alphanumeric character
    ///   * end with an alphanumeric character
    name: String,

    /// The instance identifier of the assigner service. Must satisfy the same RFC
    /// 1123 label name requirements as `name`.
    instance_id: String,
}

impl AssignerServiceInfo {
    /// Creates a new [`AssignerServiceInfo`].
    ///
    /// Returns an `INVALID_ARGUMENT` status when `name` or `instance_id` is not a valid RFC 1123 label.
    pub fn new(name: String, instance_id: String) -> Result<AssignerServiceInfo, Status> {
        let info: AssignerServiceInfo = AssignerServiceInfo { name, instance_id };
        info.validate()?;
        Ok(info)
    }

    /// Returns an [`AssignerServiceInfo`] deserialized from its proto.
    ///
    /// Returns an `INVALID_ARGUMENT` status if the proto is invalid due to invalid fields.
    pub fn from_proto(proto: &AssignerServiceInfoP) -> Result<AssignerServiceInfo, Status> {
        let name: String = proto.name.clone().unwrap_or_default();
        let instance_id: String = proto.instance_id.clone().unwrap_or_default();
        AssignerServiceInfo::new(name, instance_id)
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn instance_id(&self) -> &str {
        &self.instance_id
    }

    /// Returns this service info as a proto.
    pub fn to_proto(&self) -> AssignerServiceInfoP {
        AssignerServiceInfoP {
            name: Some(self.name.clone()),
            instance_id: Some(self.instance_id.clone()),
        }
    }

    /// Validates service info fields.
    ///
    /// Returns an `INVALID_ARGUMENT` status if `name` or `instance_id` is not a valid RFC 1123 label.
    fn validate(&self) -> Result<(), Status> {
        if !rfc1123::is_valid(&self.name) {
            return Err(Status::invalid_argument(format!(
                "Name must match RFC 1123 regex {}: {}",
                *rfc1123::REGEX,
                self.name
            )));
        }
        if !rfc1123::is_valid(&self.instance_id) {
            return Err(Status::invalid_argument(format!(
                "Instance ID must match RFC 1123 regex {}: {}",
                *rfc1123::REGEX,
                self.instance_id
            )));
        }
        Ok(())
    }
}

impl Debug for AssignerServiceInfo {
    /// Returns a string representation of this service info.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "AssignerServiceInfo(name={}, instanceId={})", self.name, self.instance_id)
    }
}
