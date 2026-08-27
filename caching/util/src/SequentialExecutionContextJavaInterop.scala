package com.databricks.caching.util

import java.util.concurrent.Executor

/**
 * The parts of [[SequentialExecutionContext]] that Java cannot reach idiomatically, for the Java
 * shim (`javaapi.SequentialExecutionContext`); each method says why it is here.
 *
 * Scala callers should use [[SequentialExecutionContext]] directly. This is public only because
 * Java cannot see a qualified-private Scala member, as with other `*JavaInterop` objects.
 */
object SequentialExecutionContextJavaInterop {

  /**
   * Creates a [[SequentialExecutionContext]] backed by a dedicated single-threaded pool.
   *
   * @param name the context name, used for debugging and pool naming.
   * @param alertOwnerTeam the alert routing name of the team owning the pool. Required rather than
   *                       defaulted: the defaulting overload is deprecated because its Caching
   *                       default misroutes other teams' alerts (<internal bug>).
   */
  def createWithDedicatedPool(name: String, alertOwnerTeam: String): SequentialExecutionContext =
    SequentialExecutionContext.createWithDedicatedPool(
      name,
      alertOwnerTeam = alertOwnerTeam,
      enableContextPropagation = true
    )

  /**
   * Creates a [[SequentialExecutionContextPool]] of `numThreads` threads.
   *
   * @param poolName the name of the thread pool.
   * @param numThreads the number of threads in the pool.
   * @param alertOwnerTeam the alert routing name of the team owning the pool. Required rather than
   *                       defaulted: the defaulting overload is deprecated because its Caching
   *                       default misroutes other teams' alerts (<internal bug>).
   */
  def createPool(
      poolName: String,
      numThreads: Int,
      alertOwnerTeam: String): SequentialExecutionContextPool =
    SequentialExecutionContextPool.create(
      poolName,
      numThreads,
      alertOwnerTeam = alertOwnerTeam,
      enableContextPropagation = true
    )

  /**
   * Returns `context`'s executor as a plain [[java.util.concurrent.Executor]]: work submitted to it
   * runs serially on the context's thread, with `assertCurrentContext()` satisfied and the current
   * attribution context propagated.
   *
   * Adapted rather than returned directly, because `asExecutionContext` yields a Scala
   * [[scala.concurrent.ExecutionContext]], which is not an [[java.util.concurrent.Executor]].
   */
  def executorFor(context: SequentialExecutionContext): Executor = {
    val executionContext = SequentialExecutionContext.asExecutionContext(context)
    (command: Runnable) => executionContext.execute(command)
  }
}
