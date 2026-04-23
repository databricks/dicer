package com.databricks.caching.util

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.collection.mutable

import io.grpc.Status

import com.databricks.caching.util.Lock.withLock

/**
 * This abstraction provides the notion of a "cell", a single value of type T that can be watched
 * by one or more subscribers. A single producer (or a coordinated set of producers) produce values
 * and the value is sent to the subscribers. If the watch cell is provided with an error, this
 * error is propagated to the subscribers and all subscribers are unsubscribed from the cell.
 * After this point no more values or errors can be provided to the cell. Subscribers watch the cell
 * using the `watch` call and terminate their watching using the `cancel` call. The producer
 * provides values using [[setValue]] and an error using [[setErrorStatus]]. At any point, the
 * caller can check the latest value (even after an error) using [[getLatestValueOpt]] or the final
 * error status using [[getStatus]]. This abstraction is thread-safe.
 */
@ThreadSafe
sealed class WatchCell[T] extends WatchCell.Consumer[T] with WatchCell.Producer[T] {

  /** The lock used to guard the internal state. */
  private val lock = new ReentrantLock()

  /** The latest value set by the caller, if any. */
  @GuardedBy("lock")
  private var value: Option[T] = None

  /** Error set by the caller, if any. */
  @GuardedBy("lock")
  private var status: Status = Status.OK

  /** The set of callbacks watching this cell. */
  @GuardedBy("lock")
  private val watchCallbacks = new mutable.HashSet[StreamCallback[T]]

  // Consumer calls.

  override def watch(callback: StreamCallback[T]): Cancellable = withLock(lock) {
    assert(watchCallbacks.add(callback), s"Callback already present in set: $callback")
    if (!status.isOk) { // If an error is set, that is what the caller is informed.
      informError()
    } else if (value.isDefined) { // If there is any value present, inform the caller.
      informSuccess(callback)
    }
    (_: Status) =>
      withLock(lock) {
        watchCallbacks.remove(callback)
      }
  }

  override def getLatestValueOpt: Option[T] = withLock(lock) {
    value
  }

  override def getStatus: Status = withLock(lock) {
    status
  }

  // Producer calls.

  override def setValue(newValue: T): Unit = withLock(lock) {
    assert(newValue != null, "Value cannot be set to null")
    assert(status.isOk, s" Setting value when an error already exists ($status): $newValue")
    value = Some(newValue)
    for (callback <- watchCallbacks) {
      informSuccess(callback)
    }
  }

  override def setErrorStatus(newStatus: Status): Unit = withLock(lock) {
    assert(!newStatus.isOk, s"Cannot set status to be ok for error")
    assert(status.isOk, s"Setting status when an error already exists ($status): $newStatus")
    status = newStatus
    informError()
  }

  // Private functions.

  /** Informs the watching subscriber about the current value in this cell via `callback`. */
  @GuardedBy("lock")
  private def informSuccess(callback: StreamCallback[T]): Unit = {
    assert(lock.isHeldByCurrentThread)
    assert(status.isOk && value.isDefined, s"Already an error $status or $value is none")
    callback.executeOnSuccess(value.get)
  }

  /** Informs all watching subscribers about the final error status and unregisters them. */
  @GuardedBy("lock")
  private def informError(): Unit = {
    assert(lock.isHeldByCurrentThread)
    assert(!status.isOk, s"No error exists")
    for (callback <- watchCallbacks) {
      callback.executeOnFailure(status)
    }
    watchCallbacks.clear()
  }
}

/** Declares "Producer" and "Consumer" behaviors for [[WatchCell]]. */
object WatchCell {

  /**
   * The consumer part of the [[WatchCell]]. Does *not* extend its [[WatchValueCell.Consumer]]
   * counterpart because such consumers accept watchers that cannot handle errors.
   */
  trait Consumer[T] {

    /**
     * REQUIRES: `callback` is not currently being used with this [[WatchCell]].
     *
     * Starts a watch of this cell. Values will be provided to `callback`. If there is a value
     * already present in the cell, that value will be provided followed by any other values that
     * come later. NOTE: the abstraction only guarantees that the latest value is provided to the
     * subscriber. For example, if the producer produces values in quick succession, this
     * abstraction is allowed to suppress the intermediate values and just provide some or none of
     * them. It is guaranteed to provide the latest value.
     *
     * If the watch is already in an error state, provides the error status and does not register
     * the `callback` with this cell.
     */
    def watch(callback: StreamCallback[T]): Cancellable

    /** Returns the latest value provided using [[Producer.setValue]], if any. */
    def getLatestValueOpt: Option[T]

    /**
     * Returns the error status provided by [[Producer.setErrorStatus]]. If no such call has been
     * made, returns [[Status.OK]].
     */
    def getStatus: Status
  }

  /**
   * The producer part of the [[WatchCell]]. Extends [[WatchValueCell.Producer]] with the ability to
   * write terminal errors to the underlying cell.
   */
  trait Producer[T] extends WatchValueCell.Producer[T] {

    /**
     * REQUIRES: `newStatus` is not `ok` and the cell already has not received an error.
     *
     * Sets the final state of the cell to be error corresponding to `status`.
     */
    def setErrorStatus(newStatus: Status): Unit
  }
}

/** Extends [[ValueStreamCallback]] with a handler for the failure case. */
abstract class StreamCallback[T](val sec: SequentialExecutionContext)
    extends ValueStreamCallback[T](sec) {

  /** Run `onFailure` on the `SequentialExecutionContext`. */
  final def executeOnFailure(status: Status): Unit = sec.run { onFailure(status) }

  /**
   * Implemented by the subclass - work done if an error status is received. The caller must ensure
   * that the status is not Status.OK.
   */
  protected def onFailure(status: Status): Unit
}

/**
 * An alternative to [[WatchCell]] that does not allow errors to be set: [[getStatus]] always
 * returns `OK` and registered callbacks never need to handle the `onFailure` case. As such, callers
 * should generally prefer [[watch(ValueStreamCallback)]] to [[watch(StreamCallback)]], but both are
 * permitted so that the cell can be exposed as a [[WatchCell.Consumer]].
 */
sealed class WatchValueCell[T] extends WatchValueCell.Consumer[T] with WatchValueCell.Producer[T] {
  private val cell = new WatchCell[T]

  // Consumer calls.

  override def watch(callback: ValueStreamCallback[T]): Cancellable = {
    // We can use `unsafeToStreamCallback` because we know that the encapsulated `cell` will never
    // have an error set on it.
    cell.watch(callback.unsafeToStreamCallback)
  }

  override def getLatestValueOpt: Option[T] = cell.getLatestValueOpt

  override def watch(callback: StreamCallback[T]): Cancellable = {
    cell.watch(callback)
  }

  override def getStatus: Status = Status.OK

  // Producer calls.

  override def setValue(newValue: T): Unit = cell.setValue(newValue)
}

/** Declares "Producer" and "Consumer" behaviors for [[WatchValueCell]]. */
object WatchValueCell {

  /**
   * The consumer part of the [[WatchValueCell]]. Extends [[WatchCell.Consumer]] because from the
   * perspective of the consumer, a [[WatchValueCell]] is just a [[WatchCell]] that never emits an
   * error.
   */
  trait Consumer[T] extends WatchCell.Consumer[T] {

    /**
     * Starts a watch of this cell. Values will be provided to `callback`. If there is a value
     * already present in the cell, that value will be provided followed by any other values that
     * come later. NOTE: the abstraction only guarantees that the latest value is provided to the
     * subscriber. For example, if the producer produces values in quick succession, this
     * abstraction is allowed to suppress the intermediate values and just provide some or none of
     * them. It is guaranteed to provide the latest value.
     */
    def watch(callback: ValueStreamCallback[T]): Cancellable

    override def watch(callback: StreamCallback[T]): Cancellable
  }

  /**
   * The producer part of the [[WatchValueCell]]. Does *not* extend its [[WatchCell.Producer]]
   * counterpart because that contract would require this producer to accept errors.
   */
  trait Producer[T] {

    /**
     * REQUIRES: `newValue` is not `null` and the cell has not already received an error.
     *
     * Sets the value of the cell to be `newValue`.
     */
    def setValue(newValue: T): Unit
  }
}

/**
 * A callback that can be used by a "producer" and a "consumer". The consumer implements the
 * `onSuccess` methods that handles the value. The producer calls `executeOnSuccess` and that
 * ensures that the callbacks are executed on `sec`, the [[SequentialExecutionContext]] bound to
 * this callback.
 */
abstract class ValueStreamCallback[T](sec: SequentialExecutionContext) {

  /** Run `onSuccess` on `sec`. */
  final def executeOnSuccess(value: T): Unit = sec.run { onSuccess(value) }

  /** Implemented by the subclass - work done if a value is received.  */
  protected def onSuccess(value: T): Unit

  /**
   * Returns this callback as a [[StreamCallback]] that expects [[StreamCallback.onFailure]] will
   * never be called.
   */
  private[util] def unsafeToStreamCallback: StreamCallback[T] = {
    val valueCallback: ValueStreamCallback[T] = this
    new StreamCallback[T](sec) {
      override protected def onFailure(status: Status): Unit = {
        // $COVERAGE-OFF$: unsafeToStreamCallback must only be used when errors are not possible.
        throw new RuntimeException(s"Value callback should not receive failure: $status")
        // $COVERAGE-ON$
      }

      override protected def onSuccess(value: T): Unit = {
        valueCallback.onSuccess(value)
      }
    }
  }
}
