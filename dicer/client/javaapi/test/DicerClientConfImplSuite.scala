package com.databricks.dicer.client.javaapi

import com.databricks.conf.{Configs, RawConfigSingleton}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest
import com.databricks.backend.common.util.CurrentProject
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.external.DicerClientConf

class DicerClientConfImplSuite extends DatabricksTest {

  override def beforeAll(): Unit = {
    // Initialize the project context. Required for configuration loading.
    CurrentProject.initializeProject(Project.TestProject)
    // Configure static flags with valid test certificate paths before creating Slicelets/Clerks.
    RawConfigSingleton.initializeConfig(
      Configs.parseString(
        s"""
           |databricks.dicer.library.client.keystore = "${TestTLSOptions.clientKeystorePath}"
           |databricks.dicer.library.client.truststore = "${TestTLSOptions.clientTruststorePath}"
           |databricks.dicer.library.server.keystore = "${TestTLSOptions.serverKeystorePath}"
           |databricks.dicer.library.server.truststore = "${TestTLSOptions.serverTruststorePath}"
           |""".stripMargin
      )
    )
  }

  test("SliceletConfImpl.INSTANCE with TLS config") {
    // Test plan: Verify that SliceletConfImpl.INSTANCE remains a singleton and reads
    // TLS config from RawConfigSingleton.
    val conf1: DicerClientConf = SliceletConfImpl.INSTANCE
    val conf2: DicerClientConf = SliceletConfImpl.INSTANCE

    // Verify they are the same instance (singleton behavior)
    assert(conf1 eq conf2)

    // Verify that dicerTlsOptions is set.
    assert(conf1.getDicerClientTlsOptions.isDefined)
    assert(conf2.getDicerServerTlsOptions.isDefined)
  }

  test("ClerkConfImpl builder with TLS config") {
    // Test plan: Verify that ClerkConfImpl.builder().build() creates non-singleton instances
    // that still read TLS config from RawConfigSingleton.
    val clerkConf1: ClerkConfImpl = ClerkConfImpl.builder().build()
    val clerkConf2: ClerkConfImpl = ClerkConfImpl.builder().build()
    assert(clerkConf1 ne clerkConf2)
    assert(clerkConf1.getDicerClientTlsOptions.isDefined)
    assert(clerkConf2.getDicerServerTlsOptions.isDefined)
  }

  test("SliceletConfImpl create for test") {
    // Test plan: Verify that multiple SliceletConfImpl test instances can be created,
    // and that each instance is a different object (not a singleton).
    val testConf1: SliceletConfImpl =
      SliceletConfImpl.forTest.create(Configs.parseString("databricks.dicer.slicelet.uuid = 1"))
    val testConf2: SliceletConfImpl =
      SliceletConfImpl.forTest.create(Configs.parseString("databricks.dicer.slicelet.uuid = 2"))

    // Verify they are different instances (not singletons)
    assert(testConf1 != testConf2)
    assert(testConf1.sliceletUuidOpt != testConf2.sliceletUuidOpt)
  }

  test("ClerkConfImpl builder setSliceletPort") {
    // Test plan: Verify that ClerkConfImpl.builder().setSliceletPort creates a ClerkConfImpl whose
    // slicelet port override is reflected in getSliceletURI(). Also verify that invalid ports are
    // rejected.
    val overriddenPort: Int = 443
    val overriddenConf: ClerkConfImpl =
      ClerkConfImpl.builder().setSliceletPort(overriddenPort).build()
    assert(overriddenConf.getSliceletURI("localhost").toString == s"localhost:$overriddenPort")

    assertThrow[IllegalArgumentException]("must be positive") {
      ClerkConfImpl.builder().setSliceletPort(0).build()
    }
  }
}
