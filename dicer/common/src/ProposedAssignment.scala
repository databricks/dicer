package com.databricks.dicer.common

import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer

import com.databricks.dicer.common.ProposedAssignment.{
  commitWithPredecessor,
  commitWithoutPredecessor
}
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.{MutableSliceMap, SliceMap, Squid}
import com.databricks.caching.util.UnixTimeVersion

/**
 * REQUIRES: when defined, `primaryRateLoadOpt` must contain a non-negative, finite number.
 *
 * An element of a [[ProposedAssignment]] assigning [[SliceWithResources]].
 *
 * When defined, [[primaryRateLoadOpt]] is the latest observed primary rate load for this Slice as
 * of the proposal. It may be None if no load has been measured yet, as with an initial assignment.
 * See [[SliceAssignment.primaryRateLoadOpt]] for additional background on this
 * measurement and its usage.
 */
case class ProposedSliceAssignment(
    private val sliceWithResources: SliceWithResources,
    primaryRateLoadOpt: Option[Double]) {
  val slice: Slice = sliceWithResources.slice
  val resources: Set[Squid] = sliceWithResources.resources

  if (primaryRateLoadOpt.isDefined) {
    LoadMeasurement.requireValidLoadMeasurement(primaryRateLoadOpt.get)
  }

  override def toString: String = {
    val primaryRateLoadDescription: String = primaryRateLoadOpt match {
      case Some(primaryRateLoad: Double) => s" load=$primaryRateLoad"
      case None => ""
    }

    val resourcesDescription: String =
      if (resources.size > 1) {
        // e.g. "{[pod0 creationTime UUID], [pod1 creationTime UUID]}".
        resources.mkString("{", ", ", "}")
      } else {
        // No brace for single resource to reduce verbosity.
        resources.head.toString
      }

    s"""$slice -> $resourcesDescription$primaryRateLoadDescription"""
  }
}

object ProposedSliceAssignment {

  @throws[IllegalArgumentException]("If `resources` is empty.")
  @throws[IllegalArgumentException](
    "If `primaryRateLoadOpt` is defined but doesn't satisfy " +
    "LoadMeasurement.requireValidLoadMeasurement()."
  )
  def apply(
      slice: Slice,
      resources: Set[Squid],
      primaryRateLoadOpt: Option[Double]): ProposedSliceAssignment = {
    // SliceWithResources will internally check whether `resources` is empty.
    ProposedSliceAssignment(SliceWithResources(slice, resources), primaryRateLoadOpt)
  }
}

/**
 * A proposed mapping from slices to a set of resources for each Slice. Structurally, this is like
 * an [[Assignment]] with only assignment and load information but no other metadata
 * (e.g. Generation, SubsliceAnnotations). The proposal may be accepted by calling [[commit]].
 *
 * @param predecessorOpt the assignment that is required to immediately precede the proposal when it
 *                       is committed. Used for creating SubsliceAnnotations for the new assignment.
 *                       The storage layer is responsible for enforcing this requirement when the
 *                       proposal is committed. None indicates that the proposal is for the initial
 *                       assignment, which has no predecessor.
 * @param sliceMap proposed mapping from Slices to resources.
 */
case class ProposedAssignment(
    predecessorOpt: Option[Assignment],
    sliceMap: SliceMap[ProposedSliceAssignment]) {

  def sliceAssignments: immutable.Vector[ProposedSliceAssignment] = sliceMap.entries

  /** The generation of the predecessor assignment, or None if there is no predecessor. */
  def predecessorGenerationOrEmpty: Generation = predecessorOpt match {
    case Some(predecessor: Assignment) => predecessor.generation
    case None => Generation.EMPTY
  }

  /**
   * Accepts the proposal. The returned [[Assignment]] has the given `generation`. Slice
   * generations for the committed assignment indicate how long each Slice has been unchanged in the
   * assignment, unless the assignment incarnation has changed from the predecessor, in which case
   * no continuity in the assignment can be assumed. Slice generations are used when serializing and
   * deserializing assignments (see [[Assignment.fromDiff()]] for more information), for
   * debugging, and to provide a conservative indication of how long a key has been assigned to the
   * same SQUID ("conservative" for reasons illustrated below). Consider the result of committing a
   * proposal at generation 50:
   *
   * {{{
   * predecessor @47: | pod1 @42 | pod1 @47        | pod2 @42 | pod3 @42 | pod4 @47            |
   * sliceMap:        | pod1     | {pod1,pod2}     | pod2                | pod3     | pod4     |
   * result @50:      | pod1 @42 | {pod1,pod2} @50 | pod2 @50            | pod3 @50 | pod4 @50 |
   * }}}
   *
   * Notice that in the result,
   * - The first Slice has kept its previous generation @42, as the first Slice is newly assigned
   *   with exactly its previous resource and without Slice boundaries change.
   * - The second Slice is assigned with a different set of resources, so it has a new generation.
   * - Although part of the third Slice and the entire last Slice have been stably assigned to the
   *   same resource, their Slice boundaries has changed so they are having new generations.
   *
   * We only carry forward Slice generations when the set of assigned resources and load
   * measurements are unchanged, or within some threshold in the case of load measurements.
   * Assignment continuity is captured with finer granularity in
   * [[SliceAssignment.subsliceAnnotationsByResource]].
   *
   * The [[SubsliceAnnotation]]s in [[SliceAssignment.subsliceAnnotationsByResource]] will also fill
   * in the `stateTransferOpt` field when appropriate, to indicate the previous resource that
   * can be used to fetch state for a Subslice. Note that as a current limitation,
   * `stateTransferOpt` will not be filled in if there are multiple source replicas to choose from.
   * If it is false, then the newly generated assignment will clear all `stateTransferOpt`, so that
   * state transfer basically gets disabled.
   *
   * TODO(<internal bug>): Create state transfer information even when there are multiple source replicas
   *                  to choose from.
   *
   * The `primaryRateLoadThreshold` parameter controls the threshold above which a new
   * [[SliceAssignment]] will be created for a Slice, even if the proposed assignment is
   * identical modulo [[SliceAssignment.primaryRateLoadOpt]]. (See the remarks on
   * [[SliceAssignment]] to understand why we do not require an exact match between the
   * previous and current load measurements. Note that when the previous Slice is in the "loose"
   * generation incarnation, any change in load is considered above threshold, as there is no
   * opportunity to optimize assignment sync by using assignment diffs in that case.) The threshold
   * is a change ratio relative to:
   *
   *    `abs(primaryRateLoad - previous.primaryRateLoad) / previous.primaryRateLoad`
   *
   * If `previous.primaryRateLoad` is zero, then a transition to non-zero is considered a change,
   * which is how we avoid a divide-by-zero error.
   *
   * Consider the following example of a previous Slice assignment, with primary rate load 10:
   *
   *    `SliceAssignment([a .. b), gen42, pod-0, ..., primaryRateLoad = 10.0)`
   *
   * Assuming a 10% (0.1) threshold, a [[ProposedSliceAssignment]] with the same Slice
   * and resource would be carried forward unchanged iff. its `primaryRateLoad` is in the range
   * [9.0, 11.0]:
   *
   *  * `ProposedSliceAssignment([a .. b), pod-0, primaryRateLoad = 9.0)` appears as
   *    `SliceAssignment([a .. b), gen42, pod-0, ..., primaryRateLoad = 10.0)` in the committed
   *    assignment. In this case, the new load (9.0) is within the threshold (10.0 +/- 10%), so
   *    the committed `SliceAssignment` has the same generation as the predecessor, and the primary
   *    rate load is not changed (10.0 is "close enough" based on the threshold).
   *  * `ProposedSliceAssignment([a .. b), pod-0, primaryRateLoad = 12.0)` appears as
   *    `SliceAssignment([a .. b), gen47, pod-0, ..., primaryRateLoad = 12.0)` in the committed
   *    assignment. In this case, the new load (12.0) is outside of the threshold (10.0 +/- 10%), so
   *    the committed `SliceAssignment` has a new generation, and the primary rate load is changed.
   *
   * @throws NotImplementedError TODO(<internal bug>) support Consistency mode
   */
  @throws[IllegalArgumentException](
    "If predecessor exists but new generation is not within same incarnation as predecessor."
  )
  @throws[IllegalArgumentException](
    "If predecessor exists but the new generation is no greater than predecessor generation."
  )
  @throws[NotImplementedError]("If `consistencyMode` is Strong.")
  def commit(
      isFrozen: Boolean,
      consistencyMode: AssignmentConsistencyMode,
      generation: Generation,
      primaryRateLoadThreshold: Double = 0.1): Assignment = {
    if (consistencyMode != AssignmentConsistencyMode.Affinity) {
      throw new NotImplementedError("TODO(<internal bug>): Only Affinity is currently supported")
    }
    predecessorOpt match {
      case Some(predecessor: Assignment)
          if predecessor.generation.incarnation == generation.incarnation =>
        commitWithPredecessor(
          sliceMap,
          predecessor,
          isFrozen,
          consistencyMode,
          generation,
          primaryRateLoadThreshold
        )
      case _ =>
        // Either there's no predecessor or it's from an earlier incarnation.
        commitWithoutPredecessor(
          sliceMap,
          isFrozen,
          consistencyMode,
          generation
        )
    }
  }

  override def toString: String = sliceAssignments.mkString("\n")
}

object ProposedAssignment {

  /**
   * Implements [[ProposedAssignment.commit()]] for the case where there is a predecessor
   * assignment in the same incarnation as `generation.incarnation`.
   */
  @throws[IllegalArgumentException](
    "If predecessor exists but new generation is not within same incarnation as predecessor."
  )
  @throws[IllegalArgumentException](
    "If predecessor exists but the new generation is no greater than predecessor generation."
  )
  private def commitWithPredecessor(
      proposedSliceMap: SliceMap[ProposedSliceAssignment],
      predecessor: Assignment,
      isFrozen: Boolean,
      consistencyMode: AssignmentConsistencyMode,
      generation: Generation,
      primaryRateLoadThreshold: Double
  ): Assignment = {
    require(
      generation > predecessor.generation,
      s"Proposal generation $generation must be greater than the predecessor " +
      s"${predecessor.generation}"
    )

    val committedSliceAssignments = ArrayBuffer[SliceAssignment]()
    val intersectedAssignments = SliceMap.intersectSlices(predecessor.sliceMap, proposedSliceMap)

    // Collect all candidate annotations with generation numbers that we can carry forward for each
    // assigned resource. The subslices might overlap; they will be handled appropriately right
    // before they are added to a `SliceAssignment`.
    val annotationCandidatesByResource = mutable.Map[Squid, ArrayBuffer[SubsliceAnnotation]]()
    // Counter for generating unique transition (currently just state transfer) IDs for this
    // `generation`. See `TransferP.id` specs for more information.
    var transferId: Int = 0

    for (intersectionEntry <- intersectedAssignments.entries) {
      val intersection: Slice = intersectionEntry.slice
      val previous: SliceAssignment = intersectionEntry.leftEntry
      val proposed: ProposedSliceAssignment = intersectionEntry.rightEntry

      // Calculate continuously assigned number for continuously assigned resources in the proposed
      // assignment, and create state transfer schedule for newly assigned ones.
      //
      // The following strategy is applied to generate state transfer schedules:
      //
      // 1. Choose a set of previously assigned resources as the state provider candidates. If there
      //    are resources assigned in both the previous and the proposed assignment, we prefer to
      //    choose them as the state provider candidates. Otherwise, we use (and can only use) the
      //    previous resources that are not assigned in the current assignment as the state
      //    providers, which are less preferred because they might be unhealthy or overloaded. (An
      //    extra reason is that state providers without slice ownership tend to drop application
      //    state quickly after serving the first acquiring request, but this is a higher level
      //    StateMigrator detail and is subject to change in the future.)
      // 2. After choosing a set of state provider candidates, we sort the provider candidates and
      //    the proposed resources by their internal ordering, and use this order to generate the
      //    transfer schedule by assigning state provider candidates to newly assigned resources
      //    (state acquirers) in a round-robin manner. This is a sub-optimal but simple and
      //    deterministic (ProposedAssignment.commit() should be deterministic) way to assign state
      //    providers to support the most basic state transfer functionality for asymmetric key
      //    replication.
      //
      // For example:
      //
      // Previous resources:  pod0  pod1  pod2  pod3
      // Proposed resources:              pod2  pod3  pod4  pod5  pod6  pod7  pod8
      // State providers:                             pod2  pod3  pod2  pod3  pod2
      // Explanation:         Only commonly assigned resources (pod2, pod3) serve as state
      //                      providers. They are matched with state acquirers in a round-robin
      //                      manner.
      //
      // Previous resources:  pod0  pod1  pod2
      // Proposed resources:                    pod3  pod4  pod5  pod6  pod7  pod8
      // State providers:                       pod0  pod1  pod2  pod0  pod1  pod2
      // Explanation:         When there are no commonly assigned resources, we resort to the rest
      //                      of previous resources as state providers.
      //
      // TODO(<internal bug>): Move the transfer schedule generation to Algorithm to generate a more
      //                  optimized transfer schedule using global load information and to avoid
      //                  putting too much intelligence in ProposedAssignment.

      val stateProviderCandidates: Set[Squid] = {
        val commonlyAssignedResources: Set[Squid] =
          previous.resources.intersect(proposed.resources)
        if (commonlyAssignedResources.nonEmpty) {
          commonlyAssignedResources
        } else {
          previous.resources
        }
      }
      val sortedProviderCandidates: Vector[Squid] = stateProviderCandidates.toVector.sorted
      var sortedProviderCandidatesIterator: Iterator[Squid] = sortedProviderCandidates.iterator
      val sortedProposedResources: Vector[Squid] = proposed.resources.toVector.sorted

      for (proposedResource: Squid <- sortedProposedResources) {
        if (previous.resources.contains(proposedResource)) {
          // Carry over all relevant annotations from `previous` for continuously assigned resource.
          // Filter out subslice annotations that do not intersect with the proposed Slice, and for
          // the ones that do, change its subslice to only cover the intersection.
          val previousAnnotationsWithinIntersection: Seq[SubsliceAnnotation] =
            previous.subsliceAnnotationsByResource
              .getOrElse(proposedResource, Vector.empty)
              .flatMap { ca: SubsliceAnnotation =>
                val intersectionOpt: Option[Slice] = ca.subslice.intersection(proposed.slice)
                // Replace `subslice` with only the intersecting portion. If state transfer is
                // disabled, clear `stateTransferOpt`, per the function specs.
                intersectionOpt.map { (intersection: Slice) =>
                  ca.copy(subslice = intersection)
                }
              }
          annotationCandidatesByResource.getOrElseUpdate(proposedResource, ArrayBuffer.empty) ++=
          previousAnnotationsWithinIntersection
          // Also add the intersection with `previous` as a candidate subslice annotation. Note we
          // already called `getOrElseUpdate` for `proposedResource` so we don't call it again here.
          annotationCandidatesByResource(proposedResource).append(
            SubsliceAnnotation(intersection, previous.generation.number, stateTransferOpt = None)
          )
        } else {
          // If the resource is newly assigned, add a subslice annotation to indicate where to fetch
          // the state from. Note that state transfer may not be possible if the previous resource
          // is dead, but that may happen at any time so for simplicity we do not attempt to
          // integrate health signals here.

          // Choose the next state provider candidate in `sortedProviderCandidates` as the state
          // provider for the current newly assigned resource.
          if (!sortedProviderCandidatesIterator.hasNext) {
            sortedProviderCandidatesIterator = sortedProviderCandidates.iterator
          }
          val stateProvider: Squid = sortedProviderCandidatesIterator.next()
          val stateTransfer = Transfer(id = transferId, fromResource = stateProvider)
          // Ensure the `transferId` is unique within `generation` for newly added Subslice
          // Annotations with Transfer information.
          transferId += 1
          annotationCandidatesByResource
            .getOrElseUpdate(proposedResource, ArrayBuffer.empty)
            .append(
              SubsliceAnnotation(
                intersection,
                generation.number,
                stateTransferOpt = Some(stateTransfer)
              )
            )
        }
      }
      if (intersection.highExclusive == proposed.slice.highExclusive) {
        // Since the proposed Slice might intersect multiple predecessors, we emit a committed
        // Slice only once when we reach the last intersecting entry. By waiting until the last
        // intersecting entry, we ensure that `annotationCandidatesByResource` contains all
        // intersecting subslice annotation candidates for the proposed Slice.
        if (generation.incarnation.isNonLoose &&
          previous.resources == proposed.resources &&
          previous.slice == proposed.slice &&
          isLoadWithinThreshold(
            previousLoadOpt = previous.primaryRateLoadOpt,
            proposedLoadOpt = proposed.primaryRateLoadOpt,
            threshold = primaryRateLoadThreshold,
            previousSliceGeneration = previous.generation
          )) {
          // If the previous entry had:
          //  - the same Slice boundary
          //  - the same assigned resources
          //  - the primary rate load is within the given threshold
          //  - the incarnation is non-loose
          // then we can carry forward the previous Slice, as with `pod0 @42` in the example above.
          // Note that when all these conditions are true we can also directly carry the annotations
          // forward from the previous slice assignment.
          committedSliceAssignments += previous
        } else {
          // Otherwise, the Slice generation is the assignment generation, because it's a new
          // Slice in the committed assignment, as with `pod3 @50` in the example above.
          val finalSubsliceAnnotationsByResource: Map[Squid, Vector[SubsliceAnnotation]] =
            annotationCandidatesByResource.mapValues(getEarliestSubsliceAnnotations).toMap.filter {
              case (_, annotations: Vector[SubsliceAnnotation]) => annotations.nonEmpty
            }
          committedSliceAssignments += SliceAssignment(
            SliceWithResources(proposed.slice, proposed.resources),
            generation = generation,
            subsliceAnnotationsByResource = finalSubsliceAnnotationsByResource,
            primaryRateLoadOpt = proposed.primaryRateLoadOpt
          )
        }
        annotationCandidatesByResource.clear()
      }
    }
    val committedSliceMap: SliceMap[SliceAssignment] =
      SliceMapHelper.ofSliceAssignments(committedSliceAssignments.toVector)
    Assignment(isFrozen, consistencyMode, generation, committedSliceMap)
  }

  /**
   * Implements [[ProposedAssignment.commit()]] for the case where there is no predecessor
   * assignment or where the predecessor assignment belongs to a different incarnation.
   */
  private def commitWithoutPredecessor(
      sliceMap: SliceMap[ProposedSliceAssignment],
      isFrozen: Boolean,
      consistencyMode: AssignmentConsistencyMode,
      generation: Generation): Assignment = {
    val committedSliceAssignments = Vector.newBuilder[SliceAssignment]
    // Since there is no predecessor, all Slices in the emitted assignment have the same
    // generation as the overall assignment.
    for (proposed: ProposedSliceAssignment <- sliceMap.entries) {
      committedSliceAssignments += SliceAssignment(
        SliceWithResources(proposed.slice, proposed.resources),
        generation = generation,
        subsliceAnnotationsByResource = Map.empty,
        primaryRateLoadOpt = proposed.primaryRateLoadOpt
      )
    }
    val committedSliceMap: SliceMap[SliceAssignment] =
      SliceMapHelper.ofSliceAssignments(committedSliceAssignments.result())
    Assignment(isFrozen, consistencyMode, generation, committedSliceMap)
  }

  /**
   * Given a sequence of potential candidates for subslice annotations, which may be overlapping,
   * generate the final vector of non-overlapping subslice annotations that is ordered and has the
   * minimum generation number for each Slice.
   *
   * For example given the following candidates:
   *   Sublice    Generation number
   * |---------|  10
   * |----|       5
   *    |------|  2
   *      |-|     1
   * The result would be:
   * |--|         5
   *    |-|       2
   *      |-|     1
   *        |--|  2
   */
  private def getEarliestSubsliceAnnotations(
      annotationCandidates: Iterable[SubsliceAnnotation]): Vector[SubsliceAnnotation] = {
    // We want to track all the fields of `SubsliceAnnotation` except `subslice`. The
    // MutableSliceMap is responsible for maintaining the subslice boundaries as its keys.
    case class NoSliceAnnotation(
        generationNumber: UnixTimeVersion,
        stateTransferOpt: Option[Transfer])
    // Map from subslices to NoSliceAnnotation. We go through each candidate and use
    // `MutableSliceMap.merge` to pick the lower generation number.
    val annotationMap = new MutableSliceMap[NoSliceAnnotation]
    val combiner: (NoSliceAnnotation, NoSliceAnnotation) => NoSliceAnnotation =
      (ca1: NoSliceAnnotation, ca2: NoSliceAnnotation) => {
        if (ca1.generationNumber < ca2.generationNumber) {
          ca1
        } else if (ca1.generationNumber == ca2.generationNumber && ca1.stateTransferOpt.isDefined) {
          // If generations are equal, prefer the one with state transfer information (if one
          // exists).
          ca1
        } else {
          ca2
        }
      }
    for (candidate: SubsliceAnnotation <- annotationCandidates) {
      annotationMap.merge(
        candidate.subslice,
        NoSliceAnnotation(candidate.continuousGenerationNumber, candidate.stateTransferOpt),
        combiner
      )
    }

    annotationMap.iterator.map {
      case (subslice: Slice, ca: NoSliceAnnotation) =>
        SubsliceAnnotation(subslice, ca.generationNumber, ca.stateTransferOpt)
    }.toVector
  }

  /** Returns whether the previous and proposed load are withing the configured threshold. */
  private def isLoadWithinThreshold(
      previousLoadOpt: Option[Double],
      proposedLoadOpt: Option[Double],
      threshold: Double,
      previousSliceGeneration: Generation): Boolean = {
    if (previousLoadOpt == proposedLoadOpt) {
      return true // no change!
    }
    if (previousLoadOpt.isEmpty || proposedLoadOpt.isEmpty) {
      return false // one or the other measurement is missing, so the change is significant.
    }
    val previousLoad: Double = previousLoadOpt.get
    if (previousLoad == 0.0) {
      return false // previous load is zero, so any change is not within threshold.
    }
    val proposedLoad: Double = proposedLoadOpt.get
    val changeRatio = Math.abs(proposedLoad - previousLoad) / previousLoad
    changeRatio <= threshold
  }
}
