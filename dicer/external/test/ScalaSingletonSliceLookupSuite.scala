package com.databricks.dicer.external

import java.net.URI

import scala.collection.mutable

import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.conf.trusted.ProjectConfByName
import com.databricks.conf.{Config, Configs, RichConfig}
import com.databricks.dicer.client.{ClerkImpl, DicerClientProtoLogger, TestClientUtils}
import com.databricks.dicer.common.{ClientType, InternalClientConf}
import com.databricks.rpc.tls.TLSOptions
import io.prometheus.client.CollectorRegistry

/** Tests for Scala [[SliceLookupCache]] behavior in Clerk creation. */
private class ScalaSingletonSliceLookupSuite extends SingletonSliceLookupSuiteBase {

  /**
   * A cache of [[ClerkConf]]s keyed by slicelet port. [[TestClientUtils.createClerkConfig]]
   * generates a random `clientUuid` on every call, so a separately-built config per Clerk would
   * yield a different config and bypass any cached SliceLookups. Reusing one config per port
   * enables multiple Clerks to share the same SliceLookup via [[SliceLookupCache]].
   */
  private val clerkConfMap: mutable.Map[Int, ClerkConf] = mutable.Map.empty

  override protected def createSharingClerk(target: Target, sliceletPort: Int): ClerkHarness = {
    val clerkConf: ClerkConf = clerkConfMap.getOrElseUpdate(
      sliceletPort, {
        val rawConf: Config = TestClientUtils
          .createClerkConfig(sliceletPort = sliceletPort, clientTlsFilePathsOpt = None)
          .merge(
            Configs.parseMap(
              InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
            )
          )
        new ProjectConfByName("test", rawConf) with ClerkConf {
          override def dicerTlsOptions: Option[TLSOptions] = None
        }
      }
    )
    val clerk: Clerk[ResourceAddress] =
      Clerk.create(clerkConf, target, sliceletHostName = "localhost", createStubFactory())
    ScalaClerkHarness.create(clerk)
  }

  override protected def readPrometheusMetric(
      metricName: String,
      labels: Vector[(String, String)]): Double = {
    MetricUtils.getMetricValue(CollectorRegistry.defaultRegistry, metricName, labels.toMap)
  }

  /**
   * A stub factory that creates a new function instance each time it is called, but returns the
   * resource address unchanged. This simulates realistic usage where each Clerk creation may use
   * a different anonymous function instance for the stub factory.
   */
  private def createStubFactory(): ResourceAddress => ResourceAddress = {
    (address: ResourceAddress) =>
      address
  }

  // Scala only because `createForShardedStub` is only supported in Scala.
  test("createForShardedStub does not reuse SliceLookup for same Target") {
    // Test plan: Verify that createForShardedStub does not reuse SliceLookup for the same Target.
    // TODO(<internal bug>): Once lookup reuse is enabled for sharded stubs, this test should be updated
    // to verify that the SliceLookup is reused.

    val target: Target = Target(getUniqueTargetName)
    val watchAddress: URI = URI.create("http://localhost:1241")

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create multiple Clerks via createForShardedStub with the same Target and config.
    val numClerks: Int = 5
    for (_: Int <- 0 until numClerks) {
      ClerkImpl.createForShardedStub(
        target,
        watchAddress,
        TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
        tlsOptions = None,
        clientUuidOpt = None
      )
    }

    // Verify: there are 5 SliceLookups created.
    assert(sliceLookupTracker.totalChange() == 5)

    // Verify: no cache hits recorded.
    assert(cacheHitTracker.totalChange() == 0)
  }

  // Scala only because the Rust version of `createForMultiClerk` is under a `temp` directory.
  // The Rust version is tested through `MultiClerkHarness` (in the same `temp` directory).
  test("createForMultiClerk never uses caching even for same Target") {
    // Test plan: Verify that createForMultiClerk never uses SliceLookupCache, which means
    // multiple Clerks for the same Target will each have their own SliceLookup.
    // Verify that no caching is used, despite allowMultipleClerksShareLookupPerTarget being true.

    // Use an AppTarget to satisfy the precondition (AppTargets are fully qualified by instanceId).
    val target: Target =
      Target.createAppTarget(getUniqueTargetName, instanceId = "test-instance")
    val assignerAddress: URI = URI.create("http://localhost:1242")

    val rawConf: Config = TestClientUtils
      .createDataPlaneDirectClerkConfig(
        assignerPort = 1242,
        clientTlsFilePathsOpt = None
      )
      .merge(
        Configs.parseMap(
          InternalClientConf.allowMultipleClerksShareLookupPerTargetPropertyName -> true
        )
      )
    val clerkConf: ClerkConf = new ProjectConfByName("test", rawConf) with ClerkConf {
      override def dicerTlsOptions: Option[TLSOptions] = None
    }

    val cacheHitTracker: ChangeTracker[Double] =
      createCacheResultTracker(target, configMatched = true)
    val sliceLookupTracker: ChangeTracker[Double] = createSliceLookupCountTracker(target)

    // Create a shared proto logger as MultiClerkImpl would.
    val sharedProtoLogger = DicerClientProtoLogger.create(
      clientType = ClientType.Clerk,
      conf = clerkConf,
      ownerName = "singleton-slice-lookup-test"
    )

    // Create multiple Clerks via createForMultiClerk with the same Target and config, but
    // different stub factory instances.
    val numClerks: Int = 5
    for (_: Int <- 0 until numClerks) {
      ClerkImpl.createForMultiClerk(
        secPoolOpt = None,
        protoLogger = sharedProtoLogger,
        clerkConf,
        target,
        assignerAddress,
        createStubFactory()
      )
    }

    // Verify: one SliceLookup created for each Clerk.
    assert(sliceLookupTracker.totalChange() == numClerks)

    // Verify: no cache hits recorded.
    assert(cacheHitTracker.totalChange() == 0)
  }
}
