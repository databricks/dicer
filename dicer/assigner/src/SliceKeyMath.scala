package com.databricks.dicer.assigner

import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, SliceKey}

/** Utility methods for arithmetic on [[SliceKey]] values. */
private[assigner] object SliceKeyMath {

  /**
   * Returns the number of bytes in `key`, treating [[InfinitySliceKey]] as contributing no bytes.
   */
  def byteLength(key: HighSliceKey): Int = key match {
    case InfinitySliceKey => 0
    case finiteKey: SliceKey => finiteKey.toRawBytes.size()
  }

  /**
   * Converts the given key to its numeric value, decoding its bytes as a big-endian magnitude and
   * zero padding the least significant bytes such that the key has the given length.
   *
   * For [[InfinitySliceKey]], a number greater than the largest number representable within
   * `length` is returned.
   *
   * Note: `key` may be an arbitrary SliceKey; the [[HighSliceKey]] type is used here only to
   * support the [[InfinitySliceKey]] case.
   *
   * Examples:
   *
   *     toBigIntWithLength(0x0403, 3) => 0x040300
   *     toBigIntWithLength(∞, 3) => 0x01000000
   *
   * @throws IllegalArgumentException if `key` is longer than `length`.
   */
  @throws[IllegalArgumentException]("if key is longer than length")
  def toBigIntWithLength(key: HighSliceKey, length: Int): BigInt = key match {
    case InfinitySliceKey => BigInt(1) << (length * 8)
    case finiteKey: SliceKey =>
      val trailingZeroBytes: Int = length - finiteKey.toRawBytes.size()
      require(trailingZeroBytes >= 0, "key length must not exceed the common length")
      finiteKey.toBigInt << (trailingZeroBytes * 8)
  }

  /**
   * Returns lhs/rhs as a Double value. Accounts for possible overflow in conversion to Double.
   *
   * @throws IllegalArgumentException if `lhs` is negative, `rhs` is non-positive, or `lhs > rhs`.
   */
  @throws[IllegalArgumentException]("if lhs is negative, rhs is non-positive, or lhs > rhs")
  def getProperRatio(lhs: BigInt, rhs: BigInt): Double = {
    require(lhs.signum >= 0)
    require(rhs.signum > 0)
    require(lhs <= rhs)

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
   * Returns multiplicand*ratio as a BigInt value. Accounts for possible overflow in the conversion
   * from BigInt to Double for the multiplicand. Also special cases 0 and 1 ratios.
   *
   * @throws IllegalArgumentException if `multiplicand` is negative or `ratio` is not in [0, 1].
   */
  @throws[IllegalArgumentException]("if multiplicand is negative or ratio is not in [0, 1]")
  def multiplyByProperRatio(multiplicand: BigInt, ratio: Double): BigInt = {
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
}
