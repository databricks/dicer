package com.databricks.dicer.assigner

import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.databricks.api.proto.dicer.common.RedirectTokenP
import com.databricks.caching.util.{CachingErrorCode, PrefixLogger, Severity}
import com.google.protobuf.ByteString

/**
 * Encapsulates a [[RedirectTokenP]]. Produced by the server when issuing a redirect for target
 * migration, serialized to opaque bytes via [[toBytes]], and stored in
 * [[Redirect.redirectTokenOpt]].
 *
 * @param targetMigrationConfigVersion The version of the server's target migration config that
 *                                     resulted in the redirect. If the receiving server has an
 *                                     older version of the config, the newer config takes
 *                                     precedence and it assumes the request should be handled by
 *                                     the current Assigner.
 */
case class RedirectToken(targetMigrationConfigVersion: Int) {

  /** Returns the serialized representation suitable for [[Redirect.redirectTokenOpt]]. */
  def toBytes: ByteString = {
    new RedirectTokenP(
      targetMigrationConfigVersion = Some(targetMigrationConfigVersion)
    ).toByteString
  }
}

object RedirectToken {

  private val logger: PrefixLogger = PrefixLogger.create(getClass, "")

  /**
   * Parses a [[RedirectToken]] from its serialized representation (i.e. as output from
   * [[RedirectToken.toBytes]]).
   *
   * Returns `None` if `bytes` cannot be parsed as a [[RedirectTokenP]]; in that case a DEGRADED
   * alert is also fired as it indicates a bug in backwards-compatibility.
   *
   * Note: if the proto `target_migration_config_version` field is unset, the default value of 0 is
   * used. This is not expected to be triggered in practice because currently the server always sets
   * the field.
   */
  def tryFromBytes(bytes: ByteString): Option[RedirectToken] = {
    try {
      val proto: RedirectTokenP = RedirectTokenP.parseFrom(bytes.toByteArray)
      Some(RedirectToken(proto.getTargetMigrationConfigVersion))
    } catch {
      case NonFatal(e) =>
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.ASSIGNER_INVALID_REDIRECT_TOKEN,
          s"Failed to parse RedirectTokenP; treating as absent. Cause: $e",
          every = 30.seconds
        )
        None
    }
  }
}
