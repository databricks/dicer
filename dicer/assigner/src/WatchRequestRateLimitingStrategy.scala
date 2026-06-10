package com.databricks.dicer.assigner

import com.databricks.caching.util.{SequentialExecutionContext, TypedClock}
import com.databricks.dicer.assigner.config.InternalTargetConfigMap
import com.databricks.dicer.common.ThrottlingShims.{HttpRequest, ThrottlingStrategy}

/**
 * OSS stub for the internal `WatchRequestRateLimitingStrategy`. OSS does not implement HTTP-layer
 * rate limiting; this stub exists only so the Assigner integration compiles uniformly across
 * internal and OSS builds. All operations are no-ops.
 */
private[dicer] final class WatchRequestRateLimitingStrategy(
    sec: SequentialExecutionContext,
    initialConfigMap: InternalTargetConfigMap,
    allowDefaultConfigForExperimentalTargets: Boolean,
    clock: TypedClock)
    extends ThrottlingStrategy[HttpRequest] {

  /** No-op in OSS: rate limiting is not enforced. */
  def updateTargetConfigMapAsync(newConfigMap: InternalTargetConfigMap): Unit = ()
}
