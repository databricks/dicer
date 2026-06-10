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
      getMetric(
        "dicer_clerk_getstubforkey_call_count_total",
        defaultTarget,
        clerkFactoryContext
      )
    )
    assertResult(0.0)(
      getMetric(
        "dicer_clerk_getstubforkey_call_count_total",
        defaultTarget,
        shardedStubFactoryContext
      )
    )

    // Setup: Create two ClerkMetrics instances and record getStubForKey calls; once for "clerk",
    // ten times for "shardedStub".
    val clerkMetrics = new ClerkMetrics(defaultTarget, clerkFactoryContext)
    val shardedStubMetrics = new ClerkMetrics(defaultTarget, shardedStubFactoryContext)
    clerkMetrics.incrementClerkGetStubForKeyCallCount()
    for (_ <- 0 until 10) {
      shardedStubMetrics.incrementClerkGetStubForKeyCallCount()
    }

    // Verify: The getStubForKey counts differentiate by factoryContext.
    assertResult(1.0)(
      getMetric(
        "dicer_clerk_getstubforkey_call_count_total",
        defaultTarget,
        clerkFactoryContext
      )
    )
    assertResult(10.0)(
      getMetric(
        "dicer_clerk_getstubforkey_call_count_total",
        defaultTarget,
        shardedStubFactoryContext
      )
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
