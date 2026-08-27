package com.databricks.caching.util

import scala.concurrent.duration._
import scala.util.matching.Regex

import org.scalatest.exceptions.TestFailedException

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.LogCapturer.CapturedLogEvent
import com.databricks.testing.DatabricksTest

class AssertionWaiterSuite extends DatabricksTest {

  /** Context on which assertions run in some test variations. */
  private val sec = SequentialExecutionContext.createWithDedicatedPool(
    name = "assertion_waiter_suite",
    alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
  )

  /**
   * Supplier that returns a "wrong" result (3) the first time is called, and continues to return
   * the wrong result until 10 millis have elapsed, at which point it returns the "right" result
   * (42). The supplier is guaranteed to return the wrong result at least once, which ensures that
   * the assertion waiter is actually retrying the assertion, even if tests are running slowly.
   */
  private class Supplier {
    private val startTime: TickerTime = RealtimeTypedClock.tickerTime()
    private var called = false // set after the wrong result has been returned at least once

    def apply(): Int = {
      val elapsed: FiniteDuration = RealtimeTypedClock.tickerTime() - startTime
      if (called && elapsed > 10.millis) {
        42
      } else {
        called = true
        3
      }
    }
  }

  test("await() surfaces useful error message on failure") {
    // Test plan: verify that the error returned by `await` contains the cause of the failure.
    val answer = 41
    assertThrow[TestFailedException]("41 did not equal 42") {
      AssertionWaiter("test", timeout = 1.millisecond).await {
        assert(answer == 42)
      }
    }
  }

  test("await() surfaces useful error message on failure (sec variation)") {
    // Test plan: verify that the error returned by `await` contains the cause of the failure.
    val answer = 41
    assertThrow[TestFailedException]("41 did not equal 42") {
      AssertionWaiter("test", timeout = 1.second, ecOpt = Some(sec)).await {
        sec.assertCurrentContext()
        assert(answer == 42)
      }
    }
  }

  test("await() succeeds if assertions are eventually satisfied") {
    // Test plan: arrange for an assertion to be satisfied after a short delay. Verify that a call
    // to `AssertionWaiter.await` succeeds.
    val supplier = new Supplier()
    AssertionWaiter("test").await {
      assert(supplier() == 42)
    }
  }

  test("await() succeeds if assertions are eventually satisfied (sec variation)") {
    // Test plan: arrange for an assertion to be satisfied after a short delay. Verify that a call
    // to `AssertionWaiter.await` succeeds.
    val supplier = new Supplier()
    AssertionWaiter("test", ecOpt = Some(sec)).await {
      sec.assertCurrentContext()
      assert(supplier() == 42)
    }
  }

  test("await() logs intermediate failures") {
    // Test plan: capture log events emitted by AssertionWaiter.await(). Verify that these log
    // events are associated with the expected logger name (which should include the log prefix
    // given to the waiter), and that the include useful information about the assertion that
    // failed.
    val logPrefix = "logtestprefix"
    LogCapturer.withCapturer(new Regex("Still waiting")) { capturer: LogCapturer =>
      val supplier = new Supplier()
      AssertionWaiter(logPrefix).await {
        assert(supplier() == 42)
      }
      val events: Seq[CapturedLogEvent] = capturer.getCapturedEvents
      assert(
        events.exists { capturedEvent =>
          val loggerName = capturedEvent.loggerName
          val message = capturedEvent.formattedMessage
          loggerName.contains(s"AssertionWaiter[$logPrefix]") &&
          message.contains("Still waiting: 3 did not equal 42")
        },
        s"Expected log event not found: $events"
      )
    }
  }

  test("await() fails when a predicate is specified") {
    // Test plan: verify that AssertionWaiter fails when supplied with something that looks like a
    // predicate rather than an assertion. Verify that a "fixed" version of the await works as
    // intended.
    val answer = 42
    assertThrow[TestFailedException]("Did you mean await { assert(predicate) }?") {
      AssertionWaiter("test").await {
        answer == 42
      }
    }
    AssertionWaiter("test").await {
      assert(answer == 42)
    }
  }
}
