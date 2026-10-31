// LINT.IfChange
package com.databricks.dicer.external

import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets

import scala.collection.mutable.ArrayBuffer

import com.google.common.hash.Hashing

import com.google.protobuf.ByteString

/** A trait for building [[SliceKey]] instances incrementally by adding key parts. */
sealed trait SliceKeyBuilder {

  /** Adds a Long value to the key being built. */
  def putLong(value: Long): SliceKeyBuilder

  /** Adds a String value to the key being built (UTF-8 encoded). */
  def putString(value: String): SliceKeyBuilder

  /** Adds bytes to the key being built. */
  def putBytes(value: ByteString): SliceKeyBuilder

  /** Adds bytes to the key being built. */
  def putBytes(value: java.nio.ByteBuffer): SliceKeyBuilder

  /** Adds bytes to the key being built. */
  def putBytes(value: Array[Byte]): SliceKeyBuilder

  /**
   * Builds the final [[SliceKey]].
   *
   * @throws IllegalArgumentException may be thrown if the resulting key is too long. Note:
   *                                  [[SliceKey.newFingerprintBuilder]] never produces overly long
   *                                  keys (always returns an 8-byte [[SliceKey]]).
   */
  def build(): SliceKey
}

/**
 * Builds a [[SliceKey]] by concatenating up to eight Farmhash fingerprint64 segments. Used by
 * [[SliceKey.newFingerprintBuilder]] for single-segment keys and by the friend API for
 * multi-segment keys.
 */
private[dicer] final class SliceKeySegmentedFingerprintBuilder private () extends SliceKeyBuilder {

  /** The buffer of the raw bytes that is being accumulated for the current open segment. */
  private val openedSegmentBuffer: ByteArrayOutputStream = new ByteArrayOutputStream()

  /** The fingerprints of the N - 1 closed segments. */
  private val segmentedFingerprints: ArrayBuffer[Long] =
    new ArrayBuffer[Long](SliceKeySegmentedFingerprintBuilder.MAX_SEGMENTS)

  override def putLong(value: Long): SliceKeySegmentedFingerprintBuilder = {
    // Use little-endian byte order to match Guava's behavior.
    val bytes: ByteBuffer =
      ByteBuffer.allocate(java.lang.Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    openedSegmentBuffer.write(bytes.putLong(value).array())
    this
  }

  override def putString(value: String): SliceKeySegmentedFingerprintBuilder = {
    openedSegmentBuffer.write(value.getBytes(StandardCharsets.UTF_8))
    this
  }

  override def putBytes(value: ByteString): SliceKeySegmentedFingerprintBuilder = {
    value.writeTo(openedSegmentBuffer)
    this
  }

  override def putBytes(value: java.nio.ByteBuffer): SliceKeySegmentedFingerprintBuilder = {
    val bytes: Array[Byte] = new Array[Byte](value.remaining())
    value.duplicate().get(bytes)
    openedSegmentBuffer.write(bytes)
    this
  }

  override def putBytes(value: Array[Byte]): SliceKeySegmentedFingerprintBuilder = {
    openedSegmentBuffer.write(value)
    this
  }

  /**
   * Closes the open segment and opens a new one, while returning this builder.
   */
  def nextFingerprint(): SliceKeySegmentedFingerprintBuilder = {
    // Even if no values were put into the open segment, the closed segment contributes a
    // fingerprint.
    segmentedFingerprints +=
    Hashing.farmHashFingerprint64().hashBytes(openedSegmentBuffer.toByteArray).asLong()
    openedSegmentBuffer.reset()
    this
  }

  /**
   * Builds the final [[SliceKey]].
   *
   * @throws IllegalArgumentException if the key has more than 8 segments.
   */
  override def build(): SliceKey = {
    val numSegments: Int = segmentedFingerprints.size + 1
    if (numSegments > SliceKeySegmentedFingerprintBuilder.MAX_SEGMENTS) {
      throw new IllegalArgumentException(
        s"must not exceed ${SliceKeySegmentedFingerprintBuilder.MAX_SEGMENTS} segments, " +
        s"got $numSegments"
      )
    }

    val fingerprints: ArrayBuffer[Long] = segmentedFingerprints :+
      Hashing.farmHashFingerprint64().hashBytes(openedSegmentBuffer.toByteArray).asLong()
    // Use little-endian byte order to match Guava's behavior.
    val bytes: ByteBuffer =
      ByteBuffer.allocate(numSegments * java.lang.Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (fingerprint64: Long <- fingerprints) {
      bytes.putLong(fingerprint64)
    }
    SliceKey.fromRawBytes(ByteString.copyFrom(bytes.array()))
  }
}

private[dicer] object SliceKeySegmentedFingerprintBuilder {

  /**
   * The maximum number of segments in a [[SliceKey]] built by
   * [[SliceKeySegmentedFingerprintBuilder]].
   */
  private val MAX_SEGMENTS: Int = 8

  /** Creates a new builder with a single open segment. */
  def create(): SliceKeySegmentedFingerprintBuilder = {
    new SliceKeySegmentedFingerprintBuilder()
  }
}
// LINT.ThenChange(dicer/external/javaapi/src/SliceKeyBuilder.java)
// LINT.ThenChange(dicer/rust/external/types/src/slice_key_builder.rs)
