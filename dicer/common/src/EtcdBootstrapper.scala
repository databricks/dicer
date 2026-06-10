package com.databricks.dicer.common

import com.databricks.caching.util.PrefixLogger
import com.databricks.caching.util.EtcdClient
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.threading.NamedExecutor
import io.grpc.{Status, StatusException}

import scala.concurrent.duration.Duration
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
   * Attempts to initialize etcd metadata for each request in `requests`.
   *
   * If any request fails, the first failed exit code is returned.
   */
  @SuppressWarnings(
    Array(
      "AwaitError",
      "reason:blocking is acceptable during application bootstrap"
    )
  )
  def bootstrapEtcdBlocking(requests: Seq[BootstrapRequest]): ExitCode = {
    // Kick off all bootstrap requests.
    val exitCodeFutures: Seq[Future[ExitCode]] = requests.map(bootstrapEtcdAsync)

    // Wait for them all to complete and collect the results. We use the globalImplicit executor for
    // convenience, as `Future.sequence` is documented to be non-blocking.
    val exitCodesFuture: Future[Seq[ExitCode]] =
      Future.sequence(exitCodeFutures)(implicitly, NamedExecutor.globalImplicit)
    val exitCodes: Seq[ExitCode] = Await.result(exitCodesFuture, Duration.Inf)

    // If any request failed, return the first failed code.
    exitCodes
      .find { exitCode: ExitCode =>
        exitCode != ExitCode.SUCCESS
      }
      .getOrElse(ExitCode.SUCCESS)
  }

  /**
   * Asynchronously writes required etcd metadata using `client` for the given `incarnation`.
   *
   * @return A future that will complete with an [[ExitCode]] with which the application should
   *         exit.
   */
  private def bootstrapEtcdAsync(request: BootstrapRequest): Future[ExitCode] = {
    val versionHighWatermark =
      EtcdClient.Version(highBits = request.incarnation.value, lowBits = UnixTimeVersion.MIN)
    val namespace: EtcdClient.KeyNamespace = request.client.config.keyNamespace
    logger.info(
      s"Bootstrapping etcd namespace $namespace with version high watermark $versionHighWatermark"
    )

    request.client
      .initializeVersionHighWatermarkUnsafe(versionHighWatermark)
      .transform {
        case Success(_) =>
          logger.info(
            s"Successfully written high watermark: $versionHighWatermark for namespace $namespace"
          )
          Success(ExitCode.SUCCESS)
        case Failure(ex: StatusException) if ex.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
          logger.info(s"High watermark already exists: ${ex.toString} for namespace $namespace")
          Success(ExitCode.SUCCESS)
        case Failure(ex: Throwable) =>
          logger.info(s"Bootstrapping failed with error: ${ex.toString} for namespace $namespace")
          Success(ExitCode.RETRYABLE_FAILURE)
      }(NamedExecutor.globalImplicit) // Stateless transformation.
  }
}
