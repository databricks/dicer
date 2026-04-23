package com.databricks.dicer.common

import scala.collection.immutable.SortedMap

import com.databricks.api.proto.dicer.dpage.{
  AssignmentViewP,
  GenerationViewP,
  ResourceViewP,
  SliceViewP,
  SubsliceAnnotationViewP,
  TopKeyViewP
}
import com.databricks.dicer.external.{InfinitySliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.Squid

/**
 * Pure helper functions that convert Dicer domain objects to DPage view protos.
 *
 * These conversions are shared across Dicer components (assigner, storelet, etc.) and are
 * intentionally kept free of component-specific logic.
 */
object DPageViewHelpers {

  /** Converts a [[Generation]] to a [[GenerationViewP]] view proto. */
  def generationToViewProto(generation: Generation): GenerationViewP = {
    GenerationViewP(
      incarnation = Some(generation.incarnation.value),
      number = Some(generation.number.value),
      timestampStr = Some(generation.toTime.toString)
    )
  }

  /**
   * Converts an assignment to an [[AssignmentViewP]] view proto.
   *
   * When no assignment is present, returns an empty [[AssignmentViewP]].
   * Optional double fields (`attributedLoad`, `load`) are `None` when no measurement is available;
   * optional string fields (`stateProviderAddress`) are `None` when absent.
   *
   * @param assignmentOpt the assignment to convert, or None if no assignment exists
   * @param reportedLoadPerResourceOpt the reported load per resource to include in the resource
   *                                   table
   * @param reportedLoadPerSliceOpt overrides the recorded load per slice in the slice table
   * @param topKeysOpt the top keys to include in each slice's topKeys list
   */
  def getAssignmentViewProto(
      assignmentOpt: Option[Assignment],
      reportedLoadPerResourceOpt: Option[Map[Squid, Double]],
      reportedLoadPerSliceOpt: Option[Map[Slice, Double]],
      topKeysOpt: Option[SortedMap[SliceKey, Double]]): AssignmentViewP = {
    assignmentOpt match {
      case Some(assignment: Assignment) =>
        val resourcesList: Seq[ResourceViewP] =
          assignment.assignedResources.toSeq.map { squid: Squid =>
            ResourceViewP(
              address = Some(squid.resourceAddress.toString),
              uuid = Some(squid.resourceUuid.toString),
              creationTimeStr = Some(squid.creationTime.toString),
              // None when no load has been reported for this resource.
              attributedLoad = reportedLoadPerResourceOpt.flatMap { loadMap: Map[Squid, Double] =>
                loadMap.get(squid)
              }
            )
          }

        val slicesList: Seq[SliceViewP] = assignment.sliceAssignments.map { sa: SliceAssignment =>
          // Load: prefer externally reported override, fall back to recorded primary rate load.
          val loadOpt: Option[Double] = reportedLoadPerSliceOpt
            .flatMap { loadMap: Map[Slice, Double] =>
              loadMap.get(sa.slice)
            }
            .orElse(sa.primaryRateLoadOpt)

          // Top keys within this slice's key range.
          val topKeysInSlice: Seq[TopKeyViewP] = topKeysOpt match {
            case Some(topKeys: SortedMap[SliceKey, Double]) =>
              val keysInSlice: Map[SliceKey, Double] = sa.slice.highExclusive match {
                case key: SliceKey => topKeys.range(sa.slice.lowInclusive, key)
                case InfinitySliceKey => topKeys.from(sa.slice.lowInclusive)
              }
              keysInSlice.toSeq.map { entry: (SliceKey, Double) =>
                val (key, keyLoad): (SliceKey, Double) = entry
                TopKeyViewP(key = Some(key.toString), load = Some(keyLoad))
              }
            case None =>
              Seq.empty
          }

          // Subslice annotations as a flat list grouped by resource.
          val subsliceAnnotations: Seq[SubsliceAnnotationViewP] =
            sa.subsliceAnnotationsByResource.toSeq.flatMap {
              entry: (Squid, Vector[SubsliceAnnotation]) =>
                val (resource, annotations): (Squid, Vector[SubsliceAnnotation]) = entry
                annotations.map { ann: SubsliceAnnotation =>
                  SubsliceAnnotationViewP(
                    resource = Some(resource.resourceAddress.toString),
                    lowKey = Some(ann.subslice.lowInclusive.toString),
                    highKey = Some(ann.subslice.highExclusive.toString),
                    continuousGenerationNumber = Some(ann.continuousGenerationNumber.value),
                    // None when no state transfer is in progress for this subslice.
                    stateProviderAddress = ann.stateTransferOpt
                      .map((t: Transfer) => t.fromResource.resourceAddress.toString)
                  )
                }
            }

          SliceViewP(
            lowKey = Some(sa.slice.lowInclusive.toString),
            highKey = Some(sa.slice.highExclusive.toString),
            generation = Some(generationToViewProto(sa.generation)),
            resources = sa.resources.map((_: Squid).resourceAddress.toString).toSeq,
            // None when no load measurement is available for this slice.
            load = loadOpt,
            topKeys = topKeysInSlice,
            subsliceAnnotations = subsliceAnnotations
          )
        }

        AssignmentViewP(
          generation = Some(generationToViewProto(assignment.generation)),
          isFrozen = Some(assignment.isFrozen),
          consistencyMode = Some(assignment.consistencyMode.toString),
          resources = resourcesList,
          slices = slicesList
        )

      case None =>
        AssignmentViewP()
    }
  }
}
