package com.databricks.dicer.client

import com.databricks.dicer.client.testing.ScalaClerkHarness
import com.databricks.dicer.external.{Slicelet, Target}

/** Runs [[ClerkRetrySuiteBase]] against the in-process Scala Clerk's retry picker. */
class ScalaClerkRetrySuite extends ClerkRetrySuiteBase {

  override protected def createClerkHarness(target: Target, slicelet: Slicelet): ScalaClerkHarness =
    ScalaClerkHarness.create(testEnv.createClerk(slicelet))

}
