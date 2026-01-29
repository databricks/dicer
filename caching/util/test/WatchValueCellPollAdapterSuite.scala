package com.databricks.caching.util

import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils.TestName
import io.grpc.Status
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.mutable
import scala.collection.JavaConverters._

class WatchValueCellPollAdapterSuite extends DatabricksTest with TestName {

  /**
   * A major use case of the WatchValueCellPollAdapter class is dynamic configuration via SAFE.
   * A SAFE batch flag produces a value typed Map[String, String], which can be polled by calling
   * `batchFlag.getSubFlagValues()`. An example value is:
   * {{{
   *   "databricks.softstore.config.namespaces.foo" => "$foo_config_value_in_proto_string."
   *   "databricks.softstore.config.namespaces.bar" => "$bar_config_value_in_proto_string."
   * }}}
   * The value is of raw type and the desired parsed type is Map[String, NamespaceConfig] where the
   * keys are namespace names (e.g. "foo", "bar"), and the values are NamespaceConfig case class
   * instances which include memory limit, rate limit, and other configured values.
   *
   * In this suite, we define two classes below to simulate the process of watching the SAFE batch
   * flag value. The raw value type is Map[String, String] with key in the format of "raw-*" and
   * value in the format of "raw-value-*".
   * The parsed value type is Map[String, ParsedValue] with key in the format of "parsed-*" and the
   * value in the format of "parsed-value-*"
   */
  /** Type alias for better readability. */
  private type RawValueType = Map[String, String]
  private type ParsedValueMap = Map[String, ParsedValue]

  /** A case class for the parsed value. */
  private case class ParsedValue(value: String) {
    require(value.startsWith("raw-"))
  }

  /** A class that mock the SAFE batch flag. */
  private class MockSafeBatchFlag(initialValue: RawValueType) {
    private val flagValue = new ConcurrentHashMap[String, String]()
    for ((key, value) <- initialValue) {
      flagValue.put(key, value)
    }

    /** Get the most recent value of the map. */
    def getCurrentValue: RawValueType = {
      flagValue.asScala.toMap
    }

    /** Update the value for the map. */
    def updateValue(key: String, value: String): Unit = {
      flagValue.put(key, value)
    }
  }

  /** A subscriber class for test that watch the map value. */
  private class TestSubscriber(index: Int) {
    // Guarded by subscriberSec.
    private var latestKeyValueMap: StatusOr[ParsedValueMap] = StatusOr.success(Map.empty)

    private val subscriberSec =
      SequentialExecutionContext.createWithDedicatedPool(s"subscriber-sec-$index")

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
   * Transform `rawValue` to a parsed value. If the rawValue is malformed, returns the
   * `lastParsedValue`. This is to prevent from SAFE creating a bad value.
   */
  private def transformation(rawValue: RawValueType): ParsedValueMap = {
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
    // 1. Create a WatchValueCellPollAdapter where the pollerThunk is pollCurrentValue.
    //    The periodical poll will start implicitly after startup.
    // 2. Add a subscriber to watch the batch flag.
    // 3. Update the value for the batch flag with a valid value.
    // 4. Wait for a sufficient amount of time to ensure the callback has been executed, and
    //    verify that the subscriber has received the latest value.
    // 5. Update the value for the batch flag with an invalid value.
    // 6. Wait for a sufficient amount of time to ensure the callback has been executed, and
    //    verify that the subscriber's value hasn't been changed.
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    // Update the flag value with a valid value.
    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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

    // Update the flag value with an INVALID value.
    mockSafeBatchFlag.updateValue("raw-key-2", "invalid-value-2-1")

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
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    // Update the flag value with a valid value.
    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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
    // Do some further updates for the batch flag.
    mockSafeBatchFlag.updateValue("raw-key-3", "raw-value-3-1")
    mockSafeBatchFlag.updateValue("raw-key-1", "raw-value-1-1")

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

    // Update the flag value with an INVALID value.
    mockSafeBatchFlag.updateValue("raw-key-2", "invalid-value-2-1")

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
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    // Update the flag value with a valid value.
    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

    // Verify: After waiting, the subscriber should NOT have received the new value, confirming
    // that a single cancel() stopped all polling (i.e., there was only one poller).
    TestUtils.shamefullyAwaitForNonEventInAsyncTest()
    assert(subscriber.getLatestKeyValueMap == INITIAL_MAP_VALUE_PARSED)
  }

  test("Test periodic polling continues when first poll throws exception") {
    // Test plan: Verify that periodic polling is scheduled even if the first poll throws an
    // exception. This tests the try/finally block in start(). Create the SEC on a separate thread
    // to prevent uncaught exceptions from interrupting the test thread. Set up a poller that throws
    // on the first call but succeeds on subsequent calls, call start(), and verify that
    // subscribers eventually receive updates from the subsequent successful polls.

    // Setup: Create a mock batch flag with a special marker key to track whether the first poll
    // has occurred.
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)
    val FIRST_POLL_MARKER_KEY = "first-poll-completed"

    // Create the SEC on a throwaway thread so uncaught exceptions don't interrupt the test thread
    // (uncaught exceptions will result in an interruption of the thread on which the executor is
    // created).
    val secFuture: Future[SequentialExecutionContext] = Future {
      SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    }(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()))
    val sec: SequentialExecutionContext = Await.result(secFuture, Duration.Inf)
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      Some(INITIAL_MAP_VALUE_PARSED),
      () => {
        val currentValue: RawValueType = mockSafeBatchFlag.getCurrentValue
        if (!currentValue.contains(FIRST_POLL_MARKER_KEY)) {
          // Mark that the first poll has been attempted, then throw.
          mockSafeBatchFlag.updateValue(FIRST_POLL_MARKER_KEY, "true")
          throw new RuntimeException("First poll intentionally fails")
        }
        // Remove the marker key so it doesn't interfere with transformation.
        currentValue - FIRST_POLL_MARKER_KEY
      },
      transformation,
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

    // Setup: Update the mock flag value.
    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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

  test("Test that initial value is set from first poll when initialValueOpt is None") {
    // Test plan: Verify that when initialValueOpt is None, the cell has no value until the first
    // poll completes, and then the subscriber receives the polled value. Create a mock flag,
    // create an adapter with None as the initial value, add a subscriber, start the adapter, and
    // verify the subscriber receives the value from the first poll.

    // Setup: Create a mock flag with initial data.
    val mockSafeBatchFlag = new MockSafeBatchFlag(INITIAL_MAP_VALUE_RAW)

    val sec = SequentialExecutionContext.createWithDedicatedPool(s"ec-$getSafeName")
    val watchValueCellAdapter = new WatchValueCellPollAdapter[RawValueType, ParsedValueMap](
      initialValueOpt = None,
      poller = () => mockSafeBatchFlag.getCurrentValue,
      transformation,
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

    // Setup: Update the mock flag value.
    mockSafeBatchFlag.updateValue("raw-key-2", "raw-value-2-1")

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
