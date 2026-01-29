package com.databricks.dicer.assigner

import com.databricks.dicer.common.Generation
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.EtcdBootstrapper
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
import java.util.UUID

import com.databricks.dicer.assigner.PreferredAssignerValue.SomeAssigner
import com.databricks.rpc.DatabricksObjectMapper

class AssignerMainSuite extends DatabricksTest {
  val etcd: EtcdTestEnvironment = EtcdTestEnvironment.create()

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

  test("etcd bootstrapper initializes all namespaces for the Assigner") {
    // Test plan: verify that running AssignerMain in etcd_bootstrapper mode initializes the
    // preferred assigner and durable assignments EtcdClient namespaces with their corresponding
    // incarnations.
    val conf = new DicerAssignerConf(
      Configs.parseMap(
        Map(
          "databricks.dicer.assigner.executionMode" -> "etcd_bootstrapper",
          "databricks.dicer.assigner.storeIncarnation" -> 43,
          "databricks.dicer.assigner.preferredAssigner.storeIncarnation" -> 42,
          "databricks.dicer.assigner.store.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.store.etcd.endpoints" -> DatabricksObjectMapper.toJson(
            Seq(etcd.endpoint)
          )
        )
      )
    )

    val statusCodeOpt: Option[Int] = AssignerMain.staticForTest.wrappedMainInternal(conf)
    assert(statusCodeOpt.contains(EtcdBootstrapper.ExitCode.SUCCESS.id))

    assert(
      etcd
        .getKey(
          EtcdKeyValueMapper.ForTest
            .getVersionHighWatermarkKeyString(Assigner.getAssignmentsEtcdNamespace(conf))
        )
        .contains(
          EtcdKeyValueMapper.ForTest
            .toVersionValueString(EtcdClient.Version(43, UnixTimeVersion.MIN))
        )
    )
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
          "databricks.dicer.assigner.store.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.store.etcd.endpoints" -> DatabricksObjectMapper.toJson(
            Seq(address)
          )
        )
      )
    )

    val statusCodeOpt: Option[Int] = AssignerMain.staticForTest.wrappedMainInternal(conf)
    assert(statusCodeOpt.contains(EtcdBootstrapper.ExitCode.RETRYABLE_FAILURE.id))
  }

  test("Assigner sources POD_UID and POD_IP environment variables correctly") {
    // Test plan: Verify that the Assigner sources the POD_UID and POD_IP environment variables
    // correctly for configuring its identify in the context of preferred assignership. Verify this
    // by invoking wrappedMainInternal with a configuration that enables preferred assigner and
    // checking that the assigner ultimately writes itself as the preferred assigner with the
    // expected UUID and IP address. Note that POD_UID and POD_IP have been set to well known values
    // by the `cross_databricks_test` rule that runs this test suite.
    val conf = new DicerAssignerConf(
      Configs.parseMap(
        Map(
          "databricks.dicer.assigner.preferredAssigner.modeEnabled" -> true,
          "databricks.dicer.assigner.preferredAssigner.storeIncarnation" -> 42,
          "databricks.dicer.assigner.store.etcd.sslEnabled" -> false,
          "databricks.dicer.assigner.store.etcd.endpoints" -> DatabricksObjectMapper.toJson(
            Seq(etcd.endpoint)
          ),
          "databricks.dicer.assigner.rpc.port" -> 0
        )
      )
    ) {
      // Needed so that the Assigner doesn't throw an exception when trying to start the watch
      // server and thus fail this test.
      override lazy val sslArgs: SslArguments = TestSslArguments.serverSslArgs
    }
    etcd.initializeStore(Assigner.getPreferredAssignerEtcdNamespace(conf))

    withLocationConfSingleton(LOCATION_CONFIG_WITH_CLUSTER_LOCATION) {
      AssignerMain.staticForTest.wrappedMainInternal(conf)
    }

    val paStore: EtcdPreferredAssignerStore = Assigner.createPreferredAssignerStore(conf)
    AssertionWaiter("Waiting for expected preferred assigner value").await {
      paStore.getPreferredAssignerWatchCell.getLatestValueOpt
        .getOrElse(fail("No preferred assigner value written to store yet")) match {
        case SomeAssigner(assignerInfo: AssignerInfo, _: Generation) =>
          assert(assignerInfo.uuid == UUID.fromString("67a738a6-0f47-49ab-97db-b3e9858f196f"))
          // We don't know what port the assigner started on, so we just check that the host matches
          // expectations.
          assert(assignerInfo.uri.getHost == "192.168.0.1")
        case _ => fail("Expected preferred assigner value with UUID and IP.")
      }
    }
  }

}
