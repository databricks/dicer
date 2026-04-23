package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future}
import scala.util.Random
import io.grpc.{Status, StatusException}
import com.databricks.caching.util.TestUtils.{TestName, assertThrow}
import com.databricks.caching.util.{
  AlertOwnerTeam,
  AssertionWaiter,
  CachingErrorCode,
  Cancellable,
  FakeSequentialExecutionContext,
  FakeTypedClock,
  LoggingStreamCallback,
  MetricUtils,
  SequentialExecutionContextPool,
  Severity,
  StatusOr,
  TypedClock
}
import com.google.protobuf.ByteString
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.dicer.common.{EtcdClientHelper, Generation, Incarnation}
import com.databricks.caching.util.EtcdClient.Version
import com.databricks.caching.util.{
  EtcdTestEnvironment,
  EtcdClient,
  EtcdTestUtils,
  InterposingJetcdWrapper
}
import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.TestUtils

class EtcdPreferredAssignerStoreSuite extends DatabricksTest with TestName {
  private val STORE_INCARNATION = Incarnation(36)
  private val NAMESPACE = EtcdClient.KeyNamespace("test-namespace")

  protected val pool: SequentialExecutionContextPool =
    SequentialExecutionContextPool.create("test-pool", numThreads = 2)
  private val etcd = EtcdTestEnvironment.create()

  override def afterAll(): Unit = {
    etcd.close()
  }

  override def beforeEach(): Unit = {
    etcd.deleteAll()
  }

  // Random used for the store, just so we have no jitter in delays.
  private val testRandom = new Random {
    override def nextDouble = 0.5
  }

  // A config with short retry intervals so that tests that uses real clocks will run quickly.
  private val CONFIG: EtcdPreferredAssignerStore.Config =
    EtcdPreferredAssignerStore.Config(50.millis, 300.millis)

  // The assigners used in the suite.
  private val ASSIGNER0 = AssignerInfo(
    UUID.fromString("00000000-1234-5678-abcd-68454e98b111"),
    new URI("http://assigner-1:1234")
  )
  private val ASSIGNER1 = AssignerInfo(
    UUID.fromString("11111111-1234-5678-abcd-68454e98b111"),
    new URI("http://assigner-1:1234")
  )
  private val ASSIGNER2 = AssignerInfo(
    UUID.fromString("22222222-1234-5678-abcd-68454e98b222"),
    new URI("http://assigner-2:4567")
  )
  private val ASSIGNER3 = AssignerInfo(
    UUID.fromString("33333333-1234-5678-abcd-68454e98b222"),
    new URI("http://assigner-3:6789")
  )
  private val ASSIGNERS = Seq(ASSIGNER0, ASSIGNER1, ASSIGNER2, ASSIGNER3)

  /**
   * Creates an etcd client and store connecting to [[etcd]].
   *
   * Ensures that the returned [[EtcdStore]] is initialized.
   */
  private def createClientWithInitializedStoreMetadata(): EtcdClient = {
    val client = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    ensureStoreMetadataInitialized(client)
    client
  }

  /**
   * Creates an [[EtcdClient]] with an interposing jetcd wrapper, and ensures that the etcd instance
   * pointed to by the returned client has its metadata intialized.
   */
  private def createErrorInjectionClientWithInitializedStoreMetadata(
      clock: TypedClock): (EtcdClient, InterposingJetcdWrapper) = {
    val (client, errorInjector) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(clock, EtcdClient.Config(NAMESPACE))
    ensureStoreMetadataInitialized(client)
    (client, errorInjector)
  }

  /**
   * Attempts to initialize the metadata in the store pointed to by `client`, ignoring any errors.
   *
   * Useful for tests where multiple clients are created and all require an initialized store.
   */
  private def ensureStoreMetadataInitialized(client: EtcdClient): Unit = {
    // Initialize the store manually so we can ignore any errors (some tests create multiple clients
    // to the same store).
    try {
      TestUtils.awaitResult(client.initializeVersionHighWatermarkUnsafe(Version.MIN), Duration.Inf)
    } catch {
      case e: StatusException if e.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
      case e: Throwable =>
        throw e
    }
  }

  /**
   * Writes the preferred assigner with an expected predecessor and returns the committed
   * assignment. If an unexpected OCC failure occurs, the test fails.
   * */
  private def writePreferredAssignerWithExpectedPredecessor(
      store: EtcdPreferredAssignerStore,
      proposal: PreferredAssignerProposal): PreferredAssignerValue = {
    TestUtils.awaitResult(store.write(proposal), Duration.Inf) match {
      case WriteResult.Committed(committedPreferredAssigner) => committedPreferredAssigner
      case WriteResult.OccFailure(actualGeneration: Generation) =>
        // Should not happen.
        val expectedGenerationOpt: Option[Generation] = proposal.predecessorGenerationOpt
        fail(
          s"Test bug: Unexpected OCC failure writing preferred assigner. " +
          s"Test specified $expectedGenerationOpt, but actual value is $actualGeneration"
        )
    }
  }

  test("EtcdPreferredAssignerStore.Config must be within the valid range") {
    // Test plan: verify that the store configuration is within the valid range.
    // 1. The minWatchRetryDelay must be non-negative.
    assertThrow[IllegalArgumentException]("") {
      EtcdPreferredAssignerStore.Config(
        minWatchRetryDelay = Duration.Zero - 1.nanos,
        maxWatchRetryDelay = 1.second
      )
    }

    // 2. The maxWatchRetryDelay must be larger than the minWatchRetryDelay.
    assertThrow[IllegalArgumentException]("") {
      EtcdPreferredAssignerStore.Config(
        minWatchRetryDelay = 1.second,
        maxWatchRetryDelay = 1.second
      )
    }

    // 3. The maxWatchRetryDelay must be less than 5 minutes.
    assertThrow[IllegalArgumentException]("") {
      EtcdPreferredAssignerStore.Config(
        minWatchRetryDelay = 1.second,
        maxWatchRetryDelay = 6.minutes
      )
    }

    // 4. A valid config.
    val validConfig = EtcdPreferredAssignerStore.Config(5.second, 2.minutes)
    assert(validConfig.minWatchRetryDelay == 5.second)
    assert(validConfig.maxWatchRetryDelay == 2.minutes)
  }

  test("Store writes preferred assigners with OCC checks") {
    // Test plan: Verify that `EtcdPreferredAssignerStore.write` works as expected:
    // 0. The latest preferred assigner is empty before any writes.
    // 1. A write with the wrong (non-empty) predecessor assigner fails with an OCC failure.
    // 2. A write with the correct (empty) predecessor succeeds, and the cached preferred assigner
    //    is updated accordingly.
    // 3. Another write with a non-empty predecessor also succeeds, even if the ticker is
    //    unchanged. The generation number will be the current ticker plus 1.
    // 3a. Another write with a correct, non-empty predecessor also succeeds after time elapses.
    // 4. A write with the wrong predecessor generation number fails with an OCC failure.
    // 5. A write with None as the predecessor, where a predecessor in fact does exist, also fails
    //    with an OCC failure, and the cached preferred assigner is unchanged.
    // 6. A write with the correct predecessor and an empty proposed preferred assigner succeeds,
    //    and the preferred assigner is updated to None.
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val client: EtcdClient = createClientWithInitializedStoreMetadata()
    val store =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, client, testRandom, CONFIG, sec)

    // Verify: `store.storeIncarnation` returns the expected value.
    assert(store.storeIncarnation == STORE_INCARNATION)

    // The latest known preferred assigner is empty before any writes.
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.isEmpty)

    // A write with the wrong predecessor assigner fails with an Occ failure.
    val clock: FakeTypedClock = sec.getClock
    val wrongPredecessor = PreferredAssignerValue.SomeAssigner(
      ASSIGNER2,
      EtcdClientHelper.createGenerationFromVersion(Version(STORE_INCARNATION.value, 1))
    )
    val proposal1 = PreferredAssignerProposal(Some(wrongPredecessor.generation), Some(ASSIGNER1))
    val writeResult1: WriteResult =
      TestUtils.awaitResult(store.write(proposal1), Duration.Inf)
    assert(writeResult1 == WriteResult.OccFailure(Generation.EMPTY))
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.isEmpty)

    // The write with the correct (empty) predecessor succeeds, and the cached preferred assigner
    // gets updated.
    clock.advanceBy(2.seconds)

    var commitTime = clock.instant().toEpochMilli
    val proposal2 = PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER2))
    var previousPreferredAssigner: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(store, proposal2)
    val committed1: PreferredAssignerValue = previousPreferredAssigner
    assert(previousPreferredAssigner.generation.number.value == commitTime)
    val expectedPreferredAssigner2 =
      PreferredAssignerValue.SomeAssigner(ASSIGNER2, previousPreferredAssigner.generation)
    assert(previousPreferredAssigner == expectedPreferredAssigner2)
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == expectedPreferredAssigner2)

    // Even if the ticker says that no time has passed, an additional write of ASSIGNER3 with the
    // correct predecessor will succeeds, with a generation number will just of the current ticker
    // plus 1.
    commitTime = clock.instant().toEpochMilli + 1

    val proposal3 =
      PreferredAssignerProposal(Some(previousPreferredAssigner.generation), Some(ASSIGNER3))
    previousPreferredAssigner = writePreferredAssignerWithExpectedPredecessor(store, proposal3)
    assert(previousPreferredAssigner.generation.number.value == commitTime)
    val expectedPreferredAssigner3 =
      PreferredAssignerValue.SomeAssigner(ASSIGNER3, previousPreferredAssigner.generation)
    assert(previousPreferredAssigner == expectedPreferredAssigner3)
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == expectedPreferredAssigner3)

    // Advance time and ensure we can write another preferred assigner.
    clock.advanceBy(1.second)
    commitTime = clock.instant().toEpochMilli

    val proposal3a =
      PreferredAssignerProposal(Some(previousPreferredAssigner.generation), Some(ASSIGNER1))
    previousPreferredAssigner = writePreferredAssignerWithExpectedPredecessor(store, proposal3a)
    assert(previousPreferredAssigner.generation.number.value == commitTime)
    val expectedPreferredAssigner3a =
      PreferredAssignerValue.SomeAssigner(ASSIGNER1, previousPreferredAssigner.generation)
    assert(previousPreferredAssigner == expectedPreferredAssigner3a)
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == expectedPreferredAssigner3a)

    // A write with a stale predecessor generation fails with an Occ failure.
    val wrongGenerationPredecessor = PreferredAssignerValue.SomeAssigner(
      ASSIGNER1,
      generation = committed1.generation
    )
    val proposal4 =
      PreferredAssignerProposal(Some(wrongGenerationPredecessor.generation), Some(ASSIGNER1))

    val writeResult4: WriteResult =
      TestUtils.awaitResult(store.write(proposal4), Duration.Inf)
    assert(writeResult4 == WriteResult.OccFailure(previousPreferredAssigner.generation))
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == expectedPreferredAssigner3a)

    // A write with None as the predecessor also fails with an Occ failure.
    val proposal5 = PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER1))
    val writeResult5: WriteResult =
      TestUtils.awaitResult(store.write(proposal5), Duration.Inf)
    assert(writeResult5 == WriteResult.OccFailure(previousPreferredAssigner.generation))
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == expectedPreferredAssigner3a)

    // A write with the correct predecessor and an empty proposed preferred assigner.
    val proposal6 =
      PreferredAssignerProposal(
        Some(previousPreferredAssigner.generation),
        newPreferredAssignerInfoOpt = None
      )
    val preferredAssignerValue6: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(store, proposal6)
    assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == preferredAssignerValue6)
  }

  test("write with a predecessor from different store incarnation triggers critical error") {
    // Test plan: verify that the write with a predecessor from different store incarnation
    // triggers critical state machine error.
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val client: EtcdClient = createClientWithInitializedStoreMetadata()
    val storeIncarnation: Incarnation = Incarnation(42)
    val store =
      EtcdPreferredAssignerStore.create(storeIncarnation, client, testRandom, CONFIG, sec)

    var currentStateMachineErrorCount: Int = MetricUtils.getPrefixLoggerErrorCount(
      Severity.CRITICAL,
      CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR(AlertOwnerTeam.CachingTeam),
      prefix = ""
    )

    // A write with a predecessor from a different store incarnation should fail.
    for (differentIncarnationNumber: Int <- Seq(40, 41, 43, 44)) {
      val differentIncarnation = Incarnation(differentIncarnationNumber)
      val proposal = PreferredAssignerProposal(
        Some(Generation(differentIncarnation, 1)),
        Some(ASSIGNER1)
      )
      store.write(proposal)
      AssertionWaiter("Wait for state machine error to be logged").await {
        val newErrorCount: Int = MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR(AlertOwnerTeam.CachingTeam),
          prefix = ""
        )
        assert(newErrorCount == currentStateMachineErrorCount + 1)
        currentStateMachineErrorCount = newErrorCount
      }
    }
  }

  test("Writes with no predecessor overwrites prior incarnation but not later incarnation") {
    // Test plan: verify that a write with no predecessor can overwrite a preferred assigner
    // value from a previous store incarnation.
    // Additionally, verify that a write with no predecessor cannot overwrite a preferred assigner
    // value from a later store incarnation. In this case, an OccFailure is expected.
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val client: EtcdClient = createClientWithInitializedStoreMetadata()
    val storeIncarnation0: Incarnation = Incarnation(42)
    val storeIncarnation1: Incarnation = Incarnation(44)
    val store0 =
      EtcdPreferredAssignerStore.create(storeIncarnation0, client, testRandom, CONFIG, sec)
    val store1 =
      EtcdPreferredAssignerStore.create(storeIncarnation1, client, testRandom, CONFIG, sec)

    // Let store0 write a preferred assigner.
    val proposal0 = PreferredAssignerProposal(None, Some(ASSIGNER1))
    writePreferredAssignerWithExpectedPredecessor(store0, proposal0)

    // If the new proposal is from a newer store incarnation, a write that does not name the
    // existing record as the predecessor should succeed because the store has retry logic
    // internally.
    val proposal = PreferredAssignerProposal(None, Some(ASSIGNER2))
    val newValue: PreferredAssignerValue = EtcdTestUtils.retryOnceOnWatermarkError {
      writePreferredAssignerWithExpectedPredecessor(store1, proposal)
    }

    sec.getClock.advanceBy(10.seconds)
    // A write from a future store incarnation should fail with OccFailure where the actual
    // generation is the generation of the latest preferred assigner.
    val proposal2 = PreferredAssignerProposal(None, Some(ASSIGNER1))
    EtcdTestUtils.retryOnceOnWatermarkError {
      TestUtils.awaitResult(store0.write(proposal2), Duration.Inf) match {
        case WriteResult.Committed(committedPreferredAssigner) =>
          // Should not happen.
          fail(s"Expected OCC failure, but got $committedPreferredAssigner")
        case WriteResult.OccFailure(actualGeneration: Generation) =>
          val expectedGeneration: Generation = newValue.generation
          assert(actualGeneration == expectedGeneration)
      }
    }
  }

  test("Watching preferred assigner") {
    // Test plan: Verify that the watch initially observes the most recent value written to the
    // store prior to the watch and then observes subsequent writes.
    val etcdClient = createClientWithInitializedStoreMetadata()
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val clock = sec.getClock

    // Create a store to do the writes.
    val writingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)

    // Write a set of values prior to the watch.
    var commitTime = clock.instant().toEpochMilli
    var prevCommittedAssignerOpt: Option[PreferredAssignerValue] = None
    for (assignerInfo: AssignerInfo <- ASSIGNERS) {
      clock.advanceBy(2.seconds)
      commitTime = clock.instant().toEpochMilli

      val preferredAssignerProposal =
        PreferredAssignerProposal(
          prevCommittedAssignerOpt.map((_: PreferredAssignerValue).generation),
          Some(assignerInfo)
        )
      prevCommittedAssignerOpt = Some(
        writePreferredAssignerWithExpectedPredecessor(writingStore, preferredAssignerProposal)
      )
    }

    // Create another store for the watch.
    val watchingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)

    val callback = new LoggingStreamCallback[PreferredAssignerValue](sec)
    val cancellable: Cancellable = watchingStore.getPreferredAssignerWatchCell.watch(callback)

    // The watch should initially observe only most recent value written to the store prior to the
    // watch.
    val expectedLog = mutable.ArrayBuffer[StatusOr[PreferredAssignerValue]]()
    expectedLog += StatusOr.success(prevCommittedAssignerOpt.get)
    AssertionWaiter("Wait for preferred assigner watch to observe last written value").await {
      assert(callback.getLog == expectedLog)
    }

    // Write additional values and ensure that they are observed by the watch.
    for (assignerInfo: AssignerInfo <- ASSIGNERS.reverse) {
      clock.advanceBy(2.seconds)
      commitTime = clock.instant().toEpochMilli

      val preferredAssignerProposal =
        PreferredAssignerProposal(
          prevCommittedAssignerOpt.map((_: PreferredAssignerValue).generation),
          Some(assignerInfo)
        )
      prevCommittedAssignerOpt = Some(
        writePreferredAssignerWithExpectedPredecessor(writingStore, preferredAssignerProposal)
      )
      expectedLog += StatusOr.success(prevCommittedAssignerOpt.get)
    }
    AssertionWaiter("Wait for preferred assigner watch to observe additional values").await {
      assert(callback.getLog == expectedLog)
    }

    cancellable.cancel(Status.CANCELLED)
  }

  test("Store retries on Etcd watch failures") {
    // Test plan: Verify that the store retries the watch with exponential backoff after a watch
    // failure and that the watch eventually resumes and observes the correct values.
    val etcdClient = createClientWithInitializedStoreMetadata()
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val clock = sec.getClock

    // Create a store to do the writes.
    val writingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)

    // Write a set of values prior to the watch.
    var prevCommittedAssigner = writePreferredAssignerWithExpectedPredecessor(
      writingStore,
      PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER2))
    )

    // Create another store for the watch.
    val (client2, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      createErrorInjectionClientWithInitializedStoreMetadata(
        clock
      )
    val watchingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, client2, testRandom, CONFIG, sec)

    val callback = new LoggingStreamCallback[PreferredAssignerValue](sec)
    val cancellable: Cancellable = watchingStore.getPreferredAssignerWatchCell.watch(callback)

    // The watch should initially observe only most recent value written to the store prior to the
    // watch.
    val expectedLog = mutable.ArrayBuffer[StatusOr[PreferredAssignerValue]]()
    expectedLog += StatusOr.success(prevCommittedAssigner)
    AssertionWaiter("Wait for preferred assigner watch to observe last written value").await {
      assert(callback.getLog == expectedLog)
    }

    // Write an additional value but fail the watch before the value can be observed.
    prevCommittedAssigner = writePreferredAssignerWithExpectedPredecessor(
      writingStore,
      PreferredAssignerProposal(Some(prevCommittedAssigner.generation), Some(ASSIGNER1))
    )

    // Fail future watch calls and the current one with a status exception.
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))
    jetcdWrapper.failCurrentWatch(new StatusException(Status.UNAVAILABLE))

    AssertionWaiter("Wait for watch failure to be injected with exponential backoff").await {
      // Wait for at least 3 watch retries and ensure that each is separated from the previous one
      // by at least the expected backoff value. See also the "Store driver retries on watch
      // failures" test which uses a fake clock and exercises the driver directly to verify exact
      // exponential backoff behavior and reset after success.
      val watchIntervals: List[FiniteDuration] =
        jetcdWrapper.getCallTimes
          .sliding(2)
          .map { case Seq(time1, time2) => time2 - time1 }
          .toList

      assert(watchIntervals.size >= 3)
      for (i <- 0 until watchIntervals.size - 1) {
        assert(watchIntervals(i) >= CONFIG.minWatchRetryDelay * Math.pow(2, i))
      }
    }
    jetcdWrapper.stopFailingGets()

    expectedLog += StatusOr.success(prevCommittedAssigner)
    AssertionWaiter("Wait for preferred assigner watch to observe additional values").await {
      assert(callback.getLog == expectedLog)
    }

    cancellable.cancel(Status.CANCELLED)
  }

  test("Store retries and recovers from Etcd corruption") {
    // Test plan: Write corrupted data to Etcd and verify that the store retries the watch until
    // the corruption is resolved and then observes the corrected values.
    val etcdClient = createClientWithInitializedStoreMetadata()
    val sec = pool.createExecutionContext(s"$getSafeName-store")

    // Create a store to do the writes.
    val writingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)
    val prevCommittedAssigner = writePreferredAssignerWithExpectedPredecessor(
      writingStore,
      PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER2))
    )

    // Create another store for the watch.
    val etcdClient2 = createClientWithInitializedStoreMetadata()
    val watchingStore =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient2, testRandom, CONFIG, sec)

    val callback = new LoggingStreamCallback[PreferredAssignerValue](sec)
    val cancellable: Cancellable = watchingStore.getPreferredAssignerWatchCell.watch(callback)

    // Observe the initial value.
    val expectedLog = mutable.ArrayBuffer[StatusOr[PreferredAssignerValue]]()
    expectedLog += StatusOr.success(prevCommittedAssigner)
    AssertionWaiter("Wait for preferred assigner watch to observe the initial value").await {
      assert(callback.getLog == expectedLog)
    }

    // Write a corrupted value.
    val version: Version =
      EtcdClientHelper.getVersionFromNonLooseGeneration(prevCommittedAssigner.generation)

    val newVersion: Version = version.copy(lowBits = version.lowBits.value + 1)
    TestUtils.awaitResult(
      etcdClient.write(
        key = EtcdPreferredAssignerStore.ForTest.key,
        version = newVersion,
        value = ByteString.copyFrom("corrupted_value".getBytes),
        previousVersionOpt = Some(version),
        isIncremental = false // No incremental writes are needed.
      ),
      Duration.Inf
    )

    // Wait a little while to make sure the store does not observe the corrupted value.
    Thread.sleep(100)
    assert(
      watchingStore.getPreferredAssignerWatchCell.getLatestValueOpt.get == prevCommittedAssigner
    )

    // Now write a valid value.
    val newVersion2: Version = version.copy(lowBits = version.lowBits.value + 2)
    val correctedPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      ASSIGNER3,
      EtcdClientHelper.createGenerationFromVersion(newVersion2)
    )
    TestUtils.awaitResult(
      etcdClient.write(
        key = EtcdPreferredAssignerStore.ForTest.key,
        version = newVersion2,
        value = ByteString.copyFrom(correctedPreferredAssigner.toSpecProto.toByteArray),
        previousVersionOpt = Some(newVersion),
        isIncremental = false // No incremental writes are needed.
      ),
      Duration.Inf
    )

    // Wait for the corrected preferred assigner to be seen by the watcher.
    expectedLog += StatusOr.success(correctedPreferredAssigner)
    AssertionWaiter("Wait for preferred assigner watch to observe corrected value").await {
      assert(callback.getLog == expectedLog)
    }

    cancellable.cancel(Status.CANCELLED)
  }

  test("informPreferredAssigner incorporates preferred assigner when newer") {
    // Test plan: Verify that informPreferredAssigner incorporates a preferred assigner that is
    // newer than the latest preferred assigner known to the store, but that it ignores older
    // preferred assigners or preferred assigners with a different store incarnation.
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val clock = sec.getClock

    val (client1, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      createErrorInjectionClientWithInitializedStoreMetadata(clock)
    val client2: EtcdClient = createClientWithInitializedStoreMetadata()
    val store1 =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, client1, testRandom, CONFIG, sec)
    val store2 =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, client2, testRandom, CONFIG, sec)

    // Write ASSIGNER1 using store2.
    val proposal1 = PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER1))
    val committed1: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(store2, proposal1)

    // Watch from store1.
    val callback = new LoggingStreamCallback[PreferredAssignerValue](sec)
    val cancellable: Cancellable = store1.getPreferredAssignerWatchCell.watch(callback)

    // Wait for the preferred assigner to be seen by the watch, this is done to ensure the watch to
    // etcd is established.
    AssertionWaiter("Wait for preferred assigner watch to observe the initial value").await {
      assert(callback.getLog == Seq(StatusOr.success(committed1)))
    }

    // Fail the latest watch on store1.
    logger.info("Failing current and future store1 `jetcd.watch` calls.")
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))
    jetcdWrapper.failCurrentWatch(new StatusException(Status.UNAVAILABLE))

    // Write ASSIGNER2 using store2.
    val commitTime = clock.instant().toEpochMilli
    val proposal2 = PreferredAssignerProposal(
      predecessorGenerationOpt = Some(committed1.generation),
      Some(ASSIGNER2)
    )
    val committed2: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(store2, proposal2)
    assert(
      committed2 == PreferredAssignerValue.SomeAssigner(
        ASSIGNER2,
        committed2.generation
      )
    )

    // Store1 should not learn about the new preferred assigner because its watches are failing.
    assert(callback.getLog == Seq(StatusOr.success(committed1))) // committed2 is not known yet.

    // Inform store1 about the new preferred assigner.
    store1.informPreferredAssigner(committed2)

    // store1 should now know about assignment2.
    AssertionWaiter("Wait for store1 to learn about the new preferred assigner").await {
      assert(store1.getPreferredAssignerWatchCell.getLatestValueOpt.get == committed2)
    }

    // Inform store1 about an older preferred assigner; nothing should change.
    val version = EtcdClientHelper.getVersionFromNonLooseGeneration(committed2.generation)
    val olderPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      ASSIGNER3,
      EtcdClientHelper.createGenerationFromVersion(
        Version(STORE_INCARNATION.value, version.lowBits.value - 1)
      )
    )
    store1.informPreferredAssigner(olderPreferredAssigner)
    // Wait a short time to make sure the preferred assigner doesn't change.
    Thread.sleep(100)
    assert(store1.getPreferredAssignerWatchCell.getLatestValueOpt.get == committed2)

    // Inform store1 about a preferred assigner from the wrong store incarnation and a higher key
    // version; nothing should change.
    val oldIncarnationPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      ASSIGNER3,
      EtcdClientHelper.createGenerationFromVersion(
        Version(highBits = 0, commitTime + 5)
      )
    )
    store1.informPreferredAssigner(oldIncarnationPreferredAssigner)
    assert(store1.getPreferredAssignerWatchCell.getLatestValueOpt.get == committed2)

    cancellable.cancel(Status.CANCELLED)
  }

  test("Loose store incarnation causes exception") {
    // Test plan: verify that creating a store with a loose store incarnation value causes an
    // exception to be thrown.
    assertThrow[IllegalArgumentException]("must be non-loose") {
      EtcdPreferredAssignerStore.create(
        storeIncarnation = Incarnation.MIN,
        client = createClientWithInitializedStoreMetadata(),
        random = testRandom,
        config = CONFIG,
        sec = pool.createExecutionContext(s"$getSafeName-store")
      )
    }
  }

  test("InterceptableEtcdPreferredAssignerStore.blockWrites() and unblockWrites()") {
    // Test plan: verify that `InterceptableEtcdPreferredAssignerStore.blockWrites()` and
    // `unbloackWrites()` work as expected. Specifically:
    //  1. blockWrites() blocks the write() method.
    //  2. unblockWrites() executes the previously blocked writes and unblocks future writes.
    //  3. informPreferredAssigner() is not blocked by blockWrites().

    // Set up: create a store and set an initial PA value.
    val clock = new FakeTypedClock
    val sec = FakeSequentialExecutionContext.create(getSafeName, Some(clock))
    val etcdClient: EtcdClient = createClientWithInitializedStoreMetadata()
    val store = InterposingEtcdPreferredAssignerStore.create(
      sec,
      STORE_INCARNATION,
      etcd,
      EtcdClient.Config(NAMESPACE),
      testRandom,
      CONFIG
    )
    var currentPreferredAssigner: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(
        store,
        PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER2))
      )
    clock.advanceBy(5.seconds)

    // Verify: `store.blockWrites()` blocks writes.
    TestUtils.awaitResult(store.blockWrites(), Duration.Inf)
    val proposal1 =
      PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER3))
    val writeFuture1: Future[WriteResult] = store.write(proposal1)
    clock.advanceBy(1.minute)
    // Wait a little while to make sure the write doesn't actually happen.
    Thread.sleep(100)
    assert(!writeFuture1.isCompleted)

    // Verify: after `unblockWrites` is called the write is unblocked.
    TestUtils.awaitResult(store.unblockWrites(), Duration.Inf)
    val preferredAssignerValue1: PreferredAssignerValue =
      TestUtils.awaitResult(writeFuture1, Duration.Inf) match {
        case WriteResult.Committed(value: PreferredAssignerValue) => value
        case WriteResult.OccFailure(_) =>
          fail("Expected write to succeed, but it failed with an OCC failure.")
      }

    // Verify: call `store.write` succeeds.
    clock.advanceBy(5.seconds)
    val proposal2 =
      PreferredAssignerProposal(Some(preferredAssignerValue1.generation), Some(ASSIGNER1))
    currentPreferredAssigner = writePreferredAssignerWithExpectedPredecessor(store, proposal2)

    // Call `store.blockWrites()` and then write two proposals.
    TestUtils.awaitResult(store.blockWrites(), Duration.Inf)
    clock.advanceBy(5.seconds)
    val proposal3 =
      PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER3))
    val writeFuture3: Future[WriteResult] = store.write(proposal3)
    clock.advanceBy(5.seconds)
    val proposal4 =
      PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER2))
    val writeFuture4: Future[WriteResult] = store.write(proposal4)

    // Verify: the writes are blocked.
    clock.advanceBy(1.minute)
    assert(!writeFuture3.isCompleted && !writeFuture4.isCompleted)

    // Have another store write a new preferred assigner and inform this store of the new value.
    val store2 =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)
    val newPreferredAssigner: PreferredAssignerValue = TestUtils.awaitResult(
      store2.write(
        PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER1))
      ),
      Duration.Inf
    ) match {
      case WriteResult.Committed(value: PreferredAssignerValue) => value
      case _ => fail("Expected write to be committed, but it wasn't.")
    }

    // Verify: we can still inform the store of a new preferred assigner while writes are blocked.
    store.informPreferredAssigner(newPreferredAssigner)
    AssertionWaiter("Wait for the new value to be observed").await {
      assert(store.getPreferredAssignerWatchCell.getLatestValueOpt.get == newPreferredAssigner)
    }
    // Wait a little while to make sure the write doesn't actually happen.
    Thread.sleep(100)
    assert(!writeFuture3.isCompleted && !writeFuture4.isCompleted)

    // Verify: after `unblockWrites` is called the writes are unblocked, and both writes fail due
    // to OCC failures.
    TestUtils.awaitResult(store.unblockWrites(), Duration.Inf)
    clock.advanceBy(1.minute)
    assert(TestUtils.awaitResult(writeFuture3, Duration.Inf).isInstanceOf[WriteResult.OccFailure])
    assert(TestUtils.awaitResult(writeFuture4, Duration.Inf).isInstanceOf[WriteResult.OccFailure])
  }

  test("InterceptableEtcdPreferredAssignerStore.stopAsync") {
    // Test plan: Verify that `InterceptableEtcdPreferredAssignerStore.stopAsync` fails the etcd
    // watch and blocks writes.
    // Set up: create an interposing store and set an initial PA value.
    val clock = new FakeTypedClock
    val sec = FakeSequentialExecutionContext.create(getSafeName, Some(clock))
    val etcdClient: EtcdClient = createClientWithInitializedStoreMetadata()
    val store1 = InterposingEtcdPreferredAssignerStore.create(
      sec,
      STORE_INCARNATION,
      etcd,
      EtcdClient.Config(NAMESPACE),
      testRandom,
      CONFIG
    )
    val initialPreferredAssigner: PreferredAssignerValue =
      writePreferredAssignerWithExpectedPredecessor(
        store1,
        PreferredAssignerProposal(predecessorGenerationOpt = None, Some(ASSIGNER2))
      )
    clock.advanceBy(5.seconds)

    // Set up: create another store for writing.
    val store2 =
      EtcdPreferredAssignerStore.create(STORE_INCARNATION, etcdClient, testRandom, CONFIG, sec)
    val proposal =
      PreferredAssignerProposal(Some(initialPreferredAssigner.generation), Some(ASSIGNER1))

    // Let store2 write a new preferred assigner.
    TestUtils.awaitResult(store2.write(proposal), Duration.Inf) match {
      case WriteResult.Committed(value: PreferredAssignerValue) => value
      case _ => fail("Expected write to be committed, but it wasn't.")
    }

    // Verify: the interposing store observes the new value.
    val currentPreferredAssigner: PreferredAssignerValue =
      AssertionWaiter("interposing store observes the new value").await {
        assert(
          store1.getPreferredAssignerWatchCell.getLatestValueOpt
          == store2.getPreferredAssignerWatchCell.getLatestValueOpt
        )
        assert(store1.getPreferredAssignerWatchCell.getLatestValueOpt.isDefined)
        store1.getPreferredAssignerWatchCell.getLatestValueOpt.get
      }

    // Synchronously stop the `store1`.
    TestUtils.awaitResult(store1.shutdown(ShutdownOption.ABRUPT), Duration.Inf)

    val proposal1 =
      PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER3))
    // Let store1 write a new preferred assigner.
    val writeFuture1: Future[WriteResult] = store1.write(proposal1)
    clock.advanceBy(1.minute)

    // Wait a little while to make sure the write doesn't actually happen.
    Thread.sleep(100)
    // Verify: the write didn't execute.
    assert(!writeFuture1.isCompleted)

    val proposal2 =
      PreferredAssignerProposal(Some(currentPreferredAssigner.generation), Some(ASSIGNER2))
    // Let store2 write a new preferred assigner.
    TestUtils.awaitResult(store2.write(proposal2), Duration.Inf) match {
      case WriteResult.Committed(value: PreferredAssignerValue) => value
      case _ => fail("Expected write to be committed, but it didn't.")
    }
    // Wait a little while to make sure the watch doesn't actually happen.
    Thread.sleep(100)
    // Verify: store1 didn't observe the new value.
    assert(store1.getPreferredAssignerWatchCell.getLatestValueOpt.get == currentPreferredAssigner)
  }

}
