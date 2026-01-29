package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID

import com.databricks.dicer.assigner.conf.DicerAssignerConf
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import io.grpc.{ClientInterceptor, Metadata}
import io.grpc.stub.MetadataUtils

import com.databricks.rpc.DatabricksObjectMapper
import io.grpc.Deadline

import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc.AssignmentServiceStub
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{AssertionWaiter, MetricUtils, PrefixLogger, TestUtils}
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.conf.Configs
import com.databricks.dicer.client.WatchStubHelper
import com.databricks.rpc.tls.TLSOptionsMigration
import com.databricks.dicer.assigner.config.{
  StaticTargetConfigProvider,
  InternalTargetConfigMap,
  InternalTargetConfigMetrics
}
import com.databricks.dicer.common.TestSliceUtils.{createTestSquid, sampleProposal}
import com.databricks.dicer.common.Version.LATEST_VERSION
import com.databricks.dicer.common.{
  Assignment,
  ClerkData,
  ClientRequest,
  ClientResponse,
  Generation,
  InternalDicerTestEnvironment,
  ProposedSliceAssignment,
  SliceletData,
  SubscriberHandler,
  SubscriberHandlerMetricUtils,
  SyncAssignmentState,
  TestAssigner,
  TestSliceUtils
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.{Clerk, ResourceAddress, Slicelet, Target}
import com.databricks.dicer.friend.SliceMap
import com.databricks.rpc.testing.TestSslArguments
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest
import io.grpc.StatusRuntimeException
import io.prometheus.client.CollectorRegistry

import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP
import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.common.http.Headers
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.dicer.assigner.TargetMetrics.WatchError
import com.databricks.dicer.assigner.AssignerSuite.{
  ASSIGNER_CLUSTER_LOCATION_CONF,
  DP1_CLUSTER_LOCATION_CONF,
  DP1_CLUSTER_URI,
  DP2_CLUSTER_LOCATION_CONF,
  DP2_CLUSTER_URI
}
import com.databricks.dicer.common.SubscriberHandler.MetricsKey

object AssignerSuite {

  /**
   * Timeout for the Watch RPC from a client perspective. Set to a larger value than used in
   * production to avoid test flakes, since the tests do not retry watch requests.
   */
  private val WATCH_RPC_TIMEOUT: FiniteDuration = 1.minute

  /** URI of the kubernetes cluster where the Assigner will run. */
  private val ASSIGNER_CLUSTER_URI: URI = new URI(
    "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01"
  )

  /** A [[LocationConf]] where the "LOCATION" env var is set to the Assigner's cluster. */
  private val ASSIGNER_CLUSTER_LOCATION_CONF: LocationConf =
    LocationConfTestUtils.newTestLocationConfig(
      envMap = Map(
        "LOCATION" -> DatabricksObjectMapper.toJson(
          Map(
            "cloud_provider" -> "AWS",
            "cloud_provider_region" -> "AWS_US_WEST_2",
            "environment" -> "DEV",
            "kubernetes_cluster_type" -> "GENERAL",
            "kubernetes_cluster_uri" -> s"${ASSIGNER_CLUSTER_URI.toASCIIString}",
            "region_uri" -> "region:dev/cloud1/public/region1",
            "regulatory_domain" -> "PUBLIC"
          )
        )
      )
    )

  /** Sample URI of a data plane kubernetes cluster. */
  private val DP1_CLUSTER_URI: URI =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")

  /** A [[LocationConf]] where the "LOCATION" env var is configured as a data plane cluster. */
  private val DP1_CLUSTER_LOCATION_CONF: LocationConf = LocationConfTestUtils.newTestLocationConfig(
    envMap = Map(
      "LOCATION" -> DatabricksObjectMapper.toJson(
        Map(
          "cloud_provider" -> "AWS",
          "cloud_provider_region" -> "AWS_US_WEST_2",
          "environment" -> "DEV",
          "kubernetes_cluster_type" -> "NEPHOS",
          "kubernetes_cluster_uri" -> s"${DP1_CLUSTER_URI.toASCIIString}",
          "region_uri" -> "region:dev/cloud1/public/region1",
          "regulatory_domain" -> "PUBLIC"
        )
      )
    )
  )

  /** Sample URI of a data plane kubernetes cluster. */
  private val DP2_CLUSTER_URI: URI =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/fkma35")

  /** A [[LocationConf]] where the "LOCATION" env var is configured as a data plane cluster. */
  private val DP2_CLUSTER_LOCATION_CONF: LocationConf = LocationConfTestUtils.newTestLocationConfig(
    envMap = Map(
      "LOCATION" -> DatabricksObjectMapper.toJson(
        Map(
          "cloud_provider" -> "AWS",
          "cloud_provider_region" -> "AWS_US_WEST_2",
          "environment" -> "DEV",
          "kubernetes_cluster_type" -> "NEPHOS",
          "kubernetes_cluster_uri" -> s"${DP2_CLUSTER_URI.toASCIIString}",
          "region_uri" -> "region:dev/cloud1/public/region1",
          "regulatory_domain" -> "PUBLIC"
        )
      )
    )
  )

  /** Issues a watch request to the given stub and awaits a response. */
  private def performWatchCallSync(
      stub: AssignmentServiceStub,
      request: ClientRequest): ClientResponse = {
    val responseProto: ClientResponseP =
      TestUtils.awaitResult(
        stub
          .withDeadline(Deadline.after(WATCH_RPC_TIMEOUT.toNanos, NANOSECONDS))
          .watch(request.toProto),
        Duration.Inf
      )
    ClientResponse.fromProto(responseProto)
  }
}

class AssignerSuite extends DatabricksTest with TestName {
  import AssignerSuite.{WATCH_RPC_TIMEOUT, ASSIGNER_CLUSTER_URI, performWatchCallSync}

  private val testAssignerConf: TestAssigner.Config = TestAssigner.Config.create()
  private val testEnv =
    InternalDicerTestEnvironment.create(
      config = testAssignerConf,
      assignerClusterUri = ASSIGNER_CLUSTER_URI
    )
  private val testAssigner: TestAssigner = testEnv.testAssigner
  private var stub: AssignmentServiceStub = _
  private val log = PrefixLogger.create(getClass, "")

  /** A helper class for creating Watch stubs. */
  private val watchStubHelper = new WatchStubHelper(
    clientName = Project.DicerAssigner.name,
    subscriberDebugName = "assigner-suite-test",
    defaultWatchAddress = URI.create(s"http://localhost:${testEnv.getAssignerPort}"),
    tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
    watchFromDataPlane = false
  )
  override def beforeAll(): Unit = {
    // Create a stub that can be used to communicate with the Assigner to get the assignment.
    stub = watchStubHelper.createWatchStub(redirectAddressOpt = None)
  }

  override def beforeEach(): Unit = {
    InternalTargetConfigMetrics.forTest.clearMetrics()
  }


  test("backend returns the correct test result") {
    // Test plan: call the watch RPC and verify that the returned assignment is the expected value.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val target = Target(getSafeName)
    val assignment: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)
    val request = ClientRequest(
      target,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "main-subscriber",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
    val asnResponse: ClientResponse = performWatchCallSync(stub, request)
    assert(
      asnResponse.syncState == SyncAssignmentState.KnownAssignment(assignment)
    )
  }



  test("Test two clients") {
    // Test plan: call the watch RPC twice concurrently and verify that the returned assignment is
    // the expected value.
    val target = Target(getSafeName)

    // Create another stub to communicate with the Assigner that has a low timeout. Then make it
    // timeout so that the WatchCell is created at the Assigner due to this request - but no
    // assignment is returned since it has not been set yet.
    val shortTimeout = 1.second
    val stub2: AssignmentServiceStub = watchStubHelper.createWatchStub(
      Some(URI.create(s"http://localhost:${testEnv.getAssignerPort}"))
    )

    // Send the request with a short timeout and let the assigner return an empty assignment.
    val syncState = SyncAssignmentState.KnownGeneration(
      Generation(testEnv.testAssigner.storeIncarnation, 87)
    )
    val request2 = ClientRequest(
      target,
      syncState,
      "second-sub",
      shortTimeout,
      ClerkData,
      supportsSerializedAssignment = true
    )
    log.info(s"Starting watch call from AssignmentWatcher: $target")
    val response2: ClientResponse = performWatchCallSync(stub2, request2)
    assert(response2.syncState.isInstanceOf[SyncAssignmentState.KnownGeneration])
    log.info("RPC with empty response done")

    // Now set the assignment and make the requests for the same assignment and check that both
    // calls succeed.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val assignment: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target, proposal), Duration.Inf)
    val syncState1 = SyncAssignmentState.KnownGeneration(Generation.EMPTY)
    val request1 = ClientRequest(
      target,
      syncState1,
      "two-clients",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
    val asn: ClientResponse = performWatchCallSync(stub, request1)
    assert(
      asn.syncState == SyncAssignmentState.KnownAssignment(assignment)
    )
    log.info("Assignment received on first call")

    // Second attempt via stub2.
    val asn2: ClientResponse = performWatchCallSync(stub2, request2)
    assert(asn2.syncState == SyncAssignmentState.KnownAssignment(assignment))
    log.info("Assignment received on second call")

    assert(
      SubscriberHandlerMetricUtils
        .getNumSliceletsByHandler(SubscriberHandler.Location.Assigner, target, LATEST_VERSION) == 0
    )
    assert(
      SubscriberHandlerMetricUtils
        .getNumClerksByHandler(SubscriberHandler.Location.Assigner, target, LATEST_VERSION) == 2
    )
  }

  test("Test multiple assignments") {
    // Test plan: call the watch RPC twice for different assignments and verify that the
    // correct assignment is returned.
    val proposal: SliceMap[ProposedSliceAssignment] = sampleProposal()
    val target1 = Target(s"$getSafeName-1")
    val target2 = Target(s"$getSafeName-2")

    val assignment1: Assignment =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target1, proposal), Duration.Inf)
    val assignment2 =
      TestUtils.awaitResult(testAssigner.setAndFreezeAssignment(target2, proposal), Duration.Inf)

    val request1 = ClientRequest(
      target1,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "asn1",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
    val request2 = ClientRequest(
      target2,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "asn2",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )

    // Send the requests and wait for their respective assignments.
    val asn1: ClientResponse = performWatchCallSync(stub, request1)
    assert(
      asn1.syncState == SyncAssignmentState.KnownAssignment(assignment1)
    )

    val asn2: ClientResponse = performWatchCallSync(stub, request2)
    assert(
      asn2.syncState == SyncAssignmentState.KnownAssignment(assignment2)
    )
  }

  gridTest("Unknown targets are rejected")(
    Seq(
      Target.apply(_: String),
      (name: String) => Target.createAppTarget(transformToSafeAppTargetName(name), "instance-id")
    )
  ) { (targetFactory: String => Target) =>
    // Test plan: Verify that the Assigner rejects watch requests for unknown targets (i.e., targets
    // that do not have a config). Verify this by sending a watch request for an unknown target and
    // checking that the Assigner returns an error and increments the appropriate metric.

    // Create a local test environment with default target configs disabled.
    val localTestEnv = InternalDicerTestEnvironment.create(withDefaultTargetConfig = false)
    val localStub: AssignmentServiceStub = watchStubHelper.createWatchStub(
      Some(URI.create(s"http://localhost:${localTestEnv.getAssignerPort}"))
    )

    val target = targetFactory(getSafeName)
    val request = ClientRequest(
      target,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      "unknown-target",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
    assertThrow[StatusRuntimeException]("Unknown target") {
      TestUtils.awaitResult(localStub.watch(request.toProto), Duration.Inf)
    }

    // Verify that the Assigner incremented the appropriate metric.
    val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

    assertResult(1)(
      MetricUtils
        .getMetricValue(
          registry,
          metric = "dicer_assigner_num_watch_errors_total",
          labels = Map(
            "targetCluster" -> target.getTargetClusterLabel,
            "targetName" -> target.getTargetNameLabel,
            "targetInstanceId" -> target.getTargetInstanceIdLabel,
            "reason" -> "NO_CONFIG"
          )
        )
        .toInt
    )
  }

  test("createAndStart() completes successfully") {
    // Test plan: create an Assigner with the default k8s watcher and health watcher. This test
    // is very basic, it only verifies that the method completes without throwing any exceptions.

    val assignerConf = new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.rpc.port" -> 0,
        "databricks.dicer.library.server.keystore" -> TestTLSOptions.serverKeystorePath,
        "databricks.dicer.library.server.truststore" -> TestTLSOptions.serverTruststorePath
      )
    )

    // Create a dynamic config provider. Specific configs are not important for this test.
    val staticTargetConfigMap: InternalTargetConfigMap = InternalTargetConfigMap.create(
      configScopeOpt = None,
      targetConfigMap = Map.empty
    )
    val dynamicConfigProvider: StaticTargetConfigProvider =
      StaticTargetConfigProvider.create(staticTargetConfigMap, assignerConf)
    dynamicConfigProvider.startBlocking(1.second)

    val assigner = Assigner.createAndStart(
      assignerConf,
      dynamicConfigProvider,
      UUID.randomUUID(),
      "localhost",
      ASSIGNER_CLUSTER_URI,
      KubernetesTargetWatcher.NoOpFactory
    )

    assigner.forTest.stopAsync()
  }

  test("Assigner differentiates targets by cluster URI") {
    // Test plan: Verify that targets with the same *name* but different cluster URIs are treated as
    // distinct targets by the Assigner. Verify this by creating 3 targets with the same name but
    // where the first lives in the Assigner's local cluster and the other 2 live in two different
    // data plane clusters, and check that they constitute 3 separate targets with 3 separate
    // assignments.
    //
    // TODO(<internal bug>): For the control plane slicelets, verify that they are considered part of the
    // control plane target regardless of whether they have a cluster URI set in their target
    // identifiers. Currently all Slicelets in control plane won't have cluster URI in production,
    // but once we eventually include ClusterURI information for Slicelets in control planes, we
    // need to also support control plane slicelets in stale versions which do not have WhereAmI
    // support, and thus are unable to populate the cluster URI in their target identifiers.

    // Setup: Create 2 slicelets in each cluster.
    val cpSlicelet1: Slicelet = withLocationConfSingleton(ASSIGNER_CLUSTER_LOCATION_CONF) {
      testEnv.createSlicelet(Target(getSafeName)).start(selfPort = 1234, listenerOpt = None)
    }
    val cpSlicelet2: Slicelet = withLocationConfSingleton(ASSIGNER_CLUSTER_LOCATION_CONF) {
      testEnv.createSlicelet(Target(getSafeName)).start(selfPort = 2345, listenerOpt = None)
    }
    val dp1Slicelet1: Slicelet = withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
      testEnv
        .createSlicelet(
          Target(getSafeName),
          initialAssignerIndex = 0,
          watchFromDataPlane = true
        )
        .start(selfPort = 3456, listenerOpt = None)
    }
    val dp1Slicelet2: Slicelet = withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
      testEnv
        .createSlicelet(
          Target(getSafeName),
          initialAssignerIndex = 0,
          watchFromDataPlane = true
        )
        .start(selfPort = 4567, listenerOpt = None)
    }
    val dp2Slicelet1: Slicelet = withLocationConfSingleton(DP2_CLUSTER_LOCATION_CONF) {
      testEnv
        .createSlicelet(
          Target(getSafeName),
          initialAssignerIndex = 0,
          watchFromDataPlane = true
        )
        .start(selfPort = 5678, listenerOpt = None)
    }
    val dp2Slicelet2: Slicelet = withLocationConfSingleton(DP2_CLUSTER_LOCATION_CONF) {
      testEnv
        .createSlicelet(
          Target(getSafeName),
          initialAssignerIndex = 0,
          watchFromDataPlane = true
        )
        .start(selfPort = 6789, listenerOpt = None)
    }

    // Sanity check: Verify that target cluster URIs have not been overwritten / populated by
    // WhereAmI support to different values. WhereAmI support is not expected to do this in tests,
    // but we have these sanity checks here anyway to ensure this doesn't break going forward.
    assert(cpSlicelet1.impl.target == Target(getSafeName))
    assert(cpSlicelet2.impl.target == Target(getSafeName))
    assert(dp1Slicelet1.impl.target == Target.createKubernetesTarget(DP1_CLUSTER_URI, getSafeName))
    assert(dp1Slicelet2.impl.target == Target.createKubernetesTarget(DP1_CLUSTER_URI, getSafeName))
    assert(dp2Slicelet1.impl.target == Target.createKubernetesTarget(DP2_CLUSTER_URI, getSafeName))
    assert(dp2Slicelet2.impl.target == Target.createKubernetesTarget(DP2_CLUSTER_URI, getSafeName))

    // Double sanity check: The watch requests show up at the Assigner with the expected target
    // identifiers.
    AssertionWaiter("Waiting for expected assignment").await {
      testAssigner
        .getLatestSliceletWatchRequest(Target(getSafeName), cpSlicelet1.impl.squid)
        .getOrElse(fail("No watch request received yet for cpSlicelet1"))
      testAssigner
        .getLatestSliceletWatchRequest(Target(getSafeName), cpSlicelet2.impl.squid)
        .getOrElse(fail("No watch request received yet for cpSlicelet2"))
      testAssigner
        .getLatestSliceletWatchRequest(
          Target.createKubernetesTarget(DP1_CLUSTER_URI, getSafeName),
          dp1Slicelet1.impl.squid
        )
        .getOrElse(fail("No watch request received yet for dp1Slicelet1"))
      testAssigner
        .getLatestSliceletWatchRequest(
          Target.createKubernetesTarget(DP1_CLUSTER_URI, getSafeName),
          dp1Slicelet2.impl.squid
        )
        .getOrElse(fail("No watch request received yet for dp1Slicelet2"))
      testAssigner
        .getLatestSliceletWatchRequest(
          Target.createKubernetesTarget(DP2_CLUSTER_URI, getSafeName),
          dp2Slicelet1.impl.squid
        )
        .getOrElse(fail("No watch request received yet for dp2Slicelet1"))
      testAssigner
        .getLatestSliceletWatchRequest(
          Target.createKubernetesTarget(DP2_CLUSTER_URI, getSafeName),
          dp2Slicelet2.impl.squid
        )
        .getOrElse(fail("No watch request received yet for dp2Slicelet2"))
    }

    // Verify: For each of the 3 targets, check that the assigner and the two slicelets for the
    // target eventually see the same assignment, and that the expected two slicelets are the
    // complete set of resources for that target (i.e. no other slicelets from other targets somehow
    // find their way into the assignment).
    for ((target, slicelet1, slicelet2) <- Vector(
        (Target(getSafeName), cpSlicelet1, cpSlicelet2),
        (Target.createKubernetesTarget(DP1_CLUSTER_URI, getSafeName), dp1Slicelet1, dp1Slicelet2),
        (Target.createKubernetesTarget(DP2_CLUSTER_URI, getSafeName), dp2Slicelet1, dp2Slicelet2)
      )) {
      AssertionWaiter("Waiting for expected assignment").await {
        val assignerAsn: Assignment =
          TestUtils
            .awaitResult(testAssigner.getAssignment(target), Duration.Inf)
            .getOrElse(fail("assigner has not yet generated an assignment"))
        val slicelet1Asn: Assignment =
          slicelet1.impl.forTest.getLatestAssignmentOpt
            .getOrElse(fail(s"${slicelet1.impl.squid} has not yet received an assignment"))
        val slicelet2Asn: Assignment =
          slicelet2.impl.forTest.getLatestAssignmentOpt
            .getOrElse(fail(s"${slicelet2.impl.squid} has not yet received an assignment"))
        // Verify: The assigner, slicelet1, and slicelet2 all had the same assignment.
        assert(assignerAsn == slicelet1Asn)
        assert(slicelet1Asn == slicelet2Asn)
        // Verify: slicelet1 and slicelet2 are the complete set of resources for this target.
        assert(
          assignerAsn.assignedResources == Set(
            slicelet1.impl.squid,
            slicelet2.impl.squid
          )
        )
      }
    }

    // Verify: Lastly, check that the assigner does not have an assignment for a local target
    // identifier that includes the cluster URI (since we expect the assigner to canonicalize such
    // identifiers to be cluster-URI-less).
    assert(
      TestUtils
        .awaitResult(
          testAssigner
            .getAssignment(Target.createKubernetesTarget(ASSIGNER_CLUSTER_URI, getSafeName)),
          Duration.Inf
        )
        .isEmpty
    )

    cpSlicelet1.forTest.stop()
    cpSlicelet2.forTest.stop()
    dp1Slicelet1.forTest.stop()
    dp1Slicelet2.forTest.stop()
    dp2Slicelet1.forTest.stop()
    dp2Slicelet2.forTest.stop()
  }

  test("Assigner differentiates all target types") {
    // Test plan: Verify that targets with the same *name* but are otherwise different are treated
    // as distinct targets by the Assigner. Verify this by creating 4 targets with the
    // same name but with different other fields (e.g., `cluster` for KubernetesTargets and
    // `instanceId` for AppTargets), and check that they constitute 4 separate targets
    // with 4 separate assignments as perceived by Slicelets and Clerks.

    // Setup: Create 4 different targets that all have the same name. The first two targets are
    // default Targets (i.e., KubernetesTargets) where the second has an explicit cluster. The
    // second two targets are AppTargets with different instance IDs.
    val targets: Seq[Target] = Seq(
      Target(name = "differentiate-target"),
      Target.createKubernetesTarget(cluster = DP1_CLUSTER_URI, name = "differentiate-target"),
      Target.createAppTarget(name = "differentiate-target", "instance-1"),
      Target.createAppTarget(name = "differentiate-target", "instance-2")
    )

    // Setup: Create two Slicelets for each target. For the second target, create the Slicelets in
    // the data-plane cluster corresponding to the Target's cluster.
    val slicelets: Seq[(Slicelet, Slicelet)] = Seq(
      (
        testEnv.createSlicelet(targets(0)).start(selfPort = 1234, listenerOpt = None),
        testEnv.createSlicelet(targets(0)).start(selfPort = 2345, listenerOpt = None)
      ),
      (withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
        testEnv
          .createSlicelet(targets(1), initialAssignerIndex = 0, watchFromDataPlane = true)
          .start(selfPort = 3456, listenerOpt = None)
      }, withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
        testEnv
          .createSlicelet(targets(1), initialAssignerIndex = 0, watchFromDataPlane = true)
          .start(selfPort = 4567, listenerOpt = None)
      }),
      (
        withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
          testEnv
            .createSlicelet(targets(2), initialAssignerIndex = 0, watchFromDataPlane = true)
            .start(selfPort = 5678, listenerOpt = None)
        },
        withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
          testEnv
            .createSlicelet(targets(2), initialAssignerIndex = 0, watchFromDataPlane = true)
            .start(selfPort = 6789, listenerOpt = None)
        }
      ),
      (
        withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
          testEnv
            .createSlicelet(targets(3), initialAssignerIndex = 0, watchFromDataPlane = true)
            .start(selfPort = 7891, listenerOpt = None)
        },
        withLocationConfSingleton(DP1_CLUSTER_LOCATION_CONF) {
          testEnv
            .createSlicelet(targets(3), initialAssignerIndex = 0, watchFromDataPlane = true)
            .start(selfPort = 8912, listenerOpt = None)
        }
      )
    )

    // Setup: Define a Clerk factory for each target.
    val clerkFactories: Seq[() => Clerk[ResourceAddress]] = Seq(
      // Classic CP Clerk -> CP Slicelet in the same cluster as the Assigner.
      () => testEnv.createClerk(slicelets(0)._1),
      // DP Clerk -> DP Slicelet in a different cluster from the Assigner.
      () => testEnv.createClerk(slicelets(1)._1),
      // DP Clerk -> Assigner - models a Clerk from DP API Proxy connecting to the Assigner.
      () => testEnv.createDirectClerk(targets(2), initialAssignerIndex = 0),
      // Same as previous Clerk factory for a different target.
      () => testEnv.createDirectClerk(targets(3), initialAssignerIndex = 0)
    )

    // Setup: Define the expected watch request handler locations for the clerk watches.
    val handlerLocations: Seq[SubscriberHandler.Location] = Seq(
      SubscriberHandler.Location.Slicelet,
      SubscriberHandler.Location.Slicelet,
      SubscriberHandler.Location.Assigner,
      SubscriberHandler.Location.Assigner
    )

    // For each target, verify that it has a distinct assignment.
    for (i <- 0 until targets.size) {
      val target: Target = targets(i)
      val (slicelet1, slicelet2): (Slicelet, Slicelet) = slicelets(i)

      // Setup: Create a metric change tracker for the watch requests received metric that is
      // tracked by the Slicelet.
      val numWatches: MetricUtils.ChangeTracker[Long] = MetricUtils.ChangeTracker[Long](
        () =>
          SubscriberHandlerMetricUtils.getNumWatchRequests(
            // By setting the handlerTarget and requestTarget to the same underlying target, we
            // filter the metric for observations where the SubscriberHandler at the Slicelet
            // considers the target in the request to match its target.
            handlerTarget = target,
            requestTarget = target,
            callerService = "unknown",
            metricsKey = MetricsKey(isClerk = true, LATEST_VERSION),
            handlerLocation = handlerLocations(i)
          )
      )

      // Setup: Create a Clerk after the ChangeTracker has been initialized with the initial value.
      val clerk: Clerk[ResourceAddress] = clerkFactories(i)()

      // Double sanity check: The watch requests show up at the Assigner with the expected target
      // identifiers.
      AssertionWaiter(s"Waiting for watch requests at assigner for $target").await {
        testAssigner
          .getLatestSliceletWatchRequest(
            target,
            slicelet1.impl.squid
          )
          .getOrElse(fail(s"No watch request received yet for $target at $slicelet1"))
        testAssigner
          .getLatestSliceletWatchRequest(
            target,
            slicelet2.impl.squid
          )
          .getOrElse(fail(s"No watch request received yet for $target at $slicelet2"))
      }

      // Verify: Check that the assigner, the two slicelets, and the clerk for the target eventually
      // see the same assignment, and that the expected two slicelets are the complete set of
      // resources for that target (i.e. no other slicelets from other targets somehow find their
      // way into the assignment).
      AssertionWaiter(s"Waiting for expected assignment for $target").await {
        val assignerAsn: Assignment =
          TestUtils
            .awaitResult(testAssigner.getAssignment(target), Duration.Inf)
            .getOrElse(fail("assigner has not yet generated an assignment"))
        val slicelet1Asn: Assignment =
          slicelet1.impl.forTest.getLatestAssignmentOpt
            .getOrElse(fail(s"${slicelet1.impl.squid} has not yet received an assignment"))
        val slicelet2Asn: Assignment =
          slicelet2.impl.forTest.getLatestAssignmentOpt
            .getOrElse(fail(s"${slicelet2.impl.squid} has not yet received an assignment"))
        val clerkAsn: Assignment =
          clerk.impl.forTest.getLatestAssignmentOpt
            .getOrElse(fail(s"$clerk has not yet received an assignment"))

        // Verify: The assigner, slicelet1, and slicelet2 all had the same assignment.
        assert(assignerAsn == slicelet1Asn)
        assert(slicelet1Asn == slicelet2Asn)
        assert(slicelet2Asn == clerkAsn)

        // Verify: slicelet1 and slicelet2 are the complete set of resources for this target.
        assert(
          assignerAsn.assignedResources == Set(
            slicelet1.impl.squid,
            slicelet2.impl.squid
          )
        )

        assert(numWatches.totalChange() > 0)
      }

      clerk.forTest.stop()
    }

    // Stop all the Slicelets.
    for (i <- 0 until targets.size) {
      val (slicelet1, slicelet2): (Slicelet, Slicelet) = slicelets(i)
      slicelet1.forTest.stop()
      slicelet2.forTest.stop()
    }
  }



}
