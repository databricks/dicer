package com.databricks.dicer.assigner.algorithm

import scala.concurrent.duration._
import scala.collection.immutable.SortedMap

import com.databricks.caching.util.FakeTypedClock
import com.databricks.dicer.assigner.config.ChurnConfig
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  SliceWithResources,
  SubsliceAnnotation,
  TestSliceUtils
}
import com.databricks.dicer.external.SliceKey
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

class MutableAssignmentSuite extends DatabricksTest {

  /**
   * Returns the [[MutableAssignment.ResourceState]] instance inside `mutableAssignment` for
   * `resource`.
   */
  @throws[NoSuchElementException]("If the desired ResourceState doesn't exist.")
  private def getResourceState(
      mutableAssignment: MutableAssignment,
      resource: Squid): mutableAssignment.ResourceState = {
    mutableAssignment
      .resourceStatesIteratorFromColdest()
      .find((_: mutableAssignment.ResourceState).resource == resource)
      .get
  }

  test("A MutableAssignment can be correctly created from an immutable Assignment") {
    // Test plan: Verify that a MutableAssignment can be correctly created from an existing
    // immutable Assignment and LoadMap, and can be correctly converted to immutable
    // SliceWithResources. Verify this by creating a MutableAssignment from an existing Assignment
    // and LoadMap, inspecting and verifying its MutableSliceAssignments have correct Slices and
    // load, then converting the MutableAssignment into a SliceMap of immutable SliceWithResources
    // and verifying the SliceWithResources are as expected.

    val clock = new FakeTypedClock
    val generation: Generation = TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- 10) @@ generation -> Seq("resource0"),
      (10 -- 20) @@ generation -> Seq("resource1"),
      (20 -- ∞) @@ generation -> Seq("resource0", "resource1", "resource2")
    )
    // The LoadMap has different load distribution than the predecessor assignment above.
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 10),
        LoadMap.Entry(10 -- 20, 20),
        LoadMap.Entry(20 -- ∞, 30)
      )
      .build()
    val resources: Resources =
      createResources("resource0", "resource1", "resource2")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()

    // The MutableAssignment should contain the same number of MutableSliceAssignments as the
    // predecessor Assignment's SliceAssignment.
    val mutableSliceAssignments: Vector[mutableAssignment.MutableSliceAssignment] =
      mutableAssignment.sliceAssignmentsIterator.toVector
    assert(mutableSliceAssignments.size == 3)

    // Each MutableSliceAssignment should have correct Slice, number of replicas, and load. The load
    // should be in accordance with the LoadMap, rather than the load in the predecessor Assignment.

    val mutableSliceAssignment0: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments(0)
    assert(mutableSliceAssignment0.slice == "" -- 10)
    assert(mutableSliceAssignment0.currentNumReplicas == 1)
    assert(mutableSliceAssignment0.rawLoad == 10)
    assert(mutableSliceAssignment0.rawLoadPerReplica == 10)

    val mutableSliceAssignment1: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments(1)
    assert(mutableSliceAssignment1.slice == 10 -- 20)
    assert(mutableSliceAssignment1.currentNumReplicas == 1)
    assert(mutableSliceAssignment1.rawLoad == 20)
    assert(mutableSliceAssignment1.rawLoadPerReplica == 20)

    val mutableSliceAssignment2: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments(2)
    assert(mutableSliceAssignment2.slice == 20 -- ∞)
    assert(mutableSliceAssignment2.currentNumReplicas == 3)
    assert(mutableSliceAssignment2.rawLoad == 30)
    assert(mutableSliceAssignment2.rawLoadPerReplica == 10)

    // Without any mutation to the MutableAssignment, convert it to immutable SliceWithResources.
    // They should contain the same Slices and assigned resources as the predecessor Assignment.
    assert(
      mutableAssignment.toSliceAssignments == new SliceMap(
        Vector(
          SliceWithResources("" -- 10, Set("resource0")),
          SliceWithResources(10 -- 20, Set("resource1")),
          SliceWithResources(20 -- ∞, Set("resource0", "resource1", "resource2"))
        ),
        (_: SliceWithResources).slice
      )
    )
  }

  test("MutableSliceAssignment.{de}allocateResource and reassignResource work correctly") {
    // Test plan: Verify that:
    // - allocateResource adds a new replica to the slice (incrementing replica count)
    // - deallocateResource removes a replica from the slice (decrementing replica count)
    // - reassignResource moves a replica from one resource to another (keeping replica count same)
    // Also verify that load per replica and total load on resources update correctly.

    val clock = new FakeTypedClock()
    val generation: Generation =
      TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- 10) @@ generation -> Seq("resource0", "resource1"),
      (10 -- ∞) @@ generation -> Seq("resource2")
    )
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 20), // 10 per replica with 2 replicas
        LoadMap.Entry(10 -- ∞, 30) // 30 per replica with 1 replica
      )
      .build()
    val resources: Resources =
      createResources("resource0", "resource1", "resource2", "resource3")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()

    val mutableSliceAssignments: Vector[mutableAssignment.MutableSliceAssignment] =
      mutableAssignment.sliceAssignmentsIterator.toVector
    assert(mutableSliceAssignments.size == 2)
    val slice0: mutableAssignment.MutableSliceAssignment = mutableSliceAssignments(0)
    val slice1: mutableAssignment.MutableSliceAssignment = mutableSliceAssignments(1)

    val rs0: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource0")
    val rs1: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource1")
    val rs2: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource2")
    val rs3: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource3")

    // Initial state verification
    // slice0: load=20, 2 replicas (r0, r1) -> 10 per replica
    // slice1: load=30, 1 replica (r2) -> 30 per replica
    assert(slice0.currentNumReplicas == 2)
    assert(slice0.rawLoad == 20)
    assert(slice0.rawLoadPerReplica == 10)
    assert(slice1.currentNumReplicas == 1)
    assert(slice1.rawLoad == 30)
    assert(slice1.rawLoadPerReplica == 30)
    assert(rs0.getTotalLoad == 10)
    assert(rs1.getTotalLoad == 10)
    assert(rs2.getTotalLoad == 30)
    assert(rs3.getTotalLoad == 0)

    // Test allocateResource: adds a new replica, incrementing replica count
    // Add resource3 to slice0: now 3 replicas, 20/3 ≈ 6.67 per replica
    slice0.allocateResource(rs3)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 3)
    assert(slice0.rawLoad == 20)
    assert(slice0.rawLoadPerReplica == 20.0 / 3)
    assert(slice0.isAllocatedToResource("resource0"))
    assert(slice0.isAllocatedToResource("resource1"))
    assert(slice0.isAllocatedToResource("resource3"))
    assert(rs0.getTotalLoad == 20.0 / 3)
    assert(rs1.getTotalLoad == 20.0 / 3)
    assert(rs3.getTotalLoad == 20.0 / 3)

    // Test deallocateResource: removes a replica, decrementing replica count
    // Remove resource1 from slice0: now 2 replicas, 20/2 = 10 per replica
    slice0.deallocateResource(rs1)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 2)
    assert(slice0.rawLoad == 20)
    assert(slice0.rawLoadPerReplica == 10)
    assert(slice0.isAllocatedToResource("resource0"))
    assert(!slice0.isAllocatedToResource("resource1"))
    assert(slice0.isAllocatedToResource("resource3"))
    assert(rs0.getTotalLoad == 10)
    assert(rs1.getTotalLoad == 0)
    assert(rs3.getTotalLoad == 10)

    // Test reassignResource: moves replica without changing count
    // Move slice0 from resource0 to resource1: still 2 replicas, same load per replica
    slice0.reassignResource(rs0, rs1)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 2)
    assert(slice0.rawLoadPerReplica == 10)
    assert(!slice0.isAllocatedToResource("resource0"))
    assert(slice0.isAllocatedToResource("resource1"))
    assert(slice0.isAllocatedToResource("resource3"))
    assert(rs0.getTotalLoad == 0)
    assert(rs1.getTotalLoad == 10)
    assert(rs3.getTotalLoad == 10)

    // Test allocateResource on slice1: add resource0
    // slice1: now 2 replicas, 30/2 = 15 per replica
    slice1.allocateResource(rs0)
    mutableAssignment.forTest.checkInvariants()
    assert(slice1.currentNumReplicas == 2)
    assert(slice1.rawLoadPerReplica == 15)
    assert(slice1.isAllocatedToResource("resource0"))
    assert(slice1.isAllocatedToResource("resource2"))
    assert(rs0.getTotalLoad == 15)
    assert(rs2.getTotalLoad == 15)

    // Verify final assignment
    assert(
      mutableAssignment.toSliceAssignments == new SliceMap(
        Vector(
          SliceWithResources("" -- 10, Set("resource1", "resource3")),
          SliceWithResources(10 -- ∞, Set("resource0", "resource2"))
        ),
        (_: SliceWithResources).slice
      )
    )
  }

  test("MutableAssignment correctly returns coldest resource (iterator)") {
    // Test plan: Verify that MutableAssignment.resourceStatesIteratorFromColdest() iterates
    // resources from ones with the lowest total load to ones with the highest. Verify this by
    // creating a MutableAssignment, reassigning resources to/from Slices in various ways to change
    // their total load, and after each change, verifying
    // MutableAssignment.resourceStatesIteratorFromColdest() returns expected results.

    val clock = new FakeTypedClock()
    val generation: Generation =
      TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- 10) @@ generation -> Seq("resource0", "resource1"),
      (10 -- ∞) @@ generation -> Seq("resource1", "resource2")
    )
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 10),
        LoadMap.Entry(10 -- ∞, 100)
      )
      .build()
    val resources: Resources =
      createResources("resource0", "resource1", "resource2")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()

    val mutableSliceAssignments: Vector[mutableAssignment.MutableSliceAssignment] =
      mutableAssignment.sliceAssignmentsIterator.toVector
    val mutableSliceAssignment0: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments(0)
    val mutableSliceAssignment1: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments(1)

    val resourceState0: mutableAssignment.ResourceState =
      getResourceState(mutableAssignment, "resource0")
    val resourceState1: mutableAssignment.ResourceState =
      getResourceState(mutableAssignment, "resource1")
    val resourceState2: mutableAssignment.ResourceState =
      getResourceState(mutableAssignment, "resource2")

    // Without any mutation to the predecessor assignment, check the results of
    // coldestResourceState() and resourceStatesIteratorFromColdest().
    // slice0: load=10, 2 replicas -> 5 per replica
    // slice1: load=100, 2 replicas -> 50 per replica
    // resource0: slice0 -> 5, resource1: slice0+slice1 -> 55, resource2: slice1 -> 50
    assert(resourceState0.getTotalLoad == 5)
    assert(resourceState1.getTotalLoad == 55)
    assert(resourceState2.getTotalLoad == 50)
    assert(mutableAssignment.resourceStatesIteratorFromColdest().next() == resourceState0)
    assert(
      mutableAssignment
        .resourceStatesIteratorFromColdest()
        .toVector == Vector(resourceState0, resourceState2, resourceState1)
    )

    // Reassign slice1 from resource2 to resource0. This moves load without changing replica count.
    // resource0: slice0 + slice1 -> 5 + 50 = 55
    // resource1: slice0 + slice1 -> 5 + 50 = 55
    // resource2: nothing -> 0
    mutableSliceAssignment1.reassignResource(resourceState2, resourceState0)
    assert(resourceState0.getTotalLoad == 55)
    assert(resourceState1.getTotalLoad == 55)
    assert(resourceState2.getTotalLoad == 0)
    assert(mutableAssignment.resourceStatesIteratorFromColdest().next() == resourceState2)
    assert(
      mutableAssignment
        .resourceStatesIteratorFromColdest()
        .toVector == Vector(resourceState2, resourceState0, resourceState1)
    )

    // Reassign slice0 from resource1 to resource2. Now:
    // resource0: slice0 + slice1 -> 5 + 50 = 55
    // resource1: slice1 -> 50
    // resource2: slice0 -> 5
    mutableSliceAssignment0.reassignResource(resourceState1, resourceState2)
    assert(resourceState0.getTotalLoad == 55)
    assert(resourceState1.getTotalLoad == 50)
    assert(resourceState2.getTotalLoad == 5)
    assert(mutableAssignment.resourceStatesIteratorFromColdest().next() == resourceState2)
    assert(
      mutableAssignment
        .resourceStatesIteratorFromColdest()
        .toVector == Vector(resourceState2, resourceState1, resourceState0)
    )

    // Reassign slice1 from resource0 to resource2. Now:
    // resource0: slice0 -> 5
    // resource1: slice1 -> 50
    // resource2: slice0 + slice1 -> 5 + 50 = 55
    mutableSliceAssignment1.reassignResource(resourceState0, resourceState2)
    assert(resourceState0.getTotalLoad == 5)
    assert(resourceState1.getTotalLoad == 50)
    assert(resourceState2.getTotalLoad == 55)
    assert(mutableAssignment.resourceStatesIteratorFromColdest().next() == resourceState0)
    assert(
      mutableAssignment
        .resourceStatesIteratorFromColdest()
        .toVector == Vector(resourceState0, resourceState1, resourceState2)
    )

    mutableAssignment.forTest.checkInvariants()
  }

  test("Unhealthy resources are returned and treated correctly by MutableAssignment") {
    // Test plan: Verify that MutableAssignment.getUnhealthyResourceStates() correctly returns the
    // set of unhealthy resources, and MutableAssignment.resourceStatesIteratorFromColdest() doesn't
    // contain the unhealthy resources.

    val clock = new FakeTypedClock
    val generation: Generation = TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- ∞) @@ generation -> Seq(
        "resource0",
        "resource1",
        "resourceUnhealthy0",
        "resourceUnhealthy1"
      )
    )
    val resources: Resources = createResources("resource0", "resource1")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      LoadMap.UNIFORM_LOAD_MAP,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()

    assert(
      mutableAssignment.getUnhealthyResourceStates
        .map((_: mutableAssignment.ResourceState).resource.resourceAddress.toString)
        .toSet == Set("resourceUnhealthy0", "resourceUnhealthy1")
    )

    assert(
      mutableAssignment
        .resourceStatesIteratorFromColdest()
        .map((_: mutableAssignment.ResourceState).resource.resourceAddress.toString)
        .toSet == Set("resource0", "resource1")
    )
  }

  test("MutableSliceAssignment.adjustReplicas() works correctly") {
    // Test plan: Verify that MutableSliceAssignment.adjustReplicas()
    // - when scaling DOWN: deallocates from hottest resources first (sorted by getTotalLoad)
    // - when scaling UP: allocates to coldest available resources (sorted by getTotalLoad)
    // - updates the per-slice-replica load based on the new number of replicas
    // - maintains the load on allocated resources correctly

    val clock = new FakeTypedClock()
    val generation: Generation =
      TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      // slice0 has 3 replicas on resource0, resource1, resource2
      ("" -- 10) @@ generation -> Seq("resource0", "resource1", "resource2"),
      // Other slices to give resources different base loads
      (10 -- 20) @@ generation -> Seq("resource0"), // +10 load on resource0
      (20 -- 30) @@ generation -> Seq("resource1"), // +20 load on resource1
      (30 -- ∞) @@ generation -> Seq("resource2") // +30 load on resource2
    )
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 30), // 10 per replica with 3 replicas
        LoadMap.Entry(10 -- 20, 10),
        LoadMap.Entry(20 -- 30, 20),
        LoadMap.Entry(30 -- ∞, 30)
      )
      .build()
    val resources: Resources =
      createResources("resource0", "resource1", "resource2", "resource3")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()

    val rs0: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource0")
    val rs1: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource1")
    val rs2: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource2")
    val rs3: mutableAssignment.ResourceState = getResourceState(mutableAssignment, "resource3")

    // Initial state:
    // slice0: load=30, 3 replicas -> 10 per replica
    // resource0: slice0(10) + slice1(10) = 20
    // resource1: slice0(10) + slice2(20) = 30
    // resource2: slice0(10) + slice3(30) = 40
    // resource3: 0 (no slices)
    assert(rs0.getTotalLoad == 20)
    assert(rs1.getTotalLoad == 30)
    assert(rs2.getTotalLoad == 40)
    assert(rs3.getTotalLoad == 0)

    val slice0: mutableAssignment.MutableSliceAssignment =
      mutableAssignment.sliceAssignmentsIterator.next()
    assert(slice0.currentNumReplicas == 3)
    assert(slice0.rawLoadPerReplica == 10)
    assert(slice0.isAllocatedToResource("resource0"))
    assert(slice0.isAllocatedToResource("resource1"))
    assert(slice0.isAllocatedToResource("resource2"))

    // Test scaling DOWN from 3 to 2: should evict the hottest resource (resource2 with load 40)
    slice0.adjustReplicas(newNumReplicas = 2)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 2)
    assert(slice0.rawLoadPerReplica == 15) // 30 / 2
    // resource2 (hottest) should be evicted
    assert(slice0.isAllocatedToResource("resource0"))
    assert(slice0.isAllocatedToResource("resource1"))
    assert(!slice0.isAllocatedToResource("resource2"))
    // Updated loads:
    // resource0: slice0(15) + slice1(10) = 25
    // resource1: slice0(15) + slice2(20) = 35
    // resource2: slice3(30) = 30 (no longer has slice0)
    assert(rs0.getTotalLoad == 25)
    assert(rs1.getTotalLoad == 35)
    assert(rs2.getTotalLoad == 30)

    // Test scaling DOWN from 2 to 1: should evict resource1 (now hottest with slice0)
    slice0.adjustReplicas(newNumReplicas = 1)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 1)
    assert(slice0.rawLoadPerReplica == 30) // 30 / 1
    // resource1 (hottest among slice0's resources) should be evicted
    assert(slice0.isAllocatedToResource("resource0"))
    assert(!slice0.isAllocatedToResource("resource1"))
    // Updated loads:
    // resource0: slice0(30) + slice1(10) = 40
    // resource1: slice2(20) = 20 (no longer has slice0)
    assert(rs0.getTotalLoad == 40)
    assert(rs1.getTotalLoad == 20)

    // Test scaling UP from 1 to 3: should allocate to coldest available resources
    // Current order by load: rs3(0) < rs1(20) < rs2(30) < rs0(40)
    // rs0 already has slice0, so allocate to rs3 and rs1
    slice0.adjustReplicas(newNumReplicas = 3)
    mutableAssignment.forTest.checkInvariants()
    assert(slice0.currentNumReplicas == 3)
    assert(slice0.rawLoadPerReplica == 10) // 30 / 3
    // Should have resource0 (original) plus resource3 and resource1 (coldest available)
    assert(slice0.isAllocatedToResource("resource0"))
    assert(slice0.isAllocatedToResource("resource1"))
    assert(!slice0.isAllocatedToResource("resource2"))
    assert(slice0.isAllocatedToResource("resource3"))
    // Updated loads:
    // resource0: slice0(10) + slice1(10) = 20
    // resource1: slice0(10) + slice2(20) = 30
    // resource3: slice0(10) = 10
    assert(rs0.getTotalLoad == 20)
    assert(rs1.getTotalLoad == 30)
    assert(rs2.getTotalLoad == 30)
    assert(rs3.getTotalLoad == 10)

    // Verify final assignment for slice0
    assert(
      mutableAssignment.toSliceAssignments.entries.head == SliceWithResources(
        "" -- 10,
        Set("resource0", "resource1", "resource3")
      )
    )
  }

  test("MutableSliceAssignment.mergeWithSuccessor() works correctly") {
    // Test plan: Verify that MutableSliceAssignment.mergeWithSuccessor() creates a merged Slice
    // with desired number of replicas, chooses resources to achieve minimized churn (breaking ties
    // by choosing the least loaded resources), and doesn't choose unhealthy resources. In addition,
    // verify that correct churn penalties are applied to the resources after mering.

    val clock = new FakeTypedClock()
    val generationNow: Generation =
      TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val generation1MinuteAgo: Generation =
      TestSliceUtils.createLooseGeneration(generationNow.number.value - 1.minute.toMillis)
    val generation2MinutesAgo: Generation =
      TestSliceUtils.createLooseGeneration(generationNow.number.value - 2.minute.toMillis)
    val predecessor: Assignment = createAssignment(
      generationNow,
      AssignmentConsistencyMode.Affinity,
      // ["", 10) and [10, 20) are the slices to be merged.
      ("" -- 10) @@ generationNow -> Seq("resource0", "resource2", "resourceUnhealthy") | Map(
        // resource0 has no churn penalty on ["", 10).
        "resource0" -> Vector(
          SubsliceAnnotation("" -- 10, generation2MinutesAgo.number, stateTransferOpt = None)
        ),
        // resource2 has no churn penalty on ["", 10).
        "resource2" -> Vector(
          SubsliceAnnotation("" -- 10, generation2MinutesAgo.number, stateTransferOpt = None)
        )
      ),
      (10 -- 20) @@ generationNow -> Seq("resource1", "resource2", "resourceUnhealthy") | Map(
        // resource1 has no churn penalty on [10, 20).
        "resource1" -> Vector(
          SubsliceAnnotation(10 -- 20, generation2MinutesAgo.number, stateTransferOpt = None)
        ),
        // resource2 has half churn penalty on [10, 20).
        "resource2" -> Vector(
          SubsliceAnnotation(10 -- 20, generation1MinuteAgo.number, stateTransferOpt = None)
        )
      ),
      (20 -- ∞) @@ generationNow -> Seq("resource1")
    )
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("" -- 10, 30),
        LoadMap.Entry(10 -- 20, 30),
        LoadMap.Entry(20 -- ∞, 100)
      )
      .build()
    val resources: Resources =
      createResources("resource0", "resource1", "resource2")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      ChurnConfig.DEFAULT
    )
    mutableAssignment.forTest.checkInvariants()

    mutableAssignment.sliceAssignmentsIterator.next().mergeWithSuccessor(numReplicas = 2)

    val mutableSliceAssignments: Vector[mutableAssignment.MutableSliceAssignment] =
      mutableAssignment.sliceAssignmentsIterator.toVector
    assert(mutableSliceAssignments.size == 2)
    val mutableSliceAssignment0: mutableAssignment.MutableSliceAssignment =
      mutableSliceAssignments.head

    // Verify the slice, raw load, and number of replicas after merge.
    assert(mutableSliceAssignment0.slice == "" -- 20)
    assert(mutableSliceAssignment0.rawLoad == 60)
    assert(mutableSliceAssignment0.currentNumReplicas == 2)

    // Verify the resources after merge: resource2 shares the most common load on ["", 10) and
    // [10, 20), so it is chosen as one of the resources after merge. resourceUnhealthy shares the
    // same amount of common load as resource2, but it is not chosen because it is not healthy.
    // Resource0 and resource1 share the same amount of common load, but resource0 has less total
    // load than resource1, so resource0 is chosen.
    assert(
      mutableAssignment.toSliceAssignments.entries.head == SliceWithResources(
        "" -- 20,
        Set("resource2", "resource0")
      )
    )
  }

  test(
    "Split can isolate hot key, and doesn't change the number of replicas and assigned resources"
  ) {
    // Test plan: Verify that `MutableSliceAssignment.split()` can isolate a hot key within a Slice,
    // and doesn't change the number of replicas and allocated resources. Verify this by creating a
    // MutableAssignment with a LoadMap where most of the load is concentrated in a single SliceKey,
    // splitting the Slice twice, and verify that the result contains just the hot key, i.e.
    // the slice `[hotKey, hotKey.successor())`. Verify that the allocated resources are not changed
    // by the split, and that the Slice cannot be split further. Note that this is mostly testing
    // the behavior of `LoadMap.getSplit()` but in the way that it is exercised in Algorithm.scala.
    val clock = new FakeTypedClock
    val generation = TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      "".andGreater @@ generation -> Seq("pod0", "pod1")
    )
    val hotKey: SliceKey = "a"
    val hotKeyLoad: Double = 10.0
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        LoadMap.Entry("".andGreater, 1.0),
        SortedMap(hotKey -> hotKeyLoad)
      )
      .build()
    val resources: Resources = createResources("pod0", "pod1")
    val mutableAssignment =
      new MutableAssignment(
        clock.instant(),
        predecessor.sliceMap,
        resources,
        loadMap,
        ChurnConfig.DEFAULT
      )

    val resourceState0: mutableAssignment.ResourceState =
      getResourceState(mutableAssignment, "pod0")
    val resourceState1: mutableAssignment.ResourceState =
      getResourceState(mutableAssignment, "pod1")

    // Get the one MutableSliceAssignment in the MutableAssignment and split it.
    val sliceAsns: Seq[mutableAssignment.MutableSliceAssignment] =
      mutableAssignment.sliceAssignmentsIterator.toSeq
    assert(sliceAsns.size == 1)
    val sliceAsn: mutableAssignment.MutableSliceAssignment = sliceAsns.head
    val split: Option[
      (mutableAssignment.MutableSliceAssignment, mutableAssignment.MutableSliceAssignment)] =
      sliceAsn.split()
    assert(split.isDefined)

    /** Return the `MutableSliceAssignment` with the higher `rawLoad`. */
    def getHigherLoadAsn(
        tuple: (mutableAssignment.MutableSliceAssignment, mutableAssignment.MutableSliceAssignment))
        : mutableAssignment.MutableSliceAssignment = {
      val (left, right) = tuple
      if (left.rawLoad >= right.rawLoad) {
        left
      } else {
        right
      }
    }

    // Slice with higher load should contain the hot key. Since the load map contains some
    // background load over the Slice, not just from the hot key, the overall Slice's load should be
    // strictly greater than the hot key's load.
    val higherLoadAsn: mutableAssignment.MutableSliceAssignment = getHigherLoadAsn(split.get)
    assert(higherLoadAsn.slice.contains(hotKey))
    assert(higherLoadAsn.rawLoad > hotKeyLoad)
    // Number of replicas is not changed.
    assert(higherLoadAsn.currentNumReplicas == 2)
    // Allocated resources are not changed.
    assert(resourceState0.isAssigned(higherLoadAsn))
    assert(resourceState1.isAssigned(higherLoadAsn))
    assert(higherLoadAsn.isAllocatedToResource("pod0"))
    assert(higherLoadAsn.isAllocatedToResource("pod1"))

    // We can split the higher load `MutableSliceAssignment` again and it should now isolate the hot
    // key.
    val split2: Option[
      (mutableAssignment.MutableSliceAssignment, mutableAssignment.MutableSliceAssignment)] =
      higherLoadAsn.split()
    assert(split2.isDefined)
    val higherLoadAsn2: mutableAssignment.MutableSliceAssignment = getHigherLoadAsn(split2.get)
    assert(higherLoadAsn2.slice == (hotKey -- hotKey.successor()))
    assert(higherLoadAsn2.rawLoad == hotKeyLoad)
    // Number of replicas is not changed.
    assert(higherLoadAsn2.currentNumReplicas == 2)
    // Allocated resources are not changed.
    assert(resourceState0.isAssigned(higherLoadAsn2))
    assert(resourceState1.isAssigned(higherLoadAsn2))
    assert(higherLoadAsn2.isAllocatedToResource("pod0"))
    assert(higherLoadAsn2.isAllocatedToResource("pod1"))

    // Since the Slice consists of a single key, it cannot be split further.
    assert(higherLoadAsn2.split().isEmpty)
  }

  test("Returns correct total number of slice replicas") {
    // Test plan: Verify that the number of total slice replicas in MutableAssignment can be
    // maintained and queried correctly through split, merge, adjustReplicas, allocate, and
    // deallocate operations.

    val clock = new FakeTypedClock
    val generation: Generation = TestSliceUtils.createLooseGeneration(clock.instant().toEpochMilli)
    val predecessor: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- 10) @@ generation -> Seq("resource0"),
      (10 -- 20) @@ generation -> Seq("resource1", "resource2"),
      (20 -- ∞) @@ generation -> Seq("resource0", "resource1", "resource2")
    )
    val resources: Resources =
      createResources("resource0", "resource1", "resource2", "resource3")
    val mutableAssignment = new MutableAssignment(
      clock.instant(),
      predecessor.sliceMap,
      resources,
      LoadMap.UNIFORM_LOAD_MAP,
      ChurnConfig.ZERO_PENALTY
    )
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 6)

    // Split increases total number of slice replicas (each child inherits parent's replicas).
    mutableAssignment.sliceAssignmentsIterator.toVector(0).split()
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 7)
    mutableAssignment.sliceAssignmentsIterator.toVector.last.split()
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 10)

    // Merge decreases total number of slice replicas.
    mutableAssignment.sliceAssignmentsIterator.toVector(0).mergeWithSuccessor(numReplicas = 1)
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 9)
    mutableAssignment.sliceAssignmentsIterator.toVector(0).mergeWithSuccessor(numReplicas = 1)
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 7)

    // adjustReplicas changes total number of slice replicas (scaling up).
    mutableAssignment.sliceAssignmentsIterator.toVector(0).adjustReplicas(2)
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 8)

    // adjustReplicas changes total number of slice replicas (scaling up more, limited by
    // resources). We have 4 resources, so we can only go up to 4 replicas per slice.
    mutableAssignment.sliceAssignmentsIterator.toVector(0).adjustReplicas(4)
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 10)

    // allocateResource increases total number of slice replicas by 1.
    val slice1: mutableAssignment.MutableSliceAssignment =
      mutableAssignment.sliceAssignmentsIterator.toVector(1)
    val rs3 = getResourceState(mutableAssignment, "resource3")
    // First deallocate resource3 from slice0 so we can add it to slice1
    val slice0: mutableAssignment.MutableSliceAssignment =
      mutableAssignment.sliceAssignmentsIterator.toVector(0)
    slice0.deallocateResource(rs3)
    mutableAssignment.forTest.checkInvariants()
    assert(mutableAssignment.currentNumTotalSliceReplicas == 9)

    // Now allocate resource3 to slice1
    slice1.allocateResource(rs3)
    mutableAssignment.forTest.checkInvariants()
    assert(slice1.isAllocatedToResource("resource3"))
    assert(mutableAssignment.currentNumTotalSliceReplicas == 10)

    // deallocateResource decreases total number of slice replicas by 1.
    val sliceToDeallocate: mutableAssignment.MutableSliceAssignment =
      mutableAssignment.sliceAssignmentsIterator.toVector.last
    sliceToDeallocate.deallocateResource(getResourceState(mutableAssignment, "resource0"))
    mutableAssignment.forTest.checkInvariants()
    assert(!sliceToDeallocate.isAllocatedToResource("resource0"))
    assert(mutableAssignment.currentNumTotalSliceReplicas == 9)
  }
}
