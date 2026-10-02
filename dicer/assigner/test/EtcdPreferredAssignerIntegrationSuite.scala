package com.databricks.dicer.assigner

import java.net.URI

import scala.concurrent.duration._
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContextPool,
  FakeTypedClock,
  MetricUtils
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.dicer.assigner.testing.PreferredAssignerTestUtils
import com.databricks.dicer.assigner.testing.PreferredAssignerTestUtils.{
  advanceClockBySync,
  assertAssignerGeneratesAssignment,
  assertAssignerGeneratesAssignmentByDirectWatchRequest,
  assertStandbyRedirectsToPreferredAssigner,
  createAssignerConfig,
  createClerkClientRequest,
  createSliceletClientRequest
}
import com.databricks.dicer.common.SyncAssignmentState.{KnownAssignment, KnownGeneration}
import com.databricks.dicer.common.testing.SliceTestUtils.createTestSquid
import com.databricks.dicer.common.{
  Assignment,
  ClientRequest,
  ClientResponse,
  Generation,
  Incarnation,
  Redirect
}
import com.databricks.dicer.common.testing.{InternalDicerTestEnvironment, TestAssigner}
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext
import io.prometheus.client.CollectorRegistry

class EtcdPreferredAssignerIntegrationSuite extends DatabricksTest with TestName {

  // TODO(<internal bug>) make this a base test with two suites (assignment store mode "in-memory"
  //  and "etcd") that extend it. In each suite, test the combination of the following
  //  scenarios:
  //  - assignment store has a higher incarnation than the preferred assigner store.
  //  - both stores have the same incarnation.
  //  - assignment store has a lower incarnation than the preferred assigner store.

  /** The single fake clock used in the suite. */
  private val fakeClock = new FakeTypedClock()

  private val fakeSecPool =
    FakeSequentialExecutionContextPool.create(this.getClass.getName, numThreads = 10, fakeClock)

  /** The RPC context used in watch requests. */
  private val CTX: RPCContext = JettyTestRPCContext
    .builder()
    .method("POST")
    .uri("/")
    .build()

  /** The number of assigners used in the suite. */
  private val NUM_ASSIGNERS: Int = 3

  /** The default preferred assigner store incarnation. */
  private val DEFAULT_INCARNATION: Incarnation = Incarnation(40)

  private val driverConfig = EtcdPreferredAssignerDriver.Config()

  /** The default test assigner config where the preferred assigner mode is enabled. */
  private val DEFAULT_PA_ENABLED_CONF: TestAssigner.Config =
    createAssignerConfig(preferredAssignerStoreIncarnation = DEFAULT_INCARNATION)

  private val testEnv = InternalDicerTestEnvironment.create(
    config = DEFAULT_PA_ENABLED_CONF,
    numAssigners = 0, // Initially, start 0 assigners, let each test start their own assigners.
    allowEtcdMode = true,
    secPool = fakeSecPool
  )

  /** A sequence number that helps generating a unique slicelet port. */
  private var sliceletPortSequenceNumber: Int = 31234

  override def afterAll(): Unit = {
    testEnv.clear()
  }

  override def beforeEach(): Unit = {
    for (assigner: TestAssigner <- testEnv.testAssigners) {
      assigner.shutDownPreferredAssignerDriver()
    }

    // Clear the test environment and start `NUM_ASSIGNERS` assigners.
    testEnv.clear()
  }

  /** Generates a unique port number for the slicelet. */
  private def getNextSliceletPortNumber: Int = {
    sliceletPortSequenceNumber += 1
    sliceletPortSequenceNumber
  }

  /** Initializes a set of Assigners. */
  private def initializeAssigners(numAssigners: Int, config: TestAssigner.Config): Unit = {
    for (_: Int <- 1 to numAssigners) {
      testEnv.addAssigner(config)
    }

    logger.debug(
      s"Assigners in this test: ${testEnv.testAssigners.map(_.getAssignerInfoBlocking()).toSet}"
    )
  }

  test("Ensure the initial preferred assigner selection is correct") {
    // Test plan: Ensure that at start up, only one of the assigners is selected as the preferred
    // assigner, and verify that:
    //  1. All assigners agree on the same preferred assigner, and the standbys initiate periodic
    //     heartbeats to the preferred assigner.
    //  2. The preferred assigner generates assignments, and the assignment is propagated to the
    //     Slicelet and the Clerk that watches directly to Assigners.
    //  3. Standby assigners redirect watch requests to the preferred assigner.
    //  4. The preferred assigner keeps being the preferred assigner, and there are no heartbeats
    //     sent to the standby assigners.

    // Setup: start up assigners and advance the clock to trigger the initial preferred assigner
    // selection.
    initializeAssigners(NUM_ASSIGNERS, DEFAULT_PA_ENABLED_CONF)
    assert(testEnv.testAssigners.size == NUM_ASSIGNERS)
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.

    PreferredAssignerTestUtils.verifyPreferredAssignerFunctionality(
      driverConfig = driverConfig,
      testAssigners = testEnv.testAssigners,
      createDirectClerk = testEnv.createDirectClerk,
      createSlicelet = testEnv.createSlicelet,
      getNextSliceletPortNumber = getNextSliceletPortNumber _,
      clock = fakeClock
    )
  }

  test("The PA abdicates when it receives a termination notice") {
    // Test plan: Verify that the preferred assigner abdicates when it receives a termination
    // notice, and a standby takes over and generates assignments, and receives heartbeats from
    // the other standby assigner.

    // Setup: start up assigners and advance the clock to trigger the initial preferred assigner
    // selection.
    initializeAssigners(NUM_ASSIGNERS, DEFAULT_PA_ENABLED_CONF)
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)

    val standBys: Seq[TestAssigner] = testEnv.testAssigners.filter { assigner: TestAssigner =>
      assigner.getAssignerInfoBlocking() != initialPreferredAssigner.getAssignerInfoBlocking()
    }

    initialPreferredAssigner.stop(ShutdownOption.ALLOW_ABDICATION)
    // We don't need to advance the clock here because the standby assigner should take over
    // immediately after the preferred assigner is abdicated.
    // Verify: a new preferred assigner is selected from the standbys, and it generates assignments.
    val newPreferredAssigner: TestAssigner = AssertionWaiter("wait for new PA").await {
      val newCandidate: TestAssigner =
        PreferredAssignerTestUtils.getConvergedPreferredAssigner(standBys)
      assert(
        newCandidate.getAssignerInfoBlocking() != initialPreferredAssigner.getAssignerInfoBlocking()
      )
      newCandidate
    }
    assert(newPreferredAssigner != initialPreferredAssigner)

    // Verify: the new preferred assigner generates assignments.
    val target = Target(getSafeName)
    val expectedRedirect =
      Redirect(Some(newPreferredAssigner.getAssignerInfoBlocking().uri), redirectTokenOpt = None)
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      newPreferredAssigner,
      target,
      expectedRedirect
    )

    // Verify: the new standbys respond watch requests with redirect to the preferred assigner.
    val newStandbys: Seq[TestAssigner] = standBys.filter { assigner: TestAssigner =>
      assigner.getAssignerInfoBlocking() != newPreferredAssigner.getAssignerInfoBlocking()
    }
    for (standbyAssigner: TestAssigner <- newStandbys) {
      val sliceletReq = createSliceletClientRequest(target, createTestSquid(s"pod-$target-1"))
      val clerkRequest = createClerkClientRequest(target)
      for (clientReq: ClientRequest <- Seq(sliceletReq, clerkRequest)) {
        AssertionWaiter("Standby redirects to preferred assigner").await {
          val response = ClientResponse.fromProto(
            TestUtils.awaitResult(standbyAssigner.handleWatch(CTX, clientReq.toProto), Duration.Inf)
          )
          assert(response.syncState == KnownGeneration(Generation.EMPTY))
          assert(response.redirect.addressOpt.nonEmpty)
          assert(
            response.redirect.addressOpt.get == newPreferredAssigner.getAssignerInfoBlocking().uri
          )
        }
      }
    }
    // Advance the clock to trigger the next heartbeat.
    advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    // Verify: the new preferred assigner receives heartbeats from the standby.
    AssertionWaiter("Heartbeat completes").await {
      assert(newPreferredAssigner.getNumberOfHeartbeatsReceived > 0)
    }
  }

  test("A standby assigner takes over when the PA is down") {
    // Test plan: Verify that when the preferred assigner is unhealthy, a standby assigner takes
    // over as the preferred assigner, and the new preferred assigner generates assignments, and
    // receives heartbeats from the other standby assigner.

    // Setup: start up assigners and advance the clock to trigger the initial preferred assigner
    // selection.
    initializeAssigners(NUM_ASSIGNERS, DEFAULT_PA_ENABLED_CONF)
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)
    val initialStandbys: Seq[TestAssigner] = testEnv.testAssigners.filter {
      assigner: TestAssigner =>
        assigner.getAssignerInfoBlocking() != initialPreferredAssigner.getAssignerInfoBlocking()
    }

    AssertionWaiter("Initial heartbeats").await {
      // Await the initial heartbeats from the standbys.
      for (standbyAssigner: TestAssigner <- initialStandbys) {
        assert(standbyAssigner.getNumberOfHeartbeatsReceived == 0)
        assert(
          TestUtils.awaitResult(standbyAssigner.getHighestSucceededHeartbeatOpID, Duration.Inf) == 1
        )
      }
    }

    // Simulate the preferred assigner being unhealthy by stopping it. We stop it synchronously
    // to ensure that the preferred assigner is fully stopped before the next heartbeat is sent.
    initialPreferredAssigner.stop(ShutdownOption.ABRUPT)

    // Advance the clock so that the standby assigner's heartbeat failure threshold is reached.
    for (_: Int <- 1 to driverConfig.heartbeatFailureThreshold + 1) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    }

    // Verify: a new preferred assigner is selected, and it generates assignments.
    val newPreferredAssigner1: TestAssigner = AssertionWaiter("wait for new PA").await {
      val newCandidate: TestAssigner =
        PreferredAssignerTestUtils.getConvergedPreferredAssigner(initialStandbys)
      assert(
        newCandidate.getAssignerInfoBlocking() != initialPreferredAssigner.getAssignerInfoBlocking()
      )
      newCandidate
    }

    // Verify: the new preferred assigner generates assignments.
    // We don't create a Slicelet here, because in the test environment, we don't have a mechanism
    // to send a watch request to a random Assigner (like what clusterIP does). In this case, if we
    // were to create a Slicelet here, and the default address in its underlying `SliceletLookup`
    // happened to be a stopped assigner, then this assigner would keep sending requests to the
    // stopped assigner, resulting in test failures.
    val target = Target(getSafeName)
    val testSquid = createTestSquid(s"pod-$target-1")
    val sliceletRequest = createSliceletClientRequest(target, testSquid)
    val clerkRequest = createClerkClientRequest(target)
    AssertionWaiter("Preferred assigner generates assignments").await {
      for (clientReq: ClientRequest <- Seq(sliceletRequest, clerkRequest)) {
        val response = ClientResponse.fromProto(
          TestUtils
            .awaitResult(newPreferredAssigner1.handleWatch(CTX, clientReq.toProto), Duration.Inf)
        )
        response.syncState match {
          case KnownGeneration(_) =>
            assert(false, "The preferred assigner should generate assignments")
          case KnownAssignment(_) => // Pass.
        }
        assert(response.redirect.addressOpt.nonEmpty, "Redirect address should be non-empty")
        assert(
          response.redirect.addressOpt.get == newPreferredAssigner1.getAssignerInfoBlocking().uri
        )
      }
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(newPreferredAssigner1.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources.contains(testSquid))
    }

    // Advance the clock to trigger the next heartbeat.
    advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    // Verify: the new preferred assigner receives heartbeats from the standby.
    AssertionWaiter("Heartbeat completes").await {
      assert(newPreferredAssigner1.getNumberOfHeartbeatsReceived > 0)
    }

    // Shutdown the new preferred assigner, and ensure that the only remaining assigner takes over,
    // and generates assignments. We stop it synchronously to ensure that the preferred assigner is
    // fully stopped before the next heartbeat is sent.
    newPreferredAssigner1.stop(ShutdownOption.ABRUPT)

    // Advance the clock so that the standby assigner's heartbeat failure threshold is reached.
    for (_: Int <- 1 to driverConfig.heartbeatFailureThreshold + 1) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    }

    // Verify: the only remaining assigner is selected as the new preferred assigner.
    val newPreferredAssigner2: TestAssigner = initialStandbys.filter { assigner: TestAssigner =>
      assigner.getAssignerInfoBlocking() != newPreferredAssigner1.getAssignerInfoBlocking()
    }.head

    // Verify: the new preferred assigner generates assignments by sending a direct watch request.
    val target2 = Target(getSuffixedSafeName("next"))
    val testSquid2 = createTestSquid(s"pod-$target2-1")
    val sliceletRequest2 = createSliceletClientRequest(target2, testSquid2)
    val clerkRequest2 = createClerkClientRequest(target2)
    AssertionWaiter("Preferred assigner generates assignments 2").await {
      for (clientReq2: ClientRequest <- Seq(sliceletRequest2, clerkRequest2)) {
        val response = ClientResponse.fromProto(
          TestUtils
            .awaitResult(newPreferredAssigner2.handleWatch(CTX, clientReq2.toProto), Duration.Inf)
        )
        response.syncState match {
          case KnownGeneration(_) =>
            assert(false, "The preferred assigner should generate assignments")
          case KnownAssignment(_) => // Pass.
        }
        assert(response.redirect.addressOpt.nonEmpty, "Redirect address should be non-empty")
        assert(
          response.redirect.addressOpt.get == newPreferredAssigner2.getAssignerInfoBlocking().uri
        )
      }
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(newPreferredAssigner2.getAssignment(target2), Duration.Inf)
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources.contains(testSquid2))
    }
  }

  test("Assigner loses and then reassumes preferred assignership") {
    // Test plan: Verify that when an assigner loses the preferred assignership, it can reassume
    // the preferred assignership and generate update-to-date assignments when it becomes healthy
    // again. Specifically:
    // 1. Start two assigners with the preferred assigner mode enabled, and wait for the initial
    //    preferred assigner selection.
    // 2. Mangle the preferred assigner (A) by temporarily making it not respond to heartbeats.
    //    And verify that another assigner (B) takes over as the preferred assigner.
    // 3. Mangle the preferred assigner (B) by temporarily making it not respond to heartbeats.
    //    Bring the original preferred assigner (A) back to health by allowing it to respond to
    //    heartbeats. And verify that the original preferred assigner (A) reassumes the preferred
    //    assignership.
    // 4. Verify that the preferred assigner (A) generates update-to-date assignments, and the
    //    standby assigner (B) redirects watch requests to the preferred assigner (A).
    val oldIncarnationConf: TestAssigner.Config =
      createAssignerConfig(DEFAULT_INCARNATION)

    initializeAssigners(numAssigners = 2, oldIncarnationConf)
    val assignersWithOldIncarnation: Seq[TestAssigner] = testEnv.testAssigners

    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val assignerA: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)
    // Wait for the preferred assigner to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assertAssignerGeneratesAssignment(testEnv, assignerA, target, portNum)

    // Mangle the preferred assigner (A) by temporarily making it not respond to heartbeats.
    assignerA.pauseHeartbeatResponse()

    // Advance the clock so that the standby assigner's heartbeat failure threshold is reached.
    for (_: Int <- 1 to driverConfig.heartbeatFailureThreshold + 1) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, assignersWithOldIncarnation)
    }

    // Verify: another assigner (B) takes over as the preferred assigner.
    val assignerB: TestAssigner = AssertionWaiter("wait for new PA").await {
      val newCandidate: TestAssigner =
        PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)
      assert(newCandidate.getAssignerInfoBlocking() != assignerA.getAssignerInfoBlocking())
      newCandidate
    }

    // Verify: the new preferred assigner (B) generates assignments.
    assertAssignerGeneratesAssignment(testEnv, assignerB, target, portNum)
    val assignment2Opt: Option[Assignment] =
      TestUtils.awaitResult(assignerB.getAssignment(target), Duration.Inf)
    assert(assignment2Opt.isDefined)
    // Verify: the assignment is up-to-date.
    assert(
      assignment2Opt.get.generation.toTime == fakeClock.instant() ||
      assignment2Opt.get.generation.toTime.isAfter(fakeClock.instant())
    )

    // Verify that the standby assigner (A) redirects watch requests to the preferred assigner (B).
    assertStandbyRedirectsToPreferredAssigner(assignerA, assignerB.localUri, target)

    // Reset the preferred assigner (A) to health by allowing it to respond to heartbeats.
    assignerA.resumeHeartbeatResponse()

    // Mangling the preferred assigner (B) by temporarily making it not respond to heartbeats.
    assignerB.pauseHeartbeatResponse()

    // Advance the clock so that the standby assigner's heartbeat failure threshold is reached.
    for (_: Int <- 1 to driverConfig.heartbeatFailureThreshold + 1) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, assignersWithOldIncarnation)
    }

    // Verify: the original preferred assigner (A) reassumes the preferred assignership.
    AssertionWaiter("wait for new PA").await {
      assert(
        PreferredAssignerTestUtils
          .getConvergedPreferredAssigner(testEnv.testAssigners)
          .getAssignerInfoBlocking() == assignerA.getAssignerInfoBlocking()
      )
    }

    // Verify: the original preferred assigner (A) generates assignments.
    assertAssignerGeneratesAssignment(testEnv, assignerA, target, portNum)
    val assignment3Opt: Option[Assignment] =
      TestUtils.awaitResult(assignerA.getAssignment(target), Duration.Inf)
    assert(assignment3Opt.isDefined)
    // Verify: the assignment is up-to-date.
    assert(
      assignment3Opt.get.generation.toTime == fakeClock.instant() ||
      assignment3Opt.get.generation.toTime.isAfter(fakeClock.instant())
    )

    // Verify that the standby assigner (B) redirects watch requests to the preferred assigner (A).
    assertStandbyRedirectsToPreferredAssigner(assignerB, assignerA.localUri, target)
  }

  test("Going from PA mode disabled in one incarnation to another") {
    // Test plan: Verify that the new assigner will generate assignments when going from PA mode
    // disabled in one incarnation to another. It doesn't matter if the latter PA's incarnation
    // is higher or lower than the former PA's incarnation.
    // 1. Start with one assigner (incarnation 39) with PA mode disabled.
    // 2. Verify that the assigner generates assignments.
    // 3. Start a new assigner with a higher incarnation (41) and PA mode disabled.
    // 4. Verify that the new assigner (41) generates assignments.
    // 5. Start a new assigner with a lower incarnation (37) and PA mode disabled.
    // 6. Verify that the new assigner (37) generates assignments.
    val priorIncarnation = Incarnation(39)
    val paDisabledConf: TestAssigner.Config =
      createAssignerConfig(priorIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf)

    val target = Target(getSafeName)

    // Issuing a direct watch request to the assigner and verify that it generates assignments.
    val assigner1: TestAssigner = testEnv.testAssigners.head
    val expectedRedirect1 = Redirect.EMPTY // Assigner with PA disabled should redirect to random.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(assigner1, target, expectedRedirect1)

    // Start a new assigner with a higher incarnation (41) and PA mode disabled.
    val higherIncarnation = Incarnation(41)
    val paDisabledConf2: TestAssigner.Config =
      createAssignerConfig(higherIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf2)
    // Stop the old assigner.
    assigner1.stop(ShutdownOption.ABRUPT)

    // Issuing a direct watch request to the new assigner and verify that it generates assignments.
    val assigner2: TestAssigner = testEnv.testAssigners.slice(1, 2).head
    val expectedRedirect2 = Redirect.EMPTY // Assigner with PA disabled should redirect to random.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(assigner2, target, expectedRedirect2)

    // Start a new assigner with a lower incarnation (37) and PA mode disabled.
    val lowerIncarnation = Incarnation(37)
    val paDisabledConf3: TestAssigner.Config =
      createAssignerConfig(lowerIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf3)
    // Stop the old assigner.
    assigner2.stop(ShutdownOption.ABRUPT)

    // Issuing a direct watch request to the new assigner and verify that it generates assignments.
    val assigner3: TestAssigner = testEnv.testAssigners.slice(2, 3).head
    val expectedRedirect3 = Redirect.EMPTY // Assigner with PA disabled should redirect to random.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(assigner3, target, expectedRedirect3)
  }

  test("Going from PA disabled to enabled with a higher incarnation") {
    // Test plan: Verify that a preferred assigner is chosen when we go from preferred assigner
    // mode disabled to enabled where the latter has a higher incarnation, specifically
    //  - Start with one assigner with a lower incarnation and PA disabled.
    //  - Wait for a slicelet to receive the assignment generated by the assigner.
    //  - Add three assigners with PA enabled and higher incarnations
    //  - Verify that one of them is chosen as PA and generates new assignments.
    val priorIncarnation = Incarnation(DEFAULT_INCARNATION.value - 1)
    val paDisabledConf: TestAssigner.Config =
      createAssignerConfig(priorIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf)
    // Wait for the assigner (PA disabled) to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assert(testEnv.testAssigners.size == 1, "only one assigner should be running")
    val disabledPaAssigner: TestAssigner = testEnv.testAssigners.head
    assertAssignerGeneratesAssignment(testEnv, disabledPaAssigner, target, portNum)

    // Add three assigners with PA enabled and higher incarnations.
    val paEnabledConf: TestAssigner.Config =
      createAssignerConfig(DEFAULT_INCARNATION)
    initializeAssigners(numAssigners = 3, paEnabledConf)

    assert(testEnv.testAssigners.size == 4)
    val enabledPaAssigners: Seq[TestAssigner] = testEnv.testAssigners.slice(1, 4)
    // Verify that only one assigner is selected as the preferred assigner, and the preferred
    // assigner is one of the new assigners.
    // And all new assigners agree on the same preferred assigner.
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    )
    val preferredAssigner: TestAssigner = PreferredAssignerTestUtils.getConvergedPreferredAssigner(
      enabledPaAssigners
    )

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri = preferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      preferredAssigner,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri), redirectTokenOpt = None)
    )
  }

  // The test below is a test which should be avoided in production, but it's good to have it here
  // to ensure that the system behaves as desired.
  test("Going from PA disabled to enabled with a lower incarnation") {
    // Test plan: Verify that a preferred assigner is chosen when we go from preferred assigner
    // mode disabled to enabled where the latter has a lower preferred assigner store incarnation.
    // In this case, split brain will occur, because the preferred assigner value with PA
    // disabled is not persisted in the store, and it keeps generating assignments. The assigner
    // with PA disabled will have a PA selected and generates assignments. In production, this
    // scenario should not happen, and even if it does, the assigner with PA disabled should be
    // stopped by Kubernetes, so that the split brain scenario does not persist. Additionally,
    // it is better to be split brained than no brained, and also that if disabled and preferred
    // assigners co-exist then all slicelets will tend to eventually land up speaking to the
    // preferred assigner clique because they will be redirected to a random assigner on their next
    // request if they talk to the disabled PA assigner, whereas with an enabled PA assigner they
    // will always be redirected to the preferred assigner in the PA clique.
    val incarnation = Incarnation(17)
    val paDisabledConf: TestAssigner.Config =
      createAssignerConfig(incarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf)
    // Wait for the assigner (PA disabled) to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assert(testEnv.testAssigners.size == 1, "only one assigner should be running")
    val disabledPaAssigner: TestAssigner = testEnv.testAssigners.head
    assertAssignerGeneratesAssignment(testEnv, disabledPaAssigner, target, portNum)

    // Add three assigners with PA enabled and lower incarnations.
    val lowerIncarnation = Incarnation(incarnation.value - 1)
    val paEnabledConf: TestAssigner.Config =
      createAssignerConfig(lowerIncarnation)
    initializeAssigners(numAssigners = 3, paEnabledConf)

    assert(testEnv.testAssigners.size == 4)
    val enabledPaAssigners: Seq[TestAssigner] = testEnv.testAssigners.slice(1, 4)
    // Verify that only one assigner is selected as the preferred assigner, and the preferred
    // assigner is one of the new assigners.
    // And all new assigners agree on the same preferred assigner.
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    )

    val preferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(enabledPaAssigners)

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri = preferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      preferredAssigner,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri), redirectTokenOpt = None)
    )
  }

  test("bump store incarnation up from one incarnation to another") {
    // Test plan: start with three assigners with an old incarnation, and later start
    // three assigners with a higher incarnation. Verify that, after shutting down the
    // the old assigners, all new assigners agree on the same preferred assigner with the new
    // incarnation. Additionally, verify that the new preferred assigner generates assignments,
    // and all other assigners redirect watch requests to the new preferred assigner.
    val oldIncarnation: Incarnation = Incarnation(20)
    val oldIncarnationConf: TestAssigner.Config =
      createAssignerConfig(oldIncarnation)

    initializeAssigners(NUM_ASSIGNERS, oldIncarnationConf)
    val assignersWithOldIncarnation: Seq[TestAssigner] = testEnv.testAssigners

    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(assignersWithOldIncarnation)
    // Wait for the preferred assigner to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assertAssignerGeneratesAssignment(testEnv, initialPreferredAssigner, target, portNum)

    // Add three assigners with a higher incarnation.
    val newIncarnation: Incarnation = Incarnation(oldIncarnation.value + 1)
    assert(newIncarnation > oldIncarnation)
    val newIncarnationConf: TestAssigner.Config =
      createAssignerConfig(newIncarnation)

    // Before initializing the new assigners, track the changes in the number of preferred assigner
    // write exceptions.
    val paWriteExceptionsChangeTracker = MetricUtils.ChangeTracker[Long] { () =>
      PreferredAssignerTestUtils.getNumPreferredAssignerWriteExceptions
    }
    initializeAssigners(NUM_ASSIGNERS, newIncarnationConf)
    assert(testEnv.testAssigners.size == NUM_ASSIGNERS * 2)
    val assignersWithNewIncarnation: Seq[TestAssigner] =
      testEnv.testAssigners.slice(NUM_ASSIGNERS, NUM_ASSIGNERS * 2)
    assert(assignersWithNewIncarnation.size == NUM_ASSIGNERS)

    // Shutdown the old assigners.
    for (assigner: TestAssigner <- assignersWithOldIncarnation) {
      assigner.stop(ShutdownOption.ABRUPT)
    }

    // Verify that the latest preferred assigner incarnation is updated to the new incarnation.
    // In most cases, this should succeed without advancing the clock. However, in an edge case
    // where three new Assigners start up at almost the same time, all writes to the store may
    // fail due to insufficient version high watermark. The underlying EtcdClient bumps up the
    // watermark but won't retry the write. In this case, we advance the clock exactly once to
    // trigger the retry logic in the preferred assigner driver.
    var hasClockAdvanced = false
    AssertionWaiter("latest incarnation is new incarnation").await {
      if (paWriteExceptionsChangeTracker.totalChange() > 0 && !hasClockAdvanced) {
        // Advance the clock to trigger the retry logic in the preferred assigner driver.
        advanceClockBySync(fakeClock, driverConfig.writeRetryInterval, testEnv.testAssigners)
        hasClockAdvanced = true
      }
      assert(PreferredAssignerTestUtils.getLatestPreferredAssignerIncarnation == newIncarnation)
    }

    // Verify: an assigner is selected from the new incarnation and it generates assignments.
    val newPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(assignersWithNewIncarnation)
    val preferredAssignerUri = newPreferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      newPreferredAssigner,
      target,
      Redirect(Some(preferredAssignerUri), redirectTokenOpt = None)
    )
  }

  // The test below is a test which should be avoided in production, but it's good to have it here
  // to ensure that the system behaves as desired.
  test("lower store incarnation down from one incarnation to another") {
    // Test plan: start with three assigners with a higher incarnation, and later start
    // three assigners with a lower incarnation. Verify that, after shutting down the
    // the old assigners, the assigners with new incarnation cannot be selected as the preferred
    // assigner.
    val oldHigherIncarnation: Incarnation = Incarnation(20)
    val oldIncarnationConf: TestAssigner.Config =
      createAssignerConfig(oldHigherIncarnation)

    initializeAssigners(NUM_ASSIGNERS, oldIncarnationConf)
    val assignersWithOldIncarnation: Seq[TestAssigner] = testEnv.testAssigners

    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(assignersWithOldIncarnation)

    // Wait for the preferred assigner to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assertAssignerGeneratesAssignment(testEnv, initialPreferredAssigner, target, portNum)

    // Add three assigners with a lower incarnation.
    val newIncarnation: Incarnation = Incarnation(oldHigherIncarnation.value - 1)
    assert(newIncarnation < oldHigherIncarnation)
    val newIncarnationConf: TestAssigner.Config =
      createAssignerConfig(newIncarnation)
    initializeAssigners(NUM_ASSIGNERS, newIncarnationConf)
    assert(testEnv.testAssigners.size == NUM_ASSIGNERS * 2)
    val assignersWithNewIncarnation: Seq[TestAssigner] =
      testEnv.testAssigners.slice(NUM_ASSIGNERS, NUM_ASSIGNERS * 2)
    assert(assignersWithNewIncarnation.size == NUM_ASSIGNERS)

    // Shutdown the old assigners.
    for (assigner: TestAssigner <- assignersWithOldIncarnation) {
      assigner.stop(ShutdownOption.ABRUPT)
    }

    // Advance the clock so that the standby assigner's heartbeat failure threshold is reached.
    for (_: Int <- 1 to driverConfig.heartbeatFailureThreshold + 1) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    }

    // Verify: the new assigners cannot be selected as the preferred assigner, and they cannot
    // generate assignments.
    Thread.sleep(100) // Wait for a bit to ensure that the takeover won't accidentally happen.
    val sliceletRequest = createSliceletClientRequest(target, createTestSquid(s"pod-$target-1"))
    val clerkRequest = createClerkClientRequest(target)
    for (assigner: TestAssigner <- assignersWithNewIncarnation) {
      // Verify: the new assigners does not generate assignments.
      for (clientReq: ClientRequest <- Seq(sliceletRequest, clerkRequest)) {
        AssertionWaiter("New assigners don't generate assignments").await {
          val response = ClientResponse.fromProto(
            TestUtils.awaitResult(assigner.handleWatch(CTX, clientReq.toProto), Duration.Inf)
          )
          assert(response.syncState == KnownGeneration(Generation.EMPTY))
        }
      }
    }
  }

  test("Going from PA enabled to PA disabled with lower incarnation") {
    // Test plan: Verify that when we go from preferred assigner mode enabled to disabled where the
    // latter has a lower incarnation, and that the new PA disabled assigner generates assignments.
    // This simulates the case of rolling back the PA feature. In production, this should not matter
    // because a PA disabled assigner does not read the store incarnation. But it's good to
    // verify that it behaves as expected.
    val oldIncarnationConf: TestAssigner.Config =
      createAssignerConfig(DEFAULT_INCARNATION)

    initializeAssigners(NUM_ASSIGNERS, oldIncarnationConf)
    val assignersWithOldIncarnation: Seq[TestAssigner] = testEnv.testAssigners

    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)

    // Wait for the preferred assigner to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assertAssignerGeneratesAssignment(testEnv, initialPreferredAssigner, target, portNum)

    val newIncarnation = Incarnation(DEFAULT_INCARNATION.value - 1)
    assert(newIncarnation < DEFAULT_INCARNATION)
    // Start a new assigner with a lower incarnation and PA disabled.
    val newIncarnationConf: TestAssigner.Config =
      createAssignerConfig(newIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, newIncarnationConf)
    assert(testEnv.testAssigners.size == 4)
    val newAssigner: TestAssigner = testEnv.testAssigners.last

    // Stop the old assigners.
    for (assigner: TestAssigner <- assignersWithOldIncarnation) {
      assigner.stop(ShutdownOption.ABRUPT)
    }
    // Verify that the new assigner generates assignments.
    val expectedRedirect = Redirect.EMPTY // Assigner with PA disabled should redirect to random.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(newAssigner, target, expectedRedirect)
  }

  test("Going from PA enabled to PA disabled with higher incarnation") {
    // Test plan: Verify that when we go from preferred assigner mode enabled to disabled where the
    // latter has a higher incarnation, then the new PA disabled assigner generates assignments.
    // This simulates the case of disabling the PA feature and bumping the store incarnation.
    val oldIncarnationConf: TestAssigner.Config =
      createAssignerConfig(DEFAULT_INCARNATION)

    initializeAssigners(NUM_ASSIGNERS, oldIncarnationConf)
    val assignersWithOldIncarnation: Seq[TestAssigner] = testEnv.testAssigners

    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val initialPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(testEnv.testAssigners)

    // Wait for the preferred assigner to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assertAssignerGeneratesAssignment(testEnv, initialPreferredAssigner, target, portNum)

    val newIncarnation = Incarnation(DEFAULT_INCARNATION.value + 1)
    assert(newIncarnation > DEFAULT_INCARNATION)
    // Start a new assigner with a higher incarnation and PA disabled.
    val newIncarnationConf: TestAssigner.Config =
      createAssignerConfig(newIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, newIncarnationConf)
    assert(testEnv.testAssigners.size == 4)
    val newAssigner: TestAssigner = testEnv.testAssigners.last

    // Stop the old assigners.
    for (assigner: TestAssigner <- assignersWithOldIncarnation) {
      assigner.stop(ShutdownOption.ABRUPT)
    }
    // Verify that the new assigner generates assignments.
    val expectedRedirect = Redirect.EMPTY // Assigner with PA disabled should redirect to random.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(newAssigner, target, expectedRedirect)
  }

  test("PA disabled to PA enabled to PA disabled to PA enabled with incarnation bumps") {
    // Test plan: Simulate the case of toggling the PA feature on and off multiple times where
    // each toggle has a higher incarnation than the previous one. Verify that the new preferred
    // assigner generates assignments.
    val firstIncarnation = Incarnation(17)
    val paDisabledConf: TestAssigner.Config =
      createAssignerConfig(firstIncarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf)
    // Wait for the assigner (PA disabled) to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assert(testEnv.testAssigners.size == 1, "only one assigner should be running")
    val disabledPaAssigner: TestAssigner = testEnv.testAssigners.head
    assertAssignerGeneratesAssignment(testEnv, disabledPaAssigner, target, portNum)

    // Add three assigners with PA enabled and higher incarnations.
    val secondIncarnation = Incarnation(firstIncarnation.value + 1)
    val paEnabledConf: TestAssigner.Config =
      createAssignerConfig(secondIncarnation)
    initializeAssigners(numAssigners = 3, paEnabledConf)

    assert(testEnv.testAssigners.size == 4)
    disabledPaAssigner.stop(ShutdownOption.ABRUPT)
    val enabledPaAssigners: Seq[TestAssigner] = testEnv.testAssigners.slice(1, 4)
    // Verify that only one assigner is selected as the preferred assigner, and the preferred
    // assigner is one of the new assigners.
    // And all new assigners agree on the same preferred assigner.
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    ) // Trigger PA writes.
    val preferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(enabledPaAssigners)

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri = preferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      preferredAssigner,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri), redirectTokenOpt = None)
    )

    // Start a new assigner with a higher incarnation and PA disabled.
    val thirdIncarnation = Incarnation(secondIncarnation.value + 1)
    assert(thirdIncarnation > secondIncarnation)
    val paDisabledConf2: TestAssigner.Config =
      createAssignerConfig(thirdIncarnation, preferredAssignerEnabled = false)

    // Before initializing the new assigners, track the changes in the number of preferred assigner
    // write exceptions.
    val paWriteExceptionsChangeTracker = MetricUtils.ChangeTracker[Long] { () =>
      PreferredAssignerTestUtils.getNumPreferredAssignerWriteExceptions
    }
    initializeAssigners(numAssigners = 1, paDisabledConf2)

    assert(testEnv.testAssigners.size == 5)
    val disabledPaAssigner2: TestAssigner = testEnv.testAssigners.last

    // Verify that the new PA disabled assigner generates assignments.
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      disabledPaAssigner2,
      target,
      Redirect.EMPTY
    )

    // Shutdown the PA enabled assigners.
    for (assigner: TestAssigner <- enabledPaAssigners) {
      assigner.stop(ShutdownOption.ABRUPT)
    }

    // Start a set of assigners with the highest incarnation and PA enabled.
    val fourthIncarnation = Incarnation(thirdIncarnation.value + 1)
    assert(fourthIncarnation > thirdIncarnation)
    val paEnabledConf2: TestAssigner.Config =
      createAssignerConfig(fourthIncarnation)
    initializeAssigners(numAssigners = 3, paEnabledConf2)
    assert(testEnv.testAssigners.size == 8)

    val enabledPaAssigners2: Seq[TestAssigner] = testEnv.testAssigners.slice(5, 8)
    // Verify that only one assigner is selected as the preferred assigner, and the preferred
    // assigner is one of the new assigners.
    // In most cases, this should succeed without advancing the clock. However, in an edge case
    // where three new Assigners start up at almost the same time, all writes to the store may
    // fail due to insufficient version high watermark. The underlying EtcdClient bumps up the
    // watermark but won't retry the write. In this case, we advance the clock exactly once to
    // trigger the retry logic in the preferred assigner driver.
    var hasClockAdvanced = false
    AssertionWaiter("latest incarnation is new incarnation").await {
      if (paWriteExceptionsChangeTracker.totalChange() > 0 && !hasClockAdvanced) {
        // Advance the clock to trigger the retry logic in the preferred assigner driver.
        advanceClockBySync(fakeClock, driverConfig.writeRetryInterval, testEnv.testAssigners)
        hasClockAdvanced = true
      }
      assert(
        PreferredAssignerTestUtils.getLatestPreferredAssignerIncarnation ==
        fourthIncarnation
      )
    }
    val newPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(enabledPaAssigners2)

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri2 = newPreferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      testEnv.testAssigners
        .find(_.getAssignerInfoBlocking() == newPreferredAssigner.getAssignerInfoBlocking())
        .get,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri2), redirectTokenOpt = None)
    )
  }

  test("PA disabled to PA enabled to PA disabled to PA enabled with same store incarnation") {
    // Test plan: Simulate the case of toggling the PA feature on and off multiple times where
    // each toggle has the same store incarnation. We have two cycles of disabling and enabling
    // PA to test how later PA enabled assigners handle seeing a valid PA value in etcd. Verify
    // that the new preferred assigner generates assignments.
    val incarnation = Incarnation(17)
    val paDisabledConf: TestAssigner.Config =
      createAssignerConfig(incarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf)
    // Wait for the assigner (PA disabled) to generate an assignment.
    val target = Target(getSafeName)
    val portNum: Int = getNextSliceletPortNumber
    assert(testEnv.testAssigners.size == 1, "only one assigner should be running")
    val disabledPaAssigner: TestAssigner = testEnv.testAssigners.head
    assertAssignerGeneratesAssignment(testEnv, disabledPaAssigner, target, portNum)

    // Add three assigners with PA enabled with the same incarnation.
    val paEnabledConf: TestAssigner.Config =
      createAssignerConfig(incarnation)
    initializeAssigners(numAssigners = 3, paEnabledConf)

    assert(testEnv.testAssigners.size == 4)
    disabledPaAssigner.stop(ShutdownOption.ABRUPT)

    // Trigger PA write and get the new preferred assigner.
    advanceClockBySync(
      fakeClock,
      driverConfig.initialPreferredAssignerTimeout,
      testEnv.testAssigners
    )
    val enabledPaAssigners: Seq[TestAssigner] = testEnv.testAssigners.slice(1, 4)
    val preferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(enabledPaAssigners)

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri: URI = preferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      preferredAssigner,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri), redirectTokenOpt = None)
    )

    // Start a new assigner with the same incarnation and PA disabled.
    val paDisabledConf2: TestAssigner.Config =
      createAssignerConfig(incarnation, preferredAssignerEnabled = false)
    initializeAssigners(numAssigners = 1, paDisabledConf2)
    assert(testEnv.testAssigners.size == 5)

    // Verify that the new PA disabled assigner generates assignments.
    val disabledPaAssigner2: TestAssigner = testEnv.testAssigners.last
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      disabledPaAssigner2,
      target,
      Redirect.EMPTY
    )

    // Shut down the PA enabled assigners abruptly so they do not abdicate the PA value in etcd.
    for (assigner: TestAssigner <- enabledPaAssigners) {
      assigner.stop(ShutdownOption.ABRUPT)
    }

    // Start a set of assigners with the same incarnation and PA enabled.
    val paEnabledConf2: TestAssigner.Config =
      createAssignerConfig(incarnation)
    initializeAssigners(numAssigners = 3, paEnabledConf2)
    assert(testEnv.testAssigners.size == 8)

    // The store still holds the preferred assigner value written by the previous preferred
    // assigner with the same store incarnation because it was not abdicated during shutdown.
    // Due to the same store incarnation, there is no eager takeover and the new assigners
    // must heartbeat to detect the dead preferred assigner.
    //
    // We ensure the that the new assigners successfully redirect watch requests to the
    // previous preferred assigner. This indicates the assigners have incorporated the old
    // preferred assigner value from etcd and have begun sending heartbeats.
    val enabledPaAssigners2: Seq[TestAssigner] = testEnv.testAssigners.slice(5, 8)
    for (assigner: TestAssigner <- enabledPaAssigners2) {
      assertStandbyRedirectsToPreferredAssigner(assigner, expectedPreferredAssignerUri, target)
    }

    // We advance the clock by the heartbeat interval until the heartbeat failure threshold is
    // reached to trigger a new preferred assigner election.
    for (_: Int <- 0 to driverConfig.heartbeatFailureThreshold) {
      advanceClockBySync(fakeClock, driverConfig.heartbeatInterval, testEnv.testAssigners)
    }
    val newPreferredAssigner: TestAssigner =
      PreferredAssignerTestUtils.getConvergedPreferredAssigner(enabledPaAssigners2)

    // Verify: the new preferred assigner generates assignments.
    val expectedPreferredAssignerUri2: URI = newPreferredAssigner.getAssignerInfoBlocking().uri
    assertAssignerGeneratesAssignmentByDirectWatchRequest(
      testEnv.testAssigners
        .find(_.getAssignerInfoBlocking() == newPreferredAssigner.getAssignerInfoBlocking())
        .get,
      target,
      expectedRedirect = Redirect(Some(expectedPreferredAssignerUri2), redirectTokenOpt = None)
    )
  }
}
