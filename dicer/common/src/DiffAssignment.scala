package com.databricks.dicer.common

import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.api.proto.dicer.common.DiffAssignmentP.SliceAssignmentP
import com.databricks.api.proto.dicer.common.{DiffAssignmentP, GenerationP}
import com.databricks.api.proto.dicer.friend.SquidP
import com.databricks.caching.util.PrefixLogger
import com.databricks.dicer.common.Assignment.ResourceMap
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import scalapb.TextFormat
import scala.util.control.NonFatal

/**
 * REQUIRES:
 *  - For assignments in the "loose" incarnation, the map must be full (diffs not permitted).
 *  - For assignments in the "loose" incarnation, `consistencyMode` must not be `Strong`.
 *  - For partial maps, the diff generation ([[DiffAssignmentSliceMap.Partial.diffGeneration]]) must
 *    be in the same incarnation as `generation`, which is the generation of the assignment, and
 *    must also be less than or equal to `generation`.
 *  - All Slice assignments must have generations that are in the same incarnation as `generation`,
 *    and must be less than or equal to `generation`.
 *
 * A representation of an [[Assignment]] that may be full, or may contain only those
 * Slice assignments with generations greater than some diff generation. Used as an optimization in
 * protocols and storage so that only changed Slices need to be conveyed or stored.
 *
 * @param isFrozen        Whether the assignment is frozen (see [[Assignment.isFrozen]]).
 * @param consistencyMode The consistency mode to use for the the assignment (see
 *                        [[Assignment.consistencyMode]]).
 * @param generation      The generation of the assignment.
 * @param sliceMap        Either a "full" or "partial" mapping from Slices to assigned resources.
 */
case class DiffAssignment(
    isFrozen: Boolean,
    consistencyMode: AssignmentConsistencyMode,
    generation: Generation,
    sliceMap: DiffAssignmentSliceMap) {
  require(generation != Generation.EMPTY, "Assignment must have non-empty generation.")
  require(
    consistencyMode != AssignmentConsistencyMode.Strong
    || generation.incarnation.isNonLoose,
    "Consistent assignment cannot be in the loose incarnation."
  )
  sliceMap match {
    case DiffAssignmentSliceMap
          .Partial(
          diffGeneration: Generation,
          sliceMap: SliceMap[GapEntry[SliceAssignment]]
          ) =>
      require(
        diffGeneration.incarnation.isNonLoose,
        "Assignments in the loose incarnation cannot have diffs."
      )
      require(
        diffGeneration.incarnation == generation.incarnation,
        s"Diff generation $diffGeneration must be in the same incarnation as the " +
        s"assignment generation $generation."
      )
      require(
        diffGeneration <= generation,
        s"Diff generation $diffGeneration must be less than or equal to the " +
        s"assignment generation $generation."
      )
      for (entry: GapEntry[SliceAssignment] <- sliceMap.entries) {
        entry match {
          case GapEntry.Some(sliceAssignment: SliceAssignment) =>
            sliceAssignment.checkAssignmentGeneration(generation)
          case GapEntry.Gap(_) =>
          // Nothing to validate.
        }
      }
    case DiffAssignmentSliceMap.Full(sliceMap: SliceMap[SliceAssignment]) =>
      for (sliceAssignment: SliceAssignment <- sliceMap.entries) {
        sliceAssignment.checkAssignmentGeneration(generation)
      }
  }

  def toProto: DiffAssignmentP = {
    val generationProto: Option[GenerationP] = Some(this.generation.toProto)
    val resourceBuilder = new Assignment.ResourceProtoBuilder
    val sliceAssignmentProtos = Seq.newBuilder[SliceAssignmentP]
    val diffGenerationProto: Option[GenerationP] = sliceMap match {
      case DiffAssignmentSliceMap
            .Partial(
            diffGeneration: Generation,
            sliceMap: SliceMap[GapEntry[SliceAssignment]]
            ) =>
        for (entry: GapEntry[SliceAssignment] <- sliceMap.entries) {
          entry match {
            case GapEntry.Some(sliceAssignment: SliceAssignment) =>
              sliceAssignmentProtos += sliceAssignment.toProto(resourceBuilder)
            case GapEntry.Gap(_) =>
            // Gaps are not serialized.
          }
        }
        Some(diffGeneration.toProto)
      case DiffAssignmentSliceMap.Full(
          sliceMap: SliceMap[SliceAssignment]
          ) =>
        for (sliceAssignment: SliceAssignment <- sliceMap.entries) {
          sliceAssignmentProtos += sliceAssignment.toProto(resourceBuilder)
        }
        None
    }
    val resourceProtos: Seq[SquidP] = resourceBuilder.toProtos
    val isFrozenProto: Option[Boolean] = if (this.isFrozen) Some(true) else None
    new DiffAssignmentP(
      generationProto,
      sliceAssignmentProtos.result(),
      resourceProtos,
      isFrozenProto,
      diffGenerationProto
    )
  }

  /** All resources that are assigned to some slice. */
  def assignedResources(): Set[Squid] = {
    sliceMap match {
      case DiffAssignmentSliceMap
            .Partial(_: Generation, sliceMap: SliceMap[GapEntry[SliceAssignment]]) =>
        sliceMap.entries.flatMap { entry: GapEntry[SliceAssignment] =>
          entry match {
            case GapEntry.Some(sliceAssignment: SliceAssignment) =>
              sliceAssignment.resources
            case GapEntry.Gap(_) => Vector.empty
          }
        }.toSet
      case DiffAssignmentSliceMap.Full(
          sliceMap: SliceMap[SliceAssignment]
          ) =>
        sliceMap.entries.flatMap { sliceAssignment: SliceAssignment =>
          sliceAssignment.resources
        }.toSet
    }
  }

  override def toString: String = {
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendDiffAssignmentToStringBuilder(
      this,
      builder,
      maxResources = 16,
      maxSlices = 32
    )
    builder.toString()
  }
}

object DiffAssignment {
  private val logger = PrefixLogger.create(getClass, "")

  def fromProto(proto: DiffAssignmentP): DiffAssignment = {
    try {
      fromProtoInternal(proto)
    } catch {
      case NonFatal(e: Throwable) =>
        // Every 5 minutes, perform a complete dump of the proto that did not parse to aid
        // debugging.
        logger.warn(
          s"Failed to parse DiffAssignment proto: $e, ${e.getStackTrace.mkString("\n  ")} " +
          s"Proto: ${TextFormat.printToString(proto)}",
          every = 5.minutes
        )
        throw e
    }
  }

  // Private implementation of `fromProto` to permit detailed logging on failure.
  private def fromProtoInternal(proto: DiffAssignmentP): DiffAssignment = {
    val resourceMap = ResourceMap.fromProtos(proto.resources)
    val assignmentGeneration = Generation.fromProto(proto.getGeneration)
    val diffGeneration = Generation.fromProto(proto.getDiffGeneration)
    val sliceAssignments: Seq[SliceAssignment] = proto.sliceAssignments.map {
      proto: SliceAssignmentP =>
        SliceAssignment.fromProto(proto, resourceMap)
    }
    val sliceMap: DiffAssignmentSliceMap = if (diffGeneration.incarnation.isLoose) {
      // When `diff_generation` field is empty or in a loose incarnation, a full assignment must be
      // contained in the proto.
      DiffAssignmentSliceMap.Full(
        SliceMapHelper.ofSliceAssignments(sliceAssignments.toVector)
      )
    } else {
      DiffAssignmentSliceMap.Partial(
        diffGeneration,
        SliceMap
          .createFromOrderedDisjointEntries(
            sliceAssignments,
            SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR
          )
      )
    }
    // TODO(<internal bug>) support strongly consistent assignments
    DiffAssignment(
      proto.getIsFrozen,
      AssignmentConsistencyMode.Affinity,
      assignmentGeneration,
      sliceMap
    )
  }
}

/**
 * Possible representations of the Slice map in a [[DiffAssignmentSliceMap]] instance.
 */
sealed trait DiffAssignmentSliceMap
object DiffAssignmentSliceMap {

  /**
   * A map containing only [[SliceAssignment]] with generations greater than
   * `diffGeneration`.
   */
  case class Partial(
      diffGeneration: Generation,
      sliceMap: SliceMap[GapEntry[SliceAssignment]]
  ) extends DiffAssignmentSliceMap

  /** A map containing all [[SliceAssignment]]. */
  case class Full(sliceMap: SliceMap[SliceAssignment]) extends DiffAssignmentSliceMap
}
