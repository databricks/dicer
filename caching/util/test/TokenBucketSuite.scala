package com.databricks.caching.util

import scala.concurrent.duration._

import com.databricks.caching.util.TokenBucket.{MAX_CAPACITY_IN_SECONDS_OF_RATE, MAX_RATE}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class TokenBucketSuite extends DatabricksTest {

  test("Create token bucket") {
    // Test plan: Create token buckets with invalid (non-positive rate or capacity) parameters, and
    // verify exceptions are thrown accordingly. Also verify that a token bucket can be created with
    // valid parameters.

    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    assertThrow[IllegalArgumentException](
      "Bucket capacity in seconds of rate should be positive, but was -1."
    ) {
      TokenBucket.create(capacityInSecondsOfRate = -1L, rate = 5L, initTime)
    }
    assertThrow[IllegalArgumentException](
      "Bucket capacity in seconds of rate should be positive, but was 0."
    ) {
      TokenBucket.create(capacityInSecondsOfRate = 0L, rate = 5L, initTime)
    }
    assertThrow[IllegalArgumentException]("Bucket refill rate should be positive, but was -1.") {
      TokenBucket.create(capacityInSecondsOfRate = 5L, rate = -1L, initTime)
    }
    assertThrow[IllegalArgumentException]("Bucket refill rate should be positive, but was 0.") {
      TokenBucket.create(capacityInSecondsOfRate = 5L, rate = 0L, initTime)
    }
    TokenBucket.create(capacityInSecondsOfRate = 5L, rate = 5L, initTime)
  }

  test("Token bucket starts off full") {
    // Test plan: Create a token bucket, verify that it starts off with full capacity, and its
    // attributes are set to the correct values.

    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)
    assert(bucket.getCapacityInSecondsOfRate == capacityInSecondsOfRate)
    assert(bucket.getRate == rate)

    bucket.refill(fakeClock.tickerTime())
    assert(bucket.tryAcquire(capacityTokenCount))
    assert(!bucket.tryAcquire(1))
  }

  test("Attempt to acquire different amounts of tokens") {
    // Test plan: Create a token bucket, and then attempt to acquire different amounts of tokens.
    // Make sure that we can't acquire a negative number of tokens or more than the available number
    // of tokens.

    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Invalid cases:

    bucket.refill(fakeClock.tickerTime())
    assertThrow[IllegalArgumentException](
      "Requested token count should be non-negative, but was -1."
    ) {
      bucket.tryAcquire(-1)
    }
    assert(!bucket.tryAcquire(capacityTokenCount + 1))

    // Valid cases (bucket is full):

    assert(bucket.tryAcquire(0))
    // Verify that the bucket is left with capacityTokenCount tokens.
    assert(bucket.tryAcquire(capacityTokenCount))
    assert(!bucket.tryAcquire(1))
  }

  test("Bucket can store fractional tokens") {
    // Test plan: Verify that the bucket accumulates and preserves fractional tokens across refills.
    // Refill after an interval too short to generate a full token, and confirm no token is
    // acquireable yet. Then, refill again after some time so the fractions sum past 1.0 and
    // confirm a whole token can be acquired.

    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Empty the bucket.
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.tryAcquire(capacityTokenCount))
    assert(!bucket.tryAcquire(1))

    // At rate 100 tokens/second, 8 millis generates 0.8 tokens.
    fakeClock.advanceBy(8.millis)
    bucket.refill(fakeClock.tickerTime())
    assert(!bucket.tryAcquire(1))

    // At rate 100 tokens/second, 5 millis generates 0.5 tokens.
    fakeClock.advanceBy(5.millis)
    bucket.refill(fakeClock.tickerTime())
    // The bucket should have 1.3 tokens.
    assert(bucket.tryAcquire(1))
    assert(!bucket.tryAcquire(1))
  }

  test("Consecutive attempts to acquire tokens") {
    // Test plan: Send consecutive requests to acquire tokens, and verify that the request only
    // succeeds when there are sufficient tokens in the bucket. Verify across small bucket and
    // large bucket.

    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    bucket.refill(fakeClock.tickerTime())
    // Continuous attempts to acquire small amount of tokens should succeed.
    for (_: Int <- 1 to 100) {
      assert(bucket.tryAcquire(5))
    }
    assert(!bucket.tryAcquire(1)) // Bucket is empty.

    // Continuous attempts to acquire large amount of tokens at the right interval should succeed.
    val tokenCount: Int = 100
    for (_: Int <- 1 to 100) {
      val currentTime: TickerTime = fakeClock.tickerTime()
      // Advance clock by the time needed to refill to `tokenCount` tokens.
      val timeWhenRefilled: TickerTime = bucket.timeWhenRefilled(tokenCount)
      if (timeWhenRefilled > currentTime) {
        fakeClock.advanceBy(timeWhenRefilled - currentTime)
      }
      bucket.refill(fakeClock.tickerTime())
      assert(bucket.tryAcquire(tokenCount))
    }

    // Refill the bucket.
    fakeClock.advanceBy(1.hour)
    bucket.refill(fakeClock.tickerTime())

    // Continuous attempts to acquire large amount of tokens should succeed until the bucket runs
    // out of tokens, and fail afterwards.
    val expectedSuccessfulAttempts: Int = capacityTokenCount / tokenCount
    for (i: Int <- 1 to 10) {
      assert(bucket.tryAcquire(tokenCount) == (i <= expectedSuccessfulAttempts))
    }

    // Test with the maximum rate and capacity.
    val largeBucket: TokenBucket =
      TokenBucket.create(
        capacityInSecondsOfRate = MAX_CAPACITY_IN_SECONDS_OF_RATE,
        rate = MAX_RATE,
        initTime = fakeClock.tickerTime()
      )

    // Verify that the capacity in tokens is not constrained by the limits of Long.
    largeBucket.refill(fakeClock.tickerTime())
    assert(largeBucket.tryAcquire(Long.MaxValue))
    assert(largeBucket.tryAcquire(Long.MaxValue))
  }

  test("Tokens spill when bucket is full") {
    // Test plan: Create a token bucket and elapse some time when the bucket is already full. Verify
    // that all tokens generated during this time period are disregarded.

    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 1, rate = 11, initTime)

    // Since the bucket is full, tokens generated during this time period should spill.
    fakeClock.advanceBy(700.millis)
    bucket.refill(fakeClock.tickerTime())
    // Clear the bucket.
    assert(bucket.tryAcquire(11))
    assert(!bucket.tryAcquire(1))

    // Make sure the tokens generated during the previous time window are disregarded, instead of
    // being counted towards the next refill. After advancing by 300 millis, only 3 tokens should
    // be generated instead of 11.
    fakeClock.advanceBy(300.millis)
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.tryAcquire(3))
    assert(!bucket.tryAcquire(1))
  }

  test("Set new capacity") {
    // Test plan: Create a token bucket, then increase and reduce its capacity in terms of seconds
    // of rate. Verify that the bucket is confined by the old capacity for time elapsed before the
    // change and by the new capacity afterwards, that reducing the capacity immediately clamps the
    // available tokens, and that invalid capacities are rejected.

    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 5, rate = 100, initTime)

    val lastRefillTime1: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime1)
    assert(bucket.tryAcquire(100))
    // Verify that the bucket has exactly 400 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(400) == lastRefillTime1)
    assert(bucket.timeWhenRefilled(401) > lastRefillTime1)

    // Advance by 3 seconds, and then increase the capacity. The bucket should still be confined
    // by the old capacity for this time period. So after increasing the capacity, the bucket should
    // contain 500 tokens instead of 700.
    fakeClock.advanceBy(3.seconds)
    val lastRefillTime2: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime2)
    bucket.setCapacityInSecondsOfRate(10)
    assert(bucket.getCapacityInSecondsOfRate == 10)
    // Verify that the bucket has exactly 500 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(500) == lastRefillTime2)
    assert(bucket.timeWhenRefilled(501) > lastRefillTime2)

    // Now the new capacity is set, the bucket should be confined by the new capacity.
    fakeClock.advanceBy(3.seconds)
    val lastRefillTime3: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime3)
    // Verify that the bucket has 800 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(800) == lastRefillTime3)

    // Verify that the bucket is left with the correct number of tokens.
    assert(bucket.tryAcquire(800))
    assert(!bucket.tryAcquire(1))

    // The bucket should be bound by the new capacity.
    fakeClock.advanceBy(1.hour)
    val lastRefillTime4: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime4)
    // Verify that the bucket has 1000 tokens by checking timeWhenRefilled and acquiring them.
    assert(bucket.timeWhenRefilled(1000) == lastRefillTime4)
    assert(bucket.tryAcquire(1000))
    assert(!bucket.tryAcquire(1))

    // Refill the bucket.
    fakeClock.advanceBy(1.hour)
    val lastRefillTime5: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime5)

    // After reducing the capacity, the bucket should be confined by the new capacity, even though
    // previously there were more tokens in the bucket.
    bucket.setCapacityInSecondsOfRate(6)
    assert(bucket.getCapacityInSecondsOfRate == 6)
    // Verify that the bucket has 600 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(600) == lastRefillTime5)

    fakeClock.advanceBy(1.hour)
    bucket.refill(fakeClock.tickerTime())
    // Verify that the bucket still has 600 tokens.
    assert(bucket.tryAcquire(600))
    assert(!bucket.tryAcquire(1))

    // Test capacityInSecondsOfRate boundaries.
    bucket.setCapacityInSecondsOfRate(MAX_CAPACITY_IN_SECONDS_OF_RATE)
    assertThrow[IllegalArgumentException](
      "Bucket capacity in seconds of rate should be positive, but was -1."
    ) {
      bucket.setCapacityInSecondsOfRate(-1)
    }
    assertThrow[IllegalArgumentException](
      "Bucket capacity in seconds of rate should be positive, but was 0."
    ) {
      bucket.setCapacityInSecondsOfRate(0)
    }
  }

  test("Set new rate") {
    // Test plan: Create a token bucket, and then adjust the rate. Verify that the maximum capacity
    // in terms of token count is adjusted accordingly, and the bucket is refilled at the new rate.
    // Also verify that invalid rates are rejected.

    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate = 5, rate = 100, initTime)

    // Clear the bucket.
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.tryAcquire(500))
    // Verify that the bucket is empty by checking that we can't acquire any more tokens.
    assert(!bucket.tryAcquire(1))

    // Advance by 3 seconds, and then increase the rate. The bucket should refill at the old
    // rate for this time period. So immediately after increasing the rate, the bucket should
    // contain 300 tokens instead of 600.
    fakeClock.advanceBy(3.seconds)
    val lastRefillTime1: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime1)
    bucket.setRate(200)
    assert(bucket.getRate == 200)
    // Verify that the bucket has exactly 300 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(300) == lastRefillTime1)
    assert(bucket.timeWhenRefilled(301) > lastRefillTime1)

    // Advance by another 4 seconds. Now the refill should be at the new rate, and the total
    // capacity in terms of token count also increases from 500 to 1000.
    fakeClock.advanceBy(4.seconds)
    val lastRefillTime2: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime2)
    // Verify that the bucket has 1000 tokens by checking timeWhenRefilled.
    assert(bucket.timeWhenRefilled(1000) == lastRefillTime2)

    // Reduce the rate. The total capacity in terms of token count should decrease to 250.
    bucket.setRate(50)
    assert(bucket.getRate == 50)
    assert(!bucket.tryAcquire(300))
    // Verify that the bucket has 250 tokens by checking timeWhenRefilled and acquiring them.
    assert(bucket.timeWhenRefilled(250) == lastRefillTime2)
    assert(bucket.tryAcquire(250))
    assert(!bucket.tryAcquire(1))

    // Test rate boundaries.
    bucket.setRate(MAX_RATE)
    assertThrow[IllegalArgumentException]("Bucket refill rate should be positive, but was -1.") {
      bucket.setRate(-1)
    }
    assertThrow[IllegalArgumentException]("Bucket refill rate should be positive, but was 0.") {
      bucket.setRate(0)
    }
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
    bucket.refill(fakeClock.tickerTime())
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
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate

    // Setup: Clear the bucket by refilling at MIN and acquiring all tokens.
    bucket.refill(TickerTime.MIN)
    assert(bucket.tryAcquire(capacityTokenCount))
    assert(!bucket.tryAcquire(1))

    // Verify: Refill with TickerTime.MAX. The elapsed time is huge, so the bucket should be filled
    // to maximum capacity.
    bucket.refill(TickerTime.MAX)
    assert(bucket.tryAcquire(capacityTokenCount))
    assert(!bucket.tryAcquire(1))
  }

  test("timeWhenRefilled") {
    // Test plan: Verify that timeWhenRefilled correctly computes the refill time in both cases:
    // when the bucket already has enough `desired` tokens and when it does not. Also verify that
    // timeWhenRefilled throws on invalid inputs.

    val capacityInSecondsOfRate: Int = 10
    val rate: Int = 100
    val capacityTokenCount: Int = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket =
      TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Verify: When the bucket already has enough tokens, timeWhenRefilled should return the last
    // refill time.
    val lastRefillTime1: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime1)
    // The bucket should have 1000 tokens based on its capacity and rate.
    assert(bucket.timeWhenRefilled(desired = 500) == lastRefillTime1)
    assert(bucket.timeWhenRefilled(desired = 1000) == lastRefillTime1)

    // Verify: When the bucket doesn't have enough tokens, timeWhenRefilled should compute the
    // correct time based on the refill rate.

    // Clear the bucket.
    assert(bucket.tryAcquire(1000))
    assert(!bucket.tryAcquire(1))

    // The bucket has 0 tokens and we need 0 tokens. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 0) == lastRefillTime1)

    // At rate 100 tokens/sec, it should take 10 millis to acquire 1 token.
    assert(bucket.timeWhenRefilled(desired = 1) == lastRefillTime1 + 10.milliseconds)

    // At rate 100 tokens/sec, it should take 1 second to acquire 100 tokens.
    assert(bucket.timeWhenRefilled(desired = 100) == lastRefillTime1 + 1.second)

    // At rate 100 tokens/sec, it should take 10 seconds to acquire 1000 tokens.
    assert(bucket.timeWhenRefilled(desired = 1000) == lastRefillTime1 + 10.seconds)

    // Partially refill the bucket.
    fakeClock.advanceBy(3.seconds)
    val lastRefillTime2: TickerTime = fakeClock.tickerTime()
    bucket.refill(lastRefillTime2)

    // The bucket has 300 tokens and we need 300 tokens. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 300) == lastRefillTime2)

    // The bucket has 300 tokens and we need 200 tokens. Should return the last refill time.
    assert(bucket.timeWhenRefilled(desired = 200) == lastRefillTime2)

    // The bucket has 300 tokens and we need 500 tokens. At rate 100 tokens/sec, it should take
    // 2 seconds to acquire 200 tokens.
    assert(bucket.timeWhenRefilled(desired = 500) == lastRefillTime2 + 2.seconds)

    // The bucket has 300 tokens and we need 800 tokens. At rate 100 tokens/sec, it should take
    // 5 seconds to acquire 500 tokens.
    assert(bucket.timeWhenRefilled(desired = 800) == lastRefillTime2 + 5.seconds)

    // Verify: Invalid inputs should throw IllegalArgumentException.
    assertThrow[IllegalArgumentException](
      "Desired token number should be non-negative, but was -1."
    ) {
      bucket.timeWhenRefilled(desired = -1)
    }
    assertThrow[IllegalArgumentException](
      s"Desired token number ${capacityTokenCount + 1} exceeds maximum capacity $capacityTokenCount"
    ) {
      bucket.timeWhenRefilled(desired = capacityTokenCount + 1)
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

  test("getUsageRatio reflects the consumed fraction of the bucket") {
    // Test plan: Verify that getUsageRatio accurately reflects the consumed fraction of the bucket.

    val capacityInSecondsOfRate: Int = 5
    val rate: Int = 100
    val capacityTokenCount: Long = capacityInSecondsOfRate * rate
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val initTime: TickerTime = fakeClock.tickerTime()
    val bucket: TokenBucket = TokenBucket.create(capacityInSecondsOfRate, rate, initTime)

    // Verify: A freshly created bucket starts full -> ratio 0.0.
    assert(bucket.getUsageRatio == 0.0)

    // Verify: After consuming half the capacity, ratio is 0.5.
    assert(bucket.tryAcquire(capacityTokenCount / 2))
    assert(bucket.getUsageRatio == 0.5)

    // Verify: After consuming everything, ratio is 1.0.
    assert(bucket.tryAcquire(capacityTokenCount / 2))
    assert(bucket.getUsageRatio == 1.0)

    // Verify: A failed tryAcquire (insufficient tokens) does not change the ratio.
    assert(!bucket.tryAcquire(1))
    assert(bucket.getUsageRatio == 1.0)

    // Verify: Refilling 20% of the bucket's capacity drops the ratio to 0.8.
    fakeClock.advanceBy(1.second)
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.getUsageRatio == 0.8)

    // Verify: Refilling fully restores the ratio to 0.0.
    fakeClock.advanceBy((capacityInSecondsOfRate - 1).seconds)
    bucket.refill(fakeClock.tickerTime())
    assert(bucket.getUsageRatio == 0.0)
  }
}
