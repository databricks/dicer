package com.databricks.dicer.external.javaapi;

import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP.HighSliceKeyP;
import com.databricks.dicer.friend.external.javaapi.SliceKeyAccessor;
import com.google.protobuf.ByteString;
import java.util.List;

/** Test utilities for creating and manipulating SliceKeys and Slices. */
public final class TestSliceUtils {

  private TestSliceUtils() {
    // Prevent instantiation.
  }

  private static final ByteString ZERO = ByteString.copyFrom(new byte[] {0});

  /**
   * Returns the successor of the given SliceKey. The successor is defined as the key with a 0 byte
   * appended to the end of the original key's bytes.
   *
   * <p>Example: If key has bytes [1, 2, 3], the successor will be [1, 2, 3, 0].
   */
  public static SliceKey successor(SliceKey key) {
    ByteString originalBytes = key.toRawBytes();
    return identity(originalBytes.concat(ZERO));
  }

  /** Converts a SliceKeyP to a SliceKey, with the appropriate slice key function applied. */
  public static SliceKey sliceKeyFromProto(HighSliceKeyP.SliceKeyP sliceKeyP)
      throws IllegalArgumentException {
    List<Integer> applicationKey = sliceKeyP.getApplicationKeyList();
    // Copy the applicationKey into a byte array.
    byte[] bytes = new byte[applicationKey.size()];
    for (int i = 0; i < applicationKey.size(); i++) {
      bytes[i] = applicationKey.get(i).byteValue();
    }
    switch (sliceKeyP.getSliceKeyFunction()) {
      case IDENTITY:
        return identity(bytes);
      case FARM_HASH_FINGERPRINT_64:
        return fp(bytes);
      default:
        throw new IllegalArgumentException("Unknown function: " + sliceKeyP.getSliceKeyFunction());
    }
  }

  /** Converts a HighSliceKeyP proto to a HighSliceKey instance. */
  public static HighSliceKey highSliceKeyFromProto(HighSliceKeyP highSliceKeyP) {
    switch (highSliceKeyP.getKeyCase()) {
      case SLICE_KEY:
        return sliceKeyFromProto(highSliceKeyP.getSliceKey());
      case INFINITY_SLICE_KEY:
        return InfinitySliceKey.INSTANCE;
      default:
        throw new IllegalArgumentException("Unknown key type: " + highSliceKeyP.getKeyCase());
    }
  }

  /** Creates a SliceKey using FarmHash Fingerprint64 from a string. */
  public static SliceKey fp(String applicationKey) {
    return SliceKey.newFingerprintBuilder().putString(applicationKey).build();
  }

  /** Creates a SliceKey using FarmHash Fingerprint64 from raw bytes. */
  public static SliceKey fp(byte[] applicationKey) {
    return SliceKey.newFingerprintBuilder().putBytes(applicationKey).build();
  }

  /** Creates a SliceKey using FarmHash Fingerprint64 from a ByteString. */
  public static SliceKey fp(ByteString applicationKey) {
    return SliceKey.newFingerprintBuilder().putBytes(applicationKey).build();
  }

  /** Creates a SliceKey with the identity function from raw bytes. */
  public static SliceKey identity(byte[] applicationKey) {
    return SliceKeyAccessor.fromRawBytes(ByteString.copyFrom(applicationKey));
  }

  /** Creates a SliceKey with the identity function from a ByteString. */
  public static SliceKey identity(ByteString applicationKey) {
    return SliceKeyAccessor.fromRawBytes(applicationKey);
  }

  /** Creates a SliceKey with the identity function from a UTF-8 encoded string. */
  public static SliceKey identity(String applicationKey) {
    return SliceKeyAccessor.fromRawBytes(ByteString.copyFromUtf8(applicationKey));
  }
}
