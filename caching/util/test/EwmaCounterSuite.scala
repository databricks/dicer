package com.databricks.caching.util

import java.time.{Instant, Duration => JDuration}

import scala.util.Random

import org.apache.commons.math3.distribution.PoissonDistribution
import org.apache.commons.math3.random.AbstractRandomGenerator

import com.databricks.caching.util.AssertMacros.ifail
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.caching.util.EwmaCounter.Value
import com.databricks.caching.util.test.EwmaCounterTestDataP
import com.databricks.caching.util.test.EwmaCounterTestDataP.ActionP.Action
import com.databricks.caching.util.test.EwmaCounterTestDataP.{
  ActionP,
  AssertCountersValueMatchP,
  AssertValueEqualsP,
  AssertValueInRangeP,
  IncrementByP,
  RepeatedGetValueP,
  RepeatedIncrementByPoissonSample,
  RepeatedIncrementP,
  TestCaseP,
  messageCompanion
}
import com.databricks.testing.DatabricksTest

class EwmaCounterSuite extends DatabricksTest with TestName {

  /** A small the tolerance value to use for floating point comparisons */
  private val EPSILON: Double = 1e-14

  /**
   * Asserts that `actual` and `expected` have exactly the same window and that they have
   * approximately the same weighted value (+/- expected * 1e-14). `startTime` is used to provide
   * a more meaningful error message, by displaying window bounds as offsets from the test start
   * time.
   */
  private def assertApproximatelyEqual(startTime: Instant, actual: Value, expected: Value): Unit = {
    assertWindowsMatch(startTime, actual, expected)
    val tolerance: Double = if (expected.weightedValue == 0.0) {
      Double.MinPositiveValue
    } else {
      expected.weightedValue * EPSILON
    }
    assert(
      Math.abs(actual.weightedValue - expected.weightedValue) < tolerance,
      s"weightedValue not approximately equal. Actual: ${actual.weightedValue}, " +
      s"expected ${expected.weightedValue}"
    )
  }

  /** Asserts that `actual` and `expected` have the same value window. */
  private def assertWindowsMatch(startTime: Instant, actual: Value, expected: Value): Unit = {
    assert(
      actual.windowLowInclusive == expected.windowLowInclusive,
      s"windowLowInclusive not equal: " +
      s"${JDuration.between(startTime, actual.windowLowInclusive)} != " +
      s"${JDuration.between(startTime, expected.windowLowInclusive)}"
    )
    assert(
      actual.windowHighExclusive == expected.windowHighExclusive,
      s"windowHighExclusive not equal: " +
      s"${JDuration.between(startTime, actual.windowHighExclusive)} != " +
      s"${JDuration.between(startTime, expected.windowHighExclusive)}"
    )
  }

  /** Performs an action based on the proto. */
  private def performAction(
      counter: EwmaCounter,
      startTime: Instant,
      actionP: ActionP,
      referenceCounterOpt: Option[EwmaCounter] = None): Unit = {
    actionP.action match {
      case Action.Empty =>
        ifail("Unexpected empty action")

      case Action.IncrementBy(proto: IncrementByP) =>
        val now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val ewmaCounter = if (proto.getPerformOnReference) {
          referenceCounterOpt.getOrElse(ifail("Reference counter not provided"))
        } else {
          counter
        }
        ewmaCounter.incrementBy(now, proto.getValue)

      case Action.AssertValueEquals(proto: AssertValueEqualsP) =>
        val now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val actual: Value = counter.value(now)
        val expected: Value = Value(
          windowLowInclusive = startTime.plusMillis(proto.getWindowLowElapsedMs),
          windowHighExclusive = startTime.plusMillis(proto.getWindowHighElapsedMs),
          weightedValue = proto.getExpected
        )
        assertApproximatelyEqual(startTime, actual, expected)

      case Action.AssertValueInRange(proto: AssertValueInRangeP) =>
        val now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val actual: Value = counter.value(now)
        val expected: Value = Value(
          windowLowInclusive = startTime.plusMillis(proto.getWindowLowElapsedMs),
          windowHighExclusive = startTime.plusMillis(proto.getWindowHighElapsedMs),
          weightedValue = 0.0 // We only check the window, not the value.
        )
        assertWindowsMatch(startTime, actual, expected)
        assert(proto.getMinValue < actual.weightedValue && actual.weightedValue < proto.getMaxValue)

      case Action.RepeatedGetValue(proto: RepeatedGetValueP) =>
        var now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val ewmaCounter: EwmaCounter = if (proto.getPerformOnReference) {
          referenceCounterOpt.getOrElse(ifail("Reference counter not provided"))
        } else {
          counter
        }
        for (_ <- 0 until proto.getRepeatCount) {
          ewmaCounter.value(now)
          now = now.plusMillis(proto.getRepeatIntervalMs)
        }

      case Action.RepeatedIncrement(proto: RepeatedIncrementP) =>
        var now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val ewmaCounter: EwmaCounter = if (proto.getPerformOnReference) {
          referenceCounterOpt.getOrElse(ifail("Reference counter not provided"))
        } else {
          counter
        }
        for (_ <- 0 until proto.getRepeatCount) {
          ewmaCounter.incrementBy(now, proto.getValue)
          now = now.plusMillis(proto.getRepeatIntervalMs)
        }

      case Action.RepeatedIncrementByPoissonSample(proto: RepeatedIncrementByPoissonSample) =>
        // Use a fixed seed random generator to make the test deterministic.
        val generator = new AbstractRandomGenerator {
          private val rng = new Random(42)
          override def setSeed(seed: Long): Unit = throw new UnsupportedOperationException
          override def nextDouble(): Double = rng.nextDouble()
        }

        val distribution = new PoissonDistribution(
          generator,
          proto.getPoissonMean,
          PoissonDistribution.DEFAULT_EPSILON,
          PoissonDistribution.DEFAULT_MAX_ITERATIONS
        )
        var now = startTime.plusMillis(proto.getElapsedMs)
        for (_ <- 0 until proto.getRepeatCount) {
          counter.incrementBy(now, distribution.sample())
          now = now.plusMillis(proto.getRepeatIntervalMs)
        }

      case Action.AssertCountersValueMatch(proto: AssertCountersValueMatchP) =>
        val nowForMainCounter: Instant = startTime.plusMillis(proto.getMainElapsedMs)
        val nowForReferenceCounter: Instant = startTime.plusMillis(proto.getReferenceElapsedMs)

        val mainWeightedValue: Double = counter.value(nowForMainCounter).weightedValue
        val referenceWeightedValue: Double =
          referenceCounterOpt.get.value(nowForReferenceCounter).weightedValue

        assert(Math.abs(mainWeightedValue - referenceWeightedValue) < EPSILON)
    }
  }

  /** Loads the test data proto to a Map of test names to their corresponding test cases. */
  private val TEST_DATA: EwmaCounterTestDataP =
    TestUtils
      .loadTestData[EwmaCounterTestDataP]("caching/util/test/data/ewma_counter_test_data.textproto")

  test("Invalid cases") {
    // Test plan: verify that exceptions are thrown for invalid cases.
    val testCases: Seq[TestCaseP] = TEST_DATA.invalidCases

    for (testCase: TestCaseP <- testCases) {
      assertThrow[IllegalArgumentException]("") {
        val startTime: Instant = RealtimeTypedClock.instant()
        val halfLifeSeconds: Int = testCase.getEwmaCounter.getHalfLifeSeconds
        val seedOpt: Option[Double] = testCase.getEwmaCounter.seedOpt
        val ewmaCounter = new EwmaCounter(startTime, halfLifeSeconds, seedOpt)

        for (actionP: ActionP <- testCase.actions) {
          performAction(ewmaCounter, startTime, actionP)
        }
      }
    }
  }

  test("Valid cases") {
    // Test plan: verify that valid cases pass without throwing exceptions.
    val testCases: Seq[TestCaseP] = TEST_DATA.validCases

    for (testCase: TestCaseP <- testCases) {
      val startTime: Instant = RealtimeTypedClock.instant()
      val halfLifeSeconds: Int = testCase.getEwmaCounter.getHalfLifeSeconds
      val seedOpt: Option[Double] = testCase.getEwmaCounter.seedOpt
      val ewmaCounter = new EwmaCounter(startTime, halfLifeSeconds, seedOpt)

      for (actionP: ActionP <- testCase.actions) {
        performAction(ewmaCounter, startTime, actionP)
      }
    }
  }

  test("Reference counter cases") {
    // Test plan: verify that the reference counter cases pass without throwing exceptions.
    val testCases: Seq[TestCaseP] = TEST_DATA.referenceCases

    for (testCase: TestCaseP <- testCases) {
      val startTime: Instant = RealtimeTypedClock.instant()
      val halfLifeSeconds: Int = testCase.getEwmaCounter.getHalfLifeSeconds
      val seedOpt: Option[Double] = testCase.getEwmaCounter.seedOpt
      val ewmaCounter = new EwmaCounter(startTime, halfLifeSeconds, seedOpt)
      val referenceEwmaCounter = new EwmaCounter(startTime, halfLifeSeconds, seedOpt)

      for (actionP: ActionP <- testCase.actions) {
        performAction(ewmaCounter, startTime, actionP, Some(referenceEwmaCounter))
      }
    }

  }
}
