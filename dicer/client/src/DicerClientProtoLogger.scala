package com.databricks.dicer.client

import java.time.Instant

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.common.{ClientType, Generation}

/**
 * Client-specific proto logger that provides convenience methods for Dicer client logging.
 * This is a no-op implementation for OSS builds - logging is disabled.
 *
 * @param conf the configuration (unused in OSS).
 * @param clientType the type of client (unused in OSS).
 * @param subscriberDebugName the debug name of the subscriber (unused in OSS).
 * @param sec the sequential execution context (unused in OSS).
 */
private[client] class DicerClientProtoLogger(
    conf: DicerClientProtoLoggerConf,
    clientType: ClientType,
    subscriberDebugName: String,
    sec: SequentialExecutionContext) {

  /**
   * Convenience method to log assignment propagation latency.
   * No-op in OSS.
   *
   * @param generation the assignment generation.
   * @param currentTime the current time when this assignment was received.
   */
  def logAssignmentPropagationLatency(generation: Generation, currentTime: Instant): Unit = ()
}

private[client] object DicerClientProtoLogger {

  /**
   * Creates a new [[DicerClientProtoLogger]] instance. Always returns a no-op logger for OSS.
   *
   * @param conf the configuration.
   * @param clientType the type of client (Clerk or Slicelet).
   * @param subscriberDebugName the debug name of the subscriber.
   * @param sec the sequential execution context.
   */
  def create(
      conf: DicerClientProtoLoggerConf,
      clientType: ClientType,
      subscriberDebugName: String,
      sec: SequentialExecutionContext): DicerClientProtoLogger = {
    new DicerClientProtoLogger(conf, clientType, subscriberDebugName, sec)
  }
}
