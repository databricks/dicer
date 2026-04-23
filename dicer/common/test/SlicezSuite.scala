package com.databricks.dicer.common

import scala.concurrent.duration.Duration

import org.apache.commons.lang3.StringUtils

import com.databricks.HasDebugString
import com.databricks.caching.util.{AssertionWaiter, PrefixLogger, TestUtils}
import com.databricks.dicer.assigner.{AssignerSlicez, AssignerSlicezData}
import com.databricks.dicer.client.{ClientSlicez, TestClientUtils}
import com.databricks.dicer.external.{Clerk, ClerkConf, Slicelet, Target}
import com.databricks.instrumentation.DebugStringServletRegistry
import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils

class SlicezSuite extends DatabricksTest {
  private val log = PrefixLogger.create(this.getClass, "")

  private val CLIENT_SLICEZ_TABLE_HEADER: String =
    "<tr style=\"background-color: PaleGreen;\">" +
    "<td><strong>Target</strong></td>" +
    "<td><strong>Subscriber Debug name</strong></td>" +
    "<td><strong>Watch Address</strong></td>" +
    "<td><strong>Address Used Since</strong></td>" +
    "<td><strong>Last Successful Heartbeat</strong></td>" +
    "<td><strong>Unattributed Load Table</strong></td>" +
    "</tr>"

  test("Check Assigner and Clients slicez contents") {
    // Test plan: Create a new environment (so that we can for emptiness of Slicez data etc) and
    // then add Clerks, Slicelets in succession while checking that the Slicez HTML contains the
    // relevant information. Then add a new target and make sure that an entry for it shows up as
    // well. The sequence of operations is the same as the Client Slicez test - we keep them
    // separate to make it easier to analyze the different Slicez contents.

    // Note that this test is long because it is trying to go through different transitions of
    // adding Clerks, Slicelets and a new target. Furthermore, we are checking for the contents of
    // the Assigner slicez and the Clients slicez. Breaking them up into two or more tests means
    // that the checks become weaker, e.g., we cannot assert that there are exactly 5 Clerks +
    // Slicelets since there is no ordering guarantee for the tests (and all the Slicez information
    // is global, by definition)

    // TODO(<internal bug>): Use golden snippets for this test after Clientz refactor is done.
    val slicezTestEnv = InternalDicerTestEnvironment.create()
    val slicezTestAssigner: TestAssigner = slicezTestEnv.testAssigner
    val assignerComponentSuffix: String = "test-test"

    // Call the setup to make sure that it does not crash. Later. check the HTML.
    AssignerSlicez.setup(slicezTestAssigner, assignerComponentSuffix)

    // In an empty Assigner, check that there is nothing but the "Targets ..." line
    val emptyHtml: String =
      TestUtils.awaitResult(AssignerSlicez.getHtml(slicezTestAssigner.getSlicezData), Duration.Inf)
    log.info(s"Initial Slicez data: $emptyHtml")
    assert(emptyHtml.contains("Targets (Slicelet: [debug name, address], Clerk: [debug name])"))
    assert(!emptyHtml.contains("Generation: None"))
    assert(!emptyHtml.contains("<pre>\nNo Assignment\n</pre>"))

    // Check that the key tracker is displayed on the Assigner Zpage.
    assert(
      StringUtils.countMatches(emptyHtml, "Track the assignment of the keys!") == 1
    )

    // Create two clerks.
    // TODO (<internal bug>): Clerks should connect to a Slicelet rather than the Assigner. Slicez tests
    //                   must be extended to test the contents of the Slicelet /slicez when there
    //                   are Clerks connecting to the Slicelet.
    val target = Target("slicez-contents")
    val clerkConf: ClerkConf =
      TestClientUtils.createTestClerkConf(
        sliceletPort = slicezTestEnv.getAssignerPort,
        clientTlsFilePathsOpt = None
      )
    Clerk.create(clerkConf, target, sliceletHostName = "localhost", addr => addr)
    Clerk.create(clerkConf, target, sliceletHostName = "localhost", addr => addr)

    // Wait for the Clerks to show up in the SubscriberManager on the Assigner side.
    AssertionWaiter("Clerks to show up").await {
      val slicezData: AssignerSlicezData =
        TestUtils.awaitResult(slicezTestAssigner.getSlicezData, Duration.Inf)
      assert(
        slicezData.targetsSlicezData.size == 1 &&
        slicezData.targetsSlicezData.head.clerksData.size == 2
      )
    }

    val clerkOnlyHtml: String =
      TestUtils.awaitResult(AssignerSlicez.getHtml(slicezTestAssigner.getSlicezData), Duration.Inf)
    log.info(s"Clerk only AssignerSlicez data: $clerkOnlyHtml")

    // Check that the clerk information is present in the Assigner HTML but no assignment.
    assert(clerkOnlyHtml.contains("slicez-contents"))
    assert(clerkOnlyHtml.contains("Slicelets: 0"))
    assert(clerkOnlyHtml.contains("Clerks: 2"))

    // Check that no duplicated assignment information is shown.
    assert(StringUtils.countMatches(clerkOnlyHtml, "Generation: None") == 1)
    assert(StringUtils.countMatches(clerkOnlyHtml, "No assignment") == 1)

    // Check that the target is present in key tracker exactly once on the Assigner Zpage.
    assert(
      StringUtils.countMatches(clerkOnlyHtml, "For target <strong>slicez-contents</strong>") == 1
    )

    // Check the Slicez for the clients.
    val noAssignmentHtml: String =
      TestUtils.awaitResult(ClientSlicez.forTest.getHtmlFut, Duration.Inf)
    // Log on two lines since the assignment overflows in the log.
    log.info(s"Clerk only ClientSlicez data")
    log.info(s"$noAssignmentHtml")

    // Check that the clerk information is present in the Client HTML but no assignment.
    assert(StringUtils.countMatches(noAssignmentHtml, "<td>slicez-contents</td>") == 2)
    assert(noAssignmentHtml.contains("<td>slicez-contents</td>"))

    // Check that no duplicated assignment information is shown.
    assert(StringUtils.countMatches(noAssignmentHtml, "Generation: None") == 1)
    assert(StringUtils.countMatches(noAssignmentHtml, "No assignment") == 1)

    // Check that the target is present in key tracker exactly once on the Client Zpage.
    assert(
      StringUtils.countMatches(noAssignmentHtml, "Track the assignment of the keys!") == 1
    )

    // Create the Slicelets. We don't actually run sharded RPC service, so `selfPort` isn't doing
    // any work here beyond ensuring different addresses per Slicelet.
    slicezTestEnv.createSlicelet(target).start(selfPort = 8080, listenerOpt = None)
    slicezTestEnv.createSlicelet(target).start(selfPort = 8081, listenerOpt = None)
    slicezTestEnv.createSlicelet(target).start(selfPort = 8082, listenerOpt = None)

    // Wait for the information to show up in the Assigner, i.e., assignment to be generated.
    AssertionWaiter("Slicelets to show up").await {
      val slicezData: AssignerSlicezData =
        TestUtils.awaitResult(slicezTestAssigner.getSlicezData, Duration.Inf)
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(slicezTestAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      assert(
        slicezData.targetsSlicezData.head.sliceletsData.size == 3 &&
        assignment.assignedResources.nonEmpty
      )
    }
    // Check that the Assigner slicez contains the information about the generation and the
    // assignment along with the Slicelets.
    val singleTargetHtml: String =
      TestUtils.awaitResult(AssignerSlicez.getHtml(slicezTestAssigner.getSlicezData), Duration.Inf)
    log.info(s"Assigner Slicez data with 3 Slicelets, 2 Clerks: $singleTargetHtml")
    assert(singleTargetHtml.contains("slicez-contents"))
    assert(singleTargetHtml.contains("Slicelets: 3"))
    assert(singleTargetHtml.contains("Clerks: 2"))
    assert(singleTargetHtml.contains("https://slicez-contents-0"))
    assert(singleTargetHtml.contains("https://slicez-contents-2"))

    // Check that the target is present in key tracker exactly once on the Assigner Zpage.
    assert(
      StringUtils.countMatches(singleTargetHtml, "For target <strong>slicez-contents</strong>") == 1
    )

    // Check the Client slicez.
    AssertionWaiter("Assignment to show up at clerks and slicelets").await {
      val slicezData = TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      val slicezHtml: String =
        TestUtils.awaitResult(ClientSlicez.forTest.getHtmlFut, Duration.Inf)
      assert(slicezData.length == 5 && !slicezHtml.contains("No assignment"))
    }
    val clientSlicezHtml: String =
      TestUtils.awaitResult(ClientSlicez.forTest.getHtmlFut, Duration.Inf)
    // Separate lines for logging so that the next line is at least printed. $slicezHtml is anyway
    // truncated since it is too big (the front is removed).
    log.info(s"Slicelets and Clerks ClientSlicez data:")
    log.info(s"$clientSlicezHtml")
    assert(clientSlicezHtml.contains("https://slicez-contents")) // Some Slicelet

    // Check that the target is present in key tracker exactly once on the Client Zpage.
    assert(
      StringUtils.countMatches(clientSlicezHtml, "For target <strong>slicez-contents</strong>") == 1
    )

    // Add a Slicelet for another target.
    val slicelet: Slicelet =
      slicezTestEnv
        .createSlicelet(Target("new-contents"))
        .start(selfPort = 8080, listenerOpt = None)

    // Wait for Slicelet to receive an assignment.
    AssertionWaiter("Wait for assignment").await {
      assert(slicelet.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Wait for the Assigner's slicez to reflect the two targets.
    AssertionWaiter("Second target at Assigner").await {
      val slicezData = TestUtils.awaitResult(slicezTestAssigner.getSlicezData, Duration.Inf)
      assert(slicezData.targetsSlicezData.size == 2)
    }

    // Check that both targets are present.
    val finalHtml: String =
      TestUtils.awaitResult(AssignerSlicez.getHtml(slicezTestAssigner.getSlicezData), Duration.Inf)
    log.info(s"Final Assigner Slicez data: $finalHtml")
    assert(finalHtml.contains("new-contents"))
    assert(finalHtml.contains("Slicelets: 3"))
    assert(finalHtml.contains("Slicelets: 1"))
    assert(finalHtml.contains("Clerks: 2"))
    assert(finalHtml.contains("Clerks: 0"))
    assert(finalHtml.contains("https://slicez-contents-0"))

    // Check that all targets are present in key tracker exactly once on the Assigner Zpage.
    assert(StringUtils.countMatches(finalHtml, "For target <strong>slicez-contents</strong>") == 1)
    assert(StringUtils.countMatches(finalHtml, "For target <strong>new-contents</strong>") == 1)

    // Check Client slicez - wait for all Slicelets + Clerks to show up in the Client slicez.
    AssertionWaiter("Second target at client").await {
      val slicezData = TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      val slicezHtml: String =
        TestUtils.awaitResult(ClientSlicez.forTest.getHtmlFut, Duration.Inf)
      assert(slicezData.length == 6 && !slicezHtml.contains("No assignment"))
    }
    log.info("Checking for second target at Client")

    // Check the contents of the Client's slicez.
    val finalClientHtml: String =
      TestUtils.awaitResult(ClientSlicez.forTest.getHtmlFut, Duration.Inf)
    // Log on separate lines since finalClientHtml is too big and is truncated.
    log.info("Final Client Html:")
    log.info(s"$finalClientHtml")
    assert(finalClientHtml.contains("https://new-contents-0"))
    assert(finalClientHtml.contains("https://slicez-contents-0"))
    assert(finalClientHtml.contains(CLIENT_SLICEZ_TABLE_HEADER))

    // Check that all targets are present in key tracker exactly on the Client Zpage..
    assert(
      StringUtils.countMatches(finalClientHtml, "For target <strong>slicez-contents</strong>") == 1
    )
    assert(
      StringUtils.countMatches(finalClientHtml, "For target <strong>new-contents</strong>") == 1
    )

    // Check that AssignerSlicez's code did register itself with central registry (by
    // checking some of the strings in the contents)
    val assignerComponentName: String =
      AssignerSlicez.forTest.getAssignerDebugStringComponentPrefix + " " + assignerComponentSuffix
    val hasAssignerString: HasDebugString =
      DebugStringServletRegistry.components.get(assignerComponentName)
    val assignerHtml: String = hasAssignerString.debugHtml
    assert(assignerHtml.contains("slicez-contents-2") && assignerHtml.contains("Slicelets: 3"))

    // Check the toString for hasString that it contains a couple of expected addresses from
    // each target.
    val hasAssignerStr = hasAssignerString.toString
    assert(hasAssignerStr.contains("slicez-contents-2"))
    assert(hasAssignerStr.contains("new-contents-0"))

    // Check ClientSlicez's code did register itself with central registry (by
    // checking some of the strings in the contents)
    val hasClientString: HasDebugString =
      DebugStringServletRegistry.components.get(
        ClientSlicez.forTest.getClientDebugStringComponentName
      )
    val clientHtml: String = hasClientString.debugHtml
    assert(clientHtml.contains("slicez-contents-2"))

    // Check the toString for hasString that it contains a couple of expected addresses from
    // each target.
    val hasClientStr = hasClientString.toString
    assert(hasClientStr.contains("slicez-contents-2"))
    assert(hasClientStr.contains("new-contents-0"))
  }

}
