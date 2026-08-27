package com.databricks.dicer.client

import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.TestUtils.TestName
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

  /** Returns the value of `metric` for the given target and factory context. */
  private def getMetric(metric: String, target: Target, factoryContext: String): Double = {
    MetricUtils.getMetricValue(
      registry,
      metric,
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
      getMetric("dicer_clerk_created_total", defaultTarget, clerkFactoryContext)
    )
    assertResult(0.0)(
      getMetric("dicer_clerk_created_total", defaultTarget, shardedStubFactoryContext)
    )

    // Setup: Create two ClerkMetrics and record one creation each.
    val clerkMetrics = new ClerkMetrics(defaultTarget, clerkFactoryContext)
    val shardedStubMetrics = new ClerkMetrics(defaultTarget, shardedStubFactoryContext)
    clerkMetrics.incrementClerkCreatedCount()
    shardedStubMetrics.incrementClerkCreatedCount()

    // Verify: Each call recorded exactly one Clerk creation under its own factoryContext.
    assertResult(1.0)(
      getMetric("dicer_clerk_created_total", defaultTarget, clerkFactoryContext)
    )
    assertResult(1.0)(
      getMetric("dicer_clerk_created_total", defaultTarget, shardedStubFactoryContext)
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
