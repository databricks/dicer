package com.databricks.dicer.client

import java.net.URI
import scala.concurrent.duration._

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class InternalClientConfigSuite extends DatabricksTest {

  /** The watch address the base config connects to. */
  private val TEST_WATCH_ADDRESS: URI = URI.create("https://localhost:8080")

  /**
   * A second watch address, for the variant that differs from the base config in
   * `watchAddress`.
   */
  private val TEST_OTHER_WATCH_ADDRESS: URI = URI.create("https://localhost:9090")

  /**
   * Builds a config for `target`, with each field overridable. The watch-connection parameters are
   * the ones the config derives its [[WatchStubManagerConfig]] from.
   */
  private def createConfig(
      target: Target,
      clientType: ClientType = ClientType.Clerk,
      subscriberDebugName: String = "test-subscriber",
      watchAddress: URI = TEST_WATCH_ADDRESS,
      watchFromDataPlane: Boolean = false,
      watchRpcTimeout: FiniteDuration = 5.seconds): InternalClientConfig =
    InternalClientConfig(
      SliceLookupConfig(
        clientType = clientType,
        target = target,
        clientIdOpt = Some(TEST_CLIENT_UUID),
        watchStubCacheTime = 5.minutes,
        alternativeTargetOpt = None,
        watchRpcTimeout = watchRpcTimeout,
        minRetryDelay = 1.second,
        maxRetryDelay = 10.seconds,
        enableRateLimiting = false
      ),
      subscriberDebugName = subscriberDebugName,
      watchAddress = watchAddress,
      tlsOptionsOpt = None,
      watchFromDataPlane = watchFromDataPlane,
      sourceIpOpt = None
    )

  test("InternalClientConfig construction succeeds with valid watchRpcTimeout") {
    // Test plan: Verify that creating an InternalClientConfig with a valid watch RPC timeout
    // succeeds.
    val validTimeouts: Seq[FiniteDuration] =
      Seq(500.millis, 1.second, 2.seconds, 5.seconds, 10.seconds, 1.minute)

    for (timeout <- validTimeouts) {
      val config: InternalClientConfig =
        createConfig(Target("test-service"), watchRpcTimeout = timeout)

      assert(config.sliceLookupConfig.watchRpcTimeout == timeout)
    }
  }

  test("InternalClientConfig construction fails with watchRpcTimeout less than 500 milliseconds") {
    // Test plan: Verify that creating InternalClientConfig fails when watchRpcTimeout is
    // less than 500 milliseconds.
    val invalidTimeouts: Seq[FiniteDuration] = Seq(0.seconds, 100.millis, 499.millis)

    for (timeout <- invalidTimeouts) {
      assertThrow[IllegalArgumentException]("at least 500 milliseconds") {
        createConfig(Target("test-service"), watchRpcTimeout = timeout)
      }
    }
  }

  test("the watch stub manager config is derived from the constructor parameters") {
    // Test plan: Verify that the config derives its WatchStubManagerConfig from its own constructor
    // parameters, for an instance `copy` produces as much as for a directly built one, so the
    // derived clientName can never describe a different client than the lookup half does. Verify
    // this by checking a directly built config, then copying a lookup-identity field and a
    // watch-connection field in turn.

    // Setup: Build a Clerk config, plus the Slicelet lookup config to copy onto it.
    val clerkConfig: InternalClientConfig = createConfig(Target("derive-test"))
    val sliceletLookupConfig: SliceLookupConfig =
      createConfig(Target("derive-test-other"), clientType = ClientType.Slicelet).sliceLookupConfig

    // Verify: The directly built config derives from its own parameters.
    assertResult("dicer-clerk-derive-test")(clerkConfig.watchStubManagerConfig.clientName)
    assertResult(TEST_WATCH_ADDRESS)(clerkConfig.watchStubManagerConfig.watchAddress)

    // Verify: Copying the lookup config re-derives the manager config's clientName from it.
    assertResult("dicer-slicelet-derive-test-other")(
      clerkConfig.copy(sliceLookupConfig = sliceletLookupConfig).watchStubManagerConfig.clientName
    )

    // Verify: Copying a watch-connection field re-derives the manager config from it.
    assertResult(TEST_OTHER_WATCH_ADDRESS)(
      clerkConfig.copy(watchAddress = TEST_OTHER_WATCH_ADDRESS).watchStubManagerConfig.watchAddress
    )
  }
}
