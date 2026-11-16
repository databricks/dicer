package com.databricks.caching.util

import com.databricks.caching.util.TestUtils.loadTestData
import com.databricks.caching.util.test.KubernetesClusterUriTestDataP
import com.databricks.testing.DatabricksTest

class KubernetesClusterUriSuite extends DatabricksTest {

  private val TEST_DATA: KubernetesClusterUriTestDataP =
    loadTestData[KubernetesClusterUriTestDataP](
      "caching/util/test/data/kubernetes_cluster_uri_test_data.textproto"
    )

  gridTest("fromUri accepts well-formed cluster URIs")(TEST_DATA.validUris) { uri: String =>
    // Test plan: Verify that a well-formed cluster URI in the shared test data parses successfully
    // and round-trips back to its string form.
    val parsed: Option[KubernetesClusterUri] = KubernetesClusterUri.fromUri(uri)
    assert(parsed.isDefined, s"$uri should parse")
    assertResult(uri)(parsed.get.uri)
    assertResult(uri)(parsed.get.toString)
  }

  gridTest("fromUri rejects invalid URIs")(TEST_DATA.invalidUris) { uri: String =>
    // Test plan: Verify that a value in the shared invalid set (wrong or missing scheme, or
    // structurally malformed) resolves to None rather than throwing.
    assertResult(None)(KubernetesClusterUri.fromUri(uri))
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
