package com.databricks.dicer.client

import scala.concurrent.duration._
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.ThreadSafe

import com.databricks.caching.util.{
  CachingErrorCode,
  EwmaCounter,
  EwmaQuantileSummaries,
  LossyEwmaCounter,
  PrefixLogger,
  Severity,
  TypedClock
}
import com.databricks.dicer.client.SliceletLoadAccumulator.{
  EWMA_HALF_LIFE_SECONDS,
  SliceAccumulator,
  TOP_KEYS_ERROR,
  TOP_KEYS_MINIMUM_LOAD_FRACTION,
  TOP_KEYS_SUPPORT
}
import com.databricks.dicer.common.{Assignment, Generation, SliceAssignment, SliceletData}
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.dicer.common.SliceletData.{KeyLoad, LoadDistribution}
import com.databricks.dicer.common.SliceletData.LoadDistribution.CdfPoint
import com.databricks.caching.util.Lock.withLock
import com.databricks.dicer.common.SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR

/**
 * Accumulates load observed by a Slicelet.
 *
 *  - Granularity: load is tracked per assigned Slice. When the assignment changes, load data
 *    for unassigned Slices is discarded, as our assumption is that the Dicer Assigner will wait for
 *    the assignment (and load measurements) to stabilize before reassigning Slices again.
 *
 *  - A single "primary rate" load measurement is currently supported. This is an [[EwmaCounter]]
 *    that is incremented by 1 every time the Slicelet returns a handle for a Slice key.
 *
 *    TODO(<internal bug>) support other load measurements by expanding `SliceAccumulator`.
 *  - For debugging purposes, aggregate load data for all unassigned Slices is tracked, a
 *    measurement that may eventually be used in a heuristic to disable load balancing in Dicer when
 *    a significant fraction of the load is discarded. We intentionally do _not_ track unassigned
 *    load at the Slice level, as doing so could result in excessive network, memory, and CPU costs
 *    when a particular service is misconfigured (or Dicer is struggling) such that there is a large
 *    number of misdirected requests.
 *  - We intentionally do not attempt to perform load apportioning when assignments change. For
 *    example, if a Slicelet was previously assigned [0, 10) and [20, 30), and subsequently is
 *    assigned [5, 25), we do not attempt to carry forward some fraction of the load from [0, 10)
 *    and [20, 30) to the new range, or guess at the probable load for [10, 20) which was previously
 *    unassigned. We instead rely on the Dicer Assigner waiting until it has stable load information
 *    before reassigning Slices.
 *
 * @param cdfPointBudget Maximum number of CDF points reported across all assigned Slices per load
 *                       report. The budget is split proportionally to primary EWMA load: Slices
 *                       with more load get more points. The higher load a Slice has, the higher
 *                       chance the Slice will be split by the Assigner and the CDF points will be
 *                       more useful for the Assigner to make split and load balancing decisions. A
 *                       zero budget disables per-Slice load-distribution recording and reporting.
 *                       Must be non-negative.
 * @param maxKeyLengthBytes The maximum length of SliceKeys that the SliceletLoadAccumulator will
 *                          include in the load report. SliceKeys longer than this are dropped from
 *                          the top keys and load distribution, but still count toward total load.
 *                          Must be positive.
 *
 * @throws IllegalArgumentException if `cdfPointBudget` is negative.
 * @throws IllegalArgumentException if `maxKeyLengthBytes` is not positive.
 */
@ThreadSafe
private[dicer] class SliceletLoadAccumulator @throws[IllegalArgumentException]()(
    clock: TypedClock,
    target: Target,
    metrics: SliceletMetrics,
    cdfPointBudget: Int,
    maxKeyLengthBytes: Int) {

  import SliceletLoadAccumulator.AssignmentAccumulator

  // Rust enforces this with the unsigned `usize` type.
  require(cdfPointBudget >= 0, "cdf_point_budget must be non-negative")
  require(maxKeyLengthBytes > 0, "max_key_length_bytes must be positive")

  /* This class achieves thread-safety using a non-standard approach that is not generally
   * recommended in Caching team code. Please see <internal link> for the standard
   * approach.
   *
   * What is the strategy?
   *
   * The accumulator maintains a shallowly immutable map from Slices to [[SliceAccumulator]]s. When
   * the assignment changes, the accumulator creates a new map and atomically swaps it in. Values in
   * the map are mutable and thread-safe, protected by their own locks, and can be safely mutated by
   * multiple threads. Load for different assigned Slices may also be concurrently modified because
   * of the fine-grained locks.
   *
   * Why use a different strategy?
   *
   * Because load reporting is on the critical path for request processing in Dicer-sharded services
   * it is important to minimize the overhead of load reporting. It would be too expensive to
   * enqueue a command on a `SequentialExecutionContext` for every incoming request, though this
   * would be the conventional approach for caching code.
   *
   * Another strategy is to use a ConcurrentHashMap, which would be ineffective in this case as we
   * need a map from Slices (ranges) to accumulators, not from individual keys to accumulators. In
   * addition, ConcurrentHashMap is not generally allowed in Caching code.
   */

  /**
   * Reference to the latest assignment in which load is being accumulated. The accumulator is
   * atomically swapped in [[onAssignmentChanged()]].
   */
  private val assignmentAccumulatorRef = new AtomicReference(AssignmentAccumulator.EMPTY)

  /**
   * Accumulator for all unattributed load, i.e., load for keys that are not assigned to the current
   * Slicelet.
   */
  private val unattributedAccumulator: SliceAccumulator = createUnattributedAccumulator()

  /**
   * Increments the primary rate load metric and the corresponding Prometheus metrics for `key`.
   * `value` should be positive: negative or zero values are ignored.
   */
  def incrementPrimaryRateBy(key: SliceKey, value: Int): Unit = {
    metrics.onLoadIncrement()
    if (value <= 0) {
      // Ignore zero or negative values.
      return
    }
    // Access the accumulator SliceMap for the current assignment.
    val map: SliceMap[GapEntry[SliceAccumulator]] = assignmentAccumulatorRef.get().map
    map.lookUp(key) match {
      case GapEntry.Some(sliceAccumulator: SliceAccumulator) =>
        // The key is assigned to the current Slicelet. Increment the primary rate load metric for
        // the assigned Slice, and also export to the Prometheus metric for attributed load.
        sliceAccumulator.incrementBy(clock.instant(), key, value)
        metrics.incrementAttributedLoadBy(value)
      case GapEntry.Gap(_) =>
        // The key is not assigned to the current Slicelet. Increment the unattributed load metric
        // for the Slicelet, and also export to the Prometheus metric for unattributed load.
        unattributedAccumulator.incrementBy(clock.instant(), key, value)
        metrics.incrementUnattributedLoadBy(value)
    }
  }

  /**
   * Called when the assignment for this Slicelet, as identified by `squid`, changes. Changes the
   * per-Slice accumulators tracked internally by this accumulator. Does nothing if `assignment` has
   * a generation that is not higher than the generation of the currently tracked assignment.
   */
  final def onAssignmentChanged(squid: Squid, assignment: Assignment): Unit = {
    while (true) {
      // Get the current map. We will create a new map and atomically swap it in. The swap will fail
      // if some other thread concurrently swaps the map, in which case we will retry until either
      // we succeed in swapping the assignment, or the tracked assignment has a generation at least
      // as high as `assignment`'s generation.
      val previousAssignmentAccumulator: AssignmentAccumulator = assignmentAccumulatorRef.get()
      if (previousAssignmentAccumulator.generation >= assignment.generation) {
        // The assignment has not advanced since the last time we updated the accumulator.
        return
      }
      val nextAssignmentAccumulator: AssignmentAccumulator =
        previousAssignmentAccumulator.createNextAccumulator(
          clock,
          squid,
          assignment,
          createQuantileSummaries = cdfPointBudget > 0,
          maxKeyLengthBytes,
          target
        )
      if (assignmentAccumulatorRef.compareAndSet(
          previousAssignmentAccumulator,
          nextAssignmentAccumulator
        )) {
        // Successfully swapped in the new accumulator.
        return
      }
    }
  }

  /**
   * Returns a snapshot of all load attributed to assigned Slices and all unattributed load for the
   * current Slicelet.
   */
  def readLoad(): (Vector[SliceletData.SliceLoad], SliceletData.SliceLoad) = {
    val now: Instant = clock.instant()

    // Access the accumulator SliceMap for the current assignment.
    val map: SliceMap[GapEntry[SliceAccumulator]] = assignmentAccumulatorRef.get().map

    // Calculate the total attributed load and collect per-slice load used for CDF budget
    // apportioning. At the same time, collect the SliceAccumulators of the assigned Slices to avoid
    // applying the filter one more time when generating the load report.
    //
    // Note that other concurrent `incrementPrimaryRateBy` calls may change the total load before
    // reporting; this is acceptable because budgets are approximate anyway and EWMA smoothing
    // should keep the differences small.
    var totalAttributedLoad: Double = 0.0
    val measurements = Vector.newBuilder[(Double, SliceAccumulator)]
    for (sliceAccumulatorOrGap: GapEntry[SliceAccumulator] <- map.entries) {
      // Skip unassigned ranges.
      if (sliceAccumulatorOrGap.isDefined) {
        val sliceAccumulator: SliceAccumulator = sliceAccumulatorOrGap.get
        val load: Double = sliceAccumulator.primaryRateLoad(now)
        totalAttributedLoad += load
        measurements += ((load, sliceAccumulator))
      }
    }

    // Sort the measurements by load, so that when we are assigning budgets to the Slices with
    // relatively low load, we will prioritize the higher-loaded slices. See how we calculate
    // `sliceCdfBudget` for slices below.
    val sortedMeasurements: Seq[(Double, SliceAccumulator)] = measurements.result().sortWith {
      (left: (Double, SliceAccumulator), right: (Double, SliceAccumulator)) =>
        val (leftLoad, _): (Double, SliceAccumulator) = left
        val (rightLoad, _): (Double, SliceAccumulator) = right
        leftLoad > rightLoad
    }

    // Now calculate the attributed SliceLoads from the measurements.
    var remainingBudget: Int = cdfPointBudget
    val unorderedAttributedLoads: Seq[SliceletData.SliceLoad] = sortedMeasurements.map {
      measurement: (Double, SliceAccumulator) =>
        val (primaryRateLoad, sliceAccumulator): (Double, SliceAccumulator) = measurement
        // Split the total available load distribution budget across the assigned Slices,
        // proportionally to the primary EWMA load for each Slice. Each share is rounded down first
        // so as not to exceed the total budget.
        val roundedDownBudget: Int = if (totalAttributedLoad > 0.0) {
          val loadFraction: Double = primaryRateLoad / totalAttributedLoad
          (loadFraction * cdfPointBudget).toInt
        } else {
          0
        }
        // As a small optimization to make full use of the budget, if the rounded-down budget for
        // the Slice is 0 and there is total budget remaining, we assign 1 budget to the slice
        // instead of 0. Since the measurements are sorted, higher-loaded Slices are more likely to
        // get this round-up.
        val sliceCdfBudget: Int = if (roundedDownBudget == 0 && remainingBudget > 0) {
          1
        } else {
          roundedDownBudget
        }
        remainingBudget -= sliceCdfBudget
        sliceAccumulator.toSliceletDataSliceLoad(now, sliceCdfBudget)
    }

    // Restore Slice order for the report, just for the purpose of easier debugging.
    val attributedLoads: Vector[SliceletData.SliceLoad] = unorderedAttributedLoads.sortWith {
      (left: SliceletData.SliceLoad, right: SliceletData.SliceLoad) =>
        left.slice.lowInclusive.compare(right.slice.lowInclusive) < 0
    }.toVector

    // Take a snapshot of the unattributed load. Unattributed load tracks no load distribution, so
    // the CDF-point budget is not used.
    val unattributedLoad: SliceletData.SliceLoad =
      unattributedAccumulator.toSliceletDataSliceLoad(now, cdfPointBudget = 0)

    // Calculate threshold and filter out keys that do not meet the threshold.
    val totalLoad: Double = unattributedLoad.primaryRateLoad + totalAttributedLoad
    val threshold: Double = totalLoad * TOP_KEYS_MINIMUM_LOAD_FRACTION
    // Filter to only keep the top keys that are above the threshold.
    def filterKeysAboveThreshold(sliceLoad: SliceletData.SliceLoad): SliceletData.SliceLoad = {
      val newTopKeys = sliceLoad.topKeys.filter { keyLoad: KeyLoad =>
        keyLoad.underestimatedPrimaryRateLoad >= threshold
      }
      sliceLoad.copy(topKeys = newTopKeys)
    }
    (attributedLoads.map(filterKeysAboveThreshold), filterKeysAboveThreshold(unattributedLoad))
  }

  /**
   * Creates a new [[SliceAccumulator]] for unattributed load, where [[EwmaCounter]] and
   * [[LossyEwmaCounter]] have the default configuration parameters in [[SliceletLoadAccumulator]].
   */
  private def createUnattributedAccumulator(): SliceAccumulator = {
    val startTime = clock.instant()
    val primaryRateCounter = new EwmaCounter(startTime, EWMA_HALF_LIFE_SECONDS)
    val topKeysCounter = new LossyEwmaCounter[SliceKey](
      LossyEwmaCounter.Config(
        startTime,
        TOP_KEYS_SUPPORT,
        TOP_KEYS_ERROR,
        EWMA_HALF_LIFE_SECONDS
      )
    )
    new SliceAccumulator(
      // Use full slice for unattributed load accumulator so that any possible top keys will be
      // covered by it.
      slice = Slice.FULL,
      primaryRateCounter,
      topKeysCounter,
      // We report no CDF for disjoint unassigned ranges.
      loadQuantileSummariesOpt = None,
      // numReplicas is not used by unattributed load accumulator. Just set it to 1.
      numReplicas = 1,
      maxKeyLengthBytes,
      target
    )
  }
}

private[client] object SliceletLoadAccumulator {

  // Configuration for the rate load measurements follows. All these parameters are somewhat
  // arbitrarily chosen.

  /**
   * The interval over which the weight of a measurement decays by half. Applies to the
   * [[EwmaCounter]] for primary rate load, [[LossyEwmaCounter]] for top keys, and
   * [[EwmaQuantileSummaries]] for load distribution.
   */
  private val EWMA_HALF_LIFE_SECONDS: Int = 120

  /**
   * A key's estimated fraction of the Slice load must exceed this value to be considered a top key.
   * See [[LossyEwmaCounter]] for more details on the meaning of "support". Also see
   * [[TOP_KEYS_MINIMUM_LOAD_FRACTION]] for an additional condition to be a top key.
   */
  private val TOP_KEYS_SUPPORT: Double = 0.05

  /**
   * The maximum allowed error in contribution for a top key. See [[LossyEwmaCounter]] for more
   * details.
   */
  private val TOP_KEYS_ERROR: Double = 0.005

  /**
   * The minimum estimated fraction of the total Slicelet load that a key must be responsible for
   * in order to be considered a top key. Thus a key must both exceed [[TOP_KEYS_SUPPORT]] within
   * the Slice, ''and'' exceed [[TOP_KEYS_MINIMUM_LOAD_FRACTION]] on the overall Slicelet, to be
   * considered a top key. Note that this means the number of top keys the Slicelet will send is
   * bounded by the reciprocal of this value.
   */
  private[client] val TOP_KEYS_MINIMUM_LOAD_FRACTION: Double = 0.01

  /** Target quantile rank error as a fraction of load in the summary. */
  private val LOAD_DISTRIBUTION_RELATIVE_ERROR: Double = 0.01

  /** Retained samples per Slice, allowing the summary's recommended 10 / error headroom. */
  private val LOAD_DISTRIBUTION_SAMPLE_COUNT_UPPER_BOUND: Int = 1000

  /**
   * Returns the Slice for a [[SliceAccumulator]]. Needed to a create a [[SliceMap]] containing
   * accumulators.
   */
  private val SLICE_ACCUMULATOR_ACCESSOR: SliceAccumulator => Slice = accumulator =>
    accumulator.slice

  /**
   * PRECONDITION: `numReplicas` > 0.
   *
   * Accumulates load and tracks the current number of replicas (see
   * [[AssignmentAccumulator.createNextAccumulator]] for the usage) for the given `slice`.
   *
   * @param loadQuantileSummariesOpt The time-decayed summary of load across keys in this Slice,
   *                                 from which the reported load distribution (CDF) is queried.
   *                                 TODO(<internal bug>): Remove the Option wrapper once quantile
   *                                                summaries is enabled for every target.
   * @param maxKeyLengthBytes The maximum key length in bytes that the SliceAccumulator will use to
   *                          report fine-grained load information for load balancing, including
   *                          top keys and CDF points. SliceKeys longer than this value are omitted
   *                          from this information.
   * @param target The target used to generate debugging information in the logs.
   */
  @ThreadSafe
  private class SliceAccumulator(
      val slice: Slice,
      primaryRateCounter: EwmaCounter,
      topKeysCounter: LossyEwmaCounter[SliceKey],
      loadQuantileSummariesOpt: Option[EwmaQuantileSummaries[SliceKey]],
      val numReplicas: Int,
      maxKeyLengthBytes: Int,
      target: Target) {
    iassert(numReplicas > 0, "Number of replicas must be positive.")

    /**
     * Lock protecting mutable state. See the comment in [[SliceletLoadAccumulator]] for why we are
     * using fine-grained locks.
     */
    private val lock = new ReentrantLock()

    /** Logger for alert metrics and structured log output. */
    private val logger: PrefixLogger = PrefixLogger.create(getClass, target.toString)

    /** Returns the current primary rate load of this Slice. */
    def primaryRateLoad(now: Instant): Double = withLock(lock) {
      primaryRateCounter.value(now).weightedValue
    }

    /**
     * Converts the currently accumulated load data into a [[SliceletData.SliceLoad]] object, with a
     * load distribution of at most `cdfPointBudget` points.
     */
    def toSliceletDataSliceLoad(now: Instant, cdfPointBudget: Int): SliceletData.SliceLoad =
      withLock(lock) {
        val value: EwmaCounter.Value = primaryRateCounter.value(now)
        val topKeys: Seq[SliceletData.KeyLoad] = {
          if (value.weightedValue == 0) {
            // If there is no load, don't report any top keys. `EwmaCounter` requires the clock to
            // advance for it to incorporate new load, and if the current load is 0 there's no
            // point checking the top keys in `topKeysCounter`. While there wouldn't be any real
            // harm in reporting keys with load 0, it is helpful to suppress it for tests.
            Seq.empty
          } else {
            val topKeysMap: Map[SliceKey, Double] = topKeysCounter.getHotKeys()
            topKeysMap.map {
              case (key: SliceKey, contribution: Double) =>
                val underestimatedPrimaryRateLoad: Double = contribution * value.weightedValue
                SliceletData.KeyLoad(key, underestimatedPrimaryRateLoad)
            }.toSeq
          }
        }

        val loadDistributionOpt: Option[LoadDistribution] = loadQuantileSummariesOpt.flatMap {
          summary: EwmaQuantileSummaries[SliceKey] =>
            buildLoadDistribution(summary, cdfPointBudget)
        }

        SliceletData.SliceLoad(
          primaryRateLoad = value.weightedValue,
          value.windowLowInclusive,
          value.windowHighExclusive,
          slice,
          topKeys,
          numReplicas = numReplicas,
          loadDistributionOpt
        )
      }

    /**
     * Increments total load, top-key counters, and the optional load distribution summary for the
     * given `key` by `value`. Keys longer than `maxKeyLengthBytes` contribute only to the total
     * load, not to the fine-grained load information, and fire an alert.
     */
    def incrementBy(now: Instant, key: SliceKey, value: Int): Unit = withLock(lock) {
      // Note that the load from the key is included in the per-slice total load even if the key
      // is longer than the limit. This won't cause the long to flow to the Assigner. Excluding the
      // long key from the EwmaQuantileSummaries can result in a less accurate load histogram, but
      // that should still be better than reporting no load histogram at all typically.
      primaryRateCounter.incrementBy(now, value)
      val keyLengthBytes: Int = key.bytes.size()
      if (keyLengthBytes > maxKeyLengthBytes) {
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.SLICELET_LOAD_ACCUMULATOR_KEY_TOO_LONG,
          s"Omitting a $keyLengthBytes-byte key from top keys and load distribution: exceeds " +
          s"max_key_length_bytes $maxKeyLengthBytes",
          every = 30.seconds
        )
      } else {
        for (summary: EwmaQuantileSummaries[SliceKey] <- loadQuantileSummariesOpt) {
          summary.insert(now, key, value)
        }
        topKeysCounter.incrementBy(now, key, value)
      }
    }

    /**
     * PRECONDITION: [[lock]] must be held by the current thread.
     *
     * Samples the load quantile summaries with at most `cdfPointBudget` quantiles and assembles a
     * [[LoadDistribution]]. Returns `None` when the budget is zero or the summary is empty.
     */
    private def buildLoadDistribution(
        loadQuantileSummaries: EwmaQuantileSummaries[SliceKey],
        cdfPointBudget: Int): Option[LoadDistribution] = {
      iassert(lock.isHeldByCurrentThread, "lock must be held")
      if (cdfPointBudget == 0) {
        return None
      }

      val quantiles: Vector[Double] = SliceAccumulator.selectCdfQuantiles(cdfPointBudget)
      val sampledCdfPoints: Vector[(SliceKey, Double)] = loadQuantileSummaries.query(quantiles)
      if (sampledCdfPoints.isEmpty) {
        return None
      }
      // A key holding a large load fraction may absorb several consecutive quantile queries. We
      // deduplicate by key since duplicate keys carry the same cumulative fraction.
      val distinctPoints = Vector.newBuilder[CdfPoint]
      var previousKeyOpt: Option[SliceKey] = None
      for (sampledCdfPoint: (SliceKey, Double) <- sampledCdfPoints) {
        val (key, cumulativeLoadFraction): (SliceKey, Double) = sampledCdfPoint
        if (!previousKeyOpt.contains(key)) {
          distinctPoints += CdfPoint(key, cumulativeLoadFraction)
          previousKeyOpt = Some(key)
        }
      }

      // The summary's lower bounds have at most twice the configured rank error unless forced
      // compression degrades accuracy, in which case the summary emits an alert.
      Some(LoadDistribution(distinctPoints.result(), 2.0 * LOAD_DISTRIBUTION_RELATIVE_ERROR))
    }
  }

  private object SliceAccumulator {

    /**
     * Chooses no more than `budget` many quantiles to sample for the load-distribution CDF. The
     * returned quantiles are in ascending order.
     *
     * We select quantiles in dyadic levels: `1/2`; then `1/4, 3/4`; then `1/8, 3/8, 5/8, 7/8`; and
     * so on. By doing this, we use the budget to provide the most important information about the
     * load distribution first, and then use the budget to provide less important and more
     * fine-grained details of the distribution.
     *
     * We choose layers of quantiles until when including the next dyadic layer would exceed the
     * budget. For example, for budget == 10, we will choose 7 quantiles in total since the next
     * dyadic layer (8 more quantiles) would exceed the budget.
     */
    private def selectCdfQuantiles(budget: Int): Vector[Double] = {
      // Rounded-down budget is always the closest (2^m - 1) value no greater than `budget`.
      // For example, for budget == 10, we have
      // budget:            0b01010
      // budget + 1:        0b01011
      // highestOneBit:     0b01000
      // roundedDownBudget: 0b00111 (7)
      // We do `budget + 1` because when budget is exactly `2^m - 1`, we want to fully use it.
      val roundedDownBudget: Int = Integer.highestOneBit(budget + 1) - 1
      val denominator: Double = roundedDownBudget.toDouble + 1.0
      (1 to roundedDownBudget).map { numerator: Int =>
        numerator.toDouble / denominator
      }.toVector
    }
  }

  /**
   * Accumulates load for a specific assignment generation.
   *
   * @param map A map from Slice to SliceAccumulator. The [[SliceAccumulator]] instances are mutable
   *            but thread-safe. The map itself is also thread-safe, as it is shallowly immutable.
   * @param generation The generation of the assignment for which load is being accumulated.
   */
  @ThreadSafe
  private class AssignmentAccumulator(
      val map: SliceMap[GapEntry[SliceAccumulator]],
      val generation: Generation) {

    /**
     * PRECONDITION: `assignment.generation > generation`
     *
     * Creates a new accumulator that
     *
     * - For Slices which remain assigned and have the same number of replicas, carries forward
     *   their previous [[SliceAccumulator]] instances.
     *
     * - For Slices which remain assigned but have a different number of replicas, reseeds their
     *   [[SliceAccumulator]]s with their predicted load (for a single Slice replica), calculated as
     *   the total predicted load for the Slice divided by the number of replicas.
     *
     * - For Slices which are newly assigned, creates new [[SliceAccumulator]]s with their predicted
     *   load (for a single Slice replica), calculated as the total predicted load for the Slice
     *   divided by the number of replicas. Note that if there are overlapping Slices that do not
     *   have the same boundaries, we do not attempt to apportion previous load, we just discard
     *   them.
     *
     * - For Slices which have become unassigned, discards their [[SliceAccumulator]]s.
     *
     * `createQuantileSummaries` controls whether the new SliceAccumulators contain the fine-grained
     * load quantile summaries tracker. `maxKeyLengthBytes` and `target` are passed down to the
     * created SliceAccumulator. See [[SliceAccumulator]] for their meanings.
     *
     * Here's an example of how this works, where
     *   L means the predicted Load for this Slice replica, seeded into the [[SliceAccumulator]];
     *   T means the Total estimated load on the Slice (from all replicas) in the assignment;
     *   R means the number of Replicas for the Slice.
     *
     * {{{
     *  this.map: [""..20 -> L=10,R=3)   ...       [40..60 -> L=50,R=3)... [70..90 -> L=100,R=3) ...
     *  assignment:    ...   [10..30 -> T=60,R=3)..[40..60 -> T=80,R=2)... [70..90 -> T=600,R=3) ...
     *  result:        ...   [10..30 -> L=20,R=3)..[40..60 -> L=40,R=2)... [70..90 -> L=100,R=3) ...
     *  explanation:         (Reseeded: L=T/R)     (Reseeded: L=T/R)       (Carried forward)
     * }}}
     *
     * Notice that a new [[SliceAccumulator]] is created for [10, 30) despite the existing
     * overlapping accumulators. Only the existing [[SliceAccumulator]] for [70, 90) is carried
     * forward, as the Slice boundaries and number of replicas match. Also, although [40, 60)
     * remains assigned with same boundaries, its replicas have changed, and so a new
     * SliceAccumulated is created with a Load seed equal to L=T/R.
     *
     * The implementation needs to ensure that every Slice assigned to `squid` in `assignment` has
     * exactly one [[SliceAccumulator]] associated with it in the result.
     */
    def createNextAccumulator(
        clock: TypedClock,
        squid: Squid,
        assignment: Assignment,
        // TODO(<internal bug>): Remove this parameter when enabling quantile summaries everywhere.
        createQuantileSummaries: Boolean,
        maxKeyLengthBytes: Int,
        target: Target): AssignmentAccumulator = {
      iassert(assignment.generation > generation)

      // Create a map containing the Slices assigned to the Slicelet, along with the predicted load
      // for the slice replica and number of replicas for those Slices, and gaps for unassigned
      // ranges.
      val assignedSlicesMap: SliceMap[GapEntry[SliceAssignment]] =
        SliceMap.createFromOrderedDisjointEntries(
          assignment.getAssignedSliceAssignments(squid),
          getSlice = SLICE_ASSIGNMENT_ACCESSOR
        )
      // Gather per-Slice accumulators for assigned Slices.
      val accumulators = Vector.newBuilder[SliceAccumulator]

      // Zip together the previous map (from this SliceAccumulator) and the assigned Slices map
      // (which will be used to create the next SliceAccumulator). In the example above, this would
      // yield the following intersections:
      //  Slice    -> (accumulator or gap,      assigned slice assignment or gap)
      //  ["", 10) -> (["", 20 -> L=10,R=3),    ["", 10 -> unassigned))
      //  [10, 20) -> (["", 20 -> L=10,R=3),    [10, 30 -> T=60,R=3))
      //  [20, 30) -> ([20, 40 -> unassigned),  [10, 30 -> T=60,R=3))
      //  [30, 40) -> ([20, 40 -> unassigned),  [30, 40 -> unassigned))
      //  [40, 60) -> ([40, 60 -> L=50,R=3),    [40, 60 -> T=80,R=2))
      //  [60, 70) -> ([60, 70 -> unassigned),  [60, 70 -> unassigned))
      //  [70, 90) -> ([70, 90 -> L=100,R=3),   [70, 90 -> T=600,R=3))
      //  [90, ∞)  -> ([90, ∞  -> unassigned),  [90, ∞ -> unassigned))
      for (intersected: SliceMap.IntersectionEntry[
          GapEntry[SliceAccumulator],
          GapEntry[SliceAssignment]] <- SliceMap
          .intersectSlices(this.map, assignedSlicesMap)
          .entries) {
        val previousAccumulatorOrGap: GapEntry[SliceAccumulator] = intersected.leftEntry
        val nextSliceAssignmentOrGap: GapEntry[SliceAssignment] = intersected.rightEntry
        if (nextSliceAssignmentOrGap.isDefined) {
          // Only process assigned Slices (e.g., [10, 30) in the example above), not gaps (e.g.,
          // [60, 70) in the example above).
          val nextSliceAssignment: SliceAssignment = nextSliceAssignmentOrGap.get
          val nextSlice: Slice = nextSliceAssignment.slice
          val nextNumReplicas: Int = nextSliceAssignment.resourcesSet.size
          if (previousAccumulatorOrGap.isDefined &&
            previousAccumulatorOrGap.get.slice == nextSlice &&
            previousAccumulatorOrGap.get.numReplicas == nextNumReplicas) {
            // Carry forward the existing accumulator for the Slice if the Slice boundaries and the
            // number of replicas have not changed, as with [70, 90) in the example above.
            accumulators += previousAccumulatorOrGap.get
          } else if (nextSlice.highExclusive == intersected.slice.highExclusive) {
            // Create a new accumulator for the assigned Slice, but only the last time we encounter
            // the newly assigned Slice. Notice that in the example above, the [10, 30) Slice is
            // encountered two times (in the [10, 20) and [20, 30) intersections), but we want to
            // create only a single accumulator for it, which we do for the [20, 30) intersection.
            //
            // For simplicity we do not attempt to carry forward any top keys. Note that since
            // `LossyEwmaCounter` reports fractions rather than absolute load, we could end up
            // reporting an overly high load for the top keys initially. This should be OK as the
            // assigner waits for a load report to be of sufficient duration before it starts using
            // it.
            val startTime = clock.instant()
            // Calculate the (optional) predicted load for the Slice replica by dividing the
            // predicted load for the total slice with the number of replicas, and use the predicted
            // load for the Slice replica as a seed for the new EwmaCounter so that we preserve any
            // historical load information.
            val predictedSliceReplicaLoadOpt: Option[Double] =
              nextSliceAssignment.primaryRateLoadOpt.map((_: Double) / nextNumReplicas)
            val primaryRateCounter =
              new EwmaCounter(
                startTime,
                EWMA_HALF_LIFE_SECONDS,
                seedOpt = predictedSliceReplicaLoadOpt
              )
            val topKeysCounter = new LossyEwmaCounter[SliceKey](
              LossyEwmaCounter.Config(
                startTime,
                TOP_KEYS_SUPPORT,
                TOP_KEYS_ERROR,
                EWMA_HALF_LIFE_SECONDS
              )
            )
            // Avoid tracking distributions when reporting is disabled.
            val loadQuantileSummariesOpt: Option[EwmaQuantileSummaries[SliceKey]] =
              if (createQuantileSummaries) {
                Some(
                  new EwmaQuantileSummaries[SliceKey](
                    startTime,
                    EWMA_HALF_LIFE_SECONDS,
                    LOAD_DISTRIBUTION_RELATIVE_ERROR,
                    LOAD_DISTRIBUTION_SAMPLE_COUNT_UPPER_BOUND
                  )
                )
              } else {
                None
              }
            accumulators += new SliceAccumulator(
              nextSlice,
              primaryRateCounter,
              topKeysCounter,
              loadQuantileSummariesOpt,
              nextNumReplicas,
              maxKeyLengthBytes,
              target
            )
          }
        }
      }
      val nextMap: SliceMap[GapEntry[SliceAccumulator]] = SliceMap.createFromOrderedDisjointEntries(
        accumulators.result(),
        SLICE_ACCUMULATOR_ACCESSOR
      )
      new AssignmentAccumulator(nextMap, assignment.generation)
    }
  }

  private object AssignmentAccumulator {
    val EMPTY: AssignmentAccumulator = new AssignmentAccumulator(
      SliceMap.createFromOrderedDisjointEntries(Vector.empty, SLICE_ACCUMULATOR_ACCESSOR),
      Generation.EMPTY
    )
  }
}
