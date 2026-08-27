package com.databricks.dicer.assigner.conf

import java.util.UUID

import com.databricks.conf.Configs
import com.databricks.dicer.assigner.{
  Assigner,
  DisabledPreferredAssignerDriver,
  FakeKubernetesTestSupport,
  KubernetesMembershipChecker,
  MigrationMode,
  MigrationPreferredAssignerDriver
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
      etcdEndpoints: Seq[String],
      migrationModeOpt: Option[MigrationMode] = None): Map[String, Any] = {
    val endpointsConfigString: String = DatabricksObjectMapper.toJson(etcdEndpoints)

    val baseConfig: Map[String, Any] = Map(
      "databricks.dicer.assigner.preferredAssigner.modeEnabled" ->
      preferredAssignerEnabled,
      "databricks.dicer.assigner.preferredAssigner.storeIncarnation" ->
      preferredAssignerStoreIncarnation.value,
      "databricks.dicer.assigner.preferredAssigner.etcd.endpoints" ->
      endpointsConfigString
    )
    migrationModeOpt match {
      case Some(migrationMode: MigrationMode) =>
        baseConfig +
        ("databricks.dicer.assigner.preferredAssigner.migrationMode" -> migrationMode.name)
      case None =>
        baseConfig
    }
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

  test("createPreferredAssignerDriver selects the driver by PA mode") {
    // Test plan: verify createPreferredAssignerDriver returns a MigrationPreferredAssignerDriver
    // when the preferred assigner mode is enabled, and a DisabledPreferredAssignerDriver when it is
    // disabled. Supply an inert membership checker (the driver requires one but never polls it).
    val checker: KubernetesMembershipChecker =
      FakeKubernetesTestSupport.inertMembershipCheckerFactory.create(UUID.randomUUID())

    val enabledConf = new DicerAssignerConf(
      Configs.parseMap(
        generateConfigMap(
          preferredAssignerEnabled = true,
          preferredAssignerStoreIncarnation = NON_LOOSE_INCARNATION,
          etcdEndpoints = ETCD_ENDPOINTS
        )
      )
    )
    Assigner.createPreferredAssignerDriver(enabledConf, checker) match {
      case _: MigrationPreferredAssignerDriver => // expected
      case driver => fail(s"expected MigrationPreferredAssignerDriver but got $driver")
    }

    val disabledConf = new DicerAssignerConf(
      Configs.parseMap(
        generateConfigMap(
          preferredAssignerEnabled = false,
          preferredAssignerStoreIncarnation = LOOSE_INCARNATION,
          etcdEndpoints = ETCD_ENDPOINTS
        )
      )
    )
    Assigner.createPreferredAssignerDriver(disabledConf, checker) match {
      case _: DisabledPreferredAssignerDriver => // expected
      case driver => fail(s"expected DisabledPreferredAssignerDriver but got $driver")
    }
  }

}
