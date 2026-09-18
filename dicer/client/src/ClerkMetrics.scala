package com.databricks.dicer.client

import io.prometheus.client.Histogram

import com.databricks.caching.util.SafeCounter
import com.databricks.dicer.client.ClerkMetrics.{ClerkFactoryContext, ResourceType}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target

/**
 * Prometheus metrics for the Clerk.
 *
 * Each [[ClerkMetrics]] instance is associated with a specific [[Target]] and
 * [[ClerkFactoryContext]], and memoizes labeled metric children for that combination.
 */
private[dicer] class ClerkMetrics(target: Target, factoryContext: ClerkFactoryContext) {

  // Memoize the getStubForKey call-count children for the given (`target`, `factoryContext`), split
  // by whether the call supplied a secondary SliceKey (two-level sharding) or not.
  private val getStubForKeyCallCountWithSecondaryKeyChild: SafeCounter.Child =
    ClerkMetrics.getStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        true.toString
      )
  private val getStubForKeyCallCountWithoutSecondaryKeyChild: SafeCounter.Child =
    ClerkMetrics.getStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        false.toString
      )

  // Memoize the child counter for Clerk creation events on this (`target`, `factoryContext`).
  private val clerkCreatedTotalChild: SafeCounter.Child =
    ClerkMetrics.clerkCreatedTotal
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext
      )

  // Memoize the getNextStubForKey call-count children for the given (`target`, `factoryContext`),
  // split by the returned resource type.
  private val getNextStubForKeyCallCountAssignedResourceChild: SafeCounter.Child =
    ClerkMetrics.getNextStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        ResourceType.AssignedResource.label
      )
  private val getNextStubForKeyCallCountFallbackResourceChild: SafeCounter.Child =
    ClerkMetrics.getNextStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        ResourceType.FallbackResource.label
      )
  private val getNextStubForKeyCallCountRandomAssignedResourceChild: SafeCounter.Child =
    ClerkMetrics.getNextStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        ResourceType.RandomAssignedResource.label
      )
  private val getNextStubForKeyCallCountAssignedResourceAfterTokenResetChild: SafeCounter.Child =
    ClerkMetrics.getNextStubForKeyCallCount
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext,
        ResourceType.AssignedResourceAfterTokenReset.label
      )

  // Memoize the histogram child for the number of assigned resources picked so far at each
  // `getNextStubForKey` call for the given (`target`, `factoryContext`).
  private val getNextStubForKeyPickedResourceCountHistogramChild: Histogram.Child =
    ClerkMetrics.getNextStubForKeyPickedResourceCountHistogram
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        factoryContext
      )

  /**
   * Increments the counter tracking the number of times
   * [[com.databricks.dicer.external.Clerk.getStubForKey]] was called.
   *
   * @param secondaryKeyProvided whether the call supplied a secondary [[SliceKey]], indicating a
   *                             two-level sharding lookup.
   */
  def incrementClerkGetStubForKeyCallCount(secondaryKeyProvided: Boolean): Unit = {
    if (secondaryKeyProvided) {
      getStubForKeyCallCountWithSecondaryKeyChild.inc()
    } else {
      getStubForKeyCallCountWithoutSecondaryKeyChild.inc()
    }
  }

  /** Records one Clerk creation event. */
  def incrementClerkCreatedCount(): Unit = {
    clerkCreatedTotalChild.inc()
  }

  /**
   * Increments the getNextStubForKey call counter for the returned resource type.
   *
   * @param resourceType the type of resource the call returned.
   */
  def incrementGetNextStubForKeyCallCount(resourceType: ResourceType): Unit = {
    resourceType match {
      case ResourceType.AssignedResource => getNextStubForKeyCallCountAssignedResourceChild.inc()
      case ResourceType.FallbackResource => getNextStubForKeyCallCountFallbackResourceChild.inc()
      case ResourceType.RandomAssignedResource =>
        getNextStubForKeyCallCountRandomAssignedResourceChild.inc()
      case ResourceType.AssignedResourceAfterTokenReset =>
        getNextStubForKeyCallCountAssignedResourceAfterTokenResetChild.inc()
    }
  }

  /**
   * Observes the number of assigned resources picked so far at a getNextStubForKey call.
   *
   * @param pickedResourceCount the number of assigned resources picked so far.
   */
  def observeGetNextStubForKeyPickedResourceCount(pickedResourceCount: Int): Unit = {
    getNextStubForKeyPickedResourceCountHistogramChild.observe(pickedResourceCount.toDouble)
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

  /**
   * The type of resource a [[ClerkImpl.getNextStubForKey]] call returned, surfaced as the
   * resourceType label on the getNextStubForKey metric.
   */
  sealed trait ResourceType {

    /** The label value for the getNextStubForKey metric. */
    def label: String
  }

  object ResourceType {

    /** A pick of an assigned resource. */
    case object AssignedResource extends ResourceType {
      val label: String = "assigned"
    }

    /** A pick of the fallback resource. */
    case object FallbackResource extends ResourceType {
      val label: String = "fallback"
    }

    /** A random pick of an assigned resource made after all resources are exhausted. */
    case object RandomAssignedResource extends ResourceType {
      val label: String = "randomAssigned"
    }

    /** A pick of an assigned resource made after the token was reset. */
    case object AssignedResourceAfterTokenReset extends ResourceType {
      val label: String = "assignedAfterTokenReset"
    }
  }

  private val getStubForKeyCallCount: SafeCounter = SafeCounter.create(
    metricName = "dicer_clerk_getstubforkey_call_count_total",
    help = "The number of times Clerk was called to get a stub for a key.",
    labelNames = Seq(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "factoryContext",
      "secondaryKeyProvided"
    )
  )

  private val clerkCreatedTotal: SafeCounter = SafeCounter.create(
    metricName = "dicer_clerk_created_total",
    help =
      "The number of Clerk instances created in this process. Incremented once per ClerkImpl " +
      "construction, labeled by the target and the entry point used to create the Clerk.",
    labelNames = Seq("targetCluster", "targetName", "targetInstanceId", "factoryContext")
  )

  private val getNextStubForKeyCallCount: SafeCounter = SafeCounter.create(
    metricName = "dicer_clerk_getnextstubforkey_call_count_total",
    help = "The number of getNextStubForKey calls",
    labelNames = Seq(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "factoryContext",
      "resourceType"
    )
  )

  // Note 1: This histogram observes every `getNextStubForKey` picked resource count, which will
  // inflate the histogram towards 1. For example, if at the time of recording this metric,
  // `RetryTokenImpl.pickedResourceIndices.size == 5`, the samples observed so far would've been
  // [1, 2, 3, 4]. Ideally, only the last pick gets reported, but that information is unknown at
  // the Clerk's layer.
  // Note 2: The size of `RetryTokenImpl.pickedResourceIndices` is limited by
  // `MAX_PICKED_ASSIGNED_RESOURCES` which inherently becomes the ceiling for the observed values.
  private val getNextStubForKeyPickedResourceCountHistogram: Histogram = Histogram
    .build()
    .name("dicer_clerk_getnextstubforkey_picked_resource_count_histogram")
    .help(
      "The number of assigned resources picked so far at a getNextStubForKey call."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "factoryContext")
    .buckets(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
    .register()
}
