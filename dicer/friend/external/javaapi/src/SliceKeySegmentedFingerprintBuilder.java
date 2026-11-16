package com.databricks.dicer.friend.external.javaapi;

import com.databricks.dicer.external.javaapi.ImplFriend;
import com.databricks.dicer.external.javaapi.SliceKey;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;

/**
 * A builder for creating {@link SliceKey} instances composed of one or more key segments. Each key
 * segment is calculated as the Farmhash fingerprint64 of a byte sequence, where the byte sequence
 * is accumulated by calling {@code putLong}, {@code putString} or {@code putBytes}.
 *
 * <p>{@code nextFingerprint()} closes the current segment and starts a new one, and {@code build()}
 * closes the last segment and builds the final SliceKey. The resulting key is the concatenation of
 * the fingerprints of each segment, in the order the segments were closed, so a key with N segments
 * contains 8 * N bytes.
 *
 * <p>N must be no more than 8 (that is, {@code nextFingerprint()} can be called no more than 7
 * times before {@code build()}). Otherwise, {@code build()} throws an {@code
 * IllegalArgumentException}.
 *
 * <p>Contact the maintainers to request access.
 *
 * <p>For example, the following code
 *
 * <pre>{@code
 * SliceKeySegmentedFingerprintBuilder.create()
 *   .putBytes(...).putLong(...).putString(...)  // First segment
 *   .nextFingerprint()
 *   .putString(...).putLong(...).putLong(...)   // Second segment
 *   .nextFingerprint()
 *   .putBytes(...)                              // Third segment
 *   .build()
 * }</pre>
 *
 * <p>will result in the following SliceKey:
 *
 * <pre>{@code
 * |----bytes---|long|--string--|------string------|long|long|---------bytes----------|
 *                              ↓                            ↓                        |
 *                     nextFingerprint()              nextFingerprint()               |
 *                              |                            |                        ↓
 *                              |                            |                     build()
 * |      fingerprint1          |      fingerprint2          |    fingerprint3        |
 * |----------------------------------------------------------------------------------|
 * |                               The final SliceKey                                 |
 * }</pre>
 *
 * <p>Note that two consecutive {@code nextFingerprint()} calls also append a new 8-byte fingerprint
 * segment into the SliceKey.
 *
 * <p>Note that a builder on which {@code nextFingerprint()} is never called produces a single
 * 8-byte fingerprint of all bytes that are put into the builder, the same key as {@link
 * SliceKey#newFingerprintBuilder()} builds.
 *
 * <p>Note that {@code nextFingerprint()} updates this builder and returns it, while {@code build()}
 * leaves the builder unchanged, so a builder can still be used and built again after {@code
 * build()}.
 *
 * @see com.databricks.dicer.friend.external.SliceKeySegmentedFingerprintBuilder
 */
public final class SliceKeySegmentedFingerprintBuilder {

  /** The underlying Scala builder, which holds the accumulated segments. */
  private com.databricks.dicer.friend.external.SliceKeySegmentedFingerprintBuilder scalaBuilder;

  private SliceKeySegmentedFingerprintBuilder(
      com.databricks.dicer.friend.external.SliceKeySegmentedFingerprintBuilder scalaBuilder) {
    this.scalaBuilder = scalaBuilder;
  }

  /** Creates a new builder with a single open segment. */
  public static SliceKeySegmentedFingerprintBuilder create() {
    return new SliceKeySegmentedFingerprintBuilder(
        com.databricks.dicer.friend.external.SliceKeySegmentedFingerprintBuilder.create());
  }

  /** Adds a Long value to the current segment. */
  public SliceKeySegmentedFingerprintBuilder putLong(long value) {
    this.scalaBuilder = this.scalaBuilder.putLong(value);
    return this;
  }

  /** Adds a String value to the current segment (UTF-8 encoded). */
  public SliceKeySegmentedFingerprintBuilder putString(String value) {
    this.scalaBuilder = this.scalaBuilder.putString(value);
    return this;
  }

  /** Adds bytes to the current segment. */
  public SliceKeySegmentedFingerprintBuilder putBytes(ByteString value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /** Adds bytes to the current segment. */
  public SliceKeySegmentedFingerprintBuilder putBytes(byte[] value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /** Adds the remaining bytes to the current segment without modifying the buffer. */
  public SliceKeySegmentedFingerprintBuilder putBytes(ByteBuffer value) {
    this.scalaBuilder = this.scalaBuilder.putBytes(value);
    return this;
  }

  /** Closes the current segment and starts a new one, while returning this builder. */
  public SliceKeySegmentedFingerprintBuilder nextFingerprint() {
    this.scalaBuilder = this.scalaBuilder.nextFingerprint();
    return this;
  }

  /**
   * Builds the final {@link SliceKey}.
   *
   * @throws IllegalArgumentException if the key has more than 8 segments
   */
  public SliceKey build() {
    com.databricks.dicer.external.SliceKey scalaSliceKey = this.scalaBuilder.build();
    return ImplFriend.convertToJavaSliceKey(scalaSliceKey);
  }
}
