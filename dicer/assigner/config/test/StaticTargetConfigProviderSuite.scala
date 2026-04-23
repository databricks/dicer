package com.databricks.dicer.assigner.config

import scala.concurrent.duration._
import com.databricks.caching.util.{SequentialExecutionContext, ValueStreamCallback}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.TestableDicerAssignerConf
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

import scala.util.Random

class StaticTargetConfigProviderSuite extends DatabricksTest {

  /** Sequential execution context for tests. */
  private val sec = SequentialExecutionContext.createWithDedicatedPool("static-provider-test")

  /** Test configuration for the assigner. */
  private val defaultAssignerConfig: TestableDicerAssignerConf =
    new TestableDicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.forceDisableDynamicConfig" -> false,
        "databricks.dicer.enableDynamicConfig" -> true
      )
    )

  /** Randomly generates a non-default configuration. */
  private def createRandomConfig(): InternalTargetConfig = {
    ConfigTestUtil.createConfig(primaryRateMaxLoadHint = 100.0 + 100.0 * Random.nextDouble())
  }

  /** Create a simple static target config map for testing. */
  private def createStaticTargetConfigMap(): InternalTargetConfigMap = {
    InternalTargetConfigMap.create(
      configScopeOpt = None,
      targetConfigMap = Map(
        TargetName("test-target-1") -> createRandomConfig(),
        TargetName("test-target-2") -> createRandomConfig(),
        TargetName("test-target-3") -> createRandomConfig()
      )
    )
  }

  test("StaticTargetConfigProvider basic functionality") {
    // Test plan: verify that the static config provider always returns the same configuration
    // that was provided during construction, also including basic functionality like:
    //  - isDynamicConfigEnabled always returning false
    //  - watch returning a no-op cancellable
    //  - start being a no-op
    val staticConfigMap = createStaticTargetConfigMap()
    val provider = StaticTargetConfigProvider.create(staticConfigMap, defaultAssignerConfig)

    // Should work even before start() is called (no requirement to call start first).
    val retrievedConfigMap = provider.getLatestTargetConfigMap
    assert(retrievedConfigMap === staticConfigMap)

    // Dynamic config should always be disabled for static provider.
    assert(!provider.isDynamicConfigEnabled)

    // Watch should return a no-op cancellable.
    val callback = new ValueStreamCallback[InternalTargetConfigMap](sec) {
      override protected def onSuccess(value: InternalTargetConfigMap): Unit = {
        // No-op for testing.
      }
    }
    val cancellable = provider.watch(callback)
    // Should not throw when cancelled.
    cancellable.cancel()

    // Start should be a no-op.
    provider.startBlocking(5.seconds)

    // Should still work after start() call.
    val retrievedConfigMapAfterStart = provider.getLatestTargetConfigMap
    assert(retrievedConfigMapAfterStart === staticConfigMap)
  }
}
