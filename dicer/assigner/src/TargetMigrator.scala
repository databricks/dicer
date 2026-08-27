package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.annotation.concurrent.GuardedBy

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NonFatal

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  CachingErrorCode,
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  Severity,
  StateMachineDriver,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.dicer.assigner.TargetMigrator.RemoteClusterEndpointWatcher
import com.databricks.dicer.assigner.TargetMigratorStateMachine.{
  AssignerEndpointSetLocation,
  DriverAction,
  Event
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.{
  StaticTargetMigrationConfigProvider,
  TargetMigrationConfig
}

/**
 * This is the "brain" of the target migration. It is responsible for watching for updates to the
 * [[TargetMigrationConfig]] and updating the [[TargetOwnershipResolver]] when the underlying
 * [[TargetMigrationSnapshot]] changes.
 */
private[dicer] trait TargetMigrator {
  // Since the real [[TargetMigrator]] implementation is currently in a reduced-functionality state
  // (i.e. the development to support active target migrations is still in progress), we keep this
  // trait around so that the [[TargetMigrationIntegrationSuite]] can implement a fake against it to
  // test the desired behaviours.
  //
  // TODO(<internal bug>): Once the full [[TargetMigrator]] implementation is complete and usable in tests,
  // remove this trait, replace it with the concrete class, and drop [[FakeTargetMigrator]] in favor
  // of the real migrator in the integration suite.

  /**
   * Synchronously returns the latest [[TargetOwnershipResolver]]. The migrator is fully initialized
   * before [[TargetMigrator.create]] returns, so this always returns a valid resolver.
   */
  def getLatestResolver: TargetOwnershipResolver

  /**
   * Subscribes to changes in the latest [[TargetOwnershipResolver]]. The callback receives the
   * current resolver immediately upon subscription, and again on every subsequent change.
   */
  def watch(callback: ValueStreamCallback[TargetOwnershipResolver]): Cancellable

  /**
   * Responds to [[TargetMigrationConfig]] gossiped by a peer Assigner. Adopts `peerConfigOpt` into
   * this migrator's state machine when it is strictly newer than the local config.
   *
   * Returns `Some(localConfig)` if and only if local config is strictly newer than `peerConfigOpt`
   * - i.e. this Assigner is ahead of the peer and should push its config back, or the peer did not
   * gossip any config — and `None` otherwise.
   */
  def handleGossipRequest(
      peerConfigOpt: Option[TargetMigrationConfig]): Future[Option[TargetMigrationConfig]]
}

private[dicer] object TargetMigrator {
  private val logger: PrefixLogger = PrefixLogger.create(getClass, "target-migrator")

  /**
   * The remote cluster Assigner endpoint watcher and its watch subscription, held together because
   * they share a lifecycle: both are created when an active target migration begins and torn down
   * when it ends.
   *
   * @param remoteClusterMembershipChecker the remote cluster Assigner endpoint watcher.
   * @param subscription the watch subscription on [[remoteClusterMembershipChecker]], retained so
   *        it can be cancelled when the active target migration ends (see
   *        [[stopRemoteClusterEndpointWatcher]]).
   */
  private[assigner] case class RemoteClusterEndpointWatcher(
      remoteClusterMembershipChecker: KubernetesMembershipChecker,
      subscription: Cancellable)

  /** A default timeout for the initial [[TargetOwnershipResolver]] creation at startup. */
  val DEFAULT_INITIAL_TARGET_OWNERSHIP_RESOLVER_AWAIT_TIMEOUT: FiniteDuration = 30.seconds

  /**
   * Creates a [[TargetMigrator]], blocking until the migrator is initialized and
   * [[TargetMigrator.getLatestResolver]] has a valid value.
   *
   * @param sec the [[SequentialExecutionContext]] within which all of this migrator's state is
   *            accessed.
   * @param assignerConf the Assigner's configuration.
   * @param assignerUuid the UUID of this Assigner pod. Passed to the remote cluster membership
   *                     checker and for logs.
   * @param assignerClusterUri the URI of the cluster this Assigner runs in, used to resolve this
   *                           Assigner's [[TargetMigrationRole]] in an active migration.
   * @param remoteClusterMembershipCheckerFactoryOpt factory for the remote cluster Assigner
   *        endpoint watcher. `None` when no remote cluster to watch is configured.
   * @param initialResolverAwaitTimeout how long to wait for the migrator to create its initial
   *                                    [[TargetOwnershipResolver]] before failing startup.
   */
  @throws[TimeoutException](
    "if the initial TargetOwnershipResolver is not created within the timeout"
  )
  @SuppressWarnings(
    Array(
      "AwaitError",
      "reason:blocking until the initial resolver is created is part of create's contract (so " +
      "getLatestResolver is always valid), with a timeout to enforce a deadline"
    )
  )
  def create(
      sec: SequentialExecutionContext,
      assignerConf: DicerAssignerConf,
      assignerUuid: UUID,
      assignerClusterUri: URI,
      remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory],
      initialResolverAwaitTimeout: FiniteDuration
  ): TargetMigrator = {
    val staticTargetMigrationConfigOpt: Option[TargetMigrationConfig] =
      assignerConf.staticTargetMigrationConfigOpt

    // Initialize the config provider which does a blocking poll to SAFE.
    // Once <internal bug> is implemented, if the initial poll to SAFE fails, this will throw an exception.
    val targetMigrationConfigProvider: StaticTargetMigrationConfigProvider =
      StaticTargetMigrationConfigProvider.create(
        assignerConf,
        StaticTargetMigrationConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
      )

    // Initialize the target migrator.
    val targetMigrator: TargetMigratorImpl =
      new TargetMigratorImpl(
        sec,
        targetMigrationConfigProvider,
        assignerUuid,
        assignerClusterUri,
        remoteClusterMembershipCheckerFactoryOpt
      )

    // Start the target migrator and return a future that completes when the first
    // [[TargetOwnershipResolver]] is created.
    val initialTargetOwnershipResolverPopulated: Future[Unit] =
      targetMigrator.start(staticTargetMigrationConfigOpt)

    // Block until the first [[TargetOwnershipResolver]] is created. This is done so that the
    // caller can be sure that the migrator is initialized and
    // [[TargetMigrator.getLatestResolver]] has a valid value. If the timeout is reached, throw an
    // exception.
    try {
      Await.result(initialTargetOwnershipResolverPopulated, initialResolverAwaitTimeout)
    } catch {
      case e: TimeoutException =>
        logger.alert(
          Severity.CRITICAL,
          CachingErrorCode.INITIAL_TARGET_OWNERSHIP_RESOLVER_CREATION_TIMED_OUT,
          s"TargetMigrator's initial TargetOwnershipResolver was not created within " +
          s"$initialResolverAwaitTimeout; cannot start the Assigner without a valid " +
          s"TargetOwnershipResolver."
        )
        throw new TimeoutException(
          s"TargetMigrator's initial TargetOwnershipResolver creation did not occur within " +
          s"$initialResolverAwaitTimeout. We cannot start an Assigner without a valid " +
          s"TargetOwnershipResolver."
        ).initCause(e)
    }

    targetMigrator
  }
}

/**
 * Implementation of [[TargetMigrator]].
 *
 * See [[TargetMigrator.create]] for the descriptions of the shared parameters (`sec`,
 * `assignerUuid`, `assignerClusterUri`, `remoteClusterMembershipCheckerFactoryOpt`).
 *
 * @param targetMigrationConfigProvider supplies the [[TargetMigrationConfig]].
 */
private[assigner] class TargetMigratorImpl(
    sec: SequentialExecutionContext,
    targetMigrationConfigProvider: StaticTargetMigrationConfigProvider,
    assignerUuid: UUID,
    assignerClusterUri: URI,
    remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory]
) extends TargetMigrator {

  /** Logger for the target migrator. */
  private val logger: PrefixLogger = PrefixLogger.create(getClass, "target-migrator")

  /**
   * The state machine's latest [[TargetMigrationConfig]], or `None` before the first config is
   * received. Kept in sync by the [[DriverAction.SetTargetMigrationConfig]] action, which the
   * state machine emits upon config changes.
   */
  @GuardedBy("sec")
  private var latestTargetMigrationConfigOpt: Option[TargetMigrationConfig] = None

  /** The target migrator state machine's driver. */
  @GuardedBy("sec")
  private val stateMachineDriver
      : StateMachineDriver[Event, DriverAction, TargetMigratorStateMachine] =
    new StateMachineDriver[Event, DriverAction, TargetMigratorStateMachine](
      sec,
      new TargetMigratorStateMachine(new TargetMigrationRoleResolver(assignerClusterUri)),
      performAction,
      AlertOwnerTeam.CACHING_TEAM_NAME
    )

  /**
   * The remote cluster Assigner endpoint watcher and its watch subscription, present only during an
   * active target migration. Both are created when the migration begins and torn down when it ends
   * (see [[startRemoteClusterEndpointWatcher]] and [[stopRemoteClusterEndpointWatcher]]).
   */
  @GuardedBy("sec")
  private var remoteClusterEndpointWatcherOpt: Option[RemoteClusterEndpointWatcher] = None

  /**
   * Cell for distributing [[TargetOwnershipResolver]] updates to watchers. The Assigner will use
   * this to fetch the latest resolver to make routing decisions for targets.
   */
  private val targetOwnershipResolverCell: WatchValueCell[TargetOwnershipResolver] =
    new WatchValueCell[TargetOwnershipResolver]()

  override def getLatestResolver: TargetOwnershipResolver = {
    iassert(
      targetOwnershipResolverCell.getLatestValueOpt.isDefined,
      "A valid TargetOwnershipResolver must always be populated after TargetMigrator.create returns"
    )
    targetOwnershipResolverCell.getLatestValueOpt.get
  }

  override def watch(callback: ValueStreamCallback[TargetOwnershipResolver]): Cancellable = {
    targetOwnershipResolverCell.watch(callback)
  }

  override def handleGossipRequest(
      peerConfigOpt: Option[TargetMigrationConfig]): Future[Option[TargetMigrationConfig]] =
    sec.call {
      // Feed the peer's config into the state machine. The state machine adopts it only if it is
      // strictly newer than the current config; stale or duplicate configs are ignored, using the
      // `SetTargetMigrationConfig` action to refresh `latestTargetMigrationConfigOpt`
      // synchronously.
      if (peerConfigOpt.isDefined) {
        stateMachineDriver.handleEvent(Event.TargetMigrationConfigReceived(peerConfigOpt.get))
      }

      // Return our config only when we are strictly ahead of the peer or the peer did not gossip
      // a config, so the peer can catch up.
      latestTargetMigrationConfigOpt.filter { localConfig: TargetMigrationConfig =>
        // Real `TargetMigrationConfig`s are required to be non-negative, so if not provided, we
        // use -1 to ensure that we always push our config back.
        val peerVersion: Int = peerConfigOpt.map((_: TargetMigrationConfig).version).getOrElse(-1)
        localConfig.version > peerVersion
      }
    }

  /**
   * Starts the state machine driver and returns a [[Future]] that completes once
   * [[getLatestResolver]] is properly initialized.
   *
   * @param staticTargetMigrationConfigOpt an optional static [[TargetMigrationConfig]] which can be
   *        used to seed the migration config (see:
   *        [[DicerAssignerConf.staticTargetMigrationConfigOpt]] for further details).
   */
  private[assigner] def start(
      staticTargetMigrationConfigOpt: Option[TargetMigrationConfig]): Future[Unit] = {
    sec.flatCall {
      // Start the state machine driver.
      stateMachineDriver.start()

      // A static target migration config being present means we are in an emergency situation (see:
      // `DicerAssignerConf.staticTargetMigrationConfigOpt` for when and how we set this value).
      //
      // We provide the static config to the state machine through the same path (i.e. sending it an
      // `Event.TargetMigrationConfigReceived`) as we provide config updates received from SAFE or
      // through the Assigner's Gossip so that there is only a single predictable way to deliver a
      // config and drive a state transition.
      //
      // In the case where we're trying to override the latest dynamic config from SAFE, the event
      // providing the static config and the event(s) providing the dynamic config may race, and
      // there may be a brief window of time where the Assigner still operates on the dynamic config
      // we are trying to override. This is acceptable because we're relying on the fact that the
      // static config will be set with a higher version than the dynamic config we are trying to
      // override (note that we also specify this in the doc for
      // `DicerAssignerConf.staticTargetMigrationConfigOpt`). Once the state machine eventually
      // accepts this higher-version static config, it will ignore any current dynamic config that
      // has the lower version until we put out a future dynamic config with a higher version (that
      // will presumably fix any regression introduced by the previous dynamic config).
      staticTargetMigrationConfigOpt.foreach { staticTargetMigrationConfig: TargetMigrationConfig =>
        stateMachineDriver.handleEvent(
          Event.TargetMigrationConfigReceived(staticTargetMigrationConfig)
        )
      }

      // Subscribe to target migration config updates. The provider delivers the current config
      // immediately upon subscription, which seeds the state machine with the initial config, and
      // then forwards every subsequent change.
      watchTargetMigrationConfigProviderUpdates()

      // Watch the cell so that the future we return will be completed once the first
      // [[TargetOwnershipResolver]] is created.
      val initialTargetOwnershipResolverCreated: Promise[Unit] = Promise[Unit]()
      val cancellable: Cancellable = targetOwnershipResolverCell.watch(
        new ValueStreamCallback[TargetOwnershipResolver](sec) {
          override protected def onSuccess(resolver: TargetOwnershipResolver): Unit = {
            sec.assertCurrentContext()
            // Completes the promise when the first [[TargetOwnershipResolver]] is created.
            // Since the cell will likely be updated multiple times, we use `trySuccess`
            // to make subsequent calls to this promise a no-op.
            initialTargetOwnershipResolverCreated.trySuccess(())
          }
        }
      )

      // The watch exists only to detect the first [[TargetOwnershipResolver]] being created.
      // Once that promise is fulfilled, we cancel the watch since future updates are not relevant.
      initialTargetOwnershipResolverCreated.future.onComplete { _ =>
        cancellable.cancel()
      }(sec)

      initialTargetOwnershipResolverCreated.future
    }
  }

  /**
   * Subscribes to [[TargetMigrationConfig]] updates from the SAFE-backed config provider and
   * forwards each one to the state machine as a [[Event.TargetMigrationConfigReceived]] event. The
   * provider delivers the current config immediately upon subscription, so this also seeds the
   * state machine with the initial config.
   *
   * PRECONDITION: called on `sec`.
   */
  private def watchTargetMigrationConfigProviderUpdates(): Unit = {
    sec.assertCurrentContext()
    targetMigrationConfigProvider.watch(
      new ValueStreamCallback[TargetMigrationConfig](sec) {
        override protected def onSuccess(config: TargetMigrationConfig): Unit = {
          sec.assertCurrentContext()
          stateMachineDriver.handleEvent(Event.TargetMigrationConfigReceived(config))
        }
      }
    )
  }

  /** Handles actions emitted by the state machine. */
  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()
    action match {
      case DriverAction.UpdateTargetOwnershipResolver(snapshot: TargetMigrationSnapshot) =>
        targetOwnershipResolverCell.setValue(new TargetOwnershipResolver(assignerUuid, snapshot))
      case DriverAction.StartRemoteClusterEndpointWatcher =>
        startRemoteClusterEndpointWatcher()
      case DriverAction.StopRemoteClusterEndpointWatcher =>
        stopRemoteClusterEndpointWatcher()
      case DriverAction.SetTargetMigrationConfig(config: TargetMigrationConfig) =>
        latestTargetMigrationConfigOpt = Some(config)
    }
  }

  /**
   * Creates and starts the remote cluster Assigner endpoint watcher, forwarding its endpoint-set
   * updates to the state machine. The state machine needs the remote cluster's Assigner endpoints
   * to know which Assigners it can redirect targets to during a migration.
   *
   * PRECONDITION: called on `sec`.
   * PRECONDITION: no remote cluster endpoint watcher currently exists.
   */
  private def startRemoteClusterEndpointWatcher(): Unit = {
    sec.assertCurrentContext()
    // We spin up a remote cluster endpoint watcher when entering a new target migration and tear
    // it down when exiting, so there should be no existing watcher when we start one for a new
    // migration.
    iassert(
      remoteClusterEndpointWatcherOpt.isEmpty,
      "A remote cluster endpoint watcher already exists but it is trying to be re-created."
    )

    try {
      // We use a [[KubernetesMembershipChecker]] purely for its endpoint watching capabilities - we
      // consume its endpoint set updates and ignore its other capabilities (such as the
      // self-presence checks that it performs).
      val remoteClusterMembershipChecker: KubernetesMembershipChecker =
        createRemoteClusterMembershipChecker()

      // Start the checker and forward every remote cluster Assigner endpoint set it discovers to
      // the state machine.
      //
      // NOTE: The TargetMigrator is created and started before the Assigner's server. However, the
      // AssignerInfo that the proto logger depends on relies on server details (e.g. the port) in
      // order to be created, but this doesn't exist yet. So we use a no-op proto logger instead. We
      // don't need detailed structured logs, and have sufficient observability through metrics.
      remoteClusterMembershipChecker.start(AssignerProtoLogger.createNoop(sec))
      val subscription: Cancellable = remoteClusterMembershipChecker.watch(
        new ValueStreamCallback[VersionedResourceSet](sec) {
          override protected def onSuccess(assignerEndpointSet: VersionedResourceSet): Unit = {
            sec.assertCurrentContext()
            stateMachineDriver.handleEvent(
              Event.AssignerEndpointSetReceived(
                AssignerEndpointSetLocation.RemoteCluster,
                assignerEndpointSet
              )
            )
          }
        }
      )

      remoteClusterEndpointWatcherOpt = Some(
        RemoteClusterEndpointWatcher(remoteClusterMembershipChecker, subscription)
      )
    } catch {
      case NonFatal(e) =>
        // The remote cluster endpoint watcher could not be started even though the target
        // migrator's state machine has entered an active migration state. We alert because without
        // the endpoint watcher, watch requests will fail for targets that need to be redirected
        // since the Assigner will not know which Assigner pod in the remote cluster to redirect to.
        // However, this is not fatal (see the docs of the alert fired below for more details).
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.ACTIVE_TARGET_MIGRATION_REMOTE_CLUSTER_ENDPOINT_WATCHER_CREATION_FAILED,
          s"Failed to start the remote cluster endpoint watcher for the active target migration. " +
          s"Cause: $e",
          every = 30.seconds
        )
    }
  }

  /**
   * Creates a remote cluster membership checker, which the target migrator uses to watch for
   * Assigner endpoint updates in the remote cluster.
   *
   * PRECONDITION: called on `sec`.
   */
  @throws[IllegalStateException]("if no remote membership checker factory is configured")
  @throws[RuntimeException]("if the remote membership checker factory did not create a checker")
  private def createRemoteClusterMembershipChecker(): KubernetesMembershipChecker = {
    sec.assertCurrentContext()
    val remoteFactory: KubernetesMembershipChecker.Factory =
      remoteClusterMembershipCheckerFactoryOpt.getOrElse(
        throw new IllegalStateException(
          "This Assigner is part of an active target migration but no remote cluster membership " +
          "checker factory is configured. This likely indicates a misconfiguration of the " +
          "remote cluster watching config (RemoteMembershipCheckerConf), but check the alert " +
          "that should have fired at this Assigner's startup for more detail on why this failure " +
          "occurred."
        )
      )

    remoteFactory.create(assignerUuid)
  }

  /**
   * Stops the remote cluster Assigner endpoint watcher when the migration ends: it cancels the
   * watch subscription and stops the checker (which enables its cleanup).
   *
   * PRECONDITION: called on `sec`.
   */
  private def stopRemoteClusterEndpointWatcher(): Unit = {
    sec.assertCurrentContext()
    remoteClusterEndpointWatcherOpt match {
      case Some(watcher: RemoteClusterEndpointWatcher) =>
        // Cancel the watch subscription to stop receiving any more endpoint set updates and then
        // stop the checker.
        watcher.subscription.cancel()
        watcher.remoteClusterMembershipChecker.stopAsync()
      case None =>
        // This means that the remote cluster's endpoint watcher previously failed to be created
        // after the state machine transitioned into the active migration state, and then we
        // received a config that transitioned us out of the active migration, which is what
        // prompts the tear down of the remote cluster's endpoint watcher (which doesn't exist in
        // this case). We already alert earlier when the watcher's creation fails, so we will only
        // log here to not create redundancy.
        logger.info(
          "There is no remote cluster endpoint watcher to stop when exiting the active target " +
          "migration, since it was never successfully created for this migration."
        )
    }
    remoteClusterEndpointWatcherOpt = None
  }
}
