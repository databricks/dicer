package com.databricks.dicer.assigner

import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.util.Random
import org.apache.commons.lang3.StringUtils
import org.apache.commons.text.StringEscapeUtils
import com.databricks.api.proto.dicer.assigner.{
  AssignerSliceViewP,
  AssignerTargetViewP,
  ChurnViewP,
  PreferredAssignerStateViewP
}
import com.databricks.api.proto.dicer.dpage.{
  AssignmentViewP,
  GenerationViewP,
  ResourceViewP,
  SliceViewP
}
import com.databricks.dicer.assigner.AssignerTargetSlicezData.TargetConfigMethod
import com.databricks.dicer.assigner.AssignerTargetSlicezData.TargetConfigMethod.TargetConfigMethod
import com.databricks.dicer.assigner.AssignmentGenerator.GeneratorTargetSlicezData
import com.databricks.dicer.assigner.AssignmentStats.AssignmentChangeStats

import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  HealthWatcherTargetConfig,
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  LoadWatcherTargetConfig
}
import com.databricks.dicer.assigner.algorithm.{LoadMap, Resources}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  ClerkSubscriberSlicezData,
  Generation,
  ProposedAssignment,
  SliceAssignment,
  SliceletSubscriberSlicezData,
  TestSliceUtils
}
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

class AssignerSlicezSuite extends DatabricksTest {

  /** Configuration used in `LoadBalancingMetricConfig`. */
  private val MAX_LOAD_HINT: Double = 1610.0

  /** The fake assigner information for the suite. */
  private val FAKE_ASSIGNER_INFO: AssignerInfo =
    AssignerInfo(UUID.randomUUID(), new java.net.URI("http://localhost:12345"))

  /** An arbitrary generation. */
  private val GENERATION: Generation = TestSliceUtils.createLooseGeneration(42)

  /** Create random assignments for tests. */
  private def createRandomAssignment: Assignment = {
    val resources: Resources = createResources("resource0", "resource1", "resource2")
    val proposedAsn1: ProposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        numSlices = 10,
        resources = resources.availableResources.toIndexedSeq,
        numMaxReplicas = 3,
        rng = Random
      )
    )
    val generation: Generation = TestSliceUtils.createLooseGeneration(42)
    proposedAsn1.commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      generation
    )
  }

  test(
    "Check AssigerTargetSlicezData renders empty assignment and empty assignment change " +
    "stats data table HTML contents"
  ) {
    // Test plan: Create an AssignerTargetSlicezData with no assignment change stats and verify
    // that the HTML contents are as expected. We only verify assignment and assignment change-
    // related snippets, as load-related snippets are either checked by
    // [[AssignmentFormatterSuite]] or not rendered directly on the ZPage.
    val assignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("dummy-target"),
      sliceletsData = Seq(),
      clerksData = Seq(),
      assignmentOpt = None,
      generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY,
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        AssignerTargetSlicezData.TargetConfigMethod.Dynamic
      )
    )

    // Verify assignment information.
    val assignmentDivRendered: String = assignerTargetSlicezData.createAssignmentDiv.render
    val expectedAssignmentFragment: String =
      "Target: <strong>dummy-target</strong></th></tr><div>" +
      "<tr style=\"background-color: PaleTurquoise;\"><th colspan=\"2\">Generation: None"
    assert(
      StringUtils.countMatches(assignmentDivRendered, expectedAssignmentFragment) == 1,
      s"Rendered:\n$assignmentDivRendered\n============\n" +
      s"Expected fragment:\n$expectedAssignmentFragment\n"
    )

    // Verify churn information.
    val churnDivRendered: String = assignerTargetSlicezData.createChurnDiv.render
    val expectedChurnFragment: String =
      "Target: <strong>dummy-target</strong></th></tr><div>" +
      "<tr style=\"background-color: PaleTurquoise;\"><th colspan=\"2\">Meaningful Assignment " +
      "Change: N/A</th></tr><tr style=\"background-color: PaleTurquoise;\">" +
      "<th colspan=\"2\">No Churn Ratio Information</th></tr></div>"
    assert(
      StringUtils.countMatches(churnDivRendered, expectedChurnFragment) == 1,
      s"Rendered:\n$churnDivRendered\n============\nExpected fragment:\n$expectedChurnFragment\n"
    )
  }

  test(
    "Check AssignerTargetSlicezData renders assignment change stats data in " +
    "GeneratorTargetSlicezData into table HTML contents"
  ) {
    // Test plan: Directly create AssignerTargetSlicezData using data from an assignment change and
    // verify golden snippets are rendered as expected. We only verify assignment change-related
    // snippets, as load-related snippets are either checked by [[AssignmentFormatterSuite]] or not
    // rendered directly on the ZPage.

    // Create random assignments and load map to calculate the assignment change stats from.
    val prevAsn: Assignment = createRandomAssignment
    val curAsn: Assignment = createRandomAssignment
    val loadMap: LoadMap = createRandomLoadMap(rng = Random, totalLoad = 1.0)

    // Calculate the assignment change stats from prevAsn to curAsn.
    val assignmentChangeStats: AssignmentChangeStats =
      AssignmentChangeStats.calculate(prevAsn, curAsn, loadMap)

    val assignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("dummy-target"),
      sliceletsData = Seq(),
      clerksData = Seq(),
      assignmentOpt = Some(curAsn),
      generatorTargetSlicezData = GeneratorTargetSlicezData(
        reportedLoadPerResourceOpt = None,
        reportedLoadPerSliceOpt = None,
        topKeysOpt = None,
        adjustedLoadPerResourceOpt = None,
        assignmentChangeStatsOpt = Some(assignmentChangeStats)
      ),
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        AssignerTargetSlicezData.TargetConfigMethod.Dynamic
      )
    )

    // Verify assignment information. We only check the target name and generation, as assignment
    // load details are checked by [[AssignmentFormatterSuite]].
    val assignmentDivRendered: String = assignerTargetSlicezData.createAssignmentDiv.render
    val expectedAssignmentFragment: String =
      "Target: <strong>dummy-target</strong></th></tr><div>" +
      "<tr style=\"background-color: PaleTurquoise;\"><th colspan=\"2\">Generation: " +
      s"""${curAsn.generation}</th></tr>"""
    assert(
      StringUtils.countMatches(assignmentDivRendered, expectedAssignmentFragment) == 1,
      s"Rendered:\n$assignmentDivRendered\n============\n" +
      s"Expected fragment:\n$expectedAssignmentFragment\n"
    )

    // Verify churn information.
    val churnDivRendered: String = assignerTargetSlicezData.createChurnDiv.render
    val expectedChurnFragment: String =
      "Target: <strong>dummy-target</strong></th></tr><div><tr style=" +
      "\"background-color: PaleTurquoise;\"><th colspan=\"2\">Meaningful Assignment Change: " +
      s"""${assignmentChangeStats.isMeaningfulAssignmentChange.toString}</th></tr>""" +
      "<tr style=\"background-color: PaleTurquoise;\"><th>Churn Type</th><th>Churn Ratio</th>" +
      "</tr><tr style=\"background-color: PaleTurquoise;\"><td>Load Balancing Churn Ratio</td>" +
      s"""<td>${assignmentChangeStats.loadBalancingChurnRatio.toString}</td></tr>""" +
      "<tr style=\"background-color: PaleTurquoise;\"><td>Addition Churn Ratio</td>" +
      s"""<td>${assignmentChangeStats.additionChurnRatio.toString}</td></tr>""" +
      "<tr style=\"background-color: PaleTurquoise;\"><td>Removal Churn Ratio</td>" +
      s"""<td>${assignmentChangeStats.removalChurnRatio.toString}</td></tr>"""
    assert(
      StringUtils.countMatches(churnDivRendered, expectedChurnFragment) == 1,
      s"Rendered:\n$churnDivRendered\n============\nExpected fragment:\n$expectedChurnFragment\n"
    )
  }

  test("Check AssignerSlicezData data table HTML contents") {
    // Test plan: Directly create AssignerSlicezData with random data and verify snippets are
    // rendered as expected. We create targets across all combinations of having an assignment vs
    // not and having dynamic, static, or default-for-experimental-targets config.
    //
    // Note: This test only checks for the presence of all targets without doing detailed validation
    // for each rendered [[GeneratorTargetSlicezData]], which is checked either in the above
    // `AssignerTargetSlicezData` test or in [[AssignmentFormatterSuite]].

    val assignment: Assignment = createRandomAssignment

    var i = 0
    val allTargetsData = mutable.ArrayBuffer[AssignerTargetSlicezData]()
    // Track the expected HTML fragments in the zpage output.
    val expectedUniqueFragments = mutable.ArrayBuffer[String]()
    for (hasAssignment: Boolean <- Seq(true, false)) {
      for (targetConfigMethod: TargetConfigMethod <- Seq(
          TargetConfigMethod.Dynamic,
          TargetConfigMethod.Static,
          TargetConfigMethod.DefaultForExperimentalTargets
        )) {
        val target = Target(s"softstore-storelet-$i")
        val targetDescription: String = target.toParseableDescription
        val sliceletsData: immutable.Seq[SliceletSubscriberSlicezData] =
          (0 until Random.nextInt(3)).map { j =>
            SliceletSubscriberSlicezData(s"slicelet-$j", s"localhost:12345")
          }
        val clerksData: Seq[ClerkSubscriberSlicezData] = (0 until Random.nextInt(5)).map { j =>
          ClerkSubscriberSlicezData(s"clerk-$j")
        }
        // Set `minDuration` to `i + 1` seconds so that we can uniquely match against the detailed
        // configuration in `expectedUniqueFragments`.
        val internalTargetConfig = InternalTargetConfig.forTest.DEFAULT.copy(
          loadWatcherConfig = LoadWatcherTargetConfig.DEFAULT.copy(minDuration = (i + 1).seconds),
          loadBalancingConfig = LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(MAX_LOAD_HINT)
          ),
          keyReplicationConfig = KeyReplicationConfig(minReplicas = 1, maxReplicas = 2),
          healthWatcherConfig = HealthWatcherTargetConfig(
            observeSliceletReadiness = true,
            permitRunningToNotReady = true
          )
        )
        val targetConfigData = AssignerTargetSlicezData.TargetConfigData(
          internalTargetConfig,
          targetConfigMethod
        )
        allTargetsData.append(
          AssignerTargetSlicezData(
            target,
            sliceletsData,
            clerksData,
            assignmentOpt = if (hasAssignment) Option(assignment) else None,
            generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY,
            targetConfigData
          )
        )

        // Verify: The assignment accessor on the TargetSlicezData returns the expected assignment.
        if (hasAssignment) {
          assert(allTargetsData.last.getAssignmentOpt == Some(assignment))
        } else {
          assert(allTargetsData.last.getAssignmentOpt == None)
        }

        // Overview information.
        expectedUniqueFragments.append(
          s"""<th colspan="2">Target: <strong>$targetDescription</strong> """ +
          s"Slicelets: ${sliceletsData.size} Clerks: ${clerksData.size}</th>"
        )

        // Configuration method and color.
        val configColor: String =
          AssignerTargetSlicezData.TargetConfigData.TARGET_CONFIG_BACKGROUND_COLOR
        val configMethodAndColorFragment =
          s"Target: <strong>$targetDescription</strong></th></tr><tr>" +
          s"""<td style="background-color: $configColor;">""" +
          s"""Configuration Method</td><td style="background-color: $configColor;">""" +
          s"""Configuration Detail</td></tr><tr><td style="background-color: $configColor;">""" +
          targetConfigMethod.toString
        expectedUniqueFragments.append(
          configMethodAndColorFragment
        )

        // Detailed configuration information.
        val primaryRateConfigDescription: String =
          s"""
           |  primary_rate_metric_config {
           |    max_load_hint: $MAX_LOAD_HINT
           |    imbalance_tolerance_hint: DEFAULT
           |    uniform_load_reservation_hint: NO_RESERVATION
           |  }""".stripMargin
        expectedUniqueFragments.append(
          s"""target_config {$primaryRateConfigDescription
          |  key_replication_config {
          |    min_replicas: 1
          |    max_replicas: 2
          |  }
          |}
          |advanced_config {
          |  load_watcher_config {
          |    min_duration_seconds: ${i + 1}
          |    max_age_seconds: 300
          |    use_top_keys: true
          |  }
          |  key_replication_config {
          |    min_replicas: 1
          |    max_replicas: 2
          |  }
          |  health_watcher_config {
          |    observe_slicelet_readiness: true
          |    permit_running_to_not_ready: true
          |  }
          |}""".stripMargin
        )

        i += 1
      }
    }

    val assignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        allTargetsData
      )
    // Get HTML rendered result in String.
    val renderedHtmlString = assignerSlicezData.getHtml.render

    for (fragment: String <- expectedUniqueFragments) {
      assert(
        StringUtils.countMatches(renderedHtmlString, fragment) == 1,
        s"Rendered:\n$renderedHtmlString\n============\nExpected fragment:\n$fragment\n"
      )
    }
  }

  test("Check PreferredAssignerSlicezData data table HTML contents for all roles") {
    val otherAssignerInfo: AssignerInfo =
      AssignerInfo(UUID.randomUUID(), new java.net.URI("http://localhost:12345"))

    for (mode <- Seq(
        PreferredAssignerValue.ModeDisabled(GENERATION),
        PreferredAssignerValue.SomeAssigner(FAKE_ASSIGNER_INFO, GENERATION),
        PreferredAssignerValue.SomeAssigner(otherAssignerInfo, GENERATION),
        PreferredAssignerValue.NoAssigner(GENERATION),
        PreferredAssignerValue.ModeDisabled(GENERATION)
      )) {

      val assignerSlicezData = AssignerSlicezData(FAKE_ASSIGNER_INFO, mode, Seq())
      val html = assignerSlicezData.getHtml.render

      def assertContains(html: String, fragment: String): Unit = {
        assert(
          html.contains(fragment),
          s"Rendered:\n$html\n============\nExpected fragment:\n$fragment\n"
        )
      }

      mode match {
        case PreferredAssignerValue.ModeDisabled(generation: Generation) =>
          assertContains(html, "<th>Action</th><td>Always generating assignments</td>")
          assertContains(html, s"<th>Generation</th><td>$generation</td>")

        case PreferredAssignerValue
              .SomeAssigner(preferredAssignerInfo: AssignerInfo, generation: Generation) =>
          if (preferredAssignerInfo == FAKE_ASSIGNER_INFO) {
            assertContains(html, "<th>Role</th><td>Preferred assigner</td>")
            assertContains(html, "<th>Action</th><td>Generating assignments while preferred</td>")
          } else {
            assertContains(html, "<th>Role</th><td>Standby</td>")
            assertContains(html, "<th>Action</th><td>Monitoring health of preferred assigner</td>")
            assertContains(html, s"<th>PA UUID</th><td>${otherAssignerInfo.uuid.toString}</td>")
          }
          assertContains(html, s"<th>Generation</th><td>$generation</td>")

        case PreferredAssignerValue.NoAssigner(generation: Generation) =>
          assertContains(html, "<th>Role</th><td>Standby without preferred</td>")
          assertContains(
            html,
            "<th>Action</th><td>Attempting to take over as preferred assigner</td>"
          )
          assertContains(html, "<th>PA UUID</th><td>N/A</td>")
          assertContains(html, s"<th>Generation</th><td>$generation</td>")
      }
    }
  }

  test("Check escaping contents for AssignerSlicez") {
    // Test plan: Create AssignerSlicezData with contents that needs escaping and test if they get
    // handled appropriately.

    val target = Target("softstore-storelet-4")
    val nonsenseName = "<button>&nonsense name</button>"
    val nonsenseAddress = "&& 1 === 1"

    // Calculate expected escaped contents using `StringEscapeUtils.escapeHtml4`.
    val targetDescriptionEscaped = StringEscapeUtils.escapeHtml4(target.toParseableDescription)
    val nonsenseNameEscaped = StringEscapeUtils.escapeHtml4(nonsenseName)
    val nonsenseAddressEscaped = StringEscapeUtils.escapeHtml4(nonsenseAddress)

    val targetSlicezData = AssignerTargetSlicezData(
      target = target,
      sliceletsData = Seq(SliceletSubscriberSlicezData(nonsenseName, nonsenseAddress)),
      clerksData = Seq(ClerkSubscriberSlicezData("clerk")),
      assignmentOpt = None,
      generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY,
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        AssignerTargetSlicezData.TargetConfigMethod.Static
      )
    )

    val assignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        Seq(targetSlicezData)
      )

    val renderedData = assignerSlicezData.getHtml.render

    // Verify the rendered HTML should contain snippets with escaped contents rather than raw ones.
    assert(renderedData.contains(FAKE_ASSIGNER_INFO.uri.toString))
    assert(renderedData.contains(FAKE_ASSIGNER_INFO.uuid.toString))
    assert(renderedData.contains(targetDescriptionEscaped))
    assert(!renderedData.contains(nonsenseName))
    assert(!renderedData.contains(nonsenseAddress))
    assert(renderedData.contains(nonsenseNameEscaped))
    assert(renderedData.contains(nonsenseAddressEscaped))
  }

  // -- AssignerSlicezData.toViewProto / toJson --

  test("toViewProto converts assignerInfo uuid and uri") {
    // Test plan: Verify that toViewProto correctly converts the UUID and URI from
    // assignerInfo into an AssignerInfoViewP proto with matching string representations.
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        Seq.empty
      )

    val result: AssignerSliceViewP = data.toViewProto

    assert(result.assignerInfo.isDefined)
    assert(result.assignerInfo.get.uuid.contains(FAKE_ASSIGNER_INFO.uuid.toString))
    assert(result.assignerInfo.get.uri.contains(FAKE_ASSIGNER_INFO.uri.toString))
  }

  test("toViewProto preferredAssignerState with ModeDisabled") {
    // Test plan: Verify that ModeDisabled produces a proto with the "Preferred because PA mode
    // disabled" role, "Always generating assignments" action, the correct generation, and
    // pa_uuid set to "Self".
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        Seq.empty
      )

    val result: PreferredAssignerStateViewP = data.toViewProto.getPreferredAssignerState

    assert(result.role.contains("Preferred because PA mode disabled"))
    assert(result.action.contains("Always generating assignments"))
    assertGenerationViewMatches(result.generation, GENERATION)
    assert(result.paUuid.contains("Self"))
  }

  test("toViewProto preferredAssignerState when this assigner is preferred") {
    // Test plan: Verify that SomeAssigner referencing the same UUID as assignerInfo produces a
    // proto with "Preferred assigner" role and pa_uuid set to "Self".
    val data: AssignerSlicezData = AssignerSlicezData(
      FAKE_ASSIGNER_INFO,
      PreferredAssignerValue.SomeAssigner(FAKE_ASSIGNER_INFO, GENERATION),
      Seq.empty
    )

    val result: PreferredAssignerStateViewP = data.toViewProto.getPreferredAssignerState

    assert(result.role.contains("Preferred assigner"))
    assert(result.action.contains("Generating assignments while preferred"))
    assertGenerationViewMatches(result.generation, GENERATION)
    assert(result.paUuid.contains("Self"))
  }

  test("toViewProto preferredAssignerState when this assigner is standby") {
    // Test plan: Verify that SomeAssigner referencing a different UUID produces a proto with
    // "Standby" role and the other assigner's UUID in pa_uuid.
    val otherAssignerInfo: AssignerInfo =
      AssignerInfo(UUID.randomUUID(), new java.net.URI("http://localhost:22222"))
    val data: AssignerSlicezData = AssignerSlicezData(
      FAKE_ASSIGNER_INFO,
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, GENERATION),
      Seq.empty
    )

    val result: PreferredAssignerStateViewP = data.toViewProto.getPreferredAssignerState

    assert(result.role.contains("Standby"))
    assert(result.action.contains("Monitoring health of preferred assigner"))
    assert(result.paUuid.contains(otherAssignerInfo.uuid.toString))
    assertGenerationViewMatches(result.generation, GENERATION)
  }

  test("toViewProto preferredAssignerState with NoAssigner") {
    // Test plan: Verify that NoAssigner produces a proto with "Standby without preferred" role,
    // pa_uuid set to "N/A", and the correct generation.
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.NoAssigner(GENERATION),
        Seq.empty
      )

    val result: PreferredAssignerStateViewP = data.toViewProto.getPreferredAssignerState

    assert(result.role.contains("Standby without preferred"))
    assert(result.action.contains("Attempting to take over as preferred assigner"))
    assert(result.paUuid.contains("N/A"))
    assertGenerationViewMatches(result.generation, GENERATION)
  }

  test("toViewProto assembles assignerInfo, preferredAssignerState, and targets") {
    // Test plan: Verify that toViewProto assembles the top-level AssignerSliceViewP with all three
    // sections populated. Do this by creating AssignerSlicezData with a single target and
    // checking that assignerInfo, preferredAssignerState, and targets are present.
    val td: AssignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("assembled-target"),
      sliceletsData = Seq.empty,
      clerksData = Seq.empty,
      assignmentOpt = None,
      generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY,
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        TargetConfigMethod.Dynamic
      )
    )
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        Seq(td)
      )

    val result: AssignerSliceViewP = data.toViewProto

    assert(result.assignerInfo.isDefined)
    assert(result.assignerInfo.get.uuid.contains(FAKE_ASSIGNER_INFO.uuid.toString))
    assert(result.preferredAssignerState.isDefined)
    assert(result.preferredAssignerState.get.role.contains("Preferred because PA mode disabled"))
    assert(result.targets.size == 1)
    assert(result.targets.head.target.contains("assembled-target"))
  }

  test("toViewProto with empty targets list") {
    // Test plan: Verify that toViewProto returns an AssignerSliceViewP with an empty targets list
    // when no target data is provided.
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.NoAssigner(GENERATION),
        Seq.empty
      )

    val result: AssignerSliceViewP = data.toViewProto

    assert(result.assignerInfo.isDefined)
    assert(result.preferredAssignerState.isDefined)
    assert(result.targets.isEmpty)
  }

  test("toJson returns valid JSON containing expected fields") {
    // Test plan: Verify that toJson produces a JSON string containing the expected assigner
    // info and preferred assigner state fields. Do this by creating AssignerSlicezData with
    // known values and checking that the JSON string contains the expected UUID and role.
    val data: AssignerSlicezData =
      AssignerSlicezData(
        FAKE_ASSIGNER_INFO,
        PreferredAssignerValue.ModeDisabled(GENERATION),
        Seq.empty
      )

    val json: String = data.toJson

    assert(json.contains(FAKE_ASSIGNER_INFO.uuid.toString))
    assert(json.contains(FAKE_ASSIGNER_INFO.uri.toString))
    assert(json.contains("Preferred because PA mode disabled"))
    assert(json.contains("Always generating assignments"))
  }

  // -- AssignerTargetSlicezData.toViewProto --

  test("toViewProto on target with no assignment and no churn stats omits churn") {
    // Test plan: Verify that toViewProto on a target produces a proto with churn=None when the
    // GeneratorTargetSlicezData has no assignment change stats.
    val td: AssignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("test-target"),
      sliceletsData = Seq(SliceletSubscriberSlicezData("sl-0", "addr:1000")),
      clerksData = Seq(ClerkSubscriberSlicezData("cl-0")),
      assignmentOpt = None,
      generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY,
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        TargetConfigMethod.Dynamic
      )
    )

    val result: AssignerTargetViewP = td.toViewProto

    assert(result.target.contains("test-target"))
    assert(result.slicelets.size == 1)
    assert(result.slicelets.head.debugName.contains("sl-0"))
    assert(result.slicelets.head.watchAddress.contains("addr:1000"))
    assert(result.clerks.size == 1)
    assert(result.clerks.head.debugName.contains("cl-0"))
    assert(result.churn.isEmpty)
    assert(result.config.isDefined)
    assert(result.config.get.configMethod.contains("Dynamic"))
  }

  test("toViewProto on target with assignment change stats includes churn") {
    // Test plan: Verify that toViewProto on a target produces a proto with churn populated when
    // assignment change stats are present in the GeneratorTargetSlicezData.
    val resources: Resources = createResources("r0", "r1", "r2")
    val curAsn: Assignment = createRandomAssignment(resources)
    val prevAsn: Assignment = createRandomAssignment(resources)
    val loadMap: LoadMap = createRandomLoadMap(rng = Random, totalLoad = 1.0)
    val stats: AssignmentChangeStats = AssignmentChangeStats.calculate(prevAsn, curAsn, loadMap)

    val td: AssignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("churn-target"),
      sliceletsData = Seq.empty,
      clerksData = Seq.empty,
      assignmentOpt = Some(curAsn),
      generatorTargetSlicezData = GeneratorTargetSlicezData(
        reportedLoadPerResourceOpt = None,
        reportedLoadPerSliceOpt = None,
        topKeysOpt = None,
        adjustedLoadPerResourceOpt = None,
        assignmentChangeStatsOpt = Some(stats)
      ),
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        TargetConfigMethod.Static
      )
    )

    val result: AssignerTargetViewP = td.toViewProto

    val churn: ChurnViewP = result.getChurn
    assert(churn.meaningfulAssignmentChange.contains(stats.isMeaningfulAssignmentChange))
    assert(churn.additionChurnRatio.contains(stats.additionChurnRatio))
    assert(churn.removalChurnRatio.contains(stats.removalChurnRatio))
    assert(churn.loadBalancingChurnRatio.contains(stats.loadBalancingChurnRatio))
    assert(result.config.get.configMethod.contains("Static"))
  }

  test("toViewProto on target with load maps and top keys populates all optional view fields") {
    // Test plan: Verify that toViewProto correctly populates attributed load per resource,
    // load per slice, and top keys when all optional maps are provided in
    // GeneratorTargetSlicezData. This covers the reportedLoadPerResourceOpt lookup,
    // the reportedLoadPerSliceOpt lookup, and the top-keys slice-range logic for both
    // finite high keys (SliceKey case) and the infinite high key (InfinitySliceKey case)
    // of the last slice, as well as the mapping of keysInSlice to TopKeyViewP.
    val resources: Resources = createResources("r0", "r1", "r2")
    val asn: Assignment = createRandomAssignment(resources)

    // Map each resource to a distinct attributed load.
    val loadPerResource: Map[Squid, Double] =
      asn.assignedResources.zipWithIndex.map { entry: (Squid, Int) =>
        val (squid, i): (Squid, Int) = entry
        squid -> (i + 1).toDouble
      }.toMap

    // Map each slice to a known load override.
    val loadPerSlice: Map[Slice, Double] =
      asn.sliceAssignments.map { sa: SliceAssignment =>
        (sa.slice, 0.5)
      }.toMap

    // Create one top key per slice at its low-inclusive boundary, guaranteeing that each
    // slice will find at least one matching key in its range. With 10 slices the last slice
    // has InfinitySliceKey as its high bound, exercising the InfinitySliceKey branch.
    val topKeys: SortedMap[SliceKey, Double] = SortedMap(
      asn.sliceAssignments.map { sa: SliceAssignment =>
        (sa.slice.lowInclusive, 1.0)
      }: _*
    )

    val td: AssignerTargetSlicezData = AssignerTargetSlicezData(
      target = Target("load-and-top-keys-target"),
      sliceletsData = Seq.empty,
      clerksData = Seq.empty,
      assignmentOpt = Some(asn),
      generatorTargetSlicezData = GeneratorTargetSlicezData(
        reportedLoadPerResourceOpt = Some(loadPerResource),
        reportedLoadPerSliceOpt = Some(loadPerSlice),
        topKeysOpt = Some(topKeys),
        adjustedLoadPerResourceOpt = None,
        assignmentChangeStatsOpt = None
      ),
      targetConfig = AssignerTargetSlicezData.TargetConfigData(
        InternalTargetConfig.forTest.DEFAULT,
        TargetConfigMethod.Dynamic
      )
    )

    val result: AssignerTargetViewP = td.toViewProto

    // All resources should have attributed loads set from reportedLoadPerResourceOpt.
    assert(result.assignment.isDefined)
    val assignmentProto: AssignmentViewP = result.assignment.get
    assert(assignmentProto.resources.nonEmpty)
    assert(assignmentProto.resources.forall { r: ResourceViewP =>
      r.attributedLoad.isDefined
    })

    // All slices should have load set from reportedLoadPerSliceOpt.
    assert(assignmentProto.slices.nonEmpty)
    assert(assignmentProto.slices.forall { s: SliceViewP =>
      s.load.contains(0.5)
    })

    // Each slice should have exactly one top key (the one at its low-inclusive boundary).
    assert(assignmentProto.slices.forall { s: SliceViewP =>
      s.topKeys.nonEmpty
    })
  }

  /** Asserts that a [[GenerationViewP]] matches the expected [[Generation]]. */
  private def assertGenerationViewMatches(
      genViewOpt: Option[GenerationViewP],
      expected: Generation): Unit = {
    assert(genViewOpt.isDefined, "Expected generation to be present")
    val gv: GenerationViewP = genViewOpt.get
    assert(gv.incarnation.contains(expected.incarnation.value))
    assert(gv.number.contains(expected.toTime.toEpochMilli))
    assert(gv.timestampStr.contains(expected.toTime.toString))
  }

  /** Creates a random assignment for use in tests. */
  private def createRandomAssignment(resources: Resources): Assignment = {
    val proposedAsn: ProposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        numSlices = 10,
        resources = resources.availableResources.toIndexedSeq,
        numMaxReplicas = 3,
        rng = Random
      )
    )
    proposedAsn.commit(isFrozen = false, AssignmentConsistencyMode.Affinity, GENERATION)
  }
}
