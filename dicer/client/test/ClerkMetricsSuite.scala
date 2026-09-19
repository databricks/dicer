package com.databricks.dicer.client

import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.client.ClerkMetrics.ResourceType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import java.net.URI
import io.prometheus.client.CollectorRegistry

/**
 * Base test suite that validates Prometheus metrics functionality for [[ClerkMetrics]]. Subclasses
 * need to implement the [[defaultTarget]] method, which is used throughout the test cases to create
 * a [[Target]] used to test metrics.
 */
abstract class ClerkMetricsSuiteBase extends DatabricksTest with TestName {

  /** Returns a [[Target]] unique to the current test case to use for testing metrics. */
  protected def defaultTarget: Target

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /**
   * Returns the value of the clerk creation metric for the given target and factory context.
   */
  private def getClerkCreatedCallCount(target: Target, factoryContext: String): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_clerk_created_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "factoryContext" -> factoryContext
      )
    )
  }

  /**
   * Returns the value of the getStubForKey call-count metric for the given target, factory context,
   * and whether or not a secondary [[SliceKey]] for two-level sharding was provided.
   */
  private def getStubForKeyCallCount(
      target: Target,
      factoryContext: String,
      secondaryKeyProvided: Boolean): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_clerk_getstubforkey_call_count_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "factoryContext" -> factoryContext,
        "secondaryKeyProvided" -> secondaryKeyProvided.toString
      )
    )
  }

  test("incrementClerkCreatedCount differentiates by factoryContext") {
    // Test plan: Verify that incrementClerkCreatedCount increments dicer_clerk_created_total for
    // the (target, factoryContext) labels and that counts for different factory contexts are
    // recorded independently. Use two ClerkMetrics with the same target but distinct factory
    // contexts; call increment on each and assert that each counter reflects exactly one call.
    val clerkFactoryContext: String = "clerk"
    val shardedStubFactoryContext: String = "shardedStub"

    // Verify: Initial values are 0 for both factory contexts.
    assertResult(0.0)(
      getClerkCreatedCallCount(defaultTarget, clerkFactoryContext)
    )
    assertResult(0.0)(
      getClerkCreatedCallCount(defaultTarget, shardedStubFactoryContext)
    )

    // Setup: Create two ClerkMetrics and record one creation each.
    val clerkMetrics = new ClerkMetrics(defaultTarget, clerkFactoryContext)
    val shardedStubMetrics = new ClerkMetrics(defaultTarget, shardedStubFactoryContext)
    clerkMetrics.incrementClerkCreatedCount()
    shardedStubMetrics.incrementClerkCreatedCount()

    // Verify: Each call recorded exactly one Clerk creation under its own factoryContext.
    assertResult(1.0)(
      getClerkCreatedCallCount(defaultTarget, clerkFactoryContext)
    )
    assertResult(1.0)(
      getClerkCreatedCallCount(defaultTarget, shardedStubFactoryContext)
    )
  }

  test("incrementClerkGetStubForKeyCallCount differentiates by factoryContext") {
    // Test plan: Verify that incrementClerkGetStubForKeyCallCount increments
    // dicer_clerk_getstubforkey_call_count_total for the (target, factoryContext) labels, and
    // that counts for different factory contexts are recorded independently. Use two
    // ClerkMetrics with the same target but distinct factory contexts; increment one once and
    // the other ten times, then verify both counters reflect the calls made against them.
    val clerkFactoryContext: String = "clerk"
    val shardedStubFactoryContext: String = "shardedStub"

    // Verify: Initial values are 0 for both factory contexts.
    assertResult(0.0)(
      getStubForKeyCallCount(defaultTarget, clerkFactoryContext, secondaryKeyProvided = false)
    )
    assertResult(0.0)(
      getStubForKeyCallCount(defaultTarget, shardedStubFactoryContext, secondaryKeyProvided = false)
    )

    // Setup: Create two ClerkMetrics instances and record getStubForKey calls; once for "clerk",
    // ten times for "shardedStub".
    val clerkMetrics = new ClerkMetrics(defaultTarget, clerkFactoryContext)
    val shardedStubMetrics = new ClerkMetrics(defaultTarget, shardedStubFactoryContext)
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = false)
    for (_ <- 0 until 10) {
      shardedStubMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = false)
    }

    // Verify: The getStubForKey counts differentiate by factoryContext.
    assertResult(1.0)(
      getStubForKeyCallCount(defaultTarget, clerkFactoryContext, secondaryKeyProvided = false)
    )
    assertResult(10.0)(
      getStubForKeyCallCount(defaultTarget, shardedStubFactoryContext, secondaryKeyProvided = false)
    )
  }

  test("incrementClerkGetStubForKeyCallCount differentiates by secondaryKeyProvided") {
    // Test plan: Verify that incrementClerkGetStubForKeyCallCount records calls under the
    // secondaryKeyProvided label independently, so single-key lookups (secondaryKeyProvided =
    // false) and two-level sharding lookups (secondaryKeyProvided = true) are counted separately.
    val factoryContext: String = "clerk"

    // Verify: Initial values are 0 for both secondaryKeyProvided values.
    assertResult(0.0)(
      getStubForKeyCallCount(defaultTarget, factoryContext, secondaryKeyProvided = false)
    )
    assertResult(0.0)(
      getStubForKeyCallCount(defaultTarget, factoryContext, secondaryKeyProvided = true)
    )

    // Setup: Record one single-key lookup and two two-level sharding lookups.
    val clerkMetrics = new ClerkMetrics(defaultTarget, factoryContext)
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = false)
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = true)
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = true)

    // Verify: The counts are recorded independently per secondaryKeyProvided value.
    assertResult(1.0)(
      getStubForKeyCallCount(defaultTarget, factoryContext, secondaryKeyProvided = false)
    )
    assertResult(2.0)(
      getStubForKeyCallCount(defaultTarget, factoryContext, secondaryKeyProvided = true)
    )
  }

  test("incrementGetNextStubForKeyCallCount records the resourceType label correctly") {
    // Test plan: Verify that incrementGetNextStubForKeyCallCount increments the corresponding
    // label for the dicer_clerk_getnextstubforkey_call_count_total metric. Verify by calling it
    // with each resource type a distinct number of times and assert that each resource type's
    // counter reflects exactly the number of calls made.
    val factoryContext: String = "clerk"

    /**
     * Returns the value of the getNextStubForKey call-count metric for the given target, factory
     * context, and returned resource type.
     */
    def getNextStubForKeyCallCount(
        target: Target,
        factoryContext: String,
        resourceType: String): Double = {
      MetricUtils.getMetricValue(
        registry,
        "dicer_clerk_getnextstubforkey_call_count_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "factoryContext" -> factoryContext,
          "resourceType" -> resourceType
        )
      )
    }

    // Verify: Initial values are 0 for every resource type.
    assertResult(0.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "assigned"))
    assertResult(0.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "fallback"))
    assertResult(0.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "randomAssigned"))
    assertResult(0.0)(
      getNextStubForKeyCallCount(defaultTarget, factoryContext, "assignedAfterTokenReset")
    )

    // Setup: Record a distinct number of calls per resource type.
    val clerkMetrics = new ClerkMetrics(defaultTarget, factoryContext)
    clerkMetrics.incrementGetNextStubForKeyCallCount(ResourceType.AssignedResource)
    for (_ <- 0 until 2) {
      clerkMetrics.incrementGetNextStubForKeyCallCount(ResourceType.FallbackResource)
    }
    for (_ <- 0 until 3) {
      clerkMetrics.incrementGetNextStubForKeyCallCount(ResourceType.RandomAssignedResource)
    }
    for (_ <- 0 until 4) {
      clerkMetrics.incrementGetNextStubForKeyCallCount(ResourceType.AssignedResourceAfterTokenReset)
    }

    // Verify: Each resource type's counter reflects exactly its own calls.
    assertResult(1.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "assigned"))
    assertResult(2.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "fallback"))
    assertResult(3.0)(getNextStubForKeyCallCount(defaultTarget, factoryContext, "randomAssigned"))
    assertResult(4.0)(
      getNextStubForKeyCallCount(defaultTarget, factoryContext, "assignedAfterTokenReset")
    )
  }

  test(
    "observeGetNextStubForKeyPickedResourceCount records each observation"
  ) {
    // Test plan: Verify that observeGetNextStubForKeyPickedResourceCount records each call into
    // the histogram. Verify by calling with different values and assert that the total sum matches
    // the sum of all observations.
    val factoryContext: String = "clerk"

    /**
     * Returns the sum of the observations recorded in the getNextStubForKey picked-resource-count
     * histogram for the given target and factory context.
     */
    def getNextStubForKeyPickedResourceCountHistogramSum(
        target: Target,
        factoryContext: String): Double = {
      MetricUtils.getHistogramSum(
        registry,
        "dicer_clerk_getnextstubforkey_picked_resource_count_histogram",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "factoryContext" -> factoryContext
        )
      )
    }

    // Verify: Initial sum is 0.
    assertResult(0.0)(
      getNextStubForKeyPickedResourceCountHistogramSum(defaultTarget, factoryContext)
    )

    // Setup: Observe serveral samples.
    val clerkMetrics = new ClerkMetrics(defaultTarget, factoryContext)
    clerkMetrics.observeGetNextStubForKeyPickedResourceCount(1)
    clerkMetrics.observeGetNextStubForKeyPickedResourceCount(5)
    clerkMetrics.observeGetNextStubForKeyPickedResourceCount(15)

    // Verify: The histogram recorded one observation per call, summing to the observed total.
    assertResult(21.0)(
      getNextStubForKeyPickedResourceCountHistogramSum(defaultTarget, factoryContext)
    )
  }
}

/** Test suite that validates metric functionality for Kubernetes Targets. */
class KubernetesTargetClerkMetricsSuite extends ClerkMetricsSuiteBase {
  override protected def defaultTarget: Target =
    Target.createKubernetesTarget(
      URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"),
      getSafeName
    )
}

/** Test suite that validates metric functionality for App Targets. */
class AppTargetClerkMetricsSuite extends ClerkMetricsSuiteBase {
  override protected def defaultTarget: Target =
    Target.createAppTarget(getSafeAppTargetName, "instance-id")
}
