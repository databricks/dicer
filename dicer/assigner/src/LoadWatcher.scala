package com.databricks.dicer.assigner

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.CachingErrorCode.{
  INCONGRUENT_LOAD_DISTRIBUTION,
  TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD
}
import com.databricks.caching.util.{
  IntrusiveMinHeap,
  IntrusiveMinHeapElement,
  PrefixLogger,
  Severity,
  TickerTime
}
import com.databricks.dicer.assigner.config.InternalTargetConfig.LoadWatcherTargetConfig
import com.databricks.dicer.assigner.algorithm.LoadMap
import com.databricks.dicer.assigner.algorithm.LoadMap.KeyLoadMap
import com.databricks.dicer.assigner.LoadWatcher.{
  Measurement,
  MeasurementElement,
  SliceLoadWatcher,
  StaticConfig
}
import com.databricks.dicer.assigner.conf.LoadWatcherConf
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.SliceletData.{KeyLoad, LoadDistribution}
import com.databricks.dicer.common.{Assignment, LoadMeasurement, SliceAssignment}
import com.databricks.dicer.external.{InfinitySliceKey, ResourceAddress, Slice, SliceKey}

/**
 * This abstraction tracks the load reported by Slicelets for a particular target. Load on
 * individual Slices (Slice replicas, to be precise) is reported to the watcher via
 * [[reportLoad]], and an aggregate view of the current load is computed and returned via
 * [[getPrimaryRateLoadMap]].
 *
 * Load report and aggregation:
 *
 * The LoadWatcher tracks the latest load report from each resource for each Slice. The reported
 * load from a specific resource on a given slice replica is recorded as a [[Measurement]].
 *
 * Given a Measurement of load on a slice replica, we will first compute an extrapolated estimation
 * of the total load on the whole slice based on this Measurement by taking the product of replica
 * load and the number of replicas, then do this for all replicas we have measurements for (possibly
 * a subset in certain cases), and take a weighted average where weights are based on the age of the
 * load report. In more detail, we compute total load on the slice in the following manner:
 *
 * Assuming we have N tracked Measurements indexed by i for a given Slice, to calculate the
 * aggregated load for each Slice, we take the following information in the load report for each
 * Slice replica:
 *
 * Li: The actual Load value accumulated by the Slicelet during the report's time window (see
 *     [[Measurement.load]]);
 * Ri: The number of Replicas for the Slice known by the Slicelet when the load report was generated
 *     (see [[Measurement.numReplicas]]);
 * Ti: The Time when the report was generated on the Slicelet (see [[Measurement.time]]).
 *
 * Then, calculate the aggregated load as:
 *   Wi = 0.5 ^ ((now - Ti) / HalfLife)
 * See [[LOAD_WEIGHT_DECAYING_HALFLIFE]] for HalfLife information.
 *
 * Assuming there are N load reports tracked for a Slice, the aggregated load for the Slice is
 * calculated as
 *   TotalLoad(Slice) = sum(i=1..N, Wi * Li * Ri) / sum(i=1..N, Wi).
 *
 * See <internal link> for more information.
 *
 * Top keys handling:
 *
 * The loads of top keys from [[Measurement]]s (i.e. [[Measurement.topKeys]]) for each Slice are
 * aggregated using the same strategy as Slices, and the top keys will form single-key Slices in
 * the [[LoadMap]] returned by [[getPrimaryRateLoadMap]]. If a top key is missing from some of the
 * [[Measurement]]s for a Slice (but presented in others), we estimate the load for of key in the
 * Measurements that are missing it to be 0. While we have various ways to make the estimation, we
 * prefer this conservative approach that doesn't risk underestimating the load of the rest of
 * the Slice. Note that this approach is also roughly equivalent to taking all the top keys
 * k1, k2, ... over all replica load measurements for a given slice([start, end)) and first
 * apportioning load onto the ranges [start, k1), [k1, k1\0), [k1\0, k2), ..., [kN\0, end) for all
 * measurements and then aggregating them in the way described above (because if e.g. k1 is not a
 * top key in a measurement, then the apportioned load on [k1, k1\0) would be ~0).
 *
 * Garbage collection:
 *
 * The Measurements that are expired (i.e. [[Measurement.time]] < now - [[config.maxAge]]) will
 * will be cleaned up by the load watcher. In addition, we will also bound the number of
 * Measurements for each Slice by the maximum known number of replicas (as reported to us in the
 * Measurements for a Slice) - The oldest Measurements for each Slice will be cleaned, until the
 * number of tracked Measurements is no more than the max number of replicas reported by the
 * remaining tracked Measurements themselves. This is a heuristic approach to keep the most
 * measurement information as possible while not blowing the memory (as the number of reports will
 * be bounded by the max number of replicas that the Slice had in recent assignments.) It also has
 * the nice property that if the number of replicas for a Slice in the most recent assignment drops
 * from a high number to a low number (e.g. from 10 down to 1), we don't immediately garbage collect
 * all but the most recent load report. Instead the LoadWatcher will gracefully decay the old
 * measurements out while we receive up to date information from the new smaller set of replicas.
 *
 * The GC will be done passively when any public API of LoadWatcher is called.
 *
 * Not thread-safe. In practice, the load watcher will be called exclusively from the execution
 * context guarding all target state.
 */
private[assigner] class LoadWatcher(config: LoadWatcherTargetConfig, staticConfig: StaticConfig) {

  /*
   * Implementation note: the LoadWatcher uses a 2-layer structure to keep track of the latest load
   * reported by Slicelets: the LoadWatcher is tracking the SliceLoadWatcher instances, and the
   * SliceLoadWatcher is tracking the Measurement instances for each Slice.
   *
   * The LoadWatcher tracks the SliceLoadWatchers that contain un-expired Measurements in the
   * `sliceLoadWatchersBySlice` map and the `sliceLoadWatchersByAge` heap. The map is used to
   * quickly find the SliceLoadWatcher for any Slice, and the heap is used to quickly find the
   * SliceLoadWatchers containing expired Measurements. The map and heap will be kept in sync.
   */

  /**
   * Map tracking the [[SliceLoadWatcher]]s for each Slice.
   *
   * Invariant:
   *  - Any SliceLoadWatcher tracked in `sliceLoadWatchersBySlice` is also tracked in
   *    `sliceLoadWatchersByAge`.
   */
  private val sliceLoadWatchersBySlice = mutable.Map[Slice, SliceLoadWatcher]()

  /**
   * Heap tracking the [[SliceLoadWatcher]]s to quickly find the empty SliceLoadWatchers or the ones
   * containing expired Measurements. The heap's priority is the earliest time of Measurements
   * tracked in a SliceLoadWatcher, or TickerTime.Min if the SliceLoadWatcher is empty.
   *
   * Invariant:
   * - Any MeasurementElement tracked in sliceLoadWatchersByAge is also tracked in
   *   sliceLoadWatchersBySlice.
   */
  private val sliceLoadWatchersByAge = new IntrusiveMinHeap[TickerTime, SliceLoadWatcher]()

  /**
   * Returns load reported for Slices in the given `assignment`. For each Slice in the assignment,
   * gathers load information as follows:
   *
   *  * If there are measurements for the Slice satisfying [[LoadWatcherTargetConfig.maxAge]] and
   *    [[LoadWatcherTargetConfig.minDuration]] constraints, they will be used to calculate the
   *    aggregated load for the Slice with the strategy described in "Load report and aggregation"
   *    section of the main class doc.
   *  * If no usable measurement is available, historical measurements from `assignment` will be
   *    used instead.
   *  * Absent both measurements and historical load data, the load information is considered
   *    incomplete.
   *
   * Returns a load map containing measurements for each Slice, along with load from top keys
   * (within their respective Slice), or None if there is incomplete load information.
   */
  @SuppressWarnings(Array("NonLocalReturn", "reason: TODO(<internal bug>): fix and remove suppression"))
  def getPrimaryRateLoadMap(
      now: TickerTime,
      assignment: Assignment): Option[(LoadMap, KeyLoadMap)] = {
    cleanUp(now)

    val loadMapBuilder = LoadMap.newBuilder()
    val allTopKeysBuilder = SortedMap.newBuilder[SliceKey, Double]
    for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
      val sliceLoadWatcherOpt: Option[SliceLoadWatcher] =
        sliceLoadWatchersBySlice.get(sliceAssignment.slice)

      sliceLoadWatcherOpt match {
        case Some(sliceLoadWatcher: SliceLoadWatcher) =>
          for (loadEntryWithTopKeys <- sliceLoadWatcher.getLoadEntries(now)) {
            val (loadMapEntry, keyLoadMap): (LoadMap.Entry, KeyLoadMap) = loadEntryWithTopKeys
            allTopKeysBuilder ++= keyLoadMap
            loadMapBuilder.putLoad(loadMapEntry, keyLoadMap)
          }
        case None =>
          // There's a gap in our measurements, check for history in the assignment.
          val historicLoadOpt: Option[Double] = sliceAssignment.primaryRateLoadOpt
          historicLoadOpt match {
            case Some(historicLoad: Double) =>
              loadMapBuilder.putLoad(LoadMap.Entry(sliceAssignment.slice, historicLoad))
            case None =>
              // We have incomplete load data.
              return None
          }
      }
    }
    Some((loadMapBuilder.build(), allTopKeysBuilder.result()))
  }

  /**
   * PRECONDITION: Measurements in `primaryRateMeasurements` must be created by the
   *               [[createMeasurement]] method of the same LoadWatcher instance.
   *
   * Updates load based on measurements from a Slicelet. A measurement replaces an existing
   * measurement for a Slice replica if it is newer than the existing measurement and contains a
   * measurement window long enough (no shorter than [[config.minDuration]]). Multiple measurements
   * for a Slice replica may be included in the `primaryRateMeasurements` passed in but this is not
   * expected given the load collection implementation in Slicelets.
   */
  def reportLoad(now: TickerTime, primaryRateMeasurements: Seq[Measurement]): Unit = {
    // Remove Measurements with too short windows, converts them to MeasurementElements and group
    // them by Slice so we can supply the Measurements to SliceLoadWatchers in batch, in order to
    // avoid performing garbage collection multiple times for each Slice.
    val newMeasurementsBySlice =
      mutable.Map[Slice, mutable.Builder[MeasurementElement, Seq[MeasurementElement]]]()
    for (measurement: Measurement <- primaryRateMeasurements) {
      if (measurement.windowDuration >= config.minDuration) {
        val element: MeasurementElement = MeasurementElement.from(measurement)
        val builder: mutable.Builder[MeasurementElement, Seq[MeasurementElement]] =
          newMeasurementsBySlice.getOrElseUpdate(element.slice, Seq.newBuilder[MeasurementElement])
        builder += element
      }
    }
    for (sliceWithBuilder <- newMeasurementsBySlice) {
      val (slice, newMeasurements): (Slice, Seq[MeasurementElement]) =
        (sliceWithBuilder._1, sliceWithBuilder._2.result())
      val sliceLoadWatcher: SliceLoadWatcher = sliceLoadWatchersBySlice.getOrElseUpdate(
        slice, {
          // No SliceLoadWatcher exists for `slice`, creates a new one.
          val newSliceLoadWatcher = new SliceLoadWatcher(
            slice,
            measurementMaxAge = config.maxAge
          )
          // Put the newly created SliceLoadWatcher into the heap so map and heap will be in sync.
          sliceLoadWatchersByAge.push(newSliceLoadWatcher)
          newSliceLoadWatcher
        }
      )
      sliceLoadWatcher.reportLoad(now, newMeasurements)
    }

    // While each Slice that has been reported with new Measurements has performed garbage
    // collection internally, we also trigger cleaning up for other Slices that may have expired
    // Measurements, as a new time `now` is informed to the LoadWatcher.
    cleanUp(now)
  }

  /**
   * Creates a new [[Measurement]] containing the information of the parameters passed in. See specs
   * of [[Measurement]] about the meanings of variables and requirements.
   *
   * Top keys will be discarded in the created [[Measurement]] if the LoadWatcher is configure to
   * not use them, in order to save memory and simplify top key handling code structure.
   */
  def createMeasurement(
      time: TickerTime,
      windowDuration: FiniteDuration,
      slice: Slice,
      resource: ResourceAddress,
      numReplicas: Int,
      load: Double,
      topKeys: Seq[KeyLoad],
      loadDistributionOpt: Option[LoadDistribution]): Measurement = {
    val overriddenTopKeys: Seq[KeyLoad] =
      if (staticConfig.allowTopKeys && config.useTopKeys) {
        topKeys
      } else {
        Seq.empty
      }
    val overriddenLoadDistributionOpt: Option[LoadDistribution] =
      if (staticConfig.allowLoadDistribution && config.useLoadDistribution) {
        loadDistributionOpt
      } else {
        None
      }
    new Measurement(
      time,
      windowDuration,
      slice,
      resource,
      numReplicas,
      load,
      overriddenTopKeys,
      overriddenLoadDistributionOpt
    )
  }

  /**
   * Cleans up the LoadWatcher as described in "garbage collection" section of the main class doc.
   * After this method returns, there exist no expired Measurements, and no empty SliceLoadWatchers
   * in `sliceLoadWatchersBySlice`.
   */
  private def cleanUp(now: TickerTime): Unit = {
    // Run SliceLoadWatcher.cleanUp() for all SliceLoadWatchers that are empty or contain expired
    // Measurements, and remove empty SliceLoadWatchers from the map and the heap.
    val expiryTime: TickerTime = now - config.maxAge

    @tailrec
    def cleanUpExpiredSliceLoadWatchers(): Unit = sliceLoadWatchersByAge.peek match {
      case Some(sliceLoadWatcher: SliceLoadWatcher)
          if sliceLoadWatcher.earliestMeasurementTimeOrMin < expiryTime =>
        // No-op if the SliceLoadWatcher is already empty.
        sliceLoadWatcher.cleanUp(now)
        if (sliceLoadWatcher.isEmpty) {
          sliceLoadWatchersByAge.pop()
          sliceLoadWatchersBySlice.remove(sliceLoadWatcher.slice)
        }
        cleanUpExpiredSliceLoadWatchers()
      case _ => ()
    }

    cleanUpExpiredSliceLoadWatchers()
  }

  object forTest {

    /**
     * Checks the invariants of LoadWatcher:
     * - [[sliceLoadWatchersBySlice]] and [[sliceLoadWatchersByAge]] are well-formed and tracking
     *   the same set of elements;
     * - all [[SliceLoadWatcher]] tracked are non-empty and doesn't contain expired Measurements.
     *
     * These invariants are maintained passively and are expected to hold after every call to the
     * LoadWatcher, i.e. after a new `now` is explicitly supplied to the LoadWatcher.
     */
    @throws[AssertionError]("If any of the invariants above is not true.")
    def checkInvariants(now: TickerTime): Unit = {
      sliceLoadWatchersByAge.forTest.checkInvariants()
      val expirationTime: TickerTime = now - config.maxAge

      // `sliceLoadWatchersBySlice` is well-formed and doesn't track empty SliceLoadWatchers or
      // expired Measurements.
      for (sliceWithWatcher <- sliceLoadWatchersBySlice) {
        val (slice, sliceLoadWatcher): (Slice, SliceLoadWatcher) = sliceWithWatcher
        sliceLoadWatcher.forTestAdditional.checkInvariants(now)
        iassert(sliceLoadWatcher.slice == slice)
        iassert(!sliceLoadWatcher.isEmpty)
        iassert(sliceLoadWatcher.earliestMeasurementTimeOrMin >= expirationTime)
      }

      // The map and heap are tracking the same measurement elements.
      iassert(sliceLoadWatchersByAge.size == sliceLoadWatchersBySlice.size)
      for (sliceLoadWatcher: SliceLoadWatcher <- sliceLoadWatchersByAge.forTest.copyContents()) {
        iassert(sliceLoadWatchersBySlice(sliceLoadWatcher.slice).eq(sliceLoadWatcher))
      }
    }

    /** Returns the number of measurements tracked by the watcher. */
    def size: Int = {
      sliceLoadWatchersBySlice.values.map((_: SliceLoadWatcher).forTestAdditional.size).sum
    }
  }
}

private[assigner] object LoadWatcher {

  /**
   * Static configuration (i.e. based on DbConf, does not vary per target) for the LoadWatcher.
   *
   * @param allowTopKeys whether to allow any target to use top key information from the Slicelet.
   *                     [[LoadWatcherTargetConfig.useTopKeys]] must still be enabled to actually
   *                     use the top keys.
   * @param allowLoadDistribution whether to allow any target to use the per-key load distribution
   *                              (CDF) reported by the Slicelet.
   *                              [[LoadWatcherTargetConfig.useLoadDistribution]] must still be
   *                              enabled to actually use the load distribution.
   */
  case class StaticConfig(allowTopKeys: Boolean, allowLoadDistribution: Boolean)

  object StaticConfig {

    /** Create a [[StaticConfig]] derived from the DbConfs in [[LoadWatcherConf]]. */
    def fromConf(conf: LoadWatcherConf): StaticConfig = {
      StaticConfig(
        allowTopKeys = conf.allowTopKeys,
        allowLoadDistribution = conf.allowLoadDistribution
      )
    }
  }

  /**
   * A load measurement for a Slice replica (a `slice` assigned on a certain `resource`). The load
   * is the exponentially smoothed load over the window of time [`time` - `windowDuration`, `time`],
   * and `numReplicas` indicates the number of replicas (known by the Slicelet who reported this
   * Measurement) for `slice` when this load report was generated. `topKeys` contains estimated load
   * measurements for keys within the Slice, and the sum of their estimated load should be <= `load`
   * (with some small wiggle room for floating point errors). `loadDistributionOpt`, when present,
   * is the approximate distribution (CDF) of `load` across keys within the Slice.
   *
   * Must be created by [[LoadWatcher.createMeasurement]].
   *
   * @throws IllegalArgumentException If windowDuration is negative.
   * @throws IllegalArgumentException If numReplicas is non-positive.
   * @throws IllegalArgumentException If load doesn't satisfy
   *                                  LoadMeasurement.requireValidLoadMeasurement().
   */
  class Measurement @throws[IllegalArgumentException] private[LoadWatcher] (
      val time: TickerTime,
      val windowDuration: FiniteDuration,
      val slice: Slice,
      val resource: ResourceAddress,
      val numReplicas: Int,
      val load: Double,
      val topKeys: Seq[KeyLoad],
      val loadDistributionOpt: Option[LoadDistribution]) {
    if (windowDuration < Duration.Zero) {
      throw new IllegalArgumentException(s"windowDuration must be non-negative: $windowDuration")
    }
    if (numReplicas <= 0) {
      throw new IllegalArgumentException(s"numReplicas must be positive: $numReplicas")
    }
    LoadMeasurement.requireValidLoadMeasurement(load)
  }

  /**
   * Encapsulates a load measurement. Extends [[IntrusiveMinHeapElement]] so that measurements can
   * be efficiently organized by [[time]] (which is the priority of the measurement in the minheap),
   * supporting the [[SliceLoadWatcher.cleanUp]] method.
   */
  private class MeasurementElement private (
      val slice: Slice,
      val resource: ResourceAddress,
      val load: Double,
      val topKeys: Seq[KeyLoad],
      val numReplicas: Int,
      val loadDistributionOpt: Option[LoadDistribution]
  ) extends IntrusiveMinHeapElement[TickerTime] {
    iassert(numReplicas > 0)
    LoadMeasurement.requireValidLoadMeasurement(load)

    /** Exposes the protected remove method to the [[LoadWatcher]] implementation. */
    def removeFromHeap(): Unit = remove()

    /** See [[Measurement.time]]. */
    def time: TickerTime = getPriority
  }

  private object MeasurementElement {

    /**
     * Creates an [[MeasurementElement]] containing the same load information as `measurement`.
     */
    def from(measurement: Measurement): MeasurementElement = {
      val measurementElement = new MeasurementElement(
        measurement.slice,
        measurement.resource,
        measurement.load,
        measurement.topKeys,
        measurement.numReplicas,
        measurement.loadDistributionOpt
      )
      measurementElement.setPriority(measurement.time)
      measurementElement
    }
  }

  /**
   * Abstraction that tracks Measurements and maintains size and age requirements of the tracked
   * Measurements for a `slice`:
   *
   * - No expired Measurements will be tracked.
   * - The number of tracked Measurements is no more than the of max number of replicas reported by
   *   the currently tracked (i.e. non-expired) Measurements themselves. Oldest Measurements will be
   *   cleaned up to satisfy this requirement. See the main doc of [[LoadWatcher]] for more
   *   information.
   *
   * These invariants are maintained passively and are expected to hold after the SliceLoadWatcher
   * learns an updated current time `now` via this public APIs.
   *
   * Extends [[IntrusiveMinHeapElement]] so that SliceLoadWatchers can be efficiently organized by
   * the earliest time of Measurements it is tracking and we can perform efficient garbage
   * collection (see [[LoadWatcher.sliceLoadWatchersByAge]]).
   *
   * @param measurementMaxAge The maximum age of Measurements. Measurements older than this age
   *                          (since [[Measurement.time]]) are considered expired and will be
   *                          cleaned up.
   */
  private class SliceLoadWatcher(val slice: Slice, measurementMaxAge: FiniteDuration)
      extends IntrusiveMinHeapElement[TickerTime] {

    private val logger = PrefixLogger.create(getClass, s"slice=$slice")

    /*
     * Implementation note: The SliceLoadWatcher keeps track of the latest load from each Resource
     * in the `measurementsByResource` map and `measurementsByAge` heap, with an additional
     * `measurementsCountsByNumReplicas` sorted map from number of Replicas to the number of
     * Measurements. The measurements in the map and the heap are kept in sync.
     * `measurementsByResource` is used to quickly find the latest load reported for a resource. The
     * heap is used to quickly find the reports that are eligible for garbage collection by expiry
     * time, which is performed lazily when `cleanUp()` or `reportLoad()` of this class is called.
     * An extra sorted map `measurementsCountsByNumReplicas` is maintained to find the maximum
     * number of replicas among currently tracked Measurements, which is the information needed for
     * garbage collection.
     */

    /**
     * Map tracking the [[MeasurementElement]] for each resource.
     *
     * Invariants:
     * - Any MeasurementElement tracked in measurementsByResource is also tracked in
     *   measurementsByAge.
     * - See [[SliceLoadWatcher]]'s main doc.
     */
    private val measurementsByResource = mutable.Map[ResourceAddress, MeasurementElement]()

    /**
     * Heap tracking the [[MeasurementElement]]s to quickly find the one eligible to be cleaned.
     */
    private val measurementsByAge = new IntrusiveMinHeap[TickerTime, MeasurementElement]

    /**
     * Sorted Map from number of replicas to the number of Measurements that hold this number of
     * replicas, based on the Measurements tracked in `measurementsByResource`. Used to quickly find
     * the maximum number of replicas among currently tracked Measurements.
     *
     * e.g. when measurementsByResource ==
     * Map(
     *   "pod0" -> MeasurementElement(numReplicas=3, ...),
     *   "pod1" -> MeasurementElement(numReplicas=3, ...),
     *   "pod2" -> MeasurementElement(numReplicas=4, ...)
     * ),
     * measurementsCountsByNumReplicas will be Map(3 -> 2, 4 -> 1), and maximum number of Replicas
     * is 4.
     *
     * We only track the counts of Measurements rather than tracking the Measurements themselves
     * by numReplicas in a Sorted Set or Heap, because this approach is cheaper as all Measurements
     * should have the same number of Replicas for most of the time in production, and only
     * assignment changes which change the number of replicas will lead to multiple replica counts.
     * Typically the rate of replica number changes is much less than the rate of Measurement
     * expiry, so we expect the size of `measurementsCountsByNumReplicas` to be no more than 2.
     */
    private val measurementsCountsByNumReplicas = mutable.SortedMap[Int, Int]()

    /**
     * The creation time of the youngest Measurement, i.e. the time ([[Measurement.time]]) that is
     * highest in value among the currently tracked Measurements. Used to normalize the ages of
     * Measurements by subtracting them with the youngest age in load calculation, so the normalized
     * ages will be as small as possible while also being non-negative. In this way we reduce the
     * floating point calculation error.
     *
     * e.g. real ages       = [8 min, 9 min, 10 min]
     *      youngest age    = 8 min (calculated by `now - highestMeasurementTime`)
     *      normalized ages = [0 min, 1 min, 2 min].
     *
     * Will be `None` when the SliceLoadWatcher is empty.
     */
    private var highestMeasurementTimeOpt: Option[TickerTime] = None

    // Initial priority is TickerTime.MIN as it's currently empty.
    setPriority(TickerTime.MIN)

    /**
     * PRECONDITION: `newMeasurements` must be for `slice`.
     *
     * Puts `newMeasurements` into load watcher if they are newer than existing ones for each
     * resource. Cleaning up and heap position adjustment will be performed.
     */
    def reportLoad(now: TickerTime, newMeasurements: Seq[MeasurementElement]): Unit = {
      for (newMeasurement: MeasurementElement <- newMeasurements) {
        iassert(newMeasurement.slice == slice)
        measurementsByResource.get(newMeasurement.resource) match {
          case Some(existingMeasurement: MeasurementElement) =>
            if (existingMeasurement.time < newMeasurement.time) {
              replaceMeasurement(existingMeasurement, newMeasurement)
            }
          case None =>
            putNewMeasurement(newMeasurement)
        }
      }
      cleanUp(now)
    }

    /**
     * PRECONDITION: The SliceLoadWatcher is non-empty.
     *
     * Returns this Slice's load as [[LoadMap.Entry]]s, each paired with the top keys it contains.
     * When the freshest Measurement carries a load distribution, the Slice is apportioned into one
     * entry per CDF bucket (see [[apportionLoadUsingDistribution]]); otherwise the whole Slice's
     * load is reported as a single entry.
     *
     * Each entry's load ''excludes'' its top keys, matching the input contract of
     * [[LoadMap.Builder.putLoad]].
     */
    def getLoadEntries(now: TickerTime): Seq[(LoadMap.Entry, KeyLoadMap)] = {
      val (fullSliceLoad, topKeys, loadDistributionOpt): (
          Double,
          KeyLoadMap,
          Option[LoadDistribution]) = aggregateLoadFromReplicas(now)

      // When the Slice is exactly one key and that key is a top key, the key is the whole
      // Slice and must carry its entire load.
      if (slice.highExclusive == slice.lowInclusive.successor() &&
        topKeys.contains(slice.lowInclusive)) {
        return Seq((LoadMap.Entry(slice, 0.0), KeyLoadMap(slice.lowInclusive -> fullSliceLoad)))
      }

      loadDistributionOpt match {
        case Some(loadDistribution: LoadDistribution)
            if loadDistribution.points.nonEmpty && fullSliceLoad > 0.0 =>
          // Apportion the Slice into CDF buckets: returns one (Entry, KeyLoadMap) pair per bucket.
          apportionLoadUsingDistribution(fullSliceLoad, loadDistribution, topKeys)
        case _ =>
          // No distribution, or no load to apportion: return one entry with top keys carved out.
          val loadExcludingTopKeys: Double =
            Math.max(fullSliceLoad - topKeys.values.sum, 0.0)
          Seq((LoadMap.Entry(slice, loadExcludingTopKeys), topKeys))
      }
    }

    /**
     * Cleans up oldest Measurements to satisfy SliceLoadWatcher's invariants (described in the main
     * class doc) while maintaining the position of SliceLoadWatcher on the IntrusiveMinHeap it
     * attached (if any) based on the new priority.
     */
    def cleanUp(now: TickerTime): Unit = {
      val expiryTime: TickerTime = now - measurementMaxAge
      var oldestMeasurementOpt: Option[MeasurementElement] = measurementsByAge.peek
      while ({
        val hasExpired: Boolean =
          oldestMeasurementOpt.exists((_: MeasurementElement).time < expiryTime)
        // Condition to maintain the size requirement of SliceLoadWatcher: The number of tracked
        // Measurements should be no more than the of max number of replicas reported by the
        // currently tracked (i.e. non-expired) Measurements themselves. This is a heuristic
        // approach to restrict memory usage but also keep the most measurement information as
        // possible, especially in the face of a sudden drop of replica number. See
        // "Garbage collection" section of LoadWatcher's main doc for detailed information.
        val oversizing: Boolean = measurementsCountsByNumReplicas.lastOption.exists {
          case (maxNumReplicas: Int, _) => measurementsByAge.size > maxNumReplicas
        }
        hasExpired || oversizing
      }) {
        // `oversizing` can only be true when at least two Measurements are tracked, so the head is
        // always present here.
        removeExistingMeasurement(oldestMeasurementOpt.get)
        oldestMeasurementOpt = measurementsByAge.peek
      }

      // After removing all Measurements needed, reset the priority of SliceLoadWatcher and adjust
      // its position in the heap (if it's attached to any).
      setPriority(
        priority = oldestMeasurementOpt.map((_: MeasurementElement).time).getOrElse(TickerTime.MIN)
      )
    }

    /**
     * Returns the earliest time of currently tracked Measurements, or [[TickerTime.MIN]] if the
     * SliceLoadWatcher is empty. Used as the heap priority so that the empty SliceLoadWatcher or
     * the ones tracking expired Measurements can be quickly found.
     */
    def earliestMeasurementTimeOrMin: TickerTime = {
      getPriority
    }

    /** Returns whether the SliceLoadWatcher is empty. */
    def isEmpty: Boolean = {
      measurementsByResource.isEmpty
    }

    /**
     * PRECONDITION: `existingMeasurement` is contained in SliceLoadWatcher.
     * PRECONDITION: `newMeasurement` is for the same Slice and resource, and is newer.
     *
     * Replaces a tracked measurement with a newer measurement for the same resource, keeping the
     * measurement indexes in sync. Does not perform garbage collection.
     */
    private def replaceMeasurement(
        existingMeasurement: MeasurementElement,
        newMeasurement: MeasurementElement): Unit = {
      iassert(existingMeasurement.slice == newMeasurement.slice)
      iassert(existingMeasurement.resource == newMeasurement.resource)
      iassert(existingMeasurement.time < newMeasurement.time)
      if (existingMeasurement.numReplicas == newMeasurement.numReplicas) {
        // Replacing a measurement with the same replica count leaves the counts unchanged, avoiding
        // cancelling tree mutations and node allocation in the steady state.
        val replacedMeasurementOpt: Option[MeasurementElement] =
          measurementsByResource.put(newMeasurement.resource, newMeasurement)
        iassert(replacedMeasurementOpt.contains(existingMeasurement))
        existingMeasurement.removeFromHeap()
        measurementsByAge.push(newMeasurement)
        if (highestMeasurementTimeOpt.forall((_: TickerTime) < newMeasurement.time)) {
          highestMeasurementTimeOpt = Some(newMeasurement.time)
        }
      } else {
        removeExistingMeasurement(existingMeasurement)
        putNewMeasurement(newMeasurement)
      }
    }

    /**
     * PRECONDITION: `measurement` is contained in SliceLoadWatcher.
     *
     * Removes an existing `measurement` from `measurementsByResource` and `measurementsByAge` while
     * adjusting `measurementsCountsByNumReplicas` based on the change. Does not perform garbage
     * collection.
     */
    private def removeExistingMeasurement(measurement: MeasurementElement): Unit = {
      {
        val removedMeasurementOpt: Option[MeasurementElement] =
          measurementsByResource.remove(measurement.resource)
        iassert(
          removedMeasurementOpt.isDefined,
          "removeExistingMeasurement() tries to remove a non-existing resource: " +
          s"${measurement.resource}"
        )
        iassert(
          removedMeasurementOpt.isDefined,
          s"removeExistingMeasurement() tries to remove a non-existing measurement: $measurement"
        )
      }
      measurement.removeFromHeap()
      val numReplicas: Int = measurement.numReplicas
      if (measurementsCountsByNumReplicas(numReplicas) == 1) {
        measurementsCountsByNumReplicas.remove(numReplicas)
      } else {
        measurementsCountsByNumReplicas(numReplicas) -= 1
      }
      if (isEmpty) {
        highestMeasurementTimeOpt = None
      }
    }

    /**
     * PRECONDITION: `measurement.resource` is not contained in SliceLoadWatcher.
     *
     * Puts a new `measurement` into `measurementsByResource` and `measurementsByAge` while
     * adjusting `measurementsCountsByNumReplicas` based on the change. Does not perform garbage
     * collection.
     */
    private def putNewMeasurement(measurement: MeasurementElement): Unit = {
      iassert(
        measurementsByResource.put(measurement.resource, measurement).isEmpty,
        s"putNewMeasurement() tries to put Measurement $measurement for an existing resource. "
      )
      measurementsByAge.push(measurement)
      val numReplicas: Int = measurement.numReplicas
      measurementsCountsByNumReplicas(numReplicas) =
        measurementsCountsByNumReplicas.getOrElse(numReplicas, 0) + 1
      if (highestMeasurementTimeOpt.forall((_: TickerTime) < measurement.time)) {
        highestMeasurementTimeOpt = Some(measurement.time)
      }
    }

    /**
     * Given a KeyLoadMap and the total load on the Slice, if the sum of key loads exceeds the total
     * load for the Slice, returns a new KeyLoadMap where the key loads are rescaled such that the
     * sum of key loads is equal to the total slice load. Otherwise, returns the original
     * KeyLoadMap.
     *
     * Also fires a `TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD` alert if the key load sum exceeds the
     * total slice load by too much an error (`1E-5`), which is not within expected floating point
     * error range.
     *
     * @param resourceOptForDebug Optional ResourceAddress to be shown in the alert message. When
     *                            empty, it means the loads are accumulated from all resources.
     */
    private def rescaleKeyLoadsIfExceedingSliceLoad(
        totalSliceLoadIncludingKeys: Double,
        unprocessedKeyLoads: KeyLoadMap,
        resourceOptForDebug: Option[ResourceAddress]): KeyLoadMap = {
      val keyLoadSum: Double = unprocessedKeyLoads.values.sum

      if (keyLoadSum > totalSliceLoadIncludingKeys * (1 + 1e-5) && keyLoadSum > 1e-300) {
        // Fire alert for further investigation if the key load sum exceeds the total slice load
        // by too much an error (`1E-5`) which is not within expected floating point error range.
        // We also ignore absolute values smaller than `1E-300`, since `Double.MinPositiveValue` is
        // `4.9E-324` so we may lose some precision near that; in that case the load value is not
        // meaningful for load balancing anyway.
        logger.alert(
          Severity.DEGRADED,
          TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD,
          s"Load for top keys: $unprocessedKeyLoads is exceeding total load on the " +
          s"Slice $slice: $totalSliceLoadIncludingKeys from " +
          s"${resourceOptForDebug.getOrElse("all resources after accumulation")} " +
          "by an unexpected large error."
        )
      }

      if (keyLoadSum > totalSliceLoadIncludingKeys) {
        // Always rescale load from the keys if key load sum exceeds total slice load, regardless
        // how much it's exceeding the slice load by.
        val scaleFactor: Double = totalSliceLoadIncludingKeys / keyLoadSum
        KeyLoadMap.empty ++ unprocessedKeyLoads.mapValues { keyLoad: Double =>
          keyLoad * scaleFactor
        }
      } else {
        unprocessedKeyLoads
      }
    }

    /** Helper method to convert a Sequence of [[KeyLoad]] to a [[KeyLoadMap]]. */
    private def toKeyLoadMap(keyLoads: Seq[KeyLoad]): KeyLoadMap = {
      KeyLoadMap.empty ++ keyLoads.map { keyLoad: KeyLoad =>
        (keyLoad.key, keyLoad.underestimatedPrimaryRateLoad)
      }
    }

    /**
     * PRECONDITION: The SliceLoadWatcher is non-empty.
     *
     * Returns the Slice's aggregated total load (including top-key load), the top keys' aggregated
     * loads, and the load distribution from the freshest Measurement (if any). The load and top
     * keys are aggregated across the tracked Measurements using the strategy from the "Load report
     * and aggregation" and "Top keys handling" sections of the main doc of [[LoadWatcher]].
     */
    private def aggregateLoadFromReplicas(
        now: TickerTime): (Double, KeyLoadMap, Option[LoadDistribution]) = {
      iassert(measurementsByResource.nonEmpty)
      iassert(highestMeasurementTimeOpt.nonEmpty)

      // See `highestMeasurementTimeOpt` for how this will be used.
      val youngestAge: FiniteDuration = now - highestMeasurementTimeOpt.get

      // Accumulator for total Slice load, including top keys.
      val sliceLoadAccumulatorIncludingTopKeys = new WeightedLoadAccumulator(
        LOAD_WEIGHT_DECAYING_HALFLIFE
      )
      // Map containing the accumulators for all keys we need to process when aggregating the load
      // for the Slice, i.e. all the keys that have appeared in currently tracked Measurements for
      // at least once. Note that when the LoadWatcher is configured to not use top keys, they are
      // empty.
      val topKeyAccumulators: Map[SliceKey, WeightedLoadAccumulator] =
        measurementsByResource.values
          .flatMap((_: MeasurementElement).topKeys.map { keyLoad: KeyLoad =>
            (keyLoad.key, new WeightedLoadAccumulator(LOAD_WEIGHT_DECAYING_HALFLIFE))
          })
          .toMap

      // For each Measurement tracked, accumulate its Slice load on
      // `sliceLoadAccumulatorIncludingTopKeys`, and accumulate its top keys' load on
      // `topKeyAccumulators`. We assume 0 load for top keys when they don't appear in a load
      // report.
      for (measurement: MeasurementElement <- measurementsByResource.values) {
        val numReplicas: Int = measurement.numReplicas
        // See `highestMeasurementTimeOpt` for information about normalized age.
        val measurementNormalizedAge: FiniteDuration = now - measurement.time - youngestAge
        sliceLoadAccumulatorIncludingTopKeys
          .accumulate(measurementNormalizedAge, measurement.load, numReplicas)

        // For each Measurement, try rescaling the key load if they exceed the slice load in this
        // Measurement.
        val adjustedTopKeyLoadMap: KeyLoadMap =
          rescaleKeyLoadsIfExceedingSliceLoad(
            measurement.load,
            toKeyLoadMap(measurement.topKeys),
            resourceOptForDebug = Some(measurement.resource)
          )

        for (key: SliceKey <- topKeyAccumulators.keys) {
          // Note that all Measurements are for the same Slice, which is SliceLoadWatcher.slice, and
          // all the `key`s are within this Slice.
          topKeyAccumulators(key).accumulate(
            measurementNormalizedAge,
            // Note we assume 0 load for top keys when they don't appear in a load report. See
            // "Top keys handling" section of LoadWatcher's main doc.
            adjustedTopKeyLoadMap.getOrElse(key, 0.0),
            numReplicas
          )
        }
      }

      // Build results from the accumulators for Slice and top keys.
      val accumulatedSliceLoadIncludingTopKeys: Double =
        sliceLoadAccumulatorIncludingTopKeys.result()
      val accumulatedTopKeyLoadMap: KeyLoadMap =
        KeyLoadMap.empty ++ topKeyAccumulators.mapValues { accumulator: WeightedLoadAccumulator =>
          accumulator.result()
        }

      // Based on the way we estimate the load of missing keys (missing keys have 0 load), the
      // accumulated key load sum should mathematically be no greater than the accumulated slice
      // load. However taking floating point error into consideration, we still rescale the
      // accumulated load to ensure the key load will not exceed Slice load in practice.
      val adjustedAccumulatedTopKeyLoadMap: KeyLoadMap = rescaleKeyLoadsIfExceedingSliceLoad(
        accumulatedSliceLoadIncludingTopKeys,
        accumulatedTopKeyLoadMap,
        resourceOptForDebug = None
      )

      // Unlike scalar load and top keys, we don't aggregate load distributions across Measurements;
      // the freshest histogram should suffice as a representative load distribution. See the "Load
      // distribution" section of the main doc.
      //
      // TODO(<internal bug>): Picking the freshest distribution is an interim strategy. It is correct when
      // there is only a single replica, or if a Slice's Measurements come from replicas with
      // interchangeable key distributions. It does not, however, accurately reflect load that is
      // distributed asymmetrically across replicas. Refine this to combine the distributions across
      // replicas, weighted by each replica's age-decayed load.
      val freshestLoadDistributionOpt: Option[LoadDistribution] =
        measurementsByResource.values.maxBy((_: MeasurementElement).time).loadDistributionOpt

      (
        accumulatedSliceLoadIncludingTopKeys,
        adjustedAccumulatedTopKeyLoadMap,
        freshestLoadDistributionOpt
      )
    }

    /**
     * PRECONDITION: `loadDistribution.points` is non-empty.
     *
     * Apportions the Slice's total load into finer-grained entries following the per-key load CDF
     * in `loadDistribution`, reserving each of `topKeys` at its load so it can later be split into
     * its own single-key entry. Each returned entry's load ''excludes'' its contained top keys
     * (which are returned alongside it), matching the contract [[getPrimaryRateLoadMap]] expects
     * (no double counting of load).
     *
     * For a Slice `[low, high)` whose distribution has key samples `K_0 < K_1 < ... < K_n` with
     * cumulative load fractions `f_0 <= f_1 <= ... <= f_n`, the *gross* per-bucket CDF load is:
     * {{{
     *   [low, K_0.successor())                  -> f_0 * fullSliceLoad
     *   [K_{i-1}.successor(), K_i.successor())  -> (f_i - f_{i-1}) * fullSliceLoad  for 1 <= i <= n
     *   [K_n.successor(), high)                 -> (1 - f_n) * fullSliceLoad
     * }}}
     * Note: Each bucket's exclusive high bound is `K.successor()` (not `K`) because `f_i` counts
     * keys *at or below* `K_i`, so `K_i` must fall inside its own bucket.
     *
     * Each bucket then reserves the load of each top key it contains, and computes a background
     * load by subtracting the top keys load from the gross CDF load. When the CDF and the top keys
     * are not perfectly in sync, a bucket's top keys load can exceed its gross CDF load; we drain
     * the resulting overshoot by rescaling all buckets' background loads to ensure the total
     * background load + top keys load equals the Slice's total load. If the top keys load exceeds
     * the Slice's total load, we preserve the top keys load and assign zero background load.
     *
     * @param fullSliceLoad The Slice's total load, top keys included.
     * @param loadDistribution The Slice's load distribution.
     * @param topKeys The Slice's top keys, and their loads.
     * @return A sequence of (background Entry, reserved keys) pairs.
     */
    private def apportionLoadUsingDistribution(
        fullSliceLoad: Double,
        loadDistribution: LoadDistribution,
        topKeys: KeyLoadMap): Seq[(LoadMap.Entry, KeyLoadMap)] = {
      iassert(loadDistribution.points.nonEmpty, "loadDistribution.points must be non-empty")

      // An intermediary representation of a CDF bucket before its background load is finalized:
      // contains the bucket's key range, the top keys it encompasses, and its tentative background
      // load - the bucket's gross CDF load minus the load reserved for those top keys (at least 0).
      // This tentative load may be rescaled later to ensure we respect the Slice's total load.
      case class LoadBucket(subSlice: Slice, topKeys: KeyLoadMap, backgroundLoad: Double)

      // Builds a LoadBucket for `subSlice`, reserving the load of each top key within it.
      def buildLoadBucket(subSlice: Slice, grossLoad: Double): LoadBucket = {
        val keysInSubSlice: KeyLoadMap = subSlice.highExclusive match {
          case highKey: SliceKey => topKeys.range(subSlice.lowInclusive, highKey)
          case InfinitySliceKey => topKeys.from(subSlice.lowInclusive)
        }
        // Flooring background at zero can make reserved keys plus background exceed the Slice's
        // total load across buckets. Step 2 rescales backgrounds to remove this excess.
        LoadBucket(subSlice, keysInSubSlice, Math.max(grossLoad - keysInSubSlice.values.sum, 0.0))
      }

      // Step 1: build one sub-slice bucket per CDF sample (plus a tail bucket to cover potential
      // residual load), reserving the top keys each contains.
      val loadBuckets = Seq.newBuilder[LoadBucket]
      var bucketLowInclusive: SliceKey = slice.lowInclusive
      var previousFraction: Double = 0.0
      // Accumulate the total gross background load (load before rescaling).
      var unscaledBackground: Double = 0.0
      for (point: LoadDistribution.CdfPoint <- loadDistribution.points) {
        // Keys at or below `point.key` fall in `[bucketLowInclusive, point.key.successor())`.
        val bucketHighExclusive: SliceKey = point.key.successor()
        // When this bucket already reaches the Slice's high bound, every key is <= `point.key`, so
        // its cumulative fraction must be 1.0 (there is no tail range left to carry a residual).
        val canonicalCumulativeLoadFraction: Double =
          if (bucketHighExclusive == slice.highExclusive) {
            // If the reported fraction is below 1.0 the distribution is incongruent: we fold the
            // residual into this bucket rather than dropping it, and alert for investigation.
            if (point.cumulativeLoadFraction < 1.0) {
              logger.alert(
                Severity.DEGRADED,
                INCONGRUENT_LOAD_DISTRIBUTION,
                s"Load distribution for Slice $slice tops out at less than 1.0 (" +
                s"${point.cumulativeLoadFraction}) at the Slice's last key ${point.key}."
              )
            }
            1.0
          } else {
            point.cumulativeLoadFraction
          }
        val bucketFraction: Double = canonicalCumulativeLoadFraction - previousFraction
        val bucket: LoadBucket = buildLoadBucket(
          Slice(bucketLowInclusive, bucketHighExclusive),
          bucketFraction * fullSliceLoad
        )
        loadBuckets += bucket
        unscaledBackground += bucket.backgroundLoad
        bucketLowInclusive = bucketHighExclusive
        previousFraction = canonicalCumulativeLoadFraction
      }
      // Emit a tail bucket to cover potential residual load in the remaining key range.
      if (bucketLowInclusive < slice.highExclusive) {
        val tailSlice: Slice = Slice(bucketLowInclusive, slice.highExclusive)
        // Note: The tailFraction may be 0.0. We compute a bucket for it regardless to ensure that
        // we correctly account for any top keys that may live in the tail range.
        // If the fraction is 0.0 AND there are no top keys in the tail range, this bucket is not
        // strictly necessary, but it is simpler (and harmless) to always emit it.
        val tailFraction: Double = 1.0 - previousFraction
        val tailBucket: LoadBucket = buildLoadBucket(tailSlice, tailFraction * fullSliceLoad)
        loadBuckets += tailBucket
        unscaledBackground += tailBucket.backgroundLoad
      }
      val buckets: Seq[LoadBucket] = loadBuckets.result()

      // Step 2: rescale the buckets' background load to respect the Slice's total load, and emit
      // each bucket as a (background Entry, reserved keys) pair.
      val topKeysLoad: Double = topKeys.values.sum
      // Target background is at least 0.0; we clamp here in case topKeysLoad exceeds fullSliceLoad.
      val targetBackground: Double = Math.max(fullSliceLoad - topKeysLoad, 0.0)
      // backgroundScale is always in [0, 1], subject to float drift in the two sums which we clamp
      // away: each bucket's background is its gross CDF load minus its reserved keys, floored at
      // zero. Their sum is therefore >= targetBackground. If unscaledBackground is 0,
      // targetBackground is 0 too, so scaling is a no-op.
      val backgroundScale: Double =
        if (unscaledBackground > 0.0) (targetBackground / unscaledBackground).min(1.0) else 0.0

      buckets.map { bucket: LoadBucket =>
        val scaledBackgroundLoad: Double = bucket.backgroundLoad * backgroundScale
        // A single-key bucket (its high bound is its low key's successor) is fully occupied by its
        // lone key: if that key is reserved as a top key, there is no range left to carry the
        // bucket's background so we fold it into the key instead to ensure we do not drop load.
        val isSingleKeyBucket: Boolean =
          bucket.subSlice.highExclusive == bucket.subSlice.lowInclusive.successor()
        if (isSingleKeyBucket && bucket.topKeys.nonEmpty) {
          val (key, keyLoad): (SliceKey, Double) = bucket.topKeys.head
          (LoadMap.Entry(bucket.subSlice, 0.0), KeyLoadMap(key -> (keyLoad + scaledBackgroundLoad)))
        } else {
          (LoadMap.Entry(bucket.subSlice, scaledBackgroundLoad), bucket.topKeys)
        }
      }
    }

    /** Additional test utils for [[SliceLoadWatcher]] than [[IntrusiveMinHeapElement.forTest]]. */
    object forTestAdditional {

      /**
       * Asserts the following invariants of SliceLoadWatcher:
       * - `measurementsBySlice`, `measurementsByAge` and `measurementsCountsByNumReplicas` are kept
       *   in sync;
       * - no measurement has expired;
       * - number of measurements tracked does not exceed the maximum known number of replicas.
       *
       * The invariants are kept passively after [[reportLoad]] or [[cleanUp]] is called.
       */
      def checkInvariants(now: TickerTime): Unit = {
        measurementsByAge.forTest.checkInvariants()
        val expiryTime: TickerTime = now - measurementMaxAge

        // `measurementBySlice` is well-formed, and has no expired Measurement.
        for (resourceWithMeasurement <- measurementsByResource) {
          val (resource, measurement): (ResourceAddress, MeasurementElement) =
            resourceWithMeasurement
          iassert(measurement.slice == slice)
          iassert(measurement.resource == resource)
          iassert(measurement.time >= expiryTime)
        }

        // `measurementsByResource`, `measurementsByAge` and `measurementsCountsByNumReplicas` are
        // kept in sync.
        iassert(measurementsByAge.size == measurementsByResource.size)
        for (measurement: MeasurementElement <- measurementsByAge.forTest.copyContents()) {
          iassert(measurementsByResource(measurement.resource).eq(measurement))
        }
        iassert(
          measurementsCountsByNumReplicas.toMap ==
          measurementsByResource.values
            .groupBy((_: MeasurementElement).numReplicas)
            .mapValues((_: Iterable[MeasurementElement]).size)
        )

        if (measurementsByResource.nonEmpty) {
          // Number of measurements tracked does not exceed the maximum known number of replicas.
          iassert(
            measurementsByResource.size <= measurementsByResource.values
              .map((_: MeasurementElement).numReplicas)
              .max
          )
          val measurementTimes: Seq[TickerTime] =
            measurementsByResource.values.map((_: MeasurementElement).time).toSeq
          // `highestMeasurementTimeOpt` tracks the highest measurement time.
          iassert(highestMeasurementTimeOpt.get == measurementTimes.max)
          // Priority is correctly set.
          iassert(getPriority == measurementTimes.min)
        }
      }

      /** Returns the number of Measurements tracked in the SliceLoadWatcher. */
      def size: Int = {
        measurementsByResource.size
      }
    }
  }

  /**
   * Helper class to implement the load aggregation for a Slice. It accumulates the `age`, `load`
   * and `numReplicas` for a Slice or a top key from multiple Measurements, using the strategy
   * in "Load report and aggregation" section of the main doc of [[LoadWatcher]].
   */
  private class WeightedLoadAccumulator(halfLife: Duration) {
    private var totalWeight: Double = 0.0
    private var totalWeightedLoad: Double = 0.0

    /**
     * PRECONDITION: `load` satisfies [[LoadMeasurement.requireValidLoadMeasurement]].
     * PRECONDITION: `numReplicas` > 0.
     *
     * Accumulates `load` for a slice replica that reported that it was one of `numReplicas`, and
     * whose age is `age`.
     */
    def accumulate(age: FiniteDuration, load: Double, numReplicas: Int): Unit = {
      LoadMeasurement.requireValidLoadMeasurement(load)
      iassert(numReplicas > 0)

      val weight: Double = Math.pow(2, -age.toMillis.toDouble / halfLife.toMillis)
      totalWeight += weight
      totalWeightedLoad += (weight * load * numReplicas)
    }

    /**
     * PRECONDITION: [[accumulate]] has been called at least once for this class.
     *
     * Returns the result for the current accumulated loads.
     */
    def result(): Double = {
      iassert(totalWeight != 0.0)

      totalWeightedLoad / totalWeight
    }
  }

  /**
   * Halflife to determine the time-decayed weight of Measurements when calculating the aggregated
   * load for a Slice or a top key.
   *
   * TODO(<internal bug>): Add the halflife to [[LoadWatcherTargetConfig]].
   */
  private[assigner] val LOAD_WEIGHT_DECAYING_HALFLIFE: FiniteDuration = 10.seconds
}
