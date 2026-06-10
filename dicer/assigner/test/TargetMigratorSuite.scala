package com.databricks.dicer.assigner

import java.net.URI
import java.util.concurrent.TimeoutException

import scala.concurrent.Promise
import scala.concurrent.duration._

import com.databricks.caching.util.{
  AssertionWaiter,
  CachingErrorCode,
  MetricUtils,
  SequentialExecutionContext,
  Severity,
  TestUtils,
  ValueStreamCallback
}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.config.{
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class TargetMigratorSuite extends DatabricksTest {

  /** A valid active [[TargetMigrationConfig]] used across multiple tests. */
  private val EXAMPLE_ACTIVE_MIGRATION_CONFIG: TargetMigrationConfig = TargetMigrationConfig(
    version = 1,
    // `GeneralToSmk` is currently the only supported active target migration type.
    migrationType = TargetMigrationType.GeneralToSmk,
    forceToSourceTargetNames = Set.empty,
    forceToDestinationTargetNames = Set.empty,
    destinationTargetNameFraction = 0.0
  )

  /** A few example targets used to assert routing verdicts across tests. */
  private val EXAMPLE_TARGETS: Seq[TargetName] =
    Seq(TargetName("foo"), TargetName("bar"), TargetName("baz"))

  test(
    "TargetMigrationSnapshot.NoActiveMigration rejects a config whose migrationType is not " +
    "NoMigration"
  ) {
    // Test plan: Verify the require guard on NoActiveMigration — constructing it with an active
    // migration config (e.g. GeneralToSmk) must throw, since the variant is reserved for the
    // no-op migration type.
    assertThrow[IllegalArgumentException]("must wrap a `NoMigration`") {
      TargetMigrationSnapshot.NoActiveMigration(EXAMPLE_ACTIVE_MIGRATION_CONFIG)
    }
  }

  test(
    "TargetMigrationSnapshot.ActiveMigration rejects a config whose migrationType is NoMigration"
  ) {
    // Test plan: Verify the require guard on ActiveMigration — constructing it with a
    // NoMigration config must throw, since the variant is reserved for active migration types.
    assertThrow[IllegalArgumentException]("must not wrap a `NoMigration`") {
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = TargetMigrationConfig.NO_MIGRATION,
        targetMigrationRole = TargetMigrationRole.Source,
        peerAssignerUri = URI.create("https://peer-assigner.test")
      )
    }
  }

  test("The resolver yields Handle for every target when there is no active target migration") {
    // Test plan: Verify the `getLatestResolver` contract of the real TargetMigrator — that by
    // the time `TargetMigrator.create` returns, the migrator has a valid resolver corresponding
    // to the SAFE-supplied NoMigration config. Drive SAFE via TestableDicerAssignerConf and
    // assert that the resolver yields Handle with no outbound redirect token for every target
    // (the NoActiveMigration behavior).
    val sec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool("TargetMigratorSuite-create")
    val assignerConf: TestableDicerAssignerConf =
      new TestableDicerAssignerConf(Configs.parseMap()) {
        override val dynamicTargetMigrationConfigPollInterval: FiniteDuration = 1.second
      }
    assignerConf.putDynamicTargetMigrationConfig(
      TargetMigrationConfig.toJsonString(TargetMigrationConfig.NO_MIGRATION)
    )

    val migrator: TargetMigrator = TargetMigrator.create(
      sec,
      assignerConf,
      TargetMigrator.DEFAULT_INITIAL_TARGET_OWNERSHIP_RESOLVER_AWAIT_TIMEOUT
    )
    val resolver: TargetOwnershipResolver = migrator.getLatestResolver
    for (target: TargetName <- EXAMPLE_TARGETS) {
      assertResult(RoutingVerdict.Handle(redirectTokenOpt = None))(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
    }

    // Verify the `watch` contract: a new subscriber is delivered the current resolver immediately.
    val promise = Promise[TargetOwnershipResolver]()
    migrator.watch(new ValueStreamCallback[TargetOwnershipResolver](sec) {
      override protected def onSuccess(delivered: TargetOwnershipResolver): Unit = {
        assert(!promise.isCompleted, "The migrator's watch callback should be called only once")
        promise.success(delivered)
      }
    })
    assertResult(resolver)(TestUtils.awaitResult(promise.future, Duration.Inf))
  }

  test(
    "TargetMigrator.create throws a TimeoutException and alerts when the initial resolver is not " +
    "created in time"
  ) {
    // Test plan: Verify that the TargetMigrator's factory method will fail loudly if the
    // migrator is not able to create its initial [[TargetOwnershipResolver]] before the timeout.
    // Do this by indefinitely blocking the migrator's SEC so it is never able to create a
    // resolver, thus hitting the Await's timeout in the factory method. Assert that the exception
    // is thrown and the alert fires.
    val timeoutAlerts: MetricUtils.ChangeTracker[Int] = MetricUtils.ChangeTracker { () =>
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.INITIAL_TARGET_OWNERSHIP_RESOLVER_CREATION_TIMED_OUT,
        prefix = "target-migrator"
      )
    }

    // Setup a mock no-op [[TargetMigrationConfig]] value.
    val assignerConf: TestableDicerAssignerConf =
      new TestableDicerAssignerConf(Configs.parseMap()) {
        override val dynamicTargetMigrationConfigPollInterval: FiniteDuration = 500.milliseconds
      }
    assignerConf.putDynamicTargetMigrationConfig(
      TargetMigrationConfig.toJsonString(TargetMigrationConfig.NO_MIGRATION)
    )

    // Create and indefinitely occupy the TargetMigrator's SEC so nothing else it enqueues can run.
    val sec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        "target-migrator-suite-startup-timeout-test-sec"
      )
    val blocker: Promise[Unit] = Promise[Unit]()
    sec.run {
      TestUtils.awaitResult(blocker.future, Duration.Inf)
    }

    // Validate that the create method throws a TimeoutException and alerts.
    try {
      assertThrow[TimeoutException]("did not occur within") {
        TargetMigrator.create(sec, assignerConf, 500.milliseconds)
      }
      assertResult(1)(timeoutAlerts.totalChange())
    } finally {
      // Release the blocked SEC worker so the dedicated pool can drain.
      blocker.success(())
    }
  }

}
