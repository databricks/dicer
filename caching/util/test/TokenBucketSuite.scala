package com.databricks.caching.util

import scala.concurrent.duration._

import com.databricks.caching.util.TokenBucket.{MAX_CAPACITY_IN_SECONDS_OF_RATE, MAX_RATE}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class TokenBucketSuite extends DatabricksTest {

  test("Create token bucket") {
    // Test plan: create token buckets with invalid (negative or very large rates or capacity)
    // parameters, and verify exceptions are thrown accordingly. Also verify that token bucket can
    // be created with valid parameters.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    assertThrow[IllegalArgumentException]("Bucket capacity in seconds of rate must be positive.") {
      TokenBucket.create(capacityInSecondsOfRate = -1L, rate = 5L, initTime)
    }
    assertThrow[IllegalArgumentException]("Bucket refill rate must be positive.") {
      TokenBucket.create(capacityInSecondsOfRate = 5L, rate = -1L, initTime)
    }
    TokenBucket.create(capacityInSecondsOfRate = 5L, rate = 5L, initTime)
  }

  test("Token bucket starts off full") {
    // Test plan: create a token bucket, verify that it starts off with full capacity, and its
    // attributes are set to the correct values.
    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)
    assert(bucket.getCapacityInSecondsOfRate == capacityInSecondsOfRate)
    assert(bucket.getRate == rate)

    bucket.refill(initTime)
    assert(bucket.tryAcquire(capacityInSecondsOfRate * rate))
    assert(!bucket.tryAcquire(1))
  }

  test("The number of tokens in the bucket can never exceed the capacity") {
    // Test plan: create a token bucket, advance the clock by a large time period, and then verify
    // that the total number of tokens can never exceed the maximum capacity.

    // - Test with small rate and capacity.
    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Clear the bucket.
    bucket.refill(initTime)
    assert(bucket.tryAcquire(capacityInSecondsOfRate * rate))
    // Make sure the bucket is empty.
    assert(!bucket.tryAcquire(1))

    // Advanced by 1 hour, the bucket should be full.
    fakeClock.advanceBy(1.hour)
    var lastRefillTime: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime)
    assert(
      bucket.timeWhenRefilled(capacityInSecondsOfRate * rate) == lastRefillTime
    )

    // Advanced by another hour, the bucket is still full.
    fakeClock.advanceBy(1.hour)
    lastRefillTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime)
    assert(
      bucket.timeWhenRefilled(capacityInSecondsOfRate * rate) == lastRefillTime
    )

    // Confirm that the bucket contains the correct number of tokens.
    val now: TickerTime = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(capacityInSecondsOfRate * rate))
    assert(!bucket.tryAcquire(1))

    // - Test with the maximum rate and capacity.
    val largeBucket: TokenBucket =
      TokenBucket.create(
        capacityInSecondsOfRate = MAX_CAPACITY_IN_SECONDS_OF_RATE,
        rate = MAX_RATE,
        initTime = fakeClock.tickerTime()
      )
    // Advance the clock by a large time period.
    fakeClock.advanceBy(51100.days)

    // Ensure that the bucket is full by checking timeWhenRefilled. The maximum capacity is
    // MAX_CAPACITY_IN_SECONDS_OF_RATE * MAX_RATE (calculated as Double). Since timeWhenRefilled
    // takes an Long, we verify with the largest value.
    val largeBucketRefillTime: TickerTime = fakeClock.tickerTime()
    largeBucket.refill(largeBucketRefillTime)
    assert(largeBucket.timeWhenRefilled(Long.MaxValue) == largeBucketRefillTime)
  }

  test("Single attempt to acquire tokens") {
    // Test plan: create a token bucket, and then attempt to acquire different amounts of tokens.
    // Make sure that we can't acquire negative number of tokens or more than the available number
    // of tokens.
    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val maximumTokenCount: Int = capacityInSecondsOfRate * rate
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Invalid case: acquire negative amount of tokens.
    assertThrow[IllegalArgumentException]("Requested token count must be non-negative.") {
      bucket.tryAcquire(-1)
    }
    bucket.refill(initTime)
    assert(!bucket.tryAcquire(maximumTokenCount + 1))

    // Valid cases:
    assert(bucket.tryAcquire(0))
    // Confirm that the bucket is left with capacityInSecondsOfRate * rate tokens.
    assert(bucket.tryAcquire(capacityInSecondsOfRate * rate))
    assert(!bucket.tryAcquire(1))
    fakeClock.advanceBy(1.hour)

    var now: TickerTime = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(maximumTokenCount))
    // Confirm that the bucket is left with 0 token.
    assert(!bucket.tryAcquire(1))
    fakeClock.advanceBy(1.hour)

    now = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(maximumTokenCount - 1))
    // Confirm that the bucket is left with 1 token.
    assert(bucket.tryAcquire(1))
    assert(!bucket.tryAcquire(1))
    fakeClock.advanceBy(1.hour)

    now = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(maximumTokenCount / 2))
    // Confirm that the bucket is left with maximumTokenCount / 2 tokens.
    assert(bucket.tryAcquire(maximumTokenCount / 2))
    assert(!bucket.tryAcquire(1))
  }

  test("Consecutively attempts to acquire tokens.") {
    // Test plan: sends consecutive requests to acquire tokens, and verify that the request only
    // succeeds when there are sufficient tokens in the bucket.
    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val maximumTokenCount: Int = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Continuous attempts to acquire small amount of tokens should succeed.
    bucket.refill(initTime)
    for (_: Int <- 1 to 100) {
      assert(bucket.tryAcquire(1))
    }

    // Refill the bucket.
    fakeClock.advanceBy(1.hour)
    val now: TickerTime = fakeClock.tickerTime()

    // Continuous attempts to acquire tokens more than the maximum capacity should fail.
    bucket.refill(now)
    for (_: Int <- 1 to 100) {
      assert(!bucket.tryAcquire(maximumTokenCount + 100))
    }

    // Continuous attempts to acquire large amount of tokens at the right interval should succeed.
    val tokenCount: Int = 400
    for (_: Int <- 1 to 100) {
      val currentTime: TickerTime = fakeClock.tickerTime()
      bucket.refill(currentTime)
      assert(bucket.tryAcquire(tokenCount))
      // Advance clock by the time needed to refill to `tokenCount` tokens.
      val timeWhenRefilled: TickerTime = bucket.timeWhenRefilled(tokenCount)
      if (timeWhenRefilled > currentTime) {
        fakeClock.advanceBy(timeWhenRefilled - currentTime)
      }
    }

    // Refill the bucket.
    fakeClock.advanceBy(1.hour)

    // Continuous attempts to acquire large amount of tokens should start to fail when tokens run
    // out.
    for (_: Int <- 1 to (maximumTokenCount / tokenCount)) {
      bucket.refill(fakeClock.tickerTime())
      assert(bucket.tryAcquire(tokenCount))
    }
    for (_: Int <- maximumTokenCount / tokenCount to 100) {
      assert(!bucket.tryAcquire(tokenCount))
    }
  }

  test("Tokens spill when bucket is full") {
    // Test plan: create a token bucket and elapse some time when the bucket is already full. Verify
    // that all tokens generated during this time period are disregarded.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 1, rate = 11, initTime)

    // Since the bucket is full, tokens generated during this time period should spill.
    fakeClock.advanceBy(700.millis)

    // Clear the bucket.
    var now: TickerTime = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(11))

    // Makes sure the tokens generated during the previous time window are disregarded, instead of
    // being counted towards the next refill. After advancing by 300 millis, only 3 tokens should
    // be generated instead of 11.
    fakeClock.advanceBy(300.millis)
    now = fakeClock.tickerTime()
    bucket.refill(now)
    assert(bucket.tryAcquire(3))
    assert(!bucket.tryAcquire(1))
  }

  test("Bucket is filled up at the correct rate") {
    // Test plan: Advance the clock in a way that within each interval, non-integer tokens are
    // generated (for example 2.8 tokens). Make sure that after a large number of steps, the total
    // number of tokens matches the expected value, despite that in each step, the refilled number
    // of tokens might not exactly match the rate.
    val totalElapsedTime: FiniteDuration = 60000.seconds
    val interval: FiniteDuration = 13.millis
    val iterations: Int = (totalElapsedTime / interval).toInt
    val margin: Double = 0.0000001 // 1E-6
    case class TestCase(capacityInSecondsOfRate: Long, rate: Long, expectedFinalTokens: Long)
    val testCases: Seq[TestCase] = Seq(
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = Int.MaxValue.toLong,
        expectedFinalTokens = totalElapsedTime.toSeconds * Int.MaxValue.toLong
      ),
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = 1000000L,
        expectedFinalTokens = totalElapsedTime.toSeconds * 1000000L
      ),
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = 333333L,
        expectedFinalTokens = totalElapsedTime.toSeconds * 333333L
      ),
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = 7777L,
        expectedFinalTokens = totalElapsedTime.toSeconds * 7777L
      ),
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = 9L,
        expectedFinalTokens = totalElapsedTime.toSeconds * 9L
      )
    )
    for (testCase: TestCase <- testCases) {
      val fakeClock: FakeTypedClock = new FakeTypedClock()
      val initTime: TickerTime = fakeClock.tickerTime()
      val bucket: TokenBucket =
        TokenBucket.create(testCase.capacityInSecondsOfRate, testCase.rate, initTime)

      // Clear the bucket.
      bucket.refill(initTime)
      assert(bucket.tryAcquire(testCase.capacityInSecondsOfRate * testCase.rate))
      assert(!bucket.tryAcquire(1))

      for (_: Int <- 1 to iterations) {
        fakeClock.advanceBy(interval)

        // Only to trigger the refill, no token is consumed.
        bucket.refill(fakeClock.tickerTime())
        bucket.tryAcquire(0)
      }

      // Advance by the remaining time.
      fakeClock.advanceBy(totalElapsedTime - iterations * interval)
      val finalTime: TickerTime = fakeClock.tickerTime()
      bucket.refill(finalTime)
      // Verify the bucket has approximately the expected tokens by checking timeWhenRefilled.
      // Verify that we have at least `1 - margin` * expected tokens.
      val nearExpectedTokens: Long = (testCase.expectedFinalTokens * (1 - margin)).toLong
      assert(bucket.timeWhenRefilled(nearExpectedTokens) == finalTime)
      // Also verify we can acquire these tokens.
      assert(bucket.tryAcquire(nearExpectedTokens))
    }
  }

  test("Bucket is filled up at the correct rate - very large values") {
    // Test plan: create a token bucket with very large rate, make sure that the calculated token
    // count falls in the affinity of the expected value.
    val totalElapsedTime: FiniteDuration = 60000.seconds
    val interval: FiniteDuration = 13.millis
    val iterations: Int = (totalElapsedTime / interval).toInt
    val margin: Double = 0.0000000001 // 1E-10
    case class TestCase(capacityInSecondsOfRate: Long, rate: Long, expectedFinalTokens: Double)
    val testCases: Seq[TestCase] = Seq(
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = Long.MaxValue,
        expectedFinalTokens = totalElapsedTime.toSeconds.toDouble * Long.MaxValue
      ),
      TestCase(
        capacityInSecondsOfRate = 10000000L,
        rate = Long.MaxValue / 2,
        expectedFinalTokens = totalElapsedTime.toSeconds.toDouble * Long.MaxValue / 2
      )
    )
    for (testCase: TestCase <- testCases) {
      val fakeClock: FakeTypedClock = new FakeTypedClock()
      val initTime: TickerTime = fakeClock.tickerTime()
      val bucket: TokenBucket =
        TokenBucket.create(capacityInSecondsOfRate = 1, rate = 1, initTime)

      // Instead of directly set the rate to very large value and then clear the bucket in a large
      // loop, we first set the rate to 1 and clear the bucket, and then set the rate to the desired
      // value, so the bucket starts off empty.
      bucket.refill(initTime)
      assert(bucket.tryAcquire(1))
      assert(!bucket.tryAcquire(1))
      bucket.refill(initTime)
      bucket.setCapacityInSecondsOfRate(testCase.capacityInSecondsOfRate)
      bucket.setRate(testCase.rate)

      for (_: Int <- 1 to iterations) {
        fakeClock.advanceBy(interval)

        // Only to trigger the refill, no token is consumed.
        bucket.refill(fakeClock.tickerTime())
        bucket.tryAcquire(0)
      }
      fakeClock.advanceBy(totalElapsedTime - iterations * interval)
      val finalTime: TickerTime = fakeClock.tickerTime()
      bucket.refill(finalTime)
      // Verify the bucket has approximately the expected tokens by checking timeWhenRefilled.
      // Verify that we have at least `1 - margin` * expected tokens.
      val nearExpectedTokens: Long = (testCase.expectedFinalTokens * (1 - margin)).toLong
      assert(bucket.timeWhenRefilled(nearExpectedTokens) == finalTime)
    }
  }

  test("Increase capacity") {
    // Test plan: create a token bucket, and then increase the capacity in terms of seconds of rate.
    // Verify that the bucket is confined by the new capacity.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 5, rate = 100, initTime)

    bucket.refill(initTime)
    assert(bucket.tryAcquire(100))
    // Verify we have 400 tokens remaining by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(400) == initTime)

    // Advance by 3 seconds, and then increase the capacity. The bucket should still be confined
    // by the old capacity for this time period. So after increasing the capacity, the bucket should
    // contain 500 tokens instead of 700.
    fakeClock.advanceBy(3.seconds)
    val now1: TickerTime = fakeClock.tickerTime()
    bucket.refill(now1)
    bucket.setCapacityInSecondsOfRate(10)
    assert(bucket.getCapacityInSecondsOfRate == 10)
    // Verify we have 500 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(500) == now1)

    // Now the new capacity is set, the bucket should be confined by the new capacity.
    fakeClock.advanceBy(3.seconds)
    val now2: TickerTime = fakeClock.tickerTime()
    bucket.refill(now2)
    // Verify we have 800 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(800) == now2)

    // Confirm that the bucket is left with the correct number of tokens.
    assert(bucket.tryAcquire(800))
    assert(!bucket.tryAcquire(1))

    // The bucket should be bound by the new capacity.
    fakeClock.advanceBy(1.hour)
    val now3: TickerTime = fakeClock.tickerTime()
    bucket.refill(now3)
    // Verify we have 1000 tokens by checking timeWhenRefilled and acquiring them.
    assert(bucket.timeWhenRefilled(1000) == now3)
    assert(bucket.tryAcquire(1000))
    assert(!bucket.tryAcquire(1))

    bucket.refill(now3)
    bucket.setCapacityInSecondsOfRate(MAX_RATE)
  }

  test("Reduce capacity") {
    // Test plan: create a token bucket, and then decrease the capacity in terms of seconds of rate.
    // Verify that the bucket is confined by the new capacity, and the number of available tokens
    // is reduced accordingly.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 10, rate = 100, initTime)
    bucket.refill(initTime)
    // Verify we have 1000 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(1000) == initTime)

    // After reducing the capacity, the bucket should be confined by the new capacity, even though
    // previously there are more tokens in the bucket.
    bucket.setCapacityInSecondsOfRate(6)
    assert(bucket.getCapacityInSecondsOfRate == 6)
    // Verify we have 600 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(600) == initTime)

    // Further reduce the capacity, the bucket should be confined by the new capacity.
    bucket.setCapacityInSecondsOfRate(2)
    assert(bucket.getCapacityInSecondsOfRate == 2)
    fakeClock.advanceBy(1.hour)
    val now: TickerTime = fakeClock.tickerTime()
    bucket.refill(now)
    // Verify we have 200 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(200) == now)

    // Confirm that the bucket is left with the correct number of tokens.
    assert(bucket.tryAcquire(200))
    assert(!bucket.tryAcquire(1))

    // Test boundaries.
    assertThrow[IllegalArgumentException]("Bucket capacity in seconds of rate must be positive.") {
      bucket.setCapacityInSecondsOfRate(-1)
    }
    assertThrow[IllegalArgumentException]("Bucket capacity in seconds of rate must be positive.") {
      bucket.setCapacityInSecondsOfRate(0)
    }
  }

  test("Set new rate") {
    // Test plan: create a token bucket, and then adjust the rate. Verify that the maximum capacity
    // in terms of token count is adjusted accordingly, and the bucket is refilled at the new rate.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 5, rate = 100, initTime)

    // Clear the bucket.
    bucket.refill(initTime)
    assert(bucket.tryAcquire(500))
    // Verify bucket is empty by checking we can't acquire any more tokens.
    assert(!bucket.tryAcquire(1))

    // Advance by 3 seconds, and then increase the rate. The bucket should refill at the old
    // rate for this time period. So immediately after increasing the rate, the bucket should
    // contain 300 tokens instead of 600.
    fakeClock.advanceBy(3.seconds)
    var now: TickerTime = fakeClock.tickerTime()
    bucket.refill(now)
    bucket.setRate(200)
    assert(bucket.getRate == 200)
    // Verify we have 300 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(300) == now)

    // Advance by another 3 seconds, now the refill should be at the new rate, and the total
    // capacity in terms of token count also increases to 1000 from 500.
    fakeClock.advanceBy(4.seconds)
    now = fakeClock.tickerTime()
    bucket.refill(now)
    // Verify we have 1000 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(1000) == now)

    // Reduce the rate, the total capacity in terms of token count should decrease to 250.
    bucket.setRate(50)
    assert(bucket.getRate == 50)
    assert(!bucket.tryAcquire(300))
    // Verify we have 250 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(250) == now)

    // Confirm that the bucket is left with the correct number of tokens.
    assert(bucket.tryAcquire(250))
    assert(!bucket.tryAcquire(1))

    // Test boundaries.
    assertThrow[IllegalArgumentException]("Bucket refill rate must be positive.") {
      bucket.setRate(-1)
    }
    assertThrow[IllegalArgumentException]("Bucket refill rate must be positive.") {
      bucket.setRate(0)
    }
    bucket.setRate(MAX_RATE)
  }

  test("refill with non-positive elapsed time") {
    // Test plan: Create a token bucket, clear it, and then attempt to refill it using a
    // non-positive elapsed time (same or earlier time). Verify that refill does nothing when the
    // elapsed time is non-positive.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 5, rate = 100, initTime)

    // Clear the bucket.
    bucket.refill(initTime)
    assert(bucket.tryAcquire(500))
    assert(!bucket.tryAcquire(1))

    // Refill the bucket with `initTime`. It should be a no-op.
    bucket.refill(initTime)
    assert(!bucket.tryAcquire(1))

    // Refill the bucket with an earlier time. It should be a no-op.
    bucket.refill(initTime - 1.hour)
    assert(!bucket.tryAcquire(1))

    // Advance the clock and refill the bucket. It should correctly refill the bucket.
    fakeClock.advanceBy(1.hour)
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.tryAcquire(500))

    // Refill the bucket with a backward `initTime`. It should be a no-op.
    bucket.refill(initTime)
    assert(!bucket.tryAcquire(1))
  }

  test("Refill with TickerTime.MIN and TickerTime.MAX") {
    // Test plan: Verify that refilling a TokenBucket with TickerTime.MIN and TickerTime.MAX works
    // correctly.

    // Setup: Create a token bucket. It starts with lastRefillTime = TickerTime.MIN.
    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime = TickerTime.MIN)
    val maxCapacity: Int = capacityInSecondsOfRate * rate

    // Setup: Clear the bucket by refilling at MIN and acquiring all tokens.
    bucket.refill(TickerTime.MIN)
    assert(bucket.tryAcquire(maxCapacity))
    assert(!bucket.tryAcquire(1))

    // Verify: Refill with TickerTime.Max. The elapsed time is huge, so the bucket should be filled
    // to maximum capacity.
    bucket.refill(TickerTime.MAX)
    assert(bucket.tryAcquire(maxCapacity))
    assert(!bucket.tryAcquire(1))
  }

  test("timeWhenRefilled") {
    // Test plan: Verify that timeWhenRefilled correctly computes the refill time in both cases:
    // when the bucket already has enough `desired` tokens and when it does not. Also verify that
    // timeWhenRefilled throws on invalid inputs.
    val capacityInSecondsOfRate: Int = 10
    val rate: Int = 100
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Verify: When bucket already has enough tokens, timeWhenRefilled should return the last refill
    // time.
    var lastRefillTime: TickerTime = initTime
    bucket.refill(lastRefillTime)
    // The bucket should have 1000 tokens based on its capacity and rate.
    assert(bucket.timeWhenRefilled(desired = 500) == lastRefillTime)
    assert(bucket.timeWhenRefilled(desired = 1000) == lastRefillTime)

    // Verify: When bucket doesn't have enough tokens, timeWhenRefilled should compute the correct
    // time based on the refill rate.

    // Clear the bucket.
    assert(bucket.tryAcquire(1000))
    assert(!bucket.tryAcquire(1))

    // We have 0 tokens and need 0 token. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 0) == lastRefillTime)

    // We have 0 tokens and need 1 token. At rate 100 tokens/second, it should take 10 millis.
    assert(bucket.timeWhenRefilled(desired = 1) == lastRefillTime + 10.milliseconds)

    // We have 0 tokens and need 100 tokens. At rate 100 tokens/second, it should take 1 second.
    assert(bucket.timeWhenRefilled(desired = 100) == lastRefillTime + 1.second)

    // We have 0 tokens and need 1000 tokens. At rate 100 tokens/second, it should take 10 seconds.
    assert(bucket.timeWhenRefilled(desired = 1000) == lastRefillTime + 10.seconds)

    // Partially refill the bucket.
    fakeClock.advanceBy(3.seconds)
    lastRefillTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime)

    // We have 300 tokens and need 300 tokens. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 300) == lastRefillTime)

    // We have 300 tokens and need 200 tokens. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 200) == lastRefillTime)

    // We have 300 tokens and need 500 tokens. Need 200 more tokens, which takes 2 seconds at rate
    // 100.
    assert(bucket.timeWhenRefilled(desired = 500) == lastRefillTime + 2.seconds)

    // We have 300 tokens and need 800 tokens. Need 500 more tokens, which takes 5 seconds at rate
    // 100.
    assert(bucket.timeWhenRefilled(desired = 800) == lastRefillTime + 5.seconds)

    // Verify: Invalid inputs should throw IllegalArgumentException.

    // Negative `desired` should throw.
    assertThrow[IllegalArgumentException]("Desired token number is negative.") {
      bucket.timeWhenRefilled(desired = -1)
    }
    // `desired` exceeding maximum capacity should throw.
    assertThrow[IllegalArgumentException](
      s"Desired token number exceeds maximum capacity ${capacityInSecondsOfRate * rate}"
    ) {
      bucket.timeWhenRefilled(desired = capacityInSecondsOfRate * rate + 1)
    }
  }

  test("timeWhenRefilled supports nanosecond precision") {
    // Test plan: Verify that timeWhenRefilled correctly computes refill times with nanosecond
    // precision for high-rate buckets.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val highRateBucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 10, rate = 1000000000L, fakeClock.tickerTime())
    val refillTime: TickerTime = fakeClock.tickerTime()
    highRateBucket.refill(refillTime)

    // Clear the bucket.
    assert(highRateBucket.tryAcquire(10000000000L))
    assert(!highRateBucket.tryAcquire(1))

    // At rate 1,000,000,000 tokens/second, 1 token takes 1 nanosecond.
    assert(highRateBucket.timeWhenRefilled(desired = 1) == refillTime + 1.nanosecond)

    // At rate 1,000,000,000 tokens/second, 10 tokens takes 10 nanoseconds.
    assert(highRateBucket.timeWhenRefilled(desired = 10) == refillTime + 10.nanoseconds)

    // At rate 1,000,000,000 tokens/second, 500 tokens takes 500 nanoseconds.
    assert(highRateBucket.timeWhenRefilled(desired = 500) == refillTime + 500.nanoseconds)
  }
}
