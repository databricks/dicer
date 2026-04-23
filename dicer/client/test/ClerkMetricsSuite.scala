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

  /**
   * Returns the value of the `dicer_clerk_getstubforkey_call_count_total` counter for the given
   * target.
   */
  private def getGetStubForKeyCallCount(target: Target): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_clerk_getstubforkey_call_count_total",
      Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel
      )
    )
  }

  test("incrementClerkGetStubForKeyCallCount increments counter correctly") {
    // Test plan: Verify that calling incrementClerkGetStubForKeyCallCount increments the
    // Prometheus counter correctly. First verify the initial value is 0, then increment
    // the counter multiple times and verify the accumulated value.

    // Verify: The initial counter value is 0 for a fresh target.
    assertResult(0.0)(getGetStubForKeyCallCount(defaultTarget))

    // Setup: Create a ClerkMetrics instance for the target and increment the counter once.
    val clerkMetrics = new ClerkMetrics(defaultTarget)
    clerkMetrics.incrementClerkGetStubForKeyCallCount()

    // Verify: The counter is incremented to 1.
    assertResult(1.0)(getGetStubForKeyCallCount(defaultTarget))

    // Setup: Increment the counter multiple more times.
    for (_ <- 0 until 9) {
      clerkMetrics.incrementClerkGetStubForKeyCallCount()
    }

    // Verify: The counter accumulated correctly.
    assertResult(10.0)(getGetStubForKeyCallCount(defaultTarget))
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
