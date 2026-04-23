package com.databricks.dicer.external

import java.net.URI
import java.util.UUID

import scala.concurrent.duration.Duration

import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{AssertionWaiter, MetricUtils, TestUtils}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.trusted.ProjectConf
import com.databricks.conf.{Config, Configs, RichConfig}
import com.databricks.dicer.client.{ClerkImpl, TestClientUtils}
import com.databricks.dicer.client.DicerClientProtoLogger
import com.databricks.dicer.common.{
  Assignment,
  ClientType,
  InternalClientConf,
  InternalDicerTestEnvironment,
  SubscriberHandler,
  SubscriberHandlerMetricUtils
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.Version.LATEST_VERSION
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry

/**
 * Tests for [[SliceLookupCache]] behavior in Clerk creation. These tests verify that SliceLookup
 * instances are properly cached and reused when creating multiple Clerks for the same Target.
 */
private class SingletonSliceLookupSuite extends DatabricksTest with TestName {

  /** Used to generate unique target names. */
  private var targetSequenceNumber: Int = 0

  /** Test environment for integration tests. */
  private val testEnv: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create(
    assignerClusterUri = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
  )

  /** Returns a unique target name each time the method is called. */
  private def getUniqueTargetName: String = {
    targetSequenceNumber += 1
    "target" + targetSequenceNumber.toString
  }

  override def afterAll(): Unit = {
    testEnv.stop()
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

  test("Clerk creation reuses SliceLookup for same Target and same InternalClerkConfig") {
    // Test plan: Verify that creating multiple Clerks with the same (Target, InternalClerkConfig)
    // reuses the same SliceLookup instance, even when different stub factory instances are used.
    // Create multiple Clerks with the same parameters and check that the SliceLookup metric only
    // increments once and cache hits are recorded.

    val target: Target = Target(s"$getUniqueTargetName")

    val rawConf: Config = TestClientUtils
      .createClerkConfig(
        sliceletPort = 1236,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf: ClerkConf = new ProjectConf(Project.TestProject, rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create a number of Clerks with the same (Target, SliceLookupConfig) but different stub
    // factory instances. This verifies that lookup caching works correctly even when stub factories
    // differ (which is the common case in production, where anonymous functions are used).
    val numClerks: Int = 10
    for (_ <- 0 until numClerks) {
      Clerk.create(clerkConf, target, sliceletHostName = "localhost", createStubFactory())
    }

    // Verify: there is only one SliceLookup created for the (Target, SliceLookupConfig).
    assert(sliceLookupTracker.totalChange() == 1)

    // Verify: cache hit metric should be incremented (numClerks - 1) times, because the first
    // Clerk creation doesn't count as a cache hit.
    assert(cacheHitTracker.totalChange() == numClerks - 1)
  }

  test(
    "Clerk creation does not reuse SliceLookup for same Target but different SliceLookupConfigs"
  ) {
    // Test plan: Verify that creating Clerks for the same Target but with different
    // SliceLookupConfig instances creates separate SliceLookup instances. Create 2
    // SliceLookupConfig instances then create multiple Clerks for the same Target for each
    // config. Verify that the SliceLookup metric is incremented twice, cache hits are recorded
    // for each config, and config mismatches are tracked.

    val target: Target = Target(s"$getUniqueTargetName")

    val rawConf1: Config = TestClientUtils
      .createClerkConfig(
        sliceletPort = 1236,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf1: ClerkConf = new ProjectConf(Project.TestProject, rawConf1) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val rawConf2: Config = TestClientUtils
      .createClerkConfig(
        sliceletPort = 1237,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf2: ClerkConf = new ProjectConf(Project.TestProject, rawConf2) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val configMismatchTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = false)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create first Clerk with clerkConf1 - no cache hit, no config mismatch.
    Clerk.create(clerkConf1, target, sliceletHostName = "localhost", createStubFactory())
    assert(cacheHitTracker.totalChange() == 0)
    assert(configMismatchTracker.totalChange() == 0)
    assert(sliceLookupTracker.totalChange() == 1)

    // Create second Clerk with clerkConf1 (same config) - cache hit, no config mismatch.
    Clerk.create(clerkConf1, target, sliceletHostName = "localhost", createStubFactory())
    assert(cacheHitTracker.totalChange() == 1)
    assert(configMismatchTracker.totalChange() == 0)
    assert(sliceLookupTracker.totalChange() == 1)

    // Create third Clerk with clerkConf2 (different config) - config mismatch.
    Clerk.create(clerkConf2, target, sliceletHostName = "localhost", createStubFactory())
    assert(cacheHitTracker.totalChange() == 1)
    assert(configMismatchTracker.totalChange() == 1)
    assert(sliceLookupTracker.totalChange() == 2)

    // Create fourth Clerk with clerkConf2 (same config as third) - cache hit, no config mismatch.
    Clerk.create(clerkConf2, target, sliceletHostName = "localhost", createStubFactory())
    assert(cacheHitTracker.totalChange() == 2)
    assert(configMismatchTracker.totalChange() == 1)
  }

  test("createForShardedStub does not reuse SliceLookup for same Target") {
    // Test plan: Verify that createForShardedStub does not reuse SliceLookup for the same Target.
    // TODO(<internal bug>): Once lookup reuse is enabled for sharded stubs, this test should be updated
    // to verify that the SliceLookup is reused.

    val target: Target = Target(s"$getUniqueTargetName")
    val watchAddress: URI = URI.create("http://localhost:1241")

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create multiple Clerks via createForShardedStub with the same Target and config.
    val numClerks: Int = 5
    for (_ <- 0 until numClerks) {
      ClerkImpl.createForShardedStub(
        target,
        watchAddress,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        tlsOptions = None,
        clientUuidOpt = None
      )
    }

    // Verify: there are 5 SliceLookups created.
    assert(sliceLookupTracker.totalChange() == 5)

    // Verify: no cache hits recorded.
    assert(cacheHitTracker.totalChange() == 0)
  }

  test("createForDataPlaneDirectClerk uses caching for same Target when configured") {
    // Test plan: Verify that createForDataPlaneDirectClerk uses SliceLookupCache when
    // allowMultipleClerksShareLookupPerTarget is true. Multiple Clerks created for the same
    // Target should share the same SliceLookup.

    // Use an AppTarget to satisfy the precondition (AppTargets are fully qualified by instanceId).
    val target: Target =
      Target.createAppTarget(s"$getUniqueTargetName", instanceId = "test-instance")
    val assignerAddress: URI = URI.create("http://localhost:1242")

    val rawConf: Config = TestClientUtils
      .createDataPlaneDirectClerkConfig(
        assignerPort = 1242,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf: ClerkConf = new ProjectConf(Project.TestProject, rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create multiple Clerks via createForDataPlaneDirectClerk with the same Target and config,
    // but different stub factory instances.
    val numClerks: Int = 5
    for (_ <- 0 until numClerks) {
      ClerkImpl.createForDataPlaneDirectClerk(
        secPoolOpt = None,
        clerkConf,
        target,
        assignerAddress,
        createStubFactory()
      )
    }

    // Verify: there is only one SliceLookup created.
    assert(sliceLookupTracker.totalChange() == 1)

    // Verify: cache hits recorded for all but the first Clerk.
    assert(cacheHitTracker.totalChange() == numClerks - 1)
  }

  test("createForMultiClerk never uses caching even for same Target") {
    // Test plan: Verify that createForMultiClerk never uses SliceLookupCache, which means
    // multiple Clerks for the same Target will each have their own SliceLookup.
    // Verify that no caching is used, despite allowMultipleClerksShareLookupPerTarget being true.

    // Use an AppTarget to satisfy the precondition (AppTargets are fully qualified by instanceId).
    val target: Target =
      Target.createAppTarget(s"$getUniqueTargetName", instanceId = "test-instance")
    val assignerAddress: URI = URI.create("http://localhost:1242")

    val rawConf: Config = TestClientUtils
      .createDataPlaneDirectClerkConfig(
        assignerPort = 1242,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf: ClerkConf = new ProjectConf(Project.TestProject, rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
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
    for (_ <- 0 until numClerks) {
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

  test("E2E: Multiple Clerks sharing SliceLookup all receive assignments from Slicelet") {
    // Test plan: Create a Slicelet, then create multiple Clerks that watch the Slicelet with
    // lookup caching enabled. Verify that:
    // 1. All Clerks receive the same assignment from the Slicelet.
    // 2. Only one SliceLookup is created (verified via metrics).
    // 3. The Slicelet sees only one Clerk subscriber (since they share the same SliceLookup).

    val target: Target = Target(getIntegrationTestTargetName)

    // Create and start a Slicelet.
    val slicelet: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    // Wait for the Slicelet to receive an initial assignment.
    AssertionWaiter("Slicelet receives initial assignment").await {
      assert(slicelet.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Create a ClerkConf with lookup caching enabled.
    val rawConf: Config = TestClientUtils
      .createClerkConfig(
        sliceletPort = slicelet.impl.forTest.sliceletPort,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf: ClerkConf = new ProjectConf(Project.TestProject, rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create multiple Clerks with the same config (caching enabled).
    val numClerks: Int = 3
    val clerks: Seq[Clerk[ResourceAddress]] = (0 until numClerks).map { _ =>
      Clerk.create(clerkConf, target, sliceletHostName = "localhost", createStubFactory())
    }

    // Verify: Only one SliceLookup was created for all Clerks.
    assert(sliceLookupTracker.totalChange() == 1)

    // Wait for all Clerks to be ready and receive assignments.
    for (clerk <- clerks) {
      TestUtils.awaitResult(clerk.ready, Duration.Inf)
    }

    // Verify: All Clerks received the same assignment.
    AssertionWaiter("All Clerks receive same assignment").await {
      val assignments: Seq[Option[Assignment]] =
        clerks.map(_.impl.forTest.getLatestAssignmentOpt)
      assert(assignments.forall(_.isDefined))
      val generations = assignments.flatten.map(_.generation)
      assert(generations.distinct.size == 1, s"Expected same generation, got: $generations")
    }

    // Verify: The Slicelet sees only one Clerk subscriber (since they share the same SliceLookup).
    AssertionWaiter("Slicelet sees single Clerk subscriber").await {
      val numClerkSubscribers: Long = SubscriberHandlerMetricUtils
        .getNumClerksByHandler(SubscriberHandler.Location.Slicelet, target, LATEST_VERSION)
      assert(
        numClerkSubscribers == 1,
        s"Expected 1 Clerk subscriber, got $numClerkSubscribers"
      )
    }

    // Clean up: Stop all Clerks.
    for (clerk <- clerks) {
      clerk.forTest.stop()
    }
  }

  /** Returns a unique and safe Target name for integration tests. Keeps name short for validity. */
  private def getIntegrationTestTargetName: String = {
    // Target names have a 63-character limit, so use a short UUID suffix instead of the full name.
    s"lookup-test-${UUID.randomUUID().toString.take(8)}"
  }

  /** Creates a ChangeTracker for the SliceLookup count metric for the given target. */
  private def createSliceLookupCountTracker(target: Target): ChangeTracker[Double] =
    ChangeTracker { () =>
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_client_num_slice_lookups_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> ClientType.Clerk.toString
        )
      )
    }

  /**
   * Creates a ChangeTracker for the dicer_client_num_slice_lookup_cache_hits_total metric for the
   * given target and configMatched value.
   */
  private def createCacheResultTracker(
      target: Target,
      configMatched: Boolean
  ): ChangeTracker[Double] =
    ChangeTracker { () =>
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_client_num_slice_lookup_cache_hits_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "configMatched" -> configMatched.toString
        )
      )
    }
}
