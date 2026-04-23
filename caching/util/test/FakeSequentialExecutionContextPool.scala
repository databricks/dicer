package com.databricks.caching.util

import com.databricks.caching.util.SequentialExecutionContextPool.ExceptionHandler

import com.databricks.caching.util.ContextAwareUtil.ContextAwareScheduledExecutorService

import com.databricks.caching.util.SequentialExecutionContextPool.UncaughtExceptionSource.UncaughtExceptionSource

/**
 * A [[SequentialExecutionContextPool]] implementation that creates
 * [[FakeSequentialExecutionContext]]s, each of which shares the same fake clock.
 */
object FakeSequentialExecutionContextPool {

  /** Interceptor for uncaught exceptions. */
  trait UncaughtExceptionInterceptor {

    /**
     * Attempts to intercept an uncaught exception. Returns true to indicate that the exception
     * was handled, or false to indicate that the exception should be handled by the default
     * uncaught exception handler.
     */
    def interceptUncaughtException(source: String, debugName: String, e: Throwable): Boolean
  }

  /**
   * Creates a fake pool with a default uncaught exception handler and context propagation enabled.
   *
   * @param name       the name of the pool, used for debugging and monitoring purposes.
   * @param numThreads the maximum number of threads in the underlying thread pool.
   * @param fakeClock  the [[FakeTypedClock]] used to manually advance time in test code for all
   *                   execution contexts in the pool.
   */
  def create(
      name: String,
      numThreads: Int,
      fakeClock: FakeTypedClock): FakeSequentialExecutionContextPool = {
    createInternal(
      name,
      numThreads,
      fakeClock,
      exceptionInterceptorOpt = None,
      enableContextPropagation = true
    )
  }

  /**
   * Creates a fake pool with a default uncaught exception handler.
   *
   * @param name       the name of the pool, used for debugging and monitoring purposes.
   * @param numThreads the maximum number of threads in the underlying thread pool.
   * @param fakeClock  the [[FakeTypeClock]] used to manually advance time in test code for all
   *                   execution contexts in the pool.
   * @param enableContextPropagation whether to enable context propagation.
   */
  def create(
      name: String,
      numThreads: Int,
      fakeClock: FakeTypedClock,
      enableContextPropagation: Boolean): FakeSequentialExecutionContextPool = {
    createInternal(
      name,
      numThreads,
      fakeClock,
      exceptionInterceptorOpt = None,
      enableContextPropagation
    )
  }

  /**
   * Creates a fake pool with context propagation enabled.
   *
   * @param name       the name of the pool, used for debugging and monitoring purposes.
   * @param numThreads the maximum number of threads in the underlying thread pool.
   * @param fakeClock  the [[FakeTypedClock]] used to manually advance time in test code for all
   *                   execution contexts in the pool.
   * @param exceptionInterceptor an interceptor that can selectively intercept uncaught exceptions
   *                             thrown from the executor threads.
   */
  def create(
      name: String,
      numThreads: Int,
      fakeClock: FakeTypedClock,
      exceptionInterceptor: UncaughtExceptionInterceptor): FakeSequentialExecutionContextPool = {
    createInternal(
      name,
      numThreads,
      fakeClock,
      Some(exceptionInterceptor),
      enableContextPropagation = true
    )
  }

  /**
   * Creates a fake pool.
   *
   * @param name       the name of the pool, used for debugging and monitoring purposes.
   * @param numThreads the maximum number of threads in the underlying thread pool.
   * @param fakeClock  the [[FakeTypedClock]] used to manually advance time in test code for all
   *                   execution contexts in the pool.
   * @param exceptionInterceptor an interceptor that can selectively intercept uncaught exceptions
   *                             thrown from the executor threads.
   * @param enableContextPropagation whether to enable context propagation.
   */
  def create(
      name: String,
      numThreads: Int,
      fakeClock: FakeTypedClock,
      exceptionInterceptor: UncaughtExceptionInterceptor,
      enableContextPropagation: Boolean): FakeSequentialExecutionContextPool = {
    createInternal(
      name,
      numThreads,
      fakeClock,
      Some(exceptionInterceptor),
      enableContextPropagation
    )
  }

  /**
   * Creates a fake pool with the given `name` for debug and monitoring purposes. All
   * [[SequentialExecutionContext]] instances in the pool track the given
   * `fakeClock`. An optional [[UncaughtExceptionInterceptor]] can be provided to selectively
   * intercept uncaught exceptions thrown from the executor threads.
   *
   * This is the common internal method that is used by all `create` overloads, which accept
   * different subsets of parameters.
   */
  private def createInternal(
      name: String,
      numThreads: Int,
      fakeClock: FakeTypedClock,
      exceptionInterceptorOpt: Option[UncaughtExceptionInterceptor],
      enableContextPropagation: Boolean): FakeSequentialExecutionContextPool = {
    val exceptionHandler: ExceptionHandler = exceptionInterceptorOpt match {
      case Some(exceptionInterceptor: UncaughtExceptionInterceptor) =>
        new ExceptionHandler(name, AlertOwnerTeam.CachingTeam) {
          // If an uncaught exception interceptor is provided, we create a custom
          // ExceptionHandler overriding the handleUncaughtException method. The interceptor can
          // choose to handle the uncaught exception by returning true, or let the default
          // ExceptionHandler handle it by returning false.
          override def handleUncaughtException(
              source: UncaughtExceptionSource,
              debugName: String,
              e: Throwable): Unit = {
            if (!exceptionInterceptor.interceptUncaughtException(source.toString, debugName, e)) {
              super.handleUncaughtException(source, debugName, e)
            }
          }
        }
      case None =>
        // No interceptor, so we use the default ExceptionHandler.
        new ExceptionHandler(name, AlertOwnerTeam.CachingTeam)
    }
    val pool = SequentialExecutionContextPool.forTest.create(
      name,
      numThreads,
      exceptionHandler,
      enableContextPropagation
    )
    new FakeSequentialExecutionContextPool(pool, fakeClock)
  }

}
class FakeSequentialExecutionContextPool(
    pool: SequentialExecutionContextPool,
    fakeClock: FakeTypedClock)
    extends SequentialExecutionContextPool {

  override val name: String = pool.name

  override private[util] val executorService: ContextAwareScheduledExecutorService = {
    pool.executorService
  }

  override private[util] val exceptionHandler: SequentialExecutionContextPool.ExceptionHandler = {
    pool.exceptionHandler
  }

  override private[util] val enableContextPropagation: Boolean = pool.enableContextPropagation

  /** Returns an execution context with the given `name` (for debugging). */
  override def createExecutionContext(contextName: String): SequentialExecutionContext = {
    FakeSequentialExecutionContext.create(contextName, Some(fakeClock), pool)
  }

}
