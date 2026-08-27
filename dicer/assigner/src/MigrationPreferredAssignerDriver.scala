package com.databricks.dicer.assigner

import java.util.UUID

import javax.annotation.concurrent.GuardedBy

import scala.concurrent.Future
import scala.util.{Failure, Success}

import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  ValueStreamCallback
}
import com.databricks.dicer.assigner.MigrationMode.{
  ConsistentHashingNominatedEtcdReadMode,
  ConsistentHashingPrimaryEtcdWritesMode,
  ShadowMode
}

/**
 * A [[PreferredAssignerDriver]] that wraps two drivers -- an old production driver and a new
 * replacement -- to enable staged migration between them.
 *
 * The [[MigrationMode]] determines which driver's result is authoritative at each migration
 * stage. Lifecycle and heartbeat signals are delivered to both drivers (so the new driver
 * exercises its full lifecycle), but only the authoritative driver's result is returned to
 * callers.
 *
 * Some migration modes additionally forward state between the inner drivers (for example, the
 * new driver's elected pick becomes an input to the old driver). In both consistent-hashing modes
 * (`ConsistentHashingNominatedEtcdReadMode` and `ConsistentHashingPrimaryEtcdWritesMode`), the
 * migration driver subscribes to `newDriver`'s watch stream and forwards every observed pick into
 * `oldDriver` via [[PreferredAssignerDriver.updateExternalPick]]. Drivers that don't model an
 * external-pick input inherit the base trait's no-op default; modes that don't forward picks (only
 * `ShadowMode` today) simply don't subscribe to `newDriver`'s watch stream, so nothing is
 * forwarded to `oldDriver`.
 *
 * The two consistent-hashing modes differ only in what external watchers see via [[watch]]. In
 * `ConsistentHashingNominatedEtcdReadMode` etcd remains authoritative for reads, so [[watch]]
 * exposes `oldDriver`'s stream directly. In `ConsistentHashingPrimaryEtcdWritesMode` consistent
 * hashing is authoritative for reads: [[watch]] exposes `newDriver`'s stream directly, so watchers
 * see the consistent-hashing pick as soon as it is elected. Etcd is still written (the pick is
 * forwarded to `oldDriver`, which writes it for durability, interop, and rollback), but the
 * migration driver no longer waits for or reads back that write. This concerns the [[watch]] read
 * surface only; [[handleHeartbeatRequest]] remains served by the etcd-backed driver in every mode,
 * so the etcd lease/election protocol keeps running underneath.
 *
 * PRECONDITION: in either consistent-hashing mode, `oldDriver`, `newDriver`, and this driver must
 * all run on the same `SequentialExecutionContext`. With a shared SEC, FIFO ordering between
 * `oldDriver.start`, `newDriver.start`, and the watch subscriptions scheduled on `sec` is what
 * guarantees that any forwarded pick observes an initialized `oldDriver`.
 */
private[dicer] final class MigrationPreferredAssignerDriver(
    sec: SequentialExecutionContext,
    migrationMode: MigrationMode,
    oldDriver: PreferredAssignerDriver,
    newDriver: PreferredAssignerDriver)
    extends PreferredAssignerDriver {

  private val logger: PrefixLogger = PrefixLogger.create(getClass, "")

  /**
   * Latest pick UUID observed from the new (consistent-hashing) driver, or `None` if the
   * driver has not yet published. The inner `Option[UUID]` is `None` when the published
   * pick is "no PA known".
   *
   * Used in both consistent-hashing modes to drive the consistent-hashing-vs-etcd agreement gauge.
   */
  @GuardedBy("sec")
  private var latestConsistentHashingPickUuidOpt: Option[Option[UUID]] = None

  /**
   * Latest pick UUID observed from the old (etcd-backed) driver, or `None` if the driver
   * has not yet published. The inner `Option[UUID]` is `None` when the published value is
   * "no PA known".
   *
   * Used in both consistent-hashing modes to drive the consistent-hashing-vs-etcd agreement gauge.
   */
  @GuardedBy("sec")
  private var latestEtcdPickUuidOpt: Option[Option[UUID]] = None

  /**
   * The agreement gauge/counter are emitted in both consistent-hashing modes; this label
   * distinguishes Phase 2.1 (etcd authoritative for reads) from Phase 2.2 (consistent-hashing
   * authoritative). See [[PreferredAssignerMetrics.setConsistentHashingVsEtcdAgreement]].
   */
  private val agreementModeLabel: String =
    migrationMode match {
      case ShadowMode | ConsistentHashingNominatedEtcdReadMode =>
        PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_1
      case ConsistentHashingPrimaryEtcdWritesMode =>
        PreferredAssignerMetrics.CONSISTENT_HASHING_VS_ETCD_AGREEMENT_MODE_PHASE_2_2
    }

  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit = {
    oldDriver.start(assignerInfo, assignerProtoLogger)
    newDriver.start(assignerInfo, assignerProtoLogger)
    // TODO(<internal bug>): invert this gating. Always subscribe to pick forwarding regardless of
    // mode (so the watch + dispatch code paths are exercised under `ShadowMode` too), and
    // gate the write decision inside `EtcdPreferredAssignerStateMachine` instead. That way
    // the only thing the migration-mode toggle controls is whether the etcd state machine
    // *acts* on the externally-supplied pick when proposing writes — the plumbing itself
    // gets continuous coverage in production well before the consistent-hashing modes ramp up.
    migrationMode match {
      case ShadowMode =>
        // In shadow mode consistent hashing does not drive selection: there is no pick forwarding
        // or agreement tracking, and the new driver's output is discarded.
        ()
      case ConsistentHashingNominatedEtcdReadMode | ConsistentHashingPrimaryEtcdWritesMode =>
        // The consistent-hashing modes forward the elected pick to the etcd driver (which writes
        // it to the etcd store) and track consistent-hashing-vs-etcd agreement. They differ only
        // in which driver [[watch]] exposes; the pick forwarding and write path are identical.
        // Schedule the subscriptions on `sec` so they run after both inner drivers' `start` tasks
        // (FIFO). Per the class scaladoc, this is the load-bearing ordering. The `WatchValueCell`
        // "latest value" guarantee plus `updateExternalPick`'s idempotency (see trait scaladoc)
        // handle any intermediate values.
        sec.run {
          subscribeForPickForwarding()
          subscribeForAgreementTracking()
        }
    }
  }

  /**
   * Subscribes `callback` to the authoritative preferred-assigner stream for the current mode: the
   * EtcdPreferredAssignerDriver's stream when etcd is the read source of truth (shadow and
   * consistent-hashing-nominated modes), or the ConsistentHashingPreferredAssignerDriver's stream
   * in `ConsistentHashingPrimaryEtcdWritesMode` (the elected pick as soon as it is elected; etcd is
   * still written but not read back). The exposed stream's `PreferredAssignerConfig` generation is
   * sourced from whichever driver is authoritative; in `ConsistentHashingPrimaryEtcdWritesMode` it
   * carries the consistent-hashing driver's sentinel generation, not an etcd store generation, and
   * so is not comparable across a mode transition.
   */
  override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable = {
    migrationMode match {
      case ShadowMode | ConsistentHashingNominatedEtcdReadMode =>
        oldDriver.watch(callback)
      case ConsistentHashingPrimaryEtcdWritesMode =>
        newDriver.watch(callback)
    }
  }

  override def sendTerminationNotice(): Unit = {
    // Both drivers should receive all signals regardless of mode. We don't depend on the order in
    // which the drivers receive the signal (which is nondeterministic anyway, since
    // `sendTerminationNotice` schedules a task to run asynchronously).
    oldDriver.sendTerminationNotice()
    newDriver.sendTerminationNotice()
  }

  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] = {
    // Deliver the signal to both drivers regardless of mode; ordering is nondeterministic but
    // unimportant. Only the old (authoritative) driver's response is returned to the caller;
    // the new driver's response is fire-and-forget. Successes are discarded silently; failures
    // are logged.
    val oldResponse: Future[HeartbeatResponse] = oldDriver.handleHeartbeatRequest(request)
    val newResponse: Future[HeartbeatResponse] = newDriver.handleHeartbeatRequest(request)
    newResponse.onComplete {
      case Success(_) => ()
      case Failure(e: Throwable) =>
        logger.warn(
          s"Ignoring heartbeat error from new (non-authoritative) driver in mode $migrationMode: $e"
        )
    }(sec)
    oldResponse
  }

  // The consistent-hashing state shown on the debug page comes from the new (CH) driver.
  override private[assigner] def consistentHashingStateView
      : Future[Option[ConsistentHashingState]] =
    newDriver.consistentHashingStateView

  /**
   * Subscribes to [[newDriver]]'s watch stream and forwards every observed
   * preferred-assigner identity to [[oldDriver]] via
   * [[PreferredAssignerDriver.updateExternalPick]]. The returned [[Cancellable]] is
   * intentionally discarded: this class has no shutdown method today, so the subscription
   * lives for the process. If a shutdown hook is ever added, this subscription must be
   * cancelled before either inner driver is torn down.
   *
   * Scheduled on [[sec]] from [[start]]; FIFO ordering with the two inner drivers' `start`
   * tasks ensures both drivers are initialized before this subscription is registered. The
   * `ValueStreamCallback` contract guarantees that `onSuccess` will be invoked on [[sec]]
   * regardless of which thread emits the underlying value.
   *
   * The consistent-hashing pick UUID is also recorded for the consistent-hashing-vs-etcd
   * agreement gauge maintained by [[recomputeAgreementGauge]].
   */
  private def subscribeForPickForwarding(): Unit = {
    sec.assertCurrentContext()
    val callback = new ValueStreamCallback[PreferredAssignerConfig](sec) {
      override def onSuccess(config: PreferredAssignerConfig): Unit = {
        sec.assertCurrentContext()
        val externalPickOpt: Option[AssignerInfo] = assignerInfoFrom(config)
        oldDriver.updateExternalPick(externalPickOpt)
        latestConsistentHashingPickUuidOpt = Some(externalPickOpt.map(_.uuid))
        recomputeAgreementGauge()
      }
    }
    val _: Cancellable = newDriver.watch(callback)
  }

  /**
   * Subscribes to [[oldDriver]]'s watch stream to observe the authoritative etcd-backed
   * preferred-assigner value and update the consistent-hashing-vs-etcd agreement gauge. This
   * subscription is independent of the outer caller's subscription registered via [[watch]] —
   * both subscriptions co-exist on the same [[WatchValueCell]].
   *
   * The returned [[Cancellable]] is intentionally discarded, for the same reason as in
   * [[subscribeForPickForwarding]].
   */
  private def subscribeForAgreementTracking(): Unit = {
    sec.assertCurrentContext()
    val callback: ValueStreamCallback[PreferredAssignerConfig] =
      new ValueStreamCallback[PreferredAssignerConfig](sec) {
        override def onSuccess(config: PreferredAssignerConfig): Unit = {
          sec.assertCurrentContext()
          val etcdPickOpt: Option[AssignerInfo] = assignerInfoFrom(config)
          latestEtcdPickUuidOpt = Some(etcdPickOpt.map(_.uuid))
          recomputeAgreementGauge()
        }
      }
    val _: Cancellable = oldDriver.watch(callback)
  }

  /**
   * Returns the [[AssignerInfo]] carried by the latest [[PreferredAssignerValue]] published on
   * one of the inner driver's watch streams, or `None` when no preferred assigner is currently
   * known (or the inner driver is disabled). Used to normalize the value shape for both
   * pick-forwarding and agreement-gauge maintenance.
   */
  private def assignerInfoFrom(config: PreferredAssignerConfig): Option[AssignerInfo] =
    config.knownPreferredAssigner match {
      case someAssigner: PreferredAssignerValue.SomeAssigner => Some(someAssigner.assignerInfo)
      case _: PreferredAssignerValue.NoAssigner | _: PreferredAssignerValue.ModeDisabled => None
    }

  /**
   * Sets the [[PreferredAssignerMetrics]] consistent-hashing-vs-etcd agreement gauge based on
   * the latest picks observed from each driver. Two UUIDs agree when they are equal (including
   * both being "no PA known"). The gauge is intentionally not touched until both drivers have
   * published at least one value, so it stays absent during warm-up rather than emitting
   * a misleading "disagree".
   *
   * The gauge can transiently read `0` immediately after the consistent-hashing driver elects a
   * new pick and the etcd driver has not yet republished its updated value: the
   * consistent-hashing callback runs first (updating the consistent-hashing-side state),
   * recomputes the gauge against the previous etcd value, sets the gauge to `0`, and the gauge
   * returns to `1` once the etcd driver's watch callback fires. The disagreement alert's `for:`
   * duration is sized to swallow this transient.
   *
   * PRECONDITION: must be called on [[sec]].
   */
  private def recomputeAgreementGauge(): Unit = {
    sec.assertCurrentContext()
    (latestConsistentHashingPickUuidOpt, latestEtcdPickUuidOpt) match {
      case (Some(chUuidOpt: Option[UUID]), Some(etcdUuidOpt: Option[UUID])) =>
        PreferredAssignerMetrics.setConsistentHashingVsEtcdAgreement(
          modeLabel = agreementModeLabel,
          consistentHashingPickUuidOpt = chUuidOpt,
          etcdPickUuidOpt = etcdUuidOpt
        )
      case _ =>
        // Warm-up: at least one driver has not yet published. Leave the gauge unset.
        ()
    }
  }

  /**
   * Test-only accessors. Exposes the etcd-backed old driver so tests can reach it to simulate etcd
   * faults on an assigner running a migration mode (production never unwraps the migration driver).
   */
  private[dicer] object forTest {
    def oldDriver: PreferredAssignerDriver = MigrationPreferredAssignerDriver.this.oldDriver
  }
}
