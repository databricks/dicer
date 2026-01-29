package com.databricks.dicer.client

import java.net.URI

import scala.concurrent.duration._

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest

class InternalClientConfigSuite extends DatabricksTest {

  test("InternalClientConfig construction succeeds with valid watchRpcTimeout") {
    // Test plan: Verify that creating an InternalClientConfig with a valid watch RPC timeout
    // succeeds.
    val validTimeouts: Seq[FiniteDuration] =
      Seq(1.second, 2.seconds, 5.seconds, 10.seconds, 1.minute)

    for (timeout <- validTimeouts) {
      val config = InternalClientConfig(
        clientType = ClientType.Clerk,
        watchAddress = URI.create("https://localhost:8080"),
        tlsOptionsOpt = None,
        target = Target("test-service"),
        watchStubCacheTime = 5.minutes,
        watchFromDataPlane = false,
        watchRpcTimeout = timeout,
        minRetryDelay = 1.second,
        maxRetryDelay = 10.seconds,
        enableRateLimiting = false
      )

      assert(config.watchRpcTimeout == timeout)
    }
  }

  test("InternalClientConfig construction fails with watchRpcTimeout less than 1 second") {
    // Test plan: Verify that creating InternalClientConfig fails when watchRpcTimeout is
    // less than 1 second.
    val invalidTimeouts: Seq[FiniteDuration] = Seq(0.seconds, 500.millis, 999.millis)

    for (timeout <- invalidTimeouts) {
      assertThrow[IllegalArgumentException]("at least 1 second") {
        InternalClientConfig(
          clientType = ClientType.Clerk,
          watchAddress = URI.create("https://localhost:8080"),
          tlsOptionsOpt = None,
          target = Target("test-service"),
          watchStubCacheTime = 5.minutes,
          watchFromDataPlane = false,
          watchRpcTimeout = timeout,
          minRetryDelay = 1.second,
          maxRetryDelay = 10.seconds,
          enableRateLimiting = false
        )
      }
    }
  }
}
