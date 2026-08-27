package com.databricks.dicer.client

import java.io.File
import java.net.URI
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.caching.util.{KubernetesClusterUri, RegionUri, WhereAmITestUtils}
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.{AppTarget, Target}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest

class SliceLookupConfigSuite extends DatabricksTest {

  /** A cluster and region URI pair present in the embedded InfraDataModel. */
  private val TEST_CLUSTER_URI: String = "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
  private val TEST_REGION_URI: String = "region:dev/cloud1/public/region1"

  /** A second such pair, for distinguishing configs that captured differing valid URIs. */
  private val OTHER_CLUSTER_URI: String = "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"
  private val OTHER_REGION_URI: String = "region:prod/cloud1/public/region1"

  /** A fixed client UUID, for the variant that differs from the base config in `clientIdOpt`. */
  private val TEST_CLIENT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

  /** Builds a config for `target`, with each field overridable to derive one-field variants. */
  private def createConfig(
      target: Target,
      clientType: ClientType = ClientType.Clerk,
      watchAddress: URI = URI.create("https://localhost:8080"),
      tlsOptionsOpt: Option[TLSOptions] = None,
      clientIdOpt: Option[UUID] = None,
      watchStubCacheTime: FiniteDuration = 5.minutes,
      watchFromDataPlane: Boolean = false,
      alternativeTargetOpt: Option[AppTarget] = None,
      watchRpcTimeout: FiniteDuration = 5.seconds,
      minRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      enableRateLimiting: Boolean = false): SliceLookupConfig =
    SliceLookupConfig(
      clientType = clientType,
      watchAddress = watchAddress,
      tlsOptionsOpt = tlsOptionsOpt,
      target = target,
      clientIdOpt = clientIdOpt,
      watchStubCacheTime = watchStubCacheTime,
      watchFromDataPlane = watchFromDataPlane,
      alternativeTargetOpt = alternativeTargetOpt,
      watchRpcTimeout = watchRpcTimeout,
      minRetryDelay = minRetryDelay,
      maxRetryDelay = maxRetryDelay,
      enableRateLimiting = enableRateLimiting
    )

  /**
   * Builds a config for `target` under a [[LocationConf]] naming `kubernetesClusterUri` and
   * `regionUri`, so tests can vary the captured `clientClusterUriOpt` / `clientRegionUriOpt`, which
   * `apply` reads from the WhereAmI singleton rather than taking as parameters.
   */
  private def createConfigInCluster(
      target: Target,
      kubernetesClusterUri: String,
      regionUri: String): SliceLookupConfig =
    WhereAmITestUtils.withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConf(
        kubernetesClusterUri = kubernetesClusterUri,
        regionUri = regionUri
      )
    ) {
      createConfig(target)
    }

  test("apply captures and validates the sender cluster and region URIs from WhereAmIHelper") {
    // Test plan: Verify that SliceLookupConfig.apply populates clientClusterUriOpt and
    // clientRegionUriOpt from the WhereAmI singleton, validated into their IDM wrapper types.
    // Verify this by setting a LocationConf naming a known cluster and region, building a config,
    // and asserting the captured URIs match the wrappers parsed from those same URIs.

    // Setup: Install a LocationConf naming a known cluster and region.
    WhereAmITestUtils.withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConf(
        kubernetesClusterUri = TEST_CLUSTER_URI,
        regionUri = TEST_REGION_URI
      )
    ) {
      // Verify: A config built under that LocationConf captures the named cluster URI.
      val config: SliceLookupConfig = createConfig(Target("capture-test"))
      assertResult(KubernetesClusterUri.fromUri(TEST_CLUSTER_URI))(config.clientClusterUriOpt)
      assertResult(RegionUri.fromUri(TEST_REGION_URI))(config.clientRegionUriOpt)
    }
  }

  test("apply leaves the URIs empty when WhereAmI is unavailable") {
    // Test plan: Verify that when the location URIs are unset, apply resolves clientClusterUriOpt
    // and clientRegionUriOpt to None rather than throwing. Verify this by installing a LocationConf
    // with empty cluster and region URIs and building a config.

    // Setup: Install a LocationConf whose cluster and region URIs are unset.
    WhereAmITestUtils.withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConf(kubernetesClusterUri = "", regionUri = "")
    ) {
      // Verify: A config built under that LocationConf captures no cluster URI.
      val config: SliceLookupConfig = createConfig(Target("empty-location-test"))
      assertResult(None)(config.clientClusterUriOpt)
      assertResult(None)(config.clientRegionUriOpt)
    }
  }

  test("apply drops cluster and region URIs that are absent from the embedded model") {
    // Test plan: Verify that well-formed cluster and region URIs naming resources absent from the
    // binary's embedded InfraDataModel resolve to None rather than failing. Verify this by
    // installing a LocationConf naming an unknown cluster and an unknown region, then asserting
    // both captured URIs are empty.
    //
    // This covers a theoretical case rather than an expected one: the captured URIs name the
    // cluster and region the pod is itself running in, so the embedded model is essentially
    // guaranteed to include them, as deploying a pod to a cluster the model does not know would be
    // nonsensical. The test pins that the theoretical case degrades to None instead of failing
    // config creation.
    //
    // The region case is worth pinning separately because `WhereAmIHelper.getRegionUri` returns its
    // value unvalidated, so `RegionUri.fromUri` is the only guard on that path.

    // Setup: Install a LocationConf naming a cluster and a region absent from the embedded model.
    WhereAmITestUtils.withLocationConfSingleton(
      LocationConfTestUtils.newTestLocationConf(
        kubernetesClusterUri = "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/99",
        regionUri = "region:dev/cloud1/public/us-west-9"
      )
    ) {
      // Verify: Both unrecognized URIs are dropped rather than failing the build.
      val config: SliceLookupConfig = createConfig(Target("unknown-location-test"))
      assertResult(None)(config.clientClusterUriOpt)
      assertResult(None)(config.clientRegionUriOpt)
    }
  }

  test("equals and hashCode treat configs with identical fields as the same value") {
    // Test plan: Verify that the explicit equals and hashCode agree for two separately built
    // configs whose fields all match, and that equals satisfies the reflexive and null/other-type
    // cases of its contract. Verify this by building the same config twice under one LocationConf,
    // so even the captured cluster URI matches, and comparing them.

    // Setup: Build the same config twice, so the two instances are distinct but field-identical.
    val target: Target = Target("equal-configs-test")
    val config: SliceLookupConfig = createConfigInCluster(target, TEST_CLUSTER_URI, TEST_REGION_URI)
    val identicalConfig: SliceLookupConfig =
      createConfigInCluster(target, TEST_CLUSTER_URI, TEST_REGION_URI)

    // Verify: The two configs are equal, hash equal, and equals honors its remaining contract.
    assertResult(config)(identicalConfig)
    assertResult(config.hashCode())(identicalConfig.hashCode())
    assertResult(config)(config)
    assert(!config.equals(null), "a config should not equal null")
    assert(!config.equals("not a config"), "a config should not equal an unrelated type")
  }

  test("equals distinguishes configs that differ in any single field") {
    // Test plan: Verify that every field participates in equals, so no two configs differing in one
    // field are conflated as one map key. Verify this by building a base config and, for each
    // field, a variant differing only in that field, then asserting each variant is unequal to the
    // base. `target` is included here rather than being covered by the cache's target-level tests
    // because equals is a property of the config alone.

    // Setup: Build a base config and the TLS options one of the variants needs.
    val target: Target = Target("differing-fields-test")
    val baseConfig: SliceLookupConfig = createConfig(target)

    val keystore: File = new File(TestTLSOptions.clientKeystorePath)
    val truststore: File = new File(TestTLSOptions.clientTruststorePath)
    val tlsOptions: TLSOptions =
      TLSOptions.builder.addKeyManager(keystore, keystore).addTrustManager(truststore).build()

    // Setup: Derive one variant per field, each differing from `baseConfig` in exactly that field.
    val variedConfigs: List[(String, SliceLookupConfig)] = List(
      "clientType" -> createConfig(target, clientType = ClientType.Slicelet),
      "watchAddress" -> createConfig(target, watchAddress = URI.create("https://other:9090")),
      "tlsOptionsOpt" -> createConfig(target, tlsOptionsOpt = Some(tlsOptions)),
      "target" -> createConfig(Target("differing-fields-test-other")),
      "clientIdOpt" -> createConfig(target, clientIdOpt = Some(TEST_CLIENT_UUID)),
      "watchStubCacheTime" -> createConfig(target, watchStubCacheTime = 10.minutes),
      "watchFromDataPlane" -> createConfig(target, watchFromDataPlane = true),
      "watchRpcTimeout" -> createConfig(target, watchRpcTimeout = 10.seconds),
      "minRetryDelay" -> createConfig(target, minRetryDelay = 2.seconds),
      "maxRetryDelay" -> createConfig(target, maxRetryDelay = 20.seconds),
      "enableRateLimiting" -> createConfig(target, enableRateLimiting = true)
    )

    // Verify: Every variant is unequal to the base config.
    variedConfigs.foreach {
      case (fieldName: String, variedConfig: SliceLookupConfig) =>
        withClue(s"configs differing in $fieldName should be unequal: ") {
          assert(baseConfig != variedConfig)
        }
    }
  }

  test("equals distinguishes configs that captured different cluster or region URIs") {
    // Test plan: Verify that the captured clientClusterUriOpt and clientRegionUriOpt each
    // participate in equals, which the per-field test above cannot cover because apply captures
    // those fields instead of accepting them. Verify this by building the same config under a
    // LocationConf naming a cluster and region, then under ones that name a different valid cluster
    // or region, and under ones with the cluster URI and region URI unset in turn. The differing
    // valid URIs matter because an equals that compared only emptiness would still pass the unset
    // cases.

    // Setup: Build the same config under a named cluster and region, then under a different valid
    // cluster and region, then with each URI unset.
    val target: Target = Target("differing-location-test")
    val inLocationConfig: SliceLookupConfig =
      createConfigInCluster(target, TEST_CLUSTER_URI, TEST_REGION_URI)
    val otherClusterConfig: SliceLookupConfig =
      createConfigInCluster(target, OTHER_CLUSTER_URI, TEST_REGION_URI)
    val otherRegionConfig: SliceLookupConfig =
      createConfigInCluster(target, TEST_CLUSTER_URI, OTHER_REGION_URI)
    val noClusterConfig: SliceLookupConfig = createConfigInCluster(target, "", TEST_REGION_URI)
    val noRegionConfig: SliceLookupConfig =
      createConfigInCluster(target, TEST_CLUSTER_URI, "")

    // Verify: The captured URIs differ as expected, and each difference alone makes the configs
    // unequal.
    assertResult(KubernetesClusterUri.fromUri(TEST_CLUSTER_URI))(
      inLocationConfig.clientClusterUriOpt
    )
    assertResult(RegionUri.fromUri(TEST_REGION_URI))(inLocationConfig.clientRegionUriOpt)
    assertResult(KubernetesClusterUri.fromUri(OTHER_CLUSTER_URI))(
      otherClusterConfig.clientClusterUriOpt
    )
    assertResult(RegionUri.fromUri(OTHER_REGION_URI))(otherRegionConfig.clientRegionUriOpt)
    assertResult(None)(noClusterConfig.clientClusterUriOpt)
    assertResult(None)(noRegionConfig.clientRegionUriOpt)

    assert(inLocationConfig != otherClusterConfig, "differing cluster URIs should be unequal")
    assert(inLocationConfig != otherRegionConfig, "differing region URIs should be unequal")
    assert(inLocationConfig != noClusterConfig, "an unset cluster URI should be unequal")
    assert(inLocationConfig != noRegionConfig, "an unset region URI should be unequal")
  }

  test("configs are usable as hash map keys") {
    // Test plan: Verify that equals and hashCode are mutually consistent under the hash-based
    // lookup SliceLookupCache relies on: an equal-but-distinct config must retrieve the existing
    // entry rather than adding a second one, and an unequal config must be its own key. Verify this
    // by keying a mutable.Map on two differing configs and then putting an equal-but-distinct
    // config. The map is a mutable.Map, matching the cache, because it hashes the key on every
    // lookup, whereas a small immutable Map compares keys linearly and so would pass even with an
    // inconsistent hashCode.

    // Setup: Build two differing configs plus a distinct instance equal to the first.
    val target: Target = Target("map-key-test")
    val config: SliceLookupConfig = createConfigInCluster(target, TEST_CLUSTER_URI, TEST_REGION_URI)
    val identicalConfig: SliceLookupConfig =
      createConfigInCluster(target, TEST_CLUSTER_URI, TEST_REGION_URI)
    val differingConfig: SliceLookupConfig =
      createConfig(target, clientType = ClientType.Slicelet)

    // Setup: Key a map on the two differing configs, then put the equal-but-distinct config, which
    // must land on the existing entry rather than creating a third one.
    val configLabelMap: mutable.Map[SliceLookupConfig, String] = mutable.Map.empty
    configLabelMap.put(config, "clerk")
    configLabelMap.put(differingConfig, "slicelet")
    configLabelMap.put(identicalConfig, "clerk-again")

    // Verify: The map holds two entries, and the equal config overwrote the first one's value.
    assertResult(2)(configLabelMap.size)
    assertResult(Some("clerk-again"))(configLabelMap.get(config))
    assertResult(Some("slicelet"))(configLabelMap.get(differingConfig))
  }

  test("toString names every field") {
    // Test plan: Verify that toString reports each field by name, since it is what logs and test
    // failure messages surface when a config is unexpected. Verify this by rendering a config with
    // a known cluster URI and comparing against the full expected rendering.

    // Setup: Build a config whose every field has a known value.
    val config: SliceLookupConfig =
      createConfigInCluster(Target("to-string-test"), TEST_CLUSTER_URI, TEST_REGION_URI)

    // Verify: The rendering names every field alongside its value.
    val expectedRendering: String =
      "SliceLookupConfig(clientType=clerk, watchAddress=https://localhost:8080, " +
      "tlsOptionsOpt=None, target=to-string-test, clientIdOpt=None, " +
      "watchStubCacheTime=5 minutes, watchFromDataPlane=false, watchRpcTimeout=5 seconds, " +
      "minRetryDelay=1 second, maxRetryDelay=10 seconds, enableRateLimiting=false, " +
      "alternativeTargetOpt=None, " +
      s"clientClusterUriOpt=Some($TEST_CLUSTER_URI), clientRegionUriOpt=Some($TEST_REGION_URI))"
    assertResult(expectedRendering)(config.toString)
  }
}
