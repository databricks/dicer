package com.databricks.caching.util

import com.databricks.caching.util.Lock.withLock
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import com.databricks.caching.util.ExecutorUtil.ContextAwareExecutionContext

/**
 * A concurrency control abstraction which serializes concurrent access to a shared resource.
 * "Hybrid" refers to the fact that both asynchronous tasks and synchronous tasks can be scheduled
 * and serialized within the domain. For a fully cooperative, sequential domain, use
 * [[SequentialExecutionContext]] instead.
 *
 * Synchronous execution is performed via [[executeSync()]] (see warnings on using this method in
 * its spec), while asynchronous execution is done by scheduling `Pipeline` transformations on the
 * domain. All the asynchronous tasks are guaranteed to be executed in the order they are scheduled.
 *
 * Support for [[StateMachineDriver]] will be implemented in a future PR.
 *
 * Synchronous execution examples:
 *
 * {{{
 *   val domain: HybridConcurrencyDomain = ...
 *
 *   def func(): T = ...
 *
 *   // `func` is executed here in the calling thread, and `result` will be of type T.
 *   val result: T = domain.executeSync {
 *     func()
 *   }
 * }}}
 *
 * Asynchronous execution examples:
 *
 * {{{
 *   val domain: HybridConcurrencyDomain = ...
 *
 *   // `func` is scheduled asynchronously in `domain`.
 *   Pipeline {
 *     func()
 *   }(domain).toFuture
 *
 *   // `func2` is scheduled asynchronously in `domain` after func1, which runs inline.
 *   Pipeline {
 *     func1()
 *   }(InlinePipelineExecutor)
 *   .map {
 *     func2()
 *   }(domain)
 * }}}
 */
@ThreadSafe
trait HybridConcurrencyDomain {

  /** Asserts that the caller is executing within this concurrency domain. */
  def assertInDomain(): Unit

  /**
   * Executes the given `thunk` on the calling thread in the domain. This will block if another task
   * is already active in the domain, so callers must be careful calling this method to avoid
   * starving shared, critical thread pools.
   */
  def executeSync[T](thunk: => T): T

  /** Returns the clock used by this domain to control the scheduling of delayed tasks. */
  def getClock: TypedClock

  /**
   * Schedules the given `runnable` in the future with a delay of `delay`. `name` is the runnable
   * name for debugging purposes. Returns a token that the caller can use to (best-effort) cancel
   * the scheduled work. The supplied `runnable` is executed asynchronously in this domain and
   * with the current attribution context. Only intended to be called by [[StateMachineDriver]].
   */
  private[util] def schedule(name: String, delay: FiniteDuration, runnable: Runnable): Cancellable

  /** See [[ExecutionContext.prepare()]]. */
  private[util] def prepare(): ExecutionContext
}

object HybridConcurrencyDomain {

  /**
   * Creates a new instance with the given name.
   *
   * @param enableContextPropagation controls whether tasks in the domain will always observe the
   *                                 caller's AttributionContext. Synchronous commands will always
   *                                 observe the caller's context, but asynchronous commands will
   *                                 only observe it if this is true.
   * @param alertOwnerTeam the team's registered alert routing name, e.g.
   *                       "platform-team" for Caching-owned domains, or "eng-my-team" for
   *                       domains owned by other teams.
   */
  def create(
      name: String,
      alertOwnerTeam: String,
      enableContextPropagation: Boolean): HybridConcurrencyDomain = {
    // In production, always use a dedicated thread for the internal executor. Tasks will block
    // on the internal task lock which we shouldn't do on any shared threads.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"$name-internal-sec",
      enableContextPropagation = enableContextPropagation,
      alertOwnerTeam = alertOwnerTeam
    )
    new Impl(name, sec, enableContextPropagation)
  }

  /**
   * REQUIRES: `sec` must enable/disable context propagation based on `enableContextPropagation`.
   *
   * Implementation note: we could avoid declaring the above requirement by simply creating the SEC
   * within the class. However, we must support injecting the SEC here to allow tests to pass a fake
   * SEC with a fake clock.
   *
   * Concurrency control: all tasks within the domain are serialized using `taskLock`. `sec` is not
   * used for concurrency control and only for scheduling. We don't use a
   * ScheduledExecutorService here because we aren't aware of any implementations for which you can
   * use a fake clock, which means that tests which depend on scheduling would be required to wait
   * for real time to pass.
   */
  private class Impl(
      name: String,
      sec: SequentialExecutionContext,
      enableContextPropagation: Boolean)
      extends HybridConcurrencyDomain {

    /** Guards execution of all tasks, sync and async, within the domain. */
    private val taskLock = new ReentrantLock()

    private val contextAwareExecutionContext: ContextAwareExecutionContext =
      ExecutorUtil.Internal.wrapExecutionContext(
        name = name,
        // Create an `ExecutionContext` which delegates to `sec`, but decorates incoming `Runnables`
        // by ensuring that they run with the task lock held.
        new ExecutionContext {
          override def execute(runnable: Runnable): Unit = sec.execute(asLockedRunnable(runnable))

          override def reportFailure(cause: Throwable): Unit = sec.reportFailure(cause)
        },
        enableContextPropagation = enableContextPropagation
      )

    override def assertInDomain(): Unit = {
      assert(taskLock.isHeldByCurrentThread)
    }

    override def executeSync[T](thunk: => T): T = {
      withLock(taskLock) {
        thunk
      }
    }

    override def getClock: TypedClock = sec.getClock

    override private[util] def schedule(
        name: String,
        delay: FiniteDuration,
        runnable: Runnable): Cancellable = {
      sec.schedule(name, delay, asLockedRunnable(runnable))
    }

    override private[util] def prepare(): ExecutionContext = contextAwareExecutionContext.prepare()

    /** Returns a new [[Runnable]] which executes `wrapped` while holding the internal lock. */
    private def asLockedRunnable(wrapped: Runnable): Runnable = { () =>
      withLock(taskLock) {
        wrapped.run()
      }
    }
  }

  object forTest {

    /**
     * REQUIRES: `sec` must enable/disable context propagation based on `enableContextPropagation`.
     *
     * Returns a [[HybridConcurrencyDomain]] allowing the caller to directly inject an SEC to
     * support testing with a fake clock.
     */
    def create(
        name: String,
        sec: SequentialExecutionContext,
        enableContextPropagation: Boolean): HybridConcurrencyDomain = {
      new Impl(name, sec, enableContextPropagation)
    }
  }
}
