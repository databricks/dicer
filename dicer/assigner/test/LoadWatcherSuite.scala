package com.databricks.dicer.assigner

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

import com.databricks.caching.util.CachingErrorCode.TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{MetricUtils, Severity, TickerTime}
import com.databricks.dicer.assigner.config.InternalTargetConfig.LoadWatcherTargetConfig
import com.databricks.dicer.assigner.algorithm.LoadMap
import com.databricks.dicer.assigner.algorithm.LoadMap.{Entry, KeyLoadMap}
import com.databricks.dicer.assigner.LoadWatcher.{LOAD_WEIGHT_DECAYING_HALFLIFE, Measurement}
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.SliceletData.KeyLoad
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{AssignmentConsistencyMode, Assignment, SliceAssignment}
import com.databricks.dicer.external.{
  HighSliceKey,
  InfinitySliceKey,
  ResourceAddress,
  Slice,
  SliceKey
}
import com.databricks.testing.DatabricksTest

class LoadWatcherSuite extends DatabricksTest {

  private val DEFAULT_STATIC_CONFIG = LoadWatcher.StaticConfig(allowTopKeys = true)

  /**
   * Wrapper around [[LoadWatcher]] that checks invariants after all operations and includes
   * convenience methods for generating data and calling into the watcher.
   */
  private class LoadWatcherWrapper(
      config: LoadWatcherTargetConfig,
      staticConfig: LoadWatcher.StaticConfig = DEFAULT_STATIC_CONFIG) {

    var now: TickerTime = TickerTime.ofNanos(0)

    private val watcher = new LoadWatcher(config, staticConfig)

    /**
     * Returns the primary rate load map for `assignment` while verifying that the top keys in the
     * returned KeyLoadMap are correctly separated as outstanding Slices in the returned LoadMap.
     */
    def getPrimaryRateLoadMap(assignment: Assignment): Option[LoadMap] = {
      val result: Option[LoadMap] =
        watcher.getPrimaryRateLoadMap(now, assignment).map {
          case (loadMap: LoadMap, keyLoadMap: KeyLoadMap) =>
            verifyTopKeysInLoadMap(loadMap, keyLoadMap)
            loadMap
        }
      watcher.forTest.checkInvariants(now)
      result
    }

    def recordSliceletLoad(measurements: Seq[Measurement]): Unit = {
      watcher.reportLoad(now, measurements)
      watcher.forTest.checkInvariants(now)
    }

    def size: Int = {
      // Report empty load to make load watcher perform cleaning up based on `now`.
      watcher.reportLoad(now, primaryRateMeasurements = Seq.empty)
      watcher.forTest.checkInvariants(now)
      watcher.forTest.size
    }

    def createMeasurement(
        time: TickerTime,
        windowDuration: FiniteDuration,
        slice: Slice,
        resource: ResourceAddress,
        numReplicas: Int,
        load: Double,
        topKeys: Seq[KeyLoad]): Measurement = {
      watcher.createMeasurement(time, windowDuration, slice, resource, numReplicas, load, topKeys)
    }

    /**
     * Verifies that the top keys in `keyLoadMap` are correctly separated as outstanding Slices
     * in `loadMap`.
     */
    @throws[AssertionError]("If the assertion condition above is not true.")
    private def verifyTopKeysInLoadMap(loadMap: LoadMap, keyLoadMap: KeyLoadMap): Unit = {
      for (keyWithLoad <- keyLoadMap) {
        val (sliceKey, keyLoad): (SliceKey, Double) = keyWithLoad
        assert(
          loadMap.sliceMap.lookUp(sliceKey) ==
          LoadMap.Entry(Slice(sliceKey, sliceKey.successor()), keyLoad)
        )
      }
    }
  }

  /**
   * Asserts that `actualLoadMap` contains the same [[LoadMap.Entry]]s as `expectedLoadMap`, but the
   * actual load value for each entry can be deviating from `expectedLoadMap` for a small tolerance
   * range. This is because the production code and the test code can have different calculation
   * implementation for the actual and expected load respectively, where floating point calculation
   * error can arise.
   */
  private def assertEqualWithinTolerance(
      actualLoadMap: LoadMap,
      expectedLoadMap: LoadMap,
      floatingPointToleranceRange: Double = 1e-10): Unit = {
    val actualEntries: Vector[LoadMap.Entry] = actualLoadMap.sliceMap.entries
    val expectedEntries: Vector[LoadMap.Entry] = expectedLoadMap.sliceMap.entries

    for (zippedEntries <- actualEntries.zip(expectedEntries)) {
      val (actualEntry, expectedEntry): (LoadMap.Entry, LoadMap.Entry) = zippedEntries
      assert(actualEntry.slice == expectedEntry.slice)
      assert(
        Math.abs(actualEntry.load - expectedEntry.load) < floatingPointToleranceRange,
        s"Actual load deviates from expected load for more than float point calculation " +
        s"tolerance range $floatingPointToleranceRange: Slice=${actualEntry.slice}, " +
        s"actualLoad=${actualEntry.load}, expectedLoad=${expectedEntry.load}."
      )
    }
  }

  /**
   * Returns the number of alerts filed because of the sum of top key loads exceeds the Slice laod.
   */
  private def getTopKeysAlertCount(slice: Slice): Int = {
    MetricUtils.getPrefixLoggerErrorCount(
      Severity.DEGRADED,
      TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD,
      prefix = s"slice=$slice"
    )
  }

  /**
   * Generate a random finite duration between [lowInclusive, highExclusive). Note that this may be
   * prone to double rounding effects, thus should generally only be used when `lowInclusive` and
   * `highExclusive` are sufficiently far apart.
   */
  private def randomDurationInRange(
      lowInclusive: FiniteDuration,
      highExclusive: FiniteDuration,
      random: Random): FiniteDuration = {
    randomInRange(lowInclusive.toNanos, highExclusive.toNanos, random).nanoseconds
  }

  test("Measurement validation") {
    // Test plan: Verify that Measurement rejects invalid constructor parameters.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )

    assertThrow[IllegalArgumentException]("windowDuration must be non-negative") {
      watcher.createMeasurement(
        time = TickerTime.ofNanos(1.day.toNanos),
        windowDuration = -2.seconds,
        slice = Slice.FULL,
        resource = "pod0",
        numReplicas = 1,
        load = 20,
        topKeys = Seq.empty
      )
    }

    assertThrow[IllegalArgumentException]("numReplicas must be positive") {
      watcher.createMeasurement(
        time = TickerTime.ofNanos(1.day.toNanos),
        windowDuration = 2.minutes,
        slice = Slice.FULL,
        resource = "pod0",
        numReplicas = 0,
        load = 20,
        topKeys = Seq.empty
      )
    }

    assertThrow[IllegalArgumentException]("numReplicas must be positive") {
      watcher.createMeasurement(
        time = TickerTime.ofNanos(1.day.toNanos),
        windowDuration = 2.minutes,
        slice = Slice.FULL,
        resource = "pod0",
        numReplicas = -10,
        load = 20,
        topKeys = Seq.empty
      )
    }

    // Invalid load.
    assertThrow[IllegalArgumentException]("must be non-negative") {
      watcher.createMeasurement(
        time = TickerTime.ofNanos(1.day.toNanos),
        windowDuration = 2.minutes,
        slice = Slice.FULL,
        resource = "pod0",
        numReplicas = 10,
        load = -20,
        topKeys = Seq.empty
      )
    }
  }

  test("Simulate initial assignment") {
    // Test plan: Supply an initial assignment (one with no historical load data) to the watcher.
    // Verify that None is returned by `getPrimaryRateLoadMap` until load has been recorded for
    // every Slice in the assignment.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )

    // Try to record empty measurements. Just verify this doesn't throw and doesn't break the
    // watcher's invariants or change the watcher's state.
    watcher.recordSliceletLoad(measurements = Seq.empty)
    assert(watcher.size == 0)

    // Create an assignment with two Slices and no historical load data.
    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "fili") @@ (2 ## 42) -> Seq("pod0")).clearPrimaryRateLoad(),
      ("fili".andGreater @@ (2 ## 42) -> Seq("pod1")).clearPrimaryRateLoad()
    )

    // Until load has been recorded for all Slices, `getPrimaryRateLoadMap` should return None.
    assert(watcher.getPrimaryRateLoadMap(assignment).isEmpty)
    val now: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "Pod0",
          numReplicas = 1,
          load = 47,
          topKeys = Seq.empty
        )
      )
    )
    assert(watcher.getPrimaryRateLoadMap(assignment).isEmpty)

    // Record load for the second Slice. Now `getPrimaryRateLoadMap` should return a load map.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "fili".andGreater,
          resource = "Pod0",
          numReplicas = 1,
          load = 53,
          topKeys = Seq.empty
        )
      )
    )
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 47),
          Entry("fili".andGreater, 53)
        )
        .build()
    )
  }

  test("Fall back to historical data") {
    // Test plan: simulate a recent assignment change where the new assignment has historical data
    // and where reports for individual Slices are:
    //  - Missing
    //  - Cover too short a window
    //  - Too old
    //  - Are viable!
    // Verify that in all cases but the last, the watcher falls back to historical data from the
    // assignment.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "fili") @@ 42 -> Seq("pod0")).withPrimaryRateLoad(10),
      ("fili".andGreater @@ 42 -> Seq("pod0")).withPrimaryRateLoad(20)
    )

    // Before any load reports are received, the watcher should return just historical data.
    var mapOpt: Option[LoadMap] = watcher.getPrimaryRateLoadMap(assignment)
    assertEqualWithinTolerance(
      mapOpt.get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 10),
          Entry("fili".andGreater, 20)
        )
        .build()
    )

    // Supply a load report for the first Slice that covers a window that is too short (30 seconds
    // long).
    watcher.now += 30.seconds
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = 30.seconds,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 30,
          topKeys = Seq.empty
        )
      )
    )
    mapOpt = watcher.getPrimaryRateLoadMap(assignment)
    assertEqualWithinTolerance(
      mapOpt.get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 10), // Still using historical data.
          Entry("fili".andGreater, 20) // Historical data.
        )
        .build()
    )

    // Supply a load report for the second Slice that covers a window that is long enough.
    watcher.now += 30.seconds
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "po0",
          numReplicas = 1,
          load = 40,
          topKeys = Seq.empty
        )
      )
    )
    mapOpt = watcher.getPrimaryRateLoadMap(assignment)
    assertEqualWithinTolerance(
      mapOpt.get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 40), // Using load report from Slicelet!
          Entry("fili".andGreater, 20) // Historical data.
        )
        .build()
    )

    // Let time pass without providing any reports. The watcher should fall back to historical data
    // again.
    watcher.now += 2.minutes
    mapOpt = watcher.getPrimaryRateLoadMap(assignment)
    assertEqualWithinTolerance(
      mapOpt.get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 10), // Using historical data again.
          Entry("fili".andGreater, 20) // Historical data.
        )
        .build()
    )

    // Finally, supply reports for both Slices, where the report for the second Slice is viable but
    // the first is not.
    watcher.now += 30.seconds
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - 15.seconds,
          windowDuration = 30.seconds,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 50,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          watcher.now,
          2.minutes,
          "fili".andGreater,
          "pod0",
          1,
          60,
          topKeys = Seq.empty
        )
      )
    )
    mapOpt = watcher.getPrimaryRateLoadMap(assignment)
    assertEqualWithinTolerance(
      mapOpt.get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 10), // Historical data.
          Entry("fili".andGreater, 60) // Using load report from Slicelet!
        )
        .build()
    )
  }

  test("recordSliceletLoad preserves only latest value for a Slice replica") {
    // Test plan: create a load watcher configured to aggregate primary-rate load from Slicelets.
    // Provide multiple load reports for the same Slices. Verify that only the reports covering the
    // most recent windows are preserved.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      ("" -- "fili") @@ 42 -> Seq("pod0"),
      "fili".andGreater @@ 42 -> Seq("pod0")
    )

    // Supply two load reports covering both assigned Slices. Each load report includes one "winner"
    // with a timestamp higher than the report from the other report for the corresponding Slice.
    val now: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 10.seconds,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 1,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = now - 5.seconds,
          windowDuration = 1.minute,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 2,
          topKeys = Seq.empty
        )
      )
    )
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 5.seconds,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 3,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = now - 10.seconds,
          windowDuration = 1.minute,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 4,
          topKeys = Seq.empty
        )
      )
    )
    // Check that the "winners" are included in the load map.
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 3),
          Entry("fili".andGreater, 2)
        )
        .build()
    )
  }

  test("Too short reports are excluded") {
    // Test plan: supply a load report with a window shorter than the minimum duration. Verify that
    // the report is excluded from the load map.

    val minDuration: FiniteDuration = 10.seconds
    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration, maxAge = 1.minute, useTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "fili") @@ 42 -> Seq("pod0")).clearPrimaryRateLoad(),
      ("fili".andGreater @@ 42 -> Seq("pod0")).clearPrimaryRateLoad()
    )

    // Try to initialize the watcher with a load report with a window shorter than the minimum
    // duration.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = 1.millisecond,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 1,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = minDuration - 1.millisecond,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 2,
          topKeys = Seq.empty
        )
      )
    )
    // The reports are not tracked by watcher.
    assert(watcher.size == 0)
    assert(watcher.getPrimaryRateLoadMap(assignment).isEmpty)

    // Initialize the watcher with a load report containing windows as long as the minimum required
    // duration.
    val startTime1: TickerTime = watcher.now
    watcher.now += minDuration
    val endTime1: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = endTime1,
          windowDuration = endTime1 - startTime1,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 1,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = endTime1,
          windowDuration = endTime1 - startTime1,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 2,
          topKeys = Seq.empty
        )
      )
    )
    // Check that the load map contains the initial load.
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- "fili", 1), Entry("fili".andGreater, 2))
        .build()
    )

    // Supply a fresh load report where one of the windows is shorter than the minimum duration and
    // the other is longer.
    val startTime2: TickerTime = watcher.now
    watcher.now += minDuration - 1.second
    val endTime2: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = endTime2,
          windowDuration = endTime2 - startTime1,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 3,
          topKeys = Seq.empty
        ),
        // Duration too short.
        watcher.createMeasurement(
          time = endTime2,
          windowDuration = endTime2 - startTime2,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 4,
          topKeys = Seq.empty
        )
      )
    )
    // Check that the load map contains the new load measurement for the first Slice but the initial
    // load measurement for the second Slice, as the latest report for the second Slice is covers
    // too short a window.
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 3),
          Entry("fili".andGreater, 2)
        )
        .build()
    )
  }

  test("Elements are cleaned up after maxAge") {
    // Test plan: simulate the case where load reports are supplied for an assignment and then its
    // successor, which has different Slice boundaries. Verify that measurements for the old
    // assignment are garbage collected after `config.maxAge`.

    val maxAge: FiniteDuration = 10.minutes
    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge, useTopKeys = true)
    )
    val assignment1: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "fili") @@ 42 -> Seq("pod0")).withPrimaryRateLoad(11),
      ("fili".andGreater @@ 42 -> Seq("pod0")).withPrimaryRateLoad(12)
    )
    // Supply some load information about Slices in the first assignment.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 1,
          load = 1,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 1.second,
          windowDuration = 1.minute,
          slice = "fili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 2,
          topKeys = Seq.empty
        )
      )
    )
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment1).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 1),
          Entry("fili".andGreater, 2)
        )
        .build()
    )

    // Advance the clock (not quite by maxAge yet!) and supply a load report for the second
    // assignment.
    watcher.now += maxAge - 1.minute
    val assignment2: Assignment = createAssignment(
      43,
      AssignmentConsistencyMode.Affinity,
      ("" -- "kili") @@ 43 -> Seq("pod0"),
      "kili".andGreater @@ 43 -> Seq("pod0")
    )
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "kili",
          resource = "pod0",
          numReplicas = 1,
          load = 3,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 1.second,
          windowDuration = 1.minute,
          slice = "kili".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 4,
          topKeys = Seq.empty
        )
      )
    )
    assertEqualWithinTolerance(
      watcher
        .getPrimaryRateLoadMap(assignment2)
        .get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "kili", 3),
          Entry("kili".andGreater, 4)
        )
        .build()
    )

    // As of now, measurements from both assignments should still be tracked.
    assert(watcher.size == 4)

    // When we advance to the maxAge, the measurements from the first assignment should be garbage
    // collected.
    watcher.now += 1.minute
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment2).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "kili", 3),
          Entry("kili".andGreater, 4)
        )
        .build()
    )
    assert(watcher.size == 2)

    // Double check the Measurements for the first assignment are expired by trying to get LoadMap
    // for the first assignment. Should fall back to historical data.
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment1).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "fili", 11),
          Entry("fili" -- ∞, 12)
        )
        .build()
    )
  }

  test("allowTopKeys and useTopKeys conf is respected") {
    // Test plan: verify that top keys information is incorporated into the LoadMap depending on the
    // value of `LoadWatcher.StaticConfig.allowTopKeys` and `LoadWatcherTargetConfig.useTopKeys` -
    // both must be true.
    val watcher1 = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true),
      LoadWatcher.StaticConfig(allowTopKeys = false)
    )

    // Create an assignment with two Slices and no historical load data.
    val assignment: Assignment = createAssignment(
      12,
      AssignmentConsistencyMode.Affinity,
      ("".andGreater @@ 12 -> Seq("pod0")).clearPrimaryRateLoad()
    )

    val now1: TickerTime = watcher1.now
    watcher1.recordSliceletLoad(
      Seq(
        watcher1.createMeasurement(
          time = now1 - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 70.0,
          topKeys = Seq(KeyLoad("fili", 42))
        )
      )
    )
    // `topKeys` are ignored with `allowTopKeys = false`.
    assertEqualWithinTolerance(
      watcher1.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("".andGreater, 70.0)
        )
        .build()
    )

    val watcher2 = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = false),
      LoadWatcher.StaticConfig(allowTopKeys = true)
    )
    val now2: TickerTime = watcher2.now
    watcher2.recordSliceletLoad(
      Seq(
        watcher2.createMeasurement(
          time = now2 - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 70.0,
          topKeys = Seq(KeyLoad("fili", 42.0))
        )
      )
    )
    // `topKeys` are ignored with `useTopKeys = false`.
    assertEqualWithinTolerance(
      watcher2.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("".andGreater, 70.0)
        )
        .build()
    )

    val watcher3 = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true),
      LoadWatcher.StaticConfig(allowTopKeys = true)
    )
    val now3: TickerTime = watcher3.now
    watcher3.recordSliceletLoad(
      Seq(
        watcher3.createMeasurement(
          time = now3 - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 70.0,
          topKeys = Seq(KeyLoad("fili", 42.0))
        )
      )
    )
    // `topKeys` are incorporated with `useTopKeys = true` and `allowTopKeys = true`.
    assertEqualWithinTolerance(
      watcher3.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("".andGreater, 28.0),
          SortedMap(toSliceKey("fili") -> 42.0)
        )
        .build()
    )
  }

  test("Top keys load scaled if exceeding Slice load") {
    // Test plan: verify that top keys information is scaled down if the top keys load exceeds the
    // total Slice load.
    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true),
      LoadWatcher.StaticConfig(allowTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      "".andGreater @@ 42 -> Seq("pod0")
    )
    val now: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 70.0,
          topKeys = Seq(KeyLoad("fili", 80.0), KeyLoad("foo", 60.0))
        )
      )
    )
    // `topKeys` are scaled down to half so that they sum to the total Slice load.
    assertEqualWithinTolerance(
      watcher
        .getPrimaryRateLoadMap(assignment)
        .get,
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("".andGreater, 0.0),
          SortedMap(toSliceKey("fili") -> 40.0, toSliceKey("foo") -> 30.0)
        )
        .build()
    )
  }

  test("Alert if top keys load exceeds Slice load") {
    // Test plan: verify that when the top keys load exceeds the total Slice load, an alert is
    // fired. This alert is not fired if top keys load is near `Double.MinPositiveValue`.

    val initialCount: Int = getTopKeysAlertCount("" -- ∞)
    val watcher1 = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true),
      LoadWatcher.StaticConfig(allowTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      42,
      AssignmentConsistencyMode.Affinity,
      "".andGreater @@ 42 -> Seq("pod0")
    )
    val now: TickerTime = watcher1.now

    watcher1.recordSliceletLoad(
      Seq(
        watcher1.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = 70.0,
          topKeys = Seq(KeyLoad("fili", 80.0), KeyLoad("foo", 60.0))
        )
      )
    )
    // Alert should fire because top keys load exceeds total Slice load.
    watcher1.getPrimaryRateLoadMap(assignment)
    val newCount: Int = getTopKeysAlertCount("" -- ∞)
    assert(newCount == initialCount + 1)

    val watcher2 = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true),
      LoadWatcher.StaticConfig(allowTopKeys = true)
    )
    // If load values are very small, alert should not fire.
    watcher2.recordSliceletLoad(
      Seq(
        watcher2.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "".andGreater,
          resource = "pod0",
          numReplicas = 1,
          load = Double.MinPositiveValue,
          topKeys = Seq(
            KeyLoad("fili", Double.MinPositiveValue * 10),
            KeyLoad("foo", Double.MinPositiveValue)
          )
        )
      )
    )
    watcher2.getPrimaryRateLoadMap(assignment)
    assert(getTopKeysAlertCount("" -- ∞) == newCount)
  }

  test("Loads from each slicelet are scaled based on the number of replicas") {
    // Test plan: Verify that the load reported from slicelets will be scaled based on the number
    // of replicas when this load report was generated. Verify this by providing the load watcher
    // with various load reports for different Slices with different number of replicas, loads, and
    // top keys, then checking getPrimaryRateLoadMap() returns loads that take the number of
    // replicas into consideration. Only providing one load report for each Slice for simpler
    // reasoning for this test case.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )

    val now: TickerTime = watcher.now
    watcher.recordSliceletLoad(
      Seq(
        // 1 replicas, no top keys.
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "dori",
          resource = "pod0",
          numReplicas = 1,
          load = 47,
          topKeys = Seq.empty
        ),
        // 2 replicas, no top keys.
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 2.minutes,
          slice = "dori" -- "fili",
          resource = "pod1",
          numReplicas = 2,
          load = 42,
          topKeys = Seq.empty
        ),
        // 1 replica, with top keys.
        watcher.createMeasurement(
          time = now - 2.second,
          windowDuration = 2.minutes,
          slice = "fili" -- "nori",
          resource = "pod2",
          numReplicas = 1,
          load = 42,
          topKeys = Seq(KeyLoad("fili", 10), KeyLoad("kili", 20))
        ),
        // 3 replicas, with top keys.
        watcher.createMeasurement(
          time = now - 2.second,
          windowDuration = 2.minutes,
          slice = "nori" -- ∞,
          resource = "pod3",
          numReplicas = 3,
          load = 42,
          topKeys = Seq(KeyLoad("nori", 10), KeyLoad("zala", 20))
        )
      )
    )

    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "dori") @@ (2 ## 42) -> Seq("pod0")).clearPrimaryRateLoad(),
      (("dori" -- "fili") @@ (2 ## 42) -> Seq("pod1")).clearPrimaryRateLoad(),
      (("fili" -- "nori") @@ (2 ## 42) -> Seq("pod2")).clearPrimaryRateLoad(),
      (("nori" -- ∞) @@ (2 ## 42) -> Seq("pod3")).clearPrimaryRateLoad()
    )

    val expectedLoadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- "dori", 47)
        )
        .putLoad(
          Entry("dori" -- "fili", 42 * 2)
        )
        .putLoad(
          Entry("fili" -- "nori", 12), // 42 - 10 - 20 = 12.
          KeyLoadMap(identityKey("fili") -> 10, identityKey("kili") -> 20)
        )
        .putLoad(
          LoadMap.Entry("nori" -- ∞, 36), // (42 - 10 - 20) * 3 = 36.
          KeyLoadMap(
            identityKey("nori") -> 30, // 10 * 3.
            identityKey("zala") -> 60 // 20 * 3.
          )
        )
        .build()

    // The accumulated load doesn't change with the passage of time.
    for (timeInterval: FiniteDuration <- Seq(
        Duration.Zero,
        1.nanosecond,
        10.milliseconds,
        5.seconds
      )) {
      watcher.now += timeInterval
      assertEqualWithinTolerance(
        watcher
          .getPrimaryRateLoadMap(assignment)
          .get,
        expectedLoadMap
      )
    }
  }

  test("Measurements from different resources are correctly accumulated") {
    // Test plan: Verify that the LoadWatcher can accumulate loads from multiple resources for the
    // same Slice. Verify this by providing the load watcher with multiple measurements from
    // different resources, and verifying getPrimaryRateLoadMap() returns expected results.
    // Measurements for the same Slice are generated at the same time for simpler calculation and
    // reasoning of the expected results.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )

    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (("" -- "fili") @@ (2 ## 42) -> Seq("pod0", "pod1", "pod2")).clearPrimaryRateLoad(),
      (("fili" -- ∞) @@ (2 ## 42) -> Seq("pod1", "pod2")).clearPrimaryRateLoad()
    )

    val now: TickerTime = watcher.now

    // Put loads for Slice ["", "fili"). All measurements are generated at the same time. Set the
    // max number of replicas to be 3 (for ["", "fili")) so that no measurement will be evicted.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "pod0",
          numReplicas = 3,
          load = 16,
          topKeys = Seq(KeyLoad("dori", 10))
        ),
        watcher.createMeasurement(
          time = now - 1.second,
          windowDuration = 1.minute,
          slice = "" -- "fili",
          resource = "pod1",
          numReplicas = 2,
          load = 43,
          topKeys = Seq(KeyLoad("dori", 20))
        ),
        watcher.createMeasurement(
          time = now - 1.seconds,
          windowDuration = 2.minutes,
          slice = "" -- "fili",
          resource = "pod2",
          numReplicas = 2,
          load = 23,
          topKeys = Seq(KeyLoad("dori", 10))
        )
      )
    )

    // Put loads for Slice ["fili", ∞). All measurements are generated at the same time. Maximum
    // number of replicas is 2 so no measurement will be evicted.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = now - 5.second,
          windowDuration = 3.minute,
          slice = "fili" -- ∞,
          resource = "pod1",
          numReplicas = 2,
          load = 47,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = now - 5.second,
          windowDuration = 2.minute,
          slice = "fili" -- ∞,
          resource = "pod2",
          numReplicas = 2,
          load = 43,
          topKeys = Seq.empty
        )
      )
    )

    // Measurements for the same Slice are generated at the same time, so the time-decayed weights
    // are ignored when calculating the expected result.
    val expectedLoadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          // (16 * 3 + 43 * 2 + 23 * 2) / 3 - 30 = 30.
          LoadMap.Entry("" -- "fili", 30),
          // (10 * 3 + 20 * 2 + 10 * 2) / 3 = 30.
          KeyLoadMap(identityKey("dori") -> 30)
        )
        .putLoad(
          // (47 * 2 + 43 * 2) / 2 = 90.
          LoadMap.Entry("fili" -- ∞, 90)
        )
        .build()

    // The accumulated load doesn't change with the passage of time.
    for (timeInterval: FiniteDuration <- Seq(
        Duration.Zero,
        1.nanosecond,
        10.milliseconds,
        5.seconds
      )) {
      watcher.now += timeInterval
      assertEqualWithinTolerance(
        watcher
          .getPrimaryRateLoadMap(assignment)
          .get,
        expectedLoadMap
      )
    }
  }

  test("Newer generated measurements have heavier weights in load accumulation") {
    // Test plan: Verify that measurements from different resources have weights that will decay
    // with time when calculating the load for a Slice. Verify this by supplying the load watcher
    // with measurements that are generated at different times, and verifying the accumulated load
    // is "closer" to the newer measurement.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 10.minute, useTopKeys = true)
    )

    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (Slice.FULL @@ (2 ## 42) -> Seq("pod0", "pod1")).clearPrimaryRateLoad()
    )

    // Two measurements: one very new and one very old.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          // This measurement is very new.
          time = watcher.now,
          windowDuration = 3.minute,
          slice = Slice.FULL,
          resource = "pod0",
          numReplicas = 2,
          load = 5,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          // This measurement is much older (10 halflifes old).
          time = watcher.now - LOAD_WEIGHT_DECAYING_HALFLIFE * 10,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 2,
          load = 15,
          topKeys = Seq.empty
        )
      )
    )

    // Measurement from "pod0" is quite new and that from "pod1" is very old. The accumulated load
    // should be very close to the load from "pod0" (5.0 * 2 = 10.0).
    {
      val load: Double = watcher.getPrimaryRateLoadMap(assignment).get.getLoad(Slice.FULL)
      assert(load < 10.1)
      assert(load > 10.0)
    }

    // Expire all previous measurements.
    watcher.now += 1.hour

    // Two measurements: one very old and one very new, with a top key.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          // This measurement rather old (15 times of halflife).
          time = watcher.now - LOAD_WEIGHT_DECAYING_HALFLIFE * 15,
          windowDuration = 3.minute,
          slice = Slice.FULL,
          resource = "pod0",
          numReplicas = 2,
          load = 10,
          topKeys = Seq(KeyLoad("dori", 5))
        ),
        watcher.createMeasurement(
          // This measurement is quite new.
          time = watcher.now - 1.second,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 3,
          load = 30,
          topKeys = Seq(KeyLoad("dori", 15))
        )
      )
    )

    {
      // Same as the previous case, the weighed accumulated load should be close to the scaled load
      // from "pod1" (30 * 3 = 90.0), which is much newer than that from "pod0".
      val load: Double = watcher.getPrimaryRateLoadMap(assignment).get.getLoad(Slice.FULL)
      assert(load < 90.0)
      assert(load > 89.0)

      // The top key load should be close to 15 * 3 = 45.
      val topKeyLoad: Double = watcher
        .getPrimaryRateLoadMap(assignment)
        .get
        .getLoad(Slice("dori", identityKey("dori").successor()))
      assert(topKeyLoad < 45.0)
      assert(topKeyLoad > 44.0)
    }

    // Expire all previous measurements.
    watcher.now += 1.hour

    // Two measurements with similar freshness.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - LOAD_WEIGHT_DECAYING_HALFLIFE,
          windowDuration = 3.minute,
          slice = Slice.FULL,
          resource = "pod0",
          numReplicas = 2,
          load = 20,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          // Slightly newer than measurement from "pod0".
          time = watcher.now - (LOAD_WEIGHT_DECAYING_HALFLIFE - 1.second),
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 2,
          load = 30,
          topKeys = Seq.empty
        )
      )
    )

    // Measurement from "pod1" is slightly newer than that from "pod0", so the accumulated load
    // should be nearly their "unweighted" average load, but slightly closer to the load from
    // "pod1".
    {
      val unweighedAverageLoad: Double = 50 // (20.0 * 2 + 30.0 * 2) / 2.
      val load: Double = watcher.getPrimaryRateLoadMap(assignment).get.getLoad(Slice.FULL)
      assert(load > unweighedAverageLoad)
      assert(load < unweighedAverageLoad + 1)
    }

    // Expire all previous measurements.
    watcher.now += 1.hour

    // Two measurements with moderate freshness difference.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - LOAD_WEIGHT_DECAYING_HALFLIFE,
          windowDuration = 3.minute,
          slice = Slice.FULL,
          resource = "pod0",
          numReplicas = 2,
          load = 20,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - LOAD_WEIGHT_DECAYING_HALFLIFE * 2,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 2,
          load = 30,
          topKeys = Seq.empty
        )
      )
    )

    {
      // Measurement from pod0 is one halflife old, weight = 2 ^ (-1) = 0.5.
      // Measurement from pod1 is two halflifes old, weight = 2 ^ (-2) = 0.25.
      val expectedResult: Double = (0.5 * 20 * 2 + 0.25 * 30 * 2) / (0.5 + 0.25)
      val load: Double = watcher.getPrimaryRateLoadMap(assignment).get.getLoad(Slice.FULL)
      assert(load == expectedResult)

      // In addition, verify that the accumulated load doesn't change with time (before any
      // measurement expires), even if they have different weights.
      for (timeInterval: FiniteDuration <- Seq(
          Duration.Zero,
          1.nanosecond,
          10.milliseconds,
          5.seconds
        )) {
        watcher.now += timeInterval
        assert(
          // Allow some floating point errors.
          Math.abs(watcher.getPrimaryRateLoadMap(assignment).get.getLoad(Slice.FULL) - load) < 1e-10
        )
      }
    }
  }

  test("Measurements from different resources randomized") {
    // Test plan: Verify that the load watcher correctly calculates load from measurements from
    // different resources and generated at different times. Verify this by randomly generating
    // measurements with arbitrary slice load, number of replicas, top keys, and time for random
    // Slices. Verify getPrimaryRateLoadMap() returns expected results. For simpler reasoning,
    // all measurements don't contain other corner cases (e.g. key load exceeding slice load,
    // measurement expires).

    val seed: Long = Random.nextLong()
    logger.info(s"Using seed $seed")
    val random = new Random(seed)

    for (_ <- 0 until 100) { // 100 trials.
      // Create a watcher while randomly choosing whether using top keys.
      val useTopKeys: Boolean = random.nextBoolean()
      val watcher = new LoadWatcherWrapper(
        LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys)
      )

      // Tracking the expected load for Slices when supplying the random test Measurements to the
      // load watcher.
      val expectedLoadMapBuilder = LoadMap.newBuilder()

      // Number of Slices in the assignment and the measurements within [1, 10].
      val numSlices: Int = 1 + random.nextInt(10)
      val sliceAssignmentsBuilder = Vector.newBuilder[SliceAssignment]

      // During each loop, we
      // 1. Construct a new Slice to be included in the test Measurements (and test assignment).
      // 2. Create random test Measurements for this Slice and supply it to the load `watcher`.
      // 3. Track the expected result for the test Measurements `expectedLoadMapBuilder`.
      // 4. Create a SliceAssignment for the Slice and track it in `sliceAssignmentsBuilder`, so we
      //    can create an assignment whose Slice boundaries match the test Measurements.
      var lowSliceKeyLong: Long = 0 // Tracking the low slice boundary.
      for (sliceIndex: Int <- 0 until numSlices) {
        // Random high slice boundary.
        val highSliceKeyLong: Long =
          randomInRange(lowSliceKeyLong + 1, lowSliceKeyLong + 1000, random)

        val lowSliceKey: SliceKey =
          // Ignore `lowSliceKeyLong` and use SliceKey.Min for the first Slice.
          if (sliceIndex == 0) SliceKey.MIN else toSliceKey(lowSliceKeyLong)
        // Ignore `highSliceKeyLong` and use infinite high SliceKey for the last Slice.
        val highSliceKey: HighSliceKey =
          if (sliceIndex == numSlices - 1) InfinitySliceKey else toSliceKey(highSliceKeyLong)
        val slice: Slice = Slice(lowSliceKey, highSliceKey)

        sliceAssignmentsBuilder += createRandomSliceAssignment(
          slice,
          subslices = Vector.empty,
          generation = 2 ## 42,
          random
        )

        // Number of measurements within [1, 10].
        val numMeasurements: Int = 1 + random.nextInt(10)
        // Different resource addresses for each measurement.
        val resources: Seq[ResourceAddress] = (0 until numMeasurements).map { index: Int =>
          toResourceAddress(s"pod$index")
        }

        // Each measurement contain the same set of top keys. No more than 3 top keys for
        // test efficiency.
        val topKeys: Seq[SliceKey] =
          (0 until 3)
            .map { _ =>
              // random topKeys within `slice`.
              toSliceKey(randomInRange(lowSliceKeyLong, highSliceKeyLong, random))
            }
            .toSet
            .toSeq

        // Variables used to track the randomly-generated loads and to construct expected results
        // for `slice`.
        var weightedSliceLoadSumIncludingKeys: Double = 0
        val weightedKeyLoadSumMap = mutable.Map[SliceKey, Double]()
        for (sliceKey: SliceKey <- topKeys) {
          weightedKeyLoadSumMap(sliceKey) = 0.0
        }
        var weightSum: Double = 0

        for (resource: ResourceAddress <- resources) {
          // Randomly create Measurement data for each resource.

          // Random slice replica load within [100, 200).
          val sliceReplicaLoad: Double = 100.0 + random.nextDouble() * 100
          // Generate numReplicas in the Measurement.
          val numReplicas: Int =
            if (resource == resources.head) {
              // Make sure at least one Measurement contains a numReplicas no less than the number
              // of Measurements, so no Measurement will be evicted.
              resources.size
            } else {
              // random number of replicas within [1, 10].
              1 + random.nextInt(10)
            }
          val topKeyLoads: Seq[KeyLoad] =
            topKeys.map { sliceKey: SliceKey =>
              // Load for each top key within [20, 30). This guarantees the top key load sum in each
              // measurement is less than 3 * 30 = 90 < 100 <= total slice load.
              KeyLoad(sliceKey, 20 + random.nextDouble() * 10)
            }
          // random age of measurement within [0, 10) seconds.
          val measurementAge: FiniteDuration =
            random.nextInt(10.seconds.toMillis.toInt).milliseconds

          watcher.recordSliceletLoad(
            Seq(
              watcher.createMeasurement(
                time = watcher.now - measurementAge,
                windowDuration = 1.minute,
                slice,
                resource,
                numReplicas,
                sliceReplicaLoad,
                topKeyLoads
              )
            )
          )

          // Track the randomly generated measurement information needed to calculate the expected
          // results.
          val weight: Double =
            Math.pow(
              2.0,
              -measurementAge.toMillis.toDouble / LOAD_WEIGHT_DECAYING_HALFLIFE.toMillis
            )
          weightSum += weight
          weightedSliceLoadSumIncludingKeys += sliceReplicaLoad * numReplicas * weight
          for (keyLoad: KeyLoad <- topKeyLoads) {
            weightedKeyLoadSumMap(keyLoad.key) +=
            keyLoad.underestimatedPrimaryRateLoad * numReplicas * weight
          }
        }

        // Construct expected results using the information tracked before.
        val expectedKeyLoadMap: KeyLoadMap =
          if (useTopKeys) {
            KeyLoadMap(weightedKeyLoadSumMap.toSeq.map {
              case (sliceKey: SliceKey, weightedKeyLoadSum: Double) =>
                (sliceKey, weightedKeyLoadSum / weightSum)
            }: _*)
          } else {
            KeyLoadMap.empty
          }
        val expectedSliceLoadExcludingKeys: Double =
          weightedSliceLoadSumIncludingKeys / weightSum - expectedKeyLoadMap.values.sum

        expectedLoadMapBuilder.putLoad(
          LoadMap.Entry(slice, expectedSliceLoadExcludingKeys),
          expectedKeyLoadMap
        )

        lowSliceKeyLong = highSliceKeyLong
      }

      val assignment: Assignment = createAssignment(
        generation = 2 ## 42,
        AssignmentConsistencyMode.Affinity,
        sliceAssignmentsBuilder.result()
      )

      // Verify expected results. The accumulated load doesn't change with the passage of time
      // (before any measurement expires).
      for (timeInterval: FiniteDuration <- Seq(
          Duration.Zero,
          1.nanosecond,
          10.milliseconds,
          5.seconds
        )) {
        watcher.now += timeInterval
        assertEqualWithinTolerance(
          watcher
            .getPrimaryRateLoadMap(assignment)
            .get,
          expectedLoadMapBuilder
            .build()
        )
      }
    }
  }

  test("Top keys that only appear in some measurements") {
    // Test plan: Verify that for the uncommon case where a top key is only presented in a part of
    // the measurements, the accumulated load for the top key will be calculated based on only the
    // measurements containing it, and the measurements that don't contain it will not be
    // considered in calculation.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 10.minute, useTopKeys = true)
    )
    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (Slice.FULL @@ (2 ## 42) -> Seq("pod0", "pod1")).clearPrimaryRateLoad()
    )

    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - 5.second,
          windowDuration = 3.minute,
          slice = Slice.FULL,
          resource = "pod0",
          numReplicas = 2,
          load = 50,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 5.second,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 2,
          load = 100,
          topKeys = Seq(KeyLoad("dori", 42)) // "dori" not presented in measurement from "pod0".
        )
      )
    )

    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        // The key load for "dori" is (42 * 2 + 0 * 2) / 2 = 42, as the measurement from "pod0"
        // doesn't contain top key information for "dori" and we estimate its load being 0.
        // The final slice load excluding top keys is (50 * 2 + 100 * 2) / 2 - 42 = 108.
        .putLoad(Entry(Slice.FULL, 108), KeyLoadMap(identityKey("dori") -> 42))
        .build()
    )
  }

  test("Number of tracked measurements for each Slice doesn't exceed max number of replicas") {
    // Test plan: Verify that the number of tracked measurements for each Slice doesn't exceed
    // the maximum number of replicas reported by the measurements it currently tracked.

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 10.minute, useTopKeys = true)
    )

    val assignment: Assignment = createAssignment(
      generation = 2 ## 42,
      AssignmentConsistencyMode.Affinity,
      (Slice.FULL @@ (2 ## 42) -> Seq("pod1", "pod2", "pod3")).clearPrimaryRateLoad()
    )

    // Supply 4 measurements from different resources who have very similar but different time of
    // generation, so we can verify the cleaning up while easily calculating the expected result.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod1",
          numReplicas = 1,
          load = 10,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 1.nanosecond,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod2",
          numReplicas = 1,
          load = 20,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 2.nanoseconds,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod3",
          numReplicas = 3,
          load = 30,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 3.nanoseconds,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod4",
          numReplicas = 3,
          load = 40,
          topKeys = Seq.empty
        )
      )
    )

    // The Measurement from "pod4" will be evicted, as the maximum number of replicas is 3 it is
    // oldest among the 4 measurements.
    assert(watcher.size == 3)
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        // (10 * 1 + 20 * 1 + 30 * 3) / 3 = 40. The measurement from "pod4", who has earliest time,
        // is cleaned to keep max size to be 3.
        .putLoad(Entry(Slice.FULL, 40))
        .build()
    )

    // Advance the time to a point that should expire pod4's measurement. But as it's not tracked,
    // this should take no effect.
    watcher.now += 10.minutes - 2.nanoseconds
    assert(watcher.size == 3)
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(Entry(Slice.FULL, 40))
        .build()
    )

    // Expire the measurement from pod3. Now the maximum number of replicas is 1, so even the
    // measurement from pod2 will be evicted.
    watcher.now += 1.nanoseconds
    assert(watcher.size == 1)
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        .putLoad(Entry(Slice.FULL, 10))
        .build()
    )

    // Again, supply the load watcher with a list of measurements (max numReplicas = 2), and only
    // the 2 newest measurements will remain.
    watcher.recordSliceletLoad(
      Seq(
        watcher.createMeasurement(
          time = watcher.now - 3.nanoseconds,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod5",
          numReplicas = 2,
          load = 50,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 2.nanosecond,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod6",
          numReplicas = 2,
          load = 60,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now - 1.nanoseconds,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod7",
          numReplicas = 2,
          load = 70,
          topKeys = Seq.empty
        ),
        watcher.createMeasurement(
          time = watcher.now,
          windowDuration = 2.minute,
          slice = Slice.FULL,
          resource = "pod8",
          numReplicas = 2,
          load = 80,
          topKeys = Seq.empty
        )
      )
    )
    // Only measurements from pod7 and pod8 will remain.
    assert(watcher.size == 2)
    assertEqualWithinTolerance(
      watcher.getPrimaryRateLoadMap(assignment).get,
      LoadMap
        .newBuilder()
        // (70 * 2 + 80 * 2) / 2 = 150.
        .putLoad(Entry(Slice.FULL, 150))
        .build()
    )
  }

  test("Chaotic random calls to LoadWatcher") {
    // Test plan: Verify that LoadWatcher is robust and maintains its invariants well. Verify this
    // by calling LoadWatcher's APIs with totally random data at totally random times, and verifying
    // the LoadWatcher doesn't crash and its invariants are not broken. We don't verify the exact
    // results of getPrimaryRateLoadMap() as it's hard to calculate expected result.

    val seed: Long = Random.nextLong()
    logger.info(s"Using seed $seed")
    val random = new Random(seed)

    val watcher = new LoadWatcherWrapper(
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 1.minute, useTopKeys = true)
    )

    // Create a ordered, disjoint and complete list of slices along with some candidate top keys for
    // each slice, used for creating the random test Measurements.
    val numSlices: Int = 1 + random.nextInt(100)
    val sliceWithTopKeys = mutable.ArrayBuffer[(Slice, Vector[SliceKey])]()
    var lowSliceKeyLong: Long = 0 // Tracking the low slice boundary.
    // The loop just populates data into `sliceWithTopKeys`.
    for (sliceIndex: Int <- 0 until numSlices) {
      // Random high slice boundary.
      val highSliceKeyLong: Long =
        randomInRange(lowSliceKeyLong + 1, lowSliceKeyLong + 1000, random)
      // Ignore `lowSliceKeyLong` and use SliceKey.Min for the first Slice.
      val lowSliceKey: SliceKey =
        if (sliceIndex == 0) SliceKey.MIN else toSliceKey(lowSliceKeyLong)
      // Ignore `highSliceKeyLong` and use infinite high SliceKey for the last Slice.
      val highSliceKey: HighSliceKey =
        if (sliceIndex == numSlices - 1) InfinitySliceKey else toSliceKey(highSliceKeyLong)

      val slice: Slice = Slice(lowSliceKey, highSliceKey)
      // Create no more than 100 candidate top keys for each Slice.
      val topKeys: Vector[SliceKey] =
        (0 until 100)
          .map { _ =>
            // random topKeys within `slice`.
            toSliceKey(randomInRange(lowSliceKeyLong, highSliceKeyLong, random))
          }
          .toSet
          .toVector
      sliceWithTopKeys.append((slice, topKeys))
      lowSliceKeyLong = highSliceKeyLong
    }

    // Create a assignment whose slice boundaries match the test slices.
    val sliceAssignments: Iterable[SliceAssignment] = sliceWithTopKeys.map {
      case (slice: Slice, _) =>
        createRandomSliceAssignment(
          slice,
          subslices = Vector.empty,
          generation = 2 ## 42,
          random
        )
    }
    val assignment: Assignment =
      createAssignment(
        generation = 2 ## 42,
        AssignmentConsistencyMode.Affinity,
        sliceAssignments
      )

    // Calling LoadWatcher.reportLoad() and LoadWatcher.getPrimaryRateLoadMap() for 10000 times with
    // completely random Measurements and at random times.
    for (_ <- 0 until 10000) {
      // Creates a list of random Measurements to supply to reportLoad().
      val numMeasurements: Int = random.nextInt(100)
      val measurements: Seq[Measurement] = (0 until numMeasurements) map { _ =>
          // Random Slice from `sliceWithTopKeys`.
          val (slice, topKeys): (Slice, Vector[SliceKey]) =
            sliceWithTopKeys(random.nextInt(sliceWithTopKeys.length))
          val sliceLoad: Double = 0.1 + 1000 * random.nextDouble() // Load cannot be 0.
          // Randomly select no more than 20 top keys to use in the measurement.
          val selectedTopKeys: Seq[SliceKey] = (0 until random.nextInt(20)).map { _ =>
            topKeys(random.nextInt(topKeys.length))
          }
          // The sum of key loads may or may not exceed slice load.
          val keyLoads: Seq[KeyLoad] = selectedTopKeys.map { sliceKey: SliceKey =>
            KeyLoad(
              sliceKey,
              underestimatedPrimaryRateLoad = 0.1 + 100 * random.nextDouble() // Load cannot be 0.
            )
          }
          // Random time, window duration, resource and numReplicas.
          val measurementTime: TickerTime = watcher.now - randomDurationInRange(
              Duration.Zero,
              20.minutes,
              random
            )
          val windowDuration = randomDurationInRange(10.seconds, 5.minutes, random)
          val resource: ResourceAddress = s"pod-${random.nextInt(100)}"
          val numReplicas: Int = 1 + random.nextInt(10)

          watcher.createMeasurement(
            measurementTime,
            windowDuration,
            slice,
            resource,
            numReplicas,
            sliceLoad,
            keyLoads
          )
        }

      // Calling the LoadWatcher at different times. Just ensuring the LoadWatcher doesn't crash and
      // its invariants are not broken.
      watcher.now += randomDurationInRange(10.seconds, 11.minutes, random)
      watcher.recordSliceletLoad(measurements)
      watcher.now += randomDurationInRange(Duration.Zero, 2.minutes, random)
      watcher.getPrimaryRateLoadMap(assignment)
    }
  }
}
