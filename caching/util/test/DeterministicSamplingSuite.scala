package com.databricks.caching.util

import scala.util.Random

import com.databricks.testing.DatabricksTest

class DeterministicSamplingSuite extends DatabricksTest {

  test("0.0 fraction samples nothing") {
    // Test plan: Verify that a sample fraction of 0.0 never samples any item regardless of the
    // flag name. Do this by sampling all items and asserting the count is 0.
    val sampledCount: Int = (0 until 10).count { i: Int =>
      DeterministicSampling.isSampled(
        item = s"item_$i",
        sampleNamespace = s"flag_$i",
        sampleFraction = 0.0
      )
    }
    assert(
      sampledCount == 0,
      s"Expected 0 sampled items at fraction 0.0, got $sampledCount"
    )
  }

  test("1.0 fraction samples everything") {
    // Test plan: Verify that a sample fraction of 1.0 always samples every item regardless of the
    // flag name. Do this by sampling all items and asserting the count equals item count.
    val sampledCount: Int = (0 until 10).count { i: Int =>
      DeterministicSampling.isSampled(
        item = s"item_$i",
        sampleNamespace = s"flag_$i",
        sampleFraction = 1.0
      )
    }
    assert(
      sampledCount == 10,
      s"Expected 10 sampled items at fraction 1.0, got $sampledCount"
    )
  }

  gridTest("Throws IllegalArgumentException with correct message for out-of-range fraction")(
    Seq(-1.0, -0.1, 1.1, 2.0, Double.NaN)
  ) { fraction: Double =>
    // Test plan: Verify that isSampled throws IllegalArgumentException with
    // the correct message when sampleFraction is outside [0.0, 1.0]. Do this by calling the method
    // with an invalid fraction and asserting on both the exception type and message.
    val exception: IllegalArgumentException = intercept[IllegalArgumentException] {
      DeterministicSampling.isSampled(
        item = "item",
        sampleNamespace = "flag",
        sampleFraction = fraction
      )
    }
    assert(
      exception.getMessage == s"sampleFraction must be in [0.0, 1.0], got $fraction",
      s"Unexpected exception message: ${exception.getMessage}"
    )
  }

  test("Same item and flag name always produce the same sampling result") {
    // Test plan: Verify that sampling is stable across repeated calls for the same (item,
    // sampleNamespace) pair. Do this by generating a random item and namespace, calling isSampled
    // 10 times, and asserting every result matches the first.
    val item: String = Random.alphanumeric.take(10).mkString
    val namespace: String = Random.alphanumeric.take(10).mkString

    // Get the result on the first call — this is our reference.
    val firstResult: Boolean = DeterministicSampling.isSampled(
      item = item,
      sampleNamespace = namespace,
      sampleFraction = 0.5
    )

    // Call 9 more times and verify each result matches.
    for (_ <- 1 until 10) {
      val result: Boolean = DeterministicSampling.isSampled(
        item = item,
        sampleNamespace = namespace,
        sampleFraction = 0.5
      )
      assert(
        result
        ==
        firstResult,
        s"isSampled($item, $namespace) returned $result, expected $firstResult"
      )
    }
  }

  test("Different flag names can yield different sampling results for the same item") {
    // Test plan: Verify that the flag name acts as an independent salt so that the same item can
    // have different sampling decisions across flags. Do this by comparing the result for a single
    // item under two different flag names, and asserting the results are not identical.
    val item: String = "some_item"
    val resultForFlagA: Boolean = DeterministicSampling.isSampled(
      item = item,
      sampleNamespace = "flag_a",
      sampleFraction = 0.5
    )
    val resultForFlagB: Boolean = DeterministicSampling.isSampled(
      item = item,
      sampleNamespace = "flag_c",
      sampleFraction = 0.5
    )
    assert(resultForFlagA != resultForFlagB)
  }

  test("Different items can yield different sampling results for the same flag name") {
    // Test plan: Verify that the item acts as an independent salt so that the same flag name can
    // have different sampling decisions across items. Do this by comparing the result for a single
    // flag name under two different items, and asserting the results are not identical.
    val namespace: String = "some_flag"
    val resultForItemA: Boolean = DeterministicSampling.isSampled(
      item = "item_a",
      sampleNamespace = namespace,
      sampleFraction = 0.5
    )
    val resultForItemB: Boolean = DeterministicSampling.isSampled(
      item = "item_c",
      sampleNamespace = namespace,
      sampleFraction = 0.5
    )
    assert(resultForItemA != resultForItemB)
  }

  test("Pairs with the same concatenation but different splits yield different results") {
    // Test plan: Verify that (item, sampleNamespace) pairs whose naive string concatenation is
    // identical are not treated as equivalent. Do this by sampling a set of such colliding pairs
    // at fraction 1.0 and asserting that not all pairs produce the same result — if the key were
    // just item + sampleNamespace, every pair would hash identically and always agree.
    val collidingPairs: Seq[(String, String)] = Seq(
      ("hello", "world"),
      ("he", "lloworld"),
      ("hellow", "orld"),
      ("h", "elloworld")
    )
    val results: Seq[Boolean] = collidingPairs.map {
      case (item: String, namespace: String) =>
        DeterministicSampling.isSampled(
          item = item,
          sampleNamespace = namespace,
          sampleFraction = 0.5
        )
    }
    assert(
      results.distinct.size > 1,
      s"Expected colliding pairs to yield different results, but all produced: ${results.head}"
    )
  }

  gridTest("Sampled count is within 3 sigma of expectation for various fractions")(
    Seq(0.0001, 0.1000, 0.2345, 0.42, 0.5, 0.9, 0.9999)
  ) { fraction: Double =>
    // Test plan: Verify that the sampled count for 1e6 items is within 3 sigma of the expectation.
    // For Binomial(n, p): expectation = n*p, sigma = sqrt(n*p*(1-p)). Use 1e6 items so that even
    // extreme fractions (e.g. 0.0001) yield a large enough expected count for a meaningful bound.
    // This test is not flaky because the input is fixed and the sampling behavior is deterministic.
    val n: Int = 1000000

    val sampledCount: Int = (0 until n).count { i: Int =>
      DeterministicSampling.isSampled(
        item = s"item_$i",
        sampleNamespace = "sampling_flag",
        sampleFraction = fraction
      )
    }

    val expected: Double = n * fraction
    val sigma: Double = math.sqrt(n * fraction * (1.0 - fraction))
    assert(
      math.abs(sampledCount.toDouble - expected) <= 3 * sigma,
      s"Expected count near $expected ± ${3 * sigma}, got $sampledCount at fraction $fraction."
    )
  }
}
