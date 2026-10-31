package com.databricks.caching.util

import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.api.proto.caching.util.test.TestOnlyExampleConfigFieldsP
import com.databricks.caching.util.TestUtils.{assertThrow, checkComparisons}
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.testing.DatabricksTest

class ConfigScopeSuite extends DatabricksTest {
  test("ConfigScope comparison") {
    // Test plan: Verify that cluster scopes precede instance scopes and that scopes within each
    // kind are ordered lexicographically. Do this by checking an ordered sequence with the shared
    // comparison utility. Since "instance-a" precedes "kubernetes..." lexicographically, this also
    // validates that ClusterConfigScope precedes InstanceConfigScope regardless of the identifiers.
    val orderedScopes: Vector[ConfigScope] = Vector(
      ClusterConfigScope(clusterUri = "kubernetes-cluster:test/a"),
      ClusterConfigScope(clusterUri = "kubernetes-cluster:test/b"),
      InstanceConfigScope(instanceId = "instance-a"),
      InstanceConfigScope(instanceId = "instance-b")
    )
    checkComparisons(orderedScopes)
  }

  test("ConfigScope comparison treats equal-but-distinct scopes as equal") {
    // Test plan: Verify that equal-but-distinct scopes compare as equal in both directions. Do this
    // by constructing separate objects with the same cluster URI or instance ID, checking their
    // distinct identities, and comparing each pair in both directions.
    val clusterScope1: ConfigScope = ClusterConfigScope(clusterUri = "kubernetes-cluster:test/a")
    val clusterScope2: ConfigScope = ClusterConfigScope(clusterUri = "kubernetes-cluster:test/a")
    val instanceScope1: ConfigScope = InstanceConfigScope(instanceId = "instance-a")
    val instanceScope2: ConfigScope = InstanceConfigScope(instanceId = "instance-a")

    // Sanity check they do not just refer to the same instance.
    assert(clusterScope1 ne clusterScope2)
    assert(clusterScope1 == clusterScope2)
    assert(clusterScope1.compare(clusterScope2) == 0)
    assert(clusterScope2.compare(clusterScope1) == 0)

    // Sanity check they do not just refer to the same instance.
    assert(instanceScope1 ne instanceScope2)
    assert(instanceScope1 == instanceScope2)
    assert(instanceScope1.compare(instanceScope2) == 0)
    assert(instanceScope2.compare(instanceScope1) == 0)
  }

  gridTest("ClusterConfigScope proto round trips")(
    Seq(
      "kubernetes-cluster:prod/cloud1/public/region2/clustertype2/01",
      "kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01",
      "kubernetes-cluster:prod/cloud3/public/region5/clustertype2/01"
    )
  ) { clusterUri: String =>
    // Test plan: Verify that cluster scopes serialize to the cluster URI field and round-trip
    // unchanged. Do this by serializing through the ConfigScope interface, checking the field,
    // and parsing it to recover the original scope.
    val scope: ConfigScope = ClusterConfigScope(clusterUri = clusterUri)
    val proto: ConfigScopeP = scope.toProto
    assertResult(ConfigScopeP.Scope.ClusterUri(clusterUri))(proto.scope)
    assertResult(scope)(ConfigScope.fromProto(proto))
  }

  gridTest("InstanceConfigScope proto round trips")(
    Seq("a", "0", "instance-1", "a" * 63)
  ) { instanceId: String =>
    // Test plan: Verify that instance scopes serialize to the instance ID field and round-trip
    // unchanged, including minimum and maximum length IDs. Do this by serializing through the
    // ConfigScope interface, checking the proto field, and parsing it back to the original scope.
    val scope: ConfigScope = InstanceConfigScope(instanceId = instanceId)
    val proto: ConfigScopeP = scope.toProto
    assertResult(ConfigScopeP.Scope.InstanceId(instanceId))(proto.scope)
    assertResult(scope)(ConfigScope.fromProto(proto))
  }

  test("Creating `ClusterConfigScope` instances from `ConfigScopeP` messages") {
    // Test plan: Construct `ClusterConfigScope` with various `ConfigScopeP` messages, verify that
    // exceptions are thrown for an invalid `cluster_uri` and an unset scope.

    // Illegal cluster URI.
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope.fromProto(ConfigScopeP().withClusterUri("http://databricks.com"))
    }
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope.fromProto(ConfigScopeP().withClusterUri(""))
    }

    // Unspecified scope.
    assertThrow[IllegalArgumentException]("Config scope must be specified.") {
      ConfigScope.fromProto(ConfigScopeP())
    }

    // Good message.
    ConfigScope.fromProto(
      ConfigScopeP().withClusterUri("kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
    )
    ConfigScope.fromProto(
      ConfigScopeP().withClusterUri("kubernetes-cluster:prod/cloud1/public/region2/clustertype2/01")
    )
    ConfigScope.fromProto(
      ConfigScopeP().withClusterUri("kubernetes-cluster:prod/cloud3/public/region5/clustertype2/01")
    )
  }

  gridTest("Instance scopes accept valid RFC 1123 labels")(
    Seq("a", "0", "instance-1", "a--b", "a" * 63, "pgpusc1-c1-prod-mt-cloud3-region7")
  ) { instanceId: String =>
    // Test plan: Parse instance IDs including single characters, the maximum length, and the
    // workload portability design example, and verify the scope retains the identifier.
    val scope = InstanceConfigScope(instanceId)
    assert(ConfigScope.fromProto(ConfigScopeP().withInstanceId(instanceId)) == scope)
    assert(scope.toString == instanceId)
  }

  gridTest("Instance scopes reject malformed RFC 1123 labels")(
    Seq(
      "",
      "a" * 64,
      "Instance",
      "instance_id",
      "-instance",
      "instance-",
      "a.b",
      "a/b",
      "a b",
      "a\n",
      "é"
    )
  ) { instanceId: String =>
    // Test plan: Reject malformed identifiers both when constructing the wrapper directly and
    // when parsing protos, including invalid length, case, punctuation, and non-ASCII characters.
    assertThrow[IllegalArgumentException]("Instance ID is invalid") {
      InstanceConfigScope(instanceId)
    }
    assertThrow[IllegalArgumentException]("Instance ID is invalid") {
      ConfigScope.fromProto(ConfigScopeP().withInstanceId(instanceId))
    }
  }

  test("Instance lookup keeps scopes distinct and ignores malformed unrelated scopes") {
    // Test plan: Look up a cluster and two instances in a mixed scope list containing malformed
    // scopes. Verify each instance ID selects its own override and absent instances do not fall
    // back to the cluster override.
    val cluster = ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val instance1 = InstanceConfigScope("instance-1")
    val instance2 = InstanceConfigScope("instance-2")
    val clusterOverride = TestOnlyExampleConfigFieldsP().withIntField(1000)
    val instance1Override = TestOnlyExampleConfigFieldsP().withIntField(2000)
    val instance2Override = TestOnlyExampleConfigFieldsP().withIntField(3000)
    val overrides: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      Seq(ConfigScopeP().withClusterUri(cluster.clusterUri)) -> clusterOverride,
      Seq(
        ConfigScopeP(),
        ConfigScopeP().withClusterUri("bad-uri"),
        ConfigScopeP().withInstanceId("bad_instance"),
        ConfigScopeP().withInstanceId(instance1.instanceId)
      ) -> instance1Override,
      Seq(ConfigScopeP().withInstanceId(instance2.instanceId)) -> instance2Override
    )
    assert(ConfigScope.findScopeOverride(cluster, overrides).contains(clusterOverride))
    assert(ConfigScope.findScopeOverride(instance1, overrides).contains(instance1Override))
    assert(ConfigScope.findScopeOverride(instance2, overrides).contains(instance2Override))
    assert(ConfigScope.findScopeOverride(InstanceConfigScope("instance-3"), overrides).isEmpty)
  }

  test("Instance lookup rejects duplicate matching overrides") {
    // Test plan: Duplicate one instance across overrides and verify only its lookup fails.
    val instance = InstanceConfigScope("instance-1")
    val duplicatedScopeP: ConfigScopeP = ConfigScopeP().withInstanceId(instance.instanceId)
    val config = TestOnlyExampleConfigFieldsP().withIntField(1000)
    val overrideEntry: (Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP) =
      Seq(duplicatedScopeP) -> config
    assertThrow[IllegalArgumentException]("At most one override can be defined for the scope") {
      ConfigScope.findScopeOverride(instance, Seq(overrideEntry, overrideEntry))
    }
    assert(
      ConfigScope
        .findScopeOverride(InstanceConfigScope("instance-2"), Seq(overrideEntry, overrideEntry))
        .isEmpty
    )
  }

  test("Creating `ClusterConfigScope` instances from cluster URIs") {
    // Test plan: Construct `ClusterConfigScope` with cluster URIs, and verify that exceptions are
    // thrown for invalid URIs.

    // Invalid cluster URI.
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ClusterConfigScope("http://databricks.com")
    }
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ClusterConfigScope("")
    }

    // Good cluster URIs.
    ClusterConfigScope("kubernetes-cluster:prod/cloud1/public/region2/clustertype2/01")
    ClusterConfigScope("kubernetes-cluster:prod/cloud3/public/region5/clustertype2/01")
    ClusterConfigScope("kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
  }

  test("Test ClusterConfigScope `toString`") {
    // Test plan: sanity check if `ClusterConfigScope.toString` returns string in the expected
    // format.
    val configScope = ClusterConfigScope("kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
    assert(configScope.toString == "kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
  }

  test("Validate `findScopeOverride` throws if config scope is duplicated") {
    // Test plan: Verify IllegalArgumentException exception is thrown when duplicated config scopes
    // are encountered in the overrides.
    // Note that we only care about the shard in which we are currently running, so even if other
    // config scopes have duplicates, there will be no exception thrown.
    val firstScopeProto =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")
    val secondScopeProto =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

    // Override that we will apply to firstScopeProto.
    val override1 =
      TestOnlyExampleConfigFieldsP().withIntField(1024).withStringArrayField(Seq("foo", "bar"))

    // Override that we will apply to both firstScopeProto and secondScopeProto.
    val override2 =
      TestOnlyExampleConfigFieldsP().withIntField(2048).withStringArrayField(Seq("foo"))

    val firstScope: ClusterConfigScope =
      ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")
    val secondScope: ClusterConfigScope =
      ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

    // Config scope `firstScopeProto` is duplicated in different overrides.
    val overridesWithDuplicates: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      (Seq(firstScopeProto), override1),
      (Seq(firstScopeProto, secondScopeProto), override2)
    )

    // `firstScope` is in both overrides, expect exception to be thrown.
    assertThrow[IllegalArgumentException]("At most one override can be defined") {
      ConfigScope.findScopeOverride(
        firstScope,
        overridesWithDuplicates
      )
    }

    // `secondScope` is not duplicated, so no exception should be thrown.
    val overrideOpt1: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(
        secondScope,
        overridesWithDuplicates
      )
    assert(overrideOpt1.isDefined)
    assert(overrideOpt1.get.getIntField == 2048)
    assert(overrideOpt1.get.stringArrayField == Seq("foo"))

    // No exception should be thrown if no shard for the override matches the current shard.
    val overrideOpt2: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(secondScope, Seq())
    assert(overrideOpt2.isEmpty)
  }

  test("Validate `findScopeOverride` ignores unrelated invalid cluster and instance scopes") {
    // Test plan: Create invalid cluster and instance scopes, verify that no exception is thrown
    // when unrelated invalid scopes are encountered.
    val shardWithBadUri =
      ConfigScopeP().withClusterUri("http://databricks.com")
    val instanceWithBadId: ConfigScopeP = ConfigScopeP().withInstanceId("bad_instance")

    // Valid shards.
    val validScopeProto =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

    // Valid override that we will apply to shardWithUnspecifiedCloud, shardWithMissingRegion
    // and validScopeProto.
    val singleValidOverride =
      TestOnlyExampleConfigFieldsP().withIntField(1024).withStringArrayField(Seq("foo", "bar"))

    val overridesWithDuplicates: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      (Seq(shardWithBadUri, instanceWithBadId, validScopeProto), singleValidOverride)
    )

    val validScope = ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

    val overrideOpt: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(
        validScope,
        overridesWithDuplicates
      )
    assert(overrideOpt.isDefined)
    assert(overrideOpt.get.getIntField == 1024)
    assert(overrideOpt.get.stringArrayField == Seq("foo", "bar"))
  }

  /** Creates a [[LocationConf]] with the given cluster URI populated. */
  private def createLocationConf(clusterUri: Option[String]): LocationConf = {
    LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> (clusterUri match {
        case Some(uri) => s"""{"kubernetes_cluster_uri": "$uri"}"""
        case None => "{}"
      }))
    )
  }

  test("ClusterConfigScope.fromLocationConf") {
    // Test plan: construct global conf with real k8s cluster URIs. Validate that the expected
    // config scopes are constructed.
    val testCases = Seq(
      "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01",
      "kubernetes-cluster:prod/cloud2/public/region4/clustertype2/01",
      "kubernetes-cluster:prod/cloud3/public/region7/clustertype2/01",
      "kubernetes-cluster:prod/cloud3/public/region5/clustertype2/01"
    )
    for (testCase: String <- testCases) {
      val locationConf: LocationConf = createLocationConf(clusterUri = Some(testCase))
      val configScope: ClusterConfigScope = ClusterConfigScope.fromLocationConf(locationConf)
      assert(configScope.clusterUri == testCase)
    }
  }

  test("ClusterConfigScope.fromLocationConf invalid") {
    // Test plan: construct invalid global confs and validate that creating a ClusterConfigScope
    // from them fails with the expected messages.

    case class TestCase(clusterUri: Option[String], expectedMessage: String)
    val testCases = Seq[TestCase](
      TestCase(
        clusterUri = Some("http://databricks.com"),
        expectedMessage = "Cluster URI must start with 'kubernetes-cluster:'"
      ),
      TestCase(
        clusterUri = Some(""),
        expectedMessage = "Cluster URI must start with 'kubernetes-cluster:'"
      ),
      TestCase(
        clusterUri = None,
        expectedMessage = "LocationConf does not include a cluster URI"
      )
    )
    for (testCase: TestCase <- testCases) {
      val locationConf: LocationConf = createLocationConf(testCase.clusterUri)
      assertThrow[IllegalArgumentException](testCase.expectedMessage) {
        ClusterConfigScope.fromLocationConf(locationConf)
      }
    }
  }
}
