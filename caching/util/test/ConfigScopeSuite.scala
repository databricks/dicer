package com.databricks.caching.util

import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.api.proto.caching.util.test.TestOnlyExampleConfigFieldsP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.testing.DatabricksTest

class ConfigScopeSuite extends DatabricksTest {
  test("Creating `ConfigScope` instances from `ConfigScopeP` messages") {
    // Test plan: Construct `ConfigScope` with various `ConfigScopeP` messages, verify that
    // exceptions are thrown for an invalid `cluster_uri`.

    // Illegal cluster URI.
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope.fromProto(ConfigScopeP().withClusterUri("http://databricks.com"))
    }
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope.fromProto(ConfigScopeP().withClusterUri(""))
    }

    // Unspecified cluster URI.
    assertThrow[IllegalArgumentException]("Cluster URI must be specified.") {
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

  test("Creating `ConfigScope` instances from cluster URIs") {
    // Test plan: Construct `ConfigScope` with cluster URIs, and verify that exceptions are thrown
    // for invalid URIs.

    // Invalid cluster URI.
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope("http://databricks.com")
    }
    assertThrow[IllegalArgumentException]("Cluster URI must start with 'kubernetes-cluster:'") {
      ConfigScope("")
    }

    // Good cluster URIs.
    ConfigScope("kubernetes-cluster:prod/cloud1/public/region2/clustertype2/01")
    ConfigScope("kubernetes-cluster:prod/cloud3/public/region5/clustertype2/01")
    ConfigScope("kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
  }

  test("Test ConfigScope `toString`") {
    // Test plan: sanity check if `ConfigScope.toString` returns string in the expected format.
    val configScope = ConfigScope("kubernetes-cluster:prod/cloud2/public/region6/clustertype2/01")
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

    val configScopeUsWest1: ConfigScope =
      ConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")
    val configScopeUsWest2: ConfigScope =
      ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

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

  test(
    "Validate `findScopeOverride` doesn't throw if invalid shard unrelated to the current " +
    "shard is encountered"
  ) {
    // Test plan: Create various invalid shard, verify that no exception is thrown when unrelated
    // invalid shard is encountered.
    val shardWithBadUri =
      ConfigScopeP().withClusterUri("http://databricks.com")

    // Valid shards.
    val devAwsUsWest1 =
      ConfigScopeP().withClusterUri("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

    // Valid override that we will apply to shardWithUnspecifiedCloud, shardWithMissingRegion
    // and devAwsUsWest1
    val singleValidOverride =
      TestOnlyExampleConfigFieldsP().withIntField(1024).withStringArrayField(Seq("foo", "bar"))

    val overridesWithDuplicates: Seq[(Seq[ConfigScopeP], TestOnlyExampleConfigFieldsP)] = Seq(
      (Seq(shardWithBadUri, devAwsUsWest1), singleValidOverride)
    )

    val shardUsWest1 = ConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01")

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

  test("ConfigScope.fromLocationConf") {
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
      val configScope: ConfigScope = ConfigScope.fromLocationConf(locationConf)
      assert(configScope.clusterUri == testCase)
    }
  }

  test("ConfigScope.fromLocationConf invalid") {
    // Test plan: construct invalid global confs and validate that creating a ConfigScope from them
    // fails with the expected messages.

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
        ConfigScope.fromLocationConf(locationConf)
      }
    }
  }
}
