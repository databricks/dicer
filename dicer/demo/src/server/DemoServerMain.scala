package com.databricks.dicer.demo.server

import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters.asJavaIterable
import scala.util.control.NonFatal

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.common.collect.{ImmutableRangeSet, Range}
import io.grpc.{Grpc, InsecureServerCredentials, Server, ServerBuilder, ServerCredentials}

import com.databricks.DatabricksMain
import com.databricks.api.proto.dicer.demo.{
  DeleteValueRequestP,
  DeleteValueResponseP,
  DemoServiceGrpc,
  GetValueRequestP,
  GetValueResponseP,
  PutValueRequestP,
  PutValueResponseP
}
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{PrefixLogger, ServerConf}
import com.databricks.common.util.ShutdownHookManager
import com.databricks.conf.Config
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.demo.common.DemoCommon
import com.databricks.dicer.external.{
  InfinitySliceKey,
  Slice,
  SliceKey,
  SliceKeyHandle,
  Slicelet,
  SliceletConf,
  SliceletListener,
  Target
}
import com.databricks.rpc.tls.{TLSOptions, TLSOptionsMigration}

/** Configuration for the Demo Server. */
class DemoServerConf(project: Project.Project, rawConfig: Config)
    extends ProjectConf(project, rawConfig)
    with SliceletConf
    with ServerConf {

  // SSL configuration is read from databricks.rpc.cert/key/truststore via ServerConf.
  // When those config keys are set, sslArgs will be populated and SSL will be enabled.
  override def dicerTlsOptions: Option[TLSOptions] = TLSOptionsMigration.convert(sslArgs)
}

/**
 * DemoServer demonstrates a Dicer-sharded service.
 *
 * This server:
 * - Exposes gRPC endpoints for Get, Put, and Delete operations
 * - Stores all state in an in-memory cache (5,000 entry maximum)
 * - Registers with Dicer via Slicelet to receive key assignments
 */
object DemoServerMain extends DatabricksMain(Project.DemoServer) {

  /** The gRPC server port for client requests. */
  private val SERVER_PORT: Int = 8080

  override def wrappedMain(args: Array[String]): Unit = {
    val conf = new DemoServerConf(Project.DemoServer, rawConfig)
    val target = Target(DemoCommon.TARGET_NAME)

    // Create the Slicelet for Dicer integration.
    val slicelet = Slicelet(conf, target)

    // Add an example listener that logs assignment changes.
    val listener = new DemoSliceletListener(slicelet)

    // Build and start the gRPC server.
    val serviceImpl = new DemoServiceImpl(slicelet)

    // Determine server credentials based on TLS configuration.
    val serverCredentials: ServerCredentials = conf.getDicerServerTlsOptions match {
      case Some(tlsOptions) =>
        logger.info(s"Starting demo server on port $SERVER_PORT with TLS enabled")
        tlsOptions.serverCredentials()
      case None =>
        logger.info(s"Starting demo server on port $SERVER_PORT without TLS (plaintext)")
        InsecureServerCredentials.create()
    }

    val serverBuilder: ServerBuilder[_] = Grpc
      .newServerBuilderForPort(SERVER_PORT, serverCredentials)
      .addService(
        DemoServiceGrpc.bindService(serviceImpl, ExecutionContext.global)
      )

    val server: Server = serverBuilder.build().start()

    logger.info(s"DemoServer started on port $SERVER_PORT")

    // Register graceful shutdown hook for the gRPC server.
    ShutdownHookManager.addShutdownHook(ShutdownHookManager.DEFAULT_SHUTDOWN_PRIORITY) {
      logger.info("Initiating graceful shutdown of gRPC server")

      // Wait an unconditional 30 seconds for: the Slicelet to notify the Assigner that we are
      // shutting down, the Assigner to reassign our keys away, and the updated assignment to
      // propagate to all clients. In the meantime we still accept requests as usual.
      Thread.sleep(30000)

      // Stop accepting new requests.
      server.shutdown()
      // Wait for in-flight RPCs to complete (15 second grace period).
      if (!server.awaitTermination(15, TimeUnit.SECONDS)) {
        logger.warn("Grace period expired, forcing shutdown")
        // shutdownNow isn't actually instantaneous, the `server.awaitTermination()` at the end of
        // `wrappedMain` gives it time to clean resources up gracefully.
        server.shutdownNow()
      }

      logger.info("gRPC server shutdown complete")
    }

    // Start the Slicelet and connect to Dicer.
    // The selfPort tells Dicer where clients should send requests.
    slicelet.start(SERVER_PORT, Some(listener))

    logger.info(s"Slicelet started for target ${DemoCommon.TARGET_NAME}")

    server.awaitTermination()
  }
}

/**
 * gRPC service implementation for DemoService. It uses an in-memory cache as the primary data
 * store for get, put, and delete operations.
 */
class DemoServiceImpl(slicelet: Slicelet) extends DemoServiceGrpc.DemoService {

  private val prefixLogger = PrefixLogger.create(getClass, "")

  /**
   * In-memory cache with 5,000 entry maximum. Note that in practice you'd likely want to use
   * `maximumWeight` instead of `maximumSize` to control the total size of the cache, if values
   * can have significantly different weights.
   */
  private val cache: Cache[Int, String] = Scaffeine()
    .maximumSize(5000)
    .build[Int, String]()

  /**
   * Helper method to run `thunk`, factoring out the common Dicer logic of every request: create a
   * handle, check assignment, report load, execute `thunk`, and ensure cleanup. `operation` is used
   * to describe the operation being performed in logs.
   */
  private def withHandle[T](key: Int, operation: String)(thunk: => Future[T]): Future[T] = {
    val sliceKey: SliceKey = DemoCommon.toSliceKey(key)

    // Check if this server is assigned this key by Dicer.
    val handle: SliceKeyHandle = slicelet.createHandle(sliceKey)
    try {
      if (!handle.isAssignedContinuously) {
        // For this use case, we log a warning and still serve the request, favoring availability.
        // In production, you would likely want metrics to track and alert on this.
        prefixLogger.warn(s"Key $key may not be assigned to this server", every = 1.second)
      }

      // Report some load to Dicer for load balancing decisions.
      handle.incrementLoadBy(100)

      thunk
    } catch {
      case NonFatal(e) =>
        prefixLogger.error(s"Error $operation for key $key: $e")
        Future.failed(e)
    } finally {
      handle.close()
    }
  }

  override def getValue(request: GetValueRequestP): Future[GetValueResponseP] = {
    val key: Int = request.getKey

    withHandle(key, "getting value") {
      val valueOpt: Option[String] = cache.getIfPresent(key)
      val cacheStatus: String = if (valueOpt.isDefined) "HIT" else "MISS"
      prefixLogger.info(f"GET Key=$key, Cache $cacheStatus", every = 2.second)
      Future.successful(GetValueResponseP(value = valueOpt))
    }
  }

  override def putValue(request: PutValueRequestP): Future[PutValueResponseP] = {
    val key: Int = request.getKey
    val value: String = request.getValue

    withHandle(key, "putting value") {
      cache.put(key, value)
      prefixLogger.info(f"PUT Key=$key", every = 2.second)
      Future.successful(PutValueResponseP())
    }
  }

  override def deleteValue(request: DeleteValueRequestP): Future[DeleteValueResponseP] = {
    val key: Int = request.getKey

    withHandle(key, "deleting value") {
      cache.invalidate(key)
      prefixLogger.info(f"DELETE Key=$key", every = 2.second)
      Future.successful(DeleteValueResponseP())
    }
  }
}

/** Listener that tracks and logs assignment updates from Dicer. */
class DemoSliceletListener(slicelet: Slicelet) extends SliceletListener {

  private val logger = PrefixLogger.create(getClass, "")

  /**
   * The ranges assigned to this Slicelet. The listener is guaranteed to be called serially, so we
   * don't need a lock.
   */
  private var assignedRanges: ImmutableRangeSet[SliceKey] = ImmutableRangeSet.of[SliceKey]()

  private def sliceToRange(slice: Slice): Range[SliceKey] = slice.highExclusive match {
    case highExclusive: SliceKey => Range.closedOpen(slice.lowInclusive, highExclusive)
    case InfinitySliceKey => Range.atLeast(slice.lowInclusive)
  }

  override def onAssignmentUpdated(): Unit = {
    val assignedSlices: Seq[Slice] = slicelet.assignedSlices
    val currentAssignedRanges: ImmutableRangeSet[SliceKey] =
      ImmutableRangeSet.copyOf(asJavaIterable(assignedSlices.map(sliceToRange)))

    val previousAssignedRanges: ImmutableRangeSet[SliceKey] = this.assignedRanges
    this.assignedRanges = currentAssignedRanges

    val removed: ImmutableRangeSet[SliceKey] =
      previousAssignedRanges.difference(currentAssignedRanges)
    val added: ImmutableRangeSet[SliceKey] =
      currentAssignedRanges.difference(previousAssignedRanges)

    logger.info(
      s"Assignment updated - Added: $added, Removed: $removed. " +
      s"Currently assigned ${assignedSlices.size} slices."
    )
  }
}
