package com.databricks.caching.util

import java.time.Instant

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.test.EwmaQuantileSummariesTestDataP
import com.databricks.caching.util.test.EwmaQuantileSummariesTestDataP.ActionP.Action
import com.databricks.caching.util.test.EwmaQuantileSummariesTestDataP.{
  ActionP,
  AssertQueryWithinP,
  CdfPoint,
  EwmaQuantileSummariesP,
  InsertP,
  TestCaseP,
  messageCompanion
}
import com.databricks.testing.DatabricksTest

/**
 * Drives [[EwmaQuantileSummaries]] (wrapping a concrete [[QuantileSummaries]] sketch) through the
 * textproto fixture at `caching/util/test/data/ewma_quantile_summaries_test_data.textproto`, which
 * the Rust counterpart `ewma_quantile_summaries_test.rs` also runs against.
 */
class EwmaQuantileSummariesSuite extends DatabricksTest {

  /** The actual test data from .textproto (also shared by Rust test). */
  private val TEST_DATA: EwmaQuantileSummariesTestDataP =
    TestUtils.loadTestData[EwmaQuantileSummariesTestDataP](
      "caching/util/test/data/ewma_quantile_summaries_test_data.textproto"
    )

  /** Performs a test action. See [[EwmaQuantileSummariesTestDataP]] for more details. */
  private def performAction(
      summary: EwmaQuantileSummaries[Int],
      startTime: Instant,
      action: ActionP): Unit = {
    action.action match {
      case Action.Empty =>
        fail("action is required")

      case Action.Insert(insert: InsertP) =>
        val value: Int = insert.value.getOrElse(fail("value is required"))
        val weight: Int = insert.weight.getOrElse(fail("weight is required"))
        val elapsedMs: Long = insert.elapsedMs.getOrElse(fail("elapsed_ms is required"))
        val now: Instant = startTime.plusMillis(elapsedMs)
        summary.insert(now, value, weight)

      case Action.AssertQueryWithin(assertQueryWithin: AssertQueryWithinP) =>
        val quantiles: Vector[Double] = assertQueryWithin.quantiles.toVector
        val relativeError: Double =
          assertQueryWithin.relativeError.getOrElse(fail("relative_error is required"))
        val actualResults: Vector[(Int, Double)] = summary.query(quantiles)
        val expectedResults: Vector[CdfPoint] = assertQueryWithin.expectedResults.toVector
        // An empty `expected_results` means the summary has no observations, so `query` must
        // return an empty result regardless of how many quantiles were batched.
        if (expectedResults.isEmpty) {
          assert(
            actualResults.isEmpty,
            s"query($quantiles): expected empty result, got $actualResults"
          )
        } else {
          // The returned value must match exactly, while its cumulative-weight fraction need only
          // agree with the expected one within `relativeError`.
          for (i: Int <- expectedResults.indices) {
            val (actualValue, actualFraction): (Int, Double) = actualResults(i)
            val expectedResult: CdfPoint = expectedResults(i)
            val expectedValue: Int = expectedResult.value.getOrElse(fail("value is required"))
            val expectedFraction: Double = expectedResult.cumulativeWeightFraction.getOrElse(
              fail("cumulative_weight_fraction is required")
            )
            assert(
              actualValue == expectedValue,
              s"query($quantiles) at index $i (quantile=${quantiles(i)}): " +
              s"actualValue=$actualValue, expectedValue=$expectedValue"
            )
            assert(
              math.abs(actualFraction - expectedFraction) <= relativeError,
              s"query($quantiles) at index $i (quantile=${quantiles(i)}): " +
              s"actualFraction=$actualFraction, expectedFraction=$expectedFraction, " +
              s"relativeError=$relativeError"
            )
          }
        }
    }
  }

  test("Valid cases") {
    // Test plan: See the textproto definition for the test plans of each case.
    val testCases: Seq[TestCaseP] = TEST_DATA.validCases
    assert(testCases.nonEmpty, "expected at least one valid test case")
    for (testCase: TestCaseP <- testCases) {
      val startTime: Instant = Instant.ofEpochMilli(0)
      val factory: EwmaQuantileSummariesP =
        testCase.ewmaQuantileSummaries.getOrElse(fail("ewma_quantile_summaries is required"))
      val halfLifeSeconds: Double =
        factory.halfLifeSeconds.getOrElse(fail("half_life_seconds is required"))
      val relativeError: Double =
        factory.relativeError.getOrElse(fail("relative_error is required"))
      val sampleCountUpperBound: Int =
        factory.sampleCountUpperBound.getOrElse(fail("sample_count_upper_bound is required"))
      val summary = new EwmaQuantileSummaries[Int](
        startTime,
        halfLifeSeconds,
        relativeError,
        sampleCountUpperBound
      )

      for (action: ActionP <- testCase.actions) {
        performAction(summary, startTime, action)
      }
    }
  }

  test("Constructor validates arguments") {
    // Test plan: Verify the EwmaQuantileSummaries constructor accepts valid parameters and rejects
    // a non-positive half-life and an out-of-range relative error.
    val startTime: Instant = Instant.ofEpochMilli(0)
    val halfLifeSeconds: Double = 120.0

    // Valid parameters are accepted.
    new EwmaQuantileSummaries[Int](
      startTime,
      halfLifeSeconds,
      relativeError = 0.01,
      sampleCountUpperBound = 1000
    )

    // A non-positive half-life is rejected.
    for (badHalfLifeSeconds: Double <- Seq(0.0, -1.0)) {
      assertThrow[IllegalArgumentException]("half_life must be positive") {
        new EwmaQuantileSummaries[Int](
          startTime,
          badHalfLifeSeconds,
          relativeError = 0.01,
          sampleCountUpperBound = 1000
        )
      }
    }

    // relativeError must lie strictly inside (0, 1); the boundaries and out-of-range values are
    // rejected.
    for (badRelativeError: Double <- Seq(0.0, 1.0, -0.1, 1.5)) {
      assertThrow[IllegalArgumentException]("relative_error must be in (0, 1)") {
        new EwmaQuantileSummaries[Int](
          startTime,
          halfLifeSeconds,
          badRelativeError,
          sampleCountUpperBound = 1000
        )
      }
    }
  }
}
