package com.databricks.dicer.client

import java.net.URI
import java.time.Instant
import java.util.{Random, UUID}

import com.databricks.conf.Config
import com.databricks.conf.Configs
import com.databricks.conf.trusted.ProjectConfByName
import com.databricks.conf.trusted.RPCPortConf
import com.databricks.rpc.tls.TLSOptions

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

import com.databricks.rpc.RequestHeaders
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{TestName, shamefullyAwait200msForNonEventInAsyncTest}
import com.databricks.caching.util.{
  AssertionWaiter,
  ExecutorUtil,
  FakeSequentialExecutionContextPool,
  FakeTypedClock,
  MetricUtils
}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  AssignerServiceInfo,
  Assignment,
  AssignmentMetricsSource,
  ClientRequest,
  ClientType,
  Generation,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment
}
import com.databricks.dicer.external.{Clerk, ClerkConf, ResourceAddress, SliceKey, Slicelet, Target}
import com.databricks.dicer.friend.SliceMap
import com.databricks.testing.DatabricksTest

class ClerkImplSuite extends DatabricksTest with TestName {

  private val fakeClock = new FakeTypedClock()

  /** A Dicer test environment where the assigner uses `fakeClock`. */
  private val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create(
    secPool =
      FakeSequentialExecutionContextPool.create("clerk-impl-suite", numThreads = 8, fakeClock)
  )

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /** Creates a clerk that connects to the given Slicelet for assignments. */
  private def createClerk(slicelet: Slicelet): Clerk[ResourceAddress] = {
    testEnv.createClerk(slicelet)
  }

  /** Creates a clerk that directly connects to the Assigner for assignments. */
  private def createDirectClerk(target: Target): Clerk[ResourceAddress] = {
    testEnv.createDirectClerk(target, initialAssignerIndex = 0)
  }

  /**
   * Verifies that the assigner stops receiving watch requests once the clerk is stopped. After
   * advancing the fake clock far into the future, this function checks the latest watch request
   * recorded in the test assigner to determine if a new request was received.
   */
  private def verifyEventuallyNoWatchRequestsReceivedAfterStop(target: Target): Unit = {
    val latestReceivedWatchRequestOpt: Option[(RequestHeaders, ClientRequest)] =
      testEnv.testAssigner.getLatestClerkWatchRequest(target)
    // Advance the clock by 1 day, so the assigner will respond to any hanging watch requests and
    // the clerk can send the next request if it is still active.
    fakeClock.advanceBy(1.day)
    // Sleep for some time to allow the assigner time to respond and the clerk to send the next
    // watch request if it remains active. Note that sleeping is highly discouraged in tests, but
    // here we are checking for a *non-event*, and in such cases we have no other option than to
    // wait a little time to leave room for the undesired event to occur.
    shamefullyAwait200msForNonEventInAsyncTest()
    assert(
      testEnv.testAssigner.getLatestClerkWatchRequest(target) == latestReceivedWatchRequestOpt
    )
  }

  /**
   * Returns the labels that a Clerk records on its assignment metrics for `target`, taking the
   * assigner labels from `assignerServiceInfoOpt` and falling back to the unknown-assigner
   * sentinels when the assignment did not carry any service info.
   */
  private def assignmentMetricLabels(
      target: Target,
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Map[String, String] = {
    Map(
      "targetCluster" -> target.getTargetClusterLabel,
      "targetName" -> target.getTargetNameLabel,
      "source" -> AssignmentMetricsSource.Clerk.toString,
      "assignerName" -> assignerServiceInfoOpt
        .map(_.name)
        .getOrElse(ClientMetrics.UNKNOWN_ASSIGNER_NAME),
      "assignerInstanceId" -> assignerServiceInfoOpt
        .map(_.instanceId)
        .getOrElse(ClientMetrics.UNKNOWN_ASSIGNER_INSTANCE_ID)
    )
  }

  /**
   * Returns the latest generation number sample recorded for the `target` by the Assigner
   * identified by `assignerServiceInfoOpt`, or `None` if there is no such sample (i.e. the labels
   * were never set or have been removed).
   */
  private def getLatestGenerationNumberOpt(
      target: Target,
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Option[Double] = {
    MetricUtils.getMetricValueOpt(
      CollectorRegistry.defaultRegistry,
      "dicer_assignment_latest_generation_number",
      assignmentMetricLabels(target, assignerServiceInfoOpt)
    )
  }

  /**
   * Returns the latest store incarnation sample recorded for the `target` by the Assigner
   * identified by `assignerServiceInfoOpt`, or `None` if there is no such sample (i.e. the labels
   * were never set or have been removed).
   */
  private def getLatestStoreIncarnationOpt(
      target: Target,
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Option[Double] = {
    MetricUtils.getMetricValueOpt(
      CollectorRegistry.defaultRegistry,
      "dicer_assignment_latest_store_incarnation",
      assignmentMetricLabels(target, assignerServiceInfoOpt)
    )
  }

  /** Returns the number of active `SliceLookup`s for the `target` with `ClientType.Clerk`. */
  private def getNumActiveSliceLookups(target: Target): Long = {
    MetricUtils
      .getMetricValue(
        CollectorRegistry.defaultRegistry,
        metric = "dicer_client_num_active_slice_lookups",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "clientType" -> ClientType.Clerk.toString
        )
      )
      .toLong
  }

  test("Methods called after stop don't throw") {
    // Test plan: Verify that all methods called after `ClerkImpl.stop` don't throw for both a
    // regular Clerk and a direct Clerk.
    val target = Target(getSafeName)
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val regularClerk: Clerk[ResourceAddress] = createClerk(slicelet)
    val directClerk: Clerk[ResourceAddress] = createDirectClerk(target)

    // Setup: Stop both `ClerkImpl`s.
    regularClerk.impl.stop()
    directClerk.impl.stop()

    // Verify: All methods invoked after stopping don't throw.
    regularClerk.impl.ready
    regularClerk.impl.getStubForKey(SliceKey.MIN)
    regularClerk.impl.stop()
    directClerk.impl.ready
    directClerk.impl.getStubForKey(SliceKey.MIN)
    directClerk.impl.stop()

    // Cleanup: Stop the Slicelet.
    slicelet.forTest.stop()
  }

  test("ClerkImpl.stop") {
    // Test plan: Verify that `ClerkImpl.stop` removes the clerk info from `ClientSlicez` and that
    // the `ClerkImpl` neither sends watch requests nor incorporates new assignments after being
    // stopped.
    val target = Target(getSafeName)

    // Setup: Set and freeze an initial assignment to the assigner.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val initialAssignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)

    val initialNumActiveSliceLookup: Long = getNumActiveSliceLookups(target)
    // Setup: Create a Clerk that connects directly to the assigner to send watch requests, because
    // the type of watch server backing a particular address is unlikely to affect the clerk's
    // stopping behavior, and the assigner is easier to control and verify than a Slicelet. (Note:
    // currently, creating a Slicelet with `fakeClock` in `testEnv` is not supported. Also, Slicelet
    // does not have an equivalent method to `getLatestClerkWatchRequest` for verification).
    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)
    // Setup: Wait for the clerk to start and receive the first response.
    AssertionWaiter("Wait for the clerk to start and receive the first response").await {
      assert(getNumActiveSliceLookups(target) == (initialNumActiveSliceLookup + 1))
      assert(clerk.impl.forTest.getLatestAssignmentOpt.contains(initialAssignment))
    }
    val numClientSlicezDataForTargetBeforeStop: Int =
      TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf).count {
        clientSlicezData: ClientTargetSlicezData =>
          clientSlicezData.target == target
      }

    // Verify: While the clerk is running, the generation-number and store-incarnation gauges are
    // populated for it.
    assert(
      getLatestGenerationNumberOpt(target, assignerServiceInfoOpt = None).exists((_: Double) > 0.0)
    )
    assert(getLatestStoreIncarnationOpt(target, assignerServiceInfoOpt = None).isDefined)

    // Setup: Stop the clerk. Also wait for the SliceLookup to be cancelled.
    clerk.impl.stop()
    AssertionWaiter("Wait for the lookup metric to be decremented").await {
      assert(getNumActiveSliceLookups(target) == initialNumActiveSliceLookup)
    }

    // Verify: ClientSlicez should unregister the clerk after stopping the clerk.
    AssertionWaiter("Wait for ClientSlicez to unregister the clerk").await {
      val numClientSlicezDataForTargetAfterStop: Int =
        TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf).count {
          clientSlicezData: ClientTargetSlicezData =>
            clientSlicezData.target == target
        }
      assert(numClientSlicezDataForTargetAfterStop == (numClientSlicezDataForTargetBeforeStop - 1))
    }

    // Verify: After stop, the per-target gauge labels are removed so the stopped clerk does not
    // leave a stale sample in the scrape (which would cause `DicerClientNotReceivingAssignments`
    // alerts to fire indefinitely against a clerk that is no longer running).
    AssertionWaiter("Wait for the gauge labels to be removed").await {
      assert(getLatestGenerationNumberOpt(target, assignerServiceInfoOpt = None).isEmpty)
      assert(getLatestStoreIncarnationOpt(target, assignerServiceInfoOpt = None).isEmpty)
    }

    // Setup: Record the clerk's current assignment.
    val initialAssignmentOpt: Option[Assignment] = clerk.impl.forTest.getLatestAssignmentOpt

    // Setup: Set and freeze a new assignment after the clerk is stopped.
    val newAssignment: Assignment =
      TestUtils.awaitResult(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
    assert(newAssignment != initialAssignment)

    // Verify: Since there may be a delay between when the clerk sends a request and when the
    // assigner receives it, a hanging request sent before `ClerkImpl.stop` might not have been
    // received. `AssertionWaiter` is used to prevent flakiness in these cases. (Note: if the clerk
    // isn't stopped, even if this test passes by chance, it will eventually fail after multiple
    // runs).
    AssertionWaiter("Wait for the clerk to be fully stopped").await {
      verifyEventuallyNoWatchRequestsReceivedAfterStop(target)
    }

    // Verify: Even if the assigner has a new assignment, the clerk's latest assignment should stay
    // unchanged and the gauge labels should remain removed (i.e. a stopped clerk does not
    // resurrect its metric labels in response to assigner-side activity).
    assert(clerk.impl.forTest.getLatestAssignmentOpt == initialAssignmentOpt)
    assert(getLatestGenerationNumberOpt(target, assignerServiceInfoOpt = None).isEmpty)
    assert(getLatestStoreIncarnationOpt(target, assignerServiceInfoOpt = None).isEmpty)
  }

  test("Multi-thread ClerkImpl.stop") {
    // Test plan: Verify that ClerkImpl.stop is thread-safe when invoked concurrently with other
    // clerk methods. Verify it as follows:
    // - Create a clerk and start concurrent operations from multiple threads, where one performs
    //   ClerkImpl.stop() and the remaining operations randomly invoke stop(), ready(), or
    //   getStubForKey().
    // - After all concurrent operations complete, set a new assignment and verify the clerk is
    //   fully stopped by confirming it neither sends new watch requests to the assigner nor
    //   incorporates the new assignment.

    val target = Target(getSafeName)

    // Setup: Create a Clerk that connects directly to the assigner to send watch requests, because
    // the type of watch server backing a particular address is unlikely to affect the clerk's
    // stopping behavior, and the assigner is easier to control and verify than a Slicelet. (Note:
    // currently, creating a Slicelet with `fakeClock` in `testEnv` is not supported. Also, Slicelet
    // does not have an equivalent method to `getLatestClerkWatchRequest` for verification).
    val clerk: Clerk[ResourceAddress] = createDirectClerk(target)

    val numOps: Int = 20
    val ec: ExecutionContext = ExecutorUtil.createContextPropagatingExecutionContext(getSafeName, 4)
    val random = new Random()
    // Setup: Randomly select one operation that will serve as the stop operation.
    val stopOpIndex: Int = random.nextInt(numOps)

    val futs: Seq[Future[Unit]] =
      for (i: Int <- 0 until numOps) yield {
        Future {
          if (i == stopOpIndex) {
            // If it is the chosen stop operation index, call `ClerkImpl.stop`.
            clerk.impl.stop()
          } else {
            // Otherwise, randomly invoke a method on the clerk.
            random.nextInt(3) match {
              case 0 => clerk.impl.stop()
              case 1 => clerk.impl.ready
              case 2 => clerk.impl.getStubForKey(SliceKey.MIN)
            }
          }
          ()
        }(ec)
      }
    // Setup: Wait for all tasks to finish.
    val fut: Future[Seq[Unit]] = Future.sequence(futs)(implicitly, ec)
    Await.result(fut, Duration.Inf)

    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()

    // Verify: The clerk should stop sending new watch requests and incorporating new assignments
    // after being stopped.
    AssertionWaiter("Wait for the clerk to be fully stopped").await {
      // Setup: Set and freeze a new assignment after the clerk is stopped.
      val newAssignment: Assignment =
        Await.result(testEnv.setAndFreezeAssignment(target, proposal), Duration.Inf)
      verifyEventuallyNoWatchRequestsReceivedAfterStop(target)
      assert(!clerk.impl.forTest.getLatestAssignmentOpt.contains(newAssignment))
    }
  }

  test("Clerk removes gauges for a target after assigner service info change") {
    // Test plan: Verify that the Clerk removes gauges for a target after the assigner service info
    // changes. Verify this by recording metrics for one target under an initial assigner service
    // info, injecting a newer assignment stamped with a different assigner service info, and
    // checking that the stale metrics are removed.
    val target = Target(getSafeName)
    val initialServiceInfo =
      AssignerServiceInfo(name = "test-assigner-1", instanceId = "instance-1")
    val changedServiceInfo =
      AssignerServiceInfo(name = "test-assigner-2", instanceId = "instance-2")

    // Setup: Create a test environment with the initial assigner service info. Then create a
    // Clerk that receives its first assignment from the test environment.
    val env: InternalDicerTestEnvironment =
      InternalDicerTestEnvironment.create(assignerServiceInfoOpt = Some(initialServiceInfo))
    val initialAssignment: Assignment =
      TestUtils.awaitResult(env.setAndFreezeAssignment(target, sampleProposal()), Duration.Inf)
    val clerk: Clerk[ResourceAddress] =
      env.createDirectClerk(target, initialAssignerIndex = 0)

    // Verify: After the clerk receives its first assignment, it records assignment metrics with the
    // initial assigner service info.
    AssertionWaiter("Wait for metrics to be recorded").await {
      assert(getLatestGenerationNumberOpt(target, Some(initialServiceInfo)).isDefined)
      assert(getLatestStoreIncarnationOpt(target, Some(initialServiceInfo)).isDefined)
    }

    // Setup: Simulate a restarted assigner with a different service info by injecting a newer
    // assignment with a different service info.
    val assignmentWithChangedServiceInfo: Assignment = initialAssignment.copy(
      generation = Generation.createForCurrentTime(
        incarnation = initialAssignment.generation.incarnation,
        now = Instant.now(),
        lowerBoundExclusive = initialAssignment.generation
      ),
      assignerServiceInfoOpt = Some(changedServiceInfo)
    )
    clerk.impl.forTest.injectAssignment(assignmentWithChangedServiceInfo)

    // Verify: After the clerk receives the updated assignment, it records assignment metrics and
    // clears the stale metrics. The difference in assigner service infos should trigger the Clerk
    // to clear the gauge with the initial assigner service info.
    AssertionWaiter("Wait for new metrics to be recorded and stale metrics to be removed").await {
      assert(getLatestGenerationNumberOpt(target, Some(changedServiceInfo)).isDefined)
      assert(getLatestStoreIncarnationOpt(target, Some(changedServiceInfo)).isDefined)
      assert(getLatestGenerationNumberOpt(target, Some(initialServiceInfo)).isEmpty)
      assert(getLatestStoreIncarnationOpt(target, Some(initialServiceInfo)).isEmpty)
    }

    // Cleanup: Stop the Clerk and the test environment created by this test.
    clerk.impl.stop()
    env.stop()
  }

  import ClientMetrics.ClientUuidStatus

  /** Type alias for a function that creates a ClerkImpl given a target and UUID status to test. */
  private type ClerkImplFactory = (Target, ClientUuidStatus) => ClerkImpl[ResourceAddress]

  /** A dummy watch address to use in tests. */
  private val DUMMY_WATCH_ADDRESS: URI = URI.create("https://localhost:12345")

  /** Creates a [[ClerkConf]] for metric testing with the specified client UUID status. */
  private def createClientUuidTestClerkConf(status: ClientUuidStatus): ClerkConf = {
    val baseEntries: Seq[(String, Any)] = Seq(
      "databricks.dicer.slicelet.rpc.port" -> 0,
      "databricks.dicer.assigner.rpc.port" -> 0
    )
    val entries: Seq[(String, Any)] = status match {
      case ClientUuidStatus.Valid =>
        baseEntries :+ (
          "databricks.dicer.internal.cachingteamonly.clientUuid" -> UUID.randomUUID().toString
        )
      case ClientUuidStatus.Malformed =>
        baseEntries :+ (
          "databricks.dicer.internal.cachingteamonly.clientUuid" -> "not-a-valid-uuid"
        )
      case ClientUuidStatus.Missing =>
        baseEntries
    }
    val config: Config = Configs.parseMap(entries: _*)
    new ProjectConfByName("test", config) with ClerkConf with RPCPortConf {
      override protected def dicerTlsOptions: Option[TLSOptions] = None
      override def envVars: Map[String, String] = Map.empty
    }
  }

  /** Creates a Clerk via [[ClerkImpl.create]] (standard Clerk path). */
  private def createViaStandard(
      target: Target,
      status: ClientUuidStatus): ClerkImpl[ResourceAddress] = {
    val clerkConf: ClerkConf = createClientUuidTestClerkConf(status)
    ClerkImpl
      .create(clerkConf, target, DUMMY_WATCH_ADDRESS, identity[ResourceAddress], sourceIpOpt = None)
  }

  /** Creates a Clerk via [[ClerkImpl.createForDataPlaneDirectClerk]] (direct-to-assigner path). */
  private def createViaDataPlaneDirect(
      target: Target,
      status: ClientUuidStatus): ClerkImpl[ResourceAddress] = {
    val clerkConf: ClerkConf = createClientUuidTestClerkConf(status)
    ClerkImpl.createForDataPlaneDirectClerk(
      secPoolOpt = None,
      clerkConf,
      target,
      DUMMY_WATCH_ADDRESS,
      identity[ResourceAddress]
    )
  }

  /** Creates a Clerk via [[ClerkImpl.createForShardedStub]] (sharded stub path). */
  private def createViaShardedStub(
      target: Target,
      status: ClientUuidStatus): ClerkImpl[ResourceAddress] = {
    val clientUuidOpt: Option[String] = status match {
      case ClientUuidStatus.Valid => Some(UUID.randomUUID().toString)
      case ClientUuidStatus.Malformed => Some("not-a-valid-uuid")
      case ClientUuidStatus.Missing => None
    }
    ClerkImpl.createForShardedStub(
      target,
      watchAddress = DUMMY_WATCH_ADDRESS,
      protoLoggerConf = TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
      tlsOptions = None,
      clientUuidOpt = clientUuidOpt
    )
  }

  /** Creates a Clerk via [[ClerkImpl.createForMultiClerk]] (multi-clerk path). */
  private def createViaMultiClerk(
      target: Target,
      status: ClientUuidStatus): ClerkImpl[ResourceAddress] = {
    val clerkConf: ClerkConf = createClientUuidTestClerkConf(status)
    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      clientType = ClientType.Clerk,
      conf = clerkConf,
      ownerName = s"multi-clerk-test-${target.name}"
    )
    ClerkImpl.createForMultiClerk(
      secPoolOpt = None,
      protoLogger = protoLogger,
      clerkConf,
      target,
      DUMMY_WATCH_ADDRESS,
      identity[ResourceAddress]
    )
  }

  // TODO(<internal bug>): Once all Dicer client deployments are confirmed to set POD_UID, this test can be
  // retired. Absence of client UUID should trigger an exception at creation time.
  namedGridTest("Client UUID status metric tracks status correctly")(
    Map[String, ClerkImplFactory](
      "standard" -> createViaStandard,
      "dataPlaneDirectClerk" -> createViaDataPlaneDirect,
      "shardedStub" -> createViaShardedStub
    )
  ) { factory: ClerkImplFactory =>
    // Test plan: Verify that the dicer_client_uuid_status_total metric correctly tracks client
    // UUID resolution status at Clerk creation time. For each Clerk creation path:
    // 1. Create a Clerk with a valid UUID and verify clientUuidStatus=valid increments.
    // 2. Create a Clerk without UUID and verify clientUuidStatus=missing increments.
    // 3. Create a Clerk with a malformed UUID and verify clientUuidStatus=malformed increments.
    val target: Target = Target.createKubernetesTarget(
      URI.create("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"),
      getSafeName
    )

    def createUuidStatusTracker(status: ClientUuidStatus): ChangeTracker[Double] = {
      ChangeTracker { () =>
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "dicer_client_uuid_status_total",
          Map(
            "targetName" -> target.getTargetNameLabel,
            "clientType" -> ClientType.Clerk.toString,
            "clientUuidStatus" -> status.toString
          )
        )
      }
    }

    // Verify: Creating a Clerk with a valid UUID is tracked.
    val validTracker: ChangeTracker[Double] = createUuidStatusTracker(ClientUuidStatus.Valid)
    val clerkWithUuid: ClerkImpl[ResourceAddress] = factory(target, ClientUuidStatus.Valid)
    assert(validTracker.totalChange() == 1.0, "valid metric should increment")
    clerkWithUuid.stop()

    // Verify: Creating a Clerk without UUID is tracked.
    val missingTracker: ChangeTracker[Double] = createUuidStatusTracker(ClientUuidStatus.Missing)
    val clerkWithoutUuid: ClerkImpl[ResourceAddress] = factory(target, ClientUuidStatus.Missing)
    assert(missingTracker.totalChange() == 1.0, "missing metric should increment")
    clerkWithoutUuid.stop()

    // Verify: Creating a Clerk with malformed UUID is tracked (fail-open).
    val malformedTracker: ChangeTracker[Double] =
      createUuidStatusTracker(ClientUuidStatus.Malformed)
    val clerkMalformed: ClerkImpl[ResourceAddress] = factory(target, ClientUuidStatus.Malformed)
    assert(malformedTracker.totalChange() == 1.0, "malformed metric should increment")
    clerkMalformed.stop()
  }

  namedGridTest("ClerkImpl factory tags Clerks with the right factoryContext")(
    // Each entry: name -> (expected factoryContext label, factory function).
    // The "clerk" factory context (the public ClerkImpl.create path) is covered by
    // ClerkSuiteBase; this test covers the remaining entry points.
    Map[String, (String, ClerkImplFactory)](
      "createForDataPlaneDirectClerk" -> (("dataPlaneDirectClerk", createViaDataPlaneDirect)),
      "createForMultiClerk" -> (("multiClerk", createViaMultiClerk)),
      "createForShardedStub" -> (("shardedStub", createViaShardedStub))
    )
  ) {
    case (expectedFactoryContext: String, factory: ClerkImplFactory) =>
      // Test plan: Verify that each non-default ClerkImpl factory method tags the created Clerk
      // with the correct factoryContext label on dicer_clerk_created_total. Open a ChangeTracker
      // on the creation counter for the (target, expectedFactoryContext), create a Clerk via
      // the factory, then assert the tracker observed exactly one increment.
      val target: Target = Target.createKubernetesTarget(
        URI.create("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"),
        getSafeName
      )

      val creationCountTracker: ChangeTracker[Double] = ChangeTracker { () =>
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "dicer_clerk_created_total",
          Map(
            "targetCluster" -> target.getTargetClusterLabel,
            "targetName" -> target.getTargetNameLabel,
            "targetInstanceId" -> target.getTargetInstanceIdLabel,
            "factoryContext" -> expectedFactoryContext
          )
        )
      }

      // Setup: Create the Clerk via the factory.
      val clerk: ClerkImpl[ResourceAddress] = factory(target, ClientUuidStatus.Valid)

      // Verify: Exactly one Clerk creation was recorded under the expected factoryContext.
      assert(
        creationCountTracker.totalChange() == 1.0,
        s"Expected creation count for factoryContext=$expectedFactoryContext to increase by 1, " +
        s"but totalChange was ${creationCountTracker.totalChange()}"
      )

      clerk.stop()
  }
}
