package com.databricks.dicer.assigner

import java.time.Instant

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import scalatags.Text.TypedTag
import scalatags.Text.all._

import com.databricks.HasDebugString
import com.databricks.api.proto.dicer.assigner.{
  AssignerInfoViewP,
  AssignerSliceViewP,
  AssignerTargetViewP,
  ChurnViewP,
  PreferredAssignerStateViewP,
  TargetConfigViewP
}
import com.databricks.api.proto.dicer.dpage.{ClerkViewP, SliceletViewP}
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.assigner.AssignmentGenerator.GeneratorTargetSlicezData
import com.databricks.dicer.assigner.AssignmentStats.AssignmentChangeStats
import com.databricks.dicer.assigner.PreferredAssignerValue.{ModeDisabled, NoAssigner, SomeAssigner}
import com.databricks.dicer.assigner.config.{InternalTargetConfig, NamedInternalTargetConfig}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.common.ZPageHelpers.{COLLAPSE_SCRIPT, HEAD, createCollapseButton}
import com.databricks.dicer.common.{
  Assignment,
  ClerkSubscriberSlicezData,
  CommonSlicez,
  DPageViewHelpers,
  Generation,
  SliceletSubscriberSlicezData,
  SlicezAssignmentKeyTracker,
  SlicezData,
  TargetSlicezData
}
import com.databricks.dicer.external.Target
import com.databricks.instrumentation.DebugStringServletRegistry
import com.databricks.rpc.DatabricksObjectMapper

// This file contains support for slicez Zpages for the Assigner.

/** The object that is used to register the Slicez with the servlet registry. */
object AssignerSlicez {

  /** The common prefix the Dicer component name. */
  private val ASSIGNER_COMPONENT_PREFIX: String = "Dicer Assigner"

  /** Sequential execution context used to handle futures. */
  private val sec = SequentialExecutionContext.createWithDedicatedPool("AssignerSlicez")

  /** The Zpage component that allows users to track assignment information for target-key pairs. */
  private val slicezAssignmentKeyTracker = new SlicezAssignmentKeyTracker(sec)

  /**
   * Sets up the Zpage so that Dicer information is displayed on the custom debugging page.
   *
   * If multiple assigners are running in the same process (which can only happen in the test
   * environment), then different assigners should use different `assignerInfoString` in order for
   * them to be all displayed correctly.
   */
  def setup(slicezExporter: AssignerSlicezDataExporter, assignerInfoString: String): Unit = {
    val componentFullName = ASSIGNER_COMPONENT_PREFIX + " " + assignerInfoString
    DebugStringServletRegistry.registerComponent(
      componentFullName,
      new HasDebugString {
        // We intentionally await in a blocking fashion because the registry requires us to return
        // a String synchronously.
        @SuppressWarnings(Array("AwaitError", "AwaitWarning", "reason:synchronous result required"))
        override def debugHtml: String = {
          // We await the result because the registry requires us to return a String synchronously.
          val slicezHtml: Future[String] = getHtml(slicezExporter.getSlicezData)
          Await.result(slicezHtml, Duration.Inf)
        }

        /**
         * Implemented this method since there is a link on the debug page that displays this text.
         */
        // We intentionally await in a blocking fashion because the registry requires us to return
        // a String synchronously.
        @SuppressWarnings(Array("AwaitError", "AwaitWarning", "reason:synchronous result required"))
        override def toString: String = {
          val slicezData: AssignerSlicezData =
            Await.result(slicezExporter.getSlicezData, Duration.Inf)
          slicezData.toString
        }
      }
    )
  }

  /** Returns a Future containing the Slicez HTML representing the state of current Assigner. */
  def getHtml(slicezsDataFut: Future[AssignerSlicezData]): Future[String] = {
    slicezsDataFut.flatMap(
      (assignerSlicezData: AssignerSlicezData) => {
        slicezAssignmentKeyTracker
          .getHtml(assignerSlicezData.targetsSlicezData)
          .map((assignmentTrackerHtml: TypedTag[String]) => {
            val page: TypedTag[String] = html(
              HEAD,
              tag("style") {
                """
                  |  table.assigner-table th {
                  |    background-color: PaleGreen;
                  |  }
                  |
                  |  table.assigner-table td {
                  |    background-color: BlanchedAlmond;
                  |  }
                  |
                  |""".stripMargin
              },
              body(
                assignerSlicezData.getHtml,
                assignmentTrackerHtml
              ),
              COLLAPSE_SCRIPT
            )
            page.render
          })(sec)
      }
    )(sec)
  }

  object forTest {

    /** Returns the component name prefix for the Assigner in the debug string registry. */
    def getAssignerDebugStringComponentPrefix: String = ASSIGNER_COMPONENT_PREFIX
  }
}

/** Trait that allows the caller to get the [[AssignerSlicezData]] for displaying. */
trait AssignerSlicezDataExporter {
  def getSlicezData: Future[AssignerSlicezData]
}

/**
 * An immutable data structure representing a single target's ZPage information on the
 * assigner side. It includes both:
 * - the common target information as defined in [[TargetSlicezData]] and
 * - assigner-specific details, such as statistics on the most recent assignment change and
 *   target's configuration information.
 *
 * @param target see [[TargetSlicezData.target]].
 * @param sliceletsData see [[TargetSlicezData.sliceletsData]].
 * @param clerksData see [[TargetSlicezData.clerksData]].
 * @param assignmentOpt see [[TargetSlicezData.assignmentOpt]].
 * @param generatorTargetSlicezData see [[AssignmentGenerator.GeneratorTargetSlicezData]].
 * @param targetConfig assigner-specific data on the target's configuration information.
 */
case class AssignerTargetSlicezData(
    target: Target,
    sliceletsData: Seq[SliceletSubscriberSlicezData],
    clerksData: Seq[ClerkSubscriberSlicezData],
    assignmentOpt: Option[Assignment],
    generatorTargetSlicezData: GeneratorTargetSlicezData,
    targetConfig: AssignerTargetSlicezData.TargetConfigData)
    extends TargetSlicezData(
      target,
      sliceletsData,
      clerksData,
      assignmentOpt
    ) {

  import AssignerTargetSlicezData.{TARGET_NAME_BACKGROUND_COLOR, TARGET_CHURN_BACKGROUND_COLOR}
  import AssignerTargetSlicezData.TargetConfigData.TARGET_CONFIG_BACKGROUND_COLOR

  /**
   * See [[TargetSlicezData.createAssignmentDiv]] for more details and example output. Note
   * that the assignment information displayed here is assigner-specific: it contains
   * information relevant to all resources in the assignment associated with this
   * [[AssignerTargetSlicezData]].
   */
  final override def createAssignmentDiv: TypedTag[String] = {
    val (generationString, asnString): (String, String) =
      CommonSlicez.getAssignmentString(
        assignmentOpt,
        generatorTargetSlicezData.reportedLoadPerResourceOpt,
        generatorTargetSlicezData.reportedLoadPerSliceOpt,
        generatorTargetSlicezData.topKeysOpt,
        squidOpt = None
      )
    val asnHtml: TypedTag[String] = CommonSlicez.getAssignmentHtml(generationString, asnString)
    div(
      tr(
        backgroundColor := TARGET_NAME_BACKGROUND_COLOR,
        th(colspan := 2, "Target: ", strong(target.toParseableDescription))
      ),
      asnHtml
    )
  }

  /**
   * Creates an HTML table containing information on the churn ratios tracked by
   * [[AssignerTargetSlicezData.commonTargetSlicezData.assignmentStatsSlicezData.churnRatioOpt]].
   * The div structure with example data is as follows:
   * {{{
   *   ----------------------------------------------------------------------------------
   *   | Target: softstore-storelet                                                     |
   *   ----------------------------------------------------------------------------------
   *   | Meaningful Assignment Change: true                                             |
   *   ----------------------------------------------------------------------------------
   *   | Churn Type                    |                  Churn Ratio                   |
   *   ----------------------------------------------------------------------------------
   *   | Load Balancing Churn Ratio    |                   0.333333                     |
   *   | Addition Churn Ratio          |                   0.000000                     |
   *   | Removal Churn Ratio           |                   0.666667                     |
   *   ----------------------------------------------------------------------------------
   * }}}
   */
  def createChurnDiv: TypedTag[String] = {
    div(
      tr(
        backgroundColor := TARGET_NAME_BACKGROUND_COLOR,
        th(colspan := 2, "Target: ", strong(getTarget.toParseableDescription))
      ),
      generatorTargetSlicezData.assignmentChangeStatsOpt match {
        case Some(assignmentChangeStats: AssignmentChangeStats) =>
          div(
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              th(
                colspan := 2,
                "Meaningful Assignment Change: ",
                assignmentChangeStats.isMeaningfulAssignmentChange.toString
              )
            ),
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              th("Churn Type"),
              th("Churn Ratio")
            ),
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              td("Load Balancing Churn Ratio"),
              td(assignmentChangeStats.loadBalancingChurnRatio.toString)
            ),
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              td("Addition Churn Ratio"),
              td(assignmentChangeStats.additionChurnRatio.toString)
            ),
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              td("Removal Churn Ratio"),
              td(assignmentChangeStats.removalChurnRatio.toString)
            )
          )
        case None =>
          div(
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              th(colspan := 2, "Meaningful Assignment Change: N/A")
            ),
            tr(
              backgroundColor := TARGET_CHURN_BACKGROUND_COLOR,
              th(colspan := 2, "No Churn Ratio Information")
            )
          )
      }
    )
  }

  /**
   * Creates an HTML table containing configuration information associated with this
   * [[AssignerTargetSlicezData]]. The div structure with example data is as follows:
   * {{{
   *   ----------------------------------------------------------------------------------
   *   | Target: softstore-storelet                                                     |
   *   ----------------------------------------------------------------------------------
   *   | Configuration Method      |          Configuration Detail                      |
   *   ----------------------------------------------------------------------------------
   *   |                           |                                                    |
   *   |                           |    {                                               |
   *   |                           |       "advanced_config": {"load_watcher_config": { |
   *   |                           |           "use_top_keys": false,                   |
   *   |         Static            |           "min_duration_seconds": 60,              |
   *   |                           |           "max_age_seconds": 300                   |
   *   |                           |       }},                                          |
   *   |                           |       "target_config": {},                         |
   *   |                           |       "target": "sample-version-tracker"           |
   *   |                           |   }                                                |
   *   ----------------------------------------------------------------------------------
   *  }}}
   */
  def createConfigDiv: TypedTag[String] = {
    val prettyConfigString: String = namedTargetConfig.toProto.toProtoString
    val collapsedConfigInfo: TypedTag[String] =
      div(
        createCollapseButton("Click to Expand/Collapse"),
        div(`class` := "content", pre(prettyConfigString))
      )

    div(
      tr(
        backgroundColor := TARGET_NAME_BACKGROUND_COLOR,
        th(colspan := 2, "Target: ", strong(getTarget.toParseableDescription))
      ),
      tr(
        td(backgroundColor := TARGET_CONFIG_BACKGROUND_COLOR, "Configuration Method"),
        td(backgroundColor := TARGET_CONFIG_BACKGROUND_COLOR, "Configuration Detail")
      ),
      tr(
        td(backgroundColor := TARGET_CONFIG_BACKGROUND_COLOR, targetConfig.configMethod.toString),
        td(backgroundColor := TARGET_CONFIG_BACKGROUND_COLOR, colspan := 2, collapsedConfigInfo)
      )
    )
  }

  /**
   * Converts this [[AssignerTargetSlicezData]] to an [[AssignerTargetViewP]] view proto,
   * including subscriber info, assignment, churn ratios, and target configuration.
   */
  private[assigner] def toViewProto: AssignerTargetViewP = {
    AssignerTargetViewP(
      target = Some(getTarget.toParseableDescription),
      slicelets = sliceletsData.map { s: SliceletSubscriberSlicezData =>
        SliceletViewP(debugName = Some(s.debugName), watchAddress = Some(s.address))
      },
      clerks = clerksData.map { c: ClerkSubscriberSlicezData =>
        ClerkViewP(debugName = Some(c.debugName))
      },
      assignment = Some(
        DPageViewHelpers.getAssignmentViewProto(
          assignmentOpt,
          generatorTargetSlicezData.reportedLoadPerResourceOpt,
          generatorTargetSlicezData.reportedLoadPerSliceOpt,
          generatorTargetSlicezData.topKeysOpt
        )
      ),
      churn = generatorTargetSlicezData.assignmentChangeStatsOpt.map {
        stats: AssignmentChangeStats =>
          ChurnViewP(
            meaningfulAssignmentChange = Some(stats.isMeaningfulAssignmentChange),
            loadBalancingChurnRatio = Some(stats.loadBalancingChurnRatio),
            additionChurnRatio = Some(stats.additionChurnRatio),
            removalChurnRatio = Some(stats.removalChurnRatio)
          )
      },
      config = Some(
        TargetConfigViewP(
          configMethod = Some(targetConfig.configMethod.toString),
          config = Some(namedTargetConfig.toProto.toProtoString)
        )
      )
    )
  }

  /**
   * Returns the [[NamedInternalTargetConfig]] for this target, combining the target name with its
   * internal config. Used by both the ZPage HTML and DPage JSON representations.
   */
  private def namedTargetConfig: NamedInternalTargetConfig =
    NamedInternalTargetConfig(TargetName.forTarget(getTarget), targetConfig.internalTargetConfig)
}

object AssignerTargetSlicezData {

  /**
   * Background color displayed for the rows showing the target's name in the assignment,
   * churn, and configuration tables.
   */
  private val TARGET_NAME_BACKGROUND_COLOR: String = "PaleGreen"

  /**
   * Background color displayed for the churn information table.
   */
  private val TARGET_CHURN_BACKGROUND_COLOR: String = "PaleTurquoise"

  /**
   * [[TargetConfigData]] carries the information about a target's configuration to be displayed
   * in the ZPage.
   *
   * @param internalTargetConfig The actual configuration that the assigner is generating
   *                             assignments for the target with.
   * @param configMethod How the configuration is specified or generated.
   */
  case class TargetConfigData(
      internalTargetConfig: InternalTargetConfig,
      configMethod: TargetConfigMethod.TargetConfigMethod)

  object TargetConfigData {

    /**
     * Background color displayed for the row showing the config method and the detailed config
     * information in the configuration information table.
     */
    val TARGET_CONFIG_BACKGROUND_COLOR: String = "BlanchedAlmond"
  }

  /** Enumeration that indicates how a target is configured. */
  object TargetConfigMethod extends Enumeration {
    type TargetConfigMethod = Value

    /** `Static` means the target is configured by a static configuration file. */
    val Static: TargetConfigMethod = Value("Static")

    /** `Dynamic` means the target is configured dynamically. */
    val Dynamic: TargetConfigMethod = Value("Dynamic")

    /**
     * `DefaultForExperimentalTargets` means the target is configured by a default config for
     * experimental targets [[InternalTargetConfig.DEFAULT_FOR_EXPERIMENTAL_TARGETS]].
     */
    val DefaultForExperimentalTargets: TargetConfigMethod = Value("DefaultForExperimentalTargets")
  }
}

/** Class that contains the assigner information and the Slicez data for all targets. */
case class AssignerSlicezData(
    assignerInfo: AssignerInfo,
    preferredAssignerValue: PreferredAssignerValue,
    targetsSlicezData: Seq[AssignerTargetSlicezData])
    extends SlicezData {

  /** Generates an HTML table based on all [[AssignerTargetSlicezData]]. */
  def getHtml: TypedTag[String] = {
    div(
      div(
        h3("Assigner Information"),
        table(
          tr(
            backgroundColor := "PaleGreen",
            td(strong("Assigner")),
            td(strong("URI")),
            td(strong("UUID"))
          ),
          tr(td("self", td(assignerInfo.uri.toString), td(assignerInfo.uuid.toString)))
        ),
        h3("Preferred Assigner State"),
        renderPreferredAssignerState,
        h3("Subscriber Information"),
        p(
          "This table lists Clerks and Slicelets that sent an RPC to this Dicer Assigner " +
          "(in the last 10-20 minutes)."
        ),
        table(
          p(
            style := "line-height: 0;",
            "Targets (Slicelet: [debug name, address], Clerk: [debug name])" +
            s"(Current time ${Instant.now()})"
          ),
          targetsSlicezData.map { targetSlicezData: AssignerTargetSlicezData =>
            targetSlicezData.createSubscriberDiv
          }
        )
      ),
      div(
        h3("Assignment Information"),
        p("This table contains the complete assignment info."),
        table(
          p(
            style := "line-height: 0;",
            s"Target (Generation, Detailed Assignment) (Current time ${Instant.now()})"
          ),
          targetsSlicezData.map { targetSlicezData: AssignerTargetSlicezData =>
            targetSlicezData.createAssignmentDiv
          }
        )
      ),
      div(
        h3("Churn Information"),
        p("This table contains the churn information for each target."),
        table(
          targetsSlicezData.map { targetsSlicezData: AssignerTargetSlicezData =>
            targetsSlicezData.createChurnDiv
          }
        )
      ),
      div(
        h3("Configuration Information"),
        p("This table lists the configuration information for each target."),
        table(
          targetsSlicezData.map { targetsSlicezData: AssignerTargetSlicezData =>
            targetsSlicezData.createConfigDiv
          }
        )
      )
    )
  }

  /**
   * Converts this [[AssignerSlicezData]] to an [[AssignerSliceViewP]] view proto for serialization.
   */
  private[assigner] def toViewProto: AssignerSliceViewP = {
    AssignerSliceViewP(
      assignerInfo = Some(
        AssignerInfoViewP(
          uuid = Some(assignerInfo.uuid.toString),
          uri = Some(assignerInfo.uri.toString)
        )
      ),
      preferredAssignerState = Some(preferredAssignerStateToViewProto),
      targets = targetsSlicezData.map { td: AssignerTargetSlicezData =>
        td.toViewProto
      }
    )
  }

  /** Serializes this [[AssignerSlicezData]] to a JSON string via [[toViewProto]]. */
  private[assigner] def toJson: String = DatabricksObjectMapper.toJson(toViewProto)

  /**
   * Returns the display strings for the preferred assigner state, shared between the ZPage HTML
   * and DPage JSON representations.
   */
  private def preferredAssignerStateDisplay: AssignerSlicezData.PreferredAssignerStateDisplay = {
    preferredAssignerValue match {
      case SomeAssigner(preferredAssignerInfo: AssignerInfo, generation: Generation) =>
        if (preferredAssignerInfo.uuid == assignerInfo.uuid) {
          AssignerSlicezData.PreferredAssignerStateDisplay(
            role = "Preferred assigner",
            action = "Generating assignments while preferred",
            preferredAssignerUuidOpt = Some("Self"),
            generation = generation
          )
        } else {
          AssignerSlicezData.PreferredAssignerStateDisplay(
            role = "Standby",
            action = "Monitoring health of preferred assigner",
            preferredAssignerUuidOpt = Some(preferredAssignerInfo.uuid.toString),
            generation = generation
          )
        }

      case NoAssigner(generation: Generation) =>
        AssignerSlicezData.PreferredAssignerStateDisplay(
          role = "Standby without preferred",
          action = "Attempting to take over as preferred assigner",
          preferredAssignerUuidOpt = Some("N/A"),
          generation = generation
        )

      case ModeDisabled(generation: Generation) =>
        AssignerSlicezData.PreferredAssignerStateDisplay(
          role = "Preferred because PA mode disabled",
          action = "Always generating assignments",
          preferredAssignerUuidOpt = Some("Self"),
          generation = generation
        )
    }
  }

  /**
   * Converts [[preferredAssignerValue]] to a [[PreferredAssignerStateViewP]] view proto,
   * reflecting the role and action of this assigner.
   */
  private def preferredAssignerStateToViewProto: PreferredAssignerStateViewP = {
    val display: AssignerSlicezData.PreferredAssignerStateDisplay = preferredAssignerStateDisplay
    PreferredAssignerStateViewP(
      role = Some(display.role),
      action = Some(display.action),
      paUuid = display.preferredAssignerUuidOpt,
      generation = Some(
        DPageViewHelpers.generationToViewProto(display.generation)
      )
    )
  }

  /** Renders the preferred assigner state as an HTML table (ZPage only). */
  private def renderPreferredAssignerState: TypedTag[String] = {
    val display: AssignerSlicezData.PreferredAssignerStateDisplay = preferredAssignerStateDisplay
    val rows: Seq[TypedTag[String]] = Seq(
        tr(th("Role"), td(display.role)),
        tr(th("Action"), td(display.action))
      ) ++
      display.preferredAssignerUuidOpt.map { paUuid: String =>
        tr(th("PA UUID"), td(paUuid))
      } ++
      Seq(tr(th("Generation"), td(display.generation.toString)))
    table(cls := "assigner-table")(rows: _*)
  }
}

object AssignerSlicezData {

  /**
   * Display data for the preferred assigner state, shared between the ZPage HTML and DPage
   * JSON representations.
   *
   * @param role human-readable description of this assigner's role (e.g. "Preferred assigner")
   * @param action human-readable description of what this assigner is doing
   * @param preferredAssignerUuidOpt the UUID of the preferred assigner, "Self" when this
   *                                 assigner is preferred, or "N/A" when no preferred assigner
   *                                 exists
   * @param generation the current preferred assigner generation
   */
  private case class PreferredAssignerStateDisplay(
      role: String,
      action: String,
      preferredAssignerUuidOpt: Option[String],
      generation: Generation)
}
