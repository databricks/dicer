package com.databricks.caching.util

import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.duration.DurationInt

import io.prometheus.client.Counter

/**
 * A safety wrapper around [[io.prometheus.client.Counter]] that suppresses negative increments,
 * treating them as increments of 0, instead of letting them throw [[IllegalArgumentException]].
 * All other [[io.prometheus.client.Counter]] behavior is unchanged.
 *
 * Thread-safe, derived from the underlying Prometheus counter.
 *
 * @param prometheusCounter underlying Prometheus Counter.
 * @param metricName name of the metric.
 * @param labelNames names of the metric's labels.
 */
@ThreadSafe
class SafeCounter private (
    prometheusCounter: Counter,
    metricName: String,
    labelNames: Seq[String]) {

  /** Returns the [[SafeCounter.Child]] for the given tuple of `labelValues`. */
  @throws[IllegalArgumentException](
    "if `labelValues` has a different number of labels than the number of label names"
  )
  def labels(labelValues: String*): SafeCounter.Child = {
    new SafeCounter.Child(
      prometheusCounter.labels(labelValues: _*),
      metricName,
      labelNames,
      labelValues
    )
  }

  /** Removes the Child with the given labels. Any references to the Child are invalidated. */
  def remove(labelValues: String*): Unit = prometheusCounter.remove(labelValues: _*)

  /** Removes all children. Any references to any children are invalidated. */
  def clear(): Unit = prometheusCounter.clear()
}

object SafeCounter {

  /**
   * REQUIRES: `metricName` is not empty.
   * REQUIRES: `help` is not empty.
   *
   * Creates a [[SafeCounter]] backed by a fresh Prometheus [[Counter]] registered in the
   * default `CollectorRegistry`.
   *
   * @param metricName name of the metric.
   * @param help help text for the metric, for debugging and to help readers of dashboards.
   * @param labelNames names of the labels to include in the metric.
   */
  def create(metricName: String, help: String, labelNames: Seq[String]): SafeCounter = {
    require(metricName.nonEmpty, "metricName must be non-empty")
    require(help.nonEmpty, "help must be non-empty")

    val prometheusCounter = Counter
      .build()
      .name(metricName)
      .help(help)
      .labelNames(labelNames: _*)
      .register()
    new SafeCounter(prometheusCounter, metricName, labelNames)
  }

  /**
   * REQUIRES: `labelNames` and `labelValues` have the same length.
   *
   * A safety wrapper around [[io.prometheus.client.Counter.Child]], which corresponds to a single
   * label-value tuple. Carries the same negative-suppression semantics as [[SafeCounter]].
   *
   * Thread-safe. Same reason as [[SafeCounter]] - underlying Prometheus counter is thread-safe.
   *
   * @param prometheusCounterChild underlying Prometheus Counter.Child.
   * @param metricName name of the metric.
   * @param labelNames names of the metric's labels.
   * @param labelValues this child's label values.
   */
  @ThreadSafe
  class Child private[SafeCounter] (
      prometheusCounterChild: Counter.Child,
      metricName: String,
      labelNames: Seq[String],
      labelValues: Seq[String]) {

    require(
      labelNames.size == labelValues.size,
      s"labelNames (${labelNames.size}) and labelValues (${labelValues.size}) have unequal lengths"
    )

    /** Increments this counter by 1. */
    def inc(): Unit = prometheusCounterChild.inc()

    /** Increments this counter by `amount`. */
    def inc(amount: Double): Unit = {
      if (amount < 0) {
        val labelPairs: String =
          labelNames
            .zip(labelValues)
            .map { pair =>
              val (name, value): (String, String) = pair
              s"$name=$value"
            }
            .mkString(", ")
        Child.logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.SAFE_COUNTER_SUPPRESSED_NEGATIVE_INCREMENT,
          s"suppressed negative increment $amount for metric $metricName {$labelPairs}",
          every = 30.seconds
        )
      } else {
        prometheusCounterChild.inc(amount)
      }
    }

    /** Returns the current value of this counter. */
    def get(): Double = prometheusCounterChild.get()
  }

  object Child {

    /** Logger shared across all [[Child]] instances. Emits suppression alerts. */
    private val logger: PrefixLogger = PrefixLogger.create(classOf[SafeCounter], prefix = "")
  }
}
