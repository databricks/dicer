package com.databricks.caching.util

import scala.concurrent.duration._
import scala.util.Random

import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.test.ExponentialBackoffTestDataP

class ExponentialBackoffSuite extends DatabricksTest {

  private val SHARED_TEST_DATA: ExponentialBackoffTestDataP =
    TestUtils.loadTestData[ExponentialBackoffTestDataP](
      "caching/util/test/data/exponential_backoff_test_data.textproto"
    )

  test("Exponential backoff") {
    // Test plan: Create an exponential back off object with a deterministic random number generator
    // and test that the delays generated are as expected

    /** A deterministic random number generator. */
    object TestRandom extends Random { override def nextDouble = 0.75 }

    val backoff = new ExponentialBackoff(
      TestRandom,
      2.seconds,
      1.minute
    )

    // The delays expected on each call to nextDelay.
    val expectedDelays = Array(
      2200.milliseconds,
      4400.milliseconds,
      8800.milliseconds,
      17600.milliseconds,
      35200.milliseconds,
      66.seconds,
      66.seconds,
      66.seconds
    )

    // Verify the nextDelay values.
    for (expectedDelay <- expectedDelays) {
      val delay = backoff.nextDelay()
      logger.info(s"Delay = $delay")
      assert(delay == expectedDelay)
    }

    // Reset the backoff and check that the next delay is indeed the minimum one that is expected.
    backoff.reset()
    val delay = backoff.nextDelay()
    assert(delay == 2200.milliseconds)
  }

  test("Exponential backoff shared test data") {
    // Test plan: Verify that `ExponentialBackoff` doubles, caps, and jitters within the shared
    // textproto parameters.
    val testData = SHARED_TEST_DATA
    val jitterRatio = testData.getJitterRatio
    val minMillis = testData.getMinRetryDelayMillis
    val maxMillis = testData.getMaxRetryDelayMillis
    val multiplier = testData.getMultiplier

    val backoff = new ExponentialBackoff(new Random(), minMillis.millis, maxMillis.millis)

    var preJitterMillis = minMillis
    for (_ <- 0 until testData.getInitialDoublingStepsToVerify) {
      val delay = backoff.nextDelay()
      assert(delayWithinJitter(delay, preJitterMillis, jitterRatio))
      preJitterMillis = math.min(math.round(preJitterMillis * multiplier), maxMillis)
    }

    while (preJitterMillis < maxMillis) {
      val delay = backoff.nextDelay()
      assert(delayWithinJitter(delay, preJitterMillis, jitterRatio))
      preJitterMillis = math.min(math.round(preJitterMillis * multiplier), maxMillis)
    }

    for (_ <- 0 until testData.getIterationsAtMaxInterval) {
      val delay = backoff.nextDelay()
      assert(delayWithinJitter(delay, maxMillis, jitterRatio))
    }

    for (_ <- 0 until testData.getMaxElapsedNoneIterations) {
      backoff.nextDelay()
    }
  }

  /** Returns whether `delay` is within ±`jitterRatio`/2 of `preJitterMillis`. */
  private def delayWithinJitter(
      delay: FiniteDuration,
      preJitterMillis: Long,
      jitterRatio: Double): Boolean = {
    val low = math.round(preJitterMillis * (1.0 - jitterRatio / 2.0))
    val high = math.round(preJitterMillis * (1.0 + jitterRatio / 2.0))
    val delayMillis = delay.toMillis
    delayMillis >= low && delayMillis <= high
  }

  test("Exponential backoff overflow") {
    // Test plan: reproduces an internal bug where the (pre-jitter) delay was not clamped to the
    // maxRetryDelay boundary, and doubling the delay caused an overflow such that the delay wraps
    // around to negative values. Prior to the fix, the output of `assert(delay == maxRetryDelay)`
    // was:
    //
    //   "2 seconds did not equal 1 minute, overflowed after 28 steps"

    // Disable jitter by returning the "midpoint" value.
    object TestRandom extends Random { override def nextDouble = 0.5 }
    val maxRetryDelay = 1.minute
    val backoff = new ExponentialBackoff(TestRandom, 2.seconds, maxRetryDelay)

    // 64 steps is enough to overflow the internal int64 representation of delays, with doubling of
    // delays at every step.
    while (backoff.nextDelay() < maxRetryDelay) {}
    for (i <- 1 to 64) {
      val delay = backoff.nextDelay()
      assert(delay == maxRetryDelay, s", overflowed after $i steps")
    }
  }

  test("Attempt count increments") {
    // Test plan: verify that calls to nextDelay() increments the attempt counter, which should
    // initially start at 0.
    val backoff = new ExponentialBackoff(
      new Random(),
      2.seconds,
      1.minute
    )

    assert(backoff.getAttempts == 0)
    backoff.nextDelay()
    assert(backoff.getAttempts == 1)
    backoff.nextDelay()
    assert(backoff.getAttempts == 2)
  }

  test("Attempt count resets") {
    // Test plan: verify that a call to reset() sets the attempt counter back to 0.
    val backoff = new ExponentialBackoff(
      new Random(),
      2.seconds,
      1.minute
    )
    backoff.nextDelay()
    backoff.nextDelay()
    assert(backoff.getAttempts == 2)

    backoff.reset()

    assert(backoff.getAttempts == 0)
  }

  test("Unexpected overflow due to bad Random") {
    // Test plan: verify that a bad Random implementation causes an assertion error.
    object TestRandom extends Random {
      override def nextDouble = Double.MaxValue
    }
    val backoff = new ExponentialBackoff(
      TestRandom,
      2.seconds,
      1.day - 1.nanosecond
    )

    assertThrow[AssertionError]("unexpected over/under flow: Duration.Inf") {
      backoff.nextDelay()
    }
  }
}
