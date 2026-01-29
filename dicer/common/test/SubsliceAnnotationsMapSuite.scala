package com.databricks.dicer.common

import scala.util.Random

import com.databricks.dicer.common.SubsliceAnnotationsMap.SliceWithAnnotations
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.{MutableSliceMap, SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.{GapEntry, IntersectionEntry}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.testing.DatabricksTest

class SubsliceAnnotationsMapSuite extends DatabricksTest {

  private val CONSISTENCY_MODES = Seq[AssignmentConsistencyMode](
    AssignmentConsistencyMode.Affinity,
    AssignmentConsistencyMode.Strong
  )

  test("SubsliceAnnotationsMap manual") {
    // Test plan: Create an assignment with some subslice annotations. Verify that the entries from
    // `SubsliceAnnotationsMap` are as expected.
    val assignment: Assignment = createAssignment(
      generation = 50,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Balin") @@ 10 -> Seq("pod1"),
      ("Balin" -- "Kili") @@ 20 -> Seq("pod1") | Map(
        "pod1" -> Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      ),
      ("Kili" -- "Nori") @@ 30 -> Seq("pod1") | Map(
        "pod1" -> Seq(SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None))
      ),
      ("Nori" -- ∞) @@ 50 -> Seq("pod2")
    )
    val map = SubsliceAnnotationsMap(assignment, createTestSquid("pod1"))
    // Convert `map.entries` to a sequence of (slice, generation number).
    val subsliceAnnotations: Seq[(Slice, Option[UnixTimeVersion])] = map.entries.map {
      case GapEntry.Some(entry: SliceWithAnnotations) =>
        (entry.slice, Some(entry.continuousGenerationNumber))
      case GapEntry.Gap(slice: Slice) =>
        (slice, None)
    }
    assert(
      subsliceAnnotations == Vector(
        "" -- "Balin" | 10,
        "Balin" -- "Bifur" | 20,
        "Bifur" -- "Bofur" | 10,
        "Bofur" -- "Dwalin" | 20,
        "Dwalin" -- "Fili" | 15,
        "Fili" -- "Kili" | 20,
        "Kili" -- "Nori" | 10,
        "Nori" -- ∞ | None
      )
    )
  }

  test("SubsliceAnnotationsMap for resource") {
    // Test plan: Create an assignment and verify that `SubsliceAnnotationsMap` calls return the
    // Slice generation for only assigned resources. Tests consistency mode variations.
    for (consistencyMode <- CONSISTENCY_MODES) {
      val assignment: Assignment = createAssignment(
        generation = 42 ## 67,
        consistencyMode,
        ("" -- fp("Dori")) @@ (42 ## 34) -> Seq("Pod2"),
        (fp("Dori") -- fp("Fili")) @@ (42 ## 24) -> Seq("Pod0"),
        (fp("Fili") -- fp("Kili")) @@ (42 ## 45) -> Seq("Pod1"),
        (fp("Kili") -- fp("Nori")) @@ (42 ## 67) -> Seq("Pod2"),
        (fp("Nori") -- ∞) @@ (42 ## 34) -> Seq("Pod3")
      )

      // Test data maps keys to expected assigned resources and Slice generations.
      val testData = Seq[(SliceKey, String, Generation)](
        (SliceKey.MIN, "Pod2", 42 ## 34),
        (fp("Dori"), "Pod0", 42 ## 24),
        (fp("Fili"), "Pod1", 42 ## 45),
        (fp("Kili"), "Pod2", 42 ## 67),
        (fp("Nori"), "Pod3", 42 ## 34),
        (fp("Ori"), "Pod3", 42 ## 34)
      )
      // In addition to assigned resources, produce variant SQUIDs with the same address to test
      // behavior that needs to be different depending on the consistency mode.
      val altIncarnationResources = Seq[Squid](
        createTestSquid("Pod0", salt = "'"),
        createTestSquid("Pod1", salt = "'"),
        createTestSquid("Pod2", salt = "'"),
        createTestSquid("Pod3", salt = "'")
      )
      for (tuple <- testData) {
        val (key, assignedResourceUri, sliceGeneration): (SliceKey, String, Generation) = tuple
        val assignedResource: Squid = createTestSquid(assignedResourceUri)
        assert(assignment.isAssignedKey(key, assignedResource))
        for (resource: Squid <- assignment.assignedResources ++ altIncarnationResources) {
          // If the SQUID of the resource we're looking up is the same as the assigned resource
          // SQUID, we should get the Slice generation. In the Affinity consistency mode,
          // only the addresses need to match.
          val expectedGenerationNumber: Option[UnixTimeVersion] =
            if (resource == assignedResource ||
              (consistencyMode == AssignmentConsistencyMode.Affinity &&
              resource.resourceAddress == assignedResource.resourceAddress)) {
              Some(sliceGeneration.number)
            } else {
              None
            }
          val subsliceAnnotationsMap =
            SubsliceAnnotationsMap(assignment, resource)
          assert(
            subsliceAnnotationsMap.continuouslyAssignedGeneration(key) == expectedGenerationNumber
          )
        }
      }
    }
  }

  test("SubsliceAnnotationsMap randomized") {
    // Test plan: Verify that `SubsliceAnnotationsMap()` generation numbers work correctly with
    // randomly generated assignments. Do this by first creating a random assignment. Then, in each
    // iteration, create a new assignment with random boundaries, which, with high probability,
    // keeps the same resource as the previous assignment, for a random point in each slice. Verify
    // the resulting `SubsliceAnnotationsMap` against a reference MutableSliceMap.

    val numRuns = 10
    val numResources = 10
    val numSlices = 20
    val numAssignmentChanges = 5
    val numMaxReplicas = 5

    val seed: Long = Random.nextLong()
    logger.info(s"Using seed $seed")
    val rng = new Random(seed)

    for (_ <- 0 until numRuns) {
      val resources: IndexedSeq[Squid] = (0 until numResources).map(i => createTestSquid(s"pod$i"))
      val pod0 = resources.head
      // Map (sub)slice to the earliest generation number that it was assigned to pod0, or None if
      // it is not currently assigned to pod0.
      val contGenOnPod0 = new MutableSliceMap[Option[UnixTimeVersion]]
      // Merge function for `contGenOnPod0`.
      val mergeFn: (Option[UnixTimeVersion], Option[UnixTimeVersion]) => Option[UnixTimeVersion] = {
        case (Some(oldGen: UnixTimeVersion), Some(newGen: UnixTimeVersion)) =>
          // Keep the old generation number as the slice is continuously assigned to pod0.
          Some(oldGen)
        case (None, Some(newGen: UnixTimeVersion)) =>
          // Record the new generation number for newly assigned slice replicas.
          Some(newGen)
        case _ =>
          // Slice replica not assigned.
          None
      }
      var predecessorOpt: Option[Assignment] = None

      for (i: Int <- 0 until numAssignmentChanges) {
        val proposal: SliceMap[ProposedSliceAssignment] = predecessorOpt match {
          case Some(predecessor) =>
            createBiasedProposal(numSlices, predecessor, resources, numMaxReplicas = 1, rng)
          case None => createRandomProposal(numSlices, resources, numMaxReplicas, rng)
        }
        val assignment: Assignment =
          ProposedAssignment(predecessorOpt, proposal)
            .commit(
              isFrozen = false,
              AssignmentConsistencyMode.Affinity,
              i * 10 + 1
            )

        for (asn <- assignment.sliceMap.entries) {
          if (asn.resources.contains(pod0)) {
            contGenOnPod0.merge(asn.slice, Some(asn.generation.number), mergeFn)
          } else {
            contGenOnPod0.merge(asn.slice, value = None, mergeFn)
          }
        }
        predecessorOpt = Some(assignment)
      }

      val finalAssignment: Assignment = predecessorOpt.get
      val subsliceAnnotationsMap =
        SubsliceAnnotationsMap(finalAssignment, pod0)
      val expectedMap: Vector[(Slice, Option[UnixTimeVersion])] = {
        // MutableSliceMap coalesces adjacent entries, but getSubsliceAnnotationsMapForResource does
        // not make any guarantees on whether or not it coalesces. So intersect the MutableSliceMap
        // with the result of getSubsliceAnnotationsMapForResource and simply construct a new entry
        // for each intersecting portion.
        type ContAsnEntry = (Slice, Option[UnixTimeVersion])
        val convertedMap =
          new SliceMap[ContAsnEntry](
            contGenOnPod0.iterator.toVector,
            (entry: ContAsnEntry) => entry._1
          )
        val intersectionEntries
            : Vector[IntersectionEntry[ContAsnEntry, GapEntry[SliceWithAnnotations]]] =
          SliceMap.intersectSlices(convertedMap, subsliceAnnotationsMap.sliceMap).entries
        // Create a resulting (subslice, continuous generation number) vector.
        intersectionEntries.map { entry =>
          val leftVal: Option[UnixTimeVersion] = entry.leftEntry._2
          (entry.slice, leftVal)
        }
      }

      val subsliceAnnotations: Seq[(Slice, Option[UnixTimeVersion])] =
        subsliceAnnotationsMap.entries.map {
          case GapEntry.Some(entry: SliceWithAnnotations) =>
            (entry.slice, Some(entry.continuousGenerationNumber))
          case GapEntry.Gap(slice: Slice) =>
            (slice, None)
        }
      assert(subsliceAnnotations == expectedMap)
    }
  }

  test("SubsliceAnnotationsMap creates correct state providers") {
    // Test plan: Verify that `SubsliceAnnotationsMap()` works correctly for resources with and
    // without any state transfers. Do this by creating a manual assignment with everything assigned
    // to `resource1`, then transferring part of it to `resource2`. Verify that the result of
    // `SubsliceAnnotationsMap` for each of the resources is as expected.
    val resource1: Squid = createTestSquid("resource1")
    val resource2: Squid = createTestSquid("resource2")
    val assignment1: Assignment =
      createAssignment(
        generation = 1,
        AssignmentConsistencyMode.Affinity,
        ("" -- ∞) @@ 1 -> Seq(resource1)
      )

    // Transfer ["", "foo") from resource1 to resource2.
    val proposal2 = createProposal(
      ("" -- "foo") -> Seq(resource2),
      ("foo" -- ∞) -> Seq(resource1)
    )
    val assignment2: Assignment =
      ProposedAssignment(Some(assignment1), proposal2).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        generation = 32
      )
    assert(TestSliceUtils.hasStateTransfers(assignment2))

    val resource1Map = SubsliceAnnotationsMap(assignment2, resource1)
    assert(
      resource1Map.entries == Vector(
        SliceMap.GapEntry.Gap("" -- "foo"),
        SliceMap.GapEntry.Some(SliceWithAnnotations("foo" -- ∞, 1, None))
      )
    )

    val resource2Map = SubsliceAnnotationsMap(assignment2, resource2)
    assert(
      resource2Map.entries == Vector(
        SliceMap.GapEntry.Some(SliceWithAnnotations("" -- "foo", 32, Some(resource1))),
        SliceMap.GapEntry.Gap("foo" -- ∞)
      )
    )
  }
}
