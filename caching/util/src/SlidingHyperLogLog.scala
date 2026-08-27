package com.databricks.caching.util

import java.util.ArrayDeque
import javax.annotation.concurrent.NotThreadSafe

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import com.google.protobuf.ByteString

/**
 * SlidingHyperLogLog does cardinality estimation of observations for a sliding time window, for
 * example to estimate the number of distinct keys seen approximately in the retention window.
 *
 * @param retention Approximately how long an observation remains part of the estimate.
 *
 * @throws IllegalArgumentException if retention is not positive.
 */
@NotThreadSafe
final class SlidingHyperLogLog @throws[IllegalArgumentException]()(retention: FiniteDuration) {

  require(retention.toNanos > 0)

  /**
   * Time buckets as (bucketStart, bucket). The front is the oldest bucket and the back the newest.
   */
  private val buckets: ArrayDeque[(TickerTime, HyperLogLog)] = new ArrayDeque()

  /** How long a time bucket lasts. */
  private val bucketWidth: FiniteDuration = retention / SlidingHyperLogLog.N_BUCKETS

  /**
   * Adds the key with the given hash to the estimate. The key will remain part of the estimate for
   * the retention window.
   */
  def add(now: TickerTime, key: ByteString): Unit = {
    trim(now)

    val current = currentBucket(now)
    current.add(key)
  }

  /**
   * Adds all of the keys from the given HyperLogLog to the estimate. The keys will remain part of
   * the estimate for the retention window.
   */
  def merge(now: TickerTime, hll: HyperLogLog): Unit = {
    trim(now)

    val current = currentBucket(now)
    current.merge(hll)
  }

  /**
   * Returns a HyperLogLog that estimates the distinct count of the items observed in the retention
   * window. Returned as a HyperLogLog rather than the raw estimate so that it can be combined with
   * other estimates.
   */
  def recent(now: TickerTime): HyperLogLog = {
    trim(now)

    val merged = new HyperLogLog()
    for (bucket <- buckets.asScala) {
      val (_, hll): (TickerTime, HyperLogLog) = bucket
      merged.merge(hll)
    }

    merged
  }

  /** Returns the HyperLogLog for the current time bucket, creating one if necessary. */
  private def currentBucket(now: TickerTime): HyperLogLog = {
    val currentBucketStart: TickerTime = truncateDuration(now, bucketWidth)

    if (!buckets.isEmpty()) {
      val (latestBucketStart, bucket): (TickerTime, HyperLogLog) = buckets.getLast()
      // >= here because tickerTime is "supposed" to be monotonic but it's technically not
      // guaranteed, so if time goes backwards just keep using the latest bucket.
      if (latestBucketStart >= currentBucketStart) {
        return bucket
      }
    }

    val hll = new HyperLogLog()
    buckets.addLast((currentBucketStart, hll))
    hll
  }

  /** Remove buckets that have fallen off of retention. **/
  private def trim(now: TickerTime): Unit = {
    // This extra bucketWidth/2 slop here is because observations will arrive on average halfway
    // through a time bucket, so evicting buckets when they're half a bucket older than the
    // retention window means that we retain observations for the retention window on average.
    val cutoff: TickerTime = now - retention - (bucketWidth / 2)
    while (!buckets.isEmpty()) {
      val (bucketStart, _): (TickerTime, HyperLogLog) = buckets.getFirst()
      if (bucketStart > cutoff) {
        return
      }
      buckets.removeFirst()
    }
  }

  /**
   * Rounds t down to the nearest multiple of resolution. Since [[TickerTime]]s have an arbitrary
   * origin this truncated value doesn't have any real-world meaning, but it can be used to bucket
   * [[TickerTime]]s together.
   */
  private def truncateDuration(t: TickerTime, resolution: FiniteDuration): TickerTime = {
    val sinceZero = t - TickerTime.ofNanos(0)
    val truncatedNanos = Math.floorDiv(sinceZero.toNanos, resolution.toNanos) * resolution.toNanos
    TickerTime.ofNanos(truncatedNanos)
  }
}

private object SlidingHyperLogLog {

  /**
   * How many time buckets to keep. Observations are kept for a period of `retention` with an error
   * of +/- half a bucket, so more buckets increases fidelity at the cost of storing and aggregating
   * over more buckets.
   *
   * For example, with retention=60s and N_BUCKETS=4, an observation added now may be evicted
   * between 52.5s and 67.5s from now.
   */
  private val N_BUCKETS: Int = 4
}
