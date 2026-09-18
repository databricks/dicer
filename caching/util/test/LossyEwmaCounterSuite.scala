package com.databricks.caching.util

import java.time.Instant

import com.databricks.testing.DatabricksTest
import scala.util.Random

import org.apache.commons.math3.distribution.ZipfDistribution
import org.apache.commons.math3.random.{RandomGenerator, Well19937c}

import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.ErrorP.Error
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.HalfLifeSecondP.HalfLifeSeconds
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.IncrementValueP.IncrementValue
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.StartTimeP.Time
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.SupportP.Support
import com.databricks.caching.util.test.LossyEwmaCounterTestDataP.{
  AdvanceClockP,
  AssertHotKeysEqualsP,
  IncrementByP,
  KeyP,
  LossyEwmaCounterP,
  OneTimeActionP,
  RandomizedReferenceTestCaseP,
  RepeatedActionP,
  TestActionP,
  TestCaseP,
  messageCompanion
}

class LossyEwmaCounterSuite extends DatabricksTest {

  /** Loads the test data proto to a Map of test names to their corresponding test cases. */
  private val TEST_DATA: LossyEwmaCounterTestDataP =
    TestUtils.loadTestData[LossyEwmaCounterTestDataP](
      "caching/util/test/data/lossy_ewma_counter_test_data.textproto"
    )

  /** Creates a lossy EWMA counter with the given proto, also returns the start time. */
  private def createLossyEwmaCounter(
      proto: LossyEwmaCounterP,
      rng: RandomGenerator): (LossyEwmaCounter[String], Instant) = {
    val startTime: Instant = proto.getStartTime.time match {
      case Time.Empty => fail("time must be set")
      case Time.UnixEpochMillis(epochMilli) => Instant.ofEpochMilli(epochMilli)
      // Use Int to avoid any overflow/underflow/conversion issues.
      case Time.Random(_) => Instant.ofEpochMilli(rng.nextInt())
    }

    val support: Double = proto.getSupport.support match {
      case Support.Empty => fail("support must be set")
      case Support.Random(_) => rng.nextDouble() // (0, 1)
      case Support.Value(value: Double) => value
    }

    val error: Double = proto.getError.error match {
      case Error.Empty => fail("error must be set")
      case Error.OneTenthSupport(_) => support / 10.0 // 10% of support
      case Error.Value(value: Double) => value
    }

    val halfLifeSeconds: Double = proto.getHalfLifeSeconds.halfLifeSeconds match {
      case HalfLifeSeconds.Empty => fail("half life seconds must be set")
      case HalfLifeSeconds.Value(v: Double) => v
      case HalfLifeSeconds.Random(_) => rng.nextDouble() * 1000 // (0, 1000)
    }

    val counter = new LossyEwmaCounter[String](
      LossyEwmaCounter.Config(
        startTime = startTime,
        support = support,
        error = error,
        halfLifeSeconds = halfLifeSeconds
      )
    )
    (counter, startTime)
  }

  /** Performs the one-time action on `counter`, and returns the new time after the action. */
  private def performAction(
      actionP: OneTimeActionP,
      counter: LossyEwmaCounter[String],
      startTime: Instant,
      rng: RandomGenerator,
      testName: String): Instant = {
    var now: Instant = startTime

    actionP.action match {
      case OneTimeActionP.Action.Empty =>
        fail(s"one time action must be set in test case $testName")

      case OneTimeActionP.Action.IncrementBy(proto: IncrementByP) =>
        val value: Int = proto.getValue.incrementValue match {
          case IncrementValue.Empty => fail(s"increment value must be set in test case $testName")
          case IncrementValue.Value(v: Int) => v
          case IncrementValue.Random(_) =>
            rng.nextInt(Int.MaxValue - 1) + 1 // Positive Int
        }
        counter.incrementBy(now, proto.getKey, value)

      case OneTimeActionP.Action.CheckHotKeys(proto: AssertHotKeysEqualsP) =>
        val expected: Map[String, Double] =
          proto.expected.map { keyProto: KeyP =>
            keyProto.getKey -> keyProto.getContribution
          }.toMap
        val actual: Map[String, Double] = counter.getHotKeys()
        // Allow some floating point error in the hot keys.
        assert(
          expected.keySet == actual.keySet,
          s"Test case $testName: " +
          s"Expected hot keys: ${expected.keySet}, Actual hot keys: ${actual.keySet}"
        )
        for (entry <- expected) {
          val (key, expectedContribution): (String, Double) = entry;
          val actualContribution: Double =
            actual.getOrElse(key, fail(s"expected hot key $key not found in test case $testName"))
          assert(
            Math.abs(expectedContribution - actualContribution) <= 1e-10,
            s"Test case $testName: " +
            s"Expected contribution for key '$key': $expectedContribution, " +
            s"Actual contribution: $actualContribution"
          )
        }

      case OneTimeActionP.Action.AdvanceClock(proto: AdvanceClockP) =>
        now = now.plusMillis(proto.getMs)
    }
    now
  }

  /** Loops through all actions and executes them on `counter`. */
  private def performActions(
      actions: Seq[TestActionP],
      counter: LossyEwmaCounter[String],
      startTime: Instant,
      rng: RandomGenerator,
      testName: String): Unit = {
    var now: Instant = startTime

    for (action: TestActionP <- actions) {
      action.action match {
        case TestActionP.Action.Empty =>
          fail(s"action must be set in test case $testName")

        case TestActionP.Action.OneTimeAction(actionP: OneTimeActionP) =>
          now = performAction(actionP, counter, now, rng, testName)

        case TestActionP.Action.RepeatedAction(actionP: RepeatedActionP) =>
          val repeatCount: Int = actionP.getRepeatCount
          for (_ <- 0 until repeatCount) {
            for (actionP: OneTimeActionP <- actionP.actions) {
              now = performAction(actionP, counter, now, rng, testName)
            }
          }
      }
    }
  }

  /** Creates the change trackers for total sum and amplification overflows. */
  private def createOverflowChangeTrackers(): (ChangeTracker[Long], ChangeTracker[Long]) = {
    val totalSumOverflowChangeTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        LossyEwmaCounter.forTestCompanionObject.getTotalSumOverflowCount
      }
    val amplificationOverflowChangeTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        LossyEwmaCounter.forTestCompanionObject.getAmplificationOverflowCount
      }
    (totalSumOverflowChangeTracker, amplificationOverflowChangeTracker)
  }

  test("invalid cases") {
    // Test plan: verify that invalid cases throw exceptions.
    val testCases: Seq[TestCaseP] = TEST_DATA.invalidCases
    val rng: RandomGenerator = createRandomGenerator()

    for (testCase: TestCaseP <- testCases) {
      val testName: String = testCase.testCaseName.getOrElse(
        fail("Test name is not specified in the test case")
      )

      // Require the error message for invalid cases;
      require(testCase.errorMessageOpt.isDefined, s"Error message should be specified in $testName")
      val ex: IllegalArgumentException = assertThrow[IllegalArgumentException]("") {
        // Create a lossy EWMA counter from the proto.
        // Note: we use String as the key type to match the test data.
        val (counter, startTime): (LossyEwmaCounter[String], Instant) =
          createLossyEwmaCounter(testCase.getLossyEwmaCounter, rng)

        // Loop through the actions and perform them on the counter.
        performActions(testCase.actions, counter, startTime, rng, testName)
      }
      assert(
        ex.getMessage.contains(testCase.getErrorMessageOpt),
        s"Test case '$testName' failed with unexpected error message: ${ex.getMessage}"
      )
    }
  }

  test("valid cases") {
    // Test plan: verify that valid cases pass without throwing exceptions.
    val testCases: Seq[TestCaseP] = TEST_DATA.validCases
    val rng: RandomGenerator = createRandomGenerator()

    for (testCase: TestCaseP <- testCases) {
      val testName: String = testCase.testCaseName.getOrElse(
        fail("Test name is not specified in the test case")
      )

      val repeatedCount = testCase.repeatedCountOpt.getOrElse(1)

      val (totalSumOverflowTracker, amplificationOverflowTracker): (
          ChangeTracker[Long],
          ChangeTracker[Long]) =
        createOverflowChangeTrackers()

      for (i <- 0 until repeatedCount) {
        // Create a lossy EWMA counter from the proto.
        // Note: we use String as the key type to match the test data.
        val (counter, startTime): (LossyEwmaCounter[String], Instant) =
          createLossyEwmaCounter(testCase.getLossyEwmaCounter, rng)

        val now: Instant = testCase.repeatedIntervalMsOpt match {
          case Some(intervalMs: Long) =>
            startTime.plusMillis(intervalMs * i)
          case None => startTime
        }

        // Loop through the actions and perform them on the counter.
        performActions(testCase.actions, counter, now, rng, testName)
      }

      // Check overflow changes if the test expects them.
      if (testCase.getExpectedOverflowsOpt) {
        assert(totalSumOverflowTracker.totalChange() > 0)
        assert(amplificationOverflowTracker.totalChange() > 0)
      }
    }
  }

  test("Randomized") {
    // Test plan: Verify that LossyEwmaCounter generates the same output as the
    // InefficientLossyEwmaCounter reference implementation given the same input. Verify this by
    // generating random keys using a Power law/Zipfian distribution and recording random values in
    // both implementations and checking that the hot keys returned from the LossyEwmaCounter are
    // consistent with those returned by the reference implementation (see `checkHotKeys`).
    // Test case parameters:
    //  - shouldInterleaveGetHotKeysCalls: because accessing hot keys has side-effects on
    //    the internal representation used by the LossyEwmaCounter, we vary whether or not calls to
    //    `getHotKeys` are interleaved with `incrementBy` calls in each test case.
    //  - support: determines the Config.support configuration. For this test, we always use
    //    `error = support / 10`.
    //  - halfLifeSeconds: determines the Config.halfLifeSeconds configuration.
    //  - tolerance: floating point error tolerance (see `checkHotKeys` spec). The `halfLifeSeconds`
    //    value determines the tolerance in practice (1 is well-behaved, 10 is not!).
    // While the key generator generates up to 8000 distinct key values, for the Power law
    // distribution used, the vast majority of keys will have low frequency. We validate that the
    // trials generate at least 1000 distinct key values but that the lossy implementation maintains
    // state for at most 25 of those keys.
    val testCase: RandomizedReferenceTestCaseP = TEST_DATA.getRandomizedWithReferenceCase
    assert(testCase.getRequiresHardcoding)

    val rng: RandomGenerator = createRandomGenerator()
    val zipfDistribution = new ZipfDistribution(rng, 8000, 2.0)
    var now = Instant.ofEpochMilli(0)
    case class TestCase(
        description: String,
        shouldInterleaveGetHotKeysCalls: Boolean,
        support: Double,
        halfLifeSeconds: Double,
        tolerance: Double = 0)
    val testCases = Seq(
      TestCase(
        "halfLife=1 (interleaved gets)",
        shouldInterleaveGetHotKeysCalls = true,
        support = 0.05,
        halfLifeSeconds = 1
      ),
      TestCase(
        "halfLife=1 (non-interleaved gets)",
        shouldInterleaveGetHotKeysCalls = false,
        support = 0.05,
        halfLifeSeconds = 1
      ),
      TestCase(
        "halfLife=10 (interleaved gets)",
        shouldInterleaveGetHotKeysCalls = true,
        support = 0.05,
        halfLifeSeconds = 10,
        tolerance = 1e-12 // 10 halfLifeSeconds results in fp errors
      ),
      TestCase(
        "halfLife=10 (non-interleaved gets)",
        shouldInterleaveGetHotKeysCalls = false,
        support = 0.05,
        halfLifeSeconds = 10,
        tolerance = 1e-12 // 10 halfLifeSeconds results in fp errors
      )
    )
    for (testCase <- testCases) {
      val config = LossyEwmaCounter.Config(
        startTime = now,
        support = testCase.support,
        error = testCase.support / 10,
        halfLifeSeconds = testCase.halfLifeSeconds
      )
      val counter = new LossyEwmaCounter[Int](config)
      val referenceCounter = new InefficientLossyEwmaCounter[Int](config)
      for (_ <- 0 until 1000000) {
        // Increase by 0-1ms.
        now = now.plusNanos(rng.nextInt(1000000))
        val value: Int = zipfDistribution.sample()
        counter.incrementBy(now, value, 1)
        referenceCounter.incrementBy(now, value, 1)

        // Aperiodically check that the hot keys returned by the counters are consistent if we are
        // testing this interleaving in the current test variation.
        if (testCase.shouldInterleaveGetHotKeysCalls && rng.nextDouble() < 0.01) {
          referenceCounter.checkHotKeys(counter, testCase.tolerance)
        }
      }
      // Check that the final tally agrees.
      referenceCounter.checkHotKeys(counter, testCase.tolerance)

      // Check that the number of keys tracked by the actual counter is substantially less than the
      // number tracked by the reference counter. In the current distribution, very few keys should
      // appear with significant frequency.
      assert(referenceCounter.numKeys >= 1000) // expect at least 1000 of 8000 distinct keys
      assert(counter.forTest.numKeys <= 50) // expect at most 50 keys to be interesting
    }
  }

  /**
   * Creates a random number generator for a random seed. Logs the random seed so that we can
   * reproduce flakes by hard-coding the seed.
   */
  private def createRandomGenerator(): RandomGenerator = {
    val seed = Random.nextInt()
    logger.info(s"Using seed $seed")
    new Well19937c(seed)
  }
}
