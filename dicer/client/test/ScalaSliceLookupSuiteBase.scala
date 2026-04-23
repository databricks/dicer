package com.databricks.dicer.client

import java.time.Instant
import java.util.UUID

import com.databricks.dicer.external.Target
import TestClientUtils.TEST_CLIENT_UUID

import scala.collection.immutable.SortedMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.{
  AssertionWaiter,
  Cancellable,
  FakeSequentialExecutionContext,
  FakeTypedClock,
  LoggingStreamCallback,
  MetricUtils,
  SequentialExecutionContext,
  StreamCallback,
  TestUtils
}
import com.databricks.dicer.common.{
  Assignment,
  ClientType,
  ClerkData,
  ProposedSliceAssignment,
  SliceletData,
  SliceletState,
  TestAssigner
}
import com.databricks.dicer.common.SliceletData.{KeyLoad, SliceLoad}
import com.databricks.dicer.common.TestAssigner.AssignerReplyType
import com.databricks.dicer.common.TestSliceUtils.{createTestSquid, sampleProposal}
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.{SliceMap, Squid}
import io.grpc.Status
import io.prometheus.client.CollectorRegistry

/**
 * Includes the common test cases from [[SliceLookupSuiteBase]] plus test cases that apply to the
 * Scala implementation but not the Rust one.
 */
abstract class ScalaSliceLookupSuiteBase(watchFromDataPlane: Boolean)
    extends SliceLookupSuiteBase(watchFromDataPlane) {

  /** Creates a test DicerClientProtoLogger using the given config. */
  private def createTestLogger(
      clientType: ClientType,
      protoLoggerConf: DicerClientProtoLoggerConf,
      ownerName: String): DicerClientProtoLogger = {
    DicerClientProtoLogger.create(
      clientType = clientType,
      conf = protoLoggerConf,
      ownerName = ownerName
    )
  }

  override protected def withLookup(
      testAssigner: TestAssigner,
      watchStubCacheTime: FiniteDuration = 20.seconds,
      protoLoggerConf: DicerClientProtoLoggerConf = TestClientUtils.createTestProtoLoggerConf(
        sampleFraction = 0.0
      ),
      testTarget: Target = target,
      clientIdOpt: Option[UUID] = Some(TEST_CLIENT_UUID))(
      func: (SliceLookupDriver, LoggingStreamCallback[Assignment]) => Unit): Unit = {
    fakeS2SProxy.setFallbackUpstreamPorts(Vector(testAssigner.localUri.getPort))
    val subscriberDebugName: String = "test-clerk"
    val config: InternalClientConfig =
      createInternalClientConfig(
        ClientType.Clerk,
        portToConnectTo(testAssigner),
        watchStubCacheTime,
        testTarget = testTarget,
        clientIdOpt = clientIdOpt
      )
    val lookup =
      SliceLookup.createUnstarted(
        sec,
        config.sliceLookupConfig,
        subscriberDebugName,
        createTestLogger(ClientType.Clerk, protoLoggerConf, subscriberDebugName),
        serviceBuilderOpt = None
      )
    val callback = new LoggingStreamCallback[Assignment](sec)
    val watchHandle: Cancellable =
      lookup.cellConsumer.watch(new StreamCallback[Assignment](sec) {
        override protected def onFailure(status: Status): Unit = callback.executeOnFailure(status)
        override protected def onSuccess(assignment: Assignment): Unit = {
          callback.executeOnSuccess(assignment)
        }
      })
    val driver = new ScalaSliceLookupDriver(lookup, () => ClerkData)
    driver.start()
    try {
      func(driver, callback)
    } finally {
      lookup.cancel()
      watchHandle.cancel(Status.CANCELLED.withDescription("cleaning up after withLookup"))
    }
  }

  override protected def createUnstartedSliceLookup(
      testAssigner: TestAssigner,
      clientType: ClientType,
      watchStubCacheTime: FiniteDuration = 20.seconds,
      sec: SequentialExecutionContext = sec): SliceLookupDriver = {
    val subscriberDebugName: String = "test-clerk"
    val config: InternalClientConfig =
      createInternalClientConfig(
        clientType,
        portToConnectTo(testAssigner),
        watchStubCacheTime
      )
    val lookup =
      SliceLookup.createUnstarted(
        sec,
        config.sliceLookupConfig,
        subscriberDebugName,
        createTestLogger(
          clientType,
          TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
          subscriberDebugName
        ),
        serviceBuilderOpt = None
      )
    new ScalaSliceLookupDriver(lookup, () => ClerkData)
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }

  // This test case is not exercised for Rust, because the Rust implementation currently (as of
  // March 2025) does not have z-pages.
  test("getSlicezData returns appropriate watch address") {
    // Test plan: Verify that the `watchAddress` from `lookup.getSlicezData` works as expected with
    // and without EDS. Without EDS, it should be the address of the Assigner. With EDS, it should
    // indicate EDS is enabled with a `eds://` address. But when the lookup is redirected, it should
    // show the redirected address.
    val assigner1: TestAssigner = multiAssignerTestEnv.testAssigners(0)

    withLookup(assigner1) { (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
      val data: ClientTargetSlicezData = lookup.getSlicezData
      val scheme: String = if (useSsl) "https" else "http"
      assert(
        data.watchAddress.toString.contains(s"$scheme://localhost:${portToConnectTo(assigner1)}")
      )
    }
  }

  // This test case is not exercised for Rust, because the Rust implementation currently (as of
  // March 2025) does not have z-pages.
  test("ClientTargetSlicezData is correctly generated based on the SliceLookup") {
    // Test plan: Ensure that the values of ClientTargetSlicezData are correctly generated from
    // SliceLookup. To verify this, populate the SliceLookup with clerk and slicelet data with
    // various load information, and check that the generated ClientTargetSlicezData is as
    // expected.
    val kubernetesNamespace: String = "kubernetesNamespace"

    // Use a fake clock so timestamps are deterministic.
    val fakeSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(
        s"fake-lookup-context-$getSafeName",
        Some(new FakeTypedClock)
      )

    val clerkDebugName: String = "clerk"
    val slicelet1DebugName: String = "slicelet1"
    val slicelet2DebugName: String = "slicelet2"

    val squid1: Squid = createTestSquid(slicelet1DebugName)
    val squid2: Squid = createTestSquid(slicelet2DebugName)

    // SliceletData for `squid1` with attributed loads and top keys.
    val slicelet1Data = SliceletData(
      squid1,
      SliceletState.Running,
      kubernetesNamespace,
      attributedLoads = Vector(
        SliceLoad(
          primaryRateLoad = 100.0,
          windowLowInclusive = Instant.EPOCH,
          windowHighExclusive = Instant.EPOCH,
          slice = Slice.FULL,
          topKeys = Seq(KeyLoad(SliceKey.MIN, 100)),
          numReplicas = 1
        )
      ),
      unattributedLoadOpt = None
    )

    // Configure the fake S2S proxy to forward requests to the assigner (for data plane tests).
    fakeS2SProxy.setFallbackUpstreamPorts(
      Vector(singleAssignerTestEnv.testAssigner.localUri.getPort)
    )

    val clerkConfig: InternalClientConfig = createInternalClientConfig(
      ClientType.Clerk,
      portToConnectTo(singleAssignerTestEnv.testAssigner),
      watchStubCacheTime = 20.seconds
    )
    val clerkSliceLookupConfig: SliceLookupConfig = clerkConfig.sliceLookupConfig
    val clerkSliceLookup = SliceLookup.createUnstarted(
      fakeSec,
      clerkSliceLookupConfig,
      clerkDebugName,
      createTestLogger(
        ClientType.Clerk,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        clerkDebugName
      ),
      serviceBuilderOpt = None
    )

    clerkSliceLookup.start(() => ClerkData)
    val slicelet1SliceLookupConfig: SliceLookupConfig =
      clerkSliceLookupConfig.copy(clientType = ClientType.Slicelet)
    val slicelet1SliceLookup = SliceLookup.createUnstarted(
      fakeSec,
      slicelet1SliceLookupConfig,
      slicelet1DebugName,
      createTestLogger(
        ClientType.Slicelet,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        slicelet1DebugName
      ),
      serviceBuilderOpt = None
    )
    slicelet1SliceLookup.start(() => slicelet1Data)

    // Setup: Write an initial frozen assignment to prevent the AssignmentGenerator from
    // auto-generating an assignment when the slicelet reports as healthy. Without this, reading
    // slicezData below would be racy because the auto-generated assignment may or may not have
    // propagated. Later, we write a new assignment to verify that it propagates to all lookups.
    val initialProposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val initialAssignment: Assignment =
      TestUtils.awaitResult(
        singleAssignerTestEnv.setAndFreezeAssignment(target, initialProposal),
        Duration.Inf
      )

    // Verify: Wait for both lookups to observe the initial assignment before reading slicezData.
    AssertionWaiter("Wait for lookups to receive initial assignment").await {
      val clerkData: ClientTargetSlicezData =
        TestUtils.awaitResult(clerkSliceLookup.getSlicezData, Duration.Inf)
      val slicelet1Data: ClientTargetSlicezData =
        TestUtils.awaitResult(slicelet1SliceLookup.getSlicezData, Duration.Inf)
      assert(clerkData.assignmentOpt.exists(_.generation == initialAssignment.generation))
      assert(slicelet1Data.assignmentOpt.exists(_.generation == initialAssignment.generation))
    }

    // Verify that the generated ClientTargetSlicezData is as expected for the clerk and slicelet1.
    val clerkSlicezData: ClientTargetSlicezData =
      TestUtils.awaitResult(clerkSliceLookup.getSlicezData, Duration.Inf)
    val slicelet1SlicezData: ClientTargetSlicezData =
      TestUtils.awaitResult(slicelet1SliceLookup.getSlicezData, Duration.Inf)

    // The last successful heartbeat and watch address timestamps are not deterministic (and we
    // don't need to test the correctness of those values), so we use the actual values from the
    // SliceLookups to set the expected values.
    val expectedClerkSlicezData = ClientTargetSlicezData(
      target,
      sliceletsData = ArrayBuffer.empty,
      clerksData = ArrayBuffer.empty,
      assignmentOpt = Some(initialAssignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None,
      squidOpt = None,
      unattributedLoadBySliceOpt = None,
      subscriberDebugName = clerkDebugName,
      watchAddress = clerkSliceLookupConfig.watchAddress,
      lastSuccessfulHeartbeat = clerkSlicezData.lastSuccessfulHeartbeat,
      watchAddressUsedSince = clerkSlicezData.watchAddressUsedSince,
      clientClusterOpt = clerkSlicezData.clientClusterOpt
    )
    val expectedSlicelet1SlicezData: ClientTargetSlicezData =
      expectedClerkSlicezData.copy(
        subscriberDebugName = slicelet1DebugName,
        reportedLoadPerResourceOpt = Some(Map((squid1, 100.0))),
        reportedLoadPerSliceOpt = Some(Map((Slice.FULL, 100.0))),
        unattributedLoadBySliceOpt = Some(Map.empty),
        squidOpt = Some(squid1),
        topKeysOpt = Some(SortedMap((SliceKey.MIN, 100.0))),
        lastSuccessfulHeartbeat = slicelet1SlicezData.lastSuccessfulHeartbeat,
        watchAddressUsedSince = slicelet1SlicezData.watchAddressUsedSince
      )

    // Now handle `slicelet2`.
    // SliceletData for `squid2` with only unattributed load and top keys.
    val slicelet2Data = SliceletData(
      squid2,
      SliceletState.Running,
      kubernetesNamespace,
      attributedLoads = Vector.empty,
      unattributedLoadOpt = Some(
        SliceLoad(
          primaryRateLoad = 100.0,
          windowLowInclusive = Instant.EPOCH,
          windowHighExclusive = Instant.EPOCH,
          slice = Slice.FULL,
          topKeys = Seq(KeyLoad(SliceKey.MIN, 200)),
          numReplicas = 1
        )
      )
    )

    // Start slicelet2's SliceLookup.
    val slicelet2SliceLookupConfig: SliceLookupConfig =
      clerkSliceLookupConfig.copy(clientType = ClientType.Slicelet)
    val slicelet2SliceLookup = SliceLookup.createUnstarted(
      fakeSec,
      slicelet2SliceLookupConfig,
      slicelet2DebugName,
      createTestLogger(
        ClientType.Slicelet,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        slicelet2DebugName
      ),
      serviceBuilderOpt = None
    )
    slicelet2SliceLookup.start(() => slicelet2Data)

    assertResult(expectedClerkSlicezData)(clerkSlicezData)
    assertResult(expectedSlicelet1SlicezData)(slicelet1SlicezData)

    // Setup: Write a new assignment to the Assigner, which will propagate asynchronously to all
    // started SliceLookups.
    val newProposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val newAssignment: Assignment =
      TestUtils.awaitResult(
        singleAssignerTestEnv.setAndFreezeAssignment(target, newProposal),
        Duration.Inf
      )

    // Verify: Wait for slicelet2 to receive the new assignment.
    AssertionWaiter("Wait for new assignment to propagate").await {
      val clerkSlicezData: ClientTargetSlicezData =
        TestUtils.awaitResult(clerkSliceLookup.getSlicezData, Duration.Inf)
      val slicelet1SlicezData: ClientTargetSlicezData =
        TestUtils.awaitResult(slicelet1SliceLookup.getSlicezData, Duration.Inf)
      val slicelet2SlicezData: ClientTargetSlicezData =
        TestUtils.awaitResult(slicelet2SliceLookup.getSlicezData, Duration.Inf)

      val newExpectedClerkSlicezData: ClientTargetSlicezData =
        expectedClerkSlicezData.copy(
          assignmentOpt = Some(newAssignment),
          lastSuccessfulHeartbeat = clerkSlicezData.lastSuccessfulHeartbeat,
          watchAddressUsedSince = clerkSlicezData.watchAddressUsedSince
        )
      val newExpectedSlicelet1SlicezData: ClientTargetSlicezData =
        expectedSlicelet1SlicezData.copy(
          assignmentOpt = Some(newAssignment),
          lastSuccessfulHeartbeat = slicelet1SlicezData.lastSuccessfulHeartbeat,
          watchAddressUsedSince = slicelet1SlicezData.watchAddressUsedSince
        )
      val newExpectedSlicelet2SlicezData: ClientTargetSlicezData =
        expectedClerkSlicezData.copy(
          assignmentOpt = Some(newAssignment),
          subscriberDebugName = slicelet2DebugName,
          reportedLoadPerResourceOpt = Some(Map((squid2, 0.0))),
          reportedLoadPerSliceOpt = Some(Map.empty),
          unattributedLoadBySliceOpt = Some(Map((Slice.FULL, 100.0))),
          squidOpt = Some(squid2),
          topKeysOpt = Some(SortedMap((SliceKey.MIN, 200.0))),
          lastSuccessfulHeartbeat = slicelet2SlicezData.lastSuccessfulHeartbeat,
          watchAddressUsedSince = slicelet2SlicezData.watchAddressUsedSince
        )
      assertResult(newExpectedClerkSlicezData)(clerkSlicezData)
      assertResult(newExpectedSlicelet1SlicezData)(slicelet1SlicezData)
      assertResult(newExpectedSlicelet2SlicezData)(slicelet2SlicezData)
    }

    // Cleanup: Cancel all lookups.
    clerkSliceLookup.cancel()
    slicelet1SliceLookup.cancel()
    slicelet2SliceLookup.cancel()
  }

  // This test case is not exercised for Rust, because the Rust implementation currently (as of
  // Oct 2025) does not have z-pages.
  test("SliceLookup ClientSlicez.register") {
    // Test plan: Verify that SliceLookup correctly registers to the ClientSlicez upon start, and
    // unregisters upon cancel. Verify it by creating an unstarted SliceLookup, checking it's not
    // registered, starting it and checking it's registered, then canceling it and checking it's
    // unregistered.

    // Setup: Create an unstarted SliceLookup with `subscriberDebugName`.
    val subscriberDebugName: String = "slice-lookup-client-slicez-register-test"
    val config: InternalClientConfig = createInternalClientConfig(
      ClientType.Clerk,
      portToConnectTo(singleAssignerTestEnv.testAssigner),
      watchStubCacheTime = 20.seconds
    )
    val lookup: SliceLookup = SliceLookup.createUnstarted(
      sec,
      config.sliceLookupConfig,
      subscriberDebugName,
      createTestLogger(
        ClientType.Clerk,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        subscriberDebugName
      ),
      serviceBuilderOpt = None
    )

    // Verify: Before starting, the lookup should not be registered in ClientSlicez.
    val dataBefore: Seq[ClientTargetSlicezData] =
      TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
    assert(
      !dataBefore.exists((_: ClientTargetSlicezData).subscriberDebugName == subscriberDebugName)
    )

    // Setup: Start the lookup to trigger registration.
    lookup.start(() => ClerkData)

    // Verify: After starting, the lookup should be registered in ClientSlicez.
    AssertionWaiter("Wait for the lookup to be registered").await {
      val dataAfterStart: Seq[ClientTargetSlicezData] =
        TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      assert(
        dataAfterStart.exists(
          (_: ClientTargetSlicezData).subscriberDebugName == subscriberDebugName
        )
      )
    }

    // Setup: Cancel the lookup to trigger unregistration.
    lookup.cancel()

    // Verify: After canceling, the lookup should be unregistered from ClientSlicez.
    AssertionWaiter("Wait for the lookup to be unregistered").await {
      val dataAfterCancel: Seq[ClientTargetSlicezData] =
        TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      assert(
        !dataAfterCancel
          .exists((_: ClientTargetSlicezData).subscriberDebugName == subscriberDebugName)
      )
    }
  }

  test("start is idempotent") {
    // Test plan: Verify that calling start() multiple times is safe and only the first call
    // has any effect. The lookup should continue to work correctly after multiple start() calls.

    val lookup: SliceLookupDriver = createUnstartedSliceLookup(
      singleAssignerTestEnv.testAssigner,
      ClientType.Clerk
    )

    try {
      // Call start() the first time - this should initialize the lookup.
      lookup.start()

      // Call start() multiple additional times - these should be no-ops.
      lookup.start()
      lookup.start()

      // Verify the lookup still works correctly by receiving an assignment.
      val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
      val assignment: Assignment =
        TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )

      AssertionWaiter("Wait for assignment after multiple start() calls").await {
        assert(lookup.assignmentOpt.isDefined)
        assert(lookup.assignmentOpt.get.generation == assignment.generation)
      }

      // Verify we can receive subsequent assignments as well.
      val proposal2: SliceMap[ProposedSliceAssignment] = sampleProposal()
      val assignment2: Assignment =
        TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal2),
          Duration.Inf
        )

      AssertionWaiter("Wait for second assignment").await {
        assert(lookup.assignmentOpt.get.generation == assignment2.generation)
      }
    } finally {
      lookup.cancel()
    }
  }

}
