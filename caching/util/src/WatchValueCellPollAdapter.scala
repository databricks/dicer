package com.databricks.caching.util

import javax.annotation.concurrent.{GuardedBy, ThreadSafe}
import io.grpc.Status

import scala.concurrent.duration.FiniteDuration

/**
 * An abstraction for implementing a [[WatchValueCell]] on top of a producer that only supports
 * polling for determining when the underlying value has changed.
 *
 * @param initialValueOpt       Optional initial value of the transformed type. If None, the cell
 *                              will not have a value until the first poll completes.
 * @param poller                A code function that polls the watched value.
 * @param transform             The code function that transforms the raw value to the parsed one.
 * @param pollInterval          The finite duration of the interval between each value poll.
 * @param sec                   A sequential execution context for scheduling and protecting mutable
 *                              state. Blocking work may be performed on this execution context.
 *
 * @tparam T                The type for the raw value.
 * @tparam R                The type for the parsed value.
 *
 * The producer starts to poll the raw value every `pollInterval` using `poller` at startup.
 * Once it gets the value, it applies `transform` to get the parsed value. The periodical poll
 * will be canceled if `cancel()` is called.
 * The consumer can register its callback by calling the `watch()` function.
 */
@ThreadSafe
sealed class WatchValueCellPollAdapter[T, R](
    initialValueOpt: Option[R],
    poller: () => T,
    transform: T => R,
    pollInterval: FiniteDuration,
    sec: SequentialExecutionContext)
    extends WatchValueCell.Consumer[R]
    with Cancellable {

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

  override def getLatestValueOpt: Option[R] = cell.getLatestValueOpt

  override def getStatus: Status = cell.getStatus

  /** Executes a single poll operation. Must be called from the sec context. */
  private def executePoll(): Unit = {
    sec.assertCurrentContext()

    val latestValueOpt: Option[R] = cell.getLatestValueOpt

    val newValueRaw: T = poller()
    val newValue: R = transform(newValueRaw)

    // Update the value if: (1) there's no existing value, or (2) the value has changed.
    if (!latestValueOpt.contains(newValue)) {
      cell.setValue(newValue)
    }
  }
}
