package com.databricks.dicer.friend.external

import com.databricks.caching.util.TestUtils
import com.google.protobuf.ByteString
import com.databricks.dicer.friend.external.test.TwoLevelShardingHeadersTestDataP
import com.databricks.dicer.friend.external.test.TwoLevelShardingHeadersTestDataP.{
  DecodeCaseP,
  EncodeCaseP,
  RoundTripCaseP
}
import com.databricks.dicer.friend.external.test.TwoLevelShardingHeadersTestDataP.DecodeCaseP.{
  ExpectedOutcome,
  HeaderEntryP
}
import com.databricks.dicer.external.SliceKey
import com.databricks.testing.DatabricksTest

class TwoLevelShardingHeadersSuite extends DatabricksTest {

  /**
   * Test data shared with the Rust `two_level_sharding_headers_test` so that both languages
   * serialize and parse the secondary-slice-key header identically.
   */
  private lazy val TEST_DATA: TwoLevelShardingHeadersTestDataP =
    TestUtils.loadTestData[TwoLevelShardingHeadersTestDataP](
      "dicer/friend/external/test/data/two_level_sharding_headers_test_data.textproto"
    )

  test("createSecondarySliceKeyHeader serializes to the expected header name and value") {
    // Test plan: For each encode case, verify that createSecondarySliceKeyHeader produces exactly
    // a single-entry map with the expected header name and base64 value.
    for (encodeCase: EncodeCaseP <- TEST_DATA.encodeCases) {
      val secondarySliceKey: SliceKey =
        SliceKeyAccessor.fromRawBytes(encodeCase.getSecondarySliceKey)

      val headers: Map[String, String] =
        TwoLevelShardingHeaders.createSecondarySliceKeyHeader(secondarySliceKey)

      assertResult(Map(encodeCase.getExpectedHeaderName -> encodeCase.getExpectedHeaderValue))(
        headers
      )
    }
  }

  test("getSecondarySliceKeyFromHeader extracts, absents, or rejects per the decode cases") {
    // Test plan: For each decode case, build the header map and verify that
    // getSecondarySliceKeyFromHeader returns the expected SliceKey, signals absence via None, or
    // throws IllegalArgumentException with the expected message on malformed base64, as the case
    // dictates.
    for (decodeCase: DecodeCaseP <- TEST_DATA.decodeCases) {
      val headers: Map[String, String] =
        decodeCase.headers.map { entry: HeaderEntryP =>
          entry.getName -> entry.getValue
        }.toMap

      decodeCase.expectedOutcome match {
        case ExpectedOutcome.ExpectedSliceKey(sliceKeyBytes: ByteString) =>
          val expectedSliceKey: SliceKey = SliceKeyAccessor.fromRawBytes(sliceKeyBytes)
          assertResult(Some(expectedSliceKey))(
            TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
          )
        case ExpectedOutcome.ExpectedAbsent(_: Boolean) =>
          assertResult(None)(
            TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
          )
        case ExpectedOutcome.ExpectedErrorMessage(expectedErrorMessage: String) =>
          TestUtils.assertThrow[IllegalArgumentException](expectedErrorMessage) {
            TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
          }
        case ExpectedOutcome.Empty =>
          fail("Decode case must set an expected outcome")
      }
    }
  }

  test("createSecondarySliceKeyHeader then getSecondarySliceKeyFromHeader round-trips the key") {
    // Test plan: For each round-trip case, verify that serializing a slice key into a header
    // and then parsing it back returns the original key.
    for (roundTripCase: RoundTripCaseP <- TEST_DATA.roundTripCases) {
      val secondarySliceKey: SliceKey =
        SliceKeyAccessor.fromRawBytes(roundTripCase.getSecondarySliceKey)

      val headers: Map[String, String] =
        TwoLevelShardingHeaders.createSecondarySliceKeyHeader(secondarySliceKey)

      assertResult(Some(secondarySliceKey))(
        TwoLevelShardingHeaders.getSecondarySliceKeyFromHeader(headers)
      )
    }
  }
}
