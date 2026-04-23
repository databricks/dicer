package com.databricks.caching.util

import com.databricks.caching.util.TestUtils.{assertApproxEqual, assertThrow}
import com.databricks.testing.DatabricksTest
import io.grpc.{Status, StatusException}
import io.prometheus.client.CollectorRegistry
import scala.concurrent.duration.Duration.Inf
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}

import com.databricks.api.base.DatabricksServiceException
import com.databricks.ErrorCode

class CachingLatencyHistogramSuite extends DatabricksTest {
  private val sec = SequentialExecutionContext.createWithDedicatedPool("CachingUsageLoggingSuite")
  private val clock = new FakeTypedClock()

  // Name for the histogram metric.
  private val METRIC: String = "softstore_testlib_op_latency"

  private val METRIC_LABEL_NAMES: Vector[String] = Vector("namespace", "softmap")
  private val NAMESPACE: String = "MyNamespace"
  private val SOFTMAP: String = "MySoftmap"
  private val METRIC_LABELS: Vector[String] = Vector(NAMESPACE, SOFTMAP)

  /**
   * A small set of buckets for use in many tests so that our tests will be comprehensible.
   * (We also have a "Test standard buckets" test that verifies that latencies are correctly
   * bucketed in the standard buckets.)
   */
  private val BUCKETS_SECS = Vector[Double](.001, .003, .005, .007)

  // Expected values for the status label.
  private val SUCCESS: String = "success"
  private val FAILURE: String = "failure"

  // Expected values for the operation label
  private val READ: String = "read"
  private val WRITE: String = "write"

  /**
   * Wrapper for [[MetricUtils.assertHistogramBucketsAndCounts]] that factors out common parameters
   * that are fixed for these tests, and waits for it to eventually pass.
   **/
  private def awaitHistogramCounts(
      expectedBucketCounts: Seq[Int],
      registry: CollectorRegistry,
      operation: String,
      status: String,
      grpcStatusOpt: Option[Status],
      errorCodeOpt: Option[ErrorCode] = None,
      buckets: Vector[Double] = BUCKETS_SECS,
      additionalLabels: Map[String, String] = Map.empty
  ): Unit = {
    val labelsMap: Map[String, String] = Map(
        "operation" -> operation,
        "status" -> status
      ) ++
      METRIC_LABEL_NAMES.zip(METRIC_LABELS).toMap ++
      grpcStatusOpt.map(status => "grpc_status" -> status.getCode.name()).toMap ++
      errorCodeOpt.map(code => "error_code" -> code.name).toMap ++
      additionalLabels

    AssertionWaiter(s"Waiting for expected bucket counts $expectedBucketCounts").await {
      MetricUtils.assertHistogramBucketsAndCounts(
        buckets,
        expectedBucketCounts,
        registry,
        METRIC,
        labelsMap
      )
    }
  }

  /**
   * Wrapper for [[MetricUtils.getHistogramCount]] that factors out common parameters that are
   * fixed for these tests.
   **/
  private def getHistogramCount(
      registry: CollectorRegistry,
      operation: String,
      status: String,
      grpcStatusOpt: Option[Status]): Int = {
    val labelsMap: Map[String, String] = Map(
        "operation" -> operation,
        "status" -> status
      ) ++
      grpcStatusOpt.map(status => "grpc_status" -> status.getCode.name()).toMap ++
      METRIC_LABEL_NAMES.zip(METRIC_LABELS).toMap

    MetricUtils.getHistogramCount(registry, METRIC, labelsMap)
  }

  test("Latency metrics for successful synchronous call") {
    // Test plan: Record latency for a successful synchronous thunk.
    // Verify that the latency histogram metrics are correctly recorded. Record latency metrics for
    // a second synchronous call in a different set of bucket and verify again that the counts are
    // correct.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    val result: String =
      histogram.recordLatencySync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
        clock.advanceBy(4.milliseconds)
        "result"
      }
    assert(result == "result")

    // The total count should have been incremented.
    assert(getHistogramCount(registry, WRITE, SUCCESS, Some(Status.OK)) == 1)

    // Since the latency was 4 milliseconds, we expect the following buckets to be incremented:
    // <=5 (bucket index 3), <=7 (index 4), and <= +Inf (index 5).
    awaitHistogramCounts(Seq(0, 0, 1, 1, 1), registry, WRITE, SUCCESS, Some(Status.OK))

    // Make sure the failure bucket metrics did not change.
    assert(getHistogramCount(registry, WRITE, FAILURE, None) == 0)

    // Simulate another call that takes 6 milliseconds.
    val result2: String =
      histogram.recordLatencySync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
        clock.advanceBy(6.milliseconds)
        "result2"
      }
    assert(result2 == "result2")

    // This should increment the counts only in the last 3 buckets (indexes 4 and 5).
    awaitHistogramCounts(Seq(0, 0, 1, 2, 2), registry, WRITE, SUCCESS, Some(Status.OK))
  }

  test("Latency metrics for successful asynchronous call") {
    // Test plan: Record latency for a thunk that returns a future that succeeds.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    val fut: Future[String] =
      histogram
        .recordLatencyAsync(READ, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            "result"
          }(sec)
        }
        .toFuture

    assert(Await.result(fut, Inf) == "result")

    awaitHistogramCounts(Seq(0, 0, 0, 1, 1), registry, READ, SUCCESS, Some(Status.OK))
  }

  test("Latency metrics for failing synchronous thunk") {
    // Test plan: Record latency for a synchronous thunk that fails by throwing an exception.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    assertThrows[StatusException] {
      histogram.recordLatencySync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
        clock.advanceBy(0.5.milliseconds)
        throw new StatusException(
          Status.INTERNAL.withDescription(
            "Internal error thrown"
          )
        )
      }
    }

    // failure bucket for grpc_status = INTERNAL should increase
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INTERNAL)
    )

    // failure bucket for all failures should be the same as INTERNAL only
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      None
    )
  }

  test("Non StatusException is correctly caught and converted (Sync)") {
    // Test plan: Record latency for a synchronous thunk that fails by throwing an exception.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    assertThrows[IllegalArgumentException] {
      histogram.recordLatencySync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
        clock.advanceBy(0.5.milliseconds)
        throw new IllegalArgumentException("Non StatusException thrown")
      }
    }

    // failure bucket for grpc_status = INVALID_ARGUMENT should increase
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INVALID_ARGUMENT)
    )

    // failure bucket for all failures should be the same as INTERNAL only
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      None
    )
  }

  test("Non StatusException is correctly caught and converted (Async)") {
    // Test plan: Record latency for a synchronous thunk that fails by throwing an exception.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    assertThrows[IllegalStateException] {
      histogram.recordLatencyAsync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP))({
        clock.advanceBy(0.5.milliseconds)
        throw new IllegalStateException("Non StatusException thrown")
      })
    }

    // failure bucket for grpc_status = UNKNOWN should increase
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.UNKNOWN)
    )

    // failure bucket for all failures should be the same as UNKNOWN only
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      None
    )
  }

  test("Latency metrics for failing asynchronous thunk") {
    // Test plan: Record latency for a thunk that returns a future that fails with an exception.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    val future: Future[String] =
      histogram
        .recordLatencyAsync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
          Pipeline {
            clock.advanceBy(0.5.milliseconds)
            throw new StatusException(
              Status.INTERNAL.withDescription(
                "Internal error thrown"
              )
            )
          }(sec)
        }
        .toFuture

    assertThrows[StatusException] {
      Await.result(future, Inf)
    }

    // failure bucket for grpc_status = INTERNAL should increase
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INTERNAL)
    )

    // failure bucket for all failures should be the same as INTERNAL only
    awaitHistogramCounts(Seq(1, 1, 1, 1, 1), registry, WRITE, FAILURE, None)
  }

  test("Latency metrics for asynchronous thunk that fails immediately.") {
    // Test plan: Record latency for a thunk that returns a future that fails with an exception.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    assertThrows[StatusException] {
      histogram.recordLatencyAsync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP))({
        clock.advanceBy(5000.milliseconds)
        throw new StatusException(
          Status.INVALID_ARGUMENT.withDescription(
            "This should have been async but failed immediately."
          )
        )
      })
    }

    // failure bucket for grpc_status = INVALID_ARGUMENT should increase
    awaitHistogramCounts(
      Seq(0, 0, 0, 0, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INVALID_ARGUMENT)
    )

    // failure bucket for all failures should be the same as INVALID_ARGUMENT only
    awaitHistogramCounts(Seq(0, 0, 0, 0, 1), registry, WRITE, FAILURE, None)
  }

  test("Test standard buckets") {
    // Test plan: Create a CachingLatencyHistogram using the standard buckets.
    // Make sure that the standard buckets occur at the expected intervals. Record a short
    // duration operation and make sure we got the units right on the buckets.
    val registry = new CollectorRegistry(true)
    val bucketSecs: Vector[Double] = CachingLatencyHistogram.staticForTest.LOW_LATENCY_BUCKET_SECS
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      bucketSecs = bucketSecs,
      registry = registry
    )
    val callDuration: FiniteDuration = 125.microseconds
    histogram.recordLatencySync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
      clock.advanceBy(callDuration)
      "result"
    }

    // The total count should have been incremented.
    assert(getHistogramCount(registry, WRITE, SUCCESS, Some(Status.OK)) == 1)

    // Make sure that we have buckets at the expected intervals
    // (e.g. 10 microsecond intervals up to 100 microseconds.)
    assert(bucketSecs(0).seconds == 10.microseconds)
    assert(bucketSecs.length == 33)
    val expectedBucketRatio: Double = Math.pow(10, 0.2)
    for (i: Int <- 1 until 26) {
      assertApproxEqual(bucketSecs(i) / bucketSecs(i - 1), expectedBucketRatio)
    }
    val expectedBigBucketRatio: Double = Math.pow(10, 0.4)
    for (i: Int <- 26 until bucketSecs.length) {
      assertApproxEqual(bucketSecs(i) / bucketSecs(i - 1), expectedBigBucketRatio)
    }

    val expectedCounts: Seq[Int] = bucketSecs.map { bucketLessThanDuration: Double =>
        if (callDuration < bucketLessThanDuration.seconds) 1 else 0
      } ++
      Seq(1) // Append the expected +Inf count which is always 1.

    awaitHistogramCounts(
      expectedCounts,
      registry,
      WRITE,
      SUCCESS,
      Some(Status.OK),
      errorCodeOpt = None,
      bucketSecs
    )
  }

  test("Wrong number of labels") {
    // Test plan: Provide wrong number of labels for latency observation and verify that an
    // exception is thrown.

    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    assertThrow[IllegalArgumentException]("Incorrect number of labels.") {
      histogram.recordLatencySync(WRITE, (_: Try[Unit]) => Seq(NAMESPACE)) {
        clock.advanceBy(4.milliseconds)
      }
    }

    assertThrow[IllegalArgumentException]("Incorrect number of labels.") {
      histogram.recordLatencySync(
        WRITE,
        (_: Try[Unit]) => Seq(NAMESPACE, SOFTMAP, "extra_label")
      ) {
        clock.advanceBy(4.milliseconds)
      }
    }
  }

  test("Latency metrics for failing asynchronous thunk with additional cause") {
    // Test plan: Record latency for a thunk that returns a future that fails with a StatusException
    // that has a DatabricksServiceException as a cause. Verify that the cause is converted to the
    // correct label and that the other operation metrics are recorded correctly.
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      registry
    )

    val future: Future[String] =
      histogram
        .recordLatencyAsync(WRITE, (_: Try[String]) => Seq(NAMESPACE, SOFTMAP)) {
          Pipeline {
            clock.advanceBy(0.5.milliseconds)
            throw new StatusException(
              Status.INTERNAL
                .withDescription("Internal error thrown")
                .withCause(
                  DatabricksServiceException(
                    ErrorCode.REQUEST_LIMIT_EXCEEDED,
                    "Request limit exceeded"
                  )
                )
            )
          }(sec)
        }
        .toFuture

    assertThrows[StatusException] {
      Await.result(future, Inf)
    }

    // failure bucket for grpc_status = INTERNAL and error_code = REQUEST_LIMIT_EXCEEDED should
    // increase
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INTERNAL),
      Some(ErrorCode.REQUEST_LIMIT_EXCEEDED)
    )

    // failure bucket for all failures should be the same as INTERNAL only
    awaitHistogramCounts(Seq(1, 1, 1, 1, 1), registry, WRITE, FAILURE, None)
  }

  test("Latency metrics for asynchronous call with computed labels") {
    // Test plan: Record latencies for thunks that return random results or randomly throw
    // exceptions, with a computeExtraLabels function that computes different labels based on these
    // results. Verifying the metrics are recorded with correct computed labels.

    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES :+ "result",
      BUCKETS_SECS,
      registry
    )

    // Create unique fake results for the thunks.
    val testResults: Set[Int] = (0 until 100).map { _ =>
      Random.nextInt()
    }.toSet

    val computeExtraLabels: Try[Int] => Seq[String] = {
      case Success(result: Int) => METRIC_LABELS :+ result.toString
      case Failure(ex) =>
        METRIC_LABELS :+ ex.toString
    }

    val testException = new StatusException(
      Status.INTERNAL.withDescription(
        "Internal error thrown"
      )
    )

    var failureCount: Int = 0

    for (testResult: Int <- testResults) {
      // Randomly inject exception.
      val willThrow: Boolean = Random.nextBoolean()

      val fut: Future[Int] =
        histogram
          .recordLatencyAsync(READ, computeExtraLabels) {
            Pipeline {
              clock.advanceBy(6.milliseconds)
              if (willThrow)
                throw testException
              testResult
            }(sec)
          }
          .toFuture

      if (willThrow) {
        failureCount += 1
        assertThrow[StatusException](testException.getMessage) { Await.result(fut, Inf) }
        awaitHistogramCounts(
          // The metrics from failed thunks has the same label, so the failed metrics are
          // accumulated.
          // As the task takes 6 ms, the counter for the fourth and fifth buckets for [0, 0.7) and
          // [0, Inf) latency will be incremented.
          Seq(0, 0, 0, failureCount, failureCount),
          registry,
          READ,
          FAILURE,
          None,
          additionalLabels = Map("result" -> testException.toString)
        )
      } else {
        assert(Await.result(fut, Inf) == testResult)
        awaitHistogramCounts(
          // The test thunk results are unique, so the count here should always be 1.
          Seq(0, 0, 0, 1, 1),
          registry,
          READ,
          SUCCESS,
          Some(Status.OK),
          additionalLabels = Map("result" -> testResult.toString)
        )
      }
    }
  }

  test("Custom status code extractor") {
    // Test plan: create a custom status code extractor and verify that it is used to choose error
    // classifications for operations recorded by the histogram.

    val extractor: Throwable => Status.Code = {
      case _: NoSuchElementException => Status.Code.NOT_FOUND
      case _: IllegalArgumentException => Status.Code.INVALID_ARGUMENT
      case _ => Status.Code.UNKNOWN
    }
    val registry = new CollectorRegistry(true)
    val histogram = CachingLatencyHistogram.staticForTest.create(
      METRIC,
      clock,
      METRIC_LABEL_NAMES,
      BUCKETS_SECS,
      statusCodeExtractor = extractor,
      registry = registry
    )

    // Record examples of all error codes returned by the extractor, and one success.
    val computeExtraLabels: Try[Int] => Seq[String] = (_: Try[Int]) => METRIC_LABELS
    assertThrow[IllegalArgumentException]("bad arg") {
      histogram.recordLatencySync(WRITE, computeExtraLabels) {
        clock.advanceBy(BUCKETS_SECS(0).seconds)
        throw new IllegalArgumentException("bad arg")
      }
    }
    assertThrow[NoSuchElementException]("not found") {
      histogram.recordLatencySync(WRITE, computeExtraLabels) {
        clock.advanceBy(BUCKETS_SECS(1).seconds)
        throw new NoSuchElementException("not found")
      }
    }
    assertThrow[RuntimeException]("unknown") {
      histogram.recordLatencySync(WRITE, computeExtraLabels) {
        clock.advanceBy(BUCKETS_SECS(2).seconds)
        throw new RuntimeException("unknown")
      }
    }
    assert(histogram.recordLatencySync(WRITE, computeExtraLabels) {
      clock.advanceBy(BUCKETS_SECS(3).seconds)
      42
    } == 42)

    // Await the expected counts.
    awaitHistogramCounts(
      Seq(1, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.INVALID_ARGUMENT)
    )
    awaitHistogramCounts(
      Seq(0, 1, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.NOT_FOUND)
    )
    awaitHistogramCounts(
      Seq(0, 0, 1, 1, 1),
      registry,
      WRITE,
      FAILURE,
      Some(Status.UNKNOWN)
    )
    awaitHistogramCounts(
      Seq(0, 0, 0, 1, 1),
      registry,
      WRITE,
      SUCCESS,
      Some(Status.OK)
    )
  }
}
