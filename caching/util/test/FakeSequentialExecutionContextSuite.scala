package com.databricks.caching.util

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.ServerTestUtils.AttributionContextPropagationTester
import com.databricks.caching.util.Lock.withLock
import com.databricks.testing.DatabricksTest
import com.databricks.threading.FakeClock
import scala.concurrent.{Await, Promise}

import com.databricks.caching.util.FakeSequentialExecutionContextPool.UncaughtExceptionInterceptor

/**
 * Tests for [[FakeSequentialExecutionContext]]. Its functionality is also indirectly tested in
 * [[SequentialExecutionContextSuite]], so this suite is mostly focused on
 * [[com.databricks.threading.FakeClock]] and [[FakeTypedClock]] integration.
 */
class FakeSequentialExecutionContextSuite
    extends DatabricksTest
    with TestName
    with AttributionContextPropagationTester {

  test("Advance time") {
    // Test plan: verify that advancing the `FakeTypedClock` used to create an executor results in
    // execution of all pending tasks that were scheduled to run at or before the new clock time,
    // including recursively scheduled tasks. Use all overloads supporting advancing time.
    for (advancer: Advancer <- Advancer.ADVANCERS) {
      val sec = FakeSequentialExecutionContext.create(s"$getSafeName-${advancer.getClass}")
      val log = new ExecutionLog(sec)

      // Schedule tasks for startTime + 10 minutes, startTime + 11 minutes and startTime + 12
      // minutes. The middle task is scheduled recursively.
      val startTime: TickerTime = sec.getClock.tickerTime()
      sec.schedule(
        "task1",
        10.minutes,
        () => {
          log.logCallback("task1")

          // Recursively schedule the middle task at startTime + 11 seconds
          val now: TickerTime = sec.getClock.tickerTime()
          val delay: FiniteDuration = (startTime + 11.minutes) - now
          sec.schedule("task2", delay, () => {
            log.logCallback("task2")
          })
        }
      )
      sec.schedule("task3", 12.minutes, () => {
        log.logCallback("task3")
      })

      // Execute a control task that should execute immediately. After it runs, we can verify that
      // the scheduled tasks have not yet run.
      sec.run {
        log.logCallback("controlTask")
      }
      AssertionWaiter("Wait for the control task").await {
        assert(log.getLog == Seq(("controlTask", startTime)))
      }

      // Advance to startTime + 11 minutes. This results in execution of the first and second tasks.
      advancer.advance(sec.getClock, startTime = startTime, desiredTime = startTime + 11.minutes)
      AssertionWaiter("Wait for task1, task2").await {
        assert(
          log.getLog == Seq(
            ("controlTask", startTime),
            ("task1", startTime + 11.minutes), // runs after desired execution time
            ("task2", startTime + 11.minutes)
          )
        )
      }

      // Advance to startTime + 12 minutes. This results in execution of the third task.
      advancer.advance(sec.getClock, startTime = startTime, desiredTime = startTime + 12.minutes)
      AssertionWaiter("Wait for task3").await {
        assert(
          log.getLog == Seq(
            ("controlTask", startTime),
            ("task1", startTime + 11.minutes),
            ("task2", startTime + 11.minutes),
            ("task3", startTime + 12.minutes)
          )
        )
      }
    }
  }

  test("Single clock driving multiple executors") {
    // Test plan: create a fake clock and use it to create two fake executors. Verify that advancing
    // time (using all overloads supported by `FakeTypedClock`) has the expected effect on both
    // executors.
    for (advancer: Advancer <- Advancer.ADVANCERS) {
      val fakeClock = new FakeTypedClock
      val sec1 = FakeSequentialExecutionContext.create(
        s"${getSafeName}1-${advancer.getClass}",
        fakeClockOpt = Some(fakeClock)
      )
      val sec2 = FakeSequentialExecutionContext.create(
        s"${getSafeName}2-${advancer.getClass}",
        fakeClockOpt = Some(fakeClock)
      )
      val log1 = new ExecutionLog(sec1)
      val log2 = new ExecutionLog(sec2)

      // Schedule tasks on both executors.
      val startTime: TickerTime = fakeClock.tickerTime()
      sec1.schedule("task1", 1.hour, () => {
        log1.logCallback("task1")
      })
      sec2.schedule("task2", 2.hours, () => {
        log2.logCallback("task2")
      })
      advancer.advance(fakeClock, startTime = startTime, desiredTime = startTime + 2.hours)
      AssertionWaiter("Wait for task1, task2").await {
        assert(log1.getLog == Seq(("task1", startTime + 2.hours)))
        assert(log2.getLog == Seq(("task2", startTime + 2.hours)))
      }
    }
  }

  test("All sec in the FakeSequentialExecutionContextPool shares the same fake clock") {
    // Test plan: Verify that all sequential execution contexts created by a
    // `FakeSequentialExecutionContextPool` share the same fake clock. This is done by ensuring
    // that advancing time has the expected effect on all executors.
    for (advancer: Advancer <- Advancer.ADVANCERS) {
      val fakeClock = new FakeTypedClock
      val fakeSecPool = FakeSequentialExecutionContextPool.create(
        name = "FakePool",
        numThreads = 2,
        fakeClock
      )

      // Create `numThreads + 1` executors.
      val sec1 = fakeSecPool.createExecutionContext(s"$getSafeName-sec1")
      val sec2 = fakeSecPool.createExecutionContext(s"$getSafeName-sec2")
      val sec3 = fakeSecPool.createExecutionContext(s"$getSafeName-sec3")

      val log1 = new ExecutionLog(sec1)
      val log2 = new ExecutionLog(sec2)
      val log3 = new ExecutionLog(sec3)

      // Schedule tasks on all executors.
      val startTime: TickerTime = fakeClock.tickerTime()
      sec1.schedule("task1", 1.hour, () => {
        log1.logCallback("task1")
      })
      sec2.schedule("task2", 90.minutes, () => {
        log2.logCallback("task2")
      })
      sec3.schedule("task3", 2.hours, () => {
        log3.logCallback("task3")
      })
      advancer.advance(fakeClock, startTime = startTime, desiredTime = startTime + 2.hours)
      AssertionWaiter("Wait for task1, task2, task3").await {
        assert(log1.getLog == Seq(("task1", startTime + 2.hours)))
        assert(log2.getLog == Seq(("task2", startTime + 2.hours)))
        assert(log3.getLog == Seq(("task3", startTime + 2.hours)))
      }

      // Verify: the clock of all executors is the same clock.
      for (sec: SequentialExecutionContext <- Seq(sec1, sec2, sec3)) {
        assert(sec.getClock eq fakeClock)
      }
    }
  }

  test("Custom exception handler") {
    // Test plan: Verify that custom exception handlers are invoked as expected.

    // Setup: Create a fake SEC with a custom exception handler that will complete a promise with
    // the uncaught exception.
    val fakeClock = new FakeTypedClock
    val exceptionPromise = Promise[Throwable]()
    val exceptionInterceptor = new UncaughtExceptionInterceptor {
      override def interceptUncaughtException(
          source: String,
          debugName: String,
          t: Throwable): Boolean = {
        exceptionPromise.success(t)
        true // Indicate that the exception was handled.
      }
    }
    val pool = FakeSequentialExecutionContextPool.create(
      getSafeName,
      numThreads = 1,
      fakeClock,
      exceptionInterceptor,
      enableContextPropagation = true
    )
    val sec = FakeSequentialExecutionContext.create(getSafeName, pool = pool)

    // Run a function on the SEC that throws an exception.
    val e = new IllegalStateException("Example exception")
    sec.run {
      throw e
    }

    // Verify that the custom exception handler was invoked.
    assert(Await.result(exceptionPromise.future, Duration.Inf) == e)
  }

  /**
   * Thread-safe log used to verify an expected sequence of callbacks executed on a given
   * [[SequentialExecutionContext]].
   *
   * @param sec the context on which all callbacks are expected to run.
   */
  @ThreadSafe
  private class ExecutionLog(sec: SequentialExecutionContext) {

    /** Lock protecting all internal state. */
    private val lock = new ReentrantLock()

    /** The log of callbacks that have been executed (name and time). */
    @GuardedBy("lock")
    private val log = mutable.ArrayBuffer.empty[(String, TickerTime)]

    /** Records a callback. */
    def logCallback(name: String): Unit = withLock(lock) {
      sec.assertCurrentContext()
      log += ((name, sec.getClock.tickerTime()))
    }

    /** Returns a copy of all callbacks logged so far. */
    def getLog: Seq[(String, TickerTime)] = withLock(lock) {
      Seq(log: _*)
    }
  }

  /**
   * Generic time advancer. Allows the same test logic to be used for all advance-time overloads
   * supported by [[FakeTypedClock]].
   */
  private trait Advancer {

    /**
     * Advances the given fake clock to the given desired time.
     *
     * @param fakeClock the clock to advance.
     * @param startTime the initial time for the fake clock, used to compute the `timeSinceStart`
     *                  parameter passed to [[FakeClock.advanceTo()]].
     * @param desiredTime the time to which the clock should be advanced.
     */
    def advance(fakeClock: FakeTypedClock, startTime: TickerTime, desiredTime: TickerTime): Unit
  }

  private object Advancer {
    val ADVANCERS: Seq[Advancer] = Seq(
      ADVANCE_BY,
      ADVANCE,
      ADVANCE_TO
    )

    private object ADVANCE_BY extends Advancer {
      override def advance(
          fakeClock: FakeTypedClock,
          startTime: TickerTime,
          desiredTime: TickerTime): Unit = {
        // `advanceBy` takes a time relative to the current time for the fake clock.
        val time: FiniteDuration = desiredTime - fakeClock.tickerTime()
        fakeClock.advanceBy(time)
      }
    }

    private object ADVANCE extends Advancer {
      override def advance(
          fakeClock: FakeTypedClock,
          startTime: TickerTime,
          desiredTime: TickerTime): Unit = {
        // `advance` takes a time relative to the current time for the fake clock.
        val fakeDatabrickClock: FakeClock = fakeClock.asFakeDatabricksClock
        val time: FiniteDuration = desiredTime - fakeClock.tickerTime()
        fakeDatabrickClock.advance(time)
      }
    }

    private object ADVANCE_TO extends Advancer {
      override def advance(
          fakeClock: FakeTypedClock,
          startTime: TickerTime,
          desiredTime: TickerTime): Unit = {
        // `advanceTo` take a time relative to the start time of the fake clock.
        val fakeDatabrickClock: FakeClock = fakeClock.asFakeDatabricksClock
        val timeSinceStart: FiniteDuration = desiredTime - startTime
        fakeDatabrickClock.advanceTo(timeSinceStart)
      }
    }
  }
}
