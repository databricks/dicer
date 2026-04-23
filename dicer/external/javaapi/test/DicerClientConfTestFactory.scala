package com.databricks.dicer.external.javaapi

import com.databricks.conf.Config
import com.databricks.dicer.client.javaapi.{ClerkConfImpl, SliceletConfImpl}

/** Test factory for creating DicerClientConf instances (e.g. SliceletConfImpl) in Java tests. */
// This provides a Java-friendly API to create test instances without dealing with Scala's
// nested object syntax (`forTest$.MODULE$.create()`), and keeping the internal `forTest`
// object package-private.
object DicerClientConfTestFactory {

  /** Creates a test instance from the provided base config. */
  def createSliceletConf(baseConf: Config): SliceletConfImpl = {
    SliceletConfImpl.forTest.create(baseConf)
  }

  /** Sets the base configuration on the given ClerkConf builder and returns it. */
  def setClerkBaseConf(
      clerkConfBuilder: ClerkConfImpl.Builder,
      baseConf: Config): ClerkConfImpl.Builder = {
    clerkConfBuilder.forTest.setBaseConfig(baseConf)
  }
}
