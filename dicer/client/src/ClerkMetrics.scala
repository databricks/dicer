package com.databricks.dicer.client

import com.databricks.dicer.external.Target
import com.databricks.dicer.common.TargetHelper.TargetOps
import io.prometheus.client.Counter

/**
 * Prometheus metrics for the Clerk.
 *
 * Each [[ClerkMetrics]] instance is associated with a specific [[Target]] and memoizes labeled
 * metric children for that target.
 */
private[dicer] class ClerkMetrics(target: Target) {

  // Memoize the child counter for the given target.
  private val getStubForKeyCallCountChild: Counter.Child =
    ClerkMetrics.getStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )

  /**
   * Increments the counter tracking the number of times
   * [[com.databricks.dicer.external.Clerk.getStubForKey]] was called.
   */
  def incrementClerkGetStubForKeyCallCount(): Unit = {
    getStubForKeyCallCountChild.inc()
  }
}

object ClerkMetrics {

  private val getStubForKeyCallCount: Counter = Counter
    .build()
    .name("dicer_clerk_getstubforkey_call_count_total")
    .help("The number of times Clerk was called to get a stub for a key")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()
}
