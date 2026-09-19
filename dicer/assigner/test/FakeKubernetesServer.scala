package com.databricks.dicer.assigner

import java.io.OutputStream
import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.models.{V1ListMeta, V1Pod, V1PodList, V1Status}

import io.grpc.Status

import javax.annotation.concurrent.GuardedBy

import com.databricks.caching.util.{
  Cancellable,
  SequentialExecutionContext,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.dicer.assigner.FakeKubernetesServer.{
  FakeK8sResponse,
  PodScope,
  PodsOrError,
  ResponseHandle
}

/**
 * An HTTP server that fakes the Kubernetes `listNamespacedPod` endpoint for integration testing.
 *
 * This OSS implementation uses the JDK HTTP server because Databricks' Armeria server abstractions
 * are not available outside Universe. State and watch callbacks are serialized by [[sec]]; request
 * threads only parse and render HTTP messages.
 *
 * @param sec The [[SequentialExecutionContext]] that serializes all state access.
 */
private[assigner] final class FakeKubernetesServer private (sec: SequentialExecutionContext) {

  /** Pod state and active watchers, scoped by namespace and app name. */
  @GuardedBy("sec")
  private val podsByScope: mutable.Map[PodScope, WatchValueCell[PodsOrError]] = mutable.Map.empty

  /** Monotonically increasing fake Kubernetes resource version. */
  @GuardedBy("sec")
  private var nextResourceVersion: Int = 1

  /** Most recently observed Authorization header for each request scope that supplied one. */
  @GuardedBy("sec")
  private var authorizationByScope: Map[PodScope, String] = Map.empty

  /** Shared serializer for Kubernetes model objects. */
  private val k8sJson: JSON = new JSON()

  /** Request-dispatch executor, shut down with the server. */
  private val executor: ExecutorService = Executors.newFixedThreadPool(
    FakeKubernetesServer.NUM_DISPATCH_THREADS,
    new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        val thread: Thread = new Thread(runnable, "fake-k8s-server")
        thread.setDaemon(true)
        thread
      }
    }
  )

  /** JDK HTTP server bound to loopback on an ephemeral port. */
  private val server: HttpServer = {
    val httpServer: HttpServer =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    httpServer.createContext("/api/v1/namespaces/", new ListPodsHandler)
    httpServer.setExecutor(executor)
    httpServer
  }

  /** Configures the pods returned for `namespace` and `appName`. */
  def setPods(namespace: String, appName: String, pods: Option[List[V1Pod]]): Unit = sec.run {
    checkInvariants()
    setCell(PodScope(namespace, appName), PodsOrError.Pods(pods))
    nextResourceVersion += 100
  }

  /** Configures an HTTP error response for `namespace` and `appName`. */
  def setErrorResponse(namespace: String, appName: String, statusCode: Int): Unit = sec.run {
    checkInvariants()
    setCell(PodScope(namespace, appName), PodsOrError.Error(statusCode))
  }

  /** Starts the server synchronously. */
  def start(): Unit = server.start()

  /** Stops the server and its request-dispatch executor. */
  def stop(): Unit = {
    server.stop(0)
    executor.shutdownNow()
  }

  /** Returns the bound server port. */
  def port: Int = server.getAddress.getPort

  /** Returns the most recently observed Authorization header for the given request scope. */
  def getLastAuthorizationHeader(namespace: String, appName: String): Future[Option[String]] =
    sec.call {
      checkInvariants()
      authorizationByScope.get(PodScope(namespace, appName))
    }

  /** Handles list and watch requests without blocking the fake's SEC. */
  private class ListPodsHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      var responseHandleOpt: Option[ResponseHandle] = None
      try {
        val responseHandle: ResponseHandle = Await.result(
          sec.call(createResponseHandle(exchange)),
          FakeKubernetesServer.RESPONSE_TIMEOUT
        )
        responseHandleOpt = Some(responseHandle)
        val response: FakeK8sResponse =
          Await.result(responseHandle.response, FakeKubernetesServer.RESPONSE_TIMEOUT)
        writeResponse(exchange, response.statusCode, response.jsonBody)
      } catch {
        case NonFatal(exception) =>
          writeResponse(
            exchange,
            statusCode = 500,
            k8sJson.serialize(buildFailureStatus(500, Option(exception.getMessage).getOrElse("")))
          )
      } finally {
        responseHandleOpt.foreach(_.cancel())
        exchange.close()
      }
    }
  }

  /** Creates a response whose state access and watch registration happen on [[sec]]. */
  private def createResponseHandle(exchange: HttpExchange): ResponseHandle = {
    sec.assertCurrentContext()
    val namespaceOpt: Option[String] = extractNamespace(exchange.getRequestURI.getPath)
    val rawQueryOpt: Option[String] = Option(exchange.getRequestURI.getRawQuery)
    val labelSelectorOpt: Option[String] = extractQueryParam(rawQueryOpt, "labelSelector")

    validateRequest(namespaceOpt, labelSelectorOpt) match {
      case Left(errorResponse: FakeK8sResponse) => ResponseHandle.completed(errorResponse)
      case Right(scope: PodScope) =>
        Option(exchange.getRequestHeaders.getFirst("Authorization")) match {
          case Some(authorization: String) =>
            authorizationByScope = authorizationByScope.updated(scope, authorization)
          case None =>
            authorizationByScope = authorizationByScope - scope
        }
        val isWatchRequest: Boolean =
          extractQueryParam(rawQueryOpt, "watch").exists(_.equalsIgnoreCase("true"))
        if (isWatchRequest) {
          createWatchResponse(scope, extractQueryParam(rawQueryOpt, "resourceVersion"))
        } else {
          ResponseHandle.completed(createListResponse(scope))
        }
    }
  }

  /** Holds a matching watch request until its scope changes, then forces the client to re-list. */
  private def createWatchResponse(
      scope: PodScope,
      requestedResourceVersionOpt: Option[String]): ResponseHandle = {
    val resourceVersionAtRequest: String = nextResourceVersion.toString
    val cell: WatchValueCell[PodsOrError] =
      podsByScope.getOrElse(scope, setCell(scope, PodsOrError.Pods(Some(List.empty))))

    if (requestedResourceVersionOpt.forall(_ != resourceVersionAtRequest)) {
      return ResponseHandle.completed(createGoneResponse())
    }

    val requestedResourceVersion: String = requestedResourceVersionOpt.get
    val responsePromise: Promise[FakeK8sResponse] = Promise[FakeK8sResponse]()
    val cancellable: Cancellable = cell.watch(new ValueStreamCallback[PodsOrError](sec) {
      override protected def onSuccess(ignoredValue: PodsOrError): Unit = {
        cell.getLatestValueOpt.get match {
          case PodsOrError.Error(statusCode: Int) =>
            responsePromise.trySuccess(createErrorResponse(statusCode, "fake error"))
          case PodsOrError.Pods(_) if requestedResourceVersion != nextResourceVersion.toString =>
            responsePromise.trySuccess(createGoneResponse())
          case PodsOrError.Pods(_) =>
        }
      }
    })
    ResponseHandle(
      responsePromise.future,
      () => cancellable.cancel(Status.CANCELLED.withDescription("HTTP watch closed"))
    )
  }

  /** Creates the current unary list/error response for `scope`. */
  private def createListResponse(scope: PodScope): FakeK8sResponse = {
    podsByScope
      .get(scope)
      .flatMap(_.getLatestValueOpt)
      .getOrElse(PodsOrError.Pods(Some(List.empty))) match {
      case PodsOrError.Error(statusCode: Int) => createErrorResponse(statusCode, "fake error")
      case PodsOrError.Pods(pods: Option[List[V1Pod]]) =>
        val podList: V1PodList = new V1PodList()
        podList.setMetadata(new V1ListMeta().resourceVersion(nextResourceVersion.toString))
        pods.foreach(pods => podList.setItems(pods.asJava))
        FakeK8sResponse(200, k8sJson.serialize(podList))
    }
  }

  /** Stores `value` in the watch cell for `scope`, creating the cell when necessary. */
  private def setCell(scope: PodScope, value: PodsOrError): WatchValueCell[PodsOrError] = {
    val cell: WatchValueCell[PodsOrError] =
      podsByScope.getOrElseUpdate(scope, new WatchValueCell[PodsOrError]())
    cell.setValue(value)
    cell
  }

  /** Validates and resolves the namespace and app label from a request. */
  private def validateRequest(
      namespaceOpt: Option[String],
      labelSelectorOpt: Option[String]): Either[FakeK8sResponse, PodScope] = {
    (namespaceOpt, labelSelectorOpt) match {
      case (None, _) => Left(createErrorResponse(400, "namespace is required"))
      case (_, None) => Left(createErrorResponse(400, "labelSelector is required"))
      case (_, Some(selector: String)) if !selector.startsWith("app=") =>
        Left(createErrorResponse(400, "labelSelector must start with 'app='"))
      case (Some(namespace: String), Some(selector: String)) =>
        Right(PodScope(namespace, selector.stripPrefix("app=")))
    }
  }

  /** Extracts the namespace from a Kubernetes list-pods path. */
  private def extractNamespace(path: String): Option[String] = {
    val prefix: String = "/api/v1/namespaces/"
    val suffix: String = "/pods"
    if (path.startsWith(prefix) && path.endsWith(suffix) &&
      path.length >= prefix.length + suffix.length) {
      Some(path.substring(prefix.length, path.length - suffix.length)).filter(_.nonEmpty)
    } else {
      None
    }
  }

  /** Extracts a decoded query parameter from `rawQueryOpt`. */
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

  /** Creates a Kubernetes-style error response. */
  private def createErrorResponse(statusCode: Int, message: String): FakeK8sResponse = {
    FakeK8sResponse(statusCode, k8sJson.serialize(buildFailureStatus(statusCode, message)))
  }

  /** Creates the response that tells a watch client to re-list. */
  private def createGoneResponse(): FakeK8sResponse = FakeK8sResponse(410, "")

  /** Builds a Kubernetes failure status. */
  private def buildFailureStatus(statusCode: Int, message: String): V1Status = {
    val status: V1Status = new V1Status()
    status.setKind("Status")
    status.setApiVersion("v1")
    status.setStatus("Failure")
    status.setMessage(message)
    status.setCode(statusCode)
    status
  }

  /** Writes a JSON response to `exchange`. */
  private def writeResponse(exchange: HttpExchange, statusCode: Int, jsonBody: String): Unit = {
    val bodyBytes: Array[Byte] = jsonBody.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, bodyBytes.length.toLong)
    val output: OutputStream = exchange.getResponseBody
    try output.write(bodyBytes)
    finally output.close()
  }

  /** Verifies invariants for state guarded by [[sec]]. */
  private def checkInvariants(): Unit = {
    sec.assertCurrentContext()
    podsByScope.values.foreach(cell => assert(cell.getLatestValueOpt.isDefined))
  }
}

/** Factory and private value types for [[FakeKubernetesServer]]. */
private[assigner] object FakeKubernetesServer {

  /** Maximum time a dispatch thread waits for the SEC or a watch update. */
  private val RESPONSE_TIMEOUT: FiniteDuration = 15.seconds

  /** Maximum number of concurrent fake Kubernetes requests. */
  private val NUM_DISPATCH_THREADS: Int = 4

  /** Creates and starts a fake server bound to `sec`. */
  def createAndStart(sec: SequentialExecutionContext): FakeKubernetesServer = {
    val server: FakeKubernetesServer = new FakeKubernetesServer(sec)
    server.start()
    server
  }

  /** Scopes pod responses by namespace and app name. */
  private case class PodScope(namespace: String, appName: String)

  /** A list of pods or an HTTP error response. */
  private sealed trait PodsOrError
  private object PodsOrError {
    case class Pods(pods: Option[List[V1Pod]]) extends PodsOrError
    case class Error(statusCode: Int) extends PodsOrError
  }

  /** A rendered HTTP status and JSON body. */
  private case class FakeK8sResponse(statusCode: Int, jsonBody: String)

  /** A possibly deferred response and the cleanup action for its watch registration. */
  private case class ResponseHandle(response: Future[FakeK8sResponse], cancel: () => Unit)
  private object ResponseHandle {
    def completed(response: FakeK8sResponse): ResponseHandle =
      ResponseHandle(Future.successful(response), () => ())
  }
}
