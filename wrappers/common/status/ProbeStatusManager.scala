package com.databricks.common.status

import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.util.control.NonFatal

import com.databricks.common.util.Lock

/**
 * OSS readiness manager. The first start registers the process's sources; later starts are ignored.
 * Readiness is unavailable until startup succeeds, and stale results fail closed. Sources run on
 * separate daemon timers so a blocked source cannot block readiness consumers.
 *
 * Liveness is unused in OSS. Unlike the internal manager, this wrapper has no flags or metrics.
 */
@ThreadSafe
object ProbeStatusManager {

  /** Protects registration and the test polling interval. */
  private val lock: ReentrantLock = new ReentrantLock()

  @GuardedBy("lock")
  private var instanceOpt: Option[ReadinessPoller] = None

  @GuardedBy("lock")
  private var pollIntervalMs: Long = 5000L

  /** Starts polling once, preserving the first caller's sources. Liveness is unused in OSS. */
  def start(
      startup: ProbeStatusSource,
      readiness: ProbeStatusSource,
      liveness: ProbeStatusSource): Unit = Lock.withLock(lock) {
    if (instanceOpt.isEmpty) {
      val poller: ReadinessPoller = new ReadinessPoller(startup, readiness, pollIntervalMs)
      poller.start()
      instanceOpt = Some(poller)
    }
  }

  /** Returns whether sources have been registered with [[start]]. */
  def isStarted: Boolean = Lock.withLock(lock) { instanceOpt.nonEmpty }

  /** Returns cached readiness, or NOT_YET_READY until startup has succeeded. */
  def currentReadiness: ProbeStatus = {
    val snapshotOpt: Option[ReadinessPoller] = Lock.withLock(lock) { instanceOpt }
    snapshotOpt match {
      case Some(poller) => poller.currentReadiness
      case None => ProbeStatuses.notYetReady("startup")
    }
  }

  /** Returns true when [[currentReadiness]] is a 2xx code. */
  def isReady: Boolean = {
    val code: Int = currentReadiness.code
    code >= 200 && code < 300
  }

  /** Stops polling and clears registration. Intended for isolated test fixtures. */
  def resetForTesting(): Unit = Lock.withLock(lock) {
    instanceOpt.foreach { poller: ReadinessPoller =>
      poller.stop()
    }
    instanceOpt = None
  }

  /** Uses `ms` for managers started within `body`; restores the interval on exit. */
  @throws[IllegalArgumentException]("if ms is not positive")
  def withPollIntervalMsForTesting[T](ms: Long)(body: => T): T = {
    require(ms > 0, "poll interval must be positive")
    val previous: Long = Lock.withLock(lock) {
      val saved: Long = pollIntervalMs
      pollIntervalMs = ms
      saved
    }
    try body
    finally Lock.withLock(lock) { pollIntervalMs = previous }
  }

  /** Owns one registration's cached readiness and latched startup result. */
  @ThreadSafe
  private class ReadinessPoller(
      startup: ProbeStatusSource,
      readiness: ProbeStatusSource,
      intervalMs: Long) {

    private val lock: ReentrantLock = new ReentrantLock()
    private val startupTimer: Timer = new Timer("probe-status-manager-startup", true)
    private val readinessTimer: Timer = new Timer("probe-status-manager-readiness", true)
    private val staleAfterNanos: Long = TimeUnit.MILLISECONDS.toNanos(intervalMs) * 3

    @GuardedBy("lock")
    private var startupComplete: Boolean = false

    @GuardedBy("lock")
    private var readinessStatus: ProbeStatus = ProbeStatuses.notYetReady("readiness")

    @GuardedBy("lock")
    private var updatedAtNanos: Long = System.nanoTime()

    /** Schedules independent polls; neither caller nor consumers invoke external sources. */
    def start(): Unit = {
      startupTimer.schedule(
        new TimerTask {
          override def run(): Unit = {
            readSource(startup).foreach { status: ProbeStatus =>
              if (status.code == ProbeStatuses.OK_STATUS) {
                Lock.withLock(lock) { startupComplete = true }
                startupTimer.cancel()
              }
            }
          }
        },
        intervalMs,
        intervalMs
      )
      readinessTimer.schedule(
        new TimerTask {
          override def run(): Unit = {
            readSource(readiness).foreach { status: ProbeStatus =>
              Lock.withLock(lock) {
                readinessStatus = status
                updatedAtNanos = System.nanoTime()
              }
            }
          }
        },
        intervalMs,
        intervalMs
      )
    }

    /** Returns a startup-gated cache snapshot, failing closed after three polling intervals. */
    def currentReadiness: ProbeStatus = Lock.withLock(lock) {
      if (!startupComplete) {
        ProbeStatuses.notYetReady("startup")
      } else if (System.nanoTime() - updatedAtNanos > staleAfterNanos) {
        ProbeStatuses.needRestart("readiness poller")
      } else {
        readinessStatus
      }
    }

    /** Cancels future polls. In-flight source calls can only update this retired registration. */
    def stop(): Unit = {
      startupTimer.cancel()
      readinessTimer.cancel()
    }

    /** Keeps the old cache on failure so repeated exceptions eventually cause stale readiness. */
    private def readSource(source: ProbeStatusSource): Option[ProbeStatus] = {
      try Some(source.getStatus)
      catch { case NonFatal(_) => None }
    }
  }
}
