package com.databricks.dicer.assigner

import com.databricks.caching.util.{
  AssertionWaiter,
  Cancellable,
  FakeTypedClock,
  MetricUtils,
  SequentialExecutionContext,
  ValueStreamCallback,
  TestUtils
}
import scala.concurrent.duration._

import com.databricks.conf.Configs
import com.databricks.dicer.assigner.PreferredAssignerMetrics.MonitoredAssignerRole
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.WatchServerHelper.WATCH_RPC_TIMEOUT
import com.databricks.dicer.common.SyncAssignmentState.{KnownAssignment, KnownGeneration}
import com.databricks.dicer.common.TestSliceUtils.createTestSquid
import com.databricks.dicer.common.{
  Assignment,
  ClerkData,
  ClientRequest,
  ClientResponse,
  Generation,
  Incarnation,
  InternalDicerTestEnvironment,
  Redirect,
  SliceletData,
  SliceletState,
  TestAssigner
}
import com.databricks.dicer.external.{Clerk, ResourceAddress, Slicelet, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext
import io.prometheus.client.CollectorRegistry
import java.net.URI

import scala.concurrent.{Future, Promise}
import scala.util.Random
import com.databricks.caching.util.TestUtils

object PreferredAssignerTestHelper {

  /**
   * A [[KubernetesMembershipChecker.Factory]] for tests that always returns [[None]],
   * disabling K8s pod membership checking.
   */
  val noOpMembershipCheckerFactory: KubernetesMembershipChecker.Factory =
    new KubernetesMembershipChecker.Factory {
      override def create(
          assignerInfo: AssignerInfo,
          assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker] = None
    }

  /** The RPC context used in watch requests. */
  private val CTX: RPCContext = JettyTestRPCContext
    .builder()
    .method("POST")
    .uri("/")
    .build()

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /**
   * Returns the latest known PA value from `driver` and blocks the calling thread until a
   * value is available.
   */
  def getLatestKnownPreferredAssignerBlocking(
      driver: PreferredAssignerDriver,
      ec: SequentialExecutionContext): PreferredAssignerValue = {
    val promise: Promise[PreferredAssignerValue] = Promise()
    val cancellable: Cancellable = driver.watch(
      new ValueStreamCallback[PreferredAssignerConfig](ec) {
        override def onSuccess(value: PreferredAssignerConfig): Unit = {
          promise.trySuccess(value.knownPreferredAssigner)
        }
      }
    )
    val result: PreferredAssignerValue = TestUtils.awaitResult(promise.future, Duration.Inf)
    cancellable.cancel() // Don't leak the watch stream.
    result
  }

  /**
   * REQUIRES: `assigners` is non-empty.
   *
   * From the group of `assigners`, returns a [[PreferredAssignerValue]] only after it designates
   * one of the members in the group and all members agree on the value.
   */
  def getConvergedPreferredAssigner(assigners: Seq[TestAssigner]): TestAssigner = {
    require(assigners.nonEmpty)
    val target: Target = Target("test-target-for-pa-discovery")
    if (assigners.size == 1) {
      AssertionWaiter("Wait for single assigner to be preferred").await {
        assertAssignerGeneratesAssignmentByDirectWatchRequest(
          assigners.head,
          target,
          Redirect(Some(assigners.head.localUri))
        )
      }
      assigners.head
    } else {
      var preferredAssigner: TestAssigner = null // We'll never return null, but throw instead.
      val ec = SequentialExecutionContext.createWithDedicatedPool("pa-discovery")
      AssertionWaiter("Await for preferred agreement").await {
        // Send a watch request to all assigners and wait for the first response with a redirect.
        // We can't send to a random assigner because:
        // 1. If we happen to send to the preferred assigner, it might not respond without a fake
        //    clock advancement (it waits until a new assignment is available or timeout occurs).
        // 2. Standby assigners return immediately with a redirect to the preferred assigner.
        // By sending to all assigners, we ensure we get an immediate response from at least one
        // standby, avoiding unnecessary delays in tests.
        val request: ClientRequest =
          createSliceletClientRequest(target, createTestSquid(s"pod-$target-1"))

        // Send watch requests to all assigners concurrently
        val responseFutures = assigners.map { assigner =>
          assigner.handleWatch(CTX, request.toProto)
        }

        // Wait for the first successful response with a redirect
        val response = ClientResponse.fromProto(
          TestUtils.awaitResult(Future.firstCompletedOf(responseFutures)(ec), Duration.Inf)
        )
        val preferredUri: URI = response.redirect.addressOpt match {
          case Some(uri: URI) => uri
          case None =>
            // If we didn't get a redirect for whatever reason (this is the PA and there isn't yet
            // a PA, or this particular assigner hasn't yet learned of the PA), just try again.
            throw new AssertionError("Didn't get initial redirect; trying again.")
        }

        // To be converged, the preferred must be in the group and all others in the group must
        // agree that it is preferred.
        var uriInGroup: Boolean = false
        for (assigner: TestAssigner <- assigners) {
          if (assigner.localUri == preferredUri) {
            uriInGroup = true
            preferredAssigner = assigner
          } else {
            val response = ClientResponse.fromProto(
              TestUtils.awaitResult(assigner.handleWatch(CTX, request.toProto), Duration.Inf)
            )
            val redirectToSamePreferred: Boolean =
              response.redirect.addressOpt.contains(preferredUri)
            assert(redirectToSamePreferred)
          }
        }
        assert(uriInGroup)
      }
      preferredAssigner
    }
  }

  /**
   * Asserts that the current assigner role in the gauge is `role`.
   *
   * @note Please note that when there are multiple assigners, this method will only check the role
   *       of the one who last updated the gauge. In production, different assigners are running in
   *       different pods, and we can filter by "kubernetes_pod_name" label to get the role of a
   *       specific assigner.
   */
  def assertAssignerRoleGaugeMatches(
      role: PreferredAssignerMetrics.MonitoredAssignerRole.Value): Unit = {
    val gaugeName: String = "dicer_assigner_preferred_assigner_role_gauge"
    AssertionWaiter(s"Assert current role is $role").await {
      val currentRoleValues: Map[String, Int] = MonitoredAssignerRole.values.map {
        role: MonitoredAssignerRole.Value =>
          role.toString -> MetricUtils
            .getMetricValue(registry, gaugeName, Map("role" -> role.toString))
            .toInt
      }.toMap
      for (otherRole: MonitoredAssignerRole.Value <- MonitoredAssignerRole.values.filterNot(
          _ == role
        )) {
        assert(currentRoleValues(otherRole.toString) == 0, s"Role $otherRole should be 0")
      }
      assert(currentRoleValues(role.toString) == 1, s"Role $role should be 1")
    }
  }

  def createAssignerConfig(
      preferredAssignerStoreIncarnation: Incarnation,
      preferredAssignerEnabled: Boolean = true): TestAssigner.Config = {
    TestAssigner.Config.create(
      assignerConf = new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.preferredAssigner.modeEnabled" -> preferredAssignerEnabled,
          "databricks.dicer.assigner.preferredAssigner.storeIncarnation" ->
          preferredAssignerStoreIncarnation.value,
          "databricks.dicer.assigner.store.etcd.sslEnabled" -> false
        )
      )
    )
  }

  /**
   * Asserts that the `assigner` specified generates assignments.
   *
   * This is done by creating a new slicelet specified by `sliceletPort`, and verifying that the new
   * slicelet receives an assignment, which includes the new slicelet resource.
   */
  def assertAssignerGeneratesAssignment(
      testEnv: InternalDicerTestEnvironment,
      assigner: TestAssigner,
      target: Target,
      sliceletPort: Int): Unit = {
    val slicelet: Slicelet = testEnv.createSlicelet(target).start(sliceletPort, listenerOpt = None)
    val pod = slicelet.impl.squid

    AssertionWaiter("Assigner-can-generate-assignments").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(assigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources.contains(pod))
    }

    // Make sure that the Slicelet has the assignment as well.
    AssertionWaiter("Slicelet-receive-the-assignment").await {
      assert(slicelet.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    slicelet.forTest.stop() // Stop the slicelet to reduce log spam
  }

  /** Creates a Slicelet client request with the given parameters and no known assignment. */
  def createSliceletClientRequest(target: Target, squid: Squid): ClientRequest = {
    ClientRequest(
      target,
      KnownGeneration(Generation.EMPTY),
      "subscriber",
      WATCH_RPC_TIMEOUT,
      SliceletData(
        squid,
        SliceletState.Running,
        "localhostNamespace",
        attributedLoads = Vector.empty,
        unattributedLoadOpt = None
      ),
      supportsSerializedAssignment = true
    )
  }

  /** Creates a Clerk request with the given parameters and no known assignment. */
  def createClerkClientRequest(target: Target): ClientRequest = {
    ClientRequest(
      target,
      KnownGeneration(Generation.EMPTY),
      "direct-clerk",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
  }

  /**
   * Asserts that the `preferredAssigner` can generate assignments by sending a direct watch
   * request to the Assigner for `target`.
   *
   * Unlike [[assertAssignerGeneratesAssignment]], we don't create a Slicelet here, because in the
   * test environment, we don't have a mechanism to send a watch request to a random Assigner (like
   * what clusterIP does). Therefore, if we were to create a Slicelet here, and the default address
   * in its underlying `SliceletLookup` happened to be a stopped assigner, then this assigner would
   * keep sending requests to the stopped assigner, resulting in test failures.
   *
   * Additionally, asserts that `preferredAssigner` responds to our watch request with
   * `expectedRedirect`.
   */
  def assertAssignerGeneratesAssignmentByDirectWatchRequest(
      preferredAssigner: TestAssigner,
      target: Target,
      expectedRedirect: Redirect): Unit = {
    // Create a Slicelet request directly to the given `preferredAssigner`. Verify that the
    // preferred assigner generates an assignment.
    val testSquid = createTestSquid(s"pod-$target-1")
    val clientRequest: ClientRequest = createSliceletClientRequest(target, testSquid)

    AssertionWaiter("preferred-assigner-generates-assignments").await {
      val response = ClientResponse.fromProto(
        TestUtils
          .awaitResult(preferredAssigner.handleWatch(CTX, clientRequest.toProto), Duration.Inf)
      )
      response.syncState match {
        case KnownGeneration(_) =>
          assert(false, "The preferred assigner should generate assignments")
        case KnownAssignment(_) => // Pass.
      }
      assert(response.redirect == expectedRedirect)
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(preferredAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources.contains(testSquid))
    }
  }

  /**
   * Asserts that `standbyAssigner` redirects watch requests to the expected preferred assigner URI
   * specified by `expectedPreferredAssignerUri`.
   */
  def assertStandbyRedirectsToPreferredAssigner(
      standbyAssigner: TestAssigner,
      expectedPreferredAssignerUri: URI,
      target: Target): Unit = {
    // Create a Slicelet request directly to the given `standbyAssigner`. Verify that standby
    // assigner redirects the watch request to the preferred assigner with sync state as
    // KnownGeneration(Generation.EMPTY).
    val clientRequest: ClientRequest =
      createSliceletClientRequest(target, createTestSquid(s"pod-$target-1"))

    AssertionWaiter("standby-redirects-to-preferred-assigner").await {
      val response = ClientResponse.fromProto(
        TestUtils.awaitResult(standbyAssigner.handleWatch(CTX, clientRequest.toProto), Duration.Inf)
      )
      assert(response.syncState == KnownGeneration(Generation.EMPTY), "Sync state should be empty")
      assert(response.redirect.addressOpt.nonEmpty, "Redirect address should be non-empty")
      assert(
        response.redirect.addressOpt.get == expectedPreferredAssignerUri,
        "Should redirect to PA"
      )
    }
  }

  /**
   * Advances the clock by the given amount of time, and make sure all `assigners` observed the
   * advanced clock state.
   */
  def advanceClockBySync(
      fakeClock: FakeTypedClock,
      duration: FiniteDuration,
      assigners: Seq[TestAssigner]): Unit = {
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

  /** Returns the number of preferred assigner write exceptions. */
  def getNumPreferredAssignerWriteExceptions: Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_etcd_client_op_latency_count",
        Map(
          "operationResult" -> "failure",
          "keyNamespace" -> "preferred-assigner"
        )
      )
      .toLong
  }

  /** Returns the incarnation of the latest preferred assigner in the store. */
  def getLatestPreferredAssignerIncarnation: Incarnation = {
    val incarnationNum: Long = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_preferred_assigner_latest_known_incarnation_gauge",
        Map.empty
      )
      .toLong
    Incarnation(incarnationNum)
  }

  /**
   * Returns the count of write operations with the specified outcome in the preferred assigner
   * write latency histogram.
   */
  def getPreferredAssignerWriteLatencyCount(outcome: String): Int = {
    MetricUtils.getHistogramCount(
      registry,
      "dicer_assigner_preferred_assigner_write_latency",
      Map("outcome" -> outcome)
    )
  }

  /**
   * Returns the sum of latencies (in seconds) for write operations with the specified outcome in
   * the preferred assigner write latency histogram.
   */
  def getPreferredAssignerWriteLatencySum(outcome: String): Double = {
    MetricUtils.getHistogramSum(
      registry,
      "dicer_assigner_preferred_assigner_write_latency",
      Map("outcome" -> outcome)
    )
  }

  /**
   * Verifies that preferred assigner functionality works as expected at startup. Specifically,
   * checks that:
   *  1. All assigners agree on the same preferred assigner, and the standbys initiate periodic
   *     heartbeats to the preferred assigner.
   *  2. The preferred assigner generates assignments, and the assignment is propagated to the
   *     Slicelet and the Clerk that watches directly to Assigners.
   *  3. Standby assigners redirect watch requests to the preferred assigner.
   *  4. The preferred assigner keeps being the preferred assigner, and there are no heartbeats
   *     sent to the standby assigners.
   *
   * @param driverConfig configuration for the preferred assigner driver in use by the assigners
   *                     whose behavior is being verified.
   * @param testAssigners assigners whose behavior is being verified
   * @param createDirectClerk function that takes a [[Target]] and an initial assigner index and
   *                          returns a newly created [[Clerk]] that connects directly to the
   *                          assigner at the given index in the list of `testAssigners`
   * @param createSlicelet function that takes a [[Target]] and returns a newly created [[Slicelet]]
   * @param getNextSliceletPortNumber function that returns an unused local port number
   * @param clock fake clock with which each assigner in `testAssigners` has been instantiated
   */
  def verifyPreferredAssignerFunctionality(
      driverConfig: EtcdPreferredAssignerDriver.Config,
      testAssigners: IndexedSeq[TestAssigner],
      createDirectClerk: (Target, Int, Option[String]) => Clerk[ResourceAddress],
      createSlicelet: Target => Slicelet,
      getNextSliceletPortNumber: () => Int,
      clock: FakeTypedClock
  ): Unit = {
    // Verify 1: all assigners agree on the same preferred assigner, and the standbys initiate
    // heartbeats to the preferred assigner.
    val preferredAssigner: TestAssigner =
      PreferredAssignerTestHelper.getConvergedPreferredAssigner(testAssigners)
    val standbys: Seq[TestAssigner] = testAssigners.filter { assigner: TestAssigner =>
      assigner.getAssignerInfoBlocking() != preferredAssigner.getAssignerInfoBlocking()
    }
    AssertionWaiter("Initial heartbeats").await {
      // After a preferred assigner is determined, each standby sends a heartbeat to the preferred
      // assigner. Because we haven't advanced the clock afterward, the preferred assigner should
      // have received exactly 2 heartbeats. And the standby assigners should have received the
      // response from the preferred assigner.
      assert(preferredAssigner.getNumberOfHeartbeatsReceived == 2)
      for (standbyAssigner: TestAssigner <- standbys) {
        assert(standbyAssigner.getNumberOfHeartbeatsReceived == 0)
        assert(
          TestUtils.awaitResult(standbyAssigner.getHighestSucceededHeartbeatOpID, Duration.Inf) == 1
        )
      }
    }

    // Verify 2: only the preferred assigner generates assignments. This is done by creating a new
    // Slicelet and starting it, and then verifying that the preferred assigner generates an
    // assignment for the target. Also, create a direct clerk that initially issues a watch request
    // to a random assigner, and verify that the clerk receives the assignment.
    val target = Target(s"preferred-assigner-test-target-${Random.nextLong()}")
    val slicelet: Slicelet =
      createSlicelet(target).start(getNextSliceletPortNumber(), listenerOpt = None)
    val directClerk: Clerk[ResourceAddress] =
      createDirectClerk(target, Random.nextInt(testAssigners.length), None)
    val pod = slicelet.impl.squid
    AssertionWaiter("Preferred assigner generates assignments").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(preferredAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources.contains(pod))
    }
    // Make sure that the Slicelet and the direct clerk have the assignment as well.
    AssertionWaiter("Slicelet and direct clerk receives the assignment").await {
      assert(slicelet.impl.forTest.getLatestAssignmentOpt.isDefined)
      assert(directClerk.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Verify 3: the standbys respond to watch requests with redirects to the preferred assigner.
    // Create a Slicelet and a Clerk request directly to the given `standbyAssigner`. Verify that
    // standby assigner redirects the watch requests to the preferred assigner with sync state as
    // KnownGeneration(Generation.EMPTY).
    val sliceletRequest: ClientRequest =
      createSliceletClientRequest(target, createTestSquid(s"pod-$target-1"))
    val clerkRequest: ClientRequest = createClerkClientRequest(target)
    for (standbyAssigner: TestAssigner <- standbys) {
      for (clientReq: ClientRequest <- Seq(sliceletRequest, clerkRequest)) {
        AssertionWaiter("Standby redirects to preferred assigner").await {
          val response = ClientResponse.fromProto(
            TestUtils.awaitResult(standbyAssigner.handleWatch(CTX, clientReq.toProto), Duration.Inf)
          )
          assert(response.syncState == KnownGeneration(Generation.EMPTY))
          assert(response.redirect.addressOpt.nonEmpty, "Redirect address should be non-empty")
          assert(
            response.redirect.addressOpt.get == preferredAssigner.getAssignerInfoBlocking().uri
          )
        }
      }
    }

    // Verify 4: the preferred assigner keeps being the preferred assigner, and there are no
    // heartbeats sent to the standby assigners.
    var numReceivedHeartbeats: Long = preferredAssigner.getNumberOfHeartbeatsReceived
    for (index: Int <- 1 to driverConfig.heartbeatFailureThreshold * 2) {
      val expectedHeartbeatOpId: Long = index + 1L
      // Advance the clock to trigger the next heartbeat.
      clock.advanceBy(driverConfig.heartbeatInterval)
      // Wait for the next heartbeat to be received. We wait here to ensure that there aren't going
      // to be too many heartbeats sent simultaneously, causing the connection to be refused.
      AssertionWaiter("Heartbeat completes").await {
        assert(preferredAssigner.getNumberOfHeartbeatsReceived == numReceivedHeartbeats + 2)
        for (standby: TestAssigner <- standbys) {
          assert(
            TestUtils.awaitResult(standby.getHighestSucceededHeartbeatOpID, Duration.Inf) ==
            expectedHeartbeatOpId
          )
        }
      }
      numReceivedHeartbeats = preferredAssigner.getNumberOfHeartbeatsReceived
    }
    // Ensure that the standby assigners have not received any heartbeats.
    for (standbyAssigner: TestAssigner <- standbys) {
      assert(standbyAssigner.getNumberOfHeartbeatsReceived == 0)
    }
  }
}
