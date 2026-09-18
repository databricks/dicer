package com.databricks.dicer.client

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.util.Random

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.TestUtils
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{Assignment, Generation, ProposedSliceAssignment}
import com.databricks.dicer.external.{Clerk, ResourceAddress, Slice, SliceKey, Slicelet, Target}
import com.databricks.dicer.friend.SliceMap
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.InternalDicerTestEnvironment

/**
 * Tests for [[ClerkImpl.getNextStubForKey]]. Once getNextStubForKey is exposed through a friend
 * accessor, these tests should move to the location of the accessor.
 */
class ClerkRetrySuite extends DatabricksTest with TestUtils.TestName {

  /** A Dicer test environment */
  private val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create()

  /**
   * Calls [[ClerkImpl.getNextStubForKey]] on `clerkImpl` for `key`, verifying the ClerkAssignment
   * invariants hold both before and after the call, and returns the (stub, retry token) it
   * produced. Fails the test if the `clerkImpl` returned no stub.
   */
  private def getNextStubForKey(
      clerkImpl: ClerkImpl[ResourceAddress],
      key: SliceKey,
      retryTokenOpt: Option[RetryTokenImpl]): (ResourceAddress, RetryTokenImpl) = {
    clerkImpl.forTest.checkInvariants()
    val stubWithTokenOpt: Option[(ResourceAddress, RetryTokenImpl)] =
      clerkImpl.getNextStubForKey(key, retryTokenOpt)
    clerkImpl.forTest.checkInvariants()
    stubWithTokenOpt.getOrElse(throw new AssertionError("Expected a stub with a retry token"))
  }

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

  test(
    "ClerkImpl.getNextStubForKey returns the assigned resource when retryToken is None"
  ) {
    // Test plan: Verify that ClerkImpl.getNextStubForKey returns the assigned resource if the
    // retryToken passed in is None. Verify this by calling getNextStubForKey 5 times
    // with None as the retryToken and checking that it returns the assigned resource every time
    // with the picked resource indices list always as 1.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)

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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk.impl, SliceKey.MIN, None)
      assert(stub.toString() == "Pod1")
      assert(token.pickedResourceIndices.size == 1)
      assertResult(i)(assignedCallCount.totalChange())
    }
  }

  test(
    "ClerkImpl.getNextStubForKey returns a random first pick when a slice is assigned to " +
    "multiple resources"
  ) {
    // Test plan: Verify that ClerkImpl.getNextStubForKey eventually returns each assigned
    // resource as the first pick when retryToken passed in is None. Verify this by calling
    // getNextStubForKey as many times as it takes to observe all assigned resources
    // are returned on the first pick.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)

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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
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
        getNextStubForKey(clerk.impl, SliceKey.MIN, None)
      numAssertionIterations += 1
      assertResult(numAssertionIterations)(assignedCallCount.totalChange())
      returnedStubs.add(stub)
      assert(returnedStubs == expectedStubs)
    }
  }

  test(
    "ClerkImpl.getNextStubForKey returns a newly assigned resource after an assignment update"
  ) {
    // Test plan: Verify that during a chain of retry requests, if an assignment update happens,
    // the token's pickedResourceIndices & fallbackPicked are reset and the newly assigned resource
    // is returned next. Verify this by generating an assignment in between the retry attempts.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    val (firstStub, firstToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, None)
    assert(firstStub.toString() == "Pod1")
    assert(!firstToken.fallbackPicked)
    assert(firstToken.pickedResourceIndices.size == 1)
    assertResult(1.0)(assignedCallCount.totalChange())

    val (secondStub, secondToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, Some(firstToken))
    assert(secondStub.toString() != "Pod1") // fallback resource
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment2.generation)
      )
    }

    // The next call returns the newly assigned resource with the token's pickedResourceIndices &
    // fallbackPicked reset.
    val (stub, token): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, Some(secondToken))
    assert(stub.toString() == "Pod3")
    assert(token.pickedResourceIndices.size == 1)
    assert(!token.fallbackPicked)
    assertResult(1.0)(tokenResetCallCount.totalChange())
  }

  test("ClerkImpl.getNextStubForKey returns assigned resources before the fallback resource") {
    // Test plan: Verify that ClerkImpl.getNextStubForKey returns assigned resources first before
    // returning the fallback resource. Verify this by checking that all assigned resources are
    // returned by passing back the retry token.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
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
        getNextStubForKey(clerk.impl, SliceKey.MIN, lastTokenOpt)
      assert(sliceMinAssignedResources.contains(stub))
      assert(token.pickedResourceIndices.size == i)
      assert(!seenSoFar.contains(stub))
      assertResult(i)(assignedCallCount.totalChange())
      seenSoFar.add(stub)
      lastTokenOpt = Some(token)
    }
    // The next call should return the fallback resource.
    val (fallbackStub, retryToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, lastTokenOpt)
    assert(!sliceMinAssignedResources.contains(fallbackStub))
    assert(retryToken.pickedResourceIndices.size == 3)
    assert(retryToken.fallbackPicked)
    assertResult(1.0)(fallbackCallCount.totalChange())
    lastTokenOpt = Some(retryToken)
    // Once the assigned resources and the fallback resource are exhausted, subsequent calls return
    // a random assigned resource (same behaviour as getStubForKey).
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk.impl, SliceKey.MIN, lastTokenOpt)
      assert(sliceMinAssignedResources.contains(stub))
      // The fallback resource has already been returned, so the token stays in the steady state.
      assert(retryToken.pickedResourceIndices.size == 3)
      assert(token.fallbackPicked)
      assertResult(i)(randomAssignedCallCount.totalChange())
    }
  }

  test(
    "ClerkImpl.getNextStubForKey returns the same fallback resource for the same slice key " +
    "across multiple clerks"
  ) {
    // Test plan: Verify that multiple clerks return the same fallback resource for the same slice.
    // Verify this by spinning up two clerks with the same assignment, advancing both past the
    // assigned resource to the fallback resource, and checking that both return the same resource.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk1: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
    val clerk2: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk1.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
      assert(
        clerk2.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")

    // The first call returns the assigned resource (Pod1) on both clerks.
    val (stub1, token1): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1.impl, SliceKey.MIN, None)
    assert(stub1.toString() == "Pod1")
    assertResult(1.0)(assignedCallCount.totalChange())
    val (stub2, token2): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2.impl, SliceKey.MIN, None)
    assert(stub2.toString() == "Pod1")
    assertResult(2.0)(assignedCallCount.totalChange())

    // The second call returns the fallback resource, which must be identical across clerks.
    val (fallbackResource1, _): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1.impl, SliceKey.MIN, Some(token1))
    assertResult(1.0)(fallbackCallCount.totalChange())
    val (fallbackResource2, _): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2.impl, SliceKey.MIN, Some(token2))
    assertResult(2.0)(fallbackCallCount.totalChange())
    // A fallback resource, not the assigned resource.
    assert(fallbackResource1.toString() != "Pod1")
    assert(fallbackResource1.toString() == fallbackResource2.toString())
  }

  test("ClerkImpl can resume retrying by handling a retry token returned by another clerk") {
    // Test plan: Verify that a clerk can resume retrying, by handling a [[RetryTokenImpl]] which
    // was returned by another clerk. Verify this by starting a getNextStubForKey request on one
    // clerk, and passing the returned [[RetryTokenImpl]] to another clerk's getNextStubForKey call.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk1: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
    val clerk2: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk1.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
      assert(
        clerk2.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")

    val (_, retryToken1): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk1.impl, SliceKey.MIN, None)
    assertResult(1.0)(assignedCallCount.totalChange())

    // Clerk2 resumes from clerk1's token. The fallback stub is returned because clerk1's first
    // getNextStubForKey call already recorded a picked index in the token.
    val (resumedStub, resumedToken): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk2.impl, SliceKey.MIN, Some(retryToken1))
    assert(resumedStub.toString() != "Pod1")
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
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment2.generation)
      )
    }

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    val (assignedStub, token1): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, None)
    assert(assignedStub.toString() == "Pod10")
    assertResult(1.0)(assignedCallCount.totalChange())

    // Verify that the fallback resource is now one of the new resources, and not the assigned one.
    val newResources: Set[String] = Set("Pod10", "Pod20", "Pod30", "Pod40", "Pod50")
    val (stub2, token2): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, Some(token1))
    assert(newResources.contains(stub2.toString()))
    assert(stub2.toString() != "Pod10")
    assert(token2.fallbackPicked)
    assertResult(1.0)(fallbackCallCount.totalChange())

    // Both calls ran against the latest assignment, so neither reset the token.
    assertResult(0.0)(tokenResetCallCount.totalChange())
  }

  test("ClerkImpl.getNextStubForKey returns None when there is no assignment") {
    // Test plan: Verify that getNextStubForKey returns None when the clerk has no assignment,
    // regardless of whether the caller passes in a retry token. Verify this by creating a clerk
    // without seeding any assignment and calling getNextStubForKey both with None and with a
    // caller-supplied token, expecting None in both cases.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)

    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    val fallbackCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "fallback")
    val randomAssignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "randomAssigned")
    val tokenResetCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assignedAfterTokenReset")

    assert(clerk.impl.getNextStubForKey(SliceKey.MIN, None).isEmpty)
    assertResult(0.0)(assignedCallCount.totalChange())
    assertResult(0.0)(fallbackCallCount.totalChange())
    assertResult(0.0)(randomAssignedCallCount.totalChange())
    assertResult(0.0)(tokenResetCallCount.totalChange())

    // Neither call made a pick (the clerk has no assignment), so no metrics were recorded.
    val callerToken: RetryTokenImpl = RetryTokenImpl.create(Generation.EMPTY)
    assert(clerk.impl.getNextStubForKey(SliceKey.MIN, Some(callerToken)).isEmpty)
    assertResult(0.0)(assignedCallCount.totalChange())
    assertResult(0.0)(fallbackCallCount.totalChange())
    assertResult(0.0)(randomAssignedCallCount.totalChange())
    assertResult(0.0)(tokenResetCallCount.totalChange())
  }

  test(
    "ClerkImpl.getNextStubForKey never returns a fallback resource when the assignment has " +
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
    val clerk: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
    }

    val randomAssignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "randomAssigned")
    val assignedCallCount: MetricUtils.ChangeTracker[Double] =
      getNextStubForKeyCallTracker(target, "assigned")
    // The first call picks the single assigned resource.
    val (stub, token): (ResourceAddress, RetryTokenImpl) =
      getNextStubForKey(clerk.impl, SliceKey.MIN, None)
    assert(stub.toString() == "Pod1")
    assertResult(1.0)(assignedCallCount.totalChange())
    var lastTokenOpt: Option[RetryTokenImpl] = Some(token)
    // Each subsequent call returns a random assigned resource because there is no fallback
    // resource to advance to.
    for (i <- 1 to 5) {
      val (stub, token): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk.impl, SliceKey.MIN, lastTokenOpt)
      assert(stub.toString() == "Pod1")
      // The picked-index list stays at size 1: there is no fallback resource to advance to.
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
    val clerk1: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
    val clerk2: Clerk[ResourceAddress] = testEnv.createClerk(slicelet)
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
        clerk1.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
      )
      assert(
        clerk2.impl.forTest.getLatestAssignmentOpt.exists(_.generation == assignment.generation)
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
          getNextStubForKey(clerk1.impl, slice.lowInclusive, lastTokenOpt1)
        assertResult(1.0)(assignedCallCount.totalChange())
        assert(token1.pickedResourceIndices.size == i)
        assert(!token1.fallbackPicked)

        val (_, token2): (ResourceAddress, RetryTokenImpl) =
          getNextStubForKey(clerk2.impl, slice.lowInclusive, lastTokenOpt2)
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
      val (stub1, token1): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk1.impl, slice.lowInclusive, lastTokenOpt1)
      assertResult(1.0)(fallbackCallCount.totalChange())
      assert(token1.fallbackPicked)

      val (stub2, token2): (ResourceAddress, RetryTokenImpl) =
        getNextStubForKey(clerk2.impl, slice.lowInclusive, lastTokenOpt2)
      assertResult(2.0)(fallbackCallCount.totalChange())
      assert(token2.fallbackPicked)

      assert(stub1.toString() == stub2.toString())
    }
  }
}
