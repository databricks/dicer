package com.databricks.common.web

import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, SynchronousQueue, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import com.sun.net.httpserver.HttpServer
import io.opencensus.contrib.grpc.metrics.RpcViews
import io.opencensus.contrib.zpages.ZPageHandlers

import com.databricks.common.status.ProbeStatusSource
import com.databricks.common.util.Lock
import com.databricks.instrumentation.{DebugStringServletRegistry, DebuggingRootPageHandler}
import com.databricks.logging.ConsoleLogging

import javax.annotation.concurrent.ThreadSafe

/**
 * Open source version of internal InfoService. Creates a single HTTP server hosting ZPages,
 * debug endpoints, a debugging dashboard, and an optional readiness endpoint.
 *
 * This object provides an API compatible with the internal ArmeriaInfoServer, so that
 * callers that depend on the ArmeriaInfoServer API can use it in OSS builds without code
 * changes. The optional status source and branch parameters are accepted but ignored since
 * the OSS debug server does not support health check endpoints.
 */
@ThreadSafe
object InfoService extends ConsoleLogging {

  /**
   * Maximum number of threads in the HTTP server's thread pool. This server only handles
   * low-traffic debugging and ZPages endpoints, so a small cap is sufficient and prevents
   * runaway thread creation under unexpected load.
   */
  private val MAX_WORKER_THREADS: Int = 16

  /** Mutex protecting all mutable state in this object. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** An HTTP server and its backing executor. */
  private case class ServerAndExecutor(httpServer: HttpServer, executor: ExecutorService)

  /** The HTTP server and its backing executor. */
  @GuardedBy("lock")
  private var serverAndExecutorOpt: Option[ServerAndExecutor] = None

  /** Shutdown hook thread registered during start(), removed during stop(). */
  @GuardedBy("lock")
  private var shutdownHookOpt: Option[Thread] = None

  override def loggerName: String = getClass.getName

  /**
   * Starts the InfoService on the given port. Creates a single HTTP server with ZPages/debug
   * endpoints and, if `readinessStatusSourceOpt` is provided, a `/ready` endpoint. The parameters
   * mirror the internal ArmeriaInfoServer.start() API for compatibility, but only `port` is used in
   * the OSS implementation.
   *
   * @param port The port to listen on, or 0 to use an ephemeral port.
   * @param startupStatusSourceOpt Ignored in OSS.
   * @param livenessStatusSourceOpt Ignored in OSS.
   * @param readinessStatusSourceOpt The source of the readiness status served by the `/ready`
   *                                 endpoint, if defined.
   * @param branchOpt Ignored in OSS.
   * @throws IllegalStateException if the info server is already running.
   */
  @throws[IllegalStateException]("if the info server is already running")
  def start(
      port: Int,
      enableAdminServices: Boolean = true,
      startupStatusSourceOpt: Option[ProbeStatusSource] = None,
      livenessStatusSourceOpt: Option[ProbeStatusSource] = None,
      readinessStatusSourceOpt: Option[ProbeStatusSource] = None,
      branchOpt: Option[String] = None,
      cachedCompressPromMetrics: Boolean = false,
      promMetricsSampleLimit: Int = -1): Unit = Lock.withLock(lock) {
    if (serverAndExecutorOpt.isDefined) {
      throw new IllegalStateException("Info Server is already running.")
    }

    logger.info(s"InfoService starting on port $port")

    // `registerAllGrpcViews()` is idempotent, so it is safe to call on every start cycle.
    RpcViews.registerAllGrpcViews()

    // Both the HttpServer's internal dispatcher thread and the ThreadPoolExecutor's worker
    // threads are non-daemon (Thread.daemon defaults to false), which keeps the JVM alive
    // while the server is running.
    val httpServer: HttpServer = HttpServer.create(new InetSocketAddress(port), 0)
    val executor: ExecutorService = new ThreadPoolExecutor(
      /* corePoolSize = */ 1,
      /* maximumPoolSize = */ MAX_WORKER_THREADS,
      /* keepAliveTime = */ 60L,
      /* unit = */ TimeUnit.SECONDS,
      /* workQueue = */ new SynchronousQueue[Runnable](),
      /* threadFactory = */ (r: Runnable) => new Thread(r, "InfoService-worker")
    )
    httpServer.setExecutor(executor)

    try {
      registerZPagesAndDebugEndpoint(httpServer)

      // Register readiness endpoint if a readiness source is specified.
      for (source: ProbeStatusSource <- readinessStatusSourceOpt) {
        httpServer.createContext("/ready", new ReadinessHandler(source))
      }

      httpServer.start()
    } catch {
      case t: Throwable =>
        // Clean up partially-initialized resources so we don't leak non-daemon threads.
        try httpServer.stop(0)
        finally executor.shutdownNow()
        throw t
    }

    logger.info(s"InfoService started on port ${httpServer.getAddress.getPort}")

    serverAndExecutorOpt = Some(ServerAndExecutor(httpServer, executor))

    // The HttpServer's dispatcher thread and the ThreadPoolExecutor's worker threads are
    // non-daemon, so they keep the JVM alive while the server is running. On JVM shutdown
    // (e.g., SIGTERM → System.exit()), this hook stops the server and executor so those
    // threads terminate cleanly. The hook is removed in stop() so that start/stop cycles
    // do not accumulate stale hooks.
    val hook: Thread = new Thread(() => {
      stop(delaySeconds = 0)
    }, "InfoService-ShutdownHook")
    Runtime.getRuntime.addShutdownHook(hook)
    shutdownHookOpt = Some(hook)
  }

  /**
   * Returns the port of the HTTP server, or 0 if the server is not running.
   *
   * @note Returns Int instead of Option[Int] to match the internal InfoService signature.
   */
  def getActivePort(): Int = Lock.withLock(lock) {
    serverAndExecutorOpt
      .map { server: ServerAndExecutor =>
        server.httpServer.getAddress.getPort
      }
      .getOrElse(0)
  }

  /**
   * Stops the HTTP server. After stopping, the service may be restarted by calling `start()`.
   *
   * This method is idempotent: calling it when no server is running is a no-op. This is
   * necessary because the shutdown hook also calls this method, and we must tolerate the case
   * where the caller explicitly stops the server before JVM exit.
   *
   * @param delaySeconds The maximum time in seconds to wait until server exchanges have completed.
   */
  def stop(delaySeconds: Int = 0): Unit = {
    // Extract the server and hook references under the lock, then release the lock before
    // calling httpServer.stop(), which may block for up to `delaySeconds`.
    val (serverOpt, hookOpt): (Option[ServerAndExecutor], Option[Thread]) = Lock.withLock(lock) {
      val s: Option[ServerAndExecutor] = serverAndExecutorOpt
      val h: Option[Thread] = shutdownHookOpt
      serverAndExecutorOpt = None
      shutdownHookOpt = None
      (s, h)
    }

    serverOpt match {
      case Some(server: ServerAndExecutor) =>
        logger.info("Starting shutdown of InfoService")

        val delay: Int = if (delaySeconds <= 0) 0 else delaySeconds
        logger.info(s"Stopping HTTP server in $delay seconds")
        server.httpServer.stop(delay)

        server.executor.shutdownNow()

        // Remove the shutdown hook since it is no longer needed. removeShutdownHook throws
        // IllegalStateException if the JVM is already shutting down, so we catch and ignore it.
        for (hook: Thread <- hookOpt) {
          try {
            Runtime.getRuntime.removeShutdownHook(hook)
          } catch {
            // JVM is already shutting down; hook will run on its own.
            case _: IllegalStateException =>
          }
        }

        logger.info("Completed shutdown of InfoService")
      case None =>
      // Already stopped or never started — nothing to do.
    }
  }

  /** Registers ZPages and debug endpoints on the given HTTP server. */
  private def registerZPagesAndDebugEndpoint(httpServer: HttpServer): Unit = {
    httpServer.createContext(DebugStringServletRegistry.DEBUG_URL_PATH, DebugStringServletRegistry)
    ZPageHandlers.registerAllToHttpServer(httpServer)
    httpServer.createContext("/", new DebuggingRootPageHandler("Debug Dashboard"))
  }
}
