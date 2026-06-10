package com.databricks.dicer.assigner

import java.util.concurrent.TimeoutException

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

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
import com.databricks.dicer.assigner.TargetMigratorStateMachine.{DriverAction, Event}
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
}

private[dicer] object TargetMigrator {
  private val logger: PrefixLogger = PrefixLogger.create(getClass, "target-migrator")

  /** A default timeout for the initial [[TargetOwnershipResolver]] creation at startup. */
  val DEFAULT_INITIAL_TARGET_OWNERSHIP_RESOLVER_AWAIT_TIMEOUT: FiniteDuration = 30.seconds

  /**
   * Creates a [[TargetMigrator]], blocking until the migrator is initialized and
   * [[TargetMigrator.getLatestResolver]] has a valid value.
   *
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
      initialResolverAwaitTimeout: FiniteDuration
  ): TargetMigrator = {
    // Initialize the config provider which does a blocking poll to SAFE.
    // Once <internal bug> is implemented, if the initial poll to SAFE fails, this will throw an exception.
    val targetMigrationConfigProvider: StaticTargetMigrationConfigProvider =
      StaticTargetMigrationConfigProvider.create(
        assignerConf,
        StaticTargetMigrationConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
      )

    // Initialize the target migrator.
    val targetMigrator: TargetMigratorImpl =
      new TargetMigratorImpl(sec, targetMigrationConfigProvider)

    // Start the target migrator and return a future that completes when the first
    // [[TargetOwnershipResolver]] is created.
    val initialTargetOwnershipResolverPopulated: Future[Unit] = targetMigrator.start()

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
 * @param sec the [[SequentialExecutionContext]] within which all of this migrator's state is
 *            accessed.
 * @param targetMigrationConfigProvider supplies the [[TargetMigrationConfig]].
 */
private[assigner] class TargetMigratorImpl(
    sec: SequentialExecutionContext,
    targetMigrationConfigProvider: StaticTargetMigrationConfigProvider
) extends TargetMigrator {

  /**
   * Cell for distributing [[TargetOwnershipResolver]] updates to watchers. The Assigner will use
   * this to fetch the latest resolver to make routing decisions for targets.
   */
  private val targetOwnershipResolverCell: WatchValueCell[TargetOwnershipResolver] =
    new WatchValueCell[TargetOwnershipResolver]()

  /** The target migrator state machine's driver. */
  private val stateMachineDriver
      : StateMachineDriver[Event, DriverAction, TargetMigratorStateMachine] =
    new StateMachineDriver[Event, DriverAction, TargetMigratorStateMachine](
      sec,
      new TargetMigratorStateMachine(),
      performAction
    )

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

  /**
   * Starts the state machine driver and returns a [[Future]] that completes once
   * [[getLatestResolver]] is properly initialized.
   */
  private[assigner] def start(): Future[Unit] = {
    sec.flatCall {
      // Start the state machine driver.
      stateMachineDriver.start()

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
        targetOwnershipResolverCell.setValue(new TargetOwnershipResolver(snapshot))
    }
  }
}
