package com.databricks.caching.util

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.ThreadFactory

import io.prometheus.client.Gauge

import com.databricks.caching.util.CachingErrorCode.UNCAUGHT_SEC_POOL_EXCEPTION
import com.databricks.caching.util.ContextAwareUtil.ContextAwareScheduledExecutorService
import com.databricks.caching.util.SequentialExecutionContextPool.{ExceptionHandler, Metrics}

// This file contains abstractions relevant to the SequentialExecutionContext. These classes are
// kept together since their interactions are somewhat tied to each other.

/**
 * An abstraction exposing a pool of threads that run various [[SequentialExecutionContext]]s that
 * this pool provides. [[SequentialExecutionContext]] is responsible for ensuring sequential
 * execution of commands. This class exists merely to ensure that the underlying
 * [[ScheduledExecutorService]] has a specific implementation, to avoid exploding the test matrix
 * for the context implementation.
 */
trait SequentialExecutionContextPool {

  private[util] val name: String

  private[util] val executorService: ContextAwareScheduledExecutorService

  private[util] val exceptionHandler: ExceptionHandler

  private[util] val enableContextPropagation: Boolean

  /** Returns an execution context with the given `contextName` (for debugging). */
  def createExecutionContext(contextName: String): SequentialExecutionContext

}

/**
 * A companion object to provide a factory method  for creating the pool rather than doing it as a
 * part of the constructor.
 */
object SequentialExecutionContextPool {

  /**
   * Creates a thread pool with `numThreads`. The supplied `name` is used to derive names for the
   * threads created by the pool.
   *
   * The thread pool registers an [[UncaughtExceptionHandler]] on each of its threads that
   * interrupts the thread that is calling this method, i.e. [[Thread.currentThread()]]. This
   * handler is also called whenever a command added to the [[SequentialExecutionContext]] throws
   * a non-fatal error (fatal errors propagate).
   *
   * @param poolName the name of the thread pool
   * @param numThreads the number of threads in the pool
   * @param enableContextPropagation whether to propagate the context object to runnables submitted
   *                                 to execution contexts created by this pool
   */
  def create(
      poolName: String,
      numThreads: Int,
      enableContextPropagation: Boolean = true): SequentialExecutionContextPool = {
    val exceptionHandler = new ExceptionHandler(poolName)
    createInternal(poolName, numThreads, exceptionHandler, enableContextPropagation)
  }


  private def createInternal(
      poolName: String,
      numThreads: Int,
      exceptionHandler: ExceptionHandler,
      enableContextPropagation: Boolean
  ): SequentialExecutionContextPool = {
    val factory = new SequentialExecutionContextPoolThreadFactory(poolName, exceptionHandler)

    // Although the SequentialExecutionContext executors running on the pool are already
    // instrumented and perform their own context propagation, we use an instrumented thread pool
    // executor here anyway to support the use of SECs in existing services that rely on the common
    // monitoring and alerting support that is in place for instrumented thread pools (i.e. Generic
    // Service Dashboard panels, Service Inspector alerts, ...). We disable context propagation in
    // the underlying executor service since context propagation is already handled by the SECs.
    val executor: ContextAwareScheduledExecutorService =
      ContextAwareUtil.createScheduledExecutorService(
        poolName,
        maxThreads = numThreads,
        factory = factory
      )
    new SequentialExecutionContextPoolImpl(
      poolName,
      executor,
      exceptionHandler,
      enableContextPropagation
    )
  }

  /**
   * Handler for uncaught exceptions that:
   *
   *  - Fires a critical alert using [[PrefixLogger.alert]] and logs details of the exception.
   *  - Interrupts the thread on which the handler is created in an attempt to make the failures
   *    more evident, if that thread is still alive. This is primarily useful in tests, as the main
   *    test thread will be interrupted which in turn causes the test to fail.
   *    COPYBARA:REMOVE:START
   *    In production, the pool is typically created on the main thread which is shortlived for
   *    Databricks servers (work is performed on other non-daemon threads), so metrics and logs are
   *    the only reliable indication of a problem.
   *    COPYBARA:REMOVE:END
   *
   * Uncaught exceptions may be intercepted from multiple locations, enumerated in
   * [[UncaughtExceptionSource]].
   */
  class ExceptionHandler(poolName: String) extends Thread.UncaughtExceptionHandler {
    private val logger = PrefixLogger.create(getClass, poolName)

    /** Used by this handler to interrupt the thread on which the pool was created. */
    private val creatingThread = Thread.currentThread()

    final override def uncaughtException(t: Thread, e: Throwable): Unit = {
      // $COVERAGE-OFF$: it's unclear how to trigger this method, see docs for
      // `UncaughtExceptionSource.Thread` for when this is supposed to happen.
      handleUncaughtException(UncaughtExceptionSource.Thread, t.getName, e)
      // $COVERAGE-ON$
    }

    /** Handles an uncaught exception (see class docs for more information). */
    def handleUncaughtException(
        source: UncaughtExceptionSource.UncaughtExceptionSource,
        debugName: String,
        e: Throwable): Unit = {
      val stackTrace = e.getStackTrace.mkString("Array(\n    ", "\n    ", ")")
      logger.alert(
        Severity.CRITICAL,
        UNCAUGHT_SEC_POOL_EXCEPTION,
        s"Unhandled exception from $source:$debugName is $e:\n$stackTrace"
      )
      if (creatingThread.isAlive) {
        logger.error(
          s"INTERRUPTING CREATOR THREAD $creatingThread DUE TO UNCAUGHT EXCEPTION," +
          s" THIS WILL CAUSE InterruptedException!"
        )
        creatingThread.interrupt()
      } else {
        logger.error(s"Creator thread $creatingThread has exited, nothing more to do")
      }
    }
  }

  /**
   * Identifies where an uncaught exception was observed.
   *
   *  - `Thread`: an exception that was uncaught by the underlying thread pool executor service.
   *    Extremely unlikely in practice as the executor service is expected to eat all exceptions.
   *  - `ExecutionContext`: an exception passed to [[SequentialExecutionContext.reportFailure()]].
   *    Appears to be extremely unlikely in practice, but as `reportFailure` is undocumented
   *    we don't really know.
   *  - `Command`: an exception thrown from a command added to the [[SequentialExecutionContext]].
   */
  private[util] object UncaughtExceptionSource extends Enumeration {
    type UncaughtExceptionSource = Value
    val Thread, ExecutionContext, Command = Value
  }

  private[util] object Metrics {

    private val numThreadsGauge: Gauge = Gauge
      .build()
      .help("Number of created and unfinished threads.")
      .name("sequential_execution_context_pool_num_threads")
      .labelNames("pool_name")
      .register()

    def incrementNumThreadsGauge(poolName: String): Unit = {
      numThreadsGauge.labels(poolName).inc()
    }
  }

  private[util] object forTest {

    /** Supports creating a pool with a custom uncaught exception handler. */
    def create(
        poolName: String,
        numThreads: Int,
        exceptionHandler: ExceptionHandler,
        enableContextPropagation: Boolean): SequentialExecutionContextPool = {
      createInternal(poolName, numThreads, exceptionHandler, enableContextPropagation)
    }

  }
}

/**
 * The production implementation of [[SequentialExecutionContextPool]]. Separate from the
 * [[SequentialExecutionContextPool]] trait to allow modified behavior in tests.
 */
private class SequentialExecutionContextPoolImpl private[util] (
    val name: String,
    val executorService: ContextAwareScheduledExecutorService,
    val exceptionHandler: ExceptionHandler,
    val enableContextPropagation: Boolean)
    extends SequentialExecutionContextPool {

  override def createExecutionContext(contextName: String): SequentialExecutionContext = {
    SequentialExecutionContext.create(this, contextName)
  }
}

/**
 * Thread factory for [[SequentialExecutionContextPool]] that registers itself as the uncaught
 * exception handler on each thread.
 */
private class SequentialExecutionContextPoolThreadFactory(
    poolName: String,
    uncaughtExceptionHandler: UncaughtExceptionHandler)
    extends ThreadFactory {
  private val logger = PrefixLogger.create(getClass, poolName)

  /** Used to uniquely identify threads as they are created. */
  private val nextThreadId = new java.util.concurrent.atomic.AtomicLong(0)

  override def newThread(r: Runnable): Thread = {
    val threadId: Long = nextThreadId.getAndIncrement()
    val threadName = s"$poolName-$threadId"
    logger.debug(s"Creating thread $threadName")
    val thread = new Thread(r, threadName): @SuppressWarnings(
      Array("BadMethodCall-NewThread", "reason:grandfathered-2685b5e321e85aac")
    )
    Metrics.incrementNumThreadsGauge(poolName)
    thread.setUncaughtExceptionHandler(uncaughtExceptionHandler)
    thread.setDaemon(true)
    thread
  }
}
