package com.databricks.common.status

import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.concurrent.locks.ReentrantLock

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import com.databricks.common.util.Lock
import com.databricks.testing.DatabricksTest

/** Verifies OSS readiness registration, startup gating, and isolation from blocked sources. */
class ProbeStatusManagerSuite extends DatabricksTest with Eventually {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(10, Millis))

  /** A source whose status can be changed independently of the polling thread. */
  private class MutableSource(initial: ProbeStatus) extends ProbeStatusSource {
    private val lock: ReentrantLock = new ReentrantLock()
    private var status: ProbeStatus = initial // Guarded by lock.
    private val firstRead: CountDownLatch = new CountDownLatch(1)

    /** Sets the status returned by subsequent polls. */
    def set(next: ProbeStatus): Unit = Lock.withLock(lock) { status = next }

    /** Waits until the polling thread has read this source. */
    def awaitPolled(): Unit = assert(firstRead.await(5, TimeUnit.SECONDS))

    override def getStatus: ProbeStatus = {
      val snapshot: ProbeStatus = Lock.withLock(lock) { status }
      firstRead.countDown()
      snapshot
    }
  }

  /** Registers sources with a short polling interval. */
  private def start(startup: ProbeStatusSource, readiness: ProbeStatusSource): Unit = {
    ProbeStatusManager.withPollIntervalMsForTesting(50L) {
      ProbeStatusManager.start(startup, readiness, startup)
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    ProbeStatusManager.resetForTesting()
  }

  override def afterEach(): Unit = {
    ProbeStatusManager.resetForTesting()
    super.afterEach()
  }

  test("Readiness requires registration and successful startup, which is latched") {
    // Test plan: Keep startup pending while readiness is OK, then complete startup and verify
    // readiness can transition independently without a later startup failure closing the gate.
    assert(!ProbeStatusManager.isStarted)
    assert(ProbeStatusManager.currentReadiness.code == ProbeStatuses.NOT_YET_READY_STATUS)
    val startup: MutableSource = new MutableSource(ProbeStatuses.notYetReady("startup"))
    val readiness: MutableSource = new MutableSource(ProbeStatuses.ok("service"))
    start(startup, readiness)
    assert(ProbeStatusManager.isStarted)
    readiness.awaitPolled()
    assert(!ProbeStatusManager.isReady)
    startup.set(ProbeStatuses.ok("startup"))
    eventually { assert(ProbeStatusManager.isReady) }
    startup.set(ProbeStatuses.notYetReady("startup"))
    readiness.set(ProbeStatuses.notYetReady("service"))
    eventually { assert(ProbeStatusManager.currentReadiness == readiness.getStatus) }
    readiness.set(ProbeStatus(204, "ready"))
    eventually { assert(ProbeStatusManager.isReady) }
  }

  test("The first registration wins, and reset permits new sources") {
    // Test plan: A second start cannot replace the first caller's unreadiness. Reset then allows
    // that second source to become authoritative.
    val ok: MutableSource = new MutableSource(ProbeStatuses.ok("service"))
    val notReady: MutableSource = new MutableSource(ProbeStatuses.notYetReady("service"))
    start(ok, notReady)
    start(ok, ok)
    eventually { assert(ProbeStatusManager.currentReadiness == notReady.getStatus) }
    ProbeStatusManager.resetForTesting()
    assert(!ProbeStatusManager.isStarted)
    assert(!ProbeStatusManager.isReady)
    start(ok, ok)
    eventually { assert(ProbeStatusManager.isReady) }
  }

  test("A blocked source does not block readers and eventually fails stale readiness") {
    // Test plan: Cache OK, block the next poll, and verify a bounded read fails closed once the
    // cache expires. Releasing the source allows the same registration to recover.
    val blocked: CountDownLatch = new CountDownLatch(1)
    val release: CountDownLatch = new CountDownLatch(1)
    val shouldBlock: MutableSource = new MutableSource(ProbeStatuses.ok("control"))
    val source: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = {
        if (shouldBlock.getStatus.code != ProbeStatuses.OK_STATUS) {
          blocked.countDown()
          release.await()
        }
        ProbeStatuses.ok("service")
      }
    }
    val ok: MutableSource = new MutableSource(ProbeStatuses.ok("startup"))
    start(ok, source)
    try {
      eventually { assert(ProbeStatusManager.isReady) }
      shouldBlock.set(ProbeStatuses.notYetReady("control"))
      assert(blocked.await(5, TimeUnit.SECONDS))
      eventually {
        val read: CompletableFuture[ProbeStatus] =
          CompletableFuture.supplyAsync(() => ProbeStatusManager.currentReadiness)
        assert(read.get(1, TimeUnit.SECONDS).code == ProbeStatuses.NEED_RESTART_STATUS)
      }
      shouldBlock.set(ProbeStatuses.ok("control"))
      release.countDown()
      eventually { assert(ProbeStatusManager.isReady) }
    } finally {
      release.countDown()
    }
  }
}
