package com.databricks.caching.util

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.caching.util.test.KubernetesClusterUriTestDataP
import com.databricks.testing.DatabricksTest

class KubernetesClusterUriSuite extends DatabricksTest {

  private val TEST_DATA: KubernetesClusterUriTestDataP =
    loadTestData[KubernetesClusterUriTestDataP](
      "caching/util/test/data/kubernetes_cluster_uri_test_data.textproto"
    )

  /** Returns the current `caching_util_idm_uri_parse_total` count for clusters with `status`. */
  private def parseCount(status: String): Double =
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "caching_util_idm_uri_parse_total",
      Map("uriType" -> "kubernetesCluster", "status" -> status)
    )

  gridTest("fromUri accepts cluster URIs present in the model")(TEST_DATA.validUris) {
    uri: String =>
      // Test plan: Verify that a cluster URI in the shared test data (present in the embedded
      // InfraDataModel) parses successfully, round-trips back to its string form, and records
      // metrics.
      val successTracker = ChangeTracker(() => parseCount("success"))
      val failureTracker = ChangeTracker(() => parseCount("failure"))
      val parsed: Option[KubernetesClusterUri] = KubernetesClusterUri.fromUri(uri)
      assert(parsed.isDefined, s"$uri should parse")
      assertResult(uri)(parsed.get.uri)
      assertResult(uri)(parsed.get.toString)
      assertResult(1.0)(successTracker.totalChange())
      assertResult(0.0)(failureTracker.totalChange())
  }

  gridTest("fromUri rejects invalid URIs")(TEST_DATA.invalidUris) { uri: String =>
    // Test plan: Verify that a value in the shared invalid set (wrong scheme, malformed, or absent
    // from the embedded InfraDataModel) resolves to None rather than throwing, and records metrics.
    val successTracker = ChangeTracker(() => parseCount("success"))
    val failureTracker = ChangeTracker(() => parseCount("failure"))
    assertResult(None)(KubernetesClusterUri.fromUri(uri))
    assertResult(0.0)(successTracker.totalChange())
    assertResult(1.0)(failureTracker.totalChange())
  }

  test("equal cluster URIs compare equal") {
    // Test plan: Verify that two KubernetesClusterUri parsed from the same string are equal and
    // share a hash code. Verify this by parsing the same URI twice and comparing.
    val uri: String = TEST_DATA.validUris.head
    val first: KubernetesClusterUri = KubernetesClusterUri.fromUri(uri).get
    val second: KubernetesClusterUri = KubernetesClusterUri.fromUri(uri).get
    assertResult(first)(second)
    assertResult(first.hashCode())(second.hashCode())
  }
}
