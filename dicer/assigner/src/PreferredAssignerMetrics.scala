package com.databricks.dicer.assigner

import scala.concurrent.duration._

import io.grpc.Status
import io.prometheus.client.{Counter, Gauge}

import com.databricks.caching.util.CachingLatencyHistogram
import com.databricks.dicer.common.Generation

/** Metrics for the preferred assigner mechanism. */
object PreferredAssignerMetrics {

  /** The possible roles of an assigner. */
  sealed trait MonitoredAssignerRole

  object MonitoredAssignerRole {
    case object PREFERRED extends MonitoredAssignerRole
    case object PREFERRED_BECAUSE_PA_DISABLED extends MonitoredAssignerRole
    case object STANDBY_WITHOUT_PREFERRED extends MonitoredAssignerRole
    case object STANDBY extends MonitoredAssignerRole
    case object STARTUP extends MonitoredAssignerRole

    /** All cases of [[MonitoredAssignerRole]], for callers that need to iterate over them. */
    val values: Vector[MonitoredAssignerRole] = Vector(
      PREFERRED,
      PREFERRED_BECAUSE_PA_DISABLED,
      STANDBY_WITHOUT_PREFERRED,
      STANDBY,
      STARTUP
    )
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
  private sealed trait HeartbeatOutcome

  private object HeartbeatOutcome {
    case object SUCCESS extends HeartbeatOutcome
    case object FAILURE extends HeartbeatOutcome
  }

  /**
   * Identifies the source of the value a write proposes (which is independent of who is doing
   * the writing — the writer is always this pod). Used to label the [[writeCounter]] metric.
   */
  private[assigner] sealed abstract class ValueSource(val labelValue: String)

  private[assigner] object ValueSource {

    /** The proposed value is the writing assigner's own identity. */
    case object Self extends ValueSource("self")

    /** The proposed value is "no preferred assigner" (i.e. an abdication). */
    case object NoAssigner extends ValueSource("noAssigner")
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
   * A counter incremented on every preferred-assigner write proposal, labeled by the
   * [[ValueSource]] (which source supplied the value the write proposes). Counts proposals,
   * not on-the-wire RPC attempts.
   */
  private val writeCounter: Counter = Counter
    .build()
    .name("dicer_assigner_preferred_assigner_writes_total")
    .labelNames("valueSource")
    .help(
      "Number of preferred-assigner write proposals (including retry proposals), by source " +
      "of the proposed value."
    )
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
  def setAssignerRoleGauge(role: MonitoredAssignerRole): Unit = {
    for (enumValue: MonitoredAssignerRole <- MonitoredAssignerRole.values) {
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

  /** Increments the [[writeCounter]] for the given `valueSource`. */
  private[assigner] def recordWrite(valueSource: ValueSource): Unit = {
    writeCounter.labels(valueSource.labelValue).inc()
  }

}
