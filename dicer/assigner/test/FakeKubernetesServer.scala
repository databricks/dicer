package com.databricks.dicer.assigner

import scala.collection.JavaConverters._
import scala.concurrent.Future

import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.models.{V1ListMeta, V1Pod, V1PodList, V1Status}

import javax.annotation.concurrent.GuardedBy

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.assigner.FakeKubernetesServer.{
  FakeK8sRequest,
  FakeK8sResponse,
  PodScope
}

/**
 * An HTTP server that fakes the Kubernetes `listNamespacedPod` endpoint for integration testing.
 *
 * This class owns all fake state and response logic. The transport (route matching, request
 * parameter extraction, and HTTP wiring) is factored into [[FakeKubernetesHttpServer]], and this
 * class produces transport-neutral [[FakeK8sResponse]]s.
 *
 * Concurrency: the transport layer invokes [[handleListPods]] from its own (non-SEC) thread; that
 * method hops onto [[sec]] to touch all mutable state. A transport that blocks its server thread on
 * the returned future stays deadlock-free ONLY because callers drive this fake with an asynchronous
 * poll (off the SEC) — see [[FakeKubernetesHttpServer]].
 *
 * Responses are scoped by namespace and app name (extracted from the request's path parameter
 * and `labelSelector` query parameter respectively). Callers MUST call setter methods
 * ([[setPods]], [[setErrorResponse]]) BEFORE advancing time with `advanceBySync`.
 * Correctness relies on [[FakeSequentialExecutionContext]] processing `sec.run` tasks in
 * FIFO order before time-advanced tasks, so setters enqueued before `advanceBySync` are
 * guaranteed to take effect before the next poll fires.
 *
 * @param sec The [[SequentialExecutionContext]] that serializes all state access.
 */
private[assigner] final class FakeKubernetesServer private (sec: SequentialExecutionContext) {

  /**
   * Per-scope pod state. Scoping by namespace and appName allows multiple checkers to share a
   * single fake server.
   */
  @GuardedBy("sec")
  private var podsByScope: Map[PodScope, Option[List[V1Pod]]] = Map.empty

  /**
   * Per-scope error overrides. When set for a scope, the server responds with
   * the given HTTP status code instead of the pod list.
   */
  @GuardedBy("sec")
  private var errorByScope: Map[PodScope, Int] = Map.empty

  /**
   * Auto-incrementing resourceVersion. Bumped on each [[setPods]] call so the state machine
   * sees monotonically increasing versions without callers needing to track them.
   */
  @GuardedBy("sec")
  private var nextResourceVersion: Int = 1

  /**
   * Per-scope record of the most recently observed `Authorization` header, or `None` if the most
   * recent request omitted the header.
   */
  @GuardedBy("sec")
  private var authorizationByScope: Map[PodScope, Option[String]] = Map.empty

  /** Shared serializer for K8s model objects. Thread-safe per the K8s Java client documentation. */
  private val k8sJson: JSON = new JSON()

  /** The underlying HTTP server. Created eagerly but not started until [[start]] is called. */
  private val httpServer: FakeKubernetesHttpServer =
    FakeKubernetesHttpServer.create(handleListPods)

  /**
   * Configures the server to return the given pods on subsequent requests for the specified
   * namespace and appName. Clears any error override for that scope.
   *
   * @param namespace The Kubernetes namespace to scope the response to.
   * @param appName The app label to scope the response to.
   * @param pods [[Some]] with the pod list to include in the response, or [[None]] to simulate
   *             a K8s response where `V1PodList.getItems` returns null.
   */
  def setPods(namespace: String, appName: String, pods: Option[List[V1Pod]]): Unit = sec.run {
    val key: PodScope = PodScope(namespace, appName)
    podsByScope = podsByScope + (key -> pods)
    nextResourceVersion += 100
    errorByScope = errorByScope - key
  }

  /**
   * Configures the server to respond with the given HTTP status code and a K8s-style error body
   * for the specified namespace and appName.
   *
   * @param namespace The Kubernetes namespace to scope the error to.
   * @param appName The app label to scope the error to.
   * @param statusCode The HTTP status code to return (e.g. 500, 503).
   */
  def setErrorResponse(namespace: String, appName: String, statusCode: Int): Unit = sec.run {
    errorByScope = errorByScope + (PodScope(namespace, appName) -> statusCode)
  }

  /**
   * Starts the server synchronously. The server binds to the loopback address on an ephemeral port.
   * After this method returns, [[port]] is available immediately.
   */
  def start(): Unit = {
    httpServer.start()
  }

  /** Stops the server. */
  def stop(): Unit = httpServer.stop()

  /**
   * Returns the port number the server is listening on. Only valid after [[start]] has returned.
   * Safe to read outside the SEC because the port is immutable once the server has started.
   */
  def port: Int = httpServer.port

  /**
   * Returns the most recent `Authorization` header observed for the request scope identified by
   * the given `namespace` and `appName`, or [[None]] if no request has arrived for that scope or
   * the most recent request omitted the header.
   */
  def getLastAuthorizationHeader(namespace: String, appName: String): Future[Option[String]] =
    sec.call { authorizationByScope.getOrElse(PodScope(namespace, appName), None) }

  /**
   * Handles a list-pods request, recording the Authorization header by resolved scope and producing
   * a transport-neutral response.
   */
  private def handleListPods(request: FakeK8sRequest): Future[FakeK8sResponse] = sec.call {
    // Invoked by the transport from an arbitrary thread (NOT the SEC), so hop onto sec via sec.call
    // rather than asserting the current context.
    // Validate the request, returning an error response or the extracted scope.
    validateRequest(request.namespaceOpt, request.labelSelectorOpt) match {
      case Left(errorResponse: FakeK8sResponse) => errorResponse
      case Right(PodScope(namespace: String, appName: String)) =>
        authorizationByScope =
          authorizationByScope.updated(PodScope(namespace, appName), request.authorizationOpt)
        produceResponse(namespace, appName)
    }
  }

  /**
   * Validates that the request contains the required namespace and labelSelector parameters.
   *
   * @return Left with an error [[FakeK8sResponse]] if validation fails, or Right with a
   *         [[PodScope]] if validation succeeds.
   */
  private def validateRequest(
      namespaceOpt: Option[String],
      labelSelectorOpt: Option[String]): Either[FakeK8sResponse, PodScope] = {
    (namespaceOpt, labelSelectorOpt) match {
      case (None, _) =>
        Left(badRequest("namespace is required"))
      case (_, None) =>
        Left(badRequest("labelSelector is required"))
      case (_, Some(selector: String)) if !selector.startsWith("app=") =>
        Left(badRequest("labelSelector must start with 'app='"))
      case (Some(namespace: String), Some(selector: String)) =>
        Right(PodScope(namespace, selector.stripPrefix("app=")))
    }
  }

  /** Builds a 400 BAD_REQUEST response with a K8s-style error body. */
  private def badRequest(message: String): FakeK8sResponse = {
    FakeK8sResponse(statusCode = 400, jsonBody = buildFailureStatusJson(statusCode = 400, message))
  }

  /**
   * Produces a [[FakeK8sResponse]] based on the current state for the given scope.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def produceResponse(namespace: String, appName: String): FakeK8sResponse = {
    sec.assertCurrentContext()
    val key: PodScope = PodScope(namespace, appName)
    errorByScope.get(key) match {
      case Some(statusCode: Int) =>
        FakeK8sResponse(statusCode, buildFailureStatusJson(statusCode, message = "fake error"))
      case None =>
        val pods: Option[List[V1Pod]] = podsByScope.getOrElse(key, Some(List.empty))
        val podList: V1PodList = new V1PodList()
        // TODO(<internal bug>): resourceVersion is auto-incremented per setPods call. Extend to
        // support per-scope versions if tests need independent version sequences.
        podList.setMetadata(new V1ListMeta().resourceVersion(nextResourceVersion.toString))
        // None means null items (simulate K8s returning a V1PodList with getItems == null).
        // Some(list) sets the items normally.
        pods match {
          case Some(podItems: List[V1Pod]) => podList.setItems(podItems.asJava)
          case None => // Leave items as null.
        }
        FakeK8sResponse(statusCode = 200, jsonBody = k8sJson.serialize(podList))
    }
  }

  /** Builds a K8s-style Status JSON response body. */
  private def buildFailureStatusJson(statusCode: Int, message: String): String = {
    val status: V1Status = new V1Status()
    status.setKind("Status")
    status.setApiVersion("v1")
    status.setStatus("Failure")
    status.setMessage(message)
    status.setCode(statusCode)
    k8sJson.serialize(status)
  }
}

/** Factory and types for [[FakeKubernetesServer]]. */
private[assigner] object FakeKubernetesServer {

  /** Scopes pod responses by namespace and app name. */
  private case class PodScope(namespace: String, appName: String)

  /**
   * A transport-neutral fake request: the extracted `namespace` path parameter, `labelSelector`
   * query parameter, and `Authorization` header (each `None` when absent).
   */
  private[assigner] case class FakeK8sRequest(
      namespaceOpt: Option[String],
      labelSelectorOpt: Option[String],
      authorizationOpt: Option[String])

  /**
   * A transport-neutral fake response: an HTTP status code and a K8s-style JSON body. Produced by
   * [[FakeKubernetesServer]] and rendered onto the wire by [[FakeKubernetesHttpServer]].
   */
  private[assigner] case class FakeK8sResponse(statusCode: Int, jsonBody: String)

  /** Creates and starts a new [[FakeKubernetesServer]] bound to the given SEC. */
  def createAndStart(sec: SequentialExecutionContext): FakeKubernetesServer = {
    val server: FakeKubernetesServer = new FakeKubernetesServer(sec)
    server.start()
    server
  }
}
