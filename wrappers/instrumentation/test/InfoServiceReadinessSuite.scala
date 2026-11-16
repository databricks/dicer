package com.databricks.instrumentation.test

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import com.databricks.common.status.{
  ProbeStatus,
  ProbeStatusManager,
  ProbeStatuses,
  ProbeStatusSource
}
import com.databricks.common.util.Lock
import com.databricks.common.web.InfoService
import com.databricks.testing.DatabricksTest

/** Verifies that OSS HTTP probes and Slicelets use the same registered readiness source. */
class InfoServiceReadinessSuite extends DatabricksTest with Eventually {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(10, Millis))

  override def beforeEach(): Unit = {
    super.beforeEach()
    ProbeStatusManager.resetForTesting()
  }

  override def afterEach(): Unit = {
    InfoService.stop()
    ProbeStatusManager.resetForTesting()
    super.afterEach()
  }

  /** Reads the HTTP readiness status code. */
  private def readinessCode(): Int = {
    val request: HttpRequest = HttpRequest
      .newBuilder()
      .uri(URI.create(s"http://localhost:${InfoService.getActivePort()}/ready"))
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build()
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
  }

  gridTest("HTTP and PSM share readiness")(Seq(false, true)) { preinitialized: Boolean =>
    // Test plan: Register a controllable source either through InfoService or before InfoService
    // starts. Observe both readiness transitions without HTTP traffic, then check /ready agrees.
    val lock: ReentrantLock = new ReentrantLock()
    var ready: Boolean = false // Guarded by lock.
    val source: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = Lock.withLock(lock) {
        if (ready) ProbeStatuses.ok("service") else ProbeStatuses.notYetReady("service")
      }
    }
    val alwaysOk: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = ProbeStatuses.ok("default")
    }
    ProbeStatusManager.withPollIntervalMsForTesting(50L) {
      if (preinitialized) ProbeStatusManager.start(alwaysOk, source, alwaysOk)
      InfoService.start(
        port = 0,
        readinessStatusSourceOpt = Some(if (preinitialized) alwaysOk else source)
      )
    }
    eventually { assert(ProbeStatusManager.currentReadiness == source.getStatus) }
    assert(readinessCode() == ProbeStatuses.NOT_YET_READY_STATUS)
    Lock.withLock(lock) { ready = true }
    eventually { assert(ProbeStatusManager.isReady) }
    assert(readinessCode() == ProbeStatuses.OK_STATUS)
    Lock.withLock(lock) { ready = false }
    eventually { assert(ProbeStatusManager.currentReadiness == source.getStatus) }
    assert(readinessCode() == ProbeStatuses.NOT_YET_READY_STATUS)
  }
}
