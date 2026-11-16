package com.databricks.caching.util

import com.databricks.caching.util.LogCapturer.CapturedLogEvent

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration._
import scala.util.matching.Regex
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.context.Ctx
import com.databricks.testing.DatabricksTest

class PrefixLoggerSuite extends DatabricksTest with TestName {
  override def beforeAll(): Unit = {
    Configurator.setAllLevels("com.databricks", Level.ALL)
  }

  test("Simple log statements") {
    // Test plan: Test all the logging functions that just take a String by logging strings at
    // different levels. It then uses the LogCapturer to ensure that the strings are present.
    val logger = PrefixLogger.create(getClass, "Simple")

    // For each logging function at different levels, log a string and then get all the events
    // from the LogCapturer and examine them.
    val loggingFunctions: Seq[(=> Any, FiniteDuration) => Unit] =
      Seq(logger.info, logger.warn, logger.debug, logger.error)
    for (loggingFunction <- loggingFunctions) {
      loggingFunction("TestPrefixMessage 1", Duration.Zero) // Won't be captured.
      LogCapturer.withCapturer(new Regex("TestPrefixMessage")) { capturer: LogCapturer =>
        loggingFunction("Random message not to be captured", Duration.Zero)
        loggingFunction("TestPrefixMessage 2", Duration.Zero)
        loggingFunction("TestPrefixMessage 3", Duration.Zero)

        val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents
        assert(events.size == 2)
        assert(
          events.head.formattedMessage.matches(".*PrefixLoggerSuite.scala.*TestPrefixMessage 2")
        )
        assert(events(1).formattedMessage.contains("TestPrefixMessage 3"))
      }
    }
  }

  test("Prefix included in log message") {
    // Test plan: Verify that the full prefix is included in the log message (in tests). Create a
    // PrefixLogger with a prefix which includes special characters. Then redirect System.out so
    // that we can examine the fully formatted output, log a message and ensure that the resulting
    // log includes the expected components.
    val logger = PrefixLogger.create(getClass, "[Multi, word. prefix]")
    val originalOut: PrintStream = System.out
    try {
      val outStream = new ByteArrayOutputStream()
      System.setOut(new PrintStream(outStream))
      logger.info("Test message")
      val output: String = outStream.toString
      val expectedRegex: Regex =
        raw".*\[Multi, word\. prefix].*PrefixLoggerSuite\.scala:\d+.*Test message".r
      assert(expectedRegex.findFirstIn(output).isDefined, s"Output: $output")
    } finally {
      System.setOut(originalOut)
    }
  }

  test("Log every time statement") {
    // Test plan: Log using `every` with a fake clock, advance time and make sure that the
    // logging statements are printed at the expected frequency.
    import scala.concurrent.duration._
    val fakeClock = new FakeTypedClock
    val logger = PrefixLogger.create(getClass, "Every", clock = fakeClock)
    LogCapturer.withCapturer(new Regex("Time log")) { capturer: LogCapturer =>
      for (i <- 0 until 10) {
        logger.info(s"Time log $i", 30.seconds)
        fakeClock.advanceBy(10.seconds)
      }
      val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents
      assert(events.size == 4)
      assert(events.head.formattedMessage.contains("Time log 0"))
      assert(events(1).formattedMessage.contains("Time log 3"))
      assert(events(2).formattedMessage.contains("Time log 6"))
      assert(events(3).formattedMessage.contains("Time log 9"))
    }
  }

  test("Log every included in output") {
    // Test plan: Verify that log messages include an indication of the `every` parameter.
    val logger = PrefixLogger.create(getClass, "Every output")
    LogCapturer.withCapturer(new Regex("Every")) { capturer: LogCapturer =>
      logger.info(s"Every 1", 30.seconds)
      logger.info(s"Every 2", 842.millis)
      logger.info(s"Every 3", 1820.millis)
      logger.info(s"Every 4", 1.minute)
      logger.info(s"Every 5", 1.hour)
      logger.info(s"Every 6", 1.micros)

      val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents
      val expectedOutputs = Seq(
        "every=30s",
        "every=842ms",
        "every=1s", // We always round down.
        "every=1m",
        "every=1h+",
        "every=0ms" // Minimum resolution is milliseconds.
      )
      for (tuple <- events.zip(expectedOutputs)) {
        val (event, expected): (CapturedLogEvent, String) = tuple
        assert(event.formattedMessage.contains(expected))
      }

      logger.info(s"Every 7")
      val lastLog: CapturedLogEvent = capturer.getCapturedEvents.last
      assert(!lastLog.formattedMessage.contains("every"))
    }
  }

  test("Log every includes skipped count") {
    // Test plan: Verify that a throttled message reports how many calls on the same call site were
    // suppressed since the previous emitted message, and that the count resets after each emitted
    // message. Do this with a fake clock: log a burst of calls in one `every` window, advance past
    // the window and log again, then advance again and log once more with no calls in between.
    val fakeClock = new FakeTypedClock
    val logger = PrefixLogger.create(getClass, "Skipped", clock = fakeClock)
    LogCapturer.withCapturer(new Regex("Skip log")) { capturer: LogCapturer =>
      def logOnce(i: Int): Unit = logger.info(s"Skip log $i", 30.seconds)

      // The first call is emitted, the next 4 are throttled.
      for (i <- 0 until 5) {
        logOnce(i)
      }
      fakeClock.advanceBy(30.seconds)
      logOnce(5)
      fakeClock.advanceBy(30.seconds)
      logOnce(6)

      val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents
      assert(events.size == 3)
      assert(events(0).formattedMessage.contains("[every=30s] Skip log 0"))
      assert(events(1).formattedMessage.contains("[every=30s, skipped=4] Skip log 5"))
      assert(events(2).formattedMessage.contains("[every=30s] Skip log 6"))
    }
  }

  test("Log every skipped count loses no calls under concurrency") {
    // Test plan: Verify that every call on a throttled call site is either emitted or reported in
    // the skipped count of a later emitted message, even when calls race with emitted messages.
    // Do this by calling the same call site from several threads while the main thread keeps
    // advancing a fake clock past `every`, then advancing once more and making a final call that
    // reports any remaining skips. The emitted messages plus their skipped counts must add up to
    // the total number of calls.
    val fakeClock = new FakeTypedClock
    val logger = PrefixLogger.create(getClass, "Concurrent skipped", clock = fakeClock)
    val skippedRegex: Regex = raw"skipped=(\d+)".r
    val numThreads = 4
    val callsPerThread = 20000
    def logOnce(): Unit = logger.info("Concurrent skip log", 30.seconds)

    LogCapturer.withCapturer(new Regex("Concurrent skip log")) { capturer: LogCapturer =>
      val threads: Seq[Thread] = (0 until numThreads).map { _ =>
        new Thread(() => for (_ <- 0 until callsPerThread) logOnce())
      }
      threads.foreach { thread: Thread =>
        thread.start()
      }
      // Advance the clock repeatedly so emitted messages interleave with throttled calls. The
      // sleep only paces the advances: without it the clock could advance about as often as calls
      // are made, so many calls would be emitted and few throttled, leaving little to count.
      // The test does not depend on timing to pass: the assertion below must hold for every
      // interleaving of threads and clock advances. A slower or faster machine only changes how
      // many emitted messages race with throttled calls, not whether the counts must add up.
      while (threads.exists { thread: Thread =>
          thread.isAlive
        }) {
        fakeClock.advanceBy(30.seconds)
        Thread.sleep(1)
      }
      threads.foreach { thread: Thread =>
        thread.join()
      }
      fakeClock.advanceBy(30.seconds)
      logOnce()

      val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents
      val totalSkipped: Long = events.map { event: CapturedLogEvent =>
        skippedRegex.findFirstMatchIn(event.formattedMessage).map(_.group(1).toLong).getOrElse(0L)
      }.sum
      assertResult(numThreads * callsPerThread + 1)(events.size + totalSkipped)
    }
  }

  test("trace statements") {
    // Test plan: Just log trace statements to make sure that the code does not crash. We can't
    // seem to capture trace statements since they don't even show up in the log file.
    val logger = PrefixLogger.create(getClass, "With Tracing")
    logger.trace("Trace statement 1")
  }

  test("Check thunk evaluation") {
    // Test plan: Log using `every` and make sure that the message thunk is evaluated only when
    // the message is actually printed and not every time.
    import scala.concurrent.duration._
    val fakeClock = new FakeTypedClock
    val logger = PrefixLogger.create(getClass, "Thunk check", clock = fakeClock)
    var counter: Int = 0
    for (i <- 0 until 3) {
      logger.info(s"Thunk log ${ counter += 1; i }  ", 30.seconds)
      fakeClock.advanceBy(10.seconds)
    }
    assert(counter == 1, s"Thunk called too many times or never")
  }

  test("assert throws and increments a metric") {
    // Test plan: assert throws AssertionError on invalid assert and increments error metric.
    val logger = PrefixLogger.create(getClass, getSafeName)
    val severity = Severity.CRITICAL
    val errorCode = CachingErrorCode.KUBERNETES_INIT

    val initialValue: Int =
      MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)

    assertThrow[AssertionError]("Critical error") {
      logger.assert(1 == 0, CachingErrorCode.KUBERNETES_INIT, "Critical error")
    }
    assert(
      initialValue + 1 == MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)
    )

    // Should not increment metric.
    logger.assert(1 == 1, CachingErrorCode.KUBERNETES_INIT, "Critical error")
    assert(
      initialValue + 1 == MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)
    )
  }

  test("expect increments error metric") {
    // Test plan: expect increments error metric.
    val severity = Severity.DEGRADED
    val errorCode = CachingErrorCode.SLICELET_NAMESPACE_MISMATCH

    val logger = PrefixLogger.create(getClass, getSafeName)
    val degradedErrorInitialValue: Int =
      MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)

    // log with stack trace.
    logger.expect(
      1 == 2,
      CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
      s"Mismatch is slicelet namespace " +
      s"${new Exception("Test exception").getStackTrace.mkString("\n")}"
    )

    // Increments degraded metric.
    assert(
      degradedErrorInitialValue + 1.0 ==
      MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)
    )
  }

  test("alert increments error metric") {
    // Test plan: verify `PrefixLogger.alert` increments the appropriate error metric.
    val logger = PrefixLogger.create(getClass, getSafeName)
    val errorCode = CachingErrorCode.SLICELET_NAMESPACE_MISMATCH

    // Get the current metric value for the given severity and the hardcoded `errorCode`.
    def getMetricValue(severity: Severity): Int = {
      MetricUtils.getPrefixLoggerErrorCount(severity, errorCode, getSafeName)
    }

    val degradedErrorInitialValue: Double = getMetricValue(Severity.DEGRADED)
    logger.alert(Severity.DEGRADED, errorCode, "Test alert")
    // Degraded metric was incremented.
    assert(degradedErrorInitialValue + 1 == getMetricValue(Severity.DEGRADED))

    val criticalErrorInitialValue: Double = getMetricValue(Severity.CRITICAL)
    logger.alert(Severity.CRITICAL, errorCode, "Test alert")
    // Critical metric was incremented.
    assert(criticalErrorInitialValue + 1 == getMetricValue(Severity.CRITICAL))
  }

  test("UNCAUGHT_STATE_MACHINE_ERROR and UNCAUGHT_SEC_POOL_ERROR route alerts to the owner team") {
    // Test plan: Verify that the parameterized error codes correctly reflect the owner team.
    // Do this by constructing error codes with both a named team (CachingTeam) and a custom
    // team, and verifying that alertOwnerTeam returns the expected AlertOwnerTeam in both cases.
    assertResult(AlertOwnerTeam.CachingTeam)(
      CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR(AlertOwnerTeam.CachingTeam).alertOwnerTeam
    )
    assertResult(AlertOwnerTeam.CachingTeam)(
      CachingErrorCode.UNCAUGHT_SEC_POOL_ERROR(AlertOwnerTeam.CachingTeam).alertOwnerTeam
    )
  }

  test("expect and assert logs are recorded after specified duration") {
    // Test plan: expect with a fake clock, advance time and make sure that the
    // logging statements are printed at the expected frequency ie. every 30 seconds
    import scala.concurrent.duration._
    val fakeClock = new FakeTypedClock
    val logger = PrefixLogger.create(getClass, getSafeName, clock = fakeClock)

    LogCapturer.withCapturer(new Regex("happened")) { capturer: LogCapturer =>
      for (i <- 0 until 10) {
        logger.expect(
          assertion = false,
          CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
          s"expect happened $i",
          30.seconds
        )
        intercept[AssertionError] {
          logger.assert(
            assertion = false,
            CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
            s"assert happened $i",
            30.seconds
          )
        }
        fakeClock.advanceBy(10.seconds)
      }
      val events: Vector[CapturedLogEvent] = capturer.getCapturedEvents

      // log events from assert.
      val errorEvents: Vector[CapturedLogEvent] = events.filter(_.level == Level.ERROR)
      assert(errorEvents.size == 4)
      assert(errorEvents.head.formattedMessage.contains("assert happened 0"))
      assert(errorEvents(1).formattedMessage.contains("assert happened 3"))
      assert(errorEvents(2).formattedMessage.contains("assert happened 6"))
      assert(errorEvents(3).formattedMessage.contains("assert happened 9"))

      // log events from expect.
      val warnEvents: Vector[CapturedLogEvent] = events.filter(_.level == Level.WARN)
      assert(warnEvents.size == 4)
      assert(warnEvents.head.formattedMessage.contains("expect happened 0"))
      assert(warnEvents(1).formattedMessage.contains("expect happened 3"))
      assert(warnEvents(2).formattedMessage.contains("expect happened 6"))
      assert(warnEvents(3).formattedMessage.contains("expect happened 9"))
    }
  }
}

