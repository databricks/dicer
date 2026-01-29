package com.databricks.caching.util

import com.databricks.caching.util.SequentialExecutionContextPool.{
  ExceptionHandler,
  UncaughtExceptionSource
}
import java.util
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration.{Duration, FiniteDuration, NANOSECONDS}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

import io.grpc.Status
import io.prometheus.client.{Counter, Gauge, Histogram}

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.ContextAwareUtil.{
  ContextAwareExecutionContext,
  ContextAwareScheduledExecutorService
}

/**
 * This type provides an abstraction of a thread of execution such that each piece of work run or
 * scheduled on it is executed sequentially one at a time, i.e., not concurrently. These execution
 * contexts are supposed to be used as part of a cooperative task management paradigm where each
 * context guards some coarse-grained piece of state. One can think of these contexts as
 * "coarse-grained" locks with a nice property that one does have to explicitly acquire or release
 * locks. However, it is the responsibility of each work item to not use the execution context for a
 * "long time" or block the execution context (since the remaining items will simply wait for such
 * long/blocking work items to finish).
 *
 * ORDERING
 *
 * The context is a FIFO abstraction. Commands added to the context with zero delay -- e.g. via
 * `execute`, `run`, or `call` -- will run in the order they are added. An ordering guarantee
 * applies not only to such "immediate" commands, but to all commands with non-negative delays:
 *
 *     A is added with delay X before B is added with delay Y, and X <= Y ==> A runs before B
 *
 * For example:
 *
 * {{{
 *   // a() runs before b()
 *   sec.run { a() }
 *   sec.run { b() }
 *
 *   // c() runs before d()
 *   sec.schedule("c", 10.seconds, c)
 *   sec.schedule("d", 10.seconds, d)
 *
 *   // e() runs before f()
 *   sec.run { e() }
 *   sec.schedule("f", Duration.Zero, f)
 *
 *   // g() runs before h()
 *   sec.schedule("g", Duration.Zero, g)
 *   sec.run { h() }
 *
 *   // i() runs before j()
 *   sec.schedule("i", 2.seconds, i)
 *   sec.schedule("j", 3.seconds, j)
 *
 *   // k() runs before l()
 *   sec.run { k() }
 *   sec.schedule("l", 1.nanosecond, l)
 *
 *   // Ordering of m and n is non-deterministic (how much delay between calls?)
 *   sec.schedule("m", 1.nanosecond, m)
 *   sec.run { n() }
 * }}}
 *
 * Internally, the `WorkItemQueue` data structure provides this guarantee.
 *
 * FAIRNESS
 *
 * A common failure mode in executor implementations is starvation of scheduled commands when the
 * queue is backlogged with immediate commands. This can have serious consequences. Consider for
 * example what happens when scheduled background garbage collection is deprioritized because a
 * system is heavily loaded: accumulation of garbage makes all work more expensive, which
 * exacerbates the work backlog, and you have a cascade failure. This context implements a simple
 * policy to prevent this failure: when a command is added, either for immediate execution or with
 * some delay, its "desired execution time" is recorded (basically, "now" plus some non-negative
 * delay), and commands are executed in order of their desired execution time. When two commands
 * have the same desired execution time, the command that was added first runs first. The effect of
 * this policy is that all commands, whether scheduled or immediate, experience the same delay
 * relative to their desired execution time based on the current backlog in the context.
 *
 * Internally, the `WorkItemQueue` data structure implements this policy.
 *
 * CANCELLATION
 *
 * The execution context supports cancellation of scheduled commands even though futures in Scala do
 * not directly support it. (Commands introduced for "immediate" execution cannot be cancelled.)
 * This implementation avoids two pitfalls of cancellation implementations:
 *
 *  - Cancelling does not require a scan of all scheduled commands. (We have seen implementations
 *    that scan the schedule heap to find cancelled commands to remove; internally, our
 *    implementation relies on [[IntrusiveMinHeap]] which has O(log(n)) removal of elements.)
 *  - Cancelled commands do not occupy any memory. (We have seen implementations that leave stub
 *    entries in the schedule heap that get cleared only when the cancelled command would have run
 *    if not cancelled. This becomes prohibitively expensive in cancellation-heavy workloads, e.g.
 *    request hedging.)
 *
 * For additional background on the concurrency patterns encouraged and supported by this
 * abstraction, see <internal link>.
 */
trait SequentialExecutionContext {

  @deprecated("Use `asExecutionContext` instead", "<internal bug>")
  final def asNamedExecutor: ContextAwareExecutionContext = contextAwareExecutionContext

  /**
   * Schedules the given `runnable` in the future with a delay of `delay`. `name` is the runnable
   * name for debugging purposes. Returns a token that the caller can use to (best-effort) cancel
   * the scheduled work. The supplied `runnable` is executed on this executor and with the current
   * attribution context.
   */
  def schedule(name: String, delay: FiniteDuration, runnable: Runnable): Cancellable

  /** The clock associated with this context. */
  def getClock: TypedClock

  /**
   * Ensures that the calling code is running on `this` context's executor, i.e., the thread's
   * current executor is this context's executor.
   */
  def assertCurrentContext(): Unit

  /** The name of the execution context, for debugging purposes.  */
  def getName: String

  /**
   * Returns a future completed with the value of `func`, which runs on this executor and with the
   * current attribution context.
   */
  final def call[T](func: => T): Future[T] = Future { func }(this.prepare())

  /**
   * Returns a future completed with the value of the future returned by `func`, which runs on this
   * executor and with the current attribution context.
   *
   * Why is this a flattening operation? It's probably easiest to see if we break it up into two
   * steps:
   *
   * {{{
   *   val sec: SequentialExecutionContext = ...
   *   val func: (=> Future[Int]) = Future.successful(42)
   *
   *   // Step 1: call `func` on `sec`.
   *   val callResult: Future[Future[Int]] = sec.call { func }
   *
   *   // Step 2: flatten the resulting future of future.
   *   val flattenedCallResult: Future[Int] = callResult.flatten
   * }}}
   *
   * Basically, `func` is called in the future, and it returns a `Future`, so the intermediate type
   * is `Future[Future[T]]`, which is then "flattened" to a `Future[T]`.
   */
  final def flatCall[T](func: => Future[T]): Future[T] = {
    val callResult: Future[Future[T]] = call(func)
    callResult.flatten
  }

  /**
   * Returns a handle that can be used to cancel the operation initiated by `func`, which runs on
   * this executor and with the current attribution context. The cancellable returned by this `func`
   * also runs on this executor and with the current attribution context, but the returned handle
   * can be called from any thread.
   */
  final def callCancellable(func: => Cancellable): Cancellable = {
    // A reference to the cancellable to be populated in this context.
    var cancellable: Cancellable = null

    // Prepare an execution context bound to the current attribution context that can be used for
    // cancellation.
    val preparedEc: ExecutionContext = contextAwareExecutionContext.prepare()
    preparedEc.execute(() => {
      cancellable = func
    })
    (reason: Status) =>
      preparedEc.execute(() => {
        // Because this is a sequential execution context, we know this command will run after
        // `func` is executed and `cancellable` has been set. We nonetheless check for `null` here
        // because `func` may throw. In that case, there's hopefully no operation to cancel, and
        // definitely no handle with which to cancel it.
        if (cancellable != null) {
          cancellable.cancel(reason)
        }
      })
  }

  /** Runs `func` on this executor and with the current attribution context. */
  def run[U](func: => U): Unit

  /**
   * Schedules the given `runnable` to run at the given time `interval`. The next work is scheduled
   * when the previous one finishes - so there is always a gap of `interval` between each run.
   * `name` is used for debugging. Returns a `Cancellable` that can be used to cancel the repeated
   * work.
   */
  final def scheduleRepeating(
      name: String,
      interval: FiniteDuration,
      runnable: Runnable): Cancellable = {

    /** Thread-safe state associated with the repeating command. */
    class State extends Cancellable {
      val lock = new ReentrantLock()

      /**
       * A handle that can be used to cancel any currently scheduled run (populated in
       * [[scheduleNext()]]).
       */
      private var cancellable: Cancellable = _

      /** Whether the overall repeating op has been cancelled. */
      private var isCancelled = false

      /** Schedules the next run, and repeats after it executes. */
      def scheduleNext(): Unit = withLock(lock) {
        // Only schedule if the overall repeating op has not been cancelled.
        if (!isCancelled) {
          cancellable = schedule(
            name,
            interval,
            () => {
              try {
                runnable.run()
              } finally {
                // Exceptions thrown by `runnable` bubble up, but we schedule the next action in a
                // finally block to avoid wedging when they are thrown.
                scheduleNext()
              }
            }
          )
        }
      }

      override def cancel(reason: Status): Unit = withLock(lock) {
        if (!isCancelled) {
          // Prevent scheduleNext() from scheduling new work items.
          isCancelled = true

          // Best-effort cancel currently scheduled work item.
          if (cancellable != null) {
            cancellable.cancel(reason)
            cancellable = null
          }
        }
      }
    }
    val state = new State

    // Schedule the first run (and recursively schedule subsequent runs).
    state.scheduleNext()
    state
  }

  override final def toString: String = getName

  /** See [[ContextAwareUtil]]. */
  private[util] val contextAwareExecutionContext: ContextAwareExecutionContext
}

/** Factory methods and static state for the context. */
object SequentialExecutionContext {

  /** The execution context being run on the local thread, if any. */
  private val currentContext = new ThreadLocal[Impl]

  private object Metrics {
    private val LABEL_NAMES: Array[String] = Array("pool_name", "context_name")

    // TODO(<internal bug>): Are powers of ten really the only bucket boundaries we need? Consider
    //  changing these after getting more empirical data.
    // Buckets ranging from 1μs to 1000s.
    private val EXECUTION_LATENCY_BUCKETS_NSECS: Array[Double] =
      Array(1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12)

    val executionTimeHistogram: Histogram = Histogram
      .build()
      .name("sequential_execution_context_execution_duration_nanoseconds")
      .labelNames(LABEL_NAMES: _*)
      .help(
        "Histogram of time (in nanoseconds) the context has spent executing commands."
      )
      .buckets(EXECUTION_LATENCY_BUCKETS_NSECS: _*)
      .register()

    val executionDelayHistogram: Histogram = Histogram
      .build()
      .name("sequential_execution_context_execution_delay_duration_nanoseconds")
      .labelNames(LABEL_NAMES: _*)
      .help(
        "Histogram of time (in nanoseconds) that commands have waited to execute (beyond " +
        "their scheduled time) on this context."
      )
      .buckets(EXECUTION_LATENCY_BUCKETS_NSECS: _*)
      .register()

    val pendingCommandsImmediateGauge: Gauge = Gauge
      .build()
      .help("Number of pending commands with 0 or negative execution delay (ready to execute)")
      .name("sequential_execution_context_pending_commands_immediate")
      .labelNames(LABEL_NAMES: _*)
      .register()

    val pendingCommandsScheduledGauge: Gauge = Gauge
      .build()
      .help("Number of pending commands with positive execution delay (not ready to execute)")
      .name("sequential_execution_context_pending_commands_scheduled")
      .labelNames(LABEL_NAMES: _*)
      .register()

    /** The possible reasons for a spurious wakeup. */
    object SpuriousWakeupReason extends Enumeration {
      val ALREADY_RUNNING, NO_PENDING_COMMANDS = Value
    }

    val spuriousWakeupsCounter: Counter = Counter
      .build()
      .help("Number of spurious wakeups on this context.")
      .name("sequential_execution_context_spurious_wakeups_total")
      .labelNames(LABEL_NAMES :+ "reason": _*)
      .register()
  }

  /** Converts `context` to an [[ExecutionContext]]. */
  implicit def asExecutionContext(
      context: SequentialExecutionContext): ContextAwareExecutionContext = {
    context.contextAwareExecutionContext
  }

  /** Creates a context backed by the given pool. */
  def create(pool: SequentialExecutionContextPool, name: String): SequentialExecutionContext = {
    new Impl(
      poolName = pool.name,
      name = name,
      pool.executorService,
      pool.exceptionHandler,
      RealtimeTypedClock,
      pool.enableContextPropagation
    )
  }

  /** Creates a context backed by its own (single-thread) pool. */
  def createWithDedicatedPool(
      name: String,
      enableContextPropagation: Boolean = true): SequentialExecutionContext = {
    val pool = SequentialExecutionContextPool.create(
      s"$name-pool",
      numThreads = 1,
      enableContextPropagation
    )
    pool.createExecutionContext(contextName = name)
  }


  /**
   * The production implementation of [[SequentialExecutionContext]]. Separate from the
   * [[SequentialExecutionContext]] trait to allow modified behavior in tests.
   *
   * @param poolName Name of the underlying [[SequentialExecutionContextPool]], for monitoring
   *                 and debugging purposes.
   * @param name Name of the context, for debugging and monitoring purposes.
   * @param executorService The underlying executor service that runs or schedules the commands.
   * @param exceptionHandler The handler for uncaught exceptions.
   * @param clock The clock used to measure the passage of time.
   * @param enableContextPropagation Whether to propagate the current attribution context to
   *                                 commands run on this execution context.
   */
  private[util] final class Impl(
      poolName: String,
      name: String,
      executorService: ContextAwareScheduledExecutorService,
      exceptionHandler: ExceptionHandler,
      clock: TypedClock,
      enableContextPropagation: Boolean)
      extends SequentialExecutionContext {

    /** Lock protecting all context state. */
    private val lock = new ReentrantLock()

    private[util] override val contextAwareExecutionContext: ContextAwareExecutionContext =
      ContextAwareUtil.wrapExecutionContext(
        name,
        new ExecutionContext {
          override def execute(runnable: Runnable): Unit = {
            // When a runnable is enqueued in this path, it has already been made context-aware
            // by the `ContextAwareUtil.wrapExecutionContext` wrapper. We enqueue the runnable
            // with zero delay relative to "now", and then call `ensureScheduled` to make sure the
            // current execution context is scheduled to run on one of the underlying thread pool
            // threads.
            val now: TickerTime = clock.tickerTime()
            withLock(lock) {
              queue.push(now, Duration.Zero, runnable)
              ensureScheduled(now)
            }
          }

          override def reportFailure(cause: scala.Throwable): Unit = {
            exceptionHandler.handleUncaughtException(UncaughtExceptionSource.Command, name, cause)
          }
        },
        enableContextPropagation = enableContextPropagation
      )

    /**
     * Runnable calling [[tickle]] that is added to `pool` when there is work to do. Created once to
     * avoid repeated allocations.
     *
     * Notice that per locking discipline, this callback is invoked while holding the state lock for
     * the execution context.
     */
    private val tickleRunnable: Runnable = () => withLock(lock) { tickle() }

    /** Contains all pending [[WorkItem]]s. */
    private val queue = new WorkItemQueue

    /** State of the context! See [[State]] class docs for background. */
    private var state = State.PENDING

    // References to labeled metrics (so that we don't need to repeatedly resolve them).
    private val executionDelayHistogram: Histogram.Child =
      Metrics.executionDelayHistogram.labels(poolName, name)
    private val executionTimeHistogram: Histogram.Child =
      Metrics.executionTimeHistogram.labels(poolName, name)
    private val pendingCommandsScheduledGauge: Gauge.Child =
      Metrics.pendingCommandsScheduledGauge.labels(poolName, name)
    private val pendingCommandsImmediateGauge: Gauge.Child =
      Metrics.pendingCommandsImmediateGauge.labels(poolName, name)

    /**
     * When in [[State.PENDING]], the time at which the `pool` is expected to [[tickle]] the
     * context. The pool may of course tickle us earlier or later than this time because of timer/
     * clock mismatches or backlogs. We use this value to avoid unnecessarily re-adding ourselves to
     * the executor when we're already scheduled to run soon enough.
     */
    private var nextTickleTime = TickerTime.MAX

    /**
     * When in [[State.PENDING]], a handle that can be used to (best-effort) cancel an outstanding
     * "tickle" task on `pool`. It is always possible for this handle to be None, since the pool
     * does not provide a handle for a task that is added for "immediate" execution.
     */
    private var tickleHandle: Option[ScheduledFuture[_]] = None

    override def run[U](func: => U): Unit = {
      val now: TickerTime = clock.tickerTime()
      val boundRunnable: Runnable =
        ContextAwareUtil.wrapRunnable(() => func, enableContextPropagation)
      withLock(lock) {
        queue.push(now, Duration.Zero, boundRunnable)
        ensureScheduled(now)
      }
    }

    override def getClock: TypedClock = {
      // Note that while internally in `WorkItemQueue` we use monotonic ticker time values, we
      // choose not to expose those monotonic values via `getClock`, or make monotonicity a general
      // feature of `TypedClock`. In general, ticker time (or `System.nanoTime`) is a lightweight
      // and useful feature for measuring durations with good precision. The fact that the
      // `SequentialExecutionContext`'s ordering guarantee relies on monotonicity does not, and
      // should not, leak into the lower-level clock abstraction.
      clock
    }

    /** See [[SequentialExecutionContext.assertCurrentContext()]]. */
    override def assertCurrentContext(): Unit = assert(currentContext.get() == this)

    override def schedule(name: String, delay: FiniteDuration, runnable: Runnable): Cancellable = {
      val now: TickerTime = clock.tickerTime()
      val boundRunnable: Runnable = ContextAwareUtil.wrapRunnable(
        runnable,
        enableContextPropagation = enableContextPropagation
      )
      withLock(lock) {
        val workItem: WorkItem = queue.push(now, delay, boundRunnable)
        val cancellable: Cancellable = (_: Status) =>
          withLock(lock) {
            workItem.cancel()
            // Call `ensureScheduled` to ensure that the next tickle time is updated if the
            // cancelled work item was the next one due to run. If there are no pending commands,
            // this will cancel the pending tickle task.
            ensureScheduled(clock.tickerTime())
          }
        ensureScheduled(now)
        cancellable
      }
    }

    override def getName: String = name

    /**
     * INTERNAL IMPLEMENTATION SECTION
     *
     * Locking discipline, see <internal link> for details:
     *
     *  - Private methods must only be called while holding `lock` (a single [[ReentrantLock]]
     *    protects all internal state for the context).
     *  - Corollary: all public methods and callbacks should immediately acquire the `lock`.
     *  - The lock must not be held while executing commands supplied to the context.
     *
     * High-level strategy:
     *
     *  - The context keeps track of all pending commands in [[WorkItemQueue]], which is responsible
     *    for ordering guarantees and implementing the fairness policy.
     *  - Commands are executed in the [[tickle]] method, which is in turn executed on the
     *    underlying `pool`. Every time it is tickled, the context checks for pending work, executes
     *    the next command if any, and then makes sure the context will be tickled again when the
     *    next command is due to run by calling [[ensureScheduled]].
     *  - When a command is added to the context (via `schedule`, `execute`, `run`, etc.), it is
     *    added to the [[WorkItemQueue]]. Then [[ensureScheduled]] is called to make sure that
     *    `tickle` will be called at the appropriate time if the added command is the next one due
     *    to run.
     *  - In general, [[ensureScheduled]] tries to maintain at most a single outstanding `tickle`
     *    call on the underlying `pool` at a time, by (for example) cancelling any pending scheduled
     *    pool task before scheduling a new one. But cancellation is best-effort, so we need to
     *    account for spurious tickle calls (where no new work is discovered).
     *  - We can't use just `lock` to ensure sequential execution, as the lock must be released when
     *    calling a user-supplied command (see "locking discipline"). As a result, the executor
     *    keeps track of whether it's in the `PENDING` or `RUNNING` state. If [[tickle]] or
     *    [[ensureScheduled]] is called while the context is in the `RUNNING` state, they do
     *    nothing, relying on the `RUNNING` thread to advance the context as needed when the current
     *    command runs.
     *
     * Notable constraints:
     *
     *  - The implementation assumes that the `pool` will not execute commands synchronously on the
     *    calling thread. We would need to revisit our locking discipline were that to change.
     *  - Beyond releasing `lock` while running commands, locks are "maximally" acquired for the
     *    duration of all public methods and callbacks. Optimize with care! See notes on programming
     *    with locks in <internal link>.
     */
    /** A reference to the spurious wakeups counter for this context with labels set. */
    private def spuriousWakeupsCounter(reason: Metrics.SpuriousWakeupReason.Value): Counter.Child =
      Metrics.spuriousWakeupsCounter.labels(poolName, name, reason.toString)

    /**
     * Runs any work that is due to run and ensures that the context will be tickled again when work
     * is scheduled to run again.
     *
     * Calling tickle spuriously (when a command is already running, when there are no pending
     * commands, or when the next scheduled pending command isn't due yet) is permitted, but should
     * generally be avoided in the interest of avoiding unnecessary work.
     */
    @SuppressWarnings(
      Array(
        "CatchFatalExceptions",
        "reason:catch all exceptions to report to executor's uncaught exception handler"
      )
    )
    private def tickle(): Unit = {
      assert(lock.isHeldByCurrentThread)

      if (state == State.RUNNING) {
        // Spurious wakeup. This context is already running, so we can't run any commands
        // concurrently and we can the assume the RUNNING thread will call `ensureScheduled` when
        // it's done.
        spuriousWakeupsCounter(Metrics.SpuriousWakeupReason.ALREADY_RUNNING).inc()
        return
      }
      // Clear the pending tickle task so that in the next call to `ensureScheduled`, no existing
      // tickle task is assumed to exist.
      cancelTickleTask()

      val now: TickerTime = clock.tickerTime()
      val command: Option[(Runnable, TickerTime)] = queue.pollReadyCommand(now)
      command match {
        case None =>
          // A different kind of spurious wakeup: there are no pending commands. Ensure that this
          // executor will be tickled again when the next command is due.
          spuriousWakeupsCounter(Metrics.SpuriousWakeupReason.NO_PENDING_COMMANDS).inc()
          ensureScheduled(now)
        case Some(command) =>
          // Enter the running state to ensure that no concurrent work is done (violating the
          // "sequential" part of our contract) while the command is running.
          state = State.RUNNING

          // Set the current context so that `assertCurrentContext` is satisfied while the
          // command is running.
          currentContext.set(Impl.this)

          // Release the lock while the command is running, so that commands can be added to the
          // executor from other threads without blocking while the current command is running.
          lock.unlock()

          val startTime: TickerTime = clock.tickerTime()
          val executionDelay: FiniteDuration =
            (startTime - command._2).max(Duration.Zero)
          executionDelayHistogram.observe(executionDelay.toNanos)
          try {
            command._1.run()
          } catch {
            case ex: InterruptedException =>
              // Report the exception for our metrics, and restore the interrupted bit for the
              // underlying ExecutorService. This is more likely to be handled correctly vs.
              // rethrowing the InterruptedException, which would be unexpected from a Runnable in
              // Java (InterruptedException is checked, and Runnable declares no checked
              // exceptions).
              handleCommandException(ex)
              Thread.currentThread().interrupt()

            case ex: Throwable =>
              // Intercept all other exceptions and report them to the uncaught exception
              // handler. Note that the executor service on which we run would also catch these
              // exceptions just fine, but would not surface them in any way.
              handleCommandException(ex)
          } finally {
            val endTime: TickerTime = clock.tickerTime()
            val executionTime: FiniteDuration = (endTime - startTime).max(Duration.Zero)
            executionTimeHistogram.observe(executionTime.toNanos)

            // Reacquire the lock.
            lock.lock()

            // Do cleanup work in the finally block, to ensure that invariants are restored even
            // when a fatal exception is encountered.

            // We no longer want `assertCurrentContext` to be satisfied on this thread now that
            // the command has run.
            currentContext.remove()

            // We're responsible for ensuring that future work is scheduled after running a
            // command. Exit the RUNNING state, since `ensureScheduled` will/should do nothing
            // while it believes a command is running.
            state = State.PENDING
            ensureScheduled(now = clock.tickerTime())
          }
      }
    }

    /**
     * Cancels the pending tickle task (if any) and updates the internal state to reflect that no
     * tickle is currently pending.
     */
    private def cancelTickleTask(): Unit = {
      assert(lock.isHeldByCurrentThread)
      this.tickleHandle match {
        case Some(tickleHandle) =>
          tickleHandle.cancel( /*mayInterruptIfRunning=*/ false)
          this.tickleHandle = None
        case None =>
        // No handle... either no tickle is outstanding or the latest tickle used `pool.execute`
        // which returns no cancellation handle.
      }
      // Reset the next tickle time so that `ensureScheduled` knows that no tickles will occur if it
      // doesn't ask the `pool`.
      this.nextTickleTime = TickerTime.MAX
    }

    /** Ensures that any pending work will be scheduled. */
    private def ensureScheduled(now: TickerTime): Unit = {
      assert(lock.isHeldByCurrentThread)

      // Take this opportunity to update gauge metrics, since ensureScheduled is called when
      // adding commands to the executor, after running commands, and when cancelling scheduled
      // commands.
      pendingCommandsScheduledGauge.set(queue.getNumPendingScheduled)
      pendingCommandsImmediateGauge.set(queue.getNumPendingImmediate)

      state match {
        case State.RUNNING =>
        // Nothing to do. The thread that is running a command is responsible for calling
        // ensureScheduled when the command is done.
        case State.PENDING =>
          val (monotonicNow, desiredExecutionTime): (TickerTime, TickerTime) =
            queue.getMonotonicNowAndDesiredExecutionTime(now)
          if (desiredExecutionTime == TickerTime.MAX) {
            // No commands are currently pending. Cancel pending tickle task (if any).
            cancelTickleTask()
          } else if (desiredExecutionTime < this.nextTickleTime) {
            // Since the earliest pending command is due before the currently scheduled task, cancel
            // it and schedule a new task that runs earlier.
            cancelTickleTask()
            this.nextTickleTime = desiredExecutionTime
            val delay: FiniteDuration = desiredExecutionTime - monotonicNow
            if (delay <= Duration.Zero) {
              // Schedule for immediate execution.
              // TODO(<internal bug>): in this code path, we are not creating a tickle handle. So the
              //                  next time ensureScheduled is called, we will not know if there is
              //                  a tickle pending or not and may unnecessarily add another command
              executorService.execute(tickleRunnable)
            } else {
              // Schedule after the appropriate delay.
              tickleHandle = Some(
                executorService.schedule(tickleRunnable, delay.toNanos, NANOSECONDS)
              )
            }
          }
      }
    }

    private def handleCommandException(cause: Throwable): Unit = {
      exceptionHandler.handleUncaughtException(UncaughtExceptionSource.Command, name, cause)
    }

    private[util] object forTest {

      /**
       * Enqueues the `runnable` to run on the SEC, but does not ensure that the SEC is scheduled
       * to run it. Should only be used in tests where we'd like to ensure that a particular
       * invocation of [[forTest.tickle()]] finds this runnable.
       */
      def enqueue(runnable: Runnable): Unit = withLock(lock) {
        queue.push(
          clock.tickerTime(),
          Duration.Zero,
          ContextAwareUtil
            .wrapRunnable(runnable, enableContextPropagation = enableContextPropagation)
        )
      }

      /**
       * Forces the context to clear pending work due "now". Useful when [[clock]] is a fake that
       * advances faster than "real" time.
       *
       * IMPLEMENTATION NOTE: Synchronization is unnecessary because `pool` is thread-safe and
       * `tickleRunnable` is final/val. We therefore do not acquire the lock because it could mask a
       * real issues in concurrency tests and because there is no need to do so.
       */
      def tickle(): Unit = executorService.execute(tickleRunnable)
    }
  }

  /**
   * The possible states for the context. Either the context is RUNNING, which means that it is
   * executing a command, or it is in the PENDING state where it's waiting to be tickled by the
   * underlying thread-pool executor.
   */
  private[SequentialExecutionContext] object State extends Enumeration {
    val RUNNING, PENDING = Value
  }

  /**
   * Internal representation of an outstanding command. All accesses must be protected by the
   * owning [[SequentialExecutionContext]]'s lock.
   *
   * Extends [[IntrusiveMinHeapElement]] so that work items can be efficiently removed from the
   * context's scheduled work heap when they are cancelled.
   *
   * Not thread-safe. All interactions with [[WorkItem]] must be externally synchronized.
   */
  private class WorkItem private extends IntrusiveMinHeapElement[TickerTime] {

    /**
     * The command to run. Note that the context's lock should be released before running this
     * command, as it may be long-running. The command is cleared (to None) when this work item is
     * either cancelled via [[cancel]] or it is picked up for execution by the context using
     * [[takeRunnable]], so that any state pinned in the command's closure environment can be GCed.
     */
    private var runnableOpt: Option[Runnable] = None

    def this(runnable: Runnable, desiredExecutionTime: TickerTime) = {
      this()
      this.runnableOpt = Some(runnable)
      setPriority(desiredExecutionTime)
    }

    /** The time at which the command ought to run barring backlogs or timer delays. */
    def desiredExecutionTime: TickerTime = getPriority

    /**
     * Cancels execution of this work item. No-op if the work item has already been cancelled or
     * run.
     */
    def cancel(): Unit = {
      if (runnableOpt.isDefined) {
        runnableOpt = None

        // If the work item is currently attached to the context's heap, which tracks scheduled
        // work, we remove it to avoid leaking memory for commands that will never run.
        if (attached) {
          remove()
        }
      }
    }

    /** Returns Some Runnable to run, or None if the work item has been cancelled/run before. */
    def takeRunnable(): Option[(Runnable, TickerTime)] = {
      val runnableOpt = this.runnableOpt
      this.runnableOpt = None
      runnableOpt match {
        case None =>
          None
        case Some(command: Runnable) =>
          Some((command, desiredExecutionTime))
      }
    }
  }

  /**
   * A hybrid data structure ensuring [[SequentialExecutionContext]]'s ordering guarantee and
   * fairness policy (see [[SequentialExecutionContext]] docs). Since commands are executed in order
   * of desired execution time, it would be feasible to internally use a single heap for all
   * commands, but as an optimization for the common immediate-command dominated work loads, we
   * store immediate commands in a [[util.ArrayDeque]] (constant time push and pop) and scheduled
   * commands in an [[IntrusiveMinHeap]] (O(log n) push, pop and cancel in the number of scheduled
   * commands).
   *
   * Not thread-safe. All interactions with [[WorkItemQueue]] must be externally synchronized.
   */
  private class WorkItemQueue {

    /**
     * Heap containing all work items added with positive execution delays, by desired execution
     * time. This data structure has order stability, meaning that work items with the same desired
     * execution time will be popped in the same order they were pushed onto the heap.
     */
    private val heap = new IntrusiveMinHeap[TickerTime, WorkItem]

    /**
     * Queue containing all work items pushed with zero (or negative) execution delays. Work items
     * in this queue are ordered by desired execution time then by insertion order because they are
     * added the the back of the queue with `desiredExecutionTime = monotonicNow + 0`.
     */
    private val queue = new util.ArrayDeque[WorkItem]

    /**
     * Latest value returned by [[getMonotonicNow]]. Used to ensure monotonicity of work item
     * desired execution times, which in turn ensures that the ordering guarantee is honored.
     */
    private var latestNow = TickerTime.MIN

    /**
     * Returns the number of [[WorkItem]]s scheduled to execute (i.e., that are stored in `heap`).
     */
    def getNumPendingScheduled: Int = heap.size

    /**
     * Returns the number of [[WorkItem]]s ready to execute immediately (i.e., that are stored in
     * `queue`).
     */
    def getNumPendingImmediate: Int = queue.size()

    /**
     * Adds a task to the queue and returns [[WorkItem]] which may be used as a cancellation
     * handle.
     */
    def push(now: TickerTime, delay: FiniteDuration, runnable: Runnable): WorkItem = {
      // Since `System.nanoTime` is not guaranteed to be monotonic, and since the ordering
      // guarantees in the context require that execution order honors insertion order, we adjust
      // `now` to ensure that it is non-decreasing in successive calls to `push`.
      val monotonicNow: TickerTime = getMonotonicNow(now)
      if (delay <= Duration.Zero) {
        // If the command has negative or zero delay, we add it to queue rather than the heap.
        // Negative delays are rounded up to zero (our desired execution time is just "now"),
        // because we don't want commands to "jump the line" (which would violate both our ordering
        // guarantee and our fairness policy).
        val workItem = new WorkItem(runnable, desiredExecutionTime = monotonicNow)
        queue.addLast(workItem)
        workItem
      } else {
        // Delayed commands are added to the heap.
        val workItem = new WorkItem(runnable, desiredExecutionTime = monotonicNow + delay)
        heap.push(workItem)
        workItem
      }
    }

    /**
     * Pops and returns the next scheduled or immediate command due to run no later than `now`, or
     * returns None if no commands are ready.
     */
    def pollReadyCommand(now: TickerTime): Option[(Runnable, TickerTime)] = {
      val monotonicNow: TickerTime = getMonotonicNow(now)
      if (heap.isEmpty) {
        if (queue.isEmpty) {
          // No pending commands.
          return None
        }
        // Only immediate commands are pending; return the next one.
        return queue.pollFirst().takeRunnable()
      }
      val nextScheduledTime: TickerTime = heap.peek.get.desiredExecutionTime
      if (queue.isEmpty) {
        // Only scheduled work is pending; return it if enough time has elapsed.
        if (nextScheduledTime <= monotonicNow) {
          return heap.pop().takeRunnable()
        }
        return None
      }
      // Both scheduled and immediate work is pending, so choose whichever has the earlier desired
      // execution time. If they have the same desired execution time, we give precedence to the
      // scheduled work because it was added first (`monotonicNow` was less than
      // `desiredExecutionTime` when the scheduled item was added to `heap`). This is a requirement
      // the fairness policy, which is motivated in the `SequentialExecutionContext` class docs.
      if (nextScheduledTime <= queue.peekFirst.desiredExecutionTime) {
        return heap.pop().takeRunnable()
      }
      queue.pollFirst().takeRunnable()
    }

    /**
     * Returns `getMonotonicNow(now)` and the desired execution time of the next item that is due to
     * run. Interpretation of results:
     *
     *  - `monotonicNow >= desiredExecutionTime` when there are commands to run immediately.
     *  - `desiredExecutionTime == TickerTime.MAX` when there are no commands left in the queue.
     *  - Otherwise, commands should be polled again at `desiredExecutionTime`.
     *
     * Why not just return the delay, or `desiredExecutionTime - monotonicNow`? The context depends
     * on both the delay and on the absolute desired execution time to determine whether and when it
     * needs to schedule a new task on the underlying pool.
     */
    def getMonotonicNowAndDesiredExecutionTime(now: TickerTime): (TickerTime, TickerTime) = {
      // Figure out the next desired execution time.
      val nextHeapTime = heap.peek.map(_.desiredExecutionTime).getOrElse(TickerTime.MAX)
      val nextQueueTime =
        if (queue.isEmpty) TickerTime.MAX else queue.peekFirst().desiredExecutionTime
      val desiredExecutionTime = if (nextQueueTime < nextHeapTime) nextQueueTime else nextHeapTime
      (getMonotonicNow(now), desiredExecutionTime)
    }

    /** Returns a monotonic `now` value. */
    private def getMonotonicNow(now: TickerTime): TickerTime = {
      // Adjust the `latestNow` value to ensure that this method returns non-decreasing values in
      // successive calls.
      if (now > latestNow) {
        latestNow = now
        now
      } else {
        latestNow
      }
    }
  }
}
