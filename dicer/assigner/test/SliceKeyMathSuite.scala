package com.databricks.dicer.assigner

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.testing.SliceTestUtils.identityKey
import com.databricks.dicer.external.{InfinitySliceKey, SliceKey}
import com.databricks.testing.DatabricksTest

class SliceKeyMathSuite extends DatabricksTest {

  test("byteLength returns the key's byte count, and zero for infinity") {
    // Test plan: Verify byteLength reports the number of bytes for finite keys and treats
    // InfinitySliceKey as contributing no bytes (so it never inflates a common length).
    assert(SliceKeyMath.byteLength(identityKey()) == 0)
    assert(SliceKeyMath.byteLength(identityKey(0x01, 0x02, 0x03)) == 3)
    assert(SliceKeyMath.byteLength(InfinitySliceKey) == 0)
  }

  test("toBigIntWithLength pads a finite key to the requested length") {
    // Test plan: Verify a finite key is converted to its big-endian magnitude, right-padded with
    // trailing zero bytes to `length` -- so `0x0403` at length 3 becomes `0x040300`.
    assert(SliceKeyMath.toBigIntWithLength(identityKey(0x04, 0x03), 2) == BigInt(0x0403))
    assert(SliceKeyMath.toBigIntWithLength(identityKey(0x04, 0x03), 3) == BigInt(0x040300))
    assert(SliceKeyMath.toBigIntWithLength(identityKey(0x04, 0x03), 4) == BigInt(0x04030000))
  }

  test("toBigIntWithLength maps infinity to one past the largest key of that length") {
    // Test plan: Verify InfinitySliceKey maps to 256^length, which is strictly greater than every
    // finite key that fits in `length` bytes (whose maximum is 256^length - 1).
    assert(SliceKeyMath.toBigIntWithLength(InfinitySliceKey, 1) == BigInt(0x0100))
    assert(SliceKeyMath.toBigIntWithLength(InfinitySliceKey, 3) == BigInt(0x01000000))
  }

  test("toBigIntWithLength keeps ordering consistent across differing key lengths") {
    // Test plan: Verify that padding to a common length makes magnitudes order like the keys
    // themselves. `0x02` (1 byte) sorts after `0x01 0x00` (2 bytes) lexicographically; naively
    // reading raw magnitudes (2 vs 256) would reverse that, but padding to a common length of 2
    // yields 0x0200 (512) vs 0x0100 (256), preserving the order.
    val shorterButLarger: SliceKey = identityKey(Array[Byte](0x02.toByte))
    val longerButSmaller: SliceKey = identityKey(0x01, 0x00)
    assert(shorterButLarger.compare(longerButSmaller) > 0)
    val commonLength = 2
    assert(
      SliceKeyMath.toBigIntWithLength(shorterButLarger, commonLength) >
      SliceKeyMath.toBigIntWithLength(longerButSmaller, commonLength)
    )
  }

  test("toBigIntWithLength rejects a key longer than the requested length") {
    // Test plan: Verify the precondition (length >= key length) is enforced.
    assertThrow[IllegalArgumentException]("key length must not exceed the common length") {
      SliceKeyMath.toBigIntWithLength(identityKey(0x04, 0x03), 1)
    }
  }

  test("getProperRatio returns the exact ratio when operands fit within Double's range") {
    // Test plan: Verify that getProperRatio returns exact results for representable ratios
    // with operands well below Double's exponent range.
    assert(SliceKeyMath.getProperRatio(BigInt(0), BigInt(4)) == 0.0)
    assert(SliceKeyMath.getProperRatio(BigInt(1), BigInt(4)) == 0.25)
    assert(SliceKeyMath.getProperRatio(BigInt(3), BigInt(4)) == 0.75)
    assert(SliceKeyMath.getProperRatio(BigInt(4), BigInt(4)) == 1.0)
  }

  test("getProperRatio stays accurate to Double precision for magnitudes that overflow a Double") {
    // Test plan: Verify the ratio is computed to within Double precision when both operands exceed
    // Double's exponent range (~2^1024), where the scaling path engages -- a naive toDouble would
    // turn the operands into Infinity.
    val denominator: BigInt = BigInt(1) << 2000
    val numerator: BigInt = denominator / 3
    val relativeError: Double =
      Math.abs(SliceKeyMath.getProperRatio(numerator, denominator) - (1.0 / 3.0))
    // Tolerance 2^-52 reflects a Double's ~53-bit precision but accounts for rounding errors.
    assert(relativeError <= Math.pow(2, -52))
  }

  test("getProperRatio rejects invalid arguments") {
    // Test plan: Verify the preconditions (0 <= lhs <= rhs, rhs > 0) are enforced.
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.getProperRatio(BigInt(-1), BigInt(4))
    }
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.getProperRatio(BigInt(1), BigInt(0))
    }
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.getProperRatio(BigInt(5), BigInt(4))
    }
  }

  test("multiplyByProperRatio scales a BigInt precisely by a ratio for low magnitudes") {
    // Test plan: Verify basic multiplications.
    assert(SliceKeyMath.multiplyByProperRatio(BigInt(100), 0.25) == BigInt(25))
    assert(SliceKeyMath.multiplyByProperRatio(BigInt(100), 0.0) == BigInt(0))
    assert(SliceKeyMath.multiplyByProperRatio(BigInt(100), 1.0) == BigInt(100))
  }

  test("multiplyByProperRatio preserves full precision at ratio 1 for large magnitudes") {
    // Test plan: Verify the ratio == 1 special case returns the multiplicand exactly, without
    // losing least significant bits through a Double round-trip. Use a magnitude far beyond
    // Double's 52-bit mantissa.
    val large: BigInt = (BigInt(1) << 200) + 1
    assert(SliceKeyMath.multiplyByProperRatio(large, 1.0) == large)
  }

  test("multiplyByProperRatio's loss stays within Double precision for large magnitudes") {
    // Test plan: Verify that the error on the scaled-multiply path stays within Double precision.
    //
    // Use a multiplicand with a magnitude that would overflow a naive toDouble and that has many
    // significant bits across its whole width -- not a clean power of two -- so the scaling
    // actually discards low bits and the product is genuinely approximate.
    val multiplicand: BigInt = (BigInt(1) << 2000) + (BigInt(1) << 1000) + BigInt(1234567)
    val result: BigInt = SliceKeyMath.multiplyByProperRatio(multiplicand, 0.75)
    val expected: BigInt = multiplicand * 3 / 4
    val relativeError: Double = SliceKeyMath.getProperRatio((result - expected).abs, expected)
    // Tolerance 2^-52 reflects a Double's ~53-bit precision but accounts for rounding errors.
    assert(relativeError <= Math.pow(2, -52))
  }

  test("multiplyByProperRatio rejects invalid arguments") {
    // Test plan: Verify the preconditions (multiplicand >= 0, ratio in [0, 1]) are enforced.
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.multiplyByProperRatio(BigInt(-1), 0.5)
    }
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.multiplyByProperRatio(BigInt(100), -0.1)
    }
    assertThrow[IllegalArgumentException]("") {
      SliceKeyMath.multiplyByProperRatio(BigInt(100), 1.1)
    }
  }
}
