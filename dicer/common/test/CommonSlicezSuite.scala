package com.databricks.dicer.common

import org.apache.commons.lang3.StringUtils
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{SliceKey, Target}
import com.databricks.testing.DatabricksTest
import scalatags.Text.TypedTag

class CommonSlicezSuite extends DatabricksTest {

  /** To verify the methods defined in the abstract class [[TargetSlicezData]]. */
  private case class TargetSlicezDataForTest(
      target: Target,
      sliceletsData: Seq[SliceletSubscriberSlicezData],
      clerksData: Seq[ClerkSubscriberSlicezData],
      assignmentOpt: Option[Assignment])
      extends TargetSlicezData(
        target,
        sliceletsData,
        clerksData,
        assignmentOpt
      ) {

    // This method is not implemented in the abstract class [[TargetSlicezData]] and
    // does not need to be tested here.
    override def createAssignmentDiv: TypedTag[String] = {
      throw new UnsupportedOperationException(
        "TargetSlicezDataForTest.createAssignmentDiv is not implemented here."
      )
    }
  }

  test("Check createSubscriberDiv contents in HTML format") {
    // Test plan: Directly create targetSlicezData with and without Slicelet and Clerk data,
    // and validate its rendered createSubscriberDiv content against golden snippets.

    val sliceletInfoTableHeader: String =
      "<tr style=\"background-color: Moccasin;\">" +
      "<td>Slicelet Debug Name</td>" +
      "<td>Slicelet Address</td>" +
      "</tr>"
    val clerkInfoTableHeader: String =
      "<tr style=\"background-color: Pink;\">" +
      "<th colspan=\"2\">Clerk Debug Name</th>" +
      "</tr>"

    val sliceExpectedHtml0 = "<td>slicelet0</td><td>localhost:12345</td>"
    val sliceExpectedHtml1 = "<td>slicelet1</td><td>localhost:12346</td>"
    val clerkExpectedHtml0 = "<th colspan=\"2\">clerk0</th></tr>"
    val clerkExpectedHtml1 = "<th colspan=\"2\">clerk1</th></tr>"

    // Conctruct targetData with assignment.
    val targetData0 = TargetSlicezDataForTest(
      target = Target("softstore-storelet"),
      sliceletsData = Seq(
        SliceletSubscriberSlicezData("slicelet0", "localhost:12345"),
        SliceletSubscriberSlicezData("slicelet1", "localhost:12346")
      ),
      clerksData = Seq(ClerkSubscriberSlicezData("clerk0"), ClerkSubscriberSlicezData("clerk1")),
      assignmentOpt = None
    )

    // Conctruct targetData without assignment.
    val targetData1 = TargetSlicezDataForTest(
      target = Target("softstore-storelet"),
      sliceletsData = Seq(),
      clerksData = Seq(),
      assignmentOpt = None
    )

    val renderedHtmlString0 = targetData0.createSubscriberDiv.render
    val renderedHtmlString1 = targetData1.createSubscriberDiv.render

    // Validate rendered content for targetData0.
    val target0Overview: String =
      "<th colspan=\"2\">Target: <strong>softstore-storelet</strong> Slicelets: 2 Clerks: 2</th>"
    assert(StringUtils.countMatches(renderedHtmlString0, target0Overview) == 1)

    // Rendered string should contain sliceletInfoTableHeader.
    assert(StringUtils.countMatches(renderedHtmlString0, sliceletInfoTableHeader) == 1)

    // Rendered string should contain clerkInfoTableHeader.
    assert(StringUtils.countMatches(renderedHtmlString0, clerkInfoTableHeader) == 1)

    // Rendered string should contain slicelet and clerk info.
    assert(StringUtils.countMatches(renderedHtmlString0, sliceExpectedHtml0) == 1)
    assert(StringUtils.countMatches(renderedHtmlString0, sliceExpectedHtml1) == 1)
    assert(StringUtils.countMatches(renderedHtmlString0, clerkExpectedHtml0) == 1)
    assert(StringUtils.countMatches(renderedHtmlString0, clerkExpectedHtml1) == 1)

    // Validate rendered content for targetData1.
    val target1Overview1: String =
      "<th colspan=\"2\">Target: <strong>softstore-storelet</strong> Slicelets: 0 Clerks: 0</th>"
    assert(StringUtils.countMatches(renderedHtmlString1, target1Overview1) == 1)

    // Rendered string should not contain sliceletInfoTableHeader.
    assert(StringUtils.countMatches(renderedHtmlString1, sliceletInfoTableHeader) == 0)

    // Rendered string should not contain clerkInfoTableHeader.
    assert(StringUtils.countMatches(renderedHtmlString1, clerkInfoTableHeader) == 0)

    // Rendered string should not contain slicelet and clerk info.
    assert(StringUtils.countMatches(renderedHtmlString1, sliceExpectedHtml0) == 0)
    assert(StringUtils.countMatches(renderedHtmlString1, sliceExpectedHtml1) == 0)
    assert(StringUtils.countMatches(renderedHtmlString1, clerkExpectedHtml0) == 0)
    assert(StringUtils.countMatches(renderedHtmlString1, clerkExpectedHtml1) == 0)
  }

  test("Search a key in TargetSlicezData returns correct SliceAssignment") {
    // Test plan: verifies that searching for a SliceKey in TargetSlicezData yields the correct
    // SliceAssignment. Verify this by creating TargetSlicezData instances with and without
    // assignment information, anc performing key searches on both. Ensure that the correct
    // SliceAssignment is returned when an assignment exists, and that no SliceAssignment is
    // returned when no assignment exists.
    val assignment: Assignment = createAssignment(
      23 ## 67,
      AssignmentConsistencyMode.Affinity,
      assignerServiceInfoOpt = None,
      ("" -- "Balin") @@ (23 ## 34) -> Seq("Pod2"),
      ("Balin" -- "Fili") @@ (23 ## 34) -> Seq("Pod0"),
      ("Fili" -- "Nori") @@ (23 ## 45) -> Seq("Pod1"),
      ("Nori" -- "Thorin") @@ (23 ## 67) -> Seq("Pod2"),
      ("Thorin" -- ∞) @@ (23 ## 34) -> Seq("Pod3")
    )

    val target1 = Target("target1")
    val target2 = Target("target2")
    val slicezData1 = TargetSlicezDataForTest(
      target1,
      sliceletsData = Seq(),
      clerksData = Seq(),
      assignmentOpt = Some(assignment)
    )
    val slicezData2 = TargetSlicezDataForTest(
      target2,
      sliceletsData = Seq(),
      clerksData = Seq(),
      assignmentOpt = None
    )

    val keys: Seq[String] = Seq("key0", "key1", "key2")
    for (key: String <- keys) {
      // Perform searches for different variants of the key.
      val identitySliceKey: SliceKey = identityKey(key)
      val farmHashedSliceKey: SliceKey = fp(key)
      val encryptedSliceKey: SliceKey = encryptedFp(key)

      // Lookup the expected SliceAssignment.
      val expectedAssignmentIdentity: String = assignment.sliceMap.lookUp(identitySliceKey).toString
      val expectedAssignmentHashed: String = assignment.sliceMap.lookUp(farmHashedSliceKey).toString
      val expectedAssignmentEncrypted: String =
        assignment.sliceMap.lookUp(encryptedSliceKey).toString

      assertResult(expectedAssignmentIdentity)(slicezData1.searchBySliceKey(identitySliceKey).get)
      assertResult(expectedAssignmentHashed)(slicezData1.searchBySliceKey(farmHashedSliceKey).get)
      assertResult(expectedAssignmentEncrypted)(slicezData1.searchBySliceKey(encryptedSliceKey).get)

      // Target2 has no assignment, so nothing should be returned.
      assertResult(None)(slicezData2.searchBySliceKey(identitySliceKey))
      assertResult(None)(slicezData2.searchBySliceKey(farmHashedSliceKey))
      assertResult(None)(slicezData2.searchBySliceKey(encryptedSliceKey))
    }
  }

  test("Check getAssignmentString properly handles empty assignment") {
    // Test plan: verifies that getAssignmentString returns the correct generation string and
    // assignment string when the assignment option is empty.
    val (generationString, assignmentString): (String, String) = CommonSlicez.getAssignmentString(
      assignmentOpt = None,
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None,
      squidOpt = None
    )
    assert(generationString == "None")
    assert(assignmentString == "No assignment")
  }

  test("Check getAssignmentString helper function returns correct generation string") {
    // Test plan: verifies that getAssignmentString returns the correct generation string when the
    // assignment option is not empty. We do not verify the assignment string, as it is verified by
    // [[AssignmentFormatterSuite]].
    val generation: Generation = 23 ## 67
    val assignment: Assignment = createAssignment(
      generation = generation,
      AssignmentConsistencyMode.Affinity,
      assignerServiceInfoOpt = None,
      ("" -- ∞) @@ (23 ## 34) -> Seq("Pod0")
    )
    val (generationString, _): (String, String) = CommonSlicez.getAssignmentString(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None,
      squidOpt = None
    )
    assert(generationString == generation.toString)
  }

  test("Check getAssignmentHtml helper function returns properly formatted HTML") {
    // Test plan: verifies that getAssignmentHtml returns the correct HTML for the given generation
    // and assignment string by comparing it against a golden snippet.
    val generation: Generation = 23 ## 67
    val assignment: Assignment = createAssignment(
      generation = generation,
      AssignmentConsistencyMode.Affinity,
      assignerServiceInfoOpt = None,
      ("" -- ∞) @@ (23 ## 34) -> Seq("Pod0")
    )
    val (generationString, assignmentString): (String, String) = CommonSlicez.getAssignmentString(
      assignmentOpt = Some(assignment),
      reportedLoadPerResourceOpt = None,
      reportedLoadPerSliceOpt = None,
      topKeysOpt = None,
      squidOpt = None
    )
    val htmlRendered: String =
      CommonSlicez.getAssignmentHtml(generationString, assignmentString).render
    val expectedHtmlFragment: String =
      "<div><tr style=\"background-color: PaleTurquoise;\"><th colspan=\"2\">Generation: " +
      s"""$generationString</th></tr><tr style=\"background-color: PaleTurquoise;\"><th colspan=\"2\">""" +
      "<div><button type=\"button\" style=\"color:red; background-color:transparent\" " +
      "name=\"db-caching-collapse\" class=\"collapsible\"><strong>+</strong><strong " +
      "style=\"color:black\">Assignment</strong></button><div class=\"content\"><pre>"
    assert(
      StringUtils.countMatches(htmlRendered, expectedHtmlFragment) == 1,
      s"Rendered:\n$htmlRendered\n============\nExpected fragment:\n$expectedHtmlFragment\n"
    )
  }
}
