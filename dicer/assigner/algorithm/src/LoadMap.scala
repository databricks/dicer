package com.databricks.dicer.assigner.algorithm

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.dicer.assigner.algorithm.LoadMap.{
  ENTRY_SLICE_ACCESSOR,
  Entry,
  Split,
  UNIFORM_LOAD_MAP
}
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.{LoadMeasurement, SliceKeyHelper}
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.SliceMap.GapEntry

/**
 * An immutable data structure tracking load associated with [[Slice]]s. Each entry in the map
 * includes the total load observed for some Slice. The load may represent queries per second for
 * the keys contained in the slice, bytes of memory maintained for keys in the slice, or any
 * arbitrary load metric.
 *
 * The data structure supports two basic behaviors:
 *
 *  - [[getLoad]] can be called to estimate the load associated with an arbitrary slice. We say
 *    "estimate" because the requested slice may not be aligned with entries in the map, which
 *    means that "apportioning" of load is needed. Apportioning is discussed in detail below.
 *    [[subRange]] is a lower-level helper method that shows how load is apportioned amongst all
 *    entries intersecting a particular slice.
 *  - [[getSplit]] can be called to determine where a slice should be split such that its prefix has
 *    approximately some desired load. Again, estimation based on load apportioning is needed since
 *    the desired load may not be achieved at entry boundaries in the map.
 *
 * <h1>Apportionment</h1>
 *
 * For a worked example illustrating how apportioning is used, please see [[subRange]].
 *
 * We apportion load within an entry based on the relative size of a sub-slice. For example, if a
 * sub-slice is half the size of the entry's slice that contains it, the sub-slice is assigned half
 * of the load for the entry. But how can we assign a numeric size value for a slice or sub-slice? A
 * slice has a low and high key, where each key is a string of unsigned bytes. Keys are ordered
 * lexicographically by unsigned byte values. We can therefore assign a numeric value to each key
 * that respects the ordering as follows:
 *
 *     0x0.{key bytes}
 *
 * For example, the slice [\x42\x0A, \x42\x11\xB2) has the following size:
 *
 *     size = high - low = 0x0.4211B2 - 0x0.420A = 0x0.0007B2
 *
 * Let's say the slice above has load 10.0. If we were to apportion load to a prefix of that slice,
 * [\x42\x0A, \x42\x0D\xD9), we would first compute the sub-slice's size:
 *
 *     size = 0x0.420DD9 - 0x0.420A = 0x0.0003D9
 *
 * The load for the sub-slice is then 10.0 * 0x0.0003D9 / 0x0.0007B2 = 5.0, or the load multiplied
 * by the ratio of the sub-slice size to the total slice size.
 *
 * <h2>Simplifications</h2>
 *
 * Note that in this scheme, the key \x42\x00 has the same key number as \x42 (the trailing zero
 * does not affect the number). This basically means that apportioning assigns 0 size for slices
 * that include keys that vary only by the number of trailing zeroes. This "limitation" does not
 * affect any use cases we can imagine: if keys are fingerprints, they are fixed-length, and no two
 * keys will differ by trailing zeroes only; for natural keys, tailing zeroes and collisions on key
 * numbers may occur, but any interesting load involving those keys (e.g., hot keys) should be
 * explicitly reported to the load map regardless. A final argument in defense of this
 * simplification: there will always be cases where a slice cannot be split, e.g. [\x42, \x42\x00)
 * which contains only one key; we are effectively extending the un-splittable set to include
 * [\x42, \x42\x00\x00), and all other slices where the high is equal to low plus one or more
 * trailing zeroes.
 *
 * Apportionment is just a heuristic. We err on the side of keeping the implementation and design
 * simple (e.g., ignoring trailing zeroes, or choosing lengths for synthesized keys based on the
 * lengths of surrounding keys in [[getSplit]]). Ultimately, the goal is to choose slice boundaries
 * based on actual load reported by customer applications, and to estimate load or choose split
 * points based on a reasonable model in cases where the load map lacks detailed breakdowns.
 *
 * <h2>Are the heuristics good (enough)?</h2>
 *
 * Assuming that an application is using fingerprints for its slice keys, that there are many
 * distinct keys, and that load is uniformly distributed amongst those keys, the apportioning
 * strategy reasonably models a real application, since load is likely to be approximately equal
 * between any two sufficiently large, equally-sized slices. These assumptions can be violated
 * however, so ultimately the quality of the estimates relies on having finer-grained load
 * information in the map. Absent that finer-grained information, the apportioning strategy at least
 * gives us something to work with for a wide range of scenarios. Consider how apportioning chooses
 * a "mid-point" in the following scenarios:
 *
 *  * For a natural key slice where the low and high values have a long common prefix
 *    [http://mysite/customers/alice, http://mysite/customers/charlie), the midpoint between the key
 *    numbers (as described above) is http://mysite/customers/bjej\xE72. Given the likely
 *    distribution of customer names, this is probably not an exact midpoint, but it's a useful
 *    starting point as it has the same prefix as the surrounding keys.
 *  * For fingerprint keys (so-called "hash sharding"), the apportioning strategy performs at least
 *    as well as any consistent hashing one-off sharding algorithm. For example, if you construct
 *    a map with uniform load in the full slice -- `map.putLoad(Slice.FULL, 1.0)` -- and then
 *    split the key space by chaining [[getSplit]] calls for `desiredLoad = 1.0 / (1 << 20)`, you
 *    end up with ~million (1 << 20) slices at equally spaced boundaries:
 *
 *        ["", 0x00000400), [0x00000400, 0x00000800), [0x00000800, 0x00000C00), ...
 *
 * <h2>What about infinity?</h2>
 *
 * The special infinity slice's (see [[InfinitySliceKey]]) high value maps to:
 *
 *     0x1.0
 *
 * which is larger than any other key number, since all other key numbers are of the form
 * 0x0.{key bytes}.
 *
 * <h2>Representation of key numbers</h2>
 *
 * Because keys can be very long (1000s of bytes), there is no convenient type to represent the
 * corresponding key numbers. [[BigInt]] is an inconvenient but viable choice however. When
 * performing arithmetic on slices, we take the maximum length of any key involved, and then scale
 * all key numbers to accommodate that longest key. To return to the example above, where we were
 * apportioning load [\x42\x0A, \x42\x11\xB2) based on a split key \x42\x0D\xD9, we map the bounds
 * to:
 *
 *     slice = [0x420A00, 0x4211B2), prefix sub-slice = [0x420A00, 0x420DD9)
 *
 * Because the numbers are scaled uniformly, the size ratios are unaffected and we get the same
 * result.
 *
 *     apportioned load = 10.0 * (0x420DD9 - 0x420A00) / (0x4211B2 - 0x420A00) = 5.0
 *
 * When the load map is asked to choose a split key to achieve some desired load (as in
 * [[getSplit()]]), there is another dilemma: we can get arbitrary close to the desired load by
 * adding more and more bytes to the split key. How do we decide how may bytes to add? We simply
 * choose the number of bytes for the split key based on the number of bytes in the surrounding
 * keys, and use `LoadMap.MinLength` as a lower bound so that we can bootstrap synthetic boundaries
 * before any load has been explicitly reported to Dicer.
 *
 * Rejected alternative: we could also dynamically adjust the synthesized key lengths based on some
 * error tolerance relative to `desiredLoad`. This alternative was rejected because it's hard,
 * brittle, and has limited practical value for the following reason: if the keys are fingerprints,
 * they are of uniform length, and it is always desirable to synthesize splits of the same length;
 * if they are natural keys, our current apportioning strategy isn't well-grounded, and you can't
 * efficiently tackle accuracy problems through greater precision alone. It's likely safer to wait
 * for finer-grained error reports to guide the split decisions, rather than introduce longer and
 * longer keys to improve the perceived quality of the splits relative to the apportioning model.
 *
 * At some point, we need to convert back to [[Double]] values to compute ratios and produce
 * apportioned load numbers. Please see `getProperRatio` and `multiplyByProperRatio` for the
 * (unfortunately significant) details of those conversions.
 *
 * @param sliceMap Slice map tracking load within slice ranges.
 */
case class LoadMap private[LoadMap] (sliceMap: SliceMap[LoadMap.Entry]) {

  /**
   * Yields all entries for which load is known that intersect the given slice. For entries that are
   * not fully contained in `subRangeSlice`, returns a truncated entry with the load apportioned to
   * the intersection. Consider the following load map contents, where [....) represents a slice
   * and | indicates a boundary in the worked example (as a visual aid):
   *
   * <pre>
   *     [load=1................)            [load=3.........)            |
   *     |           |          [load=2......)          |    [load=4......)
   * </pre>
   *
   * if sub-ranges intersecting the following slice are requested:
   *
   * <pre>
   *     |           [slice.............................)    |            |
   * </pre>
   *
   * then the first and third entries are truncated and load is apportioned using the strategy
   * described in [[LoadMap]]. For example, assuming the intersection for the first entry is
   * apportioned 1/2 of the load, and the intersection for the third entry is apportioned 2/3 of the
   * load, the output is:
   *
   * <pre>
   *     |           [load=0.5..)            [load=2....)    |            |
   *     |           |          [load=2......)          |    |            |
   * </pre>
   */
  def subRange(subRangeSlice: Slice): Iterable[LoadMap.Entry] = {
    new Iterable[LoadMap.Entry] {
      override def iterator: Iterator[LoadMap.Entry] = {
        new Iterator[LoadMap.Entry] {

          /**
           * Iterator position, initialized to the index of the first entry intersecting the queried
           * Slice `subRangeSlice`. Whenever this value is modified, [[nextIndex]] must be set to
           * the value returned by [[getNextEntry]]. Note that nextIndex cannot be -1 because
           * sliceMap covers the full keyspace.
           */
          private var nextIndex =
            SliceMap.findIndexInOrderedDisjointEntries(
              sliceMap.entries,
              subRangeSlice.lowInclusive,
              LoadMap.ENTRY_SLICE_ACCESSOR
            )

          /**
           * The next entry the iterator should yield, or None if the iterator is done. Must be set
           * to the value returned by [[getNextEntry]] whenever [[nextIndex]] is modified.
           */
          private var nextEntry: Option[LoadMap.Entry] = getNextEntry

          override def hasNext: Boolean = nextEntry.isDefined

          override def next(): LoadMap.Entry = {
            this.nextEntry match {
              case Some(entry: LoadMap.Entry) =>
                this.nextIndex += 1
                this.nextEntry = getNextEntry
                entry
              case None =>
                // Undefined behavior when hasNext is false (based on the Iterator contract).
                throw new NoSuchElementException("next on empty iterator")
            }
          }

          /**
           * Gets the entry corresponding to [[nextIndex]], or None if [[nextIndex]] is beyond the
           * end of the map, or the entry at that index is after [[subRangeSlice]].
           */
          private def getNextEntry: Option[LoadMap.Entry] = {
            if (nextIndex < sliceMap.entries.size) {
              sliceMap.entries(nextIndex).intersection(subRangeSlice)
            } else {
              None
            }
          }
        }
      }
    }
  }

  /**
   * Returns the load attributed to the given slice. Load is apportioned for intersecting slices in
   * the map (see discussion in [[subRange]]).
   */
  def getLoad(slice: Slice): Double = subRange(slice).map((entry: LoadMap.Entry) => entry.load).sum

  /**
   * REQUIRES: `desiredLoad` is a non-negative, finite, number
   *
   * Determines how to split `slice` such that its prefix -- [slice.lowInclusive, splitKey) -- has
   * estimated load close to `desiredLoad`. Returns the chosen split key and the estimated load of
   * the prefix.
   *
   * A split key is chosen that is as close to `desiredLoad` as possible, modulo some precision
   * limitations (see Precision Limitations below). When two split key choices are equally close to
   * `desiredLoad`, we prefer the lower split key, except in the case where the lower split key is
   * equal to `slice.lowInclusive` (see Hot Key Splitting below).
   *
   * <h2>Precision Limitations</h2>
   *
   * If the optimal split key is in the middle of an entry, the apportioning strategy described in
   * the class docs is used to find that split key. Floating point precision issues may result in a
   * slightly sub-optimal key. Also, as discussed in the class docs, `LoadMap` is unwilling to
   * synthesize arbitrarily long split keys, which further reduces the precision of the split key
   * choice.
   *
   * <h2>Hot Key Splitting</h2>
   *
   * Some entries can not be split (e.g., because they contain only a single key), and must be
   * included in their entirety or not at all in the prefix. There is a not uncommon edge case where
   * a Slice contains a single key with non-zero load, and the algorithm wants to split the Slice in
   * half by load. In such cases, including the non-zero load key in either the prefix or suffix of
   * the split gets us equally close to `desiredLoad`, and we adopt a simple policy to break the
   * tie: include the unsplittable entry if it is at the start of `slice`, and exclude it otherwise.
   * For example, if only [b, b\0) has any load associated with it, we expect the following
   * behavior:
   *
   * {{{
   * // `load` is the load associated with the key "b".
   * iassert(map.getSplit("a" -- "c", load / 2) == Split("b", load)) // include "b" in suffix
   * iassert(map.getSplit("b" -- "c", load / 2) == Split("b\0", load)) // include "b" in prefix
   * iassert(map.getSplit("a" -- "b\0", load / 2) == Split("b", load)) // include "b" in suffix
   * }}}
   *
   * The effect of this tie-breaker policy is that recursively splitting a Slice containing a hot
   * key isolates that hot key with two calls to `getSplit`: the first call includes the hot key in
   * the suffix, and the second call includes it in the prefix. Note that this tie breaker policy
   * does not kick in when there is background load outside of the hot key, as when uniform load
   * reservations are configured for a target.
   */
  @SuppressWarnings(Array("NonLocalReturn", "reason: TODO(<internal bug>): fix and remove suppression"))
  def getSplit(slice: Slice, desiredLoad: Double): Split = {
    LoadMeasurement.requireValidLoadMeasurement(desiredLoad)

    // Walk through `slice` from low to high until we reach the desired load.
    var splitKey: HighSliceKey = slice.lowInclusive
    var accumulatedLoad: Double = 0
    var isFirstEntry: Boolean = true
    for (entry: LoadMap.Entry <- subRange(slice)) {
      val entryDesiredLoad: Double = desiredLoad - accumulatedLoad
      val entrySplit: Split = entry.getSplit(entryDesiredLoad, isFirstEntry)
      isFirstEntry = false
      splitKey = entrySplit.splitKey
      accumulatedLoad += entrySplit.prefixApportionedLoad
      if (splitKey < entry.slice.highExclusive) {
        // Short-circuit if we achieved the desired load within the current entry.
        return Split(splitKey, accumulatedLoad)
      }
    }
    Split(splitKey, accumulatedLoad)
  }

  /**
   * Returns a new [[LoadMap]] where `addedLoad` is uniformly distributed across (and added to) all
   * Slices in the current map. Load is distributed using the apportioning strategy described in
   * class docs. For example, if the current load map is:
   *
   *  - `["", 0x40) -> 10`: the first Slice has load 10 and accounts for 25% of the apportioned key
   *     space.
   *  - `[0x40, 0x80) -> 30`: the second Slice has load 30 and accounts for 25% of the apportioned
   *     key space.
   *  - `[0x80, ∞) -> 20`: the final Slice has load 20 and accounts for 50% of the apportioned key
   *    space.
   *
   * and `withAddedUniformLoad(100)` is called, the resulting map is:
   *
   *  - `["", 0x40) -> 35`: adjusted load is 10 + 25% * 100
   *  - `[0x40, 0x80) -> 55`: adjusted load is 30 + 25% * 100
   *  - `[0x80, ∞) -> 70`: adjusted load is 20 + 50% * 100
   */
  def withAddedUniformLoad(addedLoad: Double): LoadMap = {
    val adjustedEntries = Vector.newBuilder[Entry]
    adjustedEntries.sizeHint(sliceMap.entries.size)
    for (entry: Entry <- sliceMap.entries) {
      val slice: Slice = entry.slice

      // Determine the fraction of `load` to apportion to this entry. We reuse the existing
      // `getLoad` function against the uniform load map, which has total load 1, to determine this
      // ratio.
      val ratio: Double = UNIFORM_LOAD_MAP.getLoad(slice)
      val apportionedUniformLoad: Double = addedLoad * ratio
      val adjustedLoad: Double = entry.load + apportionedUniformLoad
      adjustedEntries += Entry(slice, adjustedLoad)
    }
    new LoadMap(new SliceMap(adjustedEntries.result(), ENTRY_SLICE_ACCESSOR))
  }
}

object LoadMap {

  /** Estimated load for specific keys. */
  type KeyLoadMap = SortedMap[SliceKey, Double]

  /** Factory methods for KeyLoadMap. */
  object KeyLoadMap {
    // Type aliases don't also create an alias to the companion object. Aliasing the companion
    // object directly with `val KeyLoadMap = SortedMap` still requires annotating types when
    // calling the factory methods, so we create our own aliases here.
    /** Create an empty [[KeyLoadMap]]. */
    def empty: KeyLoadMap = SortedMap.empty

    /** Create a [[KeyLoadMap]] using Map syntax, e.g. `KeyLoadMap(key -> 1.0)`. */
    def apply(entries: (SliceKey, Double)*): KeyLoadMap = SortedMap(entries: _*)
  }

  /** Function returning the Slice from a [[LoadMap]] entry. */
  private val ENTRY_SLICE_ACCESSOR = (entry: LoadMap.Entry) => entry.slice

  /** The minimum length for synthesized key boundaries in [[LoadMap.getSplit()]]. */
  private val MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES: Int = 8

  /** A load map with uniform load in the full key space. The total load is 1. */
  val UNIFORM_LOAD_MAP: LoadMap = LoadMap.newBuilder().putLoad(Entry(Slice.FULL, 1.0)).build()

  /**
   * Divides the full [[SliceKey]] space into `sliceCount` slices of equal "size", where size
   * respects the definition used for apportioning in [[LoadMap]]. For example, if `sliceCount` is
   * 4, returns equally spaced Slices with 8-byte keys for all internal boundaries:
   *
   *   - ["" -- 0x4000000000000000)
   *   - [0x4000000000000000 -- 0x8000000000000000)
   *   - [0x8000000000000000 -- 0xc000000000000000)
   *   - [0xc000000000000000 -- ∞)
   *
   * By definition, the uniform partitioning results in Slices with uniform load when applied to
   * [[UNIFORM_LOAD_MAP]] (ignoring rounding errors for Slice boundaries).
   */
  def getUniformPartitioning(sliceCount: Int): Vector[Slice] = {
    val slices = Vector.newBuilder[Slice]
    slices.sizeHint(sliceCount)

    // We create `count` 64-bit integers that are equally spaced apart. To do so, we increment by
    // `step` each time - `step` is required to ensure we are setting the most-significant bits in
    // the byte. E.g. when `count` is 4, `step` is 0x04..00 and `curKey` will be 0x4..00,
    // 0x8..00, 0xc..00. We track the previous key used (starting at `SliceKey.MIN`) and create
    // slices from previous to current. So the resulting slices will be ["" .. 0x4000000000000000),
    // [0x4000000000000000 .. 0x8000000000000000), [0x8000000000000000 .. 0xc000000000000000),
    // [0xc000000000000000 .. ∞).
    val step: BigInt = (BigInt(1) << 64) / sliceCount
    var previousHighKey = SliceKey.MIN
    for (i <- 1 until sliceCount) {
      val highKey: SliceKey = SliceKeyHelper.fromBigInt(magnitude = step * i, length = 8)
      slices += Slice(previousHighKey, highKey)
      previousHighKey = highKey
    }
    slices += Slice.atLeast(previousHighKey)
    slices.result()
  }

  /**
   * The outcome of [[LoadMap.getSplit()]] (and of the private [[Entry.getSplit()]] as well).
   *
   * @param splitKey the split-point identified. May be equal to the low key of the requested Slice,
   *                 indicating that the desired load would be exceeded if the smallest splittable
   *                 prefix of the Slice were included. May be equal to the high key of the
   *                 requested Slice if the desired load can only be reached or approached if the
   *                 entirety of the Slice is included. Otherwise, returns a key between the bounds
   *                 of the requested Slice that results in a prefix with approximately the desired
   *                 load.
   * @param prefixApportionedLoad the estimated apportioned load for
   *                              `[slice.lowInclusive, splitKey)`.
   */
  case class Split(splitKey: HighSliceKey, prefixApportionedLoad: Double)

  /**
   * REQUIRES: slice is non-empty
   * REQUIRES: load is a non-negative, finite, number
   *
   * An entry in the load map indicates the total load across a particular slice range.
   *
   * @param slice range of keys for which load was observed
   * @param load load attributed to the slice
   */
  case class Entry(slice: Slice, load: Double) {
    LoadMeasurement.requireValidLoadMeasurement(load)

    /**
     * Returns the intersection of this entry with the given slice. Returns None if `other` does not
     * intersect this entry. Returns this entry if `other` fully contains [[slice]]. Otherwise,
     * returns a new entry with the intersecting slice range, and with load apportioned to the
     * intersection. See remarks on [[LoadMap]] for details of the apportioning strategy.
     */
    private[LoadMap] def intersection(other: Slice): Option[Entry] = {
      // Short-circuit before calling `slice.intersection` in the case that `other` is a
      // (non-strict) superset of `slice`. This avoids wasted allocation from `slice.intersection`
      // in the common case where the load map has been built from an assignment's slices and is
      // then queried per slice in the same assignment.
      if (other.contains(slice)) {
        return Some(this)
      }
      slice.intersection(other) match {
        case None => None // no intersection between `this` and `other`
        case Some(intersection) =>
          // Associate all slice bounds with numbers so that we can determine their relative sizes.
          // Numeric values are scaled so that the most significant bytes of all bounds have the
          // same magnitude.
          val length: Int =
            getMaxLengthOfSliceBounds(slice).max(getMaxLengthOfSliceBounds(intersection))
          val (sliceLow, sliceHigh): (BigInt, BigInt) = toBigIntRange(slice, length)
          val (intersectionLow, intersectionHigh): (BigInt, BigInt) =
            toBigIntRange(intersection, length)
          val sliceSize: BigInt = sliceHigh - sliceLow
          val intersectionSize: BigInt = intersectionHigh - intersectionLow

          // Figure out what ratio of the load can be attributed to the intersection.
          val intersectionRatio: Double =
            if (sliceSize == 0) {
              // The slice size may be zero if its low and high keys map to equivalent numbers, e.g.
              // ["foo", "foo\0"). In such cases, we arbitrarily assume the intersection ratio is 0,
              // though 1 would be a fine choice as well.
              0
            } else {
              getProperRatio(intersectionSize, sliceSize)
            }
          Some(Entry(intersection, intersectionRatio * load))
      }
    }

    /**
     * Determines how (and whether) to split this entry to get `desiredLoad` in its prefix
     * (the slice [slice.lowInclusive, splitKey)).
     */
    private[LoadMap] def getSplit(desiredLoad: Double, isFirstEntry: Boolean): Split = {
      if (desiredLoad <= 0) {
        // If there is no desired load, include none of the Slice. This code path eliminates the
        // non-positive desiredLoad case.
        return Split(slice.lowInclusive, 0)
      }
      if (load <= desiredLoad) {
        // If this entry in its entirety is needed to reach the desired load, short-circuit. This
        // code path eliminates the non-positive load case.
        return Split(slice.highExclusive, load)
      }
      // Determine what ratio of the entry's load is required. Note that based on the checks above
      // we now know that `0 < desiredLoad < load`, so desiredRatio ought to be in (0, 1), or
      // [0.0, 1.0] due to rounding errors.
      val desiredRatio: Double = desiredLoad / load

      // Determine the length to use for the generated split key, which is based on the lengths of
      // the surrounding keys with MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES as a lower bound.
      // See the discussion in the `LoadMap` docs for more details on this policy and its
      // implications.
      val length: Int =
        getMaxLengthOfSliceBounds(slice).max(MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES)
      val (low, high): (BigInt, BigInt) = toBigIntRange(slice, length)

      // Synthesize a split key that is estimated to include `desiredRatio` of `load` in the prefix.
      val split: BigInt = low + multiplyByProperRatio(high - low, desiredRatio)
      if (split == low || split == high) {
        // For several reasons, it may not be possible or necessary to split the current Slice to
        // achieve the desired ratio:
        //  * The Slice may be unsplittable because it contains only a single key, e.g.,
        //    ["a", "a\0"). In this case, `low == split == high`.
        //  * The Slice may be unsplittable because no key can be synthesized between its 8-byte low
        //    and high bounds, e.g., [0xEFEFEFEFEFEFEFEF, 0xEFEFEFEFEFEFEFF0). In this case,
        //    `low + 1 == high`.
        //  * After rounding to the nearest `length`-byte key, the split key may be equal to the low
        //    or high bound of the Slice. (Note that rounding affects examples where `low + 1 ==
        //    high` as well, and the policy may prescribe choosing the low boundary in this case.
        //    This is why we check for equality of `split` with either the `low` or `high` bounds
        //    above rather than adding a separate case for `split == high`.)
        // In all of these cases, we choose to include all or none of the entry in the prefix of the
        // split, depending on which choice brings us closer to the desired load. If both choices
        // bring us equally close to the desired load, we prefer to introduce a split when possible:
        // if this is the first entry explored in a `LoadMap.getSplit` call (per `isFirstEntry`), we
        // include the entire entry, and otherwise we exclude it.
        val errorWith: Double = (load - desiredLoad).abs
        val errorWithout: Double = desiredLoad
        val include
            : Boolean = errorWith < errorWithout || (errorWith == errorWithout && isFirstEntry)
        val split = if (include) Split(slice.highExclusive, load) else Split(slice.lowInclusive, 0)
        return split
      }
      val splitKey = SliceKeyHelper.fromBigInt(magnitude = split, length)

      // Due to the limited precision afforded by `length`, the actual ratio of load apportioned to
      // the prefix may be different from `desiredRatio`. Compute the actual ratio and then the
      // actual apportioned load based on the chosen split key.
      val prefixRatio: Double = getProperRatio(split - low, high - low)
      val prefixApportionedLoad: Double = load * prefixRatio
      Split(splitKey, prefixApportionedLoad)
    }
  }

  /** Supports building an immutable [[LoadMap]]. */
  class Builder private[LoadMap] () {

    /** Range map maintaining load with slice ranges. See remarks on `LoadMap.map`. */
    private val entries = mutable.ArrayBuffer[Entry]()

    /**
     * REQUIRES: no load is currently recorded that overlaps with `entry.slice` (this is a temporary
     *   limitation, as we currently have no need for more sophisticated behavior)
     *
     * Assigns the given load to the given slice.
     */
    def putLoad(entry: Entry): this.type = {
      entries.append(entry)
      this
    }

    /** Calls `putLoad(Entry)` for each entry in `entries`. */
    def putLoad(entries: Entry*): this.type = {
      for (entry <- entries) putLoad(entry)
      this
    }

    /**
     * REQUIRES: no load is currently recorded that overlaps with `entry.slice`.
     * REQUIRES: the keys in `keyLoads` are contained in `entry.slice`.
     *
     * Assigns the given load for the given slice, along with load for specific keys as given in
     * `keyLoads`. `entry.load` is the load for the slice ''excluding'' `keyLoads`, and is
     * apportioned uniformly as per the [[SliceMap]] docs. For example, given
     * {{{
     *   putLoad(Entry([0x40, 0x80), 40), SortedMap(0x50 -> 10))
     * }}}
     * then the resulting load is:
     * {{{
     *   [0x40, 0x50) -> 10
     *   [0x50, 0x50.successor()) -> 10
     *   [0x50.successor(), 0x80) -> 30
     * }}}
     */
    def putLoad(entry: Entry, keyLoads: KeyLoadMap): this.type = {
      // Track the remaining part of `entry`, along with the non-key load.
      var remainingEntry: Entry = entry

      val iterator = keyLoads.iterator
      while (iterator.hasNext) {
        val (key, keyLoad): (SliceKey, Double) = iterator.next()
        require(remainingEntry.slice.contains(key), s"$key must be in ${entry.slice}")

        // |---------| `remainingEntry.slice`
        //    ^ `key`
        // Result:
        // |--|        putLoad(`leftEntry`)
        //    ||       putLoad(Slice for `key`)
        //     |-----| `remainingEntry`
        if (key != remainingEntry.slice.lowInclusive) {
          // Add an entry up until `key`.
          val leftSlice = Slice(remainingEntry.slice.lowInclusive, key)
          // Use `entry.intersection` rather than `remainingEntry.intersection` to reduce
          // accumulation of apportioning/floating point errors.
          val leftEntry: Entry = entry.intersection(leftSlice) match {
            case None =>
              throw new AssertionError(s"Unexpected: $leftSlice did not intersect ${entry.slice}")
            case Some(leftEntry: Entry) => leftEntry
          }
          putLoad(leftEntry)
          remainingEntry = Entry(
            Slice(key, entry.slice.highExclusive),
            // Handle floating point rounding (<internal bug>).
            Math.max(remainingEntry.load - leftEntry.load, 0)
          )
        }
        // Add an entry for `key` using the Slice [`key`, `key.successor()`).
        val successor: SliceKey = key.successor()
        putLoad(Entry(Slice(key, successor), keyLoad))
        if (successor == remainingEntry.slice.highExclusive) {
          // We've reached the end of `entry`.
          return this
        }

        remainingEntry = remainingEntry.copy(slice = Slice(successor, entry.slice.highExclusive))
      }
      putLoad(remainingEntry)

      this
    }

    /** Builds a `LoadMap` with all load recorded on the builder. */
    def build(): LoadMap = {
      // First, build a map with gaps.
      val gappyMap: SliceMap[GapEntry[Entry]] = SliceMap.createFromOrderedDisjointEntries(
        entries.toSeq.sortBy(ENTRY_SLICE_ACCESSOR),
        ENTRY_SLICE_ACCESSOR
      )

      // Next, fill in the gaps with 0-load entries.
      val map: SliceMap[Entry] = new SliceMap(
        gappyMap.entries.map {
          case GapEntry.Gap(slice: Slice) => Entry(slice, load = 0)
          case GapEntry.Some(entry: Entry) => entry
        },
        ENTRY_SLICE_ACCESSOR
      )
      new LoadMap(map)
    }
  }

  /** Creates a [[LoadMap]] builder. */
  def newBuilder(): Builder = new Builder

  /**
   * REQUIRES: non-negative lhs
   * REQUIRES: positive rhs
   * REQUIRES: lhs <= rhs
   *
   * Returns lhs/rhs as a Double value. Accounts for possible overflow in conversion to Double.
   */
  private def getProperRatio(lhs: BigInt, rhs: BigInt): Double = {
    iassert(lhs.signum >= 0)
    iassert(rhs.signum > 0)
    iassert(lhs <= rhs)

    // The maximum exponent for a Double is 1023, so we must scale the arguments to avoid overflow
    // in the conversion. While this scaling may discard the least significant bits in our function
    // parameters, we wouldn't benefit from those bits anyway, as the Double mantissa has only 52
    // bits. When the magnitude of `rhs` greatly exceeds `lhs` (by a factor of 10^300 or more in
    // practice) we may end up discarding all bits in `lhs` and returning 0 from this function.
    val scale: Int = (rhs.bitLength - java.lang.Double.MAX_EXPONENT).max(0)
    val scaledLhs: BigInt = lhs >> scale
    val scaledRhs: BigInt = rhs >> scale
    scaledLhs.toDouble / scaledRhs.toDouble
  }

  /**
   * REQUIRES: non-negative `multiplicand`
   * REQUIRES: `ratio` is in the unit interval [0, 1] (i.e., represents a proper fraction)
   *
   * Returns multiplicand*ratio as a BigInt value. Accounts for possible overflow in the conversion
   * from BigInt to Double for the multiplicand. Also special cases 0 and 1 ratios.
   */
  private def multiplyByProperRatio(multiplicand: BigInt, ratio: Double): BigInt = {
    require(multiplicand.signum >= 0)
    require(ratio >= 0)
    require(ratio <= 1)

    if (ratio == 1) {
      return multiplicand // avoid losing least significant bits in the toDouble conversion below
    }
    // Only the 52 most significant bits will be used in the Double calculation, so we can safely
    // scale the multiplicand so that its bit length is 53. This preserves enough bits to exploit
    // Double's maximum precision, and eliminates the possibility of overflow when converting to
    // Double and then back to BigInt via Long.
    val scale: Int = (multiplicand.bitLength - 53).max(0)
    val scaledMultiplicand: BigInt = multiplicand >> scale
    val scaledFactor: Double = scaledMultiplicand.toDouble * ratio
    val result = BigInt(Math.round(scaledFactor)) << scale

    // Clamp the result to [0, multiplicand] in case of unanticipated floating point errors.
    result.max(0).min(multiplicand)
  }

  /**
   * REQUIRES: length >= key.length
   *
   * Converts the given key to its numeric value, with zero padding such that it has the given
   * length.
   *
   * Examples:
   *
   *     toBigInt(0x0403, 3) => 0x040300
   *     toBigInt(0x040300, 2) => undefined (violates requirements)
   */
  private def toBigInt(key: SliceKey, length: Int): BigInt = {
    key.toBigInt << ((length - key.bytes.size) * 8)
  }

  /**
   * REQUIRES: length >= slice.lowInclusive.length
   * REQUIRES: slice.highExclusive is infinity or slice.highExclusive length is less than `length`
   *
   * Converts the given Slice bounds to their numeric values, as described in [[toBigInt]]. For
   * infinity slices, a number greater than the largest number representable within `length` is
   * returned for the upper bound.
   *
   * Examples:
   *
   *     toBigIntRange([0x040300 .. 0x0404), 4) => (0x04030000, 0x04040000)
   *     toBigIntRange([0x040300 .. ∞), 4) => (0x04030000, 0x0100000000)
   */
  private def toBigIntRange(slice: Slice, length: Int): (BigInt, BigInt) = {
    val low: BigInt = toBigInt(slice.lowInclusive, length)
    val high: BigInt = slice.highExclusive match {
      case InfinitySliceKey => BigInt(1) << (length * 8)
      case highExclusive: SliceKey => toBigInt(highExclusive, length)
    }
    (low, high)
  }

  /**
   * Returns the maximum length of slice bounds. This length is used when translating keys to
   * (comparable) numbers.
   */
  private def getMaxLengthOfSliceBounds(slice: Slice): Int = {
    val lowLength: Int = slice.lowInclusive.bytes.size
    val highLength: Int = slice.highExclusive match {
      case InfinitySliceKey => 0
      case highExclusive: SliceKey => highExclusive.bytes.size
    }
    lowLength.max(highLength)
  }
}
