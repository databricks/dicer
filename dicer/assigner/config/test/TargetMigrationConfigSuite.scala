package com.databricks.dicer.assigner.config

import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP
import com.databricks.api.proto.dicer.assigner.config.TargetMigrationConfigP.TargetMigrationTypeP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class TargetMigrationConfigSuite extends DatabricksTest {

  test(
    "TargetMigrationConfig.fromProto parses a fully populated proto into a TargetMigrationConfig"
  ) {
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

  test("TargetMigrationConfig.fromProto maps NO_MIGRATION to NoMigration") {
    // Test plan: Verify that fromProto correctly maps NO_MIGRATION to the NoMigration case object.
    // An Assigner receiving a config with this type will not be migrating any targets.
    val proto: TargetMigrationConfigP = TargetMigrationConfigP(
      version = Some(1),
      forceToSourceTargetNames = Seq.empty,
      forceToDestinationTargetNames = Seq.empty,
      destinationTargetNameFraction = Some(0.0),
      migrationType = Some(TargetMigrationTypeP.NO_MIGRATION)
    )
    val config: TargetMigrationConfig = TargetMigrationConfig.fromProto(proto)
    assertResult(TargetMigrationType.NoMigration)(config.migrationType)
  }

  test("TargetMigrationConfig.fromProto rejects invalid protos") {
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

  test("TargetMigrationType.toProto maps to the expected proto values") {
    // Test plan: Verify that each TargetMigrationType case maps to its corresponding proto value.
    assertResult(TargetMigrationTypeP.NO_MIGRATION)(
      TargetMigrationType.toProto(TargetMigrationType.NoMigration)
    )
    assertResult(TargetMigrationTypeP.GENERAL_TO_SMK)(
      TargetMigrationType.toProto(TargetMigrationType.GeneralToSmk)
    )
  }

  test("TargetMigrationConfig.toProto round-trips through fromProto") {
    // Test plan: Verify that toProto produces a proto that fromProto can parse back into the
    // original TargetMigrationConfig. Covers a NoMigration config and a non-trivial active config.
    assertResult(TargetMigrationConfig.NO_MIGRATION)(
      TargetMigrationConfig.fromProto(
        TargetMigrationConfig.toProto(TargetMigrationConfig.NO_MIGRATION)
      )
    )

    val activeConfig: TargetMigrationConfig = TargetMigrationConfig(
      version = 3,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set(TargetName("target-a")),
      forceToDestinationTargetNames = Set(TargetName("target-b")),
      destinationTargetNameFraction = 0.25
    )
    assertResult(activeConfig)(
      TargetMigrationConfig.fromProto(TargetMigrationConfig.toProto(activeConfig))
    )
  }

  test("TargetMigrationConfig rejects invalid argument combinations") {
    // Test plan: Verify that the case class validates its arguments. Start with a valid config
    // and modify it in various ways to make it invalid, checking that construction fails.
    val validConfig: TargetMigrationConfig = TargetMigrationConfig(
      version = 1,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.5
    )

    assertThrow[IllegalArgumentException]("version must be non-negative") {
      validConfig.copy(version = -1)
    }
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

  test("TargetMigrationConfig rejects NoMigration configs with non-default routing fields") {
    // Test plan: Verify that when migrationType is NoMigration, the routing fields must be
    // empty / zero. Construction must fail if any is set — NoMigration is a true no-op and
    // misconfigured flag values should be rejected at parse time rather than silently ignored.
    assertThrow[IllegalArgumentException](
      "forceToSourceTargetNames must be empty when migrationType is NoMigration"
    ) {
      TargetMigrationConfig.NO_MIGRATION.copy(
        forceToSourceTargetNames = Set(TargetName("target-a"))
      )
    }
    assertThrow[IllegalArgumentException](
      "forceToDestinationTargetNames must be empty when migrationType is NoMigration"
    ) {
      TargetMigrationConfig.NO_MIGRATION.copy(
        forceToDestinationTargetNames = Set(TargetName("target-b"))
      )
    }
    assertThrow[IllegalArgumentException](
      "destinationTargetNameFraction must be 0.0 when migrationType is NoMigration"
    ) {
      TargetMigrationConfig.NO_MIGRATION.copy(destinationTargetNameFraction = 0.5)
    }
  }

}
