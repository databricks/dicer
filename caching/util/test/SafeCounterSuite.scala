package com.databricks.caching.util

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.testing.DatabricksTest

class SafeCounterSuite extends DatabricksTest with TestName {

  /** The [[CollectorRegistry]] from which to fetch metric samples. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** Clears the registry after each test so registered counters don't persist across tests. */
  override protected def afterEach(): Unit = {
    try {
      registry.clear()
    } finally {
      super.afterEach()
    }
  }

  /** Unique counter name for each test, optionally distinguished by `infix`. */
  private def getCounterName(infix: String = ""): String =
    getSafeName.replace("-", "_") + infix + "_total"

  /** Current value of the counter named `name` for the given `labels`, or 0.0 if absent. */
  private def getValue(name: String, labels: Map[String, String]): Double =
    MetricUtils.getMetricValue(registry, name, labels)

  test("inc() accumulates non-negative amounts and ignores negatives. get() returns the value.") {
    // Test plan: Verify that inc() and inc(non-negative) increase the counter appropriately.
    // Verify across counters with 0, 1, and more than 1 labels. Verify that inc(negative) leaves
    // the counter unchanged. Verify that get() returns the current value of the counter.
    val zeroLabelsName: String = getCounterName("_zero")
    val oneLabelName: String = getCounterName("_one")
    val twoLabelsName: String = getCounterName("_two")

    // 0 labels
    val noLabelsChild: SafeCounter.Child =
      SafeCounter.create(zeroLabelsName, "Test counter", Seq.empty).labels()
    noLabelsChild.inc()
    noLabelsChild.inc(2.5)
    noLabelsChild.inc(-1.0)
    assertResult(3.5)(noLabelsChild.get())
    assertResult(3.5)(getValue(zeroLabelsName, Map.empty))

    // 1 label
    val oneLabelChild: SafeCounter.Child =
      SafeCounter.create(oneLabelName, "Test counter", Seq("label1")).labels("A1")
    oneLabelChild.inc()
    oneLabelChild.inc(2.5)
    oneLabelChild.inc(-1.0)
    assertResult(3.5)(oneLabelChild.get())
    assertResult(3.5)(getValue(oneLabelName, Map("label1" -> "A1")))

    // More than 1 label
    val twoLabelsChild: SafeCounter.Child =
      SafeCounter.create(twoLabelsName, "Test counter", Seq("label1", "label2")).labels("A1", "A2")
    twoLabelsChild.inc()
    twoLabelsChild.inc(2.5)
    twoLabelsChild.inc(-1.0)
    assertResult(3.5)(twoLabelsChild.get())
    assertResult(3.5)(getValue(twoLabelsName, Map("label1" -> "A1", "label2" -> "A2")))
  }

  test("counts for different label values are incremented independently") {
    // Test plan: Verify that counts for two children of the same counter are incremented
    // independently. Create a counter with two label values and increment each child with
    // different amounts. Verify that each child's count reflects its own increments.
    val counter: SafeCounter = SafeCounter.create(getCounterName(), "Test counter", Seq("label1"))

    counter.labels("A1").inc(2.0)
    counter.labels("B1").inc()

    assertResult(2.0)(getValue(getCounterName(), Map("label1" -> "A1")))
    assertResult(1.0)(getValue(getCounterName(), Map("label1" -> "B1")))
  }

  test("remove() removes the Child for the given label value") {
    // Test plan: Verify that remove() removes the Child for the given label value and leaves
    // any other Child untouched. Verify across counters with 0, 1, and more than 1 labels.
    val zeroLabelsName: String = getCounterName("_zero")
    val oneLabelName: String = getCounterName("_one")
    val twoLabelsName: String = getCounterName("_two")

    // 0 labels
    val noLabelsCounter: SafeCounter = SafeCounter.create(zeroLabelsName, "Test counter", Seq.empty)
    noLabelsCounter.labels().inc(2.0)
    noLabelsCounter.remove()
    assertResult(0.0)(getValue(zeroLabelsName, Map.empty))

    // 1 label
    val oneLabelCounter: SafeCounter =
      SafeCounter.create(oneLabelName, "Test counter", Seq("label1"))
    oneLabelCounter.labels("A1").inc(2.0)
    oneLabelCounter.labels("B1").inc()
    oneLabelCounter.remove("A1")
    assertResult(0.0)(getValue(oneLabelName, Map("label1" -> "A1")))
    assertResult(1.0)(getValue(oneLabelName, Map("label1" -> "B1")))

    // More than 1 label
    val twoLabelsCounter: SafeCounter =
      SafeCounter.create(twoLabelsName, "Test counter", Seq("label1", "label2"))
    twoLabelsCounter.labels("A1", "A2").inc(2.0)
    twoLabelsCounter.labels("B1", "B2").inc()
    twoLabelsCounter.remove("A1", "A2")
    assertResult(0.0)(getValue(twoLabelsName, Map("label1" -> "A1", "label2" -> "A2")))
    assertResult(1.0)(getValue(twoLabelsName, Map("label1" -> "B1", "label2" -> "B2")))
  }

  test("clear() removes all children of a counter") {
    // Test plan: Verify that clear() removes all children of a counter. Create a counter with
    // two label values. Clear the counter and verify that both children are gone.
    val counter: SafeCounter = SafeCounter.create(getCounterName(), "Test counter", Seq("label1"))

    counter.labels("A1").inc(2.0)
    counter.labels("B1").inc()
    counter.clear()

    assertResult(0.0)(getValue(getCounterName(), Map("label1" -> "A1")))
    assertResult(0.0)(getValue(getCounterName(), Map("label1" -> "B1")))
  }

  test("labels() throws IllegalArgumentException if the number of label values is wrong") {
    // Test plan: Verify that labels() throws IllegalArgumentException if the number of label
    // values does not match the number of label names. Create a counter with no labels and verify
    // that labels(one label) throws. Create a counter with one label and verify that
    // labels(no label) throws.
    val zeroLabelsName: String = getCounterName("_zero")
    val oneLabelName: String = getCounterName("_one")

    // labels(one label) with no label names
    val noLabelsCounter: SafeCounter = SafeCounter.create(zeroLabelsName, "Test counter", Seq.empty)
    assertThrow[IllegalArgumentException]("Incorrect number of labels.") {
      noLabelsCounter.labels("A1")
    }

    // labels(no label) with one label name
    val oneLabelCounter: SafeCounter =
      SafeCounter.create(oneLabelName, "Test counter", Seq("label1"))
    assertThrow[IllegalArgumentException]("Incorrect number of labels.") {
      oneLabelCounter.labels()
    }
  }

  test("invalid create") {
    // Test plan: Verify that create() throws IllegalArgumentException if the metric name or help
    // is empty.
    assertThrow[IllegalArgumentException]("metricName must be non-empty") {
      SafeCounter.create(metricName = "", help = "Test counter", labelNames = Seq("label1"))
    }
    assertThrow[IllegalArgumentException]("help must be non-empty") {
      SafeCounter.create(metricName = getCounterName(), help = "", labelNames = Seq("label1"))
    }
  }
}
