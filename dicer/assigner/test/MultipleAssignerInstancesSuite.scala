package com.databricks.dicer.assigner

import java.net.URI
import javax.annotation.concurrent.NotThreadSafe

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, FiniteDuration}

import com.databricks.conf.Configs
import com.databricks.caching.util.{
  AssertionWaiter,
  EtcdTestEnvironment,
  FakeSequentialExecutionContextPool,
  FakeTypedClock
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.assigner.MultipleAssignerInstancesSuite.{
  AssignerInstance,
  DRIVER_CONFIG,
  NUM_ASSIGNERS,
  advanceClockBySync,
  createAssignerInstance,
  verifyAssignerFunctionality
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.{StaticTargetConfigProvider, InternalTargetConfigMap}
import com.databricks.dicer.assigner.config.TargetConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
import com.databricks.dicer.client.TestClientUtils
import com.databricks.dicer.common.InternalDicerTestEnvironment.InternalTargetConfigMapWithDefault
import com.databricks.dicer.common.{Assignment, TestAssigner}
import com.databricks.dicer.external.{Clerk, ClerkConf, ResourceAddress, Slicelet, Target}
import com.databricks.testing.DatabricksTest

/**
 * Test suite for verifying that multiple assigner instances write to different etcd namespaces.
 *
 * This suite tests the behavior of multiple dicer assigner instances running concurrently,
 * ensuring they properly isolate their data by writing to different etcd namespaces based
 * on their service name configuration.
 */
private class MultipleAssignerInstancesSuite extends DatabricksTest with TestName {

  /** Dockerized etcd instance shared between all assigner instances. */
  private val dockerizedEtcd = EtcdTestEnvironment.create()

  /** A sequence number that helps generating a unique slicelet port. */
  private var sliceletPortSequenceNumber: Int = 31234

  override def afterAll(): Unit = {
    dockerizedEtcd.deleteAll()
    dockerizedEtcd.close()
  }

  /** Returns a unique port number for a slicelet. */
  private def getNextSliceletPortNumber: Int = {
    sliceletPortSequenceNumber += 1
    sliceletPortSequenceNumber
  }

  /**
   * Creates and returns two assigner instances that share `dockerizedEtcd` but have different
   * service names and thus different store namespace prefixes. Each instance's fake clock is
   * advanced such that preferred assigner selection is triggered.
   */
  private def createAndSetUpAssignerInstances(): (AssignerInstance, AssignerInstance) = {
    val assignerInstance1: AssignerInstance = createAssignerInstance(
      storeNamespacePrefix = "",
      numAssigners = NUM_ASSIGNERS,
      dockerizedEtcd = dockerizedEtcd
    )
    val assignerInstance2: AssignerInstance = createAssignerInstance(
      storeNamespacePrefix = "untrusted",
      numAssigners = NUM_ASSIGNERS,
      dockerizedEtcd = dockerizedEtcd
    )

    // Calling `getAssignerInfoBlocking` on each assigner is necessary to ensure that assigners are
    // fully initialized before starting test cases that advance the fake clocks.
    for (assigner: TestAssigner <- assignerInstance1.testAssigners) {
      assigner.getAssignerInfoBlocking()
    }
    for (assigner: TestAssigner <- assignerInstance2.testAssigners) {
      assigner.getAssignerInfoBlocking()
    }

    // Advance the clock to trigger initial preferred assigner selection
    advanceClockBySync(
      assignerInstance1.fakeClock,
      DRIVER_CONFIG.initialPreferredAssignerTimeout,
      assignerInstance1.testAssigners
    )
    advanceClockBySync(
      assignerInstance2.fakeClock,
      DRIVER_CONFIG.initialPreferredAssignerTimeout,
      assignerInstance2.testAssigners
    )

    (assignerInstance1, assignerInstance2)
  }

  test("Multiple assigner instances write to different assignments etcd namespaces") {
    // Test plan: Verify that assignments for separate assigner instances are correctly written to
    // separate etcd namespaces. Verify this by creating multiple assigner instances, creating
    // slicelets in each to trigger assignment writes, and checking that the expected assignment
    // namespaces contain data for the expected targets.

    // Setup: Create and set up two assigner instances that share an underlying etcd instance.
    val (assignerInstance1, assignerInstance2) = createAndSetUpAssignerInstances()
    try {
      // Setup: Create slicelets with different targets in the different assigner instances.
      val safeName: String = getSafeName
      val target1: Target = Target(s"${safeName.substring(0, safeName.length - 2)}-1")
      assignerInstance1.createSlicelet(target1).start(getNextSliceletPortNumber, listenerOpt = None)
      val target2: Target = Target(s"${safeName.substring(0, safeName.length - 2)}-2")
      assignerInstance2.createSlicelet(target2).start(getNextSliceletPortNumber, listenerOpt = None)

      // Verify: Assignment data for the expected target is written to the expected namespace for
      // each assigner instance.
      AssertionWaiter("Wait for data in etcd").await {
        assert(
          dockerizedEtcd.getPrefix(s"assignments/${target1.toParseableDescription}").nonEmpty
        )
        assert(
          dockerizedEtcd
            .getPrefix(s"untrusted-assignments/${target2.toParseableDescription}")
            .nonEmpty
        )
      }

      // Verify: Assignment data for each target is not written to the namespace of the assigner
      // who is not handling the target. For example, verify that `target2` handled by assigner 2
      // does not have assignment data written to assigner 1's namespace. Note that since we are
      // verifying that some event does *not* occur, these assertions do not guarantee that at some
      // later point, an unexpected write does not occur. However, since the `AssertionWaiter` above
      // waits until assignment data is written to the expected namespaces, this check helps confirm
      // that unexpected writes did not occur before or during these expected writes.
      assert(
        dockerizedEtcd.getPrefix(s"assignments/${target2.toParseableDescription}").isEmpty
      )
      assert(
        dockerizedEtcd
          .getPrefix(s"untrusted-assignments/${target1.toParseableDescription}")
          .isEmpty
      )
    } finally {
      assignerInstance1.stop()
      assignerInstance2.stop()
      dockerizedEtcd.deleteAll()
    }
  }

  test(
    "Multiple assigner instances shard same target independently"
  ) {
    // Test plan: Verify that multiple assigner instances shard different Slicelets for the same
    // target independently. Verify this by adding different Slicelets for the same target to
    // different assigner instances and checking that the assignments generated by the preferred
    // assigner in each instance contain the expected number of resources based on the number of
    // Slicelets added.

    // Setup: Create and set up two assigner instances that share an underlying etcd instance.
    val (assignerInstance1, assignerInstance2) = createAndSetUpAssignerInstances()
    try {
      // Setup: Create slicelets with same target in the different assigner instances.
      val target: Target = Target(getSafeName)
      assignerInstance1.createSlicelet(target).start(getNextSliceletPortNumber, listenerOpt = None)
      assignerInstance2.createSlicelet(target).start(getNextSliceletPortNumber, listenerOpt = None)
      assignerInstance2.createSlicelet(target).start(getNextSliceletPortNumber, listenerOpt = None)

      val preferredAssigner1: TestAssigner =
        PreferredAssignerTestHelper.getConvergedPreferredAssigner(assignerInstance1.testAssigners)
      val preferredAssigner2: TestAssigner =
        PreferredAssignerTestHelper.getConvergedPreferredAssigner(assignerInstance2.testAssigners)

      // Verify: Assignments for `target` in assigner instance 2 have two resources (Slicelets).
      AssertionWaiter("Wait for both Slicelets to be assigned in instance 2").await {
        val assignmentOpt: Option[Assignment] =
          TestUtils.awaitResult(preferredAssigner2.getAssignment(target), Duration.Inf)
        assert(assignmentOpt.isDefined)
        assert(assignmentOpt.get.assignedResources.size == 2)
      }

      // Verify: Assignments for `target` in assigner instance 1 have one resource.
      AssertionWaiter("Wait for single Slicelet to be assigned in instance 1").await {
        val assignmentOpt: Option[Assignment] =
          TestUtils.awaitResult(preferredAssigner1.getAssignment(target), Duration.Inf)
        assert(assignmentOpt.isDefined)
        assert(assignmentOpt.get.assignedResources.size == 1)
      }
    } finally {
      assignerInstance1.stop()
      assignerInstance2.stop()
      dockerizedEtcd.deleteAll()
    }
  }

  test("Multiple assigner instances write to different preferred assigner etcd namespaces") {
    // Test plan: Verify that preferred assigners for separate assigner instances are correctly
    // chosen when the instance share an underlying etcd instance. Verify this by creating multiple
    // assigner instances and then verifying the preferred assigner functionality in each instance
    // using the `PreferredAssignerTestHelper.verifyPreferredAssignerFunctionality` test helper.

    // Setup: Create and set up two assigner instances that share an underlying etcd instance.
    val (assignerInstance1, assignerInstance2) = createAndSetUpAssignerInstances()

    try {
      // Verify: Preferred assigners are correctly chosen in each separate assigner instance.
      verifyAssignerFunctionality(
        assignerInstance1,
        // The underscore passes the function by value instead of calling the function and passing
        // its return value.
        getNextSliceletPortNumber _
      )
      verifyAssignerFunctionality(
        assignerInstance2,
        getNextSliceletPortNumber _
      )
    } finally {
      // Setup: Clean up the assigner instances and reset the underlying shared etcd instance.
      assignerInstance1.stop()
      assignerInstance2.stop()
      dockerizedEtcd.deleteAll()
    }
  }
}

/**
 * Companion object containing utility methods and classes for [[MultipleAssignerInstancesSuite]].
 *
 * Provides factory methods for creating assigner instances and helper utilities.
 */
private object MultipleAssignerInstancesSuite {

  /** Number of assigners to create per instance for testing. */
  private val NUM_ASSIGNERS = 3

  /** Configuration for the etcd preferred assigner driver used across all test instances. */
  private val DRIVER_CONFIG = EtcdPreferredAssignerDriver.Config()

  /** Configuration map for target configurations, wrapped with default values. */
  private val CONFIG_MAP = new InternalTargetConfigMapWithDefault(
    InternalTargetConfigMap.create(configScopeOpt = None, Map.empty)
  )

  /** URI representing the Kubernetes cluster where assigners will run. */
  private val ASSIGNER_CLUSTER_URI = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

  /**
   * Represents a complete test assigner instance with all its associated resources.
   *
   * This class encapsulates multiple test assigners, their execution context, fake clock,
   * and tracks all created slicelets and clerks organized by target. It provides methods
   * for creating new clients and gracefully shutting down all resources.
   *
   * @param testAssigners sequence of test assigners in this assigner instance
   * @param fakeClock controlled clock for time-based testing
   * @param fakeSecPool fake sequential execution context corresponding to [[fakeClock]]
   * @param dynamicConfigProvider provider for dynamic target configurations
   * @param dockerizedEtcd the dockerized etcd instance used by this assigner instance
   */
  @NotThreadSafe
  class AssignerInstance(
      val testAssigners: IndexedSeq[TestAssigner],
      val fakeClock: FakeTypedClock,
      val fakeSecPool: FakeSequentialExecutionContextPool,
      val dynamicConfigProvider: StaticTargetConfigProvider,
      val dockerizedEtcd: EtcdTestEnvironment
  ) {

    /** Map from target to slicelets connected to this instance's assigners. */
    private val slicelets = mutable.Map[Target, ListBuffer[Slicelet]]()

    /** Map from target to clerks connected to this instance's assigners. */
    private val clerks = mutable.Map[Target, ListBuffer[Clerk[ResourceAddress]]]()

    /**
     * Creates and returns a new slicelet for the specified target.
     *
     * @param target the target for which to create a slicelet
     */
    def createSlicelet(target: Target): Slicelet = {
      val initialAssignerPort: Int = testAssigners(0).localUri.getPort

      val podOrdinal = slicelets.size
      val sliceletHostName: String = s"$target-$podOrdinal".replace(':', '-')

      val slicelet: Slicelet = TestClientUtils.createSlicelet(
        initialAssignerPort,
        target,
        sliceletHostName,
        clientTlsFilePathsOpt = None,
        serverTlsFilePathsOpt = None,
        watchFromDataPlane = false
      )
      slicelets.getOrElseUpdate(target, ListBuffer[Slicelet]()) += slicelet
      slicelet
    }

    /**
     * Creates and returns a new clerk for the specified target that directly connects to the
     * assigner.
     *
     * @param target the target for which to create a clerk
     * @param initialAssignerIndex index of the assigner to connect to initially
     * @param branchOpt optional branch name for the clerk
     */
    def createDirectClerk(
        target: Target,
        initialAssignerIndex: Int,
        branchOpt: Option[String]): Clerk[ResourceAddress] = {
      val assignerPort = testAssigners(initialAssignerIndex).localUri.getPort
      val directClerkConf: ClerkConf =
        TestClientUtils.createTestDirectClerkConf(
          assignerPort,
          clientTlsFilePathsOpt = None,
          branchOpt
        )
      val clerk = TestClientUtils.createClerk(target, directClerkConf)
      clerks.getOrElseUpdate(target, ListBuffer[Clerk[ResourceAddress]]()) += clerk
      clerk
    }

    /**
     * Stops the test environment, including all the test Assigners, Slicelets, and Clerks.
     *
     * This method performs cleanup in the following order:
     * 1. Stops all clerks and clears the clerks map
     * 2. Stops all slicelets and clears the slicelets map
     * 3. Stops all test assigners with abrupt shutdown
     */
    def stop(): Unit = {
      for (clerk: Clerk[ResourceAddress] <- clerks.values.flatten) {
        clerk.forTest.stop()
      }
      clerks.clear()

      for (slicelet: Slicelet <- slicelets.values.flatten) {
        slicelet.forTest.stop()
      }
      slicelets.clear()

      for (testAssigner <- testAssigners) {
        testAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT)
      }
    }
  }

  /**
   * Creates and returns a dicer assigner configuration with the specified `storeNamespacePrefix`.
   *
   * This method generates a configuration that:
   * - Enables preferred assigner mode
   * - Sets the given `storeNamespacePrefix` for the assigner
   * - Disables SSL
   *
   * @param storeNamespacePrefix prefix for the store namespaces of the assigner that uses this conf
   */
  private def createAssignerConf(storeNamespacePrefix: String): DicerAssignerConf = {
    new DicerAssignerConf(
      Configs
        .parseMap(
          Map(
            "databricks.dicer.assigner.preferredAssigner.modeEnabled" -> true,
            "databricks.dicer.assigner.preferredAssigner.storeIncarnation" -> 42,
            "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
            "databricks.dicer.assigner.store.type" -> "etcd",
            "databricks.dicer.assigner.storeIncarnation" -> 42,
            "databricks.dicer.assigner.storeNamespacePrefix" -> storeNamespacePrefix
          )
        )
    )
  }

  /**
   * Creates a complete assigner instance with the specified configuration.
   *
   * @param storeNamespacePrefix prefix for this instance's assigners' store namespaces.
   * @param numAssigners number of assigner to create
   * @param dockerizedEtcd the etcd instance to use for storage
   */
  def createAssignerInstance(
      storeNamespacePrefix: String,
      numAssigners: Int,
      dockerizedEtcd: EtcdTestEnvironment
  ): AssignerInstance = {
    val fakeClock = new FakeTypedClock()
    val fakeSecPool = FakeSequentialExecutionContextPool.create(
      s"MultipleAssignerInstancesSuite-$storeNamespacePrefix",
      numThreads = 10,
      fakeClock
    )
    val assignerConf = createAssignerConf(storeNamespacePrefix)
    val testAssignerConfig = TestAssigner.Config.create(assignerConf)
    val dynamicConfigProvider = StaticTargetConfigProvider.create(
      staticTargetConfigMap = CONFIG_MAP,
      assignerConf
    )
    dynamicConfigProvider.startBlocking(DEFAULT_INITIAL_POLL_TIMEOUT)

    val testAssigners = (0 until numAssigners).map { _ =>
      TestAssigner.createAndStart(
        fakeSecPool,
        testAssignerConfig,
        dynamicConfigProvider,
        Some(dockerizedEtcd),
        ASSIGNER_CLUSTER_URI
      )
    }

    dockerizedEtcd.initializeStore(Assigner.getAssignmentsEtcdNamespace(assignerConf))
    dockerizedEtcd.initializeStore(Assigner.getPreferredAssignerEtcdNamespace(assignerConf))

    new AssignerInstance(
      testAssigners,
      fakeClock,
      fakeSecPool,
      dynamicConfigProvider,
      dockerizedEtcd
    )
  }

  /**
   * Verifies that preferred assigner functionality works as expected at startup. See
   * [[PreferredAssignerTestHelper.verifyPreferredAssignerFunctionality]] for more details.
   *
   * @param instance the assigner instance to test
   * @param getNextSliceletPortNumber function that generates unique slicelet port numbers
   */
  def verifyAssignerFunctionality(
      instance: AssignerInstance,
      getNextSliceletPortNumber: () => Int): Unit = {
    PreferredAssignerTestHelper.verifyPreferredAssignerFunctionality(
      driverConfig = DRIVER_CONFIG,
      testAssigners = instance.testAssigners,
      createDirectClerk = instance.createDirectClerk,
      createSlicelet = instance.createSlicelet,
      getNextSliceletPortNumber = getNextSliceletPortNumber,
      clock = instance.fakeClock
    )
  }

  /**
   * Advances the fake clock synchronously, ensuring all pending heartbeat operations are complete.
   *
   * @param fakeClock fake clock to advance
   * @param duration amount of time to advance the clock by
   * @param assigners assigners whose pending heartbeat operations will be waited for before
   *                  advancing the fake clock.
   */
  def advanceClockBySync(
      fakeClock: FakeTypedClock,
      duration: FiniteDuration,
      assigners: Seq[TestAssigner]
  ): Unit = {
    // Before we advance the clock, we call a `getHighestSucceededHeartbeatOpID` method which will
    // ensure that any previously enqueued tasks have completed before the clock advances. Blocks
    // until the clock has been advanced, so that all subsequent test code observes the advanced
    // clock state.
    for (assigner: TestAssigner <- assigners) {
      TestUtils.awaitResult(assigner.getHighestSucceededHeartbeatOpID, Duration.Inf)
    }
    // Advance the clock to trigger the next heartbeat.
    fakeClock.advanceBy(duration)
  }

}
