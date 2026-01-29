package com.databricks.caching.util

import scala.collection.JavaConverters._
import scala.collection.mutable

import io.prometheus.client.Collector.MetricFamilySamples
import io.prometheus.client.Collector.MetricFamilySamples.Sample
import io.prometheus.client.CollectorRegistry
import org.scalatest.Assertions

import com.databricks.caching.util.AssertMacros.iassert

/** Common metric testing utilities for Caching services. */
object MetricUtils extends Assertions {

  /**
   * A test helper class for tracking changes in the value computed by a function.
   *
   * This class is useful, for example, to test that a metric has been incremented by a specified
   * amount after an operation:
   *
   * {{{
   *   val writeCount = ChangeTracker[Int] { () =>
   *     getWriteCountMetric()
   *   }
   *   doWrite()
   *   assert(writeCount.totalChange() == 1) // should have been incremented once.
   *   doWrite()
   *   assert(writeCount.totalChange() == 2) // should have been incremented a total of 2 times.
   * }}}
   *
   * Callers should use [[ChangeTracker.apply]] to create instances of this class.
   *
   * @param func The function that returns the value for which to track changes. The function
   *             should have no side effects because it may be evaluated multiple times.
   * @param calculateChange A function that computes the change between the current and the initial
   *                        value. The companion object provides an implementation for all numerics.
   * @param num An implicit Numeric instance providing operations for type `T` such as subtraction
   *            on values of type `T`.   */
  class ChangeTracker[T] private (func: () => T, calculateChange: (T, T) => T) {

    /** The value of the thunk at the time when this change tracker was created. */
    private val initialValue: T = func.apply()

    /** The change between the current value of `thunk` and the initial value. */
    def totalChange(): T = {
      val currentValue: T = func.apply() // Re-evaluate the thunk to get the current value.
      calculateChange(currentValue, initialValue)
    }
  }
  object ChangeTracker {

    /**
     * Creates an instance of a ChangeTracker for the specified numeric thunk.
     *
     * @param numericFunc A function that returns the numeric value for which to track changes. The
     *                    function should have no side effects because it may be evaluated multiple
     *                    times.
     */
    def apply[T](numericFunc: () => T)(implicit num: Numeric[T]): ChangeTracker[T] =
      new ChangeTracker[T](numericFunc, num.minus)
  }

  /**
   * Implicit class that extends the Prometheus [[Sample]] class with convenience methods
   * for getting and matching label values.
   */
  implicit class SampleExtensions(sample: Sample) {

    /** A map consisting of the label names and corresponding values. */
    def labelMap: Map[String, String] =
      sample.labelNames.asScala.zip(sample.labelValues.asScala).toMap

    /** Get value of the label named labelName */
    def getLabelValue(labelName: String): String = {
      val result = labelMap(labelName)
      result
    }

    /**
     * Whether each the specified label entries is present in the label map.
     * (Note that this is allowed to be a subset of the full set of labels present in the map.)
     */
    def matchesLabels(expectedLabels: Map[String, String]): Boolean =
      expectedLabels.toSet.subsetOf(labelMap.toSet)
  }

  /**
   * Returns the current value of the [[PrefixLogger]] error count metric for the given combination
   * of `severity`, `errorCode`, and `prefix`.
   */
  def getPrefixLoggerErrorCount(
      severity: Severity.Value,
      errorCode: CachingErrorCode,
      prefix: String): Int =
    getMetricValue(
      CollectorRegistry.defaultRegistry,
      "caching_errors",
      Map(
        "severity" -> severity.toString,
        "error_code" -> errorCode.toString,
        "prefix" -> prefix,
        "owner_team" -> errorCode.alertOwnerTeam.toString
      )
    ).toInt

  /**
   * Returns the value for a given metric with its label names and values, or 0.0 if it does not
   * exist.
   */
  def getMetricValue(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Double = {
    getMetricValueOpt(registry, metric, labels).getOrElse(0.0)
  }

  /**
   * Returns the value for a given metric with its label names and values, or `None` if it does not
   * exist.
   */
  def getMetricValueOpt(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Option[Double] = {
    val samples: Seq[Sample] = getMetricSamples(registry, metric)
    if (samples.isEmpty) {
      None // Metric does not exist.
    } else {
      val sampleOpt: Option[Sample] =
        samples.find(sample => sample.matchesLabels(labels))
      if (sampleOpt.isDefined) {
        Some(sampleOpt.get.value)
      } else {
        None // Metric with the given labels does not exist.
      }
    }
  }

  /**
   * Returns all of the samples for the given Prometheus metric.
   * Unlike `com.databricks.logging.UsageLoggingTestUtil`, this accesses actual Prometheus records
   * and does not rely upon usage logging.
   */
  def getMetricSamples(registry: CollectorRegistry, metric: String): Seq[Sample] = {
    val metricName: String = RegistryHelper.getMetricName(registry, metric)
    val metricSamplesIter: Iterator[MetricFamilySamples] =
      registry.filteredMetricFamilySamples(Set(metricName).asJava).asScala
    if (!metricSamplesIter.hasNext) {
      Seq.empty // Metric does not exist.
    } else {
      // TODO(<internal bug>): Fix buggy assumption that there is exactly one Collector for a given
      //  metric name and remove related assert below.
      val samples: Seq[Sample] = metricSamplesIter.next().samples.asScala.toSeq
      iassert(!metricSamplesIter.hasNext)
      samples
    }
  }

  /**
   * Returns all of the samples for the given Prometheus metric that match the given `sampleName`
   * and labels.
   */
  def getMetricSamplesFilteredByLabels(
      registry: CollectorRegistry,
      metric: String,
      sampleName: String,
      labels: Map[String, String]): Seq[Sample] = {
    getMetricSamples(registry, metric)
      .filter { sample =>
        sample.name == sampleName && sample.matchesLabels(labels)
      }
  }

  /** Returns the buckets samples for the given histogram metric and operations, labels, etc. */
  def getHistogramBucketSamples(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Seq[Sample] = {
    val bucketSampleName = metric + "_bucket"
    getMetricSamplesFilteredByLabels(
      registry,
      bucketSampleName,
      bucketSampleName,
      labels
    )
  }

  /**
   * Returns the buckets samples for the given histogram gauge metric and operations, labels, etc.
   */
  def getHistogramGaugeBucketSamples(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Seq[Sample] = {
    val bucketSampleName = metric + "_bucket"
    getMetricSamplesFilteredByLabels(
      registry,
      metric = bucketSampleName,
      sampleName = bucketSampleName,
      labels = labels
    ).sorted {
      Ordering.by((sample: Sample) => {
        val leLabelValue: String = sample.labelValues.get(sample.labelNames.indexOf("le"))
        leLabelValue match {
          case "+Inf" => Double.PositiveInfinity
          case _ => leLabelValue.toDouble
        }
      })
    }
  }

  /**
   * Returns the count of observations for the specified histogram metric and labels,
   * from the counter in the histogram with the "_count" suffix.
   */
  def getHistogramCount(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Int = {
    val countSampleName = metric + "_count"
    val allSamples: Seq[Sample] = getMetricSamples(registry, countSampleName)
    val samples = allSamples.filter { sample =>
      sample.name == countSampleName && sample.matchesLabels(labels)
    }
    samples.map(sample => sample.value.toInt).sum
  }

  /**
   * Returns the sum of observations for the specified histogram metric and labels,
   * from the counter in the histogram with the "_sum" suffix.
   */
  def getHistogramSum(
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Double = {
    val sumSampleName = metric + "_sum"
    val allSamples: Seq[Sample] = getMetricSamples(registry, sumSampleName)
    val samples = allSamples.filter { sample =>
      sample.name == sumSampleName && sample.matchesLabels(labels)
    }
    samples.map(sample => sample.value).sum
  }

  /**
   * REQUIRES: `histogramBuckets` must be sorted and have only finite boundaries.
   * `histogramBuckets` should be the bucket used to construct a histogram. For all histogram
   * constructions, we specify buckets that are sorted and have finite boundaries (we rely on
   * Prometheus to add the "+Inf" bucket). As a result, this requirement is enforced to align with
   * histogram construction.
   *
   * Computes the cumulative count of samples in each histogram bucket based on the provided
   * samples.
   *
   * Usage example:
   * bucket: [10, 20, 30, 40].
   * samples: [2, 4, 8, 16, 32, 64].
   * returns: [3, 4, 4, 5, 6].
   *
   * bucket: [10, 20, 30, 40].
   * samples: [].
   * returns: [0, 0, 0, 0, 0].
   */
  def computeHistogramBucketCounts(
      histogramBuckets: Seq[Double],
      samples: Seq[Double]): Seq[Int] = {
    assert(histogramBuckets.sorted == histogramBuckets, "histogramBuckets must be sorted.")
    assert(
      !histogramBuckets.contains(Double.PositiveInfinity),
      "histogramBuckets must only have finite boundaries."
    )
    // Add a infinity bucket.
    val bucketsWithInf: Seq[Double] = histogramBuckets :+ Double.PositiveInfinity
    val sortedSample: Seq[Double] = samples.sorted
    val counts = mutable.ArrayBuffer.fill(bucketsWithInf.length)(0)

    // Both `sortedSample` and `histogramBuckets` are sorted, so use two monotonically increasing
    // pointers to iterate through them and determine which sample falls into which bucket.
    var bucketIdx, sampleIdx: Int = 0
    while (sampleIdx < sortedSample.length) {
      if (sortedSample(sampleIdx) <= bucketsWithInf(bucketIdx)) {
        counts(bucketIdx) += 1
        sampleIdx += 1
      } else {
        bucketIdx += 1
      }
    }
    // Compute the cumulative count.
    val cumulativeCounts = mutable.ArrayBuffer.fill(counts.length)(0)
    for (idx: Int <- counts.indices) {
      if (idx == 0) {
        cumulativeCounts(idx) = counts(idx)
      } else {
        cumulativeCounts(idx) = cumulativeCounts(idx - 1) + counts(idx)
      }
    }
    cumulativeCounts.toVector
  }

  /**
   * REQUIRES: `expectedBuckets` must be sorted and have only finite boundaries.
   * `expectedBuckets` should be the bucket used to construct a histogram. For all histogram
   * constructions, we specify buckets that are sorted and have finite boundaries (we rely on
   * Prometheus to add the "+Inf" bucket). As a result, this requirement is enforced to align with
   * histogram construction.
   *
   * Asserts that a samples for the given histogram metric and labels match `expectedBuckets` and
   * `expectedBucketCounts`.
   *
   * Usage example:
   * samples: [2, 4, 8, 16, 32, 64].
   * expectedBuckets: [10, 20, 30, 40].
   * expectedBucketCounts: [3, 4, 4, 5, 6].
   * This function only returns successfully if the histogram has the expected buckets and counts.
   *
   * @param expectedBuckets the expected bucket boundaries for the observed metrics.
   * @param expectedBucketCounts the expected cumulative counts within each bucket. The 0th such
   *                             count is all observed values <= expectedBuckets[0], and the
   *                             count is all observed values <= Infinity.
   * @param registry the Prometheus registry from which to obtain the metric values.
   */
  def assertHistogramBucketsAndCounts(
      expectedBuckets: Seq[Double],
      expectedBucketCounts: Seq[Int],
      registry: CollectorRegistry,
      metric: String,
      labels: Map[String, String]): Unit = {
    assert(expectedBuckets.sorted == expectedBuckets, "expectedBuckets must be sorted.")
    assert(
      !expectedBuckets.contains(Double.PositiveInfinity),
      "expectedBuckets must only have finite boundaries."
    )
    val samples: Seq[Sample] = getHistogramBucketSamples(registry, metric, labels)
    if (samples.isEmpty) {
      assertResult(
        expected = 0,
        "No samples found for the histogram, but the list of expected samples is not empty."
      )(expectedBucketCounts.sum)
    } else {
      // Assert that the bucket counts are as expected.
      val bucketCounts = samples.map(getSampleValueAsInt)
      assertResult(
        expectedBucketCounts,
        "Bucket counts in the histogram do not match the expected bucket counts."
      )(
        // HistogramGauge may not return samples in the bucket order. Sorting by value restores
        // the bucket order because histogram bucket counts are cumulative (non-decreasing),
        // so numerical order equals bucket order.
        bucketCounts.sorted
      )

      // Assert that the bucket boundaries are as expected.
      val bucketBoundaries = samples.map(sample => sample.getLabelValue("le")).toSet
      // Infinity in the bucket boundary is represented as "+Inf" instead of "Infinity".
      val expectedBucketBoundaries = expectedBuckets.map(_.toString).toSet + "+Inf"
      assertResult(
        expectedBucketBoundaries,
        "Bucket boundaries in the the histogram do not match the expected boundaries."
      )(bucketBoundaries)
    }
  }

  /** Gets the value of a sample (e.g. a bucket count) as an int. */
  private def getSampleValueAsInt(sample: Sample): Int = sample.value.toInt
}
