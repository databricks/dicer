package com.databricks.dicer.common

import com.databricks.dicer.common.SubsliceAnnotationsMap.SliceWithAnnotations
import com.databricks.dicer.external.{HighSliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.caching.util.UnixTimeVersion

/**
 * A map from owned Slices (which don't have to cover the full key range) to their annotations:
 *  - The first (known) generation number at which the key was assigned to a particular `resource`.
 *  - The state provider that can be used to fetch state for a Slice, if available.
 */
case class SubsliceAnnotationsMap private (sliceMap: SliceMap[GapEntry[SliceWithAnnotations]])
    extends AnyVal {

  /**
   * Returns the first (known) generation number the key was assigned to the resource, or None if
   * the key is not assigned to the resource.
   */
  def continuouslyAssignedGeneration(key: SliceKey): Option[UnixTimeVersion] = {
    sliceMap.lookUp(key) match {
      case GapEntry.Some(sliceWithAnnotations: SliceWithAnnotations) =>
        Some(sliceWithAnnotations.continuousGenerationNumber)
      case GapEntry.Gap(_) =>
        None
    }
  }

  /**
   * Ordered, disjoint entries of [[SliceWithAnnotations]]. A `GapEntry.Gap` means the slice is not
   * assigned to the current resource, while `GapEntry.Some` with `stateProviderOpt = None` means
   * the slice is assigned, but we do not have state provider information for it.
   */
  def entries: Seq[GapEntry[SliceWithAnnotations]] = sliceMap.entries
}

object SubsliceAnnotationsMap {

  /**
   * A container associating a slice with the continuously assigned generation number, as well as an
   * optional state provider.
   */
  case class SliceWithAnnotations(
      slice: Slice,
      continuousGenerationNumber: UnixTimeVersion,
      stateProviderOpt: Option[Squid])

  /** Accessor for [[SliceWithAnnotations]] to use it with a [[SliceMap]]. */
  private val SLICE_WITH_ANNOTATIONS_ACCESSOR: SliceWithAnnotations => Slice = {
    sliceWithAnnotations: SliceWithAnnotations =>
      sliceWithAnnotations.slice
  }

  /** An empty map in which no Slices are assigned to the resource. */
  val EMPTY: SubsliceAnnotationsMap =
    new SubsliceAnnotationsMap(
      SliceMap.createFromOrderedDisjointEntries(Seq(), SLICE_WITH_ANNOTATIONS_ACCESSOR)
    )

  /**
   * Creates a [[SubsliceAnnotationsMap]] for the given `assignment` and `resource`.
   *
   * For assignments using the `Strong` [[AssignmentConsistencyMode]], the key is guaranteed to have
   * been continuously assigned to the specific Slicelet incarnation given by `resource`, and no
   * "divergent" assignments are possible per the discussion at [[Incarnation.isLoose]].
   *
   * For assignments using the `Affinity` [[AssignmentConsistencyMode]], the key is guaranteed to
   * have been continuously assigned to some Slicelet with `resource.resourceAddress`, an address
   * which may be recycled across Slicelet incarnations, and "divergent" assignments are possible
   * per the discussion at [[Incarnation.isLoose]].
   */
  def apply(assignment: Assignment, resource: Squid): SubsliceAnnotationsMap = {
    val builder = Vector.newBuilder[SliceWithAnnotations]
    // Go through all Slices in the assignment, and add any subslices that are compatible with
    // `resource` to `builder`.
    for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
      if (assignment.isResourceAssigned(sliceAssignment.resources, resource)) {
        // Go through the subslice annotations within this Slice, and add `SliceWithAnnotations` for
        // any subslice or gap to `builder`. Example showing the generation numbers within each
        // Slice:
        // |---------54-----------| slice
        //        |-9-|     |42|    continuous assignments
        // Resulting generation numbers of entries added to `builder`:
        // |--54--|-9-|-54--|42|54| builder entries

        // Track low key for the next `SliceWithAnnotations` that we should add.
        var lowKey: HighSliceKey = sliceAssignment.slice.lowInclusive
        for (annotation: SubsliceAnnotation <- sliceAssignment.subsliceAnnotationsByResource
            .getOrElse(resource, Seq.empty)) {
          if (lowKey < annotation.subslice.lowInclusive) {
            // Fill in the gap with `sliceAssignment`'s generation. There is something greater than
            // `lowKey` so `asFinite` is safe.
            builder += SliceWithAnnotations(
              Slice(lowKey.asFinite, annotation.subslice.lowInclusive),
              sliceAssignment.generation.number,
              None
            )
          }
          // Add `SliceWithAnnotations` corresponding to this annotation.
          val stateProviderOpt: Option[Squid] =
            annotation.stateTransferOpt.map((transition: Transfer) => transition.fromResource)
          builder += SliceWithAnnotations(
            annotation.subslice,
            annotation.continuousGenerationNumber,
            stateProviderOpt
          )
          lowKey = annotation.subslice.highExclusive
        }

        if (lowKey < sliceAssignment.slice.highExclusive) {
          // Fill in the gap between the last annotation and the end of the Slice, with
          // `sliceAssignment`'s generation. There is something greater than `lowKey` so `asFinite`
          // is safe.
          builder += SliceWithAnnotations(
            Slice(lowKey.asFinite, sliceAssignment.slice.highExclusive),
            sliceAssignment.generation.number,
            None
          )
        }
      }
    }

    val map = SliceMap.createFromOrderedDisjointEntries(
      builder.result(),
      SLICE_WITH_ANNOTATIONS_ACCESSOR
    )
    SubsliceAnnotationsMap(map)
  }
}
