package com.databricks.dicer.common

import java.time.Instant

import com.databricks.api.proto.dicer.common.DiffAssignmentP.{SubsliceAnnotationP, TransferP}
import com.databricks.dicer.common
import com.databricks.dicer.common.Assignment.ResourceMap
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.Squid
import com.databricks.caching.util.UnixTimeVersion

/**
 *  Annotations on a subslice within a Slice assignment. See
 *  [[SliceAssignment.subsliceAnnotationsByResource]] for more details.
 */
case class SubsliceAnnotation(
    subslice: Slice,
    continuousGenerationNumber: UnixTimeVersion,
    stateTransferOpt: Option[Transfer]) {

  /**
   * Returns the value of the generation number as an [[Instant]], assuming that the generation
   * number tracks the number of millis since the Unix epoch.
   */
  def generationToTime: Instant = continuousGenerationNumber.toTime

  /**
   * Given a resourceBuilder and the incarnation of the resource owning this SubsliceAnnotation
   * (the `ownerResource`), converts this SubsliceAnnotation instance to its corresponding
   * proto message.
   */
  def toProto(
      ownerResource: Squid,
      resourceBuilder: common.Assignment.ResourceProtoBuilder): SubsliceAnnotationP = {
    val stateTransferProtoOpt: Option[TransferP] =
      stateTransferOpt.map { transfer: Transfer =>
        transfer.toProto(resourceBuilder)
      }
    val resourceIndex = resourceBuilder.getIndex(ownerResource)
    SubsliceAnnotationP(
      resourceId = Some(resourceIndex),
      subslice = Some(subslice.toProto),
      generationNumber = Some(continuousGenerationNumber.value),
      stateTransfer = stateTransferProtoOpt
    )
  }

  override def toString: String = {
    val result = s"""$subslice:$continuousGenerationNumber"""
    if (stateTransferOpt.isDefined) {
      s"$result, state provider: ${stateTransferOpt.get.fromResource}"
    } else {
      result
    }
  }
}

object SubsliceAnnotation {

  /**
   * Converts the [[SubsliceAnnotationP]] proto message to a [[SubsliceAnnotation]] instance,
   * together with the incarnation of the resource that owns this annotation.
   *
   * @throws IllegalArgumentException if `subsliceAnnotationP` is not valid.
   * @throws IllegalArgumentException if `subsliceAnnotationP` contains any resourceId not presented
   *                                  in `resourceMap`.
   */
  @throws[IllegalArgumentException]
  def fromProto(
      subsliceAnnotationP: SubsliceAnnotationP,
      resourceMap: ResourceMap): (SubsliceAnnotation, Squid) = {
    val subsliceResourceId: Int = subsliceAnnotationP.getResourceId
    val subsliceResource: Squid =
      resourceMap.resourceFromProtoIndex(subsliceResourceId)
    val stateTransferOpt: Option[Transfer] =
      subsliceAnnotationP.stateTransfer.map { transferP: TransferP =>
        Transfer.fromProto(transferP, resourceMap)
      }
    val subsliceAnnotation = SubsliceAnnotation(
      subslice = SliceHelper.fromProto(subsliceAnnotationP.getSubslice),
      continuousGenerationNumber = subsliceAnnotationP.getGenerationNumber,
      stateTransferOpt
    )
    (subsliceAnnotation, subsliceResource)
  }
}
