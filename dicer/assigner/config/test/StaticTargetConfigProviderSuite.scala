package com.databricks.dicer.assigner.config

import scala.concurrent.Promise
import scala.concurrent.duration._
import com.databricks.caching.util.{SequentialExecutionContext, TestUtils, ValueStreamCallback}
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

  test("TargetConfigProviderFactory provides static config behavior") {
    // Test plan: verify that the static config provider always returns the same configuration
    // that was provided during construction, also including basic functionality like:
    //  - isDynamicConfigEnabled always returning false
    //  - watch delivering the current config once
    val staticConfigMap = createStaticTargetConfigMap()
    val provider = TargetConfigProviderFactory.createBlocking(
      staticConfigMap,
      defaultAssignerConfig,
      5.seconds
    )

    val retrievedConfigMap = provider.getLatestTargetConfigMap
    assert(retrievedConfigMap === staticConfigMap)

    // Dynamic config should always be disabled for static provider.
    assert(!provider.isDynamicConfigEnabled)

    // Watch should deliver the current config once and return a cancellable.
    val delivered: Promise[InternalTargetConfigMap] = Promise()
    val callback = new ValueStreamCallback[InternalTargetConfigMap](sec) {
      override protected def onSuccess(value: InternalTargetConfigMap): Unit = {
        delivered.success(value)
      }
    }
    val cancellable = provider.watch(callback)
    assert(TestUtils.awaitResult(delivered.future, Duration.Inf) === staticConfigMap)
    // Should not throw when cancelled.
    cancellable.cancel()
  }
}
