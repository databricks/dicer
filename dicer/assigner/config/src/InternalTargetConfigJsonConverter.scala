package com.databricks.dicer.assigner.config

import com.databricks.api.proto.dicer.assigner.config.InternalDicerTargetConfigP

/**
 * OSS stub: protobuf-java-util's `JsonFormat` (used by the internal converter to expand the
 * authorizer `Any` field on `TargetConfigFieldsP`) is not on the OSS classpath. OSS Dicer also
 * doesn't drive SAFE-flag JSON conversion through this codepath, so the methods are stubbed
 * rather than replaced with `DatabricksObjectMapper`-based equivalents.
 */
private[dicer] object InternalTargetConfigJsonConverter {

  /** Always throws; SAFE-flag JSON conversion is not supported in OSS. */
  def toJsonString(proto: InternalDicerTargetConfigP): String =
    throw new UnsupportedOperationException(
      "InternalTargetConfigJsonConverter.toJsonString is not supported in OSS Dicer."
    )

  /** Always throws; SAFE-flag JSON conversion is not supported in OSS. */
  def fromJsonString(jsonString: String): InternalDicerTargetConfigP =
    throw new UnsupportedOperationException(
      "InternalTargetConfigJsonConverter.fromJsonString is not supported in OSS Dicer."
    )
}
