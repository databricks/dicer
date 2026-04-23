package com.databricks.dicer.external

import com.databricks.backend.common.util.Project
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.conf.Config
import com.databricks.conf.Configs
import com.databricks.conf.RichConfig
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.client.TestClientUtils
import com.databricks.dicer.common.{InternalClientConf, InternalDicerTestEnvironment}
import com.databricks.testing.DatabricksTest

import java.net.URI
import com.databricks.rpc.tls.TLSOptions

/** Test suite for checking that only one Slicelet can be created for a given target. */
private class SingletonSliceletSuite extends DatabricksTest with TestName {

  /** The test environment used for the test. */
  private val testEnv = InternalDicerTestEnvironment.create(
    assignerClusterUri = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
  )

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  test("Multiple Slicelets throws") {
    // Test plan: Verify that attempts to instantiate multiple Slicelets throw exceptions, even
    // if they're for different targets. Verify this by creating two Slicelets with different
    // targets and with `allowMultipleSliceletInstancesPropertyName` set to false, and verifying
    // that the second one throws an exception.
    val target1 = Target(s"$getSafeName-1")
    val target2 = Target(s"$getSafeName-2")

    // Create a valid config and set `allowMultipleSliceletInstances` to false, as it would be in a
    // production environment.
    val rawConf: Config = TestClientUtils
      .createSliceletConfig(
        testEnv.getAssignerPort,
        "localhost",
        clientTlsFilePathsOpt = None,
        serverTlsFilePathsOpt = None,
        watchFromDataPlane = false
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleSliceletInstancesPropertyName -> false
        )
      )
    val sliceletConf: SliceletConf = new ProjectConf(Project.TestProject, rawConf)
    with SliceletConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    Slicelet(sliceletConf, target1)
    assertThrow[IllegalStateException]("already exists") {
      Slicelet(sliceletConf, target2)
    }
  }
}
