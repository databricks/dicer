package com.databricks.caching.util

import scala.concurrent.duration._

import com.google.common.math.LongMath.{saturatedAdd, saturatedSubtract}

import com.databricks.caching.util.TickerTime.NANOS_PER_SECOND

/**
 * A typed class that provides nano second precision along with relational, and saturation
 * arithmetic operators. Used to represent the time in nanoseconds since the beginning of some
 * fixed, arbitrary, origin time. The origin time is different in every process. The origin time may
 * be in the future, so [[nanos]] may be a negative value. See [[System.nanoTime()]] for additional
 * background.
 *
 * Saturation arithmetic: if an operator logically results in a value greater than `MAX`, returns
 * `MAX` rather than overflowing, and if an operator results in a value less than `MIN`, returns
 * `MIN` rather than underflowing (a.k.a. negative overflowing).
 *
 * @param nanos The number of nanoseconds since the origin time.
 */
case class TickerTime(nanos: Long) extends AnyVal with Ordered[TickerTime] {
  import TickerTime.MIN_FINITE_DURATION

  /** Adds `d` to this. Saturated */
  def +(d: FiniteDuration): TickerTime = {
    TickerTime.ofNanos(saturatedAdd(this.nanos, d.toNanos))
  }

  /** Subtracts `t` from this. Saturated. */
  def -(t: TickerTime): FiniteDuration = {
    val nanos: Long = saturatedSubtract(this.nanos, t.nanos)
    if (nanos == Long.MinValue) {
      MIN_FINITE_DURATION
    } else {
      nanos.nanoseconds
    }
  }

  /** Subtracts `d` from this. Saturated. */
  def -(d: FiniteDuration): TickerTime = {
    TickerTime.ofNanos(saturatedSubtract(this.nanos, d.toNanos))
  }

  /** Prints the time in seconds, including all digits after the decimal. */
  override def toString: String = {
    val seconds = Math.abs(nanos / NANOS_PER_SECOND)
    val subsecondNanos = Math.abs(nanos % NANOS_PER_SECOND)
    val sign = if (nanos < 0) {
      "-"
    } else {
      ""
    }

    f"$sign$seconds.$subsecondNanos%09d"
  }

  override def compare(that: TickerTime): Int = java.lang.Long.compare(this.nanos, that.nanos)
}

/** Factory for [[TickerTime]] instances. */
object TickerTime {

  /** The minimum representable ticker time. */
  val MIN: TickerTime = TickerTime.ofNanos(Long.MinValue)

  /** The maximum representable ticker time. */
  val MAX: TickerTime = TickerTime.ofNanos(Long.MaxValue)

  /**
   * Minimum representable [[FiniteDuration]]. Returned as "saturated" result when +/- operator
   * underflows. Note that this is one greater than [[Long.MinValue]] nanoseconds, as that value is
   * not permitted by the [[FiniteDuration]] implementation.
   */
  val MIN_FINITE_DURATION: FiniteDuration = (Long.MinValue + 1).nanoseconds

  /**
   * Maximum representable [[FiniteDuration]]. Returned as "saturated" result when +/- operator
   * overflows.
   */
  val MAX_FINITE_DURATION: FiniteDuration = Long.MaxValue.nanoseconds

  private val NANOS_PER_SECOND: Long = 1000 * 1000 * 1000

  /** Returns a ticker time representing `nanos` nanoseconds since the origin time. */
  def ofNanos(nanos: Long): TickerTime = TickerTime(nanos)
}
