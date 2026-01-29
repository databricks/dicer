package com.databricks.dicer.common

import java.time.{Duration, Instant}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import com.databricks.caching.util.AsciiTable
import com.databricks.caching.util.AsciiTable.Header
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.caching.util.UnixTimeVersion

/** Utilities producing human-readable descriptions of assignments. */
object AssignmentFormatter {

  /**
   * Appends a human-readable representation of the given `assignment` to the given `builder`. The
   * format is subject to change. For example:
   *
   * <pre>
   * 0#3#1970-01-01T00:00:00.050Z
   *
   * ┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────
   * │ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load
   * ├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────
   * │ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -
   * │ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -
   * │ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -
   * └──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────
   *
   * ┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
   * │ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
   * ├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
   * │ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │         │
   * │ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │         │
   * │ |        │ pod2                 │ -                │ -     │         │
   * │ |        │ pod3                 │ -                │ -     │         │
   * │ |-Bifur  │ pod1                 │ -                │ -     │         │
   * │ |        │ pod2 @10 (PT-0.04S)  │ -                │ -     │         │
   * │ |        │ pod3                 │ -                │ -     │         │
   * │ |-Bofur  │ pod1                 │ -                │ -     │         │
   * │ |        │ pod2                 │ -                │ -     │         │
   * │ |        │ pod3                 │ -                │ -     │         │
   * │ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
   * │ Nori     │ pod2                 │ 50 (PT0S)        │ (N/A) │         │
   * └──────────┴──────────────────────┴──────────────────┴───────┴─────────┘

   * <internal link>
   * </pre>
   *
   * In the assignment table, the rows between each pair of consecutive UN-INDENTED low keys show
   * the information of one Slice Assignment. For example, rows between "Balin" inclusive and "Kili"
   * exclusive show the information of Slice Assignment for [Balin, Kili); and rows between
   * [Kili, Nori) show another Slice Assignment.
   *
   * The "Slice Generation" row shows the generation of each Slice Assignment. The exact generation
   * is only displayed on the very first row for a Slice Assignment, and subsequent rows will show
   * "-" as placeholders.
   *
   * Each Slice Assignment is broken into multiple Subslices to display continuous assignment
   * information. The "Address" column for each Subslice contains the full set of resources assigned
   * in that Subslice (and also in its Slice Assignment). For example
   *
   * |-Bifur  │ pod1
   * |        │ pod2 @10 (PT-0.04S)
   * |        │ pod3
   * |-Bofur  │ pod1
   *
   * shows the information of the second subslice [Bifur, Bofur) in Slice Assignent [Balin, Kili).
   * It indicates resources pod1, pod2 and pod3 are assigned on [Bifur, Bofur), and pod2 is
   * continuously assigned on [Bifur, Bofur) since generation number 10. pod1 and pod3 don't have
   * the "@" annotation as suffixes, which means they are newly assigned in [Bifur, Bofur) since
   * the generation of the Slice Assignment (shown in "Slice Generation" column), which is 20.
   *
   * "Load" cells show the total load on the whole Slice for each Slice Assignment. The exact load
   * value is displayed only on the very first row of each Slice Assignment. Other rows will show
   * "-" as placeholders.
   *
   * The "Details" column includes top keys as well as state transfer information, if applicable
   * to that row.
   *
   * The output contains a link to the Dicer assignment documentation at <internal link>,
   * which explains concepts like Slice generations and continuously assigned subslices.
   *
   * *
   * @param assignment the assignment to format.
   * @param builder the StringBuilder to append the formatted assignment to.
   * @param maxResources the maximum number of resources to display in the resource table. Does not
   *                    affect the resources per slice displayed in the assignment table.
   * @param maxSlices the maximum number of slices to display in the assignment table.
   * @param loadPerResourceOpt the load per resource to display in the resource table. Note that
   *                           when this field is empty, the AssignmentFormatter does not attempt
   *                           to calculate the load per resource from the assignment-tracked load,
   *                           resulting in an empty attributed load column in the resource table.
   * @param loadPerSliceOverrideOpt if present, overrides the load per slice tracked by the
   *                                assignment itself to be displayed in the assignment table.
   * @param topKeysOpt the top keys to display in the "Details" column of the assignment table.
   * @param squidFilterOpt the squid filter to apply to both the resource table and the assignment
   *                       table. In the assignment table, only slice assignments related to the
   *                       given resource will be displayed.
   */
  def appendAssignmentToStringBuilder(
      assignment: Assignment,
      builder: StringBuilder,
      maxResources: Int,
      maxSlices: Int,
      loadPerResourceOpt: Option[Map[Squid, Double]],
      loadPerSliceOverrideOpt: Option[Map[Slice, Double]],
      topKeysOpt: Option[SortedMap[SliceKey, Double]],
      squidFilterOpt: Option[Squid]): Unit = {
    // Write assignment metadata, e.g.: "0#3#1970-01-01T00:00:00.050Z"
    builder.append(assignment.generation.toString).append('\n')
    if (assignment.isFrozen) {
      builder.append("FROZEN\n")
    }
    builder.append('\n')

    appendResourceTableToStringBuilder(
      assignment.assignedResources,
      builder,
      maxResources,
      loadPerResourceOpt,
      squidFilterOpt
    )

    // Append slice assignments table.
    // If `squidFilterOpt` is defined, only slice assignments related to the resource defined in
    // `squidFilterOpt` will be presented in the slice assignments table. Otherwise, information
    // for all assigned resources will be displayed.
    val selectedSliceAssignments: Vector[SliceAssignment] = squidFilterOpt match {
      case Some(squid: Squid) =>
        assignment.sliceAssignments
          .flatMap { sliceAssignment: SliceAssignment =>
            if (sliceAssignment.resources.contains(squid)) {
              // We only care about the information related with `squid`. Filter out all other
              // unnecessary information of other resources.
              Some(
                SliceAssignment(
                  SliceWithResources(sliceAssignment.slice, Set(squid)),
                  sliceAssignment.generation,
                  sliceAssignment.subsliceAnnotationsByResource
                    .filterKeys((_: Squid) == squid)
                    .toMap,
                  sliceAssignment.primaryRateLoadOpt
                )
              )
            } else {
              // This Slice Assignment doesn't contain any information about `squid` so we discard
              // it.
              None
            }
          }
      case None =>
        assignment.sliceAssignments
    }
    val shortResourceAddresses = createShortAddrMap(assignment.assignedResources)
    if (maxSlices < selectedSliceAssignments.size) {
      builder.append(s"$maxSlices of ${selectedSliceAssignments.size} slices:\n")
    }
    val sliceAssignmentsTable: AsciiTable = createSliceAssignmentTable(
      shortResourceAddresses,
      assignment.generation,
      selectedSliceAssignments,
      loadPerSliceOverrideOpt,
      topKeysOpt,
      maxSlices
    )
    sliceAssignmentsTable.appendTo(builder)

    // Append a link to the Dicer assignment documentation.
    builder.append("\n<internal link>\n")
  }

  /**
   * Appends a human-readable representation of the given `diffAssignment` to the given `builder`.
   * The format is subject to change. See [[appendAssignmentToStringBuilder()]] for examples of the
   * format.
   *
   * The primary difference in the string representation of a [[DiffAssignment]] and the
   * string representation of an [[Assignment]] is that gaps in the [[DiffAssignment]] are
   * represented as rows with '(Gap)' values for each column. Additionally, the string
   * representation of a [[DiffAssignment]] does not include assignment stats.
   */
  def appendDiffAssignmentToStringBuilder(
      diffAssignment: DiffAssignment,
      builder: StringBuilder,
      maxResources: Int,
      maxSlices: Int): Unit = {
    // Write assignment metadata, e.g.: "0#3#1970-01-01T00:00:00.050Z"
    builder.append(diffAssignment.generation.toString).append('\n')
    diffAssignment.sliceMap match {
      case DiffAssignmentSliceMap.Partial(diffGeneration: Generation, _) =>
        builder.append(s"[Partial diff from ${diffGeneration.toString}]\n")
      case _ =>
    }
    if (diffAssignment.isFrozen) {
      builder.append("FROZEN\n")
    }
    builder.append('\n')

    appendResourceTableToStringBuilder(
      diffAssignment.assignedResources,
      builder,
      maxResources,
      loadPerResourceOpt = None,
      squidFilterOpt = None
    )

    // Append slice assignments table.
    val sliceAssignments: Vector[GapEntry[SliceAssignment]] =
      diffAssignment.sliceMap match {
        case DiffAssignmentSliceMap
              .Partial(_, sliceMap: SliceMap[GapEntry[SliceAssignment]]) =>
          sliceMap.entries
        case DiffAssignmentSliceMap.Full(
            sliceMap: SliceMap[SliceAssignment]
            ) =>
          sliceMap.entries.map((entry: SliceAssignment) => GapEntry.Some(entry))
      }

    val shortResourceAddresses = createShortAddrMap(diffAssignment.assignedResources())
    if (maxSlices < sliceAssignments.size) {
      builder.append(s"$maxSlices of ${sliceAssignments.size} slices:\n")
    }
    val sliceAssignmentsTable: AsciiTable = createSliceDiffAssignmentTable(
      shortResourceAddresses,
      diffAssignment.generation,
      sliceAssignments,
      maxSlices
    )
    sliceAssignmentsTable.appendTo(builder)

    // Append a link to the Dicer assignment documentation.
    builder.append("\n<internal link>\n")
  }

  /**
   * Appends a human-readable representation of the given `sliceAssignment` to the given `builder`.
   * The format is the same as the assignment table returned by [[appendAssignmentToStringBuilder]],
   * but only contains one slice assignment.
   */
  def appendSliceAssignmentToStringBuilder(
      sliceAssignment: SliceAssignment,
      builder: StringBuilder
  ): Unit = {
    builder.append("\n")
    val shortResourceAddresses: Map[Squid, String] = createShortAddrMap(sliceAssignment.resources)
    val sliceAssignmentTable: AsciiTable = createSliceAssignmentTable(
      shortResourceAddresses,
      sliceAssignment.generation,
      sliceAssignments = Vector(sliceAssignment),
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      maxSlices = 1 // Only 1 slice assignment is passed in anyway.
    )
    builder.append("\n")
    sliceAssignmentTable.appendTo(builder)
  }

  private def appendResourceTableToStringBuilder(
      assignedResources: Set[Squid],
      builder: StringBuilder,
      maxResources: Int,
      loadPerResourceOpt: Option[Map[Squid, Double]],
      squidFilterOpt: Option[Squid]): Unit = {
    // Append resource table.
    // If `squidFilterOpt` is defined, only the information related to the resource defined in
    // `squidFilterOpt` will be presented in the resource table. Otherwise, information for all
    // assigned resources will be displayed.
    val selectedResources: Set[Squid] =
      squidFilterOpt match {
        case Some(squid: Squid) => Set(squid)
        case None => assignedResources
      }

    if (selectedResources.size > maxResources) {
      builder.append(s"$maxResources of ${selectedResources.size} resources:\n")
    }
    val resourcesTable: AsciiTable = createResourceTable(
      selectedResources,
      loadPerResourceOpt,
      maxResources
    )
    resourcesTable.appendTo(builder)
    builder.append('\n')
  }

  /**
   * Creates an ASCII table that represents all assigned resources.
   *
   * @param resources          resources whose information to display in the table.
   * @param loadPerResourceOpt optional map specifying the load for assigned resource.
   * @param maxResources       maximum number of resources shown in the table.
   */
  private def createResourceTable(
      resources: Set[Squid],
      loadPerResourceOpt: Option[Map[Squid, Double]],
      maxResources: Int): AsciiTable = {
    val resourcesTable =
      new AsciiTable(
        Header("Address"),
        Header("Resource UUID"),
        Header("Creation Time"),
        Header("Attributed Load")
      )
    var resourceRowCount: Int = 0 // so that we don't exceed `maxResources`
    for (resource: Squid <- resources) {
      if (resourceRowCount < maxResources) {
        resourceRowCount += 1

        // Retrieve load stats for the resource. Use "-" if resource has no load stats.
        val attributedLoadOpt: Option[Double] =
          loadPerResourceOpt.flatMap(loadPerResource => loadPerResource.get(resource))
        val attributedLoadString: String = attributedLoadOpt match {
          case Some(attributedLoad: Double) =>
            attributedLoad.toString
          case None =>
            "-"
        }
        // Add address row, e.g.
        // "https://pod1 | d1eaf1f9-7b39-3651-bb77-1d10433dacfd | 2023-03-31T16:12:12Z | 0.0"
        resourcesTable.appendRow(
          resource.resourceAddress.toString,
          resource.resourceUuid.toString,
          resource.creationTime.toString,
          attributedLoadString
        )
      }
    }
    resourcesTable
  }

  /**
   * Creates an ASCII table representation of assignment information.
   *
   * @param shortResourceAddresses short-form addresses for resources.
   * @param generation the generation of the assignment.
   * @param sliceAssignments detailed slice assignments that contain load and continuous
   *                         assignment information. The load information in slice assignments
   *                         is recorded at the assignment creation.
   * @param loadPerSliceOverrideOpt similar to load information in sliceAssignments, but it is
   *                                continuously updated while the assignment is in use. If
   *                                loadPerSliceOverrideOpt is specified, it overrides the load in
   *                               [[Assignment.sliceAssignments]] within the table.
   * @param topKeysOpt hot keys for each slice. This will be included in the "Details" column of
   *                   the table, if there is a top key in the corresponding slice.
   * @param maxSlices the maximum number of slices to display in the table.
   */
  private def createSliceAssignmentTable(
      shortResourceAddresses: Map[Squid, String],
      generation: Generation,
      sliceAssignments: Vector[SliceAssignment],
      loadPerSliceOverrideOpt: Option[Map[Slice, Double]],
      topKeysOpt: Option[SortedMap[SliceKey, Double]],
      maxSlices: Int): AsciiTable = {
    val generationInstant: Instant = generation.toTime

    // Write all Slice assignments to a table.
    val headers: Seq[Header] = Seq(
      Header("Low Key"),
      Header("Address"),
      Header("Slice Generation"),
      Header("Load"),
      Header("Details")
    )

    val sliceAssignmentsTable = new AsciiTable(headers: _*)
    var lastHighSliceKeyOpt: Option[HighSliceKey] = None

    val truncateStartCount = maxSlices - (maxSlices / 2)
    val truncateEndIndex = sliceAssignments.size - maxSlices + truncateStartCount
    var omittedCount = 0
    var unassignedCount = 0
    var firstOmitted: Option[HighSliceKey] = None

    for (item <- sliceAssignments.zipWithIndex) {
      val (sliceAssignment, index): (SliceAssignment, Int) = item
      if (index >= truncateStartCount && index < truncateEndIndex) {
        // We are in the truncated range. Keep track of what has been truncated.
        if (firstOmitted.isEmpty) {
          firstOmitted = lastHighSliceKeyOpt.orElse(Some(SliceKey.MIN))
        }

        omittedCount += 1

        if (lastHighSliceKeyOpt.exists((_: HighSliceKey) != sliceAssignment.slice.lowInclusive)) {
          unassignedCount += 1
        }

        if (index == truncateEndIndex - 1) {
          // We are exiting the truncated range. Add a row indicating what has been truncated.
          sliceAssignmentsTable.appendRow(
            firstOmitted.get.toString, // "Low Key"
            "...", // "Address"
            "...", // "Slice Generation"
            "...", // "Load"
            f"$omittedCount slices(s) omitted and $unassignedCount unassigned range(s)" // "Details"
          )
        }
      } else {
        if (lastHighSliceKeyOpt.exists((_: HighSliceKey) != sliceAssignment.slice.lowInclusive)) {
          // The current low SliceKey doesn't match the last high SliceKey. This may be because the
          // caller has filtered Slice Assignments by resource. We add a new row indicating where
          // the last Slice Assignment ends.
          sliceAssignmentsTable.appendRow(
            lastHighSliceKeyOpt.get.toString, // "Low Key"
            "(Unassigned)", // "Address"
            "(N/A)", // "Slice Generation"
            "(N/A)", // "Load"
            "" // "Details"
          )
        }

        appendSliceAssignmentRows(
          sliceAssignment,
          sliceAssignmentsTable,
          shortResourceAddresses,
          generationInstant,
          loadPerSliceOverrideOpt,
          topKeysOpt
        )
      }
      lastHighSliceKeyOpt = Some(sliceAssignment.slice.highExclusive)

    }

    if (lastHighSliceKeyOpt.exists((_: HighSliceKey) != InfinitySliceKey)) {
      // The last Slice Assignment doesn't end at infinity key. Add a row indicating where it ends.
      // This might because the last Slice Assignments are filtered by resource.
      sliceAssignmentsTable.appendRow(
        lastHighSliceKeyOpt.get.toString, // "Low Key"
        "(Unassigned)", // "Address"
        "(N/A)", // "Slice Generation"
        "(N/A)", // "Load"
        "" // "Details"
      )
    }

    sliceAssignmentsTable
  }

  private def createSliceDiffAssignmentTable(
      shortResourceAddresses: Map[Squid, String],
      generation: Generation,
      sliceAssignments: Vector[GapEntry[SliceAssignment]],
      maxSlices: Int): AsciiTable = {
    val generationInstant: Instant = generation.toTime
    // Write all Slice assignments to a table.
    val sliceAssignmentsTable = new AsciiTable(
      Header("Low Key"),
      Header("Address"),
      Header("Slice Generation"),
      Header("Load"),
      Header("Details")
    )

    for (gapSliceAssignment: GapEntry[SliceAssignment] <- sliceAssignments.take(
        maxSlices
      )) {
      gapSliceAssignment match {
        case GapEntry.Some(sliceAssignment: SliceAssignment) =>
          appendSliceAssignmentRows(
            sliceAssignment,
            sliceAssignmentsTable,
            shortResourceAddresses,
            generationInstant,
            None,
            None
          )
        case GapEntry.Gap(slice: Slice) =>
          sliceAssignmentsTable.appendRow(
            slice.lowInclusive.toString,
            "(Gap)",
            "(Gap)",
            "(Gap)",
            "(Gap)"
          )
      }
    }
    sliceAssignmentsTable
  }

  /**
   * Describes the Slice generation number relative to the assignment generation number (the
   * number approximates the number of milliseconds since the Unix epoch). For example, a Slice
   * with generation number 28000 might be rendered as "28000 (PT-2S)" if the current assignment
   * has generation number 30000 (which is 2 seconds after 28000 since the generation numbers
   * represent milliseconds since the Unix epoch).
   */
  private def describeSliceGeneration(
      generationInstant: Instant,
      sliceGenerationNumber: UnixTimeVersion): String = {
    val sliceGenerationInstant: Instant = sliceGenerationNumber.toTime
    val offset = Duration.between(generationInstant, sliceGenerationInstant)

    // Use compact formatting of the offset (don't need lots of precision).
    offset.toString
    s"${sliceGenerationNumber.value} ($offset)"
  }

  /**
   * Appends the rows for a `sliceAssignment` onto the ascii table `sliceAssignmentTable`, using the
   * format described in the docs of [[appendAssignmentToStringBuilder]].
   */
  private def appendSliceAssignmentRows(
      sliceAssignment: SliceAssignment,
      sliceAssignmentsTable: AsciiTable,
      shortResourceAddresses: Map[Squid, String],
      generationInstant: Instant,
      loadPerSliceOverrideOpt: Option[Map[Slice, Double]],
      topKeysOpt: Option[SortedMap[SliceKey, Double]]): Unit = {
    // SliceMap containing Subslice Annotations for each assigned resource. Used to quickly look
    // up the annotation information for any resource on any intersected Subslice.
    val annotationMaps: Map[Squid, SliceMap[GapEntry[SubsliceAnnotation]]] =
      sliceAssignment.subsliceAnnotationsByResource.mapValues {
        annotations: Vector[SubsliceAnnotation] =>
          SliceMap.createFromOrderedDisjointEntries(annotations, (_: SubsliceAnnotation).subslice)
      }.toMap

    // The sorted split points (intersection boundaries) after intersecting all the Subslice
    // Annotations of all the resources against the Slice Assignment.
    val orderedIntersectionSplitPoints: Vector[SliceKey] = {
      val builder = mutable.SortedSet[SliceKey]()
      val allAnnotationSlices: Vector[Slice] =
        sliceAssignment.subsliceAnnotationsByResource.values.flatten
          .map((_: SubsliceAnnotation).subslice)
          .toVector
      // Each low key of SubsliceAnnotations will make a split.
      builder ++= allAnnotationSlices.map((_: Slice).lowInclusive)
      // Each finite high key of SubsliceAnnotations will make a split, but it cannot be the high
      // key of the Slice Assignment - we don't show this high key in the table rows for this
      // Slice Assignment (it will be shown in the rows of next Slice Assignment, or it's an
      // infinite key and won't be shown in the table at all).
      builder ++= allAnnotationSlices
        .map((_: Slice).highExclusive)
        .filter { highSliceKey: HighSliceKey =>
          highSliceKey.isFinite && highSliceKey != sliceAssignment.slice.highExclusive
        }
        .map((_: HighSliceKey).asFinite)
      // Including the low key of the SliceAssignment, in case it's not an endpoint of any
      // subslice annotation.
      builder += sliceAssignment.slice.lowInclusive
      // Note the high key of SliceAssignment is not added because it doesn't appear in the table
      // rows of the current Slice Assignment.
      builder.toVector
    }

    // Whether we are rending data for the first intersection of the whole Slice Assignment.
    var isFirstIntersectionInSlice: Boolean = true
    for (intersectionLowKey: SliceKey <- orderedIntersectionSplitPoints) {
      // In each run of the loop body, we will append assignment information for one intersection
      // that starts from `intersectionLowKey` (and ends at the next intersection low key).
      var isFirstRowInIntersection: Boolean = true
      for (resource: Squid <- sliceAssignment.resources) {
        // In each run of the loop body, we will append information of one resource for the current
        // intersection as one row in the ascii table.

        // Optional subslice annotation for `resource`.
        val annotationOpt: Option[SubsliceAnnotation] = annotationMaps.get(resource).flatMap {
          annotationSliceMap: SliceMap[GapEntry[SubsliceAnnotation]] =>
            annotationSliceMap.lookUp(intersectionLowKey) match {
              case GapEntry.Some(annotation: SubsliceAnnotation) => Some(annotation)
              case _: GapEntry.Gap[SubsliceAnnotation] => None
            }
        }

        // Calculate description strings for each column of the table, based on annotation
        // information for the resource and also the position of this row.

        val lowKeyDesc: String =
          if (isFirstIntersectionInSlice && isFirstRowInIntersection) {
            intersectionLowKey.toString
          } else if (isFirstRowInIntersection) {
            // Intend if the low key is not the staring point of the Slice Assignment. "|-" to
            // make a pretty tree-like presentation.
            s"|-$intersectionLowKey"
          } else {
            // Emit redundant presentation of low keys as the key has appeared in the row above.
            // "|" to connect the rows above and below.
            "|"
          }

        val addressDesc: String = {
          // Only show the detailed continuous generation information after the resource address
          // if there's an annotation defined for it, i.e. it has a different continuous generation
          // number with the generation of the Slice Assignment.
          val continuousGenerationNumberDesc: String =
            annotationOpt
              .map { subsliceAnnotation: SubsliceAnnotation =>
                " @" + describeSliceGeneration(
                  generationInstant,
                  subsliceAnnotation.continuousGenerationNumber
                )
              }
              .getOrElse("")
          shortResourceAddresses(resource) + continuousGenerationNumberDesc
        }

        val sliceGenerationDesc: String =
          if (isFirstIntersectionInSlice && isFirstRowInIntersection) {
            // Show Slice generation only once for each Slice Assignment, at the very first row.
            describeSliceGeneration(generationInstant, sliceAssignment.generation.number)
          } else {
            "-" // Means this information can be found in the first row of the Slice Assignment.
          }

        val loadDesc: String =
          if (isFirstIntersectionInSlice && isFirstRowInIntersection) {
            // Show Slice load only once for each Slice Assignment, at the very first row.

            // Retrieve load information from `loadPerSliceOpt`. If the specified slice is not
            // found in `loadPerSliceOpt`, fall back to the load information in slice assignment.
            // If that's also not available, then use "(N/A)" to show it's unavailable.
            val sliceLoadOpt: Option[Double] = loadPerSliceOverrideOpt
              .flatMap { loadPerSlice: Map[Slice, Double] =>
                loadPerSlice.get(sliceAssignment.slice)
              }
              .orElse {
                sliceAssignment.primaryRateLoadOpt
              }
            sliceLoadOpt match {
              case Some(sliceLoad: Double) =>
                sliceLoad.toString
              case None =>
                "(N/A)"
            }
          } else {
            "-" // Means this information can be found in the first row of the Slice Assignment.
          }

        val detailsDesc: String = {
          var topKeysDesc: String = ""
          if (isFirstIntersectionInSlice && isFirstRowInIntersection) {
            // Show all top keys in `sliceAssignment.slice` on the very first row, rather than
            // showing it separately for each subslice. This is because we display the slice's
            // load only on this row, so it's easier to compare the hot keys contribution to the
            // overall slice load.
            for (topKeys: SortedMap[SliceKey, Double] <- topKeysOpt) {
              val topKeysInSlice: Map[SliceKey, Double] =
                sliceAssignment.slice.highExclusive match {
                  case key: SliceKey => topKeys.range(sliceAssignment.slice.lowInclusive, key)
                  case InfinitySliceKey => topKeys.from(sliceAssignment.slice.lowInclusive)
                }
              if (topKeysInSlice.nonEmpty) {
                topKeysDesc = "Slice top keys: " + topKeysInSlice
                    .map { case (key, load) => s"$key = $load" }
                    .mkString(", ")
              }
            }
          }

          val stateTransferDesc: String = annotationOpt
            .flatMap { annotation: SubsliceAnnotation =>
              annotation.stateTransferOpt.map { transfer: Transfer =>
                s"State provider: ${transfer.fromResource.resourceAddress}"
              }
            }
            .getOrElse("") // Empty if no state provider information.

          // `detailDesc` combines (optional) top keys and (optional) state transfer information.
          Seq(stateTransferDesc, topKeysDesc).filter((_: String).nonEmpty).mkString(", ")
        }

        sliceAssignmentsTable.appendRow(
          lowKeyDesc,
          addressDesc,
          sliceGenerationDesc,
          loadDesc,
          detailsDesc
        )

        isFirstRowInIntersection = false
      }
      isFirstIntersectionInSlice = false
    }
  }

  /** Creates a list of short-form addresses for resources. */
  private def createShortAddrMap(resources: Set[Squid]): Map[Squid, String] = {
    val shortResourceAddresses = mutable.Map[Squid, String]()
    for (resource: Squid <- resources) {
      val resourceAddress: String = resource.resourceAddress.toString
      // Omit the https scheme from the address, since it's already shown in the resource table and
      // takes up too much space in the assignment table.
      val shortResourceAddress: String = if (resourceAddress.startsWith("https://")) {
        resourceAddress.substring("https://".length)
      } else {
        resourceAddress
      }
      shortResourceAddresses(resource) = shortResourceAddress
    }
    shortResourceAddresses.toMap
  }
}
