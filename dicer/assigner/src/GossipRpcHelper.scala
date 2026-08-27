package com.databricks.dicer.assigner

import java.net.URI

import scala.concurrent.{ExecutionContext, Future}

import io.grpc.{ChannelCredentials, Grpc, InsecureChannelCredentials, ManagedChannel}

import com.databricks.api.proto.dicer.assigner.{GossipRequestP, GossipResponseP, GossipServiceGrpc}
import com.databricks.api.proto.dicer.assigner.GossipServiceGrpc.GossipServiceStub
import com.databricks.caching.util.GenericRpcServiceBuilder
import com.databricks.rpc.tls.TLSOptions

/**
 * Helper class for the Assigner gossip service.
 *
 * @note This does not actually need to be a class in OSS, but it's here to match the internal
 * implementation signature for compatibility.
 */
class GossipRpcHelper(assignerTlsOptions: Option[TLSOptions]) {

  /** Creates a gossip stub to `address`. */
  def createStub(address: URI): GossipServiceStub = {
    // Use gRPC's transport-independent TLS API.
    val credentials: ChannelCredentials = assignerTlsOptions match {
      case Some(tlsOptions) =>
        // Gossip RPCs may be configured for TLS.
        tlsOptions.channelCredentials()
      case None =>
        InsecureChannelCredentials.create()
    }
    val channel: ManagedChannel =
      Grpc.newChannelBuilderForAddress(address.getHost, address.getPort, credentials).build()
    GossipServiceGrpc.stub(channel)
  }
}

/** Helper utilities for the Assigner gossip service. */
object GossipRpcHelper {

  /**
   * Registers a GossipService that handles gossip RPCs from peer Assigners with the provided
   * service builder.
   */
  def registerGossipService(
      serviceBuilder: GenericRpcServiceBuilder,
      gossipHandler: GossipRequestP => Future[GossipResponseP]): Unit = {
    serviceBuilder.addService(
      GossipServiceGrpc.bindService(
        new GossipServiceGrpc.GossipService {
          override def gossip(req: GossipRequestP): Future[GossipResponseP] = {
            gossipHandler(req)
          }
        },
        // Use global ExecutionContext for ScalaPB's internal (lightweight) processing. The actual
        // gossip handler execution is managed by the provided gossipHandler on a separate EC.
        ExecutionContext.global
      )
    )
  }
}
