package com.databricks.dicer.friend.external

import java.net.URI

import scala.concurrent.duration.Duration
import scala.util.Random

import com.google.common.primitives.Longs
import com.google.protobuf.ByteString

import com.databricks.caching.util.{AssertionWaiter, MetricUtils, TestUtils}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.common.{InternalDicerTestEnvironment, ProposedSliceAssignment}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{
  Clerk,
  ClerkHarness,
  ResourceAddress,
  ScalaClerkHarness,
  SliceKey,
  Target
}
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.external.TwoLevelShardingClerkAccessorGoldenData.EXPECTED_OWNERS
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry

/**
 * Shared tests for the two-level sharding Clerk accessor, run against both the Scala and Rust
 * Clerk implementations through the [[ClerkHarness]] abstraction, which guarantees that both
 * languages produce identical (primary, secondary) -> pod routing decisions.
 */
abstract class TwoLevelShardingClerkAccessorSuiteBase extends DatabricksTest with TestName {

  /** Shared Dicer test environment. */
  protected val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create()

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /**
   * Creates a [[ClerkHarness]] that wraps a Clerk that receives assignments for the given
   * `target`.
   */
  protected def createClerk(target: Target): ClerkHarness

  /**
   * Reads and returns the value of the Prometheus metric with the given name and labels. Returns
   * zero if the metric has never been recorded with this set of label values.
   *
   * @note labels is a vector of (label name, label value) pairs, whose order must match the Rust
   *       struct field declaration order.
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  /** Creates a [[SliceKey]] from the big-endian byte representation of `value`. */
  private def sliceKeyFromLong(value: Long): SliceKey =
    SliceKey.fromRawBytes(ByteString.copyFrom(Longs.toByteArray(value)))

  /**
   * Returns the number of times the Clerks for a given target have recorded a getStubForKey call
   * with the given `secondaryKeyProvided` label.
   */
  private def getStubForKeyCallCount(target: Target, secondaryKeyProvided: Boolean): Double = {
    readPrometheusMetric(
      "dicer_clerk_getstubforkey_call_count_total",
      Vector(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "factoryContext" -> "clerk",
        "secondaryKeyProvided" -> secondaryKeyProvided.toString
      )
    )
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

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val expectedPod: ResourceAddress = ResourceAddress(URI.create("Pod0"))
    for (i: Int <- 0 until 10) {
      assertResult(Some(expectedPod))(clerk.getStubForKey(primaryKey, fp(s"secondary_$i")))
      assertResult(i + 1)(getStubForKeyCallCount(target, secondaryKeyProvided = true))
    }

    clerk.stop()
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

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val secondaryKey: SliceKey = fp("secondary_A")

    val firstLookup: Option[ResourceAddress] = clerk.getStubForKey(primaryKey, secondaryKey)
    assert(firstLookup.isDefined)
    assertResult(1)(getStubForKeyCallCount(target, secondaryKeyProvided = true))
    for (i: Int <- 0 until 50) {
      assert(clerk.getStubForKey(primaryKey, secondaryKey) == firstLookup)
      assertResult(i + 2)(getStubForKeyCallCount(target, secondaryKeyProvided = true))
    }

    clerk.stop()
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

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.checkInvariants()

    val primaryKey: SliceKey = fp("primary")
    val numSecondaries: Int = 200
    val ownersBySecondary: Seq[ResourceAddress] =
      (0 until numSecondaries).flatMap { i: Int =>
        val owner: Option[ResourceAddress] = clerk.getStubForKey(primaryKey, fp(s"secondary_$i"))
        assertResult(i + 1)(getStubForKeyCallCount(target, secondaryKeyProvided = true))
        owner
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

    clerk.stop()
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

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.checkInvariants()

    val secondaryKey: SliceKey = fp("secondary_A")
    val ownerInLowRange: Option[ResourceAddress] =
      clerk.getStubForKey(primaryInLowRange, secondaryKey)
    val ownerInHighRange: Option[ResourceAddress] =
      clerk.getStubForKey(primaryInHighRange, secondaryKey)

    assert(ownerInLowRange.isDefined && ownerInHighRange.isDefined)
    assert(ownerInLowRange.get == ResourceAddress(URI.create("PodA")))
    assert(ownerInHighRange.get == ResourceAddress(URI.create("PodB")))
    assertResult(2)(getStubForKeyCallCount(target, secondaryKeyProvided = true))

    clerk.stop()
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

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    clerk.checkInvariants()

    // Verify the initial assignment is in effect.
    val pod0: ResourceAddress = ResourceAddress(URI.create("Pod0"))
    val pod1: ResourceAddress = ResourceAddress(URI.create("Pod1"))
    assertResult(Some(pod0))(clerk.getStubForKey(primaryInLowRange, randomSecondaryKey))
    assertResult(Some(pod1))(clerk.getStubForKey(primaryInHighRange, randomSecondaryKey))
    assertResult(2)(getStubForKeyCallCount(target, secondaryKeyProvided = true))

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
      assertResult(Some(pod2))(clerk.getStubForKey(primaryInLowRange, randomSecondaryKey))
      assertResult(Some(pod3))(clerk.getStubForKey(primaryInHighRange, randomSecondaryKey))
    }
    clerk.checkInvariants()

    clerk.stop()
  }

  test("Two-level getStubForKey behavior is stable across code changes") {
    // Test plan: Verify with a golden test that the (primary, secondary) -> pod routing produced
    // by the two-level sharding accessor does not change even when the code is modified. Since
    // Clerks may reside in different services and thus use different code versions or languages,
    // the two-level routing API is required to be stable and to agree on the same routing
    // decisions.
    val target = Target(getSafeName)
    val pods: Seq[String] = (0 until 10).map(i => s"Pod$i")
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- ∞) -> pods
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    val clerk: ClerkHarness = createClerk(target)
    TestUtils.awaitResult(clerk.ready, Duration.Inf)

    val primaryKey: SliceKey = fp("primary")
    for (i: Int <- 0 until EXPECTED_OWNERS.size) {
      val owner: ResourceAddress = clerk.getStubForKey(primaryKey, fp(s"secondary_$i")).get
      val expectedOwner: ResourceAddress = ResourceAddress(URI.create(EXPECTED_OWNERS(i)))
      assert(
        ClerkHarness.resourceAddressEquals(owner, expectedOwner),
        s"secondary_$i routed to $owner, expected $expectedOwner"
      )
      assertResult(i + 1)(getStubForKeyCallCount(target, secondaryKeyProvided = true))
    }

    clerk.stop()
  }
}

/**
 * Runs the two-level sharding Clerk accessor tests against the Scala [[Clerk]] implementation,
 * using clerks that directly connect to the Assigner for assignments.
 */
class ScalaTwoLevelShardingClerkAccessorSuite extends TwoLevelShardingClerkAccessorSuiteBase {

  override protected def createClerk(target: Target): ClerkHarness = {
    val clerk: Clerk[ResourceAddress] = testEnv.createDirectClerk(target, initialAssignerIndex = 0)
    ScalaClerkHarness.create(clerk)
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }
}
