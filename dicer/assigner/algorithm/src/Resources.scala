package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable

import com.databricks.dicer.external.ResourceAddress
import com.databricks.dicer.friend.Squid

/**
 * The resources for which an assignment is being generated.
 *
 * @param availableResources Resource incarnations that are currently available to serve requests.
 *                           Slices can be assigned to these resources.
 */
class Resources private (val availableResources: Set[Squid])
object Resources {

  /** A [[Resources]] instance with no available resources. */
  val empty: Resources = new Resources(availableResources = Set.empty)

  /**
   * Creates a [[Resources]] object given resources that have recently sent heartbeats. If
   * multiple resources have the same address, only the resource incarnation with the latest
   * creation time is available to the assignment. This addresses the case where, after a restart,
   * recent heartbeats have been received by both the latest incarnation of the Slicelet and its
   * predecessor.
   */
  def create(healthyResources: TraversableOnce[Squid]): Resources = {
    val map = mutable.HashMap[ResourceAddress, Squid]()
    for (squid: Squid <- healthyResources) {
      // If there's no existing SQUID for the current SQUID's resource address, or existing SQUID
      // has an earlier creation time, add the current SQUID to the result.
      val existingSquid: Option[Squid] = map.get(squid.resourceAddress)
      if (!existingSquid.exists { existingSquid: Squid =>
          // existingSquid.creationTime >= squid.creationTime
          existingSquid.creationTime.compareTo(squid.creationTime) >= 0
        }) {
        map.put(squid.resourceAddress, squid)
      }
    }
    new Resources(availableResources = map.values.toSet)
  }
}
