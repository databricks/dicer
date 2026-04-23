package com.databricks.dicer.friend.external

import com.databricks.dicer.external.SliceKey
import com.google.protobuf.ByteString

/**
 * Provides friend access to low-level [[SliceKey]] deserialization methods. These methods should
 * only be used in consultation with the Caching team at the maintainers.
 *
 * DESIGN NOTE: There are two distinct methods for creating [[SliceKey]]s from bytes:
 * - [[SliceKey.fromTrustedFingerprint]]: Public API for creating new keys "from
 *   scratch" with validation (enforces 32-byte limit).
 * - [[fromRawBytes]]: Friend API for deserializing previously serialized keys without validation
 *   (accepts any length). Do not use this to create new keys exceeding 32 bytes; the call will not
 *   throw an exception but the behavior is undefined and may change in future versions.
 *
 * The reason for this distinction is that we want to review implementations that create new keys to
 * ensure they produce uniformly distributed bytes and enforce strict size limits. However, for
 * deserialization we want to unconditionally accept bytes of any length to support future size
 * limit changes and internal sentinel values used by Dicer (e.g., appending a zero byte to produce
 * the exclusive upper bound of a [[Slice]] containing a single key).
 */
object SliceKeyAccessor {

  /**
   * Creates a [[SliceKey]] from raw bytes. Used exclusively to unmarshal bytes returned from
   * [[SliceKey.toRawBytes]].
   *
   * This method performs no validation and accepts bytes of any length. It is intended only for
   * deserializing previously serialized [[SliceKey]] instances, e.g., [[SliceKey]] bytes written to
   * storage or a protobuf.
   *
   * Note that validation is not performed on the bytes with this method, so it is critical that
   * keys are initially constructed using a safe method, e.g., [[SliceKey.newFingerprintBuilder]]
   * or [[SliceKey.fromTrustedFingerprint]].
   *
   * External applications may be allowlisted for this API by contacting the maintainers.
   */
  def fromRawBytes(bytes: ByteString): SliceKey = {
    SliceKey.fromRawBytes(bytes)
  }
}
