package com.databricks.dicer.client

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.util.Random

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.TestUtils
import com.databricks.dicer.client.testing.ScalaClerkHarness
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.testing.InternalDicerTestEnvironment
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveStringFluent,
  SliceAssignmentSliceFluent,
  createProposal,
  toProposedAssignmentEntry,
  toResourceAddress,
  toSliceKey,
  toSquid,
  toSquidIterable,
  `∞`
}
import com.databricks.dicer.common.{Assignment, ProposedSliceAssignment}
import com.databricks.dicer.external.{ResourceAddress, Slice, SliceKey, Slicelet, Target}
import com.databricks.dicer.friend.SliceMap
import com.databricks.testing.DatabricksTest

/**
 * Tests for the `getNextStubForKey` retry picker, using the harness supplied by each driver.
 * See [[ClerkImpl.getNextStubForKey]] for the specification.
 *
 * Once getNextStubForKey is exposed through a friend accessor, these tests should move to the
 * location of the accessor.
 */
abstract class ClerkRetrySuiteBase extends DatabricksTest with TestUtils.TestName {

  /** A Dicer test environment. */
  protected val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create()

  /** Creates a Clerk for `target`, watching `slicelet`. */
  protected def createClerkHarness(target: Target, slicelet: Slicelet): ScalaClerkHarness

  /**
   * Returns a [[MetricUtils.ChangeTracker]] over the getNextStubForKey call-count metric for
   * `target` and `resourceType`. The metric is filtered by the target's (per-test unique) name, so
   * it isolates the calls made by the clerk(s) under test.
   */
  private def getNextStubForKeyCallTracker(
      target: Target,
      resourceType: String): MetricUtils.ChangeTracker[Double] =
    MetricUtils.ChangeTracker[Double] { () =>
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_clerk_getnextstubforkey_call_count_total",
        Map(
          "targetName" -> target.getTargetNameLabel,
          "resourceType" -> resourceType
        )
      )
    }

  /**
   * Calls [[ScalaClerkHarness.getNextStubForKey]] on `harness` for `key`, verifying the Clerk
   * invariants hold both before and after the call, and returns the result it produced. Fails the
   * test if the harness returned no stub.
   */
  private def getNextStubForKey(
      harness: ScalaClerkHarness,
      key: SliceKey,
      tokenOpt: Option[RetryTokenImpl]): (ResourceAddress, RetryTokenImpl) = {
    harness.checkInvariants()
    val resultOpt: Option[(ResourceAddress, RetryTokenImpl)] =
      harness.getNextStubForKey(key, tokenOpt)
    harness.checkInvariants()
    resultOpt.getOrElse(throw new AssertionError("Expected a stub with a retry token"))
  }

  test("getNextStubForKey returns the assigned resource when retryToken is None") {
    // Test plan: Verify that getNextStubForKey returns the assigned resource if the
    // retryToken passed in is None. Verify this by calling getNextStubForKey 5 times
    // with None as the retryToken and checking that it returns the assigned resource every time
    // with the picked resource count always at 1.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)

    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod1"),
      ("Fili" -- "Kili") -> Seq("Pod1"),
      ("Kili" -- "Nori") -> Seq("Pod1"),
      ("Nori" -- ∞) -> Seq("Pod1")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk, SliceKey.MIN, None)
      assert(stub == toResourceAddress("Pod1"))
      assert(token.pickedResourceIndices.size == 1)
      assertResult(i)(assignedCallCount.totalChange())
    }
  }

  test(
    "getNextStubForKey returns a random first pick when a slice is assigned to " +
    "multiple resources"
  ) {
    // Test plan: Verify that getNextStubForKey eventually returns each assigned
    // resource as the first pick when retryToken passed in is None. Verify this by calling
    // getNextStubForKey as many times as it takes to observe all assigned resources
    // are returned on the first pick.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)

    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1", "Pod2"),
      ("Dori" -- "Fili") -> Seq("Pod1", "Pod2"),
      ("Fili" -- "Kili") -> Seq("Pod1", "Pod2"),
      ("Kili" -- "Nori") -> Seq("Pod1", "Pod2"),
      ("Nori" -- ∞) -> Seq("Pod1", "Pod2")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val returnedStubs: mutable.Set[ResourceAddress] = mutable.Set.empty
    val expectedStubs: Set[ResourceAddress] = Set("Pod1", "Pod2")
    // The assertion waiter runs an unknown number of iterations, so track the expected call count
    // directly.
    var numAssertionIterations: Int = 0
    // Keep calling until both assigned resources have been returned as the first pick.
    AssertionWaiter("Wait until both assigned resources have been returned").await {
      val (stub, _): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk, SliceKey.MIN, None)
      numAssertionIterations += 1
      assertResult(numAssertionIterations)(assignedCallCount.totalChange())
      returnedStubs.add(stub)
      assert(returnedStubs == expectedStubs)
    }
  }

  test("getNextStubForKey returns a newly assigned resource after an assignment update") {
    // Test plan: Verify that during a chain of retry requests, if an assignment update happens,
    // the token's pickedResourceIndices & fallbackPicked are reset and the newly assigned resource
    // is returned next. Verify this by generating an assignment in between the retry attempts.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod2"),
      ("Fili" -- "Kili") -> Seq("Pod3"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    val (firstStub, firstToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, None)
    assert(firstStub == toResourceAddress("Pod1"))
    assert(!firstToken.fallbackPicked)
    assert(firstToken.pickedResourceIndices.size == 1)
    assertResult(1.0)(assignedCallCount.totalChange())

    val (secondStub, secondToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, Some(firstToken))
    assert(secondStub != toResourceAddress("Pod1")) // fallback resource
    assert(secondToken.fallbackPicked)
    assert(secondToken.pickedResourceIndices.size == 1)
    assertResult(1.0)(fallbackCallCount.totalChange())

    // Create a new assignment. SLICE_MIN is now assigned to Pod3, instead of Pod1.
    val proposal2: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod3"),
      ("Dori" -- "Fili") -> Seq("Pod2"),
      ("Fili" -- "Kili") -> Seq("Pod1"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment2: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal2), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment2.generation)
      )
    }

    // The next call returns the newly assigned resource with the token's pickedResourceIndices &
    // fallbackPicked reset.
    val (stub, token): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, Some(secondToken))
    assert(stub == toResourceAddress("Pod3"))
    assert(token.pickedResourceIndices.size == 1)
    assert(!token.fallbackPicked)
    assertResult(1.0)(tokenResetCallCount.totalChange())
  }

  test("getNextStubForKey returns assigned resources before the fallback resource") {
    // Test plan: Verify that getNextStubForKey returns assigned resources first before
    // returning the fallback resource. Verify this by checking that all assigned resources are
    // returned by passing back the retry token.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1", "Pod2", "Pod5"),
      ("Dori" -- "Fili") -> Seq("Pod3"),
      ("Fili" -- "Kili") -> Seq("Pod3"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val randomAssignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "randomAssigned")

    // The first 3 calls will return the assigned resources in some order.
    val sliceMinAssignedResources: Set[ResourceAddress] = Set("Pod1", "Pod2", "Pod5")
    val seenSoFar: mutable.Set[ResourceAddress] = mutable.Set.empty
    var lastTokenOpt: Option[RetryTokenImpl] = None
    for (i: Int <- 1 to 3) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk, SliceKey.MIN, lastTokenOpt)
      assert(sliceMinAssignedResources.contains(stub))
      assert(token.pickedResourceIndices.size == i)
      assert(!seenSoFar.contains(stub))
      assertResult(i)(assignedCallCount.totalChange())
      seenSoFar.add(stub)
      lastTokenOpt = Some(token)
    }
    // The next call should return the fallback resource.
    val (fallbackStub, fallbackToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, lastTokenOpt)
    assert(!sliceMinAssignedResources.contains(fallbackStub))
    assert(fallbackToken.pickedResourceIndices.size == 3)
    assert(fallbackToken.fallbackPicked)
    assertResult(1.0)(fallbackCallCount.totalChange())
    lastTokenOpt = Some(fallbackToken)
    // Once the assigned resources and the fallback resource are exhausted, subsequent calls return
    // a random assigned resource (same behaviour as getStubForKey).
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk, SliceKey.MIN, lastTokenOpt)
      assert(sliceMinAssignedResources.contains(stub))
      // The fallback resource has already been returned, so the token stays in the steady state.
      assert(token.pickedResourceIndices.size == 3)
      assert(token.fallbackPicked)
      assertResult(i)(randomAssignedCallCount.totalChange())
    }
  }

  test(
    "getNextStubForKey returns the same fallback resource for the same slice key " +
    "across multiple clerks"
  ) {
    // Test plan: Verify that multiple clerks return the same fallback resource for the same slice.
    // Verify this by spinning up two clerks with the same assignment, advancing both past the
    // assigned resource to the fallback resource, and checking that both return the same resource.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk1: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val clerk2: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod3"),
      ("Fili" -- "Kili") -> Seq("Pod2"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk1.getLatestGenerationOpt.contains(assignment.generation)
      )
      assert(
        clerk2.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")

    // The first call returns the assigned resource (Pod1) on both clerks.
    val (firstStub1, firstToken1): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1, SliceKey.MIN, None)
    assert(firstStub1 == toResourceAddress("Pod1"))
    assertResult(1.0)(assignedCallCount.totalChange())
    val (firstStub2, firstToken2): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2, SliceKey.MIN, None)
    assert(firstStub2 == toResourceAddress("Pod1"))
    assertResult(2.0)(assignedCallCount.totalChange())

    // The second call returns the fallback resource, which must be identical across clerks.
    val (fallbackStub1, _): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1, SliceKey.MIN, Some(firstToken1))
    assertResult(1.0)(fallbackCallCount.totalChange())
    val (fallbackStub2, _): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2, SliceKey.MIN, Some(firstToken2))
    assertResult(2.0)(fallbackCallCount.totalChange())
    // A fallback resource, not the assigned resource.
    assert(fallbackStub1 != toResourceAddress("Pod1"))
    assert(fallbackStub1 == fallbackStub2)
  }

  test("A clerk can resume retrying by handling a retry token returned by another clerk") {
    // Test plan: Verify that a clerk can resume retrying, by handling a [[RetryTokenImpl]] which
    // was returned by another clerk. Verify this by starting a getNextStubForKey request on one
    // clerk, and passing the returned [[RetryTokenImpl]] to another clerk's getNextStubForKey
    // call.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk1: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val clerk2: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod3"),
      ("Fili" -- "Kili") -> Seq("Pod2"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk1.getLatestGenerationOpt.contains(assignment.generation)
      )
      assert(
        clerk2.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")

    val (_, firstToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1, SliceKey.MIN, None)
    assertResult(1.0)(assignedCallCount.totalChange())
    // Clerk2 resumes from clerk1's token. The fallback stub is returned because clerk1's first
    // getNextStubForKey call already recorded a picked index in the token.
    val (resumedStub, resumedToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2, SliceKey.MIN, Some(firstToken))
    assert(resumedStub != toResourceAddress("Pod1"))
    assert(resumedToken.fallbackPicked)
    assert(resumedToken.pickedResourceIndices.size == 1)
    assertResult(1.0)(fallbackCallCount.totalChange())
  }

  test("The fallback resource excludes resources that are not part of the assignment") {
    // Test plan: Verify that a slice's fallback resource is ONLY ever a resource that exists in the
    // current assignment. Verify this by checking that, after an assignment update with new
    // resources and old resources removed, the fallback resource for the first slice is a member
    // of the new assignment resources.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod2"),
      ("Fili" -- "Kili") -> Seq("Pod3"),
      ("Kili" -- "Nori") -> Seq("Pod4"),
      ("Nori" -- ∞) -> Seq("Pod5")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }
    // Create a new assignment with a different set of assigned resources.
    val proposal2: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod10"),
      ("Dori" -- "Fili") -> Seq("Pod20"),
      ("Fili" -- "Kili") -> Seq("Pod30"),
      ("Kili" -- "Nori") -> Seq("Pod40"),
      ("Nori" -- ∞) -> Seq("Pod50")
    )
    val assignment2: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal2), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment2.generation)
      )
    }
    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    val (firstStub, firstToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, None)
    assert(firstStub == toResourceAddress("Pod10"))
    assertResult(1.0)(assignedCallCount.totalChange())
    // Verify that the fallback resource is now one of the new resources, and not the assigned one.
    val newResources: Set[ResourceAddress] = Set("Pod10", "Pod20", "Pod30", "Pod40", "Pod50")
    val (secondStub, secondToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, Some(firstToken))
    assert(newResources.contains(secondStub))
    assert(secondStub != toResourceAddress("Pod10"))
    assert(secondToken.fallbackPicked)
    assertResult(1.0)(fallbackCallCount.totalChange())

    // Both calls ran against the latest assignment, so neither reset the token.
    assertResult(0.0)(tokenResetCallCount.totalChange())
  }

  test("getNextStubForKey returns None when there is no assignment") {
    // Test plan: Verify that a Clerk without an assignment returns None without recording a pick,
    // both without a retry token and with one obtained from another Clerk that has an assignment.
    val target = Target(getSafeName)
    // Prevent the Assigner from creating an assignment while the other Clerk obtains its token.
    TestUtils.awaitResult(testEnv.testAssigner.blockAssignment(target), Duration.Inf)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)
    // We create another Clerk with an assignment to mint an arbitrary retry token because the
    // Clerk under test cannot produce one without an assignment.
    val assignedTarget = Target(getSuffixedSafeName(suffix = "assigned"))
    val assignedSlicelet: Slicelet =
      testEnv.createSlicelet(assignedTarget).start(selfPort = 1234, listenerOpt = None)
    val assignedClerk: ScalaClerkHarness = createClerkHarness(assignedTarget, assignedSlicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(("" -- ∞) -> Seq("Pod1"))
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(assignedTarget, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the other Clerk").await {
      assert(assignedClerk.getLatestGenerationOpt.contains(assignment.generation))
    }
    val (_, callerToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(assignedClerk, SliceKey.MIN, None)

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val randomAssignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "randomAssigned")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    // Neither call makes a pick (the clerk has no assignment), so no metrics are recorded.
    assert(clerk.getNextStubForKey(SliceKey.MIN, None).isEmpty)
    assertResult(0.0)(assignedCallCount.totalChange())
    assertResult(0.0)(fallbackCallCount.totalChange())
    assertResult(0.0)(randomAssignedCallCount.totalChange())
    assertResult(0.0)(tokenResetCallCount.totalChange())

    assert(clerk.getNextStubForKey(SliceKey.MIN, Some(callerToken)).isEmpty)
    assertResult(0.0)(assignedCallCount.totalChange())
    assertResult(0.0)(fallbackCallCount.totalChange())
    assertResult(0.0)(randomAssignedCallCount.totalChange())
    assertResult(0.0)(tokenResetCallCount.totalChange())
  }

  test(
    "getNextStubForKey never returns a fallback resource when the assignment has " +
    "none"
  ) {
    // Test plan: Verify that when the assignment has no resource unassigned to a slice (i.e. the
    // assigned resources == all assignment resources), the slice has no fallback resource, so
    // getNextStubForKey keeps returning the slice's only resource and never advances to a fallback
    // resource. Verify this by assigning every slice to the same single resource and repeatedly
    // calling getNextStubForKey with the returned token 5 times and only the assigned pod is
    // returned.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq("Pod1"),
      ("Dori" -- "Fili") -> Seq("Pod1"),
      ("Fili" -- "Kili") -> Seq("Pod1"),
      ("Kili" -- "Nori") -> Seq("Pod1"),
      ("Nori" -- ∞) -> Seq("Pod1")
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerk").await {
      assert(
        clerk.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    val randomAssignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "randomAssigned")
    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    // The first call picks the single assigned resource.
    val (firstStub, firstToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk, SliceKey.MIN, None)
    assert(firstStub == toResourceAddress("Pod1"))
    assertResult(1.0)(assignedCallCount.totalChange())

    var lastTokenOpt: Option[RetryTokenImpl] = Some(firstToken)
    // Each subsequent call returns a random assigned resource because there is no fallback
    // resource to advance to.
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk, SliceKey.MIN, lastTokenOpt)
      assert(stub == toResourceAddress("Pod1"))
      // The picked-resource count stays at 1: there is no fallback resource to advance to.
      assert(token.pickedResourceIndices.size == 1)
      assert(!token.fallbackPicked)
      assertResult(i)(randomAssignedCallCount.totalChange())
      lastTokenOpt = Some(token)
    }
  }

  test(
    "The fallback selection algorithm is deterministic when slices have more than one assigned " +
    "resource"
  ) {
    // Test plan: Verify that a slice has the same fallback resource across multiple clerks when the
    // slices in the assignment have more than one assigned resource. Verify this by assigning each
    // slice to 30 resources and verifying that each slice gets the same fallback resource across
    // multiple clerks.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk1: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val clerk2: ScalaClerkHarness = createClerkHarness(target, slicelet)
    val numResources: Int = 30
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Random.shuffle(Seq.range(0, numResources).map(i => s"Pod$i")),
      ("Dori" -- "Fili") -> Random.shuffle(Seq.range(0, numResources).map(i => s"Pod1$i")),
      ("Fili" -- "Kili") -> Random.shuffle(Seq.range(0, numResources).map(i => s"Pod2$i")),
      ("Kili" -- "Nori") -> Random.shuffle(Seq.range(0, numResources).map(i => s"Pod3$i")),
      ("Nori" -- ∞) -> Random.shuffle(Seq.range(0, numResources).map(i => s"Pod4$i"))
    )
    val assignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    AssertionWaiter("Wait for the assignment to reach the Clerks").await {
      assert(
        clerk1.getLatestGenerationOpt.contains(assignment.generation)
      )
      assert(
        clerk2.getLatestGenerationOpt.contains(assignment.generation)
      )
    }

    // For each slice, exhaust both clerks' assigned resources and verify their fallback resources
    // match.
    for (sliceMapEntry <- proposal.entries) {
      val slice: Slice = sliceMapEntry.slice
      var lastTokenOpt1: Option[RetryTokenImpl] = None
      var lastTokenOpt2: Option[RetryTokenImpl] = None
      for (i <- 1 to numResources) {
        // The call-count trackers are scoped per slice iteration, so the expected counts restart
        // for each slice.
        val assignedCallCount: MetricUtils.ChangeTracker[Double] =
          getNextStubForKeyCallTracker(target, "assigned")
        val (_, token1): (ResourceAddress, RetryTokenImpl) =
          getNextStubForKey(clerk1, slice.lowInclusive, lastTokenOpt1)
        assertResult(1.0)(assignedCallCount.totalChange())
        assert(token1.pickedResourceIndices.size == i)
        assert(!token1.fallbackPicked)

        val (_, token2): (ResourceAddress, RetryTokenImpl) =
          getNextStubForKey(clerk2, slice.lowInclusive, lastTokenOpt2)
        assertResult(2.0)(assignedCallCount.totalChange())
        assert(token2.pickedResourceIndices.size == i)
        assert(!token2.fallbackPicked)

        // Pick up the tokens for the next iteration call.
        lastTokenOpt1 = Some(token1)
        lastTokenOpt2 = Some(token2)
      }
      val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
        getNextStubForKeyCallTracker(target, "fallback")
      // The next call should return the fallback resource on both clerks.
      val (fallbackStub1, fallbackToken1): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk1, slice.lowInclusive, lastTokenOpt1)
      assertResult(1.0)(fallbackCallCount.totalChange())
      assert(fallbackToken1.fallbackPicked)

      val (fallbackStub2, fallbackToken2): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk2, slice.lowInclusive, lastTokenOpt2)
      assertResult(2.0)(fallbackCallCount.totalChange())
      assert(fallbackToken2.fallbackPicked)

      assert(fallbackStub1 == fallbackStub2)
    }
  }
}
