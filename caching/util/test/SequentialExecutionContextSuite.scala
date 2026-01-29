package com.databricks.caching.util

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Random

import io.grpc.Status
import io.prometheus.client.CollectorRegistry
import org.scalatest.Suite
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.caching.util.Lock.withLock
import com.databricks.testing.DatabricksTest


/**
 * Tests for [[SequentialExecutionContext]]. Tests are run with context propagation enabled and
 * disabled.
 */
class SequentialExecutionContextSuite extends DatabricksTest {

  override def nestedSuites: Vector[Suite] =
    Vector(
      new ParameterizedSequentialExecutionContextSuite(enableContextPropagation = true),
      new ParameterizedSequentialExecutionContextSuite(enableContextPropagation = false)
    )
}

private class ParameterizedSequentialExecutionContextSuite(enableContextPropagation: Boolean)
    extends DatabricksTest
    with TestName
    {

  /**
   * An object that is tied to an execution context and is used by the [[LogRunnable]] to maintain
   * a log of strings as the runnable is called.
   */
  private class TestLog {

    private val lock = new ReentrantLock()

    /** The log of strings */
    private val log = new mutable.ArrayBuffer[String]

    /** Appends log entry. */
    def append(value: String): Unit = withLock(lock) { log.append(value) }

    /** Copies the current log entries to an array. */
    def copyToVector(): Vector[String] = withLock(lock) { log.toVector }

    override def toString: String = withLock(lock) { log.mkString(",") }
  }

  /**
   * A runnable used in all the tests that adds the name of the runnable to a log object when
   * the runnable runs.
   * @param name The runnable name for debugging.
   * @param log The log to which this runnable appends its string.
   * @param sec The execution context on which the runnable is running.
   * @param contextTracker If present, the tracker is given the start and end times of each run.
   */
  private class LogRunnable(
      val name: String,
      val log: TestLog,
      sec: SequentialExecutionContext,
      contextTracker: Option[ContextTracker] = None,
      startedPromise: Option[Promise[Unit]] = None,
      blockingPromise: Option[Promise[Unit]] = None)
      extends Runnable {

    override def run(): Unit = {
      // Complete the started promise if provided, signaling that the runnable has started.
      startedPromise match {
        case Some(promise) => promise.success(())
        case None => ()
      }
      // Get the start and end times to inform the context tracker (if present).
      val startTime: TickerTime = contextTracker match {
        case Some(e) => e.clock().tickerTime()
        case None => TickerTime.MIN
      }
      logger.info(s"Running LogRunnable $name at ${sec.getClock.tickerTime()}")
      sec.assertCurrentContext()
      log.append(name)
      // Block until the promise is fulfilled if one is provided. This allows tests to
      // deterministically control when the runnable completes rather than relying on timing.
      blockingPromise match {
        case Some(promise) => TestUtils.awaitResult(promise.future, Duration.Inf)
        case None => ()
      }
      // Sleep a little but to allow possibility of overlap if there is a bug.
      // For a test that uses a fake clock, this sleep is not relevant.
      Thread.sleep( /*millis=*/ 50)
      if (contextTracker.isDefined) {
        contextTracker.get.informStartEnd(
          sec,
          startTime,
          contextTracker.get.clock().tickerTime()
        )
      }
    }
  }

  /**
   * The tracker that keeps track of the start and end times of each runnable run on each
   * execution context. Can later be called to check that no overlap occurred.
   * @param clock The clock used for time, scheduling etc.
   * @param contexts The list of execution contexts that this abstraction is tracking
   */
  private class ContextTracker(clock: TypedClock, contexts: Iterable[SequentialExecutionContext]) {

    /** Thread-safe tracker of start and end times for a specific context. */
    private class StartEndTimes {
      private[this] val lock = new ReentrantLock

      /** Set of start/end times for the execution context. */
      private[this] val times = new mutable.ArrayBuffer[(TickerTime, TickerTime)]

      /** Appends the given start and end times. */
      def append(startTime: TickerTime, endTime: TickerTime): Unit = {
        withLock(lock) {
          times.append((startTime, endTime))
        }
      }

      /**
       * Checks that all the start/end times for the tracked execution context are non-overlapping.
       */
      def checkNoOverlap(): Unit = {
        withLock(lock) {
          // Check that the execution context's start/end times do not overlap. Sort by end time and
          // just check overlap in adjacent entries.
          val sortedTimes = times.sortBy(_._2)
          var prevEndTime = TickerTime.MIN
          for (timePair <- sortedTimes) {
            val startTime = timePair._1
            val endTime = timePair._2
            assert(prevEndTime <= startTime, s"Should be $prevEndTime <= $startTime")
            assert(startTime <= endTime, s"Should be $startTime <= $endTime")
            prevEndTime = endTime
          }
        }
      }
    }

    /** Task start and ends times, per context under test. */
    private val contextTimes = new ConcurrentHashMap[SequentialExecutionContext, StartEndTimes]()

    for (context <- contexts) {
      assert(contextTimes.put(context, new StartEndTimes) == null)
    }

    def clock(): TypedClock = clock

    /** Inform the start/end times for the given `context`. */
    def informStartEnd(
        context: SequentialExecutionContext,
        startTime: TickerTime,
        endTime: TickerTime): Unit = {
      val times: StartEndTimes = contextTimes.get(context)
      times.append(startTime, endTime)
    }

    /** Check that all the start/end times for each context are non-overlapping. */
    def checkNoOverlap(): Unit = {
      for (context <- contexts) {
        val times: StartEndTimes = contextTimes.get(context)
        times.checkNoOverlap()
      }
    }
  }

  test("run()") {
    // Test plan: enqueue a task using `run()` on a sequential executor and ensure that the task
    // runs.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )
    val log = new TestLog
    sec.run { new LogRunnable("First", log, sec).run() }
    AssertionWaiter("Run").await { assert(log.copyToVector() == Vector("First")) }
  }

  test("schedule()") {
    // Test plan: Schedule a task on a sequential executor and ensure that the task runs after the
    // relevant time has passed.

    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)
    val log = new TestLog
    sec.schedule(
      "Single-schedule",
      200.milliseconds,
      new LogRunnable("Element", log, sec)
    )
    sec.getClock.advanceBy(200.milliseconds)
    // Wait for the work to run after the given time and element to be added to the log of
    // LogRunnable.
    AssertionWaiter("Sch").await { assert(log.copyToVector() == Vector("Element")) }
  }

  test("Scheduled task not starved by immediate tasks") {
    // Test plan: Schedule a task and then hammer the context with immediate tasks until after the
    // time the scheduled command is expected to run. Verify that the scheduled task runs before
    // at least one of the immediate tasks.

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )

    var commandsStarted: Int = 0 // number of commands started
    val commandsCompleted = new AtomicInteger(0) // number of commands completed
    val runOrder = Promise[Int]() // promise that takes the order of the scheduled command

    // Schedule a command.
    val delay = 2.milliseconds
    sec.schedule("Sched", delay, () => runOrder.success(commandsCompleted.incrementAndGet()))
    commandsStarted += 1

    // Keep track of the latest timestamp at which the context can prioritize the scheduled command
    // so we know when to stop hammering the context with immediate commands.
    val latestScheduledTime = sec.getClock.tickerTime() + delay

    // Constantly add command for immediate execution until we're past latestScheduledTime.
    var immediateTime = TickerTime.MIN
    while (immediateTime <= latestScheduledTime) {
      immediateTime = sec.getClock.tickerTime()
      sec.run { commandsCompleted.incrementAndGet() }
      commandsStarted += 1
    }

    // Verify expected that the scheduled command was not the last command to run after all commands
    // are done.
    AssertionWaiter(s"awaiting completion of $commandsStarted commands").await {
      assert(commandsCompleted.get() == commandsStarted)
    }
    assert(runOrder.isCompleted)
    assert(
      runOrder.future.value.get.get < commandsStarted,
      "scheduled command must not be the last to run"
    )
  }

  test("SequentialExecutionContext exception handler") {
    // Test plan: verify that non-fatal exceptions thrown by tasks result in interruption of the
    // thread that created the underlying pool. Unhandled exceptions on pool threads are also
    // supposed to trigger interruption, but we don't know how to trigger this case (the underlying
    // pool executor service eats all exceptions).

    // Verifies that the running `func` against an SEC eventually triggers interruption of the
    // thread on which the thread-pool was created.
    def verifyInterruption(func: SequentialExecutionContext => Unit): Unit = {
      val secPromise = Promise[SequentialExecutionContext]()
      val interruptedPromise = Promise[Unit]()

      // Create the Sec on a thread that we expect will get interrupted as part of this test case.
      new Thread(() => {
        secPromise.success(
          SequentialExecutionContext
            .createWithDedicatedPool(s"$getSafeName-Context", enableContextPropagation)
        )
        try {
          Thread.sleep( /*millis=*/ Long.MaxValue)
        } catch {
          case ex: InterruptedException =>
            interruptedPromise.success(Unit)
            throw ex
        }
      }).start()
      // Get the SEC.
      val sec: SequentialExecutionContext = Await.result(secPromise.future, Duration.Inf)

      // Run func, which is supposed to result in a unhandled command exception.
      func(sec)

      // Verify that the creating thread is interrupted when a command throws an unhandled\
      // exception.
      Await.result(interruptedPromise.future, Duration.Inf)
    }

    // Throw from an `run` task.
    verifyInterruption(
      (sec: SequentialExecutionContext) => sec.run { throw new IllegalStateException("test") }
    )

    // Throw from a `schedule` command.
    verifyInterruption(
      (sec: SequentialExecutionContext) =>
        sec.schedule("test", 1.nanoseconds, () => { throw new IllegalStateException("test") })
    )
  }

  test("SequentialExecutionContext scheduled order deterministic with non-monotonic clock") {
    // Test plan: schedule a sequence of tasks with fixed delay, but where the clock is going
    // backwards. Verify that the tasks run in the order they were added regardless. Since FakeClock
    // does not support going backwards, we use an alternate fake clock implementation for this test
    // case

    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 1,
      enableContextPropagation
    )
    var now = TickerTime.ofNanos(0)
    val clock = new TypedClock {
      override def tickerTime(): TickerTime = now
      override def instant(): Instant = throw new NotImplementedError()
    }
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      s"$getSafeName-Context",
      pool.executorService,
      pool.exceptionHandler,
      clock,
      pool.enableContextPropagation
    )

    val names: Vector[String] = (0 until 4).map { i =>
      s"Sched-$i"
    }.toVector
    val log = new TestLog
    for (name <- names) {
      now -= 1.millisecond
      sec.schedule(name, 10.milliseconds, new LogRunnable(name, log, sec))
    }
    now += 200.milliseconds
    // Trigger flush of outstanding tasks.
    sec.forTest.tickle()
    AssertionWaiter("Scheds").await { assert(log.copyToVector() == names) }
  }

  test("SequentialExecutionContext ordering guarantee") {
    // Test plan: verify that the SequentialExecutionContext ordering guarantee holds for various
    // combinations of scheduled and immediate commands.

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )

    {
      // Zero-delay/negative-delay scheduled and immediate commands run in the order they're added.
      val log = new TestLog
      sec.run { new LogRunnable("immediate1", log, sec).run() }
      sec.schedule("zero-delay", Duration.Zero, new LogRunnable("zero-delay", log, sec))
      sec.schedule("negative-delay", -1.seconds, new LogRunnable("negative-delay", log, sec))
      sec.run { new LogRunnable("immediate2", log, sec).run() }
      AssertionWaiter("Wait for tasks to run in order").await {
        assert(
          log.copyToVector() == Vector("immediate1", "zero-delay", "negative-delay", "immediate2")
        )
      }
    }

    {
      // Successive commands with increasing delays run in the order they're added.
      val log = new TestLog
      sec.run { new LogRunnable("a", log, sec).run() }
      sec.schedule("b", 1.nanosecond, new LogRunnable("b", log, sec))
      sec.schedule("c", 2.nanosecond, new LogRunnable("c", log, sec))
      AssertionWaiter("Wait for tasks with increasing delays to run in order").await {
        assert(log.copyToVector() == Vector("a", "b", "c"))
      }
    }

    {
      // Successive commands with the same delay run in the order they're added.
      val log = new TestLog
      val delay = 42.nanoseconds
      sec.schedule("a", delay, new LogRunnable("a", log, sec))
      sec.schedule("b", delay, new LogRunnable("b", log, sec))
      sec.schedule("c", delay, new LogRunnable("c", log, sec))
      AssertionWaiter("Wait for tasks with the same delay to run in order").await {
        assert(log.copyToVector() == Vector("a", "b", "c"))
      }
    }
  }

  test("SequentialExecutionContext handle spurious wakeup due to slow clock") {
    // Test plan: schedule a command for the near future but delay the clock advancing so that the
    // context ends up handling a "spurious wakeup", i.e. it wakes up to process commands and finds
    // no commands that are ready to run. Verify that the context continues scheduling tasks until
    // the clock advances to the scheduled time for the command.

    val clock = new FakeTypedClock
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 1,
      enableContextPropagation
    )
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      s"$getSafeName-Context",
      pool.executorService,
      pool.exceptionHandler,
      clock,
      pool.enableContextPropagation
    )
    val log = new TestLog
    sec.schedule("Sched", 10.milliseconds, new LogRunnable("Sched", log, sec))

    // Sleep in real-time for a bit and verify that the command does not run.
    Thread.sleep( /*milliseconds=*/ 50)
    assert(log.copyToVector().isEmpty)

    // Advance the fake clock but without triggering a manual tickle (we want the context itself to
    // to be dogged in scheduling itself until time advances, even without external stimulus).
    clock.advanceBy(10.milliseconds)
    AssertionWaiter("Sched").await { assert(log.copyToVector() == Vector("Sched")) }
  }

  test("Two runs in order") {
    // Test plan: Run two tasks on a sequential executor and ensure that the tasks run in order.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Pool",
      enableContextPropagation
    )
    val log = new TestLog
    sec.run { new LogRunnable("First", log, sec).run() }
    sec.run { new LogRunnable("Second", log, sec).run() }
    AssertionWaiter("Run two").await { assert(log.copyToVector() == Vector("First", "Second")) }
  }

  test("Future with SequentialExecutionContext") {
    // Test plan: Create an execution context and ensure that it works with a future, i.e., a
    // future bound to an execution context based on a sequential execution context runs eventually.
    // Also check that it runs on the right context (and not a random thread).
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Pool",
      enableContextPropagation
    )
    val fut: Future[String] = Future {
      sec.assertCurrentContext()
      "Hello"
    }(sec)
    val futureValue = Await.result(fut, 2.seconds)
    assert(futureValue == "Hello")
  }

  test("Future with 2 SequentialExecutionContexts") {
    // Test plan: Create two different execution contexts and run two consecutive futures on
    // different execution contexts. Check that both run in the expected order on the right context.
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(
        s"$getSafeName-Pool",
        numThreads = 2,
        enableContextPropagation
      )
    val sec1: SequentialExecutionContext = pool.createExecutionContext(s"$getSafeName-Context1")
    val sec2: SequentialExecutionContext = pool.createExecutionContext(s"$getSafeName-Context2")

    val fut: Future[String] = Future {
      sec1.assertCurrentContext()
      "Hello"
    }(sec1).map(s => {
      sec2.assertCurrentContext()
      s + " World"
    })(sec2)
    val futureValue = Await.result(fut, 2.seconds)
    assert(futureValue == "Hello World")
  }

  test("SequentialExecutionContext.run") {
    // Test plan: verify that the func passed to `run` runs on the executor.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )
    val promise = Promise[Unit]()
    sec.run {
      sec.assertCurrentContext()
      promise.success(Unit)
    }
    Await.result(promise.future, Duration.Inf)
  }

  test("SequentialExecutionContext.call") {
    // Test plan: verify that the func passed to `call` runs on the executor, and that the
    // returned future completes with the expected outcomes for the success and failure cases.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )

    // Success.
    {
      val future: Future[Int] = sec.call {
        sec.assertCurrentContext()
        42
      }
      assert(Await.result(future, Duration.Inf) == 42)
    }

    // Failure.
    {
      val future: Future[Int] = sec.call {
        sec.assertCurrentContext()
        throw new RuntimeException("test error")
      }
      assertThrow[RuntimeException]("test error") {
        Await.result(future, Duration.Inf)
      }
    }
  }

  test("SequentialExecutionContext.flatCall") {
    // Test plan: verify that the func passed to `flatCall` runs on the context, and that the
    // returned future completes with the expected outcome for permutations of:
    //  - The supplied `func` throws or returns a future
    //  - The future returned by `func` succeeds or fails
    //  - The future returned by `func` is already complete when returned, or it completes later
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )

    // Immediate success.
    {
      val future: Future[String] = sec.flatCall {
        sec.assertCurrentContext()
        Future.successful("Hello")
      }
      assert(Await.result(future, Duration.Inf) == "Hello")
    }

    // Deferred success.
    {
      val promise = Promise[String]()
      val future: Future[String] = sec.flatCall {
        sec.assertCurrentContext()
        promise.future
      }
      sec.schedule("deferred success", 1.millisecond, () => {
        assert(!future.isCompleted)
        promise.success("Bye")
      })
      assert(Await.result(future, Duration.Inf) == "Bye")
    }

    // Immediate failure.
    {
      val future: Future[String] = sec.flatCall {
        sec.assertCurrentContext()
        Future.failed(new RuntimeException("test error"))
      }
      assertThrow[RuntimeException]("test error") {
        Await.result(future, Duration.Inf)
      }
    }

    // Immediate failure due to thrown exception in func body.
    {
      val future: Future[String] = sec.flatCall {
        sec.assertCurrentContext()
        throw new RuntimeException("test error")
      }
      assertThrow[RuntimeException]("test error") {
        Await.result(future, Duration.Inf)
      }
    }

    // Deferred failure.
    {
      val promise = Promise[String]()
      val future: Future[String] = sec.flatCall {
        sec.assertCurrentContext()
        promise.future
      }
      sec.schedule("deferred failure", 1.millisecond, () => {
        assert(!future.isCompleted)
        promise.failure(new RuntimeException("test error"))
      })
      assertThrow[RuntimeException]("test error") {
        Await.result(future, Duration.Inf)
      }
    }
  }

  test("SequentialExecutionContext.callCancellable") {
    // Test plan: verify that the func passed to `callCancellable` runs on the context, and that the
    // returned cancellable's cancel request propagates to the cancellable supplied by the func
    // whether cancellation is requested before or after the command runs.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      s"$getSafeName-Context",
      enableContextPropagation
    )

    // Cancellation requested after func runs.
    {
      // Promise completed when the cancellation handle is invoked.
      val promise = Promise[Status]()
      val func: () => Cancellable = () => {
        sec.assertCurrentContext()
        promise.success
      }
      val cancellable: Cancellable = sec.callCancellable { func() }
      val reason = Status.INTERNAL.withDescription("for test")
      sec.run {
        // We know this call happens after `func` is invoked because we're running on the sec.
        cancellable.cancel(reason)
      }
      sec.run {}

      // Verify that the cancellation request propagates to the promise-backed cancellable.
      assert(Await.result(promise.future, Duration.Inf) == reason)
    }
    // Cancellation requested before func runs.
    {
      // Promise completed when the cancellation handle is invoked.
      val promise = Promise[Status]()
      val reason = Status.INTERNAL.withDescription("for test")
      sec.run {
        val func: () => Cancellable = () => {
          sec.assertCurrentContext()
          promise.success
        }
        val cancellable: Cancellable = sec.callCancellable { func() }

        // We know this call happens before `func` is invoked because we're running on the sec.
        cancellable.cancel(reason)
      }

      // Verify that the cancellation request propagates to the promise-backed cancellable.
      assert(Await.result(promise.future, Duration.Inf) == reason)
    }
  }

  test("Run then schedule") {
    // Test plan: Execute a task on a sequential executor and schedule another task for later.
    // Ensure that the first task runs first.
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)

    val log = new TestLog
    sec.run { new LogRunnable("First", log, sec).run() }
    sec.schedule(
      "Run2",
      50.milliseconds,
      new LogRunnable("Second", log, sec)
    )
    // Advance the clock and check for the order of execution.
    sec.getClock.advanceBy(50.milliseconds)
    AssertionWaiter("Exe then Sch").await {
      assert(log.copyToVector() == Vector("First", "Second"))
    }
  }

  test("Schedule then run") {
    // Test plan: Schedule a task on an execution context for a later time and then enqueue a task
    // using `run()`. Check that the immediate task runs first.
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)
    val log = new TestLog
    sec.schedule(
      "Run2",
      200.milliseconds,
      new LogRunnable("Second", log, sec)
    )
    // Call run() after schedule() (which is a bit in the future) and check the order of execution.
    sec.run { new LogRunnable("First", log, sec).run() }
    sec.getClock.advanceBy(200.milliseconds)
    AssertionWaiter("Exe then Sch").await {
      assert(log.copyToVector() == Vector("First", "Second"))
    }
  }

  test("Two schedules") {
    // Test plan: Schedule two tasks on an execution context and make sure that they run in the
    // expected order.
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)

    val log = new TestLog
    sec.schedule("+100 millis", 100.milliseconds, new LogRunnable("Second", log, sec))
    sec.schedule("+50 millis", 50.milliseconds, new LogRunnable("First", log, sec))
    // Advance the clock by the delay of the later scheduled task and check the order.
    sec.getClock.advanceBy(100.milliseconds)
    AssertionWaiter("2 Sch").await { assert(log.copyToVector() == Vector("First", "Second")) }
  }

  test("Two execution contexts") {
    // Test plan: Create two execution contexts with a pool of 2 threads and schedule a task on
    // each context and make sure that both are run.
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec1 = FakeSequentialExecutionContext.create(s"$getSafeName-Context1", pool = pool)
    val sec2 = FakeSequentialExecutionContext.create(s"$getSafeName-Context2", pool = pool)

    val log1 = new TestLog
    val log2 = new TestLog
    sec1.schedule(
      "Run1",
      50.milliseconds,
      new LogRunnable("First", log1, sec1)
    )
    sec2.schedule(
      "Run2",
      100.milliseconds,
      new LogRunnable("Second", log2, sec2)
    )
    // Advance the clocks based on the maximum of the two scheduled delay and then check.
    sec1.getClock.advanceBy(100.milliseconds)
    sec2.getClock.advanceBy(100.milliseconds)
    AssertionWaiter("Sch1").await { assert(log1.copyToVector() == Vector("First")) }
    AssertionWaiter("Sch2").await { assert(log2.copyToVector() == Vector("Second")) }
  }

  test("Cancel scheduled task") {
    // Test plan: Create an sequential executor and schedule a task. Cancel it and check for
    // cancellation. Then enqueue a task and check that it runs.
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)
    val log = new TestLog

    // Schedule at task and cancel the supplied token while running in the execution context to
    // ensure that the scheduled task does not race with the cancellation request.
    sec.run {
      val cancelToken = sec.schedule(
        "ScheduleCancelTask",
        100.milliseconds,
        new LogRunnable("First", log, sec)
      )
      cancelToken.cancel(Status.CANCELLED)
    }
    sec.getClock.advanceBy(100.milliseconds)
    sec.run { new LogRunnable("Second", log, sec).run() }
    AssertionWaiter("Exe1").await { assert(log.copyToVector() == Vector("Second")) }
  }

  test("test scheduleRepeating") {
    // Test plan: Create an execution context with a fake clock and then check that schedule
    // repeating schedules periodically and also can be cancelled.

    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val sec = FakeSequentialExecutionContext.create(s"$getSafeName-Context", pool = pool)
    val log1 = new TestLog
    val repeatInterval = 10.seconds
    val logRunnable = new LogRunnable("First", log1, sec)
    val cancelToken: Cancellable = sec.scheduleRepeating(
      "Test",
      repeatInterval,
      logRunnable
    )

    // Advance the clock so that the scheduled event runs.
    sec.getClock.advanceBy(repeatInterval)

    // Wait for the LogRunnable to run. This will block until it observes the log change resulting
    // from the execution of the LogRunnable.
    AssertionWaiter("Exe1").await { assert(log1.copyToVector() == Vector("First")) }

    // Advance the clock on `sec` to ensure that it happens after the execution of the LogRunnable
    // and the scheduling of the next event.
    sec.call(sec.getClock.advanceBy(repeatInterval))

    // Wait for the LogRunnable to run. This will block until it observes the log change resulting
    // from the execution of the LogRunnable.
    AssertionWaiter("Exe2").await { assert(log1.copyToVector() == Vector("First", "First")) }

    // Cancel the schedule repeating and make sure that it does not run again. Schedule another
    // operation and then check that only this operation is run.
    cancelToken.cancel(Status.CANCELLED)

    // Create a new test log and a new log runnable to check the schedule being done below.
    val log2 = new TestLog
    val logRunnable2 = new LogRunnable("Second", log2, sec)

    sec.schedule("Final", repeatInterval * 2, logRunnable2)

    // Advance the clock on `sec` to ensure it happens after the execution of the LogRunnable, and
    // the scheduling of the next event. Additionally, advance the time by a sufficient
    // amount to give scheduleRepeating a chance to run if there is a bug.
    // We know that the final operation above will run. So if the number of operations grows by
    // more than 1, the waitForAssertions will fail.
    sec.call(sec.getClock.advanceBy(repeatInterval * 3))
    AssertionWaiter("Exe1").await { assert(log2.copyToVector() == Vector("Second")) }
  }

  test("SequentialExecutionContext.scheduleRepeating with throwing commands") {
    // Test plan: defeat the uncaught-exception handler by creating a pool from a throwaway thread.
    // (so we don't care if the creating thread is interrupted). Then intentionally throw exceptions
    // from a command passed to `scheduleRepeating` and make sure that the command continues to run.

    // Create an using a fake clock SEC that cannot interrupt the current thread (uncaught
    // exceptions will result in an interruption of the thread on which the executor is created).
    val fakeTypedClock = new FakeTypedClock
    val secFuture: Future[SequentialExecutionContext.Impl] = Future {
      val secPool = SequentialExecutionContextPool.create(
        s"$getSafeName-Pool",
        numThreads = 1,
        enableContextPropagation
      )
      new SequentialExecutionContext.Impl(
        secPool.name,
        s"$getSafeName-Context",
        secPool.executorService,
        secPool.exceptionHandler,
        fakeTypedClock,
        secPool.enableContextPropagation
      )
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val sec: SequentialExecutionContext.Impl = Await.result(secFuture, Duration.Inf)

    // Define the repeating command. It increments a counter every time it runs then throws an
    // exception.
    val counter = new AtomicInteger(0)
    val runnable: Runnable = () => {
      sec.assertCurrentContext()
      val count: Int = counter.incrementAndGet()
      logger.info(s"Throwing from repeatable command: count=$count")
      throw new RuntimeException("uncaught!")
    }
    val interval = 10.seconds
    val cancellable: Cancellable = sec.scheduleRepeating("throwing", interval, runnable)

    // Advance the clock by 10 seconds repeatedly and verify that the command executes each time.
    for (expectedCount <- 1 to 10) {
      // Advance the executor on the executor, to make sure that the executor observes the desired
      // start time.
      sec.run {
        fakeTypedClock.advanceBy(interval)
        sec.forTest.tickle()
      }
      AssertionWaiter("Wait for commands to execute every 10-seconds").await {
        assert(counter.get() == expectedCount)
      }
    }
    cancellable.cancel(Status.CANCELLED)
  }

  test("Randomized execution contexts") {
    // Test plan: Test execute and schedule with a random number of executions on a set of threads
    // and execution contexts to ensure that forward progress and mutual exclusion (of a context)
    // does happen as expected.
    // Create a number of threads and execution contexts (more contexts than threads). Each thread
    // comes up with a "plan" for what it plans to do in terms of execute/schedule and then
    // generates the load. We make the following checks as the test proceeds:
    // (1) Ensure that each task runs on the intended sequential executor.
    // (2) Each context has no overlapping runs during the execution.
    // (3) All work is eventually completed.

    /**
     * The work planned by a test thread
     * @param scheduleName Debugging name for the schedule operation
     * @param runName Name of the runnable
     * @param delay If 0, the operation is execute; otherwise schedule it with this delay
     * @param contextIndex The index of the execution context (in the `contexts` array) to run on)
     */
    case class PlannedWork(
        scheduleName: String,
        runName: String,
        delay: FiniteDuration,
        contextIndex: Int)

    // Create a pool with some number of threads and then more contexts.
    val random = new Random(RealtimeTypedClock.tickerTime().nanos)
    val numPoolThreads = 4
    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numPoolThreads,
      enableContextPropagation
    )
    val contexts = new mutable.ArrayBuffer[(SequentialExecutionContext, TestLog)]()
    val numContexts = 10
    // For each array element, a tuple of an execution context and a test object "protected" by the
    // context.
    for (i <- 0 until numContexts) {
      contexts.append((pool.createExecutionContext(s"$getSafeName-Context$i"), new TestLog))
    }
    logger.info(
      s"Running test with $numPoolThreads pool threads and $numContexts execution contexts"
    )
    val contextTracker = new ContextTracker(RealtimeTypedClock, contexts.map(_._1))

    // Create a set of threads and the plan for each of those threads - place that plan in an array
    // that has one entry for each thread.
    val numThreads = 3
    val numTasks = 30
    val threads = new mutable.ArrayBuffer[Thread]
    val threadPlans = new Array[mutable.ArrayBuffer[PlannedWork]](numThreads)
    logger.info(s"Generating work for $numThreads threads and $numTasks tasks")
    for (i <- 0 until numThreads) {
      val thread: Thread = new Thread() {
        val planList = new mutable.ArrayBuffer[PlannedWork]()
        threadPlans(i) = planList
        for (j <- 0 until numTasks) {
          val contextIndex = random.nextInt(numContexts)
          val isRun = random.nextInt() % 4 != 0 // Higher chance for executes than schedules
          val delayMs = if (isRun) 0 else random.nextInt(100)
          // Make the prefix unique by adding "j" to the entry name.
          val prefix = s"Thread-$i-on-Exec-" + contextIndex + "-Work-" + j
          val scheduleName = s"$prefix-Schedule"
          val runName = s"""${if (isRun) "Run" else "Schedule"}-Runnable"""
          val plan =
            PlannedWork(scheduleName, runName, delayMs.milliseconds, contextIndex)
          planList.append(plan)
        }
        // The plan has been generated. Print it out for debugging.
        logger.info(s"For thread $i, the plan is $planList")
        for (plan <- planList) {
          val (context, log): (SequentialExecutionContext, TestLog) =
            contexts(plan.contextIndex)
          val logRunnable = new LogRunnable(plan.runName, log, context)
          if (plan.delay.toNanos == 0) {
            context.run { logRunnable.run() }
          } else {
            context.schedule(plan.scheduleName, plan.delay, logRunnable)
          }
        }
      }
      threads.append(thread)
      thread.start()
    }
    // Wait for all the threads to finish executing their work.
    for (thread <- threads) {
      thread.join()
    }
    // Wait for a little bit to let the pool run all the scheduled/executed work.
    val expectedTotal = numThreads * numTasks
    AssertionWaiter(s"wait for $expectedTotal entries").await {
      val total = contexts.map {
        case (_, log: TestLog) => log.copyToVector().length
      }.sum
      assert(total == expectedTotal)
    }

    logger.info(s"Logs for all TestObjects")
    assert(contexts.size == numContexts)
    for (i <- 0 until numContexts) {
      val (_, log): (_, TestLog) = contexts(i)
      logger.info(s"Object $i: $log")
    }

    // Now detect if any of the plan objects are not present in the test objects. That is, for each
    // plan entry, look it up in the test objects (matching is done by name of the immediate task
    // since the names were chosen uniquely).
    val actualPlanNames: Set[String] = contexts.flatMap {
      case (_, log: TestLog) => log.copyToVector()
    }.toSet
    for (planList <- threadPlans) {
      for (plan <- planList) {
        assert(actualPlanNames.contains(plan.runName))
      }
    }
    // Now check that the execution context tasks ran sequentially and no tasks got scheduled
    // concurrently on more than one thread.
    contextTracker.checkNoOverlap()
  }

  /**
   * Helper running some operation that is expected to result in an uncaught command exception.
   * Verifies that [[SequentialExecutionContextPool.Metrics]] are updated and that the thread on
   * which the pool was created is interrupted.
   *
   * @param description a descriptive, unique string that is used to name the underlying pool so
   *                    that the metrics can be inspected.
   * @param op: operation to run on the SEC under test.
   */
  private def assertUncaughtException(description: String)(
      op: SequentialExecutionContext => Unit): Unit = {
    // We need to create the execution context on a separate thread because the uncaught
    // exception handler is going to interrupt it.
    val poolName = s"$getSafeName-$description-uncaught-exception-pool"
    val poolPromise = Promise[SequentialExecutionContextPool]()
    val interruptedPromise = Promise[Unit]()
    val createPoolThread = new Thread {
      override def run(): Unit = {
        // Initialize the pool.
        poolPromise.success(
          SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
        )

        // Wait for the thread to be interrupted.
        try {
          Thread.sleep(Long.MaxValue)
        } catch {
          case _: InterruptedException =>
            // Signal to the test thread that the expected interrupted exception was received.
            interruptedPromise.success(Unit)

          // We don't bother restoring the interrupted state of the current thread because it's
          // about to exit.
        }
      }
    }
    createPoolThread.start()
    val pool = Await.result(poolPromise.future, Duration.Inf)

    val initialErrorCount: Int = getUncaughtErrorCount(poolName)

    // Now run the operation that is expected to trigger an uncaught exception, and then wait for
    // the expected interruption of the thread on which the pool was created.
    val sec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-$description-execution-context")
    op(sec)
    Await.result(interruptedPromise.future, Duration.Inf)

    // Verify that the appropriate PrefixLogger error metric was incremented.
    assert(initialErrorCount + 1 == getUncaughtErrorCount(poolName))
  }

  // Get the PrefixLogger `UNHANDLED_SEC_POOL_EXCEPTION` alert count metric value.
  private def getUncaughtErrorCount(poolName: String): Int = {
    MetricUtils.getPrefixLoggerErrorCount(
      Severity.CRITICAL,
      CachingErrorCode.UNCAUGHT_SEC_POOL_EXCEPTION,
      poolName
    )
  }

  test("Test uncaught exception handler") {
    // Test plan: throw exceptions of various types (non-fatal, fatal, interrupt) from `run` and
    // `schedule` and verify that the uncaught exception is recorded in
    // SequentialExecutionContextPool.Metrics.

    assertUncaughtException("run-non-fatal") { sec: SequentialExecutionContext =>
      sec.run { throw new IllegalArgumentException("foo") }
    }
    assertUncaughtException("run-fatal") { sec: SequentialExecutionContext =>
      sec.run {
        // Simulate a non-local return, which is a fatal error.
        throw new RuntimeException with scala.util.control.ControlThrowable
      }
    }
    assertUncaughtException("schedule-interrupted") { sec: SequentialExecutionContext =>
      sec.schedule("interruptee", 1.millisecond, () => throw new InterruptedException())
    }
  }

  test("InterruptedException in SEC task propagates interrupt to calling thread") {
    // Test plan: verify that a thread interrupt encountered during the execution of an SEC task
    // is propagated after being handled. Because our underlying ContextAwareScheduledExecutor
    // implementations internally process the interrupt, it's difficult to verify this behavior
    // deterministically in an E2E fashion. Instead, we test the tickle task directly.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val contextName = s"$getSafeName-Context"
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      contextName,
      pool.executorService,
      pool.exceptionHandler,
      RealtimeTypedClock,
      pool.enableContextPropagation
    )

    // Enqueue a task which, unless interrupted, will run forever.
    sec.forTest.enqueue(() => Thread.sleep(Long.MaxValue))
    val thread = new Thread(() => sec.forTest.tickle())
    thread.start()

    // Interrupt the thread, which should show up initially by throwing InterruptedException
    // from `sleep` and terminate, and then further verify that the interrupt bit on the Thread
    // is set.
    assert(thread.isAlive)
    thread.interrupt()
    thread.join()
    assert(thread.isInterrupted)
  }

  test("Uncaught exception with creator thread gone") {
    // Test plan: Verify that if the creator thread of a pool is gone, the uncaught exception metric
    // is still incremented.
    val poolName = s"$getSafeName-pool"
    // The pool will be initialized in a separate thread.
    var pool: SequentialExecutionContextPool = null
    val createPoolThread = new Thread {
      override def run(): Unit = {
        // Initialize the pool.
        pool =
          SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
      }
    }
    createPoolThread.start()
    createPoolThread.join()

    val sec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-execution-context")
    val initialErrorCount: Int = getUncaughtErrorCount(poolName)
    sec.run { throw new IllegalArgumentException("bar") }

    // The metric will be updated after the command is executed, so we wait for it.
    AssertionWaiter("Uncaught exception metric gets incremented").await {
      assert(initialErrorCount + 1 == getUncaughtErrorCount(poolName))
    }
  }

  test("Test an error in Future map") {
    // Throw an exception from the function  passed to `Future.map` and verify that the exception
    // is yielded by the returned future and  that the exception is not recorded as "uncaught".
    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val sec = pool.createExecutionContext(s"$getSafeName-Context")
    val fut: Future[Int] = Future
      .successful(42)
      .map { _: Int =>
        throw new IllegalArgumentException("non-fatal message")
      }(sec)
    assertThrow[IllegalArgumentException]("non-fatal message") {
      Await.result(fut, Duration.Inf)
    }
    // Uncaught exception metrics should not be incremented.
    assert(getUncaughtErrorCount(poolName) == 0)
  }

  test("Execution time is recorded") {
    // Test plan: run a task that (fake) sleeps for a fixed time and verify that the recorded
    // execution time is equal to the fixed sleep time.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val contextName = s"$getSafeName-Context"
    val fakeTypedClock = new FakeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      contextName,
      pool.executorService,
      pool.exceptionHandler,
      fakeTypedClock,
      pool.enableContextPropagation
    )

    val initialValue: Double = MetricUtils.getHistogramSum(
      CollectorRegistry.defaultRegistry,
      "sequential_execution_context_execution_duration_nanoseconds",
      Map("pool_name" -> poolName, "context_name" -> contextName)
    )

    val log = new TestLog

    val sleepTime: FiniteDuration = 100.nanoseconds
    sec.run { fakeTypedClock.advanceBy(sleepTime) }
    sec.run { new LogRunnable("Done", log, sec).run() }
    AssertionWaiter("command ran").await { assert(log.copyToVector() == Vector("Done")) }
    AssertionWaiter("execution delay metric updated").await {
      val finalValue: Double = MetricUtils.getHistogramSum(
        CollectorRegistry.defaultRegistry,
        "sequential_execution_context_execution_duration_nanoseconds",
        Map("pool_name" -> poolName, "context_name" -> contextName)
      )
      assert(
        initialValue + sleepTime.toNanos == finalValue,
        s"Expected ${initialValue + sleepTime.toNanos} but got $finalValue"
      )
    }
  }

  test("Execution delay is recorded") {
    // Test plan: enqueue a task using `run()` which enqueues another task then advances a (fake)
    // clock. The delay recorded for the second task should be equal to the duration advanced by the
    // clock.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val contextName = s"$getSafeName-Context"
    val fakeTypedClock = new FakeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      contextName,
      pool.executorService,
      pool.exceptionHandler,
      fakeTypedClock,
      pool.enableContextPropagation
    )

    val initialValue: Double = MetricUtils.getHistogramSum(
      CollectorRegistry.defaultRegistry,
      "sequential_execution_context_execution_delay_duration_nanoseconds",
      Map("pool_name" -> poolName, "context_name" -> contextName)
    )

    val log = new TestLog

    val advanceTime: FiniteDuration = 100.milliseconds
    sec.run {
      // The second task is enqueued "now"...
      sec.run { new LogRunnable("Done", log, sec).run() }

      // ...but can only run on the sequential executor after `advanceTime` has elapsed.
      fakeTypedClock.advanceBy(advanceTime)
    }
    AssertionWaiter("command ran").await { assert(log.copyToVector() == Vector("Done")) }
    AssertionWaiter("execution delay metric updated").await {
      val finalValue: Double = MetricUtils.getHistogramSum(
        CollectorRegistry.defaultRegistry,
        "sequential_execution_context_execution_delay_duration_nanoseconds",
        Map("pool_name" -> poolName, "context_name" -> contextName)
      )
      assert(
        initialValue + advanceTime.toNanos == finalValue,
        s"Expected ${initialValue + advanceTime.toNanos} but got $finalValue"
      )
    }
  }

  test("Execution delay for scheduled commands is correct") {
    // Test plan: schedule a task to run a fixed time in the future, enqueue a different task
    // to run immediately that (fake) sleeps for more than the scheduled time, and verify that the
    // recorded execution delay is the difference between the sleep time and the scheduled time.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val contextName = s"$getSafeName-Context"
    val fakeTypedClock = new FakeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      contextName,
      pool.executorService,
      pool.exceptionHandler,
      fakeTypedClock,
      pool.enableContextPropagation
    )

    val initialValue: Double = MetricUtils.getHistogramSum(
      CollectorRegistry.defaultRegistry,
      "sequential_execution_context_execution_delay_duration_nanoseconds",
      Map("pool_name" -> poolName, "context_name" -> contextName)
    )

    val log = new TestLog

    val scheduledTime: FiniteDuration = 250.milliseconds
    val sleepTime: FiniteDuration = 300.milliseconds
    sec.schedule("done-command", scheduledTime, new LogRunnable("Done", log, sec))
    sec.run { fakeTypedClock.advanceBy(sleepTime) }
    AssertionWaiter("command ran").await { assert(log.copyToVector() == Vector("Done")) }
    AssertionWaiter("execution delay metric updated").await {
      val finalValue: Double = MetricUtils.getHistogramSum(
        CollectorRegistry.defaultRegistry,
        "sequential_execution_context_execution_delay_duration_nanoseconds",
        Map("pool_name" -> poolName, "context_name" -> contextName)
      )
      assert(
        initialValue + (sleepTime - scheduledTime).toNanos == finalValue,
        s"Expected ${initialValue + (sleepTime - scheduledTime).toNanos} but got $finalValue"
      )
    }
  }

  test("Pending command gauges record correctly") {
    // Test plan: verify that the gauge metrics for immediate and scheduled tasks are set to the
    // correct values. Verify this by scheduling several tasks for both immediate and future
    // execution, and then checking the gauge values. Also verify that the gauges are updated
    // correctly after a scheduled command is cancelled.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 1, enableContextPropagation)
    val contextName = s"$getSafeName-Context"
    val fakeTypedClock = new FakeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      pool.name,
      contextName,
      pool.executorService,
      pool.exceptionHandler,
      fakeTypedClock,
      pool.enableContextPropagation
    )

    val scheduledTime: FiniteDuration = 10.milliseconds
    // Submit "initialization command".
    val fut: Future[(Double, Double)] = sec.call {
      sec.run {}
      sec.run {}
      sec.run {}
      sec.schedule("scheduled-command-1", scheduledTime, () => {})
      sec.schedule("scheduled-command-2", scheduledTime, () => {})

      val immediateValue: Double = MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "sequential_execution_context_pending_commands_immediate",
        Map("pool_name" -> poolName, "context_name" -> contextName)
      )
      val scheduledValue: Double = MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "sequential_execution_context_pending_commands_scheduled",
        Map("pool_name" -> poolName, "context_name" -> contextName)
      )
      (immediateValue, scheduledValue)
    }
    val (numPendingCommandsImmediate, numPendingCommandsScheduled): (Double, Double) =
      Await.result(fut, Duration.Inf)

    // Expect 3 pending immediate commands.
    assert(numPendingCommandsImmediate == 3)
    assert(numPendingCommandsScheduled == 2)

    // Wait for the scheduled commands to run.
    fakeTypedClock.advanceBy(20.milliseconds)

    // Verify that the gauges are updated correctly after the scheduled commands run.
    val scheduledAfterRun: Double = Await.result(
      sec.call {
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "sequential_execution_context_pending_commands_scheduled",
          Map("pool_name" -> poolName, "context_name" -> contextName)
        )
      },
      Duration.Inf
    )
    assertResult(0)(scheduledAfterRun)

    // Schedule a command.
    val cancellable: Cancellable = sec.schedule(
      "scheduled-command-3",
      10.milliseconds,
      () => {}
    )

    // Verify that the gauge is incremented after the command is scheduled.
    val scheduledAfterSchedule: Double = Await.result(
      sec.call {
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "sequential_execution_context_pending_commands_scheduled",
          Map("pool_name" -> poolName, "context_name" -> contextName)
        )
      },
      Duration.Inf
    )
    assertResult(1)(scheduledAfterSchedule)

    // Cancel the scheduled command.
    cancellable.cancel()

    // Verify that the gauge is decremented after the command is cancelled.
    val scheduledAfterCancel: Double = Await.result(
      sec.call {
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "sequential_execution_context_pending_commands_scheduled",
          Map("pool_name" -> poolName, "context_name" -> contextName)
        )
      },
      Duration.Inf
    )
    assertResult(0)(scheduledAfterCancel)
  }

  test("Number of threads gauge records correctly") {
    // Test plan: initialize a pool with a fixed number of threads, submit at least as many tasks to
    // run in the pool, and verify that the number of threads gauge matches the expected number of
    // threads created.

    val poolName = s"$getSafeName-Pool"
    val pool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(poolName, numThreads = 7, enableContextPropagation)
    val startingNumThreads: Double = MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "sequential_execution_context_pool_num_threads",
      Map("pool_name" -> poolName)
    )
    for (_ <- 1 to 7) {
      pool.executorService.execute(() => {})
    }
    val finalNumThreads: Double = MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "sequential_execution_context_pool_num_threads",
      Map("pool_name" -> poolName)
    )
    assert(
      finalNumThreads == startingNumThreads + 7,
      s"Expected $startingNumThreads + 7 = ${startingNumThreads + 7} threads, " +
      s"but got $finalNumThreads"
    )
  }


  /**
   * Gets the value of the spurious wakeups metric for the given pool, SEC, and reason.
   *
   * @param pool The pool to get the metric for.
   * @param sec The SEC to get the metric for.
   * @param reason The reason for the spurious wakeup. One of "ALREADY_RUNNING" or
   *               "NO_PENDING_COMMANDS".
   */
  private def getSpuriousWakeupMetricTotal(
      pool: SequentialExecutionContextPool,
      sec: SequentialExecutionContext,
      reason: String): Double = {
    val registry: CollectorRegistry = CollectorRegistry.defaultRegistry
    MetricUtils.getMetricValue(
      registry,
      "sequential_execution_context_spurious_wakeups_total",
      Map("pool_name" -> pool.name, "context_name" -> sec.getName, "reason" -> reason)
    )
  }

  test("Spurious wakeups metric is updated correctly with realtime clock") {
    // Test plan: verify that the spurious wakeups metric is updated correctly when the SEC is
    // woken up unnecessarily with a realtime clock. Verify this by waking up the SEC without a task
    // task to run and when a thread on the SEC is already running a task. The spurious wakeups
    // metric should be updated correctly in both cases.

    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val clock = RealtimeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      poolName = pool.name,
      name = s"$getSafeName-$enableContextPropagation-Context",
      pool.executorService,
      pool.exceptionHandler,
      clock,
      pool.enableContextPropagation
    )

    // Verify that the spurious wakeups metric is initially 0.
    assertResult(0)(getSpuriousWakeupMetricTotal(pool, sec, "ALREADY_RUNNING"))
    assertResult(0)(getSpuriousWakeupMetricTotal(pool, sec, "NO_PENDING_COMMANDS"))

    // Verify that the spurious wakeups metric is updated correctly when the SEC is woken up without
    // a task to run.
    sec.forTest.tickle()
    AssertionWaiter("Spurious wakeups metric is updated correctly").await {
      assertResult(1)(getSpuriousWakeupMetricTotal(pool, sec, "NO_PENDING_COMMANDS"))
    }
    // The ALREADY_RUNNING spurious wakeups metric should not be updated.
    assertResult(0)(getSpuriousWakeupMetricTotal(pool, sec, "ALREADY_RUNNING"))

    // Verify that the spurious wakeups metric is updated correctly when a thread on the SEC is
    // already running a task. The first task is run, causing the tickle to be run on the
    // underlying executor. Then we manually tickle the SEC again while the first task is still
    // running.
    val log = new TestLog
    val blockingPromise1 = Promise[Unit]()
    val startedPromise1 = Promise[Unit]()
    sec.run {
      new LogRunnable(
        "First",
        log,
        sec,
        startedPromise = Some(startedPromise1),
        blockingPromise = Some(blockingPromise1)
      ).run()
    }
    TestUtils.awaitResult(startedPromise1.future, Duration.Inf)
    sec.forTest.tickle()
    blockingPromise1.success(())
    AssertionWaiter("LogRunnable ran").await { assert(log.copyToVector() == Vector("First")) }
    AssertionWaiter("Spurious wakeups metric is updated correctly").await {
      assertResult(1)(getSpuriousWakeupMetricTotal(pool, sec, "ALREADY_RUNNING"))
    }
    // The NO_PENDING_COMMANDS spurious wakeups metric should not be updated.
    assertResult(1)(getSpuriousWakeupMetricTotal(pool, sec, "NO_PENDING_COMMANDS"))

    // Verify that the spurious wakeups metric is also updated correctly when the SEC is woken up
    // while a scheduled task is running.
    val startedPromise2 = Promise[Unit]()
    val blockingPromise2 = Promise[Unit]()
    sec.schedule(
      "Run",
      50.millisecond,
      new LogRunnable(
        "Second",
        log,
        sec,
        startedPromise = Some(startedPromise2),
        blockingPromise = Some(blockingPromise2)
      )
    )
    // Wait for the scheduled task to start running (blocked on promise).
    TestUtils.awaitResult(startedPromise2.future, Duration.Inf)
    sec.forTest.tickle()
    blockingPromise2.success(())
    AssertionWaiter("LogRunnable ran").await {
      assert(log.copyToVector() == Vector("First", "Second"))
    }
    AssertionWaiter("Spurious wakeups metric is updated correctly").await {
      assertResult(2)(getSpuriousWakeupMetricTotal(pool, sec, "ALREADY_RUNNING"))
    }
    // The NO_PENDING_COMMANDS spurious wakeups metric should not be updated.
    assertResult(1)(getSpuriousWakeupMetricTotal(pool, sec, "NO_PENDING_COMMANDS"))
  }

  test("SEC is not woken up when all scheduled tasks are cancelled") {
    // Test plan: verify that the SEC is not woken up when all scheduled tasks are cancelled. Verify
    // this by scheduling tasks on the SEC, cancelling them, and then verifying that the spurious
    // wakeups metric is not updated.

    val pool = SequentialExecutionContextPool.create(
      s"$getSafeName-Pool",
      numThreads = 2,
      enableContextPropagation
    )
    val clock = RealtimeTypedClock
    val sec = new SequentialExecutionContext.Impl(
      poolName = pool.name,
      name = s"$getSafeName-$enableContextPropagation-Context",
      pool.executorService,
      pool.exceptionHandler,
      clock,
      pool.enableContextPropagation
    )

    // Schedule tasks and then cancel them. Scheduling a task should schedule a tickle on the SEC,
    // but cancelling it should cancel the tickle if there are no other pending tasks.
    val cancellable1: Cancellable = sec.schedule(
      "Run1",
      400.milliseconds,
      () => fail("Expected cancelled task ran")
    )
    val cancellable2: Cancellable = sec.schedule(
      "Run2",
      400.milliseconds,
      () => fail("Expected cancelled task ran")
    )
    cancellable1.cancel()
    cancellable2.cancel()

    Thread.sleep(500)

    // Verify that the spurious wakeups metric is not updated, as the SEC should not have been
    // woken up.
    assertResult(0)(getSpuriousWakeupMetricTotal(pool, sec, "NO_PENDING_COMMANDS"))
    assertResult(0)(getSpuriousWakeupMetricTotal(pool, sec, "ALREADY_RUNNING"))
  }
}
