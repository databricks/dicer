package com.databricks.caching.util.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.databricks.testing.DatabricksJavaTest;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetricUtilsTest extends DatabricksJavaTest {

  /** The default registry, shared across tests. */
  private static final CollectorRegistry registry = CollectorRegistry.defaultRegistry;

  @BeforeEach
  void beforeEach() {
    registry.clear();
  }

  /** Creates a counter registered in the test registry. */
  private Counter createCounter(String name, String[] labelNames) {
    return Counter.build()
        .name(name)
        .help("test counter")
        .labelNames(labelNames)
        .register(registry);
  }

  /** Creates a histogram registered in the test registry. */
  private Histogram createHistogram(String name, String[] labelNames, double[] buckets) {
    return Histogram.build()
        .name(name)
        .help("test histogram")
        .labelNames(labelNames)
        .buckets(buckets)
        .register(registry);
  }

  @Test
  void testGetMetricValueReturnsEmptyForNonexistentMetric() {
    // Test plan: Verify that getMetricValueOpt returns empty when the metric name does not exist in
    // the registry.
    assertThat(MetricUtils.getMetricValueOpt(registry, "nonexistent_metric", Map.of())).isEmpty();
  }

  @Test
  void testGetMetricValueMatchesLabels() {
    // Test plan: Verify that getMetricValueOpt returns the correct value when labels match, and
    // returns empty when labels do not match.
    String name = "label_matching_total";
    Counter counter = createCounter(name, new String[] {"label_foo", "label_bar"});
    counter.labels("value_foo", "value_bar").inc(5);
    assertThat(
            MetricUtils.getMetricValueOpt(
                registry, name, Map.of("label_foo", "value_foo", "label_bar", "value_bar")))
        .hasValue(5.0);
    // Subset of labels does not match.
    assertThat(MetricUtils.getMetricValueOpt(registry, name, Map.of("label_foo", "value_foo")))
        .isEmpty();
    // Disjoint labels do not match.
    assertThat(MetricUtils.getMetricValueOpt(registry, name, Map.of("label_baz", "value_baz")))
        .isEmpty();
    // Superset of labels does not match.
    assertThat(
            MetricUtils.getMetricValueOpt(
                registry,
                name,
                Map.of(
                    "label_foo", "value_foo",
                    "label_bar", "value_bar",
                    "label_baz", "value_baz")))
        .isEmpty();
  }

  @Test
  void testGetMetricValueDistinguishesBetweenLabelCombinations() {
    // Test plan: Verify that getMetricValueOpt correctly distinguishes between samples with
    // different
    // label values.
    String name = "distinguish_labels_total";
    Counter counter = createCounter(name, new String[] {"label_foo", "label_bar"});
    counter.labels("value_foo", "value_bar_1").inc(10);
    counter.labels("value_foo", "value_bar_2").inc(20);
    assertThat(
            MetricUtils.getMetricValueOpt(
                registry, name, Map.of("label_foo", "value_foo", "label_bar", "value_bar_1")))
        .hasValue(10.0);
    assertThat(
            MetricUtils.getMetricValueOpt(
                registry, name, Map.of("label_foo", "value_foo", "label_bar", "value_bar_2")))
        .hasValue(20.0);
  }

  @Test
  void testGetHistogramCount() {
    // Test plan: Verify that `getHistogramCount` correctly extracts the count from the histogram.
    // This is done by creating a histogram, inserting samples, and then verifying that
    // `getHistogramCount` returns the expected values.
    double[] buckets = new double[] {10, 20, 30, 40, 50};
    List<Double> samples = List.of(1.1, 2.0, 3.0, 4.0, 5.0, 10.0, 50.0, 100.0);
    Histogram histogram = createHistogram("histogram_count", new String[] {"test_label"}, buckets);
    for (double sample : samples) {
      histogram.labels("foo").observe(sample);
    }
    assertThat(
            MetricUtils.getHistogramCount(registry, "histogram_count", Map.of("test_label", "foo")))
        .isEqualTo(samples.size());
  }

  @Test
  void testGetHistogramCountMatchesSubsetOfLabels() {
    // Test plan: Verify that `getHistogramCount` matches on a subset of labels.
    String name = "histogram_subset";
    double[] buckets = new double[] {10, 20, 30};
    Histogram histogram = createHistogram(name, new String[] {"label_foo", "label_bar"}, buckets);
    histogram.labels("value_foo", "value_bar").observe(5.0);
    histogram.labels("value_foo", "value_bar").observe(15.0);
    histogram.labels("value_foo", "value_bar").observe(25.0);

    // All labels match.
    assertThat(
            MetricUtils.getHistogramCount(
                registry, name, Map.of("label_foo", "value_foo", "label_bar", "value_bar")))
        .isEqualTo(3);
    // Subset of labels matches.
    assertThat(MetricUtils.getHistogramCount(registry, name, Map.of("label_foo", "value_foo")))
        .isEqualTo(3);
    // Disjoint labels do not match.
    assertThat(MetricUtils.getHistogramCount(registry, name, Map.of("label_baz", "value_baz")))
        .isEqualTo(0);
    // Superset of labels does not match.
    assertThat(
            MetricUtils.getHistogramCount(
                registry,
                name,
                Map.of(
                    "label_foo", "value_foo",
                    "label_bar", "value_bar",
                    "label_baz", "value_baz")))
        .isEqualTo(0);
  }
}
