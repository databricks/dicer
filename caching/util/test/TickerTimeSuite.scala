package com.databricks.caching.util

import scala.concurrent.duration._

import com.databricks.caching.util.TickerTime.{MAX_FINITE_DURATION, MIN_FINITE_DURATION}
import TestUtils.checkComparisons
import com.databricks.testing.DatabricksTest

class TickerTimeSuite extends DatabricksTest {

  /**
   * Some ticker times, in sorted order, that are designed to exercise various edge cases (e.g.,
   * overflows).
   */
  val SORTED_TICKER_TIMES: IndexedSeq[TickerTime] = IndexedSeq(
    TickerTime.ofNanos(Long.MinValue),
    TickerTime.ofNanos(Long.MinValue + 1),
    TickerTime.ofNanos(Long.MinValue + 2),
    TickerTime.ofNanos(-47),
    TickerTime.ofNanos(-1),
    TickerTime.ofNanos(0),
    TickerTime.ofNanos(1),
    TickerTime.ofNanos(35),
    TickerTime.ofNanos(36),
    TickerTime.ofNanos(42),
    TickerTime.ofNanos(Long.MaxValue - 2),
    TickerTime.ofNanos(Long.MaxValue - 1),
    TickerTime.ofNanos(Long.MaxValue)
  )

  /** Some finite durations that are designed to exercise various edge cases (e.g., overflows). */
  val FINITE_DURATIONS: IndexedSeq[FiniteDuration] = IndexedSeq(
    MIN_FINITE_DURATION,
    (MIN_FINITE_DURATION.toNanos + 1).nanoseconds,
    (MIN_FINITE_DURATION.toNanos + 2).nanoseconds,
    (-1).day,
    (-42).nanoseconds,
    (-1).nanoseconds,
    Duration.Zero,
    1.nanosecond,
    35.nanoseconds,
    36.nanoseconds,
    1.hour,
    (MAX_FINITE_DURATION.toNanos - 2).nanoseconds,
    (MAX_FINITE_DURATION.toNanos - 1).nanoseconds,
    MAX_FINITE_DURATION
  )

  test("TickerTime ordering") {
    // Test plan: Check that comparison operators (==, <, etc.) work as expected for TickerTime
    // values.
    checkComparisons(SORTED_TICKER_TIMES)
  }

  test("TickerTime bounds") {
    // Test plan: verifies expected values for constant bounds defined on TickerTime.
    assert(TickerTime.MAX == TickerTime.ofNanos(Long.MaxValue))
    assert(TickerTime.MAX.nanos == Long.MaxValue)
    assert(TickerTime.MIN == TickerTime.ofNanos(Long.MinValue))
    assert(TickerTime.MIN.nanos == Long.MinValue)
    assert(TickerTime.MAX_FINITE_DURATION == Long.MaxValue.nanoseconds)
    assert(TickerTime.MAX_FINITE_DURATION.toNanos == Long.MaxValue)

    // Special consideration: MIN_FINITE_DURATION looks off by one because of a somewhat arbitrary
    // looking decision in the FiniteDuration implementation to use -max as the min bound.
    assert(TickerTime.MIN_FINITE_DURATION == (-Long.MaxValue).nanoseconds)
    assert(TickerTime.MIN_FINITE_DURATION.toNanos == Long.MinValue + 1)

    // The minimum representable `FiniteDuration` value is sufficiently surprising that we test our
    // beliefs here.
    assertThrows[IllegalArgumentException] {
      Long.MinValue.nanoseconds
    }
    // The same bounds apply whichever units are used.
    val minMicros: Long = -Long.MaxValue / 1000
    minMicros.microseconds // ok
    assertThrows[IllegalArgumentException] {
      (minMicros - 1).microseconds // fails
    }
  }

  /** Performs saturated conversion from BigInt to [[FiniteDuration]]. */
  private def convertToFiniteDuration(nanos: BigInt): FiniteDuration = {
    if (nanos <= MIN_FINITE_DURATION.toNanos) {
      MIN_FINITE_DURATION
    } else if (nanos >= MAX_FINITE_DURATION.toNanos) {
      MAX_FINITE_DURATION
    } else {
      nanos.toLong.nanoseconds
    }
  }

  /** Performs saturated conversion from BigInt to [[TickerTime]]. */
  private def convertToTickerTime(nanos: BigInt): TickerTime = {
    if (nanos <= Long.MinValue) {
      TickerTime.MIN
    } else if (nanos >= Long.MaxValue) {
      TickerTime.MAX
    } else {
      TickerTime.ofNanos(nanos.toLong)
    }
  }

  test("TickerTime - TickerTime") {
    // Test plan: Test basic examples, underflow, overflow, and permutation tests.
    assert(TickerTime.ofNanos(35) - TickerTime.ofNanos(23) == 12.nanoseconds)
    assert(TickerTime.MIN - TickerTime.ofNanos(1) == MIN_FINITE_DURATION)
    assert(TickerTime.MAX - TickerTime.ofNanos(-1) == MAX_FINITE_DURATION)
    assert(TickerTime.ofNanos(1) - TickerTime.MIN == MAX_FINITE_DURATION)

    // Permutations.
    for (x <- SORTED_TICKER_TIMES;
      y <- SORTED_TICKER_TIMES) {
      val actual = x - y

      // Use BigInt arithmetic as baseline.
      val xNanos: BigInt = x.nanos
      val yNanos: BigInt = y.nanos
      val expectedNanos: BigInt = xNanos - yNanos
      val expected: FiniteDuration = convertToFiniteDuration(expectedNanos)
      assert(actual == expected)
    }
  }

  test("TickerTime +/- FiniteDuration") {
    // Test plan: Test addition/subtraction operations for Time.
    assert(TickerTime.ofNanos(35) + 1.nanosecond == TickerTime.ofNanos(36))
    assert(TickerTime.ofNanos(35) - 5.nanoseconds == TickerTime.ofNanos(30))

    // Overflow cases.
    assert(TickerTime.MAX + 1.nanoseconds == TickerTime.MAX)
    assert(TickerTime.MAX + MAX_FINITE_DURATION == TickerTime.MAX)
    assert(TickerTime.MAX - (-1).nanoseconds == TickerTime.MAX)

    // Underflow cases.
    assert(TickerTime.MIN + (-1).nanoseconds == TickerTime.MIN)
    assert(TickerTime.MIN - 1.nanoseconds == TickerTime.MIN)
    assert(TickerTime.ofNanos(-1) + MIN_FINITE_DURATION == TickerTime.MIN)
    assert(TickerTime.ofNanos(-1) - MAX_FINITE_DURATION == TickerTime.MIN)
    assert(TickerTime.MIN - MAX_FINITE_DURATION == TickerTime.MIN)

    // Permutations.
    for (x <- SORTED_TICKER_TIMES;
      y <- FINITE_DURATIONS) {
      // Use BigInt arithmetic as baseline.
      val xNanos: BigInt = x.nanos
      val yNanos: BigInt = y.toNanos
      val expectedSum: BigInt = xNanos + yNanos
      val expectedDiff: BigInt = xNanos - yNanos
      assert(x + y == convertToTickerTime(expectedSum))
      assert(x - y == convertToTickerTime(expectedDiff))
    }
  }

  test("toString") {
    // Test plan: verify toString produces a seconds representation

    assert(TickerTime.ofNanos(1L).toString == "0.000000001")
    assert(TickerTime.ofNanos(-1L).toString == "-0.000000001")

    assert(TickerTime.ofNanos(12000000589L).toString == "12.000000589")
    assert(TickerTime.ofNanos(-12000000589L).toString == "-12.000000589")

    assert(TickerTime.ofNanos(12000000000L).toString == "12.000000000")
    assert(TickerTime.ofNanos(12500000000L).toString == "12.500000000")
  }
}
