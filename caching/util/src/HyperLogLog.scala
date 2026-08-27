package com.databricks.caching.util

import com.databricks.caching.util.proto.HyperLogLogP
import com.google.protobuf.ByteString
import com.google.common.hash.Hashing

/**
 * HyperLogLog is used for efficient estimation of cardinality. It behaves like an add-only set.
 *
 * The original paper is https://dmtcs.episciences.org/3545/pdf. A later paper describes the
 * algorithm and several extensions (which they call HyperLogLog++) in
 * https://static.googleusercontent.com/media/research.google.com/en/pubs/archive/40671.pdf. We do
 * not use the extensions from the latter paper, but its descriptions are easier to follow.
 */
final class HyperLogLog {

  /**
   * HyperLogLog works off of a simple idea. We can estimate the cardinality of a stream by
   * measuring the "rarest" item that we've seen so far, under the assumption that we probably need
   * to see a lot of values in order to see a rare one.
   *
   * The way we measure rarity is with the number of leading zeros in the hash of the items. For a
   * good hash function that uniformly fills the output space, a lot of leading zeros in the hash
   * should be rare.
   *
   * Just doing this estimation would be vulnerable to getting unlucky, since we might have only
   * seen one value but it just happened to be a rare variety. To combat that, we use some of the
   * low-order bits of the hash to bucket the items, the register for that bucket keeps track of the
   * largest number of leading zeros we've seen for that bucket.
   *
   * Then, we use a harmonic mean over the estimates for each of the registers, which averages away
   * the outliers we get by being "unlucky" and seeing a rare value sooner than the statistics would
   * predict.
   *
   * Note: The hashes are 64 bits and we're storing the number of leading zeros, so we only ever use
   * 6 bits for each of these and we could save 25% by packing these more tightly. Since we're in
   * practice never estimating extremely large cardinalities, we also wouldn't lose much by only
   * using only 4 bits per register. 256 bytes is already reasonably cheap so we haven't bothered
   * with that optimization.
   */
  private val registers = new Array[Byte](HyperLogLog.M)

  /**
   * Adds the given key to the HyperLogLog.
   */
  def add(key: ByteString): Unit = {
    val hash =
      Hashing.farmHashFingerprint64().hashBytes(key.asReadOnlyByteBuffer()).asLong()
    val registerIdx = java.lang.Long.remainderUnsigned(hash, HyperLogLog.M).toInt
    // We use the p low-order bits to select the register so they do not carry any more
    // information, don't count them as leading zeroes by setting them all to 1.
    val leadingZeros = java.lang.Long.numberOfLeadingZeros(hash | (HyperLogLog.M - 1))

    // The +1 here allows us to the tell the difference between "no observations" for a bucket and
    // "an observation but it had no leading zeros", the rest of the math is designed around this
    // also.
    registers(registerIdx) = registers(registerIdx).max((leadingZeros + 1).toByte)
  }

  /**
   * Returns an estimate for the number of *unique* keys that were given to [[add()]].
   */
  def estimate(): Long = {
    var numZeros = 0
    var sum = 0.0

    for (register <- registers) {
      if (register == 0) {
        numZeros += 1
      }

      // This shift can't overflow because the register value is capped at 64-p+1.
      sum += 1.0 / (1L << register).toDouble
    }

    // Implies no calls to add().
    if (numZeros == HyperLogLog.M) {
      return 0
    }

    if (numZeros > 0) {
      // LinearCounting for low cardinalities.
      val estimate = HyperLogLog.M.toDouble * Math.log(HyperLogLog.M.toDouble / numZeros.toDouble)
      if (estimate < HyperLogLog.LINEAR_COUNTING_THRESHOLD) {
        return Math.round(estimate)
      }
    }

    // Harmonic mean for high cardinalities.
    return Math.round(HyperLogLog.ALPHA * HyperLogLog.M * HyperLogLog.M / sum)
  }

  /**
   * Merges the observations in [[other]] into [[this]]. That is, afterwards, [[estimate()]] will
   * return the total estimated cardinality of values added to either [[this]] or [[other]].
   */
  def merge(other: HyperLogLog): Unit = {
    for (i <- 0 until HyperLogLog.M) {
      registers(i) = registers(i).max(other.registers(i))
    }
  }

  /** Converts this HyperLogLog to a proto representation. */
  def toProto(): HyperLogLogP = {
    HyperLogLogP(inner = Some(ByteString.copyFrom(registers)))
  }
}

object HyperLogLog {

  /**
   * Referred to as 'precision' in the paper. Determines both the relative error expected in
   * estimates and the space usage of the structure.
   *
   * Kept as a constant rather than a parameter just to avoid implementing the downsampling method
   * required to merge HyperLogLogs of different precisions.
   */
  private val P: Int = 8

  /**
   * We use M bytes of space, and get 1.04/sqrt(M) relative error. That is, for our choice here, we
   * get 1.04/sqrt(256) = 6.5% expected error.
   */
  private val M: Int = 1 << P

  /**
   * HyperLogLog vastly overestimates small cardinalities. For small values, we use LinearCounting.
   *
   * Descriptions of LinearCounting have a set of bits, hashing each added value to one of the bits
   * and setting it to 1, then estimate the cardinality by doing some math over the number of
   * zeros remaining.
   *
   * The registers we have for HyperLogLog have this same information, though they're a whole byte
   * instead of a bit each. Any value that gets hashed into the register will set it to something
   * non-zero.
   */
  private val LINEAR_COUNTING_THRESHOLD: Int = 5 * M / 2

  /**
   * There is a larger formula with an integral in the paper, but Zetasketch approximates it this
   * way for values of P > 6.
   */
  private val ALPHA: Double = 0.7213 / (1 + 1.079 / M)

  /** Converts from a proto representation to a HyperLogLog. */
  @throws[IllegalArgumentException]
  def fromProto(b: HyperLogLogP): HyperLogLog = {
    val inner = b.getInner.toByteArray()

    if (inner.length != M) {
      throw new IllegalArgumentException(
        s"cannot deserialize HyperLogLog: wrong length: ${inner.length} != ${M}"
      )
    }

    for (register <- inner) {
      if (register < 0 || register > 64 - P + 1) {
        throw new IllegalArgumentException(
          s"nonsense register value ${register}, must be in [0, ${64 - P + 1}]"
        )
      }
    }

    val hll = new HyperLogLog()
    Array.copy(inner, 0, hll.registers, 0, M)
    hll
  }

  private[util] object forTest {

    /** The precision of the HyperLogLog. */
    val P = HyperLogLog.P

    /** The number of registers used per HyperLogLog. */
    val M = HyperLogLog.M
  }
}
