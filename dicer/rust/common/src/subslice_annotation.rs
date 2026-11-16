use std::sync::Arc;

use tonic::Status;
use dicer_common_proto_common_proto::dicer::diff_assignment_p::{
    SubsliceAnnotationP,
    TransferP,
};
use dicer_rust_external_types::{HighSliceKeyRef, SliceKey};
use dicer_rust_friend::Squid;

use crate::assignment::get_resource_by_index;
use crate::{slice_to_proto, ResourceProtoBuilder, Transfer, UnixTimeVersion};

/// An annotation on a subslice within an assignment.
///
/// See `InternalSliceAssignment::subslice_annotations_by_resource` for more details.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SubsliceAnnotation {
    pub continuous_generation_number: UnixTimeVersion,
    pub state_transfer_opt: Option<Transfer>,
}

impl SubsliceAnnotation {
    /// Returns the proto representation of the subslice annotation.
    ///
    /// # Arguments
    ///
    /// * `owner_resource` - The resource that this subslice annotation is for.
    pub fn to_proto<'a>(
        &'a self,
        low_inclusive: &SliceKey,
        high_exclusive: HighSliceKeyRef,
        owner_resource: &'a Squid,
        resource_builder: &mut ResourceProtoBuilder<'a>,
    ) -> SubsliceAnnotationP {
        SubsliceAnnotationP {
            resource_id: Some(resource_builder.get_index(owner_resource)),
            subslice: Some(slice_to_proto(low_inclusive, high_exclusive)),
            generation_number: Some(self.continuous_generation_number.to_unix_millis()),
            state_transfer: self
                .state_transfer_opt
                .as_ref()
                .map(|transfer: &Transfer| transfer.to_proto(resource_builder)),
        }
    }

    /// Parses a `SubsliceAnnotation`, together with the resource that it applies to, from its proto
    /// representation.
    ///
    /// # Arguments
    ///
    /// * `resources` - The list of resources in the overall assignment that the proto's resource
    ///                 ids are indexes into.
    pub fn from_proto(
        proto: &SubsliceAnnotationP,
        resources: &[Arc<Squid>],
    ) -> Result<(SubsliceAnnotation, Arc<Squid>), Status> {
        let resource: Arc<Squid> =
            get_resource_by_index(resources, proto.resource_id.unwrap_or_default())?;
        let annotation = SubsliceAnnotation {
            continuous_generation_number: UnixTimeVersion::from_unix_millis(
                proto.generation_number.unwrap_or_default(),
            )?,
            state_transfer_opt: proto
                .state_transfer
                .as_ref()
                .map(|transfer: &TransferP| Transfer::from_proto(transfer, resources))
                .transpose()?,
        };
        Ok((annotation, resource))
    }
}
