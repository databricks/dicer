package com.databricks.dicer.client

import com.databricks.caching.util.TestUtils
import com.databricks.dicer.common.{Assignment, Generation, SliceSetImpl, SubscriberData}

import com.databricks.dicer.external.SliceKey
import com.databricks.dicer.friend.Squid

import scala.concurrent.duration.Duration

/**
 * A wrapper around a [[SliceLookup]] to provide a common interface to both the Scala version
 * (running in the main test process) or the Rust version (running in a subprocess).
 *
 * This allows the same test suite to be run against both implementations.
 */
trait SliceLookupDriver {

  /** See [[SliceLookup.start]]. */
  def start(): Unit

  /** See [[SliceLookup.cancel]]. */
  def cancel(): Unit

  /** See [[SliceLookup.assignmentOpt]]. */
  def assignmentOpt: Option[Assignment]

  /**
   * Similar to [[assignmentOpt]], but just returns the generation rather than the full assignment.
   */
  def generationOpt: Option[Generation] = {
    assignmentOpt.map((assignment: Assignment) => assignment.generation)
  }

  /** See [[AssignmentWithReplicas.isAssignedKey]]. */
  def isAssignedKey(key: SliceKey, resource: Squid): Option[Boolean]

  /** See [[AssignmentWithReplicas.getSliceSetForResource]]. */
  def getSliceSetForResource(resource: Squid): SliceSetImpl

  /** Returns whether the `SliceLookup` is currently backing off. */
  def isInBackoff: Boolean

  /**
   * See [[SliceLookup.forTest.getWatchStubCacheSize]].
   *
   * Returns `None` if this `SliceLookup` implementation only uses a single watch stub (i.e. if it
   * only supports talking to Dicer via S2S Proxy).
   */
  def getWatchStubCacheSize: Option[Long]

  /** See [[SliceLookup.getSlicezData]]. */
  def getSlicezData: ClientTargetSlicezData
}

/** The [[SliceLookupDriver]] that exercises the Scala [[SliceLookup]] implementation. */
class ScalaSliceLookupDriver(sliceLookup: SliceLookup, subscriberDataSupplier: () => SubscriberData)
    extends SliceLookupDriver {
  override def start(): Unit = sliceLookup.start(subscriberDataSupplier)
  override def cancel(): Unit = sliceLookup.cancel()
  override def assignmentOpt: Option[Assignment] = sliceLookup.assignmentOpt
  override def isInBackoff: Boolean =
    TestUtils.awaitResult(sliceLookup.forTest.isInBackoff, Duration.Inf)
  override def getWatchStubCacheSize: Option[Long] = {
    Some(TestUtils.awaitResult(sliceLookup.forTest.getWatchStubCacheSize, Duration.Inf))
  }
  override def getSlicezData: ClientTargetSlicezData = {
    TestUtils.awaitResult(sliceLookup.getSlicezData, Duration.Inf)
  }

  override def isAssignedKey(key: SliceKey, resource: Squid): Option[Boolean] = {
    assignmentOpt.map { assignment: Assignment =>
      assignment.isAssignedKey(key, resource)
    }
  }

  override def getSliceSetForResource(resource: Squid): SliceSetImpl = {
    assignmentOpt
      .map { assignment: Assignment =>
        assignment.getSliceSetForResource(resource)
      }
      .getOrElse(SliceSetImpl.empty)
  }
}
