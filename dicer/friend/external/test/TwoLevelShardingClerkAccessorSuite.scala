package com.databricks.dicer.friend.external

import java.net.URI

import scala.concurrent.duration.Duration
import scala.util.Random

import com.google.common.primitives.Longs
import com.google.protobuf.ByteString

import com.databricks.caching.util.{AssertionWaiter, TestUtils}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.common.{InternalDicerTestEnvironment, ProposedSliceAssignment}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Clerk, ResourceAddress, SliceKey, Target}
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.external.TwoLevelShardingClerkAccessorGoldenData.EXPECTED_OWNERS
import com.databricks.testing.DatabricksTest

class TwoLevelShardingClerkAccessorSuite extends DatabricksTest with TestName {

  /** Shared Dicer test environment. */
  private val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create()

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /** Creates a [[SliceKey]] from the big-endian byte representation of `value`. */
  private def sliceKeyFromLong(value: Long): SliceKey =
    SliceKey.fromRawBytes(ByteString.copyFrom(Longs.toByteArray(value)))

  /** Creates a clerk that directly connects to the Assigner for assignments. */
  private def createDirectClerk(target: Target): Clerk[ResourceAddress] = {
    testEnv.createDirectClerk(target, initialAssignerIndex = 0)
  }

  test("Two-level getStubForKey returns the single pod when only one is assigned to a range") {
    // Test plan: Configure an assignment where the entire key space is owned by a single pod,
    // then call the two-level getStubForKey with a fixed primary and varying secondary keys.
    // Verify that every call returns the single assigned pod, regardless of the secondary.
    val target = Target(getSafeName)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- ∞) -> Seq("Pod0")
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.impl.forTest.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val expectedPod: ResourceAddress = ResourceAddress(URI.create("Pod0"))
    for (i: Int <- 0 until 10) {
      assertResult(Some(expectedPod))(
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryKey, fp(s"secondary_$i"))
      )
    }

    clerk.forTest.stop()
  }

  test("Two-level getStubForKey is deterministic for the same (primaryKey, secondaryKey)") {
    // Test plan: Configure an assignment with 5 pods owning the entire key space, then call the
    // two-level getStubForKey with the same (primaryKey, secondaryKey) repeatedly and verify every
    // call returns the same resource when the assignment doesn't change.
    val target = Target(getSafeName)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- ∞) -> Seq("Pod0", "Pod1", "Pod2", "Pod3", "Pod4")
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.impl.forTest.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val secondaryKey: SliceKey = fp("secondary_A")

    val firstLookup: Option[ResourceAddress] =
      TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryKey, secondaryKey)
    assert(firstLookup.isDefined)
    for (_: Int <- 0 until 50) {
      assert(
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryKey, secondaryKey) == firstLookup
      )
    }

    clerk.forTest.stop()
  }

  test("Two-level getStubForKey distributes secondaries evenly across multiple pods") {
    // Test plan: Configure an assignment with 5 pods. Vary the secondary key across many values
    // and verify that the two-level routing yields all pods as distinct owners with a roughly
    // uniform distribution. With farmhash64 and 128 vnodes per pod, a few hundred secondaries
    // should easily fan out across pods.
    val target = Target(getSafeName)
    val pods: Seq[String] = Seq("Pod0", "Pod1", "Pod2", "Pod3", "Pod4")
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- ∞) -> pods
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.impl.forTest.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val numSecondaries: Int = 200
    val ownersBySecondary: Seq[ResourceAddress] =
      (0 until numSecondaries).flatMap { i: Int =>
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryKey, fp(s"secondary_$i"))
      }
    val countsByOwner: Map[ResourceAddress, Int] =
      ownersBySecondary.groupBy(identity).map {
        case (owner: ResourceAddress, owners: Seq[ResourceAddress]) => owner -> owners.size
      }

    assert(
      countsByOwner.keySet.size == pods.size,
      s"Expected secondaries to fan out across all pods; got $countsByOwner"
    )

    // Verify a roughly uniform distribution, allowing for a +/- 25% deviation.
    val expectedMeanCount: Int = numSecondaries / pods.size
    for (entry <- countsByOwner) {
      val (owner, count): (ResourceAddress, Int) = entry
      val deviation: Double = math
          .abs(count - expectedMeanCount)
          .toDouble / expectedMeanCount.toDouble
      assert(
        deviation <= 0.25,
        s"Owner $owner received $count secondaries, expected $expectedMeanCount +/- 25%"
      )
    }

    clerk.forTest.stop()
  }

  test("Two-level getStubForKey routes different primary keys to different pod sets") {
    // Test plan: Configure an assignment with two primary ranges, each owned by a disjoint pod
    // set. For a fixed secondary key, look up two primary keys, one in each range, and verify
    // distinct pods are returned.
    val separator: SliceKey = sliceKeyFromLong(100)
    val primaryInLowRange: SliceKey = sliceKeyFromLong(50)
    val primaryInHighRange: SliceKey = sliceKeyFromLong(150)

    val target = Target(getSafeName)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- separator) -> Seq("PodA"),
      (separator -- ∞) -> Seq("PodB")
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.impl.forTest.checkInvariants()

    val secondaryKey: SliceKey = fp("secondary_A")
    val ownerInLowRange: Option[ResourceAddress] =
      TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInLowRange, secondaryKey)
    val ownerInHighRange: Option[ResourceAddress] =
      TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInHighRange, secondaryKey)

    assert(ownerInLowRange.isDefined && ownerInHighRange.isDefined)
    assert(ownerInLowRange.get == ResourceAddress(URI.create("PodA")))
    assert(ownerInHighRange.get == ResourceAddress(URI.create("PodB")))

    clerk.forTest.stop()
  }

  test("Two-level getStubForKey respects assignment changes") {
    // Test plan: Configure an initial assignment with two ranges, then replace it so each range
    // is owned by a different pod. Verify that subsequent lookups reflect the new assignment.
    val separator: SliceKey = sliceKeyFromLong(100)
    val primaryInLowRange: SliceKey = sliceKeyFromLong(50)
    val primaryInHighRange: SliceKey = sliceKeyFromLong(150)
    val randomSecondaryKey: SliceKey = sliceKeyFromLong(Random.nextLong())

    val target = Target(getSafeName)
    val initialProposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- separator) -> Seq("Pod0"),
      (separator -- ∞) -> Seq("Pod1")
    )
    testEnv.setAndFreezeAssignment(target, initialProposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.impl.forTest.checkInvariants()

    // Verify the initial assignment is in effect.
    val pod0: ResourceAddress = ResourceAddress(URI.create("Pod0"))
    val pod1: ResourceAddress = ResourceAddress(URI.create("Pod1"))
    assertResult(Some(pod0))(
      TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInLowRange, randomSecondaryKey)
    )
    assertResult(Some(pod1))(
      TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInHighRange, randomSecondaryKey)
    )

    // Replace the assignment so the low range is owned by Pod2 and the high range by Pod3.
    val updatedProposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- separator) -> Seq("Pod2"),
      (separator -- ∞) -> Seq("Pod3")
    )
    testEnv.setAndFreezeAssignment(target, updatedProposal)

    // Verify that lookups eventually reflect the new assignment.
    val pod2: ResourceAddress = ResourceAddress(URI.create("Pod2"))
    val pod3: ResourceAddress = ResourceAddress(URI.create("Pod3"))
    AssertionWaiter("Await for the new assignment to be picked up by the clerk").await {
      assertResult(Some(pod2))(
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInLowRange, randomSecondaryKey)
      )
      assertResult(Some(pod3))(
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryInHighRange, randomSecondaryKey)
      )
    }
    clerk.impl.forTest.checkInvariants()

    clerk.forTest.stop()
  }

  test("Two-level getStubForKey behavior is stable across code changes") {
    // Test plan: Verify with a golden test that the (primary, secondary) -> pod routing produced
    // by TwoLevelShardingClerkAccessor does not change even when the code is modified. Since
    // Clerks may reside in different services and thus use different code versions, the two-level
    // routing API is required to be stable and to agree on the same routing decisions.
    val target = Target(getSafeName)
    val pods: Seq[String] = (0 until 10).map(i => s"Pod$i")
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- ∞) -> pods
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    val primaryKey: SliceKey = fp("primary")
    val actualOwners: Seq[String] = (0 until EXPECTED_OWNERS.size).map { i: Int =>
      val owner: ResourceAddress =
        TwoLevelShardingClerkAccessor.getStubForKey(clerk, primaryKey, fp(s"secondary_$i")).get
      owner.uri.toString
    }
    assertResult(EXPECTED_OWNERS)(actualOwners)

    clerk.forTest.stop()
  }
}
