package com.databricks.dicer.common

import scala.util.Random

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.testing.SliceTestUtils.identityKey
import com.databricks.dicer.external.SliceKey
import com.databricks.testing.DatabricksTest

class SliceKeyHelperSuite extends DatabricksTest {

  test("SliceKeyHelper BigInt conversion") {
    // Test plan: Verify that SliceKey conversion to and from BigInt works to spec.
    def test(expected: BigInt, unsignedBytes: Integer*): Unit = {
      val bytes: Array[Byte] = unsignedBytes.map(_.toByte).toArray
      val key: SliceKey = identityKey(bytes)
      val actual: BigInt = key.toBigInt
      assert(actual == expected)

      // Verify round-trip via fromBigInt.
      val roundTrip: SliceKey = SliceKeyHelper.fromBigInt(actual, key.bytes.size)
      assert(key == roundTrip)
    }
    test(expected = 0) // empty is zero!
    test(expected = 0, unsignedBytes = 0, 0) // non-empty may also be zero
    test(expected = 0x0102, unsignedBytes = 1, 2)
    test(expected = 0xFF, unsignedBytes = 0xFF) // bytes treated as unsigned
    test(expected = 0xFE, unsignedBytes = 0, 0, 0xFE) // leading zeroes
    test(expected = 0x2A00, unsignedBytes = 0, 0, 0x2A, 0) // leading and trailing zeroes
  }

  test("SliceKeyHelper BigInt conversion randomized") {
    // Test plan: Verify that BigInt conversion round-trips for random values of various
    // bit-lengths and various desired output lengths. The implementation of
    // `SliceKeyHelper.fromBigInt` is internally complicated by the leading zero bytes added by
    // `BigInt.toByteArray` for some positive values, and this test is designed to shake out
    // possible edge cases.

    val rnd = new Random
    for (bitLength <- 0 until 2048) {
      val int: BigInt = if (bitLength == 0) {
        0
      } else {
        // Create a random sequence of bits, but ensure the MSB is set so that we get a number with
        // the desired bit-length.
        BigInt.apply(numbits = bitLength, rnd) | BigInt(1) << (bitLength - 1)
      }
      assert(int.bitLength == bitLength)

      // Attempt conversion to a `SliceKey` with and without leading zeroes to pad the length.
      val requiredLength: Int = (bitLength + 7) / 8
      for (length <- requiredLength until requiredLength + 4) {
        val key = SliceKeyHelper.fromBigInt(int, length)
        val roundTrip = key.toBigInt
        assert(int == roundTrip)
      }
      // Verify that attempts to convert to a slice key with fewer than the required bytes fail.
      assertThrow[IllegalArgumentException](s"length must be at least $requiredLength") {
        SliceKeyHelper.fromBigInt(int, requiredLength - 1)
      }
    }
  }

  test("SliceKeyHelper BigInt conversion negative cases") {
    // Test plan: Verify the expected exceptions are thrown on invalid inputs.
    assertThrow[IllegalArgumentException]("length must be at least 2") {
      SliceKeyHelper.fromBigInt(magnitude = 1 << 15, length = 1)
    }
    assertThrow[IllegalArgumentException]("length must be at least 2") {
      SliceKeyHelper.fromBigInt(magnitude = 1 << 14, length = 1)
    }
    assertThrow[IllegalArgumentException]("length must be at least 0") {
      SliceKeyHelper.fromBigInt(magnitude = 0, length = -1)
    }
    assertThrow[IllegalArgumentException]("magnitude must be non-negative") {
      SliceKeyHelper.fromBigInt(magnitude = -1, length = 1)
    }
  }
}
