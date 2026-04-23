package com.databricks.dicer.client

/**
 * OSS stub for [[TestableDicerClientProtoLoggerConf]]. In OSS, this is a no-op placeholder as SAFE
 * flags are not available and we do not use proto logging.
 */
trait TestableDicerClientProtoLoggerConf extends DicerClientProtoLoggerConf {

  /** No-op in OSS. */
  def setLoggingSampleFraction(fraction: Double): Unit = {}
}
