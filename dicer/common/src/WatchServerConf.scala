package com.databricks.dicer.common

import scala.concurrent.duration._

import com.databricks.conf.trusted.DatabricksServerConf

/**
 * Configuration for the Assignment Watcher server exposed by both the Dicer Assigner and Slicelets.
 */
trait WatchServerConf extends DatabricksServerConf {

  /**
   * Number of threads used for the Watch server thread pool.
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[common] val watchServerNumThreads: Int =
    configure("databricks.dicer.internal.cachingteamonly.watchServerNumThreads", 50)

  /**
   * Maximum number of concurrent requests that the Watch server processes. Requests beyond this
   * threshold will be queued. This should be at least as large as the expected number of watchers
   * for this Watch server because each watcher performs a "hanging get" request to observe the
   * latest assingment. We expect that the number of concurrent requests is typically as many as
   * the number of watchers due to this behavior.
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[common] val watchServerMaxConcurrentRequests: Int =
    configure("databricks.dicer.internal.cachingteamonly.watchServerMaxConcurrentRequests", 5000)

  /**
   * `suggestedRpcTimeout` is the RPC timeout/deadline that the Watch server suggests subscribers
   * use in their Watch call. The server uses this information internally to terminate the call
   * slightly before it times out so that the Watch calls terminate normally with some information
   * (known generation) rather than with a DEADLINE_EXCEEDED error status.
   *
   * If the caller wants to change any configuration value, it should change the value using named
   * parameters.
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[dicer] val watchServerSuggestedRpcTimeout: FiniteDuration =
    configure[Long](
      "databricks.dicer.internal.cachingteamonly.watchServerSuggestedRpcTimeoutMillis",
      5000
    ).millis

  /**
   * The timeout for an RPC on the server side.
   *
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[common] val watchServerTimeout: FiniteDuration = configure[Long](
    "databricks.dicer.internal.cachingteamonly.watchServerTimeoutSeconds",
    10.minutes.toSeconds
  ).seconds
}
