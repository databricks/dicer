package com.databricks.dicer.common

import scala.collection.immutable

import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.SliceMap

/**
 * The [[SliceMap]]'s helper object with convenient methods to create some certain kinds of commonly
 * used SliceMaps, but ones that we don't want to expose in dicer/friend.
 */
object SliceMapHelper {

  /**
   * REQUIRES: See REQUIRES clause on the [[SliceMap]] constructor.
   *
   * Creates a [[SliceMap]] with [[SliceWithResources]] entries.
   */
  def ofSlicesWithResources(
      entries: immutable.Vector[SliceWithResources]): SliceMap[SliceWithResources] = {
    new SliceMap(entries, SLICE_WITH_RESOURCES_ACCESSOR)
  }

  val SLICE_WITH_RESOURCES_ACCESSOR: SliceWithResources => Slice = {
    sliceWithResources: SliceWithResources =>
      sliceWithResources.slice
  }

  /**
   * REQUIRES: See REQUIRES clause on the [[SliceMap]] constructor.
   *
   * Creates a [[SliceMap]] with [[SliceAssignment]] entries.
   */
  def ofSliceAssignments(entries: immutable.Vector[SliceAssignment]): SliceMap[SliceAssignment] = {
    new SliceMap(entries, SLICE_ASSIGNMENT_ACCESSOR)
  }

  val SLICE_ASSIGNMENT_ACCESSOR: SliceAssignment => Slice = {
    sliceAssignmentWithReplica: SliceAssignment =>
      sliceAssignmentWithReplica.slice
  }
}
