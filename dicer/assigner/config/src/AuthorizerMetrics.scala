package com.databricks.dicer.assigner.config

import io.prometheus.client.Counter

import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target

/** Metrics emitted while authorizing or rejecting Dicer target watch requests. */
object AuthorizerMetrics {

  /** An error describing why a watch failed. */
  sealed trait WatchError

  object WatchError {

    /** There was no config known for the target. */
    case object NO_CONFIG extends WatchError

    /**
     * The target was invalid because header validation is enabled
     * (`enableTargetValidationViaAppIdentifierHeaders`) and the target was not an
     * [[com.databricks.dicer.external.AppTarget]] -- the only [[Target]] type whose identity can be
     * matched against App Identifier headers. Fires regardless of client type or whether
     * headers are present.
     */
    case object INVALID_TARGET_NOT_APP extends WatchError

    /**
     * The target was invalid because the client is a Slicelet and the Assigner was configured to
     * validate the request with App Identifier headers, but they were not present.
     * Clerk callers without headers are treated as trusted workloads and are not counted here.
     */
    case object INVALID_TARGET_SLICELET_NO_HEADERS extends WatchError

    /**
     * The target was invalid because the client is a Slicelet and the Assigner was configured to
     * validate the request with App Identifier headers, but the App Name header did not
     * match the target name.
     */
    case object INVALID_TARGET_SLICELET_NAME_MISMATCH extends WatchError

    /**
     * The target was invalid because the client is a Slicelet and the Assigner was configured to
     * validate the request with App Identifier headers, but the App Instance ID header
     * did not match the target instance ID.
     */
    case object INVALID_TARGET_SLICELET_INSTANCE_ID_MISMATCH extends WatchError

    /**
     * The target was invalid because the client is a Clerk with App Identifier headers
     * present, but the app name is not in the trusted-service allowlist. Clerk requests with
     * headers are only accepted from services in `conf.trustedWatchAnyTargetServices`.
     */
    case object INVALID_TARGET_CLERK_NOT_TRUSTED extends WatchError

    /**
     * The target was invalid because the configured [[Authorizer]] rejected a watch request whose
     * App Name did not match the target name.
     */
    case object INVALID_TARGET_SLICELET_UNAUTHORIZED extends WatchError
  }

  private val numTargetWatchErrors: Counter = Counter
    .build()
    .name("dicer_assigner_num_watch_errors_total")
    .help("Number of target watch errors due to the labelled reason")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "reason")
    .register()

  /** Records a rejected watch request for `target`. */
  def incrementNumTargetWatchErrors(target: Target, reason: WatchError): Unit = {
    numTargetWatchErrors
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        reason.toString
      )
      .inc()
  }

  /**
   * Counts Clerk WatchRequests for AppTargets that arrive without App Identifier headers
   * while target validation is enabled. These callers are treated as trusted workloads and the
   * request proceeds; the metric is exported purely for observability so we can monitor how many
   * Clerk requests still rely on this trusted fall-through.
   */
  private val numClerkAppTargetWatchesNoHeaders: Counter = Counter
    .build()
    .name("dicer_assigner_num_clerk_apptarget_watches_no_headers_total")
    .help(
      "Number of Clerk WatchRequests for AppTargets that arrive without App Identifier " +
      "headers. These requests are accepted as trusted workloads; the counter is for " +
      "observability only."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /** Records an accepted Clerk WatchRequest for an AppTarget without App headers. */
  def incrementNumClerkAppTargetWatchesNoHeaders(target: Target): Unit = {
    numClerkAppTargetWatchesNoHeaders
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .inc()
  }
}
