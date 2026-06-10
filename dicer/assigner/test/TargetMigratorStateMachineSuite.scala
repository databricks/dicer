package com.databricks.dicer.assigner

import java.time.Instant

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{StateMachineOutput, TickerTime}
import com.databricks.dicer.assigner.TargetMigratorStateMachine.{DriverAction, Event, MigratorState}
import com.databricks.dicer.assigner.config.{TargetMigrationConfig, TargetMigrationType}
import com.databricks.testing.DatabricksTest

class TargetMigratorStateMachineSuite extends DatabricksTest {

  /** A valid `GeneralToSmk` (active) [[TargetMigrationConfig]] used across multiple tests. */
  private val GENERAL_TO_SMK_CONFIG: TargetMigrationConfig = TargetMigrationConfig(
    version = 1,
    migrationType = TargetMigrationType.GeneralToSmk,
    forceToSourceTargetNames = Set.empty,
    forceToDestinationTargetNames = Set.empty,
    destinationTargetNameFraction = 0.0
  )

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

  // TODO(<internal bug>): This test is temporary. The ActiveMigration state will return a
  // TargetMigrationSnapshot.ActiveMigration once the role and peer Assigner URI are resolved, and
  // this test should assert that snapshot instead of None.
  test("MigratorState.ActiveMigration currently has no TargetMigrationSnapshot to publish") {
    // Test plan: Verify that the ActiveMigration state maps to no snapshot — active migrations are
    // not yet serveable (role and peer resolution is deferred), so the state publishes no resolver
    // and the Assigner keeps serving its existing one.
    val activeMigrationState: MigratorState.ActiveMigration =
      MigratorState.ActiveMigration(latestConfig = GENERAL_TO_SMK_CONFIG)
    assertResult(None)(activeMigrationState.toTargetMigrationSnapshot)
  }

  test("MigratorState.NoMigration rejects a config whose migrationType is not NoMigration") {
    // Test plan: Verify the require guard on the NoMigration state — constructing it with an active
    // migration config (e.g. GeneralToSmk) must throw, since the state is reserved for the no-op
    // migration type.
    assertThrow[IllegalArgumentException]("requires a NoMigration config") {
      MigratorState.NoMigration(latestConfig = GENERAL_TO_SMK_CONFIG)
    }
  }

  test("MigratorState.ActiveMigration rejects a config whose migrationType is NoMigration") {
    // Test plan: Verify the require guard on the ActiveMigration state — constructing it with a
    // NoMigration config must throw, since the state is reserved for active migration types.
    assertThrow[IllegalArgumentException]("requires an active migration config") {
      MigratorState.ActiveMigration(latestConfig = TargetMigrationConfig.NO_MIGRATION)
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

    val stateMachine: TargetMigratorStateMachine = new TargetMigratorStateMachine

    // The first config is accepted, so the state machine emits an action telling the driver to
    // create a new resolver.
    val acceptedOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      TickerTime.ofNanos(0),
      Instant.EPOCH,
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
      TickerTime.ofNanos(0),
      Instant.EPOCH,
      Event.TargetMigrationConfigReceived(noMigrationV1)
    )
    assert(staleOutput.actions.isEmpty)

    // An equal-version delivery is also ignored.
    val equalVersionOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      TickerTime.ofNanos(0),
      Instant.EPOCH,
      Event.TargetMigrationConfigReceived(noMigrationV2)
    )
    assert(equalVersionOutput.actions.isEmpty)
  }
}
