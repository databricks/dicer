package com.databricks.dicer.assigner

import com.databricks.testing.DatabricksTest

class MigrationModeSuite extends DatabricksTest {

  test("MigrationMode.fromName round-trips every defined mode") {
    // Test plan: For each defined MigrationMode, calling fromName(mode.name) must return the
    // same case object. This guards the wire-format contract used by DicerAssignerConf to
    // parse the migration-mode conf value at startup.
    for (mode <- MigrationMode.values) {
      assert(MigrationMode.fromName(mode.name) == mode)
    }
  }

  test("MigrationMode.fromName throws on an unknown mode name") {
    // Test plan: Unknown wire-format strings must fail loud (not silently default), so a typo
    // in the conf manifests as an assigner startup failure rather than a confusing runtime
    // behavior. Verify the exception message names the offender and lists every known mode by
    // its `name` so the test cannot drift from production wire strings.
    val exception = intercept[IllegalArgumentException] {
      MigrationMode.fromName("notARealMode")
    }
    assert(exception.getMessage.contains("notARealMode"))
    for (mode <- MigrationMode.values) {
      assert(exception.getMessage.contains(mode.name))
    }
  }
}
