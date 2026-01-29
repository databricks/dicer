package com.databricks.dicer.common

import scala.collection.mutable

import com.databricks.api.proto.dicer.common.DiffAssignmentP.{SliceAssignmentP, SubsliceAnnotationP}
import com.databricks.dicer.common.Assignment.ResourceMap
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.external.{HighSliceKey, Slice}
import com.databricks.dicer.friend.Squid

/**
 * REQUIRES: `generation` is not empty, belongs to the same incarnation as the generation of the
 *           assignment containing this Slice, and has a generation number that is less than or
 *           equal to that of the assignment generation.
 * REQUIRES: When defined, `primaryRateLoadOpt` must contain a non-negative, finite number.
 * REQUIRES: See invariants for `subsliceAnnotationsByResource` in the main doc of
 *           [[SliceAssignment]].
 *
 * Represents the assignment of a Slice to a set of resources.
 *
 * A simple example of a Slice assignment is:
 *
 * [Balin .. Nori): 2##45 => {Pod1, Pod2}
 *
 * which says that keys from Balin to Nori are assigned to Pod1 and Pod2 and this Slice was last
 * created or modified when the assignment generation number was 2##45. A detailed description of
 * the generation value or values stored in the [[SliceAssignment]] follows.
 *
 * [[generation]]:
 *
 * A [[SliceAssignment]] is guaranteed to have been unchanged between [[generation]] and
 * the generation of the assignment containing this Slice. For example, if a [[SliceAssignment]] has
 * [[generation]] 2##42 and it is in an assignment with generation 2##47, it means that all aspects
 * of the Slice assignment -- Slice boundaries, set of assigned resources, and load measurements --
 * have been unchanged between assignment generations 2##42 and 2##47, inclusive.
 *
 * For assignments with a generation in [[Incarnation.isLoose]], [[generation]] is always the same
 * as the assignment generation, since Dicer is not guaranteed to be aware of all assignments that
 * have been distributed for loose incarnations. Note that we still track best-guess subslice
 * annotation information in [[subsliceAnnotationsByResource]], but the [[generation]] value is
 * strict. This strictness is important because the [[generation]] value is used to determine which
 * Slices need to be included in [[DiffAssignment]], used to synchronize assignments
 * between Clerks, Slicelets and Assigners that may have observed divergent assignment histories.
 *
 * WARNING: [[generation]] was subtly redefined in September 2023. Assignments written before that
 * time may include Slice generations that do not satisfy the definition given above. In particular,
 * they may reflect the best-guess continuous assignment interpretation rather than the strict
 * interpretation of "unchanged". When producing diffs for the assignment sync protocol,
 * [[Assignment.toDiff]] always emits full assignments for loose-incarnation
 * assignments, so this was not a problem in practice until the definition of
 * [[Incarnation.isLoose]] was updated to support additional loose incarnations (see
 * <internal link>).
 *
 * [[subsliceAnnotationsByResource]]:
 *
 * The map from each assigned `resource`s to a Vector of [[SubsliceAnnotation]]s containing specific
 * meta-information about ordered and disjoint subslices of `slice` that have been assigned to this
 * resource. It is used for one or more of:
 *  - Finer-grained handling of `isAssignedContinuously`, e.g. in cases of splits/merges but where
 *    part of the slice is still assigned to the same resource as before.
 *  - Indicating that state for the subslice can be fetched from another resource, represented as
 *    a [[Transfer]].
 *
 * For example, if `subsliceAnnotationsByResource("Pod0")` contains
 * `SubsliceAnnotation("Fili" -- "Nori", 42, Some(Transfer(11), "Pod1"))`, it means the subslice
 * ["Fili", "Nori") has been continuously assigned to "Pod0" since generation 42, and "Pod0"
 * should acquire state for ["Fili", "Nori") from "Pod1" if state transfer is enabled.
 *
 * Invariants:
 *
 *  - Each `continuousGenerationNumber` must be less than or equal to `generation.number`.
 *     - For state transfer, when a slice is reassigned, we will have a ContinuousAssignment with
 *       the exact same generation number and `stateTransferOpt` populated with a state provider.
 *     - For fine-grained tracking of `isAssignedContinuously`, it is only useful to include an
 *       annotation when `continuousGenerationNumber` is strictly less than `generation.number`.
 *    The incarnation number is the same by construction, since if incarnation changes we cannot
 *    make any guarantees about continuous assignment.
 *  - Each `subslice` must be contained in `slice`. But the combined `subslice`s do not need to
 *    cover the entire `slice`.
 *  - The [[SubsliceAnnotation]]s for an assigned resource, if presented in
 *    [[subsliceAnnotationsByResource]], must be non-empty, ordered, and disjoint.
 *  - If a transfer is present, its `fromResource` must be different from the resource that owns
 *    this annotation in [[subsliceAnnotationsByResource]].
 *
 * Some caveats:
 *
 *  - When the assignment has a generation in [[Incarnation.isLoose]], the continuous assignment
 *    generations are only guesses. It's possible that the Assigner generating the "loose"
 *    assignment was unaware of an intervening reassignment. As such, for "loose assignments",
 *    [[SubsliceAnnotation.continuousGenerationNumber]] only suggests whether a given key was
 *    _probably_ continuously assigned. For example, in the following (non-loose) assignment:
 *
 *      4##10
 *      ["" .. Fili):4##8 => {Resource0}
 *      ...
 *
 *    it is guaranteed that the slice ["" .. Fili) was continuously assigned to {Resource0}
 *    between 4##8 and 4##10. However, with a loose assignment:
 *
 *      1##10
 *      ["" .. Fili):1##8 => {Resource0}
 *      ...
 *
 *    it's possible some other assignment at 1##9 was emitted with ["" .. Fili): 1##9 =>
 *    {Resource1}, and the Assigner that produced the 1##10 assignment may simply have been unaware
 *    of the intervening assignment.
 *
 *  - If Slice boundaries change (e.g., due to a split or merge), the new slices will be assigned
 *    new [[generation]] values, but `subsliceAnnotations` can be used to track the window of
 *    continuous assignment for all sub-Slices.
 *
 * [[primaryRateLoadOpt]]:
 *
 * The Slice assignment optionally defines [[primaryRateLoadOpt]], which is the primary rate load
 * measurement for this Slice as of `generation`. (See [[SliceletData.SliceLoad.primaryRateLoad]]
 * for more details on this measurement.) If this measurement changes significantly between
 * assignments, [[ProposedAssignment.commit()]] may choose to update this value and
 * advance [[generation]], even if nothing else has changed in the Slice assignment. In this case,
 * [[SubsliceAnnotation]]s still captures assignment continuity, but the generation bump for the
 * Slice assignment as a whole ensures that the roughly accurate load measurements are disseminated
 * and/or persisted. This measurement is used by the Assigner when either there are no recent load
 * measurements for a Slice (e.g., the assigned Slicelet is unhealthy), or if there isn't sufficient
 * history (i.e., the latest Slicelet only recently started receiving traffic for the corresponding
 * Slice).
 *
 * Invariants: See [[LoadMeasurement.requireValidLoadMeasurement]].
 */
case class SliceAssignment(
    sliceWithResources: SliceWithResources,
    generation: Generation,
    subsliceAnnotationsByResource: Map[Squid, Vector[SubsliceAnnotation]],
    primaryRateLoadOpt: Option[Double]
) {
  validate()

  /** The Slice being assigned. */
  def slice: Slice = sliceWithResources.slice

  /** A Set of resources where the [[slice]] is being assigned to. */
  def resources: Set[Squid] = sliceWithResources.resources

  /**
   * A Vector of assigned resources where the [[slice]] is being assigned to. Useful when the caller
   * needs to efficiently pick one assigned resource.
   */
  val indexedResources: Vector[Squid] = resources.toVector

  /**
   * REQUIRES: `generation` must be less than or equal to `assignmentGeneration` and must be in the
   *           same incarnation.
   *
   * Checks requirements for this Slice assignment in the context of an assignment with the given
   * generation.
   */
  def checkAssignmentGeneration(assignmentGeneration: Generation): Unit = {
    require(
      generation.incarnation == assignmentGeneration.incarnation,
      s"Slice generation $generation must be in the same incarnation as " +
      s"the assignment generation $assignmentGeneration."
    )
    require(
      generation <= assignmentGeneration,
      s"Slice generation $generation must be less than or equal to " +
      s"the assignment generation $assignmentGeneration."
    )
  }

  /**
   * Returns the proto corresponding to this object. `resourceBuilder` builds a mapping from
   * resources to indices as slice assignments are being serialized.
   */
  def toProto(resourceBuilder: Assignment.ResourceProtoBuilder): SliceAssignmentP = {
    // Convert the resources in this slice assignment to indices in the proto based on the map
    // that the encapsulating Assignment is using.
    val resourceIndices: Seq[Int] = resources.map(resourceBuilder.getIndex).toSeq
    val subsliceAnnotationProtos = Seq.newBuilder[SubsliceAnnotationP]
    // Iterate over resources in `subsliceAnnotationsByResource` in order when constructing proto
    // messages, so there will be only one possible proto representation of each
    // SliceAssignment. This is not a required invariant of the proto, but can be useful in
    // debugging.
    for (resource: Squid <- subsliceAnnotationsByResource.keys.toVector.sorted) {
      val subsliceAnnotations: Vector[SubsliceAnnotation] =
        subsliceAnnotationsByResource(resource)
      for (subsliceAnnotation: SubsliceAnnotation <- subsliceAnnotations) {
        subsliceAnnotationProtos += subsliceAnnotation.toProto(resource, resourceBuilder)
      }
    }
    new SliceAssignmentP(
      Some(slice.toProto),
      Some(generation.toProto),
      resourceIndices,
      subsliceAnnotationProtos.result(),
      primaryRateLoadOpt
    )
  }

  override def toString: String = {
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendSliceAssignmentToStringBuilder(this, builder)
    builder.toString()
  }

  /** Validate the invariants of [[SliceAssignment]]. */
  private def validate(): Unit = {
    require(generation != Generation.EMPTY, "Slice assignment must have non-empty generation.")
    // For each resource in `subsliceAnnotationsByResource`, verify the resource itself is valid,
    // and then verify the SubsliceAnnotations for this resource are non-empty, ordered, disjoint,
    // and contain valid generation number and subslice.
    for (resourceWithAnnotations <- subsliceAnnotationsByResource) {
      val (resource, subsliceAnnotations): (Squid, Vector[SubsliceAnnotation]) =
        resourceWithAnnotations
      require(
        resources.contains(resource),
        "Subslice annotations must be to one of the resources that own the slice: " +
        s"expected $resources, got $resource"
      )
      require(
        subsliceAnnotations.nonEmpty,
        s"Subslice annotations for resource $resource should not be empty"
      )
      var prevHighExclusive: HighSliceKey = slice.lowInclusive
      for (annotation: SubsliceAnnotation <- subsliceAnnotations) {
        require(
          annotation.continuousGenerationNumber <= generation.number,
          s"Subslice annotations cannot have a newer generation: " +
          s"${annotation.continuousGenerationNumber} is not <= ${generation.number}"
        )
        require(
          annotation.subslice.lowInclusive >= prevHighExclusive,
          s"Subslice annotations must be contained in slice and must be ordered and disjoint: " +
          s"${annotation.subslice.lowInclusive} is not >= $prevHighExclusive"
        )
        for (stateTransfer: Transfer <- annotation.stateTransferOpt) {
          require(
            stateTransfer.fromResource != resource,
            s"State transfer cannot be from and to the same resource $resource"
          )
        }
        prevHighExclusive = annotation.subslice.highExclusive
      }
      require(
        prevHighExclusive <= slice.highExclusive,
        s"Subslice annotations must be contained in slice: " +
        s"$prevHighExclusive is not <= ${slice.highExclusive}."
      )
    }
    for (primaryRateLoad: Double <- primaryRateLoadOpt) {
      LoadMeasurement.requireValidLoadMeasurement(primaryRateLoad)
    }
  }
}

object SliceAssignment {

  /**
   * Creates a [[SliceAssignment]] for the given `proto`.
   *
   * @throws IllegalArgumentException if the proto is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: SliceAssignmentP, resourceMap: ResourceMap): SliceAssignment = {
    val slice: Slice = SliceHelper.fromProto(proto.getSlice)
    val generation = Generation.fromProto(proto.getGeneration)
    val resources: Set[Squid] = proto.resourceIndices.map { resourceIndex: Int =>
      resourceMap.resourceFromProtoIndex(resourceIndex)
    }.toSet
    // Shorthand type for the Vector builder of [[SubsliceAnnotation]].
    type SubsliceAnnotationsBuilder =
      mutable.Builder[SubsliceAnnotation, Vector[SubsliceAnnotation]]
    // A mutable Map containing the SubsliceAnnotationsBuilders used for incrementally constructing
    // `subsliceAnnotationsByResource` when iterating over the protos of annotations.
    val subsliceAnnotationsBuilderMap = mutable.Map[Squid, SubsliceAnnotationsBuilder]()
    // Iterate through the SubsliceAnnotation protos, re-construct SubsliceAnnotation scala classes
    // and add them to the `subsliceAnnotationsBuilderMap` based on their corresponding resources.
    for (subsliceAnnotationP: SubsliceAnnotationP <- proto.subsliceAnnotations) {
      val (subsliceAnnotation, subsliceResource): (SubsliceAnnotation, Squid) =
        SubsliceAnnotation.fromProto(subsliceAnnotationP, resourceMap)
      subsliceAnnotationsBuilderMap.getOrElseUpdate(
        subsliceResource,
        Vector.newBuilder[SubsliceAnnotation]
      ) += subsliceAnnotation
    }
    // Convert the SubsliceAnnotations' builders into an immutable Map of Vectors.
    val subsliceAnnotationsByResource: Map[Squid, Vector[SubsliceAnnotation]] =
      subsliceAnnotationsBuilderMap
        .mapValues((_: SubsliceAnnotationsBuilder).result())
        .toMap
    SliceAssignment(
      sliceWithResources = SliceWithResources(slice, resources),
      generation,
      subsliceAnnotationsByResource,
      proto.primaryRateLoadOpt
    )
  }
}

/**
 * REQUIRES: [[resources]] must be non-empty.
 *
 * A simple container associating a `slice` with a Set of `resources`.
 *
 * This is useful for composing in higher level structures, e.g. a spanning, disjoint assignment
 * of slices to resources can be expressed as a `SliceMap[SliceWithResources]`.
 */
case class SliceWithResources(slice: Slice, resources: Set[Squid]) {
  require(resources.nonEmpty, "The assigned resources must be non-empty")
}
