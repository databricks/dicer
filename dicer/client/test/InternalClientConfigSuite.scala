package com.databricks.dicer.client

import java.net.URI
import scala.concurrent.duration._

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class InternalClientConfigSuite extends DatabricksTest {

  test("InternalClientConfig construction succeeds with valid watchRpcTimeout") {
    // Test plan: Verify that creating an InternalClientConfig with a valid watch RPC timeout
    // succeeds.
    val validTimeouts: Seq[FiniteDuration] =
      Seq(500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds, 1.minute)

    for (timeout <- validTimeouts) {
      val config = InternalClientConfig(
        SliceLookupConfig(
          clientType = ClientType.Clerk,
          watchAddress = URI.create("https://localhost:8080"),
          tlsOptionsOpt = None,
          target = Target("test-service"),
          clientIdOpt = Some(TEST_CLIENT_UUID),
          watchStubCacheTime = 5.minutes,
          watchFromDataPlane = false,
          watchRpcTimeout = timeout,
          minRetryDelay = 1.second,
          maxRetryDelay = 10.seconds,
          enableRateLimiting = false
        )
      )

      assert(config.sliceLookupConfig.watchRpcTimeout == timeout)
    }
  }

  test("InternalClientConfig construction fails with watchRpcTimeout less than 500 milliseconds") {
    // Test plan: Verify that creating InternalClientConfig fails when watchRpcTimeout is
    // less than 500 milliseconds.
    val invalidTimeouts: Seq[FiniteDuration] = Seq(0.seconds, 100.millis, 499.millis)

    for (timeout <- invalidTimeouts) {
      assertThrow[IllegalArgumentException]("at least 500 milliseconds") {
        InternalClientConfig(
          SliceLookupConfig(
            clientType = ClientType.Clerk,
            watchAddress = URI.create("https://localhost:8080"),
            tlsOptionsOpt = None,
            target = Target("test-service"),
            clientIdOpt = Some(TEST_CLIENT_UUID),
            watchStubCacheTime = 5.minutes,
            watchFromDataPlane = false,
            watchRpcTimeout = timeout,
            minRetryDelay = 1.second,
            maxRetryDelay = 10.seconds,
            enableRateLimiting = false
          )
        )
      }
    }
  }
}
