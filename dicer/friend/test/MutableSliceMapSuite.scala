package com.databricks.dicer.friend

import java.util

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable
import scala.util.Random

import com.google.common.collect.{Range, RangeMap, TreeMultimap, TreeRangeMap}
import com.google.common.primitives.UnsignedLongs

import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{HighSliceKey, Slice, SliceKey}

class MutableSliceMapSuite extends DatabricksTest {

  /**
   * Create `n` slices with random boundaries (or with some small probability, a slice ending at
   * [[InfinitySliceKey]]). If `maxValue` is specified, the boundaries will correspond to 8-byte
   * longs up to that max, otherwise they will be arbitrary 8-byte values. The result may contain
   * duplicates.
   */
  private def createRandomSlices(rng: Random, n: Int, maxValue: Option[Int]): Seq[Slice] = {
    require(n > 0)
    val slices = mutable.ArrayBuffer[Slice]()
    for (_ <- 0 until n) {
      val low = maxValue match {
        case Some(max) => rng.nextInt(max)
        case None => rng.nextLong()
      }
      // Arbitrary probability of 0.1% of picking ∞ as highExclusive. Based on typical values of
      // `n` that are passed, this will be hit once every few calls to `createRandomSlices`.
      if (rng.nextFloat() < 0.001) {
        slices.append(Slice(identityKey(low), ∞))
      } else {
        var high = low
        // Keep generating random `high` until we find one that is > `low`.
        while (UnsignedLongs.compare(high, low) <= 0) {
          high = maxValue match {
            case Some(max) => rng.nextInt(max + 1)
            case None => rng.nextLong()
          }
        }
        slices.append(Slice(identityKey(low), identityKey(high)))
      }
    }
    slices
  }

  /** Validate that the entries in `map` match those in `reference`. */
  private def assertEqual[V](map: MutableSliceMap[V], reference: RangeMap[SliceKey, V]): Unit = {
    assert(map.size == reference.asMapOfRanges().size())
    val refIter: Iterator[util.Map.Entry[Range[SliceKey], V]] =
      reference.asMapOfRanges().entrySet().iterator().asScala
    for ((mapElem, refElem) <- map.iterator.zip(refIter)) {
      assert(mapElem._1 == sliceFromRange(refElem.getKey))
      assert(mapElem._2 == refElem.getValue)
    }
  }

  /**
   * Given a sequence of slices, call `map.merge` on them, using their index + 1 as the value,
   * and combining values by summing them. Validate the result against a reference TreeRangeMap.
   */
  private def testMerge(slices: Seq[Slice]): Unit = {
    val map = new MutableSliceMap[Int]
    // Map both low and high `SliceKey`s to the expected change in value at that key. We use
    // `MultiMap` to handle the possibility of matching keys. Use Java's `Integer` since it
    // implements `Comparable`, whereas Scala's `Int` doesn't.
    // E.g. with `slices = Seq(Slice("fili", "kili"))`, this would contain
    // `"fili" -> 1, "kili" -> -1`.
    val sortedKeys = TreeMultimap.create[HighSliceKey, Integer]()

    for ((slice, j) <- slices.zipWithIndex) {
      val value: Int = j + 1
      // Update `map` as well as `sortedKeys`.
      map.merge(slice, value, (oldVal: Int, newVal: Int) => {
        assert(newVal == value)
        oldVal + value
      })
      sortedKeys.put(slice.lowInclusive, value)
      sortedKeys.put(slice.highExclusive, -value)
    }

    // Go through `sortedKeys`, keeping track of the current counter value and putting each range
    // into a TreeRangeMap.
    val reference = TreeRangeMap.create[SliceKey, Int]()
    var counter: Int = 0
    val iterator: Iterator[util.Map.Entry[HighSliceKey, util.Collection[Integer]]] =
      sortedKeys.asMap().entrySet().iterator().asScala
    val sortedEntries: Iterator[(HighSliceKey, util.Collection[Integer])] =
      iterator.map(entry => (entry.getKey, entry.getValue))
    // Iterate through previous and current elements from `sortedEntries`.
    for (List((low, deltas), (high, _)) <- sortedEntries.toList.sliding(2)) {
      // Sum all deltas that appear at `low`.
      counter += deltas.iterator().asScala.map(_.toInt).sum
      assert(low.isFinite, "InfinitySliceKey cannot compare less than something else")
      val lowKey: SliceKey = low.asFinite
      // If `counter` is 0 then there are no slices covering the range and `map` would be empty.
      if (counter != 0) {
        reference.putCoalescing(rangeFromSlice(Slice(lowKey, high)), counter)
      }
    }
    assertEqual(map, reference)
  }

  test("Simple") {
    // Test plan: Do a simple put, and verify that lookups before and after return the expected
    // results.
    val map = new MutableSliceMap[Int]
    assert(map.lookUp("foo").isEmpty)
    map.put("far" -- "fudge", 2)
    assert(map.lookUp("foo").contains(("far" -- "fudge", 2)))
    assert(map.lookUp("baz").isEmpty)
    assert(map.lookUp("qux").isEmpty)
  }

  test("Merge clear") {
    // Test plan: Do a simple put, then call merge and clear all entries by returning `null` in the
    // combiner. Verify this doesn't crash and that lookup returns empty results.
    val map = new MutableSliceMap[String]
    val slice = Slice(identityKey("a"), identityKey("b"))
    map.put(slice, "foo")
    map.merge(slice, "bar", (oldVal: String, newVal: String) => null)
    assert(map.lookUp("a").isEmpty)
  }

  test("Put randomized") {
    // Test plan: Pick random slice boundaries and values to put into the map. Make corresponding
    // puts to a Guava RangeMap, and periodically validate that both maps contain the same entries.
    val numIterations: Int = 10
    val rng = new Random
    for (_ <- 0 until numIterations) {
      val numSlices: Int = 200
      // Restrict the possible values so that there is more chance of coalescing.
      val maxValue: Int = 10
      val slices: Seq[Slice] = createRandomSlices(rng, numSlices, None)
      val map = new MutableSliceMap[Int]
      val reference = TreeRangeMap.create[SliceKey, Int]()

      for (slice <- slices) {
        val value: Int = rng.nextInt(maxValue)
        map.put(slice, value)
        reference.putCoalescing(rangeFromSlice(slice), value)
        assertEqual(map, reference)
      }
    }
  }

  test("Merge randomized") {
    // Test plan: Pick random slice boundaries and incrementing values to merge into the map.
    // Specify a combiner that sums the values. Keep a sorted map of the start and end keys of each
    // slice, and put each successive range into a RangeMap to validate the entries in the map.
    val numIterations: Int = 10
    val rng = new Random
    for (_ <- 0 until numIterations) {
      val numSlices: Int = 200
      val slices: Seq[Slice] = createRandomSlices(rng, numSlices, None)
      testMerge(slices)
    }
  }

  test("Merge randomized with colliding keys") {
    // Test plan: Same as "Merge randomized", but limit keys to 300 distinct values so that there
    // is a very high probability of some collisions.
    val numIterations: Int = 10
    val maxKey: Int = 300
    val rng = new Random
    for (_ <- 0 until numIterations) {
      val numSlices: Int = 200
      val slices: Seq[Slice] = createRandomSlices(rng, numSlices, Some(maxKey))
      testMerge(slices)
    }
  }

  test("clear") {
    // Test plan: Pick random slice boundaries and values to put into the map. Call the map's clear
    // method, and validate that the map contains no entries.
    val numIterations: Int = 10
    val rng = new Random
    for (_ <- 0 until numIterations) {
      val numSlices: Int = 200
      val maxValue: Int = 10
      val slices: Seq[Slice] = createRandomSlices(rng, numSlices, None)
      val map = new MutableSliceMap[Int]

      for (slice <- slices) {
        val value: Int = rng.nextInt(maxValue)
        map.put(slice, value)
      }
      assert(map.iterator.nonEmpty)

      map.clear()
      assert(map.iterator.isEmpty)
    }
  }
}
