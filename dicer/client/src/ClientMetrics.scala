package com.databricks.dicer.client

import java.time.Instant
import java.util.concurrent.TimeUnit
import io.prometheus.client.{Counter, Gauge, Histogram}
import com.databricks.dicer.common.AssignmentMetricsSource.AssignmentMetricsSource
import com.databricks.dicer.common.{ClientType, Generation}
import com.databricks.dicer.external.Target
import com.databricks.dicer.common.TargetHelper.TargetOps
import io.grpc.Status.Code
import scala.concurrent.duration._

/** Contains Prometheus metrics for the Dicer client library. */
private[dicer] object ClientMetrics {

  private val latestGenerationNumber: Gauge = Gauge
    .build()
    .name("dicer_assignment_latest_generation_number")
    .help("The latest generation number for a target")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source")
    .register()

  private val latestStoreIncarnation: Gauge = Gauge
    .build()
    .name("dicer_assignment_latest_store_incarnation")
    .help("The latest store incarnation for a target")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source")
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
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source")
    .register()

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val numEmptyWatchResponses: Counter = Counter
    .build()
    .name("dicer_empty_watch_responses_total")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .help("The number of responses received by the watcher where the assignment is empty")
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
   * Updates the Prometheus metrics for the latestGenerationNumber, latestTargetIncarnation,
   * latestStoreIncarnation, and numberNewGenerations.
   *
   * @param generation the generation of the new assignment
   * @param target the target
   * @param source the source of the metric
   */
  private[client] def updateOnNewAssignment(
      generation: Generation,
      target: Target,
      source: AssignmentMetricsSource): Unit = {
    latestGenerationNumber
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString
      )
      .set(generation.number.value)
    latestStoreIncarnation
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString
      )
      .set(generation.incarnation.value)
    numberNewGenerations
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString
      )
      .inc()
  }

  /** Increments the metric tracking the number of empty watch responses received. */
  private[client] def incrementEmptyWatchResponses(target: Target): Unit = {
    numEmptyWatchResponses
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
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
   * Records a SliceLookup cache lookup result.
   *
   * @param target the target for which the cache lookup was performed
   * @param configMatched true if an existing SliceLookup was reused (cache hit with matching
   *                      config), false if a new SliceLookup was created due to config mismatch
   */
  private[client] def recordSliceLookupCacheResult(target: Target, configMatched: Boolean): Unit = {
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
