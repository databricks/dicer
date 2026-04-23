package com.databricks.dicer.external.javaapi

import java.net.URI
/**
 * Provides access to test-only Scala functionalities for Java code. This is typically used to
 * interop with Scala's `forTest` objects.
 */
object DicerClientTestInterop {

  /** Stops the given Slicelet for testing purposes. */
  def stopSliceletForTest(scalaSlicelet: com.databricks.dicer.external.Slicelet): Unit = {
    scalaSlicelet.forTest.stop()
  }

  /** Stops the given Clerk for testing purposes. */
  def stopClerkForTest(scalaClerk: com.databricks.dicer.external.Clerk[_]): Unit = {
    scalaClerk.forTest.stop()
  }

}
