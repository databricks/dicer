package com.databricks.dicer.common

import com.databricks.caching.util.{Pipeline, PrefixLogger}
import com.databricks.caching.util.EtcdClient
import com.databricks.caching.util.UnixTimeVersion
import io.prometheus.client.Gauge

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

/**
 * Etcd bootstrapper provides the functionality to write initial metadata to the etcd cluster
 * specified, and defines the exit code that the application should exit with based on the result
 * of metadata initialization.
 */
object EtcdBootstrapper {

  private val logger = PrefixLogger.create(this.getClass, "")

  /**
   * Records the incarnation of the version high watermark the etcd bootstrapper writes or knows
   * about.
   */
  private val knownWatermarkIncarnationGauge: Gauge = Gauge
    .build()
    .name("dicer_etcd_bootstrapper_known_watermark_incarnation")
    .help(
      "Incarnation (high bits) of the version high watermark the etcd bootstrap knows about for " +
      "the labeled namespace, either from the observed existing watermark in etcd or the newly " +
      "written one, distinguished by the labeled namespace. See " +
      "dicer_etcd_bootstrapper_known_watermark_number for the low bits."
    )
    .labelNames("outcome", "namespace")
    .register()

  /**
   * Records the number (low bits) of the version high watermark the etcd bootstrap knows about.
   */
  private val knownWatermarkNumberGauge: Gauge = Gauge
    .build()
    .name("dicer_etcd_bootstrapper_known_watermark_number")
    .help(
      "Number (low bits) of the version high watermark the etcd bootstrap knows about for " +
      "the labeled namespace, either from the observed existing watermark in etcd or the newly " +
      "written one, distinguished by the labeled namespace. See " +
      "dicer_etcd_bootstrapper_known_watermark_incarnation for the high bits."
    )
    .labelNames("outcome", "namespace")
    .register()

  /**
   * Indicates different results after trying to write initial metadata to the etcd cluster. The
   * scala application should exit with one of these exit codes to inform the kubernetes about the
   * results.
   */
  sealed trait ExitCode {

    /** The numeric exit code passed to the kubernetes job. */
    def value: Int
  }

  object ExitCode {

    /**
     * The metadata is successfully written to etcd, or the data is already in etcd and etcd is in a
     * good state. The kubernetes job will succeed and finish if the scala application quite with
     * this value.
     */
    case object SUCCESS extends ExitCode {
      override val value: Int = 0
    }

    /**
     * The writing fails with some error, e.g. timeout or corrupted data. The kubernetes job will
     * retry some number of times (currently 2) before giving up when the scala application exits
     * with this value. We choose a non zero value 255 so that the kubernetes knows the bootstrapper
     * fails.
     */
    case object RETRYABLE_FAILURE extends ExitCode {
      override val value: Int = 255
    }
  }

  /** A request to initialize etcd metadata using `client` at the given `incarnation`. */
  case class BootstrapRequest(client: EtcdClient, incarnation: Incarnation)

  /**
   * Attempts to initialize etcd metadata for each request in `requests`, recording the outcome of
   * each into [[resultGauge]].
   *
   * If any request fails, the first failed exit code is returned.
   *
   * After all requests complete, this blocks for `lingerAfterFinish` before returning so that
   * [[resultGauge]] survives at least one Prometheus scrape when running as a short-lived
   * Kubernetes job. Callers that do not need the linger (e.g. tests) should pass [[Duration.Zero]].
   */
  @SuppressWarnings(
    Array(
      "AwaitError",
      "reason:blocking is acceptable during application bootstrap"
    )
  )
  def bootstrapEtcdBlocking(
      requests: Seq[BootstrapRequest],
      lingerAfterFinish: FiniteDuration): ExitCode = {
    // Kick off all bootstrap requests.
    val exitCodePipelines: Vector[Pipeline[ExitCode]] =
      requests.map(bootstrapEtcdAsync).toVector

    // Wait for them all to complete and collect the results.
    val exitCodes: Vector[ExitCode] =
      Await.result(Pipeline.sequence(exitCodePipelines).toFuture, Duration.Inf)

    // If any request failed, take the first failed code; otherwise success.
    val exitCode: ExitCode = exitCodes
      .find { exitCode: ExitCode =>
        exitCode != ExitCode.SUCCESS
      }
      .getOrElse(ExitCode.SUCCESS)

    // Linger before returning so the result metric can be scraped before the short-lived job's
    // process exits (log queries are not available in all clusters).
    logger.info(
      s"Etcd bootstrap finished; lingering $lingerAfterFinish before returning so the result " +
      s"metric can be scraped."
    )
    Thread.sleep(lingerAfterFinish.toMillis)

    exitCode
  }

  /**
   * Asynchronously writes required etcd metadata using `client` for the given `incarnation`.
   *
   * @return A pipeline that will complete with an [[ExitCode]] with which the application should
   *         exit.
   */
  private def bootstrapEtcdAsync(request: BootstrapRequest): Pipeline[ExitCode] = {
    val versionHighWatermark =
      EtcdClient.Version(highBits = request.incarnation.value, lowBits = UnixTimeVersion.MIN)
    val namespace: EtcdClient.KeyNamespace = request.client.config.keyNamespace
    logger.info(
      s"Bootstrapping etcd namespace $namespace with version high watermark $versionHighWatermark"
    )

    val resultFuture: Future[Option[EtcdClient.Version]] =
      request.client.initializeVersionHighWatermarkUnsafe(versionHighWatermark)
    // This callback only logs and records thread-safe metrics, so it is safe to run inline.
    Pipeline
      .fromFuture(resultFuture)
      .transform {
        case Success(None) =>
          // We wrote the watermark, so the watermark now in etcd is the one we requested.
          logger.info(
            s"Successfully written high watermark: $versionHighWatermark for namespace $namespace"
          )
          setWatermarkGauges(versionHighWatermark, outcome = "newly_written", namespace)
          Success(ExitCode.SUCCESS)
        case Success(Some(existingWatermark: EtcdClient.Version)) =>
          // A watermark already existed, so the watermark now in etcd is the pre-existing one.
          logger.info(
            s"High watermark already exists: $existingWatermark for namespace $namespace"
          )
          setWatermarkGauges(existingWatermark, outcome = "existing", namespace)
          Success(ExitCode.SUCCESS)
        case Failure(ex: Throwable) =>
          logger.info(s"Bootstrapping failed with error: ${ex.toString} for namespace $namespace")
          Success(ExitCode.RETRYABLE_FAILURE)
      }(Pipeline.InlinePipelineExecutor)
  }

  /** Sets the known watermark gauges. */
  private def setWatermarkGauges(
      versionHighWatermark: EtcdClient.Version,
      outcome: String,
      namespace: EtcdClient.KeyNamespace): Unit = {
    knownWatermarkIncarnationGauge
      .labels(outcome, namespace.value)
      .set(versionHighWatermark.highBits.toDouble)
    knownWatermarkNumberGauge
      .labels(outcome, namespace.value)
      .set(versionHighWatermark.lowBits.value.toDouble)
  }
}
