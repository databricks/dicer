package com.databricks.dicer.external

import java.net.URI

import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.{LocationConf, LocationConfTestUtils}

/** Tests for Scala Clerk implementation that connects directly to the Assigner for assignments. */
private class ScalaDirectClerkSuite extends ScalaClerkSuiteBase {

  override protected def createClerkInternal(
      target: Target,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): ClerkDriver = {
    val envMap = clerkLocationConfigMap.getOrElse(Map.empty)
    val locationConf = LocationConfTestUtils.newTestLocationConfig(envMap = envMap)

    withLocationConfSingleton(locationConf) {
      if (clerkLocationConfigMap.isEmpty) {
        LocationConf.restoreSingletonForTest() // Ensure location is cleared.
      }
      val clerk: Clerk[ResourceAddress] =
        testEnv.createDirectClerk(target, initialAssignerIndex = 0, clientBranchOpt)
      ScalaClerkDriver.create(clerk)
    }
  }

  override protected def createCrossClusterClerk(
      target: Target,
      targetClusterUri: URI,
      slicelet: Slicelet,
      clerkLocationConfigMap: Option[Map[String, String]],
      clientBranchOpt: Option[String] = None): ClerkDriver = {
    // Direct clerks connect to the Assigner, not Slicelets.
    throw new UnsupportedOperationException(
      "createCrossClusterClerk is not supported for direct clerks"
    )
  }

  override protected def getWatchSource: WatchSourceType = WatchSourceAssigner
}
