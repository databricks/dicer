package com.databricks.dicer.assigner

import java.util.UUID

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
    case object INELIGIBLE extends MonitoredAssignerRole

    /** All cases of [[MonitoredAssignerRole]], for callers that need to iterate over them. */
    val values: Vector[MonitoredAssignerRole] = Vector(
      PREFERRED,
      PREFERRED_BECAUSE_PA_DISABLED,
      STANDBY_WITHOUT_PREFERRED,
      STANDBY,
      STARTUP,
      INELIGIBLE
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
   * the writing — the writer is always this pod). Used to label the [[writeCounter]] metric so
   * operators can observe the effect of the staged etcd → consistent-hashing migration on
   * the values being proposed.
   */
  private[assigner] sealed abstract class ValueSource(val labelValue: String)

  private[assigner] object ValueSource {

    /** The proposed value is the writing assigner's own identity. */
    case object Self extends ValueSource("self")

    /**
     * The proposed value comes from an external pick source (see
     * `EtcdPreferredAssignerDriver.updateExternalPick`). Includes the case where the external
     * pick happens to be this assigner.
     */
    case object ExternalPick extends ValueSource("externalPick")

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

  /**
   * A gauge metric with a role label for the consistent-hashing preferred assigner protocol,
   * 1 if the pod has the specified role and 0/empty otherwise. Updated exclusively by the
   * [[ConsistentHashingPreferredAssignerDriver]].
   */
  private val chAssignerRoleGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_ch_preferred_assigner_role_gauge")
    .help("The role of the assigner as determined by the consistent-hashing protocol.")
    .labelNames("role")
    .register()

  /**
   * Counter incremented every time the [[ConsistentHashingPreferredAssignerDriver]] outcome
   * changes.
   */
  private val consistentHashingPreferredAssignerSwitchesCounter: Counter = Counter
    .build()
    .name("dicer_assigner_consistent_hashing_preferred_assigner_switches_total")
    .help(
      "Number of times the consistent-hashing preferred-assigner driver has elected a " +
      "different preferred assigner than the previously-published one (including " +
      "transitions to and from 'no preferred assigner known')."
    )
    .register()

  /** Histogram for tracking write latency of preferred assigner operations. */
  private val writeLatencyHistogram: CachingLatencyHistogram =
    CachingLatencyHistogram(
      "dicer_assigner_preferred_assigner_write_latency",
      extraLabelNames = Seq("outcome")
    )

  /**
   * Label value used on [[consistentHashingVsEtcdAgreementGauge]] during the Phase 2.1
   * dual-driver rollout.
   */
  private[assigner] val CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1: String = "phase21"

  /**
   * Label value used on [[consistentHashingVsEtcdAgreementGauge]] during the Phase 2.2
   * consistent-hashing-primary rollout.
   */
  private[assigner] val CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_2: String = "phase22"

  /**
   * Gauge tracking whether the consistent-hashing pick agrees with the etcd-backed
   * authoritative preferred-assigner value (1 = agree, 0 = disagree). Labeled so that
   * Prometheus scrapes no samples until [[setConsistentHashingVsEtcdAgreement]] is first
   * called — this avoids emitting a misleading default-zero (interpreted as "disagree") before
   * either inner driver has published a value, and keeps the gauge absent in modes where
   * consistent hashing does not drive selection (shadow mode).
   */
  private val consistentHashingVsEtcdAgreementGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_agreement_gauge")
    .help(
      "Whether the consistent-hashing pick agrees with the etcd-backed authoritative " +
      "preferred-assigner value (1 = agree, 0 = disagree). Emitted during the Phase 2.1 " +
      "(dual-driver) and Phase 2.2 (consistent-hashing-primary) rollouts; the `mode` label " +
      "distinguishes them."
    )
    .labelNames("mode")
    .register()

  /**
   * Counter incremented every time the consistent-hashing pick disagrees with the etcd-backed
   * authoritative pick.
   */
  private val consistentHashingVsEtcdDisagreementCounter: Counter = Counter
    .build()
    .name("dicer_assigner_preferred_assigner_consistent_hashing_vs_etcd_disagreement_total")
    .help(
      "Number of times the consistent-hashing pick disagreed with the etcd-backed " +
      "authoritative preferred-assigner value. Emitted during the Phase 2.1 (dual-driver) " +
      "and Phase 2.2 (consistent-hashing-primary) rollouts; the `mode` label distinguishes them."
    )
    .labelNames("mode")
    .register()

  /** Sets the assigner role gauge to be `role`. */
  def setAssignerRoleGauge(role: MonitoredAssignerRole): Unit = {
    for (enumValue: MonitoredAssignerRole <- MonitoredAssignerRole.values) {
      // Reset the gauge to 0 for other roles.
      assignerRoleGauge.labels(enumValue.toString).set(if (enumValue == role) 1 else 0)
    }
  }

  /** Sets the consistent-hashing assigner role gauge to `role`. */
  def setChAssignerRoleGauge(role: MonitoredAssignerRole): Unit = {
    for (enumValue: MonitoredAssignerRole <- MonitoredAssignerRole.values) {
      chAssignerRoleGauge.labels(enumValue.toString).set(if (enumValue == role) 1 else 0)
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

  /**
   * Increments the consistent-hashing preferred-assigner switches counter. Called by the
   * [[ConsistentHashingPreferredAssignerDriver]] when its elected preferred-assigner UUID
   * changes from the previously-published value.
   */
  private[assigner] def recordConsistentHashingPreferredAssignerSwitch(): Unit = {
    consistentHashingPreferredAssignerSwitchesCounter.inc()
  }

  /**
   * Updates the gauge to reflect whether the consistent-hashing pick UUID matches the etcd-backed
   * authoritative pick UUID. Two `None` values are treated as agreement (both report no preferred
   * assigner known). The caller supplies the phase label (`modeLabel`) identifying which rollout
   * the sample belongs to, since the gauge and counter are emitted in both consistent-hashing
   * modes.
   */
  private[assigner] def setConsistentHashingVsEtcdAgreement(
      modeLabel: String,
      consistentHashingPickUuidOpt: Option[UUID],
      etcdPickUuidOpt: Option[UUID]): Unit = {
    val agree: Boolean = consistentHashingPickUuidOpt == etcdPickUuidOpt
    consistentHashingVsEtcdAgreementGauge
      .labels(modeLabel)
      .set(if (agree) 1.0 else 0.0)
    if (!agree) {
      consistentHashingVsEtcdDisagreementCounter
        .labels(modeLabel)
        .inc()
    }
  }

  /** Test-only: clears the agreement gauge so tests are not order-dependent. */
  private[assigner] object forTest {
    def clearConsistentHashingVsEtcdAgreement(): Unit = {
      consistentHashingVsEtcdAgreementGauge.clear()
    }
  }
}
