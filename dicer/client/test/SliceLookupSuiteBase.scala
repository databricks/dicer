package com.databricks.dicer.client

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, UUID}

import com.databricks.rpc.RequestHeaders
import io.grpc.Metadata
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.matching.Regex

import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeProxy,
  FakeS2SProxyMetadataHandler,
  LogCapturer,
  LoggingStreamCallback,
  PrefixLogger,
  SequentialExecutionContext
}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.TargetMetricsUtils
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestAssigner.AssignerReplyType
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  ClientRequest,
  ClientResponse,
  ClientType,
  Generation,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment,
  Redirect,
  SliceSetImpl,
  SyncAssignmentState,
  TestAssigner
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

/** Contains test cases that apply to both the Scala and Rust implementations. */
abstract class SliceLookupSuiteBase(watchFromDataPlane: Boolean)
    extends DatabricksTest
    with TestName {

  // Disable context propagation to replicate the behavior of the real SliceLookup.
  protected val sec: SequentialExecutionContext =
    SequentialExecutionContext.createWithDedicatedPool(
      "SliceLookupSuite",
      enableContextPropagation = false
    )
  private val log = PrefixLogger.create(this.getClass, "")

  // Use an assigner that sends a lower timeout value to the client for tests.
  private val LOW_RPC_TIMEOUT = 1.second

  private val lowRpcTimeoutAssignerConfig =
    TestAssigner.Config.create(
      assignerConf = new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.internal.cachingteamonly.watchServerSuggestedRpcTimeoutMillis" ->
          LOW_RPC_TIMEOUT.toMillis,
          "databricks.dicer.assigner.assignerSuggestedClerkWatchTimeoutSeconds" ->
          LOW_RPC_TIMEOUT.toSeconds
        )
      ),
      tlsOptionsOpt = assignerTlsOptionOverride,
      expectRequestsThroughS2SProxy = watchFromDataPlane
    )

  /** A single assigner test environment used for most tests. */
  protected val singleAssignerTestEnv: InternalDicerTestEnvironment =
    InternalDicerTestEnvironment.create(lowRpcTimeoutAssignerConfig)

  /** A multi-assigner test environment for tests that need it. */
  protected val multiAssignerTestEnv: InternalDicerTestEnvironment =
    InternalDicerTestEnvironment.create(lowRpcTimeoutAssignerConfig, numAssigners = 3)

  /**
   * A fake S2S Proxy server that sits between the `SliceLookup` and the assigner when
   * [[watchFromDataPlane]] is true.
   */
  protected val fakeS2SProxy: FakeProxy = FakeProxy.createAndStart(FakeS2SProxyMetadataHandler)

  /** TLS options to use for the assigners in the test environments. */
  protected def assignerTlsOptionOverride: Option[TLSOptions] = None

  /** The target to use for the current test, derived from the test name. */
  protected def target: Target =
    Target.createKubernetesTarget(
      new URI("kubernetes-cluster:test-env/cloud2/public/region4/s1/5bxgyk"),
      getSafeName
    )

  /**
   * Returns the port that the `SliceLookup` should use to connect to the given assigner.
   *
   * Depending on the test configuration, this may be either the port for the assigner server
   * itself, or the port for the fake S2S Proxy server.
   */
  protected def portToConnectTo(testAssigner: TestAssigner): Int = {
    if (watchFromDataPlane) fakeS2SProxy.port else testAssigner.localUri.getPort
  }

  /**
   * Returns whether to use SSL for the SliceLookup's stub.
   *
   * The reason we don't use SSL for the watch-from-data-plane variants is that in those, we are
   * connecting to a fake S2S proxy, which currently doesn't use TLS, rather than directly
   * connecting to the assigner, which is an Armeria gRPC server.
   */
  // TODO(<internal bug>): Investigate if we can always use SSL if we create a fake S2S proxy that uses TLS.
  protected def useSsl: Boolean = !watchFromDataPlane

  override def beforeEach(): Unit = {
    // Reset replies so they don't pollute other tests.
    singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Normal)

    // In the multi assigner test environment, simulate that the first assigner is initially the
    // preferred assigner by having all of the assigners redirect to it.
    // TODO(<internal bug>): Remove the manual redirect overrides when we have integrated the preferred
    // assigner mechanism.
    val redirectToAssigner1: AssignerReplyType.OverwriteRedirect = createRedirectReply(
      Some(multiAssignerTestEnv.testAssigners.head.localUri)
    )
    for (assigner <- multiAssignerTestEnv.testAssigners) {
      assigner.setReplyType(redirectToAssigner1)
    }
  }

  override def afterAll(): Unit = {
    singleAssignerTestEnv.stop()
    multiAssignerTestEnv.stop()
  }

  /** Creates but does not start a [[SliceLookup]] connecting to `testAssigner`. */
  protected def createUnstartedSliceLookup(
      testAssigner: TestAssigner,
      clientType: ClientType,
      watchStubCacheTime: FiniteDuration = 20.seconds,
      sec: SequentialExecutionContext = sec): SliceLookupDriver

  /**
   * Creates and starts a [[SliceLookup]] connecting to `testAssigner`. Supplies the lookup and a
   * callback registered with the lookup to the given `func`. Cancels the callbacks and lookup after
   * the function returns. Other parameters are used for the lookup's [[InternalClientConfig]].
   *
   * @param testAssigner    the [[TestAssigner]] to connect the lookup to.
   * @param watchStubCacheTime how long gRPC stubs are cached before being evicted.
   * @param protoLoggerConf the proto logger configuration. Defaults to a test conf with 0% sampling
   *                        (logging disabled). Tests that want to verify logging should pass a
   *                        [[TestableDicerClientProtoLoggerConf]] with the desired sample fraction.
   * @param testTarget      the [[Target]] to watch. Defaults to the suite's [[target]] field.
   * @param clientIdOpt     optional client UUID sent in watch requests. Defaults to
   *                        [[TEST_CLIENT_UUID]].
   * @param func            test body receiving the started [[SliceLookupDriver]] and its
   *                        [[LoggingStreamCallback]].
   */
  protected def withLookup(
      testAssigner: TestAssigner,
      watchStubCacheTime: FiniteDuration = 20.seconds,
      protoLoggerConf: DicerClientProtoLoggerConf = TestClientUtils.createTestProtoLoggerConf(
        sampleFraction = 0.0
      ),
      testTarget: Target = target,
      clientIdOpt: Option[UUID] = Some(TEST_CLIENT_UUID))(
      func: (SliceLookupDriver, LoggingStreamCallback[Assignment]) => Unit): Unit

  /**
   * Creates the client configuration for a lookup for a local server listening on `assignerPort`.
   * Uses test SSL parameters. Other parameters are used for the lookup's [[InternalClientConfig]].
   *
   * @param clientType       the [[ClientType]] (e.g. Clerk) for the [[SliceLookupConfig]].
   * @param assignerPort     local port of the test assigner to connect to.
   * @param watchStubCacheTime how long gRPC stubs are cached before being evicted.
   * @param testTarget       the [[Target]] to watch. Defaults to the suite's [[target]] field.
   * @param clientIdOpt      optional client UUID included in the [[SliceLookupConfig]]. Defaults to
   *                         [[TEST_CLIENT_UUID]].
   */
  protected def createInternalClientConfig(
      clientType: ClientType,
      assignerPort: Int,
      watchStubCacheTime: FiniteDuration,
      testTarget: Target = target,
      clientIdOpt: Option[UUID] = Some(TEST_CLIENT_UUID)): InternalClientConfig = {
    val scheme: String = if (useSsl) "https" else "http"
    InternalClientConfig(
      SliceLookupConfig(
        clientType,
        watchAddress = new URI(s"$scheme://localhost:$assignerPort"),
        tlsOptionsOpt = if (useSsl) TestTLSOptions.clientTlsOptionsOpt else None,
        testTarget,
        clientIdOpt = clientIdOpt,
        watchStubCacheTime,
        watchRpcTimeout = LOW_RPC_TIMEOUT,
        watchFromDataPlane = watchFromDataPlane,
        enableRateLimiting = false
      )
    )
  }

  /**
   * Creates an [[AssignerReplyType.OverwriteRedirect]] with the given optional URI and
   * token.
   */
  protected def createRedirectReply(
      uriOpt: Option[URI]
  ): AssignerReplyType.OverwriteRedirect = {
    AssignerReplyType.OverwriteRedirect(Redirect(uriOpt))
  }

  /**
   * Given a logging `callback` on which results are being received, wait until `generation` is in
   * the log starting at the `fromElem` element number.
   */
  protected def waitForGeneration(
      callback: LoggingStreamCallback[Assignment],
      generation: Generation,
      fromElem: Int): Unit = {
    callback.waitForPredicate(_.generation == generation, fromElem)
  }

  /** Reads and returns the value of the empty watch responses Prometheus counter. */
  private def getNumEmptyWatchResponses(target: Target): Double = {
    readPrometheusMetric(
      "dicer_empty_watch_responses_total",
      Vector(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  /**
   * Reads and returns the value of the Prometheus metric with the given name and labels.
   *
   * For histograms, append "_count" or "_sum" to the metric name as needed.
   *
   * Returns zero if the metric has never been recorded with this set of label values.
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  test("Receive first assignment") {
    // Test plan: Create a SliceLookup. Provide the assigner with a single assignment and ensure
    // that the watcher gets it.
    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        val numInitialElements = callback.numElements
        val assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )
        waitForGeneration(callback, assignment.generation, numInitialElements)
    }
  }

  test("Receive empty message from Assigner") {
    // Test plan: Create a SliceLookup. Do not provide the assigner with any assignment and ensure
    // that the watcher gets an empty assignment.
    val initialNumEmptyResponses: Double = getNumEmptyWatchResponses(target)
    TestUtils.awaitResult(singleAssignerTestEnv.testAssigner.blockAssignment(target), Duration.Inf)
    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
        // Now wait for an empty message by waiting for the metric to increment.
        AssertionWaiter("Wait for empty message").await {
          val numEmptyResponses: Double = getNumEmptyWatchResponses(target)
          assert(numEmptyResponses > initialNumEmptyResponses)
        }
    }
  }

  test("Receive multiple assignments") {
    // Test plan: Create a SliceLookup. Provide different assignments to the Assigner and ensure
    // that they are received by the lookup.
    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        val numInitialElements = callback.numElements
        var assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )

        // Wait for the above assignment.
        waitForGeneration(callback, assignment.generation, numInitialElements)

        // Provide another assignment and wait for it - only that assignment should be received.
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
        waitForGeneration(callback, assignment.generation, numInitialElements)
        assert(
          callback.numElements == numInitialElements + 2,
          s"Tighter assert: Only ${assignment.generation} should be added"
        )

        // Now provide another higher assignment and receive it.
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
        waitForGeneration(callback, assignment.generation, numInitialElements)
        assert(
          callback.numElements == numInitialElements + 3,
          s"Tighter assert: Only ${assignment.generation} should be added"
        )
    }
  }

  test("Send assignment, receive empty message then assignment") {
    // Test plan: Create a SliceLookup. Send an assignment and then have the client's next RPC
    // return an empty message and then send another assignment.
    val initialNumEmptyResponses: Double = getNumEmptyWatchResponses(target)
    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        // Set the assignment and wait for the first assignment to be received.
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        val numInitialElements = callback.numElements
        var assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )
        waitForGeneration(callback, assignment.generation, numInitialElements)
        log.info("First assignment received")

        // Do nothing, there will be an empty message.
        // Wait for an empty message by waiting for the metric to increment.
        AssertionWaiter("Wait for empty message").await {
          val numEmptyResponses: Double = getNumEmptyWatchResponses(target)
          assert(numEmptyResponses > initialNumEmptyResponses)
        }
        log.info("Empty message received")

        // Provide an assignment and wait for the watcher to get it.
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
        waitForGeneration(callback, assignment.generation, numInitialElements)
        log.info("Second assignment received")
    }
  }

  test("Empty message then assignment") {
    // Test plan: Start the SliceLookup and make the assigner have no assignment. The SliceLookup
    // should receive an empty message. Then provide an assignment and make sure the watcher
    // receives it.
    val initialNumEmptyResponses: Double = getNumEmptyWatchResponses(target)
    TestUtils.awaitResult(singleAssignerTestEnv.testAssigner.blockAssignment(target), Duration.Inf)

    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        // Do nothing, there will be an empty message.
        // Wait for an empty message by waiting for the metric to increment.
        AssertionWaiter("Wait for empty message").await {
          val numEmptyResponses: Double = getNumEmptyWatchResponses(target)
          assert(numEmptyResponses > initialNumEmptyResponses)
        }

        // Now the assigner gets its first assignment - ensure that the watcher receives it.
        val numInitialElements = callback.numElements
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        TestUtils.awaitResult(
          singleAssignerTestEnv.testAssigner.unblockAssignment(target),
          Duration.Inf
        )
        val assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )
        waitForGeneration(callback, assignment.generation, numInitialElements)
    }
  }

  test("Send invalid assignment and then good one") {
    // Test plan: Provide an invalid assignment to the SliceLookup. Make sure that the error is
    // correctly handled. Then send a good assignment and ensure that the watcher received it.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    var assignment: Assignment =
      TestUtils.awaitResult(
        singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
        Duration.Inf
      )
    withLookup(singleAssignerTestEnv.testAssigner) {
      (lookup: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        val numInitialElements = callback.numElements

        // Send an invalid assignment, i.e., fails validation.
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.InvalidProto)
        AssertionWaiter("Wait for backoff").await {
          assert(lookup.isInBackoff)
        }
        log.info("Invalid assignment received and handled")

        // Send a good assignment and wait for it.
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Normal)
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
        waitForGeneration(callback, assignment.generation, numInitialElements)
        assert(!lookup.isInBackoff)
    }
  }

  test("Send a status error other than deadline exceeded and then good one") {
    // Test plan: Send a status exception to the SliceLookup. Make sure that the error is correctly
    // handled. Then send a good assignment and ensure that the watcher received it.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    var assignment: Assignment =
      TestUtils.awaitResult(
        singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
        Duration.Inf
      )
    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        val numInitialElements = callback.numElements

        // Send an unparseable assignment.
        LogCapturer.withCapturer(new Regex("ABORTED")) { capturer =>
          singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Error())
          capturer.waitForEvent()
        }

        // Send a good assignment and wait for it.
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Normal)
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
        waitForGeneration(callback, assignment.generation, numInitialElements)
    }
  }

  test("Watch error metrics are recorded for different error types") {
    // Test plan: Verify that watch request metrics are recorded with the correct status and
    // grpc_status labels. This test verifies both success and a few failure cases:
    // 1. Successful watch requests (status=success, grpc_status=OK).
    // 2. gRPC status errors (status=failure, grpc_status=ABORTED).
    // 3. Invalid proto responses (status=failure, grpc_status=INVALID_ARGUMENT).

    def getWatchRequestCount(status: String, grpcStatus: String): Double = {
      readPrometheusMetric(
        "dicer_client_watch_requests_total",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> ClientType.Clerk.getMetricLabel,
          "status" -> status,
          "grpc_status" -> grpcStatus
        )
      )
    }

    val successTracker = ChangeTracker(() => getWatchRequestCount("success", "OK"))
    val abortedErrorTracker = ChangeTracker(() => getWatchRequestCount("failure", "ABORTED"))
    val invalidArgumentErrorTracker =
      ChangeTracker(() => getWatchRequestCount("failure", "INVALID_ARGUMENT"))

    withLookup(singleAssignerTestEnv.testAssigner) {
      (_: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
        // Test 1: Verify successful watch requests are recorded.
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Normal)
        AssertionWaiter("Wait for success watch request metric").await {
          assert(
            successTracker.totalChange() >= 1.0,
            s"Expected success count to increase by at least 1, " +
            s"but increased by ${successTracker.totalChange()}"
          )
        }

        // Test 2: Send an ABORTED gRPC error.
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.Error())
        AssertionWaiter("Wait for ABORTED watch error metric").await {
          assert(
            abortedErrorTracker.totalChange() >= 1.0,
            s"Expected ABORTED error count to increase by at least 1, " +
            s"but increased by ${abortedErrorTracker.totalChange()}"
          )
        }

        // Test 3: Send an invalid proto response (empty proto that fails validation).
        singleAssignerTestEnv.testAssigner.setReplyType(AssignerReplyType.InvalidProto)
        AssertionWaiter("Wait for INVALID_ARGUMENT watch error metric").await {
          assert(
            invalidArgumentErrorTracker.totalChange() >= 1.0,
            s"Expected INVALID_ARGUMENT error count to increase by at least 1, " +
            s"but increased by ${invalidArgumentErrorTracker.totalChange()}"
          )
        }
    }
  }

  test("Test 2 assignments") {
    // Test Plan: Create a SliceLookup. Set an assignment at the Assigner and check that the lookup
    // receives it. Then send another assignment and make sure that it receives the assignment.
    withLookup(singleAssignerTestEnv.testAssigner) {
      (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        var assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )
        AssertionWaiter("Wait for slice assignment").await {
          assert(lookup.generationOpt.get == assignment.generation)
        }
        assignment = TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(
            target,
            proposal
          ),
          Duration.Inf
        )
        AssertionWaiter("Wait for slice assignment").await {
          assert(lookup.generationOpt.get == assignment.generation)
        }
    }
  }

  test("Redirect to new Assigner") {
    // Test plan: Verify that sending a redirect to the lookup updates the address that it watches
    // from. Do this by setting an assignment at the Assigner and checking that the lookup receives
    // it. Then inject a redirect into the Assigners, and verify that the lookup receives the new
    // assignment from the new Assigner.
    //
    // Also verify that when a lookup configured to watch cross cluster (`watchFromDataPlane =
    // true`) receives a redirect, it does not reach out to the redirected address directly, but
    // rather uses the "X-Databricks-Upstream-Host-Port" header to reach the redirected address
    // through S2S Proxy. Verify that the value of the "X-Databricks-Upstream-Host-Port" header is
    // set correctly to the base64 encoding of the UTF-8 representation of the redirect address in
    // "host:port" format.
    //
    // (Note: the assertions in this test currently assume in-memory stores, where one assigner does
    // not observe writes from another assigner via a watch against the underlying etcd store. If we
    // update this test to use etcd-backed stores, we will need a mechanism for pausing the watches
    // on the assigners or a different way of distinguishing which assigner the lookup received its
    // assignment from.)

    val assigner1 = multiAssignerTestEnv.testAssigners(0)
    val assigner2 = multiAssignerTestEnv.testAssigners(1)

    withLookup(assigner1) { (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
      val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
      val assignment: Assignment =
        TestUtils.awaitResult(
          assigner1.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
      AssertionWaiter("Wait for slice assignment").await {
        assert(lookup.generationOpt.get == assignment.generation)
      }

      // Redirect `lookup` to `assigner2`.
      val redirectReply = createRedirectReply(Some(assigner2.localUri))
      assigner1.setReplyType(redirectReply)
      assigner2.setReplyType(redirectReply)

      // Setup new assignment on `testEnv2`.
      val proposal2: SliceMap[ProposedSliceAssignment] = sampleProposal()
      val assignment2: Assignment =
        TestUtils.awaitResult(
          assigner2.setAndFreezeAssignment(target, proposal2),
          Duration.Inf
        )

      AssertionWaiter("Lookup connects to redirected Assigner").await {
        assert(lookup.generationOpt.get == assignment2.generation)

        if (watchFromDataPlane) {
          // Verify: The lookup reaches the redirect address through the "s2sproxy" by setting the
          // "X-Databricks-Upstream-Host-Port" header (instead of reaching out directly to the
          // redirect address as it would normally), and that the header is the base64 encoding of
          // the redirect host and port. This check is done in the assertion waiter block because
          // there may be in-flight last requests sent to the fake S2S proxy by other tests.
          val metadata: Metadata =
            fakeS2SProxy.latestRequestMetadataOpt.getOrElse(fail("No request received yet"))
          val base64EncodedHostPortHeaderValue: String =
            Option(
              metadata.get(
                Metadata.Key.of("X-Databricks-Upstream-Host-Port", Metadata.ASCII_STRING_MARSHALLER)
              )
            ).getOrElse(
              fail("Request does not yet have the X-Databricks-Upstream-Host-Port header")
            )
          val hostPortHeaderValue: String =
            new String(Base64.getDecoder.decode(base64EncodedHostPortHeaderValue), UTF_8)
          assert(hostPortHeaderValue == s"localhost:${assigner2.localUri.getPort}")
        }
      }
    }
  }

  if (watchFromDataPlane) {
    test("Data plane lookups do not use host-port header if redirect is missing host or port") {
      // Test plan: Verify that if a lookup configured to watch from the data plane
      // (`watchFromDataPlane = true`) receives a redirect without a valid host and port (which it
      // needs in order to populate the "X-Databricks-Upstream-Host-Port" header for executing the
      // redirect through s2sproxy), it does not populate the header and instead falls back to using
      // the default watch address.

      // Setup: Create a lookup that will connect to this "s2sproxy" which fakes out the forwarding
      // by actually being a TestAssigner and handling the request directly.
      val fakeS2SProxyTestAssigner: TestAssigner = singleAssignerTestEnv.testAssigners(0)
      withLookup(fakeS2SProxyTestAssigner) {
        (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
          // Setup: Set the redirect to a URI which does not have a host or port
          fakeS2SProxyTestAssigner.setReplyType(
            createRedirectReply(Some(URI.create("/bogus/redirect")))
          )

          // Verify: Check that the lookup is still issuing watch requests despite the bogus
          // redirect, and is still receiving assignment updates.
          val assignment1: Assignment =
            TestUtils.awaitResult(
              fakeS2SProxyTestAssigner.setAndFreezeAssignment(
                target,
                createProposal(
                  ("" -- "Dori") -> Seq("Pod2"),
                  ("Dori" -- ∞) -> Seq("Pod0")
                )
              ),
              Duration.Inf
            )
          AssertionWaiter("Wait for first assignment change to be received").await {
            assert(lookup.generationOpt.get == assignment1.generation)
          }
          val assignment2: Assignment =
            TestUtils.awaitResult(
              fakeS2SProxyTestAssigner.setAndFreezeAssignment(
                target,
                createProposal(
                  ("" -- "Fili") -> Seq("Pod8"),
                  ("Fili" -- ∞) -> Seq("Pod6")
                )
              ),
              Duration.Inf
            )
          AssertionWaiter("Wait for second assignment change to be received").await {
            assert(lookup.generationOpt.get == assignment2.generation)
          }

          // Verify: The lookup is not including the host and port header (note that we're only
          // checking the latest request, but that is good enough).
          val headers: RequestHeaders =
            fakeS2SProxyTestAssigner
              .getLatestClerkWatchRequest(target)
              .map {
                case (headers: RequestHeaders, _: ClientRequest) => headers
              }
              .getOrElse(fail("No request received yet"))
          assert(!headers.contains("X-Databricks-Upstream-Host-Port"))
      }
    }
  }

  test("Fallback when redirected Assigner request fails") {
    // Test plan: Verify that when the lookup receives a redirect, and the requests to the
    // redirected address succeed but then start failing, the lookup enters backoff and the next
    // retry eventually falls back to the default address. Also verify that stubs are cleared from
    // the cache after some delay.
    //
    // Do this by setting an assignment at the Assigner and checking that the lookup receives it.
    // Then inject a redirect into the Assigners, and verify that the lookup receives a new
    // assignment from the new Assigner. Then fail requests from the new Assigner, verify the
    // lookup enters backoff, and then verify it falls back to the original Assigner.
    val assigner1 = multiAssignerTestEnv.testAssigners(0)
    val assigner2 = multiAssignerTestEnv.testAssigners(1)

    withLookup(
      assigner1,
      watchStubCacheTime = LOW_RPC_TIMEOUT * 2
    ) { (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
      val defaultAsn: Assignment =
        TestUtils.awaitResult(
          assigner1.setAndFreezeAssignment(target, sampleProposal()),
          Duration.Inf
        )
      AssertionWaiter("Wait for slice assignment").await {
        assert(lookup.generationOpt.get == defaultAsn.generation)
      }

      // Setup new assignment on `assigner2`,
      val newAsn: Assignment =
        TestUtils.awaitResult(
          assigner2.setAndFreezeAssignment(target, sampleProposal()),
          Duration.Inf
        )
      // Redirect `lookup` to `assigner2`. First ensure that it will keep directing requests to
      // itself.
      val redirectReply = createRedirectReply(Some(assigner2.localUri))
      assigner1.setReplyType(redirectReply)
      assigner2.setReplyType(redirectReply)
      AssertionWaiter("Lookup connects to redirected Assigner").await {
        assert(lookup.generationOpt.get == newAsn.generation)
      }

      AssertionWaiter("Cache evicts old stub").await {
        for (cacheSize: Long <- lookup.getWatchStubCacheSize) {
          assert(cacheSize == 1)
        }
      }

      // Fail requests to `assigner2`, succeed them from `assigner1`.
      assigner1.setReplyType(createRedirectReply(Some(assigner1.localUri)))
      assigner2.setReplyType(AssignerReplyType.Error())

      // Verify that the lookup enters backoff.
      AssertionWaiter("Lookup enters backoff").await {
        assert(lookup.isInBackoff)
      }

      // Setup new assignment.
      val defaultAsn2: Assignment =
        TestUtils.awaitResult(
          assigner1.setAndFreezeAssignment(target, sampleProposal()),
          Duration.Inf
        )
      AssertionWaiter("Lookup falls back to original Assigner").await {
        assert(lookup.generationOpt.get == defaultAsn2.generation)
      }

      // Verify that the lookup exits backoff.
      AssertionWaiter("Lookup exits backoff").await {
        assert(!lookup.isInBackoff)
      }
    // Note the cache will not evict the stub for assigner1 because it always explicitly redirects
    // to itself in our redirect model.
    }
  }

  test("Redirect does not send assignment") {
    // Test plan: When the lookup receives a redirect from a server that has an older generation,
    // verify that the lookup does not send the assignment to the new server. Normally the client
    // sends its assignment to the assigner if needed, but if the next request is to a different
    // assigner then the client doesn't know what generation it will be at and there is no point in
    // sending the assignment.

    val assigner1: TestAssigner = multiAssignerTestEnv.testAssigners(0)
    val assigner2: TestAssigner = multiAssignerTestEnv.testAssigners(1)

    withLookup(assigner1) { (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
      val defaultAsn: Assignment =
        TestUtils.awaitResult(
          assigner1.setAndFreezeAssignment(target, sampleProposal()),
          Duration.Inf
        )
      AssertionWaiter("Wait for slice assignment").await {
        assert(lookup.generationOpt.get == defaultAsn.generation)
      }

      // Redirect `lookup` to `assigner2`. Also set `assigner1`'s generation to be older than the
      // current generation. Set a large `suggestedRpcTimeout` so that the assertion below will
      // run after the first request (rather than second or later), avoiding test flakiness.

      val redirect = Redirect(Some(assigner2.localUri))
      val response = ClientResponse(
        SyncAssignmentState.KnownGeneration(Generation.EMPTY),
        30.seconds,
        redirect
      )
      assigner1.setReplyType(
        AssignerReplyType.FutureOverride(Future.successful(response.toProto))
      )
      // Make sure that assigner2 also redirects to itself.
      assigner2.setReplyType(AssignerReplyType.OverwriteRedirect(redirect))

      // Wait for the new redirected request, and check that it doesn't contain the assignment.
      val request: ClientRequest =
        AssertionWaiter("Lookup sends redirected request").await {
          val watchRequestOpt: Option[ClientRequest] =
            assigner2.getLatestClerkWatchRequest(target).map {
              case (_: RequestHeaders, req: ClientRequest) => req
            }
          assert(watchRequestOpt.isDefined)
          watchRequestOpt.get
        }
      assert(request.syncAssignmentState.isInstanceOf[SyncAssignmentState.KnownGeneration])
    }
  }

  test("createUnstarted increments metric") {
    // Test plan: verify that creating SliceLookups increments the expected metric.
    def getNumSliceLookupsMetric(target: Target, clientType: ClientType): Long = {
      readPrometheusMetric(
        "dicer_client_num_slice_lookups_total",
        labels = Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> clientType.toString
        )
      ).toLong
    }

    // No lookups for the target: metric should be 0.
    val initialClerkLookups: Long = getNumSliceLookupsMetric(target, ClientType.Clerk)
    val initialSliceletLookups: Long = getNumSliceLookupsMetric(target, ClientType.Slicelet)

    assert(initialClerkLookups == 0)
    assert(initialSliceletLookups == 0)

    createUnstartedSliceLookup(
      singleAssignerTestEnv.testAssigner,
      ClientType.Clerk
    )

    assert(getNumSliceLookupsMetric(target, ClientType.Clerk) == initialClerkLookups + 1)
    assert(getNumSliceLookupsMetric(target, ClientType.Slicelet) == initialSliceletLookups)

    createUnstartedSliceLookup(
      singleAssignerTestEnv.testAssigner,
      ClientType.Slicelet
    )

    assert(getNumSliceLookupsMetric(target, ClientType.Clerk) == initialClerkLookups + 1)
    assert(getNumSliceLookupsMetric(target, ClientType.Slicelet) == initialSliceletLookups + 1)

    createUnstartedSliceLookup(
      singleAssignerTestEnv.testAssigner,
      ClientType.Clerk
    )

    assert(getNumSliceLookupsMetric(target, ClientType.Clerk) == initialClerkLookups + 2)
    assert(getNumSliceLookupsMetric(target, ClientType.Slicelet) == initialSliceletLookups + 1)
  }

  test("Redirect failure retries are backoff-bounded") {
    // Test plan: Verify that a persistent redirect/failure path does not create a tight retry loop.
    // Specifically, when the preferred assigner keeps failing requests, the lookup should enter
    // backoff before retrying via the default route, preventing a high-frequency retry loop to DOS
    // the preferred assigner. This is a regression test for <internal link>.
    //
    // Do this by configuring assigner1 to always redirect to assigner2, and assigner2 to always
    // fail with ABORTED, so the client cycles assigner1 (redirect success) -> clear backoff ->
    // assigner2 (failure) -> backoff -> repeat.
    //
    // Verify ABORTED failures are bounded by measuring their rate over a fixed observation window:
    // wait until at least one ABORTED failure is observed, record failuresAtStart and startTime,
    // sleep for the window, then record failuresAtEnd and endTime. Compute the failure rate and
    // verify it is greater than 0 and bounded to a reasonable value.
    val assigner1: TestAssigner = multiAssignerTestEnv.testAssigners(0)
    val assigner2: TestAssigner = multiAssignerTestEnv.testAssigners(1)
    val observationWindow: Duration = 4.seconds
    // With 40% jitter, the first backoff delay can be as low as 0.8s, so the QPS can be as high as
    // 1.25, we set the maxAbortedFailuresQps to 1.5 to allow for some wiggle room.
    val maxAbortedFailuresQps: Double = 1.5

    // Configure S2S proxy to route initial requests to assigner1 when watchFromDataPlane is true.
    if (watchFromDataPlane) {
      fakeS2SProxy.setFallbackUpstreamPorts(Vector(assigner1.localUri.getPort))
    }

    // Helper to read the number of aborted watch failures.
    def getAbortedWatchFailureCount: Long = {
      readPrometheusMetric(
        "dicer_client_watch_requests_total",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> ClientType.Slicelet.getMetricLabel,
          "status" -> "failure",
          "grpc_status" -> "ABORTED"
        )
      ).toLong
    }

    assigner1.setReplyType(createRedirectReply(Some(assigner2.localUri)))
    assigner2.setReplyType(AssignerReplyType.Error())

    val failureTracker = ChangeTracker(() => getAbortedWatchFailureCount)
    assert(getAbortedWatchFailureCount == 0)
    assert(failureTracker.totalChange() == 0)

    val lookup: SliceLookupDriver =
      createUnstartedSliceLookup(assigner1, clientType = ClientType.Slicelet)

    try {
      lookup.start()

      // Ensure the persistent failure loop is active before measuring bounded growth.
      AssertionWaiter("Wait for first ABORTED watch failure").await {
        assert(failureTracker.totalChange() >= 1)
      }

      // Record the start time of the observation window.
      val failuresAtStart: Long = failureTracker.totalChange()
      val startNs: Long = System.nanoTime()

      // Apply a sleep to ensure the observation window elapses.
      Thread.sleep(observationWindow.toMillis)

      // Verify that the failure rate does not exceed `maxAbortedFailuresQps`.
      val failuresAtEnd: Long = failureTracker.totalChange()
      val endNs: Long = System.nanoTime()
      val elapsedSeconds: Double = (endNs - startNs) / 1e9
      val abortedFailureQps: Double =
        (failuresAtEnd - failuresAtStart) / elapsedSeconds
      assert(abortedFailureQps > 0.0, "Expected at least one aborted failure")
      assert(
        abortedFailureQps <= maxAbortedFailuresQps,
        s"Aborted failure rate too high over $observationWindow: $abortedFailureQps"
      )
    } finally {
      lookup.cancel()
    }
  }

  namedGridTest("SliceLookup records serialized client request sizes as metrics")(
    Seq(
      ("Clerk to Assigner", ClientType.Clerk),
      ("Slicelet to Assigner", ClientType.Slicelet)
    ).toMap
  ) { (clientType: ClientType) =>
    // Test plan: Verify that SliceLookup emits the dicer_client_request_proto_size_bytes
    // histogram metric to track the size of serialized ClientRequestP protos sent to the Assigner.
    // This test covers both Clerk→Assigner and Slicelet→Assigner cases.

    // Configure S2S proxy to route requests to the test assigner when watchFromDataPlane is true.
    if (watchFromDataPlane) {
      fakeS2SProxy.setFallbackUpstreamPorts(
        Vector(singleAssignerTestEnv.testAssigner.localUri.getPort)
      )
    }

    // Helper to read histogram count
    def getRequestSizeCount: Double = {
      readPrometheusMetric(
        "dicer_client_request_proto_size_bytes_histogram_count",
        labels = Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> clientType.toString
        )
      )
    }

    // Helper to read histogram sum
    def getRequestSizeSum: Double = {
      readPrometheusMetric(
        "dicer_client_request_proto_size_bytes_histogram_sum",
        labels = Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> clientType.toString
        )
      )
    }

    // Create change trackers
    val countTracker = ChangeTracker[Double](() => getRequestSizeCount)
    val sumTracker = ChangeTracker[Double](() => getRequestSizeSum)

    // Create a lookup with the specified client type
    val lookup: SliceLookupDriver = createUnstartedSliceLookup(
      singleAssignerTestEnv.testAssigner,
      clientType = clientType
    )

    try {
      lookup.start()

      // Set an assignment to trigger the lookup to send a ClientRequestP
      val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal
      val assignment: Assignment =
        TestUtils.awaitResult(
          singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )

      // Wait for the lookup to receive the assignment
      AssertionWaiter(s"Wait for $clientType to receive assignment").await {
        assert(lookup.assignmentOpt.isDefined)
        assert(lookup.assignmentOpt.get.generation == assignment.generation)
      }

      // Verify the histogram was updated
      assert(
        countTracker.totalChange() >= 1.0,
        s"Expected $clientType histogram count to increase by at least 1"
      )
      assert(
        sumTracker.totalChange() > 0.0,
        s"Expected $clientType histogram sum to increase by a positive amount"
      )
    } finally {
      lookup.cancel()
    }
  }

  test("Test lookup") {
    // Test Plan: Create a SliceLookup. Set an assignment at the Assigner and check that the lookup
    // receives it. Then look up some keys on it.
    withLookup(singleAssignerTestEnv.testAssigner) {
      (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
        val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
          ("" -- fp("Dori")) -> Seq("Pod2"),
          (fp("Dori") -- fp("Fili")) -> Seq("Pod0"),
          (fp("Fili") -- fp("Kili")) -> Seq("Pod0"),
          (fp("Kili") -- fp("Nori")) -> Seq("Pod1"),
          (fp("Nori") -- ∞) -> Seq("Pod3")
        )
        val assignment: Assignment =
          TestUtils.awaitResult(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )
        AssertionWaiter("Wait for slice assignment").await {
          assert(lookup.assignmentOpt.get.generation == assignment.generation)
        }
        // Now check with synchronous calls since the assignment is cached.
        assert(
          lookup
            .isAssignedKey(fp("Nori"), assignment.sliceMap.entries(4).resources.head)
            .contains(true)
        )

        // Check isAssignedKey and getSliceSetForResource (tests for the underlying function in
        // SliceMap are in SliceMapSuite).
        val pod1: Squid = createTestSquid("Pod1")
        assert(lookup.isAssignedKey(fp("Kili"), pod1).contains(true))

        val generation = lookup.assignmentOpt.get.generation
        assert(generation == assignment.generation)
        val slices: SliceSetImpl = lookup.getSliceSetForResource(pod1)

        // Verify the slices assigned to Pod1.
        val expectedSlices = SliceSetImpl(assignment.sliceMap.entries(3).slice)
        assert(slices == expectedSlices)
    }
  }

  test("Assigner gets assignment from client") {
    // Test plan: Set an assignment at the Assigner and check that the lookup receives it. Simulate
    // an assigner restart by redirecting the lookup to connect to a different assigner instance and
    // verify that the new Assigner learns about the assignment from the lookup.

    val assigner1 = multiAssignerTestEnv.testAssigners(0)
    val assigner2: TestAssigner = multiAssignerTestEnv.testAssigners(1)

    withLookup(assigner1) { (lookup: SliceLookupDriver, _: LoggingStreamCallback[Assignment]) =>
      val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
      val assignment: Assignment =
        TestUtils.awaitResult(
          assigner1.setAndFreezeAssignment(target, proposal),
          Duration.Inf
        )
      AssertionWaiter("Wait for slice assignment").await {
        assert(lookup.assignmentOpt.get.generation == assignment.generation)
      }

      // Redirect `lookup` to `assigner2`. First ensure that the new Assigner will keep directing
      // requests to itself.
      val redirectReply = createRedirectReply(Some(assigner2.localUri))
      assigner2.setReplyType(redirectReply)
      assigner1.setReplyType(redirectReply)

      // Eventually, the new Assigner will record that it has learned about a new assignment
      // from a Clerk (which is how clients using `createInternalClientConfig` present
      // themselves). Check that the expected metric is incremented and that the new Assigner
      // has the expected assignment.
      AssertionWaiter("Assigner2 learning about new assignment").await {
        val numDistributedAssignments = TargetMetricsUtils
          .getNumDistributedAssignments(target, AssignmentDistributionSource.Clerk.toString)
        assert(numDistributedAssignments > 0)
        val assigner2Assignment: Option[Assignment] =
          TestUtils.awaitResult(assigner2.getAssignment(target), Duration.Inf)
        assert(assigner2Assignment.contains(assignment))
      }
    }
  }

  test("assignment propagation latency histogram is recorded") {
    // Test plan: Create a SliceLookup, receive an assignment, and verify that the
    // assignment propagation latency histogram is recorded with the correct labels.
    def getHistogramCount(target: Target): Double = {
      readPrometheusMetric(
        "dicer_assignment_propagation_latency_ms_count",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
    }

    def getHistogramSum(target: Target): Double = {
      readPrometheusMetric(
        "dicer_assignment_propagation_latency_ms_sum",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
    }

    // Get initial metric values
    val initialCount = getHistogramCount(target)
    val initialSum = getHistogramSum(target)

    withLookup(singleAssignerTestEnv.testAssigner) {
      (lookup: SliceLookupDriver, callback: LoggingStreamCallback[Assignment]) =>
        // Create and send an assignment
        val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
        val numInitialElements = callback.numElements
        val assignment: Assignment =
          Await.result(
            singleAssignerTestEnv.setAndFreezeAssignment(target, proposal),
            Duration.Inf
          )

        // Wait for the assignment to be received
        waitForGeneration(callback, assignment.generation, numInitialElements)

        // Verify the histogram was incremented
        val finalCount = getHistogramCount(target)
        val finalSum = getHistogramSum(target)

        assert(finalCount == initialCount + 1, "Histogram count should increment by 1")
        assert(finalSum > initialSum, "Histogram sum should increase")
    }
  }

}
