package com.databricks.dicer.assigner

import com.databricks.dicer.assigner.testing.KubernetesTestUtils
import com.databricks.dicer.assigner.testing.FakeKubernetesServer

import com.databricks.dicer.common.Generation
import com.databricks.conf.{Config, Configs, RichConfig}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.EtcdBootstrapper
import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.{EtcdTestEnvironment, EtcdClient, EtcdKeyValueMapper}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.rpc.DatabricksServerWrapper
import com.databricks.testing.DatabricksTest
import com.databricks.rpc.SslArguments
import com.databricks.rpc.testing.TestSslArguments
import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.caching.util.{
  AssertionWaiter,
  CachingErrorCode,
  MetricUtils,
  ServerTestUtils,
  Severity
}
import com.databricks.caching.util.TestUtils.TestName
import java.time.OffsetDateTime
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import io.kubernetes.client.openapi.ApiClient
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.client.TestClientUtils
import com.databricks.dicer.external.{Slicelet, Target}
import com.databricks.dicer.assigner.AssignerMainSuite.BASE_CONFIG
import com.databricks.dicer.assigner.PreferredAssignerValue.SomeAssigner
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.caching.util.TestUtils
import com.databricks.dicer.common.{AssignerServiceInfo, Assignment}
import com.databricks.dicer.common.testing.{AppIdentifierTestUtils}

class AssignerMainSuite extends DatabricksTest with TestName {
  val etcd: EtcdTestEnvironment = EtcdTestEnvironment.create()

  private val fakeServerSec: SequentialExecutionContext =
    SequentialExecutionContext.createWithDedicatedPool(
      name = "fake-k8s-assigner-main",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
  private val fakeServer: FakeKubernetesServer = FakeKubernetesServer.createAndStart(fakeServerSec)
  private val localClusterApiClientFactory: KubernetesApiClientFactory =
    new KubernetesApiClientFactory {
      override def create(): ApiClient = KubernetesTestUtils.buildApiClient(fakeServer)
    }

  private val KUBERNETES_NAMESPACE: String = sys.env("NAMESPACE")
  private val KUBERNETES_APP_NAME: String = sys.env("APP_NAME")

  /** A [[LocationConf]] that includes cluster location. */
  private val LOCATION_CONFIG_WITH_CLUSTER_LOCATION: LocationConf =
    LocationConfTestUtils.newTestLocationConfig(
      envMap = Map(
        "LOCATION" -> DatabricksObjectMapper.toJson(
          Map(
            "cloud_provider" -> "AWS",
            "cloud_provider_region" -> "AWS_US_WEST_2",
            "environment" -> "DEV",
            "kubernetes_cluster_type" -> "GENERAL",
            "kubernetes_cluster_uri" -> "kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01",
            "region_uri" -> "region:dev/cloud1/public/region1",
            "regulatory_domain" -> "PUBLIC"
          )
        )
      )
    )

  override def beforeEach(): Unit = {
    etcd.deleteAll()
    fakeServer.setPods(KUBERNETES_NAMESPACE, KUBERNETES_APP_NAME, Some(List.empty))
  }

  override def afterEach(): Unit = {
    try {
      AppIdentifierTestUtils.clearForTest()
    } finally {
      super.afterEach()
    }
  }

  /** Runs a service-mode Assigner for the duration of `body`. */
  private def runAssigner(
      conf: DicerAssignerConf,
      localClusterApiClientFactory: KubernetesApiClientFactory)(body: Assigner => Unit): Unit = {
    val assigner: Assigner = withLocationConfSingleton(LOCATION_CONFIG_WITH_CLUSTER_LOCATION) {
      AssignerMain.staticForTest.wrappedMainInternal(conf, localClusterApiClientFactory) match {
        case Left(assigner: Assigner) => assigner
        case Right(statusCode: Int) => fail(s"Expected Assigner, got exit code: $statusCode")
      }
    }
    try body(assigner)
    finally {
      // BASE_CONFIG uses one fixed RPC port for the suite. Stop this Assigner before the next test
      // or its Assigner will fail to bind that port with "Address already in use".
      Await.result(assigner.forTest.stopAsync(), 10.seconds)
    }
  }

  test("etcd bootstrapper initializes the preferred-assigner namespace") {
    // Test plan: Verify that running AssignerMain in etcd_bootstrapper mode initializes the
    // preferred-assigner EtcdClient namespace with its corresponding incarnation.
    val conf = new DicerAssignerConf(
      Configs.parseMap(
        Map(
          "databricks.dicer.assigner.executionMode" -> "etcd_bootstrapper",
          "databricks.dicer.assigner.preferredAssigner.storeIncarnation" -> 42,
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.preferredAssigner.etcd.endpoints" ->
          DatabricksObjectMapper.toJson(
            Seq(etcd.endpoint)
          )
        )
      )
    )

    val result: Either[Assigner, Int] =
      AssignerMain.staticForTest
        .wrappedMainInternal(conf, KubernetesApiClientFactory.localCluster())
    assert(result == Right(EtcdBootstrapper.ExitCode.SUCCESS.value))

    assert(
      etcd
        .getKey(
          EtcdKeyValueMapper.ForTest
            .getVersionHighWatermarkKeyString(Assigner.getPreferredAssignerEtcdNamespace(conf))
        )
        .contains(
          EtcdKeyValueMapper.ForTest
            .toVersionValueString(EtcdClient.Version(42, UnixTimeVersion.MIN))
        )
    )
  }

  test("etcd bootstrapper exits with error when initialization fails") {
    // Test plan: verify that running AssignerMain in etcd_bootstrapper mode exits with an error
    // when the attempt to bootstrap fails. Simulate this by creating an unresponsive server so that
    // the writes time out.
    val unresponsiveServer: DatabricksServerWrapper =
      ServerTestUtils.createUnresponsiveServer(port = 0)
    val address: String = s"http://localhost:${unresponsiveServer.activePort()}"
    val conf = new DicerAssignerConf(
      Configs.parseMap(
        Map(
          "databricks.dicer.assigner.executionMode" -> "etcd_bootstrapper",
          "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.preferredAssigner.etcd.endpoints" ->
          DatabricksObjectMapper.toJson(
            Seq(address)
          )
        )
      )
    )

    val result: Either[Assigner, Int] =
      AssignerMain.staticForTest
        .wrappedMainInternal(conf, KubernetesApiClientFactory.localCluster())
    assert(result == Right(EtcdBootstrapper.ExitCode.RETRYABLE_FAILURE.value))
  }

  /**
   * One app-identifier case for the Assigner service-info grid test.
   *
   * @param name The app name to configure, or the empty string to leave the process without an app
   *             identifier.
   * @param instanceId The app instance id to configure, or the empty string to leave the process
   *                   without an app identifier.
   * @param expectedAssignerServiceInfoOpt The service info expected on generated assignments, or
   *                                       [[None]] when the identifier is absent or invalid.
   * @param expectedServiceInfoStatus The status expected on the recorded service info status
   *                                  metric.
   */
  private case class AppIdentifierServiceInfoCase(
      name: String,
      instanceId: String,
      expectedAssignerServiceInfoOpt: Option[AssignerServiceInfo],
      expectedServiceInfoStatus: String)

  namedGridTest(
    "Assigner-generated assignments carry service info resolved from the process app identifier"
  )(
    Seq(
      // Valid app identifier name and instance id become AssignerServiceInfo on the generated
      // assignment.
      "valid app identifier" -> AppIdentifierServiceInfoCase(
        name = "test-assigner",
        instanceId = "test-instance",
        expectedAssignerServiceInfoOpt =
          Some(AssignerServiceInfo(name = "test-assigner", instanceId = "test-instance")),
        expectedServiceInfoStatus = "valid"
      ),
      // Uppercase and '_' are rejected by AppIdentifier constraints, so the Assigner starts with
      // no service info and generated assignments carry no service info.
      "invalid app identifier" -> AppIdentifierServiceInfoCase(
        name = "Invalid_Name",
        instanceId = "Invalid_Instance",
        expectedAssignerServiceInfoOpt = None,
        expectedServiceInfoStatus = "invalid"
      ),
      // No app identifier at all, so the Assigner starts with no service info and generated
      // assignments carry no service info.
      "absent app identifier" -> AppIdentifierServiceInfoCase(
        name = "",
        instanceId = "",
        expectedAssignerServiceInfoOpt = None,
        expectedServiceInfoStatus = "absent"
      )
    )
  ) { testCase: AppIdentifierServiceInfoCase =>
    // Test plan: Verify that AssignerMain wires service info from the process app identifier into
    // the Assigner it starts. Do this by configuring an app identifier, starting the Assigner,
    // asserting the service info status counter recorded the expected status once, then connecting
    // a real Slicelet and asserting the assignment it receives carries the expected service info.

    // Track the counter with a `ChangeTracker` as metrics are global and we want to assert on the
    // metrics emitted by this test.
    val serviceInfoStatusCount: MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker { () =>
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          metric = "dicer_assigner_service_info_status_total",
          labels = Map(
            "status" -> testCase.expectedServiceInfoStatus,
            "name" -> testCase.expectedAssignerServiceInfoOpt.map(_.name).getOrElse(""),
            "instanceId" -> testCase.expectedAssignerServiceInfoOpt.map(_.instanceId).getOrElse("")
          )
        )
      }

    // Basic assigner conf to allow the Assigner to generate assignments for unknown targets and
    // minimize health report delay for test speed.
    val conf: DicerAssignerConf = new DicerAssignerConf(
      BASE_CONFIG.merge(
        Configs.parseMap(
          Map(
            "databricks.dicer.assigner.allowDefaultTargetConfigForExperimentalTargets" -> true,
            "databricks.dicer.assigner.initialHealthReportDelayPeriodSeconds" -> 1
          )
        )
      )
    ) {
      override lazy val sslArgs: SslArguments = TestSslArguments.serverSslArgs
    }

    // Only configure the app identifier if name or instance id is non-empty because no app
    // identifier is not the same as empty strings for the app identifier.
    if (testCase.name.nonEmpty || testCase.instanceId.nonEmpty) {
      AppIdentifierTestUtils.configureForTest(testCase.name, testCase.instanceId)
    }

    runAssigner(conf, localClusterApiClientFactory) { assigner: Assigner =>
      // Verify the service info status counter recorded the status matching whether service info is
      // present or not exactly once for this Assigner startup.
      assertResult(1.0)(serviceInfoStatusCount.totalChange())

      // Clear the app identifier before creating the Slicelet because if the app identifier is
      // invalid, the Slicelet will throw an exception. This is because there is static conf for
      // the RPC framework that depends on a valid/empty app identifier.
      AppIdentifierTestUtils.clearForTest()

      // Bound the wait so an Assigner that never publishes its info fails this test rather than
      // hanging until the Bazel test timeout.
      val assignerPort: Int =
        TestUtils.awaitResult(assigner.getAssignerInfo, 10.seconds).uri.getPort
      val slicelet: Slicelet = TestClientUtils.createSlicelet(
        assignerPort,
        Target(getSafeName),
        sliceletHost = "localhost",
        clientTlsFilePathsOpt = None,
        serverTlsFilePathsOpt = None,
        watchFromDataPlane = false
      )
      slicelet.start(selfPort = 0, listenerOpt = None)

      try {
        // Wait for the Slicelet to receive an assignment because watch returns only a generation
        // until the Assigner has generated an assignment. Then verify that the assignment carries
        // the expected Assigner service info.
        AssertionWaiter("slicelet receives assignment carrying expected Assigner service info")
          .await {
            val assignment: Assignment = slicelet.impl.forTest.getLatestAssignmentOpt
              .getOrElse(fail(s"${slicelet.impl.squid} has not yet received an assignment"))
            assertResult(testCase.expectedAssignerServiceInfoOpt)(assignment.assignerServiceInfoOpt)
          }
      } finally {
        slicelet.forTest.stop()
      }
    }
  }
}

object AssignerMainSuite {

  /** Base config used in all tests that actually start the Assigner. */
  private[assigner] val BASE_CONFIG: Config =
    Configs.parseMap(Map("databricks.dicer.assigner.rpc.port" -> 0))
}
