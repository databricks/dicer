package com.databricks.dicer.client

import java.time.Instant
import java.util.concurrent.TimeUnit
import io.prometheus.client.{Counter, Gauge, Histogram}
import com.databricks.dicer.common.{
  AssignerServiceInfo,
  AssignmentMetricsSource,
  ClientType,
  Generation
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.common.TargetHelper.TargetOps
import io.grpc.Status.Code
import scala.concurrent.duration._

/** Contains Prometheus metrics for the Dicer client library. */
private[dicer] object ClientMetrics {

  /**
   * The `assignerName` label value recorded when assigner service info is missing from the
   * assignment. This could be missing when the Assigner was unable to determine its service
   * info or is running an outdated binary.
   */
  private[client] val UNKNOWN_ASSIGNER_NAME = ""

  /**
   * The `assignerInstanceId` label value recorded when assigner service info is missing from the
   * assignment. Missing for the same reasons as [[UNKNOWN_ASSIGNER_NAME]].
   */
  private[client] val UNKNOWN_ASSIGNER_INSTANCE_ID = ""

  private val latestGenerationNumber: Gauge = Gauge
    .build()
    .name("dicer_assignment_latest_generation_number")
    .help("The latest generation number for a target")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "source",
      "assignerName",
      "assignerInstanceId"
    )
    .register()

  private val latestStoreIncarnation: Gauge = Gauge
    .build()
    .name("dicer_assignment_latest_store_incarnation")
    .help("The latest store incarnation for a target")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "source",
      "assignerName",
      "assignerInstanceId"
    )
    .register()

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numberNewGenerations: Counter = Counter
    .build()
    .name("dicer_assignment_number_new_generations_total")
    .help("The number of new generations for a target")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "source",
      "assignerName",
      "assignerInstanceId"
    )
    .register()

  private val watchRequestOutcomes: Counter = Counter
    .build()
    .name("dicer_client_watch_request_outcomes_total")
    .help(
      "Count of watch request outcomes for a Dicer client, labeled by the assignment state the " +
      "client attached to the request (requestState), how that request resolved " +
      "(responseOutcome), and whether the response carried an assignment " +
      "(responseHasAssignment, noResponse when no response arrived). Every request resolves " +
      "into exactly one outcome. dicer_client_watch_requests_total covers the same requests at " +
      "the more general gRPC level, while this metric provides the more detailed, internal " +
      "assignment-sync view."
    )
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "clientType",
      "requestState",
      "responseOutcome",
      "responseHasAssignment"
    )
    .register()

  private val lateWatchResponses: Counter = Counter
    .build()
    .name("dicer_client_late_watch_responses_total")
    .help(
      "Count of watch responses that were not for the latest watch request, because a retry " +
      "superseded the request they answer after its internal deadline passed. That request was " +
      "already counted as timedOut in dicer_client_watch_request_outcomes_total, so these " +
      "responses are counted here instead of there to keep that metric summing to requests " +
      "sent. The response could still be incorporated."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "clientType")
    .register()

  private val numSliceLookups: Counter = Counter
    .build()
    .name("dicer_client_num_slice_lookups_total")
    .help("The number of slice lookups created in this process")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "clientType")
    .register()

  private val numActiveSliceLookups: Gauge = Gauge
    .build()
    .name("dicer_client_num_active_slice_lookups")
    .help("The number of active slice lookups in this process")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "clientType")
    .register()

  private val numSliceLookupCacheHits: Counter = Counter
    .build()
    .name("dicer_client_num_slice_lookup_cache_hits_total")
    .help(
      "SliceLookup cache lookup results when creating clients. " +
      "configMatched=true means an existing lookup was reused (cache hit with same config), " +
      "configMatched=false means a new lookup was created due to config mismatch."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "configMatched")
    .register()

  private val numWatchChannelsCreated: Counter = Counter
    .build()
    .name("dicer_client_watch_channels_created_total")
    .help(
      "The number of watch channels created by Dicer clients in this process."
    )
    .labelNames("clientName")
    .register()

  /**
   * Histogram buckets for assignment propagation latency in milliseconds.
   * Uses a growth factor of 1.4 in the range [1ms, 1s) and a growth factor of 2 beyond that
   * in the range [1s, 1024s (~17m)], matching the bucketing strategy used in [[SubscriberHandler]].
   */
  private val ASSIGNMENT_PROPAGATION_LATENCY_BUCKETS: Array[Double] = {
    val buckets = scala.collection.mutable.ArrayBuffer[Double]()
    // Growth factor of 1.4 from 1ms to 1s
    var bucket: FiniteDuration = 1.millisecond
    while (bucket < 1.second) {
      buckets.append(bucket.toUnit(TimeUnit.MILLISECONDS))
      bucket = bucket.mul(1400).div(1000) // i.e. *= 1.4
    }
    // Growth factor of 2 from 1s to 1024s
    bucket = 1.second
    while (bucket <= 1024.seconds) {
      buckets.append(bucket.toUnit(TimeUnit.MILLISECONDS))
      bucket *= 2
    }
    buckets.toArray
  }

  private val assignmentPropagationLatency: Histogram = Histogram
    .build()
    .name("dicer_assignment_propagation_latency_ms")
    .help("The latency from assignment generation to client application in milliseconds")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .buckets(ASSIGNMENT_PROPAGATION_LATENCY_BUCKETS: _*)
    .register()

  private val watchRequests: Counter = Counter
    .build()
    .name("dicer_client_watch_requests_total")
    .help(
      "Count of watch requests, labeled by status (success/failure) and gRPC status code."
    )
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "clientType",
      "status",
      "grpc_status"
    )
    .register()

  /**
   * Histogram buckets for ClientRequestP proto size in bytes. Uses fine granularity (128 KiB steps)
   * in the range [0, 1 MiB) where most requests are expected, and coarser granularity (1 MiB steps)
   * beyond that to capture outliers approaching the 4 MiB limit.
   */
  private val CLIENT_REQUEST_SIZE_BUCKETS: Array[Double] = {
    val buckets = scala.collection.mutable.ArrayBuffer[Double]()

    // Fine-grained buckets from 128 KiB to 1 MiB (128 KiB increments)
    // Captures typical request sizes with good resolution
    var bucketBytes: Long = 128 * 1024 // 128 KiB
    while (bucketBytes < 1024 * 1024) { // Up to (but not including) 1 MiB
      buckets.append(bucketBytes.toDouble)
      bucketBytes += 128 * 1024
    }

    // Coarse-grained buckets from 1 MiB to 8 MiB (1 MiB increments)
    // Captures outliers and requests approaching the 4 MiB limit
    bucketBytes = 1024 * 1024 // 1 MiB
    while (bucketBytes <= 8 * 1024 * 1024) { // Up to 8 MiB
      buckets.append(bucketBytes.toDouble)
      bucketBytes += 1024 * 1024
    }

    buckets.toArray
  }

  private val clientRequestProtoSizeBytesHistogram: Histogram = Histogram
    .build()
    .name("dicer_client_request_proto_size_bytes_histogram")
    .help("The size of ClientRequestP proto messages sent by a Dicer client in bytes")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "clientType")
    .buckets(CLIENT_REQUEST_SIZE_BUCKETS: _*)
    .register()

  /**
   * Removes the per-(target, source, assigner service info) labels from the gauge metrics so that
   * a stopped client or changed assigner service info does not leave stale samples in the
   * Prometheus scrape.
   *
   * Only Gauges are removed; Counters (e.g. `numberNewGenerations`) are intentionally left in
   * place because we typically observe counters by `rate()` queries and leaving them in place
   * doesn't affect the dashboards or alerts.
   *
   * @param target the target whose labels should be removed
   * @param source the source label associated with the calling client
   * @param assignerServiceInfoOpt the service info of the Assigner whose samples should be
   *                               removed, or [[None]] if the assignment did not carry it.
   */
  private[client] def removeGaugesForTarget(
      target: Target,
      source: AssignmentMetricsSource,
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Unit = {
    val assignerName: String = assignerServiceInfoOpt.map(_.name).getOrElse(UNKNOWN_ASSIGNER_NAME)
    val assignerInstanceId: String =
      assignerServiceInfoOpt.map(_.instanceId).getOrElse(UNKNOWN_ASSIGNER_INSTANCE_ID)
    val labelValues: Seq[String] = Seq(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel,
      source.toString,
      assignerName,
      assignerInstanceId
    )

    latestGenerationNumber.remove(labelValues: _*)
    latestStoreIncarnation.remove(labelValues: _*)
  }

  /**
   * Updates the Prometheus metrics for the latestGenerationNumber, latestStoreIncarnation, and
   * numberNewGenerations. Cleans up stale metrics when the assigner service info changes so at
   * most one series per (target, source) is exported.
   *
   * This function can be called concurrently by multiple clerks for the same target but should
   * never throw an exception. Concurrent writes to gauges will cause the last-written value to
   * win. In the scenario one clerk removes gauges for a target and simultaneously another clerk
   * attempts to write to the gauge, this will result in either the deleted series being recreated
   * or the write being dropped. This is acceptable because once both clerks are updated with the
   * newest assignment, the metrics will converge to the same state with no stale series.
   *
   * @param generation the generation of the new assignment.
   * @param target the target.
   * @param source the source of the metric.
   * @param previousAssignerServiceInfoOpt the service info previously recorded for this
   *                                       (target, source), or [[None]] if it was unknown.
   * @param assignerServiceInfoOpt the service info of the Assigner that generated the assignment,
   *                               or [[None]] if the assignment did not carry it.
   */
  private[client] def updateOnNewAssignment(
      generation: Generation,
      target: Target,
      source: AssignmentMetricsSource,
      previousAssignerServiceInfoOpt: Option[AssignerServiceInfo],
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Unit = {
    // Remove gauge if the assigner service info has changed. The service info can change if the
    // Assigner rolls back to a version with a different service info or possibly (but unlikely)
    // if the client received an assignment from a different Assigner service instance.
    if (previousAssignerServiceInfoOpt != assignerServiceInfoOpt) {
      removeGaugesForTarget(target, source, previousAssignerServiceInfoOpt)
    }

    val assignerName: String = assignerServiceInfoOpt.map(_.name).getOrElse(UNKNOWN_ASSIGNER_NAME)
    val assignerInstanceId: String =
      assignerServiceInfoOpt.map(_.instanceId).getOrElse(UNKNOWN_ASSIGNER_INSTANCE_ID)

    latestGenerationNumber
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString,
        assignerName,
        assignerInstanceId
      )
      .set(generation.number.value)
    latestStoreIncarnation
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString,
        assignerName,
        assignerInstanceId
      )
      .set(generation.incarnation.value)
    numberNewGenerations
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString,
        assignerName,
        assignerInstanceId
      )
      .inc()
  }

  /** The assignment state a client attached to an outgoing watch request. */
  sealed trait WatchRequestState

  object WatchRequestState {

    /** The client holds no assignment, so it is asking for one from scratch. */
    case object NoAssignment extends WatchRequestState {
      override def toString: String = "noAssignment"
    }

    /** The client holds an assignment but sent only its generation. */
    case object GenerationOnly extends WatchRequestState {
      override def toString: String = "generationOnly"
    }

    /** The client holds an assignment and sent a diff of it to catch the server up. */
    case object KnownAssignment extends WatchRequestState {
      override def toString: String = "knownAssignment"
    }
  }

  /** How a watch request resolved. */
  sealed trait ResponseOutcome

  object ResponseOutcome {

    /** The server reported the generation the client holds, so the two are in sync. */
    case object InSync extends ResponseOutcome {
      override def toString: String = "inSync"
    }

    /** The server reported an older generation than the client holds. */
    case object ServerBehind extends ResponseOutcome {
      override def toString: String = "serverBehind"
    }

    /** The server reported a newer generation than the client holds. */
    case object ServerAhead extends ResponseOutcome {
      override def toString: String = "serverAhead"
    }

    /** The request hit its internal deadline before any response arrived. */
    case object TimedOut extends ResponseOutcome {
      override def toString: String = "timedOut"
    }

    /**
     * The watch RPC returned an error instead of a response. For the specific failed status, see
     * `dicer_client_watch_requests_total`.
     */
    case object RpcError extends ResponseOutcome {
      override def toString: String = "rpcError"
    }

    /**
     * Classifies a successful watch response by comparing the generation the server reported
     * against the one the client held when the response arrived. Applies whether the response
     * carried an assignment or only a generation.
     */
    def fromGenerations(
        clientGenerationAtResponse: Generation,
        responseGeneration: Generation): ResponseOutcome = {
      if (responseGeneration == clientGenerationAtResponse) {
        InSync
      } else if (responseGeneration < clientGenerationAtResponse) {
        ServerBehind
      } else {
        ServerAhead
      }
    }
  }

  /**
   * Whether a watch response carried an assignment, alongside the case where no response arrived
   * at all. [[ResponseOutcome]] compares generations, so on its own it cannot tell a server that
   * reports a newer generation and sends the assignment from one that reports it and withholds
   * the assignment, nor show a response that reported an older or equal generation yet still
   * carried an assignment the client did not need.
   */
  sealed trait ResponseHasAssignment

  object ResponseHasAssignment {

    /** The response carried an assignment. */
    case object True extends ResponseHasAssignment {
      override def toString: String = "true"
    }

    /** The response carried only a generation, with no assignment. */
    case object False extends ResponseHasAssignment {
      override def toString: String = "false"
    }

    /** No response arrived: the request hit its internal deadline or its RPC returned an error. */
    case object NoResponse extends ResponseHasAssignment {
      override def toString: String = "noResponse"
    }
  }

  /**
   * Records how a watch request resolved, attributed to the assignment state the client attached to
   * it. This is the internal, assignment-sync view of a request; [[recordWatchRequest]] records the
   * same request at the more general gRPC level.
   *
   * @param target the target being watched
   * @param clientType the type of client that sent the request (Clerk or Slicelet)
   * @param requestState the assignment state the client attached to the request
   * @param outcome how the request resolved, see [[ResponseOutcome.fromGenerations]] for successful
   *                responses
   * @param hasAssignment whether the response carried an assignment, or
   *                      [[ResponseHasAssignment.NoResponse]] when none arrived
   */
  private[client] def recordWatchRequestOutcome(
      target: Target,
      clientType: ClientType,
      requestState: WatchRequestState,
      outcome: ResponseOutcome,
      hasAssignment: ResponseHasAssignment): Unit = {
    watchRequestOutcomes
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.getMetricLabel,
        requestState.toString,
        outcome.toString,
        hasAssignment.toString
      )
      .inc()
  }

  /**
   * Records a watch response that was not for the latest watch request. The request it answers
   * was already counted as [[ResponseOutcome.TimedOut]] by [[recordWatchRequestOutcome]], so it is
   * counted here rather than there.
   *
   * @param target the target being watched
   * @param clientType the type of client that sent the request (Clerk or Slicelet)
   */
  private[client] def recordLateWatchResponse(target: Target, clientType: ClientType): Unit = {
    lateWatchResponses
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.getMetricLabel
      )
      .inc()
  }

  /** Increments the metric tracking the number of [[SliceLookup]]s created in this process. */
  private[client] def incrementNumSliceLookups(target: Target, clientType: ClientType): Unit = {
    numSliceLookups
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.getMetricLabel
      )
      .inc()
    numActiveSliceLookups
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.toString
      )
      .inc()
  }

  /** Increments the metric tracking the number of watch channels created in this process. */
  private[client] def incrementWatchChannelsCreated(clientName: String): Unit =
    numWatchChannelsCreated.labels(clientName).inc()

  /** Decrements the metric tracking the number of active [[SliceLookup]]s in this process. */
  private[client] def decrementNumActiveSliceLookups(
      target: Target,
      clientType: ClientType): Unit = {
    numActiveSliceLookups
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.toString
      )
      .dec()
  }

  /**
   * Records the assignment propagation latency - the time from when the assignment was generated
   * (based on its generation timestamp) to when it was applied by the client.
   *
   * @param generationTime the timestamp when the assignment generation was created
   * @param currentTime the current time when the assignment is being applied
   * @param target the target for which the assignment is being applied
   */
  private[client] def recordAssignmentPropagationLatency(
      generationTime: Instant,
      currentTime: Instant,
      target: Target): Unit = {
    val latencyMs = java.time.Duration.between(generationTime, currentTime).toMillis.toDouble
    assignmentPropagationLatency
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
      .observe(latencyMs)
  }

  /**
   * Records the size of a ClientRequestP proto message being by a Dicer client.
   *
   * @param sizeBytes the size of the serialized proto in bytes
   * @param target the target for which the request is being sent
   * @param clientType the type of client sending the request (Clerk or Slicelet)
   */
  private[client] def recordClientRequestProtoSize(
      sizeBytes: Int,
      target: Target,
      clientType: ClientType): Unit = {
    clientRequestProtoSizeBytesHistogram
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.getMetricLabel
      )
      .observe(sizeBytes.toDouble)
  }

  /**
   * Records a watch request, tracking whether it succeeded or failed and the specific gRPC status
   * code.
   *
   * @param target the target for which the watch request was made.
   * @param clientType the type of client that made the request.
   * @param statusCode the gRPC status code (Code.OK for success, or an error code for failure).
   */
  private[client] def recordWatchRequest(
      target: Target,
      clientType: ClientType,
      statusCode: Code): Unit = {
    val status: String = if (statusCode == Code.OK) "success" else "failure"
    val grpcStatus: String = statusCode.toString
    watchRequests
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        clientType.getMetricLabel,
        status,
        grpcStatus
      )
      .inc()
  }

  /**
   * Records a SliceLookup cache lookup target hit.
   *
   * @param target the target for which the cache lookup was performed
   * @param configMatched true if an existing SliceLookup was reused (cache hit with matching
   *                      config), false if a new SliceLookup was created due to config mismatch
   */
  private[client] def recordSliceLookupCacheTargetHit(
      target: Target,
      configMatched: Boolean): Unit = {
    numSliceLookupCacheHits
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        configMatched.toString
      )
      .inc()
  }

  /** Status of client UUID resolution at client creation time. */
  sealed trait ClientUuidStatus

  object ClientUuidStatus {

    /** UUID was successfully resolved. */
    case object Valid extends ClientUuidStatus { override def toString: String = "valid" }

    /** UUID was not configured (absent config key and no POD_UID env var). */
    case object Missing extends ClientUuidStatus { override def toString: String = "missing" }

    /** UUID string was present but could not be parsed as a valid UUID. */
    case object Malformed extends ClientUuidStatus { override def toString: String = "malformed" }
  }

  /**
   * Counter tracking client UUID resolution status at Dicer client creation time.
   * Labels: targetName, clientType, clientUuidStatus (valid/missing/malformed).
   *
   * TODO(<internal bug>): Once all Dicer client deployments are confirmed to set POD_UID, this metric can
   * be retired.
   */
  private val clientUuidStatus: Counter = Counter
    .build()
    .name("dicer_client_uuid_status_total")
    .help(
      "Count of Dicer client instances created, labeled by target, client type, and UUID " +
      "resolution status (valid, missing, or malformed). Targets with missing client UUIDs " +
      "need their deployments updated to set POD_UID. Malformed UUIDs should be fixed."
    )
    .labelNames("targetName", "clientType", "clientUuidStatus")
    .register()

  /** Records client UUID resolution status at client creation time. */
  private[client] def recordClientUuidStatus(
      target: Target,
      clientType: ClientType,
      status: ClientUuidStatus): Unit = {
    clientUuidStatus
      .labels(target.getTargetNameLabel, clientType.toString, status.toString)
      .inc()
  }
}
