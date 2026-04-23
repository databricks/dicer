package com.databricks.dicer.common

import com.databricks.api.proto.dicer.friend.SliceP
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}

/** Utility methods for [[Slice]] that are internal (not part of the public API). */
object SliceHelper {

  /**
   * Creates a Slice for the given `proto`.
   *
   * @throws IllegalArgumentException if the proto is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: SliceP): Slice = {
    val lowInclusive = SliceKey.fromRawBytes(proto.getLowInclusive)
    proto.highExclusive match {
      case Some(highExclusiveBytes) =>
        val highExclusive = SliceKey.fromRawBytes(highExclusiveBytes)
        Slice(lowInclusive, highExclusive)
      case None =>
        // Unset `high_exclusive` proto field represents "Infinity".
        Slice.atLeast(lowInclusive)
    }
  }

  /** Internal extensions for [[Slice]]. */
  implicit class RichSlice(slice: Slice) {

    /** Converts `slice` to its proto form. */
    def toProto: SliceP = {
      new SliceP(
        lowInclusive = Some(slice.lowInclusive.bytes),
        highExclusive = slice.highExclusive match {
          case highExclusive: SliceKey => Some(highExclusive.bytes)
          case InfinitySliceKey =>
            // Unset `high_exclusive` proto field represents "Infinity".
            None
        }
      )
    }

    /**
     * Returns the intersection between Slices `slice` and `other`. Returns [[None]] if the slices
     * do not intersect.
     *
     * Differs from [[Range[].intersection()]] in the following ways:
     *
     *  - If the intersection is empty, returns [[None]]. The [[Range]] method in contrast returns
     *    either the "connecting" empty interval, or throws [[IllegalArgumentException]] if the
     *    ranges are not connected. For example, [a, b).intersection([b, c)) returns the empty
     *    connecting interval [b, b), while [a, b).intersection([c, d)) throws.
     *  - It is easier to observe (by construction) that this implementation results in valid
     *    [[Slice]]s, where the ranges are either closed-open or closed-unbounded.
     */
    def intersection(other: Slice): Option[Slice] = {
      val lowInclusive: SliceKey = if (slice.lowInclusive > other.lowInclusive) {
        slice.lowInclusive
      } else {
        other.lowInclusive
      }
      val highExclusive: HighSliceKey = if (slice.highExclusive < other.highExclusive) {
        slice.highExclusive
      } else {
        other.highExclusive
      }
      if (lowInclusive < highExclusive) Some(Slice(lowInclusive, highExclusive)) else None
    }
  }
}
