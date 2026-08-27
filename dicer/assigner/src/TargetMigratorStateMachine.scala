package com.databricks.dicer.assigner

import java.net.URI
import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe

import scala.concurrent.duration.DurationInt

import com.databricks.caching.util.{
  CachingErrorCode,
  PrefixLogger,
  Severity,
  StateMachine,
  StateMachineOutput,
  TickerTime
}
import com.databricks.dicer.assigner.TargetMigratorStateMachine._
import com.databricks.dicer.assigner.config.{
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.external.ResourceAddress
import io.prometheus.client.Gauge

/**
 * The state machine for the target migration.
 *
 * @param targetMigrationRoleResolver resolves this Assigner's [[TargetMigrationRole]] when an
 *                                    active migration begins.
 */
@NotThreadSafe
private[assigner] class TargetMigratorStateMachine(
    targetMigrationRoleResolver: TargetMigrationRoleResolver)
    extends StateMachine[Event, DriverAction] {

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
        onTargetMigrationConfigReceived(config, outputBuilder)
      case Event.AssignerEndpointSetReceived(
          location: AssignerEndpointSetLocation,
          assignerEndpointSet: VersionedResourceSet
          ) =>
        location match {
          case AssignerEndpointSetLocation.RemoteCluster =>
            onRemoteClusterAssignerEndpointSetReceived(assignerEndpointSet)
        }
    }
    updateTargetOwnershipResolverIfNewer(outputBuilder)
    outputBuilder.build()
  }

  /**
   * Processes a newly received [[TargetMigrationConfig]] and transitions the internal state
   * accordingly. On config changes, appends a [[DriverAction.SetTargetMigrationConfig]]
   * action so the driver can answer gossip rounds with the newly updated config.
   *
   * @param newTargetMigrationConfig the newly received [[TargetMigrationConfig]].
   * @param outputBuilder            collects any [[DriverAction]]s that need to be emitted as a
   *                                 side effect of the state transition.
   */
  private def onTargetMigrationConfigReceived(
      newTargetMigrationConfig: TargetMigrationConfig,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): Unit = {
    // Only accept a new config whose version is strictly greater than the current state's version.
    // This will allow the state machine to ignore stale or duplicated config deliveries.
    //
    // NOTE: The `Uninitialized` state returns a config version of -1, and a real
    // [[TargetMigrationConfig]]'s version is non-negative, so the state machine always accepts the
    // first config it receives while `Uninitialized`.
    val initialConfigVersion: Int = currentState.configVersion
    if (newTargetMigrationConfig.version > initialConfigVersion) {
      // Computing the next state can fail (e.g. the Assigner's role in a GeneralToSmk migration
      // cannot be resolved). In that case, the state transition below never happens, and we leave
      // the migrator in its current state. A later config delivery will retry this state
      // transition.
      try {
        currentState = newTargetMigrationConfig.migrationType match {
          case TargetMigrationType.NoMigration =>
            transitionToNoMigration(newTargetMigrationConfig, outputBuilder)
          case TargetMigrationType.GeneralToSmk =>
            transitionToGeneralToSmkMigration(newTargetMigrationConfig, outputBuilder)
        }
      } catch {
        case e: IllegalStateException =>
          logger.alert(
            Severity.CRITICAL,
            CachingErrorCode.TARGET_MIGRATOR_STATE_TRANSITION_FAILED,
            s"Target migrator failed to transition state after receiving a target migration " +
            s"config. The migrator will remain in its current state ($currentState). Cause: " +
            s"$e",
            every = 30.seconds
          )
      }
    }

    // Surface the current config to the driver only when it updated.
    if (currentState.configVersion > initialConfigVersion) {
      outputBuilder.appendAction(
        DriverAction.SetTargetMigrationConfig(currentState.latestConfig)
      )
    }
  }

  /**
   * Determines the new [[MigratorState.NoMigration]] state to transition to and performs any
   * required actions before returning the state.
   *
   * @param newTargetMigrationConfig the newly received "NoMigration" [[TargetMigrationConfig]].
   * @param outputBuilder            collects any [[DriverAction]]s that need to be emitted as a
   *                                 side effect of the state transition.
   */
  private def transitionToNoMigration(
      newTargetMigrationConfig: TargetMigrationConfig,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): MigratorState = {
    currentState match {
      case _: MigratorState.GeneralToSmkMigration =>
        // We are exiting an active migration, so stop the remote cluster's Assigner endpoint
        // watcher since it is no longer needed.
        outputBuilder.appendAction(DriverAction.StopRemoteClusterEndpointWatcher)
      case MigratorState.Uninitialized | _: MigratorState.NoMigration =>
        ()
    }

    // Return the new NoMigration state to transition to.
    MigratorState.NoMigration(newTargetMigrationConfig)
  }

  /**
   * Determines the new [[MigratorState.GeneralToSmkMigration]] state to transition to and performs
   * any required actions before returning the state.
   *
   * @param newGeneralToSmkMigrationConfig the newly received `GeneralToSmk`
   *                                       [[TargetMigrationConfig]].
   * @param outputBuilder                  collects any [[DriverAction]]s that need to be emitted
   *                                       as a side effect of the state transition.
   */
  @throws[IllegalStateException]("if this Assigner's role for the migration cannot be resolved.")
  private def transitionToGeneralToSmkMigration(
      newGeneralToSmkMigrationConfig: TargetMigrationConfig,
      outputBuilder: StateMachineOutput.Builder[DriverAction]): MigratorState = {
    currentState match {
      case existingGeneralToSmkMigration: MigratorState.GeneralToSmkMigration =>
        // We are already in a GeneralToSmk migration, so we just adopt the latest config, which
        // might have an updated specification on which targets to redirect. However, we don't
        // re-resolve the Assigner's role or restart the remote cluster's Assigner endpoint
        // watcher, since both stay constant for the migration's duration.
        existingGeneralToSmkMigration.copy(latestConfig = newGeneralToSmkMigrationConfig)
      case MigratorState.Uninitialized | _: MigratorState.NoMigration =>
        // We are entering the migration for the first time (either from startup or from a
        // no-migration state). We first do a one-time determination of the role this Assigner
        // serves in the migration (i.e. SOURCE or DESTINATION), which stays constant for the
        // migration's duration. An exception will be thrown if this role resolution fails (e.g.
        // this Assigner shouldn't be involved in the migration), which will be caught by the
        // calling method. This ensures that neither the transition to an active migration state nor
        // the creation of the remote cluster's Assigner endpoint watcher will occur.
        val role: TargetMigrationRole =
          targetMigrationRoleResolver.resolveTargetMigrationRole(
            newGeneralToSmkMigrationConfig.migrationType
          )

        // Start watching the remote cluster's Assigner endpoints so we know where to redirect to.
        outputBuilder.appendAction(DriverAction.StartRemoteClusterEndpointWatcher)

        // Return the GeneralToSmk migration state to transition to.
        MigratorState.GeneralToSmkMigration(
          latestConfig = newGeneralToSmkMigrationConfig,
          role = role
        )
    }
  }

  /**
   * Updates the latest remote cluster Assigner endpoint set we track if the received set is newer
   * (i.e. has a greater version) than the one we already hold. Keeping the latest set up to date
   * ensures that when we redirect targets we route them to a current Assigner in the remote
   * cluster. This only applies during an active migration; sets received while Uninitialized or
   * in NoMigration are dropped.
   *
   * @param newAssignerEndpointSet the newly received set of Assigner endpoints in the remote
   *                               cluster.
   */
  private def onRemoteClusterAssignerEndpointSetReceived(
      newAssignerEndpointSet: VersionedResourceSet): Unit = {
    currentState match {
      case activeMigration: MigratorState.GeneralToSmkMigration =>
        if (isNewerAssignerEndpointSet(
            activeMigration.remoteClusterAssignerEndpointSetOpt,
            newAssignerEndpointSet
          )) {
          currentState = activeMigration.copy(
            remoteClusterAssignerEndpointSetOpt = Some(newAssignerEndpointSet)
          )
        }
      case MigratorState.Uninitialized | _: MigratorState.NoMigration =>
        // In the Uninitialized and NoMigration states we don't expect to receive any Assigner
        // endpoint sets from the remote cluster, since we only start watching for these once we
        // enter an active migration. The exception is when transitioning from a GeneralToSmk
        // migration back to NoMigration: shutting down the remote cluster's endpoint watcher is an
        // async operation, so some in-flight polls may still complete and deliver their updates
        // before the watcher stops polling. We drop these updates and log rather than alert, as
        // dropping them has no critical effect but it is worth noting.
        logger.info(
          "Dropping a received remote cluster Assigner endpoint set because no active target " +
          "migration is in progress.",
          every = 30.seconds
        )
    }
  }

  /**
   * Determines if the incoming Assigner endpoint set is strictly newer than the currently stored
   * cluster Assigner set.
   *
   * @param currentAssignerEndpointSetOpt the currently stored cluster Assigner set, or `None` if
   *                                      no set has been stored yet.
   * @param incomingAssignerEndpointSet the incoming Assigner endpoint set to compare against the
   *                                    currently stored set.
   */
  private def isNewerAssignerEndpointSet(
      currentAssignerEndpointSetOpt: Option[VersionedResourceSet],
      incomingAssignerEndpointSet: VersionedResourceSet): Boolean = {
    currentAssignerEndpointSetOpt match {
      case Some(currentAssignerEndpointSet: VersionedResourceSet) =>
        incomingAssignerEndpointSet.version > currentAssignerEndpointSet.version
      case None =>
        // If we do not currently have a stored cluster Assigner set, we consider the incoming set
        // to be strictly newer.
        true
    }
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
          // Expose the config version this Assigner is now routing with.
          setConfigVersion(candidate.configVersion)
          out.appendAction(DriverAction.UpdateTargetOwnershipResolver(candidate))
        }

      // There is no snapshot candidate currently, so there is no new resolver for the driver to
      // create. This happens only when the state machine is uninitialized.
      case None => ()
    }
  }
}

private[assigner] object TargetMigratorStateMachine {

  /**
   * Gauge holding the [[TargetMigrationConfig.version]] of the [[TargetMigrationSnapshot]] this
   * Assigner is currently routing with. It lets operators see which config version is driving
   * routing decisions during a migration rollout. The config reaches different pods at slightly
   * different times, so during a rollout pods can briefly route with different versions.
   */
  private val configVersionGauge: Gauge = Gauge
    .build()
    .name("dicer_assigner_target_migration_config_version")
    .help(
      "The target-migration config version the target migration resolver on this Assigner is " +
      "routing with."
    )
    .register()

  /** Sets the config version gauge to `version`. */
  private def setConfigVersion(version: Int): Unit = {
    configVersionGauge.set(version.toDouble)
  }

  /** Inputs events to the state machine. */
  sealed trait Event

  object Event {

    /** A new [[TargetMigrationConfig]] was received. */
    final case class TargetMigrationConfigReceived(config: TargetMigrationConfig) extends Event

    /**
     * A new set of Assigner endpoints was discovered in a cluster participating in the migration.
     *
     * @param location            which cluster the endpoint set was discovered in.
     * @param assignerEndpointSet the set of Assigner endpoints discovered in that cluster.
     */
    final case class AssignerEndpointSetReceived(
        location: AssignerEndpointSetLocation,
        assignerEndpointSet: VersionedResourceSet)
        extends Event
  }

  /**
   * Identifies which cluster a received Assigner endpoint set was discovered in.
   *
   * Today the only location is `RemoteCluster`, whose Assigner endpoints we redirect targets to
   * during a migration.
   *
   * TODO(<internal bug>): Add a `LocalCluster` location once we watch the local cluster's Assigner
   * endpoints as well. The local cluster's Assigners aren't involved in redirecting targets, but
   * we need to know them when gossiping the latest target migration config amongst all Assigners
   * participating in the migration.
   */
  sealed trait AssignerEndpointSetLocation

  object AssignerEndpointSetLocation {

    /** The remote cluster whose Assigners we redirect targets to. */
    case object RemoteCluster extends AssignerEndpointSetLocation
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

    /**
     * Tells the driver to start watching the remote cluster's Assigner endpoints.
     *
     * PRECONDITION: Only emitted if the remote watcher isn't already running (i.e. it hasn't been
     * started yet, or was stopped).
     *
     * PRECONDITION: Only emitted after an active migration config is received and this Assigner
     * successfully resolves its role in the migration.
     */
    case object StartRemoteClusterEndpointWatcher extends DriverAction

    /**
     * Tells the driver to stop watching the remote cluster's Assigner endpoints.
     *
     * PRECONDITION: Only emitted when the state machine is transitioning out of an active migration
     * state, so it is always preceded by a matching [[StartRemoteClusterEndpointWatcher]] action.
     */
    case object StopRemoteClusterEndpointWatcher extends DriverAction

    /**
     * Tells the driver the state machine's latest [[TargetMigrationConfig]], so the driver can
     * answer gossip rounds with the current config.
     */
    final case class SetTargetMigrationConfig(config: TargetMigrationConfig) extends DriverAction
  }

  /** Internal state of the state machine. */
  sealed trait MigratorState {

    /**
     * The version of the [[TargetMigrationConfig]] that this [[MigratorState]] is based on.
     * It will return -1 for the `Uninitialized` migrator state since it has no config version to
     * compare against.
     */
    def configVersion: Int

    /** The latest [[TargetMigrationConfig]] this [[MigratorState]] is based on. */
    @throws[IllegalStateException]("if this state has no config (i.e. `Uninitialized`)")
    def latestConfig: TargetMigrationConfig

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
      override def latestConfig: TargetMigrationConfig =
        throw new IllegalStateException("Uninitialized state is not based on any config")
      override def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot] = None
    }

    /**
     * No migration is active.
     *
     * @throws IllegalArgumentException if `latestConfig` is not a
     *                                  [[TargetMigrationType.NoMigration]] config.
     */
    final case class NoMigration @throws[IllegalArgumentException]()(
        override val latestConfig: TargetMigrationConfig)
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
     * A `GeneralToSmk` target migration is in progress. We transition into this state upon
     * receiving a [[TargetMigrationType.GeneralToSmk]] config. This Assigner's role (SOURCE or
     * DESTINATION) is resolved when entering the state, so `role` is always set here. The remote
     * cluster's Assigner endpoint set arrives later, so `remoteClusterAssignerEndpointSetOpt` is
     * `None` until received.
     *
     * @param latestConfig                        the latest `GeneralToSmk`
     *                                            [[TargetMigrationConfig]] driving this migration.
     * @param role                                this Assigner's [[TargetMigrationRole]] in the
     *                                            migration.
     * @param remoteClusterAssignerEndpointSetOpt the latest Assigner endpoint set for the remote
     *                                            cluster, or `None` if not yet received.
     *
     * @throws IllegalArgumentException if `latestConfig` is not a
     *                                  [[TargetMigrationType.GeneralToSmk]] config.
     */
    final case class GeneralToSmkMigration @throws[IllegalArgumentException]()(
        override val latestConfig: TargetMigrationConfig,
        role: TargetMigrationRole,
        remoteClusterAssignerEndpointSetOpt: Option[VersionedResourceSet] = None)
        extends MigratorState {
      require(
        latestConfig.migrationType == TargetMigrationType.GeneralToSmk,
        s"GeneralToSmkMigration state requires a GeneralToSmk config, got " +
        s"${latestConfig.migrationType}"
      )

      override def configVersion: Int = latestConfig.version

      override def toTargetMigrationSnapshot: Option[TargetMigrationSnapshot] = {
        // The URIs of all the remote cluster's Assigner endpoints, which the
        // [[TargetOwnershipResolver]] uses for rerouting to the peer cluster. The sequence is empty
        // when the initial endpoint set has not been received yet or when the remote cluster has no
        // Assigner endpoints, see [[TargetOwnershipResolver.selectPeerAssignerUri]] for more
        // details on how that is handled.
        val peerAssignerUris: Seq[URI] = remoteClusterAssignerEndpointSetOpt match {
          case Some(remoteClusterAssignerEndpointSet: VersionedResourceSet) =>
            remoteClusterAssignerEndpointSet.resources.values
              .map((peerAssignerAddress: ResourceAddress) => peerAssignerAddress.uri)
              .toSeq
          case None => Seq.empty
        }
        Some(
          TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = latestConfig,
            targetMigrationRole = role,
            peerAssignerUris = peerAssignerUris
          )
        )
      }
    }
  }
}
