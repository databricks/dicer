package com.databricks.dicer.client

import java.net.URI

import io.grpc.ChannelCredentials
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder

import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc
import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc.AssignmentServiceStub
import com.databricks.caching.util.StatusOr
import com.databricks.dicer.common.WatchServerHelper
import com.databricks.rpc.tls.TLSOptions

/**
 * Helper class to create watch stubs for watching assignments.
 *
 * @note `clientName` and `subscriberDebugName` are unused but match the internal implementation
 * signature for compatibility.
 */
class WatchStubHelper private[dicer] (
    clientName: String,
    subscriberDebugName: String,
    defaultWatchAddress: URI,
    tlsOptionsOpt: Option[TLSOptions],
    watchFromDataPlane: Boolean) {

  /**
   * The default channel to use for watching assignments.
   * It is bound to the `defaultWatchAddress` provided when the class is instantiated.
   */
  private val defaultGrpcChannel: ManagedChannel = createGrpcChannel(defaultWatchAddress)

  /**
   * PRECONDITION: If [[redirectAddressOpt]] is provided, it must be a URI containing a direct
   *               pod IP address and port (e.g., `10.0.0.1:8080`) or `localhost:port` (for tests).
   *
   * Creates a watch stub for watching assignments.
   *
   * @param redirectAddressOpt The address of the redirect target to create a watch stub for.
   *                           If not provided, returns a stub bound to [[defaultWatchAddress]].
   */
  private[dicer] def createWatchStub(redirectAddressOpt: Option[URI]): AssignmentServiceStub = {
    val channel: ManagedChannel = redirectAddressOpt match {
      case None => defaultGrpcChannel
      case Some(redirectAddress: URI) =>
        createGrpcChannel(redirectAddress)
    }
    AssignmentServiceGrpc.stub(channel)
  }

  /**
   * Corrects the address if it was created without a scheme. For example, if the original string
   * address was "localhost:8080", then this method interprets it as "http://localhost:8080"
   * to avoid URI parsing issues (such as "localhost" being treated as the scheme).
   */
  private def normalizeAddress(address: URI): URI = {
    val addressString: String = address.toString
    val normalizedAddress: String =
      if (addressString.contains("://")) addressString else "http://" + addressString
    new URI(normalizedAddress)
  }

  /** Creates a gRPC channel that's bound to the provided `address`. */
  private def createGrpcChannel(address: URI): ManagedChannel = {
    val correctedUri: URI = normalizeAddress(address)

    // Use gRPC's transport-independent TLS API.
    val credentials: ChannelCredentials = tlsOptionsOpt match {
      case Some(tlsOptions) => tlsOptions.channelCredentials()
      case None => InsecureChannelCredentials.create()
    }

    // Create a channel builder bound to the corrected address.
    val channelBuilder: ManagedChannelBuilder[_] =
      Grpc
        .newChannelBuilderForAddress(correctedUri.getHost, correctedUri.getPort, credentials)
        .maxInboundMessageSize(WatchServerHelper.MAX_WATCH_MESSAGE_CONTENT_LENGTH_BYTES)
    // Note: Connect timeout is not configurable in gRPC's transport-independent API.
    // Connection establishment uses OS-level TCP defaults (typically 20-120 seconds depending
    // on the OS). This is by design to maintain transport independence. The RPC-level deadline
    // (WATCH_RPC_TIMEOUT) provides fail-fast behavior by timing out the entire call including
    // connection establishment, so clients will fail fast and retry if the server is unreachable.

    channelBuilder.build()
  }
}
