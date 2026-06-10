package com.databricks.dicer.client

import com.databricks.dicer.client.ClerkMetrics.ClerkFactoryContext
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import io.prometheus.client.Counter

/**
 * Prometheus metrics for the Clerk.
 *
 * Each [[ClerkMetrics]] instance is associated with a specific [[Target]] and
 * [[ClerkFactoryContext]], and memoizes labeled metric children for that combination.
 */
private[dicer] class ClerkMetrics(target: Target, factoryContext: ClerkFactoryContext) {

  // Memoize the child counter for the given target and factory context.
  private val getStubForKeyCallCountChild: Counter.Child =
    ClerkMetrics.getStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext
      )

  // Memoize the child counter for Clerk creation events on this (target, factoryContext).
  private val clerkCreatedTotalChild: Counter.Child =
    ClerkMetrics.clerkCreatedTotal
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext
      )

  /**
   * Increments the counter tracking the number of times
   * [[com.databricks.dicer.external.Clerk.getStubForKey]] was called.
   */
  def incrementClerkGetStubForKeyCallCount(): Unit = {
    getStubForKeyCallCountChild.inc()
  }

  /** Records one Clerk creation event. */
  def incrementClerkCreatedCount(): Unit = {
    clerkCreatedTotalChild.inc()
  }
}

object ClerkMetrics {

  /**
   * Identifies the factory that created a [[ClerkImpl]]. Surfaces as a low-cardinality label on
   * Clerk metrics so that Clerk usage can be attributed to its factory.
   *
   * Keep the set of values small and lowerCamelCase to bound the cardinality of the
   * corresponding metric label.
   */
  type ClerkFactoryContext = String

  private val getStubForKeyCallCount: Counter = Counter
    .build()
    .name("dicer_clerk_getstubforkey_call_count_total")
    .help("The number of times Clerk was called to get a stub for a key")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "factoryContext")
    .register()

  private val clerkCreatedTotal: Counter = Counter
    .build()
    .name("dicer_clerk_created_total")
    .help(
      "The number of Clerk instances created in this process. Incremented once per ClerkImpl " +
      "construction, labeled by the target and the entry point used to create the Clerk."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "factoryContext")
    .register()
}
