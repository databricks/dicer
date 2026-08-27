package com.databricks.caching.util

import io.prometheus.client.Counter

/** Records error metrics that will fire caching team's alerts defined in [[CachingErrorCode]]. */
object CachingErrorMetrics {

  /**
   * Prometheus counter that drives caching team alerts. See [[recordError]] for the meaning of the
   * label names.
   */
  private val errorCount: Counter = Counter
    .build()
    .name("caching_errors")
    .labelNames("severity", "error_code", "prefix", "owner_team")
    .help("Counter for common errors with severity and error code.")
    .register()

  /**
   * Increments the `caching_errors` counter for the given error occurrence.
   *
   * IMPORTANT: Consider using [[PrefixLogger.alert]] or [[PrefixLogger.expect]] instead of directly
   * recording [[CachingErrorMetrics]] when possible.
   *
   * @param severity The [[Severity]] of the error.
   * @param errorCode The code indicating the type or scenario of the error.
   * @param prefix A label that can be used to indicate additional information for the alert, for
   *               example, which class, component, or code position is causing the alert to fire;
   *               or the Dicer target or Softstore namespace that fires the alert. This parameter
   *               and its corresponding metric label is named "prefix", because [[PrefixLogger]]
   *               is the major user of this metric and this label will be used to indicate the
   *               logger's prefix.
   */
  def recordError(severity: Severity, errorCode: CachingErrorCode, prefix: String): Unit = {
    errorCount
      .labels(severity.toString, errorCode.toString, prefix, errorCode.alertOwnerTeam.toString)
      .inc()
  }
}
