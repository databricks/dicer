package com.databricks.caching.util.javaapi;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.CollectorRegistry;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test utility for querying metric values from a Prometheus {@link CollectorRegistry}. */
public final class MetricUtils {

  private MetricUtils() {}

  /**
   * Returns the value of the metric with the given name and labels, or {@link Optional#empty()} if
   * the metric or label combination does not exist.
   *
   * @param registry the Prometheus collector registry to query.
   * @param metricName the Prometheus metric name.
   * @param labels the full set of label name-value pairs that must exactly match a sample. If only
   *     a subset of a sample's labels is provided, the sample will not match and {@link
   *     Optional#empty()} is returned.
   */
  public static Optional<Double> getMetricValueOpt(
      CollectorRegistry registry, String metricName, Map<String, String> labels) {
    List<Sample> samples = getMetricSamples(registry, metricName);
    for (Sample sample : samples) {
      // Since `labels` is expected to be a full set of label name-value pairs, there should be
      // at most one sample that matches the provided labels. If multiple samples match due to
      // inconsistent metric registration, only the first one is returned.
      if (matchesLabels(sample, labels)) {
        return Optional.of(sample.value);
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the count of observations for the specified histogram metric and labels, from the
   * counter in the histogram with the "_count" suffix.
   */
  public static double getHistogramCount(
      CollectorRegistry registry, String metric, Map<String, String> labels) {
    String countSampleName = metric + "_count";
    List<Sample> allSamples = getMetricSamples(registry, countSampleName);
    double sum = 0;
    for (Sample sample : allSamples) {
      if (sample.name.equals(countSampleName) && containsLabels(sample, labels)) {
        sum += sample.value;
      }
    }
    return sum;
  }

  /**
   * Returns all of the samples for the given Prometheus metric.
   *
   * <p>A metric name uniquely identifies a single {@link MetricFamilySamples} in the registry (the
   * Prometheus client rejects duplicate registrations), so at most one element is returned.
   */
  private static List<Sample> getMetricSamples(CollectorRegistry registry, String metricName) {
    Enumeration<MetricFamilySamples> families =
        registry.filteredMetricFamilySamples(Collections.singleton(metricName));
    if (!families.hasMoreElements()) {
      return Collections.emptyList();
    }
    return families.nextElement().samples;
  }

  /**
   * Whether each of the specified label entries is present in the sample's label map. (Note that
   * this is allowed to be a subset of the full set of labels present in the map.)
   */
  private static boolean containsLabels(Sample sample, Map<String, String> expectedLabels) {
    Map<String, String> labelMap = new HashMap<>();
    for (int i = 0; i < sample.labelNames.size(); i++) {
      labelMap.put(sample.labelNames.get(i), sample.labelValues.get(i));
    }
    // Check that expectedLabels is a subset of labelMap.
    for (Map.Entry<String, String> entry : expectedLabels.entrySet()) {
      String actual = labelMap.get(entry.getKey());
      if (actual == null || !actual.equals(entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  /** Returns true if the sample's labels exactly match {@code expectedLabels}. */
  private static boolean matchesLabels(Sample sample, Map<String, String> expectedLabels) {
    if (sample.labelNames.size() != expectedLabels.size()) {
      return false;
    }
    for (int i = 0; i < sample.labelNames.size(); i++) {
      String expected = expectedLabels.get(sample.labelNames.get(i));
      if (expected == null || !expected.equals(sample.labelValues.get(i))) {
        return false;
      }
    }
    return true;
  }
}
