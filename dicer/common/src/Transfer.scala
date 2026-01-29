package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP.TransferP
import com.databricks.dicer.common.Assignment.ResourceMap
import com.databricks.dicer.friend.Squid

/**
 * Represents a request to transfer/copy state for state transfer. The transfer should be from
 * `fromResource` to the containing `SliceAssignment`'s resource. `id` uniquely identifies the
 * transfer within the transfer generation `SubsliceAnnotation.continuousGenerationNumber`.
 */
case class Transfer(id: Int, fromResource: Squid) {

  /** Converts the [[Transfer]] instance to its corresponding proto message. */
  def toProto(resourceBuilder: Assignment.ResourceProtoBuilder): TransferP = {
    val fromResourceId: Int = resourceBuilder.getIndex(fromResource)
    TransferP(Some(id), Some(fromResourceId))
  }
}

object Transfer {

  /**
   * Converts the [[TransferP]] proto message to a corresponding [[Transfer]] instance.
   *
   * @throws IllegalArgumentException if the `proto` message is not valid.
   * @throws IllegalArgumentException if `proto.fromResourceId` is not present in `resourceMap`.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: TransferP, resourceMap: ResourceMap): Transfer = {
    require(
      proto.fromResourceId.isDefined,
      s"from_resource_id not present in $proto"
    )
    val fromResource: Squid =
      resourceMap.resourceFromProtoIndex(proto.getFromResourceId)
    Transfer(proto.getId, fromResource)
  }
}
