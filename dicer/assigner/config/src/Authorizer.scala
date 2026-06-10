package com.databricks.dicer.assigner.config

import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.rpc.RPCContext

/**
 * Represents an authorization policy for a Dicer target, which controls whether a watch request
 * received by the Assigner should be allowed for a given target. This authorizer is not applied to
 * Clerk-to-Slicelet traffic.
 */
trait Authorizer {

  /**
   * Checks whether a watch request is authorized.
   *
   * @param target the target the request wants to watch
   * @param rpcContext RPC context for the watch request, including request headers
   * @param clientType whether the request came from a Clerk or Slicelet
   * @param trustedWatchAnyTargetServices services allowed to watch any target regardless of the
   *                                      request's target identity
   */
  @throws[UnauthorizedException]("if the request is not authorized")
  def checkAuthorized(
      target: Target,
      rpcContext: RPCContext,
      clientType: ClientType,
      trustedWatchAnyTargetServices: Set[String]): Unit
}
