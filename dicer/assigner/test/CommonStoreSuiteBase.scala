package com.databricks.dicer.assigner

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future}
import scala.util.Random

import io.grpc.Status

import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.{
  AssertionWaiter,
  Cancellable,
  LoggingStreamCallback,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  StatusOr
}
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  AssignmentConsistencyMode,
  Assignment,
  Generation,
  Incarnation,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceMapHelper
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.testing.DatabricksTest

/**
 * Common base class for [[Store]] testing - includes common test cases and test helpers.
 */
abstract class CommonStoreSuiteBase extends DatabricksTest with TestName {
  protected val pool: SequentialExecutionContextPool =
    SequentialExecutionContextPool.create("test-pool", numThreads = 2)
  protected val rng: Random = new Random(42)

  /** Returns an instance of the particular [[Store]] implementation under test. */
  def createStore(sec: SequentialExecutionContext): Store

  /** Cleanup the store resources. */
  def destroyStore(store: Store): Unit

  /**
   * Convenience method awaiting a successfully committed assignment. Throws an assertion error if
   * the supplied future yields some other result.
   */
  protected def awaitCommitted(writeResultFut: Future[WriteAssignmentResult]): Assignment = {
    TestUtils.awaitResult(writeResultFut, Duration.Inf) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case result => fail(s"Unexpected result $result")
    }
  }

  /**
   * Convenience method awaiting an OCC failure and returning the existing assignment. Throws an
   * assertion error if the supplied future yields some other result.
   */
  protected def awaitOccFailure(writeResultFut: Future[WriteAssignmentResult]): Generation = {
    TestUtils.awaitResult(writeResultFut, Duration.Inf) match {
      case WriteAssignmentResult.OccFailure(existingAssignmentGeneration) =>
        existingAssignmentGeneration
      case result => fail(s"Unexpected result $result")
    }
  }

  /**
   * Helper using `watchAssignments` to await an assignment with generation greater than
   * `knownGeneration`.
   */
  protected def awaitAssignment(
      store: Store,
      target: Target,
      knownGeneration: Generation = Generation.EMPTY): Assignment = {
    val callback =
      new LoggingStreamCallback[Assignment](pool.createExecutionContext("stream-cb"))
    val cancellable: Cancellable = store.watchAssignments(target, callback)
    try {
      callback.waitForPredicate(assignment => assignment.generation > knownGeneration, 0)
      callback.getLog.last.get
    } finally cancellable.cancel(Status.CANCELLED)
  }

  /** Helper to get latest known assignment for the target. */
  protected def getLatestKnownAssignment(store: Store, target: Target): Option[Assignment] = {
    TestUtils.awaitResult(store.getLatestKnownAssignment(target), Duration.Inf)
  }

  /**
   * Returns a proposed assignment with the given predecessor. Since the [[Store]] and these tests
   * are agnostic about proposal contents, the assignments are created at random.
   */
  protected def createProposal(predecessorOpt: Option[Assignment]): ProposedAssignment = {
    ProposedAssignment(predecessorOpt, createProposal())
  }

  /** Returns a proposed Slice map. */
  protected def createProposal(): SliceMap[ProposedSliceAssignment] = {
    createRandomProposal(
      numSlices = 10,
      Vector(createTestSquid("pod0"), createTestSquid("pod1"), createTestSquid("pod2")),
      numMaxReplicas = 1,
      rng
    )
  }

  test("Store write assignments for different targets") {
    // Test plan: write assignments for two targets and verify that they are returned by
    // `watchAssignments`.

    val store = createStore(pool.createExecutionContext(getSafeName))
    val target1 = Target("target1")
    val target2 = Target("target2")
    val proposal1: ProposedAssignment = createProposal(predecessorOpt = None)
    val proposal2: ProposedAssignment = createProposal(predecessorOpt = None)
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(target1, shouldFreeze = false, proposal1)
      )
    assert(
      assignment1 == proposal1
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          assignment1.generation
        )
    )
    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(target2, shouldFreeze = true, proposal2)
      )
    assert(
      assignment2 == proposal2
        .commit(
          isFrozen = true,
          AssignmentConsistencyMode.Affinity,
          assignment2.generation
        )
    )

    // Watch for the written assignments.
    assert(awaitAssignment(store, target1) == assignment1)
    assert(awaitAssignment(store, target2) == assignment2)

    destroyStore(store)
  }

  test("Store write multiple assignments for a single target") {
    // Test plan: write two assignments in sequence to the store and verify that they are returned
    // by `watchAssignments`.

    val store = createStore(pool.createExecutionContext(getSafeName))
    val target = Target("target")
    val proposal1: ProposedAssignment = createProposal(predecessorOpt = None)

    // Write and verify the first assignment.
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = true, proposal1)
      )
    assert(
      assignment1 == proposal1
        .commit(
          isFrozen = true,
          AssignmentConsistencyMode.Affinity,
          assignment1.generation
        )
    )
    assert(awaitAssignment(store, target) == assignment1)

    // Write and verify the second assignment.
    val proposal2: ProposedAssignment =
      createProposal(predecessorOpt = Some(assignment1))
    val assignment2: Assignment = awaitCommitted(
      store.writeAssignment(target, shouldFreeze = false, proposal2)
    )
    assert(
      assignment2 == proposal2
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          assignment2.generation
        )
    )
    assert(awaitAssignment(store, target, knownGeneration = assignment1.generation) == assignment2)

    destroyStore(store)
  }

  test("Store write assignments with multiple replicas randomized") {
    // Test plan: Verify that the store can write assignments where each Slice may have more than 1
    // replica. Verify this by iteratively creating random assignment proposals with multiple
    // replicas, writing and committing proposals to the store, verifying the store contains
    // the expected assignment result, and using the committed assignment as the base assignment for
    // the next iteration.

    val store = createStore(pool.createExecutionContext(getSafeName))
    val target = Target("target")
    val resources: IndexedSeq[Squid] = (0 until 10).map { index: Int =>
      createTestSquid(s"Pod$index")
    }

    var prevAssignmentOpt: Option[Assignment] = None
    for (_ <- 0 until 100) { // 100 store writes.
      // Setup: Create a random assignment proposal. Use the committed assignment from the previous
      // iteration as the base.
      val proposal = ProposedAssignment(
        predecessorOpt = prevAssignmentOpt,
        createRandomProposal(numSlices = 10, resources, numMaxReplicas = 5, Random)
      )
      val storeCommittedAssignment: Assignment = awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal)
      )
      val expectedCommittedAssignment: Assignment = proposal.commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        storeCommittedAssignment.generation
      )

      // Verify: Committed assignment result is as expected, and the store actually contains the
      // committed assignment.
      assert(storeCommittedAssignment == expectedCommittedAssignment)
      assert(
        awaitAssignment(
          store,
          target,
          prevAssignmentOpt.map((_: Assignment).generation).getOrElse(Generation.EMPTY)
        ) == expectedCommittedAssignment
      )

      prevAssignmentOpt = Some(storeCommittedAssignment)
    }
    destroyStore(store)
  }

  test("Store chooses increasing generations that maintain the same incarnation") {
    // Test plan: Verify that when updating an existing assignment, the newly chosen assignment
    // generation is greater than the previous one and has the same incarnation as the predecessor.
    // Verify this across several targets.

    val store = createStore(pool.createExecutionContext(getSafeName))

    for (i <- 0 until 100) {
      val target = Target(s"target-$i")
      // Write initial assignment into the store.
      var currentAssignment: Assignment =
        awaitCommitted(
          store.writeAssignment(
            target,
            shouldFreeze = true,
            createProposal(predecessorOpt = None)
          )
        )
      // Perform several updates to the assignment and verify that the new assignment has a greater
      // generation which maintains the same incarnation.
      for (_ <- 0 until 3) {
        val newAssignment: Assignment = awaitCommitted(
          store.writeAssignment(
            target,
            shouldFreeze = false,
            createProposal(predecessorOpt = Some(currentAssignment))
          )
        )
        assert(newAssignment.generation > currentAssignment.generation)
        assert(newAssignment.generation.incarnation == currentAssignment.generation.incarnation)
        currentAssignment = newAssignment
      }
    }

    destroyStore(store)
  }

  test("Store OCC failure when writing an assignment with wrong predecessor generation") {
    // Test plan: verify that the store performs an OCC check on the current assignment in the store
    // when writing new assignments, and on an OCC failure returns the current generation of
    // assignment in the store. Verify this by writing an assignment to the store successfully, then
    // attempting to write new assignments to the store with incorrect predecessor assignment
    // generations. Also verify that writes succeed when the assignment generation matches.

    val store = createStore(pool.createExecutionContext(getSafeName))
    val target = Target("target")

    val proposal1: ProposedAssignment = createProposal(predecessorOpt = None)
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal1)
      )

    // Try some writes with the wrong previous generation.
    val proposal2: SliceMap[ProposedSliceAssignment] = createProposal()
    val wrongPreviousGens = Seq(
      assignment1.generation.copy(number = assignment1.generation.number.value - 1),
      assignment1.generation.copy(number = assignment1.generation.number.value + 1)
    )
    for (previousGen <- wrongPreviousGens) {
      val predecessor: Assignment =
        createProposal(predecessorOpt = None)
          .commit(
            isFrozen = false,
            AssignmentConsistencyMode.Affinity,
            previousGen
          )
      val proposedAssignment =
        ProposedAssignment(predecessorOpt = Some(predecessor), proposal2)
      val existingAssignmentGeneration: Generation =
        awaitOccFailure(
          store.writeAssignment(
            target,
            shouldFreeze = false,
            proposedAssignment
          )
        )
      assert(existingAssignmentGeneration == assignment1.generation)
    }
    // Repeat the write attempt with the correct previous generation, which should succeed.
    val proposedAssignment2 =
      ProposedAssignment(predecessorOpt = Some(assignment1), proposal2)
    val assignment2: Assignment = awaitCommitted(
      store.writeAssignment(
        target,
        shouldFreeze = false,
        proposedAssignment2
      )
    )
    assert(
      assignment2 == proposedAssignment2
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          assignment2.generation
        )
    )

    // Verify that the store also returns an OccFailure in the case where there is no existing
    // assignment in the store. Attempt a write for target2 which has no current assignment, with
    // some predecessor.
    val target2 = Target("target2")
    val existingAssignmentGeneration: Generation =
      awaitOccFailure(
        store.writeAssignment(
          target2,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment1))
        )
      )
    assert(existingAssignmentGeneration == Generation.EMPTY)

    destroyStore(store)
  }

  test("Store watch assignments from write") {
    // Test plan: register a callback to be informed of assignments for a particular target. Verify
    // that new assignments are supplied to the callback when the store learns about them via
    // `writeAssignment`.

    val storeSec: SequentialExecutionContext = pool.createExecutionContext(s"$getSafeName-store")
    val store = createStore(storeSec)
    val target = Target("target")

    // Seed an initial assignment, which should be supplied to the callback when it is added.
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // Register a callback. Use a different SequentialExecutionContext than the store, just in case
    // there are any assumptions lurking in the code about a shared SEC.
    val callbackSec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-callback")
    val callback = new LoggingStreamCallback[Assignment](callbackSec)
    val cancellable: Cancellable = store.watchAssignments(target, callback)

    // The initial assignment should be supplied immediately.
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()
    expectedLog.append(StatusOr.success(assignment1))
    AssertionWaiter("Wait for assignment1").await {
      assert(callback.getLog == expectedLog)
    }

    // Write an assignment to the store and verify it is received by the callback.
    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(Some(assignment1))
        )
      )
    expectedLog.append(StatusOr.success(assignment2))

    AssertionWaiter("Wait for assignment2").await {
      assert(callback.getLog == expectedLog)
    }

    // Cancel the watcher then write a final assignment that should not be received by the watcher.
    cancellable.cancel(Status.CANCELLED)
    val writeFuture: Future[WriteAssignmentResult] = storeSec.flatCall {
      // We hop onto the store SEC to attempt the write because we want to make sure the store sees
      // the write _after_ it sees the cancel request above.
      store.writeAssignment(
        target,
        shouldFreeze = false,
        createProposal(predecessorOpt = Some(assignment2))
      )
    }
    awaitCommitted(writeFuture)

    // Checking that something doesn't eventually happen is impossible, but there are some hacks we
    // can use that improve the odds we'll observe the hypothetical unexpected assignment post-
    // cancellation:
    //  - We first "drain" the SECs used by both the store and callback to hopefully shake loose any
    //    pending callbacks. We drain pending commands by enqueuing another command and waiting for
    //    it to run (the SECs are FIFO).
    //  - Then we sleep for a bit.
    // If the callback still hasn't been invoked with any new assignments, we consider that good
    // enough.
    TestUtils.awaitResult(storeSec.call {}, Duration.Inf)
    TestUtils.awaitResult(callbackSec.call {}, Duration.Inf)
    Thread.sleep( /*millis=*/ 10)
    assert(callback.getLog == expectedLog)

    destroyStore(store)
  }

  test("Store write assignment shouldFreeze") {
    // Test plan: write an assignment with `shouldFreeze` false or true. Verify that the `isFrozen`
    // bit is set on the assignment based on the value of the `shouldFreeze` parameter.

    val sec: SequentialExecutionContext = pool.createExecutionContext(getSafeName)
    val store = createStore(sec)
    val target = Target(getSafeName)
    var predecessorOpt: Option[Assignment] = None
    for (shouldFreeze <- Seq(true, false)) {
      val proposal: ProposedAssignment = predecessorOpt match {
        case Some(predecessor) =>
          // Keep same assignment to avoid creating subslice annotations.
          val sliceAssignments: SliceMap[ProposedSliceAssignment] =
            predecessor.sliceMap.map(
              SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR
            ) { sliceAssignment =>
              ProposedSliceAssignment(
                sliceAssignment.slice,
                sliceAssignment.resources,
                sliceAssignment.primaryRateLoadOpt
              )
            }
          ProposedAssignment(Some(predecessor), sliceAssignments)
        case None => createProposal(predecessorOpt = None)
      }
      val assignment: Assignment =
        awaitCommitted(
          store.writeAssignment(target, shouldFreeze, proposal)
        )
      assert(assignment.isFrozen == shouldFreeze)
      assert(
        assignment ==
        proposal.commit(
          shouldFreeze,
          AssignmentConsistencyMode.Affinity,
          assignment.generation
        )
      )
      predecessorOpt = Some(assignment)
    }
    destroyStore(store)
  }

  test("informAssignment variations") {
    // Test plan: verify that the store updates its cached assignment when it learns about an
    // assignment from an external source via `informAssignment` that has a higher generation than
    // the one it already knows. Assignments with the same or lower generations should be ignored.

    val sec: SequentialExecutionContext = pool.createExecutionContext(getSafeName)
    val store = createStore(sec)
    val target = Target(getSafeName)

    // Register a watcher so that we can verify that the expected assignments are incorporated by
    // the store, and hopefully spot any unexpected assignments as well: while the watcher is not
    // guaranteed to observe every assignment written to the store, it is likely to.
    val callback = new LoggingStreamCallback[Assignment](sec)
    store.watchAssignments(target, callback)
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()

    // Simulate a normal write to initialize the store.
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )
    expectedLog.append(StatusOr.success(assignment1))

    AssertionWaiter("Wait for assignment1").await {
      assert(callback.getLog == expectedLog)
    }

    // Supply the same assignment we just wrote. It should be ignored (we do not add it to
    // `expectedLog`).
    store.informAssignment(target, assignment1)

    // Supply an assignment with an lower generation. It should also be ignored.
    val assignment2: Assignment =
      createProposal(predecessorOpt = None).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        assignment1.generation.copy(number = assignment1.generation.number.value - 1)
      )
    store.informAssignment(target, assignment2)

    // Supply a newer assignment. It should be cached.
    val assignment3: Assignment =
      createProposal(predecessorOpt = None).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        assignment1.generation.copy(number = assignment1.generation.number.value + 1)
      )
    store.informAssignment(target, assignment3)
    expectedLog.append(StatusOr.success(assignment3))
    AssertionWaiter("Wait for assignment3").await {
      assert(callback.getLog == expectedLog)
    }
  }

  test("informAssignment ignores assignments from other store incarnations") {
    // Test plan: verify that informAssignment with an assignment incarnation that has a store
    // incarnation different from that of the store is ignored (i.e. the assignment in cache still
    // has the same store incarnation of the store).

    val store = createStore(pool.createExecutionContext(getSafeName))
    val target = Target(getSafeName)

    // Write an initial assignment.
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // Generated assignment should have the same store incarnation as the store.
    val assignment1StoreIncarnation: Long = assignment1.generation.incarnation.value
    assert(assignment1StoreIncarnation == store.storeIncarnation.value)

    // Generate assignments with different store incarnations than the current assignment.
    for (storeIncarnation <- Seq(
        assignment1StoreIncarnation + 1,
        assignment1StoreIncarnation + 42
      )) {
      val newGeneration = Generation(Incarnation(storeIncarnation), 47)
      val durableAssignment: Assignment =
        createProposal(predecessorOpt = None)
          .commit(
            isFrozen = false,
            AssignmentConsistencyMode.Affinity,
            newGeneration
          )

      // Store should not cache the assignment, since it belongs to another generation
      store.informAssignment(target, durableAssignment)
      assert(awaitAssignment(store, target) == assignment1)
    }
  }

  test("Inform assignment before target write or watch") {
    // Test plan: verify that the store can learn of assignments via `informAssignment`
    // independently of other operations such as watch or write.

    val store: Store = createStore(pool.createExecutionContext(getSafeName))
    val target = Target("target")

    // Inform the store about a new assignment.
    val proposal1 = createProposal(predecessorOpt = None)
    val assignment1 = proposal1.commit(
      isFrozen = true,
      AssignmentConsistencyMode.Affinity,
      Generation(
        store.storeIncarnation,
        Instant.now().toEpochMilli
      )
    )
    store.informAssignment(target, assignment1)

    // Store should learn about assignment3.
    AssertionWaiter("Wait for assignment1 to visible in Store").await {
      assert(getLatestKnownAssignment(store, target).contains(assignment1))
    }

    destroyStore(store)
  }
}
