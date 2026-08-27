package com.databricks.caching.util

import com.databricks.caching.util.proto.HyperLogLogP
import com.google.protobuf.ByteString
import com.databricks.testing.DatabricksTest

class HyperLogLogSuite extends DatabricksTest {

  /** Returns a HyperLogLog containing keys created from ints in the given range. */
  def hllOfRange(start: Int, end: Int): HyperLogLog = {
    val hll = new HyperLogLog()
    for (i <- 0 until end) {
      hll.add(intToKey(i))
    }
    hll
  }

  def assertApproximate(actual: Long, expected: Long): Unit = {
    val threshold = 0.18

    val error = (expected.toDouble - actual.toDouble).abs / expected.toDouble
    assert(
      error < threshold,
      s"actual=${actual}, expected=${expected}, error ${f"${error * 100}%.2f"}% is over threshold"
    )
  }

  def intToKey(x: Int): ByteString = {
    ByteString.copyFrom(BigInt(x.toLong).toByteArray)
  }

  test("empty zero estimate") {
    // Test plan: verify that a HyperLogLog with no keys estimates 0.
    val hll = new HyperLogLog()
    assert(hll.estimate() == 0)
  }

  test("low-cardinality estimate") {
    // Test plan: verify that low-cardinality estimates are within an error margin of the true
    // cardinality.
    //
    // Cardinalities under ~5m/2 use the linearcounting so they're measured differently from high
    // cardinalities.
    val hll = new HyperLogLog()

    for (i <- 0 to 15000) {
      hll.add(intToKey(i))
      hll.add(intToKey(i))

      assertApproximate(hll.estimate(), i + 1)
    }
  }

  test("high-cardinality estimate") {
    // Test plan: verify that high-cardinality estimates are within an error margin of the true
    // cardinality.
    //
    // Cardinalities over ~5m/2 use the hyperloglog's estimate, so they're measured differently from
    // low cardinalities.
    val hll = new HyperLogLog()

    for (i <- 0 to 1000000) {
      hll.add(intToKey(i))
      hll.add(intToKey(i))

      // Only check occasionally to keep the test cheap.
      if (i % 1000 == 0) {
        assertApproximate(hll.estimate(), i + 1)
      }
    }
  }

  test("merge non-overlapping") {
    // Test plan: verify that merging non-overlapping sets produces an estimate that's approximately
    // the sum of the two sizes.
    val a = hllOfRange(0, 1000)
    val b = hllOfRange(1000, 2000)

    a.merge(b)

    assertApproximate(a.estimate(), 2000)
  }

  test("merge overlapping") {
    // Test plan: verify that merging overlapping sets does not double-count the shared keys.
    val a = hllOfRange(0, 1000)
    val b = hllOfRange(500, 1500)

    a.merge(b)

    assertApproximate(a.estimate(), 1500)
  }

  test("proto roundtrip") {
    // toProto's output should be decodable by fromProto.
    val a = hllOfRange(50000, 52000)
    val b = HyperLogLog.fromProto(a.toProto())

    assert(a.estimate() == b.estimate())
  }

  test("fromProto compat") {
    // Test plan: verify that fromProto is backwards compatible, continuing to be able to correctly
    // parse an old encoded proto.

    // To make one:
    //   val a = hllOfRange(30000, 1030000)
    //   val bytes = a.toProto().toByteArray
    //   val hexString = bytes.map("0x%02X" format _).mkString("Array(", ", ", ")")
    //   println(hexString)

    // Here as a literal to ensure that the library continues to be able to deserialize this format.
    val encoded: Array[Byte] = Array(
      0x0A,
      0x80.toByte,
      0x02,
      0x0B,
      0x0F,
      0x0D,
      0x0D,
      0x0C,
      0x0D,
      0x0B,
      0x0E,
      0x0C,
      0x0B,
      0x11,
      0x10,
      0x0F,
      0x0E,
      0x0E,
      0x0E,
      0x0D,
      0x0C,
      0x0E,
      0x0E,
      0x0C,
      0x0C,
      0x11,
      0x0C,
      0x0E,
      0x0B,
      0x0E,
      0x0D,
      0x0C,
      0x0B,
      0x0C,
      0x0C,
      0x11,
      0x0B,
      0x10,
      0x0C,
      0x0C,
      0x0D,
      0x0D,
      0x0E,
      0x0F,
      0x0D,
      0x0D,
      0x0D,
      0x0B,
      0x0E,
      0x0F,
      0x0C,
      0x0D,
      0x0F,
      0x0E,
      0x0B,
      0x10,
      0x0C,
      0x0C,
      0x0E,
      0x0C,
      0x0C,
      0x0F,
      0x0D,
      0x0B,
      0x0B,
      0x0E,
      0x0D,
      0x0C,
      0x0E,
      0x0C,
      0x0C,
      0x0E,
      0x0B,
      0x0B,
      0x0C,
      0x0B,
      0x0B,
      0x0C,
      0x0D,
      0x0F,
      0x10,
      0x11,
      0x0E,
      0x0C,
      0x0E,
      0x0F,
      0x0D,
      0x0C,
      0x0D,
      0x0C,
      0x0B,
      0x10,
      0x0E,
      0x0B,
      0x10,
      0x0C,
      0x0B,
      0x0D,
      0x0F,
      0x0E,
      0x12,
      0x0E,
      0x0F,
      0x0F,
      0x0E,
      0x0D,
      0x0E,
      0x0D,
      0x0F,
      0x14,
      0x0F,
      0x0E,
      0x0F,
      0x0F,
      0x0D,
      0x0D,
      0x0C,
      0x0A,
      0x0C,
      0x0C,
      0x0C,
      0x0C,
      0x0C,
      0x0D,
      0x11,
      0x0C,
      0x0D,
      0x0C,
      0x0E,
      0x10,
      0x0C,
      0x0E,
      0x0E,
      0x0E,
      0x0E,
      0x0D,
      0x0B,
      0x10,
      0x0D,
      0x0E,
      0x0C,
      0x11,
      0x0A,
      0x11,
      0x0E,
      0x0B,
      0x0F,
      0x0D,
      0x0D,
      0x14,
      0x0D,
      0x0C,
      0x0B,
      0x0F,
      0x0D,
      0x0E,
      0x0D,
      0x0E,
      0x0D,
      0x0E,
      0x0C,
      0x0D,
      0x0B,
      0x0F,
      0x0B,
      0x0D,
      0x0F,
      0x0D,
      0x0C,
      0x0E,
      0x0D,
      0x0C,
      0x0B,
      0x0D,
      0x0A,
      0x0D,
      0x0B,
      0x0E,
      0x0C,
      0x0E,
      0x10,
      0x10,
      0x0C,
      0x0D,
      0x0E,
      0x10,
      0x0C,
      0x0E,
      0x0C,
      0x0D,
      0x11,
      0x0F,
      0x0E,
      0x0B,
      0x0F,
      0x0D,
      0x0E,
      0x0C,
      0x16,
      0x0E,
      0x0D,
      0x0C,
      0x14,
      0x0B,
      0x0D,
      0x0C,
      0x0D,
      0x0D,
      0x0C,
      0x0D,
      0x0E,
      0x0E,
      0x0E,
      0x0E,
      0x0E,
      0x0F,
      0x0F,
      0x10,
      0x0E,
      0x0B,
      0x0D,
      0x0D,
      0x0C,
      0x0B,
      0x0D,
      0x0C,
      0x0D,
      0x0F,
      0x0D,
      0x0E,
      0x0C,
      0x0B,
      0x12,
      0x0D,
      0x14,
      0x0D,
      0x0D,
      0x0E,
      0x0D,
      0x0D,
      0x0B,
      0x0E,
      0x0C,
      0x0D,
      0x0D,
      0x0E,
      0x0E,
      0x0C,
      0x0E,
      0x11,
      0x0C,
      0x0C,
      0x0B,
      0x0D,
      0x0C,
      0x0C,
      0x0F,
      0x12,
      0x0D
    )
    val b = HyperLogLog.fromProto(HyperLogLogP.parseFrom(encoded))

    assert(b.estimate() == 1089507)
  }

  namedGridTest("fromProto rejects invalid protos")(
    Seq(
      ("too few registers", ByteString.copyFrom(new Array[Byte](HyperLogLog.forTest.M - 1))),
      ("too many registers", ByteString.copyFrom(new Array[Byte](HyperLogLog.forTest.M + 1))),
      // A register can hold at most 64 - P + 1 leading zeros plus one; anything larger is nonsense.
      ("large register value", {
        val registers = new Array[Byte](HyperLogLog.forTest.M)
        registers(0) = (64 - HyperLogLog.forTest.P + 2).toByte
        ByteString.copyFrom(registers)
      }),
      ("negative register value", {
        val registers = new Array[Byte](HyperLogLog.forTest.M)
        registers(0) = -1
        ByteString.copyFrom(registers)
      })
    )
  ) { inner =>
    // Test plan: verify that fromProto rejects malformed protos.
    intercept[IllegalArgumentException] {
      HyperLogLog.fromProto(HyperLogLogP(inner = Some(inner)))
    }
  }
}
