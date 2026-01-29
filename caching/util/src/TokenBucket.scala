package com.databricks.caching.util

import scala.concurrent.duration.DurationLong

/**
 * A basic token bucket primitive that tracks available tokens (tokens). This class accepts the
 * current [[TickerTime]] in its APIs and is designed to be used in contexts like
 * [[com.databricks.caching.util.StateMachine]] where time is managed externally. Otherwise,
 * [[RateLimiter]] should be used instead. Note that the caller needs to explicitly refill the
 * bucket.
 *
 * The bucket allows users to specify its capacity and refill rate. It starts with full capacity so
 * that it can start serving requests immediately.
 *
 * This class is not thread safe. It's the caller's responsibility to guarantee that only one thread
 * can access the token bucket instance at a time. Also note that we use Double internally in the
 * token bucket to simplify computation. Doubles are accurate up to 16 decimal places and we think
 * the relative error is acceptable.
 *
 * Important note: all methods do NOT automatically refill the bucket. The caller must explicitly
 * call [[refill]] with the current time to ensure tokens are up to date, if needed. This is
 * intentional — because automatically refilling the bucket in [[tryAcquire]] makes it ambiguous how
 * to handle cases where the caller provides a time that moves backwards.
 *
 * @param initCapacityInSecondsOfRate the initial capacity of the bucket in terms of seconds of rate
 * @param initRate the rate at which the bucket is initially refilled in tokens per second
 * @param initTime the time at which the bucket is initially filled with tokens
 *
 * @throws IllegalArgumentException if `initCapacityInSecondsOfRate` <= 0 or >
 *                                   [[TokenBucket.MAX_CAPACITY_IN_SECONDS_OF_RATE]].
 * @throws IllegalArgumentException if `initRate` <= 0 or > [[TokenBucket.MAX_RATE]].
 */
class TokenBucket private (
    initCapacityInSecondsOfRate: Long,
    initRate: Long,
    initTime: TickerTime) {
  import TokenBucket._

  validateCapacity(initCapacityInSecondsOfRate)
  validateRate(initRate)

  /** The time when the bucket was last refilled in nanosecond precision. */
  private var lastRefillTime: TickerTime = initTime

  /** The capacity of the bucket in terms of seconds of rate. */
  private var capacityInSecondsOfRate: Double = initCapacityInSecondsOfRate

  /** The rate at which the bucket is refilled in tokens per second. */
  private var rate: Double = initRate

  /** The number of tokens currently available in the bucket. Bucket starts off full. */
  private var availableTokens: Double = getMaximumCapacity

  /**
   * Refills the bucket with the accumulated number of tokens between the last time provided to
   * refill and `now`. If `now` is earlier than the last time this method was called, this method
   * does nothing.
   *
   * @param now the time at which the caller is refilling the bucket
   */
  def refill(now: TickerTime): Unit = {
    val elapsedNanos: Double = (now - lastRefillTime).toNanos

    // If the elapsed time is negative, it means there might be a time skew.
    if (elapsedNanos >= 0) {
      // Compute the number of newly generated tokens.
      val newTokenCount: Double = elapsedNanos / NANOS_PER_SECOND * rate

      // If the bucket is full, let the extra tokens spill.
      if (newTokenCount >= getMaximumCapacity - availableTokens) {
        availableTokens = getMaximumCapacity
      } else {
        availableTokens += newTokenCount
      }
      lastRefillTime = now
    }
  }

  /**
   * Acquires `count` tokens from the bucket if they can be obtained without delay.
   *
   * @param count the number of tokens to acquire
   * @return true if there are enough tokens in the bucket and they were acquired;
   *         false if there are not enough tokens available
   */
  @throws[IllegalArgumentException]("if count < 0.")
  def tryAcquire(count: Long): Boolean = {
    require(count >= 0, "Requested token count must be non-negative.")
    if (availableTokens >= count) {
      availableTokens -= count
      true
    } else {
      false
    }
  }

  /**
   * Returns the earliest ticker time `t` such that a call to [[refill(t)]] and then
   * [[tryAcquire(desired)]] with no intervening calls will succeed. Returns the last time provided
   * to refill if there are already at least `desired` tokens.
   *
   * @param desired the desired number of tokens
   */
  @throws[IllegalArgumentException]("if desired < 0.")
  @throws[IllegalArgumentException]("if desired > getMaximumCapacity.")
  def timeWhenRefilled(desired: Long): TickerTime = {
    require(desired >= 0, "Desired token number is negative.")
    require(
      desired <= getMaximumCapacity,
      s"Desired token number exceeds maximum capacity $getMaximumCapacity: " +
      s"rate $rate * capacityInSecondsOfRate $capacityInSecondsOfRate."
    )

    if (availableTokens >= desired) {
      // Already have enough tokens.
      lastRefillTime
    } else {
      // Calculate how many additional tokens are needed.
      val tokensNeeded: Double = desired - availableTokens
      // Calculate how long it will take to generate those tokens.
      val secondsNeededToRefill: Double = tokensNeeded / rate
      lastRefillTime + (secondsNeededToRefill * NANOS_PER_SECOND).toLong.nanoseconds
    }
  }

  /** Updates bucket capacity. */
  @throws[IllegalArgumentException](
    s"if newCapacityInSecondsOfRate <= 0 or > $MAX_CAPACITY_IN_SECONDS_OF_RATE."
  )
  def setCapacityInSecondsOfRate(newCapacityInSecondsOfRate: Long): Unit = {
    TokenBucket.validateCapacity(newCapacityInSecondsOfRate)
    capacityInSecondsOfRate = newCapacityInSecondsOfRate
    // Clamp available tokens if they exceed the new maximum capacity.
    if (availableTokens > getMaximumCapacity) {
      availableTokens = getMaximumCapacity
    }
  }

  /** Updates bucket rate. */
  @throws[IllegalArgumentException](s"if newRate <= 0 or > $MAX_RATE.")
  def setRate(newRate: Long): Unit = {
    TokenBucket.validateRate(newRate)
    rate = newRate
    // Clamp available tokens if they exceed the new maximum capacity.
    if (availableTokens > getMaximumCapacity) {
      availableTokens = getMaximumCapacity
    }
  }

  /** Returns the current `capacityInSecondsOfRate` as a Long. */
  def getCapacityInSecondsOfRate: Long = capacityInSecondsOfRate.toLong

  /** Returns the current `rate` as a Long. */
  def getRate: Long = rate.toLong

  /** Returns the maximum bucket capacity in terms of the number of tokens. */
  private def getMaximumCapacity: Double = capacityInSecondsOfRate * rate
}

object TokenBucket {

  /**
   * The maximum capacity of the bucket defined in terms of MAX_RATE. The maximum capacity
   * in terms of the number of tokens is MAX_CAPACITY_IN_SECONDS_OF_RATE * MAX_RATE.
   */
  // Please add tests against this upper bound when MAX_CAPACITY_IN_SECONDS_OF_RATE drops below
  // Long.MaxValue. These tests are missing now because there is no way to exceed Long.MaxValue.
  val MAX_CAPACITY_IN_SECONDS_OF_RATE: Long = Long.MaxValue

  /** The maximum tokens per second at which the bucket is refilled. */
  // Please add tests against this upper bound when MAX_RATE drops below Long.MaxValue. These tests
  // are missing now because there is no way to exceed Long.MaxValue.
  val MAX_RATE: Long = Long.MaxValue

  /** The number of nanoseconds in one second. */
  private val NANOS_PER_SECOND: Long = 1e9.toLong

  /**
   * Creates a TokenBucket.
   *
   * @param capacityInSecondsOfRate the capacity of the bucket in terms of seconds of rate
   * @param rate the rate at which the bucket is refilled in tokens per second
   * @param initTime the time at which the bucket is initially filled with tokens
   */
  def create(capacityInSecondsOfRate: Long, rate: Long, initTime: TickerTime): TokenBucket = {
    new TokenBucket(capacityInSecondsOfRate, rate, initTime)
  }

  /** Validates that the capacity is within acceptable bounds.  */
  @throws[IllegalArgumentException]("if capacity <= 0 or > MAX_CAPACITY_IN_SECONDS_OF_RATE")
  private def validateCapacity(capacity: Long): Unit = {
    require(capacity > 0, "Bucket capacity in seconds of rate must be positive.")
    // $COVERAGE-OFF$: This requirement cannot be violated because `MAX_CAPACITY_IN_SECONDS_OF_RATE`
    // is Long.MaxValue. If this value is changed to be less than Long.MaxValue, we need to add
    // tests against this upper bound.
    require(
      capacity <= MAX_CAPACITY_IN_SECONDS_OF_RATE,
      "Bucket capacity in seconds of rate must not exceed " +
      s"$MAX_CAPACITY_IN_SECONDS_OF_RATE."
    )
    // $COVERAGE-ON$
  }

  /** Validates that the rate is within acceptable bounds.  */
  @throws[IllegalArgumentException]("if rateValue <= 0 or > MAX_RATE")
  private def validateRate(rateValue: Long): Unit = {
    require(rateValue > 0, "Bucket refill rate must be positive.")
    // $COVERAGE-OFF$: This requirement cannot be violated because `MAX_RATE` is Long.MaxValue. If
    // this value is changed to be less than Long.MaxValue, we need to add tests against this upper
    // bound.
    require(rateValue <= MAX_RATE, s"Bucket refill rate must not exceed $MAX_RATE.")
    // $COVERAGE-ON$
  }
}
