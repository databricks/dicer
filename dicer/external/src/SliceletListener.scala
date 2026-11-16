package com.databricks.dicer.external

/**
 * A trait that an application server can implement to be informed of assignment changes.
 * [[onAssignmentUpdated]] is called whenever the assignment of Slice keys to servers changes.
 */
trait SliceletListener {

  /**
   * Informs the listener that the assignment has changed. The listener may consult
   * [[Slicelet.assignedSlices]] to determine which Slices are assigned to this server when this
   * call is made.
   *
   * Note that this method might be called spuriously where there have been no changes to the
   * Slicelet's assignment since the previous call. Calls are guaranteed to be serial:
   * [[onAssignmentUpdated()]] will not be called until after the previous call to
   * [[onAssignmentUpdated()]] returns.
   *
   * We recommend using Guava's `Range` data structures to interpret assignment changes (see
   * https://github.com/google/guava/wiki/RangesExplained for background). For example, a listener
   * that loads added and unloads removed Slice keys can, with appropriate synchronization,
   * implement:
   *
   * {{{
   * import com.google.common.collect.{ImmutableRangeSet, Range}
   * import scala.collection.JavaConverters.asJavaIterable
   *
   * ...
   *
   * /** Converts the given `slice` to a Guava `Range`. */
   * def sliceToRange(slice: Slice): Range[SliceKey] = slice.highExclusive match {
   *   case highExclusive: SliceKey => Range.closedOpen(slice.lowInclusive, highExclusive)
   *   case InfinitySliceKey => Range.atLeast(slice.lowInclusive)
   * }
   *
   * var assignedRanges: ImmutableRangeSet[SliceKey] = ImmutableRangeSet.of[SliceKey]()
   *
   * override def onAssignmentUpdated(): Unit = {
   *   // Convert assigned Slices to an ImmutableRangeSet supporting diffing.
   *   val assignedSlices: Seq[Slice] = slicelet.assignedSlices
   *   val currentAssignedRanges =
   *       ImmutableRangeSet.copyOf(asJavaIterable(assignedSlices.map(sliceToRange)))
   *
   *   // Swap current assigned ranges.
   *   val previousAssignedRanges = this.assignedRanges
   *   this.assignedRanges = currentAssignedRanges
   *
   *   // Unload ranges that are in the previous but not the current assignment.
   *   unload(previousAssignedRanges.difference(currentAssignedRanges))
   *
   *   // Load ranges that are in the current but not the previous assignment.
   *   load(currentAssignedRanges.difference(previousAssignedRanges))
   * }
   * }}}
   *
   * See dicer/external/test/Samples.scala for an end-to-end example of this pattern.
   *
   * Note that we recommend using the grpc_shaded version of the Guava library because it supports
   * the `difference` operation and has a non-beta (stable) version of the Range API.
   */
  def onAssignmentUpdated(): Unit
}
