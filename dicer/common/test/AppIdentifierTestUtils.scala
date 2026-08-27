package com.databricks.dicer.common

/** Test utilities for [[AppIdentifier]]. This is a no-op implementation for OSS builds. */
private[dicer] object AppIdentifierTestUtils {
  def configureForTest(name: String, instanceId: String): Unit = {}

  def clearForTest(): Unit = {}
}
