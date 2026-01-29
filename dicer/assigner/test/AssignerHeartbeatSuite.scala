package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.HeartbeatResponseP
import com.databricks.caching.util.AssertionWaiter
import com.databricks.dicer.common.{
  Generation,
  Incarnation,
  InternalDicerTestEnvironment,
  TestAssigner
}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest
import com.databricks.api.proto.dicer.assigner.PreferredAssignerServiceGrpc.PreferredAssignerServiceStub

import java.net.URI
import java.util.UUID
import scala.concurrent.duration.Duration
import com.databricks.caching.util.TestUtils

/**
 * Tests the behavior of the assigner when receiving heartbeat requests.
 *
 * @note This suite only tests the behavior when the preferred assigner mode is disabled, and
 *       verifies that the heartbeat RPC flows.
 *       See `EtcdPreferredAssignerIntegrationSuite` for tests when the preferred assigner mode is
 *       enabled.
 */
class AssignerHeartbeatSuite extends DatabricksTest {

  /** Incarnations used in the suite. */
  private val NON_LOOSE_INCARNATION = Incarnation(40)
  private val HIGHER_NON_LOOSE_INCARNATION = Incarnation(NON_LOOSE_INCARNATION.value + 2)
  private val LOWER_NON_LOOSE_INCARNATION = Incarnation(NON_LOOSE_INCARNATION.value - 2)
  private val LOOSE_INCARNATION = Incarnation(NON_LOOSE_INCARNATION.value - 1)
  private val HIGHER_LOOSE_INCARNATION = Incarnation(LOOSE_INCARNATION.value + 2)
  private val LOWER_LOOSE_INCARNATION = Incarnation(LOOSE_INCARNATION.value - 2)

  /** A test environment where the preferred assigner mode is disabled. */
  private val preferredAssignerDisabledTestEnv = InternalDicerTestEnvironment.create(
    PreferredAssignerTestHelper.createAssignerConfig(
      preferredAssignerEnabled = false,
      preferredAssignerStoreIncarnation = LOOSE_INCARNATION
    )
  )

  /** The opId used in the heartbeat requests. */
  private var opId: Long = 1L

  private def getNextOpId: Long = {
    opId += 1
    opId
  }

  /** A helper class for creating PreferredAssigner stubs. */
  private val preferredAssignerServerHelper = new PreferredAssignerServerHelper(
    assignerTlsOptionsOpt = Some(TestTLSOptions.clientTlsOptions)
  )

  /** Constructs and Sends the heartbeat response and verifies it against the expected response. */
  private def sendHeartbeatAndVerifyResponse(
      receiver: TestAssigner,
      heartbeatRequest: HeartbeatRequest,
      expectedHeartbeatResponse: HeartbeatResponse): Unit = {
    AssertionWaiter("Assigner parrots back the opId in heartbeat response").await {
      val stub: PreferredAssignerServiceStub =
        preferredAssignerServerHelper.createStub(receiver.localUri)

      val heartbeatResponse: HeartbeatResponseP =
        TestUtils.awaitResult(stub.heartbeat(heartbeatRequest.toProto), Duration.Inf)
      val response = HeartbeatResponse.fromProto(heartbeatResponse)
      assert(response.opId == expectedHeartbeatResponse.opId)
      assert(response.preferredAssignerValue == expectedHeartbeatResponse.preferredAssignerValue)
    }
  }

  test("Disabled preferred assigner always returns PreferredAssignerValue.ModeDisabled") {
    // Test plan: verify that an assigner with the preferred assigner feature disabled always
    // responds with the `PreferredAssignerValue.ModeDisabled` and its incarnation, regardless of
    // the preferred assigner value in the heartbeat request.
    val arbitraryGenerationNumber = 4L // An arbitrary number to use in Generations.

    // The single assigner in the test environment is in PA mode disabled.
    val testAssigner: TestAssigner = preferredAssignerDisabledTestEnv.testAssigner

    // The expected response value for the PA mode disabled.
    val expectedResponseValue =
      PreferredAssignerValue.ModeDisabled(Generation(LOOSE_INCARNATION, 0))

    val arbitraryAssigner = AssignerInfo(
      UUID.fromString("00000000-1234-5678-abcd-68454e98b111"),
      new URI("http://assigner-1:1234")
    )

    // For all possible incarnation type and arbitrary generation numbers:
    for (incarnation: Incarnation <- Seq(
        LOWER_LOOSE_INCARNATION,
        LOWER_NON_LOOSE_INCARNATION,
        LOOSE_INCARNATION,
        NON_LOOSE_INCARNATION,
        HIGHER_LOOSE_INCARNATION,
        HIGHER_NON_LOOSE_INCARNATION
      )) {
      for (number: Long <- Seq(
          arbitraryGenerationNumber,
          arbitraryGenerationNumber + 1,
          arbitraryGenerationNumber + 2
        )) {
        val generation = Generation(incarnation, number)

        // Verify that the assigner responds with the PA mode disabled value, no matter what
        // the heartbeat request preferred assigner value is.
        val preferredAssignerValue =
          PreferredAssignerValue.SomeAssigner(arbitraryAssigner, generation)
        val opIdThisTime: Long = getNextOpId
        val heartbeatRequest = HeartbeatRequest(opIdThisTime, preferredAssignerValue)
        val expectedHeartbeatResponse = HeartbeatResponse(opIdThisTime, expectedResponseValue)

        sendHeartbeatAndVerifyResponse(testAssigner, heartbeatRequest, expectedHeartbeatResponse)
      }
    }
  }
}
