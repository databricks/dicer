package com.databricks.dicer.client

import java.net.{InetAddress, URI}

import com.databricks.dicer.common.ClientType
import com.databricks.rpc.tls.TLSOptions

/**
 * The connection configuration a [[WatchStubManager]] needs to create its watch stubs.
 *
 * These fields describe the watch connection itself, as opposed to the lookup/request identity
 * fields on [[SliceLookupConfig]]. Two lookups whose `WatchStubManagerConfig`s are equal (e.g. in
 * MultiClerk) describe the same watch connection, which is what lets a shared manager be reused
 * across them.
 *
 * @param clientType The type of the client (Clerk or Slicelet), used to derive `clientName`.
 * @param targetName The name of the Dicer target being watched, used only to derive `clientName`;
 *                   each watch request carries its own target, so this never selects what is
 *                   watched. This is the target's name rather than the target itself because the
 *                   name is all a watch connection is scoped by: a MultiClerk's Clerks watch one
 *                   connection for many targets that differ only in instance ID.
 * @param watchAddress The default watch address the manager's default stub connects to. If the
 *                     client is in the data plane, this is the address of the S2S Proxy that
 *                     forwards watch requests to the general cluster.
 * @param tlsOptionsOpt TLS options for the watch channels. All stubs the manager creates use these.
 * @param watchFromDataPlane Whether the client is running in the data plane and watching
 *                           assignments from the Dicer Assigner running in the region's general
 *                           cluster.
 * @param sourceIpOpt Local (source) address to bind the watch connection to, so it egresses through
 *                    the interface owning that address, and which `clientName` includes so a
 *                    source-bound connection's channels and metrics stay distinct. `None` lets the
 *                    kernel pick a source address from the route.
 */
case class WatchStubManagerConfig private[dicer] (
    clientType: ClientType,
    targetName: String,
    watchAddress: URI,
    tlsOptionsOpt: Option[TLSOptions],
    watchFromDataPlane: Boolean,
    sourceIpOpt: Option[InetAddress]) {

  /** Client name used to name the gRPC channels and label the watch-channel metric. */
  // Derived in the class body rather than accepted as a parameter, so that it is always computed
  // from the constructor parameters.
  val clientName: String = {
    val baseClientName: String = s"dicer-$clientType-$targetName"
    sourceIpOpt.fold(baseClientName)(
      (sourceIp: InetAddress) => s"$baseClientName-src-${sourceIp.getHostAddress}"
    )
  }
}
