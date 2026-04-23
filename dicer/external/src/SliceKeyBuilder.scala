package com.databricks.dicer.external

import java.nio.charset.StandardCharsets

import com.google.common.hash.Hasher

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

/** Implementation of [[SliceKeyBuilder]] that uses a [[Hasher]]. */
private[dicer] class HasherSliceKeyBuilder(hasher: Hasher) extends SliceKeyBuilder {
  override def putLong(value: Long): SliceKeyBuilder = {
    hasher.putLong(value)
    this
  }

  override def putString(value: String): SliceKeyBuilder = {
    hasher.putString(value, StandardCharsets.UTF_8)
    this
  }

  override def putBytes(value: ByteString): SliceKeyBuilder = {
    hasher.putBytes(value.toByteArray)
    this
  }

  override def putBytes(value: java.nio.ByteBuffer): SliceKeyBuilder = {
    val bytes: Array[Byte] = new Array[Byte](value.remaining())
    value.duplicate().get(bytes)
    hasher.putBytes(bytes)
    this
  }

  override def putBytes(value: Array[Byte]): SliceKeyBuilder = {
    hasher.putBytes(value)
    this
  }

  override def build(): SliceKey = {
    val fingerprint: Array[Byte] = hasher.hash().asBytes()
    SliceKey.fromRawBytes(ByteString.copyFrom(fingerprint))
  }
}
