package com.databricks.caching.util

import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.DelegatingSequentialExecutionContext.Decorators

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.exceptions.TestFailedException
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.StateMachineDriverSuite.{
  ExpectedNow,
  TestDriver,
  TestStateMachine,
  TestTransition,
  ThrowingStateMachine
}
import com.databricks.testing.DatabricksTest

/** Tests [[StateMachineDriver]] behavior in a [[SequentialExecutionContext]]. */
class StateMachineDriverSuite extends DatabricksTest with TestName {

  private val pool = SequentialExecutionContextPool.create(
    poolName = "machine-test",
    numThreads = 2,
    alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
  )

  /** Creates a new [[TestDriver]], using `fakeClockOpt` to govern scheduling if present. */
  private def createTestDriver(
      fakeClockOpt: Option[FakeTypedClock] = None,
      secIgnoresCancellation: Boolean = false): TestDriver = {
    val sec: SequentialExecutionContext =
      createSec(fakeClockOpt, secIgnoresCancellation)
    new TestDriver(sec)
  }

  /** Creates an SEC, optionally with a fake clock and intentionally ineffective cancellation. */
  private def createSec(
      fakeClockOpt: Option[FakeTypedClock],
      secIgnoresCancellation: Boolean): SequentialExecutionContext = {
    val sec: SequentialExecutionContext = fakeClockOpt match {
      case Some(fakeClock: FakeTypedClock) =>
        FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock), pool)
      case None =>
        pool.createExecutionContext(getSafeName)
    }
    if (secIgnoresCancellation) {
      sec.ignoringCancellation()
    } else {
      sec
    }
  }

  /** Runs `func` on `sec` and returns its result. */
  private def callOnSec[T](sec: SequentialExecutionContext)(func: => T): T =
    Await.result(sec.call(func), Duration.Inf)

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

    case class TestCase(earlyAdvanceCall: Boolean, secIgnoresCancellation: Boolean) {

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
    val testCases: Seq[TestCase] =
      for {
        earlyAdvanceCall: Boolean <- Seq(false, true)
        secIgnoresCancellation: Boolean <- Seq(false, true)
      } yield TestCase(earlyAdvanceCall, secIgnoresCancellation)
    for (testCase <- testCases) {
      withClue(s"testCase=$testCase") {
        val clock = new FakeTypedClock
        val driver: TestDriver =
          createTestDriver(Some(clock), testCase.secIgnoresCancellation)
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

    val sec: SequentialExecutionContext =
      createSec(fakeClockOpt = None, secIgnoresCancellation = false)
    val driver = new StateMachineDriver[String, String, ThrowingStateMachine.type](
      sec = sec,
      stateMachine = ThrowingStateMachine,
      performAction = (_: String) => (),
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )

    callOnSec(sec) {
      driver.start()
    }

    AssertionWaiter("error observed").await {
      assert(errorTracker.totalChange() == 1)
    }

    callOnSec(sec) {
      driver.handleEvent("some-event")
    }

    AssertionWaiter("error observed").await {
      assert(errorTracker.totalChange() == 2)
    }
  }

  test("State machine runs actions in order") {
    // Test plan: verify that actions returned in `StateMachineOutput` run in order by returning
    // multiple actions from `onAdvance` and `onEvent`.
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

  test("Advance state machine using TestStateMachineDriver") {
    // Test plan: Test state machine using TestStateMachineDriver. Expect it to handle events and
    // advance time in the underlying state machine with appropriate actions.

    val sec: SequentialExecutionContext =
      createSec(fakeClockOpt = None, secIgnoresCancellation = false)
    callOnSec(sec) {
      val fakeTypedClock = new FakeTypedClock
      val stateMachine = new TestStateMachine(sec)
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
          ExpectedNow.Exactly(
            fakeTypedClock.tickerTime() + 1.second,
            fakeTypedClock.instant().plusSeconds(1)
          ),
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
   * @param sec the [[SequentialExecutionContext]] expected to be used by the driver.
   */
  class TestStateMachine(sec: SequentialExecutionContext) extends StateMachine[String, String] {
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

    /** Asserts execution on the expected context. */
    private def assertInDomain(): Unit = {
      sec.assertCurrentContext()
    }
  }

  sealed trait ExpectedNow
  object ExpectedNow {
    case object Any extends ExpectedNow
    case class Exactly(expectedTickerTime: TickerTime, expectedInstant: Instant) extends ExpectedNow
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

  /** Test driver that invokes and observes a [[StateMachineDriver]] on `sec`. */
  class TestDriver(sec: SequentialExecutionContext) {
    private val actionLog = mutable.Queue[String]()

    private val stateMachine = new TestStateMachine(sec)
    private val baseDriver: StateMachineDriver[String, String, TestStateMachine] =
      new StateMachineDriver(
        sec = sec,
        stateMachine = stateMachine,
        performAction = performAction,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )

    /** Starts the driver. */
    def start(): Unit = callOnSec {
      baseDriver.start()
    }

    /** Handles `event`. */
    def handleEvent(event: String): Unit = callOnSec {
      baseDriver.handleEvent(event)
    }

    /** Runs the pending advance call immediately. */
    def runPendingAdvanceCall(): Unit = callOnSec {
      baseDriver.forTest.runPendingAdvanceCall()
    }

    /** Dequeues all actions enqueued via `performAction`. */
    def dequeueActions(): List[String] = callOnSec {
      actionLog.dequeueAll { _ =>
        // Don't filter out any actions.
        true
      }.toList
    }

    /** Adds a transition to the internal test state machine. */
    def addTransition(transition: TestTransition): Unit = callOnSec {
      stateMachine.addTransition(transition)
    }

    /** Enqueues an action emitted by the state machine. */
    private def performAction(action: String): Unit = {
      sec.assertCurrentContext()
      actionLog.enqueue(action)
    }

    /** Runs `func` on the driver's SEC. */
    private def callOnSec[T](func: => T): T =
      Await.result(sec.call(func), Duration.Inf)
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
