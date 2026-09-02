package com.databricks.dicer.assigner

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
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import io.prometheus.client.CollectorRegistry
import com.databricks.dicer.assigner.AssignerMainSuite.BASE_CONFIG
import com.databricks.dicer.assigner.PreferredAssignerValue.SomeAssigner
import com.databricks.rpc.DatabricksObjectMapper
class AssignerMainSuite extends DatabricksTest with TestName {
  val etcd: EtcdTestEnvironment = EtcdTestEnvironment.create()

  /**
   * Tracks the Assigner started by [[startAssignerService]] so that [[afterEach]] can stop it
   * and clear DPage state. Only one Assigner per test is supported.
   */
  private var assignerOpt: Option[Assigner] = None

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
  }

  /**
   * Starts an Assigner via [[AssignerMain]] and stores it in [[assignerOpt]] so that [[afterEach]]
   * can stop it cleanly. Only supports one Assigner per test — calling this twice in the same test
   * will overwrite the previous reference, leaking the first Assigner.
   */
  private def startAssignerService(
      conf: DicerAssignerConf,
      localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory,
      remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory]
  ): Unit = {
    AssignerMain.staticForTest.wrappedMainInternalWithCheckerFactory(
      conf,
      localClusterMembershipCheckerFactory,
      remoteClusterMembershipCheckerFactoryOpt
    ) match {
      case Left(assigner: Assigner) => assignerOpt = Some(assigner)
      case Right(statusCode: Int) => fail(s"Expected Assigner, got exit code: $statusCode")
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
        .wrappedMainInternalWithCheckerFactory(
          conf,
          localClusterMembershipCheckerFactory =
            FakeKubernetesTestSupport.inertMembershipCheckerFactory,
          remoteClusterMembershipCheckerFactoryOpt = None
        )
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
        .wrappedMainInternalWithCheckerFactory(
          conf,
          localClusterMembershipCheckerFactory =
            FakeKubernetesTestSupport.inertMembershipCheckerFactory,
          remoteClusterMembershipCheckerFactoryOpt = None
        )
    assert(result == Right(EtcdBootstrapper.ExitCode.RETRYABLE_FAILURE.value))
  }

}

object AssignerMainSuite {

  /**
   * Base config used in all tests that actually start the Assigner. The RPC port is set to 0 so the
   * Assigner server binds to an ephemeral port, avoiding port conflicts with concurrent suites.
   */
  private[assigner] val BASE_CONFIG: Config =
    Configs.parseMap(Map("databricks.dicer.assigner.rpc.port" -> 0))
}
