package com.databricks.dicer.external

import scala.concurrent.duration.Duration

import com.databricks.caching.util.{AssertionWaiter, TestUtils}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.common.{
  ClientType,
  Generation,
  InternalDicerTestEnvironment,
  SubscriberHandler,
  SubscriberHandlerMetricUtils
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.testing.DatabricksTest

/**
 * Abstract base class for testing [[SliceLookupCache]] behavior in Clerk creation. These tests
 * verify that SliceLookup instances are properly cached and reused when creating multiple Clerks
 * for the same target.
 *
 * This class contains the test cases that apply to Scala and Rust. Tests that rely on the
 * scala-only `ClerkImpl.createFor*` factories are in [[ScalaSingletonSliceLookupSuite]].
 *
 * `createForDataPlaneDirectClerk` is tested in [[DataPlaneDirectClerkAccessorSuiteBase]].
 */
abstract class SingletonSliceLookupSuiteBase extends DatabricksTest with TestName {

  /** Dummy Slicelet ports for tests. No Slicelet listens on these. */
  private val DUMMY_SLICELET_PORT_A: Int = 1236
  private val DUMMY_SLICELET_PORT_B: Int = 1237

  /** The test environment used for all the tests. */
  protected final val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create()

  /** Used to generate unique target names. */
  private var targetSequenceNumber: Int = 0

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  /**
   * Returns a unique target name each time the method is called. These generated names
   * are <= 42 characters long, which is a requirement for AppTargets.
   */
  protected final def getUniqueTargetName: String = {
    targetSequenceNumber += 1
    "target" + targetSequenceNumber.toString
  }

  /**
   * Creates a Clerk for the given target with SliceLookup sharing enabled.
   *
   * Note: Each new Clerk created has a new stub factory instance.
   *
   * @param target       The target to watch.
   * @param sliceletPort The Slicelet port the Clerk watches.
   */
  protected def createSharingClerk(target: Target, sliceletPort: Int): ClerkHarness

  /**
   * Reads and returns the value of the Prometheus metric with the given name and labels. Returns
   * zero if the metric has never been recorded with this set of label values.
   *
   * @note labels is a vector of (label name, label value) pairs. This is a vector because the Rust
   *       test harness does a rudimentary string matching to get the metric values from the info
   *       service page. The order must match the Rust struct field declaration order.
   */
  protected def readPrometheusMetric(metricName: String, labels: Vector[(String, String)]): Double

  /** Creates a [[ChangeTracker]] for the SliceLookup count metric for the given target. */
  protected final def createSliceLookupCountTracker(target: Target): ChangeTracker[Double] =
    ChangeTracker { () =>
      readPrometheusMetric(
        "dicer_client_num_slice_lookups_total",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> ClientType.Clerk.toString
        )
      )
    }

  /**
   * Creates a [[ChangeTracker]] for the `dicer_client_num_slice_lookup_cache_hits_total` metric
   * for the given target and `configMatched` value.
   */
  protected final def createCacheResultTracker(
      target: Target,
      configMatched: Boolean): ChangeTracker[Double] =
    ChangeTracker { () =>
      readPrometheusMetric(
        "dicer_client_num_slice_lookup_cache_hits_total",
        Vector(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "configMatched" -> configMatched.toString
        )
      )
    }

  test("Clerk creation reuses SliceLookup for same Target and same SliceLookupConfig") {
    // Test plan: Verify that creating multiple Clerks with the same (Target, SliceLookupConfig)
    // reuses the same SliceLookup instance, even when different stub factory instances are used.
    // Create N Clerks with the same config and check that the SliceLookup count increments once
    // and cache hits are recorded for the remaining N-1.

    val target: Target = Target(getUniqueTargetName)

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create a number of Clerks with the same (Target, SliceLookupConfig) but different stub
    // factory instances. This verifies that lookup caching works correctly even when stub
    // factories differ (which is common in production, where anonymous functions are used).
    val numClerks: Int = 10
    for (_: Int <- 0 until numClerks) {
      createSharingClerk(target, DUMMY_SLICELET_PORT_A)
    }

    // Verify: there is only one SliceLookup created for the (Target, SliceLookupConfig).
    assert(sliceLookupTracker.totalChange() == 1)

    // Verify: cache hit metric should be incremented (numClerks - 1) times, because the first
    // Clerk creation doesn't count as a cache hit.
    assert(cacheHitTracker.totalChange() == numClerks - 1)
  }

  test(
    "Clerk creation does not reuse SliceLookup for same Target but different SliceLookupConfigs"
  ) {
    // Test plan: Verify that Clerks for the same Target but different SliceLookupConfigs do not
    // share a SliceLookup instance. Using two distinct ports, create Clerks for the same Target
    // with different configs. Verify that the SliceLookup count is incremented twice, cache hits
    // are recorded for each config, and config mismatches are tracked.

    val target: Target = Target(getUniqueTargetName)

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val configMismatchTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = false)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create first Clerk with config A - no cache hit, no config mismatch, new lookup.
    createSharingClerk(target, DUMMY_SLICELET_PORT_A)
    assert(cacheHitTracker.totalChange() == 0)
    assert(configMismatchTracker.totalChange() == 0)
    assert(sliceLookupTracker.totalChange() == 1)

    // Create second Clerk with config A - cache hit, no config mismatch, no new lookup.
    createSharingClerk(target, DUMMY_SLICELET_PORT_A)
    assert(cacheHitTracker.totalChange() == 1)
    assert(configMismatchTracker.totalChange() == 0)
    assert(sliceLookupTracker.totalChange() == 1)

    // Create third Clerk with config B - no cache hit, config mismatch, new lookup.
    createSharingClerk(target, DUMMY_SLICELET_PORT_B)
    assert(cacheHitTracker.totalChange() == 1)
    assert(configMismatchTracker.totalChange() == 1)
    assert(sliceLookupTracker.totalChange() == 2)

    // Create fourth Clerk with config B - cache hit, no config mismatch, no new lookup.
    createSharingClerk(target, DUMMY_SLICELET_PORT_B)
    assert(cacheHitTracker.totalChange() == 2)
    assert(configMismatchTracker.totalChange() == 1)
    assert(sliceLookupTracker.totalChange() == 2)
  }

  test("E2E: Multiple Clerks sharing SliceLookup all receive assignments from Slicelet") {
    // Test plan: Create a Slicelet, then create multiple Clerks that watch the Slicelet with
    // lookup caching enabled. Verify that:
    // 1. All Clerks receive the same assignment from the Slicelet.
    // 2. Only one SliceLookup is created (verified via metrics).
    // 3. The Slicelet sees only one Clerk subscriber (since they share the same SliceLookup).

    val target: Target = Target(getUniqueTargetName)

    // Create and start a Slicelet.
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    // Wait for the Slicelet to receive an initial assignment.
    AssertionWaiter("Slicelet receives initial assignment").await {
      assert(slicelet.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create multiple Clerks with the same config (caching enabled), all watching the Slicelet.
    val numClerks: Int = 3
    val clerks: Seq[ClerkHarness] = (0 until numClerks).map { (_: Int) =>
      createSharingClerk(target, slicelet.impl.forTest.sliceletPort)
    }

    // Verify: Only one SliceLookup was created for all Clerks.
    assert(sliceLookupTracker.totalChange() == 1)

    // Wait for all Clerks to be ready and receive assignments.
    for (clerk: ClerkHarness <- clerks) {
      TestUtils.awaitResult(clerk.ready, Duration.Inf)
    }

    // Verify: All Clerks received the same assignment.
    AssertionWaiter("All Clerks receive same assignment").await {
      val generationOpts: Seq[Option[Generation]] =
        clerks.map((clerk: ClerkHarness) => clerk.getLatestGenerationOpt)
      assert(generationOpts.forall((generationOpt: Option[Generation]) => generationOpt.isDefined))
      val generations: Seq[Generation] = generationOpts.flatten
      assert(generations.distinct.size == 1, s"Expected same generation, got: $generations")
    }

    // Verify: The Slicelet sees only one Clerk subscriber (since they share the same SliceLookup).
    AssertionWaiter("Slicelet sees single Clerk subscriber").await {
      // Scala and Rust Clerks report different `LATEST_VERSION` values, so the version label is
      // intentionally unspecified. This is safe because every Clerk in a given suite runs the same
      // client code version.
      val numClerkSubscribers: Long = SubscriberHandlerMetricUtils
        .getNumClerksByHandler(SubscriberHandler.Location.Slicelet, target)
      assert(
        numClerkSubscribers == 1,
        s"Expected 1 Clerk subscriber, got $numClerkSubscribers"
      )
    }

    // Clean up: Stop all Clerks.
    for (clerk: ClerkHarness <- clerks) {
      clerk.stop()
    }
  }

}
