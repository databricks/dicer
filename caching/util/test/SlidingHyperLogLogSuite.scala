package com.databricks.caching.util

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import com.google.protobuf.ByteString
import com.databricks.testing.DatabricksTest

class SlidingHyperLogLogSuite extends DatabricksTest {
  val RETENTION = new FiniteDuration(60, TimeUnit.SECONDS)

  /** Adds integers in the range [start, end) converted to keys to the given SlidingHyperLogLog. */
  private def addRange(shll: SlidingHyperLogLog, now: TickerTime, start: Int, end: Int): Unit = {
    for (i: Int <- start until end) {
      shll.add(now, intToKey(i))
    }
  }

  /**
   * Converts an int to a ByteString for testing convenience. The ByteString will be unique to the
   * input compared with other keys made with intToKey.
   */
  private def intToKey(x: Int): ByteString = {
    ByteString.copyFrom(BigInt(x.toLong).toByteArray)
  }

  /** Asserts that `actual` is within a few percent of `expected`. */
  private def assertApproximate(actual: Long, expected: Long): Unit = {
    if (expected == 0) {
      assert(actual == 0)
      return
    }

    // HyperLogLog produces estimates, so allow a tolerance. For the default precision this class
    // uses, a few percent is expected; we allow a bit more headroom to keep the test non-flaky.
    val TOLERANCE_FRACTION: Double = 0.15
    TestUtils.assertApproxEqual(
      actual.toDouble,
      expected.toDouble,
      expected.toDouble * TOLERANCE_FRACTION
    )
  }

  test("empty shll reports zero") {
    // Test plan: verify that a fresh SlidingHyperLogLog that has observed no keys reports a
    // cardinality estimate of zero.
    val shll = new SlidingHyperLogLog(RETENTION)

    assert(shll.recent(TickerTime.ofNanos(0)).estimate() == 0)
  }

  test("counts distinct keys within a single bucket") {
    // Test plan: verify that keys observed without advancing the clock all land in the same bucket
    // and are counted as distinct. Observe 1000 distinct keys and assert the estimate is ~1000.
    val shll = new SlidingHyperLogLog(RETENTION)
    val now = TickerTime.ofNanos(0)

    addRange(shll, now, 0, 1000)

    assertApproximate(shll.recent(now).estimate(), 1000)
  }

  test("does not double-count repeated keys") {
    // Test plan: verify that observing the same set of keys more than once does not inflate the
    // estimate. Observe the same 1000 keys twice and assert the estimate is still ~1000.
    val shll = new SlidingHyperLogLog(RETENTION)
    val now = TickerTime.ofNanos(0)

    addRange(shll, now, 0, 1000)
    addRange(shll, now, 0, 1000)

    assertApproximate(shll.recent(now).estimate(), 1000)
  }

  test("merges overlapping buckets within the retention window") {
    // Test plan: verify that observing overlapping sets of keys in different time buckets are not
    // double-counted in the estimate.

    val shll = new SlidingHyperLogLog(RETENTION)
    var now = TickerTime.ofNanos(0)

    addRange(shll, now, 0, 1000)
    now += RETENTION / 2
    addRange(shll, now, 500, 1500)

    assertApproximate(shll.recent(now).estimate(), 1500)
  }

  test("merge into bucket") {
    // Test plan: verify that merging a full HLL into the sliding HLL is counted appropriately.

    val shll = new SlidingHyperLogLog(RETENTION)
    val now = TickerTime.ofNanos(0)

    addRange(shll, now, 0, 1000)
    val hll = new HyperLogLog()
    for (i <- 500 until 1500) {
      hll.add(intToKey(i))
    }
    shll.merge(now, hll)

    assertApproximate(shll.recent(now).estimate(), 1500)
  }

  test("drops observations past the retention window") {
    // Test plan: verify that time buckets older than the retention window stop contributing to the
    // estimate. Add keys over time, continue advancing the clock, and observe the estimate no
    // longer include them once they become too old.

    val shll = new SlidingHyperLogLog(RETENTION)
    var now = TickerTime.ofNanos(0)

    addRange(shll, now, 0, 1000)
    assertApproximate(shll.recent(now).estimate(), 1000)

    // t= Times are shown relative to RETENTION.
    //
    //             [0, 1000)
    //             |
    // t =         0
    // now =       ^
    // retention = ]
    now += RETENTION / 2
    assertApproximate(shll.recent(now).estimate(), 1000)
    addRange(shll, now, 500, 1500)
    assertApproximate(shll.recent(now).estimate(), 1500)

    now += RETENTION / 4
    //             [0, 1000)       [500, 1500)
    //             |       '       |       '
    // t =         0               0.5
    // now =                               ^
    // retention = ------------------------]
    assertApproximate(shll.recent(now).estimate(), 1500)
    addRange(shll, now, 1250, 1750)
    assertApproximate(shll.recent(now).estimate(), 1750)

    now += RETENTION / 2
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '
    // t =         0               0.5             1
    // now =                                               ^
    // retention =         [-------------------------------]

    // [0, 1000) fallen off
    assertApproximate(shll.recent(now).estimate(), 1250)

    now += 3 * RETENTION / 8
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '       |       '       |
    // t =         0               0.5             1               1.5             2
    // now =                                                            ^
    // retention =                      [-------------------------------]

    // [500, 1500) fallen off
    assertApproximate(shll.recent(now).estimate(), 500)

    now += RETENTION / 2
    //             [0, 1000)       [500, 1500)
    //                                     [1250, 1750)
    //             |       '       |       '       |       '       |       '       |
    // t =         0               0.5             1               1.5             2
    // now =                                                                            ^
    // retention =                                      [-------------------------------]

    // [1250, 1750) fallen off
    assertApproximate(shll.recent(now).estimate(), 0)
  }

  test("nonmonotonic time") {
    // Test plan: verify that nothing bad happens if the clock goes backwards, we just keep reusing
    // the same time bucket until it catches up again.

    val shll = new SlidingHyperLogLog(RETENTION)
    var now = TickerTime.ofNanos((RETENTION * 2).toNanos)

    // Bucket gets greated at t=RETENTION*2
    addRange(shll, now, 0, 1000)

    // Move back to t=0
    now -= RETENTION * 2
    assertApproximate(shll.recent(now).estimate(), 1000)
    addRange(shll, now, 500, 1500)
    assertApproximate(shll.recent(now).estimate(), 1500)
    now += RETENTION
    assertApproximate(shll.recent(now).estimate(), 1500)
    now += RETENTION
    // Now back to t=RETENTION*2, the original bucket is still here.
    assertApproximate(shll.recent(now).estimate(), 1500)

    now += RETENTION * 2
    assertApproximate(shll.recent(now).estimate(), 0)
  }
}
