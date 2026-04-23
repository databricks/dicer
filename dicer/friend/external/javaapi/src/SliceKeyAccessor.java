package com.databricks.dicer.friend.external.javaapi;

import com.databricks.dicer.external.javaapi.SliceKey;

import com.google.protobuf.ByteString;

/**
 * Provides friend access to low-level {@link SliceKey} deserialization methods. These methods
 * should only be used in consultation with the Caching team at the maintainers.
 *
 * <p>See //dicer/friend/external/src/SliceKeyAccessor.scala for design notes explaining why there
 * are two distinct methods for creating {@link SliceKey}s from bytes: the public {@link
 * SliceKey#fromTrustedFingerprint(ByteString)} for creation with validation, and the friend {@link
 * #fromRawBytes(ByteString)} for deserialization without validation.
 */
public final class SliceKeyAccessor {

  /** Private constructor to prevent instantiation. */
  private SliceKeyAccessor() {}

  /**
   * Creates a {@link SliceKey} from raw bytes. Used exclusively to unmarshal bytes returned from
   * {@link SliceKey#toRawBytes()}.
   *
   * <p>This method performs no validation and accepts bytes of any length. It is intended only for
   * deserializing previously serialized {@link SliceKey} instances, e.g., {@link SliceKey} bytes
   * written to storage or a protobuf. Do not use this to create new keys exceeding 32 bytes; the
   * call will not throw an exception but the behavior is undefined and may change in future
   * versions.
   *
   * <p>Note that validation is not performed on the bytes with this method, so it is critical that
   * keys are initially constructed using a safe method, e.g., {@link
   * SliceKey#newFingerprintBuilder()} or {@link SliceKey#fromTrustedFingerprint(ByteString)}.
   *
   * <p>External applications may be allowlisted for this API by contacting the maintainers.
   *
   * @param bytes the raw bytes previously returned from {@link SliceKey#toRawBytes()}
   */
  public static SliceKey fromRawBytes(ByteString bytes) {
    com.databricks.dicer.external.SliceKey scalaSliceKey =
        com.databricks.dicer.friend.external.SliceKeyAccessor.fromRawBytes(bytes);
    return com.databricks.dicer.external.javaapi.ImplFriend.convertToJavaSliceKey(scalaSliceKey);
  }
}
