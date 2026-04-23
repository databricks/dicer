package com.databricks.caching.util

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}
import com.databricks.caching.util.CountingExecutors.{
  CountingHybridConcurrencyDomain,
  CountingSequentialExecutionContext
}
import com.databricks.caching.util.Pipeline.{InlinePipelineExecutor, PipelineExecutor}
import com.databricks.caching.util.RichPipeline.Implicits
import com.databricks.caching.util.ServerTestUtils.AttributionContextPropagationTester
import com.databricks.testing.DatabricksTest

class PipelineSuite
    extends DatabricksTest
    with AttributionContextPropagationTester
    with TestUtils.TestName {

  /** Pool on which tests execution contexts are created. */
  private val secPool = SequentialExecutionContextPool.create("test", 4)
  private val sec1 = secPool.createExecutionContext("sec1")
  private val sec2 = secPool.createExecutionContext("sec2")

  /** Hybrid domains shared across tests to reduce thread creation overhead. */
  private val hybrid1 = HybridConcurrencyDomain.create("hybrid1", enableContextPropagation = true)
  private val hybrid2 = HybridConcurrencyDomain.create("hybrid2", enableContextPropagation = true)

  /** The thread on which the test is running. Used to assert that a callback is running inline. */
  private val testThread = Thread.currentThread()

  /** Asserts that the current thread is the test thread. */
  private def assertRunningOnTestThread(): Unit = {
    assert(Thread.currentThread() == testThread)
  }

  /**
   * A wrapper for a [[PipelineExecutor]] which allows callers to assert that execution is occurring
   * in a particular concurrency domain.
   */
  private class AssertableDomainPipelineExecutor private (
      val pipelineExecutor: PipelineExecutor,
      assertInDomainFunc: () => Unit) {

    def assertInDomain(): Unit = assertInDomainFunc()
  }

  private object AssertableDomainPipelineExecutor {
    def from(sec: SequentialExecutionContext): AssertableDomainPipelineExecutor = {
      new AssertableDomainPipelineExecutor(sec, sec.assertCurrentContext)
    }

    def from(hybrid: HybridConcurrencyDomain): AssertableDomainPipelineExecutor = {
      new AssertableDomainPipelineExecutor(hybrid, hybrid.assertInDomain)
    }
  }

  test("Pipeline(thunk)") {
    // Test plan: verify that when creating a `Pipeline` from a thunk, the thunk runs on the
    // expected executor.
    val async: Pipeline[Int] = Pipeline {
      sec1.assertCurrentContext()
      42
    }(sec1)
    assert(async.await() == Success(42))
    val inline: Pipeline[Int] = Pipeline {
      assertRunningOnTestThread()
      42
    }(InlinePipelineExecutor)
    assert(inline.getNonBlocking == Success(42))

    val hybrid: Pipeline[Int] = Pipeline {
      hybrid1.assertInDomain()
      42
    }(hybrid1)
    assert(hybrid.await() == Success(42))
  }

  test("Pipeline(thunk) failure") {
    // Test plan: verify that when creating a `Pipeline` from a thunk, the thunk runs on the
    // expected executor and that thrown exceptions surface as pipeline failures.
    val exception = new Exception("test")
    val async: Pipeline[Int] = Pipeline {
      sec1.assertCurrentContext()
      throw exception
    }(sec1)
    assert(async.await() == Failure(exception))
    val inline: Pipeline[Int] = Pipeline {
      assertRunningOnTestThread()
      throw exception
    }(InlinePipelineExecutor)
    assert(inline.getNonBlocking == Failure(exception))
    val hybrid: Pipeline[Int] = Pipeline {
      hybrid1.assertInDomain()
      throw exception
    }(hybrid1)
    assert(hybrid.await() == Failure(exception))
  }

  test("Pipeline.successful") {
    // Test plan: verify that `Pipeline.success` creates a completed pipeline with the expected
    // outcome.
    val pipeline: Pipeline[Int] = Pipeline.successful(42)
    assert(pipeline.getNonBlocking == Success(42))
  }

  test("Pipeline.failed") {
    // Test plan: verify that `Pipeline.failure` creates a completed pipeline with the expected
    // outcome.
    val exception = new Exception("test")
    val pipeline: Pipeline[Int] = Pipeline.failed(exception)
    assert(pipeline.getNonBlocking == Failure(exception))
  }

  test("Pipeline.fromFuture(successfulFuture)") {
    // Test plan: verify that creating a pipeline using `Pipeline.fromFuture` with a completed
    // future creates a completed pipeline with the expected outcome.
    val completedFuture: Future[Int] = Future.successful(42)
    val pipeline: Pipeline[Int] = Pipeline.fromFuture(completedFuture)
    assert(pipeline.getNonBlocking == Success(42))
  }

  test("Pipeline.fromFuture(failedFuture)") {
    // Test plan: verify that creating a pipeline using `Pipeline.fromFuture` with a failed future
    // creates a completed pipeline with the expected outcome.
    val exception = new Exception("test")
    val failedFuture: Future[Int] = Future.failed(exception)
    val pipeline: Pipeline[Int] = Pipeline.fromFuture(failedFuture)
    assert(pipeline.getNonBlocking == Failure(exception))
  }

  test("Completed pipeline future (success)") {
    // Test plan: verify that transformers using `InlinePipelineExecutor` are executed inline on the
    // test thread when the source is already completed. Test for all operators.
    val source: Pipeline[Int] = Pipeline.successful(42)

    // Validate that the source is completed.
    assert(source.getNonBlocking == Success(42))

    // Test map().
    assert(source.map { i: Int =>
      assertRunningOnTestThread()
      i + 1
    }(InlinePipelineExecutor).getNonBlocking == Success(43))

    // Test andThen().
    var andThenCalled = false
    assert(
      source.andThen {
        case Success(_) =>
          assertRunningOnTestThread()
          andThenCalled = true
      }(InlinePipelineExecutor).getNonBlocking == Success(42)
    )
    assert(andThenCalled)

    // Test transform() to Success.
    assert(
      source.transform {
        case Success(_) =>
          assertRunningOnTestThread()
          Success(43)
        case Failure(_) => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test transform() to Failure.
    val exception = new Exception("test")
    assert(
      source.transform {
        case Success(_) =>
          assertRunningOnTestThread()
          Failure(exception)
        case Failure(_) => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Failure(exception)
    )

    // Test recover() (partial function should not be called with successful input).
    assert(
      source.recover {
        case _ => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Success(42)
    )

    // Test recoverWith() (partial function should not be called with successful input).
    assert(
      source.recoverWith {
        case _ => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Success(42)
    )

    // Test flatMap().
    assert(
      source.flatMap { i: Int =>
        assertRunningOnTestThread()
        Pipeline.successful(i + 1)
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test flatMap() with non-blocking nested pipeline.
    assert(
      source.flatMap { i: Int =>
        assertRunningOnTestThread()
        Pipeline
          .successful(i)
          .map { i: Int =>
            assertRunningOnTestThread()
            i + 1
          }(InlinePipelineExecutor)
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test flatten().
    assert(
      source
        .map { i: Int =>
          assertRunningOnTestThread()
          Pipeline.successful(i + 1)
        }(InlinePipelineExecutor)
        .flatten
        .getNonBlocking == Success(43)
    )
  }

  test("Completed pipeline future (failure)") {
    // Test plan: verify that callbacks are executed inline on the test thread when the source is
    // already completed when executed using `InlinePipelineExecutor`. Test for all operators.
    val sourceException = new Exception("text")
    val source: Pipeline[Int] = Pipeline.failed(sourceException)

    // Validate that the source is completed.
    assert(source.getNonBlocking == Failure(sourceException))

    // Test map().
    assert(source.map { _: Int =>
      assertRunningOnTestThread()
      fail("must not be called")
    }(InlinePipelineExecutor).getNonBlocking == Failure(sourceException))

    // Test andThen().
    var andThenCalled = false
    assert(
      source.andThen {
        case Failure(exception: Throwable) =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          andThenCalled = true
      }(InlinePipelineExecutor).getNonBlocking == Failure(sourceException)
    )
    assert(andThenCalled)

    // Test transform() to Success.
    assert(
      source.transform {
        case Success(_) => throw new Exception("must not happen")
        case Failure(exception: Throwable) =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          Success(43)
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test transform() to Failure.
    val outputException = new Exception("output test")
    assert(
      source.transform {
        case Success(_) => throw new Exception("must not happen")
        case Failure(exception: Throwable) =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          Failure(outputException)
      }(InlinePipelineExecutor).getNonBlocking == Failure(outputException)
    )

    // Test recover() to Success.
    assert(
      source.recover {
        case exception: Throwable =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          43
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test recover() where partial function does not match the source exception type.
    assert(
      source.recover {
        case _: IllegalArgumentException => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Failure(sourceException)
    )

    // Test recover() to Failure.
    assert(
      source.recover {
        case exception: Throwable =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          throw outputException
      }(InlinePipelineExecutor).getNonBlocking == Failure(outputException)
    )

    // Test recoverWith() to non-blocking Success.
    assert(
      source.recoverWith {
        case exception: Throwable =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          Pipeline.successful(43)
      }(InlinePipelineExecutor).getNonBlocking == Success(43)
    )

    // Test recoverWith() where partial function does not match the source exception type.
    assert(
      source.recoverWith {
        case _: IllegalArgumentException => throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Failure(sourceException)
    )

    // Test recoverWith() to non-blocking Failure.
    assert(
      source.recoverWith {
        case exception: Throwable =>
          assertRunningOnTestThread()
          assert(exception == sourceException)
          Pipeline.failed(outputException)
      }(InlinePipelineExecutor).getNonBlocking == Failure(outputException)
    )

    // Test flatMap().
    assert(
      source.flatMap { _ =>
        throw new Exception("must not happen")
      }(InlinePipelineExecutor).getNonBlocking == Failure(sourceException)
    )

    // Test flatten().
    assert(
      source
        .map { _ =>
          throw new Exception("must not happen")
        }(InlinePipelineExecutor)
        .flatten
        .getNonBlocking == Failure(sourceException)
    )
  }

  test("sequence: zero inputs") {
    // Test plan: verify that `Pipeline.sequence` creates a completed pipeline with an empty
    // sequence when input pipelines are empty.
    assert(Pipeline.sequence(Vector.empty).getNonBlocking == Success(Vector.empty))
  }

  test("sequence: one input") {
    // Test plan: verify the behavior of `Pipeline.sequence` for a single input pipeline. Check
    // the behavior for all permutations of successful/unsuccessful and completed/incomplete input.

    {
      // Complete, successful input.
      val input: Pipeline[Int] = Pipeline.successful(42)
      val output: Pipeline[Seq[Int]] = Pipeline.sequence(Vector(input))
      assert(output.getNonBlocking == Success(Seq(42)))
    }
    {
      // Complete, failed input.
      val error = new Exception("test")
      val input: Pipeline[Int] = Pipeline.failed(error)
      val output: Pipeline[Seq[Int]] = Pipeline.sequence(Vector(input))
      assert(output.getNonBlocking == Failure(error))
    }
    {
      // Deferred, successful input.
      val promise = Promise[Int]()
      val input: Pipeline[Int] = Pipeline.fromFuture(promise.future)
      val output: Pipeline[Seq[Int]] = Pipeline.sequence(Vector(input))
      promise.success(42)
      assert(output.await() == Success(Seq(42)))
    }
    {
      // Deferred, failed input.
      val promise = Promise[Int]()
      val input: Pipeline[Int] = Pipeline.fromFuture(promise.future)
      val output: Pipeline[Seq[Int]] = Pipeline.sequence(Vector(input))
      val error = new Exception("test")
      promise.failure(error)
      assert(output.await() == Failure(error))
    }
  }

  test("sequence: multiple inputs") {
    // Test plan: verify the behavior of `Pipeline.sequence` for multiple input pipelines. Verify
    // permutations in which inputs are successful/unsuccessful and completed/incomplete.

    {
      // All inputs complete successfully.
      val input1: Pipeline[Int] = Pipeline.successful(42)
      val input2: Pipeline[Int] = Pipeline.successful(43)
      val input3: Pipeline[Int] = Pipeline.successful(44)
      val output: Pipeline[Vector[Int]] = Pipeline.sequence(Vector(input1, input2, input3))
      assert(output.getNonBlocking == Success(Seq(42, 43, 44)))
    }
    {
      // All inputs complete successfully, but one input is deferred.
      val promise = Promise[Int]()
      val input1: Pipeline[Int] = Pipeline.successful(42)
      val input2: Pipeline[Int] = Pipeline.fromFuture(promise.future)
      val input3: Pipeline[Int] = Pipeline.successful(44)
      val output: Pipeline[Vector[Int]] = Pipeline.sequence(Vector(input1, input2, input3))
      promise.success(43)
      assert(output.await() == Success(Seq(42, 43, 44)))
    }
    {
      // All inputs complete successfully, but one input is deferred and fails.
      val promise = Promise[Int]()
      val input1: Pipeline[Int] = Pipeline.successful(42)
      val input2: Pipeline[Int] = Pipeline.fromFuture(promise.future)
      val input3: Pipeline[Int] = Pipeline.successful(44)
      val output: Pipeline[Vector[Int]] = Pipeline.sequence(Vector(input1, input2, input3))
      val error = new Exception("test")
      promise.failure(error)
      assert(output.await() == Failure(error))
    }
    {
      // All inputs complete, but two inputs fail. Verify that the first failure is propagated.
      val error = new Exception("test")
      val input1: Pipeline[Int] = Pipeline.successful(42)
      val input2: Pipeline[Int] = Pipeline.failed(error)
      val input3: Pipeline[Int] = Pipeline.successful(44)
      val input4: Pipeline[Int] = Pipeline.failed(new Exception("other error"))
      val output: Pipeline[Vector[Int]] = Pipeline.sequence(Vector(input1, input2, input3, input4))
      assert(output.getNonBlocking == Failure(error))
    }
    {
      // Subset of inputs complete, but two inputs fail. Verify that one of the failures is
      // propagated. For one of the deferred inputs, we monitor that its callback is invoked even
      // though the overall pipeline outcome is a failure.
      val error1 = new Exception("test")
      val promise1 = Promise[Int]
      val input1: Pipeline[Int] = Pipeline.fromFuture(promise1.future)
      val promise2 = Promise[Int]
      val input2: Pipeline[Int] = Pipeline.fromFuture(promise2.future)
      val input3: Pipeline[Int] = Pipeline.successful(44)
      val error2 = new Exception("other error")
      val input4: Pipeline[Int] = Pipeline.failed(error2)
      val callbackInvokedPromise5 = Promise[Unit]
      val input5: Pipeline[Int] = Pipeline
        .successful(46)
        .map { i: Int =>
          sec1.assertCurrentContext()
          callbackInvokedPromise5.success(())
          i
        }(sec1)
      val output: Pipeline[Vector[Int]] =
        Pipeline.sequence(Vector(input1, input2, input3, input4, input5))
      promise1.success(42)
      promise2.failure(error1)

      // Because this is a deferred pipeline, either of the errors in the sequence may be returned.
      val awaited: Try[Vector[Int]] = output.await()
      assert(awaited == Failure(error1) || awaited == Failure(error2))

      // Now verify that the callback for input5 is invoked.
      Await.result(callbackInvokedPromise5.future, Duration.Inf)
    }
  }

  test("andThen partial function throws") {
    // Test plan: verify that when the partial function passed to andThen throws, the pipeline
    // outcome is not affected and that the exception is caught by the Pipeline implementation. Test
    // for both the inline case and the delayed case.

    // Inline throw.
    assert(
      Pipeline
        .successful(42)
        .andThen {
          case Success(_) =>
            assertRunningOnTestThread()
            throw new Exception("test")
        }(InlinePipelineExecutor)
        .getNonBlocking == Success(42)
    )

    // Delayed throw.
    val promise = Promise[Int]()
    val pipeline = Pipeline
      .fromFuture(promise.future)
      .andThen {
        case Success(_: Int) =>
          throw new Exception("test")
      }(InlinePipelineExecutor)
    promise.success(42)
    assert(pipeline.await() == Success(42))
  }

  gridTest("transformer throws")(
    Seq[AssertableDomainPipelineExecutor](
      AssertableDomainPipelineExecutor.from(hybrid1),
      AssertableDomainPipelineExecutor.from(sec1)
    )
  ) { executor: AssertableDomainPipelineExecutor =>
    // Test plan: verify that when transformer functions throw an exception, the pipeline outcome is
    // a failure with that exception. Try for all transforming operators.
    val exception = new Exception("test")
    val source: Pipeline[Int] = Pipeline.successful(42)

    // Test transform().
    assert(
      source
        .transform { _ =>
          executor.assertInDomain()
          throw exception
        }(executor.pipelineExecutor)
        .await() == Failure(exception)
    )

    // Test map().
    assert(
      source
        .map { _ =>
          executor.assertInDomain()
          throw exception
        }(executor.pipelineExecutor)
        .await() == Failure(exception)
    )

    // Test flatMap().
    assert(
      source
        .flatMap { _ =>
          executor.assertInDomain()
          throw exception
        }(executor.pipelineExecutor)
        .await() == Failure(exception)
    )
  }

  test("inline transformer throws") {
    // Test plan: similar to "transformer throws", but for inline transformer functions running on
    // the calling thread.
    val exception = new Exception("test")
    val source: Pipeline[Int] = Pipeline.successful(42)

    // Test transform().
    assert(
      source
        .transform { _ =>
          assertRunningOnTestThread()
          throw exception
        }(InlinePipelineExecutor)
        .await() == Failure(exception)
    )

    // Test map().
    assert(
      source
        .map { _ =>
          assertRunningOnTestThread()
          throw exception
        }(InlinePipelineExecutor)
        .await() == Failure(exception)
    )

    // Test flatMap().
    assert(
      source
        .flatMap { _ =>
          assertRunningOnTestThread()
          throw exception
        }(InlinePipelineExecutor)
        .await() == Failure(exception)
    )
  }

  test("composed transformer throws") {
    // Test plan: similar to "transformer throws", but for composed, inline transformer functions.
    val exception = new Exception("test")
    val promise = Promise[Int]()
    val source: Pipeline[Int] = Pipeline.fromFuture(promise.future)

    // Construct composed transform pipeline.
    val composedTransform = source
      .transform {
        case Success(v) =>
          assert(v == 0)
          throw exception // throwing instead of returning Failure(exception)
        case Failure(_) => fail("unexpected failure")
      }(Pipeline.InlinePipelineExecutor)
      .transform {
        case Success(_) =>
          fail("should be dead code")
        case Failure(e) =>
          assert(e eq exception)
          Success(42)
      }(Pipeline.InlinePipelineExecutor)

    // Trigger evaluation of the composed transforms.
    promise.success(0)

    // The pipeline should complete successfully with the value produced by the second transformer.
    assert(composedTransform.await() == Success(42))
  }

  test("composed transformer variation") {
    // Test plan: similar to "composed transformer throws", but where the second transformer throws.
    val exception = new Exception("test")
    val promise = Promise[Int]()
    val source: Pipeline[Int] = Pipeline.fromFuture(promise.future)

    // Construct composed transform pipeline.
    val composedTransform = source
      .transform {
        case Success(v) =>
          assert(v == 0)
          Success(1)
        case Failure(_) => fail("unexpected failure")
      }(Pipeline.InlinePipelineExecutor)
      .transform {
        case Success(v) =>
          assert(v == 1)
          throw exception // throwing instead of returning Failure(exception)
        case Failure(_) =>
          fail("unexpected failure")
      }(Pipeline.InlinePipelineExecutor)

    // Trigger evaluation of the composed transforms.
    promise.success(0)

    // The pipeline should complete with the error thrown by the second transformer.
    assert(composedTransform.await() == Failure(exception))
  }

  gridTest("delayed transformer throws")(
    Seq[AssertableDomainPipelineExecutor](
      AssertableDomainPipelineExecutor.from(hybrid1),
      AssertableDomainPipelineExecutor.from(sec1)
    )
  ) { executor: AssertableDomainPipelineExecutor =>
    // Test plan: similar to "transformer throws", but for inline transformer functions running on
    // the calling thread.
    val exception = new Exception("test")
    val promise = Promise[Int]()
    val source: Pipeline[Int] = Pipeline.fromFuture(promise.future)

    // Construct pipelines.
    val transformPipeline = source.transform { _ =>
      executor.assertInDomain()
      throw exception
    }(executor.pipelineExecutor)
    val mapPipeline = source.map { _ =>
      executor.assertInDomain()
      throw exception
    }(executor.pipelineExecutor)
    val flatMapPipeline = source.flatMap { _ =>
      executor.assertInDomain()
      throw exception
    }(executor.pipelineExecutor)

    // Complete the source and verify that all pipelines yield the expected outcomes.
    promise.success(42)
    for (pipeline <- Seq(transformPipeline, mapPipeline, flatMapPipeline)) {
      assert(pipeline.await() == Failure(exception))
    }
  }

  gridTest("inline transformer in pipeline")(
    Seq[AssertableDomainPipelineExecutor](
      AssertableDomainPipelineExecutor.from(hybrid1),
      AssertableDomainPipelineExecutor.from(sec1)
    )
  ) { executor: AssertableDomainPipelineExecutor =>
    // Test plan: verify that an inline transformer chained to an incomplete pipeline runs on the
    // execution context for the pipeline. Test variations in which the inline transformer is the
    // only transformer in, at the start of, in the middle of, or at the end of a pipeline.
    val promise = Promise[Int]()
    val source: Pipeline[Int] = Pipeline.fromFuture(promise.future)

    // Inline transformer, only one operator in the pipeline.
    val pipelined1: Pipeline[Int] = source.map { i: Int =>
      i + 1
    }(Pipeline.InlinePipelineExecutor)

    // Inline transformer at the start of a pipeline.
    val pipeline2: Pipeline[Int] = source
      .map { i: Int =>
        executor.assertInDomain()
        i + 1
      }(Pipeline.InlinePipelineExecutor)
      .map { i: Int =>
        executor.assertInDomain()
        i * 2
      }(executor.pipelineExecutor)

    // Inline transformer in the middle of a pipeline.
    val pipeline3: Pipeline[Int] = source
      .map { i: Int =>
        executor.assertInDomain()
        i + 1
      }(executor.pipelineExecutor)
      .map { i: Int =>
        executor.assertInDomain()
        i * 2
      }(Pipeline.InlinePipelineExecutor)
      .map { i: Int =>
        executor.assertInDomain()
        i - 1
      }(executor.pipelineExecutor)

    // Inline transformer at the end of a pipeline.
    val pipeline4: Pipeline[Int] = source
      .map { i: Int =>
        executor.assertInDomain()
        i + 1
      }(executor.pipelineExecutor)
      .map { i: Int =>
        executor.assertInDomain()
        i * 2
      }(Pipeline.InlinePipelineExecutor)

    // Now complete the source and verify expected outputs.
    promise.success(42)
    assert(pipelined1.await() == Success(43)) // 42 + 1
    assert(pipeline2.await() == Success(86)) // (42 + 1) * 2
    assert(pipeline3.await() == Success(85)) // ((42 + 1) * 2) - 1
    assert(pipeline4.await() == Success(86)) // ((42 + 1) * 2)
  }

  gridTest("mixed pipeline")(
    Seq[(AssertableDomainPipelineExecutor, AssertableDomainPipelineExecutor)](
      (AssertableDomainPipelineExecutor.from(sec1), AssertableDomainPipelineExecutor.from(sec2)),
      (AssertableDomainPipelineExecutor.from(sec1), AssertableDomainPipelineExecutor.from(hybrid1)),
      (
        AssertableDomainPipelineExecutor.from(hybrid1),
        AssertableDomainPipelineExecutor.from(hybrid2)
      )
    )
  ) {
    case (
        executor1: AssertableDomainPipelineExecutor,
        executor2: AssertableDomainPipelineExecutor
        ) =>
      // Test plan: verify that in a pipeline with multiple execution contexts, transformer
      // functions run in the expected domains.
      val future: Pipeline[Int] = Pipeline.successful(1)
      val pipelined: Pipeline[Int] = future
        .map { i: Int =>
          executor1.assertInDomain()
          assert(i == 1)
          i + 1
        }(executor1.pipelineExecutor)
        .map { i: Int =>
          // Inline transformer must run on the same execution context as the previous transformer.
          executor1.assertInDomain()
          assert(i == 2)
          i + 1
        }(executor1.pipelineExecutor)
        .map { i: Int =>
          executor2.assertInDomain()
          assert(i == 3)
          i + 1
        }(executor2.pipelineExecutor)
      assert(pipelined.await() == Success(4))
  }

  test("callback pipelining in mixed pipeline") {
    // Test plan: verify that in a pipeline with multiple execution contexts, transformer functions
    // are collapsed into a single task when a chain of callbacks should be run in the same
    // domain.

    val countingSec1 = new CountingSequentialExecutionContext(sec1)
    val countingSec2 = new CountingSequentialExecutionContext(sec2)

    val countingHybrid1 = new CountingHybridConcurrencyDomain(hybrid1)
    val countingHybrid2 = new CountingHybridConcurrencyDomain(hybrid2)

    val pipelined: Pipeline[Int] = Pipeline
      .successful(1)
      .map { i: Int =>
        countingSec1.assertCurrentContext()
        assert(i == 1)
        i + 1
      }(countingSec1)
      .map { i: Int =>
        countingSec1.assertCurrentContext()
        assert(i == 2)
        i + 1
      }(countingSec1)
      .map { i: Int =>
        countingSec2.assertCurrentContext()
        assert(i == 3)
        i + 1
      }(countingSec2)
      .map { i: Int =>
        countingHybrid1.assertInDomain()
        assert(i == 4)
        i + 1
      }(countingHybrid1)
      .map { i: Int =>
        countingHybrid1.assertInDomain()
        assert(i == 5)
        i + 1
      }(countingHybrid1)
      .map { i: Int =>
        countingHybrid2.assertInDomain()
        assert(i == 6)
        i + 1
      }(countingHybrid2)
      // Now switch back to hybrid1, which should be scheduled separately.
      .map { i: Int =>
        countingHybrid1.assertInDomain()
        assert(i == 7)
        i + 1
      }(countingHybrid1)
      .map { i: Int =>
        countingHybrid1.assertInDomain()
        assert(i == 8)
        i + 1
      }(countingHybrid1)
      .map { i: Int =>
        countingHybrid1.assertInDomain()
        assert(i == 9)
        i + 1
      }(countingHybrid1)

    assert(pipelined.await() == Success(10))
    assert(countingSec1.getNumExecutionsViaPreparedExecutor == 1)
    assert(countingSec2.getNumExecutionsViaPreparedExecutor == 1)
    assert(countingHybrid1.getNumAsyncExecutions == 2)
    assert(countingHybrid2.getNumAsyncExecutions == 1)
  }

  test("context propagation for inline pipeline") {
    // Test plan: verify that the current attribution context when an operator is added to the
    // pipeline is used with the corresponding callback for a completed pipeline.
    val source: Pipeline[Int] = withAttributionContextId("should not be used") {
      Pipeline.successful(42)
    }
    // Chain callbacks after the attribution context has been restored.
    val map1: Pipeline[Int] = withAttributionContextId("inline1") {
      source.map { i: Int =>
        assertRunningOnTestThread()
        assert(getAttributionContextId == "inline1")
        i + 1
      }(InlinePipelineExecutor)
    }
    val map2: Pipeline[Int] = withAttributionContextId("inline2") {
      map1.map { i: Int =>
        assertRunningOnTestThread()
        assert(getAttributionContextId == "inline2")
        i + 1
      }(InlinePipelineExecutor)
    }
    assert(map2.getNonBlocking == Success(44))
  }

  test("PipelineExecutor.toString") {
    // Test plan: Verify that pipeline executors have the expected string representation.
    assert(
      Pipeline.fromSequentialExecutionContext(sec1).toString == s"SequentialPipelineExecutor($sec1)"
    )
    assert(InlinePipelineExecutor.toString == "InlinePipelineExecutor")
  }

  test("SequentialPipelineExecutor.hashCode") {
    // Test plan: Verify that two `SequentialPipelineExecutor`s based on the same
    // `SequentialExecutorContext` have the same hash code.
    val executor1 = Pipeline.fromSequentialExecutionContext(sec1)
    val executor2 = Pipeline.fromSequentialExecutionContext(sec1)
    assert(executor1.hashCode() == executor2.hashCode())
  }
}
