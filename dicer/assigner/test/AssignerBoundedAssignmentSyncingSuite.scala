package com.databricks.dicer.assigner

import java.net.URI
import java.util.concurrent.TimeUnit.NANOSECONDS

import scala.concurrent.Future
import scala.concurrent.duration._

import io.grpc.Deadline

import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc.AssignmentServiceStub
import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{FakeSequentialExecutionContextPool, FakeTypedClock, TestUtils}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.client.WatchStubManager
import com.databricks.dicer.common.{
  ClerkData,
  ClientRequest,
  ClientResponse,
  Generation,
  Incarnation,
  InternalDicerTestEnvironment,
  SyncAssignmentState,
  TestAssigner
}
import com.databricks.dicer.external.Target
import com.databricks.rpc.testing.TestSslArguments
import com.databricks.rpc.tls.TLSOptionsMigration
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.assigner.AssignerBoundedAssignmentSyncingSuite.ASSIGNER_CLUSTER_URI

/**
 * Integration tests verifying that the Assigner bounds the number of Slicelets that sync their
 * assignment back when the Assigner has no assignment. These tests are separated from
 * [[AssignerSuite]] because these tests need bounded assignment syncing enabled and a fake clock.
 */
class AssignerBoundedAssignmentSyncingSuite extends DatabricksTest with TestName {

  /** Test Assigner config that enables bounded assignment syncing. */
  private val testBoundedAssignerConf: TestAssigner.Config = TestAssigner.Config.create(
    assignerConf = new DicerAssignerConf(
      Configs.parseString(
        // Same as value set in `dicer/production/assigner/deploy/conf/service-conf.jsonnet`.
        "databricks.dicer.assigner.maxClientsPromptedForAssignmentRecovery = 5"
      )
    )
  )

  /** Test Assigner config that disables bounded assignment syncing. */
  private val testUnboundedAssignerConf: TestAssigner.Config = TestAssigner.Config.create(
    assignerConf = new DicerAssignerConf(
      Configs.parseString(
        "databricks.dicer.assigner.maxClientsPromptedForAssignmentRecovery = 0"
      )
    )
  )

  /** Fake clock backing the test environment's SEC pool. */
  private val fakeClock = new FakeTypedClock()

  /** Shared test environment, backed by a SEC pool with a fake clock. */
  private val testEnv =
    InternalDicerTestEnvironment.create(
      config = testBoundedAssignerConf,
      assignerClusterUri = ASSIGNER_CLUSTER_URI,
      numAssigners = 0,
      secPool = FakeSequentialExecutionContextPool.create(
        "boundedAssignmentSyncingTestPool",
        numThreads = 4,
        fakeClock
      )
    )

  /** Creates a watch stub for `target` pointed at `assigner`. */
  private def createStub(assigner: TestAssigner, target: Target): AssignmentServiceStub = {
    new WatchStubManager(
      clientName = Project.DicerAssigner.name,
      defaultWatchAddress = URI.create(s"http://localhost:${assigner.localUri.getPort}"),
      tlsOptionsOpt = TLSOptionsMigration.convert(TestSslArguments.clientSslArgs),
      watchFromDataPlane = false
    ).createWatchStub(redirectAddressOpt = None, target = target, clientIdOpt = None)
  }

  test("New Assigner prompts a Client to sync its assignment back when bounding is enabled") {
    // Test plan: Verify that on receiving a non-empty watch request, a new Assigner with bounding
    // enabled replies with an empty generation.

    val target = Target(getSafeName)

    val (boundedAssigner, _): (TestAssigner, Int) = testEnv.addAssigner(testBoundedAssignerConf)
    // Block boundedAssigner's assignment writes so its assignment cell stays empty.
    TestUtils.awaitResult(boundedAssigner.blockAssignment(target), Duration.Inf)
    val boundedStub: AssignmentServiceStub = createStub(boundedAssigner, target)

    // A non-empty request. The generation is constructed with arbitrary values.
    val request = ClientRequest(
      target = target,
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation(Incarnation(3), 42)),
      subscriberDebugName = "gen-42-client",
      timeout = 1.minute,
      subscriberData = ClerkData,
      supportsSerializedAssignment = true,
      redirectTokenOpt = None,
      alternativeTargetOpt = None,
      clusterUriOpt = None,
      regionUriOpt = None
    )

    val boundedResponseFut: Future[ClientResponseP] = boundedStub
      .withDeadline(Deadline.after(1.minute.toNanos, NANOSECONDS))
      .watch(request.toProto)
    // Verify: A non-empty request is eagerly prompted (replied with an empty generation).
    val boundedResponse: ClientResponse =
      ClientResponse.fromProto(TestUtils.awaitResult(boundedResponseFut, Duration.Inf))
    assert(boundedResponse.syncState == SyncAssignmentState.KnownGeneration(Generation.EMPTY))

    testEnv.clear()
  }

  test("New Assigner parks a Client's request when bounding is disabled") {
    // Test plan: Verify that on receiving a non-empty watch request, a new Assigner with bounding
    // disabled parks the request, rather than prompting the Client to sync back.

    val target = Target(getSafeName)

    val (unboundedAssigner, _): (TestAssigner, Int) = testEnv.addAssigner(testUnboundedAssignerConf)
    // Block unboundedAssigner's assignment writes so its assignment cell stays empty.
    TestUtils.awaitResult(unboundedAssigner.blockAssignment(target), Duration.Inf)
    val unboundedStub: AssignmentServiceStub = createStub(unboundedAssigner, target)

    // A non-empty request. The generation is constructed with arbitrary values.
    val request = ClientRequest(
      target = target,
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation(Incarnation(3), 42)),
      subscriberDebugName = "gen-42-client",
      timeout = 1.minute,
      subscriberData = ClerkData,
      supportsSerializedAssignment = true,
      redirectTokenOpt = None,
      alternativeTargetOpt = None,
      clusterUriOpt = None,
      regionUriOpt = None
    )

    val unboundedResponseFut: Future[ClientResponseP] = unboundedStub
      .withDeadline(Deadline.after(1.minute.toNanos, NANOSECONDS))
      .watch(request.toProto)
    // Verify: A non-empty request is parked. We add a short delay here to lower the chance that the
    // watch request is still in-flight, thus decreasing the odds of a false positive.
    TestUtils.shamefullyAwait200msForNonEventInAsyncTest()
    assert(!unboundedResponseFut.isCompleted)

    testEnv.clear()
  }
}

object AssignerBoundedAssignmentSyncingSuite {

  /** URI of the kubernetes cluster where the Assigner will run. */
  private val ASSIGNER_CLUSTER_URI: URI = new URI(
    "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01"
  )
}
