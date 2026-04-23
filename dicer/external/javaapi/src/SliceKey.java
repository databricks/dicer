package com.databricks.dicer.external.javaapi;

import com.google.protobuf.ByteString;

/**
 * @see com.databricks.dicer.external.SliceKey
 */
public final class SliceKey extends HighSliceKey {

  /** The underlying Scala `SliceKey` instance. */
  private final com.databricks.dicer.external.SliceKey scalaSliceKey;

  /**
   * @see com.databricks.dicer.external.SliceKey$#MIN()
   */
  public static final SliceKey MIN = new SliceKey(com.databricks.dicer.external.SliceKey.MIN());

  /**
   * This constructor is package private to prevent external instantiation; use {@link
   * #newFingerprintBuilder()} instead.
   */
  SliceKey(com.databricks.dicer.external.SliceKey scalaSliceKey) {
    this.scalaSliceKey = scalaSliceKey;
  }

  /**
   * Creates a new builder for constructing a SliceKey using the Farmhash fingerprint64 algorithm.
   * The builder allows incrementally adding key parts (longs, strings, bytes) which are then hashed
   * together to produce an 8-byte fingerprint.
   *
   * <p>If no values are put in the builder, returns the fingerprint for the empty string, _not_
   * MIN.
   *
   * <p>Example:
   *
   * <pre>{@code
   * SliceKey key = SliceKey.newFingerprintBuilder()
   *     .putString("user-")
   *     .putLong(12345L)
   *     .build();
   * }</pre>
   */
  public static SliceKeyBuilder newFingerprintBuilder() {
    return new SliceKeyBuilder(com.databricks.dicer.external.SliceKey.newFingerprintBuilder());
  }

  /**
   * Creates a SliceKey from uniformly distributed bytes.
   *
   * <p>IMPORTANT: This method is for ADVANCED USE ONLY. For most applications, use {@link
   * #newFingerprintBuilder()} instead, which automatically ensures uniform distribution and proper
   * key construction.
   *
   * <p>This method is intended for applications that need to generate custom slice keys with
   * specific distribution properties (e.g., range-based sharding schemes).
   *
   * <p>REQUIREMENTS:
   *
   * <ul>
   *   <li>The input bytes must be uniformly distributed across the keyspace
   *   <li>Maximum length: 32 bytes
   *   <li>Bytes should have sufficient entropy to avoid hotspots
   * </ul>
   *
   * <p>WHEN TO USE THIS METHOD: Use this method when you are creating NEW slice keys from scratch
   * with custom byte sequences that you have verified to be uniformly distributed.
   *
   * <p>WHEN TO USE fromRawBytes INSTEAD: If you need to deserialize a SliceKey that was previously
   * serialized (e.g., from storage, a protobuf, or returned from toRawBytes()), you should use
   * fromRawBytes() from the friend accessor API instead. Contact the maintainers to get allowlisted
   * for friend accessor access.
   *
   * @param bytes the uniformly distributed bytes (must be &lt;= 32 bytes)
   * @return a new SliceKey
   * @throws IllegalArgumentException if bytes exceeds 32 bytes
   */
  public static SliceKey fromTrustedFingerprint(ByteString bytes) {
    com.databricks.dicer.external.SliceKey scalaSliceKey =
        com.databricks.dicer.external.SliceKey.fromTrustedFingerprint(bytes);
    return new SliceKey(scalaSliceKey);
  }

  /**
   * Returns the byte-array representation of this key.
   *
   * @deprecated Use {@link #toRawBytes()} instead for consistency with the new API
   */
  @Deprecated
  public byte[] bytes() {
    return this.scalaSliceKey.bytes().toByteArray();
  }

  /**
   * Returns the raw bytes of this key. These bytes can be used as prefixes for storage keys to
   * efficiently identify rows belonging to a given Slice.
   *
   * <p>The returned bytes can be deserialized back to a SliceKey using
   * `SliceKeyAccessor#fromRawBytes(ByteString)`.
   *
   * @see com.databricks.dicer.external.SliceKey#toRawBytes()
   */
  public ByteString toRawBytes() {
    return this.scalaSliceKey.toRawBytes();
  }

  @Override
  public int compareTo(HighSliceKey that) {
    return this.scalaSliceKey.compare(that.scalaHighSliceKey());
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) return true;
    if (that == null || getClass() != that.getClass()) return false;
    SliceKey thatSliceKey = (SliceKey) that;
    return this.scalaSliceKey.equals(thatSliceKey.scalaSliceKey);
  }

  @Override
  public int hashCode() {
    return this.scalaSliceKey.hashCode();
  }

  @Override
  public String toString() {
    return this.scalaSliceKey.toString();
  }

  @Override
  public boolean isFinite() {
    return this.scalaSliceKey.isFinite();
  }

  @Override
  public SliceKey asFinite() {
    return this;
  }

  /** Returns the underlying Scala `SliceKey` instance. */
  com.databricks.dicer.external.SliceKey toScala() {
    return this.scalaSliceKey;
  }

  @Override
  protected com.databricks.dicer.external.HighSliceKey scalaHighSliceKey() {
    return this.scalaSliceKey;
  }
}
