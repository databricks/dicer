package com.databricks.dicer.friend.external

import java.util.Base64

import com.google.protobuf.ByteString

import com.databricks.dicer.external.SliceKey
import com.databricks.testing.DatabricksTest

class TwoLevelShardingHeadersSuite extends DatabricksTest {

  /** Helper for creating a SliceKey, hashing the given key. */
  private def newSliceKey(key: String): SliceKey =
    SliceKey.newFingerprintBuilder().putString(key).build()

  test("createSecondarySliceKeyHeader + getSecondarySliceKeyFromHeader round-trip") {
    // Test plan: Verify that the header entry produced by createSecondarySliceKeyHeader is able to
    // be decoded back to the same SliceKey by getSecondarySliceKeyFromHeader.
    val secondarySliceKey: SliceKey = newSliceKey("secondary-key")
    val headers: Map[String, String] =
      TwoLevelShardingHeaders.createSecondarySliceKeyHeader(secondarySliceKey) ++
      // Add in another fake entry to the headers map.
      Map("x-databricks-internal-dicer-slice-key" -> "AAAA")
    assertResult(Some(secondarySliceKey))(
      TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
    )
  }

  test("getSecondarySliceKeyFromHeader extracts the SliceKey from a manually constructed Map") {
    // Test plan: Manually constructing the header map with the literal header string and a
    // base64-encoded value, then verify that getSecondarySliceKeyFromHeader returns the expected
    // SliceKey.
    val sliceKeyBytes: Array[Byte] = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    val expectedSliceKey: SliceKey =
      SliceKey.fromTrustedFingerprint(ByteString.copyFrom(sliceKeyBytes))
    val headers: Map[String, String] = Map(
      "x-databricks-internal-dicer-secondary-slice-key" -> Base64.getEncoder.encodeToString(
        sliceKeyBytes
      )
    )
    assertResult(Some(expectedSliceKey))(
      TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
    )
  }

  test("getSecondarySliceKeyFromHeader returns None when the secondary header is absent") {
    // Test plan: Verify that getSecondarySliceKeyFromHeader signals "no secondary key" via None
    // when the secondary header is not present.
    val headers: Map[String, String] = Map("x-databricks-internal-dicer-slice-key" -> "AAAA")
    assertResult(None)(TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers))
  }

  test("getSecondarySliceKeyFromHeader throws IllegalArgumentException on malformed base64") {
    // Test plan: Verify that a malformed header value results in an IllegalArgumentException being
    // thrown when it is attempted to be decoded.
    val malformedHeaders: Map[String, String] = Map(
      "x-databricks-internal-dicer-secondary-slice-key" -> "not valid base64!!!"
    )
    assertThrows[IllegalArgumentException] {
      TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(malformedHeaders)
    }
  }
}
