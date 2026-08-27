package com.databricks.dicer.friend.external

import com.databricks.common.http.Headers.HEADER_DICER_SECONDARY_SLICE_KEY
import com.databricks.dicer.external.SliceKey
import com.google.protobuf.ByteString
import java.util.Base64

/**
 * Provides friend access to header serialization helpers for Dicer two-level sharding. See
 * [[TwoLevelShardingClerkAccessor]] for more details on two-level sharding. Usage of this API is
 * currently restricted, as this feature is currently under development. Please reach out to
 * the maintainers with any questions.
 */
object TwoLevelShardingHeaders {

  /**
   * Returns a single-entry header map carrying the base64-encoded raw bytes of
   * `secondarySliceKey` under [[HEADER_DICER_SECONDARY_SLICE_KEY]]. Callers should merge the
   * returned map into their request headers.
   */
  def createSecondarySliceKeyHeader(secondarySliceKey: SliceKey): Map[String, String] = {
    Map(
      HEADER_DICER_SECONDARY_SLICE_KEY ->
      Base64.getEncoder.encodeToString(secondarySliceKey.toRawBytes.toByteArray)
    )
  }

  /**
   * Extracts and decodes the secondary [[SliceKey]] from `headers`. Returns [[None]] if
   * [[HEADER_DICER_SECONDARY_SLICE_KEY]] is absent.
   *
   * @throws IllegalArgumentException if [[HEADER_DICER_SECONDARY_SLICE_KEY]] is present but its
   *                                  value is not valid base64.
   */
  @throws[IllegalArgumentException]("if the header value is not valid base64")
  def getSecondarySliceKeyFromHeader(headers: Map[String, String]): Option[SliceKey] = {
    headers.get(HEADER_DICER_SECONDARY_SLICE_KEY).map { headerValue: String =>
      SliceKey.fromRawBytes(ByteString.copyFrom(Base64.getDecoder.decode(headerValue)))
    }
  }
}
