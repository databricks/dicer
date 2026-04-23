package com.databricks.dicer.common

import scala.collection.immutable.SortedMap
import scala.util.Random

import com.databricks.api.proto.dicer.dpage.{
  AssignmentViewP,
  GenerationViewP,
  ResourceViewP,
  SliceViewP,
  SubsliceAnnotationViewP,
  TopKeyViewP
}
import com.databricks.dicer.assigner.algorithm.Resources
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{InfinitySliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

class DPageViewHelpersSuite extends DatabricksTest {

  /** An arbitrary generation used across tests. */
  private val GENERATION: Generation = createLooseGeneration(42)

  /** Fixed random seed for deterministic tests. */
  private val RNG: Random = new Random(12345)

  // -- generationToViewProto --

  test("generationToViewProto populates incarnation, number, and timestampStr") {
    // Test plan: Verify that generationToViewProto converts a Generation to a GenerationViewP with
    // the correct incarnation value, number (epoch millis), and human-readable timestamp string.
    val result: GenerationViewP = DPageViewHelpers.generationToViewProto(GENERATION)

    assert(result.incarnation.contains(GENERATION.incarnation.value))
    assert(result.number.contains(GENERATION.number.value))
    assert(result.timestampStr.contains(GENERATION.toTime.toString))
  }

  test("generationToViewProto with generation number zero") {
    // Test plan: Verify that generationToViewProto handles the edge case of generation number 0
    // (the Unix epoch) correctly.
    val zeroGeneration: Generation = createLooseGeneration(0)

    val result: GenerationViewP = DPageViewHelpers.generationToViewProto(zeroGeneration)

    assert(result.incarnation.contains(zeroGeneration.incarnation.value))
    assert(result.number.contains(0L))
    assert(result.timestampStr.isDefined)
  }

  // -- getAssignmentViewProto with None --

  test("getAssignmentViewProto returns empty proto when assignment is None") {
    // Test plan: Verify that passing None for the assignment returns a default (empty)
    // AssignmentViewP with no fields set.
    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = None,
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None
    )

    assert(result.generation.isEmpty)
    assert(result.isFrozen.isEmpty)
    assert(result.resources.isEmpty)
    assert(result.slices.isEmpty)
  }

  // -- getAssignmentViewProto with assignment but no optional data --

  test("getAssignmentViewProto with assignment and no optional load data") {
    // Test plan: Verify that getAssignmentViewProto correctly converts an assignment to a proto
    // when no load maps or top keys are provided. Check that generation, isFrozen, consistency
    // mode, resources, and slices are all populated.
    val assignment: Assignment = createTestAssignment(numResources = 2, numSlices = 3)

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None
    )

    // Generation should be populated.
    assert(result.generation.isDefined)
    assertGenerationViewMatches(result.generation, assignment.generation)

    // Assignment metadata.
    assert(result.isFrozen.contains(false))
    assert(result.consistencyMode.contains(AssignmentConsistencyMode.Affinity.toString))

    // Resources should be populated with no attributed load.
    assert(result.resources.size == assignment.assignedResources.size)
    assert(result.resources.forall { r: ResourceViewP =>
      r.attributedLoad.isEmpty
    })

    // Slices should be populated with no top keys. Load may be present from primaryRateLoadOpt.
    assert(result.slices.size == assignment.sliceAssignments.size)
    assert(result.slices.forall { s: SliceViewP =>
      s.topKeys.isEmpty
    })
  }

  // -- getAssignmentViewProto with reported load per resource --

  test("getAssignmentViewProto populates attributedLoad from reportedLoadPerResourceOpt") {
    // Test plan: Verify that when reportedLoadPerResourceOpt is provided, each resource's
    // attributedLoad is set to the corresponding value from the map.
    val assignment: Assignment = createTestAssignment(numResources = 2, numSlices = 2)

    // Map each resource to a known load value.
    val loadPerResource: Map[Squid, Double] =
      assignment.assignedResources.zipWithIndex.map { entry: (Squid, Int) =>
        val (squid, i): (Squid, Int) = entry
        squid -> (i + 10.0)
      }.toMap

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = Some(loadPerResource),
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None
    )

    // Every resource should have an attributed load.
    assert(result.resources.forall { r: ResourceViewP =>
      r.attributedLoad.isDefined
    })
  }

  // -- getAssignmentViewProto with reported load per slice --

  test("getAssignmentViewProto populates slice load from reportedLoadPerSliceOpt") {
    // Test plan: Verify that when reportedLoadPerSliceOpt is provided, each slice's load is set
    // to the value from the override map rather than the recorded primary rate load.
    val assignment: Assignment = createTestAssignment(numResources = 1, numSlices = 2)

    val loadPerSlice: Map[Slice, Double] =
      assignment.sliceAssignments.map { sa: SliceAssignment =>
        (sa.slice, 99.9)
      }.toMap

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = Some(loadPerSlice),
      topKeysOpt = None
    )

    // Every slice should have load = 99.9 from the override.
    assert(result.slices.forall { s: SliceViewP =>
      s.load.contains(99.9)
    })
  }

  // -- getAssignmentViewProto with top keys --

  test("getAssignmentViewProto populates top keys within each slice range") {
    // Test plan: Verify that top keys are correctly bucketed into slices by key range. Create one
    // top key at each slice's low-inclusive boundary and verify each slice gets
    // exactly one top key.
    // The last slice uses InfinitySliceKey as its high bound, exercising that branch.
    val assignment: Assignment = createTestAssignment(numResources = 2, numSlices = 4)

    // One top key per slice, at the low-inclusive boundary.
    val topKeys: SortedMap[SliceKey, Double] = SortedMap(
      assignment.sliceAssignments.map { sa: SliceAssignment =>
        (sa.slice.lowInclusive, 7.7)
      }: _*
    )

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = Some(topKeys)
    )

    // Each slice should have at least one top key.
    assert(result.slices.forall { s: SliceViewP =>
      s.topKeys.nonEmpty
    })

    // Top key proto should have key and load.
    val firstTopKey: TopKeyViewP = result.slices.head.topKeys.head
    assert(firstTopKey.key.isDefined)
    assert(firstTopKey.load.contains(7.7))
  }

  test("getAssignmentViewProto with empty top keys map produces no top keys") {
    // Test plan: Verify that when topKeysOpt is Some but the map is empty, no top keys are
    // included in any slice.
    val assignment: Assignment = createTestAssignment(numResources = 1, numSlices = 2)

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = Some(SortedMap.empty[SliceKey, Double])
    )

    assert(result.slices.forall { s: SliceViewP =>
      s.topKeys.isEmpty
    })
  }

  // -- getAssignmentViewProto subslice annotations --

  test("getAssignmentViewProto includes subslice annotations with transfer info") {
    // Test plan: Verify that subslice annotations are included in the view proto, including
    // the continuous generation number and state transfer address when present. Do this by
    // constructing a single-slice assignment with one subslice annotation containing a state
    // transfer, then checking the view proto fields.
    val squid0: Squid = createTestSquid("r0")
    val squid1: Squid = createTestSquid("r1")
    val generation: Generation = createLooseGeneration(100)
    val slice: Slice = Slice(SliceKey.MIN, InfinitySliceKey)

    // Create a subslice annotation with a state transfer.
    val subslice: Slice = Slice(SliceKey.MIN, InfinitySliceKey)
    val transfer: Transfer = Transfer(id = 1, fromResource = squid1)
    val annotation: SubsliceAnnotation = SubsliceAnnotation(
      subslice = subslice,
      continuousGenerationNumber = 50L,
      stateTransferOpt = Some(transfer)
    )

    val sliceAssignment: SliceAssignment = createSliceAssignmentAssumingUniformLoad(
      slice = slice,
      generation = generation,
      resources = Set(squid0),
      subsliceAnnotationsByResource = Map(squid0 -> Vector(annotation))
    )

    val assignment: Assignment =
      createAssignment(generation, AssignmentConsistencyMode.Affinity, sliceAssignment)

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None
    )

    assert(result.slices.size == 1)
    val sliceView: SliceViewP = result.slices.head
    assert(sliceView.subsliceAnnotations.size == 1)

    val annView: SubsliceAnnotationViewP = sliceView.subsliceAnnotations.head
    assert(annView.resource.contains(squid0.resourceAddress.toString))
    assert(annView.continuousGenerationNumber.contains(50L))
    assert(annView.stateProviderAddress.contains(squid1.resourceAddress.toString))
  }

  // -- Resource proto fields --

  test("getAssignmentViewProto resource proto contains address, uuid, and creationTime") {
    // Test plan: Verify that each ResourceViewP contains the resource's address, UUID, and
    // creation time as string representations.
    val assignment: Assignment = createTestAssignment(numResources = 1, numSlices = 1)

    val result: AssignmentViewP = DPageViewHelpers.getAssignmentViewProto(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None
    )

    assert(result.resources.size == 1)
    val resourceView: ResourceViewP = result.resources.head
    assert(resourceView.address.isDefined)
    assert(resourceView.uuid.isDefined)
    assert(resourceView.creationTimeStr.isDefined)
  }

  /** Asserts that a [[GenerationViewP]] matches the expected [[Generation]]. */
  private def assertGenerationViewMatches(
      genViewOpt: Option[GenerationViewP],
      expected: Generation): Unit = {
    assert(genViewOpt.isDefined, "Expected generation to be present")
    val gv: GenerationViewP = genViewOpt.get
    assert(gv.incarnation.contains(expected.incarnation.value))
    assert(gv.number.contains(expected.number.value))
    assert(gv.timestampStr.contains(expected.toTime.toString))
  }

  /**
   * Creates a test assignment with the given number of resources and slices.
   * Uses Affinity consistency mode and [[GENERATION]].
   */
  private def createTestAssignment(numResources: Int, numSlices: Int): Assignment = {
    val resourceNames: Seq[String] = (0 until numResources).map { i: Int =>
      s"r$i"
    }
    val resources: Resources = createResources(resourceNames: _*)
    val proposedAsn: ProposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        numSlices = numSlices,
        resources = resources.availableResources.toIndexedSeq,
        numMaxReplicas = 1,
        rng = RNG
      )
    )
    proposedAsn.commit(isFrozen = false, AssignmentConsistencyMode.Affinity, GENERATION)
  }
}
