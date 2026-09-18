package com.databricks.caching.util

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import com.databricks.caching.util.ExecutorUtil.ContextAwareExecutionContext

object CountingExecutors {

  /**
   * A [[SequentialExecutionContext]] wrapping `delegate` which exposes running counts of each
   * SEC operation. Currently, only tasks scheduled via `prepare` are needed and thus exposed, but
   * this could be extended to count the other ops as well.
   */
  final class CountingSequentialExecutionContext(delegate: SequentialExecutionContext)
      extends DelegatingSequentialExecutionContext(delegate) {

    private val counter = new AtomicInteger(0)

    /** The number of tasks executed via [[prepare]]d executor. */
    def getNumExecutionsViaPreparedExecutor: Int = counter.get

    override val contextAwareExecutionContext: ContextAwareExecutionContext = {
      ExecutorUtil.Internal.wrapExecutionContext(
        delegate.getName,
        new CountingExecutionContext(delegate.contextAwareExecutionContext, counter),
        enableContextPropagation = false // `delegate` handles context propagation if so configured
      )
    }
  }

  /**
   * A [[HybridConcurrencyDomain]] wrapping `delegate` which exposes a running count of async task
   * executions. Used to verify that a workflow incurred the expected number of executor hops.
   */
  final class CountingHybridConcurrencyDomain(delegate: HybridConcurrencyDomain)
      extends HybridConcurrencyDomain {

    private val counter = new AtomicInteger(0)

    /**
     * The number of asynchronous tasks executed in this domain. Excludes tasks executed via
     * [[HybridConcurrencyDomain.executeSync]], in particular.
     */
    def getNumAsyncExecutions: Int = counter.get

    override def assertInDomain(): Unit = delegate.assertInDomain()

    override def executeSync[T](thunk: => T): T = delegate.executeSync(thunk)

    override def getClock: TypedClock = delegate.getClock

    override private[util] def schedule(name: String, delay: FiniteDuration, runnable: Runnable) =
      delegate.schedule(name, delay, runnable)

    override private[util] def prepare(): ExecutionContext = {
      new CountingExecutionContext(delegate.prepare(), counter)
    }
  }

  /** An [[ExecutionContext]] which increments a counter for every call to [[execute]]. */
  private final class CountingExecutionContext(delegate: ExecutionContext, counter: AtomicInteger)
      extends ExecutionContext {
    override def execute(runnable: Runnable): Unit = {
      counter.incrementAndGet()
      delegate.execute(runnable)
    }

    override def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)
  }
}
