package com.databricks.caching.util

import scala.concurrent.duration.Duration.Inf
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

import io.grpc.Status
import io.prometheus.client.CollectorRegistry
import com.databricks.caching.util.EtcdClient.KeyNamespace
import com.databricks.testing.DatabricksTest

class EtcdClientLatencyHistogramSuite extends DatabricksTest {
  private val sec =
    SequentialExecutionContext.createWithDedicatedPool("EtcdClientLatencyHistogramSuite")
  private val clock = new FakeTypedClock()

  // Name for the histogram metric.
  private val METRIC = "etcd_client_testlib_op_latency"

  private val METRIC_KEY_NAMESPACE: KeyNamespace = KeyNamespace("etcd_client_testlib_namespace")

  private val OTHER_METRIC_KEY_NAMESPACE: KeyNamespace = KeyNamespace(
    "etcd_client_testlib_other_namespace"
  )

  /**
   * A small set of buckets for use in many tests so that our tests will be comprehensible.
   * (We also have a "Test standard buckets" test that verifies that latencies are correctly
   * bucketed in the standard buckets.)
   */
  private val BUCKETS_SECS = Vector[Double](.001, .003, .005, .007)

  /**
   * Wrapper for [[MetricUtils.assertHistogramBucketsAndCounts]] that factors out common parameters
   * that are fixed for these tests, and waits for it to eventually pass.
   */
  private def awaitHistogramCounts(
      expectedBucketCounts: Seq[Int],
      registry: CollectorRegistry,
      operationType: OperationType,
      keyNamespace: KeyNamespace,
      status: String,
      grpcStatusOpt: Option[Status],
      operationResult: OperationResult,
      buckets: Vector[Double] = BUCKETS_SECS
  ): Unit = {
    val labels: Map[String, String] = Map(
        "operation" -> operationType.toString,
        "status" -> status,
        "operationResult" -> operationResult.toString,
        "keyNamespace" -> keyNamespace.value
      ) ++ grpcStatusOpt.map((status: Status) => "grpc_status" -> status.getCode.name()).toMap
    AssertionWaiter(s"Waiting for expected bucket counts $expectedBucketCounts").await {
      MetricUtils.assertHistogramBucketsAndCounts(
        buckets,
        expectedBucketCounts,
        registry,
        METRIC,
        labels
      )
    }
  }

  private val OPERATION_GRID: Seq[(OperationType, OperationResult)] =
    for {
      operation <- Seq(OperationType.CREATE, OperationType.UPDATE)
      result <- Seq(
        OperationResult.SUCCESS,
        OperationResult.WRITE_OCC_FAILURE_KEY_PRESENT,
        OperationResult.WRITE_OCC_FAILURE_KEY_ABSENT
      )
    } yield (operation, result)

  gridTest("Latency metrics for successful asynchronous call")(OPERATION_GRID) { operationParams =>
    val (operationType, operationResult): (OperationType, OperationResult) =
      operationParams

    // Test plan: Record latency for a thunk that completes successfully.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry(true)
    val histogram = EtcdClientLatencyHistogram.forTest(METRIC, clock, BUCKETS_SECS, registry)

    val fut: Future[String] =
      histogram
        .recordLatencyAsync(
          operationType,
          METRIC_KEY_NAMESPACE,
          (_: Try[String]) => operationResult
        ) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            "successful_occ_failure"
          }(sec)
        }
        .toFuture

    assert(Await.result(fut, Inf) == "successful_occ_failure")

    awaitHistogramCounts(
      Seq(0, 0, 0, 1, 1),
      registry,
      operationType,
      METRIC_KEY_NAMESPACE,
      "success",
      Some(Status.OK),
      operationResult
    )
  }

  test("Latency metric for failed asynchronous call") {
    // Test plan: Record latency for a thunk that fails.
    // Verify that the operation metrics are correctly recorded.
    val registry = new CollectorRegistry()
    val histogram = EtcdClientLatencyHistogram.forTest(METRIC, clock, BUCKETS_SECS, registry)

    val fut: Future[String] =
      histogram
        .recordLatencyAsync(
          OperationType.CREATE,
          METRIC_KEY_NAMESPACE,
          (_: Try[String]) => OperationResult.FAILURE
        ) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            throw new RuntimeException("failure_occ_failure")
          }(sec)
        }
        .toFuture

    assertThrows[RuntimeException] {
      Await.result(fut, Inf)
    }

    awaitHistogramCounts(
      Seq(0, 0, 0, 1, 1),
      registry,
      OperationType.CREATE,
      METRIC_KEY_NAMESPACE,
      "failure",
      Some(Status.UNKNOWN),
      OperationResult.FAILURE
    )
  }

  test("Latency metrics for different KeyNamespaces are independent") {
    // Test plan: Verify that metrics recorded for different values of KeyNamespaces are
    // independent. Verify this by writing different values for different KeyNamespaces and
    // verifying that they are as expected.
    val registry = new CollectorRegistry(true)
    val histogram = EtcdClientLatencyHistogram.forTest(METRIC, clock, BUCKETS_SECS, registry)
    val fut1MetricKeyNamespace: Future[String] =
      histogram
        .recordLatencyAsync(
          OperationType.CREATE,
          METRIC_KEY_NAMESPACE,
          (_: Try[String]) => OperationResult.SUCCESS
        ) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            "successful_occ_failure"
          }(sec)
        }
        .toFuture
    assert(Await.result(fut1MetricKeyNamespace, Inf) == "successful_occ_failure")

    val fut2MetricKeyNamespace: Future[String] =
      histogram
        .recordLatencyAsync(
          OperationType.CREATE,
          METRIC_KEY_NAMESPACE,
          (_: Try[String]) => OperationResult.SUCCESS
        ) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            "successful_occ_failure"
          }(sec)
        }
        .toFuture
    assert(Await.result(fut2MetricKeyNamespace, Inf) == "successful_occ_failure")

    val futOtherMetricKeyNamespace: Future[String] =
      histogram
        .recordLatencyAsync(
          OperationType.CREATE,
          OTHER_METRIC_KEY_NAMESPACE,
          (_: Try[String]) => OperationResult.SUCCESS
        ) {
          Pipeline {
            clock.advanceBy(6.milliseconds)
            "successful_occ_failure"
          }(sec)
        }
        .toFuture
    assert(Await.result(futOtherMetricKeyNamespace, Inf) == "successful_occ_failure")

    // Assert the metrics for METRIC_KEY_NAMESPACE contain 2 and OTHER_METRIC_KEY_NAMESPACE contain
    // 1.
    awaitHistogramCounts(
      Seq(0, 0, 0, 2, 2),
      registry,
      OperationType.CREATE,
      METRIC_KEY_NAMESPACE,
      "success",
      Some(Status.OK),
      OperationResult.SUCCESS
    )
    awaitHistogramCounts(
      Seq(0, 0, 0, 1, 1),
      registry,
      OperationType.CREATE,
      OTHER_METRIC_KEY_NAMESPACE,
      "success",
      Some(Status.OK),
      OperationResult.SUCCESS
    )
  }
}
