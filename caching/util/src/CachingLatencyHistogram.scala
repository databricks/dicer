package com.databricks.caching.util

import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import io.grpc.Status
import io.prometheus.client.{CollectorRegistry, Histogram}

import com.databricks.ErrorCode
import com.databricks.api.ErrorCodeUtils
import com.databricks.caching.util.Pipeline.InlinePipelineExecutor

/**
 * A thin wrapper on a Prometheus histogram for recording latency metrics. By default, the wrapper
 * provides a set of exponential buckets that are appropriate for local caches (tens of
 * microseconds), remote caches (milliseconds), and db operations (tens, hundreds, or thousands of
 * milliseconds), and it provides convenience methods for recording the latency of synchronous and
 * asynchronous thunks.
 *
 * @constructor Creates a new [[CachingLatencyHistogram]].
 *
 * @param metric The name of the histogram metric, e.g. "softstore_storelet_op_latency".
 * @param clock The clock to use for measuring latency.
 * @param extraLabelNames The sequence of label names for labels that are attached to observations
 *                        of this metric. Note that the order of this sequence dictates the order in
 *                        which label values need to be passed to `recordLatencyAsync()` and
 *                        `recordLatencySync()`.
 * @param bucketSecs The bucket boundaries, measured in seconds. The bucket sample values contain
 *                   cumulative counts of the observations less than or equal to the bucket
 *                   boundary. Beyond the final boundary is an implicit boundary at positive
 *                   infinity.
 * @param registry The Prometheus registry for collecting metrics. Providing a registry other
 *                 than the default one is useful to provide isolation between tests so that
 *                 each test begins with an empty set of counters. (Resist the temptation to
 *                 call `clear()` on the default registry before each test, it will unregister
 *                 this and other registered objects and then no further statistics will be
 *                 recorded.)
 * @param statusCodeExtractor Extracts gRPC status code from an exception.
 * @param errorCodeOverride Optionally overrides the error code extracted from an exception.
 */
@ThreadSafe
class CachingLatencyHistogram private (
    metric: String,
    clock: TypedClock,
    extraLabelNames: Iterable[String],
    bucketSecs: Vector[Double],
    registry: CollectorRegistry,
    statusCodeExtractor: Throwable => Status.Code,
    errorCodeOverride: Throwable => Option[ErrorCode]) {

  /** The underlying histogram. */
  private val histogram = io.prometheus.client.Histogram
    .build()
    .name(metric)
    .help("Operation latency histogram for caching component")
    .labelNames(Seq("operation", "status", "grpc_status", "error_code") ++ extraLabelNames: _*)
    .buckets(bucketSecs: _*)
    .register(registry)

  /**
   * Record latency metrics for a thunk that returns a pipeline, with extra labels computed from
   * the result of the pipeline.
   *
   * @param operation           The cache operation whose latency is being recorded, e.g. "read" or
   *                            "write".
   * @param thunk               Asynchronous thunk to instrument.
   * @param computeExtraLabels  A function that computes the extra labels based on the result of the
   *                            task that `thunk` returns. Note that the order of returned labels
   *                            must match the order of the label names sequence that was passed to
   *                            the factory method that created this object.
   * @tparam T                  Type of the Future returned from futureThunk.
   * @throws Exception          If `thunk` itself throws, the latency will still be recorded and the
   *                            exception will then be re-thrown.
   */
  def recordLatencyAsync[T](operation: String, computeExtraLabels: Try[T] => Seq[String])(
      thunk: => Pipeline[T]): Pipeline[T] = {
    val startTime: TickerTime = clock.tickerTime()

    try {
      val pipeline: Pipeline[T] = thunk // Invokes the thunk.

      // Record the latency when the future completes.
      pipeline.andThen {
        case result @ Success(_) =>
          onSuccess(operation, startTime, computeExtraLabels(result))
        case result @ Failure(ex: Throwable) =>
          onFailure(operation, startTime, ex, computeExtraLabels(result))
      }(InlinePipelineExecutor) // Safe because this class has no mutable state of its own.
    } catch {
      // Handle the case where the thunk itself throws immediately.
      case NonFatal(ex) =>
        onFailure(operation, startTime, ex, computeExtraLabels(Failure(ex)))
        throw ex
    }
  }

  /**
   * Record latency metrics for a thunk that returns a value synchronously, with extra labels
   * computed from the result of the thunk.
   *
   * @param operation           The cache operation whose latency is being recorded, e.g. "read" or
   *                            "write".
   * @param thunk               Thunk to instrument.
   * @param computeExtraLabels  A function that computes the extra labels based on the result of
   *                            `thunk`. Note that the order of returned labels must match the order
   *                            of the label names sequence that was passed to the factory method
   *                            that created this object.
   * @tparam T                  Expected type of Future returned from operationFn.
   * @throws Exception          @throws Exception If `thunk` itself throws, the latency will still
   *                            be recorded and the exception will then be re-thrown.
   */
  def recordLatencySync[T](operation: String, computeExtraLabels: Try[T] => Seq[String])(
      thunk: => T): T = {
    val startTime: TickerTime = clock.tickerTime()
    try {
      val result: T = thunk // invokes the thunk.
      onSuccess(operation, startTime, computeExtraLabels(Success(result)))
      result
    } catch {
      case NonFatal(ex) =>
        onFailure(operation, startTime, ex, computeExtraLabels(Failure(ex)))
        throw ex
    }
  }

  /**
   * Returns a recorder for `operation` whose extra labels are always `extraLabels`. Recording
   * through it is equivalent to [[recordLatencySync]] with a `computeExtraLabels` function that
   * always returns `extraLabels`, but it does not rebuild the label values or look up the
   * histogram series for each successful operation. Intended for callers that record many
   * operations with the same labels.
   *
   * @param operation   The cache operation whose latency is being recorded, e.g. "read".
   * @param extraLabels The extra label values, in the order of the label names sequence that was
   *                    passed to the factory method that created this object.
   * @throws IllegalArgumentException If the number of `extraLabels` doesn't match the number of
   *                                  extra label names of this histogram.
   */
  @throws[IllegalArgumentException]
  def fixedLabelsRecorder(operation: String, extraLabels: Seq[String]): FixedLabelsRecorder = {
    new FixedLabelsRecorder(operation, extraLabels)
  }

  /**
   * Records operation latencies for one operation and one set of extra labels. See
   * [[fixedLabelsRecorder]].
   *
   * @throws IllegalArgumentException If the number of `extraLabels` doesn't match the number of
   *                                  extra label names of the enclosing histogram.
   */
  @ThreadSafe
  class FixedLabelsRecorder @throws[IllegalArgumentException] private[CachingLatencyHistogram] (
      operation: String,
      extraLabels: Seq[String]) {

    /** The series that successful operations are recorded in. */
    private val successChild: Histogram.Child = histogram.labels(
      (Seq(operation, "success", Status.Code.OK.toString, "") ++ extraLabels): _*
    )

    /**
     * Records the latency of a thunk that returns a value synchronously. Same as
     * [[CachingLatencyHistogram.recordLatencySync]] with this recorder's operation and extra
     * labels.
     *
     * @param thunk Thunk to instrument.
     * @tparam T    The return type of `thunk`.
     * @throws Exception If `thunk` itself throws, the latency will still be recorded and the
     *                   exception will then be re-thrown.
     */
    def recordLatencySync[T](thunk: => T): T = {
      val startTime: TickerTime = clock.tickerTime()
      try {
        val result: T = thunk // invokes the thunk.
        val latencyDuration: FiniteDuration = clock.tickerTime() - startTime
        successChild.observe(latencyDuration.toUnit(TimeUnit.SECONDS))
        result
      } catch {
        case NonFatal(ex) =>
          onFailure(operation, startTime, ex, extraLabels)
          throw ex
      }
    }
  }

  /** Helper function to observe the latency since `startTime` with the given labels. */
  def observeLatency(
      latencyDuration: FiniteDuration,
      operation: String,
      statusCode: Status.Code,
      errorCodeOpt: Option[ErrorCode],
      extraLabels: Seq[String]): Unit = {
    val statusLabel: String = if (statusCode == Status.Code.OK) "success" else "failure"
    val statusCodeLabel: String = statusCode.toString
    val errorCodeLabel: String = errorCodeOpt
      .map { errorCode: ErrorCode =>
        errorCode.name
      }
      .getOrElse("")
    val allLabels = Seq(operation, statusLabel, statusCodeLabel, errorCodeLabel) ++ extraLabels
    histogram
      .labels(allLabels: _*)
      .observe(latencyDuration.toUnit(TimeUnit.SECONDS))
  }

  /** Records the latency of a successful operation. */
  private def onSuccess(
      operation: String,
      startTime: TickerTime,
      extraLabels: Seq[String]): Unit = {
    val latencyDuration: FiniteDuration = clock.tickerTime() - startTime
    observeLatency(
      latencyDuration = latencyDuration,
      operation = operation,
      statusCode = Status.Code.OK,
      errorCodeOpt = None,
      extraLabels = extraLabels
    )
  }

  /** Records the latency of a failed operation. */
  private def onFailure(
      operation: String,
      startTime: TickerTime,
      ex: Throwable,
      extraLabels: Seq[String]): Unit = {
    val latencyDuration: FiniteDuration = clock.tickerTime() - startTime
    observeLatency(
      latencyDuration = latencyDuration,
      operation = operation,
      statusCode = statusCodeExtractor(ex),
      errorCodeOpt = Some(errorCodeOverride(ex).getOrElse(ErrorCodeUtils.getFinalErrorCode(ex))),
      extraLabels = extraLabels
    )
  }
}

/** Companion object for [[CachingLatencyHistogram]]. */
object CachingLatencyHistogram {

  /**
   * The buckets are chosen to be exponentially spaced 10 microseconds to 631 seconds
   * (10.5 minutes).
   * 10, 25.12, 63.10 (microseconds)
   * 158.49, 398.11, 1000 (microseconds)
   * 2.51, 6.31 (milliseconds)
   * 15.85, 39.81, 100 (milliseconds)
   * 251.19, 630.96 (milliseconds)
   *
   * 2.51, 15.85, 100.0, 630.96 (seconds)
   *
   * 10 microseconds to 631 milliseconds [13 buckets] is mainly for cache latency
   * f(i) = 10 * 10^(0.4 * i) / 1000000
   * and 2.51 seconds to 631 seconds [4 buckets] is for cache miss database latency
   * f(i) = 10^(0.4 * (2 * i - 1))
   *
   * total 13 + 4 = 17 buckets
   */
  private val LOW_LATENCY_BUCKET_SECS: Vector[Double] = {
    ((for (i <- 0 until 13) yield 10 * math.pow(10, 0.4 * i) / 1000000.0) ++
    (for (i <- 1 until 5) yield math.pow(10, 0.4 * (2 * i - 1)))).toVector
  }

  /**
   * Default status code extractor that uses the heuristics in
   * [[StatusUtils.convertExceptionToStatus()]] to convert exceptions to status codes.
   */
  private val DEFAULT_STATUS_CODE_EXTRACTOR: Throwable => Status.Code = { ex: Throwable =>
    StatusUtils.convertExceptionToStatus(ex).getCode
  }

  /**
   * Factory method for creating a [[CachingLatencyHistogram]]. By default, buckets
   * suitable for a mix of local cache, remote cache, and remote database operations
   * are used. See [[CachingLatencyHistogram()]] for more details.
   */
  def apply(
      metric: String,
      extraLabelNames: Iterable[String],
      statusCodeExtractor: Throwable => Status.Code = DEFAULT_STATUS_CODE_EXTRACTOR,
      customBucketsSecs: Vector[Double] = LOW_LATENCY_BUCKET_SECS,
      errorCodeOverride: Throwable => Option[ErrorCode] = _ => None): CachingLatencyHistogram = {
    new CachingLatencyHistogram(
      metric,
      RealtimeTypedClock, // Measure latency using the system clock.
      extraLabelNames,
      customBucketsSecs,
      CollectorRegistry.defaultRegistry, // Collect metrics using the real Prometheus registry.
      statusCodeExtractor,
      errorCodeOverride
    )
  }

  object staticForTest {

    private[util] val LOW_LATENCY_BUCKET_SECS: Vector[Double] = {
      CachingLatencyHistogram.LOW_LATENCY_BUCKET_SECS
    }

    /**
     * Test factory method that allows the caller to override the clock, buckets, and the
     * [[CollectorRegistry]].
     */
    def create(
        metric: String,
        clock: TypedClock,
        extraLabelNames: Iterable[String] = Vector.empty,
        bucketSecs: Vector[Double] = LOW_LATENCY_BUCKET_SECS,
        registry: CollectorRegistry = CollectorRegistry.defaultRegistry,
        statusCodeExtractor: Throwable => Status.Code = DEFAULT_STATUS_CODE_EXTRACTOR,
        errorCodeOverride: Throwable => Option[ErrorCode] = _ => None): CachingLatencyHistogram = {
      new CachingLatencyHistogram(
        metric,
        clock,
        extraLabelNames,
        bucketSecs,
        registry,
        statusCodeExtractor,
        errorCodeOverride
      )
    }
  }
}
