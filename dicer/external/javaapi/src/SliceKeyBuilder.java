package com.databricks.dicer.external.javaapi;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;

/**
 * A builder for constructing {@link SliceKey} instances incrementally by adding key parts.
 *
 * @see com.databricks.dicer.external.SliceKeyBuilder
 */
public final class SliceKeyBuilder {

  private com.databricks.dicer.external.SliceKeyBuilder scalaBuilder;

  SliceKeyBuilder(com.databricks.dicer.external.SliceKeyBuilder scalaBuilder) {
    this.scalaBuilder = scalaBuilder;
  }

  /** Adds a Long value to the key being built. */
  public SliceKeyBuilder putLong(Long value) {
    this.scalaBuilder = this.scalaBuilder.putLong(value);
    return this;
  }

  /** Adds a String value to the key being built (UTF-8 encoded). */
  public SliceKeyBuilder putString(String value) {
    this.scalaBuilder = this.scalaBuilder.putString(value);
    return this;
  }

  /** Adds bytes to the key being built. */
  public SliceKeyBuilder putBytes(ByteString value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /** Adds bytes to the key being built. */
  public SliceKeyBuilder putBytes(byte[] value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /** Adds bytes to the key being built. */
  public SliceKeyBuilder putBytes(ByteBuffer value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /**
   * Builds the final {@link SliceKey}.
   *
   * @throws IllegalArgumentException may be thrown if the resulting key is too long. Note: {@link
   *     SliceKey#newFingerprintBuilder()} never produces overly long keys (always returns an 8-byte
   *     SliceKey).
   */
  public SliceKey build() {
    com.databricks.dicer.external.SliceKey scalaSliceKey = this.scalaBuilder.build();
    return new SliceKey(scalaSliceKey);
  }
}
