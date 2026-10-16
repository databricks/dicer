package com.databricks.caching.util

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.concurrent.NotThreadSafe

import com.databricks.caching.util.AssertMacros.iassert

/**
 * Why this class is needed:
 *
 * This is a helper class for LossyEwmaCounter or EwmaQuantileSummaries. This class contains the
 * state and logic to implement the trick that exponentially amplifies the newly incoming values to
 * effectively decay the previous values. For example, when we want to track the load contribution
 * of a large number of keys within a key range, for each key, the EWMA-smoothed load contribution
 * from a key `k` at time `t` equals (assuming seeded with 0, and `i` is the timestamp)
 *
 * keyLoadContribution
 * = keyLoad / keyRangeLoad
 * = sum(i=0..t αnd key_i=k, (1-α)^(t-i) * α * x_i) / sum(i=0..t, (1-α)^(t-i) * α * x_i)
 * = sum(i=0..t αnd key_i=k, (1-α)^(-i) * α * x_i) / sum(i=0..t, (1-α)^(-i) * α * x_i)
 * = sum(i=0..t αnd key_i=k, β^i * α * x_i) / sum(i=0..t, β^i * α * x_i)
 * where β = 1/(1-a).
 *
 * So we only need to track the time-amplified load (β^i * a * x_i) for each key, and the
 * amplification factor (β^i * α) is easy to calculate and maintain. When time advances, we don't
 * need to explicitly decay the load for all keys by (1-α), which can be expensive. And this
 * amplification logic is what this class provides.
 *
 * See <internal link> and <internal link> for more details.
 *
 * How this class is used and implemented:
 *
 * This class keeps track of the current time, calculates and maintains the amplification factor for
 * the current time, and tells the users the correct amplified value when they want to increment
 * a value at a given time. The class also handles the logic to rescale the amplification and
 * accumulated values to avoid overflow.
 *
 * When using this class, users should always pair every [[amplifyAndIncrement]] with a preceding
 * [[advanceAndRescaleIfNeeded]]: advanceAndRescaleIfNeeded advances the internally tracked time
 * by the amplifier up to `now` and conditionally rescales the amplification and total load when
 * either is about to overflow, while amplifyAndIncrement then computes the amplified value for the
 * just-advanced epoch. Skipping the advanceAndRescaleIfNeeded call risks an
 * IllegalStateException from amplifyAndIncrement when the internal state overflows. Callers can
 * also call [[rescale]] to unconditionally rescale.
 *
 * Note this helper class is only useful when the user wants to keep track of the EWMA values that
 * are attributed into multiple disjoint sub-categories (e.g. load attributed to multiple keys or
 * key sub-ranges), and it can correctly maintain the ratio between the attributed value for each
 * sub-category and the total accumulated value without updating the metadata for each sub-category
 * when the time advances. For example, in [[LossyEwmaCounter]], this class can be used to maintain
 * the EWMA values for each key; in [[EwmaQuantileSummaries]], it can be used to maintain the gap
 * and delta metadata for each key range.
 *
 * An example usage of this class:
 *
 * {{{
 *   val factorOpt: Option[Double] = amplifier.advanceAndRescaleIfNeeded(now)
 *   for (factor: Double <- factorOpt) {
 *     // Multiply the caller's external aggregates (estimated sums, summary, ...) for each
 *     // sub-category by `factor`.
 *   }
 *   val amplified: Double = amplifier.amplifyAndIncrement(value)
 *   // Incorporate `amplified` into the caller's external aggregation in the correct sub-category.
 * }}}
 *
 * Note the absolute amplified value is meaningless by itself. Only the ratio of the amplified value
 * for a "sub-category" and the total amplified value will make sense.
 *
 * @param startTime The time at which the class starts.
 * @param halfLifeSeconds The half-life of the EWMA, in seconds. The effective weight of an
 *                        inserted observation halves every `halfLifeSeconds` of elapsed
 *                        physical time. Must be positive.
 *
 * @throws IllegalArgumentException if `halfLifeSeconds` is not positive.
 */
@NotThreadSafe
class EwmaAmplifier @throws[IllegalArgumentException]()(
    startTime: Instant,
    halfLifeSeconds: Double) {

  require(halfLifeSeconds > 0, "halfLifeSeconds must be positive")

  /**
   * The classic EWMA decay per second, chosen so that an observation's effective weight halves
   * every `halfLifeSeconds`. Equal to `1 - alpha`. See derivation of alpha in
   * <internal link>.
   */
  private val decay: Double = Math.pow(2, -1.0 / halfLifeSeconds)

  /**
   * The alpha value, which is the weight of all measurements at or before [[baseEpoch]] (see
   * <internal link> for background on alpha). It is equal to 1-decay.
   */
  private val alpha: Double = 1.0 - decay

  /**
   * Time-weighted sum of all values reported to [[amplifyAndIncrement]]. When [[baseEpoch]] is the
   * same as [[currentEpoch]] (which is true after rescaling), the total sum is also equal to the
   * exponentially weighted moving average (EWMA) of the rate of increments across all epochs.
   */
  private var totalSum: Double = 0.0

  /**
   * INVARIANT: `currentEpoch >= baseEpoch`
   *
   * The (monotonically increasing) epoch. This is the latest epoch based on timestamps supplied to
   * [[amplifyAndIncrement]].
   */
  private var currentEpoch: Long = 0

  /**
   * INVARIANT: `baseEpoch <= currentEpoch`
   *
   * The base, or "zero", epoch relative to which decay is computed. When the base epoch advances,
   * the [[totalSum]] and the externally kept per-category sums should decay by [[decay]] for
   * each elapsed epoch.
   */
  private var baseEpoch: Long = 0

  /**
   * INVARIANT: `amplification == alpha * decay^{baseEpoch - currentEpoch}`
   *
   * Memoized factor by which values reported to [[amplifyAndIncrement]] are amplified. This factor
   * is updated whenever [[baseEpoch]] or [[currentEpoch]] is updated. This is an inverse decay
   * factor that allows the counter implementation to defer rescaling of values after the epoch
   * advances until the next [[rescale]] or [[advanceAndRescaleIfNeeded]] operation.
   *
   * Initialized to alpha; this will be useful when we want to seed the [[EwmaQuantileSummaries]]
   * with historical load information in the future (see the last section discussing seeding in
   * <internal link>).
   */
  private var amplification: Double = alpha

  /**
   * Returns the current running total of amplified values. Useful for callers that compute
   * ratio thresholds against it.
   */
  def total: Double = totalSum

  /**
   * Amplifies `value` by the current amplification factor and adds the result to [[total]].
   * Returns the amplified value.
   *
   * On overflow [[total]] is left unchanged, so the amplifier stays usable.
   *
   * @throws IllegalStateException if the amplification and increment causes the internal state to
   *                               overflow. This can be avoided by calling
   *                               [[advanceAndRescaleIfNeeded]] to reserve room before calling this
   *                               method.
   */
  @throws[IllegalStateException]("if amplifying and incrementing overflows the internal state")
  def amplifyAndIncrement(value: Int): Double = {
    val amplified: Double = amplification * value
    // Both operands are checked before `totalSum` is assigned so that an overflow leaves the
    // amplifier untouched rather than poisoning it with an infinite total.
    val newTotalSum: Double = totalSum + amplified
    if (amplified.isInfinity || newTotalSum.isInfinity) {
      throw new IllegalStateException(
        "Call advanceAndRescaleIfNeeded before amplifyAndIncrement to reserve room and avoid " +
        "overflow."
      )
    }
    totalSum = newTotalSum
    amplified
  }

  /**
   * Tries to rescale the total accumulated sum and the amplification uniformly to smaller values to
   * avoid number overflow. Returns a factor that those values are rescaled by, or None when the
   * rescale didn't happen (when the [[currentEpoch]] is the same as [[baseEpoch]]). The caller is
   * responsible to multiply their externally-kept values by the returned factor when rescale
   * happens.
   */
  def rescale(): Option[Double] = {
    val elapsedEpochs: Long = currentEpoch - baseEpoch
    iassert(elapsedEpochs >= 0, s"baseEpoch=$baseEpoch must trail currentEpoch=$currentEpoch")
    if (elapsedEpochs == 0) {
      return None
    }
    // Folds the deferred EWMA decay into [[totalSum]], advances [[baseEpoch]]
    // up to the latest seen [[currentEpoch]], and resets [[amplification]] to [[alpha]].
    // Returns `Some(decay^(currentEpoch - baseEpochBefore))`, the factor the caller should
    // multiply its own external aggregates by so that they stay consistent with the
    // [[EwmaAmplifier]]'s newly advanced base epoch.
    val decayNTimes: Double = Math.pow(decay, elapsedEpochs.toDouble)
    totalSum *= decayNTimes
    baseEpoch = currentEpoch
    // Theoretically this can be reset to any value because we only care about the ratio
    // between value attributed to a sub-category and the total value, and the initial
    // amplification is cancelled by the ratio. But we still reset it to alpha to emphasize that
    // we are calculating EWMA values.
    amplification = alpha
    Some(decayNTimes)
  }

  /**
   * Advances the internally tracked time to `now` and, when advancing plus making room for
   * the maximum possible next increment value (i.e. [[Int.MaxValue]]) would overflow either
   * [[amplification]] or [[totalSum]], rescales.
   *
   * Returns the rescale factor the caller must multiply its external aggregates by, or `None`
   * when no rescale was required.
   *
   * @param now the timestamp the caller is about to use in [[amplifyAndIncrement]].
   */
  def advanceAndRescaleIfNeeded(now: Instant): Option[Double] = {
    val projectedAmplification: Double = projectAmplification(now)

    // Whether `amplification` will overflow.
    //
    // We fortunately rarely overflow `amplification`. Fortunate because the amortized complexity
    // analysis for the user of this class to increment a value depends on rescaling occurring only
    // in response to size thresholds of the externally kept data structure being exceeded, not
    // the rescaling needed by EwmaAmplifier. Rare because for a typical decay factor with (say) a
    // half-life of 2 minutes, the amplification factor will only overflow after
    // `log2(Double.MaxValue / alpha) * 2 minutes > log2(Double.MaxValue) * 2 minutes` which is
    // greater than 34 hours.
    val amplificationOverflow: Boolean = projectedAmplification.isInfinity

    // Whether the total sum will overflow after adding the maximum possible value (Int.MaxValue)
    // that can be passed to amplifyAndIncrement(). We rescale if it overflows to ensure the next
    // amplifyAndIncrement() call will be safe.
    //
    // Since the maximum value that can be reported to `amplifyAndIncrement()` is `Int.MaxValue`
    // (~2^31), and one million calls a second is a reasonable upper bound (~2^20), let's assume
    // that `totalSum` is at most `2^51 * amplification`. Assuming that decay is chosen such that
    // the half-life is 2 minutes, then `amplification` will ramp from `alpha` to `Double.MaxValue`
    // over more than `log2(Double.MaxValue / 2^51) * 2 minutes`, which is over 32 hours. (Note this
    // analysis is extremely pessimistic.)
    val totalOverflow: Boolean =
      !amplificationOverflow && (totalSum + projectedAmplification * Int.MaxValue).isInfinity

    if (!amplificationOverflow && !totalOverflow) {
      // No rescale needed, just advance the epoch and amplification so the matching
      // amplifyAndIncrement runs against the new epoch's factor and then early return.
      advanceEpoch(now)
      return None
    }

    if (amplificationOverflow) {
      EwmaAmplifier.amplificationOverflowCounter.incrementAndGet()
    } else {
      EwmaAmplifier.totalSumOverflowCounter.incrementAndGet()
    }

    val targetEpoch: Long = epochOf(now)
    if (targetEpoch > currentEpoch) {
      // Advance the currentEpoch directly. We don't call advanceEpoch() to advance the time because
      // it might overflow `amplification` (and `amplification` will be reset to `alpha` in
      // rescale() anyway, so we don't bother to change it).
      currentEpoch = targetEpoch
    }
    rescale()
  }

  /**
   * Computes what [[amplification]] would be after advancing the internal epoch to `now`'s
   * epoch, without mutating any state.
   */
  private def projectAmplification(now: Instant): Double = {
    val elapsedEpochs: Long = epochOf(now) - currentEpoch
    if (elapsedEpochs <= 0) {
      amplification
    } else if (elapsedEpochs == 1) {
      // Optimize for the common case.
      amplification / decay
    } else {
      amplification * Math.pow(decay, -elapsedEpochs.toDouble)
    }
  }

  /**
   * Advances [[currentEpoch]] to `now`'s epoch when `now` is in a later epoch, updating
   * [[amplification]] accordingly. No-op if `now` is no greater than `currentEpoch`.
   */
  private def advanceEpoch(now: Instant): Unit = {
    val epochOfNow: Long = epochOf(now)
    if (epochOfNow > currentEpoch) {
      amplification = projectAmplification(now)
      currentEpoch = epochOfNow
    }
  }

  /** Maps a physical time to the integer epoch index relative to [[startTime]]. */
  private def epochOf(now: Instant): Long =
    (now.toEpochMilli - startTime.toEpochMilli) / 1000
}

object EwmaAmplifier {

  /**
   * Counts the number of times [[EwmaAmplifier.amplification]] overflowed, requiring the caller to
   * rescale all their externally kept load entries.
   */
  private val amplificationOverflowCounter: AtomicLong = new AtomicLong(0)

  /**
   * Counts the number of times [[EwmaAmplifier.totalSum]] overflowed, requiring the caller to
   * rescale all their externally kept load entries.
   */
  private val totalSumOverflowCounter: AtomicLong = new AtomicLong(0)

  /** Test-only accessors for the overflow counters. */
  private[util] object forTestCompanionObject {

    def getAmplificationOverflowCount: Long = amplificationOverflowCounter.get()

    def getTotalSumOverflowCount: Long = totalSumOverflowCounter.get()
  }
}
