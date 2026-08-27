package com.databricks.caching.util

import io.prometheus.client.Counter

/**
 * Records the outcome of parsing IDM URIs (see <internal link>) through the validated-URI value
 * classes in this package (e.g. [[KubernetesClusterUri]] and [[RegionUri]]).
 */
object IdmUriParseMetrics {

  /**
   * Counter for IDM URI parse attempts. See [[recordParse]] for the meaning of the label
   * values.
   */
  private val parseCount: Counter = Counter
    .build()
    .name("caching_util_idm_uri_parse_total")
    .labelNames("uriType", "status")
    .help("Counter of IDM URI parse attempts, labeled by URI type and parse outcome.")
    .register()

  /**
   * Increments the `caching_util_idm_uri_parse_total` counter for one parse attempt.
   *
   * @param uriType the kind of IDM URI that was parsed.
   * @param succeeded whether the parse produced a validated URI (`true`) or returned `None`
   *                  (`false`).
   */
  def recordParse(uriType: IdmUriParseMetrics.UriType, succeeded: Boolean): Unit = {
    val status: String = if (succeeded) "success" else "failure"
    parseCount.labels(uriType.label, status).inc()
  }

  /** The kind of IDM URI being parsed, surfaced as the `uriType` metric label. */
  sealed abstract class UriType(val label: String)

  object UriType {
    case object KubernetesCluster extends UriType("kubernetesCluster")
    case object Region extends UriType("region")
  }
}
