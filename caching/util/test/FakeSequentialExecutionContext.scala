package com.databricks.caching.util

import io.grpc.Status

import com.databricks.caching.util.Lock.withLock
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * A fake context that can be used to control when scheduled commands run. From the perspective of
 * the context, time only progresses when the [[FakeTypedClock]] returned by [[getClock]] is
 * advanced. The underlying implementation is shared with [[SequentialExecutionContext]], so the
 * behavior should be comparable.
 */
trait FakeSequentialExecutionContext extends SequentialExecutionContext {

  /**
   * Returns the fake clock used by the context. When this clock is advanced, the context is tickled
   * so that any pending commands as of the new time are started. Note that those pending commands
   * still complete asynchronously, and may trigger additional commands as well. There is no way to
   * determine when all pending and recursively triggered commands have completed.
   */
  override def getClock: FakeTypedClock

  /**
   * Advances this context's clock by `duration` on the context itself to ensure that any previously
   * enqueued tasks have completed before the clock advances. Blocks until the clock has been
   * advanced, so that all subsequent test code observes the advanced clock state.
   *
   * @param duration the amount of time to advance the clock by.
   */
  def advanceBySync(duration: FiniteDuration): Unit = {
    Await.result(call {
      getClock.advanceBy(duration)
    }, Duration.Inf)
  }

  /**
   * Returns the number of commands scheduled under `name` (the debugging label passed to
   * [[schedule]] / [[scheduleRepeating]]) that have neither started running nor been cancelled.
   */
  def pendingScheduledCount(name: String): Int
}

/** Factory methods. */
object FakeSequentialExecutionContext {

  /** The shared default pool for all fake contexts. */
  private val defaultPool = SequentialExecutionContextPool.create(
    poolName = "FakePool",
    numThreads = 2,
    alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
  )

  /**
   * Creates a fake context running on a shared pool. The underlying implementation is shared with
   * [[SequentialExecutionContext.create()]], so the behavior should be the same except for the fake
   * clock.
   *
   * @param name the name of the context.
   * @param fakeClockOpt the fake clock to use for the context. A new fake clock is created when not
   *                     present.
   * @param pool the underlying [[SequentialExecutionContextPool]] to use. The `defaultPool` is
   *             used when not specified.
   */
  def create(
      name: String,
      fakeClockOpt: Option[FakeTypedClock] = None,
      pool: SequentialExecutionContextPool = defaultPool): FakeSequentialExecutionContext = {
    val fakeClock: FakeTypedClock = fakeClockOpt.getOrElse(new FakeTypedClock())
    val baseContext = new SequentialExecutionContext.Impl(
      pool.name,
      name,
      pool.executorService,
      pool.exceptionHandler,
      fakeClock,
      pool.enableContextPropagation
    )

    // Register for callbacks from the [[fakeClock]] to flush pending work whenever the clock
    // advances. For example, if a command is scheduled to run in 1 hour, and the clock is advanced
    // by 1 hour, the command should run immediately. If we do not register a callback, it may take
    // an hour (in real time) for the command to run.
    fakeClock.registerCallback(baseContext.forTest.tickle)
    new DelegatingSequentialExecutionContext(baseContext) with FakeSequentialExecutionContext {
      override def getClock: FakeTypedClock = fakeClock

      /** Guards [[pendingCountsByName]]. */
      private val lock = new ReentrantLock()

      /** Count of currently-pending scheduled commands, keyed by their scheduling `name`. */
      @GuardedBy("lock")
      private val pendingCountsByName: mutable.Map[String, Int] =
        mutable.Map.empty[String, Int].withDefaultValue(0)

      override def schedule(
          name: String,
          delay: FiniteDuration,
          runnable: Runnable): Cancellable = {
        withLock(lock) { pendingCountsByName(name) += 1 }

        // A scheduled command leaves the pending set exactly once, whichever comes first: it starts
        // running or is cancelled (cancellation is best-effort, so both can still fire for one
        // command).
        var decremented: Boolean = false
        def markNoLongerPending(): Unit = withLock(lock) {
          if (!decremented) {
            decremented = true
            // Drop the entry when it returns to zero so the map doesn't retain a key per name for
            // the fake's lifetime; `pendingScheduledCount` reads absent names as 0 anyway.
            val newCount: Int = pendingCountsByName(name) - 1
            if (newCount == 0) {
              pendingCountsByName -= name
            } else {
              pendingCountsByName(name) = newCount
            }
          }
        }

        val trackedRunnable: Runnable = () => {
          markNoLongerPending()
          runnable.run()
        }
        val cancellable: Cancellable = super.schedule(name, delay, trackedRunnable)
        (reason: Status) => {
          markNoLongerPending()
          cancellable.cancel(reason)
        }
      }

      override def pendingScheduledCount(name: String): Int =
        withLock(lock) { pendingCountsByName(name) }
    }
  }
}
