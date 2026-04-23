package com.databricks.dicer.client

import com.databricks.caching.util.TestUtils

import scala.concurrent.duration._

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.AssertMacros.ifail
import com.databricks.conf.Configs
import com.databricks.conf.RichConfig

import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  LoadWatcherTargetConfig
}
import com.databricks.dicer.assigner.conf.DicerAssignerConf

import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver
import com.databricks.dicer.assigner.config.{
  ChurnConfig,
  InternalTargetConfig,
  InternalTargetConfigMap
}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  ClientRequest,
  Generation,
  InternalDicerTestEnvironment,
  SliceletData,
  TestAssigner
}
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import com.databricks.rpc.RequestHeaders

class TopKeysSuite extends DatabricksTest {

  private val assignerConfig = {
    // Shorten the watch RPC timeout to speed up the test.
    val default = TestAssigner.Config.create()
    TestAssigner.Config.create(
      assignerConf = new DicerAssignerConf(
        default.assignerConf.rawConfig.merge(
          Configs.parseMap(
            "databricks.dicer.internal.cachingteamonly" +
            ".watchServerSuggestedRpcTimeoutMillis" -> 1000,
            "databricks.dicer.assigner.useTopKeys" -> true
          )
        )
      )
    )
  }

  private val targetConfigMap: InternalTargetConfigMap = createTargetConfigMap()

  /** The test environment used for all tests. */
  private val testEnv = InternalDicerTestEnvironment.create(assignerConfig, targetConfigMap)

  override def afterAll(): Unit = {
    testEnv.testAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT)
  }

  /**
   * Create an [[InternalTargetConfigMap]] appropriate for tests, which will cause load balancing
   * based on top keys.
   */
  private def createTargetConfigMap(): InternalTargetConfigMap = {
    val target = Target("top-keys-split")
    val targetName = TargetName.forTarget(target)
    // Specify a config with LoadWatcher minimum duration of 1 second (don't wait before
    // incorporating load reports), zero churn penalty (we are testing hot keys rebalancing, not
    // churn minimization), and max load hint of 1 - this is an arbitrary value that we will
    // exceed for certain keys in the test.
    val loadWatcherConfig =
      LoadWatcherTargetConfig(minDuration = 1.second, maxAge = 5.minutes, useTopKeys = true)
    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.second, // Use a shorter interval to speed up the test.
      ChurnConfig.ZERO_PENALTY,
      LoadBalancingMetricConfig(maxLoadHint = 1)
    )
    val config = InternalTargetConfig.forTest.DEFAULT.copy(
      loadWatcherConfig = loadWatcherConfig,
      loadBalancingConfig = loadBalancingConfig
    )
    InternalTargetConfigMap.create(configScopeOpt = None, Map(targetName -> config))
  }

  test("Hot keys cause Slice split") {
    // Test plan: Verify that if we have 2 hot keys that are close together, the Slice will be split
    // and they will be assigned to different Slicelets. First create a single Slicelet and wait for
    // the initial assignment. Then increment load for 2 specific keys and wait for the Assigner to
    // receive the load report. Then create another Slicelet, and verify that exactly one of the
    // two hot keys has been reassigned to the new Slicelet.
    val target = Target("top-keys-split")
    val slicelet = testEnv.createSlicelet(target)
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Wait for slice assignment").await {
      val lowerBound: Generation =
        TestUtils.awaitResult(slicelet.impl.forTest.getListenerGenerationLowerBound, Duration.Inf)
      assert(lowerBound > Generation.EMPTY)
      lowerBound
    }

    // Increment load for 2 keys that are close together. Ensure we exceed our `maxLoadHint` of 1
    // even if there is some exponential weighting.
    val h1 = slicelet.createHandle(0x42)
    val h2 = slicelet.createHandle(0x43)
    try {
      assert(h1.isAssignedContinuously)
      h1.incrementLoadBy(10)
      assert(h2.isAssignedContinuously)
      h2.incrementLoadBy(10)
    } finally {
      h1.close()
      h2.close()
    }

    AssertionWaiter("Wait for load report").await {
      val watchRequestOpt: Option[ClientRequest] =
        testEnv.testAssigner.getLatestSliceletWatchRequest(target, slicelet.impl.squid).map {
          case (_: RequestHeaders, req: ClientRequest) => req
        }
      assert(watchRequestOpt.isDefined)
      watchRequestOpt.get.subscriberData match {
        case sliceletData: SliceletData =>
          assert(sliceletData.attributedLoads.exists(_.topKeys.nonEmpty))
        case data => ifail(s"Expected slicelet data, but got $data")
      }
    }

    // Create another slicelet - this should trigger a new assignment and the assigner will
    // eventually rebalance load to it.
    val slicelet2 = testEnv.createSlicelet(target)
    slicelet2.start(selfPort = 1234, listenerOpt = None)

    // Verify that the hot keys are split between the two slicelets.
    AssertionWaiter("Wait for hot keys to be split").await {
      val h1b = slicelet.createHandle(0x42)
      val h2b = slicelet.createHandle(0x43)
      try {
        val k1Assigned = h1b.isAssignedContinuously
        val k2Assigned = h2b.isAssignedContinuously
        // Exactly one of the keys should be assigned to the slicelet, the other should be
        // rebalanced to the new slicelet.
        assert(k1Assigned != k2Assigned)
      } finally {
        h1b.close()
        h2b.close()
      }
    }
  }
}
