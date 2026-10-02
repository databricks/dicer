package com.databricks.dicer.common

import com.databricks.dicer.common.testing.{
  SubscriberHandlerMetricTestUtils,
  TestAssigner,
  TestEnvironmentMain
}

import scala.concurrent.duration.Duration

import com.databricks.caching.util.AssertionWaiter
import com.databricks.dicer.assigner.TargetMetricsUtils
import com.databricks.dicer.common.testing.TestEnvironmentMain.{
  DEFAULT_CLERKS,
  DEFAULT_SLICELETS,
  DEFAULT_TARGET
}
import com.databricks.dicer.common.Version.LATEST_VERSION
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils

class TestEnvironmentMainSuite extends DatabricksTest {

  test("Simple instantiation") {
    // Test plan: A simple test that calls the main with some arguments and make sure that the
    // target is created with the right number of Clerks/Slicelets.

    // Choose values other than the default values.
    val numClerks = DEFAULT_CLERKS + 1
    val numSlicelets = DEFAULT_SLICELETS + 1
    val target = Target(DEFAULT_TARGET + "-different")

    // To avoid port collisions in the unit test, use 0 for the info port.
    val infoPort = 0

    // Call the main and then wait for the assignment to be generated.
    TestEnvironmentMain.main(
      Array(
        "--clerks",
        s"$numClerks",
        "--slicelets",
        s"$numSlicelets",
        "--target",
        s"${target.toParseableDescription}",
        "--infoServicePort",
        s"$infoPort"
      )
    )
    AssertionWaiter("Assignment generated").await {
      val testAssigner: TestAssigner = TestEnvironmentMain.forTest.internalTestEnv.testAssigner
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      assert(assignment.assignedResources.size == numSlicelets)
    }

    // Assignment has been generated - verify some metrics.
    AssertionWaiter("Wait for Clerks/Slicelets to show up").await {
      assert(TargetMetricsUtils.getPodSetSize(target, "Running") == numSlicelets)
      assert(
        SubscriberHandlerMetricTestUtils
          .getNumSliceletsByHandler(SubscriberHandler.Location.Assigner, target, LATEST_VERSION)
        == numSlicelets
      )
    }
  }
}
