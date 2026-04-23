package com.databricks.dicer.client

import java.net.URI

import com.databricks.testing.DatabricksTest

class WatchAddressHelperSuite extends DatabricksTest {

  test("getAssignerURI constructs HTTPS URI for regular hostname") {
    // Test plan: Verify that regular hostnames are converted to https:// URIs with the port.
    val hostname = "dicer-assigner.example.com"
    val port = 8443
    val result = WatchAddressHelper.getAssignerURI(hostname, port)
    assert(result == URI.create(s"https://$hostname:$port"))
  }

  test("getAssignerURI handles localhost") {
    // Test plan: Verify that localhost is correctly converted to an HTTPS URI.
    val hostname = "localhost"
    val port = 9090
    val result = WatchAddressHelper.getAssignerURI(hostname, port)
    assert(result == URI.create(s"https://$hostname:$port"))
  }

  test("getAssignerURI handles various port numbers") {
    // Test plan: Verify that different port numbers are correctly included in the URI.
    val hostname = "watch-server-host"
    val testCases = Seq(80, 443, 8080, 8443, 9999)

    for (port <- testCases) {
      val result = WatchAddressHelper.getAssignerURI(hostname, port)
      assert(result == URI.create(s"https://$hostname:$port"))
    }
  }
}
