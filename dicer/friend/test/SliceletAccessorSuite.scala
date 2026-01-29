package com.databricks.dicer.friend

import scala.concurrent.duration.Duration
import scala.util.Random

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.client.TestClientUtils.{waitForAssignment, waitForGenerationAtLeast}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment,
  SliceAssignment,
  TestAssigner
}
import com.databricks.dicer.external.{ResourceAddress, Slice, Slicelet, Target}
import com.databricks.dicer.friend.SliceMap.{GapEntry, IntersectionEntry}
import com.databricks.testing.DatabricksTest

class SliceletAccessorSuite extends DatabricksTest with TestName {

  /** The test environment used for all the tests. */
  private val testEnv = InternalDicerTestEnvironment.create()

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  test("assignedSlicesWithStateProvider manual") {
    // Test plan: Verify that `assignedSlicesWithStateProvider` works as expected with a manually
    // created assignment. Create an initial assignment with all slices assigned to Slicelet 1. Then
    // reassign everything to a new Slicelet 2, and verify that Slicelet 2's
    // `assignedSlicesWithStateProvider` is as expected.
    val target: Target = Target(getSafeName)
    val testAssigner: TestAssigner = testEnv.testAssigner
    val slicelet1: Slicelet = testEnv
      .createSlicelet(target)
      .start(
        selfPort = 1234,
        listenerOpt = None
      )
    val slicelet1Squid: Squid = slicelet1.impl.squid

    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq(slicelet1Squid),
      ("Dori" -- "Kili") -> Seq(slicelet1Squid),
      ("Kili" -- ∞) -> Seq(slicelet1Squid)
    )
    testAssigner.setAndFreezeAssignment(target, proposal)

    val slicelet2: Slicelet = testEnv
      .createSlicelet(target)
      .start(
        selfPort = 1235,
        listenerOpt = None
      )
    assertResult(target)(SliceletAccessor.target(slicelet1))
    assertResult(target)(SliceletAccessor.target(slicelet2))
    val newProposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Fili") -> Seq(slicelet2.impl.squid),
      ("Fili" -- ∞) -> Seq(slicelet1Squid)
    )
    testAssigner.setAndFreezeAssignment(target, newProposal)
    waitForAssignment(slicelet2, "")

    val assignedSlicesWithStateProvider: Seq[SliceWithStateProvider] =
      SliceletAccessor.assignedSlicesWithStateProvider(slicelet2)
    // Expected slice metadata. Note that the transfers could be coalesced since the state provider
    // is the same, but we don't do that in the current implementation.
    val expectedSlicesWithStateProvider = Seq(
      SliceWithStateProvider("" -- "Dori", Some(SliceletAccessor.resourceAddress(slicelet1))),
      SliceWithStateProvider("Dori" -- "Fili", Some(SliceletAccessor.resourceAddress(slicelet1)))
    )
    assert(assignedSlicesWithStateProvider == expectedSlicesWithStateProvider)
  }

  test("assignedSlicesWithStateProvider randomized") {
    // Test plan: Verify that Slicelets with randomly generated assignments provide the expected
    // result for `assignedSlicesWithStateProvider`. Do this by generating random assignments with
    // several Slicelets, and whenever a Slice is reassigned, track what the state provider should
    // be. At the end we verify for each Slicelet that
    // `SliceletAccessor.assignedSlicesWithStateProvider` has the expected Slices and state
    // providers.
    val target: Target = Target(getSafeName)
    val testAssigner: TestAssigner = testEnv.testAssigner

    // Initially assign everything to a blackhole resource. All slices will be reassigned to a valid
    // slicelet, so this ensures that every slice will have a state provider.
    val blackholeResource: Squid = "blackhole"
    var previousAssignment: Assignment = TestUtils.awaitResult(
      testAssigner.setAndFreezeAssignment(
        target,
        createProposal(Slice.FULL -> Seq(blackholeResource))
      ),
      Duration.Inf
    )

    val numSlicelets = 4
    val slicelets = (0 until numSlicelets).map { i: Int =>
      testEnv
        .createSlicelet(target)
        .start(
          selfPort = 1234 + i,
          listenerOpt = None
        )
    }

    // Map (sub)slice to the _previous_ resource it was assigned to. A state transfer for a subslice
    // must come from the previous resource indicated in this map.
    val previousResourceMap = new MutableSliceMap[Squid]
    // Generate two random assignments to generate state transfers.
    val numSlices = 20
    val numAssignmentChanges = 2
    for (_ <- 0 until numAssignmentChanges) {
      val proposal: SliceMap[ProposedSliceAssignment] =
        createRandomProposal(
          numSlices,
          slicelets.map((_: Slicelet).impl.squid),
          numMaxReplicas = 1,
          new Random()
        )
      val assignment: Assignment =
        TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)

      // For any subslice that has been reassigned, insert it into `previousResourceMap`.
      val intersectionEntries: Vector[IntersectionEntry[SliceAssignment, SliceAssignment]] =
        SliceMap.intersectSlices(previousAssignment.sliceMap, assignment.sliceMap).entries
      for (entry <- intersectionEntries) {
        val previous: SliceAssignment = entry.leftEntry
        val next: SliceAssignment = entry.rightEntry
        assert(previous.resources.size == 1)
        assert(next.resources.size == 1)
        if (previous.resources.head != next.resources.head) {
          previousResourceMap.put(entry.slice, previous.resources.head)
        }
      }
      previousAssignment = assignment
    }
    val finalAssignment: Assignment = previousAssignment

    // A case class associating a Slice with a resource. Just used for constructing an immutable
    // SliceMap (`previousResourceSliceMap` below) from `previousResourceMap` so we can conveniently
    // intersect it with `finalAssignment` to calculate the expected state provider map.
    case class SliceWithResource(slice: Slice, resource: Squid)

    // Now go through and verify `assignedSlicesWithStateProvider` for each Slicelet.
    for (slicelet: Slicelet <- slicelets) {
      waitForGenerationAtLeast(slicelet, finalAssignment.generation)
      // Intersect `previousResourceSliceMap` with the assignment to get the expected state
      // providers for each Slice assigned to this `slicelet`.
      val previousResourceSliceMap: SliceMap[SliceWithResource] = new SliceMap(
        previousResourceMap.toVector.map {
          case (slice: Slice, squid: Squid) => SliceWithResource(slice, squid)
        },
        (_: SliceWithResource).slice
      )
      val intersectionEntries: Vector[IntersectionEntry[SliceAssignment, SliceWithResource]] =
        SliceMap.intersectSlices(finalAssignment.sliceMap, previousResourceSliceMap).entries
      val expectedStateProvidersMap = new MutableSliceMap[ResourceAddress]
      for (entry <- intersectionEntries) {
        val currentAssignment: SliceAssignment = entry.leftEntry
        assert(currentAssignment.resources.size == 1)
        val previous: SliceWithResource = entry.rightEntry
        if (finalAssignment.isResourceAssignmentCompatible(
            currentAssignment.resources.head,
            slicelet.impl.squid
          )) {
          expectedStateProvidersMap.put(entry.slice, previous.resource.resourceAddress)
        }
      }

      // Now get the `assignedSlicesWithStateProvider` and convert it to a SliceMap.
      val assignedSlicesWithStateProvider: Seq[SliceWithStateProvider] =
        SliceletAccessor.assignedSlicesWithStateProvider(slicelet)
      val assignedMap: SliceMap[GapEntry[SliceWithStateProvider]] =
        SliceMap.createFromOrderedDisjointEntries(
          assignedSlicesWithStateProvider,
          (_: SliceWithStateProvider).slice
        )
      // `assignedSlicesWithStateProvider()` does not currently coalesce, whereas MutableSliceMap
      // does, so coalesce the former to be able to compare them.
      val coalescedMap: SliceMap[GapEntry[SliceWithStateProvider]] = SliceMap.coalesceSlices(
        assignedMap,
        setSlice =
          (sliceWithStateProviderGap: GapEntry[SliceWithStateProvider], newSlice: Slice) => {
            sliceWithStateProviderGap match {
              case GapEntry.Some(sliceWithStateProvider: SliceWithStateProvider) =>
                GapEntry.Some(sliceWithStateProvider.copy(slice = newSlice))
              case GapEntry.Gap(_) => GapEntry.Gap(newSlice)
            }
          }
      )
      val coalescedStateProviders: Seq[(Slice, ResourceAddress)] =
        coalescedMap.entries.flatMap {
          case GapEntry.Some(sliceWithStateProvider: SliceWithStateProvider) =>
            // As mentioned above, since everything is initially assigned to blackhole, each Slice
            // is guaranteed to have a state provider and we can call `.get`.
            Some(
              (sliceWithStateProvider.slice, sliceWithStateProvider.stateProviderResourceOpt.get)
            )
          case GapEntry.Gap(_) => None
        }

      assertResult(expectedStateProvidersMap.toVector)(coalescedStateProviders)
    }
  }

  test("resourceAddress returns as expected") {
    // Test plan: Verify that `resourceAddress` works as expected by creating a Slicelet and
    // checking that the returned resource address matches the Slicelet's squid's resource address.
    val target: Target = Target(getSafeName)
    val slicelet: Slicelet = testEnv
      .createSlicelet(target)
      .start(
        selfPort = 1234,
        listenerOpt = None
      )
    val sliceletSquid: Squid = slicelet.impl.squid
    assert(SliceletAccessor.resourceAddress(slicelet) == sliceletSquid.resourceAddress)
  }

  test("getKnownResourceAddresses returns expected") {
    // Test plan: Verify that `getKnownResourceAddresses` works as expected with a manually created
    // assignment. Verify this by creating an assignment with some slices assigned to Slicelet 1 and
    // Slicelet 2 and checking that the returned resource addresses are as expected.
    val target: Target = Target(getSafeName)
    val testAssigner: TestAssigner = testEnv.testAssigner

    // Setup: Create and start Slicelets and obtain their squids.
    val slicelet1: Slicelet = testEnv
      .createSlicelet(target)
      .start(
        selfPort = 1234,
        listenerOpt = None
      )
    val slicelet1Squid: Squid = slicelet1.impl.squid
    val slicelet2: Slicelet = testEnv
      .createSlicelet(target)
      .start(
        selfPort = 1235,
        listenerOpt = None
      )
    val slicelet2Squid: Squid = slicelet2.impl.squid

    // Setup: Set and freeze an assignment where some slices are assigned to Slicelet 1 and 2.
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq(slicelet1Squid),
      ("Dori" -- ∞) -> Seq(slicelet2Squid)
    )
    testAssigner.setAndFreezeAssignment(target, proposal)

    // Verify: Check that `getKnownResourceAddresses` returns the expected addresses on each
    // Slicelet.
    val expectedResourceAddresses: Set[ResourceAddress] =
      Set(slicelet1Squid.resourceAddress, slicelet2Squid.resourceAddress)
    AssertionWaiter("Wait for assignment to be delivered to Slicelets").await {
      assert(SliceletAccessor.getKnownResourceAddresses(slicelet1) == expectedResourceAddresses)
      assert(SliceletAccessor.getKnownResourceAddresses(slicelet2) == expectedResourceAddresses)
    }
  }
}
