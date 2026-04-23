package com.databricks.dicer.assigner

import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest

class TargetMigrationRouterSuite extends DatabricksTest {

  test("ALWAYS_HANDLE returns Handle for all targets") {
    // Test plan: Verify that ALWAYS_HANDLE returns Verdict.Handle for any target. Do this by
    // evaluating multiple distinct targets and confirming each result is Verdict.Handle.
    for (target: Target <- Seq(Target("my-service"), Target("other-service"))) {
      assert(TargetMigrationRouter.ALWAYS_HANDLE.getVerdict(target) == Verdict.Handle)
    }
  }
}
