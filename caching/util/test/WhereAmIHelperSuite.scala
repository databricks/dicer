package com.databricks.caching.util

import com.databricks.caching.util.WhereAmITestUtils.withLocationConfSingleton
import com.databricks.conf.trusted.LocationConf
import com.databricks.conf.trusted.LocationConfTestUtils
import com.databricks.testing.DatabricksTest

import java.net.URI

class WhereAmIHelperSuite extends DatabricksTest {
  test("getClusterUri without LocationConf singleton is None") {
    // Test plan: verify that the URI is empty when the LocationConf singleton isn't set and that
    // getClusterUri does not throw an exception. Verify this by calling getClusterUri and checking
    // that the result is None.
    val uri = WhereAmIHelper.getClusterUri
    assert(uri.isEmpty)
  }

  test("getClusterUri with LocationConf singleton is set to the expected value") {
    // Test plan: verify that the URI is set to the value of the LocationConf location. Verify this
    // by setting the LocationConf singleton to the default test value and calling getClusterUri.

    // Set the LocationConf singleton to the default test value.
    val locationConf: LocationConf = LocationConfTestUtils.newTestLocationConfig()
    withLocationConfSingleton(locationConf) {
      // The default test cluster URI is "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01".
      val expectedUri = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

      // Call getClusterUri and verify that the result matches the expected URI.
      val uri: Option[URI] = WhereAmIHelper.getClusterUri
      assertResult(Some(expectedUri))(uri)
    }
  }

  test("getClusterUri with LocationConf singleton is set to an invalid value") {
    // Test plan: verify that the URI is empty when the LocationConf singleton is set to an invalid
    // value and that getClusterUri does not throw an exception. Verify this by setting the
    // LocationConf singleton to have an empty cluster URI, no cluster URI, or a malformed
    // cluster URI and calling getClusterUri.

    // Set the LocationConf singleton to have an empty cluster URI.
    val invalidLocationEnvVarJson1 =
      s"""
         |{
         | "cloud_provider": "AWS",
         | "cloud_provider_region": "AWS_US_WEST_2",
         | "environment": "DEV",
         | "kubernetes_cluster_type": "GENERAL_CLASSIC",
         | "kubernetes_cluster_uri": "",
         | "region_uri": "region:dev/cloud1/public/region1",
         | "regulatory_domain": "PUBLIC"
         |}
         |""".stripMargin

    val invalidLocationConf1: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> invalidLocationEnvVarJson1)
    )
    withLocationConfSingleton(invalidLocationConf1) {
      // Call getClusterUri and verify that the result is None.
      val uri: Option[URI] = WhereAmIHelper.getClusterUri
      assert(uri.isEmpty)
    }

    // Set the LocationConf singleton to have no cluster URI.
    val invalidLocationEnvVarJson2 =
      s"""
         |{
         | "cloud_provider": "AWS",
         | "cloud_provider_region": "AWS_US_WEST_2",
         | "environment": "DEV",
         | "kubernetes_cluster_type": "GENERAL_CLASSIC",
         | "region_uri": "region:dev/cloud1/public/region1",
         | "regulatory_domain": "PUBLIC"
         |}
         |""".stripMargin

    val invalidLocationConf2: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> invalidLocationEnvVarJson2)
    )
    withLocationConfSingleton(invalidLocationConf2) {
      // Call getClusterUri and verify that the result is None.
      val uri: Option[URI] = WhereAmIHelper.getClusterUri
      assert(uri.isEmpty)
    }

    // Set the LocationConf singleton to have a malformed cluster URI.
    val invalidLocationEnvVarJson3 =
      s"""
         |{
         | "cloud_provider": "AWS",
         | "cloud_provider_region": "AWS_US_WEST_2",
         | "environment": "DEV",
         | "kubernetes_cluster_type": "GENERAL_CLASSIC",
         | "kubernetes_cluster_uri": "malformed-uri",
         | "region_uri": "region:dev/cloud1/public/region1",
         | "regulatory_domain": "PUBLIC"
         |}
         |""".stripMargin

    val invalidLocationConf3: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> invalidLocationEnvVarJson3)
    )
    LocationConf.testSingleton(invalidLocationConf3)
    withLocationConfSingleton(invalidLocationConf3) {
      // Call getClusterUri and verify that the result is None.
      val uri: Option[URI] = WhereAmIHelper.getClusterUri
      assert(uri.isEmpty)
    }
  }

  test("validateCluster with a valid URI does not throw an exception") {
    // Test plan: verify that validateCluster does not throw an exception when given a valid URI.
    // Verify this by calling validateCluster with a valid URI and checking that it does not throw
    // an exception.
    val validUri = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    WhereAmIHelper.validateCluster(validUri)
  }

  test("validateCluster with an empty URI throws an exception") {
    // Test plan: verify that validateCluster throws an exception when given an empty URI. Verify
    // this by calling validateCluster with an empty URI and checking that it throws an exception.

    // The URI is empty.
    val emptyUri = new URI("")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(emptyUri)
    }
  }

  test("validateCluster with an invalid URI throws an exception") {
    // Test plan: verify that validateCluster throws an exception when given an invalid URI. Verify
    // this by calling validateCluster with an invalid URI and checking that it throws an exception.

    // The default test cluster URI is "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01".
    val validUri = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    WhereAmIHelper.validateCluster(validUri)

    // The URI is invalid because it has an invalid scheme.
    val invalidUri1 = new URI("https://www.databricks.com")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(invalidUri1)
    }

    // The URI is invalid because it is not opaque.
    val invalidUri2 = new URI("kubernetes-cluster:/dev/cloud1/public/region1")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(invalidUri2)
    }

    // The URI is invalid because it does not have 6 parts.
    val invalidUri3 = new URI("kubernetes-cluster:test-env/cloud1/public/region1")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(invalidUri3)
    }

    // The URI is invalid because it does not have 6 parts.
    val invalidUri4 = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01/extra")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(invalidUri4)
    }

    // The URI is invalid because it has a fragment.
    val invalidUri5 = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01#fragment")
    intercept[IllegalArgumentException] {
      WhereAmIHelper.validateCluster(invalidUri5)
    }
  }

  test("getRegionUri without LocationConf singleton is None") {
    // Test plan: Verify that the region URI is empty when the LocationConf singleton isn't set
    // and that getRegionUri does not throw an exception. Verify this by calling getRegionUri and
    // checking that the result is None.
    val regionUri: Option[String] = WhereAmIHelper.getRegionUri
    assert(regionUri.isEmpty)
  }

  test("getRegionUri with LocationConf singleton is set to the expected value") {
    // Test plan: Verify that the region URI is set to the value of the LocationConf location.
    // Verify this by setting the LocationConf singleton to the default test value and calling
    // getRegionUri.

    val locationConf: LocationConf = LocationConfTestUtils.newTestLocationConfig()
    withLocationConfSingleton(locationConf) {
      // The default test region URI is "region:dev/cloud1/public/region1".
      val expectedRegionUri: String = "region:dev/cloud1/public/region1"
      val regionUri: Option[String] = WhereAmIHelper.getRegionUri
      assertResult(Some(expectedRegionUri))(regionUri)
    }
  }

  test("getRegionUri with LocationConf singleton is set to an invalid value") {
    // Test plan: Verify that the region URI is empty when the LocationConf singleton is set to
    // an invalid value and that getRegionUri does not throw an exception. Verify this by setting
    // the LocationConf singleton to have an empty region URI or no region URI, and calling
    // getRegionUri in each case.

    // Set the LocationConf singleton to have an empty region URI.
    val emptyRegionUriJson =
      s"""
         |{
         | "cloud_provider": "AWS",
         | "cloud_provider_region": "AWS_US_WEST_2",
         | "environment": "DEV",
         | "kubernetes_cluster_type": "GENERAL_CLASSIC",
         | "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01",
         | "region_uri": "",
         | "regulatory_domain": "PUBLIC"
         |}
         |""".stripMargin

    val emptyRegionUriConf: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> emptyRegionUriJson)
    )
    withLocationConfSingleton(emptyRegionUriConf) {
      val regionUri: Option[String] = WhereAmIHelper.getRegionUri
      assert(regionUri.isEmpty)
    }

    // Set the LocationConf singleton to have no region URI field at all.
    val noRegionUriJson =
      s"""
         |{
         | "cloud_provider": "AWS",
         | "cloud_provider_region": "AWS_US_WEST_2",
         | "environment": "DEV",
         | "kubernetes_cluster_type": "GENERAL_CLASSIC",
         | "kubernetes_cluster_uri": "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01",
         | "regulatory_domain": "PUBLIC"
         |}
         |""".stripMargin

    val noRegionUriConf: LocationConf = LocationConfTestUtils.newTestLocationConfig(
      envMap = Map("LOCATION" -> noRegionUriJson)
    )
    withLocationConfSingleton(noRegionUriConf) {
      val regionUri: Option[String] = WhereAmIHelper.getRegionUri
      assert(regionUri.isEmpty)
    }
  }
}
