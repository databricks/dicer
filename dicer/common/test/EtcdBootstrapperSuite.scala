package com.databricks.dicer.common

import scala.concurrent.duration._

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.ServerTestUtils
import com.databricks.dicer.common.EtcdBootstrapper.{BootstrapRequest, ExitCode}
import com.databricks.caching.util.{EtcdTestEnvironment, EtcdClient, EtcdKeyValueMapper}
import com.databricks.caching.util.{MetricUtils, UnixTimeVersion}
import com.databricks.rpc.DatabricksServerWrapper
import com.databricks.testing.DatabricksTest

class EtcdBootstrapperSuite extends DatabricksTest {
  private[this] val dockerizedEtcd = EtcdTestEnvironment.create()

  private[this] val NAMESPACE = EtcdClient.KeyNamespace("test-namespace")
  private[this] val NON_LOOSE_INCARNATION: Incarnation = Incarnation(2)
  private[this] val LOOSE_INCARNATION: Incarnation = Incarnation.MIN

  private[this] val KNOWN_WATERMARK_INCARNATION_GAUGE_NAME =
    "dicer_etcd_bootstrapper_known_watermark_incarnation"
  private[this] val KNOWN_WATERMARK_NUMBER_GAUGE_NAME =
    "dicer_etcd_bootstrapper_known_watermark_number"

  override def beforeEach(): Unit = {
    dockerizedEtcd.deleteAll()
  }

  /**
   * Runs [[EtcdBootstrapper.bootstrapEtcdBlocking]] without the post-finish linger, so tests do not
   * sleep. The linger is production-only behavior that keeps the short-lived job alive for
   * scraping.
   */
  private def bootstrapEtcdBlockingNoLinger(requests: Seq[BootstrapRequest]): ExitCode =
    EtcdBootstrapper.bootstrapEtcdBlocking(requests, lingerAfterFinish = Duration.Zero)

  /**
   * Returns the value of the known-watermark `gaugeName` for the given `outcome` label and
   * `namespace`, or `None` if no such sample exists.
   */
  private def getKnownWatermarkGaugeValueOpt(
      gaugeName: String,
      outcome: String,
      namespace: EtcdClient.KeyNamespace): Option[Double] = {
    MetricUtils.getMetricValueOpt(
      CollectorRegistry.defaultRegistry,
      gaugeName,
      Map("outcome" -> outcome, "namespace" -> namespace.value)
    )
  }

  override def afterAll(): Unit = {
    dockerizedEtcd.close()
  }

  namedGridTest("success written when no high watermark already exists")(
    Map("non-loose incarnation" -> NON_LOOSE_INCARNATION, "loose incarnation" -> LOOSE_INCARNATION)
  ) { incarnation: Incarnation =>
    // Test plan: Verify `bootstrapEtcd` for returns success exit code when no high watermark exists
    // in the etcd cluster. Verify this by calling `bootstrapEtcd` with a against an empty etcd
    // cluster, then verifying the returned exit code is `SUCCESS` and the high watermark is written
    // to the etcd cluster.
    assert(
      dockerizedEtcd
        .getKey(EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE))
        .isEmpty
    )

    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(
              EtcdClient.Config(NAMESPACE)
            ),
            incarnation
          )
        )
      )
      == ExitCode.SUCCESS
    )

    assert(
      dockerizedEtcd
        .getKey(EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE))
        .contains(
          EtcdKeyValueMapper.ForTest
            .toVersionValueString(EtcdClient.Version(incarnation.value, UnixTimeVersion.MIN))
        )
    )

    // Verify: Metrics are correctly reported.
    assert(
      getKnownWatermarkGaugeValueOpt(
        KNOWN_WATERMARK_INCARNATION_GAUGE_NAME,
        "newly_written",
        NAMESPACE
      ).contains(incarnation.value.toDouble)
    )
    assert(
      getKnownWatermarkGaugeValueOpt(
        KNOWN_WATERMARK_NUMBER_GAUGE_NAME,
        "newly_written",
        NAMESPACE
      ).contains(UnixTimeVersion.MIN.value.toDouble)
    )
  }

  test("when there is high watermark already exist, bootstrap returns with success") {
    // Test plan: Verify when there is high watermark existing in the etcd cluster, `bootstrapEtcd`
    // still returns `SUCCESS`, and the existing high watermark remains untouched. Verify this by
    // writing some high watermarks to the etcd cluster (with same and different store incarnation
    // with the previous one), then calling `bootstrapEtcd` against the etcd cluster. Verify the
    // returned exit code is `SUCCESS`, and the high watermark in the etcd cluster remains
    // untouched.
    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(
              EtcdClient.Config(NAMESPACE)
            ),
            NON_LOOSE_INCARNATION
          )
        )
      )
      == ExitCode.SUCCESS
    )
    assert(
      dockerizedEtcd
        .getKey(EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE))
        .contains(
          EtcdKeyValueMapper.ForTest.toVersionValueString(
            EtcdClient.Version(NON_LOOSE_INCARNATION.value, UnixTimeVersion.MIN)
          )
        )
    )

    // Try to re-bootstrap etcd with same store incarnation. Should report success and leave the
    // existing value untouched.
    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(
              EtcdClient.Config(NAMESPACE)
            ),
            NON_LOOSE_INCARNATION
          )
        )
      )
      == ExitCode.SUCCESS
    )
    assert(
      dockerizedEtcd
        .getKey(EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE))
        .contains(
          EtcdKeyValueMapper.ForTest.toVersionValueString(
            EtcdClient.Version(NON_LOOSE_INCARNATION.value, UnixTimeVersion.MIN)
          )
        )
    )

    // Try to re-bootstrap etcd with a different store incarnation. Like above, it should report
    // success and leave the existing value untouched.
    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(
              EtcdClient.Config(NAMESPACE)
            ),
            Incarnation(NON_LOOSE_INCARNATION.value + 2)
          )
        )
      )
      == ExitCode.SUCCESS
    )
    assert(
      dockerizedEtcd
        .getKey(EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE))
        .contains(
          EtcdKeyValueMapper.ForTest.toVersionValueString(
            EtcdClient.Version(NON_LOOSE_INCARNATION.value, UnixTimeVersion.MIN)
          )
        )
    )

    // Verify: The metrics are correctly reported.
    assert(
      getKnownWatermarkGaugeValueOpt(
        KNOWN_WATERMARK_INCARNATION_GAUGE_NAME,
        "existing",
        NAMESPACE
      ).contains(NON_LOOSE_INCARNATION.value.toDouble)
    )
    assert(
      getKnownWatermarkGaugeValueOpt(
        KNOWN_WATERMARK_NUMBER_GAUGE_NAME,
        "existing",
        NAMESPACE
      ).contains(UnixTimeVersion.MIN.value.toDouble)
    )
  }

  test("when etcd data is corrupted, bootstrap returns failure exit code") {
    // Test plan: Verify when there is corrupted high watermark in the etcd cluster, `bootstrapEtcd`
    // returns `FAILURE`. Verify this by writing some corrupted high watermark to the etcd cluster,
    // then calling 'bootstrapEtcd` against it, verifying the returned exit code is `FAILURE`.
    dockerizedEtcd.put(
      EtcdKeyValueMapper.ForTest.getVersionHighWatermarkKeyString(NAMESPACE),
      "not-a-version"
    )
    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(
              EtcdClient.Config(NAMESPACE)
            ),
            NON_LOOSE_INCARNATION
          )
        )
      )
      == ExitCode.RETRYABLE_FAILURE
    )
  }

  test("Single failing request fails batch") {
    // Test plan: Verify that bootstrapEtcdBlocking() reports a failure when a single request in a
    // batch fails, even if the other requests succeed.
    val unresponsiveServer: DatabricksServerWrapper =
      ServerTestUtils.createUnresponsiveServer(port = 0)
    val failingClient = EtcdClient.create(
      etcdEndpoints = Seq(s"http://localhost:${unresponsiveServer.activePort()}"),
      tlsOptionsOpt = None,
      EtcdClient.Config(EtcdClient.KeyNamespace("failing-namespace"))
    )
    val workingClient = dockerizedEtcd.createEtcdClient(
      EtcdClient.Config(EtcdClient.KeyNamespace("successful-namespace"))
    )

    assert(
      bootstrapEtcdBlockingNoLinger(
        Seq(
          BootstrapRequest(failingClient, Incarnation(42)),
          BootstrapRequest(workingClient, Incarnation(43))
        )
      )
      == ExitCode.RETRYABLE_FAILURE
    )

    // Confirm that `workingClient` succeeded by reading etcd.
    assert(
      dockerizedEtcd
        .getKey(
          EtcdKeyValueMapper.ForTest
            .getVersionHighWatermarkKeyString(workingClient.config.keyNamespace)
        )
        .contains(
          EtcdKeyValueMapper.ForTest.toVersionValueString(
            EtcdClient.Version(43, UnixTimeVersion.MIN)
          )
        )
    )
  }

  test("bootstrapEtcdBlocking lingers for at least the requested duration before returning") {
    // Test plan: Verify that `bootstrapEtcdBlocking` keeps the process alive for at least
    // `lingerAfterFinish` after the bootstrap completes, so a short-lived job's result metrics can
    // be scraped before the process exits. Verify this by bootstrapping a single namespace with a
    // small positive linger and asserting the call takes at least that long to return.
    val linger: FiniteDuration = 800.millis
    val startNanos: Long = System.nanoTime()
    assert(
      EtcdBootstrapper.bootstrapEtcdBlocking(
        Seq(
          BootstrapRequest(
            dockerizedEtcd.createEtcdClient(EtcdClient.Config(NAMESPACE)),
            NON_LOOSE_INCARNATION
          )
        ),
        lingerAfterFinish = linger
      )
      == ExitCode.SUCCESS
    )
    val elapsed: FiniteDuration = (System.nanoTime() - startNanos).nanos
    assert(elapsed >= linger)
    // Also verify that the job can be done within a reasonable time.
    assert(elapsed <= linger + 1.second)
  }
}
