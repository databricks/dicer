package com.databricks.dicer.common.testing

import com.databricks.dicer.common.AppIdentifier

/** Process-wide test utilities for [[AppIdentifier]]. */
private[dicer] object AppIdentifierTestUtils {

  /**
   * Configures the process-wide app identifier source with the given `name` and `instanceId`.
   */
  def configureForTest(name: String, instanceId: String): Unit = {
    AppIdentifier.setInstanceForTest(nameOpt = Some(name), instanceIdOpt = Some(instanceId))
  }

  /** Configures the process-wide app identifier source to return no [[AppIdentifier]]. */
  def clearForTest(): Unit = {
    AppIdentifier.setInstanceForTest(nameOpt = None, instanceIdOpt = None)
  }
}
