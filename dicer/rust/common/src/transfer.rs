use std::sync::Arc;

use tonic::Status;
use dicer_common_proto_common_proto::dicer::diff_assignment_p::TransferP;
use dicer_rust_friend::Squid;

use crate::assignment::{get_resource_by_index, ResourceProtoBuilder};

/// Represents a request to transfer/copy state for state transfer from `from_resource` to the
/// containing `InternalSliceAssignment`'s resource.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Transfer {
    /// Uniquely identifies the transfer within the transfer generation
    /// `SubsliceAnnotation::continuous_generation_number`.
    pub id: i32,

    /// The resource that state is being transferred from.
    pub from_resource: Arc<Squid>,
}

impl Transfer {
    /// Returns the proto representation of the transfer.
    pub fn to_proto<'a>(&'a self, resource_builder: &mut ResourceProtoBuilder<'a>) -> TransferP {
        TransferP {
            id: Some(self.id),
            from_resource_id: Some(resource_builder.get_index(self.from_resource.as_ref())),
        }
    }

    /// Parses the given transfer proto into its native representation.
    ///
    /// # Arguments
    ///
    /// * `resources` - The list of resources for the overall assignment that the transfer's
    ///                 `from_resource_id` field is an index into.
    pub fn from_proto(proto: &TransferP, resources: &[Arc<Squid>]) -> Result<Self, Status> {
        let from_resource_id: i32 = proto.from_resource_id.ok_or_else(|| {
            Status::invalid_argument(format!("from_resource_id not present in {proto:?}"))
        })?;

        Ok(Transfer {
            id: proto.id.unwrap_or_default(),
            from_resource: get_resource_by_index(resources, from_resource_id)?,
        })
    }
}
