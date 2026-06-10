package com.databricks.dicer.assigner.config

import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import com.databricks.caching.util.{SequentialExecutionContext, TestUtils, ValueStreamCallback}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.TestableDicerAssignerConf
import com.databricks.testing.DatabricksTest

class StaticTargetMigrationConfigProviderSuite extends DatabricksTest {

  test("create seeds the provider with a static no-op migration config") {
    // Test plan: Verify that the factory seeds the provider with NO_MIGRATION (an OSS Assigner
    // never participates in a target migration) and that watch delivers the current config once.
    // Do this by creating the provider via the factory, confirming getLatestTargetMigrationConfig
    // returns NO_MIGRATION, and confirming watch delivers NO_MIGRATION to the callback.
    val sec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        "static-target-migration-config-provider-test"
      )
    val assignerConf: TestableDicerAssignerConf =
      new TestableDicerAssignerConf(Configs.parseMap())
    val provider = StaticTargetMigrationConfigProvider.create(
      assignerConf,
      StaticTargetMigrationConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
    )

    // Validates that the factory seeds the static provider with the no-op migration config.
    assert(provider.getLatestTargetMigrationConfig === TargetMigrationConfig.NO_MIGRATION)

    // Validates that watch delivers the current (no-op) config to the callback once.
    val delivered: Promise[TargetMigrationConfig] = Promise()
    val callback = new ValueStreamCallback[TargetMigrationConfig](sec) {
      override protected def onSuccess(value: TargetMigrationConfig): Unit = {
        delivered.success(value)
      }
    }
    val cancellable = provider.watch(callback)
    assertResult(TargetMigrationConfig.NO_MIGRATION)(
      TestUtils.awaitResult(delivered.future, Duration.Inf)
    )
    cancellable.cancel()
  }
}
