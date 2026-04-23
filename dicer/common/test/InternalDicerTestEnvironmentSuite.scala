package com.databricks.dicer.common

import java.util.UUID

import scala.concurrent.duration.{Duration, _}

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.TestUtils.{
  TestName,
  assertThrow,
  shamefullyAwaitForNonEventInAsyncTest
}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  LoadBalancingConfig,
  LoadBalancingMetricConfig
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.TargetMetricsUtils
import com.databricks.dicer.assigner.config.{
  ChurnConfig,
  InternalTargetConfig,
  InternalTargetConfigMap
}
import com.databricks.dicer.common.TestAssigner.AssignerReplyType
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.Version.LATEST_VERSION
import com.databricks.dicer.external.{Clerk, ResourceAddress, SliceKey, Slicelet, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.testing.DatabricksTest
import java.net.URI

import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.caching.util.TestUtils

class InternalDicerTestEnvironmentSuite extends DatabricksTest with TestName {

  /** The environment and assigner used for the tests. */
  private val testEnv = InternalDicerTestEnvironment.create(allowEtcdMode = true)

  private def getGlobalSafeName: String =
    getSuffixedSafeName(s"${UUID.randomUUID().toString.take(5)}")

  override def beforeEach(): Unit = {
    super.beforeEach()
    testEnv.clear()
  }

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  test("Reject invalid replicaCount configurations") {
    // Test plan: Verify that we can't initiate an environment with replicaCount greater than 1
    // when preferred assigner is disabled.
    assertThrow[IllegalArgumentException](
      "replicaCount must be 1 if not preferredAssignerEnabled."
    ) {
      val enablePreferredAssigner: Boolean = false
      val replicaCount: Int = 2
      InternalDicerTestEnvironment.create(
        TestAssigner.Config.create(
          new DicerAssignerConf(
            Configs.parseMap(
              "databricks.dicer.assigner.preferredAssigner.modeEnabled" -> enablePreferredAssigner,
              "databricks.dicer.assigner.replicaCount" -> replicaCount
            )
          )
        )
      )
    }
    assertThrow[IllegalArgumentException](
      "replicaCount must be greater than 0."
    ) {
      val replicaCount: Int = 0
      InternalDicerTestEnvironment.create(
        TestAssigner.Config.create(
          new DicerAssignerConf(
            Configs.parseMap("databricks.dicer.assigner.replicaCount" -> replicaCount)
          )
        )
      )
    }
  }

  test("Assigner store mode should match 'allowEtcdMode' of test env") {
    // Test plan: Verify that the internal dicer test environment throws when trying to create
    // assigner in etcd mode while `allowEtcdMode` is not on.

    val etcdAssignerConfig = TestAssigner.Config.create(
      new DicerAssignerConf(Configs.parseMap("databricks.dicer.assigner.store.type" -> "etcd"))
    )

    assertThrow[IllegalArgumentException]("dockerizedEtcdOpt must be defined") {
      InternalDicerTestEnvironment.create(etcdAssignerConfig)
    }

    val nonEtcdModeEnv = InternalDicerTestEnvironment.create()

    assertThrow[IllegalArgumentException]("dockerizedEtcdOpt must be defined") {
      nonEtcdModeEnv.addAssigner(etcdAssignerConfig)
    }

    assertThrow[IllegalArgumentException]("dockerizedEtcdOpt must be defined") {
      nonEtcdModeEnv.restartAssigner(index = 0, etcdAssignerConfig)
    }
  }

  test("Test Slicelets and Clerks with Assigner") {
    // Test plan: In the given environment, add two Slicelets. Make sure that an assignment is
    // generated. Then create two Clerks, one that connects to the first Slicelet and the other
    // that directly connects to the Assigner and make sure that they both get the assignment.

    testEnv.addAssigner(TestAssigner.Config.create())

    val target = Target(getGlobalSafeName)
    val slicelet0: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 12345, listenerOpt = None)
    val pod0: Squid = slicelet0.impl.squid
    val pod1: Squid = slicelet1.impl.squid
    AssertionWaiter("TestAssigner").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      val assignedResources: Set[Squid] = assignment.assignedResources
      assert(
        assignedResources.contains(pod0) && assignedResources.contains(pod1)
      )
    }

    // Make sure that the Slicelets have the assignment as well.
    AssertionWaiter("Slicelet0").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.isDefined)
    }
    AssertionWaiter("Slicelet1").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Now start two Clerks (one watches assignments from a Slicelet and the other from an Assigner)
    // and make sure that they both get the assignment.
    val clerk1: Clerk[ResourceAddress] = testEnv.createClerk(slicelet0)
    val clerk2: Clerk[ResourceAddress] =
      testEnv.createDirectClerk(target, initialAssignerIndex = 0) // points to the Assigner
    TestUtils.awaitResult(clerk1.ready, Duration.Inf)
    assert(clerk1.impl.forTest.getLatestAssignmentOpt.isDefined)
    TestUtils.awaitResult(clerk2.ready, Duration.Inf)
    assert(clerk2.impl.forTest.getLatestAssignmentOpt.isDefined)
  }

  test("Test multi-assigner test environment") {
    // Test plan: Verify that the environment supports multiple assigners by starting an environment
    // with multiple assigners, slicelets, a clerk that connects to one of the Slicelet, and another
    // clerk that connects directly to the assigner, and verifying that assignment knowledge is
    // correctly propagated between the different components in the system via watch requests. Also
    // verify that setAndFreezeAssignment works correctly with more than one assigner.
    // Start the environment with two assigners and in-memory store.

    testEnv.addAssigner(TestAssigner.Config.create())
    testEnv.addAssigner(TestAssigner.Config.create())
    val assigner1 = testEnv.testAssigners(0)
    val assigner2 = testEnv.testAssigners(1)

    // Verify the assigners are different.
    assert(assigner1.localUri != assigner2.localUri)

    // Verify the AssignerInfo matches the expected values.
    assert(assigner1.getAssignerInfoBlocking().uri == assigner1.localUri)
    assert(assigner2.getAssignerInfoBlocking().uri == assigner2.localUri)

    // Configure the first assigner to redirect to the second assigner.
    assigner1.setReplyType(AssignerReplyType.OverwriteRedirect(Redirect(Some(assigner2.localUri))))

    // Create a slicelet that issues its initial watch request against assigner1. Subsequent watch
    // requests should be issued against the assigner2 after the redirect.
    val target = Target(getGlobalSafeName)
    val slicelet1: Slicelet =
      testEnv
        .createSlicelet(target, initialAssignerIndex = 0, watchFromDataPlane = false)
        .start(selfPort = 1234, listenerOpt = None)

    // Create a slicelet that issues its initial watch request against assigner2.
    val slicelet2: Slicelet =
      testEnv
        .createSlicelet(target, initialAssignerIndex = 1, watchFromDataPlane = false)
        .start(selfPort = 12345, listenerOpt = None)

    // Create a clerk that directly connects to the Assigners, and issues its initial watch request
    // against assigner1. Subsequent watch requests should be issued against the assigner2 after the
    // redirect.
    val clerk0: Clerk[ResourceAddress] =
      testEnv.createDirectClerk(target, initialAssignerIndex = 0)

    val pod1: Squid = slicelet1.impl.squid
    val pod2: Squid = slicelet2.impl.squid

    // Verify that the assignment generated by assigner2 includes both pods because it has seen
    // watch requests from both of them after the redirect.
    AssertionWaiter(s"Waiting for assignment from assigner ${assigner2.localUri}").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(assigner2.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignedResourcesOnAssigner2: Set[Squid] = assignmentOpt.get.assignedResources
      assert(
        assignmentOpt.get.assignedResources.contains(pod1) && assignedResourcesOnAssigner2.contains(
          pod2
        )
      )
    }

    // Verify that the assignment generated by assigner1 does not include pod2 because it has only
    // seen a watch request from pod1. (Note that with an in-memory store, the only way for an
    // assigner to learn about an assignment from another assigner is via a watch request from
    // a slicelet that has seen that assignment.)
    val assignedResourcesOnAssigner1 =
      AssertionWaiter(s"Waiting for assignment from assigner ${assigner1.localUri}").await {
        val assignmentOpt: Option[Assignment] =
          TestUtils.awaitResult(assigner1.getAssignment(target), Duration.Inf)
        assert(assignmentOpt.isDefined)
        assignmentOpt.get.assignedResources
      }
    assert(!assignedResourcesOnAssigner1.contains(pod2))

    // Make sure that both Slicelets and the Clerk receive the assignment as well.
    AssertionWaiter("Slicelet0").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.isDefined)
    }
    AssertionWaiter("Slicelet1").await {
      assert(slicelet2.impl.forTest.getLatestAssignmentOpt.isDefined)
    }
    AssertionWaiter("Clerk0").await {
      assert(clerk0.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Now start a Clerk that connects to the Slicelet and make sure that it gets an assignment.
    val clerk2: Clerk[ResourceAddress] = testEnv.createClerk(slicelet1)
    TestUtils.awaitResult(clerk2.ready, Duration.Inf)
    assert(clerk2.impl.forTest.getLatestAssignmentOpt.isDefined)

    // Now reverse the redirects so that assigner2 redirects to assigner1 and the slicelets begin
    // watching against assigner1.
    assigner1.setReplyType(AssignerReplyType.Normal)
    assigner2.setReplyType(AssignerReplyType.OverwriteRedirect(Redirect(Some(assigner1.localUri))))

    // Now assigner1 should have an assignment that includes both pods.
    AssertionWaiter(s"Waiting for assignment from assigner ${assigner1.localUri}").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(assigner1.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignedResources: Set[Squid] = assignmentOpt.get.assignedResources
      assert(
        assignedResources.contains(pod1) && assignedResources.contains(pod2)
      )
    }

    // Also test that setAndFreezeAssignment works with multiple assigners.
    val proposal2: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val assignment2: Assignment =
      TestUtils.awaitResult(
        assigner2.setAndFreezeAssignment(target, proposal2),
        Duration.Inf
      )
    AssertionWaiter(s"Waiting for setAndFreezeAssignment against assigner2").await {
      val latestAssignment: Assignment =
        TestUtils.awaitResult(assigner2.getAssignment(target), Duration.Inf).get
      assert(
        latestAssignment.generation == assignment2.generation &&
        latestAssignment.sliceMap == assignment2.sliceMap
      )
    }
  }

  test("numAssigners validation") {
    // Verify that the environment throws an exception if numAssigners is less than 0.
    assertThrows[IllegalArgumentException] {
      InternalDicerTestEnvironment.create(numAssigners = -1)
    }
  }

  test("Changing environments modes") {
    // Test plan: Create a server, receive its assignment. Add another server and freeze the
    // assignment. Make sure that the assignment is received. Then unfreeze, add a server and
    // check that the server receives an assignment.
    testEnv.addAssigner(TestAssigner.Config.create())

    val target = Target(getGlobalSafeName)
    val slicelet0 = testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val clerk = testEnv.createClerk(slicelet0)

    // Now wait for each of them to receive the assignment.
    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    assert(clerk.impl.forTest.getLatestAssignmentOpt.isDefined)

    val generationA = clerk.impl.forTest.getLatestAssignmentOpt.get.generation
    logger.info(s"Generation A = $generationA")
    AssertionWaiter("First assignment").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation == generationA)
      assert(TargetMetricsUtils.getPodSetSize(target, "Running") == 1)
      assert(TargetMetricsUtils.getAllAssignmentGenerationGenerateDecisions(target) == 1)
      assert(
        SubscriberHandlerMetricUtils
          .getNumSliceletsByHandler(SubscriberHandler.Location.Assigner, target, LATEST_VERSION)
        == 1
      )
      assert(
        SubscriberHandlerMetricUtils
          .getNumClerksByHandler(SubscriberHandler.Location.Slicelet, target, LATEST_VERSION) == 1
      )
    }

    // Create another server and wait for the new assignment.
    val slicelet1 = testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("Second assignment").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation > generationA)
      assert(TargetMetricsUtils.getPodSetSize(target, "Running") == 2)
      assert(TargetMetricsUtils.getAllAssignmentGenerationGenerateDecisions(target) == 2)
      assert(
        SubscriberHandlerMetricUtils
          .getNumSliceletsByHandler(SubscriberHandler.Location.Assigner, target, LATEST_VERSION)
        == 2
      )
      assert(
        SubscriberHandlerMetricUtils
          .getNumClerksByHandler(SubscriberHandler.Location.Slicelet, target, LATEST_VERSION) == 1
      )
    }
    val generationB = slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation
    logger.info(s"Generation B = $generationB")

    // The assignment has been generated. Freeze it now.
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Fili") -> Seq("Pod0"),
      ("Fili" -- "Nori") -> Seq("Pod1"),
      ("Nori" -- ∞) -> Seq("Pod2")
    )
    // Freeze assignment and wait for it to show up at server1 and the clerk.
    val assignment: Assignment =
      TestUtils.awaitResult(
        testEnv.testAssigner.setAndFreezeAssignment(target, proposal),
        Duration.Inf
      )
    AssertionWaiter("Third assignment").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation == assignment.generation)
    }
    AssertionWaiter("Third assignment").await {
      val latestAssignment: Assignment = clerk.impl.forTest.getLatestAssignmentOpt.get
      assert(latestAssignment.generation == assignment.generation)
    }

    // Now unfreeze the assignment and let the normal generation proceed.
    TestUtils.awaitResult(testEnv.testAssigner.unfreezeAssignment(target), Duration.Inf)

    // Create a new server and make sure that it receives the new generated assignment.
    val slicelet2 = testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("Fourth assignment").await {
      assert(slicelet2.impl.forTest.getLatestAssignmentOpt.get.generation > assignment.generation)
    }
    val generationD = slicelet2.impl.forTest.getLatestAssignmentOpt.get.generation
    logger.info(s"Generation D = $generationD")
  }

  test("Dynamically adding assigners") {
    // Test plan: Verify the internal dicer test environment can dynamically add assigners. Verify
    // this by creating an internal dicer test environment with no assigners, then dynamically
    // adding assigners one by one, verifying the test environment can correctly get the newly added
    // assigners and the number of assigners, and the new assigners works correctly.

    // Create internal dicer test environment with no assigners.
    val target = Target(getGlobalSafeName)
    testEnv.clear()
    assert(testEnv.testAssigners.isEmpty)

    // Add one assigner to the test environment.
    val (assigner0, index0): (TestAssigner, Int) = testEnv.addAssigner(
      TestAssigner.Config.create(new DicerAssignerConf(Configs.empty))
    )
    assert(testEnv.testAssigners.length == 1)
    assert(index0 == 0)
    assert(testEnv.testAssigners(index0).localUri == assigner0.localUri)

    // Verify the new assigner works well by connecting a new slicelet with it and wait for the
    // assignment generation.
    val slicelet0: Slicelet = testEnv
      .createSlicelet(target, index0, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("assignment generated by assigner0").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation > Generation.EMPTY)
    }

    // Add another assigner to the test environment.
    val (assigner1, index1): (TestAssigner, Int) = testEnv.addAssigner(
      TestAssigner.Config.create(new DicerAssignerConf(Configs.empty))
    )
    assert(testEnv.testAssigners.length == 2)
    assert(index1 == 1)
    assert(testEnv.testAssigners(index1).localUri == assigner1.localUri)

    // Verify the new assigner works well by connecting a new slicelet with it and wait for the
    // assignment generation.
    val slicelet1: Slicelet = testEnv
      .createSlicelet(target, index1, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("assignment generated by assigner1").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation > Generation.EMPTY)
    }
  }

  test("Dynamically adding assigners with different configurations") {
    // Test plan: Verify the internal dicer test environment can dynamically add assigners with
    // different configurations. Verify this by creating an internal dicer test environment with no
    // assigners, then dynamically adding assigners one by one with different configurations,
    // verifying the added assigners are using the correct configuration.

    val storeIncarnation0 = Incarnation(1)
    val testAssignerConfig0 = TestAssigner.Config.create(
      new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.store.type" -> "in_memory",
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.storeIncarnation" -> storeIncarnation0.value
        )
      )
    )

    val storeIncarnation1 = Incarnation(3)
    val testAssignerConfig1 = TestAssigner.Config.create(
      new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.store.type" -> "in_memory",
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.storeIncarnation" -> storeIncarnation1.value
        )
      )
    )

    // Add one assigner to the test environment.
    val (assigner0, _): (TestAssigner, Int) = testEnv.addAssigner(
      testAssignerConfig0
    )

    // Verify the assigner is correctly configured.
    assert(assigner0.storeIncarnation == storeIncarnation0)

    // Add another assigner to the test environment.
    val (assigner1, _): (TestAssigner, Int) = testEnv.addAssigner(
      testAssignerConfig1
    )

    // Verify the assigner is correctly configured.
    assert(assigner1.storeIncarnation == storeIncarnation1)
  }

  test("Assigners share same etcd") {
    // Test plan: Verify the assigners in the internal dicer test environment can correctly sync
    // data by etcd. Verify this by dynamically adding two assigners in etcd mode to the test
    // environment, connecting separate slicelets to them, and verifying the slicelets are aware
    // of each other.

    val target = Target(getGlobalSafeName)
    val etcdAssignerConfig = TestAssigner.Config.create(
      new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.store.type" -> "etcd",
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.storeIncarnation" -> 2
        )
      )
    )

    var knownHighestGeneration = Generation.EMPTY

    // Add the first assigner, verifying it works normally by connecting a slicelet with it and
    // checking the assignment received by the slicelet.
    val (_, index0): (TestAssigner, Int) =
      testEnv.addAssigner(etcdAssignerConfig)
    val slicelet0: Slicelet = testEnv
      .createSlicelet(target, index0, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("assignment generated by assigner0").await {
      val slicelet0Generation: Generation =
        slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation
      assert(slicelet0Generation > knownHighestGeneration)
      knownHighestGeneration = slicelet0Generation
    }

    // Add the second assigner, verifying it works normally by connecting a slicelet
    // with it and checking the assignment received by the slicelet.
    val (_, index1): (TestAssigner, Int) =
      testEnv.addAssigner(etcdAssignerConfig)
    val slicelet1: Slicelet = testEnv
      .createSlicelet(target, index1, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)
    AssertionWaiter("assignment generated by assigner1").await {
      val slicelet1Generation: Generation =
        slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation
      assert(slicelet1Generation > knownHighestGeneration)
      knownHighestGeneration = slicelet1Generation
      // Checking that slicelet1 knows about an assignment containing slicelet0.
      assert(
        slicelet1.impl.forTest.getLatestAssignmentOpt.get.assignedResources
          .contains(slicelet0.impl.squid)
      )
    }

    AssertionWaiter("assigner0 knows about the newest assignment").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation >= knownHighestGeneration)
      // Checking that slicelet0 knows about an assignment containing slicelet1.
      assert(
        slicelet0.impl.forTest.getLatestAssignmentOpt.get.assignedResources
          .contains(slicelet1.impl.squid)
      )
    }
  }

  test("stop and restart assigners") {
    // Test plan: Verify the assigners in the internal dicer test environment can be stopped and
    // restarted correctly. Verify this by creating 2 test assigners in etcd mode.
    // (1) Connect slicelet 0 with assigner 0, verify assigner 0 works by checking
    //     if assigner 1 knows about slicelet 0.
    // (2) Stop assigner 0 and try to connect slicelet 1 with stopped assigner 0, verify assigner 0
    //     is stopped by verifying assigner 1 does not contain slicelet 1 in its assignment.
    // (3) Restart assigner 0 and connect slicelet 2 with the restarted assigner, verify assigner 1
    //     knows about slicelet 2.

    val target = Target(getGlobalSafeName)

    val etcdAssignerConfig = TestAssigner.Config.create(
      new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.store.type" -> "etcd",
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.storeIncarnation" -> 2
        )
      )
    )

    testEnv.clear()
    testEnv.addAssigner(etcdAssignerConfig)
    testEnv.addAssigner(etcdAssignerConfig)

    // Setup: Connect slicelet 0 to assigner 0.
    val slicelet0 = testEnv
      .createSlicelet(target, initialAssignerIndex = 0, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)

    // Verify: Assigner 0 works by checking assigner 1 knows about slicelet 0.
    AssertionWaiter("").await {
      assert(
        TestUtils
          .awaitResult(
            testEnv.testAssigners(1).getAssignmentCreatingGeneratorDeprecated(target),
            Duration.Inf
          )
          .get
          .assignedResources
          .contains(slicelet0.impl.squid)
      )
    }

    // Setup: Stop assigner 0 and try to connect slicelet 1 to the stopped assigner.
    testEnv.stopAssigner(index = 0)
    val slicelet1 = testEnv
      .createSlicelet(target, initialAssignerIndex = 0, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)

    // Verify: Assigner 0 is stopped by checking assigner 1 does not see slicelet 1. Since we're
    // testing that something does NOT happen, we wait in test to allow time for propagation
    // if the assigner were still running.
    shamefullyAwaitForNonEventInAsyncTest()
    assert(
      !TestUtils
        .awaitResult(
          testEnv.testAssigners(1).getAssignmentCreatingGeneratorDeprecated(target),
          Duration.Inf
        )
        .get
        .assignedResources
        .contains(slicelet1.impl.squid)
    )

    // Setup: Restart assigner 0 and connect slicelet 2 to the restarted assigner.
    testEnv.restartAssigner(index = 0, etcdAssignerConfig)
    val slicelet2 = testEnv
      .createSlicelet(target, initialAssignerIndex = 0, watchFromDataPlane = false)
      .start(selfPort = 1234, listenerOpt = None)

    // Verify: Restarted assigner 0 works by checking assigner 1 sees slicelet 2.
    AssertionWaiter("").await {
      assert(
        TestUtils
          .awaitResult(
            testEnv.testAssigners(1).getAssignmentCreatingGeneratorDeprecated(target),
            Duration.Inf
          )
          .get
          .assignedResources
          .contains(slicelet2.impl.squid)
      )
    }
  }

  test("Load metric getters report expected value") {
    // Test plan: Verify that getTotalAssignedLoadMetric and getTotalUnassignedLoadMetric report the
    // expected values after reporting load on multiple slicelets for mulitple keys, some of which
    // are assigned and some of which are not.
    testEnv.addAssigner(TestAssigner.Config.create())

    val target = Target(getGlobalSafeName)
    val slicelet0: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 5678, listenerOpt = None)
    val pod0: Squid = slicelet0.impl.squid
    val pod1: Squid = slicelet1.impl.squid

    // Setup: Freeze an assignment in place that assigns slices to both slicelets.
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dori") -> Seq(pod0),
      ("Dori" -- "Fili") -> Seq(pod1),
      ("Fili" -- "Kili") -> Seq(pod0),
      ("Kili" -- "Nori") -> Seq(pod1),
      ("Nori" -- ∞) -> Seq(pod0)
    )
    val assignment: Assignment = {
      TestUtils.awaitResult(
        testEnv.testAssigner.setAndFreezeAssignment(target, proposal),
        Duration.Inf
      )
    }
    AssertionWaiter("Waiting for slicelets to receive assignment").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation == assignment.generation)
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation == assignment.generation)
    }

    // Verify: For each low key in the assignment, report load on both slicelets. One slicelet will
    // be assigned the key and the other will not, resulting in each metric being incremented by 1
    // for assigned / unassigned load.
    val lowKeys: Seq[SliceKey] = Seq("", "Dori", "Fili", "Kili", "Nori")
    for (i: Int <- lowKeys.indices) {
      for (slicelet <- Seq(slicelet0, slicelet1)) {
        slicelet.createHandle(lowKeys(i)).incrementLoadBy(1)
      }
      AssertionWaiter("Waiting for expected metric update").await {
        assert(testEnv.getTotalAssignedLoadMetric(target) == i + 1)
        assert(testEnv.getTotalUnassignedLoadMetric(target) == i + 1)
      }
    }
  }

  test("Outstanding SliceKeyHandles metric reports expected value") {
    // Test plan: Create a Slicelet, and create some handles on it. Verify that the outstanding
    // handles metric behaves as expected as handles are created and closed.
    testEnv.addAssigner(TestAssigner.Config.create())
    val target = Target(getGlobalSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    val handle0 = slicelet.createHandle("Balin")
    val handle1 = slicelet.createHandle("Fili")

    assert(testEnv.getSliceKeyHandlesOutstandingMetric(target) == 2)
    handle1.close()
    assert(testEnv.getSliceKeyHandlesOutstandingMetric(target) == 1)
    handle0.close()
    assert(testEnv.getSliceKeyHandlesOutstandingMetric(target) == 0)

    slicelet.forTest.stop()
  }

}
