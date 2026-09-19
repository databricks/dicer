package com.databricks.caching.util

import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import com.databricks.caching.util.ExecutorUtil.ContextAwareExecutionContext
import com.databricks.caching.util.Pipeline.PipelineExecutor
import com.databricks.logging.AttributionContext

/**
 * <h2>Pipelines</h2>
 *
 * A library that makes it easy to create [[Future]]s that allow callbacks (e.g., the functions
 * passed to operators like `map()` or `andThen()`) to be composed in a "pipeline" such that they
 * run in a single executor task. For example, in:
 *
 * {{{
 *   val executor: SequentialExecutionContext = ???
 *   val input: Future[Int] = ???
 *   val pipeline: Pipeline[Int] = Pipeline.fromFuture(input)
 *      .map(i => i + 1)(executor)
 *      .map(i => i * 2)(executor)
 *   val output: Future[Int] = pipeline.toFuture
 * }}}
 *
 * the `i => i + 1` and `i => i * 2` callbacks are composed and run in a single task on `executor`.
 * We could equivalently write a single operator `map(i => (i + 1) * 2)(executor)`.
 *
 * Note that until the `toFuture` method is called, a pipeline is not guaranteed to execute, and
 * `toFuture` is the only way to observe the output of a pipeline.
 *
 * <h2>Inline Callbacks</h2>
 *
 * When a callback is bound to a `SequentialExecutionContext`, as in the example above, it is
 * guaranteed to run on that executor. Alternatively, a callback may be bound to a special
 * [[InlinePipelineExecutor]]. Where an inline callback runs depends on whether the source for the
 * operator is already complete. For example, in:
 *
 * {{{
 *   val source: Pipeline[Int] = Pipeline.success(42) // this pipeline is already done!
 *   val output: Future[Int] = source.map(i => i * 2)(InlinePipelineExecutor).toFuture
 * }}}
 *
 * the `i => i * 2` callback will run inline on the calling thread when `map` is invoked, because
 * the source pipeline is already complete. In contrast, in:
 *
 * {{{
 *   val executor: SequentialExecutionContext = ???
 *   val input = Promise[Int]() // not done yet!
 *   val pipeline: Pipeline[Int] = Pipeline.fromFuture(promise.future)
 *       .map(i => i + 1)(executor)
 *       .map(i => i * 2)(InlinePipelineExecutor)
 *   val output: Future[Int] = pipeline.toFuture
 *   promise.success(42)
 * }}}
 *
 * the `i => i * 2` callback will run in the same task as the `i => i + 1` callback, on `executor`,
 * after `input` completes (in `promise.success(42)`).
 *
 * When an inline callback is chained to an incomplete pipeline that is not bound to any specific
 * execution context, it will run on a default executor owned by the pipeline API (see
 * `Pipeline.inlineExecutor`). For example, in:
 *
 * {{{
 *   val input = Promise[Int]() // not done yet!
 *   val source: Pipeline[Int] = Pipeline.fromFuture(input.future)
 *   val output: Future[Int] = source.map(i => i + 1)(InlinePipelineExecutor).toFuture
 * }}}
 *
 * the `i => i + 1` callback will run on the default executor.
 *
 * Inline callbacks should be used only for trivial, stateless transformations that are not going to
 * adversely impact the thread or execution context they end up running on. Include comments in your
 * code justifying every instance of an inline callback. In general, it's best to use inline
 * callbacks only for lightweight utilities, e.g., error logging or latency measurements.
 *
 * <h2>Attribution context propagation</h2>
 *
 * tl;dr: works the same as for the corresponding [[Future]] APIs.
 *
 * The [[Pipeline]] API works only with "context-propagating" executors that run callbacks with the
 * attribution context that is current at the time the corresponding operators are added to the
 * pipeline. This restriction is the reason that pipelines can be bound only to
 * [[SequentialExecutionContext]] or to the inline executor owned by the pipeline API itself. Note
 * that callback composition is broken if chained callbacks run with different attribution contexts:
 * each callback will run in a separate executor task, even if they are allowed to run on the same
 * executor.
 *
 * <h2>Why not just use `SameThread`?</h2>
 *
 * Consider the following example that uses `SameThread`:
 *
 * {{{
 *   val source: Future[Int] = ???
 *   val output: Future[Int] =
 *     source
 *       .map(_ + 1)(executor)
 *       .recover(ex => 42)(SameThread.ec)
 *       .map(_ + 1)(SameThread.ec)
 * }}}
 *
 * While the intention of this code may have been to efficiently run all callbacks in a single task
 * on `executor`, in practice, the `recover()` and second `map()` callbacks could run on an
 * arbitrary execution context or thread, depending only on which thread triggered the completion of
 * `source`, and how it was completed (success or failure?). If we rewrite using a [[Pipeline]], we
 * can ensure that all callbacks run on the expected execution context or thread, rather than some
 * arbitrary thread (like the RPC event loop, which should never be used for application logic):
 *
 * {{{
 *   val source: Future[Int] = ???
 *   val output: Future[Int] =
 *     Pipeline.fromFuture(source)
 *       .map(_ + 1)(executor)
 *       .recover(ex => 42)(executor)
 *       .map(_ + 1)(executor)
 * }}}
 *
 * @tparam T The type of the (successful) result yielded by the pipeline.
 */
@ThreadSafe
sealed trait Pipeline[+T] {

  /** See [[Future.transform()]]. */
  def transform[R](f: Try[T] => Try[R])(executor: PipelineExecutor): Pipeline[R]

  /** See [[Future.map()]]. */
  def map[R](f: T => R)(executor: PipelineExecutor): Pipeline[R]

  /** See [[Future.andThen()]]. */
  def andThen[U](pf: PartialFunction[Try[T], U])(executor: PipelineExecutor): Pipeline[T]

  /** See [[Future.flatMap()]]. */
  def flatMap[R](f: T => Pipeline[R])(executor: PipelineExecutor): Pipeline[R]

  /** See [[Future.flatten]]. */
  def flatten[U](implicit ev: T <:< Pipeline[U]): Pipeline[U]

  /** See [[Future.recover()]]. */
  def recover[U >: T](pf: PartialFunction[Throwable, U])(executor: PipelineExecutor): Pipeline[U]

  /** See [[Future.recoverWith()]]. */
  def recoverWith[U >: T](pf: PartialFunction[Throwable, Pipeline[U]])(
      executor: PipelineExecutor): Pipeline[U]

  /** Converts this pipeline to a [[Future]]. */
  def toFuture: Future[T]
}

object Pipeline {

  /**
   * The executor for a pipeline operator (like [[Pipeline.map()]]). May either be a
   * [[SequentialExecutionContext]] or the special [[InlinePipelineExecutor]] value.
   */
  sealed abstract class PipelineExecutor

  /**
   * Converts `executor` to an [[PipelineExecutor]]. Implicit so that callers can pass a
   * sequential executor to a pipeline operator, and the compiler automatically converts it to
   * [[PipelineExecutor]].
   */
  implicit def fromSequentialExecutionContext(
      executor: SequentialExecutionContext): PipelineExecutor = {
    new SequentialPipelineExecutor(executor)
  }

  /**
   * Converts `domain` to a [[PipelineExecutor]]. Implicit so that callers can pass a
   * hybrid domain to a pipeline operator, and the compiler automatically converts it to
   * [[PipelineExecutor]].
   */
  implicit def fromHybridConcurrencyDomain(domain: HybridConcurrencyDomain): PipelineExecutor = {
    new HybridConcurrencyDomainPipelineExecutor(domain)
  }

  /**
   * Indicates that an operator continuation may be called on an arbitrary pipeline execution
   * context, or immediately on the calling thread when the source for the operator is complete. See
   * remarks on [[Pipeline]] for more information.
   */
  object InlinePipelineExecutor extends PipelineExecutor {
    override def toString: String = "InlinePipelineExecutor"
  }

  /** Starts a pipeline by executing `thunk` on `executor`. */
  def apply[T](thunk: => T)(executor: PipelineExecutor): Pipeline[T] = {
    executor match {
      case InlinePipelineExecutor =>
        val result: Try[T] = Try {
          thunk
        } // convert non-fatal exceptions to `Failure`.
        new CompleteImpl[T](result)
      case spe: SequentialPipelineExecutor =>
        val future: Future[T] = Future(thunk)(spe.executor.prepare())
        new FutureImpl[T](future)

      case hybrid: HybridConcurrencyDomainPipelineExecutor =>
        val future: Future[T] = Future(thunk)(hybrid.domain.prepare())
        new FutureImpl[T](future)
    }
  }

  /** Starts a pipeline with the given `value` as its source. */
  def successful[T](value: T): Pipeline[T] = {
    new CompleteImpl[T](Success(value))
  }

  /** Starts a pipeline with the given `exception` as its source. */
  def failed[T](exception: Throwable): Pipeline[T] = {
    new CompleteImpl[T](Failure(exception))
  }

  /** Starts a pipeline with the given `result` as its source. */
  def fromTry[T](result: Try[T]): Pipeline[T] = {
    new CompleteImpl(result)
  }

  /** Starts a pipeline with the given `future` as its source. */
  def fromFuture[T](future: Future[T]): Pipeline[T] = {
    future.value match {
      case Some(value: Try[T]) =>
        new CompleteImpl[T](value)
      case None =>
        new FutureImpl[T](future)
    }
  }

  /**
   * See [[Future.sequence]].
   *
   * Intentionally restricts the collection type of the input and output types to [[Vector]] rather
   * than using the `CanBuildFrom` idiom to support arbitrary collection types, as that pattern will
   * not be supported in Scala 2.13 (see discussion at
   * https://www.scala-lang.org/blog/2018/06/13/scala-213-collections.html), and we do not want to
   * maintain alternate code paths for Scala 2.12 and 2.13.
   */
  def sequence[T](pipelines: Vector[Pipeline[T]]): Pipeline[Vector[T]] = {
    // Optimize for the case where there's a single element in the pipeline.
    if (pipelines.size == 1) {
      return pipelines.head.map { pipeline: T =>
        Vector(pipeline)
      }(InlinePipelineExecutor)
    }
    // Optimize for the case where all pipelines are complete. We scan through all inputs, and if
    // any of them is not complete, we fall back to the generic `SequenceImpl` implementation to
    // ensure that all pipelines get a chance to run when `toFuture` is called. If all inputs are
    // complete, we can return a completed pipeline immediately, either containing the first error
    // encountered, or the sequence of values if there is no error.
    var errorOpt: Option[Throwable] = None // error for first failed input
    var hasIncompleteInput = false // whether any incomplete inputs exist
    val values = Vector.newBuilder[T] // values of successfully completed inputs
    values.sizeHint(pipelines.size)
    for (pipeline: Pipeline[T] <- pipelines) {
      pipeline match {
        case impl: CompleteImpl[T] =>
          if (errorOpt.isEmpty) {
            impl.value match {
              case Success(value: T) =>
                values += value
              case Failure(exception: Throwable) =>
                // We don't short-circuit because we want to ensure that all pipelines run, even
                // when some pipeline has already failed.
                errorOpt = Some(exception)
            }
          }
        case _ =>
          hasIncompleteInput = true
      }
    }
    if (hasIncompleteInput) {
      // We must ensure that all pipelines run.
      new SequenceImpl[T](pipelines)
    } else {
      errorOpt match {
        case Some(error: Throwable) =>
          // An error occurred, and all pipelines are complete (we don't need to ensure they run).
          // Fail the sequence.
          new CompleteImpl[Vector[T]](Failure(error))
        case None =>
          // All pipelines are complete and successful.
          new CompleteImpl[Vector[T]](Success(values.result()))
      }
    }
  }

  //
  // Implementation only below this point. Concrete implementations of `Pipeline` are:
  //  * `Impl`: base class for all pipelines.
  //  * `CompletedImpl`: pipelines for which the outcome is already known. Created from
  //    `Pipeline.successful` and `Pipeline.failed`, or whenever a completed pipeline is extended
  //    with an operator with an inline callback.
  //  * `FutureImpl`: pipelines that wrap a `Future`. Created from `Pipeline.fromFuture`.
  //  * `TransformedImpl`: pipelines for which operators are still being chained. Consists of a
  //    source pipeline and a composed callback. Created from `Pipeline.map`, `Pipeline.transform`,
  //    etc.
  //  * `SequenceImpl`: pipelines that wrap sequences of pipelines.
  // The private `Transformer` represents a callback, or the composition of chained callbacks, that
  // are passed to the `Pipeline` operators like `map()` or `transform()`. It includes an `isInline`
  // flag so that the implementation can determine whether it is permitted to run the transformer on
  // the calling thread. In addition, callers can assume that non-fatal exceptions are surfaced as
  // `Failure(exception)`, even if the callbacks supplied to the pipeline throw. This ensures
  // correct composition of `andThen` and `transform` operators, which must handle errors.
  //

  /** [[PipelineExecutor]] referencing a sequential executor. */
  private final class SequentialPipelineExecutor(val executor: SequentialExecutionContext)
      extends PipelineExecutor {
    override def toString: String = s"SequentialPipelineExecutor($executor)"
    override def equals(obj: Any): Boolean = obj match {
      case that: SequentialPipelineExecutor =>
        this.executor eq that.executor
      case _ =>
        false
    }
    override def hashCode(): Int = System.identityHashCode(executor)
  }

  /** [[PipelineExecutor]] referencing a hybrid concurrency domain. */
  private final class HybridConcurrencyDomainPipelineExecutor(val domain: HybridConcurrencyDomain)
      extends PipelineExecutor {
    override def equals(obj: Any): Boolean = {
      obj match {
        case that: HybridConcurrencyDomainPipelineExecutor =>
          this.domain eq that.domain
        case _ =>
          false
      }
    }
    override def hashCode(): Int = System.identityHashCode(domain)
  }

  private val logger = PrefixLogger.create(this.getClass, "")

  /**
   * Shared executor appropriate for inline callbacks that need to be evaluated asynchronously.
   *
   *  - Usage logging is disabled.
   *  - Supports attribution context propagation and is instrumented.
   *
   * We (somewhat arbitrarily) choose to use a pool of 4 threads. Since we expect inline callbacks
   * to be very low cost, we don't want to use too many threads, but we also want to ensure that
   * the thread pool does not become a bottleneck, so 1 feels like too few. Note that for the most
   * part we expect inline callbacks to run either directly on the calling thread or on explicitly
   * specified executors, so this thread pool should not be used much.
   */
  private val inlineExecutor: ContextAwareExecutionContext = {
    // TODO(<internal bug>): support disabling attribution context propagation
    ExecutorUtil.createContextPropagatingExecutionContext("pipeline-inline", maxThreads = 4)
  }

  /**
   * Base implementation for pipelines. Concrete implementations must implement `transformInternal`
   * and `toFuture`.
   */
  @ThreadSafe
  private sealed abstract class Impl[T] extends Pipeline[T] {
    override def transform[R](f: Try[T] => Try[R])(executor: PipelineExecutor): Pipeline[R] = {
      val transformer = new Transformer[T, R](unsafeFunction = f)
      transformInternal(transformer, executor)
    }

    final override def map[R](f: T => R)(executor: PipelineExecutor): Pipeline[R] = {
      val transformer = new Transformer[T, R](unsafeFunction = { input: Try[T] =>
        input.map(f)
      })
      transformInternal(transformer, executor)
    }

    final override def andThen[U](pf: PartialFunction[Try[T], U])(
        executor: PipelineExecutor): Pipeline[T] = {
      val transformer: Transformer[T, T] = Transformer.createAndThenTransformer(pf)
      transformInternal(transformer, executor)
    }

    final override def flatMap[R](f: T => Pipeline[R])(executor: PipelineExecutor): Pipeline[R] = {
      // `flatMap` is a trivial composition of `map` and `flatten`. We defer to those
      // implementations for error-handling and inline execution optimizations.
      map(f)(executor).flatten
    }

    final override def flatten[U](implicit ev: T <:< Pipeline[U]): Pipeline[U] = {
      // Map the nested `Pipeline` to a `Future` so that we can leverage the `Future.flatten`
      // implementation. We use the `InlinePipelineExecutor` since `Pipeline.toFuture` is trivial.
      val pipelineOfFuture: Pipeline[Future[U]] = map { pipeline: T =>
        pipeline.toFuture
      }(InlinePipelineExecutor)
      val futureOfFuture: Future[Future[U]] = pipelineOfFuture.toFuture
      val future: Future[U] = futureOfFuture.flatten
      fromFuture(future)
    }

    final override def recover[U >: T](pf: PartialFunction[Throwable, U])(
        executor: PipelineExecutor): Pipeline[U] = {
      val transformer = new Transformer[T, U](unsafeFunction = { input: Try[T] =>
        input.recover(pf)
      })
      transformInternal(transformer, executor)
    }

    final override def recoverWith[U >: T](pf: PartialFunction[Throwable, Pipeline[U]])(
        executor: PipelineExecutor): Pipeline[U] = {
      val transformer = new Transformer[T, Pipeline[U]](unsafeFunction = { input: Try[T] =>
        val output: Pipeline[U] =
          input match {
            case Success(value: T) =>
              Pipeline.successful[U](value)
            case Failure(exception: Throwable) =>
              pf.applyOrElse[Throwable, Pipeline[U]](
                exception,
                (exception: Throwable) => new CompleteImpl[U](Failure(exception))
              )
          }
        Success(output)
      })
      transformInternal(transformer, executor).flatten
    }

    /**
     * Creates a pipeline that runs on `executor`, chaining the result of `this` pipeline through
     * `transformer`.
     */
    protected def transformInternal[R](
        transformer: Transformer[T, R],
        executor: PipelineExecutor): Impl[R]
  }

  /** A [[Pipeline]] that is completed; i.e., it has a result. */
  @ThreadSafe
  private final class CompleteImpl[T](private[Pipeline] val value: Try[T]) extends Impl[T] {
    override def toFuture: Future[T] = Future.fromTry(value)

    protected override def transformInternal[R](
        transformer: Transformer[T, R],
        executor: PipelineExecutor): Impl[R] = {
      if (InlinePipelineExecutor eq executor) {
        // The callback can run inline on the calling thread. Note that we (passively) run with the
        // current attribution context, per the spec requirements. We must catch non-fatal
        // exceptions as well, since the callback may throw.
        val transformedValue: Try[R] = try {
          transformer.unsafeFunction(value)
        } catch {
          case NonFatal(exception: Throwable) =>
            Failure(exception)
        }
        new CompleteImpl[R](transformedValue)
      } else {
        // Can't run the callback on the current thread. Create a new pipeline attached to the given
        // execution context.
        val source = new CompleteImpl[T](value)
        new TransformedImpl[T, R](executor, AttributionContext.current, source, transformer)
      }
    }
  }

  /** [[Pipeline]] implementation that wraps a [[Future]]. */
  @ThreadSafe
  private final class FutureImpl[T](future: Future[T]) extends Impl[T] {
    override def toFuture: Future[T] = future

    override def transformInternal[R](
        transformer: Transformer[T, R],
        executor: PipelineExecutor): Impl[R] = {
      new TransformedImpl[T, R](executor, AttributionContext.current, this, transformer)
    }
  }

  /**
   * A [[Pipeline]] implementation where there is a `source` pipeline and a transformer to which
   * the source outcome will be applied. `transformer` may represent a single callback, or it may
   * represent the composition of multiple callbacks supplied to a chain of operators like `map()`
   * or `transform()`.
   */
  @ThreadSafe
  @SuppressWarnings(Array("ContextAsClassMemberInspection", "reason:grandfathered"))
  private final class TransformedImpl[S, T](
      executor: PipelineExecutor,
      preparedExecutor: ExecutionContext,
      attributionContext: AttributionContext,
      source: Pipeline[S],
      transformer: Transformer[S, T])
      extends Impl[T] {

    def this(
        executor: PipelineExecutor,
        attributionContext: AttributionContext,
        source: Pipeline[S],
        transformer: Transformer[S, T]) = {
      this(
        executor,
        preparedExecutor = executor match {
          case InlinePipelineExecutor => inlineExecutor.prepare()
          case spe: SequentialPipelineExecutor => spe.executor.prepare()
          case hybrid: HybridConcurrencyDomainPipelineExecutor => hybrid.domain.prepare()
        },
        attributionContext,
        source,
        transformer
      )
    }

    override def toFuture: Future[T] = {
      // We arrange for the callback to run with the required attribution context by passing the
      // prepared executor to `Future.transform`. The `Future.transform` implementation is also
      // responsible for translating non-fatal exceptions to `Failure(exception)` values.
      source.toFuture.transform(transformer.unsafeFunction)(preparedExecutor)
    }

    protected override def transformInternal[R](
        transformer: Transformer[T, R],
        executor: PipelineExecutor): Impl[R] = {
      val attributionContext = AttributionContext.current
      if (this.attributionContext ne attributionContext) {
        // We can't compose callbacks that run with different attribution contexts. We intentionally
        // use reference comparison (`ne`) rather than value comparison (`!=`) because comparing
        // attribution contexts by value is expensive, and a false negative is not a correctness
        // issue (err on the side of not running chained callbacks in the same task).
        return new TransformedImpl[T, R](executor, attributionContext, this, transformer)
      }
      // Attribution contexts are the same. We can safely compose the callbacks if they are bound to
      // the same executor, or if at least one of them is bound to the inline executor (in which
      // case the other executor determines where the composed callback runs).
      if ((InlinePipelineExecutor eq executor) || executor == this.executor) {
        new TransformedImpl[S, R](
          this.executor,
          attributionContext,
          source,
          this.transformer.compose(transformer)
        )
      } else if (InlinePipelineExecutor eq this.executor) {
        new TransformedImpl[S, R](
          executor,
          attributionContext,
          source,
          this.transformer.compose(transformer)
        )
      } else {
        // Different executors, neither callback is allowed to run inline.
        new TransformedImpl[T, R](executor, attributionContext, this, transformer)
      }
    }
  }

  @ThreadSafe
  private final class SequenceImpl[T](pipelines: Vector[Pipeline[T]]) extends Impl[Vector[T]] {
    override def toFuture: Future[Vector[T]] = {
      val futures: Vector[Future[T]] = pipelines.map { pipeline: Pipeline[T] =>
        pipeline.toFuture
      }
      // Use `inlineExecutor` since it is used in `Future.sequence` only to construct the result
      // sequence.
      Future.sequence(futures)(implicitly, inlineExecutor)
    }

    override protected def transformInternal[R](
        transformer: Transformer[Vector[T], R],
        executor: PipelineExecutor): Impl[R] = {
      new TransformedImpl[Vector[T], R](executor, AttributionContext.current, this, transformer)
    }
  }

  /**
   * Wraps a callback supplied to a [[Pipeline]] operator like `map()` or `transform()`, or the
   * composition of multiple such callbacks.
   *
   * @param unsafeFunction the callback underlying this transformer. May throw and is not guaranteed
   *                       to run with the attribution context for the pipeline. It is the
   *                       responsibility of the caller to handle exceptions and to run the callback
   *                       with the correct attribution context.
   */
  private case class Transformer[-T, +R](unsafeFunction: Try[T] => Try[R]) extends AnyVal {

    /**
     * Composes `this` with `other`, catching any non-fatal exceptions thrown by
     * `this.unsafeFunction` and converting them to [[Failure]]s before applying the result to
     * `other.unsafeFunction`.
     *
     * For example, in:
     *
     * {{{
     *   pipeline.map { _ =>
     *     throw new Exception("foo")
     *   }.transform {
     *     case Success(_) => Success(42)
     *     case Failure(_) => Success(43)
     *   }
     * }}}
     *
     * we would like the second transformer to be invoked with a [[Failure]] containing
     * `new Exception("foo")`. If we were to simply compose the callbacks directly, the exception
     * thrown in the `map` callback would propagate to the caller, rather than matching the
     * `Failure` case in the `transform` callback.
     *
     * Note that [[Transformer]] returned by this method still wraps an unsafe callback that may
     * throw non-fatal exceptions or run with the wrong attribution context. The only purpose of
     * this helper method is to address the error composition problem described above.
     */
    def compose[S](other: Transformer[R, S]): Transformer[T, S] = {
      new Transformer((input: Try[T]) => {
        val intermediateOutput: Try[R] = try {
          this.unsafeFunction(input)
        } catch {
          case NonFatal(exception) =>
            Failure(exception)
        }
        other.unsafeFunction(intermediateOutput)
      })
    }
  }

  private object Transformer {

    /** Wraps `pf` in a transformer that logs non-fatal exceptions and passes through the input. */
    def createAndThenTransformer[T, U](pf: PartialFunction[Try[T], U]): Transformer[T, T] = {
      Transformer[T, T](unsafeFunction = (input: Try[T]) => {
        try {
          // Use `applyOrElse` rather than `apply` since the domain of `pf` may not include `input`.
          // Since we don't care about the output of the partial function, the fallback function
          // passed to `applyOrElse` is just the identity function.
          pf.applyOrElse[Try[T], Any](input, identity[Try[T]])
        } catch {
          case NonFatal(exception) =>
            logger.warn(s"Unhandled exception in andThen: $exception", every = 10.seconds)
        }
        input
      })
    }
  }
}
