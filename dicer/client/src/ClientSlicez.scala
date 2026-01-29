package com.databricks.dicer.client

import java.net.URI
import java.time.Instant

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import scalatags.Text.TypedTag
import scalatags.Text.all._

import com.databricks.HasDebugString
import com.databricks.caching.util.{AsciiTable, SequentialExecutionContext}
import com.databricks.caching.util.AsciiTable.Header
import com.databricks.dicer.client.ClientSlicez.clientSlicez
import com.databricks.dicer.common.ZPageHelpers.{COLLAPSE_SCRIPT, HEAD, createCollapseButton}
import com.databricks.dicer.common.{
  Assignment,
  ClerkSubscriberSlicezData,
  CommonSlicez,
  Generation,
  SliceletSubscriberSlicezData,
  SlicezAssignmentKeyTracker,
  TargetSlicezData
}
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.instrumentation.DebugStringServletRegistry

/** The class that manages the Slicez data for all clients that register with it. */
private[client] class ClientSlicez {

  import ClientSlicez.{TARGET_NAME_BACKGROUND_COLOR}

  private val sec = SequentialExecutionContext.createWithDedicatedPool("ClientSlicez")

  /**
   * The set of clients whose Slicez is being maintained, i.e., all those that were created
   * in this process.
   */
  private val clients = new mutable.HashSet[ClientTargetSlicezDataExporter]

  /** The Zpage component that allows users to track assignment information for target-key pairs. */
  private val slicezAssignmentKeyTracker = new SlicezAssignmentKeyTracker(sec)

  /** Register the given `slicezExporter` so that its information can be displayed when needed. */
  def register(slicezExporter: ClientTargetSlicezDataExporter): Unit = sec.run {
    clients += slicezExporter
  }

  /** Unregister the given `slicezExporter` so that its information won't be displayed. */
  def unregister(slicezExporter: ClientTargetSlicezDataExporter): Unit = sec.run {
    clients -= slicezExporter
  }

  /**
   * Returns a Future containing the Slicez HTML representing the state of all clients (Clerks and
   * Slicelets)
   */
  def getHtmlFut: Future[String] = {
    val clientsDataFut: Future[Seq[ClientTargetSlicezData]] = clientSlicez.getData
    clientsDataFut.flatMap(
      (clientsData: Seq[ClientTargetSlicezData]) =>
        slicezAssignmentKeyTracker
          .getHtml(clientsData)
          .map((assignmentTrackerHtml: TypedTag[String]) => {
            val page: TypedTag[String] = html(
              HEAD,
              body(
                h3("Client Information"),
                p(
                  "This table lists Dicer Clients that are running in this pod."
                ),
                table(
                  p(
                    style := "line-height: 0;",
                    s"Clerks/Slicelets (Current time ${Instant.now()})"
                  ),
                  // Append the table header.
                  tr(
                    backgroundColor := "PaleGreen",
                    td(strong("Target")),
                    td(strong("Subscriber Debug name")),
                    td(strong("Watch Address")),
                    td(strong("Address Used Since")),
                    td(strong("Last Successful Heartbeat")),
                    td(strong("Unattributed Load Table"))
                  ),
                  // Append client tables one by one with specified color.
                  clientsData.map(clientData => clientData.getHtml)
                ),
                // Append subscriber information table.
                createSubscriberTable(clientsData),
                // Append assignment information table.
                createAssignmentTable(clientsData),
                assignmentTrackerHtml
              ),
              COLLAPSE_SCRIPT
            )
            page.render
          })(sec)
    )(sec)
  }

  /**
   * Creates a table containing subscriber info associated each client registered to this page:
   * - For Slicelets: displays a list of subscribers.
   * - For Clerks: displays a prompt indicating that Clerks should not have subscribers.
   */
  private def createSubscriberTable(clientsData: Seq[ClientTargetSlicezData]): TypedTag[String] = {
    val sliceletsData: Seq[ClientTargetSlicezData] = clientsData
      .filter(
        (clientData: ClientTargetSlicezData) => clientData.squidOpt.isDefined
      )
    div(
      h3("Subscriber Information"),
      if (sliceletsData.isEmpty) {
        p("Clerks have no subscriber.")
      } else {
        div(
          p(
            "This table presents a list of Clerks and Slicelets that subscribed to " +
            "the Slicelets running in this pod. ",
            br(),
            "Additionally, for Slicelets, it contains local load information on assigned slices."
          ),
          table(
            p(
              style := "line-height: 0;",
              "Targets (Slicelet: [debug name, address], Clerk: [debug name])" +
              s"(Current time ${Instant.now()})"
            ),
            sliceletsData.map(
              (sliceletData: ClientTargetSlicezData) =>
                div(
                  tr(
                    backgroundColor := "PaleGreen",
                    th(
                      colspan := 2,
                      s"Subscriber Debug name: ",
                      strong(sliceletData.subscriberDebugName)
                    )
                  ),
                  sliceletData.createSubscriberDiv,
                  sliceletData.createAssignmentDiv
                )
            )
          )
        )
      }
    )
  }

  /**
   * Creates a table containing the complete assignment info associated with the targets of all
   * clients that registered to this page.
   * Note: the assignment stats presented in this table are not overridden by local stats from
   * the Slicelet, because local stats in Slicelets are likely to be incomplete. It would be better
   * to show an overview of the full assignment in Slicelets and Clerks.
   */
  private def createAssignmentTable(targetsData: Seq[TargetSlicezData]): TypedTag[String] = {
    // Organize targetSlicezData by target, and consolidate all targetSlicezDatas of a single target
    // to one targetSlicezData. Aggregates all `TargetSlicezData` of the same target name to one.
    val targetSlicezDataByTarget: Map[Target, Seq[TargetSlicezData]] =
      targetsData.groupBy { targetSlicezData: TargetSlicezData =>
        targetSlicezData.getTarget
      }

    // For each target of the aggregated `TargetSlicezData`, we identify and record the latest
    // assignment information.
    val latestAssignmentByTarget: collection.mutable.Map[Target, Option[Assignment]] =
      collection.mutable.Map.empty[Target, Option[Assignment]]
    for (entry <- targetSlicezDataByTarget) {
      val (target, targetsSlicezData): (Target, Seq[TargetSlicezData]) = entry
      var latestAssignmentOpt: Option[Assignment] = None
      var latestAssignmentGeneration = Generation.EMPTY
      for (slicezData: TargetSlicezData <- targetsSlicezData) {
        for (assignment: Assignment <- slicezData.getAssignmentOpt) {
          if (assignment.generation > latestAssignmentGeneration) {
            latestAssignmentOpt = Some(assignment)
            latestAssignmentGeneration = assignment.generation
          }
        }
      }
      latestAssignmentByTarget(target) = latestAssignmentOpt
    }

    div(
      h3("Full Assignment Information (From Dicer Assigners)"),
      p(
        "This table contains the complete assignment info propagated from Dicer Assigners.",
        br(),
        "The purpose of the table is to show full assignment. Load information in this table",
        br(),
        "is a snapshot taken at the time the assignment is generated, and can be ",
        strong("STALE"),
        " compared to local stats."
      ),
      table(
        p(
          style := "line-height: 0;",
          s"Target (Generation, Detailed Assignment) (Current time ${Instant.now()})"
        ),
        // Display the assignment information for each target.
        latestAssignmentByTarget.toSeq
          .map {
            case (target: Target, assignmentOpt: Option[Assignment]) =>
              val (generationString, asnString): (String, String) =
                CommonSlicez.getAssignmentString(
                  assignmentOpt,
                  reportedLoadPerResourceOpt = None,
                  reportedLoadPerSliceOpt = None,
                  topKeysOpt = None,
                  squidOpt = None
                )
              val asnHtml: TypedTag[String] =
                CommonSlicez.getAssignmentHtml(generationString, asnString)

              div(
                tr(
                  backgroundColor := TARGET_NAME_BACKGROUND_COLOR,
                  th(colspan := 2, "Target: ", strong(target.toParseableDescription))
                ),
                asnHtml
              )
          }
          // Aggregate assignment tables.
          .reduce(
            (targetHtml1: TypedTag[String], targetHtml2: TypedTag[String]) =>
              div(targetHtml1, targetHtml2)
          )
      )
    )
  }

  /** Returns the Slicez information for all clients in structured form rather than HTML. */
  private def getData: Future[Seq[ClientTargetSlicezData]] = sec.flatCall {
    val data: Seq[Future[ClientTargetSlicezData]] =
      clients.map((client: ClientTargetSlicezDataExporter) => client.getSlicezData).toSeq
    Future.sequence(data)(implicitly, sec)
  }
}

/**
 * The object that is used to register the Slicez for the Clerks/Slicelets with the servlet
 * registry.
 */
private[dicer] object ClientSlicez {

  /** Name of the Dicer client component in the central Zpages repository. */
  private val CLIENT_COMPONENT_NAME: String = "Dicer Client"

  /**
   * The background color displayed for the row showing the target's name in the
   * full assignment information table.
   */
  private val TARGET_NAME_BACKGROUND_COLOR: String = "PaleGreen"

  /** The actual ClientSlicez that stores the clients' slicez data. */
  private val clientSlicez: ClientSlicez = new ClientSlicez

  // Register with the global servlet registry.
  DebugStringServletRegistry.registerComponent(
    CLIENT_COMPONENT_NAME,
    new HasDebugString {
      @SuppressWarnings(
        Array("AwaitError", "AwaitWarning", "reason:servlet registry requires synchronous result")
      )
      override def debugHtml: String = {
        // Await the result because the registry requires us to return a String synchronously.
        val slicezHtmlFut: Future[String] = clientSlicez.getHtmlFut
        Await.result(slicezHtmlFut, Duration.Inf)
      }

      /**
       * Implemented this method since there is a link on the debug page that displays this text.
       */
      @SuppressWarnings(
        Array("AwaitError", "AwaitWarning", "reason:servlet registry requires synchronous result")
      )
      override def toString: String = {
        val clientsDataFut: Future[Seq[ClientTargetSlicezData]] = clientSlicez.getData
        val clientsData: Seq[ClientTargetSlicezData] = Await.result(clientsDataFut, Duration.Inf)
        clientsData.mkString("\n")
      }
    }
  )

  /** Sets up the Zpage so that Dicer information is displayed on admin/debug */
  def register(slicezExporter: ClientTargetSlicezDataExporter): Unit = {
    clientSlicez.register(slicezExporter)
  }

  /**
   * Unregisters the given `slicezExporter` so that its information will no longer be displayed on
   * the Zpage, and allows it to be garbage-collected.
   */
  def unregister(slicezExporter: ClientTargetSlicezDataExporter): Unit = {
    clientSlicez.unregister(slicezExporter)
  }

  object forTest {

    /**
     * Returns the component name that the ClientSlicez object registers with the servlet
     * registry.
     */
    def getClientDebugStringComponentName: String = CLIENT_COMPONENT_NAME

    /** Returns the ClientSlicez object being used for displaying the Zpage data. */
    def getClientSlicez: ClientSlicez = clientSlicez

    /** Returns the ClientSlicez's data in a structured form rather than just an HTML String. */
    def getData: Future[Seq[ClientTargetSlicezData]] = clientSlicez.getData
  }
}

/** Trait that allows the caller to get the [[ClientTargetSlicezData]] for displaying. */
private[client] trait ClientTargetSlicezDataExporter {
  def getSlicezData: Future[ClientTargetSlicezData]

  /**
   * Enforces reference equality semantics for all concrete classes to ensure that instances are
   * compared by memory address rather than by value. Each exporter represents a distinct client
   * instance, and we need to distinguish between different clients even if their data happens to be
   * the same.
   */
  final override def equals(that: Any): Boolean = super.equals(that)

  /**
   * Enforces a hash code based on object identity for all concrete classes, rather than on their
   * contents. See [[equals]] for more details.
   */
  final override def hashCode(): Int = super.hashCode()
}

/**
 * An immutable data structure that contains both the common target information as defined in
 * [[TargetSlicezData]] and client-specific information, including statistics on the unattributed
 * load (if this client is a Slicelet) and the client's subscriber information.
 *
 * @param target see [[TargetSlicezData.target]].
 * @param sliceletsData see [[TargetSlicezData.sliceletsData]].
 * @param clerksData see [[TargetSlicezData.clerksData]].
 * @param assignmentOpt see [[TargetSlicezData.assignmentOpt]].
 * @param reportedLoadPerResourceOpt the local reported load per resource tracked by the client
 *                                   for the current assignment.
 * @param reportedLoadPerSliceOpt the local reported load per slice tracked by the client for the
 *                                current assignment.
 * @param topKeysOpt the map of top keys associated with the current assignment.
 * @param squidOpt the resource associated with this client, which is only set for Slicelets.
 * @param unattributedLoadBySliceOpt the unattributed load for each slice, which is only set for
 *                                   Slicelets.
 * @param subscriberDebugName the subscriber debug name of the client.
 * @param watchAddress the watch address of the client.
 * @param watchAddressUsedSince the time since which the current watch address has been used.
 * @param lastSuccessfulHeartbeat the time of the last successful heartbeat received from the
 *                                assignment distributor for this client.
 */
private[client] case class ClientTargetSlicezData(
    target: Target,
    sliceletsData: Seq[SliceletSubscriberSlicezData],
    clerksData: Seq[ClerkSubscriberSlicezData],
    assignmentOpt: Option[Assignment],
    reportedLoadPerResourceOpt: Option[Map[Squid, Double]],
    reportedLoadPerSliceOpt: Option[Map[Slice, Double]],
    topKeysOpt: Option[SortedMap[SliceKey, Double]],
    squidOpt: Option[Squid],
    unattributedLoadBySliceOpt: Option[Map[Slice, Double]],
    subscriberDebugName: String,
    watchAddress: URI,
    watchAddressUsedSince: Instant,
    lastSuccessfulHeartbeat: Instant)
    extends TargetSlicezData(
      target,
      sliceletsData,
      clerksData,
      assignmentOpt
    ) {

  import ClientTargetSlicezData.{TARGET_DATA_BACKGROUND_COLOR, TARGET_NAME_BACKGROUND_COLOR}

  /**
   * Generates client-specific target information (information outside of the fields defined in
   * [[TargetSlicezData]]) associated with this [[ClientTargetSlicezData]] in HTML format.
   * The [[TargetSlicezData]] fields are rendered separately, allowing for aggregation across
   * multiple [[ClientTargetSlicezData]] and eliminating duplicate information among clients
   * associated with the same target.
   */
  def getHtml: TypedTag[String] = {
    // Each client information row contains a cell for the unattributed load table, which can be
    // expanded/collapsed, displaying a nested ASCII table of the unattributed load per slice on
    // that client. Since only Slicelets maintain unattributed load information, the unattributed
    // load ASCII table will show "N/A" for Clerks.
    val unattributedLoadTable: AsciiTable =
      new AsciiTable(
        Header("Slice.low"),
        Header("Slice.high"),
        Header("Unattributed Load")
      )
    unattributedLoadBySliceOpt match {
      case Some(unattributedLoadBySlice: Map[Slice, Double]) =>
        unattributedLoadBySlice.toSeq.map { entry: (Slice, Double) =>
          val (slice, load): (Slice, Double) = entry
          unattributedLoadTable.appendRow(
            slice.lowInclusive.toString,
            slice.highExclusive.toString,
            load.toString
          )
        }
      case None =>
        unattributedLoadTable.appendRow("N/A", "N/A", "N/A")
    }
    val collapsedUnattributedLoadTable: TypedTag[String] =
      div(
        createCollapseButton("Click to expand/collapse"),
        div(`class` := "content", pre(unattributedLoadTable.toString))
      )

    tr(
      backgroundColor := TARGET_DATA_BACKGROUND_COLOR,
      td(target.toParseableDescription),
      td(subscriberDebugName),
      td(watchAddress.toString),
      td(watchAddressUsedSince.toString),
      td(lastSuccessfulHeartbeat.toString),
      td(collapsedUnattributedLoadTable)
    )
  }

  /**
   * See [[TargetSlicezData.createAssignmentDiv]] for more details and example data. The client
   * records the `squidOpt` for Slicelets, in which case the resulting assignment string contains
   * information relevant to only the given resource. For Clerks, the `squidOpt` is empty and the
   * assignment string contains information relevant to all resources in the assignment.
   */
  final override def createAssignmentDiv: TypedTag[String] = {
    val (generationString, asnString): (String, String) =
      CommonSlicez.getAssignmentString(
        assignmentOpt,
        reportedLoadPerResourceOpt,
        reportedLoadPerSliceOpt,
        topKeysOpt,
        squidOpt
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
}

private[client] object ClientTargetSlicezData {

  /** Background color displayed for the row showing the target's name. */
  private val TARGET_NAME_BACKGROUND_COLOR = "PaleGreen"

  /**
   * Background color displayed for the rows showing the client data in the table
   * holding client-specific target information.
   */
  private val TARGET_DATA_BACKGROUND_COLOR = "PaleTurquoise"
}
