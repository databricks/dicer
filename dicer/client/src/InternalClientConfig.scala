package com.databricks.dicer.client

import java.net.{InetAddress, URI}

import com.databricks.rpc.tls.TLSOptions

/**
 * Internal config for a Clerk or Slicelet.
 *
 * [[InternalClientConfig]] is designed to be extensible for future use. Any property that pertains
 * to the client itself should be included in the [[InternalClientConfig]].
 *
 * See [[WatchStubManagerConfig]] for the watch-connection parameters.
 *
 * @param sliceLookupConfig The lookup-identity part of the [[SliceLookupCache]] key.
 * @param subscriberDebugName The debug name shown in the log and string representation of the
 *                            Clerk/Slicelet.
 */
case class InternalClientConfig(
    sliceLookupConfig: SliceLookupConfig,
    subscriberDebugName: String,
    watchAddress: URI,
    tlsOptionsOpt: Option[TLSOptions],
    watchFromDataPlane: Boolean,
    sourceIpOpt: Option[InetAddress]) {

  /** The watch-connection configuration used to build the client's [[WatchStubManager]]. */
  // Derived in the class body rather than accepted as a parameter, so that it is always computed
  // from the public constructor parameters.
  val watchStubManagerConfig: WatchStubManagerConfig = WatchStubManagerConfig(
    clientType = sliceLookupConfig.clientType,
    targetName = sliceLookupConfig.target.name,
    watchAddress = watchAddress,
    tlsOptionsOpt = tlsOptionsOpt,
    watchFromDataPlane = watchFromDataPlane,
    sourceIpOpt = sourceIpOpt
  )
}
