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
class Resources private (val availableResources: Set[Squid]) {

  /**
   * Human readable string representation of the resources.
   *
   * Provides a concise summary of the number of available resources
   * and information on at most the five newest resources.
   */
  override def toString: String = {
    val builder = new StringBuilder
    builder.append(s"Resources(count=${availableResources.size}, squids=[")

    // Default iteration order is arbitrary and unstable across membership changes, so we sort by
    // creation time (tie-breaking on resource itself) for deterministic and informative logs.
    // Resources could contain tens to hundreds of squids, so we only print the five newest squids.
    val squidsToPrint: Seq[Squid] =
      availableResources.toSeq
        .sortBy { resource: Squid =>
          (-resource.creationTimeMillis, resource)
        }
        .take(Resources.MAX_SQUIDS_TO_PRINT)
    builder.append(squidsToPrint.mkString(", "))

    if (availableResources.size > Resources.MAX_SQUIDS_TO_PRINT) {
      builder.append(", ...")
    }
    builder.append("])")

    builder.toString
  }
}
object Resources {

  /** A [[Resources]] instance with no available resources. */
  val empty: Resources = new Resources(availableResources = Set.empty)

  /**
   * The max number of squids that will be printed. Any remaining squids are
   * represented by an ellipsis.
   */
  private val MAX_SQUIDS_TO_PRINT = 5

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
