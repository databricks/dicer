package com.databricks.caching.util

import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.api.proto.caching.util.test.TestOnlyExampleConfigFieldsP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.testing.DatabricksTest

class ConfigScopeSuite extends DatabricksTest {
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
    val devAwsUsWest1 =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")
    val devAwsUsWest2 =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

    // Override that we will apply to devAwsUsWest1.
    val override1 =
      TestOnlyExampleConfigFieldsP().withIntField(1024).withStringArrayField(Seq("foo", "bar"))

    // Override that we will apply to both devAwsUsWest1 and devAwsUsWest2
    val override2 =
      TestOnlyExampleConfigFieldsP().withIntField(2048).withStringArrayField(Seq("foo"))

    val configScopeUsWest1: ClusterConfigScope =
      ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")
    val configScopeUsWest2: ClusterConfigScope =
      ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

    // Config scope `devAwsUsWest1` is duplicated in different overrides.
    val overridesWithDuplicates: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      (Seq(devAwsUsWest1), override1),
      (Seq(devAwsUsWest1, devAwsUsWest2), override2)
    )

    // `configScopeUsWest1` is in both overrides, expect exception to be thrown.
    assertThrow[IllegalArgumentException]("At most one override can be defined") {
      ConfigScope.findScopeOverride(
        configScopeUsWest1,
        overridesWithDuplicates
      )
    }

    // 'shardUsWest2' is not duplicated, so no exception should be thrown.
    val overrideOpt1: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(
        configScopeUsWest2,
        overridesWithDuplicates
      )
    assert(overrideOpt1.isDefined)
    assert(overrideOpt1.get.getIntField == 2048)
    assert(overrideOpt1.get.stringArrayField == Seq("foo"))

    // No exception should be thrown if no shard for the override matches the current shard.
    val overrideOpt2: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(configScopeUsWest2, Seq())
    assert(overrideOpt2.isEmpty)
  }

  test("Validate `findScopeOverride` ignores unrelated invalid cluster and instance scopes") {
    // Test plan: Create invalid cluster and instance scopes, verify that no exception is thrown
    // when unrelated invalid scopes are encountered.
    val shardWithBadUri =
      ConfigScopeP().withClusterUri("http://databricks.com")
    val instanceWithBadId: ConfigScopeP = ConfigScopeP().withInstanceId("bad_instance")

    // Valid shards.
    val devAwsUsWest1 =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

    // Valid override that we will apply to shardWithUnspecifiedCloud, shardWithMissingRegion
    // and devAwsUsWest1
    val singleValidOverride =
      TestOnlyExampleConfigFieldsP().withIntField(1024).withStringArrayField(Seq("foo", "bar"))

    val overridesWithDuplicates: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      (Seq(shardWithBadUri, instanceWithBadId, devAwsUsWest1), singleValidOverride)
    )

    val shardUsWest1 = ClusterConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

    val overrideOpt: Option[TestOnlyExampleConfigFieldsP] =
      ConfigScope.findScopeOverride(
        shardUsWest1,
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
