package com.databricks.dicer.common

import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.api.proto.dicer.common.DiffAssignmentP.{
  AssignerServiceInfoP,
  SliceAssignmentP
}
import com.databricks.api.proto.dicer.common.{DiffAssignmentP, GenerationP}
import com.databricks.api.proto.dicer.friend.SquidP
import com.databricks.caching.util.PrefixLogger
import com.databricks.dicer.common.Assignment.ResourceMap
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import scalapb.TextFormat
import io.prometheus.client.Counter
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
 * @param assignerServiceInfoOpt The service info of the Assigner that generated the assignment
 *                               this diff is based on. It should be populated by the generating
 *                               assigner, but may be absent if the Assigner cannot determine the
 *                               service info or if an outdated Assigner binary is deployed.
 */
case class DiffAssignment(
    isFrozen: Boolean,
    consistencyMode: AssignmentConsistencyMode,
    generation: Generation,
    sliceMap: DiffAssignmentSliceMap,
    assignerServiceInfoOpt: Option[AssignerServiceInfo]
) {
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
    val assignerServiceInfoProto: Option[AssignerServiceInfoP] = assignerServiceInfoOpt.map {
      assignerServiceInfo: AssignerServiceInfo =>
        assignerServiceInfo.toProto
    }
    new DiffAssignmentP(
      generationProto,
      sliceAssignmentProtos.result(),
      resourceProtos,
      isFrozenProto,
      diffGenerationProto,
      assignerServiceInfo = assignerServiceInfoProto
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

  /**
   * The outcome of attempting to parse an [[AssignerServiceInfo]] out of a [[DiffAssignmentP]] in
   * [[DiffAssignment.fromProto]]. Used as the `outcome` label value on the
   * `dicer_assignment_service_info_parse_total` metric.
   */
  private sealed trait AssignerServiceInfoParseOutcome

  private object AssignerServiceInfoParseOutcome {

    /**
     * The `assigner_service_info` field was present and parsed into a valid
     * [[AssignerServiceInfo]].
     */
    case object Valid extends AssignerServiceInfoParseOutcome {
      override def toString: String = "valid"
    }

    /** The `assigner_service_info` field was absent from the proto. */
    case object Absent extends AssignerServiceInfoParseOutcome {
      override def toString: String = "absent"
    }

    /**
     * The `assigner_service_info` field was present but could not be parsed into a valid
     * [[AssignerServiceInfo]] (e.g. a missing or non-RFC-1123 name or instance id).
     */
    case object Invalid extends AssignerServiceInfoParseOutcome {
      override def toString: String = "invalid"
    }
  }

  private val logger = PrefixLogger.create(getClass, "")

  /**
   * Counter tracking the number of attempts to parse an assigner service.
   */
  // TODO(<internal bug>): Temporary metric until all assignments have a valid assigner service info.
  private val assignerServiceInfoParse: Counter = Counter
    .build()
    .name("dicer_assignment_service_info_parse_total")
    .labelNames("outcome", "assignerName", "assignerInstanceId")
    .help(
      "Count of assigner service info parse attempts in DiffAssignment.fromProto, labeled by " +
      "outcome (valid/absent/invalid). assignerName and assignerInstanceId are set if available."
    )
    .register()

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
    // Parses `DiffAssignmentP.AssignerServiceInfoP` and handles logging/metrics based on the
    // outcome.
    val assignerServiceInfoOpt: Option[AssignerServiceInfo] = proto.assignerServiceInfo match {
      case None =>
        // The service info is absent, so an "absent" label is recorded.
        recordParseOutcome(
          AssignerServiceInfoParseOutcome.Absent,
          assignerNameOpt = None,
          assignerInstanceIdOpt = None
        )
        None
      case Some(assignerServiceInfoProto: AssignerServiceInfoP) =>
        try {
          val info: AssignerServiceInfo = AssignerServiceInfo.fromProto(assignerServiceInfoProto)
          // The service info is valid, so a "valid" label is recorded with the `name` and
          // `instanceId`.
          recordParseOutcome(
            AssignerServiceInfoParseOutcome.Valid,
            Some(info.name),
            Some(info.instanceId)
          )
          Some(info)
        } catch {
          case e: IllegalArgumentException =>
            // The service info is invalid, so an "invalid" label is recorded with the proto's
            // `name` and `instanceId` if available.
            recordParseOutcome(
              AssignerServiceInfoParseOutcome.Invalid,
              assignerServiceInfoProto.name,
              assignerServiceInfoProto.instanceId
            )
            // Every 5 minutes, log the invalid assigner service info that was dropped, so invalid
            // assigner service info can be debugged.
            logger.warn(
              s"Dropping invalid AssignerServiceInfo: $e, " +
              s"Proto: ${TextFormat.printToString(assignerServiceInfoProto)}",
              every = 5.minutes
            )
            None
        }
    }
    // TODO(<internal bug>) support strongly consistent assignments
    DiffAssignment(
      proto.getIsFrozen,
      AssignmentConsistencyMode.Affinity,
      assignmentGeneration,
      sliceMap,
      assignerServiceInfoOpt
    )
  }

  /**
   * Records the outcome of an [[AssignerServiceInfo]] parse attempt. Absent names and instance ids
   * are recorded as empty string labels.
   *
   * @param outcome               The parse outcome as defined in
   *                              [[AssignerServiceInfoParseOutcome]] (e.g., "valid", "absent",
   *                              "invalid").
   * @param assignerNameOpt       The name of the Assigner the outcome was recorded for if known.
   * @param assignerInstanceIdOpt The instance id of the Assigner the outcome was recorded for if
   *                              known.
   */
  private def recordParseOutcome(
      outcome: AssignerServiceInfoParseOutcome,
      assignerNameOpt: Option[String],
      assignerInstanceIdOpt: Option[String]): Unit = {
    assignerServiceInfoParse
      .labels(
        outcome.toString,
        assignerNameOpt.getOrElse(""),
        assignerInstanceIdOpt.getOrElse("")
      )
      .inc()
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
