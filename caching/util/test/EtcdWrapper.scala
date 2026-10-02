package com.databricks.caching.util

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.locks.ReentrantLock

import scala.sys.process.{Process, ProcessLogger}
import scala.util.control.NonFatal
import scala.concurrent.duration._

import com.databricks.caching.util.Lock.withLock

/**
 * Wrapper around an etcd instance running as a subprocess. Handles process lifecycle management
 * (creation, startup, and cleanup) and provides etcd endpoints for client connections.
 *
 * The etcd instance is automatically started when this class is instantiated and automatically
 * stopped when it is closed.
 */
class EtcdWrapper private (process: Process, dataDir: Path, clientURL: String)
    extends AutoCloseable {

  /** Etcd endpoints for connecting to the instance. */
  val endpoint: String = clientURL

  /** Tears down the etcd process. */
  override def close(): Unit = {
    process.destroy()
    try {
      Files.walk(dataDir).iterator().forEachRemaining(p => p.toFile.delete())
      dataDir.toFile.delete()
    } catch {
      case NonFatal(e) => println(s"Failed to clean up etcd data dir: ${e.getMessage}")
    }
  }
}

object EtcdWrapper {

  /** Relative path to Dicer's etcd binary, when running under bazel. */
  private val ETCD_PATH: String = "+etcd_extension+etcd_release/etcd"

  /** Etcd data will be stored in a temporary directory with this prefix. */
  private val ETCD_DATA_DIR_PREFIX = "etcd-data"

  /** Maximum number of retries to start etcd. */
  private val MAX_RETRIES: Int = 10

  /** Maximum duration that we wait for etcd to become ready after starting it (on each try). */
  private val READY_TIMEOUT: FiniteDuration = 5.seconds

  /** Lock used to protect state shared between multiple threads in [[tryStartEtcd]]. */
  private val lock = new ReentrantLock()

  /** Creates and starts an etcd instance as a subprocess. */
  def create(): EtcdWrapper = {
    val dataDir: Path = Files.createTempDirectory(ETCD_DATA_DIR_PREFIX)
    var retryCount: Int = 0

    while (retryCount < MAX_RETRIES) {
      tryStartEtcd(dataDir, READY_TIMEOUT) match {
        case Some(etcd) => return etcd
        case None => retryCount += 1
      }
    }

    throw new RuntimeException(s"Failed to start etcd after $MAX_RETRIES retries")
  }

  /**
   * Attempts to start an etcd instance. Returns [[Some]] with the instance if successful, or
   * [[None]] if the port is already in use (indicating a retry is appropriate). Throws exceptions
   * for other non-retryable failures. Blocks until etcd is ready, or at most `readyTimeout`.
   */
  private def tryStartEtcd(dataDir: Path, readyTimeout: FiniteDuration): Option[EtcdWrapper] = {
    // Pick random ephemeral port between 32768 and 65534 - so peer port is at most 65535, the
    // maximum possible port number.
    val port: Int = 32768 + scala.util.Random.nextInt(32767)
    val peerPort: Int = port + 1
    // Listen for client traffic on this port.
    val clientURL: String = s"http://127.0.0.1:$port"
    // Listen for etcd peer traffic on this port (we don't have any peers here since it's a single
    // etcd instance).
    val peerURL: String = s"http://127.0.0.1:$peerPort"

    // These are shared between the process logger and the main thread, and protected by `lock`.
    val output = new StringBuilder
    var isReady: Boolean = false

    val logger = ProcessLogger(
      out =>
        withLock(lock) {
          output.append(out).append("\n")
        },
      err =>
        withLock(lock) {
          output.append(err).append("\n")
          if (err.contains("ready to serve client requests")) {
            isReady = true
          }
        }
    )

    val subprocess = Process(
      Seq(
        Paths.get(sys.env("RUNFILES_DIR"), ETCD_PATH).toString,
        "--data-dir",
        dataDir.toString,
        "--listen-client-urls",
        clientURL,
        "--advertise-client-urls",
        clientURL,
        "--listen-peer-urls",
        peerURL,
        "--initial-advertise-peer-urls",
        peerURL,
        "--initial-cluster",
        s"default=$peerURL",
        "--initial-cluster-state",
        "new"
      ),
      cwd = None
    ).run(logger)

    // Wait up to `readyTimeout` for etcd to become ready or fail.
    val deadline: Deadline = readyTimeout.fromNow
    while (withLock(lock) { !isReady } && subprocess.isAlive() && deadline.hasTimeLeft()) {
      Thread.sleep(100)
    }

    // Handle the result.
    withLock(lock) {
      val outputStr: String = output.toString
      if (isReady && subprocess.isAlive()) {
        Some(new EtcdWrapper(subprocess, dataDir, clientURL))
      } else if (!subprocess.isAlive()) {
        if (outputStr.contains("bind: address already in use")) {
          println(s"Port $port already in use, retrying...")
          None
        } else {
          throw new RuntimeException(
            s"etcd exited with code ${subprocess.exitValue()}. Output:\n$outputStr"
          )
        }
      } else {
        throw new RuntimeException(s"etcd did not become ready within timeout. Output:\n$outputStr")
      }
    }
  }
}
