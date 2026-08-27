package com.databricks.caching.util

import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils.TestName
import io.grpc.Status
import com.databricks.caching.util.Lock.withLock
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class WatchValueCellPollAdapterSuite extends DatabricksTest with TestName {

  /**
   * The tests model a producer that exposes a raw map through polling. An example value is:
   * {{{
   *   "raw-key-1" => "raw-value-1"
   *   "raw-key-2" => "raw-value-2"
   * }}}
   * The parsed value has type `Map[String, ParsedValue]`, with keys prefixed by `parsed-`.
   */
  /** Type alias for better readability. */
  private type RawValueType = Map[String, String]
  private type ParsedValueMap = Map[String, ParsedValue]

  /** A case class for the parsed value. */
  private case class ParsedValue(value: String) {
    require(value.startsWith("raw-"))
  }

  /** An in-memory source whose raw value can be polled and updated by tests. */
  private class FakeRawSource(initialValue: RawValueType) {
    private val lock = new ReentrantLock()
    private var rawValue: RawValueType = initialValue

    /** Returns the source's current raw value. */
    def current: RawValueType = withLock(lock) {
      rawValue
    }

    /** Updates an entry in the source's raw value. */
    def update(key: String, value: String): Unit = withLock(lock) {
      rawValue += key -> value
    }

    /** Replaces the source's raw value. */
    def set(value: RawValueType): Unit = withLock(lock) {
      rawValue = value
    }
  }

  /** A subscriber class for test that watch the map value. */
  private class TestSubscriber(index: Int) {
    // Guarded by subscriberSec.
    private var latestKeyValueMap: StatusOr[ParsedValueMap] = StatusOr.success(Map.empty)

    private val subscriberSec =
      SequentialExecutionContext.createWithDedicatedPool(
        name = s"subscriber-sec-$index",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )

    val valueStreamCallback: ValueStreamCallback[ParsedValueMap] =
      new ValueStreamCallback[ParsedValueMap](subscriberSec) {

        override protected def onSuccess(value: ParsedValueMap): Unit = {
          subscriberSec.assertCurrentContext()
          latestKeyValueMap = StatusOr.success(value)
        }
      }

    val streamCallback: StreamCallback[ParsedValueMap] =
      new StreamCallback[ParsedValueMap](subscriberSec) {

        override protected def onSuccess(value: ParsedValueMap): Unit = {
          subscriberSec.assertCurrentContext()
          latestKeyValueMap = StatusOr.success(value)
        }

        override protected def onFailure(status: Status): Unit = {
          subscriberSec.assertCurrentContext()
          latestKeyValueMap = StatusOr.error(status)
        }
      }

    def getLatestKeyValueMap: ParsedValueMap = {
      Await
        .result(
          subscriberSec.call {
            latestKeyValueMap
          },
          Duration.Inf
        )
        .get
    }
  }

  /**
   * Transform `rawValue` to a parsed value. If the rawValue is malformed, returns
   * [[INITIAL_MAP_VALUE_PARSED]].
   */
  private def parseRawValue(rawValue: RawValueType): ParsedValueMap = {
    val resultMap = mutable.Map[String, ParsedValue]()
    for ((rawKey, rawValue) <- rawValue) {
      if (!rawKey.startsWith("raw-") || !rawValue.startsWith("raw-")) {
        return INITIAL_MAP_VALUE_PARSED
      }
      val parsedKey = "parsed-" + rawKey.substring("raw-".length)
      val parsedValue = ParsedValue(rawValue)
      resultMap.put(parsedKey, parsedValue)
    }
    resultMap.toMap
  }

  /** The initial value for the KV map in raw value type. */
  private val INITIAL_MAP_VALUE_RAW: RawValueType = Map(
    "raw-key-1" -> "raw-value-1",
    "raw-key-2" -> "raw-value-2",
    "raw-key-3" -> "raw-value-3"
  )

  /** The initial value for the KV map in parsed value type. */
  private val INITIAL_MAP_VALUE_PARSED: ParsedValueMap = Map(
    "parsed-key-1" -> ParsedValue("raw-value-1"),
    "parsed-key-2" -> ParsedValue("raw-value-2"),
    "parsed-key-3" -> ParsedValue("raw-value-3")
  )

  /** The interval between each poll. */
  private val TEST_POLL_INTERVAL: FiniteDuration = 100.milliseconds

  test("Test WatchValueCellPollAdapterSuite with a single subscriber") {
    // Test plan:
    // 1. Create a WatchValueCellPollAdapter that polls a raw source.
    // 2. Add a subscriber.
    // 3. Update the source with a valid value.
    // 4. Wait for a sufficient amount of time to ensure the callback has been executed, and
    //    verify that the subscriber has received the latest value.
    // 5. Update the source with an invalid value.
    // 6. Wait for a sufficient amount of time to ensure the callback has been executed, and
    //    verify that the subscriber's value hasn't been changed.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => rawSource.current,
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )
    watchValueCellAdapter.start()

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)
    assert(watchValueCellAdapter.getStatus == Status.OK) // Status is always OK.

    // Before any updates, the key value map should return the initial value.
    AssertionWaiter("Single-subscriber-assertion-0").await {
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    // Update the source with a valid value.
    rawSource.update("raw-key-2", "raw-value-2-1")

    // Wait for consumer to catch the value update and verify the parsed value is fresh.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("Single-subscriber-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(subscriber.getLatestKeyValueMap == expectedParsedValue)
    }

    // Update the source with an invalid value.
    rawSource.update("raw-key-2", "invalid-value-2-1")

    // Wait for consumer to catch the value update and verify the parsed value is restored to the
    // static fallback.
    AssertionWaiter("Single-subscriber-assertion-2").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(INITIAL_MAP_VALUE_PARSED))
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }
    watchValueCellAdapter.cancel()
    assert(watchValueCellAdapter.getStatus == Status.OK) // Status is always OK.
  }

  test("Test WatchValueCellPollAdapterSuite with multiple subscribers") {
    // Test plan: similar to the single subscriber unit test, just add more subscribers to verify
    // things still work well when there are multiple subscribers.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => rawSource.current,
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )
    watchValueCellAdapter.start()

    val subscriber0 = new TestSubscriber(0)
    val subscriber1 = new TestSubscriber(1)
    val subscriber2 = new TestSubscriber(2)

    // Two subscribers start to watch the value.
    watchValueCellAdapter.watch(subscriber0.valueStreamCallback)
    watchValueCellAdapter.watch(subscriber1.valueStreamCallback)
    assert(watchValueCellAdapter.getStatus == Status.OK) // Status is always OK.

    // Before any updates, the key value map should return the initial value for the watched
    // subscribers.
    AssertionWaiter("Multiple-subscriber-assertion-0").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(INITIAL_MAP_VALUE_PARSED))
      assert(
        subscriber0.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED &&
        subscriber1.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED &&
        subscriber2.getLatestKeyValueMap != INITIAL_MAP_VALUE_PARSED
      )
    }

    // Update the source with a valid value.
    rawSource.update("raw-key-2", "raw-value-2-1")

    // Wait for consumer to catch the value update and verify the parsed value is fresh.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("Multiple-subscriber-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(
        subscriber0.getLatestKeyValueMap == expectedParsedValue &&
        subscriber1.getLatestKeyValueMap == expectedParsedValue
      )
    }

    // Register another subscriber.
    watchValueCellAdapter.watch(subscriber2.valueStreamCallback)
    // Do some further source updates.
    rawSource.update("raw-key-3", "raw-value-3-1")
    rawSource.update("raw-key-1", "raw-value-1-1")

    // Wait for consumer to catch the value update and verify the parsed value is fresh.
    val expectedParsedValue2: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3-1")
    )
    AssertionWaiter("Multiple-subscriber-assertion-2").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue2))
      assert(
        subscriber0.getLatestKeyValueMap == expectedParsedValue2 &&
        subscriber1.getLatestKeyValueMap == expectedParsedValue2 &&
        subscriber2.getLatestKeyValueMap == expectedParsedValue2
      )
    }

    // Update the source with an invalid value.
    rawSource.update("raw-key-2", "invalid-value-2-1")

    // Wait for consumer to catch the value update and verify the parsed value is restored to the
    // static fallback.
    AssertionWaiter("Multiple-subscriber-assertion-3").await {
      assert(
        subscriber0.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED &&
        subscriber1.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED &&
        subscriber2.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED
      )
    }
    watchValueCellAdapter.cancel()
    assert(watchValueCellAdapter.getStatus == Status.OK) // Status is always OK.
  }

  test("Test watch as StreamCallback") {
    // Test plan: verify that a subscriber receives the latest value when the adapter is watched
    // by the subscriber's callback as a StreamCallback.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => rawSource.current,
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )
    watchValueCellAdapter.start()

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.streamCallback)
    assert(watchValueCellAdapter.getStatus == Status.OK) // Status is always OK.

    // Before any updates, the key value map should return the initial value.
    AssertionWaiter("Single-subscriber-assertion-0").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(INITIAL_MAP_VALUE_PARSED))
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    // Update the source with a valid value.
    rawSource.update("raw-key-2", "raw-value-2-1")

    // Wait for consumer to catch the value update and verify the parsed value is fresh.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("Single-subscriber-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(subscriber.getLatestKeyValueMap == expectedParsedValue)
    }
  }

  test("Test cancel before start is no-op") {
    // Test plan: Verify that calling cancel() before start() is harmless. Create an adapter, call
    // cancel(), then call start(), and verify that the adapter still works correctly by observing
    // that a subscriber receives value updates.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => rawSource.current,
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )

    // Cancel before start - this should be a no-op.
    watchValueCellAdapter.cancel()

    watchValueCellAdapter.start()

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)

    AssertionWaiter("cancel-before-start-assertion-0").await {
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    rawSource.update("raw-key-2", "raw-value-2-1")

    // Verify: The subscriber receives the updated value, confirming that the adapter is polling,
    // despite being canceled before start.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("cancel-before-start-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(subscriber.getLatestKeyValueMap == expectedParsedValue)
    }

    watchValueCellAdapter.cancel()
  }

  test("Test multiple start calls is a no-op") {
    // Test plan: Verify that calling start() multiple times creates only one poller. Call start()
    // multiple times, then issue a single cancel(), and verify that no more updates are received.
    // If multiple start() calls created multiple pollers, a single cancel() would not stop them
    // all.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => rawSource.current,
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )

    watchValueCellAdapter.start()
    watchValueCellAdapter.start()
    watchValueCellAdapter.start()

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)

    AssertionWaiter("multiple-start-assertion-0").await {
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    watchValueCellAdapter.cancel()

    // Drain the sec to ensure cancel() has taken effect and any in-flight polls completed.
    TestUtils.awaitResult(sec.call { () }, Duration.Inf)

    rawSource.update("raw-key-2", "raw-value-2-1")

    // Verify: After waiting, the subscriber should NOT have received the new value, confirming
    // that a single cancel() stopped all polling (i.e., there was only one poller).
    TestUtils.shamefullyAwait200msForNonEventInAsyncTest()
    assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
  }

  test("Test periodic polling continues when first poll throws exception") {
    // Test plan: Verify that periodic polling is scheduled even if the first poll throws an
    // exception. This tests the try/finally block in start(). Create the SEC on a separate thread
    // to prevent uncaught exceptions from interrupting the test thread. Set up a poller that throws
    // on the first call but succeeds on subsequent calls, call start(), and verify that
    // subscribers eventually receive updates from the subsequent successful polls.

    // Setup: Create a raw source with a special marker key to track whether the first poll
    // has occurred.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)
    val FIRST_POLL_MARKER_KEY = "first-poll-completed"

    // Create the SEC on a throwaway thread so uncaught exceptions don't interrupt the test thread
    // (uncaught exceptions will result in an interruption of the thread on which the executor is
    // created).
    val secFuture: Future[SequentialExecutionContext] = Future {
      SequentialExecutionContext.createWithDedicatedPool(
        name = s"ec-$getSafeName",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val sec: SequentialExecutionContext = Await.result(secFuture, Duration.Inf)
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => {
        val currentValue: RawValueType = rawSource.current
        if (!currentValue.contains(FIRST_POLL_MARKER_KEY)) {
          // Mark that the first poll has been attempted, then throw.
          rawSource.update(FIRST_POLL_MARKER_KEY, "true")
          throw new RuntimeException("First poll intentionally fails")
        }
        // Remove the marker key so it doesn't interfere with parsing.
        currentValue - FIRST_POLL_MARKER_KEY
      },
      (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)

    // Setup: Start the adapter. The first poll will throw, but the periodic poller should still
    // be scheduled.
    watchValueCellAdapter.start()

    // Verify: Despite the first poll throwing, the subscriber should eventually receive the
    // initial value once the second poll succeeds.
    AssertionWaiter("first-poll-throws-assertion-0").await {
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    // Setup: Update the raw source.
    rawSource.update("raw-key-2", "raw-value-2-1")

    // Verify: The subscriber receives the updated value from a subsequent poll, confirming that
    // periodic polling was scheduled despite the exception in the first poll.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("first-poll-throws-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(subscriber.getLatestKeyValueMap == expectedParsedValue)
    }

    watchValueCellAdapter.cancel()
  }

  test("Test periodic polling continues when a scheduled poll throws an exception") {
    // Test plan: Verify that when the `poller` function throws during a scheduled poll, the
    // cell retains its value from the previous successful poll, an alert fires, and periodic
    // polling continues. Do this by toggling the poller's outcome from "return a valid raw
    // value" to "throw" once the initial poll has completed, simulating a scheduled poll, and
    // asserting the served value still matches our expectation and the alert counter is
    // incremented. Then toggle the poller's outcome back to "return an updated raw value",
    // simulate another scheduled poll, and assert the cell observes the new value.

    // Setup: create the underlying SEC pool on a throwaway thread so uncaught exceptions
    // (i.e. from a poll that throws) interrupt the throwaway thread instead of the test thread.
    // The pool's exception handler interrupts the pool's creator thread when a worker throws.
    val poolName: String = s"pool-$getSafeName"
    val poolFuture: Future[SequentialExecutionContextPool] = Future {
      SequentialExecutionContextPool.create(
        poolName = poolName,
        numThreads = 1,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val pool: SequentialExecutionContextPool = Await.result(poolFuture, Duration.Inf)

    val fakeSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(
        name = "watch-value-cell-poll-adapter-suite-fake-sec",
        pool = pool
      )

    // Track UNCAUGHT_SEC_POOL_ERROR alerts; the SEC pool fires this CRITICAL alert when a
    // task on its worker throws.
    val uncaughtSecPoolErrorAlerts: MetricUtils.ChangeTracker[Int] = MetricUtils.ChangeTracker {
      () =>
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.UNCAUGHT_SEC_POOL_ERROR(AlertOwnerTeam.CachingTeam),
          prefix = poolName
        )
    }

    // Setup: Drives each poll's outcome. The test will toggle it between `Success` (i.e. actually
    // returning a valid raw value) and `Failure` (i.e. throwing an exception).
    val pollerOutcomeLock: ReentrantLock = new ReentrantLock()
    var pollerOutcome: Try[RawValueType] = Success(INITIAL_MAP_VALUE_RAW)

    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      initialValueOpt = Some(INITIAL_MAP_VALUE_PARSED),
      poller = () => withLock(pollerOutcomeLock) { pollerOutcome }.get,
      update = (_, rawValue) => parseRawValue(rawValue),
      pollInterval = TEST_POLL_INTERVAL,
      sec = fakeSec
    )

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)
    watchValueCellAdapter.start()

    // Drain the fake SEC by awaiting a no-op enqueued behind the initial poll. This ensures
    // the initial poll actually occurred on the fake SEC before we then observe the
    // adapter's state.
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell observes the initial mock value via a successful poll.
    assertResult(Some(INITIAL_MAP_VALUE_PARSED))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(INITIAL_MAP_VALUE_PARSED)(subscriber.getLatestKeyValueMap)

    // Arm the poller to throw on its next invocation.
    withLock(pollerOutcomeLock) {
      pollerOutcome = Failure(new RuntimeException("Poll intentionally fails"))
    }

    // Simulate a scheduled poll.
    // Drain the fake SEC by awaiting a no-op enqueued behind the scheduled poll. This
    // ensures the scheduled poll actually occurred on the fake SEC before we then observe
    // the adapter's state.
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell retained its value from the prior successful poll, and that
    // the UNCAUGHT_SEC_POOL_ERROR alert fired exactly once.
    assertResult(Some(INITIAL_MAP_VALUE_PARSED))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(INITIAL_MAP_VALUE_PARSED)(subscriber.getLatestKeyValueMap)
    assertResult(1)(uncaughtSecPoolErrorAlerts.totalChange())

    // Apply a real value update. The next scheduled poll should pick this up, confirming
    // periodic polling was never canceled by the earlier throw.
    val updatedRawValue: RawValueType = INITIAL_MAP_VALUE_RAW + ("raw-key-2" -> "raw-value-2-1")
    withLock(pollerOutcomeLock) {
      pollerOutcome = Success(updatedRawValue)
    }
    val expectedAfterUpdate: ParsedValueMap =
      INITIAL_MAP_VALUE_PARSED + ("parsed-key-2" -> ParsedValue("raw-value-2-1"))

    // Simulate another scheduled poll.
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell and subscriber observe the post-update value.
    assertResult(Some(expectedAfterUpdate))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(expectedAfterUpdate)(subscriber.getLatestKeyValueMap)

    watchValueCellAdapter.cancel()
  }

  test("Test periodic polling continues when `update` throws during a scheduled poll") {
    // Test plan: Verify that when the `update` function throws during a scheduled poll,
    // the cell retains its value from the previous successful poll, an alert fires, and
    // periodic polling continues. Do this by toggling the update's outcome from "return a
    // valid parsed value" to "throw" once the initial poll has completed, simulating a
    // scheduled poll, and asserting the served value still matches our expectation and the
    // alert counter incremented. Then toggle the update's outcome back to "return an
    // updated parsed value", simulate another scheduled poll, and assert the cell observes
    // the new value.

    // Setup: create the underlying SEC pool on a throwaway thread so uncaught exceptions
    // (from an update that throws) interrupt that throwaway thread instead of the test
    // thread. The pool's exception handler interrupts the pool's creator thread when a
    // worker throws.
    val poolName: String = s"pool-$getSafeName"
    val poolFuture: Future[SequentialExecutionContextPool] = Future {
      SequentialExecutionContextPool.create(
        poolName = poolName,
        numThreads = 1,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val pool: SequentialExecutionContextPool = Await.result(poolFuture, Duration.Inf)
    val fakeSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(name = s"fakeSec-$getSafeName", pool = pool)

    // Track UNCAUGHT_SEC_POOL_ERROR alerts; the SEC pool fires this CRITICAL alert when a
    // task on its worker throws.
    val uncaughtSecPoolErrorAlerts: MetricUtils.ChangeTracker[Int] = MetricUtils.ChangeTracker {
      () =>
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.UNCAUGHT_SEC_POOL_ERROR(AlertOwnerTeam.CachingTeam),
          prefix = poolName
        )
    }

    // Setup: Drives each update's outcome. The test will toggle it between `Success`
    // (i.e. returning a valid parsed value) and `Failure` (i.e. throwing an exception).
    val updateOutcomeLock: ReentrantLock = new ReentrantLock()
    var updateOutcome: Try[ParsedValueMap] = Success(INITIAL_MAP_VALUE_PARSED)

    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      initialValueOpt = Some(INITIAL_MAP_VALUE_PARSED),
      poller = () => INITIAL_MAP_VALUE_RAW,
      update = (_, _) => withLock(updateOutcomeLock) { updateOutcome }.get,
      pollInterval = TEST_POLL_INTERVAL,
      sec = fakeSec
    )

    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)
    watchValueCellAdapter.start()

    // Drain the fake SEC by awaiting a no-op enqueued behind the initial poll. This ensures
    // the initial poll actually occurred on the fake SEC before we then observe the
    // adapter's state.
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell observes the initial mock value via a successful poll.
    assertResult(Some(INITIAL_MAP_VALUE_PARSED))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(INITIAL_MAP_VALUE_PARSED)(subscriber.getLatestKeyValueMap)

    // Arm the `update` function to throw on its next invocation.
    withLock(updateOutcomeLock) {
      updateOutcome = Failure(new RuntimeException("Update intentionally fails"))
    }

    // Simulate a scheduled poll.
    // Drain the fake SEC by awaiting a no-op enqueued behind the scheduled poll. This
    // ensures the scheduled poll actually occurred on the fake SEC before we then observe
    // the adapter's state.
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell retained its value from the prior successful poll, and that
    // the UNCAUGHT_SEC_POOL_ERROR alert fired exactly once.
    assertResult(Some(INITIAL_MAP_VALUE_PARSED))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(INITIAL_MAP_VALUE_PARSED)(subscriber.getLatestKeyValueMap)
    assertResult(1)(uncaughtSecPoolErrorAlerts.totalChange())

    // Apply a real value update. The next scheduled poll should pick this up, confirming
    // periodic polling was never canceled by the earlier throw.
    val expectedAfterUpdate: ParsedValueMap =
      INITIAL_MAP_VALUE_PARSED + ("parsed-key-2" -> ParsedValue("raw-value-2-1"))
    withLock(updateOutcomeLock) {
      updateOutcome = Success(expectedAfterUpdate)
    }

    // Simulate another scheduled poll.
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)

    // Validates that the cell and subscriber observe the post-update value.
    assertResult(Some(expectedAfterUpdate))(watchValueCellAdapter.getLatestValueOpt)
    assertResult(expectedAfterUpdate)(subscriber.getLatestKeyValueMap)

    watchValueCellAdapter.cancel()
  }

  test("Test update uses the latest published value across polls") {
    // Test plan: Verify that each update receives the value published by the previous poll. Start
    // without an initial value, poll three disjoint raw maps, and verify that the parsed value
    // accumulates all three entries.
    val rawSource = new FakeRawSource(Map("raw-key-1" -> "raw-value-1"))

    // Preserve previously published entries and overlay the newly parsed raw entries.
    def update(
        previousValueOpt: Option[ParsedValueMap],
        newRawValue: RawValueType): ParsedValueMap =
      previousValueOpt.getOrElse(Map.empty) ++ parseRawValue(newRawValue)

    val poolName = s"update-uses-latest-value-$getSafeName"
    val poolFuture: Future[SequentialExecutionContextPool] = Future {
      SequentialExecutionContextPool.create(
        poolName = poolName,
        numThreads = 1,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val pool: SequentialExecutionContextPool = Await.result(poolFuture, Duration.Inf)
    val fakeSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(name = s"fakeSec-$getSafeName", pool = pool)

    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      initialValueOpt = None,
      poller = () => rawSource.current,
      update = update,
      pollInterval = TEST_POLL_INTERVAL,
      sec = fakeSec
    )
    watchValueCellAdapter.start()

    // The first update receives None and publishes the first parsed entry.
    Await.result(fakeSec.call { () }, Duration.Inf)
    val expectedAfterFirstPoll: ParsedValueMap =
      Map("parsed-key-1" -> ParsedValue("raw-value-1"))
    assertResult(Some(expectedAfterFirstPoll))(watchValueCellAdapter.getLatestValueOpt)

    // The second update receives the first published value and adds the second entry.
    rawSource.set(Map("raw-key-2" -> "raw-value-2"))
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)
    val expectedAfterSecondPoll: ParsedValueMap =
      Map(
        "parsed-key-1" -> ParsedValue("raw-value-1"),
        "parsed-key-2" -> ParsedValue("raw-value-2")
      )
    assertResult(Some(expectedAfterSecondPoll))(watchValueCellAdapter.getLatestValueOpt)

    // The third update receives the second published value and adds the third entry.
    rawSource.set(Map("raw-key-3" -> "raw-value-3"))
    fakeSec.advanceBySync(TEST_POLL_INTERVAL)
    Await.result(fakeSec.call { () }, Duration.Inf)
    assertResult(Some(INITIAL_MAP_VALUE_PARSED))(watchValueCellAdapter.getLatestValueOpt)

    watchValueCellAdapter.cancel()
  }

  test("Test require fails for non-positive pollInterval") {
    // Test plan: Verify that constructing the adapter with a zero pollInterval throws an
    // IllegalArgumentException. A zero pollInterval would result in a busy-loop with no time
    // between successive polls.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    intercept[IllegalArgumentException] {
      new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
        Some(INITIAL_MAP_VALUE_PARSED),
        () => INITIAL_MAP_VALUE_RAW,
        (_, rawValue) => parseRawValue(rawValue),
        Duration.Zero,
        sec
      )
    }
  }

  test("Test that initial value is set from first poll when initialValueOpt is None") {
    // Test plan: Verify that when initialValueOpt is None, the cell has no value until the first
    // poll completes, and then the subscriber receives the polled value. Create a raw source,
    // create an adapter with None as the initial value, add a subscriber, start the adapter, and
    // verify the subscriber receives the value from the first poll.

    // Setup: Create a raw source with initial data.
    val rawSource = new FakeRawSource(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = s"ec-$getSafeName",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      initialValueOpt = None,
      poller = () => rawSource.current,
      update = (_, rawValue) => parseRawValue(rawValue),
      TEST_POLL_INTERVAL,
      sec
    )

    // Setup: Add a subscriber before starting.
    val subscriber = new TestSubscriber(0)
    watchValueCellAdapter.watch(subscriber.valueStreamCallback)

    // Verify: Before starting, the adapter should have no value.
    assert(watchValueCellAdapter.getLatestValueOpt.isEmpty)

    // Setup: Start the adapter. The first poll should set the value.
    watchValueCellAdapter.start()

    // Verify: The subscriber should receive the initial value from the first poll.
    AssertionWaiter("none-initial-value-assertion-0").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(INITIAL_MAP_VALUE_PARSED))
      assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
    }

    // Setup: Update the raw source.
    rawSource.update("raw-key-2", "raw-value-2-1")

    // Verify: The subscriber receives the updated value from subsequent polls.
    val expectedParsedValue: ParsedValueMap = Map[String, ParsedValue](
      "parsed-key-1" -> ParsedValue("raw-value-1"),
      "parsed-key-2" -> ParsedValue("raw-value-2-1"),
      "parsed-key-3" -> ParsedValue("raw-value-3")
    )
    AssertionWaiter("none-initial-value-assertion-1").await {
      assert(watchValueCellAdapter.getLatestValueOpt == Some(expectedParsedValue))
      assert(subscriber.getLatestKeyValueMap == expectedParsedValue)
    }

    watchValueCellAdapter.cancel()
  }
}
