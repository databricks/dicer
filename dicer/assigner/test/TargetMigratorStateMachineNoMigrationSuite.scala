package com.databricks.dicer.assigner

import java.net.URI

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{FakeTypedClock, StateMachineOutput}
import com.databricks.dicer.assigner.TargetMigratorStateMachine.{DriverAction, Event, MigratorState}
import com.databricks.dicer.assigner.config.{TargetMigrationConfig, TargetMigrationType}
import com.databricks.testing.DatabricksTest

/**
 * [[TargetMigratorStateMachine]] tests when there is no active target migration
 * ([[TargetMigrationType.NoMigration]]).
 */
class TargetMigratorStateMachineNoMigrationSuite extends DatabricksTest {

  /** Supplies the [[TickerTime]] and [[Instant]] passed to the state machine across all tests. */
  private val fakeClock: FakeTypedClock = new FakeTypedClock

  /**
   * A placeholder Assigner cluster URI. Since this test suite never drives an active target
   * migration, the role resolver which requires a valid cluster URI is never consulted and
   * so a valid Kubernetes cluster URI is not needed.
   */
  private val PLACEHOLDER_CLUSTER_URI: URI = URI.create("https://placeholder-cluster.test")

  test("MigratorState.Uninitialized has no TargetMigrationSnapshot to publish") {
    // Test plan: Verify that the Uninitialized state maps to no snapshot, so the migrator publishes
    // no resolver until the state machine has received its initial config.
    assertResult(None)(MigratorState.Uninitialized.toTargetMigrationSnapshot)
  }

  test("MigratorState.NoMigration publishes a NoActiveMigration snapshot wrapping its config") {
    // Test plan: Verify that the NoMigration state maps to a NoActiveMigration snapshot wrapping
    // the config it was built from, which is what backs the no-op resolver served when no migration
    // is active.
    val noMigrationState: MigratorState.NoMigration =
      MigratorState.NoMigration(latestConfig = TargetMigrationConfig.NO_MIGRATION)
    assertResult(
      Some(TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION))
    )(noMigrationState.toTargetMigrationSnapshot)
  }

  test("MigratorState.NoMigration rejects a config whose migrationType is not NoMigration") {
    // Test plan: Verify the require guard on the NoMigration state — constructing it with an active
    // migration config (e.g. GeneralToSmk) must throw, since the state is reserved for the no-op
    // migration type.
    val exampleActiveMigrationConfig: TargetMigrationConfig = TargetMigrationConfig(
      version = 1,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.0
    )
    assertThrow[IllegalArgumentException]("requires a NoMigration config") {
      MigratorState.NoMigration(latestConfig = exampleActiveMigrationConfig)
    }
  }

  test("Config deliveries that are not strictly newer than the current state are ignored") {
    // Test plan: Verify that once the state machine has accepted a config at version N, a later
    // config delivery with version <= N is ignored and the state machine will not send any actions
    // to the driver to publish a new resolver. Do this by feeding a v2 NoMigration config
    // (asserting it emits an action telling the driver to create a resolver), then feeding a stale
    // v1 config and an equal v2 config (asserting neither emits one).
    val noMigrationV2: TargetMigrationConfig = TargetMigrationConfig(
      version = 2,
      migrationType = TargetMigrationType.NoMigration,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.0
    )
    val noMigrationV1: TargetMigrationConfig = noMigrationV2.copy(version = 1)

    val stateMachine: TargetMigratorStateMachine =
      new TargetMigratorStateMachine(
        new TargetMigrationRoleResolver(PLACEHOLDER_CLUSTER_URI)
      )

    // The first config is accepted, so the state machine emits an action telling the driver to
    // create a new resolver.
    val acceptedOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.TargetMigrationConfigReceived(noMigrationV2)
    )
    assert(
      acceptedOutput.actions.contains(
        DriverAction.UpdateTargetOwnershipResolver(
          TargetMigrationSnapshot.NoActiveMigration(noMigrationV2)
        )
      )
    )

    // A stale (lower-version) delivery is ignored, so no action is sent to the driver.
    val staleOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.TargetMigrationConfigReceived(noMigrationV1)
    )
    assert(staleOutput.actions.isEmpty)

    // An equal-version delivery is also ignored.
    val equalVersionOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.TargetMigrationConfigReceived(noMigrationV2)
    )
    assert(equalVersionOutput.actions.isEmpty)
  }
}
