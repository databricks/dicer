package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable
import scala.util.Random

import com.databricks.caching.util.TestUtils.{assertThrow, assertApproxEqual}
import com.databricks.dicer.assigner.algorithm.LoadMap.{Entry, KeyLoadMap}
import com.databricks.dicer.common.SliceKeyHelper
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.testing.SliceTestUtils.{
  LowInclusiveLongFluent,
  LowInclusiveSliceKeyFluent,
  LowInclusiveStringFluent,
  fp,
  identityKey,
  toSliceKey,
  `∞`
}
import com.databricks.dicer.external.{HighSliceKey, Slice, SliceKey}
import com.databricks.testing.DatabricksTest

class LoadMapSuite extends DatabricksTest {

  /** Sample map for generic tests. */
  private val MAP = LoadMap
    .newBuilder()
    .putLoad(
      Entry("a" -- "c", 10.0),
      Entry("d" -- "g", 20.0),
      Entry("g" -- "k", 30.0),
      Entry("l" -- "q", 100.0),
      Entry("z" -- ∞, 42.0)
    )
    .build()

  /**
   * Test case capturing the expected entries returned by [[LoadMap.subRange()]] from [[MAP]] for
   * the given [[slice]].
   */
  private case class SubRangeTestCase(description: String, slice: Slice, expected: Vector[Entry])
  private object SubRangeTestCase {
    def apply(description: String, slice: Slice, expected: Entry*): SubRangeTestCase = {
      SubRangeTestCase(description, slice, expected.toVector)
    }
  }

  private val SUB_RANGE_TEST_CASES = Vector(
    // Full range.
    SubRangeTestCase(
      "full range",
      "" -- ∞,
      Entry("" -- "a", 0.0),
      Entry("a" -- "c", 10.0),
      Entry("c" -- "d", 0.0),
      Entry("d" -- "g", 20.0),
      Entry("g" -- "k", 30.0),
      Entry("k" -- "l", 0.0),
      Entry("l" -- "q", 100.0),
      Entry("q" -- "z", 0.0),
      Entry("z" -- ∞, 42.0)
    ),
    // Empty intersection (expect zero-load entries).
    SubRangeTestCase("Empty initial entry", "" -- "a", Entry("" -- "a", 0.0)),
    SubRangeTestCase("Empty third entry", "c" -- "d", Entry("c" -- "d", 0.0)),
    // Test cases with complete entries.
    SubRangeTestCase(
      "first three complete entries",
      "" -- "d",
      Entry("" -- "a", 0.0),
      Entry("a" -- "c", 10.0),
      Entry("c" -- "d", 0.0)
    ),
    SubRangeTestCase(
      "third and fourth complete entries",
      "c" -- "g",
      Entry("c" -- "d", 0.0),
      Entry("d" -- "g", 20.0)
    ),
    SubRangeTestCase(
      "fifth and sixth complete entries",
      "g" -- "l",
      Entry("g" -- "k", 30.0),
      Entry("k" -- "l", 0.0)
    ),
    SubRangeTestCase(
      "sixth and seventh complete entries",
      "k" -- "q",
      Entry("k" -- "l", 0.0),
      Entry("l" -- "q", 100.0)
    ),
    SubRangeTestCase("last complete entry", "z" -- ∞, Entry("z" -- ∞, 42.0)),
    // Test cases where one end of the slice is in the middle of an entry.
    SubRangeTestCase(
      "Complete first entry and prefix of second entry",
      "" -- "b",
      Entry("" -- "a", 0.0),
      Entry("a" -- "b", 5.0)
    ),
    SubRangeTestCase(
      "Suffix of first entry and complete second entry",
      "b" -- "d",
      Entry("b" -- "c", 5.0),
      Entry("c" -- "d", 0.0)
    ),
    SubRangeTestCase(
      "Prefix of fifth entry",
      "g" -- "i",
      Entry("g" -- "i", 15.0)
    ),
    SubRangeTestCase(
      "Suffix of fifth entry",
      "i" -- "k",
      Entry("i" -- "k", 15.0)
    ),
    // Multiple entries (first and last are partial).
    SubRangeTestCase(
      "Suffix of first entry, complete second entry, prefix of third entry",
      "b" -- "h",
      // Last half of first entry.
      Entry("b" -- "c", 5.0),
      // Gap between first and middle entry.
      Entry("c" -- "d", 0.0),
      // Complete middle entry.
      Entry("d" -- "g", 20.0),
      // Quarter of third entry.
      Entry("g" -- "h", 7.5)
    )
  )

  gridTest("subRange")(SUB_RANGE_TEST_CASES) { testCase: SubRangeTestCase =>
    // Test plan: scan sub-ranges including both partial and complete entries in the load map.
    // Ensure that load is properly apportioned for the partial entries (and not apportioned for the
    // complete entries).

    val actual: Vector[Entry] = MAP.subRange(testCase.slice).toVector
    assert(actual == testCase.expected)
  }

  gridTest("getLoad")(SUB_RANGE_TEST_CASES) { testCase: SubRangeTestCase =>
    // Test plan: get load for slices that span multiple entries, including partial entries. Ensure
    // that the total load is the sum of the expected loads for each (complete or partial) entry
    // included in the sub-range.

    val expected: Double = testCase.expected.map(_.load).sum
    val actual: Double = MAP.getLoad(testCase.slice)
    assert(actual == expected)
  }

  gridTest("subRange iterable")(SUB_RANGE_TEST_CASES) { testCase: SubRangeTestCase =>
    // Test plan: verify that the sub-range iterables yield the expected elements, even when
    // repeatedly iterated.

    val expectedEntries: Vector[Entry] = testCase.expected
    val actualIterable: Iterable[Entry] = MAP.subRange(testCase.slice)
    for (_ <- 0 until 2) {
      val actualIterator: Iterator[Entry] = actualIterable.iterator
      for (expectedEntry: Entry <- expectedEntries) {
        assert(actualIterator.hasNext)
        assert(actualIterator.next() == expectedEntry)
      }
      // Ensure that the iterator is exhausted.
      assert(!actualIterator.hasNext)
      assertThrow[NoSuchElementException]("next on empty iterator") {
        actualIterator.next()
      }
    }
  }

  test("getSplit") {
    // Test plan: call getSplit for examples where the desired load is contained in both complete
    // and partial load map entries.

    /**
     * Verifies that `map.getSplit` returns the `expected` split given `desiredLoad`. In addition,
     * validates that the suggested split for `Split.Apportion` has load within +/- `tolerance` of
     * the desired load. Errors need to be tolerated for cases where exact solutions are not
     * possible.
     */
    def checkSplit(slice: Slice, desiredLoad: Double, expectedSplitKey: HighSliceKey)(
        implicit tolerance: Double = 0.0): Unit = {
      val actualSplit: LoadMap.Split = MAP.getSplit(slice, desiredLoad)
      assert(actualSplit.splitKey == expectedSplitKey)
      if (actualSplit.splitKey == slice.highExclusive) {
        // If the split includes the entire slice, the apportioned load may be less than the desired
        // load.
        assert(actualSplit.prefixApportionedLoad <= desiredLoad)
      } else {
        // Note that `actualSplitLoad` may not be the same as `desiredLoad`, because the apportioned
        // split-point can have rounding errors (e.g., because the split key chosen by the load map
        // is too short to result in the exact desired load, or due to precision limitations of
        // Double).
        if (tolerance == 0.0) {
          assert(actualSplit.prefixApportionedLoad == desiredLoad)
        } else {
          assertApproxEqual(actualSplit.prefixApportionedLoad, desiredLoad, tolerance)
        }
      }
    }

    // Test cases where entire entries in the map contain the desired load.
    checkSplit("a" -- "c", 10.0, "c")
    checkSplit("a" -- "g", 30.0, "g")
    checkSplit("a" -- "k", 60.0, "k")
    checkSplit("d" -- "q", 150.0, "q")
    checkSplit("l" -- ∞, 142.0, ∞)

    // Test cases where the desired load is available leveraging the prefix of an entry (the high
    // end is thus expected to be a synthesized 8-byte key in our example above).
    checkSplit(
      "a" -- "c",
      5.0,
      identityKey("b\0\0\0\0\0\0\0")
    )
    checkSplit(
      "a" -- "c",
      6.0,
      identityKey("b333333@")
    )(
      // Error tolerance is needed because the nearest 8-byte split key cannot represent "b plus a
      // fifth of the distance between b and c".
      tolerance = 1e-15
    )
    checkSplit(
      "a" -- "g",
      20.0,
      identityKey('e', 0x80, 0, 0, 0, 0, 0, 0)
    )
    checkSplit(
      "a" -- "k",
      45.0,
      identityKey('i', 0, 0, 0, 0, 0, 0, 0)
    )
    checkSplit(
      "d" -- "q",
      100.0,
      identityKey('n', 0x80, 0, 0, 0, 0, 0, 0)
    )
    checkSplit(
      "l" -- ∞,
      121.0,
      identityKey(0xBD, 0, 0, 0, 0, 0, 0, 0)
    )

    // Test cases where the low key is in the middle of an known entry.
    checkSplit("b" -- "c", 5.0, "c")
    checkSplit("b" -- "g", 25.0, "g")
    checkSplit("b" -- "k", 55.0, "k")
    checkSplit("n" -- ∞, 102.0, ∞)

    // Test cases where the low key and the expected high key are in the middle of known entries.
    checkSplit(
      "b" -- "e",
      7.5,
      identityKey("d`\0\0\0\0\0\0")
    )
    checkSplit(
      "b" -- ∞,
      30.0,
      identityKey(0x67, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xC0)
    )
    checkSplit(
      "b" -- "m",
      60.0,
      identityKey("l@\0\0\0\0\0\0")
    )
    checkSplit(
      "e" -- ∞,
      155.0,
      identityKey(0x9F, 0x38, 0xE3, 0x8E, 0x38, 0xE3, 0xA0, 0)
    )
    checkSplit(
      "n" -- ∞,
      100.0,
      identityKey(0xF9, 0x9E, 0x79, 0xE7, 0x9E, 0x79, 0xE8, 0)
    )
  }

  /**
   * REQUIRES: bits < 30
   *
   * Reference implementation of uniform hash-space sharding. Leveraged in a test that verifies
   * uniform-load sharding matches uniform hash-space sharding when load is uniform across the key
   * space.
   *
   * The number of slices in the sharding is equal to (1 << bits).
   */
  private def createUniformShardsInHashSpace(bits: Int): IndexedSeq[Slice] = {
    if (bits == 0) {
      IndexedSeq("" -- ∞)
    } else {
      assert(bits < 30, "be reasonable")
      val numSlices: Int = 1 << bits
      val result = new mutable.ArrayBuffer[Slice](numSlices)
      val interval: Long = 1L << (64 - bits)

      // First interval starts at min.
      var high: Long = interval
      result += "" -- high

      // Populate middle intervals.
      for (_ <- 1 until numSlices - 1) {
        val low = high
        high = low + interval
        result += low -- high
      }

      // Last interval goes to infinity.
      result += high -- ∞
      result.toIndexedSeq
    }
  }

  /**
   * Implementation of uniform-load sharding. Returns `numSlices` shards with approximately equal
   * load based on `map`. Expected to have behavior as `createUniformShardsInHashSpace`.
   */
  private def createUniformLoadShards(map: LoadMap, numSlices: Int): List[Slice] = {
    assert(numSlices > 0)
    val loadPerSlice: Double = 1.0 / numSlices
    val slices = new mutable.ArrayBuffer[Slice](numSlices)
    var previousHigh = SliceKey.MIN
    for (_ <- 0 until numSlices - 1) {
      val split: LoadMap.Split = map.getSplit(Slice.atLeast(previousHigh), loadPerSlice)
      assert(split.splitKey.isFinite, "expected finite split")
      val splitKey: SliceKey = split.splitKey.asFinite
      slices += previousHigh -- splitKey
      previousHigh = splitKey
      assertApproxEqual(split.prefixApportionedLoad, loadPerSlice, 1e-15)
    }
    // Add the remainder.
    slices += Slice.atLeast(previousHigh)
    slices.toList
  }

  test("getSplit uniform load hash space carving") {
    // Test plan: create a load map with uniform load and verify that we get the expected equally-
    // spaced hash keys when sharding the key space into uniform-load slices.
    val map: LoadMap = LoadMap.UNIFORM_LOAD_MAP

    /**
     * Runs `createUniformLoadShards` for (1 << bits) slices. Validates that the results are
     * consistent with those for `createUniformShardsInHashSpace`.
     */
    def checkUniformShards(bits: Int): List[Slice] = {
      // Collect equally-loaded slices.
      val numSlices = 1 << bits
      val slices: List[Slice] = createUniformLoadShards(map, numSlices)

      // Because the map has uniform load across the entire key space, uniform hash-space sharding
      // should produce the same result.
      val expected = createUniformShardsInHashSpace(bits)
      assert(slices == expected)
      slices
    }

    // When a single slice is requested, it should cover the full key space.
    assert(checkUniformShards(bits = 0) == List(Slice.FULL))

    // When two slices are requested, they should be split at 0x0.8 (midway between 0 and 1, or
    // "min" and "infinity" in our key numbering scheme). By design, synthesized key boundaries are
    // 8-bytes long.
    assert(
      checkUniformShards(bits = 1) == List[Slice](
        "" -- identityKey(0x80, 0, 0, 0, 0, 0, 0, 0),
        identityKey(0x80, 0, 0, 0, 0, 0, 0, 0) -- ∞
      )
    )

    // Similarly, when four slices are requested they should be split at 0x0.4, 0x0.8, and 0x0.C.
    assert(
      checkUniformShards(bits = 2) == List[Slice](
        "" -- identityKey(0x40, 0, 0, 0, 0, 0, 0, 0),
        identityKey(0x40, 0, 0, 0, 0, 0, 0, 0) -- identityKey(0x80, 0, 0, 0, 0, 0, 0, 0),
        identityKey(0x80, 0, 0, 0, 0, 0, 0, 0) -- identityKey(0xC0, 0, 0, 0, 0, 0, 0, 0),
        identityKey(0xC0, 0, 0, 0, 0, 0, 0, 0) -- ∞
      )
    )

    // Beyond 2 bits it becomes tedious to explicitly enumerate the expected slices, so we rely
    // solely on the `createUniformShardsInHashSpace` reference implementation to validate
    // expectations.
    for (bits <- 3 until 12) checkUniformShards(bits)
  }

  test("getLoad with long keys") {
    // Test plan: query load in a map containing keys that are long enough that the corresponding
    // key numbers overflow Double after scaling. See remarks on `LoadMap` for more information on
    // the internal mapping of keys to numbers.
    val mapBuilder: LoadMap.Builder = LoadMap.newBuilder()

    /**
     * Creates a SliceKey with the given bytes, with default length of ceil(bytes.bitLength / 8).
     */
    def key(bytes: BigInt)(implicit length: Int = (bytes.bitLength + 7) / 8): SliceKey = {
      SliceKeyHelper.fromBigInt(magnitude = bytes, length)
    }

    /** Helper concatenating two keys. */
    def concat(key1: SliceKey, key2: SliceKey): SliceKey = {
      val int: BigInt = (key1.toBigInt << (key2.bytes.size * 8)) + key2.toBigInt
      SliceKeyHelper.fromBigInt(magnitude = int, length = key1.bytes.size + key2.bytes.size)
    }

    // Define lengthy prefix that is common to all keys used in this test case. Contains random bits
    // (except for MSB which is 1, so that we guarantee the desired bitLength!)
    val prefixBits = 2048
    val prefixInt = BigInt(numbits = prefixBits, rnd = new Random) | BigInt(1) << (prefixBits - 1)
    assert(prefixInt.bitLength == prefixBits)
    val prefix = key(prefixInt)

    // Record load for a slice [prefix, prefix ++ {128}).
    mapBuilder.putLoad(
      Entry(
        prefix -- concat(prefix, key(128)),
        1.0
      )
    )
    val map: LoadMap = mapBuilder.build()

    // Query load for the first half of the entry.
    assert(
      map.getLoad(
        prefix -- concat(prefix, key(64))
      ) == 0.5
    )

    // Query load for the last half of the entry.
    assert(
      map.getLoad(
        concat(prefix, key(64)) -- concat(prefix, key(128))
      ) == 0.5
    )

    // Query load for the the middle half of the entry.
    assert(
      map.getLoad(concat(prefix, key(32)) -- concat(prefix, key(96))) == 0.5
    )

    // Query load for increasingly tiny sub-ranges of the entry until we run out of bits and start
    // reporting zero load. We store the offset for the highExclusive value of the slice in a key
    // large enough to overflow Double.MaxValue (0x1.fffffffffffffP+1023), so this tests the load
    // map's ability to manually scale the key number representation it uses internally, rather than
    // just relying on Double's exponents.
    var offset = BigInt(1) << 2047 // occupies 256 bytes
    var expected: Double = 1.0
    var latestActualNonZero: Double = 1.0
    do {
      expected /= 2
      offset = offset >> 1

      // Maintain stable 256-byte length for the suffix as the offset shrinks.
      val suffix = key(offset)(length = 256)
      val actual = map.getLoad(prefix -- concat(prefix, suffix))
      if (actual > 0) {
        assert(actual == expected)
        latestActualNonZero = actual
      }
    } while (latestActualNonZero == expected)

    // Validate that the smallest recovered load value is really small.
    logger.info(s"latestActualNonZero=$latestActualNonZero")
    assert(latestActualNonZero < 1e-307)
  }

  test("getLoad with un-splittable keys") {
    // Test plan: populate a load map with entries that that contain keys that differ only in
    // trailing zeroes and therefore have size zero from the perspective of the key number mapping
    // used internally by the load map. Verify that calling getLoad returns the expected results
    // when interrogating slices starting or ending with trailing zeroes (trailing zeroes do not
    // influence the key numbers used internally by the LoadMap for apportioning, so adding or
    // removing trailing zeroes for keys that are not explicit boundaries in the load map is not
    // expected to have any impact on the result). Include conventional slices in the map so that we
    // can test permutations spanning multiple entries.
    //
    // Implementation plan: define a sequence of key values and the expected cumulative load in the
    // load map for each of them. Test getLoad with all ordered combinations of key values, with
    // the expected load based on the difference in the cumulative load for the low and high keys.

    val map: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        // Conventional slice.
        Entry("a" -- "b", 10.0),
        // Single-key slice, zero size, non-zero load.
        Entry("b" -- "b\0", 30.0),
        // Two-key slice, zero size and load.
        Entry("b\0" -- "b\0\0\0", 0.0),
        // Conventional slice.
        Entry("b\0\0\0" -- "c", 20.0)
      )
      .build()

    // Define expected cumulative load for multiple keys.
    type CumulativeLoad = (SliceKey, Double)
    val cumulativeLoads = Array[CumulativeLoad](
      // Before first entry.
      (SliceKey.MIN, 0.0),
      (identityKey("0"), 0.0),
      // First entry.
      (identityKey("a"), 0.0),
      (identityKey("a\0"), 0.0),
      (identityKey('a', 0x80), 5.0),
      (identityKey('a', 0x80, 0), 5.0),
      (identityKey('a', 0x80, 0, 0), 5.0),
      // Second entry.
      (identityKey("b"), 10.0),
      // Third entry.
      (identityKey("b\0"), 40.0),
      (identityKey("b\0\0"), 40.0),
      // Fourth entry.
      (identityKey("b\0\0\0"), 40.0),
      (identityKey('b', 0x80), 50.0),
      (identityKey('b', 0x80, 0), 50.0),
      (identityKey('b', 0x80, 0, 0), 50.0),
      // After fourth entry.
      (identityKey("c"), 60.0),
      (identityKey("d"), 60.0)
    )
    val totalLoad = 60.0

    // Test all permutations.
    for (i <- cumulativeLoads.indices) {
      val (low, lowLoad): (SliceKey, Double) = cumulativeLoads(i)
      for (j <- i + 1 to cumulativeLoads.length) {
        val (subSlice, highLoad): (Slice, Double) =
          if (j == cumulativeLoads.length) {
            // For the infinity slice variation, the cumulative load is the total load across the
            // map.
            (Slice.atLeast(low), totalLoad)
          } else {
            val (high, highLoad): (SliceKey, Double) = cumulativeLoads(j)
            (low -- high, highLoad)
          }
        // The expected load for the slice is the difference in the cumulative load between low
        // and high.
        val expectedLoad: Double = highLoad - lowLoad
        assert(map.getLoad(subSlice) == expectedLoad)
      }
    }
  }

  test("getSplit with un-splittable Single key Slices keys") {
    // Test plan: populate a load map with entries that that contain keys that differ only in
    // trailing zeroes and therefore have size zero from the perspective of the key number mapping
    // used internally by the load map. Verify that calling getSplit returns the expected results
    // for desired load less than, equal to, or greater than the load on the zero-size slices. We
    // bracket the zero-size slices with conventional slices to test examples spanning multiple
    // slices.

    val map: LoadMap = LoadMap
      .newBuilder()
      .putLoad(
        // Conventional slice.
        Entry("a" -- "b", 10.0),
        // Single-key slice, zero size, non-zero load.
        Entry("b" -- "b\0", 30.0),
        // Two-key slice, zero size and load.
        Entry("b\0" -- "b\0\0\0", 0.0),
        // Conventional slice.
        Entry("b\0\0\0" -- "c", 20.0)
      )
      .build()

    // Get conventional slice.
    assert(map.getSplit("a" -- "b", 10.0) == LoadMap.Split("b", 10.0))

    // Attempt to expand to include part of the single-key, zero-size slice. The result should
    // include the un-splittable slice when doing so gets us closer to the provided `desiredLoad`
    // (for `desiredLoad <= 25.0`, excluding the zero-size Slice is the preferred choice).
    assert(map.getSplit("a" -- "z", 15.0) == LoadMap.Split(identityKey("b"), 10.0))
    assert(map.getSplit("a" -- "z", 20.0) == LoadMap.Split(identityKey("b"), 10.0))
    assert(map.getSplit("a" -- "z", 25.0) == LoadMap.Split(identityKey("b"), 10.0))
    assert(map.getSplit("a" -- "z", 25.00000000001) == LoadMap.Split(identityKey("b\0"), 40.0))
    assert(map.getSplit("a" -- "z", 30.0) == LoadMap.Split(identityKey("b\0"), 40.0))
    assert(map.getSplit("a" -- "z", 40.0) == LoadMap.Split(identityKey("b\0"), 40.0))

    // Request sufficient load that the zero-load, zero-size slice needs to be skipped.
    assert(
      map.getSplit("a" -- "z", 50.0) ==
      LoadMap.Split(identityKey('b', 0x80, 0, 0, 0, 0, 0, 0), 50.0) // midway between "b" and "c"
    )
    assert(map.getSplit("a" -- "z", 60.0) == LoadMap.Split(identityKey("c"), 60.0))
    assert(map.getSplit("a" -- "z", 61.0) == LoadMap.Split("z", 60.0))
  }

  test("Isolate hot unsplittable entry") {
    // Test plan: create a load map where non-zero load is recorded for an unsplittable Slice (e.g.,
    // a hot key). Verify that when splitting the key space using `getSplit`, to hot entry is
    // included in the suffix of the first split, and the prefix of a second split, as required by
    // the policy advertised by `LoadMap.getSplit`. Test permutations include hot keys but also
    // Slices that are unsplittable because of `LoadMap`'s unwillingness to synthesize split keys
    // with more than 8 bytes or the length of the longest boundary key, whichever is larger.

    val hotEntrySlices = mutable.ArrayBuffer[Slice]()

    // Add Slices containing a single key.
    for (hotKey: SliceKey <- Seq[SliceKey](
        "a",
        "foo",
        "more than 8 bytes",
        42,
        0x0123456789ABCDEFL
      )) {
      val successor = identityKey(hotKey.bytes.toByteArray ++ Array[Byte](0))
      hotEntrySlices += Slice(hotKey, successor)
    }
    // Add Slices where one or both boundaries have keys at least 8 bytes in length and there's no
    // space to synthesize a key between them.
    hotEntrySlices ++= Seq(
      0xEFEFEFEFEFEFEFEFL -- 0xEFEFEFEFEFEFEFF0L,
      identityKey(0xEF, 0xEF, 0xEF, 0xEF) -- identityKey(0xEF, 0xEF, 0xEF, 0xEF, 0, 0, 0, 1),
      0xEFEFEFEFFFFFFFFFL -- identityKey(0xEF, 0xEF, 0xEF, 0xF0),
      identityKey(0, 0, 0, 0, 0, 0, 0, 0, 0) -- identityKey(0, 0, 0, 0, 0, 0, 0, 0, 1)
    )
    for (hotEntrySlice: Slice <- hotEntrySlices) {
      val map: LoadMap = LoadMap.newBuilder().putLoad(Entry(hotEntrySlice, 10.0)).build()
      val split1: LoadMap.Split = map.getSplit(Slice.FULL, 5.0)
      assert(split1.splitKey == hotEntrySlice.lowInclusive, s"first split for $hotEntrySlice")
      val split2: LoadMap.Split = map.getSplit(Slice.atLeast(hotEntrySlice.lowInclusive), 5.0)
      assert(split2.splitKey == hotEntrySlice.highExclusive, s"second split for $hotEntrySlice")
    }
  }

  test("getSplit with limited precision") {
    // Test plan: populate a load map with an entry including only 4 8-byte slice keys. Verify that
    // getSplit performs rounding to the nearest 8-byte value as expected, which may mean over- or
    // under- shooting the desired load. While the `LoadMap` could improve the precision of the
    // answers by adding an unbounded number of bytes to the synthesized split keys, it limits
    // itself to the length of the surrounding keys, or to 8 bytes, whichever is greater.

    val low: SliceKey = identityKey(0, 0, 0, 0, 0, 0, 0, 0)
    val high: SliceKey = identityKey(0, 0, 0, 0, 0, 0, 0, 4)
    val slice: Slice = low -- high
    val map: LoadMap = LoadMap.newBuilder().putLoad(Entry(slice, 1.0)).build()
    assert(map.getSplit(slice, 0.0) == LoadMap.Split(low, 0.0))
    assert(map.getSplit(slice, 0.1) == LoadMap.Split(low, 0.0))
    assert(map.getSplit(slice, 0.2) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 1), .25))
    assert(map.getSplit(slice, 0.3) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 1), .25))
    assert(map.getSplit(slice, 0.4) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 2), .5))
    assert(map.getSplit(slice, 0.5) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 2), .5))
    assert(map.getSplit(slice, 0.6) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 2), .5))
    assert(map.getSplit(slice, 0.7) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 3), .75))
    assert(map.getSplit(slice, 0.8) == LoadMap.Split(identityKey(0, 0, 0, 0, 0, 0, 0, 3), .75))
    assert(map.getSplit(slice, 0.9) == LoadMap.Split(high, 1.0))
    assert(map.getSplit(slice, 1.0) == LoadMap.Split(high, 1.0))
  }

  test("getLoad for FarmHash Fingerprint64 keys") {
    // Test plan: populate a load map with generated load in the FarmHash Fingerprint64 key space.
    // Verify that the load is approximately uniformly distributed in the key space.

    val mapBuilder: LoadMap.Builder = LoadMap.newBuilder()
    for (i <- 0 until 100000) {
      val key: SliceKey = fp(s"key-$i")
      val keyInt: BigInt = key.toBigInt
      val slice: Slice = key -- SliceKeyHelper.fromBigInt(keyInt + 1, 8)
      mapBuilder.putLoad(Entry(slice, 10))
    }
    val map: LoadMap = mapBuilder.build()

    // Measure load for 1024 equally-sized key ranges.
    var minLoad: Double = Double.MaxValue
    var maxLoad: Double = 0
    for (i <- 0 until 1 << 10) {
      val lowKey: SliceKey = identityKey(i.toLong << 54)
      val slice: Slice = if (i == 1023) {
        lowKey -- ∞
      } else {
        val highKey: SliceKey = identityKey((i.toLong + 1) << 54)
        lowKey -- highKey
      }
      val load: Double = map.getLoad(slice)
      minLoad = minLoad.min(load)
      maxLoad = maxLoad.max(load)
    }
    // Verify that minLoad and maxLoad are within a factor of 2.
    assert(maxLoad / minLoad < 2)
  }

  test("withAddedUniformLoad") {
    // Test plan: verify the behavior of withAddedUniformLoad for a variety of inputs. Also checks
    // that the total load of the resulting map exceeds the total load of the input map by the
    // expected amount.

    case class TestCase(
        description: String,
        input: Seq[Entry],
        addedLoad: Double,
        expected: Seq[Entry])
    val testCases = Seq(
      TestCase(
        "Add uniform load to empty LoadMap",
        input = Seq(),
        addedLoad = 10.0,
        expected = Seq(Entry("" -- ∞, 10.0))
      ),
      TestCase(
        "Add uniform load to uniform Slices",
        input = LoadMap.getUniformPartitioning(16).map { slice: Slice =>
          Entry(slice, 41.0)
        },
        addedLoad = 16.0,
        expected = LoadMap.getUniformPartitioning(16).map { slice: Slice =>
          Entry(slice, 42.0)
        }
      ),
      TestCase(
        "Add uniform load to non-uniform Slices",
        input = Seq(
          Entry("" -- 0x4000000000000000L, 10),
          Entry(0x4000000000000000L -- 0x8000000000000000L, 30),
          Entry(0x8000000000000000L -- ∞, 20)
        ),
        addedLoad = 100,
        expected = Seq(
          Entry("" -- 0x4000000000000000L, 10 + 25),
          Entry(0x4000000000000000L -- 0x8000000000000000L, 30 + 25),
          Entry(0x8000000000000000L -- ∞, 20 + 50)
        )
      )
    )
    for (testCase <- testCases) {
      val input: LoadMap = LoadMap.newBuilder().putLoad(testCase.input: _*).build()
      val actual: LoadMap = input.withAddedUniformLoad(testCase.addedLoad)
      val expected: LoadMap = LoadMap.newBuilder().putLoad(testCase.expected: _*).build()
      assert(actual == expected, s"Test case: ${testCase.description}")

      // Verify the expected increase in the total load.
      val totalBefore: Double = input.getLoad(Slice.FULL)
      val totalAfter: Double = actual.getLoad(Slice.FULL)
      val totalDelta: Double = totalAfter - totalBefore
      assert(totalDelta == testCase.addedLoad, s"Test case: ${testCase.description}")
    }
  }

  test("putLoad with keys") {
    // Test plan: verify that putLoad with the key loads parameter works as expected, with simple
    // hardcoded test values.
    val builder = LoadMap.newBuilder()
    val key: SliceKey = 0x50
    builder.putLoad(Entry(0x40 -- 0x80, 40.0), KeyLoadMap(key -> 10.0))
    val map: LoadMap = builder.build()
    assert(map.getLoad(0x40 -- key) == 10.0)
    assert(map.getLoad(key -- key.successor()) == 10.0)
    assert(map.getLoad(key.successor() -- 0x80) == 30.0)
  }

  test("putLoad with keys edge cases") {
    // Test plan: verify that putLoad with the key loads parameter works as expected in various
    // edge cases. Test that when the keys include successors of other keys, or there is overlap
    // with the start or end of the slice itself, the load map is still as expected.

    // Specified keys must all be contained in the Slice.
    assertThrow[IllegalArgumentException]("80 must be in") {
      val builder = LoadMap.newBuilder()
      builder.putLoad(
        Entry(0x40 -- 0x80, 50.0),
        KeyLoadMap(toSliceKey(0x50) -> 10.0, toSliceKey(0x80) -> 20.0)
      )
      builder.build()
    }

    // Keys can include multiple successors.
    {
      val builder = LoadMap.newBuilder()
      val key: SliceKey = 0x50
      builder.putLoad(
        Entry(0x40 -- 0x80, 40.0),
        KeyLoadMap(
          key -> 10.0,
          key.successor() -> 5.0,
          key.successor().successor() -> 5.0
        )
      )
      val map: LoadMap = builder.build()
      assert(map.getLoad(0x40 -- key) == 10.0)
      assert(map.getLoad(key -- key.successor()) == 10.0)
      assert(map.getLoad(key.successor() -- key.successor().successor()) == 5.0)
      assert(
        map.getLoad(key.successor().successor() -- key.successor().successor().successor()) == 5.0
      )
      assert(map.getLoad(key.successor().successor().successor() -- 0x80) == 30.0)
    }

    // Slice starts at a key, ends at another key's successor.
    {
      val builder = LoadMap.newBuilder()
      val key1: SliceKey = 0x40
      val key2: SliceKey = 0x50
      builder.putLoad(
        Entry(key1 -- key2.successor(), 20.0),
        KeyLoadMap(key1 -> 10.0, key2 -> 10.0)
      )
      val map: LoadMap = builder.build()
      assert(map.getLoad(key1 -- key1.successor()) == 10.0) // key1
      assert(map.getLoad(key1.successor() -- key2) == 20.0) // Non-key load
      assert(map.getLoad(key2 -- key2.successor()) == 10.0) // key2
      assert(map.getLoad(key1 -- key2.successor()) == 40.0) // Full range
      assert(map.getLoad(key2.successor() -- ∞) == 0) // After our given Slice
    }

    // Slice starts at key and ends at its successor.
    {
      val builder = LoadMap.newBuilder()
      val key: SliceKey = 0x40
      builder.putLoad(
        Entry(key -- key.successor(), 30.0),
        KeyLoadMap(key -> 10.0)
      )
      val map: LoadMap = builder.build()
      // Unclear what the semantics here should be, but in practice we end up ignoring the load from
      // the Entry.
      assert(map.getLoad(key -- key.successor()) == 10.0)
    }
  }

  test("putLoad with keys handles floating point rounding") {
    // Test plan: verify that putLoad with the key loads parameter handles floating point rounding
    // correctly, avoiding any negative load. This is a regression test for <internal bug>.

    // Construct a LoadMap that looks as follows:
    // 0 |---------------| LONG_MAX
    //                   ^
    //               key1, key2
    // key1 and key2 are very close to the end of the Slice. The implementation of `putLoad` tracks
    // remaining load, which is initially the full slice load. Then it subtracts the load of
    // [0, key1) which due to rounding is the same as the full load, leaving remaining load of 0.
    // Then it subtracts the load from [key1, key2) which is tiny but non-zero, and we need to
    // ensure we don't allow the remaining load to go negative which would fail
    // `LoadMeasurement.requireValidLoadMeasurement` and throw an exception.
    val builder = LoadMap.newBuilder()
    val key1: SliceKey = Long.MaxValue - 100
    val key2: SliceKey = Long.MaxValue - 50
    builder.putLoad(Entry(0 -- Long.MaxValue, Math.PI), KeyLoadMap(key1 -> 1, key2 -> 1))
    val map: LoadMap = builder.build()
    assert(map.getLoad(0 -- Long.MaxValue) == Math.PI + 2)
  }
}
