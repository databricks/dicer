package com.databricks.dicer.common

import com.google.protobuf.ByteString
import com.databricks.dicer.external.SliceKey

/** Utility methods for [[SliceKey]] that are internal (not part of the public API). */
object SliceKeyHelper {

  /** Created once to use in every call to [[RichSliceKey.successor]]. */
  private val ZERO_BYTE = ByteString.copyFrom(Array[Byte](0))

  /**
   * REQUIRES: magnitude is non-negative
   * REQUIRES: length is sufficient to hold a big-endian representation of the given magnitude
   *
   * Returns a SliceKey of the given length whose contents are the big-endian representation of the
   * given magnitude, with leading zeroes as needed.
   *
   * For example:
   *
   *     fromBigInt(0x42, 1) => "B"
   *     fromBigInt(0x27, 2) => "\0'"
   *     fromBigInt(0x422700, 4) => "\0B'\0"
   */
  def fromBigInt(magnitude: BigInt, length: Int): SliceKey = {
    require(magnitude.signum >= 0, "magnitude must be non-negative")

    // `BigInt.toByteArray` returns the shortest (in bytes) two's-complement representation of the
    // number. Note that even though we are always non-negative, BigInt is not aware of that.
    val bytes: Array[Byte] = magnitude.toByteArray

    // We want the shortest big-endian representation of "magnitude", which for non-negative numbers
    // is the same as the two's-complement representation except possibly with an extra leading
    // 0 byte to ensure that the most significant bit is 0. This extra byte is needed in "magnitude"
    // whenever the bit-length is a multiple of 8 (i.e., the highest bit in the number is 1), e.g.:
    //
    //     0 => {0}
    //     0x87A4 => {0, 0x87, A4}
    //
    // For other cases, there's already a leading 0 bit, and there's no need for padding in
    // "magnitude":
    //
    //     1 => {1}
    //     0x429F => {0x42, 0x9F}
    // Hence, when we copy bytes from "magnitude" to our result key, we need to skip the first byte
    // if the first byte is 0 in "magnitude" (i.e., `bytes`)
    val skipOffset: Int = if (bytes(0) == 0) 1 else 0

    // Validate that `length` is large enough to contain the number. We only need enough bytes to
    // store the big-endian representation, so the required length excludes the offset for the
    // big-endian bytes.
    val requiredLength = bytes.length - skipOffset
    require(length >= requiredLength, s"length must be at least $requiredLength")

    // Copy the big-endian bytes to the end of our result key.
    val key = new Array[Byte](length)
    val resultOffset = length - bytes.length // Where to start copying bytes into the result key.
    for (i <- skipOffset until bytes.length) {
      key(i + resultOffset) = bytes(i)
    }
    SliceKey.fromRawBytes(ByteString.copyFrom(key))
  }

  /** Internal extensions for [[SliceKey]]. */
  implicit class RichSliceKey(key: SliceKey) {

    /**
     * Translates `key` into a non-negative integer where the key's bytes are treated as the big-
     * endian representation of the magnitude of the integer.
     */
    def toBigInt: BigInt = {
      // Do not use BigInt(Array[Byte]) which takes the two's-complement binary representation.
      // Instead use the constructor taking both signum and magnitude so that we can ensure the
      // resulting BigInt is non-negative.
      val bytes = key.bytes.toByteArray
      if (bytes.forall { byte: Byte =>
          byte == '\u0000'
        }) 0
      else BigInt(signum = 1, magnitude = bytes)
    }

    /** Returns the key that is immediately after this key, i.e. the key with a 0 byte appended. */
    def successor(): SliceKey = {
      SliceKey.fromRawBytes(key.bytes.concat(ZERO_BYTE))
    }
  }
}
