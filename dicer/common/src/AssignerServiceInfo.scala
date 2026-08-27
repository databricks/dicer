package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP.AssignerServiceInfoP
import com.databricks.caching.util.Rfc1123

/**
 * Globally unique identifier for an assigner service instance (a group of Assigner processes
 * which shard a set of targets). A pair of (`name`, `instanceId`) uniquely identifies a single
 * assigner service instance.
 *
 * @param name the name of the assigner service instance (e.g., "dicer-assigner" or
 *             "dicer-assigner-untrusted"). Must meet the requirements for RFC 1123
 *             label names:
 *                - contain at most 63 characters
 *                - contain only lowercase alphanumeric characters or '-'
 *                - start with an alphanumeric character
 *                - end with an alphanumeric character
 * @param instanceId the instance identifier of the assigner service. Must satisfy the same RFC
 *                   1123 label name requirements as `name`.
 *
 * @throws IllegalArgumentException if `name` or `instanceId` is not a valid RFC 1123 label.
 */
case class AssignerServiceInfo @throws[IllegalArgumentException]()(
    name: String,
    instanceId: String) {
  validate()

  /** Returns this service info as a proto. */
  def toProto: AssignerServiceInfoP =
    AssignerServiceInfoP(name = Some(name), instanceId = Some(instanceId))

  override def toString: String = s"AssignerServiceInfo(name=$name, instanceId=$instanceId)"

  /** Validates service info fields.
   *
   * @throws IllegalArgumentException if `name` or `instanceId` is not a valid RFC 1123 label.
   */
  private def validate(): Unit = {
    require(Rfc1123.isValid(name), s"Name must match RFC 1123 regex ${Rfc1123.REGEX}: $name")
    require(
      Rfc1123.isValid(instanceId),
      s"Instance ID must match RFC 1123 regex ${Rfc1123.REGEX}: $instanceId"
    )
  }
}

object AssignerServiceInfo {

  /**
   * Returns an [[AssignerServiceInfo]] deserialized from its proto.
   */
  @throws[IllegalArgumentException]("if the proto is invalid due to invalid fields")
  def fromProto(proto: AssignerServiceInfoP): AssignerServiceInfo = {
    AssignerServiceInfo(name = proto.getName, instanceId = proto.getInstanceId)
  }
}
