package com.databricks.dicer.client

import com.databricks.testing.DatabricksTest

class WatchAddressSuite extends DatabricksTest {

  /** A `fromHostPort` input and the address it should produce. */
  private case class HostPortTestCase(
      hostname: String,
      port: Int,
      schemeOpt: Option[String],
      expected: String)

  gridTest("fromHostPort appends the port to ordinary hostnames")(
    Seq(
      HostPortTestCase(
        "dicer-assigner.example.com",
        8443,
        Some("https"),
        "https://dicer-assigner.example.com:8443"
      ),
      HostPortTestCase("localhost", 80, Some("http"), "http://localhost:80"),
      HostPortTestCase("LOCALHOST", 443, Some("https"), "https://LOCALHOST:443"),
      HostPortTestCase("localhost", 8080, None, "localhost:8080"),
      HostPortTestCase("http://localhost", 9999, None, "http://localhost:9999"),
      HostPortTestCase("[2001:DB8::ABCD]", 8080, Some("http"), "http://[2001:DB8::ABCD]:8080"),
      HostPortTestCase("not a URI", 8080, Some("http"), "http://not a URI:8080")
    )
  ) { testCase: HostPortTestCase =>
    // Test plan: Cover ordinary hostnames and ports with each existing scheme convention,
    // preserving the hostname's spelling.
    val watchAddress: WatchAddress =
      WatchAddress.fromHostPort(testCase.hostname, testCase.port, testCase.schemeOpt)
    assertResult(testCase.expected)(watchAddress.address)
  }

}
