package com.databricks.dicer.assigner

import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.{ExecutorService, Executors}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.{V1ObjectMeta, V1Pod, V1PodCondition, V1PodStatus}
import io.prometheus.client.Collector.MetricFamilySamples.Sample
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.{
  AlertOwnerTeam,
  AssertionWaiter,
  CachingErrorCode,
  FakeSequentialExecutionContext,
  MetricUtils,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  Severity,
  ValueStreamCallback
}
import com.databricks.caching.util.MetricUtils.{ChangeTracker, SampleExtensions}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.testing.DatabricksTest

/** Tests for [[KubernetesMembershipChecker]]. */
class KubernetesMembershipCheckerSuite extends DatabricksTest with TestName {

  /** The Prometheus registry to read metric values from. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** The polling interval used across tests. */
  private val pollingInterval: FiniteDuration = 1.second

  /** The RPC port used to construct resource URIs in tests. */
  private val rpcPort: Int = 24500

  // Tests do not stop checkers after each test. This is safe because each test uses unique
  // namespace/appName via getSafeName, so orphaned checkers' polls write to separate metric
  // label combinations and do not interfere with other tests.

  /** Shared SEC for all tests. Each test uses unique namespace/appName for isolation. */
  private val sec: FakeSequentialExecutionContext = FakeSequentialExecutionContext.create("shared")

  /** Noop proto logger used in tests since proto logging is not under test here. */
  private val noopProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(sec)

  /** Shared fake server for all tests. Started once at class construction time. */
  private val fakeServer: FakeKubernetesServer = FakeKubernetesServer.createAndStart(sec)

  override def afterAll(): Unit = {
    fakeServer.stop()
    super.afterAll()
  }

  /** Creates a random assigner UUID for test isolation. */
  private def createAssignerUuid(): UUID = {
    UUID.randomUUID()
  }

  /** Builds a [[V1Pod]] with the given UID set on its metadata. */
  private def buildPod(uid: String): V1Pod = {
    new V1Pod().metadata(new V1ObjectMeta().uid(uid))
  }

  /** Builds a list of [[V1Pod]] instances from the given UIDs. */
  private def buildPods(uids: String*): List[V1Pod] = {
    uids.map(buildPod).toList
  }

  /**
   * Creates a [[V1Pod]] with a UID, IP address, and configurable Ready condition.
   * When [[isReady]] is true, the pod's conditions include a "Ready" / "True" entry so that the
   * checker's single-pass extraction produces a non-empty resource entry.
   */
  private def buildPodWithUri(
      uid: UUID,
      ip: String,
      isReady: Boolean = true,
      isTerminating: Boolean = false): V1Pod = {
    val readyCondition: V1PodCondition = new V1PodCondition()
    readyCondition.setType("Ready")
    readyCondition.setStatus(if (isReady) "True" else "False")
    val metadata: V1ObjectMeta = new V1ObjectMeta().uid(uid.toString)
    if (isTerminating) {
      metadata.setDeletionTimestamp(OffsetDateTime.now())
    }
    new V1Pod()
      .metadata(metadata)
      .status(new V1PodStatus().podIP(ip).conditions(List(readyCondition).asJava))
  }

  /**
   * A [[ValueStreamCallback]] that collects all received values for test assertions.
   *
   * PRECONDITION: Must be registered on the test's [[SequentialExecutionContext]].
   */
  private class CollectingCallback[T](sec: SequentialExecutionContext)
      extends ValueStreamCallback[T](sec) {
    val values: mutable.ListBuffer[T] = mutable.ListBuffer.empty
    override protected def onSuccess(value: T): Unit = {
      values.append(value)
    }
  }

  /** Builds a [[CoreV1Api]] backed by [[fakeServer]]. */
  private def buildCoreV1Api(): CoreV1Api =
    FakeKubernetesTestSupport.buildCoreV1Api(fakeServer)

  /**
   * Returns the sum of response counts across all podCount labels for the given namespace,
   * appName, and kubeContext. The counter is labeled by podCount, so we sum across all
   * matching series to get the total number of successful polls.
   */
  private def getResponseCount(namespace: String, appName: String, kubeContext: String): Double = {
    val labels: Map[String, String] =
      Map("namespace" -> namespace, "appName" -> appName, "kubeContext" -> kubeContext)
    val samples: Seq[Sample] = MetricUtils.getMetricSamples(
      registry,
      "dicer_assigner_k8s_list_pods_count_total"
    )
    samples.filter(_.matchesLabels(labels)).map(_.value).sum
  }

  /**
   * Returns the current value of the self-present gauge for the given namespace, appName, and
   * kubeContext.
   */
  private def getSelfPresentGauge(
      namespace: String,
      appName: String,
      kubeContext: String): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_k8s_list_pods_self_present",
      Map("namespace" -> namespace, "appName" -> appName, "kubeContext" -> kubeContext)
    )
  }

  /**
   * Returns the current value of the connection-healthy gauge for the given namespace and
   * appName.
   */
  private def getConnectionHealthyGauge(namespace: String, appName: String): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_k8s_connection_healthy_gauge",
      Map("namespace" -> namespace, "appName" -> appName)
    )
  }

  /**
   * Returns the current value of the connection-unhealthy counter for the given namespace and
   * appName.
   */
  private def getConnectionUnhealthyCount(namespace: String, appName: String): Double = {
    MetricUtils.getMetricValue(
      registry,
      "dicer_assigner_k8s_connection_unhealthy_total",
      Map("namespace" -> namespace, "appName" -> appName)
    )
  }

  /**
   * Returns the histogram observation count for the given namespace, appName, HTTP status
   * code, and kubeContext.
   */
  private def getLatencyHistogramCount(
      namespace: String,
      appName: String,
      statusCode: String,
      kubeContext: String): Int = {
    MetricUtils.getHistogramCount(
      registry,
      "dicer_assigner_k8s_list_pods_latency_millis",
      Map(
        "namespace" -> namespace,
        "appName" -> appName,
        "statusCode" -> statusCode,
        "kubeContext" -> kubeContext
      )
    )
  }

  /**
   * Returns the total number of latency histogram observations across all status codes for the
   * given namespace, appName, and kubeContext. This counts all polls (both successful and
   * failed) that completed their SEC callback.
   */
  private def getTotalLatencyCount(namespace: String, appName: String, kubeContext: String): Int = {
    MetricUtils.getHistogramCount(
      registry,
      "dicer_assigner_k8s_list_pods_latency_millis",
      Map("namespace" -> namespace, "appName" -> appName, "kubeContext" -> kubeContext)
    )
  }

  /**
   * Waits for a poll to complete end-to-end by checking that the latency histogram count change
   * has reached the expected value. This ensures the K8s client callback has been processed on the
   * SEC (including [[scheduleNextPoll]]), which is necessary before calling the next
   * `advanceBySync` to fire the subsequent poll.
   *
   * After this method returns, all metrics from the poll are settled and can be safely asserted.
   */
  private def awaitPollComplete(
      description: String,
      totalPolls: ChangeTracker[Int],
      expectedChange: Int,
      checkerSec: FakeSequentialExecutionContext = sec): Unit = {
    AssertionWaiter(description, ecOpt = Some(checkerSec)).await {
      assert(totalPolls.totalChange() >= expectedChange)
    }
  }

  /**
   * Creates a single-threaded SEC pool on a throwaway thread so that uncaught exceptions interrupt
   * this throwaway thread instead of the main test thread (note that the pool's exception handler
   * will interrupt the pool's creator thread when an exception is thrown). This lets us write tests
   * that validate SEC-scheduled methods, like `start` or `stop`, throw when their preconditions are
   * violated, without these thrown exceptions interrupting the main test thread itself.
   */
  private def createSecPoolOnThrowawayThread(poolName: String): SequentialExecutionContextPool = {
    val throwawayExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    try {
      val poolFuture: Future[SequentialExecutionContextPool] = Future {
        SequentialExecutionContextPool.create(
          poolName = poolName,
          numThreads = 1,
          alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
        )
      }(ExecutionContext.fromExecutor(throwawayExecutor))
      Await.result(poolFuture, Duration.Inf)
    } finally {
      throwawayExecutor.shutdown()
    }
  }

  test("Construction fails with invalid arguments") {
    // Test plan: Verify that constructing a KubernetesMembershipChecker with an empty namespace,
    // empty appName, or non-positive pollingInterval each throw IllegalArgumentException.
    // Uses a plain CoreV1Api since the constructor's require() checks never make HTTP calls.
    val coreV1Api: CoreV1Api = new CoreV1Api(new ApiClient())

    // Empty namespace.
    assertThrows[IllegalArgumentException] {
      new KubernetesMembershipChecker(
        sec,
        coreV1Api,
        createAssignerUuid(),
        namespace = "",
        appName = "my-app",
        pollingInterval = pollingInterval,
        rpcPort = rpcPort,
        kubeContextLabelOpt = None
      )
    }

    // Empty appName.
    assertThrows[IllegalArgumentException] {
      new KubernetesMembershipChecker(
        sec,
        coreV1Api,
        createAssignerUuid(),
        namespace = "my-ns",
        appName = "",
        pollingInterval = pollingInterval,
        rpcPort = rpcPort,
        kubeContextLabelOpt = None
      )
    }

    // Non-positive pollingInterval.
    assertThrows[IllegalArgumentException] {
      new KubernetesMembershipChecker(
        sec,
        coreV1Api,
        createAssignerUuid(),
        namespace = "my-ns",
        appName = "my-app",
        pollingInterval = 0.seconds,
        rpcPort = rpcPort,
        kubeContextLabelOpt = None
      )
    }

  }

  test("Start and stop lifecycle") {
    // Test plan: Verify that stopAsync prevents future polls. Start the checker, poll once, stop
    // it, advance time, and confirm the request count stabilizes (no unbounded polling after
    // stop). An in-flight poll may still fire after stop since stop only prevents future
    // scheduling — this is expected.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )

    checker.start(noopProtoLogger)

    sec.advanceBySync(pollingInterval)

    // Wait for the first poll's SEC callback to complete (not just the HTTP request).
    awaitPollComplete(
      "first poll completes",
      totalPolls,
      expectedChange = 1
    )

    // Stop the checker, then advance enough to drain any poll that was already
    // in-flight when we stopped, and snapshot the poll count once it has stablized.
    checker.stopAsync()
    sec.advanceBySync(pollingInterval)
    sec.advanceBySync(pollingInterval)
    val pollsAfterStop: Int = totalPolls.totalChange()

    // Stop prevents any further scheduling, so we expect that the poll count should not have
    // changed. Verify that advancing more intervals must not produce any new polls.
    sec.advanceBySync(pollingInterval)
    sec.advanceBySync(pollingInterval)
    assertResult(pollsAfterStop)(totalPolls.totalChange())
  }

  test("Calling start throws when the checker has already been started or stopped") {
    // Test plan: Verify that start may only be called once on a newly constructed checker. Calling
    // it again while the checker is running, or after it has been stopped, throws an
    // exception. Since start runs on the SEC, the exception is raised on the SEC worker
    // rather than propagating to the caller, so we observe the UNCAUGHT_SEC_POOL_ERROR alert that
    // the checker's SEC pool fires. Do this by starting a checker,
    // letting a poll complete, then calling start again while it is running (we expect one alert to
    // fire here), then stopping it and calling start once more (we expect a second alert to fire
    // here).
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    // Create the SEC pool on a throwaway thread so uncaught exceptions (e.g. caused by calling
    // `start` when the checker is in an invalid state) interrupt the throwaway thread instead of
    // the main test thread.
    val checkerPoolName: String = "test-invalid-start-sec-pool"
    val checkerPool: SequentialExecutionContextPool =
      createSecPoolOnThrowawayThread(checkerPoolName)
    val checkerSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(name = checkerPoolName, pool = checkerPool)
    val checkerProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(checkerSec)

    val uncaughtSecPoolErrorAlerts: ChangeTracker[Int] = ChangeTracker[Int] { () =>
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.UNCAUGHT_SEC_POOL_ERROR(AlertOwnerTeam.CachingTeam),
        prefix = checkerPoolName
      )
    }

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      checkerSec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )

    // Start the checker and let one poll complete.
    checker.start(checkerProtoLogger)
    checkerSec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "first poll completes",
      totalPolls,
      expectedChange = 1,
      checkerSec = checkerSec
    )

    // Start again while it is running (we expect one alert to fire here).
    checker.start(checkerProtoLogger)
    AssertionWaiter("Calling start on an already running checker throws", ecOpt = Some(checkerSec))
      .await {
        assertResult(1)(uncaughtSecPoolErrorAlerts.totalChange())
      }

    // Stop the checker.
    checker.stopAsync()

    // Start again after it has been stopped (we expect a second alert to fire here).
    checker.start(checkerProtoLogger)
    AssertionWaiter("Calling start on a stopped checker throws", ecOpt = Some(checkerSec)).await {
      assertResult(2)(uncaughtSecPoolErrorAlerts.totalChange())
    }
  }

  test("Calling stop throws when the checker has not been started but is idempotent once stopped") {
    // Test plan: Verify that stopAsync throws if called before the checker has been started, but is
    // idempotent once the checker is running (a second stop after a stop is a harmless no-op).
    // Since stopAsync runs on the SEC, an exception is raised on the SEC worker rather than
    // propagating to the caller, so we observe the UNCAUGHT_SEC_POOL_ERROR alert that the checker's
    // SEC pool fires. Do this by calling stopAsync on a newly created checker that hasn't been
    // started yet (we expect one alert to fire here), then starting it, letting a poll complete,
    // stopping it, then calling stopAsync once more and confirming no further alert fires.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    // Create the SEC pool on a throwaway thread so uncaught exceptions (e.g. caused by calling
    // `stopAsync` when the checker is in an invalid state) interrupt the throwaway thread instead
    // of the main test thread.
    val checkerPoolName: String = "test-invalid-stop-sec-pool"
    val checkerPool: SequentialExecutionContextPool =
      createSecPoolOnThrowawayThread(checkerPoolName)
    val checkerSec: FakeSequentialExecutionContext =
      FakeSequentialExecutionContext.create(name = checkerPoolName, pool = checkerPool)
    val checkerProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(checkerSec)

    val uncaughtSecPoolErrorAlerts: ChangeTracker[Int] = ChangeTracker[Int] { () =>
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.UNCAUGHT_SEC_POOL_ERROR(AlertOwnerTeam.CachingTeam),
        prefix = checkerPoolName
      )
    }

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      checkerSec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )

    // Stop the checker before it has been started (we expect one alert to fire here).
    checker.stopAsync()
    AssertionWaiter(
      "Calling stopAsync on a checker that has not been started throws",
      ecOpt = Some(checkerSec)
    ).await {
      assertResult(1)(uncaughtSecPoolErrorAlerts.totalChange())
    }

    // Start the checker, let one poll complete, then stop it.
    checker.start(checkerProtoLogger)
    checkerSec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "first poll completes",
      totalPolls,
      expectedChange = 1,
      checkerSec = checkerSec
    )
    checker.stopAsync()

    // Stop again after it has already been stopped: this is idempotent, so no second alert fires.
    checker.stopAsync()
    checkerSec.advanceBySync(pollingInterval)
    AssertionWaiter(
      "Calling stopAsync on an already-stopped checker is a no-op",
      ecOpt = Some(checkerSec)
    ).await {
      assertResult(1)(uncaughtSecPoolErrorAlerts.totalChange())
    }
  }

  test("No spurious polls after calling stop") {
    // Test plan: Verify that the checker ignores scheduled polls that fire after the checker has
    // been stopped. Do this by starting a checker, letting a poll complete (which schedules the
    // next poll), stopping the checker, advancing past several poll intervals so that the scheduled
    // poll would fire, and asserting that no further polls are recorded.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )

    checker.start(noopProtoLogger)
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("first poll completes", totalPolls, expectedChange = 1)

    // Stop the checker, then advance past several intervals so the scheduled poll fires.
    checker.stopAsync()
    sec.advanceBySync(pollingInterval)
    sec.advanceBySync(pollingInterval)
    sec.advanceBySync(pollingInterval)
    sec.advanceBySync(pollingInterval)

    // Verify that the scheduled poll(s) that fired after stopping the checker were ignored/skipped
    // by checking that the total poll count is still 1 (i.e. it only reflects the first poll that
    // completed before we stopped the checker).
    assertResult(1)(totalPolls.totalChange())
  }

  test("Successful poll records all metrics correctly") {
    // Test plan: Verify that successful polls record latency, response size, and self-present
    // metrics. Start the checker, advance 3 intervals to confirm polling cadence, then swap the
    // pod list to include the assigner's UUID (self-present = 1.0), swap again to exclude it
    // (self-present = 0.0), swap to an empty pod list, swap to a list containing a pod with
    // null metadata, and finally test with a V1PodList whose items field is null. Each phase
    // verifies the expected metric values.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val assignerUuid: UUID = createAssignerUuid()

    val successLatencyCount: ChangeTracker[Int] = ChangeTracker[Int](
      () => getLatencyHistogramCount(namespace, appName, statusCode = "200", "")
    )
    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )
    val responseCount: ChangeTracker[Double] = ChangeTracker[Double](
      () => getResponseCount(namespace, appName, "")
    )

    // Start with 2 pods (neither is the assigner).
    fakeServer.setPods(
      namespace,
      appName,
      Some(buildPods(UUID.randomUUID().toString, UUID.randomUUID().toString))
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      assignerUuid,
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    checker.start(noopProtoLogger)

    // Advance 3 intervals to verify polling cadence and response size metric.
    for (i: Int <- 1 to 3) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(
        s"poll $i complete",
        totalPolls,
        expectedChange = i
      )
    }

    assert(responseCount.totalChange() == 3.0)
    assert(successLatencyCount.totalChange() == 3)

    // Swap to a pod list that includes the assigner's UUID.
    fakeServer.setPods(
      namespace,
      appName,
      Some(
        buildPods(
          UUID.randomUUID().toString,
          assignerUuid.toString,
          UUID.randomUUID().toString
        )
      )
    )
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "poll 4 complete",
      totalPolls,
      expectedChange = 4
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 1.0)
    assert(responseCount.totalChange() == 4.0)

    // Swap to a pod list without the assigner UUID -- self-present flips to 0.
    fakeServer.setPods(namespace, appName, Some(buildPods(UUID.randomUUID().toString)))
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "poll 5 complete",
      totalPolls,
      expectedChange = 5
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 0.0)

    // Swap to an empty pod list to verify graceful handling.
    fakeServer.setPods(namespace, appName, Some(List.empty))
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "poll 6 complete",
      totalPolls,
      expectedChange = 6
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 0.0)
    assert(responseCount.totalChange() == 6.0)

    // Swap to a pod list containing a pod with null metadata -- should not crash.
    fakeServer.setPods(
      namespace,
      appName,
      Some(
        List(
          new V1Pod(),
          new V1Pod().metadata(new V1ObjectMeta().uid(UUID.randomUUID().toString))
        )
      )
    )
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "poll 7 complete",
      totalPolls,
      expectedChange = 7
    )
    assert(responseCount.totalChange() == 7.0)
    assert(getSelfPresentGauge(namespace, appName, "") == 0.0)

    // Test with null items (getItems returns null).
    fakeServer.setPods(namespace, appName, pods = None)
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("poll 8 complete", totalPolls, expectedChange = 8)
    assert(responseCount.totalChange() == 8.0)
    assert(getSelfPresentGauge(namespace, appName, "") == 0.0)

    assert(successLatencyCount.totalChange() == 8)
  }

  test("Failed poll records failure latency, resets self-present, and continues polling") {
    // Test plan: Verify that when the K8s API call fails, the failure latency histogram is
    // incremented, the self-present gauge is reset to 0.0, and polling continues on the next
    // interval. First do a successful poll to set self-present to 1.0, then switch to failure
    // mode and verify the gauge resets, then switch back to success and verify metrics recover.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val assignerUuid: UUID = createAssignerUuid()

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )
    val responseCount: ChangeTracker[Double] = ChangeTracker[Double](
      () => getResponseCount(namespace, appName, "")
    )
    // With the real HTTP stack, the K8s client reports the actual HTTP status code (e.g. "503")
    // rather than the synthetic "0" that the old FakeCoreV1Api used.
    val failureCount: ChangeTracker[Int] = ChangeTracker[Int](
      () => getLatencyHistogramCount(namespace, appName, statusCode = "503", "")
    )

    // Start with a successful poll that sets self-present to 1.0.
    fakeServer.setPods(namespace, appName, Some(buildPods(assignerUuid.toString)))

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      assignerUuid,
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    checker.start(noopProtoLogger)

    sec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "initial successful poll",
      totalPolls,
      expectedChange = 1
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 1.0)

    // Switch to failure mode (503 Service Unavailable).
    fakeServer.setErrorResponse(namespace, appName, statusCode = 503)

    // First failed poll resets self-present to 0.0.
    sec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "first failure poll",
      totalPolls,
      expectedChange = 2
    )
    assert(failureCount.totalChange() == 1)
    assert(getSelfPresentGauge(namespace, appName, "") == 0.0)

    // Second poll also fails -- verify polling continues despite errors.
    sec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "second failure poll",
      totalPolls,
      expectedChange = 3
    )
    assert(failureCount.totalChange() == 2)

    // Switch back to success mode -- metrics should recover.
    fakeServer.setPods(namespace, appName, Some(buildPods(assignerUuid.toString)))
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "recovery poll",
      totalPolls,
      expectedChange = 4
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 1.0)
    // Counter is 2: one from the initial success poll and one from this recovery poll.
    assert(responseCount.totalChange() == 2.0)
  }

  test("Error response from K8s API continues polling") {
    // Test plan: Verify that when the K8s API returns an HTTP error (500 Internal Server Error),
    // the checker catches the failure and continues polling on the next interval. This replaces
    // the previous "direct RuntimeException" test — with a real HTTP stack, errors arrive through
    // the K8s client's callback rather than as direct throws from listNamespacedPodAsync.
    // Start the checker with error mode, advance an interval, verify the request was made.
    // Then switch to success mode, advance again, and verify the next poll succeeds.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val assignerUuid: UUID = createAssignerUuid()
    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )
    val responseCount: ChangeTracker[Double] = ChangeTracker[Double](
      () => getResponseCount(namespace, appName, "")
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      assignerUuid,
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )

    // Configure the fake server to return an error before starting.
    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    checker.start(noopProtoLogger)

    // First poll: K8s API returns 500.
    sec.advanceBySync(pollingInterval)
    awaitPollComplete(
      "error poll",
      totalPolls,
      expectedChange = 1
    )

    // Switch to success mode and verify that the polling loop recovered.
    fakeServer.setPods(namespace, appName, Some(buildPods(assignerUuid.toString)))
    sec.advanceBySync(pollingInterval)

    awaitPollComplete(
      "recovery after error",
      totalPolls,
      expectedChange = 2
    )
    assert(getSelfPresentGauge(namespace, appName, "") == 1.0)
    assert(responseCount.totalChange() == 1.0)
  }

  test("Successful polls deliver resources and publish health true on the first poll") {
    // Test plan: Start the checker with pods (including IP and container port) configured on
    // the fake server. Verify that the watch callback receives a non-empty resource set via the
    // full HTTP integration path, that the resource URI has the correct scheme, host, and port,
    // and that connectionHealthCell publishes true once the first (successful) poll runs (the
    // cell is unpublished until then).
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podUid: UUID = UUID.randomUUID()
    val podIp: String = "10.0.0.1"

    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, podIp))))

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)

    checker.watch(resourcesCb)
    checker.connectionHealthCell.watch(healthCb)

    checker.start(noopProtoLogger)
    sec.advanceBySync(pollingInterval)

    // Wait for the resource delivery to propagate through the full path:
    // FakeKubernetesServer → checker → watch cell.
    AssertionWaiter("first resource delivery", ecOpt = Some(sec)).await {
      assert(resourcesCb.values.nonEmpty)
      assert(healthCb.values.nonEmpty)
    }

    // Verify the resource URI has the correct scheme, host, and port — consistent with how
    // SliceletImpl constructs its own URI (e.g. "https://10.0.0.1:24500").
    val uri: URI = resourcesCb.values.last.resources(podUid).uri
    assertResult("https")(uri.getScheme)
    assertResult(podIp)(uri.getHost)
    assertResult(rpcPort)(uri.getPort)

    // Connection health should be true and never have flipped to false.
    assert(healthCb.values.contains(true))
    assert(healthCb.values.forall(_ == true))
    assert(getConnectionUnhealthyCount(namespace, appName) == 0.0)

    checker.stopAsync()
  }

  test(
    "Each successful poll publishes a fresh VersionedResourceSet with a strictly " +
    "increasing ordering token"
  ) {
    // Test plan: Verify that every successful poll publishes a fresh VersionedResourceSet
    // tagged with a strictly increasing ordering token, regardless of whether the resources
    // changed since the previous publish (i.e. the checker does not deduplicate). Drive 2 polls
    // with pod set A then 2 polls with pod set B; assert four publishes carrying versions
    // "0", "1", "2", "3" with resources reflecting the per-phase membership.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podAUid: UUID = UUID.randomUUID()
    val podBUid: UUID = UUID.randomUUID()
    val podsA: List[V1Pod] = List(buildPodWithUri(podAUid, "10.0.0.42"))
    val podsB: List[V1Pod] = List(buildPodWithUri(podBUid, "10.0.0.43"))

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setPods(namespace, appName, Some(podsA))

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    checker.watch(resourcesCb)
    checker.start(noopProtoLogger)

    def advanceClockAndAwaitPolls(count: Int): Unit = {
      val baseline: Int = totalPolls.totalChange()
      for (i: Int <- 1 to count) {
        sec.advanceBySync(pollingInterval)
        awaitPollComplete(s"poll $i complete", totalPolls, expectedChange = baseline + i)
      }
    }

    // Phase 1: 2 polls with pod set A — expect 2 publishes, both with {A}.
    advanceClockAndAwaitPolls(2)
    // Phase 2: switch to pod set B and run 2 polls — expect 2 more publishes, both with {B}.
    fakeServer.setPods(namespace, appName, Some(podsB))
    advanceClockAndAwaitPolls(2)

    AssertionWaiter("all resource publishes delivered", ecOpt = Some(sec)).await {
      assert(resourcesCb.values.size == 4)
    }
    val publishedValues: Seq[VersionedResourceSet] = resourcesCb.values.toSeq

    // Versions are the exact strings "0", "1", "2", "3": initialized to 0L, advanced after
    // each publish, no dedup so every poll bumps.
    assertResult(Seq("0", "1", "2", "3"))(publishedValues.map(_.version.value))

    // Resources reflect the pod set seen at each poll.
    assertResult(Set(podAUid))(publishedValues(0).resources.keySet)
    assertResult(Set(podAUid))(publishedValues(1).resources.keySet)
    assertResult(Set(podBUid))(publishedValues(2).resources.keySet)
    assertResult(Set(podBUid))(publishedValues(3).resources.keySet)

    checker.stopAsync()
  }

  test("Consecutive failures trigger unhealthy connection") {
    // Test plan: Start the checker with the fake server returning errors. After 3 consecutive
    // failures (the failure threshold), verify that connectionHealthCell transitions to false.
    // No resources are delivered because every poll failed (not because of health state).
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)

    checker.watch(resourcesCb)
    checker.connectionHealthCell.watch(healthCb)

    checker.start(noopProtoLogger)

    // Advance through 3 polling intervals to accumulate 3 consecutive failures,
    // reaching the failure threshold and transitioning to unhealthy.
    for (i: Int <- 1 to 3) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    AssertionWaiter("connection unhealthy", ecOpt = Some(sec)).await {
      assert(healthCb.values.contains(false))
    }

    // No resources were delivered because every poll failed (not gated by health).
    assert(resourcesCb.values.isEmpty)
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    checker.stopAsync()
  }

  test("Recovery after failure restores health; resources delivered while still unhealthy") {
    // Test plan: Drive the connection unhealthy with 3 consecutive error responses, then switch
    // to returning pods. Verify that resources are delivered on the very first successful poll
    // (while still unhealthy), and that after 3 consecutive successes the connection health
    // transitions back to true.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)

    checker.watch(resourcesCb)
    checker.connectionHealthCell.watch(healthCb)

    checker.start(noopProtoLogger)

    // Three consecutive failures to transition from healthy to unhealthy.
    for (i: Int <- 1 to 3) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    AssertionWaiter("connection becomes unhealthy", ecOpt = Some(sec)).await {
      assert(healthCb.values.contains(false))
    }

    // Switch the fake server to return pods for recovery.
    val podUid: UUID = UUID.randomUUID()
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.3"))))

    // First successful poll delivers resources even though connection is still unhealthy.
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("first success while unhealthy", totalPolls, expectedChange = 4)

    AssertionWaiter("resources delivered while unhealthy", ecOpt = Some(sec)).await {
      assert(resourcesCb.values.nonEmpty)
    }
    // Health has not yet recovered (need 3 consecutive successes, only 1 so far).
    assert(healthCb.values.last == false)

    // Two more successes to complete recovery.
    for (i: Int <- 5 to 6) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"recovery poll $i", totalPolls, expectedChange = i)
    }

    AssertionWaiter("connection recovers", ecOpt = Some(sec)).await {
      assert(healthCb.values.last == true)
    }
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    checker.stopAsync()
  }

  test("Connection-healthy gauge mirrors hysteresis state across transitions") {
    // Test plan: Verify that the connection-healthy gauge and unhealthy counter follow the
    // hysteresis monitor's state across two full healthy→unhealthy→healthy cycles. The gauge
    // must be 1.0 after the first (successful) poll resolves the verdict to healthy, 0.0 after
    // three consecutive failures, 1.0 after three consecutive successes, then 0.0 again after
    // another three failures, and 1.0 once more after another three successes. The unhealthy
    // counter must increment by 1 on each transition to unhealthy (total 2 across both cycles).
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podUid: UUID = UUID.randomUUID()

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    // Start healthy so the first poll resolves the monitor's verdict (None → Some(true)).
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.5"))))

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    checker.start(noopProtoLogger)

    // The gauge is unpublished until the first poll runs. The first poll succeeds, resolving the
    // verdict to healthy so the gauge publishes 1.0.
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("success poll 1", totalPolls, expectedChange = 1)
    AssertionWaiter("gauge published healthy after first poll", ecOpt = Some(sec)).await {
      assert(getConnectionHealthyGauge(namespace, appName) == 1.0)
    }

    // Three consecutive failures reach the threshold, transitioning to unhealthy.
    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)
    for (i: Int <- 2 to 4) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }
    AssertionWaiter("gauge reflects unhealthy state", ecOpt = Some(sec)).await {
      assert(getConnectionHealthyGauge(namespace, appName) == 0.0)
    }
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    // Three consecutive successes recover the connection and the gauge follows.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.5"))))
    for (i: Int <- 5 to 7) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"recovery poll $i", totalPolls, expectedChange = i)
    }
    AssertionWaiter("gauge reflects recovered state", ecOpt = Some(sec)).await {
      assert(getConnectionHealthyGauge(namespace, appName) == 1.0)
    }
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    // Second cycle: three more consecutive failures transition to unhealthy again.
    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)
    for (i: Int <- 8 to 10) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"second failure poll $i", totalPolls, expectedChange = i)
    }
    AssertionWaiter("gauge reflects second unhealthy state", ecOpt = Some(sec)).await {
      assert(getConnectionHealthyGauge(namespace, appName) == 0.0)
    }
    assert(getConnectionUnhealthyCount(namespace, appName) == 2.0)

    // Three more consecutive successes recover the connection a second time.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.5"))))
    for (i: Int <- 11 to 13) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"second recovery poll $i", totalPolls, expectedChange = i)
    }
    AssertionWaiter("gauge reflects second recovered state", ecOpt = Some(sec)).await {
      assert(getConnectionHealthyGauge(namespace, appName) == 1.0)
    }
    assert(getConnectionUnhealthyCount(namespace, appName) == 2.0)

    checker.stopAsync()
  }

  test("Failures below threshold leave connection health unknown") {
    // Test plan: Start the checker with the fake server returning errors so no poll ever succeeds.
    // After 2 consecutive failures (one fewer than the failure threshold of 3), verify that the
    // connection health verdict is still unknown — the cell was never published (a never-connected
    // pod does not resolve to healthy or unhealthy below the failure threshold).
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)
    checker.connectionHealthCell.watch(healthCb)
    checker.start(noopProtoLogger)

    // Two failures — one below the threshold of 3.
    for (i: Int <- 1 to 2) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    // Health is still unknown: below the failure threshold, a never-connected pod publishes
    // nothing, so the callback received no value and never transitioned to unhealthy.
    assert(healthCb.values.isEmpty)
    assert(getConnectionUnhealthyCount(namespace, appName) == 0.0)

    checker.stopAsync()
  }

  test("Success while healthy resets failure counter") {
    // Test plan: Accumulate 2 consecutive failures (below the threshold of 3), then interject
    // a successful poll to reset the failure counter, then accumulate 2 more failures. Verify
    // the connection never transitions to unhealthy because the success reset the counter.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podUid: UUID = UUID.randomUUID()

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)
    checker.connectionHealthCell.watch(healthCb)
    checker.start(noopProtoLogger)

    // Two failures.
    for (i: Int <- 1 to 2) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    // One success resets the failure counter.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.1"))))
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("success poll", totalPolls, expectedChange = 3)

    // Two more failures — still below threshold since counter was reset.
    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)
    for (i: Int <- 4 to 5) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    // Never transitioned to unhealthy.
    assert(healthCb.values.forall(_ == true))
    assert(getConnectionUnhealthyCount(namespace, appName) == 0.0)

    checker.stopAsync()
  }

  test("Partial recovery does not restore health") {
    // Test plan: Drive the connection unhealthy with 3 consecutive failures, then send 2
    // consecutive successes (one fewer than the recovery threshold of 3). Verify the health
    // callback's last value is still false.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podUid: UUID = UUID.randomUUID()

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)
    checker.connectionHealthCell.watch(healthCb)
    checker.start(noopProtoLogger)

    // Three consecutive failures to transition to unhealthy.
    for (i: Int <- 1 to 3) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    AssertionWaiter("connection becomes unhealthy", ecOpt = Some(sec)).await {
      assert(healthCb.values.contains(false))
    }

    // Two successes — one below the recovery threshold of 3.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.1"))))
    for (i: Int <- 4 to 5) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"recovery poll $i", totalPolls, expectedChange = i)
    }

    // Still unhealthy — last health transition was to false.
    assert(healthCb.values.last == false)
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    checker.stopAsync()
  }

  test("Failure while unhealthy resets recovery counter") {
    // Test plan: Drive the connection unhealthy with 3 consecutive failures, begin recovery
    // with 2 consecutive successes, then interrupt with a failure to reset the recovery counter.
    // Send 2 more successes (below threshold again). Verify health remains false.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podUid: UUID = UUID.randomUUID()

    val totalPolls: ChangeTracker[Int] = ChangeTracker[Int](
      () => getTotalLatencyCount(namespace, appName, "")
    )

    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val healthCb: CollectingCallback[Boolean] = new CollectingCallback(sec)
    checker.connectionHealthCell.watch(healthCb)
    checker.start(noopProtoLogger)

    // Three consecutive failures to transition to unhealthy.
    for (i: Int <- 1 to 3) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"failure poll $i", totalPolls, expectedChange = i)
    }

    AssertionWaiter("connection becomes unhealthy", ecOpt = Some(sec)).await {
      assert(healthCb.values.contains(false))
    }

    // Two successes toward recovery.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.1"))))
    for (i: Int <- 4 to 5) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"recovery poll $i", totalPolls, expectedChange = i)
    }

    // One failure resets the recovery counter.
    fakeServer.setErrorResponse(namespace, appName, statusCode = 500)
    sec.advanceBySync(pollingInterval)
    awaitPollComplete("interrupting failure", totalPolls, expectedChange = 6)

    // Two more successes — still below threshold since counter was reset.
    fakeServer.setPods(namespace, appName, Some(List(buildPodWithUri(podUid, "10.0.0.1"))))
    for (i: Int <- 7 to 8) {
      sec.advanceBySync(pollingInterval)
      awaitPollComplete(s"recovery poll $i", totalPolls, expectedChange = i)
    }

    // Still unhealthy — recovery counter was reset by the interleaved failure.
    assert(healthCb.values.last == false)
    assert(getConnectionUnhealthyCount(namespace, appName) == 1.0)

    checker.stopAsync()
  }

  test("Non-ready pods are excluded from resource delivery") {
    // Test plan: Verify that readiness filtering tracks pod state changes across polls.
    // Phase 1: both pods are ready — resource set contains both.
    // Phase 2: podA becomes not ready — resource set contains only podB.
    // Phase 3: podA returns to ready — resource set contains both again.
    // Phase 4: podB becomes not ready — resource set contains only podA.
    // Phase 5: podB returns to ready — resource set contains both again.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podAUid: UUID = UUID.randomUUID()
    val podBUid: UUID = UUID.randomUUID()
    val podAReady: V1Pod = buildPodWithUri(podAUid, "10.0.0.1")
    val podANotReady: V1Pod = buildPodWithUri(podAUid, "10.0.0.1", isReady = false)
    val podBReady: V1Pod = buildPodWithUri(podBUid, "10.0.0.2")
    val podBNotReady: V1Pod = buildPodWithUri(podBUid, "10.0.0.2", isReady = false)

    // Phase 1: both pods are ready.
    fakeServer.setPods(namespace, appName, Some(List(podAReady, podBReady)))

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    checker.watch(resourcesCb)
    checker.start(noopProtoLogger)

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 1 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podAUid, podBUid))(resourcesCb.values.last.resources.keySet)
    }

    // Phase 2: podA becomes not ready.
    fakeServer.setPods(namespace, appName, Some(List(podANotReady, podBReady)))

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 2 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podBUid))(resourcesCb.values.last.resources.keySet)
    }

    // Phase 3: podA returns to ready.
    fakeServer.setPods(namespace, appName, Some(List(podAReady, podBReady)))

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 3 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podAUid, podBUid))(resourcesCb.values.last.resources.keySet)
    }

    // Phase 4: podB becomes not ready.
    fakeServer.setPods(namespace, appName, Some(List(podAReady, podBNotReady)))

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 4 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podAUid))(resourcesCb.values.last.resources.keySet)
    }

    // Phase 5: podB returns to ready.
    fakeServer.setPods(namespace, appName, Some(List(podAReady, podBReady)))

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 5 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podAUid, podBUid))(resourcesCb.values.last.resources.keySet)
    }

    checker.stopAsync()
  }

  test("Terminating pods are excluded from resource delivery") {
    // Test plan: Verify that pods with a deletionTimestamp set are excluded from the resource set.
    // Phase 1: both pods are ready and not terminating — resource set contains both.
    // Phase 2: podA becomes terminating — resource set contains only podB.
    val namespace: String = "ns-" + getSafeName
    val appName: String = "app-" + getSafeName
    val podAUid: UUID = UUID.randomUUID()
    val podBUid: UUID = UUID.randomUUID()

    // Phase 1: both pods are ready and not terminating.
    fakeServer.setPods(
      namespace,
      appName,
      Some(
        List(
          buildPodWithUri(podAUid, "10.0.0.1"),
          buildPodWithUri(podBUid, "10.0.0.2")
        )
      )
    )

    val checker: KubernetesMembershipChecker = new KubernetesMembershipChecker(
      sec,
      buildCoreV1Api(),
      createAssignerUuid(),
      namespace = namespace,
      appName = appName,
      pollingInterval = pollingInterval,
      rpcPort = rpcPort,
      kubeContextLabelOpt = None
    )
    val resourcesCb: CollectingCallback[VersionedResourceSet] = new CollectingCallback(sec)
    checker.watch(resourcesCb)
    checker.start(noopProtoLogger)

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 1 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podAUid, podBUid))(resourcesCb.values.last.resources.keySet)
    }

    // Phase 2: podA becomes terminating (deletionTimestamp set).
    fakeServer.setPods(
      namespace,
      appName,
      Some(
        List(
          buildPodWithUri(podAUid, "10.0.0.1", isTerminating = true),
          buildPodWithUri(podBUid, "10.0.0.2")
        )
      )
    )

    sec.advanceBySync(pollingInterval)

    AssertionWaiter("phase 2 resource delivery", ecOpt = Some(sec)).await {
      assertResult(Set(podBUid))(resourcesCb.values.last.resources.keySet)
    }

    checker.stopAsync()
  }

  // TODO: The try/catch NonFatal block in poll() (production code) cannot be exercised through
  // the HTTP fake. Consider testing with a CoreV1Api backed by an invalid ApiClient (e.g.,
  // pointing at a closed port) to trigger a direct throw from listNamespacedPodAsync.
}
