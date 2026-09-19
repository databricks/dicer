package com.databricks.caching.util

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext => ScalaExecutionContext}

import com.google.common.util.concurrent.ThreadFactoryBuilder

/**
 * OSS executor types, factories, and context hooks. This implementation assumes there is no context
 * to propagate. Patch this file to add custom context propagation.
 */
private[databricks] object ExecutorUtil {

  /** Execution-context compatibility alias. */
  type ContextAwareExecutionContext = ScalaExecutionContext

  /** Creates a fixed-pool execution context without custom context propagation. */
  def createContextPropagatingExecutionContext(
      name: String,
      maxThreads: Int): ContextAwareExecutionContext = {
    val executorService = Executors.newFixedThreadPool(
      maxThreads,
      new ThreadFactoryBuilder().setNameFormat(s"$name-%d").build()
    )
    ScalaExecutionContext.fromExecutorService(executorService)
  }

  /** Context operations used internally by caching utilities. */
  private[util] object Internal {

    /** Returns the execution context unchanged. */
    def wrapExecutionContext(
        name: String,
        executionContext: ScalaExecutionContext,
        enableContextPropagation: Boolean): ContextAwareExecutionContext = {
      executionContext
    }

    /** Returns the runnable unchanged. */
    def wrapRunnable(runnable: Runnable, enableContextPropagation: Boolean): Runnable = {
      runnable
    }
  }
}
