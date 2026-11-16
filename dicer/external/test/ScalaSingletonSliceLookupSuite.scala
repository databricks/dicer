package com.databricks.dicer.external

import com.databricks.dicer.client.testing.{ScalaClerkHarness}

import com.databricks.dicer.client.testing.{ClerkHarness}

import java.net.URI

import scala.collection.mutable

import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.conf.trusted.ProjectConfByName
import com.databricks.conf.{Config, Configs}
import com.databricks.dicer.client.{
  ClerkImpl,
  DicerClientProtoLogger,
  TestClientUtils,
  WatchAddress
}
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.client.featurerollouts.DicerClientFeatureRolloutFlag
import com.databricks.rpc.tls.TLSOptions
import io.prometheus.client.CollectorRegistry

/** Tests for Scala [[SliceLookupCache]] behavior in Clerk creation. */
private class ScalaSingletonSliceLookupSuite extends SingletonSliceLookupSuiteBase {

  /**
   * A cache of [[ClerkConf]]s keyed by Slicelet port and Clerk Caching setting.
   * [[TestClientUtils.createClerkConfig]] generates a random `clientUuid` on every call, so a
   * separately-built config per Clerk would
   * yield a different config and bypass any cached SliceLookups. Reusing one config per key
   * enables multiple Clerks to share the same SliceLookup via [[SliceLookupCache]].
   */
  private val clerkConfMap: mutable.Map[(Int, Boolean), ClerkConf] = mutable.Map.empty

  override protected def createClerk(
      target: Target,
      sliceletPort: Int,
      clerkCachingEnabled: Boolean): ClerkHarness = {
    val clerkConf: ClerkConf = clerkConfMap.getOrElseUpdate(
      (sliceletPort, clerkCachingEnabled), {
        val rawConf: Config = TestClientUtils
          .createClerkConfig(
            sliceletPort = sliceletPort,
            clientTlsFilePathsOpt = None,
            enableRateLimitingOverrideOpt = Some(false)
          )
        val enabledFeatures: Set[String] =
          if (clerkCachingEnabled) Set(DicerClientFeatureRolloutFlag.CLERK_CACHING_FLAG_NAME)
          else Set.empty
        val featureRolloutFlag: DicerClientFeatureRolloutFlag =
          TestClientUtils.createFeatureRolloutFlag(
            enabledFeatures = enabledFeatures
          )
        new ProjectConfByName("test", rawConf) with ClerkConf {
          override def dicerTlsOptions: Option[TLSOptions] = None
          override private[dicer] def isFeatureRolloutFlagEnabled(
              flagName: String,
              target: Target): Boolean = featureRolloutFlag.isEnabled(flagName, target)
        }
      }
    )
    val clerk: Clerk[ResourceAddress] =
      Clerk.create(clerkConf, target, sliceletHostName = "localhost", createStubFactory())
    ScalaClerkHarness.create(clerk)
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }

  /**
   * A stub factory that creates a new function instance each time it is called, but returns the
   * resource address unchanged. This simulates realistic usage where each Clerk creation may use
   * a different anonymous function instance for the stub factory.
   */
  private def createStubFactory(): ResourceAddress => ResourceAddress = {
    (address: ResourceAddress) =>
      address
  }

  // Scala only because `createForShardedStub` is only supported in Scala.
  gridTest("createForShardedStub honors the caching rollout")(Seq(false, true)) {
    clerkCachingEnabled: Boolean =>
      // Test plan: Create multiple ShardedStub Clerks with identical lookup configs. Verify that
      // they share one SliceLookup when the rollout is enabled and have separate lookups otherwise.

      val target: Target = Target(getUniqueTargetName)
      val watchAddress: URI = URI.create("http://localhost:1241")

      val cacheHitTracker: ChangeTracker[Double] =
        createCacheResultTracker(target, configMatched = true)
      val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)
      val enabledFeatures: Set[String] =
        if (clerkCachingEnabled) Set(DicerClientFeatureRolloutFlag.CLERK_CACHING_FLAG_NAME)
        else Set.empty
      val featureRolloutFlag: DicerClientFeatureRolloutFlag =
        TestClientUtils.createFeatureRolloutFlag(enabledFeatures = enabledFeatures)

      // Create multiple Clerks via createForShardedStub with the same Target and config.
      val numClerks: Int = 5
      val clerks: Seq[ClerkImpl[ResourceAddress]] = (0 until numClerks).map { _: Int =>
        ClerkImpl.createForShardedStub(
          target,
          watchAddress,
          TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
          tlsOptions = None,
          clientUuidOpt = None,
          featureRolloutFlag = featureRolloutFlag,
          enableRateLimitingOverrideOpt = Some(false)
        )
      }

      try {
        assert(sliceLookupTracker.totalChange() == (if (clerkCachingEnabled) 1 else numClerks))
        assert(cacheHitTracker.totalChange() == (if (clerkCachingEnabled) numClerks - 1 else 0))
      } finally {
        clerks.foreach { clerk: ClerkImpl[ResourceAddress] =>
          clerk.stop()
        }
      }
  }

  gridTest("createForDirectClerk honors the caching rollout")(Seq(false, true)) {
    clerkCachingEnabled: Boolean =>
      // Test plan: Create two direct Clerks with identical configs. Verify that they share a
      // SliceLookup only when the rollout enables caching for their target.
      val target: Target = Target.createAppTarget(getUniqueTargetName, "instance")
      val enabledFeatures: Set[String] =
        if (clerkCachingEnabled) Set(DicerClientFeatureRolloutFlag.CLERK_CACHING_FLAG_NAME)
        else Set.empty
      val featureRolloutFlag: DicerClientFeatureRolloutFlag =
        TestClientUtils.createFeatureRolloutFlag(enabledFeatures = enabledFeatures)
      val conf: ClerkConf = new ProjectConfByName("test", Configs.empty) with ClerkConf {
        override def dicerTlsOptions: Option[TLSOptions] = None
        override def envVars: Map[String, String] = Map.empty
        override private[dicer] def isFeatureRolloutFlagEnabled(
            flagName: String,
            evaluatedTarget: Target): Boolean = {
          if (flagName == DicerClientFeatureRolloutFlag.CLERK_CACHING_FLAG_NAME) {
            assert(evaluatedTarget == target)
          }
          featureRolloutFlag.isEnabled(flagName, evaluatedTarget)
        }
      }
      val watchAddress: WatchAddress = WatchAddress.fromAddress("http://localhost:1242")
      val lookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)
      val cacheHitTracker: ChangeTracker[Double] =
        createCacheResultTracker(target, configMatched = true)
      val clerks: Seq[ClerkImpl[ResourceAddress]] = (0 until 2).map { _: Int =>
        ClerkImpl.createForDirectClerk(
          secPoolOpt = None,
          conf,
          target,
          watchAddress,
          watchFromDataPlane = true,
          alternativeTargetOpt = None,
          createStubFactory()
        )
      }
      try {
        assert(lookupTracker.totalChange() == (if (clerkCachingEnabled) 1 else 2))
        assert(cacheHitTracker.totalChange() == (if (clerkCachingEnabled) 1 else 0))
      } finally {
        clerks.foreach { clerk: ClerkImpl[ResourceAddress] =>
          clerk.stop()
        }
      }
  }

  // Scala MultiClerk bypasses caching; Rust MultiClerk follows the rollout flag.
  test("createForMultiClerk never uses caching even for same Target") {
    // Test plan: Verify that createForMultiClerk never uses SliceLookupCache, which means
    // multiple Clerks for the same Target will each have their own SliceLookup.
    // Verify that no caching is used, despite the Clerk Caching rollout being enabled.

    // Use an AppTarget to satisfy the precondition (AppTargets are fully qualified by instanceId).
    val target: Target =
      Target.createAppTarget(getUniqueTargetName, instanceId = "test-instance")
    val assignerAddress: WatchAddress = WatchAddress.fromAddress("http://localhost:1242")

    val rawConf: Config = TestClientUtils
      .createDataPlaneDirectClerkConfig(
        assignerPort = 1242,
        clientTlsFilePathsOpt = None
      )
    val featureRolloutFlag: DicerClientFeatureRolloutFlag =
      TestClientUtils.createFeatureRolloutFlag(
        enabledFeatures = Set(DicerClientFeatureRolloutFlag.CLERK_CACHING_FLAG_NAME)
      )
    val clerkConf: ClerkConf = new ProjectConfByName("test", rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
      override private[dicer] def isFeatureRolloutFlagEnabled(
          flagName: String,
          target: Target): Boolean = featureRolloutFlag.isEnabled(flagName, target)
    }

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create a shared proto logger as MultiClerkImpl would.
    val sharedProtoLogger = DicerClientProtoLogger.create(
      clientType = ClientType.Clerk,
      conf = clerkConf,
      ownerName = "singleton-slice-lookup-test"
    )

    // Create multiple Clerks via createForMultiClerk with the same Target and config, but
    // different stub factory instances.
    val numClerks: Int = 5
    for (_: Int <- 0 until numClerks) {
      ClerkImpl.createForMultiClerk(
        secPoolOpt = None,
        protoLogger = sharedProtoLogger,
        clerkConf,
        target,
        assignerAddress,
        createStubFactory()
      )
    }

    // Verify: one SliceLookup created for each Clerk.
    assert(sliceLookupTracker.totalChange() == numClerks)

    // Verify: no cache hits recorded.
    assert(cacheHitTracker.totalChange() == 0)
  }
}
