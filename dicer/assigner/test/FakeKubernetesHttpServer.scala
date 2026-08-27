package com.databricks.dicer.assigner

import java.io.OutputStream
import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import com.databricks.dicer.assigner.FakeKubernetesServer.{FakeK8sRequest, FakeK8sResponse}

/**
 * Implementation of the HTTP transport for [[FakeKubernetesServer]], backed by the JDK's built-in
 * [[HttpServer]].
 *
 * Callers MUST drive this fake off the [[FakeKubernetesServer]] SequentialExecutionContext (i.e.
 * with an asynchronous poll). A caller that drove it with a synchronous, on-SEC request would
 * self-deadlock: the request handler blocks its server thread waiting for a response the SEC can no
 * longer produce, surfacing only after [[FakeKubernetesHttpServer.RESPONSE_TIMEOUT]].
 *
 * @param server the JDK HTTP server, already bound and wired to a [[ListPodsHandler]].
 * @param executor the server's request-dispatch thread pool, shut down by [[stop]].
 * @param handler Produces a [[FakeK8sResponse]] for each [[FakeK8sRequest]] (the extracted
 *                namespace path parameter, labelSelector query parameter, and Authorization
 *                header).
 */
private[assigner] final class FakeKubernetesHttpServer private (
    server: HttpServer,
    executor: ExecutorService,
    handler: FakeK8sRequest => Future[FakeK8sResponse]) {

  server.createContext("/api/v1/namespaces/", new ListPodsHandler)

  /** Starts the server synchronously; it is already bound to the loopback ephemeral port. */
  def start(): Unit = server.start()

  /** Stops the server without waiting for in-flight exchanges and shuts down its executor. */
  def stop(): Unit = {
    server.stop(0)
    executor.shutdownNow()
  }

  /**
   * Returns the port the server is listening on. The server binds at creation, so this is valid
   * even before [[start]].
   */
  def port: Int = server.getAddress.getPort

  /**
   * [[HttpHandler]] for `GET /api/v1/namespaces/{namespace}/pods`. Extracts the request parameters
   * and delegates response production to [[handler]].
   *
   * The handler runs on the server's executor thread pool. Blocking on the [[handler]] future here
   * is safe because [[FakeKubernetesServer]] resolves it on its own SequentialExecutionContext,
   * which is never blocked waiting on this server (the checker under test polls asynchronously via
   * OkHttp's dispatcher, not on the SEC). See the class specs for the on-SEC self-deadlock caveat.
   */
  private class ListPodsHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      try {
        val namespaceOpt: Option[String] = extractNamespace(exchange.getRequestURI.getPath)
        val labelSelectorOpt: Option[String] = extractQueryParam(
          Option(exchange.getRequestURI.getRawQuery),
          "labelSelector"
        )
        val authorization: Option[String] =
          Option(exchange.getRequestHeaders.getFirst("Authorization"))

        val response: FakeK8sResponse = Await.result(
          handler(FakeK8sRequest(namespaceOpt, labelSelectorOpt, authorization)),
          FakeKubernetesHttpServer.RESPONSE_TIMEOUT
        )
        writeResponse(exchange, response.statusCode, response.jsonBody)
      } catch {
        // The handler never fails the future in practice (validation returns a 400 response, and
        // response production does not throw). Writing a 500 on an unexpected failure keeps the
        // client seeing an HTTP status rather than a dropped connection.
        case NonFatal(ex) =>
          writeResponse(
            exchange,
            statusCode = 500,
            jsonBody = s"""{"message":"${ex.getMessage}"}"""
          )
      } finally {
        exchange.close()
      }
    }
  }

  /**
   * Extracts the `{namespace}` path segment from a `/api/v1/namespaces/{namespace}/pods` path.
   * Returns `None` if the path does not match that shape. The length guard rejects paths where the
   * prefix and suffix overlap (e.g. `/api/v1/namespaces/pods`). Multi-segment namespaces are not
   * rejected, which is immaterial because the only client is the membership checker, which always
   * sends a single non-empty namespace segment.
   */
  private def extractNamespace(path: String): Option[String] = {
    val prefix: String = "/api/v1/namespaces/"
    val suffix: String = "/pods"
    if (path.startsWith(prefix) && path.endsWith(suffix) &&
      path.length >= prefix.length + suffix.length) {
      val namespace: String = path.substring(prefix.length, path.length - suffix.length)
      Some(namespace).filter(_.nonEmpty)
    } else {
      None
    }
  }

  /** Extracts a single query parameter value from a raw (undecoded) query string. */
  private def extractQueryParam(rawQueryOpt: Option[String], name: String): Option[String] = {
    rawQueryOpt.flatMap { rawQuery: String =>
      rawQuery
        .split("&")
        .collectFirst {
          case pair: String if pair.startsWith(s"$name=") =>
            URLDecoder.decode(pair.stripPrefix(s"$name="), StandardCharsets.UTF_8.name())
        }
        .filter(_.nonEmpty)
    }
  }

  /** Writes the status code and JSON body to the exchange. */
  private def writeResponse(exchange: HttpExchange, statusCode: Int, jsonBody: String): Unit = {
    val bodyBytes: Array[Byte] = jsonBody.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, bodyBytes.length.toLong)
    val out: OutputStream = exchange.getResponseBody
    try {
      out.write(bodyBytes)
    } finally {
      out.close()
    }
  }
}

/** Factory for [[FakeKubernetesHttpServer]]. */
private[assigner] object FakeKubernetesHttpServer {

  /**
   * How long a server thread waits for the response before failing the request. Set above the
   * client-side read timeout (see FakeKubernetesTestSupport) so the client observes its own read
   * timeout first; the headroom only elapses if a caller wrongly drives the fake on its SEC and
   * self-deadlocks, in which case this backstop surfaces the hang.
   */
  private val RESPONSE_TIMEOUT: FiniteDuration = 15.seconds

  /**
   * Size of the request-dispatch thread pool. Small: each checker keeps at most one poll in flight,
   * and a blocked handler must not stall the single default dispatch thread.
   */
  private val NUM_DISPATCH_THREADS: Int = 4

  /**
   * Creates a fake Kubernetes HTTP server that routes `GET /api/v1/namespaces/{namespace}/pods`
   * requests to `handler`. The server binds to the loopback address on an ephemeral port (the
   * client dials `http://localhost:$port`); it is not started, so call
   * [[FakeKubernetesHttpServer.start]].
   */
  def create(handler: FakeK8sRequest => Future[FakeK8sResponse]): FakeKubernetesHttpServer = {
    val server: HttpServer =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    // Daemon threads so a suite that forgets to call stop() can never keep a forked test JVM alive.
    val executor: ExecutorService = Executors.newFixedThreadPool(
      NUM_DISPATCH_THREADS,
      new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
          val thread: Thread = new Thread(r, "fake-k8s-server")
          thread.setDaemon(true)
          thread
        }
      }
    )
    server.setExecutor(executor)
    new FakeKubernetesHttpServer(server, executor, handler)
  }
}
