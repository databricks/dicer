package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.{
  AssignerInfoP,
  GossipRequestP,
  GossipResponseP,
  GossipValueP
}
import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP
import com.databricks.caching.util.TestUtils
import com.databricks.dicer.assigner.config.TargetMigrationConfig
import com.databricks.testing.DatabricksTest

/** Unit tests for [[GossipRequest]] and [[GossipResponse]] proto parsing. */
class GossipSuite extends DatabricksTest {

  /** Identity of a (fake) Assigner referenced by the gossip protos under test. */
  private val fakeAssigner: AssignerInfoP = AssignerInfoP(
    uuidHigh = Some(0x0000000012345678L),
    uuidLow = Some(0xABCD68454E98B111L),
    uri = Some("http://gossip-assigner:1234")
  )

  /** The parsed [[AssignerInfo]] form of [[fakeAssigner]]. */
  private val fakeAssignerInfo: AssignerInfo = AssignerInfo.fromProto(fakeAssigner)

  /** Builds a `NoMigration` [[TargetMigrationConfig]] at the given `version`. */
  private def noMigrationConfig(version: Int): TargetMigrationConfig =
    TargetMigrationConfig.NO_MIGRATION.copy(version = version)

  test("GossipRequest round-trips through its proto") {
    // Test plan: Verify that a GossipRequest survives a conversion to its proto and back, both
    // when it carries a config and when it does not.
    val requestWithConfig: GossipRequest =
      GossipRequest(fakeAssignerInfo, Some(noMigrationConfig(version = 7)))
    assertResult(requestWithConfig)(GossipRequest.fromProto(requestWithConfig.toProto))

    val requestWithoutConfig: GossipRequest = GossipRequest(fakeAssignerInfo, None)
    assertResult(requestWithoutConfig)(GossipRequest.fromProto(requestWithoutConfig.toProto))
  }

  test("GossipResponse round-trips through its proto") {
    // Test plan: Verify that a GossipResponse survives a conversion to its proto and back, both
    // when it carries a config and when it does not.
    val responseWithConfig: GossipResponse =
      GossipResponse(fakeAssignerInfo, Some(noMigrationConfig(version = 7)))
    assertResult(responseWithConfig)(GossipResponse.fromProto(responseWithConfig.toProto))

    val responseWithoutConfig: GossipResponse = GossipResponse(fakeAssignerInfo, None)
    assertResult(responseWithoutConfig)(GossipResponse.fromProto(responseWithoutConfig.toProto))
  }

  test("GossipRequest and GossipResponse reject protos gossiping more than one value") {
    // Test plan: Verify that parsing a gossip proto carrying more than one value fails, since the
    // gossip protocol carries at most one value today. Do this by building request and response
    // protos with two values, or with one value and one empty value, and asserting `fromProto`
    // throws.
    val twoValues: Seq[GossipValueP] = Seq(
      GossipValueP(
        GossipValueP.Value.TargetMigrationConfig(
          TargetMigrationConfig.toProto(noMigrationConfig(version = 1))
        )
      ),
      GossipValueP(
        GossipValueP.Value.TargetMigrationConfig(
          TargetMigrationConfig.toProto(noMigrationConfig(version = 2))
        )
      )
    )

    TestUtils.assertThrow[IllegalArgumentException]("must carry at most one value") {
      GossipRequest.fromProto(GossipRequestP(self = Some(fakeAssigner), values = twoValues))
    }
    TestUtils.assertThrow[IllegalArgumentException]("must carry at most one value") {
      GossipResponse.fromProto(GossipResponseP(self = Some(fakeAssigner), values = twoValues))
    }

    val oneEmptyValue: Seq[GossipValueP] = Seq(
      GossipValueP(
        GossipValueP.Value.Empty
      ),
      GossipValueP(
        GossipValueP.Value.TargetMigrationConfig(
          TargetMigrationConfig.toProto(noMigrationConfig(version = 1))
        )
      )
    )

    TestUtils.assertThrow[IllegalArgumentException]("must carry at most one value") {
      GossipRequest.fromProto(GossipRequestP(self = Some(fakeAssigner), values = oneEmptyValue))
    }
    TestUtils.assertThrow[IllegalArgumentException]("must carry at most one value") {
      GossipResponse.fromProto(GossipResponseP(self = Some(fakeAssigner), values = oneEmptyValue))
    }
  }

  test("GossipRequest and GossipResponse reject protos that do not identify the Assigner") {
    // Test plan: Verify that parsing a gossip proto whose `self` is empty fails, since every gossip
    // message must identify the Assigner it comes from.
    TestUtils.assertThrow[IllegalArgumentException]("Invalid UUID") {
      GossipRequest.fromProto(GossipRequestP(self = None))
    }
    TestUtils.assertThrow[IllegalArgumentException]("Invalid UUID") {
      GossipResponse.fromProto(GossipResponseP(self = None))
    }
  }

  test("GossipRequest and GossipResponse reject protos gossiping an invalid-versioned config") {
    // Test plan: Verify that parsing a gossip proto whose config has an invalid version -- either
    // negative or unset -- fails.
    val validConfigP: TargetMigrationConfigP =
      TargetMigrationConfig.toProto(noMigrationConfig(version = 1))
    val negativeVersionConfig: TargetMigrationConfigP = validConfigP.copy(version = Some(-1))
    val emptyVersionConfig: TargetMigrationConfigP = validConfigP.copy(version = None)

    for (configP: TargetMigrationConfigP <- Seq(negativeVersionConfig, emptyVersionConfig)) {
      val values: Seq[GossipValueP] =
        Seq(GossipValueP(GossipValueP.Value.TargetMigrationConfig(configP)))
      TestUtils.assertThrow[IllegalArgumentException]("version") {
        GossipRequest.fromProto(GossipRequestP(self = Some(fakeAssigner), values = values))
      }
      TestUtils.assertThrow[IllegalArgumentException]("version") {
        GossipResponse.fromProto(GossipResponseP(self = Some(fakeAssigner), values = values))
      }
    }
  }
}
