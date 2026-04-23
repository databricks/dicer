package com.databricks.dicer.external

import java.net.URI
import java.util.UUID

import com.databricks.backend.common.util.Project
import com.databricks.conf.Configs
import com.databricks.conf.trusted.ProjectConf
import com.databricks.conf.trusted.RPCPortConf
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest

class ConfSuite extends DatabricksTest {
  private val FAKE_ASSIGNER_PORT = 80

  private val FAKE_SLICELET_PORT = 86

  private val FAKE_CLIENT_UUID: String = "00000000-0000-0000-0000-000000000002"

  /**
   * Creates a ClerkConf configuration where the slicelet watch RPC port is always set to the value
   * of FAKE_SLICELET_PORT. Also sets a client UUID for rate limiting tests.
   *
   * We create this config to test conf.getSliceletURI which only uses those config values, and so
   * we do not override any other config values when instantiating the conf.
   */
  private def createClerkConf(): ClerkConf = {
    val clerkConfig = Configs.parseMap(
      "databricks.dicer.slicelet.rpc.port" -> FAKE_SLICELET_PORT,
      "databricks.dicer.assigner.rpc.port" -> FAKE_ASSIGNER_PORT,
      "databricks.dicer.internal.cachingteamonly.clientUuid" -> FAKE_CLIENT_UUID
    )

    new ProjectConf(Project.TestProject, clerkConfig) with ClerkConf with RPCPortConf {
      override protected def dicerTlsOptions: Option[TLSOptions] = None
    }
  }

  test(
    "getSliceletURI returns a URI with both host and port defined when a DBNS host is not defined"
  ) {
    // Test plan: Verify that getSliceletURI returns a URI containing both a host and port when
    // the slicelet host is NOT a DBNS host.
    val sliceletHostName = "localhost"
    val conf = createClerkConf()
    val expectedSliceletURI = new URI(s"$sliceletHostName:$FAKE_SLICELET_PORT")
    assertResult(expectedSliceletURI)(conf.getSliceletURI(sliceletHostName))
  }

  test("clientUuidOpt reads from config when set") {
    // Test plan: Verify that DicerClientConf.clientUuidOpt returns the configured UUID value.
    val clerkConfig = Configs.parseMap(
      "databricks.dicer.slicelet.rpc.port" -> FAKE_SLICELET_PORT,
      "databricks.dicer.assigner.rpc.port" -> FAKE_ASSIGNER_PORT,
      "databricks.dicer.internal.cachingteamonly.clientUuid" -> FAKE_CLIENT_UUID
    )
    val conf = new ProjectConf(Project.TestProject, clerkConfig) with ClerkConf with RPCPortConf {
      override protected def dicerTlsOptions: Option[TLSOptions] = None
    }
    assertResult(Some(FAKE_CLIENT_UUID))(conf.clientUuidOpt)
  }

  test("clientUuidOpt reads from POD_UID environment variable") {
    // Test plan: Verify that DicerClientConf.clientUuidOpt returns the value from the POD_UID
    // environment variable when the config key is not explicitly set.
    val podUid = UUID.randomUUID().toString
    val configWithoutUuid = Configs.parseMap(
      "databricks.dicer.slicelet.rpc.port" -> FAKE_SLICELET_PORT,
      "databricks.dicer.assigner.rpc.port" -> FAKE_ASSIGNER_PORT
    )

    val conf = new ProjectConf(Project.TestProject, configWithoutUuid) with ClerkConf
    with RPCPortConf {
      override protected def dicerTlsOptions: Option[TLSOptions] = None
      override def envVars: Map[String, String] = super.envVars ++ Map("POD_UID" -> podUid)
    }

    assertResult(Some(podUid))(conf.clientUuidOpt)
  }

  test("clientUuidOpt returns None when neither config nor POD_UID is set") {
    // Test plan: Verify that DicerClientConf.clientUuidOpt returns None when neither the config
    // key nor the POD_UID environment variable is present.
    val configWithoutUuid = Configs.parseMap(
      "databricks.dicer.slicelet.rpc.port" -> FAKE_SLICELET_PORT,
      "databricks.dicer.assigner.rpc.port" -> FAKE_ASSIGNER_PORT
    )

    val conf = new ProjectConf(Project.TestProject, configWithoutUuid) with ClerkConf
    with RPCPortConf {
      override protected def dicerTlsOptions: Option[TLSOptions] = None
      override def envVars: Map[String, String] = Map.empty
    }

    assertResult(None)(conf.clientUuidOpt)
  }
}
