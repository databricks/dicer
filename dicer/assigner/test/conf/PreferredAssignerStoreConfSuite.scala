package com.databricks.dicer.assigner.conf

import com.databricks.conf.Configs
import com.databricks.dicer.assigner.{
  Assigner,
  DisabledPreferredAssignerDriver,
  EtcdPreferredAssignerDriver
}
import com.databricks.dicer.common.Incarnation
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.testing.DatabricksTest

class PreferredAssignerStoreConfSuite extends DatabricksTest {

  // A list of etcd endpoints to use for testing.
  private val ETCD_ENDPOINTS: Seq[String] = Seq(
    "http://dicer-etcd-service.test-env-test.svc.cluster.local:2379"
  )

  private val STORE_INCARNATION: Incarnation = Incarnation(4)

  private def generateConfigMap(
      preferredAssignerEnabled: Boolean,
      preferredAssignerStoreIncarnation: Incarnation,
      etcdEndpoints: Seq[String]): Map[String, Any] = {
    val endpointsConfigString: String = DatabricksObjectMapper.toJson(etcdEndpoints)

    Map(
      "databricks.dicer.assigner.preferredAssigner.modeEnabled" ->
      preferredAssignerEnabled,
      "databricks.dicer.assigner.preferredAssigner.storeIncarnation" ->
      preferredAssignerStoreIncarnation.value,
      "databricks.dicer.assigner.preferredAssigner.etcd.endpoints" ->
      endpointsConfigString
    )
  }

  test("Cannot create a PA store when the PA mode is disabled") {
    // Test plan: verify the preferred assigner store throws an exception when the preferred
    // assigner mode is disabled.
    assertThrows[IllegalArgumentException] {
      val confMap: Map[String, Any] = generateConfigMap(
        preferredAssignerEnabled = false,
        preferredAssignerStoreIncarnation = STORE_INCARNATION,
        etcdEndpoints = ETCD_ENDPOINTS
      )
      val assignerConf = new DicerAssignerConf(Configs.parseMap(confMap))
      Assigner.createPreferredAssignerStore(assignerConf)
    }
  }

  test("Cannot create a PA store when etcd endpoints are empty") {
    // Test plan: verify the preferred assigner store throws an exception when the preferred
    // assigner mode is enabled but etcd endpoints are empty.
    assertThrows[IllegalArgumentException] {
      val configMap: Map[String, Any] = generateConfigMap(
        preferredAssignerEnabled = true,
        preferredAssignerStoreIncarnation = STORE_INCARNATION,
        etcdEndpoints = Seq.empty
      )
      val assignerConf = new DicerAssignerConf(Configs.parseMap(configMap))
      Assigner.createPreferredAssignerStore(assignerConf)
    }
  }

  test("Initialize PA store when PA mode is enabled") {
    // Test plan: verify that when the preferred assigner mode is enabled and etcd endpoints are
    // non-empty, an `EtcdPreferredAssignerStore` instance can be created successfully.
    val confString: Map[String, Any] = generateConfigMap(
      preferredAssignerEnabled = true,
      preferredAssignerStoreIncarnation = STORE_INCARNATION,
      etcdEndpoints = ETCD_ENDPOINTS
    )
    val assignerConf = new DicerAssignerConf(Configs.parseMap(confString))
    Assigner.createPreferredAssignerStore(assignerConf)
  }

  test("createPreferredAssignerDriver selects the driver by PA mode") {
    // Test plan: verify enabled PA mode creates an EtcdPreferredAssignerDriver and disabled PA mode
    // creates a DisabledPreferredAssignerDriver.
    val enabledConf = new DicerAssignerConf(
      Configs.parseMap(
        generateConfigMap(
          preferredAssignerEnabled = true,
          preferredAssignerStoreIncarnation = STORE_INCARNATION,
          etcdEndpoints = ETCD_ENDPOINTS
        )
      )
    )
    Assigner.createPreferredAssignerDriver(enabledConf) match {
      case _: EtcdPreferredAssignerDriver => // expected
      case driver => fail(s"expected EtcdPreferredAssignerDriver but got $driver")
    }

    val disabledConf = new DicerAssignerConf(
      Configs.parseMap(
        generateConfigMap(
          preferredAssignerEnabled = false,
          preferredAssignerStoreIncarnation = STORE_INCARNATION,
          etcdEndpoints = ETCD_ENDPOINTS
        )
      )
    )
    Assigner.createPreferredAssignerDriver(disabledConf) match {
      case _: DisabledPreferredAssignerDriver => // expected
      case driver => fail(s"expected DisabledPreferredAssignerDriver but got $driver")
    }
  }
}
