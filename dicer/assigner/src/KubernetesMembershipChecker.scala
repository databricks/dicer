package com.databricks.dicer.assigner

import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try
import scala.util.control.NonFatal

import io.kubernetes.client.openapi.{ApiCallback, ApiClient, ApiException}
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.{
  V1ListMeta,
  V1ObjectMeta,
  V1Pod,
  V1PodCondition,
  V1PodList,
  V1PodStatus
}
import io.kubernetes.client.util.ClientBuilder
import io.prometheus.client.{Counter, Gauge, Histogram}
import okhttp3.OkHttpClient

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  TickerTime,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.dicer.assigner.KubernetesMembershipChecker.{
  CheckerState,
  KubernetesConnectionHealthMonitor
}
import com.databricks.dicer.external.ResourceAddress

/**
 * A [[ResourceWatcher]] that discovers resources by polling the Kubernetes API for pods and
 * tracks connection health with hysteresis to avoid flapping.
 *
 * Resource updates are delivered to [[watch]] subscribers. Each successful poll publishes a
 * [[VersionedResourceSet]] tagged with a monotonically increasing version stamped by this
 * checker (rather than the K8s-supplied `resourceVersion`).
 * Connection health is tracked by [[KubernetesConnectionHealth]].
 *
 * Metrics tracked:
 *  - Latency of the `listNamespacedPod` call.
 *  - Number of pods in the response.
 *  - Whether this assigner's UUID is present among the pod UIDs in the response.
 *
 * For some use cases, such as the [[ConsistentHashingPreferredAssignerDriver]], the checker runs
 * for the lifetime of the owning process. However, for other use cases, such as the
 * [[TargetMigrator]], the checker is created and torn down while the process keeps running, so
 * [[stopAsync]] is provided for graceful termination.
 *
 * INVARIANTS:
 *  - All mutable state is accessed only while running on [[sec]].
 *  - [[orderingToken]] strictly increases by 1 per publish (see its field doc).
 *
 * @param sec The [[SequentialExecutionContext]] that guards mutable state in this class.
 * @param coreV1Api The Kubernetes CoreV1 API client used to list pods.
 * @param assignerUuid UUID identifying this assigner pod.
 * @param namespace The Kubernetes namespace to poll for pods.
 * @param appName The Kubernetes app label to filter pods by.
 * @param pollingInterval The interval between successive polls.
 * @param rpcPort The RPC port for resource URIs on the discovered pods.
 * @param kubeContextLabelOpt The metric label used when recording metrics for this membership
 *                            checker that signifies the kube context of the service whose
 *                            resources are being tracked. If `None`, then the label is populated
 *                            with an empty string.
 *
 * @throws IllegalArgumentException if `namespace` is empty.
 * @throws IllegalArgumentException if `appName` is empty.
 * @throws IllegalArgumentException if `pollingInterval` is not positive.
 * @throws IllegalArgumentException if `rpcPort` is not positive.
 */
private[dicer] class KubernetesMembershipChecker(
    sec: SequentialExecutionContext,
    coreV1Api: CoreV1Api,
    assignerUuid: UUID,
    namespace: String,
    appName: String,
    pollingInterval: FiniteDuration,
    rpcPort: Int,
    kubeContextLabelOpt: Option[String])
    extends ResourceWatcher {

  require(namespace.nonEmpty, "namespace must not be empty")
  require(appName.nonEmpty, "appName must not be empty")
  require(pollingInterval > Duration.Zero, "pollingInterval must be positive")
  require(rpcPort > 0, "rpcPort must be positive")

  /** The kubeContext metric label value. */
  private val kubeContextLabel: String = kubeContextLabelOpt.getOrElse("")

  /** Logger for this checker instance. */
  private val logger: PrefixLogger =
    PrefixLogger.create(getClass, "KubernetesMembershipChecker")

  /** Pre-computed string form of the assigner's UUID, used on every poll to check self-presence. */
  private val assignerUuidStr: String = assignerUuid.toString

  /** Tracks connection health with hysteresis based on consecutive poll results. */
  private val connectionHealthMonitor: KubernetesConnectionHealthMonitor =
    new KubernetesConnectionHealthMonitor(
      KubernetesMembershipChecker.DEFAULT_FAILURE_THRESHOLD,
      KubernetesMembershipChecker.DEFAULT_RECOVERY_THRESHOLD
    )

  /** Eagerly created with an initial zero value. */
  private val connectionUnhealthyCounterChild: Counter.Child =
    KubernetesMembershipChecker.connectionUnhealthyCounter.labels(namespace, appName)

  /** Latest resource set, published to [[watch]] subscribers. */
  private val resourceCell: WatchValueCell[VersionedResourceSet] =
    new WatchValueCell[VersionedResourceSet]

  /** Current connection health, exposed to consumers via [[connectionHealthCell]]. */
  private val healthCell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]

  /** The structured proto logger for emitting membership check events, set in [[start]]. */
  @GuardedBy("sec")
  private var assignerProtoLogger: AssignerProtoLogger = _

  /**
   * The checker's lifecycle state. It begins in [[CheckerState.Init]] and advances in one direction
   * only, through [[CheckerState.Running]] to [[CheckerState.Stopped]]. It never moves backwards or
   * skips a state, and once it reaches [[CheckerState.Stopped]] it stays there.
   */
  @GuardedBy("sec")
  private var checkerState: CheckerState = CheckerState.Init

  /**
   * Process-scoped counter equal to the number of [[VersionedResourceSet]]s published so far;
   * also the version that the *next* publish will stamp. Strictly monotonically increasing;
   * starts at `0L`. Advances on every successful poll.
   *
   * The token is only meaningful within a single assigner process and MUST NOT be externalized
   * or compared across processes.
   */
  @GuardedBy("sec")
  private var orderingToken: Long = 0L

  /**
   * Starts the watcher, logging poll results to `assignerProtoLogger`.
   *
   * PRECONDITION: May only be called once on a newly constructed checker (i.e. one that has not
   * already been started or stopped).
   */
  override def start(assignerProtoLogger: AssignerProtoLogger): Unit = sec.run {
    checkerState match {
      case CheckerState.Running =>
        throw new IllegalStateException(
          "Cannot start a membership checker that has already been started."
        )
      case CheckerState.Stopped =>
        throw new IllegalStateException(
          "Cannot start a membership checker that has already been stopped. Please create a new " +
          "instance instead."
        )
      case CheckerState.Init =>
        checkerState = CheckerState.Running

        this.assignerProtoLogger = assignerProtoLogger
        // Connection health is left unpublished until the first poll: the cell starts empty
        // (UNKNOWN) so a pod that has not yet reached the Kubernetes API does not advertise a
        // health value. `recordPoll` publishes the first value once the first poll resolves the
        // monitor's verdict.

        logger.info(s"Starting membership checker with polling interval $pollingInterval")
        scheduleNextPoll()
    }
  }

  /** {@inheritDoc} */
  override def watch(callback: ValueStreamCallback[VersionedResourceSet]): Cancellable = {
    resourceCell.watch(callback)
  }

  /**
   * Exposes the connection-health cell so the preferred-assigner election driver and the
   * Assigner's readiness and liveness probes can read the current verdict. The cell is empty
   * (`None`) until the checker's first poll resolves the [[connectionHealthMonitor]] verdict — a
   * first success publishes `true`, and only a sustained run reaching
   * [[KubernetesMembershipChecker.DEFAULT_FAILURE_THRESHOLD]] publishes `false` — so a pod that has
   * never confirmed connectivity stays `None` (unknown).
   */
  def connectionHealthCell: WatchValueCell.Consumer[Boolean] = healthCell

  /**
   * Stops polling the Kubernetes API and releases the underlying Kubernetes API client's HTTP
   * resources. No new polls are scheduled after this call, though polls that are already in-flight
   * will still be executed. Idempotent: stopping an already-stopped checker is a no-op, so callers
   * on different threads (e.g. a shutdown hook and a test teardown) need not coordinate.
   *
   * NOTE: Once a checker has been stopped, it cannot be restarted - a new instance must be created
   * instead.
   *
   * PRECONDITION: The checker must have been started (i.e. not still in the initial state).
   */
  def stopAsync(): Unit = sec.run {
    checkerState match {
      case CheckerState.Init =>
        throw new IllegalStateException(
          "Cannot stop a membership checker that was never started."
        )
      case CheckerState.Stopped =>
        // Already stopped; nothing to do. This is expected when both the Assigner shutdown hook and
        // a test teardown stop the same checker.
        ()
      case CheckerState.Running =>
        logger.info("Stopping membership checker and releasing its Kubernetes client resources.")
        checkerState = CheckerState.Stopped
        shutdownK8sClientHttpResources()
    }
  }

  /**
   * Releases the Kubernetes API client's underlying HTTP resources.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  @SuppressWarnings(
    Array(
      "UnsafeThreadPools",
      "reason:only shutting down OkHttp's own dispatcher executor, never submitting tasks to it"
    )
  )
  private def shutdownK8sClientHttpResources(): Unit = {
    sec.assertCurrentContext()
    // The client owns an OkHttp connection pool and dispatcher executor. OkHttp releases
    // these automatically once they remain idle (but the docs don't specify what that idle period
    // is), so tearing them down here is not required for correctness. However, since we never
    // re-use a membership checker once it has been stopped, we know that these are now unused
    // resources and can release them eagerly rather than wait for OkHttp's idle cleanup.
    // See the "Shutdown isn't necessary" section:
    // https://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/#shutdown
    //
    // Additionally, per the ExecutorService.shutdown() contract, previously submitted tasks (i.e.
    // ongoing/scheduled polls) will still be executed before shutting down the executor:
    // https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html#shutdown--
    val okHttpClient: OkHttpClient = coreV1Api.getApiClient.getHttpClient
    okHttpClient.dispatcher().executorService().shutdown()
    okHttpClient.connectionPool().evictAll()
  }

  /**
   * Publishes the current Kubernetes-connection health value to both [[healthCell]] (for
   * cross-component watchers) and the per-pod
   * [[KubernetesMembershipChecker.connectionHealthGauge]] (for monitoring), and increments the
   * unhealthy-transition counter when appropriate. Called from the polling path on the first poll
   * (the cell is unpublished until then) and whenever the hysteresis monitor flips.
   */
  private def publishConnectionHealth(healthy: Boolean): Unit = {
    healthCell.setValue(healthy)
    KubernetesMembershipChecker.connectionHealthGauge
      .labels(namespace, appName)
      .set(if (healthy) 1.0 else 0.0)
    if (!healthy) {
      connectionUnhealthyCounterChild.inc()
    }
  }

  /**
   * Schedules the next poll after [[pollingInterval]]. The next poll is only scheduled after
   * the current poll's callback completes, preventing overlapping in-flight polls when the
   * K8s API latency exceeds the polling interval.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def scheduleNextPoll(): Unit = {
    sec.assertCurrentContext()
    if (checkerState != CheckerState.Stopped) {
      sec.schedule("k8s-membership-poll", pollingInterval, () => poll())
    }
  }

  /**
   * Records all metrics for a successful poll.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def updateMetrics(
      statusCode: Int,
      latency: FiniteDuration,
      podCount: Int,
      selfPresent: Boolean): Unit = {
    sec.assertCurrentContext()
    observeLatency(statusCode, latency)
    KubernetesMembershipChecker.responseSizeCounter
      .labels(namespace, appName, podCount.toString, kubeContextLabel)
      .inc()
    KubernetesMembershipChecker.selfPresentGauge
      .labels(namespace, appName, kubeContextLabel)
      .set(if (selfPresent) 1.0 else 0.0)
  }

  /**
   * Records a latency observation for this instance's namespace and appName.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def observeLatency(statusCode: Int, latency: FiniteDuration): Unit = {
    sec.assertCurrentContext()
    KubernetesMembershipChecker.latencyHistogram
      .labels(namespace, appName, statusCode.toString, kubeContextLabel)
      .observe(latency.toMillis.toDouble)
  }

  /**
   * Initiates an asynchronous poll of the Kubernetes API. The HTTP call executes off the SEC;
   * the result is processed back on the SEC via [[handlePollSuccess]] or [[handlePollFailure]].
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def poll(): Unit = {
    sec.assertCurrentContext()

    if (checkerState == CheckerState.Stopped) {
      // It's possible that a scheduled poll fires after we call stopAsync (to stop the checker),
      // which shuts down the K8s API client's HTTP resources. Issuing a poll at this point would be
      // rejected since the executor has been shut down. To avoid recording spurious failed polls,
      // we return early if a poll fires after the checker has been stopped.
      return
    }

    val startTime: TickerTime = sec.getClock.tickerTime()

    // Issue an async list request so the SEC is not blocked during the HTTP round-trip.
    // A high limit (100) acts as a safety guard; if pagination occurs the continue token is logged.
    // Wrap in try/catch so that if listNamespacedPodAsync itself throws (rather than invoking the
    // failure callback), we still schedule the next poll to keep the polling loop alive.
    try {
      coreV1Api.listNamespacedPodAsync(
        /* namespace */ namespace,
        /* pretty */ null,
        /* allowWatchBookmarks */ null,
        /* _continue */ null,
        /* fieldSelector */ null,
        /* labelSelector */ s"app=$appName",
        /* limit */ KubernetesMembershipChecker.listPodsLimit,
        /* resourceVersion */ null,
        /* resourceVersionMatch */ null,
        /* sendInitialEvents */ null,
        /* timeoutSeconds */ null,
        /* watch */ null,
        new ApiCallback[V1PodList] {
          override def onSuccess(
              result: V1PodList,
              statusCode: Int,
              responseHeaders: java.util.Map[String, java.util.List[String]]): Unit = {
            sec.run {
              handlePollSuccess(startTime, statusCode, result)
            }
          }

          override def onFailure(
              ex: ApiException,
              statusCode: Int,
              responseHeaders: java.util.Map[String, java.util.List[String]]): Unit = {
            sec.run { handlePollFailure(startTime, ex) }
          }

          override def onUploadProgress(
              bytesWritten: Long,
              contentLength: Long,
              done: Boolean): Unit = {}

          override def onDownloadProgress(
              bytesWritten: Long,
              contentLength: Long,
              done: Boolean): Unit = {}
        }
      )
    } catch {
      case NonFatal(ex) =>
        logger.warn(
          s"listNamespacedPodAsync threw unexpectedly: ${ex.getMessage}",
          every = KubernetesMembershipChecker.WARN_INTERVAL
        )
        recordPoll(success = false)
        scheduleNextPoll()
    }
  }

  /**
   * One pod's membership-relevant fields, extracted from a V1Pod in a single pass.
   *
   * @param name the pod name, or "(unknown)" when absent from the metadata.
   * @param rawUidOpt the raw k8s UID string (the membership key), when present.
   * @param uuidOpt the parsed UID, or None when absent or not a valid UUID.
   * @param uriOpt the pod's AssignerUri built from its IP + rpcPort, or None when it has no IP.
   * @param isReady whether the pod reports a Ready condition of "True".
   * @param isTerminating whether the pod has a deletionTimestamp set.
   */
  private case class ExtractedPod(
      name: String,
      rawUidOpt: Option[String],
      uuidOpt: Option[UUID],
      uriOpt: Option[AssignerUri],
      isReady: Boolean,
      isTerminating: Boolean)

  /**
   * Processes a successful poll result: extracts pod metadata, records metrics, updates
   * connection health and delivers resources to subscribers.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def handlePollSuccess(
      startTime: TickerTime,
      statusCode: Int,
      podList: V1PodList): Unit = {
    sec.assertCurrentContext()
    val latency: FiniteDuration = sec.getClock.tickerTime() - startTime

    // Handle null from getItems gracefully (can occur with empty or malformed API responses).
    val pods: Seq[V1Pod] = Option(podList.getItems)
      .map { items: java.util.List[V1Pod] =>
        items.asScala.toSeq
      }
      .getOrElse(Seq.empty)
    val podCount: Int = pods.size

    // Extract every membership-relevant field from each pod in a single pass, emitting the per-pod
    // exclusion warnings here so we never walk `pods` a second time. The member set and the
    // filtered resource set below both derive from these ExtractedPods, so a pod's name and URI
    // always come from the same source (no cross-map lookups, no missing-entry fallbacks).
    val extractedPods: Seq[ExtractedPod] = pods.map { pod: V1Pod =>
      val metadataOpt: Option[V1ObjectMeta] = Option(pod.getMetadata)
      val nameOpt: Option[String] = metadataOpt.flatMap { metadata: V1ObjectMeta =>
        Option(metadata.getName)
      }
      val rawUidOpt: Option[String] = metadataOpt.flatMap { metadata: V1ObjectMeta =>
        Option(metadata.getUid)
      }
      val uuidOpt: Option[UUID] = rawUidOpt.flatMap { rawUid: String =>
        Try(UUID.fromString(rawUid)).toOption
      }
      // Exclude terminating pods (deletionTimestamp set) so subscribers do not route RPCs to
      // pods that Kubernetes is shutting down.
      // This matches the ready condition implementation of K8s EndpointSlices:
      // https://kubernetes.io/docs/concepts/services-networking/endpoint-slices/#ready.
      val isTerminating: Boolean = metadataOpt.exists { metadata: V1ObjectMeta =>
        metadata.getDeletionTimestamp != null
      }
      // Exclude non-ready pods so subscribers only see members that can serve RPCs.
      // See https://kubernetes.io/docs/concepts/workloads/pods/pod-condition/#lifecycle-pod-conditions.
      val isReady: Boolean = Option(pod.getStatus)
        .flatMap { status: V1PodStatus =>
          Option(status.getConditions)
        }
        .exists { conditions: java.util.List[V1PodCondition] =>
          conditions.asScala.exists { condition: V1PodCondition =>
            condition.getType == "Ready" && condition.getStatus == "True"
          }
        }
      val uriOpt: Option[AssignerUri] = for {
        status: V1PodStatus <- Option(pod.getStatus)
        ip: String <- Option(status.getPodIP) if ip.nonEmpty
      } yield AssignerUri(host = ip, port = rpcPort)

      val name: String = nameOpt.getOrElse("(unknown)")
      val podDbgStr: String = s"$name (uid=${rawUidOpt.getOrElse("(unknown)")})"

      // Emit exclusion warnings once per pod. A UID-less pod is warned about only via the "no UID"
      // message (when a name is present); we deliberately do not also emit the "invalid or missing
      // UID" message for it, since that would be redundant. Pods with a raw UID that fails to parse
      // as a UUID still get the "invalid or missing UID" warning. The IP/ready/terminating warnings
      // fire independently whenever their condition is unmet, regardless of UID validity.
      if (rawUidOpt.isEmpty) {
        nameOpt.foreach { presentName: String =>
          logger.warn(
            s"Pod $presentName is present in the namespace but has no UID; " +
            s"excluded from membership.",
            every = KubernetesMembershipChecker.WARN_INTERVAL
          )
        }
      } else if (uuidOpt.isEmpty) {
        logger.warn(
          s"Pod $podDbgStr has invalid or missing UID: metadata=${pod.getMetadata}",
          every = KubernetesMembershipChecker.WARN_INTERVAL
        )
      }
      if (uriOpt.isEmpty) {
        logger.warn(
          s"Pod $podDbgStr has no IP address: status=${pod.getStatus}",
          every = KubernetesMembershipChecker.WARN_INTERVAL
        )
      }
      if (!isReady) {
        logger.warn(
          s"Pod $podDbgStr is not in Ready state: status=${pod.getStatus}",
          every = KubernetesMembershipChecker.WARN_INTERVAL
        )
      }
      if (isTerminating) {
        logger.warn(
          s"Pod $podDbgStr is terminating: " +
          s"deletionTimestamp=${pod.getMetadata.getDeletionTimestamp}",
          every = KubernetesMembershipChecker.WARN_INTERVAL
        )
      }

      ExtractedPod(
        name = name,
        rawUidOpt = rawUidOpt,
        uuidOpt = uuidOpt,
        uriOpt = uriOpt,
        isReady = isReady,
        isTerminating = isTerminating
      )
    }

    // Members are the pods that carry a raw UID string; membership is keyed by that raw UID (RPCs
    // route by UID). A pod with no UID cannot be a member and is dropped here (its warning already
    // fired above).
    val podUidsAndNames: Seq[MemberPod] = extractedPods.flatMap { ep: ExtractedPod =>
      ep.rawUidOpt.map { rawUid: String =>
        MemberPod(rawUid, ep.name)
      }
    }
    val podUids: Seq[String] = podUidsAndNames.map { member: MemberPod =>
      member.uuid
    }

    // Check whether the assigner's UUID is present among the pod UIDs.
    val selfPresent: Boolean = podUids.contains(assignerUuidStr)

    // Extract metadata fields for logging only (not tracked as metrics). The K8s-supplied
    // resourceVersion is not used for ordering decisions; see `orderingToken`.
    val metadataOpt: Option[V1ListMeta] = Option(podList.getMetadata)
    val kubernetesResourceVersion: String = metadataOpt
      .map { metadata: V1ListMeta =>
        metadata.getResourceVersion
      }
      .getOrElse("")

    // Log a warning if the response contains a continue token, indicating pagination occurred.
    val continueTokenOpt: Option[String] = metadataOpt
      .flatMap { metadata: V1ListMeta =>
        Option(metadata.getContinue)
      }
    for (token: String <- continueTokenOpt) {
      logger.warn(
        s"listNamespacedPod response contained a continue token ($token), " +
        s"indicating the result set exceeded the limit of " +
        s"${KubernetesMembershipChecker.listPodsLimit}. " +
        s"Not all pods were retrieved in this poll.",
        every = KubernetesMembershipChecker.WARN_INTERVAL
      )
    }

    // Record success metrics for observability.
    updateMetrics(statusCode, latency, podCount, selfPresent)

    // Derive the filtered resource set (valid parseable UUID + has IP + ready + not terminating)
    // and the matching filtered members from the same ExtractedPods, so each published UUID's name
    // and URI always come from the same pod. `filteredMembers` keys the MemberPod by the canonical
    // parsed-UUID string (uuid.toString), matching the resource-set keys.
    val filteredEntries: Seq[(UUID, AssignerUri, MemberPod)] =
      extractedPods.flatMap { ep: ExtractedPod =>
        for {
          uuid: UUID <- ep.uuidOpt
          uri: AssignerUri <- ep.uriOpt
          if ep.isReady
          if !ep.isTerminating
        } yield (uuid, uri, MemberPod(uuid.toString, ep.name))
      }
    val podUidToUri: Map[UUID, AssignerUri] = filteredEntries.map { entry =>
      val (uuid, uri, _): (UUID, AssignerUri, MemberPod) = entry
      uuid -> uri
    }.toMap
    val filteredMembers: Seq[MemberPod] = filteredEntries.map { entry =>
      val (_, _, member): (UUID, AssignerUri, MemberPod) = entry
      member
    }

    // The emitter pod name is known only when the assigner is present in its own poll result;
    // resolve it from the extracted member set rather than re-reading pod metadata.
    val emitterPodNameOpt: Option[String] = podUidsAndNames
      .find { member: MemberPod =>
        member.uuid == assignerUuidStr
      }
      .map { member: MemberPod =>
        member.podName
      }

    // Emit a structured proto log for the successful membership check. Each member carries its UUID
    // paired with its pod name so the log is traceable to pods without resolving UUIDs. The emitter
    // pod name is known only when the assigner is present in its own poll result.
    // Note: the proto log carries the K8s resourceVersion, distinct from `orderingToken`
    // published to the resource cell.
    assignerProtoLogger.logMembershipCheck(
      latencyMs = latency.toMillis,
      httpStatusCode = statusCode,
      namespace = namespace,
      appName = appName,
      emitterPodNameOpt = emitterPodNameOpt,
      members = podUidsAndNames,
      filteredMembers = filteredMembers,
      kubernetesResourceVersion = kubernetesResourceVersion,
      kubeContextOpt = kubeContextLabelOpt
    )

    // Publish the resource set for this poll.
    val publishedVersion: Long = publishResourceSet(podUidToUri)
    recordPoll(success = true)

    // Rate-limit log output to once every 10 polling intervals.
    val podSummary: String =
      podUidsAndNames
        .map { member: MemberPod =>
          s"${member.podName}=${member.uuid}"
        }
        .mkString(", ")
    logger.info(
      s"Poll completed: podCount=$podCount, selfPresent=$selfPresent, " +
      s"kubernetesResourceVersion=$kubernetesResourceVersion, " +
      s"orderingToken=$publishedVersion, " +
      s"latencyMillis=${latency.toMillis}, " +
      s"pods=[$podSummary]",
      every = pollingInterval * 10
    )

    // Schedule the next poll after the event is processed to prevent overlapping polls.
    scheduleNextPoll()
  }

  /**
   * Handles a failed poll by recording the error latency metric, resetting the self-present
   * gauge to 0.0, and updating connection health.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def handlePollFailure(startTime: TickerTime, ex: ApiException): Unit = {
    sec.assertCurrentContext()
    val latency: FiniteDuration = sec.getClock.tickerTime() - startTime
    val statusCode: Int = ex.getCode
    observeLatency(statusCode, latency)
    // Reset self-present gauge manually rather than going through updateMetrics, since the
    // failure path has different semantics (unknown state rather than observed state).
    KubernetesMembershipChecker.selfPresentGauge
      .labels(namespace, appName, kubeContextLabel)
      .set(0.0)
    // Emit a structured proto log for the failed membership check.
    assignerProtoLogger.logMembershipCheck(
      latencyMs = latency.toMillis,
      httpStatusCode = statusCode,
      namespace = namespace,
      appName = appName,
      emitterPodNameOpt = None,
      members = Seq.empty,
      filteredMembers = Seq.empty,
      kubernetesResourceVersion = "",
      kubeContextOpt = kubeContextLabelOpt
    )

    // Log the failure but continue polling on the next interval.
    val message: String = Option(ex.getMessage).getOrElse("(no message)")
    logger.warn(
      s"Failed to poll Kubernetes API: statusCode=$statusCode, $message",
      every = KubernetesMembershipChecker.WARN_INTERVAL
    )

    recordPoll(success = false)

    // Schedule the next poll after the event is processed to prevent overlapping polls.
    scheduleNextPoll()
  }

  /**
   * Publishes a [[VersionedResourceSet]] carrying the next [[orderingToken]] and the given pod
   * mapping to [[resourceCell]]. Each call publishes; the version always advances by 1.
   *
   * @return the version stamped on the published [[VersionedResourceSet]].
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def publishResourceSet(podUidToUri: Map[UUID, AssignerUri]): Long = {
    sec.assertCurrentContext()
    val resources: Map[UUID, ResourceAddress] = podUidToUri.map { entry =>
      val (uid, assignerUri): (UUID, AssignerUri) = entry
      (uid, ResourceAddress(assignerUri.toUri))
    }
    val versionToStamp: Long = orderingToken
    orderingToken += 1L
    resourceCell.setValue(
      VersionedResourceSet(ResourceVersion(versionToStamp.toString), resources)
    )
    versionToStamp
  }

  /**
   * Records a poll result in [[connectionHealthMonitor]] and notifies subscribers if the health
   * status changed.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private def recordPoll(success: Boolean): Unit = {
    sec.assertCurrentContext()
    if (success) {
      connectionHealthMonitor.onPollSuccess()
    } else {
      connectionHealthMonitor.onPollFailure()
    }
    // Publish once the monitor resolves its verdict (the first success, or the failure that
    // reaches the threshold) and on every subsequent flip. The cell stays unpublished (UNKNOWN)
    // while the monitor's verdict is still `None`, so a pod that has not confirmed connectivity
    // does not advertise a health value.
    for (isHealthy: Boolean <- connectionHealthMonitor.health) {
      if (!healthCell.getLatestValueOpt.contains(isHealthy)) {
        publishConnectionHealth(healthy = isHealthy)
      }
    }
  }

}

object KubernetesMembershipChecker {

  /** The lifecycle state of a [[KubernetesMembershipChecker]]. */
  private sealed trait CheckerState

  private object CheckerState {

    /** The checker has been constructed but not yet started. It is not polling the K8s API. */
    case object Init extends CheckerState

    /** The checker has been started and is polling the K8s API. */
    case object Running extends CheckerState

    /**
     * The checker has been stopped. This is a terminal state and the checker cannot be restarted.
     */
    case object Stopped extends CheckerState
  }

  /** Default polling interval for the membership checker in production. */
  val DEFAULT_POLLING_INTERVAL: FiniteDuration = FiniteDuration(1, TimeUnit.SECONDS)

  /**
   * Default number of consecutive failures to transition the connection state from healthy to
   * unhealthy.
   */
  val DEFAULT_FAILURE_THRESHOLD: Int = 3

  /**
   * Default number of consecutive successes to transition the connection state from unhealthy
   * to healthy.
   */
  val DEFAULT_RECOVERY_THRESHOLD: Int = 3

  /** Rate-limit interval for warning-level log messages. */
  private val WARN_INTERVAL: FiniteDuration = FiniteDuration(30, TimeUnit.SECONDS)

  /**
   * Tracks the health of the Kubernetes API connection using simple consecutive-event
   * counters with hysteresis to avoid flapping.
   *
   * Starts in the healthy state. Transitions to unhealthy after [[failureThreshold]]
   * consecutive poll failures, and back to healthy after [[recoveryThreshold]] consecutive poll
   * successes from the unhealthy state. A single success/failure while healthy/unhealthy resets
   * the counter.
   *
   * Not thread-safe. Access must be serialized by the caller's
   * [[com.databricks.caching.util.SequentialExecutionContext]].
   *
   * @param failureThreshold Number of consecutive failures to transition from healthy to unhealthy.
   * @param recoveryThreshold Number of consecutive successes to transition from unhealthy to
   *                          healthy.
   *
   * @throws IllegalArgumentException if `failureThreshold` is not positive.
   * @throws IllegalArgumentException if `recoveryThreshold` is not positive.
   */
  private class KubernetesConnectionHealthMonitor(failureThreshold: Int, recoveryThreshold: Int) {

    require(failureThreshold > 0, "failureThreshold must be positive")
    require(recoveryThreshold > 0, "recoveryThreshold must be positive")

    /** Logger for state transitions. */
    private val logger: PrefixLogger =
      PrefixLogger.create(getClass, "KubernetesConnectionHealthMonitor")

    /**
     * The current health verdict, or `None` until the first poll resolves it. `None` means the
     * connection health is unknown (no poll has completed yet). A first success resolves it to
     * `Some(true)` immediately; failures from `None` accumulate toward [[failureThreshold]] just
     * like from a healthy state and only resolve to `Some(false)` once the threshold is reached.
     */
    private var healthy: Option[Boolean] = None

    /**
     * Counter tracking consecutive events toward the next health transition. While healthy or
     * unknown, counts consecutive failures; while unhealthy, counts consecutive successes.
     */
    private var transitionCount: Int = 0

    /** The current health verdict, or `None` if no poll has resolved it yet. */
    def health: Option[Boolean] = healthy

    /**
     * Records a successful poll.
     *
     * When unknown: resolves the verdict to healthy (a first success is confidently healthy).
     * When healthy: resets the failure counter.
     * When unhealthy: increments the consecutive success counter. If the counter reaches
     * [[recoveryThreshold]], transitions to healthy.
     */
    def onPollSuccess(): Unit = healthy match {
      case None =>
        // First poll succeeded: resolve directly to healthy.
        healthy = Some(true)
        transitionCount = 0
      case Some(true) =>
        // Any success while healthy resets the failure counter.
        transitionCount = 0
      case Some(false) =>
        transitionCount += 1
        if (transitionCount >= recoveryThreshold) {
          logger.debug(s"Connection recovered after $transitionCount consecutive successes")
          healthy = Some(true)
          transitionCount = 0
        }
    }

    /**
     * Records a failed poll.
     *
     * When unknown or healthy: increments the consecutive failure counter. If the counter reaches
     * [[failureThreshold]], transitions to unhealthy; otherwise the verdict is left unchanged (a
     * short failure run does not resolve an unknown connection or flip a healthy one).
     * When unhealthy: resets the consecutive success counter.
     */
    def onPollFailure(): Unit = healthy match {
      case Some(false) =>
        // Any failure while unhealthy resets the recovery counter.
        transitionCount = 0
      case None | Some(true) =>
        transitionCount += 1
        if (transitionCount >= failureThreshold) {
          logger.debug(s"Connection unhealthy after $transitionCount consecutive failures")
          healthy = Some(false)
          transitionCount = 0
        }
    }
  }

  /**
   * Factory for creating [[KubernetesMembershipChecker]] instances. Allows tests to inject
   * a checker backed by a fake K8s API client, following the
   * [[KubernetesTargetWatcher.Factory]] pattern.
   */
  trait Factory {

    /**
     * Creates a [[KubernetesMembershipChecker]] for the given assigner. The checker is a required
     * dependency, so a factory that cannot build one throws rather than returning a sentinel.
     *
     * @param assignerUuid UUID identifying this assigner pod.
     */
    def create(assignerUuid: UUID): KubernetesMembershipChecker
  }

  /**
   * Factory that creates a [[KubernetesMembershipChecker]] using the real Kubernetes
   * in-cluster API client. Exceptions from [[KubernetesMembershipChecker.create()]] (e.g. empty
   * namespace/app name, or unavailable in-cluster config) propagate to the caller and fail startup.
   * Each call creates a dedicated [[SequentialExecutionContext]] for the checker.
   *
   * @param namespace The Kubernetes namespace to poll for pods.
   * @param appName The Kubernetes app label to filter pods by.
   * @param pollingInterval The interval between successive polls.
   * @param rpcPort The RPC port used to construct resource URIs for discovered pods.
   */
  class DefaultFactory private (
      namespace: String,
      appName: String,
      pollingInterval: FiniteDuration,
      rpcPort: Int)
      extends Factory {

    override def create(assignerUuid: UUID): KubernetesMembershipChecker = {
      val checkerSec: SequentialExecutionContext =
        SequentialExecutionContext.createWithDedicatedPool(
          name = "membership-checker",
          alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
        )
      KubernetesMembershipChecker.create(
        checkerSec,
        assignerUuid,
        namespace,
        appName,
        pollingInterval,
        rpcPort,
        kubeContextLabelOpt = None
      )
    }
  }

  object DefaultFactory {

    /**
     * Creates a new [[DefaultFactory]]. Checkers created by this factory target the local
     * cluster (the same cluster that the current pod is running in).
     *
     * @param namespace The Kubernetes namespace to poll for pods.
     * @param appName The Kubernetes app label to filter pods by.
     * @param pollingInterval The interval between successive polls.
     * @param rpcPort The RPC port used to construct resource URIs for discovered pods.
     */
    def create(
        namespace: String,
        appName: String,
        pollingInterval: FiniteDuration,
        rpcPort: Int): DefaultFactory = {
      new DefaultFactory(namespace, appName, pollingInterval, rpcPort)
    }
  }

  // Prometheus metrics are registered globally per Prometheus Java client convention.
  // Tests use unique label values (namespace, appName) for isolation.

  /** Connect timeout for the K8s HTTP client. */
  private[assigner] val CONNECT_TIMEOUT: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)

  /**
   * Read timeout for the K8s pod List/Get HTTP calls. Deliberately short so a stalled call fails
   * fast and the membership checker retries promptly, rather than a hung request blocking
   * convergence on the healthy pod set. Raising it trades faster failure detection for tolerance of
   * transient K8s API slowness; before changing it, confirm the value still bounds detection
   * latency within what preferred-assigner election can tolerate.
   */
  private[assigner] val READ_TIMEOUT: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)

  /**
   * Safety limit for listNamespacedPod pagination. If the Assigner service has more pods than
   * this, the response will be paginated and a warning will be logged.
   */
  private val listPodsLimit: java.lang.Integer = 100

  /** Histogram tracking latency of the `listNamespacedPod` API call in milliseconds. */
  private val latencyHistogram: Histogram = Histogram
    .build()
    .name("dicer_assigner_k8s_list_pods_latency_millis")
    .help("Latency of the Kubernetes listNamespacedPod API call.")
    .labelNames("namespace", "appName", "statusCode", "kubeContext")
    .buckets(5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000)
    .register()

  /**
   * Counter tracking the total number of `listNamespacedPod` responses received, labeled by the
   * pod count in each response. Each successful poll increments the counter for the corresponding
   * pod count label, allowing us to track the distribution of response sizes over time.
   */
  private val responseSizeCounter: Counter = Counter
    .build()
    .name("dicer_assigner_k8s_list_pods_count_total")
    .help("Total number of Kubernetes listNamespacedPod responses received.")
    .labelNames("namespace", "appName", "podCount", "kubeContext")
    .register()

  /**
   * Gauge tracking whether the assigner's own pod UUID is present in the most recent
   * `listNamespacedPod` response (1.0 if present, 0.0 if absent).
   */
  private val selfPresentGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_k8s_list_pods_self_present")
    .help(
      "Whether the assigner's own pod UUID is present in the Kubernetes " +
      "listNamespacedPod response (1 = present, 0 = absent)."
    )
    .labelNames("namespace", "appName", "kubeContext")
    .register()

  /**
   * Gauge tracking whether the Kubernetes API connection is currently considered healthy
   * by the [[KubernetesConnectionHealthMonitor]] hysteresis logic (1.0 if healthy, 0.0 if
   * unhealthy). Mirrors the value published on [[healthCell]] and observed by the CH
   * preferred-assigner driver for suppression.
   */
  private val connectionHealthGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_k8s_connection_healthy_gauge")
    .help(
      "Whether the Kubernetes API connection is currently considered healthy by the " +
      "membership checker's hysteresis monitor (1 = healthy, 0 = unhealthy)."
    )
    .labelNames("namespace", "appName")
    .register()

  /**
   * Counter tracking the total number of times the Kubernetes API connection has transitioned
   * to the unhealthy state as determined by the [[KubernetesConnectionHealthMonitor]] hysteresis
   * logic. Incremented each time [[publishConnectionHealth]] is called with `healthy = false`.
   */
  private val connectionUnhealthyCounter: Counter = Counter
    .build()
    .name("dicer_assigner_k8s_connection_unhealthy_total")
    .help(
      "Total number of times the Kubernetes API connection transitioned to unhealthy " +
      "as determined by the membership checker's hysteresis monitor."
    )
    .labelNames("namespace", "appName")
    .register()

  /**
   * Creates a new [[KubernetesMembershipChecker]], initializing the Kubernetes API client
   * using in-cluster configuration. Adapts client creation from
   * [[KubernetesTargetWatcher.newFactory()]].
   *
   * @param sec The [[SequentialExecutionContext]] that guards mutable state.
   * @param assignerUuid UUID identifying this assigner pod.
   * @param namespace The Kubernetes namespace to poll for pods.
   * @param appName The Kubernetes app label to filter pods by.
   * @param pollingInterval The interval between successive polls.
   * @param rpcPort The RPC port used to construct resource URIs for discovered pods.
   * @param kubeContextLabelOpt The metric label used when recording metrics for this membership
   *                            checker that signifies the kube context of the service whose
   *                            resources are being tracked. If `None`, then the label is populated
   *                            with an empty string.
   * @return A new [[KubernetesMembershipChecker]] instance.
   */
  @throws[java.io.IOException]("if K8s in-cluster config is unavailable")
  @throws[IllegalArgumentException]("if namespace is empty")
  @throws[IllegalArgumentException]("if appName is empty")
  @throws[IllegalArgumentException]("if pollingInterval is not positive")
  @throws[IllegalArgumentException]("if rpcPort is not positive")
  def create(
      sec: SequentialExecutionContext,
      assignerUuid: UUID,
      namespace: String,
      appName: String,
      pollingInterval: FiniteDuration,
      rpcPort: Int,
      kubeContextLabelOpt: Option[String]
  ): KubernetesMembershipChecker = {
    // Create the Kubernetes API client using in-cluster configuration.
    val client: ApiClient = ClientBuilder.cluster().build()
    val okHttpClient: OkHttpClient = client.getHttpClient.newBuilder
      .connectTimeout(CONNECT_TIMEOUT.toMillis, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT.toMillis, TimeUnit.MILLISECONDS)
      .build
    client.setHttpClient(okHttpClient)
    val coreV1Api: CoreV1Api = new CoreV1Api(client)

    new KubernetesMembershipChecker(
      sec,
      coreV1Api,
      assignerUuid,
      namespace,
      appName,
      pollingInterval,
      rpcPort,
      kubeContextLabelOpt
    )
  }

}
