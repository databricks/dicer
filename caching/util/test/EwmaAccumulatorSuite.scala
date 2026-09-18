package com.databricks.caching.util

import com.databricks.testing.DatabricksTest

import com.databricks.caching.util.AssertMacros.ifail
import com.databricks.caching.util.EwmaCounter.EwmaAccumulator
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.test.EwmaAccumulatorTestDataP
import com.databricks.caching.util.test.EwmaAccumulatorTestDataP.TestActionP.Action
import com.databricks.caching.util.test.EwmaAccumulatorTestDataP.{
  AssertValueEqualsP,
  EwmaAccumulatorP,
  RepeatedAppendWithCheckP,
  TestActionP,
  TestCaseP,
  messageCompanion
}

class EwmaAccumulatorSuite extends DatabricksTest {

  /** A small value to use for floating point comparisons */
  private val EPSILON: Double = 1e-10

  /**
   * Performs a single action on `accumulator` based on the proto. A reference accumulator can be
   * optionally provided to compare against the value of `accumulator`.
   */
  private def performAction(
      accumulator: EwmaAccumulator,
      actionP: TestActionP,
      referenceAccumulatorOpt: Option[EwmaAccumulator] = None): Unit = {
    actionP.action match {
      case Action.Empty =>
        ifail("unknown action")

      case Action.AppendValue(value: Long) =>
        accumulator.appendValue(value)

      case Action.AppendZeros(count: Long) =>
        accumulator.appendZeroes(count.toInt)

      case Action.AssertValueEquals(proto: AssertValueEqualsP) =>
        val expectedValue: Double = proto.getExpected
        val actualValue: Double = accumulator.value
        val debugMessage: String = proto.debugMessageOpt.getOrElse("")
        assert(
          Math.abs(actualValue - expectedValue) < EPSILON,
          s"$debugMessage Expected value: $expectedValue, but got: $actualValue"
        )

      case Action.RepeatedAppendWithCheck(proto: RepeatedAppendWithCheckP) =>
        val appendValue: Long = proto.getValue
        val count: Long = proto.getCount
        for (_ <- 0L until count) {
          accumulator.appendValue(appendValue)
          val actualValue: Double = accumulator.value
          proto.expectedOpt match {
            case Some(expected: Double) => assert(Math.abs(actualValue - expected) < EPSILON)
            case None => // pass
          }

        }

      case Action.AssertAccumulatorsValueMatch(_) =>
        // Assert the reference accumulator exists, and its value matches the value of the
        // main accumulator.
        referenceAccumulatorOpt match {
          case Some(referenceAccumulator: EwmaAccumulator) =>
            assert(
              Math.abs(accumulator.value - referenceAccumulator.value) < EPSILON,
              s"Expected value: ${referenceAccumulator.value}, but got: ${accumulator.value}"
            )
          case None =>
            ifail("Reference accumulator is not provided for AssertAccumulatorsValueMatch action")
        }
    }
  }

  private val TEST_DATA: EwmaAccumulatorTestDataP =
    TestUtils.loadTestData[EwmaAccumulatorTestDataP](
      "caching/util/test/data/ewma_accumulator_test_data.textproto"
    )

  test("Invalid inputs") {
    // Test plan: verify that the accumulator throw an exception for each invalid input
    val testCases: Seq[TestCaseP] = TEST_DATA.invalidCases

    for (testCase: TestCaseP <- testCases) {
      assertThrow[IllegalArgumentException]("") {
        val alpha: Double = testCase.ewmaAccumulator.get.getAlpha
        val seedOpt: Option[Double] = testCase.ewmaAccumulator.get.seedOpt
        val accumulator = new EwmaAccumulator(alpha, seedOpt)
        for (action: TestActionP <- testCase.actions) {
          performAction(accumulator, action)
        }
      }
    }
  }

  test("Valid inputs") {
    // Test plan: verify that the accumulator works correctly for valid inputs.
    val testCases: Seq[TestCaseP] = TEST_DATA.validCases

    for (testCase: TestCaseP <- testCases) {
      val params: EwmaAccumulatorP = testCase.ewmaAccumulator.get
      val accumulator = new EwmaAccumulator(params.getAlpha, params.seedOpt)

      for (actionP: TestActionP <- testCase.actions) {
        performAction(accumulator, actionP)
      }
    }
  }

  test("EwmaAccumulator append zeros correctness") {
    // Test plan: verify that the `appendZeros` method correctly accumulates zeros. This is
    // done by comparing the result of appending zeros to the result of appending values of
    // zero one by one.
    val testInput: TestCaseP = TEST_DATA.getAppendZerosAndCompare

    assert(
      testInput.referenceEwmaAccumulator.isDefined &&
      testInput.referenceAccumulatorActions.nonEmpty,
      "Reference accumulator is not defined or has no actions"
    )
    val params: EwmaAccumulatorP = testInput.ewmaAccumulator.get
    val params2: EwmaAccumulatorP = testInput.referenceEwmaAccumulator.get
    val accumulator = new EwmaAccumulator(params.getAlpha, params.seedOpt)
    val referenceAccumulator = new EwmaAccumulator(params2.getAlpha, params2.seedOpt)

    for (actionP <- testInput.actions) {
      performAction(accumulator, actionP, Some(referenceAccumulator))
    }

    for (actionP <- testInput.referenceAccumulatorActions) {
      performAction(referenceAccumulator, actionP, Some(accumulator))
    }

    // Sanity check: values of both accumulators should be non-zero and match.
    assert(
      Math.abs(accumulator.value - referenceAccumulator.value) < EPSILON,
      s"Expected value: ${referenceAccumulator.value}, but got: ${accumulator.value}"
    )
    assert(accumulator.value != 0.0 && referenceAccumulator.value != 0.0)
  }

}
