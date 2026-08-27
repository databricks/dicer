package com.databricks.dicer.client

import java.io.File
import java.net.URI
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.WhereAmITestUtils
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class SliceLookupCacheSuite extends DatabricksTest with TestName {

  /** The Prometheus registry that the client metrics are recorded to. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** Name of the num-slice-lookups metric. */
  private val NUM_SLICE_LOOKUPS_METRIC_NAME: String = "dicer_client_num_slice_lookups_total"

  /** Name of the cache-target-hit metric. */
  private val CACHE_TARGET_HIT_METRIC_NAME: String =
    "dicer_client_num_slice_lookup_cache_hits_total"

  /** A cluster URI present in the embedded InfraDataModel. */
  private val TEST_CLUSTER_URI: String = "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"

  /**
   * The result of a single [[SliceLookupCache.getOrElseCreate]] call. The outcome fully determines
   * the expected per-call metric deltas.
   */
  private sealed trait CacheOutcome

  private object CacheOutcome {

    /** A brand-new target: a new [[SliceLookup]] is created. */
    case object TargetMiss extends CacheOutcome

    /** Target matched but config differed: a new [[SliceLookup]] is created. */
    case object TargetHitDiffConfig extends CacheOutcome

    /** Target and config both matched: the cached [[SliceLookup]] is returned. */
    case object TargetHitSameConfig extends CacheOutcome
  }

  /**
   * Creates an [[InternalClientConfig]] for testing with the given target and optional overrides.
   *
   * `kubernetesClusterUri` is installed as the WhereAmI singleton's cluster URI while the config
   * is built, because [[SliceLookupConfig.apply]] captures that field from the singleton rather
   * than accepting it as a parameter. It defaults to unset, which the config captures as `None`.
   */
  private def createTestConfig(
      target: Target,
      clientType: ClientType = ClientType.Clerk,
      watchAddress: URI = URI.create("https://localhost:8080"),
      clientIdOpt: Option[UUID] = Some(TEST_CLIENT_UUID),
      tlsOptionsOpt: Option[TLSOptions] = None,
      watchStubCacheTime: FiniteDuration = 5.minutes,
      watchFromDataPlane: Boolean = false,
      watchRpcTimeout: FiniteDuration = 5.seconds,
      minRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      enableRateLimiting: Boolean = false,
      kubernetesClusterUri: String = ""): InternalClientConfig = {
    val sliceLookupConfig: SliceLookupConfig = WhereAmITestUtils.withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConf(kubernetesClusterUri = kubernetesClusterUri)
    ) {
      SliceLookupConfig(
        clientType = clientType,
        watchAddress = watchAddress,
        tlsOptionsOpt = tlsOptionsOpt,
        target = target,
        clientIdOpt = clientIdOpt,
        watchStubCacheTime = watchStubCacheTime,
        watchFromDataPlane = watchFromDataPlane,
        alternativeTargetOpt = None,
        watchRpcTimeout = watchRpcTimeout,
        minRetryDelay = minRetryDelay,
        maxRetryDelay = maxRetryDelay,
        enableRateLimiting = enableRateLimiting
      )
    }
    InternalClientConfig(sliceLookupConfig, subscriberDebugName = s"test-lookup-${target.name}")
  }

  /** Creates a SliceLookup using SliceLookup.createUnstarted. */
  private def createSliceLookup(config: InternalClientConfig): SliceLookup = {
    val sliceLookupConfig: SliceLookupConfig = config.sliceLookupConfig
    val sec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        name = s"SliceLookupCacheSuite-${sliceLookupConfig.target.name}",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME,
        enableContextPropagation = false
      )
    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      clientType = sliceLookupConfig.clientType,
      conf = TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
      ownerName = config.subscriberDebugName
    )
    SliceLookup.createUnstarted(
      sec = sec,
      config = config,
      protoLogger = protoLogger,
      serviceBuilderOpt = None
    )
  }

  /**
   * Calls [[SliceLookupCache.getOrElseCreate]] for `config`, asserts that the per-call metric
   * deltas match `expectedOutcome`, and returns the [[SliceLookup]] returned by the cache so the
   * caller can check instance identity.
   *
   * @param cache the cache under test.
   * @param config the config to look up.
   * @param expectedOutcome the expected result of the `getOrElseCreate`call.
   * @return the [[SliceLookup]] returned by the `getOrElseCreate` call.
   */
  private def verifyGetOrElseCreate(
      cache: SliceLookupCache,
      config: InternalClientConfig,
      expectedOutcome: CacheOutcome): SliceLookup = {
    val target: Target = config.sliceLookupConfig.target
    val clientType: ClientType = config.sliceLookupConfig.clientType

    // Read each metric before the call.
    val numSliceLookupsBefore: Int = getNumSliceLookupsMetricValue(target, clientType)
    val cacheTargetHitSameConfigBefore: Int =
      getCacheTargetHitMetricValue(target, configMatched = true)
    val cacheTargetHitDiffConfigBefore: Int =
      getCacheTargetHitMetricValue(target, configMatched = false)

    val lookup: SliceLookup =
      cache.getOrElseCreate(config.sliceLookupConfig, createSliceLookup(config))

    // Read each metric after the call.
    val numSliceLookupsAfter: Int = getNumSliceLookupsMetricValue(target, clientType)
    val cacheTargetHitSameConfigAfter: Int =
      getCacheTargetHitMetricValue(target, configMatched = true)
    val cacheTargetHitDiffConfigAfter: Int =
      getCacheTargetHitMetricValue(target, configMatched = false)

    // Compute the observed per-metric deltas.
    val numSliceLookupsDelta: Int = numSliceLookupsAfter - numSliceLookupsBefore
    val cacheTargetHitSameConfigDelta: Int =
      cacheTargetHitSameConfigAfter - cacheTargetHitSameConfigBefore
    val cacheTargetHitDiffConfigDelta: Int =
      cacheTargetHitDiffConfigAfter - cacheTargetHitDiffConfigBefore

    // Assert the observed deltas against the values expected for the outcome.
    expectedOutcome match {
      case CacheOutcome.TargetMiss =>
        assert(
          numSliceLookupsDelta == 1,
          s"$expectedOutcome: expected num_slice_lookups delta of 1, got $numSliceLookupsDelta"
        )
        assert(
          cacheTargetHitSameConfigDelta == 0,
          s"$expectedOutcome: expected cache hit (config match) delta of 0, got " +
          s"$cacheTargetHitSameConfigDelta"
        )
        assert(
          cacheTargetHitDiffConfigDelta == 0,
          s"$expectedOutcome: expected cache hit (config mismatch) delta of 0, got " +
          s"$cacheTargetHitDiffConfigDelta"
        )
      case CacheOutcome.TargetHitDiffConfig =>
        assert(
          numSliceLookupsDelta == 1,
          s"$expectedOutcome: expected num_slice_lookups delta of 1, got $numSliceLookupsDelta"
        )
        assert(
          cacheTargetHitSameConfigDelta == 0,
          s"$expectedOutcome: expected cache hit (config match) delta of 0, got " +
          s"$cacheTargetHitSameConfigDelta"
        )
        assert(
          cacheTargetHitDiffConfigDelta == 1,
          s"$expectedOutcome: expected cache hit (config mismatch) delta of 1, got " +
          s"$cacheTargetHitDiffConfigDelta"
        )
      case CacheOutcome.TargetHitSameConfig =>
        assert(
          numSliceLookupsDelta == 0,
          s"$expectedOutcome: expected num_slice_lookups delta of 0, got $numSliceLookupsDelta"
        )
        assert(
          cacheTargetHitSameConfigDelta == 1,
          s"$expectedOutcome: expected cache hit (config match) delta of 1, got " +
          s"$cacheTargetHitSameConfigDelta"
        )
        assert(
          cacheTargetHitDiffConfigDelta == 0,
          s"$expectedOutcome: expected cache hit (config mismatch) delta of 0, got " +
          s"$cacheTargetHitDiffConfigDelta"
        )
    }

    lookup
  }

  /** Returns the current value of the num-slice-lookups metric for the given parameters. */
  private def getNumSliceLookupsMetricValue(target: Target, clientType: ClientType): Int =
    MetricUtils
      .getMetricValue(
        registry,
        NUM_SLICE_LOOKUPS_METRIC_NAME,
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "clientType" -> clientType.getMetricLabel
        )
      )
      .toInt

  /** Returns the current value of the cache-target-hit metric for the given parameters. */
  private def getCacheTargetHitMetricValue(target: Target, configMatched: Boolean): Int =
    MetricUtils
      .getMetricValue(
        registry,
        CACHE_TARGET_HIT_METRIC_NAME,
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "configMatched" -> configMatched.toString
        )
      )
      .toInt

  test("getOrElseCreate creates new SliceLookup for different targets") {
    // Test plan: Verify that different targets result in separate SliceLookup instances.

    val cache = new SliceLookupCache

    // Use short target names to stay within the 63 character Target name limit
    val target1 = Target("diff-targets-test-a")
    val target2 = Target("diff-targets-test-b")

    // config1 and config2 are for different targets.
    val config1: InternalClientConfig = createTestConfig(target1)
    val config2: InternalClientConfig = createTestConfig(target2)

    val lookup1: SliceLookup = verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetMiss)
    val lookup2: SliceLookup = verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetMiss)
    assert(lookup1 ne lookup2, "target1 and target2 SliceLookup instances should be different")

    val lookup1Again: SliceLookup =
      verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetHitSameConfig)
    val lookup2Again: SliceLookup =
      verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetHitSameConfig)
    assert(lookup1 eq lookup1Again, "target1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "target2 lookup should return the same instance")
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

    val lookup1: SliceLookup = verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetMiss)
    val lookup2: SliceLookup =
      verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetHitDiffConfig)
    val lookup3: SliceLookup =
      verifyGetOrElseCreate(cache, config3, CacheOutcome.TargetHitDiffConfig)

    val lookup1Again: SliceLookup =
      verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetHitSameConfig)
    val lookup2Again: SliceLookup =
      verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetHitSameConfig)
    val lookup3Again: SliceLookup =
      verifyGetOrElseCreate(cache, config3, CacheOutcome.TargetHitSameConfig)
    assert(lookup1 eq lookup1Again, "config1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "config2 lookup should return the same instance")
    assert(lookup3 eq lookup3Again, "config3 lookup should return the same instance")
  }

  test("getOrElseCreate creates new lookup for same target with different TLSOptions") {
    // Test plan: Verify that the same target with different tlsOptionsOpt values results in a cache
    // miss and a new SliceLookup being created.

    val cache = new SliceLookupCache
    val target = Target(getSafeName)

    val keystore: File = new File(TestTLSOptions.clientKeystorePath)
    val truststore: File = new File(TestTLSOptions.clientTruststorePath)

    val tlsWithClientIdentity: TLSOptions =
      TLSOptions.builder.addKeyManager(keystore, keystore).addTrustManager(truststore).build()
    val tlsWithoutClientIdentity: TLSOptions =
      TLSOptions.builder.addTrustManager(truststore).build()

    // config1, config2, and config3 differ only in tlsOptionsOpt.
    val config1: InternalClientConfig = createTestConfig(target, tlsOptionsOpt = None)
    val config2: InternalClientConfig =
      createTestConfig(target, tlsOptionsOpt = Some(tlsWithClientIdentity))
    val config3: InternalClientConfig =
      createTestConfig(target, tlsOptionsOpt = Some(tlsWithoutClientIdentity))

    val lookup1: SliceLookup = verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetMiss)
    val lookup2: SliceLookup =
      verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetHitDiffConfig)
    val lookup3: SliceLookup =
      verifyGetOrElseCreate(cache, config3, CacheOutcome.TargetHitDiffConfig)

    val lookup1Again: SliceLookup =
      verifyGetOrElseCreate(cache, config1, CacheOutcome.TargetHitSameConfig)
    val lookup2Again: SliceLookup =
      verifyGetOrElseCreate(cache, config2, CacheOutcome.TargetHitSameConfig)
    val lookup3Again: SliceLookup =
      verifyGetOrElseCreate(cache, config3, CacheOutcome.TargetHitSameConfig)
    assert(lookup1 eq lookup1Again, "config1 lookup should return the same instance")
    assert(lookup2 eq lookup2Again, "config2 lookup should return the same instance")
    assert(lookup3 eq lookup3Again, "config3 lookup should return the same instance")
  }

  test("getOrElseCreate treats each differing SliceLookupConfig field as a config mismatch") {
    // Test plan: Verify that varying each field of SliceLookupConfig results in a config mismatch
    // and a new SliceLookup being created. Verify that these distinct configs coexist in the
    // cache (i.e. each distinct config is its own entry and none evicts another). Using one shared
    // cache, (1) look up every variation and verify each is a config mismatch, and (2) look up
    // every variation again and verify each is now a config match that returns the same cached
    // instance. Varying `target`, `clientIdOpt`, and `tlsOptionsOpt` are intentionally not tested
    // here, and instead exercised in their own dedicated tests above. `target` is omitted because
    // a differing target results in a `TargetMiss` rather than a config mismatch. `clientIdOpt`
    // and `tlsOptionsOpt` are omitted because they are `Option`s, and each require a test that
    // exercises `None` alongside two variants. The base config leaves the sender cluster URI unset,
    // so the `clientClusterUriOpt` variation supplies one to differ from it.

    val cache = new SliceLookupCache
    val target: Target = Target(getSafeName)
    val baseConfig: InternalClientConfig = createTestConfig(target)

    // Setup: Derive one config per field, each whose SliceLookupConfig differs from `baseConfig`'s
    // in exactly that field.
    val variedConfigs: List[(String, InternalClientConfig)] = List(
      "clientType" -> createTestConfig(target, clientType = ClientType.Slicelet),
      "watchAddress" ->
      createTestConfig(target, watchAddress = URI.create("https://other-host:9090")),
      "watchStubCacheTime" -> createTestConfig(target, watchStubCacheTime = 10.minutes),
      "watchFromDataPlane" -> createTestConfig(target, watchFromDataPlane = true),
      "watchRpcTimeout" -> createTestConfig(target, watchRpcTimeout = 10.seconds),
      "minRetryDelay" -> createTestConfig(target, minRetryDelay = 2.seconds),
      "maxRetryDelay" -> createTestConfig(target, maxRetryDelay = 20.seconds),
      "enableRateLimiting" -> createTestConfig(target, enableRateLimiting = true),
      "clientClusterUriOpt" -> createTestConfig(target, kubernetesClusterUri = TEST_CLUSTER_URI)
    )

    val baseLookup: SliceLookup = verifyGetOrElseCreate(cache, baseConfig, CacheOutcome.TargetMiss)

    // The SliceLookup returned by the `getOrElseCreate` call of each SliceLookupConfig variation,
    // keyed by the name of the field being varied.
    val seenLookups: mutable.Map[String, SliceLookup] = mutable.Map.empty

    // First Pass: verify every variation is a config mismatch that create its own SliceLookup.
    variedConfigs.foreach {
      case (fieldName: String, variedConfig: InternalClientConfig) =>
        val variedLookup: SliceLookup =
          verifyGetOrElseCreate(cache, variedConfig, CacheOutcome.TargetHitDiffConfig)
        assert(
          baseLookup ne variedLookup,
          s"differing $fieldName in config should produce a different lookup"
        )
        seenLookups.put(fieldName, variedLookup)
    }

    // Second Pass: verify every variation returns the same instance as the first pass.
    variedConfigs.foreach {
      case (fieldName: String, variedConfig: InternalClientConfig) =>
        val variedLookupAgain: SliceLookup =
          verifyGetOrElseCreate(cache, variedConfig, CacheOutcome.TargetHitSameConfig)
        assert(
          seenLookups(fieldName) eq variedLookupAgain,
          s"varied $fieldName should return the same cached SliceLookup instance"
        )
    }
  }
}
