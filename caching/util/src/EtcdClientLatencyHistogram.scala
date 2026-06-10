package com.databricks.caching.util

import scala.util.Try
import io.prometheus.client.CollectorRegistry
import com.databricks.caching.util.EtcdClient.KeyNamespace

/** The [[EtcdClient]] operations whose latencies are described in the histogram. */
sealed trait OperationType

object OperationType {

  /** An operation that creates a new versioned key. */
  case object CREATE extends OperationType {
    override def toString: String = "create"
  }

  /** An operation that updates an existing key. */
  case object UPDATE extends OperationType {
    override def toString: String = "update"
  }
}

/** The possible results of an [[EtcdClient]] operation */
sealed trait OperationResult

object OperationResult {

  /** An operation completed successfully. */
  case object SUCCESS extends OperationResult {
    override def toString: String = "success"
  }

  /**
   * A create or update operation completed successfully and failed to commit a new version because
   * a key was present with an unexpected version.
   */
  case object WRITE_OCC_FAILURE_KEY_PRESENT extends OperationResult {
    override def toString: String = "write_occ_failure_key_present"
  }

  /**
   * A create or update operation completed successfully and failed to commit a new version because
   * an expected key was absent.
   */
  case object WRITE_OCC_FAILURE_KEY_ABSENT extends OperationResult {
    override def toString: String = "write_occ_failure_key_absent"
  }

  /**
   * An operation did not complete successfully.
   */
  case object FAILURE extends OperationResult {
    override def toString: String = "failure"
  }
}

object EtcdClientLatencyHistogram {

  private val METRIC_LABEL_NAMES = Vector("operationResult", "keyNamespace")

  /**
   * Factory method for creating a [[EtcdClientLatencyHistogram]].
   *
   * @param metric The name of the histogram metric, e.g. "dicer_etcd_client_op_latency".
   * */
  def apply(metric: String): EtcdClientLatencyHistogram = {
    val histogram: CachingLatencyHistogram = CachingLatencyHistogram(metric, METRIC_LABEL_NAMES)
    new EtcdClientLatencyHistogram(histogram)
  }

  object forTest {
    def apply(
        metric: String,
        clock: TypedClock,
        bucketsSecs: Vector[Double],
        registry: CollectorRegistry = CollectorRegistry.defaultRegistry)
        : EtcdClientLatencyHistogram = {
      val histogram: CachingLatencyHistogram = CachingLatencyHistogram.staticForTest.create(
        metric,
        clock,
        METRIC_LABEL_NAMES,
        bucketsSecs,
        registry
      )
      new EtcdClientLatencyHistogram(histogram)
    }
  }
}

/**
 * A thin wrapper around [[CachingLatencyHistogram]] for recording [[EtcdClient]] latency metrics.
 * The wrapper enforces a set of labels and buckets that are appropriate for [[EtcdClient]], and it
 * provides a convenience method for recording the latency of asynchronous thunks.
 *
 * The wrapper, like the underlying [[CachingLatencyHistogram]], is threadsafe.
 */
class EtcdClientLatencyHistogram private (histogram: CachingLatencyHistogram) {

  /**
   * Records a latency observation for an asynchronous thunk that performs the given `operation`.
   *
   * @param thunk Asynchronous thunk to instrument.
   */
  def recordLatencyAsync[T](
      operation: OperationType,
      keyNamespace: KeyNamespace,
      computeOperationResult: Try[T] => OperationResult)(thunk: => Pipeline[T]): Pipeline[T] = {
    def computeExtraLabels(triedResult: Try[T]): Seq[String] = {
      Seq(computeOperationResult(triedResult).toString, keyNamespace.value)
    }
    histogram.recordLatencyAsync(operation.toString, computeExtraLabels) { thunk }
  }
}
