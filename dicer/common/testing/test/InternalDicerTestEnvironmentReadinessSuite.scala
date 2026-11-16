package com.databricks.dicer.common.testing

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

import com.databricks.caching.util.{AssertionWaiter, WatchValueCell}
import com.databricks.common.status.{
  ProbeStatus,
  ProbeStatusManager,
  ProbeStatuses,
  ProbeStatusSource
}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.dicer.assigner.config.{InternalTargetConfig, InternalTargetConfigMap}
import com.databricks.dicer.client.{SliceletImpl, TestClientUtils}
import com.databricks.dicer.client.TestClientUtils.FakeBlockingReadinessProvider
import com.databricks.dicer.common.{Assignment, TargetName}
import com.databricks.dicer.external.{Slicelet, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

/** Tests process-wide readiness ownership through Slicelet assignment membership. */
class InternalDicerTestEnvironmentReadinessSuite extends DatabricksTest {

  private val target: Target = Target("readiness-test")
  private val environments: ArrayBuffer[InternalDicerTestEnvironment] =
    new ArrayBuffer[InternalDicerTestEnvironment]
  private val alwaysReadySlicelets: ArrayBuffer[SliceletImpl] = new ArrayBuffer[SliceletImpl]

  override def beforeEach(): Unit = {
    super.beforeEach()
    ProbeStatusManager.resetForTesting()
  }

  override def afterEach(): Unit = {
    for (alwaysReadySlicelet: SliceletImpl <- alwaysReadySlicelets)
      alwaysReadySlicelet.forTest.stop()
    alwaysReadySlicelets.clear()
    for (env: InternalDicerTestEnvironment <- environments) env.stop()
    environments.clear()
    ProbeStatusManager.resetForTesting()
    super.afterEach()
  }

  /** Returns a source that always reports `status`. */
  private def constantSource(status: ProbeStatus): ProbeStatusSource = new ProbeStatusSource {
    override def getStatus: ProbeStatus = status
  }

  /** Creates an environment that honors Slicelet readiness; cleaned up after each test. */
  private def createEnvironment(
      readinessSourceOpt: Option[ProbeStatusSource] = None): InternalDicerTestEnvironment = {
    val env: InternalDicerTestEnvironment = InternalDicerTestEnvironment.create(
      config = TestAssigner.Config.create(
        assignerConf = new DicerAssignerConf(
          Configs.parseMap("databricks.dicer.assigner.notReadyTimeoutPeriodSeconds" -> 1)
        )
      ),
      targetConfigMap = InternalTargetConfigMap.create(
        clusterConfigScopeOpt = None,
        targetConfigMap = Map(
          TargetName(target.name) -> InternalTargetConfig.forTest.DEFAULT.copy(
            healthWatcherConfig = HealthWatcherTargetConfig(
              observeSliceletReadinessOpt = Some(true),
              permitRunningToNotReady = false
            )
          )
        )
      ),
      readinessSourceOpt = readinessSourceOpt
    )
    environments += env
    env
  }

  /** Starts the Slicelet under test and an extra Slicelet that stays ready for the same target. */
  private def startSlicelets(env: InternalDicerTestEnvironment): (Slicelet, SliceletImpl) = {
    // Dicer keeps the last assignment when no healthy Slicelets remain. Keep an extra Slicelet
    // ready so Dicer can generate an assignment that removes the Slicelet under test when its
    // PSM readiness becomes false. Only the extra Slicelet uses a fake readiness provider.
    val alwaysReadyProvider: FakeBlockingReadinessProvider = new FakeBlockingReadinessProvider
    alwaysReadyProvider.setReady(true)
    val alwaysReadySlicelet: SliceletImpl = SliceletImpl.createForExternalWithReadinessProvider(
      TestClientUtils.createTestSliceletConf(
        assignerPort = env.getAssignerPort,
        sliceletHost = "always-ready",
        clientTlsFilePathsOpt = None,
        serverTlsFilePathsOpt = None,
        watchFromDataPlane = false,
        watchRpcTimeoutOpt = Some(500.milliseconds)
      ),
      target,
      alwaysReadyProvider
    )
    alwaysReadySlicelets += alwaysReadySlicelet
    alwaysReadySlicelet.start(selfPort = 1111, listenerOpt = None)
    val slicelet: Slicelet = env.createSlicelet(
      target,
      initialAssignerIndex = 0,
      watchFromDataPlane = false,
      watchRpcTimeoutOpt = Some(500.milliseconds)
    )
    slicelet.start(selfPort = 2222, listenerOpt = None)
    (slicelet, alwaysReadySlicelet)
  }

  /**
   * Waits for the assignment to contain exactly the expected Slicelets.
   *
   * @param alwaysReadySlicelet The extra Slicelet that stays ready and receives assignments even
   *                           when the Slicelet under test is not ready.
   * @param slicelet The Slicelet under test, whose readiness follows PSM.
   * @param assigned Whether the assignment should include the Slicelet under test. The extra
   *                 always-ready Slicelet must be included in either case.
   */
  private def assertMembership(
      alwaysReadySlicelet: SliceletImpl,
      slicelet: Slicelet,
      assigned: Boolean): Unit = {
    val expected: Set[Squid] =
      if (assigned) Set(alwaysReadySlicelet.squid, slicelet.impl.squid)
      else Set(alwaysReadySlicelet.squid)
    AssertionWaiter(s"Assignment membership is $expected").await {
      val assignmentOpt: Option[Assignment] = alwaysReadySlicelet.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.isDefined)
      assert(assignmentOpt.get.assignedResources == expected)
    }
  }

  test("Slicelet joins the assignment only after PSM startup completes") {
    // Test plan: Start PSM with startup pending and readiness OK. Verify that only the extra ready
    // Slicelet is assigned, then complete startup and verify the Slicelet under test joins it.
    val startup: WatchValueCell[ProbeStatus] = new WatchValueCell[ProbeStatus]
    startup.setValue(ProbeStatuses.notYetReady("startup"))
    val startupSource: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = startup.getLatestValueOpt.get
    }
    val alwaysOk: ProbeStatusSource = constantSource(ProbeStatuses.ok("service"))
    ProbeStatusManager.withPollIntervalMsForTesting(50L) {
      ProbeStatusManager.start(startupSource, alwaysOk, alwaysOk)
    }
    val env: InternalDicerTestEnvironment = createEnvironment()
    val (slicelet, alwaysReadySlicelet): (Slicelet, SliceletImpl) = startSlicelets(env)
    assertMembership(alwaysReadySlicelet, slicelet, assigned = false)
    startup.setValue(ProbeStatuses.ok("startup"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = true)
  }

  test("Default readiness allows Slicelets to join the assignment") {
    // Test plan: Create an environment without a readiness source and verify its Slicelet is
    // assigned. A second default environment must also allow its Slicelet to be assigned.
    val env: InternalDicerTestEnvironment = createEnvironment()
    val (slicelet, alwaysReadySlicelet): (Slicelet, SliceletImpl) = startSlicelets(env)
    assertMembership(alwaysReadySlicelet, slicelet, assigned = true)
    val otherEnv: InternalDicerTestEnvironment = createEnvironment()
    val (otherSlicelet, otherAlwaysReadySlicelet): (Slicelet, SliceletImpl) =
      startSlicelets(otherEnv)
    assertMembership(otherAlwaysReadySlicelet, otherSlicelet, assigned = true)
  }

  test("Caller controls membership and additional environments preserve readiness") {
    // Test plan: Supply a mutable not-ready source and create another environment without waiting
    // for readiness. Verify both Slicelets join, leave, and rejoin as the source changes.
    val status: WatchValueCell[ProbeStatus] = new WatchValueCell[ProbeStatus]
    status.setValue(ProbeStatuses.notYetReady("caller"))
    val source: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = status.getLatestValueOpt.get
    }
    val env: InternalDicerTestEnvironment = createEnvironment(Some(source))
    val (slicelet, alwaysReadySlicelet): (Slicelet, SliceletImpl) = startSlicelets(env)
    assertMembership(alwaysReadySlicelet, slicelet, assigned = false)
    val otherEnv: InternalDicerTestEnvironment = createEnvironment()
    val (otherSlicelet, otherAlwaysReadySlicelet): (Slicelet, SliceletImpl) =
      startSlicelets(otherEnv)
    assertMembership(otherAlwaysReadySlicelet, otherSlicelet, assigned = false)

    status.setValue(ProbeStatuses.ok("caller"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = true)
    assertMembership(otherAlwaysReadySlicelet, otherSlicelet, assigned = true)
    status.setValue(ProbeStatuses.notYetReady("caller"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = false)
    assertMembership(otherAlwaysReadySlicelet, otherSlicelet, assigned = false)
    status.setValue(ProbeStatuses.ok("caller"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = true)
    assertMembership(otherAlwaysReadySlicelet, otherSlicelet, assigned = true)
  }

  test("Environment preserves service-owned readiness") {
    // Test plan: Start PSM with a service-owned mutable source, then create an environment with no
    // source. Verify the Slicelet's assignment membership continues to follow the service source.
    val status: WatchValueCell[ProbeStatus] = new WatchValueCell[ProbeStatus]
    status.setValue(ProbeStatuses.notYetReady("service"))
    val source: ProbeStatusSource = new ProbeStatusSource {
      override def getStatus: ProbeStatus = status.getLatestValueOpt.get
    }
    val alwaysOk: ProbeStatusSource = constantSource(ProbeStatuses.ok("service"))
    ProbeStatusManager.withPollIntervalMsForTesting(50L) {
      ProbeStatusManager.start(alwaysOk, source, alwaysOk)
    }
    val env: InternalDicerTestEnvironment = createEnvironment()
    val (slicelet, alwaysReadySlicelet): (Slicelet, SliceletImpl) = startSlicelets(env)
    assertMembership(alwaysReadySlicelet, slicelet, assigned = false)
    status.setValue(ProbeStatuses.ok("service"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = true)
    status.setValue(ProbeStatuses.notYetReady("service"))
    assertMembership(alwaysReadySlicelet, slicelet, assigned = false)
  }
}
