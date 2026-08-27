package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID
import java.util.Random

import scala.concurrent.duration._

import io.kubernetes.client.openapi.apis.CoreV1Api
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.{
  AssertionWaiter,
  EtcdClient,
  EtcdTestEnvironment,
  MetricUtils,
  SequentialExecutionContext,
  SequentialExecutionContextPool
}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.DEFAULT_CONFIG
import com.databricks.dicer.assigner.config.{
  InternalTargetConfigMap,
  StaticTargetConfigProvider,
  TargetMigrationConfig
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.Incarnation
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest

/**
 * End-to-end tests verifying the [[KubernetesMembershipChecker]] integration within a running
 * [[EtcdPreferredAssignerDriver]]. These tests use a [[FakeKubernetesServer]] to exercise the
 * full HTTP polling path without requiring a real Kubernetes cluster.
 */
class AssignerMembershipCheckerSuite extends DatabricksTest with TestName {

  /** URI of the kubernetes cluster where the Assigner will run. */
  private val ASSIGNER_CLUSTER_URI: URI = new URI(
    "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01"
  )

  /** The Prometheus registry to read metric values from. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  private val etcd: EtcdTestEnvironment = EtcdTestEnvironment.create()

  override def afterAll(): Unit = {
    try {
    } finally {
      etcd.close()
    }
  }

  override def beforeEach(): Unit = {
    etcd.deleteAll()
    etcd.initializeStore(
      EtcdClient.KeyNamespace(Assigner.PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX)
    )
  }

  /**
   * Creates a [[KubernetesMembershipChecker.Factory]] that builds a real checker backed by
   * the given [[FakeKubernetesServer]].
   */
  private def createTestFactory(
      fakeServer: FakeKubernetesServer,
      namespace: String,
      appName: String,
      pollingInterval: FiniteDuration,
      rpcPort: Int): KubernetesMembershipChecker.Factory = {
    new KubernetesMembershipChecker.Factory {
      override def create(assignerUuid: UUID): KubernetesMembershipChecker = {
        val checkerSec: SequentialExecutionContext =
          SequentialExecutionContext.createWithDedicatedPool(
            name = "membership-checker",
            alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
          )
        val coreV1Api: CoreV1Api = FakeKubernetesTestSupport.buildCoreV1Api(fakeServer)
        new KubernetesMembershipChecker(
          checkerSec,
          coreV1Api,
          assignerUuid,
          namespace,
          appName,
          pollingInterval,
          rpcPort,
          kubeContextLabelOpt = None
        )
      }
    }
  }

  /**
   * Creates a minimal [[DicerAssignerConf]] suitable for tests that only need to start the
   * Assigner's RPC server.
   */
  private def createMinimalAssignerConf(): DicerAssignerConf = {
    new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.rpc.port" -> 0,
        "databricks.dicer.library.server.keystore" -> TestTLSOptions.serverKeystorePath,
        "databricks.dicer.library.server.truststore" -> TestTLSOptions.serverTruststorePath
      )
    )
  }

  /**
   * Creates and starts an [[Assigner]] via [[Assigner.BaseForTest]] with the given factory
   * injected into an [[EtcdPreferredAssignerDriver]]. Uses a unique DPage namespace (derived
   * from the UUID) to avoid conflicts with other tests.
   */
  private def createAndStartAssigner(
      assignerConf: DicerAssignerConf,
      uuid: UUID,
      factory: KubernetesMembershipChecker.Factory): Assigner = {
    val staticTargetConfigMap: InternalTargetConfigMap = InternalTargetConfigMap.create(
      configScopeOpt = None,
      targetConfigMap = Map.empty
    )
    val dynamicConfigProvider: StaticTargetConfigProvider =
      StaticTargetConfigProvider.createBlocking(
        staticTargetConfigMap,
        assignerConf,
        initialPollTimeout = 1.second
      )

    val assignerSecPool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(
        poolName = "Assigner-e2e",
        numThreads = 8,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val assignerSec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        name = "assigner-e2e-main",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val membershipChecker: KubernetesMembershipChecker = factory.create(uuid)

    val driverSec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        name = "test-pa-driver",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val store: InterposingEtcdPreferredAssignerStore = InterposingEtcdPreferredAssignerStore.create(
      driverSec,
      Incarnation(2L),
      etcd,
      EtcdClient.Config(
        EtcdClient.KeyNamespace(Assigner.PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX)
      ),
      new Random(),
      DEFAULT_CONFIG
    )
    val etcdDriver: EtcdPreferredAssignerDriver = new EtcdPreferredAssignerDriver(
      driverSec,
      None,
      store,
      EtcdPreferredAssignerDriver.Config()
    )
    val chDriver: ConsistentHashingPreferredAssignerDriver =
      new ConsistentHashingPreferredAssignerDriver(driverSec, membershipChecker)
    val migrationDriver: MigrationPreferredAssignerDriver = new MigrationPreferredAssignerDriver(
      driverSec,
      migrationMode = MigrationMode.ShadowMode,
      oldDriver = etcdDriver,
      newDriver = chDriver
    )

    val assigner = new Assigner.BaseForTest(
      assignerSecPool,
      assignerSec,
      assignerConf,
      migrationDriver,
      Assigner.createStoreFactory(assignerConf),
      KubernetesTargetWatcher.NoOpFactory,
      HealthWatcher.DefaultFactory,
      dynamicConfigProvider,
      uuid,
      "localhost",
      ASSIGNER_CLUSTER_URI,
      Assigner.MIN_ASSIGNMENT_GENERATION_INTERVAL,
      dPageNamespaceOpt = None,
      targetMigrator = new FakeTargetMigrator(
        TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION)
      ),
      localClusterMembershipChecker = membershipChecker,
      assignerServiceInfoOpt = None
    ) {
      def startForTest(): Unit = start()
    }
    assigner.startForTest()
    assigner
  }

  test("Assigner with enabled membership checker initializes and polls") {
    // Test plan: Verify that when a membership checker factory backed by a FakeKubernetesServer is
    // provided, the Assigner creates and starts the checker. Verify that at least 10 polls complete
    // successfully, confirming the checker is actively running.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val pollingInterval: FiniteDuration = 100.milliseconds

    val fakeServerSec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        name = "fake-k8s-server-e2e",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val fakeServer: FakeKubernetesServer = FakeKubernetesServer.createAndStart(fakeServerSec)

    fakeServer.setPods(namespace, appName, Some(List.empty))

    val testFactory: KubernetesMembershipChecker.Factory =
      createTestFactory(fakeServer, namespace, appName, pollingInterval, rpcPort = 24500)
    val assignerConf: DicerAssignerConf = createMinimalAssignerConf()
    val assigner: Assigner =
      createAndStartAssigner(assignerConf, UUID.randomUUID(), testFactory)

    // Wait for the checker to complete at least 10 polls, confirming it is actively running.
    AssertionWaiter("checker initialized and polling").await {
      val latencyCount: Int = MetricUtils.getHistogramCount(
        registry,
        "dicer_assigner_k8s_list_pods_latency_millis",
        Map("namespace" -> namespace, "appName" -> appName)
      )
      assert(latencyCount >= 10)
    }

    // Best-effort cleanup of the gRPC server, SEC pools, and fake server port.
    assigner.forTest.stopAsync()
    fakeServer.stop()
  }

  test("createAndStart fails when the membership checker factory fails to build a checker") {
    // Test plan: the membership checker is a required dependency, so the Assigner must fail startup
    // when the factory cannot build one. Drive createAndStart with a factory that throws (as the
    // production DefaultFactory does when the K8s in-cluster config is unavailable or NAMESPACE /
    // APP_NAME are unset) and confirm the failure propagates out of createAndStart, before the
    // server is started.
    val failingFactory: KubernetesMembershipChecker.Factory =
      new KubernetesMembershipChecker.Factory {
        override def create(assignerUuid: UUID): KubernetesMembershipChecker =
          throw new IllegalStateException("simulated membership checker creation failure")
      }
    val assignerConf: DicerAssignerConf = createMinimalAssignerConf()
    val staticTargetConfigMap: InternalTargetConfigMap = InternalTargetConfigMap.create(
      configScopeOpt = None,
      targetConfigMap = Map.empty
    )
    val configProvider: StaticTargetConfigProvider =
      StaticTargetConfigProvider.createBlocking(
        staticTargetConfigMap,
        assignerConf,
        initialPollTimeout = 1.second
      )

    assertThrows[IllegalStateException] {
      Assigner.createAndStart(
        assignerConf,
        configProvider,
        UUID.randomUUID(),
        hostName = "localhost",
        ASSIGNER_CLUSTER_URI,
        KubernetesTargetWatcher.NoOpFactory,
        localClusterMembershipCheckerFactory = failingFactory,
        remoteClusterMembershipCheckerFactoryOpt = None,
        assignerServiceInfoOpt = None
      )
    }
  }
}
