package com.databricks.dicer.friend.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.highSliceKeyFromProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.databricks.caching.util.javaapi.TestUtils;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP.HighSliceKeyP;
import com.databricks.dicer.external.javaapi.HighSliceKey;
import com.databricks.dicer.external.javaapi.SliceKey;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SliceKeyAccessor} friend API.
 *
 * <p>These tests verify that the accessor provides proper deserialization functionality for
 * previously serialized SliceKeys, accepting any length without validation for forward
 * compatibility with future size limit changes.
 */
final class SliceKeyAccessorTest {

  /** Test data loaded from textproto file. */
  private static final SliceKeyTestDataP TEST_DATA = loadSliceKeyTestData();

  /** Reads a SliceKeyTestDataP instance from a textproto file. */
  private static SliceKeyTestDataP loadSliceKeyTestData() {
    SliceKeyTestDataP.Builder builder = SliceKeyTestDataP.newBuilder();
    try {
      TestUtils.loadTestData("dicer/common/test/data/slice_key_test_data.textproto", builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return builder.build();
  }

  @Test
  void testFromRawBytesAndToRawBytes() {
    // Test plan: Verify that fromRawBytes and toRawBytes correctly round-trip SliceKey instances
    // using ordered test cases from shared proto test data.

    // Setup: test successful round trips using ordered slice keys.
    for (HighSliceKeyP highSliceKeyP : TEST_DATA.getOrderedHighSliceKeysList()) {
      HighSliceKey highSliceKey = highSliceKeyFromProto(highSliceKeyP);
      if (!highSliceKey.isFinite()) {
        continue;
      }
      SliceKey original = highSliceKey.asFinite();

      // Verify: round trip through toRawBytes/fromRawBytes.
      ByteString bytes = original.toRawBytes();
      SliceKey roundtrip = SliceKeyAccessor.fromRawBytes(bytes);

      assertThat(roundtrip).as("Round trip should succeed for %s", original).isEqualTo(original);
      assertThat(bytes).as("Bytes should match for %s", original).isEqualTo(original.toRawBytes());
    }
  }

  @Test
  void testFromRawBytesAcceptsAnyLength() {
    // Test plan: Verify that fromRawBytes accepts bytes of any length without validation.
    // This property is critical for deserializing keys from future versions with larger size
    // limits.

    // Verify: fromRawBytes accepts empty bytes.
    ByteString emptyBytes = ByteString.EMPTY;
    SliceKey keyFromEmpty = SliceKeyAccessor.fromRawBytes(emptyBytes);
    assertThat(keyFromEmpty.toRawBytes())
        .as("fromRawBytes should accept empty bytes")
        .isEqualTo(emptyBytes);

    // Verify: fromRawBytes accepts bytes > 32 bytes.
    // This is important for forward compatibility: if we increase the limit to 64 bytes in the
    // future, we need to be able to deserialize those keys.
    byte[] longBytesArray = new byte[64];
    for (int i = 0; i < 64; i++) {
      longBytesArray[i] = (byte) i;
    }
    ByteString longBytes = ByteString.copyFrom(longBytesArray);
    SliceKey keyFromLong = SliceKeyAccessor.fromRawBytes(longBytes);
    assertThat(keyFromLong.toRawBytes())
        .as("fromRawBytes should accept bytes longer than 32 bytes without validation")
        .isEqualTo(longBytes);
  }
}
