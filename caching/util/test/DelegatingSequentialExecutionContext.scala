package com.databricks.caching.util

import scala.concurrent.duration._

import com.databricks.caching.util.ExecutorUtil.ContextAwareExecutionContext

/**
 * A delegating implementation of [[SequentialExecutionContext]] that passes all requests through
 * to a base context. Useful when specific behaviors are being modified, but without requiring
 * inheritance. Leveraged by test helpers like [[FakeSequentialExecutionContext]], and to support
 * decorators (see [[Decorators]]).
 */
class DelegatingSequentialExecutionContext(delegate: SequentialExecutionContext)
    extends SequentialExecutionContext {
  override def getClock: TypedClock = delegate.getClock

  override def assertCurrentContext(): Unit = delegate.assertCurrentContext()

  override def schedule(name: String, delay: FiniteDuration, runnable: Runnable): Cancellable =
    delegate.schedule(name, delay, runnable)

  override def getName: String = delegate.getName

  override def run[U](func: => U): Unit = delegate.run(func)

  override private[util] val contextAwareExecutionContext: ContextAwareExecutionContext = {
    delegate.contextAwareExecutionContext
  }
}
object DelegatingSequentialExecutionContext {

  /** Defines "decorators" modifying the behavior of a `baseContext`. */
  implicit class Decorators(delegate: SequentialExecutionContext) {

    /**
     * Returns a context for which "best-effort" cancellation is "no-effort" (within spec but
     * poorly-behaved). It's very difficult to reproduce the case where cancellation of a scheduled
     * command does not succeed using the production [[SequentialExecutionContext]], so this
     * decorator is useful for tests verifying that a component is well-behaved when cancellations
     * are flaking.
     */
    def ignoringCancellation(): SequentialExecutionContext = {
      new DelegatingSequentialExecutionContext(delegate) {
        override def schedule(
            name: String,
            delay: FiniteDuration,
            runnable: Runnable): Cancellable = {
          super.schedule(name, delay, runnable)
          _ => {}
        }
      }
    }
  }
}
