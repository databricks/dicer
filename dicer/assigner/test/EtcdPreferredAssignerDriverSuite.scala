package com.databricks.dicer.assigner

import scala.concurrent.duration._
import com.databricks.caching.util.{
  AssertionWaiter,
  EtcdTestEnvironment,
  EtcdClient,
  FakeSequentialExecutionContext,
  FakeTypedClock
}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.testing.DatabricksTest

import java.net.URI
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.util.Random

/**
 * This suite only contains the very basic tests for the `EtcdPreferredAssignerDriver`. For an
 * end-to-end thorough integration test, please see `EtcdPreferredAssignerDriverIntegrationSuite`.
 * For logging-related tests, see `EtcdPreferredAssignerLoggingSuite`.
 */
class EtcdPreferredAssignerDriverSuite extends DatabricksTest with TestName {

  private val NAMESPACE_PREFIX = ""

  private val etcd = EtcdTestEnvironment.create()

  // RNG used for the store, just so we have no jitter in delays.
  private val testRandom = new Random {
    override def nextDouble = 0.5
  }

  override def afterAll(): Unit = {
    etcd.close()
  }

  override def beforeEach(): Unit = {
    etcd.deleteAll()
    etcd.initializeStore(
      EtcdClient
        .KeyNamespace(s"$NAMESPACE_PREFIX${Assigner.PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX}")
    )
  }

  /**
   * Awaits a successful preferred assigner write, and return the latest preferred assigner
   * value.
   */
  private def awaitCommitted(writeResultFuture: Future[WriteResult]): PreferredAssignerValue = {
    val writeResult: WriteResult = TestUtils.awaitResult(writeResultFuture, Duration.Inf)
    writeResult match {
      case WriteResult.Committed(committedValue: PreferredAssignerValue) => committedValue
      case _ => fail(s"Unexpected write result: $writeResult")
    }
  }

  /** Helper to create an InterposingEtcdPreferredAssignerStore for testing. */
  private def createInterposedEtcdStore(
      fakeSec: FakeSequentialExecutionContext,
      incarnation: Incarnation
  ): InterposingEtcdPreferredAssignerStore = {
    InterposingEtcdPreferredAssignerStore.create(
      fakeSec,
      incarnation,
      etcd,
      EtcdClient.Config(
        EtcdClient
          .KeyNamespace(s"$NAMESPACE_PREFIX${Assigner.PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX}")
      ),
      testRandom,
      EtcdPreferredAssignerStore.DEFAULT_CONFIG
    )
  }

  /** Helper to create an EtcdPreferredAssignerDriver for testing. */
  private def createDriver(
      fakeSec: FakeSequentialExecutionContext,
      store: InterposingEtcdPreferredAssignerStore,
      driverConfig: EtcdPreferredAssignerDriver.Config
  ): EtcdPreferredAssignerDriver = {
    new EtcdPreferredAssignerDriver(
      fakeSec,
      TestTLSOptions.clientTlsOptionsOpt,
      store,
      driverConfig,
      PreferredAssignerTestHelper.noOpMembershipCheckerFactory
    )
  }

  /**
   * Helper to create an InterposingEtcdPreferredAssignerStore and EtcdPreferredAssignerDriver
   * for testing.
   */
  private def createStoreAndDriver(
      fakeSec: FakeSequentialExecutionContext,
      incarnation: Incarnation,
      assignerInfo: AssignerInfo,
      driverConfig: EtcdPreferredAssignerDriver.Config
  ): (InterposingEtcdPreferredAssignerStore, EtcdPreferredAssignerDriver) = {
    val store = createInterposedEtcdStore(fakeSec, incarnation)
    val driver = createDriver(fakeSec, store, driverConfig)
    (store, driver)
  }

  test("EtcdPreferredAssignerDriver export correct assigner role to the metric") {
    // Test plan: verify that the assigner role gauge is set correctly when the preferred assigner
    // driver is at different states. Verify the initial state is STARTUP, and test the following
    // six edges:
    //  a. STANDBY_WITHOUT_PREFERRED <-> PREFERRED
    //  b. PREFERRED <-> STANDBY
    //  c. STANDBY <-> STANDBY_WITHOUT_PREFERRED
    val fakeClock = new FakeTypedClock()
    val fakeSec = FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock))
    val assignerInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:31234")
    )
    val otherAssignerInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:31235")
    )

    // Setup: initialize the etcd client, PA store and the PA driver.
    val driverConfig = EtcdPreferredAssignerDriver.Config()
    val (store, driver): (InterposingEtcdPreferredAssignerStore, EtcdPreferredAssignerDriver) =
      createStoreAndDriver(fakeSec, Incarnation(40), assignerInfo, driverConfig)
    driver.start(assignerInfo, AssignerProtoLogger.createNoop(fakeSec))

    // Verify: verify that the initial assigner role is STARTUP.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STARTUP
    )

    // Verify: after advancing the clock, the state becomes STANDBY_WITHOUT_PREFERRED.
    fakeSec.run {
      store.blockWrites() // Block the writes to avoid the driver takeover.
      fakeClock.advanceBy(driverConfig.initialPreferredAssignerTimeout)
    }
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY_WITHOUT_PREFERRED
    )

    var predecessorGenerationOpt: Option[Generation] = None

    // a1. From STANDBY_WITHOUT_PREFERRED to PREFERRED => unblock the writes to let the driver
    //     take over.
    store.unblockWrites()
    AssertionWaiter("Assigner becomes preferred").await {
      PreferredAssignerTestHelper.getLatestKnownPreferredAssignerBlocking(driver, fakeSec) match {
        case PreferredAssignerValue.SomeAssigner(info: AssignerInfo, generation: Generation) =>
          assert(info == assignerInfo)
          predecessorGenerationOpt = Some(generation)
        case _ => fail("Expected the assigner to become preferred.")
      }
    }

    // Verify: the assigner role for the current assigner is PREFERRED.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.PREFERRED
    )

    // b1. From PREFERRED to STANDBY => let the store write the other assigner to be the
    //     preferred assigner.
    val preferredAssignerValue: PreferredAssignerValue = awaitCommitted(
      store.write(PreferredAssignerProposal(predecessorGenerationOpt, Some(otherAssignerInfo)))
    )
    preferredAssignerValue match {
      case PreferredAssignerValue.SomeAssigner(info: AssignerInfo, generation: Generation) =>
        predecessorGenerationOpt = Some(generation)
        assert(info == otherAssignerInfo)
      case _ => fail(s"Unexpected preferred assigner value: $preferredAssignerValue")
    }

    // Verify: the assigner role for the current assigner is STANDBY.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY
    )

    // b2. STANDBY to PREFERRED => let the store write this assigner to be the preferred assigner.
    val preferredAssignerValue2: PreferredAssignerValue = awaitCommitted(
      store.write(PreferredAssignerProposal(predecessorGenerationOpt, Some(assignerInfo)))
    )
    preferredAssignerValue2 match {
      case PreferredAssignerValue.SomeAssigner(info: AssignerInfo, generation: Generation) =>
        predecessorGenerationOpt = Some(generation)
        assert(info == assignerInfo)
      case _ => fail(s"Unexpected preferred assigner value: $preferredAssignerValue2")
    }

    // Verify: the assigner role for the current assigner is PREFERRED.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.PREFERRED
    )

    // a2. PREFERRED to STANDBY_WITHOUT_PREFERRED => let the preferred assigner abdicates.
    driver.sendTerminationNotice()
    AssertionWaiter("abdication completes").await {
      val preferredAssignerValue3: PreferredAssignerValue =
        PreferredAssignerTestHelper.getLatestKnownPreferredAssignerBlocking(driver, fakeSec)
      preferredAssignerValue3 match {
        case PreferredAssignerValue.NoAssigner(generation: Generation) =>
          predecessorGenerationOpt = Some(generation)
        case _ => fail(s"Unexpected preferred assigner value: $preferredAssignerValue3")
      }
    }

    // Verify: the assigner role for the current assigner is STANDBY_WITHOUT_PREFERRED.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY_WITHOUT_PREFERRED
    )

    // c1. STANDBY_WITHOUT_PREFERRED to STANDBY => let the store write the other assigner to be
    //     the preferred assigner.
    val preferredAssignerValue4: PreferredAssignerValue = awaitCommitted(
      store.write(PreferredAssignerProposal(predecessorGenerationOpt, Some(otherAssignerInfo)))
    )
    preferredAssignerValue4 match {
      case PreferredAssignerValue.SomeAssigner(info: AssignerInfo, generation: Generation) =>
        predecessorGenerationOpt = Some(generation)
        assert(info == otherAssignerInfo)
      case _ => fail(s"Unexpected preferred assigner value: $preferredAssignerValue4")
    }

    // Verify: the assigner role for the current assigner is STANDBY.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY
    )

    // c2. STANDBY to STANDBY_WITHOUT_PREFERRED => let the store write [[NoAssigner]].
    val preferredAssignerValue5: PreferredAssignerValue = awaitCommitted(
      store.write(PreferredAssignerProposal(predecessorGenerationOpt, None))
    )
    preferredAssignerValue5 match {
      case PreferredAssignerValue.NoAssigner(generation: Generation) =>
        predecessorGenerationOpt = Some(generation)
      case _ => fail(s"Unexpected preferred assigner value: $preferredAssignerValue5")
    }

    // Verify: the assigner role for the current assigner is STANDBY_WITHOUT_PREFERRED.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY_WITHOUT_PREFERRED
    )
    // Shutdown the store to avoid interference with other tests.
    TestUtils.awaitResult(store.shutdown(ShutdownOption.ABRUPT), Duration.Inf)
  }

  test("EtcdPreferredAssignerDriver records committed write latency metrics") {
    // Test plan: Verify that the write latency histogram correctly records metrics for
    // successful writes with the "committed" outcome label.

    val fakeClock = new FakeTypedClock()
    val fakeSec = FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock))
    val assignerInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:32345")
    )
    val driverConfig = EtcdPreferredAssignerDriver.Config()
    val (store, driver): (InterposingEtcdPreferredAssignerStore, EtcdPreferredAssignerDriver) =
      createStoreAndDriver(fakeSec, Incarnation(50), assignerInfo, driverConfig)
    driver.start(assignerInfo, AssignerProtoLogger.createNoop(fakeSec))

    // Track changes in committed count and sum.
    val committedCount = ChangeTracker[Long] { () =>
      PreferredAssignerTestHelper.getPreferredAssignerWriteLatencyCount("committed")
    }
    val committedSum = ChangeTracker[Double] { () =>
      PreferredAssignerTestHelper.getPreferredAssignerWriteLatencySum("committed")
    }

    // Verify that a successful write increments the "committed" metric.
    // Advance the clock to move past the initial timeout so the driver attempts to take over.
    fakeSec.run {
      store.blockWrites() // Block the automatic takeover write.
      fakeClock.advanceBy(driverConfig.initialPreferredAssignerTimeout)
    }

    // Wait for the fakeSec to process the clock advancement and the driver to transition to
    // STANDBY_WITHOUT_PREFERRED.
    PreferredAssignerTestHelper.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.STANDBY_WITHOUT_PREFERRED
    )

    // Wait for the write to be blocked before advancing the clock.
    AssertionWaiter("Write is blocked").await {
      assert(Await.result(store.getNumBlockedWrites(), Duration.Inf) == 1)
    }

    // Advance the clock to simulate that the write operation took some time, then unblock writes.
    fakeSec.run {
      fakeClock.advanceBy(100.millis)
      store.unblockWrites()
    }

    // Verify: the "committed" count should have increased by 1 and sum should have increased.
    AssertionWaiter("Committed metric increments").await {
      assert(committedCount.totalChange() == 1)
      // Verify that latency was actually recorded (sum increased).
      assert(committedSum.totalChange() > 0)
    }

    // Cleanup: shutdown the store to avoid interference with other tests.
    Await.result(store.shutdown(ShutdownOption.ABRUPT), Duration.Inf)
  }

  test("EtcdPreferredAssignerDriver records OCC failure write latency metrics") {
    // Test plan: Verify that the write latency histogram correctly records metrics for
    // OCC failures when two drivers race to become preferred.

    val fakeClock = new FakeTypedClock()
    val fakeSec = FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock))
    val driverConfig = EtcdPreferredAssignerDriver.Config()
    val store = createInterposedEtcdStore(fakeSec, Incarnation(60))

    // Track changes in OCC failure count.
    val occFailureCount = ChangeTracker[Long] { () =>
      PreferredAssignerTestHelper.getPreferredAssignerWriteLatencyCount("occ_failure")
    }
    val committedCount = ChangeTracker[Long] { () =>
      PreferredAssignerTestHelper.getPreferredAssignerWriteLatencyCount("committed")
    }

    // Create two drivers that will race to become preferred.
    val assignerInfo1: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:32400")
    )
    val assignerInfo2: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:32401")
    )
    val driver1 = createDriver(fakeSec, store, driverConfig)
    val driver2 = createDriver(fakeSec, store, driverConfig)
    driver1.start(assignerInfo1, AssignerProtoLogger.createNoop(fakeSec))
    driver2.start(assignerInfo2, AssignerProtoLogger.createNoop(fakeSec))

    // Advance the clock to trigger both drivers' takeover attempts (but block the writes).
    fakeSec.run {
      store.blockWrites()
      fakeClock.advanceBy(driverConfig.initialPreferredAssignerTimeout)
    }

    // Unblock the writes - one will commit, one will get OCC failure.
    fakeSec.run {
      store.unblockWrites()
    }

    // Verify: we should have exactly 1 commit and exactly 1 OCC failure.
    AssertionWaiter("One commit and one OCC failure").await {
      val commits = committedCount.totalChange()
      val occFailures = occFailureCount.totalChange()
      assert(commits == 1, s"Expected 1 commit, got $commits")
      assert(occFailures == 1, s"Expected 1 OCC failure, got $occFailures")
    }

    // Cleanup: shutdown the store to avoid interference with other tests.
    Await.result(store.shutdown(ShutdownOption.ABRUPT), Duration.Inf)
  }

  test("EtcdPreferredAssignerDriver records exception write latency metrics") {
    // Test plan: Verify that the write latency histogram correctly records metrics for
    // writes that fail with exceptions. We simulate this by deleting all etcd data,
    // which causes the driver to encounter exceptions when attempting writes.

    val fakeClock = new FakeTypedClock()
    val fakeSec = FakeSequentialExecutionContext.create(getSafeName, Some(fakeClock))
    val assignerInfo: AssignerInfo = AssignerInfo(
      uuid = UUID.randomUUID(),
      uri = new URI("http://localhost:32500")
    )
    val driverConfig = EtcdPreferredAssignerDriver.Config()
    val (store, driver): (InterposingEtcdPreferredAssignerStore, EtcdPreferredAssignerDriver) =
      createStoreAndDriver(fakeSec, Incarnation(70), assignerInfo, driverConfig)
    driver.start(assignerInfo, AssignerProtoLogger.createNoop(fakeSec))

    // Track changes in exception count.
    val exceptionCount = ChangeTracker[Long] { () =>
      PreferredAssignerTestHelper.getPreferredAssignerWriteLatencyCount("exception")
    }

    // Delete all data from etcd to simulate data loss.
    etcd.deleteAll()

    // Advance the clock to trigger the driver to attempt taking over, which will
    // immediately encounter exceptions due to the missing etcd data.
    fakeSec.run {
      fakeClock.advanceBy(driverConfig.initialPreferredAssignerTimeout)
    }

    // Verify: the "exception" count should have increased.
    // We use "greater than" instead of exact count because the driver may retry
    // and encounter multiple exceptions.
    AssertionWaiter("Exception metric increments").await {
      assert(
        exceptionCount.totalChange() > 0,
        s"Expected exception count to increase, but got ${exceptionCount.totalChange()}"
      )
    }

    // Cleanup: shutdown the store to avoid interference with other tests.
    Await.result(store.shutdown(ShutdownOption.ABRUPT), Duration.Inf)
  }

}
