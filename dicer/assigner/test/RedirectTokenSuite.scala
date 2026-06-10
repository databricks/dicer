package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.common.RedirectTokenP
import com.databricks.caching.util.{CachingErrorCode, MetricUtils, Severity}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.google.protobuf.ByteString
import com.databricks.testing.DatabricksTest

class RedirectTokenSuite extends DatabricksTest {

  test("toBytes / tryFromBytes round-trip") {
    // Test plan: Construct a RedirectToken with a version, serialize, and parse it back. Confirm
    // equality is preserved.
    val tokens = Seq(
      RedirectToken(targetMigrationConfigVersion = 42),
      RedirectToken(targetMigrationConfigVersion = 0),
      RedirectToken(targetMigrationConfigVersion = 1)
    )
    for (token: RedirectToken <- tokens) {
      assertResult(Some(token))(RedirectToken.tryFromBytes(token.toBytes))
    }
    // Verify the serialized representations are unique (to avoid the case where the above assertion
    // passes because a bug results in `RedirectToken` always comparing as equal).
    val uniqueBytes = tokens.map((_: RedirectToken).toBytes).toSet
    assertResult(tokens.size)(uniqueBytes.size)
  }

  test("tryFromBytes defaults version to 0 when unset in proto") {
    // Test plan: When the serialized RedirectTokenP omits the `target_migration_config_version`
    // field, tryFromBytes parses it as `targetMigrationConfigVersion = 0`.
    val bytesWithoutVersion: ByteString =
      new RedirectTokenP(targetMigrationConfigVersion = None).toByteString
    assertResult(Some(RedirectToken(targetMigrationConfigVersion = 0)))(
      RedirectToken.tryFromBytes(bytesWithoutVersion)
    )
  }

  test("tryFromBytes returns None and fires alert on invalid bytes") {
    // Test plan: Verify `tryFromBytes` returns `None` on bytes that don't follow the proto wire
    // format and that the DEGRADED `ASSIGNER_INVALID_REDIRECT_TOKEN` alert counter advances by
    // exactly one.

    // Arbitrary bytes that don't follow the proto wire format.
    val invalidBytes: ByteString = ByteString.copyFrom(Array[Byte](0xff.toByte, 0xff.toByte))
    val invalidTokenAlertTracker: ChangeTracker[Int] = ChangeTracker[Int] { () =>
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_INVALID_REDIRECT_TOKEN,
        prefix = ""
      )
    }

    assertResult(None)(RedirectToken.tryFromBytes(invalidBytes))
    assertResult(1)(invalidTokenAlertTracker.totalChange())
  }
}
