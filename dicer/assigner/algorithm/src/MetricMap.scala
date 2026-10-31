package com.databricks.dicer.assigner.algorithm

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import com.databricks.dicer.assigner.SliceKeyMath
import com.databricks.dicer.assigner.algorithm.LoadMap.UNIFORM_LOAD_MAP
import com.databricks.dicer.assigner.algorithm.MetricMap.{ENTRY_SLICE_ACCESSOR, Split}
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.{MetricMeasurement, SliceKeyHelper}
import com.databricks.dicer.external.{HighSliceKey, Slice, SliceKey}
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.SliceMap.GapEntry

/**
 * An immutable data structure tracking a metric associated with [[Slice]]s. Each entry in the map
 * includes the total value of the metric observed for some Slice. Concrete subclasses fix the
 * metric so that maps for different metrics cannot be mixed up: [[LoadMap]] tracks the primary
 * rate metric (load, e.g. queries per second) and [[SizeMap]] tracks the primary gauge metric
 * (size, e.g. bytes of memory). The docs below call the metric's value simply "value"; concrete
 * subclasses expose accessors under metric-specific names (e.g. [[LoadMap.getLoad]],
 * [[LoadMap.Entry.load]]).
 *
 * The data structure supports two basic behaviors:
 *
 *  - [[getValue]] can be called to estimate the value associated with an arbitrary slice. We say
 *    "estimate" because the requested slice may not be aligned with entries in the map, which
 *    means that "apportioning" of value is needed. Apportioning is discussed in detail below.
 *    [[subRange]] is a lower-level helper method that shows how values are apportioned amongst all
 *    entries intersecting a particular slice.
 *  - [[getSplitForValue]] can be called to determine where a slice should be split such that its
 *    prefix has approximately some desired value. Again, estimation based on value apportioning is
 *    needed since the desired value may not be achieved at entry boundaries in the map.
 *
 * <h1>Apportionment</h1>
 *
 * For a worked example illustrating how apportioning is used, please see [[subRange]].
 *
 * We apportion value within an entry based on the relative width of a sub-slice. For example, if a
 * sub-slice is half the width of the entry's slice that contains it, the sub-slice is assigned half
 * of the value for the entry. But how can we assign a numeric width to a slice or sub-slice?
 * A slice has a low and high key, where each key is a string of unsigned bytes. Keys are ordered
 * lexicographically by unsigned byte values. We can therefore assign a numeric value to each key
 * that respects the ordering as follows:
 *
 *     0x0.{key bytes}
 *
 * For example, the slice [\x42\x0A, \x42\x11\xB2) has the following width:
 *
 *     width = high - low = 0x0.4211B2 - 0x0.420A = 0x0.0007B2
 *
 * Let's say the slice above has value 10.0. If we were to apportion value to a prefix of that
 * slice, [\x42\x0A, \x42\x0D\xD9), we would first compute the sub-slice's width:
 *
 *     width = 0x0.420DD9 - 0x0.420A = 0x0.0003D9
 *
 * The value for the sub-slice is then 10.0 * 0x0.0003D9 / 0x0.0007B2 = 5.0, or the value multiplied
 * by the ratio of the sub-slice width to the total slice width.
 *
 * <h2>Simplifications</h2>
 *
 * Note that in this scheme, the key \x42\x00 has the same key number as \x42 (the trailing zero
 * does not affect the number). This basically means that apportioning assigns 0 width for slices
 * that include keys that vary only by the number of trailing zeroes. This "limitation" does not
 * affect any use cases we can imagine: if keys are fingerprints, they are fixed-length, and no two
 * keys will differ by trailing zeroes only; for natural keys, tailing zeroes and collisions on key
 * numbers may occur, but any interesting value involving those keys (e.g., hot keys) should be
 * explicitly reported to the map regardless. A final argument in defense of this
 * simplification: there will always be cases where a slice cannot be split, e.g. [\x42, \x42\x00)
 * which contains only one key; we are effectively extending the un-splittable set to include
 * [\x42, \x42\x00\x00), and all other slices where the high is equal to low plus one or more
 * trailing zeroes.
 *
 * Apportionment is just a heuristic. We err on the side of keeping the implementation and design
 * simple (e.g., ignoring trailing zeroes, or choosing lengths for synthesized keys based on the
 * lengths of surrounding keys in [[getSplitForValue]]). Ultimately, the goal is to choose slice
 * boundaries based on actual values reported by customer applications, and to estimate values or
 * choose split points based on a reasonable model in cases where the map lacks detailed breakdowns.
 *
 * <h2>Are the heuristics good (enough)?</h2>
 *
 * Assuming that an application is using fingerprints for its slice keys, that there are many
 * distinct keys, and that value is uniformly distributed amongst those keys, the apportioning
 * strategy reasonably models a real application, since value is likely to be approximately equal
 * between any two sufficiently large, equally-wide slices. These assumptions can be violated
 * however, so ultimately the quality of the estimates relies on having finer-grained value
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
 *    a map with a uniform value in the full slice -- `Entry(Slice.FULL, 1.0)` -- and then split
 *    the key space by chaining [[getSplitForValue]] calls for `desiredValue = 1.0 / (1 << 20)`, you
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
 * apportioning value [\x42\x0A, \x42\x11\xB2) based on a split key \x42\x0D\xD9, we map the bounds
 * to:
 *
 *     slice = [0x420A00, 0x4211B2), prefix sub-slice = [0x420A00, 0x420DD9)
 *
 * Because the numbers are scaled uniformly, the width ratios are unaffected and we get the same
 * result.
 *
 *     apportioned value = 10.0 * (0x420DD9 - 0x420A00) / (0x4211B2 - 0x420A00) = 5.0
 *
 * When the map is asked to choose a split key to achieve some desired value (as in
 * [[getSplitForValue()]]), there is another dilemma: we can get arbitrary close to the desired
 * value by adding more and more bytes to the split key. How do we decide how may bytes to add? We
 * simply choose the number of bytes for the split key based on the number of bytes in the
 * surrounding keys, and use `MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES` as a lower bound so
 * that we can bootstrap synthetic boundaries before any value has been explicitly reported to
 * Dicer.
 *
 * Rejected alternative: we could also dynamically adjust the synthesized key lengths based on some
 * error tolerance relative to `desiredValue`. This alternative was rejected because it's hard,
 * brittle, and has limited practical value for the following reason: if the keys are fingerprints,
 * they are of uniform length, and it is always desirable to synthesize splits of the same length;
 * if they are natural keys, our current apportioning strategy isn't well-grounded, and you can't
 * efficiently tackle accuracy problems through greater precision alone. It's likely safer to wait
 * for finer-grained error reports to guide the split decisions, rather than introduce longer and
 * longer keys to improve the perceived quality of the splits relative to the apportioning model.
 *
 * At some point, we need to convert back to [[Double]] values to compute ratios and produce
 * apportioned values. Please see `getProperRatio` and `multiplyByProperRatio` for the
 * (unfortunately significant) details of those conversions.
 *
 *
 * @param typeName the name of the concrete map type, used by [[toString]].
 * @param sliceMap Slice map tracking value within slice ranges. Covers the full key space.
 * @param createEntry creates an entry of the concrete type with the given slice and value.
 * @tparam E the concrete entry type, which names the metric (e.g. [[LoadMap.Entry]]).
 */
sealed abstract class MetricMap[E <: MetricMap.Entry] private[algorithm] (
    typeName: String,
    val sliceMap: SliceMap[E],
    createEntry: (Slice, Double) => E) {

  /**
   * Yields all entries for which value is known that intersect the given slice. For entries that
   * are not fully contained in `subRangeSlice`, returns a truncated entry with the value
   * apportioned to the intersection. Consider the following map contents, where [....) represents a
   * slice and | indicates a boundary in the worked example (as a visual aid):
   *
   * <pre>
   *     [value=1................)            [value=3.........)            |
   *     |           |          [value=2......)          |    [value=4......)
   * </pre>
   *
   * if sub-ranges intersecting the following slice are requested:
   *
   * <pre>
   *     |           [slice.............................)    |            |
   * </pre>
   *
   * then the first and third entries are truncated and values are apportioned using the strategy
   * described in [[MetricMap]]. For example, assuming the intersection for the first entry is
   * apportioned 1/2 of the value, and the intersection for the third entry is apportioned 2/3 of
   * the value, the output is:
   *
   * <pre>
   *     |           [value=0.5..)            [value=2....)    |            |
   *     |           |          [value=2......)          |    |            |
   * </pre>
   */
  final def subRange(subRangeSlice: Slice): Iterable[E] = {
    new Iterable[E] {
      override def iterator: Iterator[E] = {
        new Iterator[E] {

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
              ENTRY_SLICE_ACCESSOR
            )

          /**
           * The next entry the iterator should yield, or None if the iterator is done. Must be set
           * to the value returned by [[getNextEntry]] whenever [[nextIndex]] is modified.
           */
          private var nextEntry: Option[E] = getNextEntry

          override def hasNext: Boolean = nextEntry.isDefined

          override def next(): E = {
            this.nextEntry match {
              case Some(entry) =>
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
          private def getNextEntry: Option[E] = {
            if (nextIndex < sliceMap.entries.size) {
              MetricMap.intersectEntry(sliceMap.entries(nextIndex), subRangeSlice, createEntry)
            } else {
              None
            }
          }
        }
      }
    }
  }

  /** Maps of different concrete types are never equal, even if they have the same values. */
  final override def equals(other: Any): Boolean = other match {
    case that: MetricMap[_] => this.getClass == that.getClass && this.sliceMap == that.sliceMap
    case _ => false
  }

  final override def hashCode(): Int = (getClass, sliceMap).hashCode()

  final override def toString: String = s"$typeName($sliceMap)"

  /**
   * Returns the value attributed to the given slice. Values are apportioned for intersecting slices
   * in the map (see discussion in [[subRange]]).
   */
  protected final def getValue(slice: Slice): Double =
    subRange(slice).map((entry: E) => entry.value).sum

  /**
   * REQUIRES: `desiredValue` is a non-negative, finite, number
   *
   * Determines how to split `slice` such that its prefix -- [slice.lowInclusive, splitKey) -- has
   * estimated value close to `desiredValue`. Returns the chosen split key and the estimated value
   * of the prefix.
   *
   * A split key is chosen that is as close to `desiredValue` as possible, modulo some precision
   * limitations (see Precision Limitations below). When two split key choices are equally close to
   * `desiredValue`, we prefer the lower split key, except in the case where the lower split key is
   * equal to `slice.lowInclusive` (see Hot Key Splitting below).
   *
   * <h2>Precision Limitations</h2>
   *
   * If the optimal split key is in the middle of an entry, the apportioning strategy described in
   * the class docs is used to find that split key. Floating point precision issues may result in a
   * slightly sub-optimal key. Also, as discussed in the class docs, `MetricMap` is unwilling to
   * synthesize arbitrarily long split keys, which further reduces the precision of the split key
   * choice.
   *
   * <h2>Hot Key Splitting</h2>
   *
   * Some entries can not be split (e.g., because they contain only a single key), and must be
   * included in their entirety or not at all in the prefix. There is a not uncommon edge case where
   * a Slice contains a single key with non-zero value, and the algorithm wants to split the Slice
   * in half by value. In such cases, including the non-zero value key in either the prefix or
   * suffix of the split gets us equally close to `desiredValue`, and we adopt a simple policy to
   * break the tie: include the unsplittable entry if it is at the start of `slice`, and exclude it
   * otherwise. For example, if only [b, b\0) has any value associated with it, we expect the
   * following behavior:
   *
   * {{{
   * // `value` is the value associated with the key "b".
   * iassert(map.getSplit("a" -- "c", value / 2) == Split("b", value)) // include "b" in suffix
   * iassert(map.getSplit("b" -- "c", value / 2) == Split("b\0", value)) // include "b" in prefix
   * iassert(map.getSplit("a" -- "b\0", value / 2) == Split("b", value)) // include "b" in suffix
   * }}}
   *
   * The effect of this tie-breaker policy is that recursively splitting a Slice containing a hot
   * key isolates that hot key with two calls to `getSplit`: the first call includes the hot key in
   * the suffix, and the second call includes it in the prefix. Note that this tie breaker policy
   * does not kick in when there is a background value outside of the hot key, as when uniform load
   * reservations are configured for a target.
   */
  @SuppressWarnings(Array("NonLocalReturn", "reason: TODO(<internal bug>): fix and remove suppression"))
  protected final def getSplitForValue(slice: Slice, desiredValue: Double): Split = {
    MetricMeasurement.requireValidMeasurement(desiredValue)

    // Walk through `slice` from low to high until we reach the desired value.
    var splitKey: HighSliceKey = slice.lowInclusive
    var accumulatedValue: Double = 0
    var isFirstEntry: Boolean = true
    for (entry: E <- subRange(slice)) {
      val entryDesiredValue: Double = desiredValue - accumulatedValue
      val entrySplit: Split = MetricMap.getEntrySplit(entry, entryDesiredValue, isFirstEntry)
      isFirstEntry = false
      splitKey = entrySplit.splitKey
      accumulatedValue += entrySplit.prefixApportionedValue
      if (splitKey < entry.slice.highExclusive) {
        // Short-circuit if we achieved the desired value within the current entry.
        return Split(splitKey, accumulatedValue)
      }
    }
    Split(splitKey, accumulatedValue)
  }
}

object MetricMap {

  /** Function returning the Slice from a [[MetricMap]] entry. */
  private[algorithm] val ENTRY_SLICE_ACCESSOR = (entry: Entry) => entry.slice

  /** The minimum length for synthesized key boundaries in [[MetricMap.getSplitForValue()]]. */
  private val MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES: Int = 8

  /**
   * The outcome of [[MetricMap.getSplitForValue()]] (and of the private `getEntrySplit` as well).
   *
   * @param splitKey the split-point identified. May be equal to the low key of the requested Slice,
   *                 indicating that the desired value would be exceeded if the smallest splittable
   *                 prefix of the Slice were included. May be equal to the high key of the
   *                 requested Slice if the desired value can only be reached or approached if the
   *                 entirety of the Slice is included. Otherwise, returns a key between the bounds
   *                 of the requested Slice that results in a prefix with approximately the desired
   *                 value.
   * @param prefixApportionedValue the estimated apportioned value for
   *                              `[slice.lowInclusive, splitKey)`.
   */
  case class Split(splitKey: HighSliceKey, prefixApportionedValue: Double)

  /**
   * An entry in a metric map indicates the total value of the metric ("value") across a particular
   * slice range. Concrete subclasses (e.g. [[LoadMap.Entry]]) name the metric.
   */
  sealed abstract class Entry {

    /** Range of keys for which value was observed. Non-empty. */
    def slice: Slice

    /** Value of the metric attributed to [[slice]]. Non-negative and finite. */
    def value: Double
  }

  /**
   * Supports building an immutable metric map of type `M`. Concrete builders expose the methods for
   * adding values under metric-specific names (e.g. [[LoadMap.Builder.putLoad]]).
   *
   * @param createEntry creates an entry of the concrete type with the given slice and value.
   * @param createMap creates the map from its complete, ordered entries.
   */
  abstract class Builder[E <: Entry, M <: MetricMap[E]] private[algorithm] (
      createEntry: (Slice, Double) => E,
      createMap: SliceMap[E] => M) {

    /** Entries recorded so far. See remarks on `MetricMap.sliceMap`. */
    private val entries = mutable.ArrayBuffer[E]()

    /** Builds a map with all values recorded on the builder. */
    final def build(): M = {
      // First, build a map with gaps.
      val gappyMap: SliceMap[GapEntry[E]] = SliceMap.createFromOrderedDisjointEntries(
        entries.toSeq.sortBy(ENTRY_SLICE_ACCESSOR),
        ENTRY_SLICE_ACCESSOR
      )

      // Next, fill in the gaps with 0-value entries.
      val map: SliceMap[E] = new SliceMap[E](
        gappyMap.entries.map {
          case GapEntry.Gap(slice: Slice) => createEntry(slice, 0)
          case GapEntry.Some(entry) => entry
        },
        ENTRY_SLICE_ACCESSOR
      )
      createMap(map)
    }

    /**
     * REQUIRES: no value is currently recorded that overlaps with any of the `entries` (this is a
     *   temporary limitation, as we currently have no need for more sophisticated behavior)
     *
     * Records the value of each entry for its slice.
     */
    protected final def putEntries(entries: E*): this.type = {
      for (entry <- entries) this.entries.append(entry)
      this
    }

    /**
     * REQUIRES: no value is currently recorded that overlaps with `entry.slice`.
     * REQUIRES: the keys in `keyValues` are contained in `entry.slice`.
     *
     * Records the given value for the given slice, along with values for specific keys as given in
     * `keyValues`. `entry.value` is the value for the slice ''excluding'' `keyValues`, and is
     * apportioned uniformly as per the [[MetricMap]] docs. For example, given
     * {{{
     *   putEntryWithKeyValues(Entry([0x40, 0x80), 40), SortedMap(0x50 -> 10))
     * }}}
     * then the resulting values are:
     * {{{
     *   [0x40, 0x50) -> 10
     *   [0x50, 0x50.successor()) -> 10
     *   [0x50.successor(), 0x80) -> 30
     * }}}
     */
    protected final def putEntryWithKeyValues(
        entry: E,
        keyValues: SortedMap[SliceKey, Double]): this.type = {
      // Track the remaining part of `entry`, along with the non-key value.
      var remainingEntry: E = entry

      val iterator = keyValues.iterator
      while (iterator.hasNext) {
        val (key, keyValue): (SliceKey, Double) = iterator.next()
        require(remainingEntry.slice.contains(key), s"$key must be in ${entry.slice}")

        // |---------| `remainingEntry.slice`
        //    ^ `key`
        // Result:
        // |--|        putEntries(`leftEntry`)
        //    ||       putEntries(Slice for `key`)
        //     |-----| `remainingEntry`
        if (key != remainingEntry.slice.lowInclusive) {
          // Add an entry up until `key`.
          val leftSlice = Slice(remainingEntry.slice.lowInclusive, key)
          // Intersect `entry` rather than `remainingEntry` to reduce accumulation of
          // apportioning/floating point errors.
          val leftEntry: E = intersectEntry(entry, leftSlice, createEntry) match {
            case None =>
              throw new AssertionError(s"Unexpected: $leftSlice did not intersect ${entry.slice}")
            case Some(leftEntry) => leftEntry
          }
          putEntries(leftEntry)
          remainingEntry = createEntry(
            Slice(key, entry.slice.highExclusive),
            // Handle floating point rounding (<internal bug>).
            Math.max(remainingEntry.value - leftEntry.value, 0)
          )
        }
        // Add an entry for `key` using the Slice [`key`, `key.successor()`).
        val successor: SliceKey = key.successor()
        putEntries(createEntry(Slice(key, successor), keyValue))
        if (successor == remainingEntry.slice.highExclusive) {
          // We've reached the end of `entry`.
          return this
        }

        remainingEntry =
          createEntry(Slice(successor, entry.slice.highExclusive), remainingEntry.value)
      }
      putEntries(remainingEntry)

      this
    }

  }

  /**
   * Returns the intersection of `entry` with the given slice. Returns None if `other` does not
   * intersect `entry`. Returns `entry` if `other` fully contains its slice. Otherwise, returns a
   * new entry (created using `createEntry`) with the intersecting slice range, and with value
   * apportioned to the intersection. See remarks on [[MetricMap]] for details of the apportioning
   * strategy.
   */
  private def intersectEntry[E <: Entry](
      entry: E,
      other: Slice,
      createEntry: (Slice, Double) => E): Option[E] = {
    val slice: Slice = entry.slice
    // Short-circuit before calling `slice.intersection` in the case that `other` is a
    // (non-strict) superset of `slice`. This avoids wasted allocation from `slice.intersection`
    // in the common case where the map has been built from an assignment's slices and is
    // then queried per slice in the same assignment.
    if (other.contains(slice)) {
      return Some(entry)
    }
    slice.intersection(other) match {
      case None => None // no intersection between `entry` and `other`
      case Some(intersection) =>
        // Associate all slice bounds with numbers so that we can determine their relative widths.
        // Numeric values are scaled so that the most significant bytes of all bounds have the
        // same magnitude.
        val length: Int =
          getMaxLengthOfSliceBounds(slice).max(getMaxLengthOfSliceBounds(intersection))
        val (sliceLow, sliceHigh): (BigInt, BigInt) = toBigIntRange(slice, length)
        val (intersectionLow, intersectionHigh): (BigInt, BigInt) =
          toBigIntRange(intersection, length)
        val sliceWidth: BigInt = sliceHigh - sliceLow
        val intersectionWidth: BigInt = intersectionHigh - intersectionLow

        // Figure out what ratio of the value can be attributed to the intersection.
        val intersectionRatio: Double =
          if (sliceWidth == 0) {
            // The slice width may be zero if its low and high keys map to equivalent numbers, e.g.
            // ["foo", "foo\0"). In such cases, we arbitrarily assume the intersection ratio is 0,
            // though 1 would be a fine choice as well.
            0
          } else {
            SliceKeyMath.getProperRatio(intersectionWidth, sliceWidth)
          }
        Some(createEntry(intersection, intersectionRatio * entry.value))
    }
  }

  /**
   * Determines how (and whether) to split `entry` to get `desiredValue` in its prefix
   * (the slice [entry.slice.lowInclusive, splitKey)).
   */
  private def getEntrySplit(entry: Entry, desiredValue: Double, isFirstEntry: Boolean): Split = {
    val slice: Slice = entry.slice
    val value: Double = entry.value
    if (desiredValue <= 0) {
      // If there is no desired value, include none of the Slice. This code path eliminates the
      // non-positive desiredValue case.
      return Split(slice.lowInclusive, 0)
    }
    if (value <= desiredValue) {
      // If this entry in its entirety is needed to reach the desired value, short-circuit. This
      // code path eliminates the non-positive value case.
      return Split(slice.highExclusive, value)
    }
    // Determine what ratio of the entry's value is required. Note that based on the checks above
    // we now know that `0 < desiredValue < value`, so desiredRatio ought to be in (0, 1), or
    // [0.0, 1.0] due to rounding errors.
    val desiredRatio: Double = desiredValue / value

    // Determine the length to use for the generated split key, which is based on the lengths of
    // the surrounding keys with MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES as a lower bound.
    // See the discussion in the `MetricMap` docs for more details on this policy and its
    // implications.
    val length: Int =
      getMaxLengthOfSliceBounds(slice).max(MIN_BYTES_LENGTH_FOR_SYNTHESIZED_KEY_BOUNDARIES)
    val (low, high): (BigInt, BigInt) = toBigIntRange(slice, length)

    // Synthesize a split key that is estimated to include `desiredRatio` of `value` in the
    // prefix.
    val split: BigInt = low + SliceKeyMath.multiplyByProperRatio(high - low, desiredRatio)
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
      // split, depending on which choice brings us closer to the desired value. If both choices
      // bring us equally close to the desired value, we prefer to introduce a split when
      // possible: if this is the first entry explored in a `MetricMap.getSplitForValue` call (per
      // `isFirstEntry`), we include the entire entry, and otherwise we exclude it.
      val errorWith: Double = (value - desiredValue).abs
      val errorWithout: Double = desiredValue
      val include: Boolean = errorWith < errorWithout || (errorWith == errorWithout && isFirstEntry)
      val split = if (include) Split(slice.highExclusive, value) else Split(slice.lowInclusive, 0)
      return split
    }
    val splitKey = SliceKeyHelper.fromBigInt(magnitude = split, length)

    // Due to the limited precision afforded by `length`, the actual ratio of value apportioned to
    // the prefix may be different from `desiredRatio`. Compute the actual ratio and then the
    // actual apportioned value based on the chosen split key.
    val prefixRatio: Double = SliceKeyMath.getProperRatio(split - low, high - low)
    val prefixApportionedValue: Double = value * prefixRatio
    Split(splitKey, prefixApportionedValue)
  }

  /**
   * REQUIRES: length >= slice.lowInclusive.length
   * REQUIRES: slice.highExclusive is infinity or slice.highExclusive length is less than `length`
   *
   * Converts the given Slice bounds to their numeric values (see
   * [[SliceKeyMath.toBigIntWithLength]]).
   *
   * Examples:
   *
   *     toBigIntRange([0x040300 .. 0x0404), 4) => (0x04030000, 0x04040000)
   *     toBigIntRange([0x040300 .. ∞), 4) => (0x04030000, 0x0100000000)
   */
  private def toBigIntRange(slice: Slice, length: Int): (BigInt, BigInt) = {
    (
      SliceKeyMath.toBigIntWithLength(slice.lowInclusive, length),
      SliceKeyMath.toBigIntWithLength(slice.highExclusive, length)
    )
  }

  /**
   * Returns the maximum length of slice bounds. This length is used when translating keys to
   * (comparable) numbers.
   */
  private def getMaxLengthOfSliceBounds(slice: Slice): Int = {
    val lowLength = SliceKeyMath.byteLength(slice.lowInclusive)
    val highLength = SliceKeyMath.byteLength(slice.highExclusive)
    lowLength.max(highLength)
  }
}

/**
 * A [[MetricMap]] tracking the primary rate metric ("load", e.g. queries per second) associated
 * with [[Slice]]s.
 *
 * @param sliceMap Slice map tracking load within slice ranges.
 */
final class LoadMap private[LoadMap] (sliceMap: SliceMap[LoadMap.Entry])
    extends MetricMap[LoadMap.Entry](typeName = "LoadMap", sliceMap, LoadMap.Entry) {

  /**
   * Returns the load attributed to the given slice. Load is apportioned for intersecting slices in
   * the map (see discussion in [[MetricMap.subRange]]).
   */
  def getLoad(slice: Slice): Double = getValue(slice)

  /**
   * REQUIRES: `desiredLoad` is a non-negative, finite, number
   *
   * Determines how to split `slice` such that its prefix has estimated load close to `desiredLoad`
   * (see [[MetricMap.getSplitForValue]]).
   */
  def getSplit(slice: Slice, desiredLoad: Double): Split = getSplitForValue(slice, desiredLoad)

  /**
   * Returns a new [[LoadMap]] where `addedLoad` is uniformly distributed across (and added to) all
   * Slices in the current map. Load is distributed using the apportioning strategy described in
   * [[MetricMap]] docs. For example, if the current load map is:
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
    val adjustedEntries = Vector.newBuilder[LoadMap.Entry]
    adjustedEntries.sizeHint(sliceMap.entries.size)
    for (entry: LoadMap.Entry <- sliceMap.entries) {
      val slice: Slice = entry.slice

      // Determine the fraction of `load` to apportion to this entry. We reuse the existing
      // `getLoad` function against the uniform load map, which has total load 1, to determine this
      // ratio.
      val ratio: Double = UNIFORM_LOAD_MAP.getLoad(slice)
      val apportionedUniformLoad: Double = addedLoad * ratio
      val adjustedLoad: Double = entry.load + apportionedUniformLoad
      adjustedEntries += LoadMap.Entry(slice, adjustedLoad)
    }
    new LoadMap(new SliceMap[LoadMap.Entry](adjustedEntries.result(), ENTRY_SLICE_ACCESSOR))
  }
}

object LoadMap {

  /**
   * REQUIRES: slice is non-empty
   * REQUIRES: load is a non-negative, finite, number
   *
   * An entry in a load map indicates the total load across a particular slice range.
   *
   * @param slice range of keys for which load was observed
   * @param load load attributed to the slice
   */
  final case class Entry(slice: Slice, load: Double) extends MetricMap.Entry {
    MetricMeasurement.requireValidMeasurement(load)

    override def value: Double = load
  }

  /** See [[MetricMap.Split]]. */
  type Split = MetricMap.Split
  val Split: MetricMap.Split.type = MetricMap.Split

  /** Builds a [[LoadMap]]. See [[MetricMap.Builder]]. */
  final class Builder private[LoadMap] ()
      extends MetricMap.Builder[Entry, LoadMap](Entry, new LoadMap(_)) {

    /**
     * REQUIRES: no load is currently recorded that overlaps with any of the `entries`.
     *
     * Records the load of each entry for its slice.
     */
    def putLoad(entries: Entry*): this.type = putEntries(entries: _*)

    /**
     * REQUIRES: no load is currently recorded that overlaps with `entry.slice`.
     * REQUIRES: the keys in `keyLoads` are contained in `entry.slice`.
     *
     * Records the load for `entry.slice` excluding `keyLoads`, plus the load of each key in
     * `keyLoads` (see [[MetricMap.Builder.putEntryWithKeyValues]]).
     */
    def putLoad(entry: Entry, keyLoads: KeyLoadMap): this.type =
      putEntryWithKeyValues(entry, keyLoads)
  }

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

  /** A load map with uniform load in the full key space. The total load is 1. */
  val UNIFORM_LOAD_MAP: LoadMap = LoadMap.newBuilder().putLoad(Entry(Slice.FULL, 1.0)).build()

  /**
   * Divides the full [[SliceKey]] space into `sliceCount` slices of equal "width", where width
   * respects the definition used for apportioning in [[MetricMap]]. For example, if `sliceCount` is
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

  /** Creates a [[LoadMap]] builder. */
  def newBuilder(): Builder = new Builder()
}

/**
 * A [[MetricMap]] tracking the primary gauge metric ("size", e.g. bytes of memory) associated with
 * [[Slice]]s. Each entry's value is the size of its Slice. Size is borne in full by each replica
 * of a Slice. See<internal link>.
 *
 * @param sliceMap Slice map tracking size within slice ranges.
 */
final class SizeMap private[SizeMap] (sliceMap: SliceMap[SizeMap.Entry])
    extends MetricMap[SizeMap.Entry](typeName = "SizeMap", sliceMap, SizeMap.Entry) {

  /**
   * Returns the size attributed to the given slice. Size is apportioned for intersecting slices in
   * the map (see discussion in [[MetricMap.subRange]]).
   */
  def getSize(slice: Slice): Double = getValue(slice)

  /**
   * REQUIRES: `desiredSize` is a non-negative, finite, number
   *
   * Determines how to split `slice` such that its prefix has estimated size close to `desiredSize`
   * (see [[MetricMap.getSplitForValue]]).
   */
  def getSplit(slice: Slice, desiredSize: Double): Split = getSplitForValue(slice, desiredSize)
}

object SizeMap {

  /**
   * REQUIRES: slice is non-empty
   * REQUIRES: size is a non-negative, finite, number
   *
   * An entry in a size map indicates the total size across a particular slice range.
   *
   * @param slice range of keys for which size was observed
   * @param size size attributed to the slice
   */
  final case class Entry(slice: Slice, size: Double) extends MetricMap.Entry {
    MetricMeasurement.requireValidMeasurement(size)

    override def value: Double = size
  }

  /** See [[MetricMap.Split]]. */
  type Split = MetricMap.Split
  val Split: MetricMap.Split.type = MetricMap.Split

  /** Builds a [[SizeMap]]. See [[MetricMap.Builder]]. */
  final class Builder private[SizeMap] ()
      extends MetricMap.Builder[Entry, SizeMap](Entry, new SizeMap(_)) {

    /**
     * REQUIRES: no size is currently recorded that overlaps with any of the `entries`.
     *
     * Records the size of each entry for its slice.
     */
    def putSize(entries: Entry*): this.type = putEntries(entries: _*)
  }

  /** Creates a [[SizeMap]] builder. */
  def newBuilder(): Builder = new Builder()
}
