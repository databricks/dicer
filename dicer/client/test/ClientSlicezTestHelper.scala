package com.databricks.dicer.client

import java.net.URI
import java.time.Instant

import scala.collection.immutable.SortedMap

import com.databricks.dicer.assigner.algorithm.{Algorithm, Resources}
import com.databricks.dicer.assigner.config.InternalTargetConfig.KeyReplicationConfig
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  ClerkSubscriberSlicezData,
  Generation,
  ProposedAssignment,
  SliceletSubscriberSlicezData,
  TestSliceUtils
}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.Squid

/** Shared test constants and helpers for Client test suites. */
private[client] object ClientSlicezTestHelper {

  /** Standard test target used across Client test suites. */
  val TARGET: Target = Target("softstore-storelet")

  /** Subscriber debug names for test clients. */
  val SUBSCRIBER_DEBUG_NAMES: IndexedSeq[String] =
    IndexedSeq("S0-softstore-storelet-localhost", "S1-softstore-storelet-localhost")

  /** Standard test watch address. */
  val WATCH_ADDRESS: URI = URI.create("https://localhost:12345")

  /**
   * Creates a [[ClientTargetSlicezData]] with sensible defaults so tests only need to
   * specify the fields they are exercising.
   */
  def createClientTargetSlicezData(
      target: Target = TARGET,
      assignmentOpt: Option[Assignment] = None,
      squidOpt: Option[Squid] = None,
      sliceletsData: Seq[SliceletSubscriberSlicezData] = Seq.empty,
      clerksData: Seq[ClerkSubscriberSlicezData] = Seq.empty,
      unattributedLoadBySliceOpt: Option[Map[Slice, Double]] = None,
      reportedLoadPerResourceOpt: Option[Map[Squid, Double]] = None,
      reportedLoadPerSliceOpt: Option[Map[Slice, Double]] = None,
      topKeysOpt: Option[SortedMap[SliceKey, Double]] = None,
      subscriberDebugName: String = SUBSCRIBER_DEBUG_NAMES(0),
      watchAddress: URI = WATCH_ADDRESS,
      watchAddressUsedSince: Instant = Instant.EPOCH,
      lastSuccessfulHeartbeat: Instant = Instant.EPOCH,
      clientClusterOpt: Option[URI] = None
  ): ClientTargetSlicezData = ClientTargetSlicezData(
    target = target,
    sliceletsData = sliceletsData,
    clerksData = clerksData,
    assignmentOpt = assignmentOpt,
    reportedLoadPerResourceOpt = reportedLoadPerResourceOpt,
    reportedLoadPerSliceOpt = reportedLoadPerSliceOpt,
    topKeysOpt = topKeysOpt,
    squidOpt = squidOpt,
    unattributedLoadBySliceOpt = unattributedLoadBySliceOpt,
    subscriberDebugName = subscriberDebugName,
    watchAddress = watchAddress,
    watchAddressUsedSince = watchAddressUsedSince,
    lastSuccessfulHeartbeat = lastSuccessfulHeartbeat,
    clientClusterOpt = clientClusterOpt
  )

  /** Creates a simple single-resource assignment for tests. */
  def createAssignment: Assignment = createAssignmentWithResources("resource0")

  /** Creates an assignment with the given resources. */
  def createAssignmentWithResources(resourceNames: String*): Assignment = {
    val resources: Resources = createResources(resourceNames: _*)
    val proposedAsn: ProposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      Algorithm.generateInitialAssignment(
        TARGET,
        resources,
        KeyReplicationConfig.DEFAULT_SINGLE_REPLICA
      )
    )

    val generation: Generation = TestSliceUtils.createLooseGeneration(42)

    proposedAsn.commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      generation
    )
  }
}
