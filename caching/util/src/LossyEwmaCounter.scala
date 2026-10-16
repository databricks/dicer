package com.databricks.caching.util

import java.time.Instant
import java.util
import javax.annotation.concurrent.NotThreadSafe

import scala.collection.mutable

import com.databricks.caching.util.LossyEwmaCounter.Config

/**
 * A counter that estimates the time-weighted contributions of "hot keys". The "contribution" of a
 * key is the exponentially-weighted moving average (EWMA) of all values recorded for the key
 * divided by the EWMA of all values recorded for all keys.
 *
 * A hot key is one with a contribution greater than [[Config.support]]. For example,
 * if a key accounts for 10% of the total traffic, and `support` is 0.05, then the key is hot.
 *
 * To avoid tracking for an unbounded number of keys, some error is permitted by this "lossy"
 * abstraction. The contribution for a key may be underestimated by as much as [[Config.error]]. To
 * avoid missing (potentially) hot keys, the [[getHotKeys()]] method may have false positives, but
 * the true contribution of any returned keys will never be less than `support - error`.
 *
 * Recent values are given more weight than older values. Every second, the weight assigned to a
 * value decreases by a factor of [[Config.decay]]. The epoch/amplification/total-sum bookkeeping
 * is delegated to [[EwmaAmplifier]].
 *
 * The motivating use case for this counter is hot key detection in the Dicer auto-sharder (see
 * <internal link>), which aims to identify, measure, and (through sharding assignments) isolate
 * hot keys that account for a significant fraction of the load offered to a service.
 *
 * See <internal link> for a more formal description of the concepts introduced above
 * and for important background on the implementation (particularly scaling of time-weighted sums
 * and performance considerations).
 *
 * @param config configuration parameters for the counter.
 * @tparam K the type of keys that are being counted.
 */
@NotThreadSafe
class LossyEwmaCounter[K](val config: Config) {
  import LossyEwmaCounter.EstimatedSum

  /**
   * The EwmaAmplifier used to correctly maintain the amplification factor for the incoming values
   * to effectively decay the previous values in EWMA manner.
   */
  private val amplifier: EwmaAmplifier =
    new EwmaAmplifier(startTime = config.startTime, halfLifeSeconds = config.halfLifeSeconds)

  /**
   * INVARIANT: includes all keys that have true time-weighted sums greater than
   * [[sumErrorThreshold]]. This means that any entry may only be removed from the collection when
   * its maximum possible time-weighted sum is less than or equal to [[sumErrorThreshold]].
   *
   * Time-weighted estimated sums of values reported to [[incrementBy()]] per key. Samples recorded
   * before a key was last removed from the collection do not contribute to the estimate, but must
   * account for less than [[sumErrorThreshold]], per the invariant described above.
   */
  private val estimatedSums = new util.HashMap[K, EstimatedSum]()

  /**
   * When the size of [[estimatedSums]] reaches this threshold, a [[scan()]] should be performed.
   * After a scan, a new value is chosen such that the amortized costs of [[incrementBy]] is O(1)
   * and the number of elements in [[estimatedSums]] remains within a constant factor of the optimal
   * number of elements. See remarks in [[scan()]] for more details. Per discussion at
   * <internal link>, we expect 7/error elements in steady state, so we start with
   * a somewhat lower threshold for the first scan. The choice of initial value is somewhat
   * arbitrary and has no effect on the amortized cost.
   */
  private var estimatedSumsSizeScanThreshold: Int = (1 / config.error).ceil.toInt.max(1)

  /**
   * The true time-weighted sum threshold below which an entry may be discarded because it's within
   * the acceptable error bounds for the lossy count algorithm. Used both when deciding whether an
   * entry can be evicted (during a scan) and to determine the maximum error associated with an
   * entry when it is added to [[estimatedSums]] (when an untracked key is encountered in
   * [[incrementBy()]]).
   */
  @SuppressWarnings(Array("EnforceMethodOrder"))
  private def sumErrorThreshold: Double = amplifier.total * config.error

  /**
   * Increments the counter for `key` by `value` as of `now`. It's ok if `now` is less than some
   * previously reported timestamp because of a non-monotonic clock: the accumulator uses the
   * highest seen timestamp seen so far internally. Negative or zero values are ignored.
   *
   * Q: Why not use the system ticker rather than the system clock, since the ticker is more likely
   * to be monotonic?
   *
   * A: See the reasoning given in [[EwmaCounter.incrementBy]], which also applies here. We are also
   * using the same value of `now` for tracking Slicelet load in both [[EwmaCounter]] and this
   * class, so we use Instant in both places to avoid any skew between them.
   */
  def incrementBy(now: Instant, key: K, value: Int): Unit = {
    if (value <= 0) {
      return // Ignore non-positive values.
    }

    val rescaleFactorOpt: Option[Double] = amplifier.advanceAndRescaleIfNeeded(now)
    for (rescaleFactor: Double <- rescaleFactorOpt) {
      scan(hotKeysBuilderOpt = None, rescaleFactor)
    }
    val amplified: Double = amplifier.amplifyAndIncrement(value)

    val estimate: EstimatedSum = estimatedSums
      .computeIfAbsent(
        key,
        _ => {
          // Determine the maximum possible error for the new estimate. If the key has never been
          // seen before, then the actual error is 0, but if the key was previously tracked by the
          // counter, it could only have been removed when its true time-weighted sum was less than
          // `sumErrorThreshold` (unscaled).
          new EstimatedSum(maxError = sumErrorThreshold)
        }
      )
    // Since per-key sums must be <= totalSum, we don't need to check for overflow here.
    estimate.minSum += amplified
    if (estimatedSums.size() >= estimatedSumsSizeScanThreshold) {
      // Perform a scan whenever we reach the `estimatedSumsSizeScanThreshold`.
      scan(hotKeysBuilderOpt = None, rescaleFactor = amplifier.rescale().getOrElse(1.0))
    }
  }

  /**
   * Returns estimated contributions for all keys that may have a true contribution of at least
   * `support`. Note that the contribution is defined as the ''fraction'' of the total that is
   * attributed to each key. For example, if a key accounts for 10% of the total EWMA traffic, its
   * contribution is 0.1 regardless of the underlying time-weighted value.
   *
   * Guarantees:
   *  - The estimated contribution may be an underestimate by up to `error` (but is never an
   *    overestimate).
   *  - Any key that has contributed more than `support` will be included in the result.
   *  - Any key that has contributed less than `support - error` will not be included in the result.
   * Thus, the minimum possible value returned for any key is `support - error`.
   */
  def getHotKeys(): Map[K, Double] = {
    val builder: HotKeysBuilder = Map.newBuilder[K, Double]
    scan(
      Some(builder),
      // Since we are scanning all keys anyway, we take this chance to unconditionally rescaling all
      // keys.
      rescaleFactor = amplifier.rescale().getOrElse(1.0)
    )
    builder.result()
  }

  /**
   * Alias for the map builder type used to populate the [[getHotKeys]] result during
   * [[scan()]].
   */
  private type HotKeysBuilder = mutable.Builder[(K, Double), Map[K, Double]]

  /**
   * POST-CONDITIONS:
   *  - Keys whose maximum possible time-weighted sums are less than or equal to
   *    [[sumErrorThreshold]] are removed from the counter. Reiterating the fundamental invariant
   *    for the counter, this is safe to do because, if the key is subsequently added back to the
   *    collection, the maximum error in its estimated time-weighted sum will be at least as large
   *    as its maximum possible time-weighted sum (unscaled) at the time of its removal.
   *
   * Scans all keys in [[estimatedSums]] and opportunistically does bookkeeping (see post-
   * conditions). Optionally populates hot keys discovered during the scan. Rescales the load of all
   * the scanned keys, and the rescale factor can be supplied by the caller. If the caller thinks
   * rescaling is not needed, they can pass in rescaleFactor = 1.0.
   */
  private def scan(hotKeysBuilderOpt: Option[HotKeysBuilder], rescaleFactor: Double): Unit = {
    val totalSum: Double = amplifier.total
    val it = estimatedSums.entrySet().iterator()
    while (it.hasNext) {
      val entry = it.next()
      val key: K = entry.getKey
      val estimate: EstimatedSum = entry.getValue

      // Apply decay to the estimated sum and maximum error with the desired rescale factor.
      estimate.minSum *= rescaleFactor
      estimate.maxError *= rescaleFactor

      if (hotKeysBuilderOpt.isDefined && (estimate.maxSum / totalSum) > config.support) {
        // If the maximum possible contribution for the current key is above the configured support,
        // include it in the hot keys result. Note that while we filter based on the maximum
        // possible contribution, we report the minimum possible contribution, since our contract
        // allows only underestimating, not overestimating.
        hotKeysBuilderOpt.get += ((key, estimate.minSum / totalSum))
      } else if (estimate.maxSum <= sumErrorThreshold) {
        // If the key's maximum possible sum is not more than the error threshold, it can be
        // removed.
        it.remove()
      }
    }
    // Reset the size threshold at which a new scan should be triggered. We allow the number of
    // tracked keys to double before the next scan, which ensures that the amortized cost of
    // `incrementBy` is O(1). It will take at least `n` calls before the size doubles (this is the
    // worst case where every call introduces a new key), which means that for each call to
    // `incrementBy`, we need to scan at most 2 entries in the next call to `scan`.
    estimatedSumsSizeScanThreshold = (estimatedSums.size() * 2).max(1)
  }

  object forTest {

    /**
     * Number of keys currently tracked by the counter. May be less than the number of distinct keys
     * that have been added to the counter, because keys that contribute less than the configured
     * error are removed during scans.
     */
    def numKeys: Int = estimatedSums.size
  }
}
object LossyEwmaCounter {

  /**
   * REQUIRES: 0 < support < 1
   * REQUIRES: 0 < error < 1
   * REQUIRES: error << support (i.e. error is at least an order of magnitude smaller than support)
   * REQUIRES: halfLifeSeconds > 0
   *
   * Configuration for the counter.
   *
   * @param startTime the time at which epoch 0 starts
   * @param support   the minimum ratio of the total time-weighted contribution that a key must
   *                  account for to be considered a hot key.
   * @param error     the maximum error in the estimated contribution for a key.
   * @param halfLifeSeconds The half-life of the counter, in seconds. The weight of an input will be
   *                        halved after this many seconds.
   */
  case class Config(startTime: Instant, support: Double, error: Double, halfLifeSeconds: Double) {
    require(support > 0 && support < 1, "support must be in (0, 1)")
    require(error > 0 && error < 1, "error must be in (0, 1)")
    require(error <= support / 10, "error must be significantly less than support")
    require(halfLifeSeconds > 0, "half life must be positive")

    // Compute decay such that the weight of an input at time t is half that of an input at time t +
    // halfLifeSeconds. See derivation of $\alpha$ in <internal link>, decay is
    // 1-alpha.
    val decay: Double = Math.pow(2, -1.0 / halfLifeSeconds)
  }

  /**
   * Time-weighted sum estimate for a key.
   *
   * @param maxError the maximum possible error in the estimate of the weighted sum for the current
   *                 key.
   */
  private class EstimatedSum(var maxError: Double) {

    /**
     * Inclusive lower bound on the true time-weighted sum of the current key. May be an
     * underestimate by up to [[maxError]]. Never an overestimate.
     */
    var minSum: Double = 0.0

    /** Inclusive upper bound on the true time-weighted sum of the current key. */
    def maxSum: Double = minSum + maxError
  }

  /**
   * Test-only accessors for the EWMA overflow counters. Thin delegators over
   * [[EwmaAmplifier.forTestCompanionObject]], preserved here so existing tests
   * (e.g. [[LossyEwmaCounterSuite]]) can keep reading the counts through
   * `LossyEwmaCounter.forTestCompanionObject` without depending on the helper directly.
   */
  private[util] object forTestCompanionObject {

    def getAmplificationOverflowCount: Long =
      EwmaAmplifier.forTestCompanionObject.getAmplificationOverflowCount

    def getTotalSumOverflowCount: Long =
      EwmaAmplifier.forTestCompanionObject.getTotalSumOverflowCount
  }
}
