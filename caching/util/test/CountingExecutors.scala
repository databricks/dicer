package com.databricks.caching.util

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext

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
