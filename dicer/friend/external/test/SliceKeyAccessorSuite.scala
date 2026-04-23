package com.databricks.dicer.friend.external

import com.databricks.caching.util.TestUtils
import com.google.protobuf.ByteString
import com.databricks.dicer.common.TestSliceUtils
import com.databricks.dicer.common.test.SliceKeyTestDataP
import com.databricks.dicer.external.{HighSliceKey, SliceKey}
import com.databricks.testing.DatabricksTest

class SliceKeyAccessorSuite extends DatabricksTest {

  /** Test data loaded from textproto file. */
  private lazy val TEST_DATA: SliceKeyTestDataP =
    TestUtils.loadTestData[SliceKeyTestDataP](
      "dicer/common/test/data/slice_key_test_data.textproto"
    )

  /** Ordered HighSliceKey values from shared test data. */
  private lazy val ORDERED_SLICE_KEYS: Vector[HighSliceKey] =
    TEST_DATA.orderedHighSliceKeys.map { highSliceKey =>
      TestSliceUtils.highSliceKeyFromProto(highSliceKey)
    }.toVector

  test("fromRawBytes and toRawBytes") {
    // Test plan: Verify that fromRawBytes and toRawBytes correctly round-trip SliceKey instances
    // using ordered test cases from shared proto test data.

    // Setup: test successful round trips using ordered slice keys.
    for (highSliceKey: HighSliceKey <- ORDERED_SLICE_KEYS) {
      if (!highSliceKey.isFinite) {
        // Skip InfinitySliceKey.
      } else {
        val original: SliceKey = highSliceKey.asFinite

        // Verify: round trip through toRawBytes/fromRawBytes via accessor (as customers do).
        val bytes: ByteString = original.toRawBytes
        val roundtrip: SliceKey = SliceKeyAccessor.fromRawBytes(bytes)

        assertResult(original, s"Round trip should succeed for $original")(roundtrip)
        assertResult(
          original.toRawBytes.toByteArray.toSeq,
          s"Bytes should match for $original"
        )(bytes.toByteArray.toSeq)
      }
    }
  }

  test("fromRawBytes accepts any length") {
    // Test plan: Verify that fromRawBytes accepts bytes of any length without validation, unlike
    // fromTrustedFingerprint which enforces a 32-byte limit. This property is critical
    // for deserializing keys from future versions with larger size limits.

    // Verify: fromRawBytes accepts empty bytes (via accessor, as customers do).
    val emptyBytes: ByteString = ByteString.EMPTY
    val keyFromEmpty: SliceKey = SliceKeyAccessor.fromRawBytes(emptyBytes)
    assertResult(emptyBytes, "fromRawBytes should accept empty bytes")(keyFromEmpty.toRawBytes)

    // Verify: fromRawBytes accepts bytes > 32 bytes (the current validation limit).
    // This is important for forward compatibility: if we increase the limit to 64 bytes in the
    // future, we need to be able to deserialize those keys.
    val longBytes: ByteString = ByteString.copyFrom((0 until 64).map(_.toByte).toArray)
    val keyFromLong: SliceKey = SliceKeyAccessor.fromRawBytes(longBytes)
    assertResult(
      longBytes,
      "fromRawBytes should accept bytes longer than 32 bytes without validation"
    )(keyFromLong.toRawBytes)
  }
}
