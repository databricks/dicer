package com.databricks.caching.util

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}
import scala.collection.mutable
import io.grpc.Status
import com.databricks.caching.util.Lock.withLock

import scala.concurrent.{Future, Promise}
import scala.util.Failure

import com.databricks.caching.util.WatchCell.{Active, Initial, State, Terminal}

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

  /**
   * The lock used to guard the internal state.
   *
   * CONCURRENCY CONTROL STRATEGY:
   *
   * We achieve wait-free reads by combining this lock with the volatile [[state]] reference:
   *
   * [[getLatestValueOpt]] and [[getStatus]] both read [[state]] without acquiring the lock,
   * allowing reads to proceed concurrently with writes.
   *
   * Writes to [[state]], reads and writes to [[watchCallbacks]], and watch callback invocations are
   * guarded by [[lock]], so the public methods other than the ones mentioned above follow the usual
   * coarse-grained locking approach and acquire `lock` as a first step. This guarantees that the
   * order in which the callbacks receive written values is consistent with the order in which the
   * values are applied to [[state]]. It also guarantees that multiple reads of [[state]] would
   * observe the same value (the lock guards against intervening writes). However, while this would
   * be correct, we avoid the unnecessary expense of multiple volatile reads by using local derived
   * values instead (at worst, it would be possible to capture the entire state reference in a local
   * reference).
   *
   * Compared to a pure ReentrantLock approach where every method, including reads, simply acquires
   * the lock, this approach achieves significantly better read throughput at the cost of a minor
   * hit to write throughput (due to volatile write overhead). This is a worthwhile tradeoff given
   * that most workloads are read-heavy.
   */
  private val lock = new ReentrantLock()

  /**
   * The current state of the cell. Transitions are guarded by [[lock]] and involve swapping the
   * pointer to a new [[State]] instance.
   *
   * See the comments above for the concurrency control strategy and @volatile.
   */
  @volatile private var state: State[T] = new Initial[T]

  /** The set of callbacks watching this cell. */
  @GuardedBy("lock")
  private val watchCallbacks = new mutable.HashSet[StreamCallback[T]]

  /**
   * The set of promises that the Futures returned by [[notifyInitial()]] are watching, and will be
   * completed when the initial value is supplied or the error status is set.
   *
   * Rather than using a single Promise to back all the [[notifyInitial()]] calls, we use a new
   * Promise for each [[notifyInitial()]] call and store them here, so when the call is cancelled,
   * its corresponding backing Promise can be garbage collected.
   */
  @GuardedBy("lock")
  private val notifyInitialPromises = new mutable.HashSet[Promise[Unit]]

  // Consumer calls.

  override def watch(callback: StreamCallback[T]): Cancellable = withLock(lock) {
    require(watchCallbacks.add(callback), s"Callback already present in set: $callback")
    state match {
      case _: Initial[T] =>
      case active: Active[T] =>
        callback.executeOnSuccess(active.value.value)
      case terminal: Terminal[T] =>
        callback.executeOnFailure(terminal.status)
        watchCallbacks.remove(callback)
    }
    (_: Status) =>
      withLock(lock) {
        watchCallbacks.remove(callback)
      }
  }

  override def getLatestValueOpt: Option[T] = {
    state match {
      case _: Initial[T] =>
        None
      case active: Active[T] =>
        active.value
      case terminal: Terminal[T] =>
        terminal.valueOpt
    }
  }

  override def getStatus: Status = {
    state match {
      case _: Initial[T] | _: Active[T] =>
        Status.OK
      case terminal: Terminal[T] =>
        terminal.status
    }
  }

  override def notifyInitial(): (Future[Unit], Cancellable) = withLock(lock) {
    state match {
      case _: Initial[T] =>
        val promise = Promise[Unit]()
        notifyInitialPromises.add(promise)

        val cancellable = new Cancellable {
          override def cancel(reason: Status): Unit = withLock(lock) {
            // When the observation is cancelled, complete the Future and clean up the backing
            // promise.
            promise.tryComplete(Failure(reason.asRuntimeException()))
            notifyInitialPromises.remove(promise)
          }
        }
        (promise.future, cancellable)
      case _: Active[T] | _: Terminal[T] =>
        (Future.successful(()), new Cancellable {
          override def cancel(reason: Status): Unit = {
            /* Successful Future, nothing to cancel. */
          }
        })
    }
  }

  // Producer calls.

  override def setValue(newValue: T): Unit = withLock(lock) {
    require(newValue != null, "Value cannot be set to null")
    val newState: Active[T] = state match {
      case _: Initial[T] | _: Active[T] =>
        new Active[T](Some(newValue))
      case terminal: Terminal[T] =>
        throw new IllegalStateException(
          s"Setting value when an error already exists (${terminal.status}): $newValue"
        )
    }

    // Write the value before dispatching any callbacks so that they're guaranteed to observe
    // at least this value through `getLatestValueOpt`. Ordering is important because
    // `getLatestValueOpt` doesn't acquire the lock.
    state = newState

    for (callback: StreamCallback[T] <- watchCallbacks) {
      callback.executeOnSuccess(newValue)
    }
    for (promise: Promise[Unit] <- notifyInitialPromises) {
      promise.trySuccess(())
    }
    // Clean up all the backing promises.
    notifyInitialPromises.clear()
  }

  override def setErrorStatus(newStatus: Status): Unit = withLock(lock) {
    require(!newStatus.isOk, s"Cannot set status to be ok for error")
    val valueOpt: Option[T] = state match {
      case _: Initial[T] =>
        None
      case active: Active[T] =>
        active.value
      case terminal: Terminal[T] =>
        throw new IllegalStateException(
          s"Setting status when an error already exists (${terminal.status}): $newStatus"
        )
    }

    // Like in `setValue`, write the new state before dispatching callbacks.
    val newState = new Terminal[T](valueOpt, newStatus)
    state = newState

    for (callback: StreamCallback[T] <- watchCallbacks) {
      callback.executeOnFailure(newStatus)
    }
    watchCallbacks.clear()
    for (promise: Promise[Unit] <- notifyInitialPromises) {
      promise.trySuccess(())
    }
    // Clean up all the backing promises.
    notifyInitialPromises.clear()
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

    /**
     * Returns a Future that will be completed when the initial value of the WatchCell is
     * supplied, or the error status is set, whichever comes first. Also returns a Cancellable to
     * cancel this observation and fail the returned Future.
     */
    def notifyInitial(): (Future[Unit], Cancellable)
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

  /**
   * The current state of the cell. [[State]] instances are immutable (and thus thread-safe), and
   * changes to the state of the cell are made by pointing at new [[State]]s.
   */
  @ThreadSafe
  private sealed trait State[T]

  /** The state before the cell has received a value or error. */
  private class Initial[T] extends State[T]

  /** The state after the cell has received `value` but no error. */
  // We store Some[T] instead of T itself so that `getLatestValueOpt` doesn't have to allocate a new
  // Some instance on each call.
  private class Active[T](val value: Some[T]) extends State[T]

  /** The state after the cell has received a permanent error, preserving its latest value. */
  private class Terminal[T](val valueOpt: Option[T], val status: Status) extends State[T]
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

  override def notifyInitial(): (Future[Unit], Cancellable) = cell.notifyInitial()

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
