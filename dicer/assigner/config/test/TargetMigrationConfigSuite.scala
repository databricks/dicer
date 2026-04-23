package com.databricks.dicer.assigner.config

import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP
import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP.TargetMigrationTypeP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class TargetMigrationConfigSuite extends DatabricksTest {

  test("TargetMigrationConfig.fromProto with all fields populated") {
    // Test plan: Verify that fromProto correctly parses a fully populated proto into the expected
    // TargetMigrationConfig by comparing against a directly constructed case class.
    val proto: TargetMigrationConfigP = TargetMigrationConfigP(
      version = Some(1),
      forceToSourceTargetNames = Seq("target-a", "target-b"),
      forceToDestinationTargetNames = Seq("target-c"),
      destinationTargetNameFraction = Some(0.505),
      migrationType = Some(TargetMigrationTypeP.GENERAL_TO_SMK)
    )
    val expected: TargetMigrationConfig = TargetMigrationConfig(
      version = 1,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set(TargetName("target-a"), TargetName("target-b")),
      forceToDestinationTargetNames = Set(TargetName("target-c")),
      destinationTargetNameFraction = 0.505
    )
    assertResult(expected)(TargetMigrationConfig.fromProto(proto))
  }

  test("TargetMigrationConfig.fromProto validates invalid protos") {
    // Test plan: Verify that fromProto performs appropriate validation. Start with a valid proto
    // and modify it in various ways to make it invalid, checking that fromProto fails.
    val validProto: TargetMigrationConfigP = TargetMigrationConfigP(
      version = Some(1),
      forceToSourceTargetNames = Seq.empty,
      forceToDestinationTargetNames = Seq.empty,
      destinationTargetNameFraction = Some(0.0),
      migrationType = Some(TargetMigrationTypeP.GENERAL_TO_SMK)
    )
    // Confirm the base proto is valid.
    TargetMigrationConfig.fromProto(validProto)

    assertThrow[IllegalArgumentException]("version is not defined in proto") {
      TargetMigrationConfig.fromProto(validProto.copy(version = None))
    }
    assertThrow[IllegalArgumentException]("Migration type is unspecified") {
      TargetMigrationConfig.fromProto(validProto.copy(migrationType = None))
    }
    assertThrow[IllegalArgumentException]("Migration type is unspecified") {
      TargetMigrationConfig.fromProto(
        validProto.copy(
          migrationType = Some(TargetMigrationTypeP.TARGET_MIGRATION_TYPE_P_UNSPECIFIED)
        )
      )
    }
  }

  test(
    "TargetMigrationConfig.fromProto defaults destinationTargetNameFraction to 0 when missing"
  ) {
    // Test plan: Verify that fromProto defaults destinationTargetNameFraction to 0.0 when
    // the field is absent.
    val proto: TargetMigrationConfigP = TargetMigrationConfigP(
      version = Some(1),
      forceToSourceTargetNames = Seq.empty,
      forceToDestinationTargetNames = Seq.empty,
      destinationTargetNameFraction = None,
      migrationType = Some(TargetMigrationTypeP.GENERAL_TO_SMK)
    )
    val config: TargetMigrationConfig = TargetMigrationConfig.fromProto(proto)
    assertResult(0.0)(config.destinationTargetNameFraction)
  }

  test("TargetMigrationConfig validates case class construction") {
    // Test plan: Verify that the case class validates its arguments. Start with a valid config
    // and modify it in various ways to make it invalid, checking that construction fails.
    val validConfig: TargetMigrationConfig = TargetMigrationConfig(
      version = 1,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.5
    )

    assertThrow[IllegalArgumentException]("less than 0.0") {
      validConfig.copy(destinationTargetNameFraction = -0.1)
    }
    assertThrow[IllegalArgumentException]("greater than 1.0") {
      validConfig.copy(destinationTargetNameFraction = 1.1)
    }
    assertThrow[IllegalArgumentException](
      "forceToSourceTargetNames and forceToDestinationTargetNames must be disjoint"
    ) {
      validConfig.copy(
        forceToSourceTargetNames = Set(TargetName("target-a"), TargetName("target-b")),
        forceToDestinationTargetNames = Set(TargetName("target-b"), TargetName("target-c"))
      )
    }
  }

}
