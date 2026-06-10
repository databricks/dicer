package com.databricks.dicer.assigner

import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe

import scala.concurrent.duration.DurationInt

import com.databricks.caching.util.AssertMacros.{iassert, ifail}
import com.databricks.caching.util.{
  CachingErrorCode,
  PrefixLogger,
  Severity,
  StateMachine,
  StateMachineOutput,
  TickerTime
}
import com.databricks.dicer.assigner.TargetMigratorStateMachine._
import com.databricks.dicer.assigner.config.{TargetMigrationConfig, TargetMigrationType}

/**
 * The state machine for the target migration.
 */
@NotThreadSafe
private[assigner] class TargetMigratorStateMachine extends StateMachine[Event, DriverAction] {

  /** Logger for the target migration state machine. */
  private val logger: PrefixLogger =
    PrefixLogger.create(getClass, "target-migrator-state-machine")

  /**
   * Current state of the state machine. Starts off as [[MigratorState.Uninitialized]] because we do
   * not have initial knowledge of the current [[TargetMigrationConfig]] that applies to this
   * Assigner.
   */
  private var currentState: MigratorState = MigratorState.Uninitialized

  /**
   * The most recently created [TargetMigrationSnapshot].
   * Starts off as `None` when the state machine is uninitialized.
   */
  private var mostRecentlyCreatedTargetMigrationSnapshotOpt: Option[TargetMigrationSnapshot] =
    None

  override def onAdvance(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[DriverAction] = {
    new StateMachineOutput.Builder[DriverAction].build()
  }

  override def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: Event): StateMachineOutput[DriverAction] = {
    val outputBuilder: StateMachineOutput.Builder[DriverAction] =
      new StateMachineOutput.Builder[DriverAction]
    event match {
      case Event.TargetMigrationConfigReceived(config: TargetMigrationConfig) =>
        onTargetMigrationConfigReceived(config)
    }
    updateTargetOwnershipResolverIfNewer(outputBuilder)
    outputBuilder.build()
  }

  /**
   * Processes a newly received [[TargetMigrationConfig]] and transitions the internal state
   * accordingly.
   *
   * Note that active target migrations are not yet supported.
   */
  private def onTargetMigrationConfigReceived(newConfig: TargetMigrationConfig): Unit = {
    // Only accept a new config whose version is strictly greater than the current state's version.
    // This will allow the state machine to ignore stale or duplicated config deliveries.
    //
    // NOTE: The `Uninitialized` state returns a config version of -1, and a real
    // [[TargetMigrationConfig]]'s version is non-negative, so the state machine always accepts the
    // first config it receives while `Uninitialized`.
    if (newConfig.version > currentState.configVersion) {
      currentState match {
        case MigratorState.Uninitialized =>
          onTargetMigrationConfigReceivedWhileUninitialized(newConfig)

        case _: MigratorState.NoMigration =>
          onTargetMigrationConfigReceivedWhileNoMigration(newConfig)

        case _: MigratorState.ActiveMigration =>
          onTargetMigrationConfigReceivedWhileActiveMigration(newConfig)
      }
    }
  }

  /**
   * Determines the state transition to take upon receiving a new [[TargetMigrationConfig]] for a
   * migrator in the `Uninitialized` state.
   *
   * PRECONDITION: the migrator is in the [[MigratorState.Uninitialized]] state.
   */
  private def onTargetMigrationConfigReceivedWhileUninitialized(
      newConfig: TargetMigrationConfig): Unit = {
    iassert(
      currentState == MigratorState.Uninitialized,
      "Expected the migrator to be in the `Uninitialized` state."
    )

    newConfig.migrationType match {
      case TargetMigrationType.NoMigration =>
        currentState = MigratorState.NoMigration(newConfig)
      case _: TargetMigrationType =>
        // TODO(<internal bug>): Replace the fallback below with the real active migration state transition
        // logic.
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.UNSUPPORTED_ACTIVE_TARGET_MIGRATION_CONFIG,
          s"Received unsupported active target migration type, '${newConfig.migrationType}', at " +
          s"startup. Currently, falling back to a no-op (no active migration) state.",
          every = 30.seconds
        )
        currentState = MigratorState.NoMigration(TargetMigrationConfig.NO_MIGRATION)
    }
  }

  /**
   * Determines the state transition to take upon receiving a new [[TargetMigrationConfig]] for a
   * migrator in the `NoMigration` state.
   *
   * PRECONDITION: the migrator is in a [[MigratorState.NoMigration]] state.
   */
  private def onTargetMigrationConfigReceivedWhileNoMigration(
      newConfig: TargetMigrationConfig): Unit = {
    iassert(
      currentState.isInstanceOf[MigratorState.NoMigration],
      "Expected the migrator to be in the `NoMigration` state."
    )

    newConfig.migrationType match {
      case TargetMigrationType.NoMigration =>
        currentState = MigratorState.NoMigration(newConfig)
      case _: TargetMigrationType =>
        // TODO(<internal bug>): Replace the fallback below with the real active migration state transition
        // logic.
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.UNSUPPORTED_ACTIVE_TARGET_MIGRATION_CONFIG,
          s"Received unsupported active target migration type, '${newConfig.migrationType}'. " +
          s"Currently, remaining in the `NoMigration` state.",
          every = 30.seconds
        )
      // Keep the current state as-is (so long as we're falling back) since we are already in the
      // NoMigration state.
    }
  }

  /**
   * Determines the state transition to take upon receiving a new [[TargetMigrationConfig]] for a
   * migrator in the `ActiveMigration` state.
   *
   * PRECONDITION: the migrator is in an [[MigratorState.ActiveMigration]] state.
   */
  private def onTargetMigrationConfigReceivedWhileActiveMigration(
      newConfig: TargetMigrationConfig): Unit = {
    iassert(
      currentState.isInstanceOf[MigratorState.ActiveMigration],
      "Expected the migrator to be in the `ActiveMigration` state."
    )

    // Currently the migrator should not enter the `ActiveMigration` state since it should fall
    // back to a `NoMigration` state when it receives an active target migration config.
    //
    // TODO(<internal bug>): Replace the `ifail` below with the real active migration state transition logic
    ifail("Migrator state is unexpectedly in the `ActiveMigration` state.")
  }

  /**
   * Computes a new [[TargetMigrationSnapshot]] candidate based on the current state and
   * tells the driver to update the [[TargetOwnershipResolver]] cell with the new snapshot
   * if it differs from the most recently created snapshot.
   */
  private def updateTargetOwnershipResolverIfNewer(
      out: StateMachineOutput.Builder[DriverAction]): Unit = {
    val newTargetMigrationSnapshotCandidateOpt: Option[TargetMigrationSnapshot] =
      currentState.toTargetMigrationSnapshot

    newTargetMigrationSnapshotCandidateOpt match {
      case Some(candidate: TargetMigrationSnapshot) =>
        // Only tell the driver to create a new resolver when the snapshot candidate differs from
        // the most recently created snapshot, to avoid redundant resolver updates.
        if (!mostRecentlyCreatedTargetMigrationSnapshotOpt.contains(candidate)) {
          mostRecentlyCreatedTargetMigrationSnapshotOpt = Some(candidate)
          out.appendAction(DriverAction.UpdateTargetOwnershipResolver(candidate))
        }

      // There is no snapshot candidate currently, so there is no new resolver for the driver to
      // create. This happens when the state machine is uninitialized, or when it is in an active
      // migration state but the peer Assigner URI has not yet been resolved.
      case None => ()
    }
  }
}

private[assigner] object TargetMigratorStateMachine {

  /** Inputs events to the state machine. */
  sealed trait Event

  object Event {

    /** A new [[TargetMigrationConfig]] was received. */
    final case class TargetMigrationConfigReceived(config: TargetMigrationConfig) extends Event
  }

  /** Outputs that the state machine asks the driver to perform. */
  sealed trait DriverAction

  object DriverAction {

    /**
     * Tells the driver to create a new [[TargetOwnershipResolver]] to reflect the latest computed
     * [[TargetMigrationSnapshot]].
     */
    final case class UpdateTargetOwnershipResolver(snapshot: TargetMigrationSnapshot)
        extends DriverAction
  }

  /** Internal state of the state machine. */
  sealed trait MigratorState {

    /**
     * The version of the [[TargetMigrationConfig]] that this [[MigratorState]] is based on.
     * It will return -1 for the `Uninitialized` migrator state since it has no config version to
     * compare against.
     */
    def configVersion: Int

    /**
     * The [[TargetMigrationSnapshot]] that can be used to initialize a [[TargetOwnershipResolver]]
     * for this state, or `None` if this state has no snapshot to publish.
     */
    def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot]
  }

  object MigratorState {

    /**
     * Initial state before the state machine has received the initial [[TargetMigrationConfig]].
     */
    case object Uninitialized extends MigratorState {
      override def configVersion: Int = -1
      override def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot] = None
    }

    /**
     * No migration is active.
     *
     * @throws IllegalArgumentException if `latestConfig` is not a
     *                                  [[TargetMigrationType.NoMigration]] config.
     */
    final case class NoMigration @throws[IllegalArgumentException]()(
        latestConfig: TargetMigrationConfig)
        extends MigratorState {
      require(
        latestConfig.migrationType == TargetMigrationType.NoMigration,
        s"NoMigration state requires a NoMigration config, got ${latestConfig.migrationType}"
      )

      override def configVersion: Int = latestConfig.version
      override def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot] =
        Some(TargetMigrationSnapshot.NoActiveMigration(latestConfig))
    }

    /**
     * An active target migration is in progress.
     *
     * Active migrations are not yet serveable — resolving this Assigner's role and the peer
     * Assigner is deferred — so [[toTargetMigrationSnapshot]] returns `None` and the Assigner keeps
     * serving its existing resolver.
     *
     * TODO(<internal bug>): Add the `role` and `peerAssignerUri` fields to the [[ActiveMigration]] state.
     *
     * @param latestConfig the active [[TargetMigrationConfig]] driving this migration.
     * @throws IllegalArgumentException if `latestConfig` is a [[TargetMigrationType.NoMigration]]
     *                                  config.
     */
    final case class ActiveMigration @throws[IllegalArgumentException]()(
        latestConfig: TargetMigrationConfig)
        extends MigratorState {
      require(
        latestConfig.migrationType != TargetMigrationType.NoMigration,
        s"ActiveMigration state requires an active migration config, got " +
        s"${latestConfig.migrationType}"
      )

      override def configVersion: Int = latestConfig.version

      // TODO(<internal bug>): Once the role and peer Assigner URI fields are added to ActiveMigration, this
      // can return a [[TargetMigrationSnapshot.ActiveMigration]] when the peer Assigner URI is
      // known.
      override def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot] = None
    }
  }
}
