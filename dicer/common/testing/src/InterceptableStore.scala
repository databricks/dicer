package com.databricks.dicer.common.testing

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import com.databricks.caching.util.{Cancellable, SequentialExecutionContext, ValueStreamCallback}
import com.databricks.dicer.assigner.Store
import com.databricks.dicer.common.{Assignment, Incarnation, ProposedAssignment}
import com.databricks.dicer.external.Target
import com.databricks.dicer.common.testing.InterceptableStore.{
  DeferredWriteAssignment,
  WatchAssignmentsState
}
import io.grpc.Status

/**
 * [[Store]] that wraps another and allows assignment writes and assignment-watching callbacks to be
 * blocked for individual targets.
 */
class InterceptableStore(val sec: SequentialExecutionContext, private val store: Store)
    extends Store {

  override val storeIncarnation: Incarnation = store.storeIncarnation

  /**
   * While assignment writes are blocked, a target has an entry in this map in which deferred
   * [[writeAssignment]] requests are enqueued. In practice, a target will have at most one blocked
   * write, because the assignment generator does not initiate concurrent writes. For simplicity,
   * this implementation does not assume anything about the wider system: it just enqueues the write
   * requests and relies on the underlying store's OCC checks to handle the conflicts that would
   * arise if multiple writes were enqueued.
   */
  private val blockedWriteState =
    mutable.Map[Target, mutable.ArrayBuffer[DeferredWriteAssignment]]()

  /**
   * Tracks the blocked/unblocked status for all callers watching assignments through this store.
   * Access to both the map itself and all [[WatchAssignmentsState]] objects within are serialized
   * through `sec`.
   */
  private val watchAssignmentsState = mutable.Map[Target, WatchAssignmentsState]()

  /**
   * Specialization of [[Store.writeAssignment]] that respects the "blocked" state of targets. When
   * a target is not blocked (the default state) requests are forwarded to the underlying
   * [[InMemoryStore]]. Otherwise, the request is enqueued for execution when the target is
   * unblocked.
   */
  override def writeAssignment(
      target: Target,
      shouldFreeze: Boolean,
      proposal: ProposedAssignment): Future[Store.WriteAssignmentResult] = {
    sec.flatCall {
      blockedWriteState.get(target) match {
        case Some(deferredWrites) =>
          // If the target is blocked, defer the write. When the target is unblocked, the deferred
          // write will be allowed to run and will complete `promise`.
          val promise = Promise[Store.WriteAssignmentResult]()
          deferredWrites.append(
            DeferredWriteAssignment(target, shouldFreeze, proposal, promise)
          )
          promise.future
        case None =>
          store.writeAssignment(target, shouldFreeze, proposal)
      }
    }
  }

  override def getLatestKnownAssignment(target: Target): Future[Option[Assignment]] = {
    store.getLatestKnownAssignment(target)
  }

  /**
   * REQUIRES: must be called on `sec`.
   *
   * Blocks write attempts for the given `target`. Any [[writeAssignment]] requests handled while
   * the target is blocked are enqueued, to be executed when [[unblockAssignmentWrites]] is called.
   */
  def blockAssignmentWrites(target: Target): Unit = {
    sec.assertCurrentContext()
    blockedWriteState.getOrElseUpdate(target, mutable.ArrayBuffer())
  }

  /**
   * REQUIRES: must be called on `sec`.
   *
   * Returns any assignment writes that are currently deferred (due to blocking) for the given
   * target.
   */
  def getDeferredAssignments(target: Target): Vector[ProposedAssignment] = {
    sec.assertCurrentContext()
    blockedWriteState.get(target) match {
      case Some(deferredWrites: mutable.ArrayBuffer[DeferredWriteAssignment]) =>
        val proposals: Vector[ProposedAssignment] = deferredWrites.toVector.map { deferredWrite =>
          deferredWrite.proposal
        }
        proposals
      case None =>
        Vector.empty
    }
  }

  /**
   * REQUIRES: must be called on `sec`.
   *
   * Unblocks assignment writes for the given `target`. Any [[writeAssignment]] requests deferred
   * while the target was blocked are executed, and subsequent [[writeAssignment]] requests will go
   * through.
   */
  def unblockAssignmentWrites(target: Target): Unit = {
    blockedWriteState.remove(target) match {
      case Some(deferredWrites) =>
        for (deferredWrite <- deferredWrites) {
          deferredWrite.run(this, sec)
        }
      case None =>
      // No-op when not blocked.
    }
  }

  /**
   * Specialization of [[Store.watchAssignments]] which can defer the callback notifying the caller
   * of a new stored assignment.
   */
  override def watchAssignments(
      target: Target,
      callback: ValueStreamCallback[Assignment]): Cancellable = sec.callCancellable {
    getWatchAssignmentsState(target).watchAssignments(store, callback)
  }

  /**
   * REQUIRES: must be called on `sec`.
   *
   * Stops new assignments from being published to [[watchAssignments]] callbacks. Any assignments
   * that would have been published while the callbacks are blocked will be queued and published
   * once the target is unblocked via `unblockWatchAssignmentsCallbacks`.
   */
  def blockWatchAssignmentsCallbacks(target: Target): Unit = {
    sec.assertCurrentContext()
    getWatchAssignmentsState(target).setBlocked(blocked = true)
  }

  /**
   * REQUIRES: must be called on `sec`.
   *
   * Resumes publishing new assignments to [[watchAssignments]] callbacks.
   */
  def unblockWatchAssignmentsCallbacks(target: Target): Unit = {
    sec.assertCurrentContext()
    getWatchAssignmentsState(target).setBlocked(blocked = false)
  }

  override def informAssignment(target: Target, assignment: Assignment): Unit = {
    store.informAssignment(target, assignment)
  }

  /** Returns the [[WatchAssignmentsState]] for `target`, creating one if necessary. */
  private def getWatchAssignmentsState(target: Target) = {
    sec.assertCurrentContext()
    watchAssignmentsState.getOrElseUpdate(target, new WatchAssignmentsState(sec, target))
  }
}

object InterceptableStore {

  /**
   * State maintained for a [[writeAssignment]] request that is being deferred because its target is
   * blocked.
   */
  private case class DeferredWriteAssignment(
      target: Target,
      shouldFreeze: Boolean,
      proposal: ProposedAssignment,
      promise: Promise[Store.WriteAssignmentResult]) {
    def run(store: Store, sec: SequentialExecutionContext): Unit = {
      store
        .writeAssignment(target, shouldFreeze, proposal)
        .onComplete(
          result => promise.complete(result)
        )(sec)
    }
  }

  /**
   * The state of all assignment watchers for a particular `target`. Access to mutable state is
   * guarded by `sec`.
   */
  private class WatchAssignmentsState(sec: SequentialExecutionContext, target: Target) {

    /** Callbacks registered to watch assignments for a target. Guarded by `sec`. */
    private val callbacks = mutable.ArrayBuffer[BlockableWatchAssignmentCallback]()

    /** The blocked state with which to register new callbacks. Guarded by `sec`. */
    private var blocked: Boolean = false

    /**
     * Registers `callback` to receive assignment updates for [[target]] through an underlying
     * `store`.
     */
    def watchAssignments(store: Store, callback: ValueStreamCallback[Assignment]): Cancellable =
      sec.callCancellable {
        val blockableCallback = new BlockableWatchAssignmentCallback(sec, callback, blocked)
        callbacks += blockableCallback
        val baseCancellable: Cancellable = store.watchAssignments(target, blockableCallback)
        (reason: Status) =>
          sec.run {
            callbacks -= blockableCallback
            baseCancellable.cancel(reason)
          }
      }

    /** Sets the blocked status for all current and future registered callbacks to `blocked`. */
    def setBlocked(blocked: Boolean): Unit = {
      sec.assertCurrentContext()
      this.blocked = blocked
      for (callback: BlockableWatchAssignmentCallback <- callbacks) {
        callback.setBlocked(blocked)
      }
    }
  }

  /**
   * A [[ValueStreamCallback]] which supports deferring invoking callbacks until they are manually
   * unblocked later.
   */
  private class BlockableWatchAssignmentCallback(
      sec: SequentialExecutionContext,
      wrappedCallback: ValueStreamCallback[Assignment],
      private var blocked: Boolean)
      extends ValueStreamCallback[Assignment](sec) {

    /**
     * Assignments which were received while this callback were blocked and will be forwarded to the
     * wrapped callback once unblocked.
     */
    private val deferredCallbacks = mutable.ArrayBuffer[Assignment]()

    /** Sets the blocked status to be `blocked`. */
    def setBlocked(blocked: Boolean): Unit = {
      sec.assertCurrentContext()
      this.blocked = blocked
      if (!blocked) {
        // We're now unblocked, forward the previously queued assignments to the caller in order.
        for (assignment: Assignment <- deferredCallbacks) {
          wrappedCallback.executeOnSuccess(assignment)
        }
        deferredCallbacks.clear()
      }
    }

    override protected def onSuccess(value: Assignment): Unit = {
      sec.assertCurrentContext()
      if (blocked) {
        deferredCallbacks += value
      } else {
        wrappedCallback.executeOnSuccess(value)
      }
    }

  }
}
