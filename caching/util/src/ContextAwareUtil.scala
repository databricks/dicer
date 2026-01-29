package com.databricks.caching.util

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import scala.concurrent.ExecutionContext

import com.databricks.threading.AbstractInstrumentedScheduledThreadPoolExecutor
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.databricks.util.advanced.AdvancedInstrumentedScheduledThreadPoolExecutor

/**
 * Minimal implementation of ContextAwareUtil. This simplified version doesn't
 * include context propagation features.
 */
object ContextAwareUtil {
  type ContextAwareScheduledExecutorService = ScheduledExecutorService
  type ContextAwareExecutionContext = ExecutionContext

  /**
   * Wraps an ExecutionContext to make it context-aware.
   * This implementation returns the execution context unchanged, without
   * context propagation logic.
   *
   * @param name                     The name of the execution context (for debugging)
   * @param executionContext         The underlying execution context to wrap
   * @param enableContextPropagation Whether to enable context propagation (parameter is ignored)
   * @return A context-aware execution context
   */
  def wrapExecutionContext(
      name: String,
      executionContext: ExecutionContext,
      enableContextPropagation: Boolean): ContextAwareExecutionContext = {
    executionContext
  }

  /**
   * Makes the given runnable "context-aware".
   * This implementation returns the runnable unchanged.
   */
  def wrapRunnable(runnable: Runnable, enableContextPropagation: Boolean): Runnable = {
    runnable
  }

  /**
   * Creates a context-aware execution context backed by a thread pool.
   * This implementation creates a basic thread pool.
   *
   * @param name The name of the thread pool
   * @param maxThreads The maximum number of threads in the pool
   * @param enableContextPropagation Whether to enable context propagation (parameter is ignored)
   * @return A context-aware execution context
   */
  def createThreadPoolExecutionContext(
      name: String,
      maxThreads: Int,
      enableContextPropagation: Boolean): ContextAwareExecutionContext = {
    val executor = new AbstractInstrumentedScheduledThreadPoolExecutor(
      name,
      maxThreads,
      new ThreadFactoryBuilder().setNameFormat(s"$name-%d").build(),
      enableContextPropagation
    )
    ExecutionContext.fromExecutorService(executor)
  }

  /**
   * Creates a scheduled executor service with the specified parameters.
   * This implementation creates a basic instrumented scheduled thread pool executor.
   *
   * @param name The name of the thread pool
   * @param maxThreads The maximum number of threads in the pool
   * @param factory The thread factory to use for creating new threads
   * @return A scheduled executor service
   */
  def createScheduledExecutorService(
      name: String,
      maxThreads: Int,
      factory: ThreadFactory): ContextAwareScheduledExecutorService = {
    AdvancedInstrumentedScheduledThreadPoolExecutor
      .create(
        name,
        maxThreads,
        factory,
        enableContextPropagation = false
      )
  }
}
