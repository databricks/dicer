package com.databricks.dicer.assigner

import java.net.URI

import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContextPool,
  FakeTypedClock,
  MetricUtils,
  TestUtils
}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.ParameterizedTestNameDecorator
import com.databricks.dicer.assigner.TargetMetrics.GeneratorShutdownReason
import com.databricks.dicer.assigner.PreferredAssignerTestHelper.{
  advanceClockBySync,
  createAssignerConfig,
  TEST_TARGET_FOR_PA_DISCOVERY
}
import com.databricks.dicer.assigner.config.{
  InternalTargetConfigMap,
  TargetConfigProvider,
  TargetConfigProviderFactory,
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.assigner.config.TargetConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
import com.databricks.dicer.client.TestClientUtils
import com.databricks.dicer.common.{
  Incarnation,
  InternalDicerTestEnvironment,
  TargetName,
  TestAssigner
}
import com.databricks.dicer.common.InternalDicerTestEnvironment.InternalTargetConfigMapWithDefault
import com.databricks.dicer.external.{Slicelet, Target}
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry
import org.scalatest.Suite
import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration._

/**
 * Drives the active-target-migration override path end-to-end across two simulated clusters. The
 * suite is parameterized by [[TargetMigrationRole]] (the local cluster's role).
 */
class TargetMigrationIntegrationSuite extends DatabricksTest {
  override def nestedSuites: IndexedSeq[Suite] = IndexedSeq(
    new ParameterizedTargetMigrationIntegrationSuite(localRole = TargetMigrationRole.Source),
    new ParameterizedTargetMigrationIntegrationSuite(localRole = TargetMigrationRole.Destination)
  )
}

class ParameterizedTargetMigrationIntegrationSuite(localRole: TargetMigrationRole)
    extends DatabricksTest
    with ParameterizedTestNameDecorator {

  /**
   * Parameters whose values get appended to each test name. Required by
   * [[ParameterizedTestNameDecorator]].
   */
  override val paramsForDebug: Map[String, Any] = Map("localRole" -> localRole)

  /**
   * We create two independent Assigner clusters. In cases where they have different migration
   * config versions and disagree on which role owns a target, we use this [[Target]] to represent
   * it.
   */
  private val MISMATCHING_TARGET: Target = Target("mismatching-target")

  /** An arbitrary blackhole URI. */
  private val BLACKHOLE_PEER_URI: URI = URI.create("http://192.0.2.0:24500")

  /**
   * Returns the metric for the migration routing override path for the given `targetName`. This
   * increments when inbound `redirect_token` is strictly newer than the Assigner's migration config
   * version, and this forces the Assigner to handle the target locally rather than rerouting it
   * to the other cluster.
   */
  private def getTargetMigrationRoutingOverrideCount(targetName: TargetName): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "dicer_assigner_target_migration_routing_overrides_total",
      Map("targetName" -> targetName.toString)
    )
  }

  /**
   * Creates a 3-Assigner cluster environment, and waits until the preferred assigner converges.
   * Returns the test environment and the preferred assigner.
   */
  private def createAssignerEnvironment(
      config: TestAssigner.Config,
      fakeClock: FakeTypedClock,
      secPool: FakeSequentialExecutionContextPool
  ): (InternalDicerTestEnvironment, TestAssigner) = {
    val env: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create(
      config = config,
      numAssigners = 3,
      allowEtcdMode = true,
      secPool = secPool
    )
    // Block until each assigner has finished starting up, so that the subsequent clock advancement
    // schedules ticks against fully-initialized state machines.
    env.testAssigners.foreach(_.getAssignerInfoBlocking())
    advanceClockBySync(
      fakeClock,
      EtcdPreferredAssignerDriver.Config().initialPreferredAssignerTimeout,
      env.testAssigners
    )
    val preferred: TestAssigner =
      PreferredAssignerTestHelper.getConvergedPreferredAssigner(env.testAssigners)
    (env, preferred)
  }

  /**
   * Creates and returns a standalone peer [[TestAssigner]] that has preferred assigner mode
   * disabled. The `assignerClusterUri` is the (simulated) URI of the kubernetes cluster that the
   * assigner will be running in (see <internal link>).
   */
  private def createStandaloneAssigner(
      config: TestAssigner.Config,
      secPool: FakeSequentialExecutionContextPool,
      assignerClusterUri: URI
  ): TestAssigner = {
    val configMap: InternalTargetConfigMap = new InternalTargetConfigMapWithDefault(
      InternalTargetConfigMap.create(configScopeOpt = None, Map.empty)
    )
    val configProvider: TargetConfigProvider =
      TargetConfigProviderFactory.createBlocking(
        staticTargetConfigMap = configMap,
        config.assignerConf,
        initialPollTimeout = DEFAULT_INITIAL_POLL_TIMEOUT
      )
    TestAssigner.createAndStart(
      secPool = secPool,
      config = config,
      configProvider = configProvider,
      preferredAssignerDriverFactory = TestAssigner.defaultPreferredAssignerDriverFactory(
        config.assignerConf,
        config.preferredAssignerDriverConfig,
        dockerizedEtcdOpt = None
      ),
      assignerClusterUri = assignerClusterUri,
      assignerServiceInfoOpt = None
    )
  }

  /** Returns the [[AssignmentGeneratorDriver]] for `target` on `assigner`, or `None` if absent. */
  private def getGeneratorOpt(
      assigner: TestAssigner,
      target: Target): Option[AssignmentGeneratorDriver] =
    TestUtils.awaitResult(assigner.forTest.getGeneratorFromMap(target), Duration.Inf)

  test("Slicelet successfully receives an assignment with peer cluster version > local version") {
    // Test plan: Verify that even when the local and peer Assigners disagree on which role owns the
    // target, a Slicelet does not ping-pong between them and is able to receive an assignment via
    // the cross-cluster override chain.
    //
    // Do this by creating a real Slicelet for a target that the local cluster (which has migration
    // config V) thinks is owned by the peer cluster, and the peer cluster (which has migration
    // config V+1) thinks is owned by the local cluster. The Slicelet starts by watching the
    // standalone peer Assigner. The peer redirects it to the local Standby URI. Even though the
    // local Standby thinks the target is owned by the peer cluster, it should handle it within the
    // local cluster because it has a newer target migration config version. This override should
    // persist through redirects to the local Preferred. We verify that the target migration routing
    // override counter is incremented at least twice: once for overriding the local Standby's
    // config, and once for overriding the local Preferred's config.

    // Setup: Create the local cluster's migration config. `MISMATCHING_TARGET` is owned by the peer
    // cluster in this config.
    val localMigrationConfigVersion: Int = 7
    val localConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = localMigrationConfigVersion,
      destinationTargetNameFraction = 0.0
    )
    val mismatchingTargetName: TargetName = TargetName.forTarget(MISMATCHING_TARGET)
    // `TEST_TARGET_FOR_PA_DISCOVERY` is used to test for preferred assigner convergence, ensure
    // it stays with `localRole`.
    localRole match {
      case TargetMigrationRole.Source =>
        localConfigBuilder.forceToSource(TargetName.forTarget(TEST_TARGET_FOR_PA_DISCOVERY))
        localConfigBuilder.forceToDestination(mismatchingTargetName)
      case TargetMigrationRole.Destination =>
        localConfigBuilder.forceToSource(mismatchingTargetName)
        localConfigBuilder.forceToDestination(TargetName.forTarget(TEST_TARGET_FOR_PA_DISCOVERY))
    }

    // The local cluster owns the target and never redirects to its peer, so the blackhole URI is
    // sufficient for `peerAssignerUris`.
    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(40),
      targetMigratorOpt = Some(
        new FakeTargetMigrator(
          initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = localConfigBuilder.build(),
            targetMigrationRole = localRole,
            peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
          )
        )
      )
    )

    // Setup: Stand up the local 3-Assigner cluster.
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)

    val (testEnv, preferredAssigner): (InternalDicerTestEnvironment, TestAssigner) =
      createAssignerEnvironment(localConfig, fakeClock, secPool)
    val standbyAssigner: TestAssigner = testEnv.testAssigners
      .find { assigner: TestAssigner =>
        assigner.getAssignerInfoBlocking() != preferredAssigner.getAssignerInfoBlocking()
      }
      .getOrElse(fail("Expected at least one standby assigner to be present"))

    // Setup: Create the peer cluster's migration config. This is at a higher version V+1, with
    // `MISMATCHING_TARGET` owned by the local cluster. Its `peerAssignerUris` points at the
    // discovered local Standby.
    val peerRole: TargetMigrationRole = localRole match {
      case TargetMigrationRole.Source => TargetMigrationRole.Destination
      case TargetMigrationRole.Destination => TargetMigrationRole.Source
    }
    val peerConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = localMigrationConfigVersion + 1,
      destinationTargetNameFraction = 0.0
    )
    peerRole match {
      case TargetMigrationRole.Source =>
        peerConfigBuilder.forceToDestination(mismatchingTargetName)
      case TargetMigrationRole.Destination =>
        peerConfigBuilder.forceToSource(mismatchingTargetName)
    }

    // Setup: Create the standalone peer Assigner with preferred assigner mode disabled.
    val peerConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(
        new FakeTargetMigrator(
          initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = peerConfigBuilder.build(),
            targetMigrationRole = peerRole,
            peerAssignerUris = Seq(standbyAssigner.getAssignerInfoBlocking().uri)
          )
        )
      )
    )
    val peerAssigner: TestAssigner = createStandaloneAssigner(
      config = peerConfig,
      secPool = secPool,
      // Doesn't really matter for this test, but it is simulated to be running in the same region
      // as `testEnv`.
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/peer/01")
    )

    val overrideCountTracker: MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker(() => getTargetMigrationRoutingOverrideCount(mismatchingTargetName))

    // Now we start the actual test. Create a Slicelet for the target that the peer Assigner thinks
    // is owned by the local cluster. As mentioned above, it should be redirected to the local
    // Standby, and then to the local Preferred, and eventually receive an assignment.
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = peerAssigner.localUri.getPort,
      target = MISMATCHING_TARGET,
      sliceletHost = s"slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Slicelet receives assignment via cross-cluster override chain").await {
      val assignmentOpt = slicelet.impl.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.isDefined, "Slicelet should have received an assignment")
    }
    assert(
      overrideCountTracker.totalChange() >= 2.0,
      "Override counter should have incremented by >= 2 (Standby + Preferred)"
    )

    slicelet.forTest.stop()
    TestUtils.awaitResult(
      peerAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
    testEnv.clear()
  }

  test("Slicelet receives an assignment with peer cluster version < local version") {
    // Test plan: Verify that even when the local and peer Assigners disagree on which role owns the
    // target, a Slicelet does not ping-pong between them and is able to receive an assignment via
    // the cross-cluster override chain. Similar to the previous test, except in this one the peer
    // cluster has a lower migration config version than the local cluster.
    //
    // Do this by creating a real Slicelet for a target that the local cluster (which has migration
    // config V) thinks is owned by the peer cluster, and the peer cluster (which has migration
    // config V-1) thinks is owned by the local cluster. The Slicelet starts by watching the
    // standalone peer Assigner. The peer redirects it to the local Standby URI, which redirects it
    // back to the peer. Since it is now redirected with a higher migration config version, it gets
    // handled by the peer Assigner. We verify that the override counter for `MISMATCHING_TARGET` is
    // incremented at least once (on the peer).

    val localMigrationConfigVersion: Int = 7
    val mismatchingTargetName: TargetName = TargetName.forTarget(MISMATCHING_TARGET)

    // Setup: Create the local cluster's migration config. `MISMATCHING_TARGET` is owned by the peer
    // cluster in this config.
    val localConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = localMigrationConfigVersion,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source =>
        localConfigBuilder.forceToDestination(mismatchingTargetName)
      case TargetMigrationRole.Destination =>
        localConfigBuilder.forceToSource(mismatchingTargetName)
    }

    // The local cluster uses a placeholder `peerAssignerUris` until the standalone peer Assigner
    // has been created.
    val localMigrator: FakeTargetMigrator = new FakeTargetMigrator(
      initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = localConfigBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )

    // Setup: Create the standalone local Assigner with preferred assigner mode disabled.
    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(localMigrator)
    )
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)
    val localAssigner: TestAssigner = createStandaloneAssigner(
      config = localConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/local/01")
    )

    // Setup: Create the peer cluster's migration config. This is at a lower version V-1, with
    // `MISMATCHING_TARGET` owned by the local cluster. Its `peerAssignerUris` points at the
    // discovered local Assigner.
    val peerRole: TargetMigrationRole = localRole match {
      case TargetMigrationRole.Source => TargetMigrationRole.Destination
      case TargetMigrationRole.Destination => TargetMigrationRole.Source
    }
    val peerConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = localMigrationConfigVersion - 1,
      destinationTargetNameFraction = 0.0
    )
    peerRole match {
      case TargetMigrationRole.Source =>
        peerConfigBuilder.forceToDestination(mismatchingTargetName)
      case TargetMigrationRole.Destination =>
        peerConfigBuilder.forceToSource(mismatchingTargetName)
    }

    // Setup: Create the standalone peer Assigner with preferred assigner mode disabled.
    val peerConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(
        new FakeTargetMigrator(
          initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = peerConfigBuilder.build(),
            targetMigrationRole = peerRole,
            peerAssignerUris = Seq(localAssigner.localUri)
          )
        )
      )
    )
    val peerAssigner: TestAssigner = createStandaloneAssigner(
      config = peerConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/peer/01")
    )

    // Update the local config to point at the actual peer Assigner's URI so the cross-cluster
    // reroute can work.
    localMigrator.setSnapshot(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = localConfigBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(peerAssigner.localUri)
      )
    )

    val overrideCountTracker: MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker(() => getTargetMigrationRoutingOverrideCount(mismatchingTargetName))

    // Now we start the actual test. Create a Slicelet for the target that each Assigner thinks is
    // owned by the other side. It should be redirected from the peer to the local Assigner, and
    // then back to the peer with a higher migration config version, and eventually receive an
    // assignment.
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = peerAssigner.localUri.getPort,
      target = MISMATCHING_TARGET,
      sliceletHost = s"slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Slicelet receives assignment via override").await {
      val assignmentOpt = slicelet.impl.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.isDefined, "Slicelet should have received an assignment")
    }
    assert(
      overrideCountTracker.totalChange() >= 1.0,
      "Override counter should have incremented by >= 1 (on peer)"
    )

    slicelet.forTest.stop()
    TestUtils.awaitResult(
      peerAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
    TestUtils.awaitResult(
      localAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
  }

  test("Slicelet is routed without override when versions match and clusters agree") {
    // Test plan: Verify that when both Assigners have the same migration config version and agree
    // that the local cluster owns the target, the Slicelet is routed cross-cluster from peer to
    // local exactly once and receives an assignment from the local Assigner.
    //
    // Do this by creating a 3-Assigner peer cluster and a standalone local Assigner, both at
    // migration config version V. Both configs have the target owned by the local role. The
    // Slicelet starts by watching one of the peer Assigners. The peer reroutes to local (since
    // local owns the target by both configs). Local handles it directly and serves the assignment.
    // We verify that the override counter did not increment.

    val migrationConfigVersion: Int = 7
    val target: Target = Target("matching-target")
    val targetName: TargetName = TargetName.forTarget(target)

    // Setup: Create the shared migration config. `MISMATCHING_TARGET` is owned by the local cluster
    // in this config.
    val configBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = migrationConfigVersion,
      destinationTargetNameFraction = 0.0
    )
    // `TEST_TARGET_FOR_PA_DISCOVERY` is used to test for preferred assigner convergence. In this
    // test only the peer has preferred assigner enabled, so we force it to the peer role.
    localRole match {
      case TargetMigrationRole.Source =>
        configBuilder.forceToSource(targetName)
        configBuilder.forceToDestination(TargetName.forTarget(TEST_TARGET_FOR_PA_DISCOVERY))
      case TargetMigrationRole.Destination =>
        configBuilder.forceToDestination(targetName)
        configBuilder.forceToSource(TargetName.forTarget(TEST_TARGET_FOR_PA_DISCOVERY))
    }

    // The local cluster owns the target and never redirects to its peer, so the blackhole URI is
    // sufficient for `peerAssignerUris`.
    val localMigrator: FakeTargetMigrator = new FakeTargetMigrator(
      initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = configBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )

    // Setup: Create the standalone local Assigner with preferred assigner mode disabled.
    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(localMigrator)
    )
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)
    val localAssigner: TestAssigner = createStandaloneAssigner(
      config = localConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/local/01")
    )

    // Setup: Create the peer cluster with the same config. We arbitrarily create 3 assigners with
    // preferred assigner mode enabled, although in practice the Slicelet should only talk to one of
    // them before being rerouted to the local cluster.
    val peerRole: TargetMigrationRole = localRole match {
      case TargetMigrationRole.Source => TargetMigrationRole.Destination
      case TargetMigrationRole.Destination => TargetMigrationRole.Source
    }
    val peerConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(40),
      targetMigratorOpt = Some(
        new FakeTargetMigrator(
          initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = configBuilder.build(),
            targetMigrationRole = peerRole,
            peerAssignerUris = Seq(localAssigner.localUri)
          )
        )
      )
    )
    val (peerEnv, _): (InternalDicerTestEnvironment, TestAssigner) =
      createAssignerEnvironment(peerConfig, fakeClock, secPool)

    val overrideCountTracker: MetricUtils.ChangeTracker[Double] =
      MetricUtils.ChangeTracker(() => getTargetMigrationRoutingOverrideCount(targetName))

    // Now we start the actual test. Create a Slicelet for the target that the peer Assigner should
    // redirect to the local cluster.
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = peerEnv.testAssigners.head.localUri.getPort,
      target = target,
      sliceletHost = s"slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Slicelet receives assignment").await {
      val assignmentOpt = slicelet.impl.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.isDefined, "Slicelet should have received an assignment")
    }
    assert(
      overrideCountTracker.totalChange() == 0.0,
      "Override counter should not have incremented (both clusters agree on ownership)"
    )

    slicelet.forTest.stop()
    TestUtils.awaitResult(
      localAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
    peerEnv.clear()
  }

  test("A new resolver that reroutes a target shuts down that target's generator") {
    // Test plan: Verify that when the Assigner adopts a new TargetOwnershipResolver that reroutes a
    // target it previously handled, the generator for that target is shut down and recorded as a
    // TARGET_MIGRATION_REROUTE removal, while a target that stays locally owned keeps its
    // generator.
    //
    // Do this by standing up a standalone local Assigner whose initial migration snapshot pins both
    // a `reroutedTarget` and a `retainedTarget` to the local role. A Slicelet for each target
    // creates its generator. We then publish a new snapshot that flips only `reroutedTarget` to the
    // peer role, and verify that `reroutedTarget`'s generator is removed (with a
    // TARGET_MIGRATION_REROUTE removal recorded) while `retainedTarget`'s generator survives.
    val reroutedTarget: Target = Target("rerouted-target")
    val retainedTarget: Target = Target("retained-target")
    val reroutedTargetName: TargetName = TargetName.forTarget(reroutedTarget)
    val retainedTargetName: TargetName = TargetName.forTarget(retainedTarget)

    // Setup: The initial migration config pins both targets to the local role, so both are handled
    // locally and get a generator.
    val initialConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 1,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source =>
        initialConfigBuilder.forceToSource(reroutedTargetName)
        initialConfigBuilder.forceToSource(retainedTargetName)
      case TargetMigrationRole.Destination =>
        initialConfigBuilder.forceToDestination(reroutedTargetName)
        initialConfigBuilder.forceToDestination(retainedTargetName)
    }
    val migrator: FakeTargetMigrator = new FakeTargetMigrator(
      initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = initialConfigBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )

    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(migrator)
    )
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)
    val localAssigner: TestAssigner = createStandaloneAssigner(
      config = localConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/local/01")
    )

    // Track reroute-driven removals for both targets from before any generator is created, so the
    // assertions below capture every removal each target accrues over the whole test.
    val reroutedRemovalTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(
            reroutedTarget,
            GeneratorShutdownReason.TARGET_MIGRATION_REROUTE
          )
      }
    val retainedRemovalTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(
            retainedTarget,
            GeneratorShutdownReason.TARGET_MIGRATION_REROUTE
          )
      }

    // Setup: Connect a Slicelet for each target so both generators are created.
    val reroutedSlicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = localAssigner.localUri.getPort,
      target = reroutedTarget,
      sliceletHost = s"rerouted-slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    reroutedSlicelet.start(selfPort = 1234, listenerOpt = None)
    val retainedSlicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = localAssigner.localUri.getPort,
      target = retainedTarget,
      sliceletHost = s"retained-slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    retainedSlicelet.start(selfPort = 5678, listenerOpt = None)

    AssertionWaiter("Both generators are created").await {
      assert(
        getGeneratorOpt(localAssigner, reroutedTarget).isDefined,
        "Rerouted target's generator should be created"
      )
      assert(
        getGeneratorOpt(localAssigner, retainedTarget).isDefined,
        "Retained target's generator should be created"
      )
      assert(
        reroutedRemovalTracker.totalChange() == 0L,
        "Creating the generators should not record a TARGET_MIGRATION_REROUTE removal for the " +
        "rerouted target"
      )
      assert(
        retainedRemovalTracker.totalChange() == 0L,
        "Creating the generators should not record a TARGET_MIGRATION_REROUTE removal for the " +
        "retained target"
      )
    }

    // Now the actual test. Publish a new snapshot flipping only `reroutedTarget` to the peer role.
    val flippedConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 2,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source =>
        // Flip `reroutedTarget` to the peer (Destination) role, keep `retainedTarget` local.
        flippedConfigBuilder.forceToDestination(reroutedTargetName)
        flippedConfigBuilder.forceToSource(retainedTargetName)
      case TargetMigrationRole.Destination =>
        flippedConfigBuilder.forceToSource(reroutedTargetName)
        flippedConfigBuilder.forceToDestination(retainedTargetName)
    }
    migrator.setSnapshot(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = flippedConfigBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )

    AssertionWaiter("Rerouted target's generator is shut down, retained target's survives").await {
      assert(
        getGeneratorOpt(localAssigner, reroutedTarget).isEmpty,
        "Rerouted target's generator should be removed after the resolver reroutes it"
      )
      assert(
        getGeneratorOpt(localAssigner, retainedTarget).isDefined,
        "Retained target's generator should survive the resolver change"
      )
      assert(
        reroutedRemovalTracker.totalChange() == 1L,
        "Rerouted target should record exactly one TARGET_MIGRATION_REROUTE removal"
      )
      assert(
        retainedRemovalTracker.totalChange() == 0L,
        "Retained target should record no TARGET_MIGRATION_REROUTE removal"
      )
    }

    reroutedSlicelet.forTest.stop()
    retainedSlicelet.forTest.stop()
    TestUtils.awaitResult(
      localAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
  }

  test("A transition to NoActiveMigration does not shut down any generator") {
    // Test plan: Verify that when a migration completes or aborts — the resolver transitions from
    // an ActiveMigration to a NoActiveMigration snapshot — no generator is removed, since a
    // NoActiveMigration resolver handles every target locally.
    //
    // Do this by pinning `target` to the local role under an ActiveMigration at version 1, creating
    // its generator, then publishing a NoActiveMigration snapshot at version 2. Verify the
    // generator survives and no TARGET_MIGRATION_REROUTE removal is recorded.
    val target: Target = Target("no-migration-transition-target")
    val targetName: TargetName = TargetName.forTarget(target)

    // Setup: The initial config pins `target` to the local role, so it is handled locally.
    val initialConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 1,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source => initialConfigBuilder.forceToSource(targetName)
      case TargetMigrationRole.Destination => initialConfigBuilder.forceToDestination(targetName)
    }
    val migrator: FakeTargetMigrator = new FakeTargetMigrator(
      initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = initialConfigBuilder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )

    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(migrator)
    )
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)
    val localAssigner: TestAssigner = createStandaloneAssigner(
      config = localConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/local/01")
    )

    // Track reroute-driven removals from before the generator is created, so the assertion below
    // captures every removal the target accrues over the whole test.
    val removalTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(target, GeneratorShutdownReason.TARGET_MIGRATION_REROUTE)
      }

    // Setup: Connect a Slicelet so the generator is created.
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = localAssigner.localUri.getPort,
      target = target,
      sliceletHost = s"slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Generator is created").await {
      assert(
        getGeneratorOpt(localAssigner, target).isDefined,
        "Target's generator should be created"
      )
    }

    // Publish a NoActiveMigration snapshot at a higher version, ending the migration.
    val noMigrationConfig: TargetMigrationConfig = TargetMigrationConfig(
      version = 2,
      migrationType = TargetMigrationType.NoMigration,
      forceToSourceTargetNames = Set.empty,
      forceToDestinationTargetNames = Set.empty,
      destinationTargetNameFraction = 0.0
    )
    migrator.setSnapshot(TargetMigrationSnapshot.NoActiveMigration(noMigrationConfig))

    // Advance the clock so any spurious scheduled work would have a chance to run, then confirm the
    // generator survives and no reroute removal was recorded.
    advanceClockBySync(fakeClock, 1.second, Vector(localAssigner))
    AssertionWaiter("Generator survives the transition to NoActiveMigration").await {
      assert(
        getGeneratorOpt(localAssigner, target).isDefined,
        "Target's generator should survive the transition to NoActiveMigration"
      )
      assert(
        removalTracker.totalChange() == 0L,
        "A transition to NoActiveMigration must not record a TARGET_MIGRATION_REROUTE removal"
      )
    }

    slicelet.forTest.stop()
    TestUtils.awaitResult(
      localAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
  }

  test("A generator shut down by a reroute is recreated when a newer inbound token forces Handle") {
    // Test plan: Verify that a target whose generator was shut down by a reroute can spin its
    // generator back up when a peer redirects it back with a strictly newer config version. This is
    // the sequence where the local cluster falls behind: at v1 the local cluster owns the target
    // (generator created); at v2 the local cluster reroutes it to the peer (generator shut down);
    // then the peer, now at v3, disagrees and redirects the Slicelet back to the local cluster with
    // a v3 token. Since v3 is newer than the local v2, the local cluster overrides its reroute,
    // handles the target locally, and must recreate the generator.
    //
    // Do this with two standalone Assigners pointing at each other: drive the local cluster through
    // v1 (handle) then v2 (reroute) and confirm the generator is created then removed, then create
    // a Slicelet against the peer (at v3, owning the target as the local cluster's) and confirm the
    // local cluster overrides and recreates the generator.
    val peerRole: TargetMigrationRole = localRole match {
      case TargetMigrationRole.Source => TargetMigrationRole.Destination
      case TargetMigrationRole.Destination => TargetMigrationRole.Source
    }
    val targetName: TargetName = TargetName.forTarget(MISMATCHING_TARGET)

    // Setup: The local cluster starts at v1 owning the target, so it is handled locally.
    val localV1Builder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 1,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source => localV1Builder.forceToSource(targetName)
      case TargetMigrationRole.Destination => localV1Builder.forceToDestination(targetName)
    }
    val localMigrator: FakeTargetMigrator = new FakeTargetMigrator(
      initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = localV1Builder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )
    val localConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(localMigrator)
    )
    val fakeClock: FakeTypedClock = new FakeTypedClock()
    val secPool: FakeSequentialExecutionContextPool =
      FakeSequentialExecutionContextPool
        .create(this.getClass.getName, numThreads = 10, fakeClock)
    val localAssigner: TestAssigner = createStandaloneAssigner(
      config = localConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/local/01")
    )

    val removalTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        TargetMetricsUtils
          .getGeneratorsRemovedTotal(
            MISMATCHING_TARGET,
            GeneratorShutdownReason.TARGET_MIGRATION_REROUTE
          )
      }

    // Setup: Connect a Slicelet directly to the local Assigner so it creates the generator while
    // the target is handled locally at v1.
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = localAssigner.localUri.getPort,
      target = MISMATCHING_TARGET,
      sliceletHost = s"recreate-slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    slicelet.start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Generator is created while handled locally at v1").await {
      assert(
        getGeneratorOpt(localAssigner, MISMATCHING_TARGET).isDefined,
        "Target's generator should be created while it is handled locally"
      )
    }

    // Advance the local cluster to v2, flipping the target to the peer role so it is now rerouted
    // and its generator is shut down.
    val localV2Builder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 2,
      destinationTargetNameFraction = 0.0
    )
    localRole match {
      case TargetMigrationRole.Source => localV2Builder.forceToDestination(targetName)
      case TargetMigrationRole.Destination => localV2Builder.forceToSource(targetName)
    }
    localMigrator.setSnapshot(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = localV2Builder.build(),
        targetMigrationRole = localRole,
        peerAssignerUris = Seq(BLACKHOLE_PEER_URI)
      )
    )
    AssertionWaiter("Generator is shut down once the target is rerouted at v2").await {
      assert(
        getGeneratorOpt(localAssigner, MISMATCHING_TARGET).isEmpty,
        "Target's generator should be shut down after the reroute"
      )
      assert(
        removalTracker.totalChange() == 1L,
        "The reroute should record exactly one TARGET_MIGRATION_REROUTE removal"
      )
    }

    // Setup: Stand up the peer Assigner at v3, owning the target as the local cluster's, and
    // pointing its reroutes back at the local Assigner.
    val peerConfigBuilder: TargetMigrationConfigBuilder = new TargetMigrationConfigBuilder(
      version = 3,
      destinationTargetNameFraction = 0.0
    )
    peerRole match {
      case TargetMigrationRole.Source => peerConfigBuilder.forceToDestination(targetName)
      case TargetMigrationRole.Destination => peerConfigBuilder.forceToSource(targetName)
    }
    val peerConfig: TestAssigner.Config = createAssignerConfig(
      preferredAssignerStoreIncarnation = Incarnation(1),
      preferredAssignerEnabled = false,
      targetMigratorOpt = Some(
        new FakeTargetMigrator(
          initialSnapshot = TargetMigrationSnapshot.ActiveMigration(
            targetMigrationConfig = peerConfigBuilder.build(),
            targetMigrationRole = peerRole,
            peerAssignerUris = Seq(localAssigner.localUri)
          )
        )
      )
    )
    val peerAssigner: TestAssigner = createStandaloneAssigner(
      config = peerConfig,
      secPool = secPool,
      assignerClusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/peer/01")
    )

    // Redirect the Slicelet to watch the peer. The peer reroutes it back to the local Assigner with
    // a v3 token; since v3 is newer than the local v2, the local cluster overrides and handles the
    // target, recreating its generator.
    slicelet.forTest.stop()
    val redirectedSlicelet: Slicelet = TestClientUtils.createSlicelet(
      assignerPort = peerAssigner.localUri.getPort,
      target = MISMATCHING_TARGET,
      sliceletHost = s"redirected-slicelet-host-$localRole",
      clientTlsFilePathsOpt = None,
      serverTlsFilePathsOpt = None,
      watchFromDataPlane = false
    )
    redirectedSlicelet.start(selfPort = 5678, listenerOpt = None)

    AssertionWaiter("Generator is recreated once the newer inbound token forces a local Handle")
      .await {
        assert(
          getGeneratorOpt(localAssigner, MISMATCHING_TARGET).isDefined,
          "Target's generator should be recreated after the override forces a local Handle"
        )
      }

    redirectedSlicelet.forTest.stop()
    TestUtils.awaitResult(
      peerAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
    TestUtils.awaitResult(
      localAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )
  }
}
