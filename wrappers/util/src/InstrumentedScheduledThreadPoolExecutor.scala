package com.databricks.threading

import java.util.concurrent.{ScheduledExecutorService, ScheduledThreadPoolExecutor, ThreadFactory}

import com.google.common.util.concurrent.ThreadFactoryBuilder

/**
 * Minimal open-source implementation of AbstractInstrumentedScheduledThreadPoolExecutor. The
 * "abstract" part of the name is not accurate in the open-source version, but we keep it for API
 * compatibility. There is no context propagation or instrumentation in this open-source version.
 * See [[AdvancedInstrumentedScheduledThreadPoolExecutor.create]] for the meaning of the parameters.
 */
class AbstractInstrumentedScheduledThreadPoolExecutor(
    name: String,
    maxThreads: Int,
    factory: ThreadFactory,
    enableContextPropagation: Boolean
) extends ScheduledThreadPoolExecutor(maxThreads, factory)
    with ScheduledExecutorService {

  // Remove tasks immediately from the work queue at time of cancellation to avoid unbounded
  // retention of cancelled tasks.
  setRemoveOnCancelPolicy(true)

  /** Returns the name of this executor. */
  def getName(): String = name
}

object InstrumentedScheduledThreadPoolExecutor {

  /**
   * Returns an [[AbstractInstrumentedScheduledThreadPoolExecutor]] with the specified parameters.
   *
   * @param name The name of the thread pool
   * @param maxThreads The number of threads to keep in the pool
   */
  def create(name: String, maxThreads: Int): AbstractInstrumentedScheduledThreadPoolExecutor = {
    new AbstractInstrumentedScheduledThreadPoolExecutor(
      name,
      maxThreads,
      new ThreadFactoryBuilder().setNameFormat(s"$name-%d").build(),
      enableContextPropagation = true
    )
  }
}
