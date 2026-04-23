package com.databricks.dicer.common

import scala.concurrent.duration._

import com.databricks.rpc.DatabricksServerWrapper
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.ExecutorService

import com.databricks.rpc.RPCContext
import com.databricks.threading.InstrumentedScheduledThreadPoolExecutor
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.ServerBuilder
import io.grpc.ServerCredentials

import com.databricks.caching.util.GenericRpcServiceBuilder
import com.databricks.api.proto.dicer.common.{
  AssignmentServiceGrpc,
  ClientRequestP,
  ClientResponseP
}

/**
 * Helper utilities for assignment watch server implementations. (Helpers that are not Dicer-
 * specific should be defined in [[com.databricks.caching.util]].)
 */
object WatchServerHelper {

  /** Timeout for the Watch RPC from a client perspective. */
  val WATCH_RPC_TIMEOUT: FiniteDuration = 5.seconds

  /**
   * Maximum byte size for watch request and response content.
   *
   * It is used for both the server-side request limit and the client-side response limit.
   */
  val MAX_WATCH_MESSAGE_CONTENT_LENGTH_BYTES: Int = 4 * 1024 * 1024

  /**
   * REQUIRES: `watchRpcTimeout` is at least 500 milliseconds
   *
   * Validates the timeout for a watch request. Exposed as separate method for the sake of the
   * `ClientResponse` and `InternalClientConfig` which both validate this timeout.
   */
  def validateWatchRpcTimeout(watchRpcTimeout: FiniteDuration): Unit = {
    require(
      watchRpcTimeout >= 500.milliseconds,
      s"Watch RPC timeout must be at least 500 milliseconds: $watchRpcTimeout"
    )
  }

  /**
   * The timeout on the server side so that the Watch RPC is "returned" rather than timed out. We
   * want to return the RPC with an empty message (no assignment) rather than let it time out since
   * a DEADLINE_EXCEEDED shows up as an error and can be confusing. Also, this model allows us to
   * return the known generation number for sync purposes.
   */
  def getWatchProcessingTimeout(clientWatchRpcTimeout: FiniteDuration): FiniteDuration = {
    // The duration before the actual deadline when the client RPC times out so that we can time
    // out at the server.
    val shortDurationBeforeTimeout: FiniteDuration = 5.seconds
    if (clientWatchRpcTimeout > shortDurationBeforeTimeout) {
      clientWatchRpcTimeout - shortDurationBeforeTimeout
    } else {
      // Time is less than the short duration - just allow part of it as budget, e.g., 50%.
      clientWatchRpcTimeout / 2
    }
  }

  /**
   * Configures and creates a unary RPC server for the Assignment Watch API, which is exported by
   * both the Dicer Assigner and Slicelets.
   *
   * `localPort` is not used.
   */
  def createWatchServer(
      conf: WatchServerConf with CommonSslConf,
      port: Int,
      localPort: Option[Int],
      serviceHandlerBuilder: GenericRpcServiceBuilder): DatabricksServerWrapper = {
    // Configure a few critical knobs:
    // - cap message sizes to handle large assignment payloads
    // - use a dedicated thread pool sized by config
    val executor: ExecutorService =
      InstrumentedScheduledThreadPoolExecutor.create("watch-server", conf.watchServerNumThreads)

    // Use gRPC's transport-independent TLS API.
    val credentials: ServerCredentials = conf.getDicerServerTlsOptions match {
      case Some(tlsOptions) => tlsOptions.serverCredentials()
      case None => InsecureServerCredentials.create()
    }

    val serverBuilder: ServerBuilder[_] =
      Grpc
        .newServerBuilderForPort(port, credentials)
        .maxInboundMessageSize(MAX_WATCH_MESSAGE_CONTENT_LENGTH_BYTES)
        .executor(executor)

    serviceHandlerBuilder.initBuilder(serverBuilder)

    new DatabricksServerWrapper(serviceHandlerBuilder.build(), executor)
  }

  /** Registers an AssignmentService with the provided service builder. */
  def registerAssignmentService(
      serviceBuilder: GenericRpcServiceBuilder,
      watchHandler: (RPCContext, ClientRequestP) => Future[ClientResponseP]): Unit = {
    serviceBuilder.addService(
      AssignmentServiceGrpc.bindService(
        new AssignmentServiceGrpc.AssignmentService {
          override def watch(req: ClientRequestP): Future[ClientResponseP] = {
            watchHandler(RPCContext(), req)
          }
        },
        // Use global ExecutionContext for ScalaPB's internal (lightweight) processing. The actual
        // watch handler execution is managed by the provided watchHandler on a separate EC.
        ExecutionContext.global
      )
    )
  }
}
