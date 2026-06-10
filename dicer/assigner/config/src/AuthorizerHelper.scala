package com.databricks.dicer.assigner.config

import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.rpc.RPCContext
import com.google.protobuf.any.{Any => ProtoAny}

/**
 * Parses an [[Authorizer]] from a protocol buffer Any message. This implementation is currently a
 * no-op, kept for compatibility with the internal code.
 */
private[assigner] object AuthorizerHelper {

  /**
   * Fallback authorizer for targets without a configured authorizer. This authorizer is effectively
   * a no-op - it accepts every target, client type, and header combination.
   */
  private[config] val DEFAULT_AUTHORIZER: Authorizer = new Authorizer {
    override def checkAuthorized(
        target: Target,
        rpcContext: RPCContext,
        clientType: ClientType,
        trustedWatchAnyTargetServices: Set[String]): Unit = ()
  }

  /**
   * Returns [[DEFAULT_AUTHORIZER]] unconditionally, regardless of the contents of `authorizerOpt`.
   * No actual authorization mechanism is currently supported.
   */
  def fromAnyProto(authorizerOpt: Option[ProtoAny]): Authorizer = DEFAULT_AUTHORIZER

  /** Returns [[None]] unconditionally. */
  def toAnyProto(authorizer: Authorizer): Option[ProtoAny] = None
}
