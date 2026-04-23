package com.databricks.dicer.assigner.conf

import com.databricks.conf.Configs
import com.databricks.dicer.assigner.{
  Assigner,
  DisabledPreferredAssignerDriver,
  EtcdPreferredAssignerDriver,
  PreferredAssignerTestHelper
}
import com.databricks.dicer.common.Incarnation
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.testing.DatabricksTest

class PreferredAssignerStoreConfSuite extends DatabricksTest {

  // A list of etcd endpoints to use for testing.
  private val ETCD_ENDPOINTS: Seq[String] = Seq(
    "http://dicer-etcd-service.test-env-test.svc.cluster.local:2379"
  )

  private val NON_LOOSE_INCARNATION: Incarnation = Incarnation(4)
  private val LOOSE_INCARNATION: Incarnation = Incarnation(3)

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
        preferredAssignerStoreIncarnation = NON_LOOSE_INCARNATION,
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
        preferredAssignerStoreIncarnation = NON_LOOSE_INCARNATION,
        etcdEndpoints = Seq.empty
      )
      val assignerConf = new DicerAssignerConf(Configs.parseMap(configMap))
      Assigner.createPreferredAssignerStore(assignerConf)
    }
  }

  test("Cannot create a PA store when store incarnation is loose") {
    // Test plan: verify the preferred assigner store throws an exception when the preferred
    // assigner mode is enabled, etcd endpoints are non-empty, but store incarnation is loose.
    assertThrows[IllegalArgumentException] {
      val configMap: Map[String, Any] = generateConfigMap(
        preferredAssignerEnabled = true,
        preferredAssignerStoreIncarnation = LOOSE_INCARNATION,
        etcdEndpoints = Seq.empty
      )
      val assignerConf = new DicerAssignerConf(Configs.parseMap(configMap))
      Assigner.createPreferredAssignerStore(assignerConf)
    }
  }

  test("Initialize PA store when PA mode is enabled") {
    // Test plan: verify that when the preferred assigner mode is enabled, etcd endpoints are
    // non-empty, and the store incarnation is non-loose, an `EtcdPreferredAssignerStore` instance
    // can be created successfully.
    val confString: Map[String, Any] = generateConfigMap(
      preferredAssignerEnabled = true,
      preferredAssignerStoreIncarnation = NON_LOOSE_INCARNATION,
      etcdEndpoints = ETCD_ENDPOINTS
    )
    val assignerConf = new DicerAssignerConf(Configs.parseMap(confString))
    Assigner.createPreferredAssignerStore(assignerConf)
  }

  test("Create PreferredAssignerDriver based on the config") {
    // Test plan: verify that when the preferred assigner mode is enabled, an
    // `EtcdPreferredAssignerDriver` instance is created, and when the preferred assigner mode is
    // disabled, a `DisabledPreferredAssignerDriver` instance is created.
    val preferredAssignerConfMap: Map[String, Any] = generateConfigMap(
      preferredAssignerEnabled = true,
      preferredAssignerStoreIncarnation = NON_LOOSE_INCARNATION,
      etcdEndpoints = ETCD_ENDPOINTS
    )
    val preferredAssignerConf: DicerAssignerConf =
      new DicerAssignerConf(Configs.parseMap(preferredAssignerConfMap))
    Assigner.createPreferredAssignerDriver(
      preferredAssignerConf,
      PreferredAssignerTestHelper.noOpMembershipCheckerFactory
    ) match {
      case _: EtcdPreferredAssignerDriver => assert(true)
      case driver => fail(s"expected `EtcdPreferredAssignerDriver` but got $driver")
    }

    val disabledPreferredAssignerConfMap: Map[String, Any] = generateConfigMap(
      preferredAssignerEnabled = false,
      preferredAssignerStoreIncarnation = LOOSE_INCARNATION,
      etcdEndpoints = ETCD_ENDPOINTS
    )
    val disabledPreferredAssignerConf: DicerAssignerConf =
      new DicerAssignerConf(Configs.parseMap(disabledPreferredAssignerConfMap))
    Assigner.createPreferredAssignerDriver(
      disabledPreferredAssignerConf,
      PreferredAssignerTestHelper.noOpMembershipCheckerFactory
    ) match {
      case _: DisabledPreferredAssignerDriver => assert(true)
      case driver => fail(s"expected `DisabledPreferredAssignerDriver` but got $driver")
    }
  }
}
