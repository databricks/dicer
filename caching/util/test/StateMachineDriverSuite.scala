package com.databricks.caching.util

import com.databricks.caching.util.MetricUtils.ChangeTracker

import java.time.Instant
import scala.collection.{immutable, mutable}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import org.scalatest.exceptions.TestFailedException
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.StateMachineDriver.ConcurrencyDomain
import com.databricks.caching.util.StateMachineDriverSuite.{
  DomainExecutor,
  ExpectedNow,
  HybridDomainEntryType,
  TestDriver,
  TestStateMachine,
  TestTransition,
  ThrowingStateMachine
}
import com.databricks.testing.DatabricksTest
import org.scalatest.Suite

/** A base suite to parameterize tests on different [[ConcurrencyDomain]] configurations. */
private trait StateMachineDriverSuiteBase extends DatabricksTest with TestName {

  /** Creates a new [[TestDriver]], using `fakeClockOpt` to govern scheduling if present. */
  def createTestDriver(fakeClockOpt: Option[FakeTypedClock] = None): TestDriver

  /**
   * Creates a base [[StateMachineDriver]] with a state machine which always throws and an executor
   * which can be used to interact with the driver in its associated concurrency domain.
   */
  def createDriverWithThrowingStateMachine()
      : (StateMachineDriver[String, String, ThrowingStateMachine.type], DomainExecutor)

  test("start driver") {
    // Test plan: verify that an initial advance event is received by the state machine when the
    // driver is started.

    val driver: TestDriver = createTestDriver()

    // Configure transition for the "start" `onAdvance` call.
    driver.addTransition(TestTransition(ExpectedNow.Any, "advance", TickerTime.MAX, "action"))

    // Start the driver and verify the expected event and requested action.
    driver.start()
    assert(driver.dequeueActions() == Seq("action"))
  }

  test("advance with fake") {
    // Test plan: the state machine requests a sequence of next-advance-time values. Uses a fake
    // sequential executor so that we can directly verify the expected advance event times. Repeats
    // the sequence of inputs using various fake sequential execution context permutations to
    // simulate poorly- but correctly-behaved contexts (o/w much of the [[StateMachineDriver]] code
    // is never exercised).

    case class TestCase(earlyAdvanceCall: Boolean) {

      /**
       * Advances the fake clock to the given time. When `earlyAdvanceCall` is set, advances
       * to before the requested time, and through a test hook forces the pending advance call to
       * fire, which exercises the driver code that reschedules a pending advance call when it fires
       * too early.
       */
      def advanceTo(clock: FakeTypedClock, driver: TestDriver, time: TickerTime): Unit = {
        val advanceByDuration: FiniteDuration = time - clock.tickerTime()
        if (earlyAdvanceCall) {
          // Advance to before the desired time and poke the driver.
          clock.advanceBy(advanceByDuration - 100.millis)
          driver.runPendingAdvanceCall()

          // Advance to the desired time, which should trigger the driver's "adjusted" pending call.
          clock.advanceBy(100.millis)
        } else {
          clock.advanceBy(advanceByDuration)
        }
      }
    }
    val testCases = Seq(
      TestCase(earlyAdvanceCall = false),
      TestCase(earlyAdvanceCall = true)
    )
    for (testCase <- testCases) {
      val clock = new FakeTypedClock
      val driver: TestDriver = createTestDriver(Some(clock))
      val testEpochTickerTime: TickerTime = clock.tickerTime()
      val testEpochInstant: Instant = clock.instant()

      // Start the driver, machine requests advance at t+1.
      driver.addTransition(
        TestTransition(ExpectedNow.Any, "advance", testEpochTickerTime + 1.second, "action1")
      )
      driver.start()
      assert(driver.dequeueActions() == List("action1"))

      // Advance clock to t+1, machine requests advance never (TickerTime.MAX).
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 1.second, testEpochInstant.plusSeconds(1)),
          "advance",
          TickerTime.MAX,
          "action2",
          "action3"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 1.second)
      assert(driver.dequeueActions() == List("action2", "action3"))

      // Handle an event, machine requests advance at t+3.
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 1.second, testEpochInstant.plusSeconds(1)),
          "FooEvent",
          testEpochTickerTime + 3.seconds,
          "action4"
        )
      )
      driver.handleEvent("FooEvent")
      assert(driver.dequeueActions() == List("action4"))

      // Advance clock to t+2, handle an event, machine requests advance at t+3 (same as last
      // requested time).
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 2.seconds, testEpochInstant.plusSeconds(2)),
          "BarEvent",
          testEpochTickerTime + 3.seconds
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 2.seconds)
      driver.handleEvent("BarEvent")
      assert(driver.dequeueActions().isEmpty)

      // Advance clock to t+3, machine requests advance at t+10.
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 3.seconds, testEpochInstant.plusSeconds(3)),
          "advance",
          testEpochTickerTime + 10.seconds,
          "action5"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 3.seconds)
      assert(driver.dequeueActions() == List("action5"))

      // Advance clock to t+8, handle an event, machine requests advance at t+9 (earlier than last
      // requested time).
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 8.seconds, testEpochInstant.plusSeconds(8)),
          "FoobarEvent",
          testEpochTickerTime + 9.seconds,
          "action6"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 8.seconds)
      driver.handleEvent("FoobarEvent")
      assert(driver.dequeueActions() == List("action6"))

      // Advance clock to t+9, machine requests advance at t+20.
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 9.seconds, testEpochInstant.plusSeconds(9)),
          "advance",
          testEpochTickerTime + 20.seconds
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 9.seconds)
      assert(driver.dequeueActions().isEmpty)

      // Advance clock to t+15, handle an event, machine requests advance at t+30 (later than last
      // requested time).
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 15.seconds, testEpochInstant.plusSeconds(15)),
          "BarfooEvent",
          testEpochTickerTime + 30.seconds,
          "action7"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 15.seconds)
      driver.handleEvent("BarfooEvent")
      assert(driver.dequeueActions() == List("action7"))

      // Advance clock to t+30, machine requests advance at t+40.
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 30.seconds, testEpochInstant.plusSeconds(30)),
          "advance",
          testEpochTickerTime + 40.seconds,
          "action8",
          "action9"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 30.seconds)
      assert(driver.dequeueActions() == List("action8", "action9"))

      // Advance clock to t+39, handle an event, machine requests advance never.
      driver.addTransition(
        TestTransition(
          ExpectedNow.Exactly(testEpochTickerTime + 39.seconds, testEpochInstant.plusSeconds(39)),
          "TriggerEvent",
          TickerTime.MAX,
          "action10"
        )
      )
      testCase.advanceTo(clock, driver, testEpochTickerTime + 39.seconds)
      driver.handleEvent("TriggerEvent")
      assert(driver.dequeueActions() == List("action10"))

      // Advance clock to make sure the machine isn't called again.
      clock.advanceBy(1.hour)
      assert(driver.dequeueActions().isEmpty)
    }
  }

  test("State machine with exception will fire alert") {
    // Test plan: Create a state machine that throws in its callbacks. Expect the driver to fire an
    // alert, which we verify using the `PrefixLogger`'s `errorCount` metric.

    // Get the current count of the `PrefixLogger` error metric.
    val errorTracker: ChangeTracker[Int] = ChangeTracker(
      () =>
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR(AlertOwnerTeam.CachingTeam),
          prefix = ""
        )
    )

    val (driver, domainExecutor): (
        StateMachineDriver[String, String, ThrowingStateMachine.type],
        DomainExecutor) =
      createDriverWithThrowingStateMachine()

    domainExecutor.callInDomain {
      driver.start()
    }

    AssertionWaiter("error observed").await {
      assert(errorTracker.totalChange() == 1)
    }

    domainExecutor.callInDomain {
      driver.handleEvent("some-event")
    }

    AssertionWaiter("error observed").await {
      assert(errorTracker.totalChange() == 2)
    }
  }

  test("State machine runs actions in order") {
    // Verify that actions returned in `StateMachineOutput` are run in order. Do this by returning
    // multiple actions from `onAdvance` and `onEvent`, and verify that they get executed in the
    // same order.
    val clock = new FakeTypedClock
    val driver: TestDriver = createTestDriver(Some(clock))

    // Configure transition for the "start" `onAdvance` call.
    driver.addTransition(
      TestTransition(ExpectedNow.Any, "advance", TickerTime.MAX, "action1", "action2")
    )

    // Start the driver and verify the expected event and requested action.
    driver.start()
    assert(driver.dequeueActions() == Seq("action1", "action2"))

    val startTime: TickerTime = clock.tickerTime()
    val startInstant: Instant = clock.instant()
    driver.addTransition(
      TestTransition(
        ExpectedNow.Any,
        "triggerEvent",
        startTime + 1.second,
        "action3",
        "action4",
        "action5"
      )
    )
    driver.handleEvent("triggerEvent")
    assert(driver.dequeueActions() == Seq("action3", "action4", "action5"))

    driver.addTransition(
      TestTransition(
        ExpectedNow.Exactly(startTime + 1.second, startInstant.plusSeconds(1)),
        "advance",
        TickerTime.MAX,
        "action6",
        "action7"
      )
    )
    clock.advanceBy(1.second)
    assert(driver.dequeueActions() == Seq("action6", "action7"))
  }
}

/** Variant of [[StateMachineDriverSuiteBase]] using [[SequentialExecutionContext]]. */
private final class SequentialDomainStateMachineDriverSuite(secIgnoresCancellation: Boolean)
    extends StateMachineDriverSuiteBase {

  private val pool = SequentialExecutionContextPool.create("machine-test", numThreads = 2)

  override def createTestDriver(fakeClockOpt: Option[FakeTypedClock]): TestDriver = {
    var sec: SequentialExecutionContext = fakeClockOpt match {
      case Some(fakeClock: FakeTypedClock) =>
        FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock), pool)
      case None =>
        pool.createExecutionContext(getSafeName)
    }

    if (secIgnoresCancellation) {
      import DelegatingSequentialExecutionContext.Decorators
      sec = sec.ignoringCancellation()
    }

    val domain: ConcurrencyDomain =
      StateMachineDriver.forTestStatic.sequentialDomain(sec)

    new TestDriver(
      domain,
      asDomainExecutor(sec),
      (performAction: (String) => Unit) =>
        new StateMachineDriver[String, String, TestStateMachine](
          sec,
          new TestStateMachine(Some(domain)),
          performAction
        )
    ) {

      // Because all methods interacting with the driver's state are serialized by scheduling on the
      // SEC, there's no need to do anything extra here. The next task which could observe the
      // outcome of any previously scheduled but ready task is already guaranteed to run after
      // it.
      override def awaitReadyScheduledTasksComplete(): Unit = {}
    }
  }

  override def createDriverWithThrowingStateMachine()
      : (StateMachineDriver[String, String, ThrowingStateMachine.type], DomainExecutor) = {
    val sec: SequentialExecutionContext = pool.createExecutionContext("throwing-machine-test")
    val driver = new StateMachineDriver[String, String, ThrowingStateMachine.type](
      sec,
      ThrowingStateMachine,
      performAction = (_: String) => ()
    )

    (driver, asDomainExecutor(sec))
  }

  /** Returns a new [[DomainExecutor]] wrapping `sec` */
  private def asDomainExecutor(sec: SequentialExecutionContext): DomainExecutor = {
    new DomainExecutor {
      override def callInDomain[T](thunk: => T): Future[T] = sec.call { thunk }
    }
  }
}

/** Variant of [[StateMachineDriverSuiteBase]] using [[HybridConcurrencyDomain]]. */
private final class HybridDomainStateMachineDriverSuite(
    entryType: HybridDomainEntryType,
    domainIgnoresCancellation: Boolean)
    extends StateMachineDriverSuiteBase {

  private val pool = SequentialExecutionContextPool.create(
    "machine-test",
    numThreads = 2,
    enableContextPropagation = true
  )

  override def createTestDriver(fakeClockOpt: Option[FakeTypedClock]): TestDriver = {
    val internalSec: SequentialExecutionContext = {
      val sec: SequentialExecutionContext = fakeClockOpt match {
        case Some(fakeClock: FakeTypedClock) =>
          FakeSequentialExecutionContext.create(
            s"$getSafeName-hybrid-internal-sec",
            Some(fakeClock),
            pool
          )
        case None =>
          pool.createExecutionContext(s"$getSafeName-hybrid-internal-sec")
      }

      if (domainIgnoresCancellation) {
        import DelegatingSequentialExecutionContext.Decorators
        sec.ignoringCancellation()
      } else {
        sec
      }
    }

    val hybrid = HybridConcurrencyDomain.forTest.create(
      s"$getSafeName-hybrid-domain",
      internalSec,
      enableContextPropagation = true
    )

    val domain: ConcurrencyDomain = StateMachineDriver.forTestStatic.hybridDomain(hybrid)
    new TestDriver(
      domain,
      asDomainExecutor(hybrid),
      (performAction: (String) => Unit) =>
        StateMachineDriver.inHybridDomain(hybrid, new TestStateMachine(Some(domain)), performAction)
    ) {

      override def awaitReadyScheduledTasksComplete(): Unit = {
        // All async tasks serialize according to program order in a hybrid domain (even though sync
        // tasks can jump the queue), so we ensure that all previously scheduled async tasks have
        // completed by waiting for the completion of a newly scheduled async task.
        Await.result(Pipeline { () }(hybrid).toFuture, Duration.Inf)
      }
    }
  }

  override def createDriverWithThrowingStateMachine()
      : (StateMachineDriver[String, String, ThrowingStateMachine.type], DomainExecutor) = {
    val hybrid =
      HybridConcurrencyDomain.create("throwing-machine-test", enableContextPropagation = true)
    val driver: StateMachineDriver[String, String, ThrowingStateMachine.type] =
      StateMachineDriver.inHybridDomain(
        hybrid,
        ThrowingStateMachine,
        performAction = (_: String) => ()
      )

    (driver, asDomainExecutor(hybrid))
  }

  /** Returns a new [[DomainExecutor]] wrapping `hybrid`. */
  private def asDomainExecutor(hybrid: HybridConcurrencyDomain): DomainExecutor = {
    new DomainExecutor {
      override def callInDomain[T](thunk: => T): Future[T] = entryType match {
        case HybridDomainEntryType.Sync => Future.successful(hybrid.executeSync { thunk })
        case HybridDomainEntryType.Async => Pipeline { thunk }(hybrid).toFuture
      }
    }
  }
}

/**
 * Nests all variants of [[StateMachineDriverSuiteBase]] and directly defines tests independent of
 * the surrounding concurrency domain.
 */
class StateMachineDriverSuite extends DatabricksTest {
  override def nestedSuites: immutable.IndexedSeq[Suite] = {
    val suites: mutable.ArrayBuffer[Suite] = mutable.ArrayBuffer.empty
    for (ignoreCancellation: Boolean <- Seq(true, false)) {
      suites += new SequentialDomainStateMachineDriverSuite(
        secIgnoresCancellation = ignoreCancellation
      )

      for (entryType: HybridDomainEntryType <- Seq(
          HybridDomainEntryType.Sync,
          HybridDomainEntryType.Async
        )) {
        suites += new HybridDomainStateMachineDriverSuite(
          domainIgnoresCancellation = ignoreCancellation,
          entryType = entryType
        )
      }
    }
    suites.toIndexedSeq
  }

  test("Advance state machine using TestStateMachineDriver") {
    // Test plan: Test state machine using TestStateMachineDriver. Expect it to handle events and
    // advance time in the underlying state machine with appropriate actions.

    val fakeTypedClock = new FakeTypedClock
    val stateMachine = new TestStateMachine(None)
    val driver = new TestStateMachineDriver(stateMachine)

    // State machine requests action1 at t+1 on event1.
    stateMachine.addTransition(
      TestTransition(ExpectedNow.Any, "event1", fakeTypedClock.tickerTime() + 1.second, "action1")
    )

    assert(
      driver
        .onEvent(fakeTypedClock.tickerTime(), fakeTypedClock.instant(), "event1") ==
      StateMachineOutput(
        fakeTypedClock.tickerTime() + 1.second,
        Seq("action1")
      )
    )

    // Advance clock to t+1, machine requests advance never (TickerTime.MAX) and action2, action3.
    stateMachine.addTransition(
      TestTransition(
        ExpectedNow
          .Exactly(fakeTypedClock.tickerTime() + 1.second, fakeTypedClock.instant().plusSeconds(1)),
        "advance",
        TickerTime.MAX,
        "action2",
        "action3"
      )
    )

    // Advance the clock by 1 second.
    fakeTypedClock.advanceBy(1.second)

    assert(
      driver.onAdvance(fakeTypedClock.tickerTime(), fakeTypedClock.instant()) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq("action2", "action3")
      )
    )
  }
}

private object StateMachineDriverSuite {

  /**
   * State machine for the driver under test that uses strings to represent events and driver
   * actions. Its behavior is programmed using [[addTransition]] which tells the machine what
   * sequence of inputs to expect and what outputs to return in response.
   *
   * While a production state machine would not typically have a `sec`, we use one for our test
   * state machine so that we can:
   *
   *  - Assert that the driver is making calls on the expected `sec`.
   *  - Safely inspect and modify the contents of the state machine.
   *
   * Not thread-safe, MUST use [[StateMachineDriver]] for thread-safety.
   *
   * @param domainOpt Optional [[ConcurrencyDomain]] expected to be used by the driver. If
   *                  specified, it is used to validate concurrency domain and to add transitions in
   *                  thread-safe manner. It is not specified for some test cases, where we access
   *                  the state machine directly on the test's main thread.
   *
   */
  class TestStateMachine(domainOpt: Option[ConcurrencyDomain])
      extends StateMachine[String, String] {
    private val transitions = mutable.Queue[TestTransition]()

    override protected def onAdvance(
        tickerTime: TickerTime,
        instant: Instant): StateMachineOutput[String] = {
      assertInDomain()
      onEvent(tickerTime, instant, "advance")
    }

    override protected def onEvent(
        tickerTime: TickerTime,
        instant: Instant,
        event: String): StateMachineOutput[String] = {
      assertInDomain()
      // Verify expected inputs. We surface resulting errors as actions, a hack to make it easier to
      // observe errors from the test thread (rather than digging around for uncaught exception
      // entries in the test log).
      try {
        assert(transitions.nonEmpty, s"unexpected $event")

        // Dequeue the next transition.
        val transition: TestTransition = transitions.dequeue()

        assert(transition.expectedEvent == event)
        transition.expectedNow match {
          case ExpectedNow.Any =>
          case ExpectedNow.Exactly(expectedTickerTime: TickerTime, expectedInstant: Instant) =>
            assert(tickerTime == expectedTickerTime, event)
            assert(instant == expectedInstant, event)
          case ExpectedNow.AtLeast(minTickerTime: TickerTime, minInstant: Instant) =>
            assert(tickerTime >= minTickerTime, event)
            assert(!instant.isBefore(minInstant), event)
        }

        // Return programmed output.
        StateMachineOutput(transition.nextTime, transition.actions)
      } catch {
        case ex: TestFailedException => StateMachineOutput(TickerTime.MAX, Seq(ex.toString))
      }
    }

    /** Programs a transition in the state machine. */
    def addTransition(transition: TestTransition): Unit = {
      assertInDomain()
      transitions.enqueue(transition)
    }

    /** Asserts execution in the expected concurrency domain if `domainOpt` is defined. */
    private def assertInDomain(): Unit = {
      for (domain <- domainOpt) {
        domain.assertInDomain()
      }
    }
  }

  sealed trait ExpectedNow
  object ExpectedNow {
    case object Any extends ExpectedNow
    case class Exactly(expectedTickerTime: TickerTime, expectedInstant: Instant) extends ExpectedNow
    case class AtLeast(minTickerTime: TickerTime, minInstant: Instant) extends ExpectedNow
  }

  /**
   * A transition telling the [[TestStateMachine]] how to handle an `onEvent` or `onAdvance` call
   * (what to expect, what to emit).
   *
   * @param expectedNow the expected `now` argument.
   * @param expectedEvent the expected `event` argument (or "advance" for `onAdvance`).
   * @param nextTime the requested `onAdvance` time returned by the state machine.
   * @param actions actions requested of the driver.
   */
  case class TestTransition(
      expectedNow: ExpectedNow,
      expectedEvent: String,
      nextTime: TickerTime,
      actions: String*)

  /**
   * A type alias for a factory function which accepts an implementation of `performAction` and
   * produces a [[StateMachineDriver]].
   */
  private type BaseDriverFactory =
    ((String) => Unit) => StateMachineDriver[String, String, TestStateMachine]

  /**
   * An executor for executing arbitrary code within an associated concurrency domain. This is
   * needed for tests to abstract over the different entry points into each type of concurrency
   * domain.
   */
  trait DomainExecutor {

    /**
     * Executes `thunk` within the concurrency domain, returning a handle for the result.
     */
    def callInDomain[T](thunk: => T): Future[T]
  }

  /**
   * Test driver implementation that logs requested actions.
   *
   * This class facilitates testing the state machine driver protocol in both types of
   * concurrency domains by requiring implementations to supply domain-specific execution methods.
   * All public methods are executed in the given `domain` by passing the work to these
   * implementations.
   */
  abstract class TestDriver(
      domain: ConcurrencyDomain,
      executor: DomainExecutor,
      baseDriverFactory: BaseDriverFactory) {
    private val actionLog = mutable.Queue[String]()

    private val baseDriver: StateMachineDriver[String, String, TestStateMachine] =
      baseDriverFactory(performAction)

    /** Calls [[StateMachineDriver]] in the concurrency domain. */
    final def start(): Unit = executor.callInDomain {
      baseDriver.start()
    }

    /** Handles `event`. */
    final def handleEvent(event: String): Unit = executor.callInDomain {
      baseDriver.handleEvent(event)
    }

    /** Runs the pending advance call, if one exists, blocking until its completion. */
    final def runPendingAdvanceCall(): Unit =
      Await.result(executor.callInDomain {
        baseDriver.forTest.runPendingAdvanceCall()
      }, Duration.Inf)

    /** Dequeues all actions enqueued via `performAction`. */
    final def dequeueActions(): List[String] = {
      // If tasks aren't fully serialized in the domain, i.e., this is a hybrid domain, there may
      // be actions that will be enqueued by scheduled tasks that are eligible to execute but
      // haven't yet completed. To avoid this race condition, we allow these tasks to drain before
      // we dequeue the actions. For a fully sequential domain, the implementation of this await can
      // just be a no-op, as future actions will queue up behind the pending tasks anyway.
      awaitReadyScheduledTasksComplete()

      val actions: Future[List[String]] =
        executor.callInDomain {
          domain.assertInDomain()
          actionLog.dequeueAll { _ =>
            // Don't filter out any actions.
            true
          }.toList
        }
      Await.result(actions, Duration.Inf)
    }

    /** Adds a transition to the internal test state machine. */
    final def addTransition(transition: TestTransition): Unit = executor.callInDomain {
      baseDriver.forTest.getStateMachine.addTransition(transition)
    }

    /**
     * Blocks until all scheduled tasks which are ready to run at the current time have
     * been executed.
     */
    protected def awaitReadyScheduledTasksComplete(): Unit

    /**
     * "Performs" the given action by enqueuing it to the log.
     *
     * Implementations must supply this method to their internal `StateMachineDriver`.
     */
    private def performAction(action: String): Unit = {
      domain.assertInDomain()
      actionLog.enqueue(action)
    }
  }

  /**
   * How the base [[StateMachineDriver]] inside a [[TestDriver]] is invoked w.r.t. to the
   * [[TestDriver]]'s calling thread.
   */
  sealed trait HybridDomainEntryType
  object HybridDomainEntryType {

    /** Invoked directly in the calling thread. */
    case object Sync extends HybridDomainEntryType

    /** Invoked asynchronously by scheduling onto the hybrid domain's internal executor. */
    case object Async extends HybridDomainEntryType
  }

  /**
   * A [[StateMachine]] implementation which simply throws for every call to [[onAdvance()]] and
   * [[onEvent()]].
   */
  object ThrowingStateMachine extends StateMachine[String, String] {

    override protected def onAdvance(
        tickerTime: TickerTime,
        instant: Instant): StateMachineOutput[String] = {
      throw new RuntimeException("onAdvance failed")
    }

    override protected def onEvent(
        tickerTime: TickerTime,
        instant: Instant,
        event: String): StateMachineOutput[String] = {
      throw new RuntimeException("onEvent failed")
    }
  }

}
