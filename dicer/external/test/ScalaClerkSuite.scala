package com.databricks.dicer.external

import java.net.URI

import com.databricks.backend.common.util.Project
import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.{LocationConf, LocationConfTestUtils, ProjectConf}
import com.databricks.dicer.client.TestClientUtils
import com.databricks.rpc.tls.TLSOptions

/** Tests for Scala Clerk implementation that connects to Slicelets for assignments.  */
private class ScalaClerkSuite extends ScalaClerkSuiteBase {

  override protected def createClerkInternal(
      target: Target,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): ClerkDriver = {
    val envMap: Map[String, String] = clerkLocationConfigMap.getOrElse(Map.empty)
    val locationConf: LocationConf = LocationConfTestUtils.newTestLocationConfig(envMap = envMap)

    withLocationConfSingleton(locationConf) {
      if (clerkLocationConfigMap.isEmpty) {
        LocationConf.restoreSingletonForTest() // Ensure location is cleared.
      }
      val slicelet: Slicelet =
        testEnv.createSlicelet(target).start(selfPort = 0, listenerOpt = None)

      val clerk: Clerk[ResourceAddress] = clientBranchOpt match {
        case Some(clientBranch) =>
          // Create a Clerk with a custom branch for version metrics testing.
          val rawConf = TestClientUtils.createClerkConfig(
            sliceletPort = slicelet.impl.forTest.sliceletPort,
            clientTlsFilePathsOpt = None
          )
          val clerkConf: ClerkConf = new ProjectConf(Project.TestProject, rawConf) with ClerkConf {
            override def dicerTlsOptions: Option[TLSOptions] = None
            override val branch: String = clientBranch
          }
          TestClientUtils.createClerk(target, clerkConf)
        case None =>
          testEnv.createClerk(slicelet)
      }
      ScalaClerkDriver.create(clerk)
    }
  }

  override protected def getWatchSource: WatchSourceType = WatchSourceSlicelet
}
