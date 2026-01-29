package com.databricks.threading

import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import scala.concurrent.ExecutionContext

import com.databricks.logging.ConsoleLogging

/**
 * Minimal open-source implementation of an executor with a name, which just delegates to an
 * underlying ExecutionContext. Note `name` is just provided for convenience, `underlying` is
 * responsible for actually setting the name of any threads it creates.
 */
class NamedExecutor private (val name: String, underlying: ExecutionContext)
    extends ExecutionContext {

  override def execute(runnable: Runnable): Unit = {
    underlying.execute(runnable)
  }

  override def reportFailure(cause: Throwable): Unit = {
    underlying.reportFailure(cause)
  }
}

object NamedExecutor extends ConsoleLogging {
  override def loggerName: String = "NamedExecutor"

  /** Alias to the global implicit ExecutionContext. */
  val globalImplicit: NamedExecutor =
    new NamedExecutor("globalImplicit", ExecutionContext.Implicits.global)

  /**
   * Creates a NamedExecutor with the given parameters.
   *
   * @param name The name of the executor
   * @param maxThreads The fixed number of threads this executor uses (note this has to be called
   *                   `maxThreads` for compatibility with the internal Databricks implementation,
   *                   if it's used as a named parameter).
   * @param enableContextPropagation Ignored in OSS
   * @param enableUsageLogging Ignored in OSS
   */
  def create(
      name: String,
      maxThreads: Int,
      enableContextPropagation: Boolean = true,
      enableUsageLogging: Boolean = true): NamedExecutor = {
    val executorService = Executors.newFixedThreadPool(
      maxThreads,
      new ThreadFactoryBuilder().setNameFormat(s"$name-%d").build()
    )
    val executionContext =
      ExecutionContext.fromExecutorService(executorService, createReporter(name))
    new NamedExecutor(name, executionContext)
  }

  /**
   * Creates a NamedExecutor backed by an instrumented executor.
   *
   * @param executor The underlying executor
   * @param enableContextPropagation Ignored in OSS
   * @param enableUsageLogging Ignored in OSS
   */
  def fromInstrumentedExecutor(
      executor: AbstractInstrumentedScheduledThreadPoolExecutor,
      enableContextPropagation: Boolean = true,
      enableUsageLogging: Boolean = true): NamedExecutor = {
    val name: String = executor.getName()
    val executionContext =
      ExecutionContext.fromExecutorService(executor, createReporter(name))
    new NamedExecutor(name, executionContext)
  }

  /**
   * Returns an exception reporter function that logs uncaught exceptions, and then calls the
   * default uncaught exception handler (if any).
   */
  private def createReporter(name: String): Throwable => Unit = {
    val standardHandler = Thread.getDefaultUncaughtExceptionHandler
    def report(t: Throwable): Unit = {
      logger.error(s"Uncaught exception in thread pool $name", t)
      if (standardHandler != null) {
        standardHandler.uncaughtException(Thread.currentThread(), t)
      }
    }
    report
  }
}
