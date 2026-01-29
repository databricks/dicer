package com.databricks.dicer.client

/**
 * OSS stub for [[TestableDicerClientProtoLoggerConf]]. In OSS, this is a no-op placeholder as SAFE
 * flags are not available and we do not use proto logging.
 */
class TestableDicerClientProtoLoggerConf extends DicerClientProtoLoggerConf {

  /** No-op in OSS. */
  def setLoggingSampleFraction(fraction: Double): Unit = {}

  /** No-op in OSS. */
  def clearMockValues(): Unit = {}
}

object TestableDicerClientProtoLoggerConf {

  /** Creates a dummy conf in OSS. */
  def create(sampleFraction: Double = 0.0): TestableDicerClientProtoLoggerConf = {
    new TestableDicerClientProtoLoggerConf()
  }
}
