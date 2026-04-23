package com.databricks.dicer.assigner

import scala.concurrent.duration._

import io.grpc.Status
import io.prometheus.client.{Counter, Gauge}

import com.databricks.caching.util.CachingLatencyHistogram
import com.databricks.dicer.common.Generation

/** Metrics for the preferred assigner mechanism. */
object PreferredAssignerMetrics {

  /** The possible roles of an assigner. */
  object MonitoredAssignerRole extends Enumeration {
    val PREFERRED, PREFERRED_BECAUSE_PA_DISABLED, STANDBY_WITHOUT_PREFERRED, STANDBY, STARTUP,
        INELIGIBLE = Value
  }

  /**
   * A gauge metric with a role label, 1 if the pod has the specified role and 0/empty otherwise.
   * Possible values for the role label are as follows
   */
  private val assignerRoleGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_preferred_assigner_role_gauge")
    .help("The role of the assigner.")
    .labelNames("role")
    .register()

  /** The possible outcomes of a heartbeat request from a standby to the preferred assigner. */
  private object HeartbeatOutcome extends Enumeration {
    val SUCCESS, FAILURE = Value
  }

  /**
   * A counter metric that is incremented by the standby after each heartbeat attempt. The "outcome"
   * label captures the [[HeartbeatOutcome]].
   */
  private val heartbeatCounter: Counter = Counter
    .build()
    .name("dicer_assigner_preferred_assigner_standby_heartbeat_total")
    .labelNames("outcome")
    .help("Number of heartbeat attempts from preferred assigner standbys.")
    .register()

  /**
   * A double approximation of the latest known generation number of the preferred assigner value.
   *
   * Note that since doubles cannot precisely represent all longs, there may be some loss of
   * precision.
   */
  private val latestKnownGenerationGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_preferred_assigner_latest_known_generation_gauge")
    .help("The approximate latest known generation number of the preferred assigner.")
    .register()

  /**
   * A double approximation of the latest known incarnation number of the preferred assigner value.
   *
   * Note that since doubles cannot precisely represent all longs, there may be some loss of
   * precision.
   */
  private val latestKnownIncarnationGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_preferred_assigner_latest_known_incarnation_gauge")
    .help("The approximate latest known store incarnation of the preferred assigner.")
    .register()

  /** Histogram for tracking write latency of preferred assigner operations. */
  private val writeLatencyHistogram: CachingLatencyHistogram =
    CachingLatencyHistogram(
      "dicer_assigner_preferred_assigner_write_latency",
      extraLabelNames = Seq("outcome")
    )

  /** Sets the assigner role gauge to be `role`. */
  def setAssignerRoleGauge(role: MonitoredAssignerRole.Value): Unit = {
    for (enumValue <- MonitoredAssignerRole.values) {
      // Reset the gauge to 0 for other roles.
      assignerRoleGauge.labels(enumValue.toString).set(if (enumValue == role) 1 else 0)
    }
  }

  def incrementHeartbeatSuccessCount(): Unit = {
    heartbeatCounter.labels(HeartbeatOutcome.SUCCESS.toString).inc()
  }

  def incrementHeartbeatFailureCount(): Unit = {
    heartbeatCounter.labels(HeartbeatOutcome.FAILURE.toString).inc()
  }

  /** Sets the latest known preferred assigner incarnation and generation number metrics. */
  def setLatestKnownGeneration(generation: Generation): Unit = {
    latestKnownIncarnationGauge.set(generation.incarnation.value)
    latestKnownGenerationGauge.set(generation.number.value)
  }

  /**
   * Records the latency of a write operation with the given outcome.
   * This is called by the state machine after it has determined the write outcome.
   *
   * @param duration The duration of the write operation.
   * @param statusCode The gRPC status code of the operation.
   * @param outcomeLabel The outcome label ("committed", "occ_failure", or "exception").
   */
  def recordWriteLatency(
      duration: FiniteDuration,
      statusCode: Status.Code,
      outcomeLabel: String): Unit = {
    writeLatencyHistogram.observeLatency(
      latencyDuration = duration,
      operation = "write",
      statusCode = statusCode,
      errorCodeOpt = None,
      extraLabels = Seq(outcomeLabel)
    )
  }

}
