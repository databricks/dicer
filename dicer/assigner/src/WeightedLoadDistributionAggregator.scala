package com.databricks.dicer.assigner

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, FiniteDuration}

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.dicer.common.LoadMeasurement
import com.databricks.dicer.common.SliceletData.LoadDistribution
import com.databricks.dicer.common.SliceletData.LoadDistribution.CdfPoint
import com.databricks.dicer.external.{HighSliceKey, Slice, SliceKey}

/**
 * Aggregates load distributions (approximate CDFs of how load is spread across a Slice's key range)
 * reported by a Slice's replicas into a single Slice-wide distribution. A replicated Slice has each
 * replica report its own view, and each sees only a fraction of the traffic; combining them
 * recovers the whole-Slice picture.
 *
 * Load distributions are combined into one aggregate distribution via a mixture weighted by both
 * load and age.
 *
 *   F(k) = sum_i ( w_i * F_i(k) )
 *
 * where k is a key in the range, F_i(k) is input i's cumulative load fraction at k (the share of
 * its load at or below k), w_i is input i's normalized weight, and F(k) is the aggregate fraction.
 *
 * Each input's weight combines its load and an age decay: w_i is proportional to
 * load_i * 2^(-age_i / halfLife), normalized so the weights sum to 1. Fresher and heavier inputs
 * count more (see [[addDistribution]]).
 *
 * Any computed mixture is a valid CDF (fractions non-decreasing and in [0, 1]) but approximate:
 * inputs are sparsely-sampled CDFs, so an input's load between (and beyond) its sampled points has
 * to be estimated via interpolation (see [[compute]]).
 *
 * @param slice    the Slice these distributions span; supplies the low/high bounds used to anchor
 *                 each input's CDF.
 * @param halfLife the interval over which an input's weight decays by half, applied to the input's
 *                 age (see [[addDistribution]]).
 */
@NotThreadSafe
private[assigner] class WeightedLoadDistributionAggregator(slice: Slice, halfLife: Duration) {

  /** The (distribution, weight) pairs accumulated so far, one per added distribution. */
  private val distributionsWithWeights = ArrayBuffer.empty[(LoadDistribution, Double)]

  /**
   * Adds `distribution` to the mixture with a weight that combines its load and an age decay, so
   * that fresher and heavier inputs count more.
   *
   * The age decay mirrors [[LoadWatcher.WeightedLoadAccumulator]], which aggregates the total load
   * for a Slice, so the distribution mixture uses the same weighting as the scalar load.
   *
   * @param age          the age of this distribution's source; older inputs decay to a smaller
   *                     weight.
   * @param load         the amount of load this distribution should contribute to the mixture (i.e.
   *                     its weight before age decay).
   * @param distribution the load distribution (CDF) to add.
   */
  @throws[IllegalArgumentException](
    "if load is not valid, age is negative, or a distribution point is outside the slice"
  )
  def addDistribution(age: FiniteDuration, load: Double, distribution: LoadDistribution): Unit = {
    LoadMeasurement.requireValidLoadMeasurement(load)
    require(age >= Duration.Zero, s"age must be non-negative: $age")
    // LoadDistribution guarantees ascending keys, so checking the endpoints is sufficient.
    require(
      distribution.points.isEmpty ||
      (slice.contains(distribution.points.head.key) && slice
        .contains(distribution.points.last.key)),
      "distribution points must be contained in slice"
    )
    val weight: Double = Math.pow(2, -age.toMillis.toDouble / halfLife.toMillis) * load
    distributionsWithWeights += ((distribution, weight))
  }

  /**
   * Returns the weighted mixture of the added distributions, or `None` if none were added.
   *
   * The result's points are the ''union'' of the inputs' keys; at each union key we take the
   * weighted average of every input's cumulative fraction there, interpolating (by each key's
   * integer value) the fraction of any input lacking a point at that key (see [[CdfCursor]]).
   * Weights are normalized internally; if they sum to zero (i.e. all inputs had zero load) the
   * inputs are averaged with equal weight.
   *
   * For example, aggregating two equally-weighted inputs A and B over the Slice `["", high)`:
   * {{{
   *   A:  (f, 0.5) (m, 1.0)
   *   B:  (m, 0.4) (t, 1.0)
   *   union keys:  f       m       t
   * }}}
   * At `m` both inputs have a point, so the aggregate is 0.5*(1.0) + 0.5*(0.4) = 0.7. At `f` and
   * `t` only one input has a point and the other's fraction is interpolated.
   *
   * The resulting mixture is a valid CDF. The normalized weights are non-negative and sum to 1,
   * so each resulting fraction is a convex combination of input fractions in [0, 1] and therefore
   * also lies in [0, 1]. Each input's evaluated CDF is non-decreasing, and a weighted sum of
   * non-decreasing functions with non-negative weights is also non-decreasing.
   *
   * @return the weighted mixture of the added distributions, or `None` if none were added.
   */
  def compute(): Option[LoadDistribution] = {
    val distributions: Seq[(LoadDistribution, Double)] = distributionsWithWeights.toVector
    if (distributions.isEmpty) {
      return None
    }

    if (distributions.size == 1) {
      val (distribution, _): (LoadDistribution, Double) = distributions.head
      return Some(distribution)
    }

    // Normalize weighted distributions, so the mixture coefficients sum to 1.
    // Fall back to equal weights (an unweighted average) when the total is zero.
    val totalWeight: Double = distributions.map {
      case (_: LoadDistribution, weight: Double) => weight
    }.sum
    val uniformWeight: Double = 1.0 / distributions.size
    val normalizedDistributions: Seq[(LoadDistribution, Double)] = distributions.map {
      case (distribution: LoadDistribution, weight: Double) =>
        (distribution, if (totalWeight > 0.0) weight / totalWeight else uniformWeight)
    }

    // The output keys are the sorted, deduplicated union of all inputs' point keys (sorted via
    // SliceKey's implicit Ordering).
    val unionKeys: Seq[SliceKey] = normalizedDistributions
      .flatMap {
        case (distribution: LoadDistribution, _: Double) =>
          distribution.points.map((point: CdfPoint) => point.key)
      }
      .distinct
      .sorted

    // One stateful cursor per distribution, swept in lockstep with the ascending `unionKeys`.
    val distributionCursorsWithWeights: Seq[(CdfCursor, Double)] = normalizedDistributions.map {
      case (distribution: LoadDistribution, weight: Double) =>
        (new CdfCursor(distribution.points.toIndexedSeq), weight)
    }

    // For each union key, emit a point carrying the weighted average of every input's cumulative
    // fraction at that key (interpolating an input's fraction where it has no point there).
    //
    // `runningMax` guards against floating-point error nudging a value below its predecessor or
    // above 1.0.
    val aggregatedPoints = Seq.newBuilder[CdfPoint]
    var runningMax: Double = 0.0
    for (key: SliceKey <- unionKeys) {
      val aggregatedFraction: Double = distributionCursorsWithWeights.map {
        case (cursor: CdfCursor, weight: Double) => weight * cursor.advanceAndGetFraction(key)
      }.sum
      runningMax = Math.min(1.0, Math.max(runningMax, aggregatedFraction))
      aggregatedPoints += CdfPoint(key, runningMax)
    }

    // The aggregate's error is at least the worst input's error; interpolation across gaps can only
    // add to it. This is a loose lower bound, but the field is only used for
    // reporting/documentation.
    val maxErrorFraction: Double = distributions.map {
      case (distribution: LoadDistribution, _: Double) => distribution.maxErrorFraction
    }.max

    Some(LoadDistribution(aggregatedPoints.result(), maxErrorFraction))
  }

  /**
   * A stateful, forward-only cursor over one input distribution's CDF points. Because it only ever
   * advances, callers must query with non-decreasing keys for correct results (see
   * `advanceAndGetFraction`'s precondition); each lookup is then amortized O(1).
   * [[compute]] sweeps all inputs in lockstep
   * with the ascending `unionKeys`.
   *
   * If the distribution does not have an exact sampled point at `key`, we return a projected
   * fraction interpolated linearly between its two surrounding points, or the Slice bounds for keys
   * outside the distribution's point range (`slice.lowInclusive` represents 0.0,
   * `slice.highExclusive` represents 1.0; see [[interpolateFraction]]). An empty distribution has
   * no points, so it ramps uniformly from 0.0 to 1.0 across the Slice.
   *
   * For example, for the input `(f, 0.5) (m, 0.7)` over the Slice `["", high)`: at `f` the fraction
   * is 0.5 (a sampled point); below `f` (e.g. at `b`) it ramps from 0 at `""` up toward 0.5;
   * between `f` and `m` it ramps from 0.5 up toward 0.7; above `m` (e.g. at `t`) it ramps from 0.7
   * up toward 1.0 at `high`.
   *
   * PRECONDITION: `points` are strictly ascending by key and all contained in `slice`.
   */
  private class CdfCursor(points: IndexedSeq[CdfPoint]) {

    /** Index of the last point at or below the queried key, or -1 before the first point. */
    private var gapIndex: Int = -1

    /**
     * Advances the cursor to `key` and returns this input's cumulative load fraction there,
     * interpolating when no sampled point exists at `key`.
     *
     * PRECONDITION: `key` is >= every key passed to prior calls, and is contained in `slice`.
     */
    def advanceAndGetFraction(key: SliceKey): Double = {
      iassert(gapIndex < 0 || points(gapIndex).key <= key)

      // Advance past sampled points at or below `key`. Before the first point or after the last,
      // use the corresponding Slice bound as the interpolation endpoint.
      while (gapIndex + 1 < points.length && points(gapIndex + 1).key <= key) {
        gapIndex += 1
      }
      val (lowerKey, lowerFraction): (SliceKey, Double) = if (gapIndex >= 0) {
        val point: CdfPoint = points(gapIndex)
        (point.key, point.cumulativeLoadFraction)
      } else {
        (slice.lowInclusive, 0.0)
      }
      val (upperKey, upperFraction): (HighSliceKey, Double) = if (gapIndex + 1 < points.length) {
        val point: CdfPoint = points(gapIndex + 1)
        (point.key, point.cumulativeLoadFraction)
      } else {
        (slice.highExclusive, 1.0)
      }
      if (lowerKey.compare(key) == 0) {
        // Exact match for `key`, return its fraction directly.
        lowerFraction
      } else {
        interpolateFraction(lowerKey, lowerFraction, upperKey, upperFraction, targetKey = key)
      }
    }
  }

  /**
   * Linearly interpolates a cumulative fraction at `targetKey` within `[lowKey, highKey)`,
   * given the fractions `lowFraction` and `highFraction` at the endpoints.
   *
   * The fraction is interpolated according to `targetKey`'s relative position between the
   * endpoints, using the same key-space distance as [[LoadMap]].
   *
   * PRECONDITION: `lowKey <= targetKey < highKey`
   * PRECONDITION: `lowFraction <= highFraction`
   *
   * @param lowKey       the lower endpoint key.
   * @param lowFraction  the fraction at the lower endpoint.
   * @param highKey      the upper endpoint key.
   * @param highFraction the fraction at the upper endpoint.
   * @param targetKey    the key to interpolate the fraction at.
   * @return the interpolated fraction at `targetKey`.
   */
  private def interpolateFraction(
      lowKey: SliceKey,
      lowFraction: Double,
      highKey: HighSliceKey,
      highFraction: Double,
      targetKey: SliceKey): Double = {
    iassert(
      lowKey <= targetKey && targetKey < highKey,
      s"targetKey ($targetKey) must be in [$lowKey, $highKey)"
    )
    iassert(
      lowFraction <= highFraction,
      s"lowFraction ($lowFraction) must not exceed highFraction ($highFraction)"
    )
    // The keys may differ in byte length, so we convert them to a magnitude padded to a common
    // length before comparing (see `SliceKeyMath.toBigIntWithLength`).
    val length: Int = Math.max(
      SliceKeyMath.byteLength(lowKey),
      Math.max(SliceKeyMath.byteLength(highKey), SliceKeyMath.byteLength(targetKey))
    )
    val low: BigInt = SliceKeyMath.toBigIntWithLength(lowKey, length)
    val high: BigInt = SliceKeyMath.toBigIntWithLength(highKey, length)
    val gap: BigInt = high - low
    if (gap == 0) {
      // `lowKey` and `highKey` collapse to the same padded magnitude: they differ only by trailing
      // zero bytes (e.g. highKey == lowKey.successor(), or more zeros), so no key lies strictly
      // between them *in magnitude*. There is nothing to interpolate across, so return lowFraction.
      lowFraction
    } else {
      val positionInGap: Double =
        SliceKeyMath.getProperRatio(SliceKeyMath.toBigIntWithLength(targetKey, length) - low, gap)
      lowFraction + positionInGap * (highFraction - lowFraction)
    }
  }
}
