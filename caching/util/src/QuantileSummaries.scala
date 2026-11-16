package com.databricks.caching.util

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.{SortedMap, mutable}
import scala.concurrent.duration._

import com.databricks.caching.util.AssertMacros.{iassert, ifail}
import com.databricks.caching.util.QuantileSummaries.{CandidatePair, CandidatePairOrdering, Stat}

/**
 * A summary structure for maintaining *approximate* quantiles over a stream of weighted
 * (value, weight) observations in a memory-bounded manner.
 *
 * Callers use [[insert]] with (value, weight) pairs to build up a weighted distribution, then
 * [[query]] with quantiles in [0, 1] to look up values. For a queried quantile q, [[query]] returns
 * a previously inserted value v whose cumulative weight fraction f (the fraction of total weight
 * from observations with value <= v) approximates q. Because q and f may differ, [[query]] returns
 * f alongside v.
 *
 * Formally, given the configured relative error 0 < epsilon < 1 and current total weight W, a
 * query for quantile `q` returns a value `v` such that
 *
 * {{{
 *   sum_{v_i <= v} w(v_i) >= q * W - epsilon * W, and
 *   sum_{v_i <  v} w(v_i) <  q * W + epsilon * W
 * }}}
 *
 * For example, after inserting the following values with relative error epsilon = 0.01:
 *
 * {{{
 *   insert(10.0, weight = 50.0)
 *   insert(20.0, weight = 1.00)
 *   insert(30.0, weight = 49.0)
 *
 *   // Value  Quantile Range
 *   // 10.0   (0.00, 0.50]
 *   // 20.0   (0.50, 0.51]
 *   // 30.0   (0.51, 1.00]
 *
 *   // Value 10.0's cumulative weight (0.00, 0.50] covers the queried quantile 0.10 and 0.30
 *   // completely.
 *   query(Seq(0.10))  ->  Vector((10.0, 0.50))
 *   query(Seq(0.30))  ->  Vector((10.0, 0.50))
 *
 *   // With epsilon = 0.01, the error tolerance range for the queried quantile 0.50 is
 *   // [0.49, 0.51]. So either value 10.0 (with quantile 0.50) or value 20.0 (with quantile 0.51)
 *   // can be possibly returned for the query. But value 30.0 is not a valid answer because its
 *   // quantile range (0.51, 1.00] doesn't overlap with the target error range.
 *   query(Seq(0.50))  ->  Vector((10.0, 0.50)) or Vector((20.0, 0.51))
 * }}}
 *
 * See <internal link> for a detailed description of the problem and the
 * algorithm.
 *
 * Creates a quantile summaries targeting `relativeError`. `sampleCountUpperBound` specifies an
 * upper bound of the sample values that will be kept in the data structure, and the sample count
 * kept will be proportional with the total memory usage of this data structure. The upper bound may
 * not be fully utilized.
 *
 * The user must note that GK's theoretical space complexity is O((1/e) * log(e * n)), where e is
 * the relative error and n is the number of unique values inserted. If `sampleCountUpperBound` is
 * set insufficiently large to satisfy the relativeError and the number of unique values that will
 * be inserted, the accuracy guarantee as documented above will be compromised. In practice, it's
 * recommended to set the upper bound at least (1/e) * log10(n).
 *
 * @param relativeError Target relative error `epsilon`, in `(0, 1)`.
 * @param sampleCountUpperBound Upper bound on the retained sample count. Must be non-negative.
 *
 * @throws IllegalArgumentException if `relativeError` is not in `(0, 1)`.
 * @throws IllegalArgumentException if `sampleCountUpperBound` is negative.
 */
@NotThreadSafe
class QuantileSummaries[T: Ordering] @throws[IllegalArgumentException]()(
    relativeError: Double,
    sampleCountUpperBound: Int) {

  require(
    relativeError > 0.0 && relativeError < 1.0,
    s"relative_error must be in (0, 1), got $relativeError"
  )
  // Rust enforces this with the unsigned `usize` type.
  require(
    sampleCountUpperBound >= 0,
    s"sample_count_upper_bound must be non-negative, got $sampleCountUpperBound"
  )

  /**
   * Sorted map from retained sample values to their stats. Maintains two invariants:
   *
   * Uncertainty constraint:
   * For each `stat: Stat` in the structure,
   *
   * `stat.gap + stat.delta - stat.weight <= 2 * relativeError * totalWeight`,
   *
   * as long as [[forceCompress]] is not called. This ensures a query result within `relativeError`
   * can always be found; see [[query]] implementation for details.
   *
   * Sentinel guarantee:
   * The stats for the smallest and largest values are never deleted, and their [[Stat.delta]] is
   * always 0.
   */
  private val samples: mutable.TreeMap[T, Stat] = mutable.TreeMap.empty[T, Stat]

  /**
   * Upper bound on the retained-stats count. When the stats are more than `maxSampleCount`, some
   * stats will be forcibly deleted even if that will break the invariants needed to guarantee
   * `relativeError`. In practice, the maxSampleCount should therefore be set conservatively large
   * with respect to the desired relative error.
   *
   * Here we set the memory bound. We first calculate an approximate sufficiently large max count,
   * then clamp it down to the user-specified upper bound if needed.
   *
   * GK's theoretical space complexity is O((1/e) * log(e * n)), where e is the relative error and n
   * is the number of unique values. For a fixed e, this still grows linearly in log(n), so there is
   * no constant theoretical bound. In practice, though, the retained-sample count stays close to
   * 1/e; the log(n) factor only appears under rare, adversarial access patterns. We therefore cap
   * it at 10/e, which leaves generous headroom so the accuracy-breaking force-compress path is
   * rarely hit.
   *
   * Then, we clamp it to the user specified value.
   */
  private val maxSampleCount: Int = math.min((10.0 / relativeError).toInt, sampleCountUpperBound)

  /** Total weight of every observation ever inserted. */
  private var totalWeight: Double = 0.0

  /** The logger. */
  private val logger: PrefixLogger = PrefixLogger.create(getClass, prefix = "")

  /** Inserts an observation of `value` with `weight`. */
  @throws[IllegalArgumentException]("if `weight` is negative or not finite")
  def insert(value: T, weight: Double): Unit = {
    require(
      !weight.isNaN && !weight.isInfinite && weight >= 0.0,
      s"weight must be strictly non-negative and finite, got $weight"
    )
    // A zero-weight observation carries no information; treat it as a no-op so it never occupies a
    // sample slot or perturbs the stats.
    if (weight == 0.0) {
      return
    }
    totalWeight += weight

    samples.get(value) match {
      case Some(sample: Stat) =>
        // A value already present absorbs the weight. The `delta` remains the same because the
        // increase of the weight doesn't introduce new uncertainty.
        samples.update(
          value,
          sample.copy(weight = sample.weight + weight, gap = sample.gap + weight)
        )
      case None =>
        // The gap for the newly inserted value should be the same as weight. This is because the
        // gap between the cumulative weights of the new value and the first less value is minimum
        // when there is no other deleted stats between them, in which case the gap is just the
        // weight of the new value itself.
        val gap: Double = weight

        // Calculate delta based on the next greater value in the samples. `value` is absent from
        // `samples`, so the first entry at or after `value` is strictly greater than it.
        val nextSamples: Iterator[(T, Stat)] = samples.iteratorFrom(value)
        val delta: Double = if (nextSamples.hasNext) {
          val (_, nextStat): (T, Stat) = nextSamples.next()
          // The maximum possible cumulative weight of the new value should be the maximum possible
          // cumulative weight of the next existing value minus its own weight. See
          // <internal link> for a formal proof. Note this also ensures
          // the smallest value always has 0 delta.
          //
          // Mathematically this result should always be non-negative. But we still max it with 0.0
          // to prevent floating point error to result in a negative value.
          math.max(nextStat.gap + nextStat.delta - nextStat.weight, 0.0)
        } else {
          // The implementation ensures the stats for the largest value is never deleted. So the
          // estimate of cumulative weight of the largest value is always accurate and delta should
          // be 0.
          0.0
        }

        samples.update(value, Stat(weight, gap, delta))
    }

    compressIfNeeded()
  }

  /**
   * Queries a batch of quantiles in a single pass. Returns one `(value, cumulativeFraction)` pair
   * per requested quantile, in the same order as `quantiles`, or an empty vector if there are no
   * observations yet. `cumulativeFraction` is the fraction of total weight covered by observations
   * with value `<= value`, and may differ from the queried quantile.
   */
  @throws[IllegalArgumentException]("if any quantile is not in [0, 1]")
  def query(quantiles: Seq[Double]): Vector[(T, Double)] = {
    for (quantile: Double <- quantiles) {
      require(quantile >= 0.0 && quantile <= 1.0, s"quantile must be in [0, 1], got $quantile")
    }

    // Early return for the trivial cases to simplify the querying code below.
    if (samples.isEmpty || quantiles.isEmpty) {
      return Vector.empty
    }

    // Build a sorted map from the minimum cumulative weights to the values; then for each queried
    // quantile, use the sorted map to find the first value whose cumulative weight is greater than
    // or equal to a "target rank" (see below) as the returned result.
    val minCumulativeWeightsToValues: SortedMap[Double, T] = {
      val builder = SortedMap.newBuilder[Double, T]

      // The minimum possible cumulative weight for the value we are visiting.
      var currentMinCumulativeWeight: Double = 0.0
      for (valueWithStat: (T, Stat) <- samples) {
        val (value, stat): (T, Stat) = valueWithStat
        // Skip a value whose gap is too small to change the running cumulative weight (e.g. after a
        // `rescaleWeights` with factor 0.0, or repeated `rescaleWeights` with tiny factors).
        // Inserting it into minCumulativeWeightsToValues would collide on the same key as its
        // predecessor and overwrite that smaller value, so the query would stop returning the
        // lowest value for that rank. Ignoring these values with near-zero weights should not
        // affect correctness.
        if (currentMinCumulativeWeight + stat.gap != currentMinCumulativeWeight) {
          currentMinCumulativeWeight += stat.gap
          builder += ((currentMinCumulativeWeight, value))
        }
      }
      builder.result()
    }

    // We use the computed total weight instead of the stored totalWeight, because they may be
    // slightly different due to summation order/floating point resolution.
    if (minCumulativeWeightsToValues.isEmpty) {
      // Defense against an extreme case: although [[insert]] only allows non-negative weights, a
      // [[rescaleWeights]] call with factor 0.0, or multiple [[rescaleWeights]] calls with small
      // factors, can underflow the total weight to 0.0. When that happens,
      // currentMinCumulativeWeight above will remain 0 and minCumulativeWeightsToValues will be
      // empty. In that case we return the minimum tracked value as a fallback. Its cumulative load
      // fraction is mathematically undefined; we return 0.0 as a placeholder, matching
      // NonLossyQuantileSummaries.
      val (minValue, _): (T, Stat) = samples.head
      return Vector.fill(quantiles.size)((minValue, 0.0))
    }
    val computedTotalWeight: Double = minCumulativeWeightsToValues.lastKey
    iassert(
      computedTotalWeight > 0.0,
      "minCumulativeWeightsToValues is non-empty, meaning currentMinCumulativeWeight doesn't " +
      "remain 0, and totalWeight must be positive"
    )

    // Build the result from the queried quantiles.
    val result: Vector[(T, Double)] = quantiles.map { quantile: Double =>
      val targetRank: Double =
        quantile * computedTotalWeight - relativeError * computedTotalWeight
      // Return the first value `v_i` with min cumulative weight >= targetRank. Why is this value a
      // valid result satisfying the error bound constraint? From `cumulative weight >= targetRank`
      // we already know that
      //
      // sum_{j|v_j <= v_i} w(v_j) >= minCumulativeWeight(v_i) >= q * W - epsilon * W.
      //
      // Then,
      // (1) if i == 0, then sum_{j|v_j < v_i} w(v_j) == 0 <= q * W + epsilon * W (see the
      //     "Sentinel Property" of samples).
      // (2) if i > 0, then minCumulativeWeight(v_{i-1}) < q * W - epsilon * W (because v_i is the
      //     first value whose minCumulativeWeight is no less than targetRank), then, due to
      //     "Uncertainty Constraint Property", we have
      //     sum_{j|v_j < v_i} w(v_j)
      //       == (sum_{j|v_j <= v_i} w(v_j)) - w(v_i)
      //       <= maxCumulativeWeight(v_i) - w(v_i)
      //       == minCumulativeWeight(v_i) + delta_i - w(v_i)
      //       == minCumulativeWeight(v_{i-1}) + g_i + delta_i - w(v_i)
      //          (From Uncertainty Constraint Property)
      //       <= minCumulativeWeight(v_{i-1}) + 2 * epsilon * W
      //       <  q * W - epsilon * W + 2 * epsilon * W
      //       == q * W + epsilon * W.
      // So v_i is the value satisfying the requirement documented by the contract of
      // QuantileSummaries.
      //
      // TODO(<internal bug>): Although returning the first satisfactory answer is valid, it may not be
      //               optimal, as there may be other values that satisfy the error range. It is
      //               unclear how we can qualify which one is the "most accurate". One option is to
      //               return the value whose minCumulativeWeight + delta/2 deviates the least from
      //               the queried quantile, i.e. its "median" of possible weight ranges is closest
      //               to our query. We leave this as a future optimization.
      val candidates: Iterator[(Double, T)] = minCumulativeWeightsToValues.iteratorFrom(targetRank)
      if (!candidates.hasNext) {
        ifail(
          "the map is non empty and targetRank <= totalWeight (the last map key), so there must " +
          "be a value whose cumulative weight is no less than target"
        )
      }
      val (minCumulativeWeight, value): (Double, T) = candidates.next()
      // Return the lower bound of the weight fraction of v, which could be different from the
      // queried quantile.
      (value, minCumulativeWeight / computedTotalWeight)
    }.toVector
    result
  }

  /**
   * Multiplies every existing observation's weight by `factor`. Since all stored weights scale
   * uniformly, the ratios between them are preserved, so quantile queries over the currently-stored
   * data return the same results. Its only observable effect is on subsequent inserts, where a
   * later insert's weight is now larger/smaller relative to the rescaled history.
   */
  @throws[IllegalArgumentException]("if `factor` is not within [0.0, 1.0]")
  def rescaleWeights(factor: Double): Unit = {
    require(factor >= 0.0 && factor <= 1.0, s"factor must be in [0, 1], got $factor")
    // Snapshot the entries before iterating since we update the map values in the loop body.
    for (valueWithStat: (T, Stat) <- samples.toVector) {
      val (value, stat): (T, Stat) = valueWithStat
      // A rescale with factor 0.0, or multiple rounds of rescaling with small factors, can
      // underflow the weights and other stats down to 0.0. When that happens, we keep the stats in
      // the data structure instead of eagerly remove them. A stat with zero weight and non-zero gap
      // is still informative and helps improve the accuracy of queries. A stat with zero weight and
      // zero gap will be very likely removed in compression once there are other non-zero weights
      // inserted.
      samples.update(
        value,
        Stat(stat.weight * factor, stat.gap * factor, stat.delta * factor)
      )
    }
    totalWeight *= factor
  }

  /** Compresses the retained samples if needed. */
  private def compressIfNeeded(): Unit = {
    if (samples.size > maxSampleCount) {
      gracefulCompress()

      // In most cases, graceful compression should bring the count well below the
      // maxSampleCount/2 cap. If it could not reclaim enough, we fall back to a forceful
      // compression, which cuts down sample count regardless of the uncertainty constraint, and
      // may thus break the QuantileSummaries' accuracy guarantee.
      //
      // The target is set to maxSampleCount / 2 because it allows sufficient number of insertions
      // before the next compress, so the overhead of compress is well amortized.
      val targetSize: Int = maxSampleCount / 2
      if (samples.size > targetSize) {
        forceCompress(targetSize)
      }
    }
  }

  /**
   * Compresses the existing sample stats in a manner that preserves the invariants necessary to
   * satisfy the relativeError requirement.
   *
   * PRECONDITION: `samples` must be non-empty.
   */
  private def gracefulCompress(): Unit = {
    iassert(samples.nonEmpty, "gracefulCompress expects non-empty stats")

    // The maximum allowed uncertainty for any sample to retain during graceful compression.
    val targetUncertainty: Double = 2.0 * relativeError * totalWeight

    // We will put the new stats after compress into this map.
    val compressedStats: mutable.TreeMap[T, Stat] = mutable.TreeMap.empty[T, Stat]

    // 1. Protect the two sentinels and initialize the merge target.
    // The maximum simultaneously serves as the seed of the merge pass below, during which we fold
    // predecessors into it.
    var (mergeTargetValue, mergeTargetStat): (T, Stat) = samples.last
    samples.remove(mergeTargetValue)
    if (samples.nonEmpty) {
      // Ensures the minimum value is never deleted.
      val (minValue, minStat): (T, Stat) = samples.head
      samples.remove(minValue)
      compressedStats.update(minValue, minStat)
    }

    // 2. Then do the compression pass by traversing the rest of the values in descending order,
    // and attempt to fold values into the current merge target.
    val remainingSamples: Vector[(T, Stat)] = samples.toVector
    samples.clear()
    for (valueWithStat: (T, Stat) <- remainingSamples.reverseIterator) {
      val (value, stat): (T, Stat) = valueWithStat
      if (QuantileSummaries.combinedUncertainty(stat, mergeTargetStat) <= targetUncertainty) {
        // If the current `stat` can be combined into the head without breaking the invariant,
        // combine them.
        // See "Delete" method in <internal link> for why this
        // combination is correct.
        mergeTargetStat = mergeTargetStat.copy(gap = mergeTargetStat.gap + stat.gap)
      } else {
        // We cannot safely combine the current stat into the head stat, so we put the current
        // head stat into the result set, and use the current stat as the new head to try to
        // combine its predecessor to it.
        compressedStats.update(mergeTargetValue, mergeTargetStat)
        mergeTargetValue = value
        mergeTargetStat = stat
      }
    }

    // 3. Store the final (smallest) surviving merge target.
    compressedStats.update(mergeTargetValue, mergeTargetStat)

    samples ++= compressedStats
  }

  /**
   * Forcefully compresses the stats to make the number of remained stats no more than
   * `targetSize`.
   */
  private def forceCompress(targetSize: Int): Unit = {
    // Fire an alert for this bad case when we need force compression.
    logger.alert(
      Severity.DEGRADED,
      CachingErrorCode.QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN,
      s"retained samples exceeded max $maxSampleCount, force-dropped to ${samples.size} and " +
      s"broke the accuracy invariant; consider a larger max_size",
      every = 60.seconds
    )

    // To give up as little accuracy as possible, we greedily merge the adjacent pair with the
    // lowest combined uncertainty first (tracked in a min heap keyed on that uncertainty) until at
    // most maxSampleCount / 2 stats remain.

    // Preserve the sentinel property for the min value. The max value is preserved naturally
    // because we always merge a value into its successor.
    val (minValue, minStat): (T, Stat) = samples.head
    samples.remove(minValue)

    // The remaining stats are the samples above the minimum. Unlike Rust, Scala can share the
    // values between the map and the heap below without reference counting.
    val remainingStats: mutable.TreeMap[T, Stat] = samples

    // Seed the heap with every adjacent (predecessor, successor) pair.
    val pairsMinHeap: mutable.PriorityQueue[CandidatePair[T]] =
      mutable.PriorityQueue.empty[CandidatePair[T]](
        new CandidatePairOrdering[T](implicitly[Ordering[T]])
      )
    val remainingEntries: Vector[(T, Stat)] = remainingStats.toVector
    for (i: Int <- 1 until remainingEntries.size) {
      val (prevValue, prevStat): (T, Stat) = remainingEntries(i - 1)
      val (currentValue, currentStat): (T, Stat) = remainingEntries(i)
      pairsMinHeap.enqueue(
        CandidatePair(
          QuantileSummaries.combinedUncertainty(prevStat, currentStat),
          currentValue,
          prevValue
        )
      )
    }

    // Merging requires at least two remaining samples. The +1 counts the minimum, which was set
    // aside and will be reinserted, toward the target size.
    while (remainingStats.size >= 2 && remainingStats.size + 1 > targetSize) {
      iassert(
        pairsMinHeap.nonEmpty,
        "remainingStats is non-empty, so pairsMinHeap must be non-empty"
      )
      val candidatePair: CandidatePair[T] = pairsMinHeap.dequeue()
      val currentValue: T = candidatePair.currentValue
      val prevValue: T = candidatePair.prevValue
      // If the current pair with the lowest uncertainty is still up-to-date, combine the pair in
      // remainingStats.
      (remainingStats.get(currentValue), remainingStats.get(prevValue)) match {
        case (Some(currentStat: Stat), Some(prevStat: Stat))
            if QuantileSummaries.combinedUncertainty(prevStat, currentStat) ==
            candidatePair.combinedUncertainty =>
          remainingStats.remove(prevValue)
          val mergedStat: Stat = currentStat.copy(gap = currentStat.gap + prevStat.gap)
          remainingStats.update(currentValue, mergedStat)
          // After removing prevValue, if there's still a predecessor of currentValue that can form
          // a pair with it, put that into the heap.
          for (newPrev: (T, Stat) <- remainingStats
              .rangeImpl(None, Some(currentValue))
              .lastOption) {
            val (newPrevValue, newPrevStat): (T, Stat) = newPrev
            pairsMinHeap.enqueue(
              CandidatePair(
                QuantileSummaries.combinedUncertainty(newPrevStat, mergedStat),
                currentValue,
                newPrevValue
              )
            )
          }
          // The `gap` of the current stat is changed. If the current stat has a successor, push
          // the updated pair between the current stat and its successor into the heap.
          val successors: Iterator[(T, Stat)] = remainingStats.iteratorFrom(currentValue).drop(1)
          if (successors.hasNext) {
            val (nextValue, nextStat): (T, Stat) = successors.next()
            pairsMinHeap.enqueue(
              CandidatePair(
                QuantileSummaries.combinedUncertainty(mergedStat, nextStat),
                nextValue,
                currentValue
              )
            )
          }
        case _ =>
          // The pair is stale: one of its values was merged away, or its stats changed since the
          // pair was pushed. A refreshed pair has been pushed in that case, so skip this one.
          ()
      }
    }

    samples.update(minValue, minStat)
  }

  /** The test functions of a [[QuantileSummaries]]. */
  private[util] object forTest {

    /** Returns the current total weight of all observations inserted so far. */
    def totalWeight: Double = QuantileSummaries.this.totalWeight

    /**
     * Returns the number of retained samples, so tests can assert that compression actually bounds
     * memory.
     */
    def sampleCount: Int = samples.size

    /** Asserts that the uncertainty invariant of the structure is held. */
    @throws[AssertionError]("if any retained sample violates the invariant")
    def assertUncertaintyConstraint(): Unit = {
      val targetUncertainty: Double = 2.0 * relativeError * QuantileSummaries.this.totalWeight
      for (stat: Stat <- samples.valuesIterator) {
        assert(stat.delta >= 0.0)
        assert(stat.gap >= stat.weight)
        val uncertainty: Double = stat.gap + stat.delta - stat.weight
        assert(
          uncertainty <= targetUncertainty,
          s"accuracy invariant violated: gap + delta - weight = $uncertainty exceeds " +
          s"relative_error * total_weight = $targetUncertainty"
        )
      }
    }

    /** Asserts that the sentinel guarantee of the structure is held. */
    @throws[AssertionError]("if any retained sample violates the invariant")
    def assertSentinelGuarantee(): Unit = {
      if (samples.nonEmpty) {
        val (_, firstStat): (T, Stat) = samples.head
        val (_, lastStat): (T, Stat) = samples.last

        // The lowest and highest entries should have no uncertainty.
        assert(firstStat.delta == 0.0)
        assert(lastStat.delta == 0.0)

        // Nothing is ever deleted below the tracked minimum value, so its gap is exactly its own
        // weight.
        assert(firstStat.gap == firstStat.weight)

        val calculatedTotalWeight: Double = samples.valuesIterator.map((_: Stat).gap).sum
        val trackedTotalWeight: Double = QuantileSummaries.this.totalWeight
        // The retained gaps sum to the tracked total, meaning nothing is ever deleted above the
        // current tracked maximum value. The totalWeight and the sum of all gaps are calculated in
        // different ways, so they are subject to a small float point error.
        val floatingPointTolerance: Double = trackedTotalWeight * 1e-10 + 1e-10
        assert(
          math.abs(calculatedTotalWeight - trackedTotalWeight) < floatingPointTolerance,
          s"weights: $calculatedTotalWeight $trackedTotalWeight"
        )
      }
    }
  }
}

object QuantileSummaries {

  /**
   * The stat that should be tracked for an individual value, needed to implement the quantile
   * query. See <internal link> for a formal definition of the fields in
   * the stats.
   *
   * @param weight The current tracked weight of the value. Note this is not necessarily the total
   *               weight of value observed by the quantile summaries so far, because there might be
   *               previous Stats for the same value being deleted. In that case, the contribution
   *               from the deleted previous weight of the value will be reflected in `delta`.
   * @param gap Intuitively, this is the min possible "gap" between the cumulative weight of the
   *            current value and the cumulative weight of the predecessor of the current value (the
   *            first value less than it), and it reflects the weights from the possible deleted
   *            `Stat`s between these 2 values.
   *
   *            Formally, if we define the minimum possible cumulative weight for the current value
   *            x_i as r_min(x_i), and the minimum possible cumulative weight for the first less
   *            value is r_min(x_{i-1}), then gap_i = r_min(x_i) - r_min(x_{i-1}).
   *
   *            See the "g" value in <internal link>.
   * @param delta The uncertainty of the cumulative weight of the current value, defined as the
   *              difference between the min possible and the max possible cumulative weight of the
   *              value. Formally, if we define the maximum possible cumulative weight for the
   *              current value x_i as r_max(x_i), then delta_i = r_max(x_i) - r_min(x_i).
   *
   *              See the "delta" value in <internal link>.
   */
  private case class Stat(weight: Double, gap: Double, delta: Double)

  /**
   * A candidate merge pair of sample `prevValue` into its immediate successor `currentValue`.
   * Primarily ordered by `combinedUncertainty` descending (see [[CandidatePairOrdering]]) so the
   * heap can pop the pair with the lowest uncertainty.
   */
  private case class CandidatePair[T](combinedUncertainty: Double, currentValue: T, prevValue: T)

  /**
   * Orders [[CandidatePair]]s so that the pair with the lowest `combinedUncertainty` is the
   * greatest, breaking ties by `currentValue` and then `prevValue` ascending. Matches the derived
   * ordering of the Rust `CandidatePair`, whose uncertainty is wrapped in `Reverse`.
   */
  private class CandidatePairOrdering[T](valueOrdering: Ordering[T])
      extends Ordering[CandidatePair[T]] {
    override def compare(x: CandidatePair[T], y: CandidatePair[T]): Int = {
      val uncertaintyComparison: Int = y.combinedUncertainty.compare(x.combinedUncertainty)
      if (uncertaintyComparison != 0) {
        uncertaintyComparison
      } else {
        val currentValueComparison: Int = valueOrdering.compare(x.currentValue, y.currentValue)
        if (currentValueComparison != 0) {
          currentValueComparison
        } else {
          valueOrdering.compare(x.prevValue, y.prevValue)
        }
      }
    }
  }

  /** Returns the combined uncertainty from a pair of adjacent stats. */
  private def combinedUncertainty(prevStat: Stat, currentStat: Stat): Double =
    prevStat.gap + currentStat.gap + currentStat.delta - currentStat.weight
}
