package com.databricks.caching.util

import java.time.Instant

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.test.EwmaAmplifierTestDataP
import com.databricks.caching.util.test.EwmaAmplifierTestDataP.{
  ActionP,
  AdvanceAndRescaleIfNeededP,
  AmplifyAndIncrementP,
  AssertTotalEqualsP,
  EwmaAmplifierP,
  RescaleP,
  TestCaseP
}
import com.databricks.testing.DatabricksTest

class EwmaAmplifierSuite extends DatabricksTest {

  private val TEST_DATA: EwmaAmplifierTestDataP =
    TestUtils.loadTestData[EwmaAmplifierTestDataP](
      "caching/util/test/data/ewma_amplifier_test_data.textproto"
    )

  /**
   * Relative tolerance for verifying the amplifier's returned double value results. This is more
   * relaxed than floating point error to avoid having over-long numbers in the textproto test
   * fixture as expected results.
   */
  private val RELATIVE_TOLERANCE: Double = 1e-6

  /** Constructs an [[EwmaAmplifier]] from the parameters in `proto` and `startTime`. */
  private def createAmplifier(proto: EwmaAmplifierP, startTime: Instant): EwmaAmplifier =
    new EwmaAmplifier(startTime = startTime, halfLifeSeconds = proto.getHalfLifeSeconds)

  /**
   * Runs the single `action` against the `amplifier`. See the proto definition for the meaning of
   * the actions.
   */
  private def performAction(
      actionP: ActionP,
      amplifier: EwmaAmplifier,
      startTime: Instant,
      testName: String): Unit = {
    actionP.action match {
      case ActionP.Action.Empty =>
        fail(s"Test '$testName': action must be set")

      case ActionP.Action.AmplifyAndIncrement(proto: AmplifyAndIncrementP) =>
        proto.errorMessageOpt match {
          case Some(expectedErrorMessage: String) =>
            // Verify the expected error message when that is set.
            assertThrow[IllegalStateException](expectedErrorMessage) {
              amplifier.amplifyAndIncrement(proto.getValue)
            }
          case None =>
            // When the expected error message is not set, verify the expected amplified value.
            val actualAmplifiedValue: Double = amplifier.amplifyAndIncrement(proto.getValue)
            val expectedAmplifiedValue: Double = proto.expectedAmplifiedValue.getOrElse(
              fail(s"Test '$testName': expected_amplified_value must be set")
            )
            assert(
              math.abs(actualAmplifiedValue - expectedAmplifiedValue) <=
              math.abs(expectedAmplifiedValue) * RELATIVE_TOLERANCE
            )
        }

      case ActionP.Action.Rescale(proto: RescaleP) =>
        val actualFactorOpt: Option[Double] = amplifier.rescale()
        proto.expectedFactorOpt match {
          case Some(expectedFactor: Double) =>
            // When expected_factor_opt is defined, the actual factor must be the same as it.
            val actualFactor: Double = actualFactorOpt.getOrElse(
              fail(s"Test '$testName': rescale() returned no factor, expected $expectedFactor")
            )
            assert(
              math.abs(actualFactor - expectedFactor) <=
              math.abs(expectedFactor) * RELATIVE_TOLERANCE
            )
          case None =>
            // When expected_factor_opt is empty, the actual factor must be also empty.
            assert(actualFactorOpt.isEmpty)
        }

      case ActionP.Action.AdvanceAndRescaleIfNeeded(proto: AdvanceAndRescaleIfNeededP) =>
        val now: Instant = startTime.plusMillis(proto.getElapsedMs)
        val actualRescaleFactorOpt: Option[Double] = amplifier.advanceAndRescaleIfNeeded(now)
        proto.expectedRescaleFactorOpt match {
          case Some(expectedRescaleFactor: Double) =>
            // When expected_rescale_factor_opt is defined, a rescale must have happened and the
            // returned factor must be the same as it.
            val actualRescaleFactor: Double = actualRescaleFactorOpt.getOrElse(
              fail(
                s"Test '$testName': advanceAndRescaleIfNeeded(now=+${proto.getElapsedMs}ms) did " +
                s"not rescale, expected factor $expectedRescaleFactor"
              )
            )
            assert(
              math.abs(actualRescaleFactor - expectedRescaleFactor) <=
              math.abs(expectedRescaleFactor) * RELATIVE_TOLERANCE
            )
          case None =>
            // When expected_rescale_factor_opt is empty, no rescale must have happened.
            assert(actualRescaleFactorOpt.isEmpty)
        }

      case ActionP.Action.AssertTotalEquals(proto: AssertTotalEqualsP) =>
        val actualTotal: Double = amplifier.total
        val expectedTotal: Double = proto.getExpectedTotal
        assert(
          math.abs(actualTotal - expectedTotal) <= math.abs(expectedTotal) * RELATIVE_TOLERANCE
        )
    }
  }

  // For each case, construct a new EwmaAmplifier from its factory parameters, and supply the
  // amplifier to the rest of the test actions for that test case.
  for (testCase: TestCaseP <- TEST_DATA.testCases) {
    val testName: String = testCase.testCaseName.getOrElse(
      fail("test_case_name must be set on every case")
    )

    test(testName) {
      // Test plan: see caching/util/test/data/ewma_amplifier_test_data.textproto for the test plan
      // of each test case.
      val startTime: Instant = Instant.now()
      val amplifierProto: EwmaAmplifierP = testCase.getEwmaAmplifier
      amplifierProto.errorMessageOpt match {
        case Some(expectedCreateError: String) =>
          // When an expected error message is set, the creation must fail with the expected error.
          assertThrow[IllegalArgumentException](expectedCreateError) {
            createAmplifier(amplifierProto, startTime)
          }
        case None =>
          // The creation succeeded. Run the rest of the actions using the created amplifier.
          val amplifier: EwmaAmplifier = createAmplifier(amplifierProto, startTime)
          for (actionP: ActionP <- testCase.actions) {
            performAction(actionP, amplifier, startTime, testName)
          }
      }
    }
  }
}
