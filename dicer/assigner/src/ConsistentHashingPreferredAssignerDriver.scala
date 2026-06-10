package com.databricks.dicer.assigner

import java.util.UUID

import javax.annotation.concurrent.GuardedBy

import scala.concurrent.Future
import scala.util.control.NonFatal

import io.prometheus.client.Gauge

import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  StateMachineDriver,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.dicer.assigner.ConsistentHashingPreferredAssignerStateMachine.{
  DriverAction,
  Event
}
import com.databricks.dicer.common.Generation
import com.databricks.dicer.external.ResourceAddress

/**
 * Driver for the [[ConsistentHashingPreferredAssignerStateMachine]]. Bridges a [[ResourceWatcher]]
 * to the state machine by converting resource set updates to [[Event.ResourceSetReceived]] events
 * and connection health updates to [[Event.SuppressionNotice]] events (suppressing when the
 * connection is unhealthy).
 *
 * See [[StateMachineDriver]] for the active-passive driver/state-machine collaboration model.
 *
 * @param sec The [[SequentialExecutionContext]] within which all driver and state machine state
 *            is accessed.
 * @param membershipCheckerFactory Factory that creates the [[KubernetesMembershipChecker]] used as
 *                                 the [[ResourceWatcher]]. The checker is created in [[start()]]
 *                                 once [[AssignerInfo]] is available.
 */
private[assigner] class ConsistentHashingPreferredAssignerDriver(
    sec: SequentialExecutionContext,
    membershipCheckerFactory: KubernetesMembershipChecker.Factory)
    extends PreferredAssignerDriver {

  /** The base state machine driver. */
  @GuardedBy("sec")
  private var baseDriver
      : StateMachineDriver[Event, DriverAction, ConsistentHashingPreferredAssignerStateMachine] = _

  /** The proto logger for Assigner-specific logging events. */
  @GuardedBy("sec")
  private var assignerProtoLogger: AssignerProtoLogger = _

  /** Cell for distributing [[PreferredAssignerConfig]] updates to watchers. */
  private val preferredAssignerConfigCell: WatchValueCell[PreferredAssignerConfig] =
    new WatchValueCell[PreferredAssignerConfig]()

  /**
   * Tracks the K8s API membership-checker connection-health observations and backs
   * [[selectionEligibilityWatchCell]] for this driver. Initialized to `false` to fail
   * closed during the startup window so the pod stays out of `Service` endpoints until the
   * membership checker confirms K8s connectivity; updated on [[sec]] from
   * `watchConnectionHealth`'s `onSuccess`; the cell handles synchronization for
   * cross-thread readers.
   */
  private val connectionHealthyWatchCell: WatchValueCell[Boolean] = {
    val cell: WatchValueCell[Boolean] = new WatchValueCell[Boolean]()
    cell.setValue(false)
    cell
  }

  /**
   * UUID of the most recently published preferred-assigner pick, or `None` if the latest
   * published config carried no preferred assigner (or no config has been published yet).
   * Used by [[performAction]] to detect transitions and drive
   * [[PreferredAssignerMetrics.recordConsistentHashingPreferredAssignerSwitch]].
   */
  @GuardedBy("sec")
  private var lastPublishedPickUuidOpt: Option[UUID] = None

  private val logger: PrefixLogger = PrefixLogger.create(this.getClass, "")

  /**
   * Starts the driver. Creates the [[KubernetesMembershipChecker]] from the factory (now that
   * [[AssignerInfo]] is available), creates the underlying state machine, starts the base driver
   * and resource watcher, and begins watching for resource and connection health updates.
   */
  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit =
    sec.run {
      this.assignerProtoLogger = assignerProtoLogger

      // Create the checker now that assignerInfo is available.
      val checkerOpt: Option[KubernetesMembershipChecker] = try {
        membershipCheckerFactory.create(assignerInfo, assignerProtoLogger) match {
          case Some(checker: KubernetesMembershipChecker) =>
            ConsistentHashingPreferredAssignerDriver.recordInitSuccess()
            Some(checker)
          case None =>
            logger.info("KubernetesMembershipChecker disabled by factory; driver will not start.")
            None
        }
      } catch {
        case NonFatal(ex) =>
          ConsistentHashingPreferredAssignerDriver.recordInitFailure()
          logger.warn(s"Failed to create KubernetesMembershipChecker: $ex")
          None
      }

      checkerOpt match {
        case None =>
          logger.warn("KubernetesMembershipChecker was not created; driver will not start.")
        case Some(checker: KubernetesMembershipChecker) =>
          // Create and start the base driver.
          baseDriver = new StateMachineDriver[
            Event,
            DriverAction,
            ConsistentHashingPreferredAssignerStateMachine](
            sec,
            new ConsistentHashingPreferredAssignerStateMachine(assignerInfo),
            performAction
          )
          baseDriver.start()

          // Start the resource watcher and begin watching for updates.
          checker.start()
          watchResourceUpdates(checker)
          watchConnectionHealth(checker)
      }
    }

  /**
   * Watches for updates to the [[PreferredAssignerConfig]]. Updates are delivered to `callback`
   * as they become available until some time after the returned handle is cancelled.
   */
  override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable = {
    preferredAssignerConfigCell.watch(callback)
  }

  /**
   * No-op for the consistent-hashing protocol. Termination is handled implicitly: the terminating
   * assigner's pod is removed from the K8s pod list, which the remaining assigners detect on
   * their next poll and exclude it from the consistent-hash ring.
   */
  override def sendTerminationNotice(): Unit = {
    logger.debug("sendTerminationNotice called; no-op for consistent-hashing protocol")
  }

  /** Not used by the consistent-hashing preferred assigner protocol. */
  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] = {
    logger.debug("handleHeartbeatRequest called; no-op for consistent-hashing protocol")
    Future.successful(
      HeartbeatResponse(request.opId, PreferredAssignerValue.NoAssigner(Generation.EMPTY))
    )
  }

  /** Handles actions emitted by the state machine. */
  @throws[AssertionError]("if not called within sec")
  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()

    action match {
      case DriverAction.UsePreferredAssignerConfig(
          config: PreferredAssignerConfig,
          eligibleAssigners: Seq[AssignerInfo]
          ) =>
        val currentPickUuidOpt: Option[UUID] = config.knownPreferredAssigner match {
          case someAssigner: PreferredAssignerValue.SomeAssigner =>
            Some(someAssigner.assignerInfo.uuid)
          case _: PreferredAssignerValue.NoAssigner | _: PreferredAssignerValue.ModeDisabled =>
            None
        }
        if (currentPickUuidOpt != lastPublishedPickUuidOpt) {
          PreferredAssignerMetrics.recordConsistentHashingPreferredAssignerSwitch()
          lastPublishedPickUuidOpt = currentPickUuidOpt
        }
        preferredAssignerConfigCell.setValue(config)
        assignerProtoLogger.logPreferredAssignerChange(
          config.knownPreferredAssigner,
        )
        logger.info(s"Preferred assigner config updated: $config")
    }
  }

  /**
   * Watches the given [[ResourceWatcher]] for resource set updates and delivers them to the state
   * machine as [[Event.ResourceSetReceived]] events. Converts [[ResourceAddress]] entries to
   * [[AssignerInfo]] for the state machine.
   */
  @throws[AssertionError]("if not called within sec")
  private def watchResourceUpdates(resourceWatcher: ResourceWatcher): Unit = {
    sec.assertCurrentContext()

    resourceWatcher.watch(
      new ValueStreamCallback[VersionedResourceSet](sec) {
        override protected def onSuccess(resourceSet: VersionedResourceSet): Unit = {
          sec.assertCurrentContext()

          // Convert the ResourceAddress entries to AssignerInfo entries expected by the state
          // machine.
          val resources: Map[UUID, AssignerInfo] = resourceSet.resources.map {
            case (uuid: UUID, address: ResourceAddress) =>
              uuid -> AssignerInfo(uuid, address.uri)
          }
          baseDriver.handleEvent(Event.ResourceSetReceived(resourceSet.version, resources))
        }
      }
    )
  }

  /**
   * Watches the given [[ResourceWatcher]]'s connection health and delivers suppression
   * notices to the state machine. Selection is suppressed when the connection is unhealthy.
   */
  @throws[AssertionError]("if not called within sec")
  private def watchConnectionHealth(resourceWatcher: ResourceWatcher): Unit = {
    sec.assertCurrentContext()

    resourceWatcher.watchConnectionHealth(
      new ValueStreamCallback[Boolean](sec) {
        override protected def onSuccess(isHealthy: Boolean): Unit = {
          sec.assertCurrentContext()
          connectionHealthyWatchCell.setValue(isHealthy)
          // Suppress when unhealthy: the resource set may be stale.
          baseDriver.handleEvent(Event.SuppressionNotice(shouldSuppress = !isHealthy))
        }
      }
    )
  }

  override private[assigner] def selectionEligibilityWatchCell: WatchValueCell.Consumer[Boolean] =
    connectionHealthyWatchCell
}

private[assigner] object ConsistentHashingPreferredAssignerDriver {

  /**
   * Gauge tracking the initialization result of the cluster-local membership checker used for
   * preferred assigner selection (1.0 = created successfully, 0.0 = creation failed). Used for
   * rollout monitoring instead of alert-based monitoring to avoid paging oncall.
   *
   * Lives in this driver companion (not on [[KubernetesMembershipChecker]]) because the driver is
   * the sole caller of [[recordInitSuccess]] / [[recordInitFailure]].
   */
  private val initResultGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_k8s_membership_checker_init_result")
    .help(
      "Result of cluster-local KubernetesMembershipChecker initialization " +
      "(1 = success, 0 = failure)."
    )
    .register()

  /** Records a successful membership checker initialization. */
  private[assigner] def recordInitSuccess(): Unit = initResultGauge.set(1.0)

  /** Records a failed membership checker initialization. */
  private[assigner] def recordInitFailure(): Unit = initResultGauge.set(0.0)
}
