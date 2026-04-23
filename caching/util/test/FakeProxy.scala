package com.databricks.caching.util

import com.databricks.caching.util.Lock.withLock
import com.databricks.rpc.testing.TestTLSOptions
import com.google.common.io.ByteStreams
import javax.annotation.concurrent.GuardedBy
import io.grpc.{
  CallOptions,
  ChannelCredentials,
  ClientCall,
  Grpc,
  InsecureChannelCredentials,
  InsecureServerCredentials,
  ManagedChannel,
  Metadata,
  MethodDescriptor,
  Server,
  ServerBuilder,
  ServerCall,
  ServerCallHandler,
  ServerCredentials,
  ServerMethodDefinition,
  Status,
  StatusException,
  TlsChannelCredentials,
  TlsServerCredentials
}
import io.grpc.MethodDescriptor.MethodType

import java.io.{ByteArrayInputStream, File, InputStream}
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.locks.ReentrantLock

/**
 * A generic gRPC proxy for use in tests.
 *
 * `FakeProxy` forwards incoming gRPC calls to an upstream server determined by its
 * [[MetadataHandler]] or forwards the request to a fallback localhost port if the handler cannot
 * determine it.
 *
 * Use [[FakeProxy.createAndStart]] to create and start a proxy server. Use
 * [[FakeProxy.MetadataHandler]] to customize how each incoming request is validated and routed
 * before forwarding.
 *
 * The implementation is adapted from the example gRPC proxy found here:
 * https://github.com/grpc/grpc-java/blob/master/examples/src/main/java/io/grpc/examples/grpcproxy/GrpcProxy.java
 */
class FakeProxy private (server: Server, handler: FakeProxy.Handler) {

  /** Returns the port that the proxy is listening on. */
  def port: Int = server.getPort

  /**
   * Configures the set of fallback localhost ports used when the metadata handler cannot determine
   * an upstream from the request metadata.
   */
  def setFallbackUpstreamPorts(ports: Vector[Int]): Unit = handler.setFallbackUpstreamPorts(ports)

  /** Returns the gRPC metadata (i.e., HTTP headers) attached to the most recent proxied request. */
  def latestRequestMetadataOpt: Option[Metadata] = handler.getLatestRequestMetadataOpt

  /** Stops the proxy server. */
  def shutdown(): Unit = server.shutdownNow()
}

object FakeProxy {

  /**
   * The header that the fake proxy attaches to forwarded requests.
   *
   * This allows the upstream servers in tests to verify whether the request came through proxy.
   */
  val ADDED_HEADER: String = "X-Request-From-FakeProxy"

  /** A strategy for handling incoming gRPC request metadata before forwarding. */
  trait MetadataHandler {

    /**
     * Parses the `metadata` and returns the upstream host and port. If it returns `None`, the proxy
     * will fall back to one of its configured ports from [[FakeProxy.setFallbackUpstreamPorts]].
     */
    @throws[StatusException]("if the metadata is invalid or malformed")
    def extractUpstreamHostAndPort(metadata: Metadata): Option[(String, Int)]
  }

  /**
   * A [[ServerCallHandler]] that forwards incoming calls to the upstream determined by
   * `metadataHandler`. Also holds the fallback upstream ports used when the metadata handler cannot
   * determine an upstream from the request metadata.
   *
   * @param metadataHandler handles validation, routing, and header injection for each request.
   * @param upstreamChannelCredentials credentials used when opening channels to upstream servers.
   */
  private class Handler(
      metadataHandler: MetadataHandler,
      upstreamChannelCredentials: ChannelCredentials)
      extends ServerCallHandler[Array[Byte], Array[Byte]] {
    private val lock: ReentrantLock = new ReentrantLock

    @GuardedBy("lock")
    private var latestRequestMetadataOpt: Option[Metadata] = None

    @GuardedBy("lock")
    private var fallbackUpstreamPorts: Vector[Int] = Vector()

    def setFallbackUpstreamPorts(ports: Vector[Int]): Unit = withLock(lock) {
      fallbackUpstreamPorts = ports
    }

    def getLatestRequestMetadataOpt: Option[Metadata] = withLock(lock) {
      latestRequestMetadataOpt
    }

    override def startCall(
        serverCall: ServerCall[Array[Byte], Array[Byte]],
        metadata: Metadata): ServerCall.Listener[Array[Byte]] = {
      // Call the metadata handler before acquiring the lock to avoid holding the lock during
      // upcalls, which could introduce deadlocks.
      val upstreamOpt: Option[(String, Int)] = metadataHandler.extractUpstreamHostAndPort(metadata)

      // Acquire the lock only to update shared state and read the fallback ports if needed.
      val (host, port): (String, Int) = withLock(lock) {
        latestRequestMetadataOpt = Some(metadata)
        upstreamOpt.getOrElse {
          // The metadataHandler cannot determine the upstream, so randomly choose one of the
          // fallbacks.
          if (fallbackUpstreamPorts.isEmpty) {
            throw Status.UNAVAILABLE
              .withDescription("No fallback upstream ports configured")
              .asException
          }
          val port: Int =
            fallbackUpstreamPorts(ThreadLocalRandom.current.nextInt(fallbackUpstreamPorts.size))
          ("localhost", port)
        }
      }

      // Open a gRPC channel to the upstream. This is done outside the lock since channel creation
      // involves I/O. For simplicity, we open a separate channel per incoming request rather than
      // caching the channels.
      val channel: ManagedChannel =
        Grpc.newChannelBuilderForAddress(host, port, upstreamChannelCredentials).build()

      // Initiate a request to the upstream server.
      val clientCall: ClientCall[Array[Byte], Array[Byte]] =
        channel.newCall(serverCall.getMethodDescriptor, CallOptions.DEFAULT)
      val clientCallListener = new ClientCall.Listener[Array[Byte]] {
        override def onClose(status: Status, trailers: Metadata): Unit = {
          serverCall.close(status, trailers)
          channel.shutdownNow()
        }
        override def onHeaders(headers: Metadata): Unit = serverCall.sendHeaders(headers)
        override def onMessage(message: Array[Byte]): Unit = serverCall.sendMessage(message)
      }
      val outboundMetadata = new Metadata
      outboundMetadata.merge(metadata)
      outboundMetadata.put(Metadata.Key.of(ADDED_HEADER, Metadata.ASCII_STRING_MARSHALLER), "true")
      clientCall.start(clientCallListener, outboundMetadata)

      // Disable flow control.
      serverCall.request(Int.MaxValue)
      clientCall.request(Int.MaxValue)

      // Return a listener for the incoming RPC that will forward it to the outgoing call we
      // initiated above.
      new ServerCall.Listener[Array[Byte]] {
        override def onCancel(): Unit = clientCall.cancel("Cancelled", null)
        override def onHalfClose(): Unit = clientCall.halfClose()
        override def onMessage(message: Array[Byte]): Unit = clientCall.sendMessage(message)
      }
    }
  }

  /**
   * A marshaller that acts as a pure identity function for byte strings, since this proxy treats
   * requests and responses as opaque blobs.
   */
  private object ByteMarshaller extends MethodDescriptor.Marshaller[Array[Byte]] {
    override def parse(stream: InputStream): Array[Byte] = ByteStreams.toByteArray(stream)
    override def stream(value: Array[Byte]): InputStream = new ByteArrayInputStream(value)
  }

  /**
   * Creates and starts a fake proxy server on an arbitrary available port.
   *
   * @param metadataHandler handles metadata for each request.
   * @param useTls if true, uses TLS credentials sourced from [[TestTLSOptions]] for both the
   *               server and upstream channel; if false (default), uses insecure credentials.
   */
  def createAndStart(metadataHandler: MetadataHandler, useTls: Boolean = false): FakeProxy = {
    val (serverCredentials, upstreamChannelCredentials): (ServerCredentials, ChannelCredentials) =
      if (useTls) {
        // The same path is passed for both arguments of keyManager, which matches the pattern used
        // by TestTLSOptions.serverTlsOptions and clientTlsOptions.
        val serverCreds: ServerCredentials = TlsServerCredentials
          .newBuilder()
          .keyManager(
            new File(TestTLSOptions.serverKeystorePath),
            new File(TestTLSOptions.serverKeystorePath)
          )
          .trustManager(new File(TestTLSOptions.serverTruststorePath))
          .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
          .build()
        val channelCreds: ChannelCredentials = TlsChannelCredentials
          .newBuilder()
          .keyManager(
            new File(TestTLSOptions.clientKeystorePath),
            new File(TestTLSOptions.clientKeystorePath)
          )
          .trustManager(new File(TestTLSOptions.clientTruststorePath))
          .build()
        (serverCreds, channelCreds)
      } else {
        (
          InsecureServerCredentials.create(),
          InsecureChannelCredentials.create()
        )
      }
    val handler: Handler = new Handler(metadataHandler, upstreamChannelCredentials)
    val builder: ServerBuilder[_] = Grpc.newServerBuilderForPort(0, serverCredentials)
    builder.fallbackHandlerRegistry((methodName: String, _: String) => {
      ServerMethodDefinition.create(
        MethodDescriptor
          .newBuilder(ByteMarshaller, ByteMarshaller)
          .setFullMethodName(methodName)
          .setType(MethodType.UNKNOWN)
          .build(),
        handler
      )
    })
    new FakeProxy(builder.build().start(), handler)
  }
}
