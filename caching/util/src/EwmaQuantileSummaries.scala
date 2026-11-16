package com.databricks.caching.util

import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe

/**
 * A summary structure maintaining *approximate* quantiles over a stream of weighted (value, weight)
 * observations where weights are decayed in an EWMA manner based on the time they were inserted.
 *
 * See [[QuantileSummaries]] for a detailed contract of how the observations can be inserted or
 * queried, and the contract for approximate results.
 *
 * Creates an EWMA layer that decays weights with the given `halfLifeSeconds`, using `startTime` as
 * the basis for the decay. See [[QuantileSummaries]] for the meaning of relativeError and
 * sampleCountUpperBound.
 *
 * @param startTime The time used as the basis for the decay.
 * @param halfLifeSeconds The half-life of the EWMA, in seconds. Must be positive.
 * @param relativeError Target relative error, in `(0, 1)`.
 * @param sampleCountUpperBound Upper bound on the retained sample count. Must be non-negative.
 *
 * @throws IllegalArgumentException if `halfLifeSeconds` is not positive or `relativeError` is not
 *                                  in `(0, 1)`, or propagated from [[QuantileSummaries]] (e.g. an
 *                                  invalid `sampleCountUpperBound`).
 */
@NotThreadSafe
class EwmaQuantileSummaries[T: Ordering] @throws[IllegalArgumentException]()(
    startTime: Instant,
    halfLifeSeconds: Double,
    relativeError: Double,
    sampleCountUpperBound: Int) {

  require(halfLifeSeconds > 0, s"half_life must be positive, got $halfLifeSeconds seconds")
  require(
    relativeError > 0.0 && relativeError < 1.0,
    s"relative_error must be in (0, 1), got $relativeError"
  )

  /**
   * The underlying [[QuantileSummaries]] tracking the histogram from values to the
   * EWMA-transformed weights.
   */
  private val summary = new QuantileSummaries[T](relativeError, sampleCountUpperBound)

  /** Amplifies the incoming weights to effectively decay the historical weights. */
  private val amplifier = new EwmaAmplifier(startTime, halfLifeSeconds)

  /**
   * Inserts an observation of `value` with integer `weight` at time `now`. `weight`s for the same
   * `value` will be effectively EWMA-smoothed based on their insertion time `now`.
   *
   * If `now`s passed to multiple inserts are non-monotonic, the highest timestamp seen when
   * `insert` is called will be used.
   *
   * Inserting zero or negative weight is a no-op.
   *
   * No exception is expected to be thrown: the factor passed to the underlying summary is always in
   * [0, 1], and the amplified weight is always non-negative and finite because the amplifier
   * reserves room before amplifying.
   *
   * Note the interface accepts an [[Instant]] here instead of a monotonic ticker time. We can use
   * Instant as we do elsewhere (LossyEwmaCounter, EwmaCounter) because the downstream consumer
   * (e.g. Dicer Assigner) eventually needs to compare these timestamps from multiple sources (e.g.
   * Slicelets), and the accuracy requirements aren't so tight that we need to complicate the code
   * by passing an extra ticker time alongside the Instant.
   */
  def insert(now: Instant, value: T, weight: Int): Unit = {
    if (weight <= 0) {
      return
    }
    for (factor: Double <- amplifier.advanceAndRescaleIfNeeded(now)) {
      summary.rescaleWeights(factor)
    }
    val amplifiedWeight: Double = amplifier.amplifyAndIncrement(weight)
    summary.insert(value, amplifiedWeight)
  }

  /**
   * Queries a batch of quantiles against the current EWMA-weighted distribution. See
   * [[QuantileSummaries.query]] for the input and output format.
   */
  @throws[IllegalArgumentException]("if any quantile is outside [0, 1]")
  def query(quantiles: Seq[Double]): Vector[(T, Double)] = summary.query(quantiles)
}
