package com.databricks.dicer.assigner

import java.net.URI

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import io.grpc.Deadline

import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc.AssignmentServiceStub
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContextPool,
  FakeTypedClock,
  MetricUtils
}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.dicer.common.{
  ClerkData,
  ClientRequest,
  Generation,
  InternalDicerTestEnvironment,
  SliceletData,
  SyncAssignmentState,
  TestAssigner
}
import com.databricks.dicer.assigner.config.InternalTargetConfigMetrics
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.external.{Slicelet, Target}
import com.databricks.dicer.assigner.TargetMetrics.GeneratorShutdownReason
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.client.WatchStubHelper
import com.databricks.dicer.common.TestSliceUtils.createTestSquid
import com.databricks.rpc.testing.TestSslArguments
import com.databricks.rpc.tls.TLSOptionsMigration
import com.databricks.testing.DatabricksTest

import io.prometheus.client.CollectorRegistry

/**
 * Test suite for Assignment Generator garbage collection and lifecycle management.
 *
 * This suite tests the behavior of assignment generators with respect to inactivity-based
 * garbage collection. It verifies that generators are properly cleaned up when they become
 * inactive (no active watch requests) for longer than the configured inactivity deadline.
 *
 * The tests use a FakeTypedClock to control time advancement.
 */
class AssignerGeneratorGCSuite extends DatabricksTest with TestName {

  // Set the inactivity deadline and scan interval to arbitrary values for ease of use in tests.
  private val GC_DEADLINE: FiniteDuration = 120.seconds
  private val INACTIVITY_SCAN_INTERVAL: FiniteDuration = 10.seconds

  /** A fake clock to control time advancement. */
  private val fakeClock = new FakeTypedClock()

  /** Test assigner configuration with short inactivity deadlines for testing. */
  private val testAssignerConf: TestAssigner.Config = TestAssigner.Config.create(
    assignerConf = new DicerAssignerConf(
      Configs.parseMap(
        // Short garbage collection period for testing (120 seconds).
        "databricks.dicer.assigner.generatorInactivityDeadlineSeconds" -> GC_DEADLINE.toSeconds,
        "databricks.dicer.assigner.generatorInactivityScanIntervalSeconds"
        -> INACTIVITY_SCAN_INTERVAL.toSeconds
      )
    )
  )

  /** A Dicer test environment where the assigner uses `fakeClock`. */
  private val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create(
    config = testAssignerConf,
    secPool =
      FakeSequentialExecutionContextPool.create("assigner-generator-gc", numThreads = 8, fakeClock)
  )

  /**
   * Timeout for the Watch RPC from a client perspective. Set to a larger value than used in
   * production to avoid test flakes, since the tests do not retry watch requests.
   */
  private val WATCH_RPC_TIMEOUT: FiniteDuration = 1.minute

  /** A helper class for creating Watch stubs. */
  private val watchStubHelper = new WatchStubHelper(
    clientName = Project.DicerAssigner.name,
    subscriberDebugName = "assigner-generator-clustertype2-test",
    defaultWatchAddress = URI.create(s"http://localhost:${testEnv.getAssignerPort}"),
    tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
    watchFromDataPlane = false
  )

  /** RPC stub for sending Watch requests to the Assigner.  */
  private var stub: AssignmentServiceStub = _

  override def beforeAll(): Unit = {
    // Create a stub that can be used to communicate with the Assigner to get the assignment.
    stub = watchStubHelper.createWatchStub(redirectAddressOpt = None)
  }

  /** Issue a watch request as if sent by a Clerk. */
  private def performClerkWatch(
      stub: AssignmentServiceStub,
      target: Target
  ): Future[ClientResponseP] = {
    val request: ClientRequest = ClientRequest(
      target = target,
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      subscriberDebugName = "subscriber1",
      timeout = 5.seconds,
      subscriberData = ClerkData,
      supportsSerializedAssignment = true
    )
    issueWatchCall(stub, request)
  }

  /** Issue a watch request as if sent by a Slicelet. */
  private def performSliceletWatch(
      stub: AssignmentServiceStub,
      target: Target
  ): Future[ClientResponseP] = {
    val request: ClientRequest = ClientRequest(
      target = target,
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      subscriberDebugName = "subscriber1",
      timeout = 5.seconds,
      subscriberData = SliceletData(
        createTestSquid("pod0"),
        ClientRequestP.SliceletDataP.State.RUNNING,
        "localhostNamespace",
        attributedLoads = Vector.empty,
        unattributedLoadOpt = None
      ),
      supportsSerializedAssignment = true
    )
    issueWatchCall(stub, request)
  }

  /** Helper method to issue a watch call. */
  private def issueWatchCall(
      stub: AssignmentServiceStub,
      request: ClientRequest): Future[ClientResponseP] = {
    stub
      .withDeadline(Deadline.after(WATCH_RPC_TIMEOUT.toNanos, NANOSECONDS))
      .watch(request.toProto)
  }

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  override def beforeEach(): Unit = {
    InternalTargetConfigMetrics.forTest.clearMetrics()
  }

  gridTest("Generator is considered inactive and remmoved after inactivity deadline")(
    Seq(
      performClerkWatch(_, _),
      performSliceletWatch(_, _)
    )
  ) { (watchFunc: (AssignmentServiceStub, Target) => Future[ClientResponseP]) =>
    // Test plan: Verify that a generator without any active watch requests for longer than the
    // inactivity deadline is considered inactive and removed. Verify this works for both Clerk
    // and Slicelet watch requests.

    val target = Target(getSafeName)

    // Issue a watch request to create a generator.
    watchFunc(stub, target)
    AssertionWaiter("Waiting for generator to be created").await {
      val numActiveGenerators = TargetMetricsUtils.getNumActiveGenerators(target)
      assert(
        numActiveGenerators == 1,
        s"numActiveGenerators for $target should be 1, was $numActiveGenerators"
      )
    }

    val shortInterval: FiniteDuration = 10.seconds
    // Advance time just before the inactivity deadline and verify the generator is still alive.
    fakeClock.advanceBy(GC_DEADLINE - shortInterval)
    AssertionWaiter("Check that prior to inactivity deadline, generator is alive").await {
      val numActiveGenerators = TargetMetricsUtils.getNumActiveGenerators(target)
      assert(
        numActiveGenerators == 1,
        s"numActiveGenerators for $target should be 1, was $numActiveGenerators"
      )
    }

    // Advance time at or past the inactivity deadline and verify that the generator has been
    // garbage collected.
    fakeClock.advanceBy(shortInterval)
    AssertionWaiter("Waiting for generator to be gc'ed").await {
      // Advance time to ensure the inactivity scan has run.
      fakeClock.advanceBy(INACTIVITY_SCAN_INTERVAL)
      assert(
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(target, GeneratorShutdownReason.GENERATOR_INACTIVITY) == 1,
        "Should have removed 1 generator due to inactivity"
      )
      assert(
        TargetMetricsUtils.getGeneratorsRemovedTotalAllReasons(target) == 1,
        "Should have removed 1 generator due to all reasons"
      )
      assert(
        TargetMetricsUtils.getTargetsWithActiveGenerators(target) == 0,
        "Should have 0 active targets"
      )
    }
  }

  gridTest("Generator is kept active by watch requests before the inactivity deadline")(
    Seq(
      performClerkWatch(_, _),
      performSliceletWatch(_, _)
    )
  ) { (watchFunc: (AssignmentServiceStub, Target) => Future[ClientResponseP]) =>
    // Test plan: Verify that a generator is kept alive if it continuously receives watch requests.

    // Issue an initial watch requests from a Slicelet to ensure an assignment is created.
    val target = Target(getSafeName)

    // We do not care about the response here, just that the generator is created and an assignment
    // is produced. This will allow us to Await future watch calls for either Clerks or Slicelets.
    Await.result(performSliceletWatch(stub, target), Duration.Inf)

    // Verify that the call creates a new generator.
    AssertionWaiter("Waiting for generator to be created").await {
      val numActiveGeneratorsBeforeCleanup = TargetMetricsUtils.getNumActiveGenerators(target)
      assert(
        numActiveGeneratorsBeforeCleanup == 1,
        s"numActiveGenerators for t$target should be 1, was $numActiveGeneratorsBeforeCleanup"
      )
    }

    // Repeat several times: send watch request, advance partway (GC_DEADLINE - 10s)
    // and verify the generator is still active. Track the total time advanced and verify that the
    // total time passed is greater than a single GC period.
    val advanceBy = GC_DEADLINE - 10.seconds
    var totalAdvancedTime = 0.seconds
    for (_ <- 1 to 10) {
      // Setup: Advance partway through the inactivity deadline (but not all the way).
      fakeClock.advanceBy(advanceBy)
      totalAdvancedTime += advanceBy
      Await.result(watchFunc(stub, target), Duration.Inf)

      // Verify that the generator is still active and hasn't been cleaned up at any point.
      AssertionWaiter("Waiting for generator to be created").await {
        val numActiveGenerators = TargetMetricsUtils.getNumActiveGenerators(target)
        assert(
          numActiveGenerators == 1,
          s"numActiveGenerators for t$target should be 1, was $numActiveGenerators"
        )
        assert(
          TargetMetricsUtils.getGeneratorsRemovedTotalAllReasons(target) == 0,
          "Should have removed 1 generator due to all reasons"
        )
      }
    }

    // Verify that the total time advanced is greater than a single GC period, despite no GC.
    assert(
      totalAdvancedTime > GC_DEADLINE,
      s"$totalAdvancedTime should be greater than GC period $GC_DEADLINE"
    )

    // Verify that not issuing any more watch requests causes the generator to be removed after
    // the inactivity deadline.
    fakeClock.advanceBy(GC_DEADLINE)
    AssertionWaiter("Waiting for generator to be gc'ed").await {
      fakeClock.advanceBy(INACTIVITY_SCAN_INTERVAL)
      assert(
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(target, GeneratorShutdownReason.GENERATOR_INACTIVITY) == 1,
        "Should have removed 1 generator due to inactivity"
      )
      assert(
        TargetMetricsUtils.getGeneratorsRemovedTotalAllReasons(target) == 1,
        "Should have removed 1 generator due to all reasons"
      )
      assert(
        TargetMetricsUtils.getTargetsWithActiveGenerators(target) == 0,
        "Should have 0 active targets"
      )
    }
  }

  test("E2E test of generator shut down on inactivity") {
    // Test plan: Verify in an e2e test that a slicelet on startup will create a generator on the
    // Assigner it connects to and once the slicelet is away, the generator will be eventually
    // marked inactive and shut down.

    val target = Target(getSafeName)

    // Setup: Create a slicelet to trigger generator creation.
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    // Setup: Advance time to allow generator creation and initial assignment.
    fakeClock.advanceBy(5.seconds)

    val testAssigner = testEnv.testAssigner

    val inactiveGeneratorsRemovedTotalTracker: ChangeTracker[Long] =
      ChangeTracker[Long](
        () =>
          TargetMetricsUtils
            .getGeneratorsRemovedTotal(target, GeneratorShutdownReason.GENERATOR_INACTIVITY)
      )
    val generatorsRemovedTotalAllReasonsTracker: ChangeTracker[Long] =
      ChangeTracker[Long](() => TargetMetricsUtils.getGeneratorsRemovedTotalAllReasons(target))
    val targetsWithActiveGeneratorsTracker: ChangeTracker[Long] =
      ChangeTracker[Long](() => TargetMetricsUtils.getTargetsWithActiveGenerators(target))

    // Verify: assignment was generated (proving that generator is active).
    AssertionWaiter("Wait for initial assignment").await {
      val assignmentOpt = Await.result(testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined, "Assignment should be generated")
      assert(
        assignmentOpt.get.generation > Generation.EMPTY,
        "Assignment should have valid generation"
      )
    }

    // Verify: generator was created and is in the map.
    val generatorOpt1: Option[AssignmentGeneratorDriver] = Await.result(
      testAssigner.forTest.getGeneratorFromMap(target),
      Duration.Inf
    )
    assert(generatorOpt1.isDefined, "Generator should be created")
    val generator1: AssignmentGeneratorDriver = generatorOpt1.get

    // Verify: Metrics were updated.
    assert(targetsWithActiveGeneratorsTracker.totalChange() == 1, "Should have 1 active target")

    // Setup: Stop the slicelet
    slicelet.impl.forTest.cancel()

    // Verify: generator still exists and is the same instance (GC period hasn't elapsed yet).
    val generatorOpt2: Option[AssignmentGeneratorDriver] = Await.result(
      testAssigner.forTest.getGeneratorFromMap(target),
      Duration.Inf
    )
    assert(
      generatorOpt2.isDefined && (generator1 == generatorOpt2.get),
      "Generator should still exist and be the same instance before GC period elapses"
    )

    // Verify: At this point, numActiveGenerators should be 1
    assert(TargetMetricsUtils.getNumActiveGenerators(target) == 1, "Should have 1 active target")

    // Verify: At this point, getGeneratorsRemovedTotal("GENERATOR_INACTIVITY") should be 0.
    assert(
      inactiveGeneratorsRemovedTotalTracker.totalChange() == 0,
      "No generators should be removed due to inactivity yet"
    )
    // Advance time to ensure we are truly past GC_DEADLINE.
    fakeClock.advanceBy(GC_DEADLINE)

    // Verify: Wait for garbage collection to complete.
    // We verify this by checking that the generator was removed from the map.
    AssertionWaiter("Wait for generator to be garbage collected").await {
      fakeClock.advanceBy(INACTIVITY_SCAN_INTERVAL)
      val generatorOpt3: Option[AssignmentGeneratorDriver] = Await.result(
        testAssigner.forTest.getGeneratorFromMap(target),
        Duration.Inf
      )
      assert(
        generatorOpt3.isEmpty,
        "Generator should be removed from map after GC period"
      )

      // Verify: Metrics were updated.
      assert(
        inactiveGeneratorsRemovedTotalTracker.totalChange() >= 1,
        "Should have removed at least 1 generator due to inactivity"
      )
      assert(
        generatorsRemovedTotalAllReasonsTracker.totalChange() >= 1,
        "Should have removed at least 1 generator due to all reasons"
      )
      assert(
        targetsWithActiveGeneratorsTracker.totalChange() == 0,
        "Should have 0 active targets"
      )
    }

    // Verify: the generator's metrics have been cleaned up from the CollectorRegistry.
    // Metrics cleanup happens asynchronously during shutdown, so we need to wait for it.
    AssertionWaiter("Wait for metrics to be cleaned up").await {
      fakeClock.advanceBy(INACTIVITY_SCAN_INTERVAL)

      // The numActiveGenerators metric is decremented (not removed), so it should be 0.
      assert(
        TargetMetricsUtils.getNumActiveGenerators(target) == 0,
        "Should have 0 active targets after GC"
      )

      // Verify: metrics that are removed (not just decremented) should return None.
      // Check latestKnownGenerationGauge which is explicitly removed in
      // TargetMatrics.clearTerminatedGeneratorFromMetrics.
      val latestKnownGenerationOpt = MetricUtils.getMetricValueOpt(
        CollectorRegistry.defaultRegistry,
        "dicer_assigner_latest_known_assignment_generation_gauge",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel
        )
      )
      assert(
        latestKnownGenerationOpt.isEmpty,
        s"latestKnownGenerationGauge for target $target should be removed " +
        s"from CollectorRegistry after GC"
      )
    }

    // Verify: a new generator can be created.
    // Setup: Create a new slicelet to trigger generator creation.
    testEnv.createSlicelet(target).start(selfPort = 5678, listenerOpt = None)

    // Setup: Advance time to allow generator creation.
    fakeClock.advanceBy(5.seconds)

    // Verify: new generator's assignment was generated (proving that new generator is created).
    AssertionWaiter("Wait for new generator's assignment").await {
      val assignmentOpt = Await.result(testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined, "Assignment should be generated")
      assert(
        assignmentOpt.get.generation > Generation.EMPTY,
        "Assignment should have valid generation"
      )
    }

    // Verify: a new generator was created.
    val generatorOpt4: Option[AssignmentGeneratorDriver] = Await.result(
      testAssigner.forTest.getGeneratorFromMap(target),
      Duration.Inf
    )
    assert(generatorOpt4.isDefined, "New generator should be created after GC")
    val generator4: AssignmentGeneratorDriver = generatorOpt4.get

    // Verify: the new generator is a different instance than the previous one.
    assert(
      generator1 ne generator4,
      "New generator should be a different instance"
    )

    // Verify: Metrics were updated.
    assert(
      generatorsRemovedTotalAllReasonsTracker.totalChange() >= 1,
      "Should have removed at least 1 generator due to all reasons"
    )
    assert(
      targetsWithActiveGeneratorsTracker.totalChange() == 1,
      "Should have 1 active targets"
    )
  }
}
