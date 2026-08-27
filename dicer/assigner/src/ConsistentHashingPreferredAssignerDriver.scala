package com.databricks.dicer.assigner

import java.util.UUID

import javax.annotation.concurrent.GuardedBy

import scala.concurrent.Future

import com.databricks.caching.util.AlertOwnerTeam
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
 * @param localClusterMembershipChecker The [[KubernetesMembershipChecker]] used as the
 *                                      [[ResourceWatcher]] that feeds the selection state machine.
 */
private[dicer] class ConsistentHashingPreferredAssignerDriver(
    sec: SequentialExecutionContext,
    localClusterMembershipChecker: KubernetesMembershipChecker)
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
   * Re-exposes the membership checker's connection-health cell as this driver's debug-page
   * health source. The cell is unset until the checker's first poll resolves (unknown), then
   * holds `Some(true)` while connectivity is confirmed and `Some(false)` after a sustained
   * failure run. The cell handles synchronization for cross-thread readers.
   */
  private val connectionHealthCell: WatchValueCell.Consumer[Boolean] =
    localClusterMembershipChecker.connectionHealthCell

  /**
   * UUID of the most recently published preferred-assigner pick, or `None` if the latest
   * published config carried no preferred assigner (or no config has been published yet).
   * Used by [[performAction]] to detect transitions and drive
   * [[PreferredAssignerMetrics.recordConsistentHashingPreferredAssignerSwitch]].
   */
  @GuardedBy("sec")
  private var lastPublishedPickUuidOpt: Option[UUID] = None

  /** This assigner's own identity, set in [[start]]; `None` until the driver starts. */
  @GuardedBy("sec")
  private var localAssignerInfoOpt: Option[AssignerInfo] = None

  /** The latest set of eligible assigner pods considered for election. */
  @GuardedBy("sec")
  private var latestEligiblePods: Seq[AssignerInfo] = Seq.empty

  private val logger: PrefixLogger = PrefixLogger.create(this.getClass, "")

  /**
   * PRECONDITION: `localClusterMembershipChecker` must have been started before this driver is
   * started.
   *
   * Starts the driver. Creates the underlying state machine, starts the base driver, and begins
   * watching the membership checker for resource and connection-health updates.
   */
  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit =
    sec.run {
      this.assignerProtoLogger = assignerProtoLogger

      // Create and start the base driver.
      baseDriver =
        new StateMachineDriver[Event, DriverAction, ConsistentHashingPreferredAssignerStateMachine](
          sec,
          new ConsistentHashingPreferredAssignerStateMachine(assignerInfo),
          performAction,
          AlertOwnerTeam.CACHING_TEAM_NAME
        )
      baseDriver.start()

      // Begin watching the (already-started) checker for resource and connection-health updates.
      watchResourceUpdates(localClusterMembershipChecker)
      watchConnectionHealth()

      // Record this assigner's identity so the debug-page snapshot shows the CH driver as
      // active immediately, before the first resource poll or connection-health observation.
      localAssignerInfoOpt = Some(assignerInfo)
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
    logger.info("sendTerminationNotice called; no-op for consistent-hashing protocol")
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

        // Record the eligible pods reported alongside this config for the debug-page snapshot;
        // the elected preferred assigner is derived from the config when the snapshot is built.
        latestEligiblePods = eligibleAssigners
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
   * Watches the membership checker's connection-health cell and delivers suppression notices to
   * the state machine. Selection is suppressed when the connection is unhealthy.
   */
  @throws[AssertionError]("if not called within sec")
  private def watchConnectionHealth(): Unit = {
    sec.assertCurrentContext()

    connectionHealthCell.watch(
      new ValueStreamCallback[Boolean](sec) {
        override protected def onSuccess(isHealthy: Boolean): Unit = {
          sec.assertCurrentContext()
          // Suppress when unhealthy: the resource set may be stale.
          baseDriver.handleEvent(Event.SuppressionNotice(shouldSuppress = !isHealthy))
        }
      }
    )
  }

  override private[assigner] def consistentHashingStateView
      : Future[Option[ConsistentHashingState]] = sec.call {
    // Read this driver's state on its own sec and assemble the snapshot. It stays `None` (and the
    // debug page shows the driver as inactive) until start() records this assigner's identity.
    localAssignerInfoOpt.map { localAssignerInfo: AssignerInfo =>
      val preferredAssignerInfoOpt: Option[AssignerInfo] =
        preferredAssignerConfigCell.getLatestValueOpt.flatMap { config: PreferredAssignerConfig =>
          config.knownPreferredAssigner match {
            case someAssigner: PreferredAssignerValue.SomeAssigner =>
              Some(someAssigner.assignerInfo)
            case _: PreferredAssignerValue.NoAssigner | _: PreferredAssignerValue.ModeDisabled =>
              None
          }
        }
      // Derive the health from the membership checker's connection-health cell: unset means no
      // poll has been observed yet (`Init`), distinct from an observed unhealthy connection
      // (`Unhealthy`).
      val k8sConnectionHealth: ConsistentHashingState.K8sConnectionHealth =
        connectionHealthCell.getLatestValueOpt match {
          case None => ConsistentHashingState.K8sConnectionHealth.Init
          case Some(true) => ConsistentHashingState.K8sConnectionHealth.Healthy
          case Some(false) => ConsistentHashingState.K8sConnectionHealth.Unhealthy
        }
      ConsistentHashingState(
        localAssignerInfo = localAssignerInfo,
        preferredAssignerInfoOpt = preferredAssignerInfoOpt,
        eligiblePods = latestEligiblePods,
        k8sConnectionHealth = k8sConnectionHealth
      )
    }
  }
}
