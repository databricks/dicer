package com.databricks.dicer.common

import scala.collection.mutable
import scala.concurrent.duration.{Duration, _}

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{ResourceAddress, SliceKey, Slicelet, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.testing.DatabricksTest

import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadWatcherTargetConfig
}
import com.databricks.dicer.assigner.config.{InternalTargetConfig, InternalTargetConfigMap}

/**
 * Tests exercising Dicer's sync protocol, which allows Clerks, Slicelets, and Assigners to learn
 * about assignments from each other.
 */
class GossipSuite extends DatabricksTest with TestName {
  type Clerk = com.databricks.dicer.external.Clerk[ResourceAddress]
  type Proposal = SliceMap[ProposedSliceAssignment]

  /** Test environment where the Assigner is configured with an in-memory store. */
  private val testEnv = InternalDicerTestEnvironment.create(
    TestAssigner.Config.create(assignerConf = getAssignerConf("in_memory", 0))
  )

  /**
   * Returns assigner configuration with specified store type (e.g. "in_memory", "etcd"),
   * and store incarnation, and where delays have been reduced to speed up tests.
   */
  private def getAssignerConf(storeType: String, storeIncarnation: Short) =
    new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.internal.cachingteamonly.watchServerSuggestedRpcTimeoutMillis" -> 1000,
        // This suite uses createDirectClerk, so we need to configure this setting as well.
        "databricks.dicer.assigner.assignerSuggestedClerkWatchTimeoutSeconds" -> 1,
        "databricks.dicer.assigner.store.type" -> storeType,
        "databricks.dicer.assigner.storeIncarnation" -> storeIncarnation
      )
    )

  // Sequence of sample assignment proposals that are designed to exercise diffing logic.
  private val PROPOSAL1: Proposal = createProposal(
    ("" -- "Dumbledore") -> Seq("pod0"),
    ("Dumbledore" -- "Gandalf") -> Seq("pod1"),
    ("Gandalf" -- "Luke") -> Seq("pod2"),
    ("Luke" -- "Picard") -> Seq("pod3"),
    ("Picard" -- ∞) -> Seq("pod2")
  )
  private val PROPOSAL2: Proposal = createProposal(
    ("" -- "Dumbledore") -> Seq("pod0"), // unchanged since PROPOSAL1
    ("Dumbledore" -- "Gandalf") -> Seq("pod3"),
    ("Gandalf" -- "Luke") -> Seq("pod2"), // unchanged since PROPOSAL1
    ("Luke" -- ∞) -> Seq("pod3")
  )
  private val PROPOSAL3: Proposal = createProposal(
    ("" -- "Dumbledore") -> Seq("pod0"), // unchanged since PROPOSAL1
    ("Dumbledore" -- "Gandalf") -> Seq("pod2"),
    ("Gandalf" -- "Luke") -> Seq("pod1"),
    ("Luke" -- ∞) -> Seq("pod3") // unchanged since PROPOSAL2
  )
  private val PROPOSALS: Seq[Proposal] = Vector(PROPOSAL1, PROPOSAL2, PROPOSAL3)

  /**
   * Creates a frozen assignment based on the given `proposal` that is a successor to `predecessor`.
   * Helpful for tests that inject authoritative-looking assignments directly into clients.
   */
  private def createFrozenSuccessor(predecessor: Assignment, proposal: Proposal): Assignment = {
    val proposedAssignment =
      ProposedAssignment(predecessorOpt = Some(predecessor), proposal)
    val generation = Generation(
      predecessor.generation.incarnation,
      predecessor.generation.number.value + 42000
    )
    proposedAssignment.commit(
      isFrozen = true,
      AssignmentConsistencyMode.Affinity,
      generation
    )
  }

  test("Assignment propagates from the Assigner to Slicelet to Clerk") {
    // Test plan: tests the normal distribution flow in which assignments flow from the store to the
    // Assigner to Slicelets and then Clerks. Writes a sequence of assignments to the store that are
    // designed to exercise assignment diffs when using `incrementalTestEnv`.
    // Note: non-open source changes to this test should also be applied to GossipWithEdsSuite.

    val target = Target(getSafeName)

    // Create a Slicelet.
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    // Create a Clerk that watches assignments via `slicelet`.
    val clerk: Clerk = testEnv.createClerk(slicelet)

    /** Waits until all parties have the given `assignment`. */
    def allAwaitAssignment(assignment: Assignment): Unit = {
      awaitAssignment(slicelet, assignment)
      awaitAssignment(testEnv, target, assignment)
      awaitAssignment(clerk, assignment)
    }

    // Wait for the Assigner to produce an initial assignment and for it to be received by the
    // Clerks and Slicelet.
    val assignment: Assignment =
      awaitAssignmentWithResources(testEnv, target, Set(slicelet.impl.squid))
    allAwaitAssignment(assignment)

    // Write a sequence of assignments that are designed to exercise diffing logic and verify
    // they are received by everyone.
    for (proposal: Proposal <- PROPOSALS) {
      val nextAssignment: Assignment =
        TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
      allAwaitAssignment(nextAssignment)
    }
  }

  test("Assignment propagates from the Assigner to Clerk directly") {
    // Test plan: test the distribution flow from the store to the Assigner and then to Clerks that
    // are directly connected to the Assigner. Write a sequence of assignments to the store that
    // are designed to exercise assignment diffs when using `incrementalTestEnv`.
    val target = Target(getSafeName)

    // Create two Clerks that watches assignments via the Assigner.
    val clerk1: Clerk = testEnv.createDirectClerk(target, initialAssignerIndex = 0)
    val clerk2: Clerk = testEnv.createDirectClerk(target, initialAssignerIndex = 0)

    /** Waits until all parties have the given `assignment`. */
    def allAwaitAssignment(assignment: Assignment): Unit = {
      awaitAssignment(testEnv, target, assignment)
      awaitAssignment(clerk1, assignment)
      awaitAssignment(clerk2, assignment)
    }

    // Inject an initial assignment.
    val assignment = TestUtils.awaitResult(
      testEnv
        .setAndFreezeAssignment(target, createProposal(("" -- ∞) -> Seq("devnull"))),
      Duration.Inf
    )

    // Wait for the initial assignment and for it to be received by the Clerks.
    allAwaitAssignment(assignment)

    // Write a sequence of assignments that are designed to exercise diffing logic and verify
    // they are received by everyone.
    for (proposal: Proposal <- PROPOSALS) {
      val nextAssignment: Assignment =
        TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
      allAwaitAssignment(nextAssignment)
    }
  }

  test("Assignment propagates from clients to other clients and Assigner") {
    // Test plan: tests the gossipy distribution flow in which assignments injected into Clerks and
    // Slicelets eventually propagate to all parties. The sequence of assignments introduced to the
    // system are designed to exercise assignment diffs when using `incrementalTestEnv`.

    val target = Target(getSafeName)

    // Use frozen assignments for the duration of the test to avoid the assigner generating its
    // own which would overwrite the ones we inject, and also create issues when attempting to
    // wait for an "initial" assignment (since multiple assignments may be generated as multiple
    // slicelets connect to the Assigner). Start with a simple blackhole assignment.
    val assignment1 =
      TestUtils.awaitResult(
        testEnv.setAndFreezeAssignment(
          target,
          createProposal(("" -- ∞) -> Seq("devnull"))
        ),
        Duration.Inf
      )

    // Create two Slicelets.
    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val slicelet2: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 12345, listenerOpt = None)

    // Create a Clerk that watches assignments via `slicelet1`.
    val clerk1: Clerk = testEnv.createClerk(slicelet1)

    // Create another Clerk that watches assignments directly via the Assigner.
    val clerk2: Clerk = testEnv.createDirectClerk(target, initialAssignerIndex = 0)

    /** Waits until all parties have the given `assignment`. */
    def allAwaitAssignment(assignment: Assignment): Unit = {
      awaitAssignment(slicelet1, assignment)
      awaitAssignment(slicelet2, assignment)
      awaitAssignment(testEnv, target, assignment)
      awaitAssignment(clerk1, assignment)
      awaitAssignment(clerk2, assignment)
    }

    // Wait for the initial assignment to be received by the Clerk and Slicelets.
    allAwaitAssignment(assignment1)

    // Now feed assignments to the subscribers and verify that they propagate to all other
    // participants. The assignment is frozen so that the Assigner doesn't try to overwrite it
    // (the assignment has the wrong resources).
    val assignment2: Assignment = createFrozenSuccessor(assignment1, PROPOSAL1)
    clerk1.impl.forTest.injectAssignment(assignment2)
    allAwaitAssignment(assignment2)

    // This time feed an assignment to slicelet1.
    val assignment3: Assignment = createFrozenSuccessor(assignment2, PROPOSAL2)
    slicelet1.impl.forTest.injectAssignment(assignment3)
    allAwaitAssignment(assignment3)

    // Feed an assignment to slicelet2.
    val assignment4: Assignment = createFrozenSuccessor(assignment3, PROPOSAL3)
    slicelet2.impl.forTest.injectAssignment(assignment4)
    allAwaitAssignment(assignment4)

    // Finally, feed an assignment to clerk2.
    val assignment5: Assignment = createFrozenSuccessor(assignment4, PROPOSAL1)
    clerk2.impl.forTest.injectAssignment(assignment5)
    allAwaitAssignment(assignment5)
  }

  test("Assignments from multi-replica back to single-replica flow in dicer system") {
    // Test plan: Verify that both the multi-replica assignment and single-replica assignment can
    // flow in the dicer system without any problems, and that when a multi-replica assignment is
    // changed back to a single-replica assignment, the system still work as expected.

    val target = Target(getSafeName)

    /** Asserts that the `assignment` assigns each Slice to exactly one resource. */
    def assertSingleReplica(assignment: Assignment): Unit = {
      for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
        assert(sliceAssignment.resources.size == 1)
      }
    }

    // Setup: 2 initial slicelets, and 2 clerks connected to each of the Slicelets respectively.
    val slicelet0: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val squid0: Squid = slicelet0.impl.squid
    val clerk0: Clerk = testEnv.createClerk(slicelet0)
    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 2345, listenerOpt = None)
    val squid1: Squid = slicelet1.impl.squid
    val clerk1: Clerk = testEnv.createClerk(slicelet1)

    // Setup: Wait for the Assigner to produce an initial assignment.
    val initialAssignment: Assignment =
      awaitAssignmentWithResources(testEnv, target, Set(squid0, squid1))
    // Verify: The initial assignment (generated by the assigner) should be single-replica, and
    // all parties in the system should be able to receive it.
    assertSingleReplica(initialAssignment)
    awaitAssignment(testEnv, target, initialAssignment)
    awaitAssignment(slicelet0, initialAssignment)
    awaitAssignment(slicelet1, initialAssignment)
    awaitAssignment(clerk0, initialAssignment)
    awaitAssignment(clerk1, initialAssignment)

    // Setup: A fake squid to be included in the multi-replica assignment, used to distinguish
    // the fake assignment and the assignment generated by the assigner in the next step.
    val otherSquid: Squid = createTestSquid("OtherPod")

    // Setup: Manually create and freeze an assignment with actually multiple replicas.
    val proposal: Proposal = createProposal(
      ("" -- "Dumbledore") -> Seq(squid0, squid1),
      ("Dumbledore" -- "Gandalf") -> Seq(squid0),
      ("Gandalf" -- "Luke") -> Seq(squid1),
      ("Luke" -- ∞) -> Seq(squid0, squid1, otherSquid)
    )
    testEnv.setAndFreezeAssignment(target, proposal)

    // Verify: All parties in the system should be able to receive the second assignment, which
    // is the multi-replica fake one.
    val assignment: Assignment =
      awaitAssignmentWithResources(testEnv, target, Set(squid0, squid1, otherSquid))
    awaitAssignment(testEnv, target, assignment)
    awaitAssignment(slicelet0, assignment)
    awaitAssignment(slicelet1, assignment)
    awaitAssignment(clerk0, assignment)
    awaitAssignment(clerk1, assignment)

    // Setup: Unfreeze the assignment, and connect a new Slicelet to the assigner to trigger it
    // to generate a new assignment. Also create a Clerk connected to it for better coverage.
    testEnv.unfreezeAssignment(target)
    val slicelet2: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 3456, listenerOpt = None)
    val squid2: Squid = slicelet2.impl.squid
    val clerk2: Clerk = testEnv.createClerk(slicelet2)

    // Verify: Assigner should be able to create a new assignment containing the new Slicelet.
    val assignmentBackToSingleReplica: Assignment =
      awaitAssignmentWithResources(testEnv, target, Set(squid0, squid1, squid2))
    // Verify: The third assignment generated by assigner should be single-replica.
    assertSingleReplica(assignmentBackToSingleReplica)
    // Verify: All parties in the system should be able to receive the new assignment.
    awaitAssignment(testEnv, target, assignmentBackToSingleReplica)
    awaitAssignment(slicelet0, assignmentBackToSingleReplica)
    awaitAssignment(slicelet1, assignmentBackToSingleReplica)
    awaitAssignment(slicelet2, assignmentBackToSingleReplica)
    awaitAssignment(clerk0, assignmentBackToSingleReplica)
    awaitAssignment(clerk1, assignmentBackToSingleReplica)
    awaitAssignment(clerk2, assignmentBackToSingleReplica)
  }

  test("Generate and flow multi-replica assignment") {
    // Test plan: Verify that dicer assigner will be able to generate multi-replica assignment as
    // expected when minReplicas > 1, and can flow it to slicelets and clerks. Verify this by
    // creating a dicer test environment with minReplicas > 1, adding new slicelets and clerks one
    // by one, supplying loads to a hot key, and verifying that assignments with expected number of
    // replicas can be generated by assigner and observed by clerks.
    //
    // Note that the main tests for generating the assignment with multiple replicas is in
    // com.databricks.dicer.assigner.AlgorithmSuite. This test is for checking the flowing of
    // assignment with multiple replicas from the Algorithm (assigner) to Slicelets and Clerks.

    val targetName: String = getSafeName
    val target = Target(targetName)

    // Setup: Create a separate dicer test environment for this test case where minReplicas = 2 and
    // maxReplicas = 3. Note that we need to create a new test environment rather than using the
    // dynamic config tool, because dynamic config doesn't support changing the value of
    // LoadBalancingInterval and it's always overwritten to 1 minute, which is too long for the
    // test.
    // TODO(<internal bug>): Change this test case to use dynamic config after it supports setting
    //                  LoadBalancingInterval.
    val multiReplicaTestEnv: InternalDicerTestEnvironment = {
      val multiReplicaConfigs: Map[TargetName, InternalTargetConfig] = Map(
        TargetName(targetName) -> InternalTargetConfig.forTest.DEFAULT
          .copy(
            keyReplicationConfig = KeyReplicationConfig(minReplicas = 2, maxReplicas = 3),
            // Override the load report min time window, so the load reports from the tests will be
            // considered.
            loadWatcherConfig = LoadWatcherTargetConfig.DEFAULT.copy(minDuration = 1.nanosecond),
            // Override the load balancing internal to 1 second so load balancing can happen
            // within the timeout of the test.
            loadBalancingConfig = InternalTargetConfig.forTest.DEFAULT.loadBalancingConfig
              .copy(loadBalancingInterval = 1.second)
          )
      )
      val enableMultiReplicaConfigMap: InternalTargetConfigMap =
        InternalTargetConfigMap.create(configScopeOpt = None, targetConfigMap = multiReplicaConfigs)
      InternalDicerTestEnvironment.create(
        config = TestAssigner.Config.create(tlsOptionsOpt = None),
        targetConfigMap = enableMultiReplicaConfigMap
      )
    }

    /**
     * Asserts that all the slices in `assignment` have no less than `minReplicas` replicas and
     * no more than `maxReplicas` replicas.
     */
    def assertNumReplicas(assignment: Assignment, minReplicas: Int, maxReplicas: Int): Unit = {
      for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
        assert(sliceAssignment.resources.size >= minReplicas)
        assert(sliceAssignment.resources.size <= maxReplicas)
      }
    }

    // Setup: Create and connect the first slicelet and the first clerk.
    val slicelet0: Slicelet =
      multiReplicaTestEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val squid0: Squid = slicelet0.impl.squid
    val clerk0: Clerk = multiReplicaTestEnv.createClerk(slicelet0)

    // Verify: The initial assignment should have only one replica for each slice, because there is
    // only one available resource.
    val assignment0: Assignment =
      awaitAssignmentWithResources(multiReplicaTestEnv, target, Set(squid0))
    assertNumReplicas(assignment0, minReplicas = 1, maxReplicas = 1)

    // Setup: Create and connect the second slicelet and the second clerk.
    val slicelet1: Slicelet =
      multiReplicaTestEnv.createSlicelet(target).start(selfPort = 2345, listenerOpt = None)
    val squid1: Squid = slicelet1.impl.squid
    val clerk1: Clerk = multiReplicaTestEnv.createClerk(slicelet1)

    // Verify: After another slicelet is added, the assignment should have at least 2 replicas for
    // each slice as minReplicas == 2. There is not any load currently, so no slice should have
    // more replicas than 2.
    val assignment1: Assignment =
      awaitAssignmentWithResources(multiReplicaTestEnv, target, Set(squid0, squid1))
    assertNumReplicas(assignment1, minReplicas = 2, maxReplicas = 2)

    // Verify: All the clerks should observe that 1 SliceKey is now assigned to 2 resources.
    AssertionWaiter(
      "Clerks observed multi-replica assignment",
      pollInterval = 300.milliseconds // Large poll interval as the polling is expensive.
    ).await {
      for (clerk: Clerk <- Seq(clerk0, clerk1)) {
        val returnedResources = mutable.Set[ResourceAddress]()
        for (_ <- 0 until 100) {
          val resource: ResourceAddress = clerk.getStubForKey(fp("Kili")).get
          returnedResources += resource
        }
        assert(returnedResources == Set(squid0.resourceAddress, squid1.resourceAddress))
      }
    }

    // Setup: Create and connect the third slicelet and the third clerk.
    val slicelet2: Slicelet =
      multiReplicaTestEnv.createSlicelet(target).start(selfPort = 3456, listenerOpt = None)
    val squid2: Squid = slicelet2.impl.squid
    val clerk2: Clerk = multiReplicaTestEnv.createClerk(slicelet2)

    // Setup: supply enormous load to a hot key, so that it should be replicated as possible.
    val hotSliceKey: SliceKey = toSliceKey(1)
    slicelet0.createHandle(hotSliceKey).incrementLoadBy(1e9.toInt)
    slicelet1.createHandle(hotSliceKey).incrementLoadBy(1e9.toInt)
    slicelet2.createHandle(hotSliceKey).incrementLoadBy(1e9.toInt)

    AssertionWaiter(
      "Generate and sync assignment with hot key having 3 replicas",
      pollInterval = 300.milliseconds // Large poll interval as the polling is expensive.
    ).await {

      val assignment2: Assignment =
        TestUtils
          .awaitResult(multiReplicaTestEnv.testAssigner.getAssignment(target), Duration.Inf)
          .get

      // Verify: the new assignment should observe all resources, and obey the replication config.
      assert(assignment2.assignedResources == Set(squid0, squid1, squid2))
      assertNumReplicas(assignment2, minReplicas = 2, maxReplicas = 3)

      // Verify: The hot key is assigned to all the 3 resources.
      assert(assignment2.sliceMap.lookUp(hotSliceKey).resources == Set(squid0, squid1, squid2))

      // Verify: All the clerks should observe that the hot key has been assigned to all the
      // 3 resources.
      for (clerk: Clerk <- Seq(clerk0, clerk1, clerk2)) {
        val returnedResources = mutable.Set[ResourceAddress]()
        for (_ <- 0 until 100) {
          val resource: ResourceAddress = clerk.getStubForKey(hotSliceKey).get
          returnedResources += resource
        }
        assert(
          returnedResources == Set(
            squid0.resourceAddress,
            squid1.resourceAddress,
            squid2.resourceAddress
          )
        )
      }
    }

    multiReplicaTestEnv.stop()
  }
}
