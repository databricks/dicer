package com.databricks.dicer.client

import java.time.Instant

import com.databricks.dicer.common.{ClientType, Generation}

/**
 * Client-specific proto logger that provides convenience methods for Dicer client logging.
 * This is a no-op implementation for OSS builds - logging is disabled.
 */
private[client] class DicerClientProtoLogger {

  /**
   * Convenience method to log assignment propagation latency.
   * No-op in OSS.
   *
   * @param generation the assignment generation.
   * @param currentTime the current time when this assignment was received.
   * @param subscriberDebugName the debug name of the subscriber.
   */
  def logAssignmentPropagationLatency(
      generation: Generation,
      currentTime: Instant,
      subscriberDebugName: String): Unit = ()
}

private[dicer] object DicerClientProtoLogger {

  /**
   * Creates a new [[DicerClientProtoLogger]] instance.
   * Always returns a no-op logger for OSS.
   *
   * @param clientType the type of client (Clerk or Slicelet).
   * @param conf       the configuration.
   * @param ownerName  a unique identifier for this logger's owner.
   */
  def create(
      clientType: ClientType,
      conf: DicerClientProtoLoggerConf,
      ownerName: String): DicerClientProtoLogger = {
    new DicerClientProtoLogger()
  }
}
