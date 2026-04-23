package com.databricks.dicer.external

import com.databricks.api.proto.dicer.common.DiffAssignmentP
import com.databricks.dicer.client.TestClientUtils.FakeBlockingReadinessProvider
import com.databricks.dicer.common.{SetReadinessProviderBlockedRequestP, SetReadinessStatusRequestP}
import com.databricks.dicer.common.{
  Assignment,
  CloseSliceKeyHandleRequestP,
  CreateSliceKeyHandleRequestP,
  CreateSliceKeyHandleResponseP,
  CreateSliceletRequestP,
  CreateSliceletResponseP,
  DiffAssignment,
  GetAssignedSlicesRequestP,
  GetAssignedSlicesResponseP,
  GetLatestAssignmentRequestP,
  GetLatestAssignmentResponseP,
  GetSliceKeyHandleIsAssignedContinuouslyRequestP,
  GetSliceKeyHandleIsAssignedContinuouslyResponseP,
  GetSliceletSquidRequestP,
  GetSliceletSquidResponseP,
  GetSliceletWatchServerPortRequestP,
  GetSliceletWatchServerPortResponseP,
  IncrementSliceKeyHandleLoadByRequestP,
  SliceHelper,
  StartSliceletRequestP,
  StartSliceletResponseP,
  StopSliceletRequestP
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import scala.concurrent.{Promise}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import com.databricks.dicer.friend.{SliceletAccessor, Squid}
import com.databricks.caching.util.TestUtils

/**
 * A wrapper around a [[Slicelet]] to provide a common interface to both the Scala version (running
 * in the main test process) or the Rust version (running in a subprocess).
 *
 * This allows the same test suite to be run against both implementations.
 */
trait SliceletDriver {

  /** See [[Slicelet.start]]. */
  def start(selfPort: Int, listenerOpt: Option[SliceletListener]): Unit

  /** See [[Slicelet.createHandle]]. */
  def createHandle(key: SliceKey): SliceletDriver.SliceKeyHandle

  /** See [[Slicelet.assignedSlices]]. */
  def assignedSlices: Seq[Slice]

  /** Whether this Slicelet has received any assignments yet. */
  def hasReceivedAssignment: Boolean

  /** See [[com.databricks.dicer.client.SliceletImpl.forTest.getLatestAssignmentOpt]]. */
  def latestAssignmentOpt: Option[Assignment]

  /** Transitions this Slicelet into the terminating state. */
  def setTerminatingState(): Unit

  /** See [[Slicelet.impl.squid]]. */
  def squid: Squid

  /** See [[Slicelet.forTest.resourceAddress]]. */
  def resourceAddress: ResourceAddress

  /** Returns the active port of the Slicelet's watch server. */
  def activePort: Int

  /**
   * Sets the readiness status of this Slicelet to the given `isReady` status. If true, the Slicelet
   * is considered ready to serve traffic; if false, the Slicelet is considered not ready.
   */
  def setReadinessStatus(isReady: Boolean): Unit

  /**
   * Sets the blocking state of this Slicelet's readiness provider. If `blocked` is true, the
   * `isReady` call will block indefinitely until [[setReadinessProviderBlocked]] is called with
   * `blocked` set to false.
   */
  def setReadinessProviderBlocked(blocked: Boolean): Unit

  /** See [[Slicelet.forTest.stop]]. */
  def stop(): Unit
}

object SliceletDriver {

  /** A wrapper around a `SliceKeyHandle`. */
  trait SliceKeyHandle extends AutoCloseable {
    def isAssignedContinuously: Boolean

    def incrementLoadBy(value: Int): Unit

    def key: SliceKey

    /**
     * Whether calling [[close]] multiple times is allowed.
     *
     * Notably, Rust `SliceKeyHandle`s can only be closed once, because closure is done by dropping
     * them, which inherently can only be done once (as enforced by the compiler).
     */
    def canBeClosedMultipleTimes: Boolean
  }
}

/** The [[SliceletDriver]] that exercises the Scala [[Slicelet]] implementation. */
class ScalaSliceletDriver(
    val slicelet: Slicelet,
    fakeReadinessProviderOpt: Option[FakeBlockingReadinessProvider])
    extends SliceletDriver {
  override def start(selfPort: Int, listenerOpt: Option[SliceletListener]): Unit = {
    slicelet.start(selfPort, listenerOpt)
  }

  override def createHandle(key: SliceKey): SliceletDriver.SliceKeyHandle = {
    val handle: SliceKeyHandle = slicelet.createHandle(key)
    new SliceletDriver.SliceKeyHandle {
      override def isAssignedContinuously: Boolean = handle.isAssignedContinuously
      override def incrementLoadBy(value: Int): Unit = handle.incrementLoadBy(value)
      override def key: SliceKey = handle.key
      override def close(): Unit = handle.close()
      override def canBeClosedMultipleTimes: Boolean = true
    }
  }

  override def assignedSlices: Seq[Slice] = slicelet.assignedSlices

  override def hasReceivedAssignment: Boolean = latestAssignmentOpt.isDefined

  override def latestAssignmentOpt: Option[Assignment] = {
    slicelet.impl.forTest.getLatestAssignmentOpt
  }

  override def setTerminatingState(): Unit = slicelet.impl.forTest.setTerminatingState()

  override def squid: Squid = slicelet.impl.squid

  override def resourceAddress: ResourceAddress = SliceletAccessor.resourceAddress(slicelet)

  override def activePort: Int = slicelet.impl.forTest.sliceletPort

  override def setReadinessStatus(isReady: Boolean): Unit = {
    fakeReadinessProviderOpt match {
      case Some(fakeReadinessProvider: FakeBlockingReadinessProvider) =>
        fakeReadinessProvider.setReady(isReady)
      case None =>
        throw new UnsupportedOperationException(
          "SliceletDriver was not initialized with a fake readiness provider, so its " +
          "readiness status cannot be manually modified."
        )
    }
  }

  override def setReadinessProviderBlocked(blocked: Boolean): Unit = {
    fakeReadinessProviderOpt match {
      case Some(fakeReadinessProvider: FakeBlockingReadinessProvider) =>
        if (blocked) {
          fakeReadinessProvider.blockForever()
        } else {
          fakeReadinessProvider.unblock()
        }
      case None =>
        throw new UnsupportedOperationException(
          "SliceletDriver was not initialized with a fake readiness provider, so its " +
          "blocking state cannot be manually modified."
        )
    }
  }

  override def stop(): Unit = slicelet.forTest.stop()

  override def toString: String = slicelet.impl.squid.toString
}

