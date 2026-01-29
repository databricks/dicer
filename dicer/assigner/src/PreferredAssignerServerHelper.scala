package com.databricks.dicer.assigner

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

import com.databricks.caching.util.GenericRpcServiceBuilder
import com.databricks.api.proto.dicer.assigner.{
  HeartbeatRequestP,
  HeartbeatResponseP,
  PreferredAssignerServiceGrpc
}

import com.databricks.rpc.tls.TLSOptions

import com.databricks.api.proto.dicer.assigner.PreferredAssignerServiceGrpc
import com.databricks.api.proto.dicer.assigner.PreferredAssignerServiceGrpc.PreferredAssignerServiceStub
import io.grpc.ChannelCredentials
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel

/**
 * Helper class for preferred assigner server implementations.
 *
 * @note This does not actually need to be a class in OSS, but it's here
 * to match the internal implementation signature for compatibility.
 */
class PreferredAssignerServerHelper(
    assignerTlsOptionsOpt: Option[TLSOptions]
) {

  /** Creates a heartbeat stub to `address`. */
  def createStub(address: URI): PreferredAssignerServiceStub = {
    // Use gRPC's transport-independent TLS API.
    val credentials: ChannelCredentials = assignerTlsOptionsOpt match {
      case Some(tlsOptions) =>
        // PreferredAssigner RPCs may be configured for TLS.
        tlsOptions.channelCredentials()
      case None =>
        InsecureChannelCredentials.create()
    }
    val channel: ManagedChannel =
      Grpc.newChannelBuilderForAddress(address.getHost, address.getPort, credentials).build()
    PreferredAssignerServiceGrpc.stub(channel)
  }
}

/** Helper utilities for preferred assigner server implementations. */
object PreferredAssignerServerHelper {

  /**
   * Registers a PreferredAssignerService that handles heartbeat RPCs from other Assigners with the
   * provided service builder.
   */
  def registerPreferredAssignerService(
      serviceBuilder: GenericRpcServiceBuilder,
      heartbeatHandler: HeartbeatRequestP => Future[HeartbeatResponseP]): Unit = {
    serviceBuilder.addService(
      PreferredAssignerServiceGrpc.bindService(
        new PreferredAssignerServiceGrpc.PreferredAssignerService {
          override def heartbeat(req: HeartbeatRequestP): Future[HeartbeatResponseP] = {
            heartbeatHandler(req)
          }
        },
        // Use global ExecutionContext for ScalaPB's internal (lightweight) processing. The actual
        // heartbeat handler execution is managed by the provided heartbeatHandler on a separate EC.
        ExecutionContext.global
      )
    )
  }
}
