package com.databricks.dicer.client

import java.net.URI
import java.util.UUID
import scala.concurrent.duration._

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class SliceLookupCacheSuite extends DatabricksTest with TestName {

  /**
   * Creates an [[InternalClientConfig]] for testing with the given target and optional overrides.
   */
  private def createTestConfig(
      target: Target,
      clientIdOpt: Option[UUID] = Some(TEST_CLIENT_UUID),
      watchRpcTimeout: FiniteDuration = 5.seconds,
      minRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds): InternalClientConfig = {
    InternalClientConfig(
      SliceLookupConfig(
        clientType = ClientType.Clerk,
        watchAddress = URI.create("https://localhost:8080"),
        tlsOptionsOpt = None,
        target = target,
        clientIdOpt = clientIdOpt,
        watchStubCacheTime = 5.minutes,
        watchFromDataPlane = false,
        watchRpcTimeout = watchRpcTimeout,
        minRetryDelay = minRetryDelay,
        maxRetryDelay = maxRetryDelay,
        enableRateLimiting = false
      )
    )
  }

  /** Creates a SliceLookup using SliceLookup.createUnstarted. */
  private def createSliceLookup(config: SliceLookupConfig): SliceLookup = {
    val subscriberDebugName: String = s"test-lookup-${config.target.name}"
    val sec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        s"SliceLookupCacheSuite-${config.target.name}",
        enableContextPropagation = false
      )
    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      clientType = config.clientType,
      conf = TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
      ownerName = subscriberDebugName
    )
    SliceLookup.createUnstarted(
      sec = sec,
      config = config,
      subscriberDebugName = subscriberDebugName,
      protoLogger = protoLogger,
      serviceBuilderOpt = None
    )
  }

  /**
   * Returns the [[SliceLookup]] for the given [[InternalClientConfig]] from the
   * [[SliceLookupCache]].
   */
  private def getSliceLookup(
      cache: SliceLookupCache,
      config: InternalClientConfig
  ): SliceLookup = {
    val sliceLookupConfig: SliceLookupConfig = config.sliceLookupConfig
    cache.getOrElseCreate(sliceLookupConfig, createSliceLookup(sliceLookupConfig))
  }

  /** Creates a ChangeTracker for the cache hit metric (configMatched=true or false). */
  private def trackCacheHitMetric(
      target: Target,
      configMatched: Boolean
  ): ChangeTracker[Double] = {
    ChangeTracker[Double] { () =>
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

  /** Creates a ChangeTracker for the num slice lookups metric. */
  private def trackNumSliceLookupsMetric(target: Target): ChangeTracker[Double] = {
    ChangeTracker[Double] { () =>
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_client_num_slice_lookups_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> ClientType.Clerk.getMetricLabel
        )
      )
    }
  }

  test("getOrElseCreate creates new SliceLookup for different targets") {
    // Test plan: Verify that different targets result in separate SliceLookup instances.

    val cache = new SliceLookupCache

    // Use short target names to stay within the 63 character Target name limit
    val target1 = Target("diff-targets-test-a")
    val target2 = Target("diff-targets-test-b")

    // config1 and config2 are for different targets.
    val config1: InternalClientConfig = createTestConfig(target1)
    val config2: InternalClientConfig = createTestConfig(target2)

    val lookupCountTracker1: ChangeTracker[Double] = trackNumSliceLookupsMetric(target1)
    val lookupCountTracker2: ChangeTracker[Double] = trackNumSliceLookupsMetric(target2)

    // Track cache hits.
    val cacheHitTracker1: ChangeTracker[Double] =
      trackCacheHitMetric(target1, configMatched = true)
    val cacheHitTracker2: ChangeTracker[Double] =
      trackCacheHitMetric(target2, configMatched = true)

    // Track cache lookups with a config mismatch (target found, but config differs)
    val cacheHitConfigMismatchTracker1: ChangeTracker[Double] =
      trackCacheHitMetric(target1, configMatched = false)
    val cacheHitConfigMismatchTracker2: ChangeTracker[Double] =
      trackCacheHitMetric(target2, configMatched = false)

    val lookup1: SliceLookup = getSliceLookup(cache, config1)
    assert(
      lookupCountTracker1.totalChange() == 1,
      "target1 lookup should create a new SliceLookup"
    )

    val lookup2: SliceLookup = getSliceLookup(cache, config2)
    assert(
      lookupCountTracker2.totalChange() == 1,
      "target2 lookup should create a new SliceLookup"
    )
    assert(lookup1 ne lookup2, "target1 and target2 SliceLookup instances should be different")

    // Verify that requesting each target again returns the cached instance
    val lookup1Again: SliceLookup = getSliceLookup(cache, config1)
    val lookup2Again: SliceLookup = getSliceLookup(cache, config2)
    assert(lookup1 eq lookup1Again, "target1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "target2 lookup should return the same instance")
    assert(
      lookupCountTracker1.totalChange() == 1,
      "No new lookups should be created for cached target1 configs"
    )
    assert(
      lookupCountTracker2.totalChange() == 1,
      "No new lookups should be created for cached target2 configs"
    )

    // Each of the 2 lookups should record a cache hit, no config mismatch.
    assert(cacheHitTracker1.totalChange() == 1, "target1 lookup should record a cache hit")
    assert(cacheHitTracker2.totalChange() == 1, "target2 lookup should record a cache hit")
    assert(
      cacheHitConfigMismatchTracker1.totalChange() == 0,
      "No config mismatch for target1 lookup"
    )
    assert(
      cacheHitConfigMismatchTracker2.totalChange() == 0,
      "No config mismatch for target2 lookup"
    )
  }

  test("getOrElseCreate creates new lookup for same target with different config") {
    // Test plan: Verify that the same target with different InternalClientConfig parameters
    // results in a new SliceLookup being created.

    val cache = new SliceLookupCache
    val target = Target(getSafeName)

    // config1 and config2 are for the same target with different watchRpcTimeout values.
    val config1: InternalClientConfig = createTestConfig(target, watchRpcTimeout = 5.seconds)
    val config2: InternalClientConfig = createTestConfig(target, watchRpcTimeout = 10.seconds)

    val lookupCountTracker: ChangeTracker[Double] = trackNumSliceLookupsMetric(target)
    val cacheHitTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = true)
    val cacheHitConfigMismatchTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = false)

    val lookup1: SliceLookup = getSliceLookup(cache, config1)
    assert(lookupCountTracker.totalChange() == 1, "target lookup should create a new SliceLookup")

    val lookup2: SliceLookup = getSliceLookup(cache, config2)
    assert(lookupCountTracker.totalChange() == 2, "target lookup should create a new SliceLookup")
    assert(
      lookup1 ne lookup2,
      "Different configs should result in different SliceLookup instances"
    )
    assert(cacheHitTracker.totalChange() == 0, "No cache hit for target lookup")
    assert(
      cacheHitConfigMismatchTracker.totalChange() == 1,
      "target lookup should record a config mismatch"
    )

    // Verify that requesting each config again returns the cached instance
    val lookup1Again: SliceLookup = getSliceLookup(cache, config1)
    val lookup2Again: SliceLookup = getSliceLookup(cache, config2)
    assert(
      lookupCountTracker.totalChange() == 2,
      "target lookups should not create new SliceLookups"
    )
    assert(lookup1 eq lookup1Again, "config1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "config1 lookup should return the same instance")

    // Each of the 2 lookups should record a cache hit.
    assert(
      cacheHitTracker.totalChange() == 2,
      "config1 and config2 lookups should record a cache hit"
    )
    assert(
      cacheHitConfigMismatchTracker.totalChange() == 1,
      "config1 and config2 lookups should not record additional config mismatches"
    )
  }

  test("getOrElseCreate caches multiple SliceLookups per target") {
    // Test plan: Verify that a target can have multiple cached lookups for different configs,
    // and each config returns its corresponding lookup on subsequent calls.

    val cache = new SliceLookupCache
    val target = Target(getSafeName)

    // config1, config2, and config3 are for the same target with different watchRpcTimeout values.
    val config1: InternalClientConfig = createTestConfig(target, watchRpcTimeout = 5.seconds)
    val config2: InternalClientConfig = createTestConfig(target, watchRpcTimeout = 10.seconds)
    val config3: InternalClientConfig = createTestConfig(target, watchRpcTimeout = 15.seconds)

    val lookupCountTracker: ChangeTracker[Double] = trackNumSliceLookupsMetric(target)
    val cacheHitTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = true)
    val cacheHitConfigMismatchTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = false)

    // Create lookups for all three configs
    val lookup1: SliceLookup = getSliceLookup(cache, config1)
    val lookup2: SliceLookup = getSliceLookup(cache, config2)
    val lookup3: SliceLookup = getSliceLookup(cache, config3)
    assert(
      lookupCountTracker.totalChange() == 3,
      "target lookups should create a new SliceLookup for each config"
    )

    // config2 and config3 each record a config mismatch (target found, but config differs)
    assert(cacheHitTracker.totalChange() == 0, "target lookups should not record a cache hit")
    assert(
      cacheHitConfigMismatchTracker.totalChange() == 2,
      "target lookups should record a config mismatch for config2 and config3"
    )

    // Verify that re-requesting each config returns the cached instance
    val lookup1Again: SliceLookup = getSliceLookup(cache, config1)
    val lookup2Again: SliceLookup = getSliceLookup(cache, config2)
    val lookup3Again: SliceLookup = getSliceLookup(cache, config3)
    assert(
      lookupCountTracker.totalChange() == 3,
      "No additional lookups should be created for cached configs"
    )
    assert(lookup1 eq lookup1Again, "config1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "config2 lookup should return the same instance")
    assert(lookup3 eq lookup3Again, "config3 lookup should return the same instance")

    // Each of the 3 re-requests should record a cache hit
    assert(
      cacheHitTracker.totalChange() == 3,
      "config1, config2, and config3 lookups should record a cache hit"
    )
  }

  test("getOrElseCreate creates new lookup for same target with different client UUID") {
    // Test plan: Verify that the same target with different clientIdOpt values results in a cache
    // miss and a new SliceLookup being created.

    val cache = new SliceLookupCache
    val target = Target(getSafeName)

    val uuid1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val uuid2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    // config1, config2, and config3 differ only in clientIdOpt.
    val config1: InternalClientConfig = createTestConfig(target, clientIdOpt = Some(uuid1))
    val config2: InternalClientConfig = createTestConfig(target, clientIdOpt = Some(uuid2))
    val config3: InternalClientConfig = createTestConfig(target, clientIdOpt = None)

    val lookupCountTracker: ChangeTracker[Double] = trackNumSliceLookupsMetric(target)
    val cacheHitTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = true)
    val cacheHitConfigMismatchTracker: ChangeTracker[Double] =
      trackCacheHitMetric(target, configMatched = false)

    // Create lookups for all three configs.
    val lookup1: SliceLookup = getSliceLookup(cache, config1)
    val lookup2: SliceLookup = getSliceLookup(cache, config2)
    val lookup3: SliceLookup = getSliceLookup(cache, config3)
    assert(
      lookupCountTracker.totalChange() == 3,
      "Each clientIdOpt variant should create a new SliceLookup"
    )

    // config2 and config3 each record a config mismatch (target found, but config differs).
    assert(cacheHitTracker.totalChange() == 0, "target lookups should not record a cache hit")
    assert(
      cacheHitConfigMismatchTracker.totalChange() == 2,
      "target lookups should record a config mismatch for config2 and config3"
    )

    // Verify that re-requesting each config returns the cached instance.
    val lookup1Again: SliceLookup = getSliceLookup(cache, config1)
    val lookup2Again: SliceLookup = getSliceLookup(cache, config2)
    val lookup3Again: SliceLookup = getSliceLookup(cache, config3)
    assert(
      lookupCountTracker.totalChange() == 3,
      "No additional lookups should be created for cached configs"
    )
    assert(lookup1 eq lookup1Again, "config1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "config2 lookup should return the same instance")
    assert(lookup3 eq lookup3Again, "config3 lookup should return the same instance")

    // Each of the 3 re-requests should record a cache hit.
    assert(
      cacheHitTracker.totalChange() == 3,
      "config1, config2, and config3 lookups should record a cache hit"
    )
  }
}
