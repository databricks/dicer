package com.databricks.dicer.assigner

import scala.concurrent.duration.Duration

import com.databricks.api.proto.dicer.assigner.{AssignerInfoP, GossipResponseP}
import com.databricks.api.proto.dicer.assigner.GossipServiceGrpc.GossipServiceStub
import com.databricks.caching.util.{AssertionWaiter, TestUtils}
import com.databricks.dicer.assigner.config.{TargetMigrationConfig, TargetMigrationType}
import com.databricks.dicer.common.{InternalDicerTestEnvironment, TestAssigner}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest

/**
 * Tests for the Assigner gossip mechanism, verifying that the Assigner stands up the gossip
 * service and is able to receive gossip RPCs from peers.
 */
class AssignerGossipSuite extends DatabricksTest {

  /** Helper for creating gossip stubs, configured with the test client TLS options. */
  private val gossipRpcHelper: GossipRpcHelper =
    new GossipRpcHelper(assignerTlsOptions = Some(TestTLSOptions.clientTlsOptions))

  /** Identity of a (fake) peer Assigner that the test sends gossip rounds as. */
  private val fakeCaller: AssignerInfoP = AssignerInfoP(
    uuidHigh = Some(0x0000000012345678L),
    uuidLow = Some(0xABCD68454E98B111L),
    uri = Some("http://gossip-caller:1234")
  )

  /** Builds a `NoMigration` [[TargetMigrationConfig]] at the given `version`. */
  private def noMigrationConfig(version: Int): TargetMigrationConfig =
    TargetMigrationConfig.NO_MIGRATION.copy(version = version)

  /** Builds a `GeneralToSmk` [[TargetMigrationConfig]] at the given `version`. */
  private def generalToSmkConfig(version: Int): TargetMigrationConfig =
    TargetMigrationConfig(
      version = version,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.0
    )

  /** Opens a gossip stub against `receiver`, reusable across gossip rounds within a test. */
  private def gossipStubFor(receiver: TestAssigner): GossipServiceStub =
    gossipRpcHelper.createStub(receiver.localUri)

  /**
   * As the [[fakeCaller]] peer, sends a gossip round carrying `configOpt` over `stub` to
   * `receiver`, returning the target migration config the response gossips back (if any). Verifies
   * that the response gossip contains `receiver`'s AssignerInfo.
   */
  private def gossip(
      stub: GossipServiceStub,
      receiver: TestAssigner,
      configOpt: Option[TargetMigrationConfig]): Option[TargetMigrationConfig] = {
    val request =
      GossipRequest(
        self = AssignerInfo.fromProto(fakeCaller),
        targetMigrationConfigOpt = configOpt
      )
    val responseProto: GossipResponseP =
      TestUtils.awaitResult(stub.gossip(request.toProto), Duration.Inf)
    val response = GossipResponse.fromProto(responseProto)

    // Every response echoes the responding Assigner's identity.
    assert(response.self == receiver.getAssignerInfoBlocking())
    response.targetMigrationConfigOpt
  }

  test("Assigner correctly updates its state in response to received gossip configs") {
    // Test plan: Verify that gossip configs are correctly handled by the receiver, which is seeded
    // initially with a no-op config (version 0).
    // - Case 1: Receiver adopts a newer config and returns nothing.
    // - Case 2: Receiver sees an identical config and does not return it.
    // - Case 3: Receiver sees a stale config and returns its own newer config.
    val receiver: TestAssigner =
      InternalDicerTestEnvironment.create().testAssigner
    val stub: GossipServiceStub = gossipStubFor(receiver)

    // Case 1: Receiver adopts a newer config and returns nothing.
    AssertionWaiter("Receiver adopts the newer config and returns nothing").await {
      assert(gossip(stub, receiver, Some(noMigrationConfig(version = 2))).isEmpty)
    }

    // Case 2: Receiver sees an identical config and does not return it.
    AssertionWaiter("Receiver sees an identical config and does not return it").await {
      assert(gossip(stub, receiver, Some(noMigrationConfig(version = 2))).isEmpty)
    }

    // Case 3: Receiver sees a stale config and returns its own newer config.
    AssertionWaiter("Receiver sees a stale config and returns its own newer config").await {
      assert(
        gossip(stub, receiver, Some(noMigrationConfig(version = 1)))
          .contains(noMigrationConfig(version = 2))
      )
    }
  }

  test("Assigner responds to an empty gossip request with the latest config") {
    // Test plan: Verify the receiver responds to an empty gossip request with the latest config.
    val receiver: TestAssigner =
      InternalDicerTestEnvironment.create().testAssigner
    val stub: GossipServiceStub = gossipStubFor(receiver)

    // Adopt a NoMigration config at version 5.
    assert(gossip(stub, receiver, Some(noMigrationConfig(version = 5))).isEmpty)

    // An empty request should see the latest config at version 5.
    assert(
      gossip(stub, receiver, None)
        .contains(noMigrationConfig(version = 5))
    )
  }

  test("Assigner handles conflicting gossip requests gracefully") {
    // Test plan: Verify the receiver handles a config that reuses the current version but changes
    // content (a different migration type) gracefully by ignoring it.
    val receiver: TestAssigner =
      InternalDicerTestEnvironment.create().testAssigner
    val stub: GossipServiceStub = gossipStubFor(receiver)

    // Case 1: adopt a NoMigration config at version 5, then gossip a GeneralToSmk config
    // at the same version 5, which is ignored and not adopted.
    assert(gossip(stub, receiver, Some(noMigrationConfig(version = 5))).isEmpty)
    assert(gossip(stub, receiver, Some(generalToSmkConfig(version = 5))).isEmpty)
    // Use a stale gossip round to verify that the receiver returns the NoMigration(version = 5)
    // config.
    assert(
      gossip(stub, receiver, Some(noMigrationConfig(version = 4)))
        .contains(noMigrationConfig(version = 5))
    )
  }

}
