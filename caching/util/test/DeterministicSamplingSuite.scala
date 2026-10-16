package com.databricks.caching.util

import scala.util.Random

import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.caching.util.test.DeterministicSamplingTestDataP
import com.databricks.caching.util.test.DeterministicSamplingTestDataP.{
  InvalidTestCaseP,
  ValidTestCaseP
}
import com.databricks.testing.DatabricksTest

class DeterministicSamplingSuite extends DatabricksTest {

  private val TEST_DATA: DeterministicSamplingTestDataP =
    loadTestData[DeterministicSamplingTestDataP](
      "caching/util/test/data/deterministic_sampling_test_data.textproto"
    )

  namedGridTest("isSampled returns the expected result for valid inputs")(
    TEST_DATA.validTestCases
      .map((testCase: ValidTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: ValidTestCaseP =>
    // Test plan: Verify that isSampled returns the expected result for every valid input from the
    // shared textproto. Do this by iterating over each `valid_test_cases` entry, calling isSampled,
    // and asserting the result equals `expected_is_sampled`.
    val result: Boolean = DeterministicSampling.isSampled(
      item = testCase.getItem,
      sampleNamespace = testCase.getSampleNamespace,
      sampleFraction = testCase.getSampleFraction
    )
    assert(result == testCase.getExpectedIsSampled)
  }

  namedGridTest("isSampled rejects invalid inputs")(
    TEST_DATA.invalidTestCases
      .map((testCase: InvalidTestCaseP) => testCase.getDescription -> testCase)
      .toMap
  ) { testCase: InvalidTestCaseP =>
    // Test plan: Verify that isSampled rejects every invalid input from the shared textproto. Do
    // this by iterating over each `invalid_test_cases` entry and asserting isSampled throws
    // IllegalArgumentException whose message contains the expected substring.
    val exception: IllegalArgumentException = intercept[IllegalArgumentException] {
      DeterministicSampling.isSampled(
        item = testCase.getItem,
        sampleNamespace = testCase.getSampleNamespace,
        sampleFraction = testCase.getSampleFraction
      )
    }
    assert(
      exception.getMessage.contains(testCase.getExpectedErrorSubstring),
      s"Unexpected exception message: ${exception.getMessage}"
    )
  }

  test("Same item and namespace always produce the same sampling result") {
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

  test("Different namespaces can yield different sampling results for the same item") {
    // Test plan: Verify that the namespace acts as an independent salt so that the same item can
    // have different sampling decisions across namespaces. Do this by comparing the result for a
    // single item under two different namespaces, and asserting the results are not identical.
    val item: String = "some_item"
    val resultForNamespaceA: Boolean = DeterministicSampling.isSampled(
      item = item,
      sampleNamespace = "namespace_a",
      sampleFraction = 0.5
    )
    val resultForNamespaceB: Boolean = DeterministicSampling.isSampled(
      item = item,
      sampleNamespace = "namespace_b",
      sampleFraction = 0.5
    )
    assert(resultForNamespaceA != resultForNamespaceB)
  }

  test("Different items can yield different sampling results for the same namespace") {
    // Test plan: Verify that the item acts as an independent salt so that the same namespace can
    // have different sampling decisions across items. Do this by comparing the result for a single
    // namespace under two different items, and asserting the results are not identical.
    val namespace: String = "some_namespace"
    val resultForItemA: Boolean = DeterministicSampling.isSampled(
      item = "item_a",
      sampleNamespace = namespace,
      sampleFraction = 0.5
    )
    val resultForItemB: Boolean = DeterministicSampling.isSampled(
      item = "item_b",
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
        sampleNamespace = "sampling_namespace",
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
