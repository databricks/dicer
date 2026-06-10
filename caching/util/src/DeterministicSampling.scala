package com.databricks.caching.util

import java.nio.charset.StandardCharsets

import com.google.common.hash.Hashing

/** Utilities for deterministic hash-based item sampling. */
object DeterministicSampling {

  /**
   * The modulus used to map hash values to the range [0, HASH_MODULUS). This serves two purposes:
   *   1. It avoids floating point precision issues: operating directly on full 64-bit hash values
   *      (e.g. values close to 2^64 from fingerprint64) can compromise calculation precision.
   *      Reducing to a small integer range makes the threshold comparison exact.
   *   2. It defines the sampling resolution: with HASH_MODULUS = 10^4 = 10000, the threshold
   *      comparison `restrictedHashValue < (sampleFraction * HASH_MODULUS).toLong` has exactly
   *      4 decimal places of resolution, matching the stated precision limit.
   */
  private val HASH_MODULUS: Long = 10000L

  /**
   * Returns whether `item` is deterministically sampled at rate `sampleFraction` for
   * `sampleNamespace`.
   *
   * The sampling is deterministic: the same (`item`, `sampleNamespace`) pair always yields the
   * same result, even across different processes. Different namespaces act as independent salts,
   * enabling independent sampling decisions for the same item across different features.
   *
   * NOTE: `sampleFraction` should be at most 4 decimal digits precise (e.g., 0.1234). Precision
   * beyond 4 decimal places is silently ignored.
   *
   * @param item            The string representation of the item to sample.
   * @param sampleNamespace The sampling namespace; acts as a salt to ensure independence across
   *                        features/usages.
   * @param sampleFraction  The fraction of items to sample, must be in [0.0, 1.0].
   * @return true if the item is sampled, false otherwise.
   */
  @throws[IllegalArgumentException]("if sampleFraction is not in [0.0, 1.0]")
  def isSampled(item: String, sampleNamespace: String, sampleFraction: Double): Boolean = {
    if (sampleFraction.isNaN || sampleFraction < 0.0 || sampleFraction > 1.0) {
      throw new IllegalArgumentException(
        s"sampleFraction must be in [0.0, 1.0], got $sampleFraction"
      )
    }
    // Two consecutive putString calls feed raw bytes with no delimiter, so different (item,
    // sampleNamespace) pairs can produce the same byte stream: ("hello","world") and
    // ("he","lloworld") both yield the bytes [h,e,l,l,o,w,o,r,l,d]. Prefixing item.length as a
    // 4-byte int encodes the boundary explicitly, making those two inputs distinct.
    val fingerprint64HashValue: Long =
      Hashing
        .farmHashFingerprint64()
        .newHasher()
        .putInt(item.length)
        .putString(item, StandardCharsets.UTF_8)
        .putString(sampleNamespace, StandardCharsets.UTF_8)
        .hash()
        .asLong()
    val restrictedHashValue: Long =
      java.lang.Long.remainderUnsigned(fingerprint64HashValue, HASH_MODULUS)
    restrictedHashValue < (sampleFraction * HASH_MODULUS).toLong
  }
}
