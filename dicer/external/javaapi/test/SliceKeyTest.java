package com.databricks.dicer.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.highSliceKeyFromProto;
import static com.databricks.dicer.external.javaapi.TestSliceUtils.identity;
import static com.databricks.dicer.external.javaapi.TestSliceUtils.sliceKeyFromProto;
import static com.databricks.dicer.external.javaapi.TestSliceUtils.successor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.caching.util.javaapi.TestUtils;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP.HighSliceKeyP;
import com.databricks.dicer.common.test.TestData.SliceKeyTestDataP.ToStringTestCaseP;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SliceKeyTest {

  /** Test data loaded from textproto file. */
  private static final SliceKeyTestDataP TEST_DATA = loadSliceKeyTestData();

  /** Converts a list of int32 values from proto to a ByteString. */
  private static ByteString intsToBytes(List<Integer> ints) {
    byte[] bytes = new byte[ints.size()];
    for (int i = 0; i < ints.size(); i++) {
      bytes[i] = (byte) ints.get(i).intValue();
    }
    return ByteString.copyFrom(bytes);
  }

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
  void testCompareTo() {
    // Test plan: Verify that the `compareTo` implementation correctly compares `SliceKey` and
    // `HighSliceKey` using test data loaded from textproto.
    List<HighSliceKey> orderedHighSliceKeys = new ArrayList<>();
    List<SliceKey> orderedSliceKeys = new ArrayList<>();

    for (HighSliceKeyP highSliceKeyP : TEST_DATA.getOrderedHighSliceKeysList()) {
      HighSliceKey highSliceKey = highSliceKeyFromProto(highSliceKeyP);
      if (highSliceKey.isFinite()) {
        orderedSliceKeys.add(highSliceKey.asFinite());
      }
      orderedHighSliceKeys.add(highSliceKey);
    }
    TestUtils.checkComparisons(orderedSliceKeys);
    TestUtils.checkComparisons(orderedHighSliceKeys);
  }

  @Test
  void testEqualsAndHashCode() {
    // Test plan: Group `HighSliceKey` instances such that keys within the same group are equal,
    // and keys across different groups are not equal. Verify that `equals` and `hashCode` behaves
    // as expected.
    List<List<HighSliceKey>> javaGroups =
        List.of(
            List.of(identity("abcd"), identity("abcd")),
            List.of(identity(new byte[] {1}), identity(new byte[] {1})),
            List.of(identity(new byte[] {}), identity(new byte[] {})),
            List.of(InfinitySliceKey.INSTANCE, InfinitySliceKey.INSTANCE));
    TestUtils.checkEquality(javaGroups);
  }

  @Test
  void testIsFinite() {
    // Test plan: Verify that `isFinite` returns true for SliceKeys but false for InfinitySliceKey.
    SliceKey key1 = identity(new byte[] {1, 2, 3, 4});
    SliceKey key2 = identity("abcd");

    assertThat(key1.isFinite()).isTrue();
    assertThat(key2.isFinite()).isTrue();
    assertThat(InfinitySliceKey.INSTANCE.isFinite()).isFalse();
  }

  @Test
  void testAsFinite() {
    // Test plan: Verify that asFinite returns the original instance for SliceKey instances and
    // throws an exception for InfinitySliceKey.
    SliceKey key1 = identity(new byte[] {1, 2, 3, 4});
    SliceKey key2 = identity("abcd");

    assertThat(key1.asFinite()).isSameAs(key1);
    assertThat(key2.asFinite()).isSameAs(key2);
    assertThatThrownBy(InfinitySliceKey.INSTANCE::asFinite)
        .isInstanceOf(ClassCastException.class)
        .hasMessage("InfinitySliceKey is not finite");
  }

  @Test
  void testToString() {
    // Test plan: Run toString on SliceKeys created from shared proto test data. Verify that the
    // string representations match the expected values specified in the test data.
    for (ToStringTestCaseP testCase : TEST_DATA.getToStringTestCasesList()) {
      SliceKey key = sliceKeyFromProto(testCase.getSliceKey());
      if (testCase.getIsSuccessor()) {
        SliceKey successor = successor(key);
        assertThat(successor.toString()).isEqualTo(testCase.getExpectedString());
      } else {
        assertThat(key.toString()).isEqualTo(testCase.getExpectedString());
      }
    }
  }

  @Test
  void testFingerprintBuilder() {
    // Test plan: Verify that the farmhash fingerprint64 builder produces the expected results
    // using test cases from shared proto test data.

    for (SliceKeyTestDataP.FarmhashBuilderTestCaseP testCase :
        TEST_DATA.getFarmhashBuilderTestCasesList()) {
      String description = testCase.getDescription();

      // Setup: build the key by applying operations
      SliceKeyBuilder builder = SliceKey.newFingerprintBuilder();
      for (SliceKeyTestDataP.FarmhashBuilderTestCaseP.BuilderOperationP op :
          testCase.getOperationsList()) {
        switch (op.getOperationCase()) {
          case PUT_LONG:
            builder = builder.putLong(op.getPutLong());
            break;
          case PUT_STRING:
            builder = builder.putString(op.getPutString());
            break;
          case PUT_BYTES:
            builder = builder.putBytes(op.getPutBytes());
            break;
          case OPERATION_NOT_SET:
            throw new IllegalArgumentException("Operation not set in test data");
        }
      }
      SliceKey key = builder.build();

      // Verify: key matches expected raw bytes
      ByteString expectedRawBytes = intsToBytes(testCase.getExpectedRawBytesList());
      assertThat(key.toRawBytes())
          .as("Builder test case '%s' should produce expected raw bytes", description)
          .isEqualTo(expectedRawBytes);
    }
  }

  @Test
  void testPutBytesOverloads() {
    // Test plan: Verify that all putBytes overloads (ByteString, byte[], ByteBuffer) produce
    // identical SliceKey results when given equivalent byte data.

    // Setup: create test data.
    byte[] testBytes = new byte[] {1, 2, 3, 4, 5};
    ByteString testByteString = ByteString.copyFrom(testBytes);
    ByteBuffer testByteBuffer = ByteBuffer.wrap(testBytes);

    // Setup: build keys with each overload.
    SliceKey keyFromByteString =
        SliceKey.newFingerprintBuilder().putString("prefix-").putBytes(testByteString).build();

    SliceKey keyFromByteArray =
        SliceKey.newFingerprintBuilder().putString("prefix-").putBytes(testBytes).build();

    SliceKey keyFromByteBuffer =
        SliceKey.newFingerprintBuilder().putString("prefix-").putBytes(testByteBuffer).build();

    // Verify: all three overloads produce the same result.
    assertThat(keyFromByteString).isEqualTo(keyFromByteArray);
    assertThat(keyFromByteString).isEqualTo(keyFromByteBuffer);
    assertThat(keyFromByteArray).isEqualTo(keyFromByteBuffer);

    // Verify: ByteBuffer position is not modified after putBytes.
    testByteBuffer.rewind();
    assertThat(testByteBuffer.position()).isEqualTo(0);
    SliceKey.newFingerprintBuilder().putBytes(testByteBuffer).build();
    assertThat(testByteBuffer.position())
        .as("putBytes should not modify ByteBuffer position")
        .isEqualTo(0);
  }

  @Test
  void testFromTrustedFingerprint() {
    // Test plan: Verify that fromTrustedFingerprint correctly creates SliceKeys for valid inputs
    // (bytes <= 32) and round-trips correctly, using ordered slice keys from shared test data.
    for (HighSliceKeyP highSliceKeyP : TEST_DATA.getOrderedHighSliceKeysList()) {
      HighSliceKey highSliceKey = highSliceKeyFromProto(highSliceKeyP);
      if (highSliceKey.isFinite()) {
        SliceKey original = highSliceKey.asFinite();
        ByteString bytes = original.toRawBytes();
        if (bytes.size() <= 32) {
          SliceKey roundtrip = SliceKey.fromTrustedFingerprint(bytes);
          assertThat(roundtrip)
              .as("Round trip should succeed for %s", original)
              .isEqualTo(original);
          assertThat(roundtrip.toRawBytes())
              .as("Bytes should match for %s", original)
              .isEqualTo(bytes);
        }
      }
    }
  }

  @Test
  void testFromTrustedFingerprintValidation() {
    // Test plan: Verify that fromTrustedFingerprint correctly rejects invalid inputs (bytes > 32)
    // using test cases from shared proto test data.
    for (SliceKeyTestDataP.FromTrustedFingerprintErrorTestCaseP testCase :
        TEST_DATA.getFromTrustedFingerprintErrorTestCasesList()) {
      ByteString rawBytes = intsToBytes(testCase.getRawBytesList());
      String expectedError = testCase.getExpectedError();
      assertThat(expectedError).as("test case must set expected_error").isNotNull();

      assertThatThrownBy(() -> SliceKey.fromTrustedFingerprint(rawBytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(expectedError);
    }
  }
}
