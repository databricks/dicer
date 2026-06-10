package com.databricks.dicer.assigner.conf

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.{ConfigParseException, Configs}
import com.databricks.dicer.assigner.MigrationMode
import com.databricks.testing.DatabricksTest

class MigrationModeConfSuite extends DatabricksTest {

  test("preferredAssignerMigrationMode parses default and legal wire values") {
    // Test plan: Verify that DicerAssignerConf.preferredAssignerMigrationMode returns the
    // expected MigrationMode object for an unset conf (default) and for each known wire value.
    val defaultConfig = new DicerAssignerConf(Configs.empty)
    assert(defaultConfig.preferredAssignerMigrationMode == MigrationMode.ShadowMode)

    for (mode <- MigrationMode.values) {
      val config = new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.preferredAssigner.migrationMode" -> mode.name
        )
      )
      assert(config.preferredAssignerMigrationMode == mode)
    }
  }

  test(
    "preferredAssignerMigrationMode surfaces known modes via the ConfigParseException cause " +
    "chain on an unknown wire value"
  ) {
    // Test plan: An invalid wire value must fail loud at startup with ConfigParseException, and
    // the cause-chain message (which the framework does NOT copy into its own message) must
    // surface every known mode so an operator reading the assigner startup log can fix the
    // typo without grepping code.
    val exception = assertThrow[ConfigParseException]("invalid_migration_mode") {
      new DicerAssignerConf(
        Configs.parseMap(
          "databricks.dicer.assigner.preferredAssigner.migrationMode" -> "invalid_migration_mode"
        )
      )
    }
    assert(exception.getCause != null, "framework must attach the parser's cause exception")
    val causeMessage: String = exception.getCause.getMessage
    for (mode <- MigrationMode.values) {
      assert(causeMessage.contains(mode.name))
    }
  }
}
