package com.databricks.dicer.assigner

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.testing.DatabricksTest

/** Test suite for AssignerMain which is run in a configuration where POD_IP is not set. */
class AssignerMainNoPodIpThrowsSuite extends DatabricksTest {

  /** Factory that returns [[None]], disabling the [[KubernetesMembershipChecker]]. */
  private val noOpMembershipCheckerFactory: KubernetesMembershipChecker.Factory =
    new KubernetesMembershipChecker.Factory {
      override def create(
          assignerInfo: AssignerInfo,
          assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker] = None
    }

  test("Assigner requires POD_IP environment variable to start") {
    // Test plan: Verify that the Assigner checks that POD_IP is set before starting, otherwise
    // throws an exception.
    assertThrow[IllegalStateException]("Environment variable POD_IP is not set.") {
      AssignerMain.staticForTest.wrappedMainInternalWithCheckerFactory(
        new DicerAssignerConf(Configs.empty),
        noOpMembershipCheckerFactory
      )
    }
  }
}
