package com.databricks.caching.util

import javax.annotation.concurrent.{GuardedBy, ThreadSafe}
import io.grpc.Status

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * An abstraction for implementing a [[WatchValueCell]] on top of a producer that only supports
 * polling for determining when the underlying value has changed.
 *
 * @param initialValueOpt       Optional initial parsed value. If None, the cell will not have a
 *                              parsed value until the first poll completes.
 * @param poller                The function that polls the raw value.
 * @param update                The function that computes the next parsed value from the latest
 *                              published value, if any, and the newly polled raw value. If update
 *                              returns a value equal to the latest published value, the cell is
 *                              not updated, and existing watchers are not notified.
 * @param pollInterval          The finite duration of the interval between each value poll. Must
 *                              be strictly positive.
 * @param sec                   A sequential execution context for scheduling and protecting mutable
 *                              state. Blocking work may be performed on this execution context.
 *
 * @tparam T                    the type of the raw polled value.
 * @tparam R                    the type of the parsed value published by the cell.
 *
 * Once [[start()]] is called, the adapter immediately polls using `poller`, then polls every
 * `pollInterval`. After each poll, `update` computes the next parsed value from the latest
 * published value and the newly polled raw value. Polling and updating execute serially on `sec`.
 *
 * Periodic polling is canceled by [[cancel()]]. Consumers register callbacks through [[watch()]].
 *
 * @throws IllegalArgumentException if [[pollInterval]] is not strictly positive.
 */
@ThreadSafe
sealed class WatchValueCellPollAdapter[T, R] @throws[IllegalArgumentException]()(
    initialValueOpt: Option[R],
    poller: () => T,
    update: (Option[R], T) => R,
    pollInterval: FiniteDuration,
    sec: SequentialExecutionContext)
    extends WatchValueCell.Consumer[R]
    with Cancellable {

  require(pollInterval.toNanos > 0, "pollInterval must be strictly positive")

  /** The cell to watch the value. */
  private val cell = new WatchValueCell[R]
  for (initialValue <- initialValueOpt) {
    cell.setValue(initialValue)
  }

  /** The poller that periodically polls value that starts at startup. */
  @GuardedBy("sec")
  private var pollerCancellableOpt: Option[Cancellable] = None

  /**
   * Starts the periodic polling of the value. The first poll is executed immediately, and
   * subsequent polls are executed at intervals of `pollInterval`.
   *
   * This is a no-op if called multiple times (if start() is called again after cancel(), the poller
   * stays canceled).
   */
  def start(): Unit = sec.run {
    if (pollerCancellableOpt.isEmpty) {
      // Execute the first poll immediately. Ensure we schedule follow-up polls even if the first
      // poll throws, matching the behavior of scheduleRepeating.
      try {
        executePoll()
      } finally {
        // Schedule subsequent polls.
        pollerCancellableOpt = Some(
          sec.scheduleRepeating(
            "periodical poller",
            pollInterval,
            () => {
              executePoll()
            }
          )
        )
      }
    }
  }

  override def watch(callback: ValueStreamCallback[R]): Cancellable = {
    cell.watch(callback)
  }

  override def watch(callback: StreamCallback[R]): Cancellable = {
    cell.watch(callback)
  }

  override def cancel(reason: Status = Status.CANCELLED): Unit = sec.run {
    // No-op if we haven't started.
    for (cancellable: Cancellable <- pollerCancellableOpt) {
      cancellable.cancel(reason)
    }
  }

  override def notifyInitial(): (Future[Unit], Cancellable) = cell.notifyInitial()

  override def getLatestValueOpt: Option[R] = cell.getLatestValueOpt

  override def getStatus: Status = cell.getStatus

  /** Executes a single poll operation. Must be called from the sec context. */
  private def executePoll(): Unit = {
    sec.assertCurrentContext()

    val latestValueOpt: Option[R] = cell.getLatestValueOpt

    val newValueRaw: T = poller()
    val newValue: R = update(latestValueOpt, newValueRaw)

    // Update the value if: (1) there's no existing value, or (2) the value has changed.
    if (!latestValueOpt.contains(newValue)) {
      cell.setValue(newValue)
    }
  }
}
