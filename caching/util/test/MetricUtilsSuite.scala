package com.databricks.caching.util
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.testing.DatabricksTest
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import org.scalatest.exceptions.TestFailedException

import com.databricks.caching.util.MetricUtils.ChangeTracker

class MetricUtilsSuite extends DatabricksTest with TestName {

  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /**
   * Creates a histogram with a distinct name so that different tests don't interfere with each
   * other.
   *
   * @param bucket the bucket boundaries for the histogram.
   * @param suffix a unique identifier used to differentiate histograms created within the same
   *               test.
   */
  private def createHistogram(bucket: Seq[Double], suffix: Int): Histogram = {
    Histogram
      .build()
      .name(s"${getSafeMetricName}_$suffix")
      .help("A histogram for testing purposes.")
      .labelNames("test_label")
      .buckets(bucket: _*)
      .register()
  }

  /**
   * Returns a safe metric name for the current test.
   * The method replaces any "-" characters in the test name with "_" to comply with Prometheus
   * metric naming conventions.
   */
  private def getSafeMetricName: String = {
    getSafeName.replace("-", "_")
  }

  test("computeHistogramBucketCounts computes the correct bucket counts") {
    // Test plan: Verify that `computeHistogramBucketCounts` correctly computes the bucket counts
    // in the histogram. Doing this by creating histograms with different buckets, inserting
    // samples, and verifying that `computeHistogramBucketCounts` produces the expected cumulative
    // bucket counts.
    val samples: Seq[Double] = Seq(-1.1, 2, 3, 4, 5, 5, 5, 5, 10, 50.5, 100)
    val bucket1: Seq[Double] = Seq(10, 20, 30, 40, 50, Double.PositiveInfinity)
    val bucket2: Seq[Double] = Seq(2, 4, 8, 16, 32, 64)
    val bucket3: Seq[Double] = Seq(0.1, 0.2, 0.4, 0.6, 0.8)
    val bucket4: Seq[Double] = Seq(1000, 2000)
    val bucket5: Seq[Double] = Seq(10, 8, 6, 4, 2)

    assertThrow[TestFailedException]("must only have finite boundaries") {
      assertResult(Seq.empty)(MetricUtils.computeHistogramBucketCounts(bucket1, samples))
    }
    assertResult(Seq(2, 4, 8, 9, 9, 10, 11))(
      MetricUtils.computeHistogramBucketCounts(bucket2, samples)
    )
    assertResult(Seq(1, 1, 1, 1, 1, 11))(MetricUtils.computeHistogramBucketCounts(bucket3, samples))
    assertResult(Seq(11, 11, 11))(MetricUtils.computeHistogramBucketCounts(bucket4, samples))

    // Empty sample.
    assertResult(Seq(0, 0, 0))(
      MetricUtils.computeHistogramBucketCounts(bucket4, samples = Seq.empty)
    )
    assertThrow[TestFailedException]("must be sorted") {
      assertResult(Seq.empty)(MetricUtils.computeHistogramBucketCounts(bucket5, samples))
    }
  }

  test("assertHistogramBucketsAndCounts correctly compares buckets and counts against histograms") {
    // Test plan: Verify that `assertHistogramBucketsAndCounts` correctly compares the provided
    // buckets and counts against those in the histogram. This is done by creating a histogram with
    // the bucket1 and bucket2, inserting samples into the histogram, and then ensuring that
    // `assertHistogramBucketsAndCounts` only succeeds if the provided buckets and counts match the
    // actual histogram.
    val samples: Seq[Double] = Seq(-1.1, 2, 3, 4, 5, 5, 5, 5, 10, 50.5, 100)
    val bucket1: Seq[Double] = Seq(10, 20, 30, 40, 50)
    val bucket2: Seq[Double] = Seq(11, 22, 30, 40, 50)

    val histogram1: Histogram = createHistogram(bucket1, suffix = 1)
    val histogram2: Histogram = createHistogram(bucket2, suffix = 2)
    // Create an empty histogram.
    createHistogram(bucket2, suffix = 3)

    for (sample: Double <- samples) {
      histogram1.labels("foo").observe(sample)
      histogram2.labels("foo").observe(sample)
    }

    val metric: String = getSafeMetricName

    MetricUtils.assertHistogramBucketsAndCounts(
      bucket1,
      MetricUtils.computeHistogramBucketCounts(bucket1, samples),
      registry,
      s"${metric}_1",
      Map("test_label" -> "foo")
    )
    MetricUtils.assertHistogramBucketsAndCounts(
      bucket2,
      MetricUtils.computeHistogramBucketCounts(bucket2, samples),
      registry,
      s"${metric}_2",
      Map("test_label" -> "foo")
    )
    MetricUtils.assertHistogramBucketsAndCounts(
      bucket2,
      MetricUtils.computeHistogramBucketCounts(bucket2, samples = Seq.empty),
      registry,
      s"${metric}_3",
      Map("test_label" -> "foo")
    )

    // Mismatched buckets or counts should fail.
    assertThrow[TestFailedException](
      "Bucket counts in the histogram do not match the expected bucket counts"
    ) {
      MetricUtils.assertHistogramBucketsAndCounts(
        bucket1,
        MetricUtils.computeHistogramBucketCounts(bucket1, samples = Seq(1, 2, 4)),
        registry,
        s"${metric}_1",
        Map("test_label" -> "foo")
      )
    }

    assertThrow[TestFailedException](
      "Bucket boundaries in the the histogram do not match the expected boundaries"
    ) {
      MetricUtils.assertHistogramBucketsAndCounts(
        bucket2,
        MetricUtils.computeHistogramBucketCounts(bucket1, samples),
        registry,
        s"${metric}_1",
        Map("test_label" -> "foo")
      )
    }

    assertThrow[TestFailedException]("but the list of expected samples is not empty") {
      MetricUtils.assertHistogramBucketsAndCounts(
        bucket1,
        expectedBucketCounts = Seq(1),
        registry,
        s"${metric}_3",
        Map("test_label" -> "foo")
      )
    }

    assertThrow[TestFailedException]("must be sorted") {
      MetricUtils.assertHistogramBucketsAndCounts(
        expectedBuckets = Seq(3, 1),
        expectedBucketCounts = Seq(1),
        registry,
        s"${metric}_3",
        Map("test_label" -> "foo")
      )
    }

    assertThrow[TestFailedException]("must only have finite boundaries") {
      MetricUtils.assertHistogramBucketsAndCounts(
        expectedBuckets = Seq(1, 3, Double.PositiveInfinity),
        expectedBucketCounts = Seq(1),
        registry,
        s"${metric}_3",
        Map("test_label" -> "foo")
      )
    }
  }

  test("Different labels do not affect each other") {
    // Test plan: Verify that samples of different labels do not affect each other. Doing this by
    // creating a histogram, and inserting samples with labels `foo` and `bar`. Then, verify that
    // `assertHistogramContainsSamples` correctly asserts the buckets and counts for each label.
    val samplesFoo: Seq[Double] = Seq(1, 2, 3, 4, 5, 10, 50, 100)
    val samplesBar: Seq[Double] = Seq(12, 10, 8, 6, 4, 2)
    val bucket: Seq[Double] = Seq(10, 20, 30, 40, 50)
    val histogram: Histogram = createHistogram(bucket, suffix = 1)
    for (sample: Double <- samplesFoo) {
      histogram.labels("foo").observe(sample)
    }
    for (sample: Double <- samplesBar) {
      histogram.labels("bar").observe(sample)
    }
    val metric: String = getSafeMetricName
    MetricUtils.assertHistogramBucketsAndCounts(
      bucket,
      MetricUtils.computeHistogramBucketCounts(bucket, samplesFoo),
      registry,
      s"${metric}_1",
      Map("test_label" -> "foo")
    )
    MetricUtils.assertHistogramBucketsAndCounts(
      bucket,
      MetricUtils.computeHistogramBucketCounts(bucket, samplesBar),
      registry,
      s"${metric}_1",
      Map("test_label" -> "bar")
    )
    // Mixing the labels with wrong samples should fail.
    assertThrow[TestFailedException](
      "Bucket counts in the histogram do not match the expected bucket counts."
    ) {
      MetricUtils.assertHistogramBucketsAndCounts(
        bucket,
        MetricUtils.computeHistogramBucketCounts(bucket, samplesFoo),
        registry,
        s"${metric}_1",
        Map("test_label" -> "bar")
      )
    }
    assertThrow[TestFailedException](
      "Bucket counts in the histogram do not match the expected bucket counts."
    ) {
      MetricUtils.assertHistogramBucketsAndCounts(
        bucket,
        MetricUtils.computeHistogramBucketCounts(bucket, samplesBar),
        registry,
        s"${metric}_1",
        Map("test_label" -> "foo")
      )
    }
  }

  test("getHistogramCount and getHistogramSum") {
    // Test plan: Verify that `getHistogramCount` and `getHistogramSum` correctly extract the count
    // and sum from the histogram. This is done by creating a histogram, inserting samples, and then
    // verifying that `getHistogramCount` and `getHistogramSum` return the expected values.
    val samples: Seq[Double] = Seq(1.1, 2, 3, 4, 5, 10, 50, 100)
    val bucket: Seq[Double] = Seq(10, 20, 30, 40, 50)
    val histogram: Histogram = createHistogram(bucket, suffix = 1)
    for (sample: Double <- samples) {
      histogram.labels("foo").observe(sample)
    }
    val metric: String = getSafeMetricName
    assertResult(samples.length)(
      MetricUtils.getHistogramCount(registry, s"${metric}_1", Map("test_label" -> "foo"))
    )
    assertResult(samples.sum)(
      MetricUtils.getHistogramSum(registry, s"${metric}_1", Map("test_label" -> "foo"))
    )
  }

  test("getMetricValue and getMetricValueOpt") {
    // Test plan: Verify that `getMetricValue` and `getMetricValueOpt` correctly retrieve the value
    // from the metric. This is done by creating a gauge and a counter, inserting samples, and then
    // verifying that `getMetricValue` and `getMetricValueOpt` return the expected values.
    val gaugeName: String = getSafeMetricName
    val counterName: String = getSafeMetricName + "_counter"
    val gauge =
      Gauge.build().name(gaugeName).labelNames("l1", "l2").help("testing").register()
    val counter =
      Counter.build().name(counterName).labelNames("l1", "l2").help("testing").register()

    // Setup: insert samples into the gauge and counter.
    gauge.labels("A", "B").set(0.0) // Intentionally set to 0.0
    gauge.labels("A", "C").set(1.0)
    gauge.labels("A", "D").set(2.0)

    counter.labels("A", "B").inc()
    counter.labels("A", "C").inc(2.0)
    counter.labels("A", "D").inc(3.0)

    // Verify that `getMetricValue` return the expected values.
    // Existing labels.
    assert(MetricUtils.getMetricValue(registry, gaugeName, Map("l1" -> "A", "l2" -> "B")) == 0.0)
    assert(MetricUtils.getMetricValue(registry, gaugeName, Map("l1" -> "A", "l2" -> "C")) == 1.0)
    assert(MetricUtils.getMetricValue(registry, gaugeName, Map("l1" -> "A", "l2" -> "D")) == 2.0)

    assert(MetricUtils.getMetricValue(registry, counterName, Map("l1" -> "A", "l2" -> "B")) == 1.0)
    assert(MetricUtils.getMetricValue(registry, counterName, Map("l1" -> "A", "l2" -> "C")) == 2.0)
    assert(MetricUtils.getMetricValue(registry, counterName, Map("l1" -> "A", "l2" -> "D")) == 3.0)

    // Verify: `getMetricValue` returns 0.0 for metrics that do not exist.
    assert(
      MetricUtils.getMetricValue(registry, "non_existent_metric", Map("l1" -> "A", "l2" -> "B"))
      == 0.0
    )

    // Verify: `getMetricValue` returns 0.0 for labels that do not exist.
    assert(MetricUtils.getMetricValue(registry, gaugeName, Map("l1" -> "B", "l2" -> "C")) == 0.0)
    assert(MetricUtils.getMetricValue(registry, counterName, Map("l1" -> "B", "l2" -> "C")) == 0.0)

    // Verify: `getMetricValueOpt` returns the expected values.
    // Existing labels.
    assert(
      MetricUtils.getMetricValueOpt(registry, gaugeName, Map("l1" -> "A", "l2" -> "B")) ==
      Some(0.0)
    )
    assert(
      MetricUtils.getMetricValueOpt(registry, gaugeName, Map("l1" -> "A", "l2" -> "C")) ==
      Some(1.0)
    )
    assert(
      MetricUtils.getMetricValueOpt(registry, gaugeName, Map("l1" -> "A", "l2" -> "D")) ==
      Some(2.0)
    )

    assert(
      MetricUtils.getMetricValueOpt(registry, counterName, Map("l1" -> "A", "l2" -> "B"))
      == Some(1.0)
    )
    assert(
      MetricUtils.getMetricValueOpt(registry, counterName, Map("l1" -> "A", "l2" -> "C"))
      == Some(2.0)
    )
    assert(
      MetricUtils.getMetricValueOpt(registry, counterName, Map("l1" -> "A", "l2" -> "D"))
      == Some(3.0)
    )

    // Verify: `getMetricValueOpt` returns None for metrics that do not exist.
    assert(
      MetricUtils
        .getMetricValueOpt(registry, "non_existent_metric", Map("l1" -> "A", "l2" -> "B"))
        .isEmpty
    )

    // Verify: `getMetricValueOpt` returns None for labels that do not exist.
    assert(
      MetricUtils.getMetricValueOpt(registry, gaugeName, Map("l1" -> "B", "l2" -> "C")).isEmpty
    )
    assert(
      MetricUtils.getMetricValueOpt(registry, counterName, Map("l1" -> "B", "l2" -> "C")).isEmpty
    )
  }

  test("Change Tracker for Int") {
    // Test plan: Create a change tracker on top of an Int "counter". Verify that `change()`
    // returns the correct differences from the initial value as the counter changes.
    var counter: Int = 2

    val changeTracker = ChangeTracker[Int] { () =>
      counter
    }
    assert(changeTracker.totalChange() == 0) // 2 - 2 = 0

    counter = 3
    assert(changeTracker.totalChange() == 1) // 3 - 2 = 1

    counter = 10
    assert(changeTracker.totalChange() == 8) // 10 - 2 = 8

    counter = -2
    assert(changeTracker.totalChange() == -4) // (-2) - 2 = -4
  }

  test("Change Tracker for Double") {
    // Test plan: Create a change tracker on top of a Double. Verify that `change()`
    // returns the correct difference from the initial value when the counter changes.
    var counter: Double = 2.0

    val changeTracker = ChangeTracker[Double] { () =>
      counter
    }
    assert(changeTracker.totalChange() == 0.0)

    counter = 4.0
    assert(changeTracker.totalChange() == 2.0)
  }
}
