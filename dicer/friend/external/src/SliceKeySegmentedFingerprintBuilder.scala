package com.databricks.dicer.friend.external

import java.nio.ByteBuffer

import com.google.protobuf.ByteString
import com.databricks.dicer.external.{
  SliceKey,
  SliceKeySegmentedFingerprintBuilder => InternalSliceKeySegmentedFingerprintBuilder
}

/**
 * A builder for creating [[SliceKey]] instances composed of one or more key segments. Each key
 * segment is calculated as the Farmhash fingerprint64 of a byte sequence, where the byte sequence
 * is accumulated by calling `putLong`, `putString` or `putBytes`.
 *
 * `nextFingerprint()` closes the current segment and starts a new one, and `build()` closes the
 * last segment and builds the final SliceKey. The resulting key is the concatenation of the
 * fingerprints of each segment, in the order the segments were closed, so a key with N segments
 * contains 8 * N bytes.
 *
 * N must be no more than 8 (that is, `nextFingerprint()` can be called no more than 7 times before
 * `build()`). Otherwise, `build()` throws an `IllegalArgumentException`.
 *
 * Access is restricted until the Assigner has been validated with 64-byte keys. Contact
 * the maintainers to request access.
 *
 * For example, the following code
 *
 * {{{
 * SliceKeySegmentedFingerprintBuilder.create()
 *   .putBytes(...).putLong(...).putString(...)  // First segment
 *   .nextFingerprint()
 *   .putString(...).putLong(...).putLong(...)   // Second segment
 *   .nextFingerprint()
 *   .putBytes(...)                              // Third segment
 *   .build()
 * }}}
 *
 * will result in the following SliceKey:
 *
 * {{{
 * |----bytes---|long|--string--|------string------|long|long|---------bytes----------|
 *                              ↓                            ↓                        |
 *                     nextFingerprint()              nextFingerprint()               |
 *                              |                            |                        ↓
 *                              |                            |                     build()
 * |      fingerprint1          |      fingerprint2          |    fingerprint3        |
 * |----------------------------------------------------------------------------------|
 * |                               The final SliceKey                                 |
 * }}}
 *
 * Note that two consecutive `nextFingerprint()` calls also append a new 8-byte fingerprint segment
 * into the SliceKey.
 *
 * Note that a builder on which `nextFingerprint()` is never called produces a single 8-byte
 * fingerprint of all bytes that are put into the builder, the same key as
 * [[SliceKey.newFingerprintBuilder]] builds.
 *
 * Note that `nextFingerprint()` updates this builder and returns it, while `build()` leaves the
 * builder unchanged, so a builder can still be used and built again after `build()`.
 */
final class SliceKeySegmentedFingerprintBuilder private () {

  /** Shared implementation for accumulating and fingerprinting the segments. */
  private val internalBuilder: InternalSliceKeySegmentedFingerprintBuilder =
    InternalSliceKeySegmentedFingerprintBuilder.create()

  /** Adds a Long value to the current segment. */
  def putLong(value: Long): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.putLong(value)
    this
  }

  /** Adds a String value to the current segment (UTF-8 encoded). */
  def putString(value: String): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.putString(value)
    this
  }

  /** Adds bytes to the current segment. */
  def putBytes(value: ByteString): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.putBytes(value)
    this
  }

  /** Adds the remaining bytes to the current segment without modifying the buffer. */
  def putBytes(value: ByteBuffer): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.putBytes(value)
    this
  }

  /** Adds bytes to the current segment. */
  def putBytes(value: Array[Byte]): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.putBytes(value)
    this
  }

  /** Closes the current segment and starts a new one, while returning this builder. */
  def nextFingerprint(): SliceKeySegmentedFingerprintBuilder = {
    internalBuilder.nextFingerprint()
    this
  }

  /** Builds the final [[SliceKey]]. */
  @throws[IllegalArgumentException]("if the key has more than 8 segments")
  def build(): SliceKey = internalBuilder.build()
}

/** Factory for the friend-only segmented fingerprint builder. */
object SliceKeySegmentedFingerprintBuilder {

  /** Creates a new builder with a single open segment. */
  def create(): SliceKeySegmentedFingerprintBuilder = new SliceKeySegmentedFingerprintBuilder()
}
