package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Random, Success}
import io.grpc.{Status, StatusException}
import com.databricks.api.proto.dicer.common.DiffAssignmentP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{
  AlertOwnerTeam,
  AssertionWaiter,
  CachingErrorCode,
  Cancellable,
  FakeSequentialExecutionContext,
  FakeTypedClock,
  LogCapturer,
  LoggingStreamCallback,
  MetricUtils,
  SequentialExecutionContext,
  Severity,
  StateMachineOutput,
  StatusOr,
  TestStateMachineDriver,
  TestUtils,
  TickerTime,
  TypedClock
}
import com.google.protobuf.ByteString
import com.databricks.dicer.assigner.EtcdStoreStateMachine.DriverAction.PerformEtcdWrite
import com.databricks.dicer.assigner.EtcdStoreStateMachine.{
  DriverAction,
  EtcdWriteRequest,
  Event,
  StoreErrorCode
}
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.common.DiffAssignmentSliceMap.Partial
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  DiffAssignment,
  EtcdClientHelper,
  Generation,
  Incarnation,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceAssignment
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.SliceMap
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.caching.util.EtcdClient.{
  ReadResponse,
  Version,
  VersionedValue,
  WatchArgs,
  WatchEvent,
  WriteResponse
}
import com.databricks.caching.util.{
  EtcdTestEnvironment,
  EtcdClient,
  EtcdTestUtils,
  InterposingJetcdWrapper
}
import io.prometheus.client.CollectorRegistry

import scala.util.matching.Regex
import com.databricks.caching.util.TestUtils

class EtcdStoreSuite extends CommonStoreSuiteBase {
  private val etcd = EtcdTestEnvironment.create()

  private val STORE_INCARNATION = Incarnation(34)

  private val NAMESPACE = EtcdClient.KeyNamespace("test-namespace")

  // Random used for the store, just so we have no jitter in delays.
  private val testRandom = new Random {
    override def nextDouble = 0.5
  }

  // Default config to be used for tests.
  private val defaultConfig = EtcdStoreConfig(STORE_INCARNATION, 5.seconds, 30.seconds)

  // A config with short retry intervals for tests that use real clocks and that need to wait for
  // retries.
  private val fastRetryConfig: EtcdStoreConfig =
    EtcdStoreConfig(STORE_INCARNATION, 50.millis, 300.millis)

  override def afterAll(): Unit = {
    etcd.close()
  }

  override def beforeEach(): Unit = {
    etcd.deleteAll()

    // All operations in our storage scheme requires that this metadata exists in etcd; write it
    // here once to avoid repeating it in all tests.
    etcd.initializeStore(NAMESPACE)
  }

  /**
   * Creates an [[EtcdClient]] with an interposing jetcd wrapper, and ensures that the etcd instance
   * pointed to by the returned client has its metadata intialized.
   */
  private def createEtcdClientWithInterposingJetcdWrapper(
      clock: TypedClock): (EtcdClient, InterposingJetcdWrapper) = {
    val (client, interposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(clock, EtcdClient.Config(NAMESPACE))
    (client, interposingJetcdWrapper)
  }

  override def createStore(sec: SequentialExecutionContext): EtcdStore = {
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    EtcdStore.create(sec, client, EtcdStoreConfig.create(STORE_INCARNATION), testRandom)
  }

  override def destroyStore(store: Store): Unit = {
    store match {
      case store: EtcdStore => TestUtils.awaitResult(store.forTest.cleanup(), Duration.Inf)
      case _ => fail("Expected EtcdStore")
    }
  }

  /** Assert [[DiffAssignment]] has full SliceMap. */
  private def assertFullSliceMap(diffAssignment: DiffAssignment): Unit = {
    diffAssignment.sliceMap match {
      case _: Partial => fail(s"Expected full SliceMap")
      case _ =>
    }
  }

  /** Assert [[DiffAssignment]] has partial SliceMap. */
  private def assertPartialSliceMap(diffAssignment: DiffAssignment): Unit = {
    diffAssignment.sliceMap match {
      case _: Partial =>
      case _ => fail(s"Expected partial SliceMap")
    }
  }

  /** Returns the immediate next version to `version`. */
  private def getSuccessorVersion(version: Version): Version = {
    version.copy(lowBits = version.lowBits.value + 1)
  }

  /** Returns the number of error logs recorded for etcd assignment store corruption. */
  private def getEtcdCorruptionErrorLogCount(): Int =
    MetricUtils.getPrefixLoggerErrorCount(
      Severity.CRITICAL,
      CachingErrorCode.ETCD_ASSIGNMENT_STORE_CORRUPTION,
      prefix = ""
    )

  test("EtcdStore writes incremental assignments") {
    // Test plan: Write assignment for a target and watch the etcd client to verify the write
    // operations happening in etcd. Perform another write and verify the bytes written to etcd only
    // contain the diff assignment and not the full assignment.

    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    val target = Target("target")
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    val callback =
      new LoggingStreamCallback[WatchEvent](pool.createExecutionContext("stream-cb"))

    // Setup watch on the etcd client.
    client.watch(WatchArgs(target.toParseableDescription), callback)

    // Write the proposal.
    val assignment: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal)
      )
    assert(
      assignment == proposal
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          assignment.generation
        )
    )

    // NOTE: We don't match the exact log, since we might have different order of versioned value
    // and causal event, if the write happens before causal event.
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment.generation),
              ByteString.copyFrom(assignment.toDiff(Generation.EMPTY).toProto.toByteArray)
            )
          )
        )
      )
    }

    // Created another proposal with same SliceMap as previous proposal.
    val proposal2 =
      ProposedAssignment(predecessorOpt = Some(assignment), proposal.sliceMap)
    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          proposal2
        )
      )

    val diff2: DiffAssignment = assignment2.toDiff(assignment.generation)

    // Expect the diff to only contain a Gap entry.
    diff2.sliceMap match {
      case partial: Partial =>
        assert(partial.sliceMap.entries.head == GapEntry.Gap[SliceAssignment]("" -- ∞))
      case _ => fail("Expected diff SliceMap to contain a gap entry.")
    }

    // Verify the only the diff is reported by the Etcd watch.
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation),
              ByteString.copyFrom(diff2.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Watch for the written assignments.
    assert(awaitAssignment(store, target) == assignment2)

    destroyStore(store)
  }

  test("Store returns INTERNAL error when writing assignment with unsafe generation") {
    // Test plan: Verify that the store returns an INTERNAL error when writing an assignment where
    // the chosen generation's incarnation is insufficiently high to surpass the store's etcd high
    // version watermark, and that a retry write succeeds leveraging the version lower bound
    // returned by EtcdClient from the original failure. Verify this by using two stores with the
    // same store incarnation, writing an initial assignment to the first store for target1 which
    // will bump the watermark, and then attempting an initial assignment write to the second store
    // for target2, which should fail because the second store doesn't know about the first store's
    // write. Then try the write again on the second store and verify that it succeeds.

    val target1 = Target("target1")
    val target2 = Target("target2")
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val store1 =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )
    val store2 =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    // Write assignment for target1 through the first store, which should succeed, causing a bump in
    // the version high watermark.
    val assignment1: Assignment =
      awaitCommitted(
        store1.writeAssignment(target1, shouldFreeze = false, proposal)
      )

    // Verify that a write to target2 via store2 returns an INTERNAL error since store2 will choose
    // an assignment incarnation that is too low.
    val statusException = assertThrow[StatusException](
      s"failed due to use of an insufficiently high version"
    ) {
      TestUtils.awaitResult(
        store2
          .writeAssignment(
            target2,
            shouldFreeze = false,
            proposal
          ),
        Duration.Inf
      )
    }
    assert(statusException.getStatus.getCode == Status.INTERNAL.getCode)

    // Retry the write and verify that it succeeds (store2 should have learned of the max used key
    // incarnation from the write failure).
    val assignment2: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target2,
          shouldFreeze = false,
          proposal
        )
      )

    // The exact generation values are not particularly important here (the new bound learned
    // from the initial write is chosen by EtcdClient rather than the store); the important thing
    // is that the second write succeeds on the first try.
    assert(assignment2.generation > assignment1.generation)

    destroyStore(store1)
    destroyStore(store2)
  }

  test("Store chooses assignment generations that track time") {
    // Test plan: Verify that the assignment generations that are chosen by the store track the
    // current number of milliseconds since the Unix Epoch. Verify this by using a fake clock,
    // writing assignments to the store, and verifying that the chosen generations track time.

    val target = Target("target1")
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val fakeSec = FakeSequentialExecutionContext.create(s"$getSafeName-store")
    val store =
      EtcdStore.create(
        fakeSec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )

    // Write initial assignment and verify that the incarnation and generation are chosen according
    // to time in milliseconds since the Unix Epoch.
    val proposal1: ProposedAssignment = createProposal(predecessorOpt = None)
    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal1)
      )
    assert(assignment1.generation.number.value == fakeSec.getClock.instant().toEpochMilli)

    // Advance time and update the assignment, verifying that the chosen generation number still
    // tracks time (and that the incarnation remains unchanged).
    fakeSec.getClock.advanceBy(10.minutes)
    val proposal2: ProposedAssignment =
      createProposal(predecessorOpt = Some(assignment1))
    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal2)
      )
    // Updating existing assignments should not change the incarnation.
    assert(assignment2.generation.incarnation == assignment1.generation.incarnation)
    assert(assignment2.generation.number.value == fakeSec.getClock.instant().toEpochMilli)

    // Now update the assignment without advancing time, and verifying that in this case the
    // generation number is still advanced (should be the successor)
    val proposal3: ProposedAssignment =
      createProposal(predecessorOpt = Some(assignment2))
    val assignment3: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal3)
      )
    assert(assignment3.generation.incarnation == assignment2.generation.incarnation)
    // The clock has not advanced, so the chosen generation should be the successor.
    assert(assignment3.generation.number.value == assignment2.generation.number.value + 1L)

    destroyStore(store)
  }

  test("Store watches assignments without writes") {
    // Test plan: Do couple of writes to etcd for a target. Create another store and establish
    // a callback to be informed of assignments for a particular target. Verify the callback sees
    // last written assignment.

    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store1 = EtcdStore.create(
      pool.createExecutionContext("store1"),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )

    val target = Target("target")

    // Seed an initial assignment, which should be supplied to the callback when it is added.
    val assignment1: Assignment =
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // Another assignment
    val assignment2: Assignment =
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment1))
        )
      )

    // Create another store for the watch.
    val store2 = EtcdStore.create(
      pool.createExecutionContext("store2"),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    // Register a callback. Use a different SequentialExecutionContext than the store, just in case
    // there are any assumptions lurking in the code about a shared SEC.
    val callbackSec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-callback")
    val callback = new LoggingStreamCallback[Assignment](callbackSec)
    val cancellable: Cancellable = store2.watchAssignments(target, callback)

    // The latest assignment should be visible eventually.
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()
    expectedLog.append(StatusOr.success(assignment2))
    AssertionWaiter("Wait for latest assignment").await {
      assert(callback.getLog == expectedLog)
    }

    // Cancel the watch.
    cancellable.cancel(Status.CANCELLED)

    destroyStore(store1)
    destroyStore(store2)
  }

  test("Store retries on Etcd watch failures") {
    // Test plan: Verify that the store correctly retries until success on watch failures, and that
    // the retries are separated by at least the expected exponential backoff time. Unlike the
    // "Store driver retries on watch failures" test, we use a real clock rather than a fake one,
    // and use the code as real production code would, rather than injecting events into the driver.
    // This means we don't test the retry behavior quite as precisely but do so much more
    // realistically.
    //
    // In more detail, we verify the correct retry behavior as follows:
    // - Do couple of writes and establish a watch.
    // - Inject a failure for the watch and prevent it from being re-established.
    // - Perform another write assignment while the watch is broken.
    // - Expect the store to keep scheduling watch with backoff. Verify the backoffs are separated
    //   in time by at least the expected interval.
    // - Allow the watch to succeed again and expected the store to learn about the new assignment.
    val target = Target("target")

    // EtcdClient to be used for store with failure injection.
    val sec1 = pool.createExecutionContext(s"$getSafeName-store1")
    val (client1, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      createEtcdClientWithInterposingJetcdWrapper(sec1.getClock)

    // Create a store for the watch.
    val config: EtcdStoreConfig = fastRetryConfig
    val store1 = EtcdStore.create(sec1, client1, config, testRandom)

    // Create another store to perform write operations outside store1.
    val store2: EtcdStore = EtcdStore.create(
      pool.createExecutionContext(s"$getSafeName-store2"),
      etcd.createEtcdClient(EtcdClient.Config(NAMESPACE)),
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )

    // Seed an initial assignment, which should be supplied to the callback when it is added.
    val assignment1: Assignment =
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // Another assignment
    val assignment2: Assignment =
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment1))
        )
      )

    // Register a callback.
    val callbackSec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-callback")
    val callback = new LoggingStreamCallback[Assignment](callbackSec)

    // Start watching the target in store1.
    val cancellable: Cancellable = store1.watchAssignments(target, callback)

    // The latest assignment should be visible eventually.
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()
    expectedLog.append(StatusOr.success(assignment2))
    AssertionWaiter("Wait for assignment2").await {
      assert(callback.getLog == expectedLog)
    }

    // Perform a write using store2 and wait for it show up in store1, this is done to ensure the
    // watch to etcd is established.
    val assignment3 = awaitCommitted(
      store2.writeAssignment(
        target,
        shouldFreeze = false,
        createProposal(predecessorOpt = Some(assignment2))
      )
    )
    assert(awaitAssignment(store1, target, assignment2.generation) == assignment3)

    // Prevent future watches from being established and fail the current watch.
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))
    jetcdWrapper.failCurrentWatch(new StatusException(Status.UNAVAILABLE))

    // Commit another assignment via store2, after retries this should show up in store1.
    val assignment4 = awaitCommitted(
      store2.writeAssignment(
        target,
        shouldFreeze = false,
        createProposal(predecessorOpt = Some(assignment3))
      )
    )

    // Make sure the store1 is retrying the watch with exponential backoff.
    AssertionWaiter(s"Wait for watch failure to be injected with exponential backoff").await {
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
        assert(watchIntervals(i) >= config.minWatchRetryDelay * Math.pow(2, i))
      }
    }

    jetcdWrapper.stopFailingGets()

    // Store1 should learn about assignment3 and assignment4 after there are no more watch failures.
    expectedLog.append(StatusOr.success(assignment3))
    expectedLog.append(StatusOr.success(assignment4))
    AssertionWaiter("Wait for assignment4").await {
      assert(callback.getLog == expectedLog)
    }

    // Cancel the watch.
    cancellable.cancel(Status.CANCELLED)

    destroyStore(store2)
    destroyStore(store1)
  }

  test("Watch failures resets pre-causal history") {
    // Test plan: Inject failures for the watch, perform few writes from store2. Have the watch on
    // store1 catch up again and assert only assignments after the causal event are distributed.

    val target = Target("target")

    // EtcdClient to be used for store with failure injection.
    val sec1 = pool.createExecutionContext("store1")
    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      createEtcdClientWithInterposingJetcdWrapper(sec1.getClock)

    // Create a store for the watch.
    val store1 =
      EtcdStore.create(
        sec1,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )

    // Create another store to perform write operations around store1.
    val client2 = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store2 = EtcdStore.create(
      pool.createExecutionContext("store2"),
      client2,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )

    // Seed an initial assignment, which should be supplied to the callback when it is added.
    val assignment1: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // Another assignment
    val assignment2: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment1))
        )
      )

    // Register a callback.
    val callbackSec: SequentialExecutionContext =
      pool.createExecutionContext(s"$getSafeName-callback")
    val callback = new LoggingStreamCallback[Assignment](callbackSec)

    // Start watching the target in store1.
    val cancellable: Cancellable = store1.watchAssignments(target, callback)

    // The latest assignment should be visible eventually.
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()
    expectedLog.append(StatusOr.success(assignment2))
    AssertionWaiter("Wait for assignment2").await {
      assert(callback.getLog == expectedLog)
    }

    // Perform a write using store2 and wait for it show up in store1, this is done to ensure the
    // watch to etcd is established. assignment2 would have been received before pre-causal event.
    val assignment3 = awaitCommitted(
      store2.writeAssignment(
        target,
        shouldFreeze = false,
        createProposal(predecessorOpt = Some(assignment2))
      )
    )
    expectedLog.append(StatusOr.success(assignment3))
    assert(awaitAssignment(store1, target, assignment2.generation) == assignment3)
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))
    jetcdWrapper.failCurrentWatch(new StatusException(Status.UNAVAILABLE))

    var lastAssignment = assignment3

    // Do 4 more writes with 10 slices each. Writes 0 and 1 will be incremental, 2 will be full
    // assignment and 3 will be incremental again.
    for (_ <- 0 until 4) {
      lastAssignment = awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(lastAssignment))
        )
      )
    }

    // Stop failing gets. After time passes, another successful watch attempt should be established.
    // The slice history has completely changed now, we expect pre-causal assignment to be reset
    // after failure and so only last assignment (instead of all the 4 assignments above) is
    // published to the watchers.
    jetcdWrapper.stopFailingGets()
    expectedLog.append(StatusOr.success(lastAssignment))
    AssertionWaiter("Wait latest assignment").await {
      assert(callback.getLog == expectedLog)
    }

    cancellable.cancel(Status.CANCELLED)

    destroyStore(store2)
    destroyStore(store1)
  }

  test("EtcdStoreStateMachine requests for watch only on the first target request") {
    // Test plan: Interact with EtcdStoreStateMachine directly, send a write request and expect both
    // PerformEtcdWrite and PerformEtchWatch driver actions. Send multiple EnsureTargetTracked
    // events to the state machine and expect PerformEtcdWatch driver action if a watch does not
    // exist.
    val target = Target("target")

    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val clock = sec.getClock
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    // PerformEtcdWatch and PerformEtcdWrite is reported on write request since this is a new
    // target.
    val writeRequestEvent = Event.WriteRequest(
      target,
      Promise[Store.WriteAssignmentResult](),
      shouldFreeze = false,
      ProposedAssignment(predecessorOpt = None, createProposal())
    )

    val writeRequestOutput =
      storeDriver.onEvent(clock.tickerTime(), clock.instant(), writeRequestEvent)

    assert(writeRequestOutput.nextTickerTime == TickerTime.MAX)
    assert(
      writeRequestOutput.actions.exists {
        case _: DriverAction.PerformEtcdWrite => true
        case _ => false
      }
    )

    assert(
      writeRequestOutput.actions.exists {
        case _: DriverAction.PerformEtcdWatch => true
        case _ => false
      }
    )

    // A watch is not requested since one is already established.
    assert(
      storeDriver.onEvent(clock.tickerTime(), clock.instant(), Event.EnsureTargetTracked(target)) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // Send causal watch event.
    clock.advanceBy(30.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // A watch is not requested since one is already established.
    clock.advanceBy(1.minute)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
  }

  test("Store driver retries on watch failures") {
    // Test plan: Interact with store driver directly, report successful watch event, then
    // report two watch failure and expect watch action to be performed with exponential backoff.
    // Report another successful watch event and expect exponential backoff is reset.
    val target = Target("target")
    val config = EtcdStoreConfig(STORE_INCARNATION, 5.seconds, 30.seconds)
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val clock = sec.getClock
    val stateMachine = new EtcdStoreStateMachine(testRandom, config)
    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    // Expect PerformEtcdWatch action on EnsureTargetTracked event.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // Report a watch failure and expect the driver to be called after 5 seconds i.e. retry
    // duration.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 5.seconds, Seq.empty)
    )

    // Advance clock by 5 seconds and expect a watch action.
    clock.advanceBy(5.seconds)

    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a watch failure after another 2 seconds and expect the state machine to be called
    // after 10 seconds i.e. retry duration with backoff.
    clock.advanceBy(2.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 10.seconds, Seq.empty)
    )

    // Advance the state machine by another 10 seconds and expect a watch action to be performed.
    clock.advanceBy(10.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a watch failure after another 3 seconds, expect state machine to be called after 20
    // seconds.
    clock.advanceBy(3.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 20.seconds, Seq.empty)
    )

    // Advance the state machine by another 20 seconds and expect a watch action to be performed.
    clock.advanceBy(20.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // Report a watch failure after another 5 seconds, expect state machine to be called after 5
    // seconds ie. retry duration is reset.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 5.seconds, Seq.empty)
    )
  }

  test("Store driver retries watch for each target independently") {
    // Test plan: Interact with Store driver directly, establish watch for two targets, report
    // failure for target1, report another failure for target1 after 5 seconds. Report a watch
    // failure for target2. Verify both targets are retried independently with same retry policy.

    val target1 = Target("target1")
    val target2 = Target("target2")
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val clock = sec.getClock
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    // Expect PerformEtcdWatch action on EnsureTargetTracked event for both targets.
    for (target <- Seq(target1, target2)) {
      assert(
        storeDriver.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.EnsureTargetTracked(target)
        ) ==
        StateMachineOutput(
          TickerTime.MAX,
          Seq(
            DriverAction.PerformEtcdWatch(
              target,
              EtcdStoreStateMachine.forTest.WATCH_DURATION,
              EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
            )
          )
        )
      )

      // Report a successful watch event and expect no action to be requested from driver.
      assert(
        storeDriver.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.EtcdWatchEvent(target, WatchEvent.Causal)
        ) ==
        StateMachineOutput(TickerTime.MAX, Seq.empty)
      )
    }

    // Report a watch failure for target1 and expect the driver to be called after 5 seconds i.e.
    // retry duration.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target1, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 5.seconds, Seq.empty)
    )

    // Advance state machine by 5 seconds and expect a watch action for target1.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target1,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report another watch failure for target1 after another 2 seconds and expect the state machine
    // to be called after 10 seconds i.e. retry duration with backoff.
    clock.advanceBy(2.seconds)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target1, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 10.seconds, Seq.empty)
    )

    // Report a watch failure for target2.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target2, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 5.seconds, Seq.empty)
    )

    // Expect a watch for target2 after 5 seconds.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        clock.tickerTime() + 5.seconds,
        Seq(
          DriverAction.PerformEtcdWatch(
            target2,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Advance 5 seconds, and expect a watch to be performed.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target1,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a watch failure for target1 again.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target1, Status.UNAVAILABLE)
      ) ==
      StateMachineOutput(clock.tickerTime() + 20.seconds, Seq.empty)
    )

    // Advance 20 seconds, and expect a watch for target1.
    clock.advanceBy(20.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target1,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Advance 60 seconds and expect more no watches for target1 and target2.
    clock.advanceBy(60.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
  }

  test("Writes with no predecessor overwrites prior incarnation assignment") {
    // Test plan: verify that a write can succeed when the specified predecessor is None even if
    // an assignment exists from a prior incarnation.
    val store0 = EtcdStore.create(
      pool.createExecutionContext(f"$getSafeName-0"),
      etcd.createEtcdClient(EtcdClient.Config(NAMESPACE)),
      EtcdStoreConfig.create(Incarnation(16)),
      testRandom
    )
    val store1 = EtcdStore.create(
      pool.createExecutionContext(f"$getSafeName-1"),
      etcd.createEtcdClient(EtcdClient.Config(NAMESPACE)),
      EtcdStoreConfig.create(Incarnation(20)),
      testRandom
    )
    val target: Target = Target(getSafeName)
    val sliceAssignments: SliceMap[ProposedSliceAssignment] = createProposal()
    awaitCommitted(
      store0.writeAssignment(
        target,
        shouldFreeze = false,
        ProposedAssignment(predecessorOpt = None, sliceMap = sliceAssignments)
      )
    )

    // Specify predecessorOpt = None even though an assignment exists in etcd from the earlier
    // write by store0, and verify that we succeed in committing over the store0 write.
    EtcdTestUtils.retryOnceOnWatermarkError {
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          ProposedAssignment(predecessorOpt = None, sliceMap = sliceAssignments)
        )
      )
    }
  }

  test("Overwrite existing, lower loose incarnation") {
    // Test plan: verify that the store can overwrite data from a lower, loose incarnation. While
    // it's unexpected that we would find a loose incarnation assignment in etcd, it should not
    // wedge or otherwise cause the store to fail in its new incarnation.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val target = Target(getSafeName)

    // EtcdStore requires non-loose incarnations, so we must use EtcdClient directly to write the
    // loose incarnation data.
    val looseIncarnation = Incarnation(7)
    assert(looseIncarnation.isLoose)
    TestUtils.awaitResult(
      client.write(
        target.toParseableDescription,
        Version(looseIncarnation.value, 42),
        value = ByteString.copyFromUtf8("does-not-matter"),
        previousVersionOpt = None,
        isIncremental = false
      ),
      Duration.Inf
    )

    val higherNonLooseIncarnation = Incarnation(8)
    assert(higherNonLooseIncarnation.isNonLoose)
    val store = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(higherNonLooseIncarnation),
      testRandom
    )

    // Even though the lower incarnation assignment has a loose incarnation, we should still be able
    // to overwrite with a fresh proposal in the higher incarnation.
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)
    EtcdTestUtils.retryOnceOnWatermarkError {
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal)
      )
    }
  }

  test("EtcdStore writes full assignments when slice history exceeds threshold") {
    // Test plan: Perform first write, expect it have full SliceMap. Perform another few writes that
    // are supposed to be incremental. Once the number of slices has reached threshold expect a full
    // write to be performed.

    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    val target = Target("target")
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    val callback =
      new LoggingStreamCallback[WatchEvent](pool.createExecutionContext("stream-cb"))

    // Setup watch on the etcd client.
    client.watch(WatchArgs(target.toParseableDescription), callback)

    // Write the proposal.
    val assignment: Assignment =
      awaitCommitted(
        store.writeAssignment(target, shouldFreeze = false, proposal)
      )
    val diff: DiffAssignment = assignment.toDiff(Generation.EMPTY)
    assertFullSliceMap(diff)

    // NOTE: We don't match the exact log, since we might have different order of versioned value
    // and causal event, if the write happens before causal event.
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment.generation),
              ByteString.copyFrom(diff.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Keep track of predecessor, updates are performed only on the thread used for the test case.
    var predecessor = assignment

    // Perform 4 writes, adding 40 slices for the target.
    for (idx <- 0 until 4) {
      logger.info(s"Perform updating $idx")
      // Create a new proposal with 10 SliceMaps.
      val assignment2: Assignment =
        awaitCommitted(
          store.writeAssignment(
            target,
            shouldFreeze = false,
            createProposal(Some(predecessor))
          )
        )

      val diff2: DiffAssignment = assignment2.toDiff(predecessor.generation)

      // Expect the diff to be partial.
      assertPartialSliceMap(diff2)

      // Verify the diff is reported by the Etcd watch.
      AssertionWaiter(s"Wait for assignment in callback for update $idx").await {
        assert(
          callback.getLog.contains(
            StatusOr.success(
              WatchEvent.VersionedValue(
                EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation),
                ByteString.copyFrom(diff2.toProto.toByteArray)
              )
            )
          )
        )
      }

      // Watch for the written assignments.
      assert(awaitAssignment(store, target) == assignment2)
      predecessor = assignment2
    }

    // Created another proposal with same SliceMap as previous proposal.
    val assignment3: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(Some(predecessor))
        )
      )

    // We have 50 slices now, the next write should be a full assignment.
    val diff3: DiffAssignment = assignment3.toDiff(Generation.EMPTY)
    assertFullSliceMap(diff3)

    // Verify the only the diff is reported by the Etcd watch.
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment3.generation),
              ByteString.copyFrom(diff3.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Watch for the written assignments.
    assert(awaitAssignment(store, target) == assignment3)

    destroyStore(store)
  }

  test("EtcdStore writes full assignments when the total number of assignments exceeds threshold") {
    // Test plan: verify that EtcdStore writes a full assignment when the total number of
    // assignments in etcd exceeds its respective threshold, even if the total number of stored
    // slices is under its own limit.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    val target = Target(getSafeName)

    val callback =
      new LoggingStreamCallback[WatchEvent](pool.createExecutionContext("stream-cb"))
    client.watch(WatchArgs(target.toParseableDescription), callback)

    // Since the stored slice count threshold is based on the number of slices in the assignment,
    // use an assignment with a large number of slices to avoid accidentally exceeding it.
    val proposalSlices: SliceMap[ProposedSliceAssignment] =
      createRandomProposal(
        numSlices = 100,
        Vector(createTestSquid("pod0"), createTestSquid("pod1"), createTestSquid("pod2")),
        numMaxReplicas = 1,
        rng
      )

    // The initial write is always an full write:
    val initial: Assignment = awaitCommitted(
      store.writeAssignment(
        target,
        shouldFreeze = false,
        ProposedAssignment(predecessorOpt = None, sliceMap = proposalSlices)
      )
    )

    // Write minimal assignment deltas (to avoid triggering the slice count threshold) and verify
    // that we get incremental writes until we reach the total assignment threshold, at which point
    // we should get a compacting write. We should compact at 50 total entries, so perform 49 more
    // writes here to get us to the threshold.
    var predecessor: Assignment = initial
    for (_ <- 0 until 49) {
      val proposal = ProposedAssignment(Some(predecessor), proposalSlices)
      val committed: Assignment = awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          proposal
        )
      )

      val expectedDiff: DiffAssignment = committed.toDiff(predecessor.generation)
      assertPartialSliceMap(expectedDiff)

      // Verify the only the diff is reported by the Etcd watch.
      AssertionWaiter("Wait for assignment in callback").await {
        assert(
          callback.getLog.contains(
            StatusOr.success(
              WatchEvent.VersionedValue(
                EtcdClientHelper.getVersionFromNonLooseGeneration(committed.generation),
                ByteString.copyFrom(expectedDiff.toProto.toByteArray)
              )
            )
          )
        )
      }

      predecessor = committed
    }

    // At the assignment entry threshold, another write should trigger a full assignment even with
    // another minimal diff.
    val committed: Assignment = awaitCommitted(
      store.writeAssignment(
        target,
        shouldFreeze = false,
        ProposedAssignment(Some(predecessor), proposalSlices)
      )
    )

    // We should get the full assignment on the watch stream.
    val expectedFullDiff: DiffAssignment = committed.toDiff(Generation.EMPTY)
    assertFullSliceMap(expectedFullDiff)
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(committed.generation),
              ByteString.copyFrom(expectedFullDiff.toProto.toByteArray)
            )
          )
        )
      )
    }

    // And we should have compacted the rest of the entries in the store.
    val readResponse: ReadResponse =
      TestUtils.awaitResult(client.read(target.toParseableDescription, limit = 100), Duration.Inf)
    assert(!readResponse.more)
    assert(readResponse.values.size == 1)
    assert(
      readResponse.values.head == VersionedValue(
        EtcdClientHelper.getVersionFromNonLooseGeneration(committed.generation),
        ByteString.copyFrom(expectedFullDiff.toProto.toByteArray)
      )
    )
  }

  test("EtcdStore writes full assignments when in-memory history is stale") {
    // Test plan: Perform first write using store1, expect it have full SliceMap. Perform another
    // write and store1 and expect it have partial SliceMap. Perform another write using store2 with
    // stale in-memory assignment and expect it have full SliceMap.

    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store1 = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    val target = Target("target")
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    val callback =
      new LoggingStreamCallback[WatchEvent](pool.createExecutionContext("stream-cb"))

    // Setup watch on the etcd client.
    client.watch(WatchArgs(target.toParseableDescription), callback)

    // Write the proposal.
    val assignment: Assignment =
      awaitCommitted(
        store1.writeAssignment(target, shouldFreeze = false, proposal)
      )
    val diff: DiffAssignment = assignment.toDiff(Generation.EMPTY)
    assertFullSliceMap(diff)

    // NOTE: We don't match the exact log, since we might have different order of versioned value
    // and causal event, if the write happens before causal event.
    AssertionWaiter("Wait for assignment in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment.generation),
              ByteString.copyFrom(diff.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Write a new proposal with assignment.
    val assignment2: Assignment =
      awaitCommitted(
        store1.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(Some(assignment))
        )
      )
    val diff2: DiffAssignment = assignment2.toDiff(assignment.generation)

    // Expect the diff to have partial SliceMap.
    assertPartialSliceMap(diff2)

    // Verify the diff is reported by the Etcd watch.
    AssertionWaiter(s"Wait for assignment in callback for update").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation),
              ByteString.copyFrom(diff2.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Watch for the written assignments.
    assert(awaitAssignment(store1, target) == assignment2)

    // Create a new store that writes to the same incarnation as store1 and perform a write. Since
    // this is the first write with this store the in-memory version should not be initialized
    // because watch has not been called on the target and so we expect a full write to be
    // performed.
    val store2 = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )

    // Write a new proposal with assignment2.
    val assignment3: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(Some(assignment2))
        )
      )

    val diff3: DiffAssignment = assignment3.toDiff(Generation.EMPTY)

    // Expect the diff to have full SliceMap.
    assertFullSliceMap(diff3)

    // Verify the diff is reported by the Etcd watch.
    AssertionWaiter("Wait for assignment3 in callback").await {
      assert(
        callback.getLog.contains(
          StatusOr.success(
            WatchEvent.VersionedValue(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment3.generation),
              ByteString.copyFrom(diff3.toProto.toByteArray)
            )
          )
        )
      )
    }

    // Watch for the written assignments.
    assert(awaitAssignment(store1, target, assignment2.generation) == assignment3)
    assert(awaitAssignment(store2, target, assignment2.generation) == assignment3)

    destroyStore(store2)
    destroyStore(store1)
  }

  test("Write after learning from informAssignment does a full assignment") {
    // Test plan: Write an assigment to store2, then inject a failure in watch. Perform
    // a write to a different store. Verify store2 doesn't know about new assignment
    // because its watches are failing, then directly inform store2 about the new
    // assignment and verify it distributes the assignment. Since, slice history is unknown now,
    // the next write handled by store2 should trigger a full assignment.
    val sec = pool.createExecutionContext(getSafeName)
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store = EtcdStore.create(
      sec,
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )
    val (errorInjectionClient, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        sec.getClock,
        EtcdClient.Config(NAMESPACE)
      )

    val store2 =
      EtcdStore.create(
        pool.createExecutionContext(s"$getSafeName-1"),
        errorInjectionClient,
        fastRetryConfig,
        testRandom
      )
    val target = Target(getSafeName)

    // Register a watcher so that we can verify that the expected assignments are incorporated by
    // the store.
    val callback = new LoggingStreamCallback[Assignment](sec)
    store2.watchAssignments(target, callback)
    val expectedLog = mutable.ArrayBuffer[StatusOr[Assignment]]()

    // Simulate a normal write to initialize the store.
    val assignment1: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )
    expectedLog.append(StatusOr.success(assignment1))

    AssertionWaiter("Wait for assignment1").await {
      assert(callback.getLog == expectedLog)
    }

    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment1))
        )
      )

    // Store1 should learn about assignment2 via watch.
    expectedLog.append(StatusOr.success(assignment2))
    assert(awaitAssignment(store2, target, assignment1.generation) == assignment2)

    // We know the watch is established. Prevent future watches from being established by failing
    // get operations, and then fail the current watch. (We do things in that order to avoid a
    // race.)
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))
    jetcdWrapper.failCurrentWatch(new StatusException(Status.UNAVAILABLE))

    // Write another assignment via store, store2 shouldn't learn about it.
    val assignment3: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment2))
        )
      )

    // The watch is failing, so store1 doesn't know about the written assignment
    AssertionWaiter("Wait for assignment1 again").await { () =>
      assert(callback.getLog == expectedLog)
    }

    // Inform the store about an assignment written by another store
    store2.informAssignment(target, assignment3)

    // store1 should now distribute assignment2.
    expectedLog.append(StatusOr.success(assignment3))
    AssertionWaiter("Wait for assignment3").await { () =>
      assert(callback.getLog == expectedLog)
    }

    jetcdWrapper.stopFailingGets()

    val etcdCallback =
      new LoggingStreamCallback[WatchEvent](pool.createExecutionContext("stream-cb"))

    // Setup watch on the etcd client.
    client.watch(WatchArgs(target.toParseableDescription), etcdCallback)

    // Perform a write using store1 and expect full assignment is written because the SliceHistory
    // is unknown.
    val assignment4: Assignment =
      awaitCommitted(
        store2.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(Some(assignment3))
        )
      )

    val diff4: DiffAssignment = assignment4.toDiff(Generation.EMPTY)

    // Expect the diff to have full SliceMap.
    assertFullSliceMap(diff4)

    // Verify the diff is reported by the Etcd watch.
    etcdCallback.waitForPredicate(
      el =>
        el ==
        WatchEvent.VersionedValue(
          EtcdClientHelper.getVersionFromNonLooseGeneration(assignment4.generation),
          ByteString.copyFrom(diff4.toProto.toByteArray)
        ),
      0
    )
  }

  test("InformAssignment and perform write") {
    // Test plan: Similar to prior test, but informs the state machine directly (and does not write
    // to etcd). Inform the driver about new assignment via watch. Inform the driver about a new
    // assignment via informAssignment and expect it to be distributed. Request a new write and
    // expect it be a full write since the slice history is unknown because watch has not caught up,
    // since we don't report watch events to the driver.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    // Ensure the target is watched by the state machine.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // Report a watch event with first assignment and expect it be distributed.
    val diff1: DiffAssignment = assignment1.toDiff(Generation.EMPTY)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            ByteString.copyFrom(diff1.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment1))
      )
    )

    // Pretend the watch is delayed and there is new assignment2 written by another store and
    // we learn about it from informAssignment.
    val assignment2 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 2)
          )
      )
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.InformAssignmentRequest(target, assignment2)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment2))
      )
    )

    val promise = Promise[WriteAssignmentResult]()
    val proposal3 = createProposal(predecessorOpt = Some(assignment2))

    // Inform the driver about write request with predecessor as assignment2. This should initiate a
    // full assignment write since the slice history is unknown because the watch hasn't caught up,
    // since we haven't reported any other watch events.
    val writeOutput = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(
        target,
        promise,
        shouldFreeze = false,
        proposal3
      )
    )

    assert(writeOutput.nextTickerTime == TickerTime.MAX)
    assert(writeOutput.actions.size == 1)

    writeOutput.actions.head match {
      case PerformEtcdWrite(
          _,
          EtcdWriteRequest(_, _, _, _, value, previousVersion, isIncremental)
          ) =>
        val diffAssignment =
          DiffAssignment.fromProto(DiffAssignmentP.parseFrom(value.toByteArray))
        assert(
          previousVersion
            .contains(EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation))
        )
        assert(!isIncremental)
        assertFullSliceMap(diffAssignment)
      case _ => fail("expected PerformEtcdWrite")
    }
  }

  test("InformAssignment, restore watch and perform write") {
    // Test plan: Inform the driver about the new assignment via watch. Inform the driver about
    // another assignment via informAssignment, and then have the watch learn the same assignment.
    // Expect the following write to be an incremental assignment since the slice history should be
    // restored.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    clock.advanceBy(2.seconds)

    val diff1: DiffAssignment = assignment1.toDiff(Generation.EMPTY)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            ByteString.copyFrom(diff1.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment1))
      )
    )

    // Pretend the watch is delayed and there is new assignment2 written by another store and
    // we learn about it from informAssignment.

    val assignment2 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 2)
          )
      )
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.InformAssignmentRequest(target, assignment2)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment2))
      )
    )

    // Let the watch catch up to assignment2, we should have recovered assignment history.
    val diff2: DiffAssignment = assignment2.toDiff(assignment1.generation)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation),
            ByteString.copyFrom(diff2.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq.empty
      )
    )

    val promise = Promise[WriteAssignmentResult]()

    val proposal3 = createProposal(predecessorOpt = Some(assignment2))
    // Inform the driver about write request with predecessor as assignment2. This should initiate a
    // incremental assignment write since the slice history is known because the watch has caught
    // up after informAssignment.
    val writeOutput = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(
        target,
        promise,
        shouldFreeze = false,
        proposal3
      )
    )

    assert(writeOutput.nextTickerTime == TickerTime.MAX)
    assert(writeOutput.actions.size == 1)

    writeOutput.actions.head match {
      case PerformEtcdWrite(
          _,
          EtcdWriteRequest(_, _, _, _, value, previousVersion, isIncremental)
          ) =>
        val diffAssignment =
          DiffAssignment.fromProto(DiffAssignmentP.parseFrom(value.toByteArray))
        assert(
          previousVersion
            .contains(EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation))
        )
        assert(isIncremental)
        assertPartialSliceMap(diffAssignment)
      case _ => fail("expected PerformEtcdWrite")
    }
  }

  test("Store can learn new assignment generation lower bounds from initial write successes") {
    // Test plan: Verify that the store updates its initial assignment generation lower bound when
    // writes succeed for initial assignments (which must have an generation that surpasses the
    // bound). Verify this by writing an initial assignment for one target, and
    // then following that up with an assignment for another target, which should succeed on the
    // first try.

    // TODO(<internal bug>): <internal bug> is for supporting retries in the store. Once that is implemented,
    // this test could pass inadvertently, thanks to retries which cause the store to learn about
    // incarnation lower bounds from write failures (and not necessarily via initial assignment
    // write successes, as intended). When retries are implemented this test should also be updated.

    val target1 = Target("target1")
    val target2 = Target("target2")
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val store =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )

    val assignment1: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target1,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    val assignment2: Assignment =
      awaitCommitted(
        store.writeAssignment(
          target2,
          shouldFreeze = false,
          createProposal(predecessorOpt = None)
        )
      )

    // The exact generation values are not particularly important here (the new bound learned
    // from the initial write is chosen by EtcdClient rather than the store); the important thing
    // is that the second write succeeds on the first try.
    assert(assignment2.generation > assignment1.generation)

    destroyStore(store)
  }

  test("Store can learn new key generation lower bounds from initial assignment write failures") {
    // Test plan: Verify that the store updates its initial assignment generation lower bound when
    // writes fail due to use of an insufficiently high incarnation. Verify this by writing an
    // initial assignment for one target, then attempting an initial assignment write for another
    // through a different store which doesn't know about the first. This second initial assignment
    // write should fail due to use of an insufficiently high incarnation, but update the lower
    // bound so that the subsequent write succeeds.

    val target1 = Target("target1")
    val target2 = Target("target2")
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store1 =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )
    val store2 =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    awaitCommitted(
      store1.writeAssignment(target1, shouldFreeze = false, proposal)
    )
    assertThrow[StatusException](
      "failed due to use of an insufficiently high version"
    ) {
      TestUtils.awaitResult(
        store2
          .writeAssignment(target2, shouldFreeze = false, proposal),
        Duration.Inf
      )
    }
    awaitCommitted(
      store2.writeAssignment(target2, shouldFreeze = false, proposal)
    )

    destroyStore(store1)
    destroyStore(store2)
  }

  test("Store retries on data corruption from EtcdClient watch") {
    // Test plan: Write an assignment, write invalid data using EtcdClient, expect the watch to be
    // cancelled and retried. Write a new full assignment using EtcdClient and expect the store to
    // learn it.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val store1 = EtcdStore.create(
      pool.createExecutionContext(getSafeName),
      client,
      EtcdStoreConfig.create(STORE_INCARNATION),
      testRandom
    )

    val target = Target("target")
    val proposal: ProposedAssignment = createProposal(predecessorOpt = None)

    // Write the proposal.
    val assignment1: Assignment =
      awaitCommitted(
        store1.writeAssignment(target, shouldFreeze = false, proposal)
      )

    // Waits for Etcd response to be committed.
    def awaitEtcdCommitted(writeResponseFut: Future[WriteResponse]): Unit = {
      TestUtils.awaitResult(writeResponseFut, Duration.Inf) match {
        case WriteResponse.Committed(_) =>
        case result => fail(s"Unexpected result: $result")
      }
    }

    // Current version of assignment in Etcd.
    val assignment1Version: Version =
      EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation)

    // Write an invalid value to cause data corruption in etcd watch.
    val invalidValueVersion: Version = getSuccessorVersion(assignment1Version)
    val initialErrorCount = getEtcdCorruptionErrorLogCount()
    awaitEtcdCommitted(
      client.write(
        target.toParseableDescription,
        invalidValueVersion,
        ByteString.copyFromUtf8("foo"),
        Some(assignment1Version),
        isIncremental = true
      )
    )

    AssertionWaiter("Wait for watch data corruption").await {
      assert(
        EtcdStore.Metrics.forTest
          .getNumEtcdStoreCorruptions(target, StoreErrorCode.WATCH_VALUE_PARSE_ERROR) > 0
      )
      assert(getEtcdCorruptionErrorLogCount() > initialErrorCount)
    }
    // Expect watch to be cancelled soon. Write a valid assignment via EtcdClient and expect it to
    // be discovered by store1.
    val assignment2Version: Version = getSuccessorVersion(invalidValueVersion)
    val assignment2 =
      Assignment(
        isFrozen = assignment1.isFrozen,
        consistencyMode = assignment1.consistencyMode,
        generation = EtcdClientHelper.createGenerationFromVersion(
          assignment2Version
        ),
        sliceMap = assignment1.sliceMap
      )

    awaitEtcdCommitted(
      client.write(
        target.toParseableDescription,
        assignment2Version,
        ByteString.copyFrom(assignment2.toDiff(Generation.EMPTY).toProto.toByteArray),
        Some(invalidValueVersion),
        isIncremental = false
      )
    )

    // Watch for the written assignments in the store and expect it to learn about assignment2.
    assert(awaitAssignment(store1, target, assignment1.generation) == assignment2)

    destroyStore(store1)
  }

  test("State machine cancels watch on data corruption when parsing value") {
    // Test plan: Pass a watch event with valid assignment, followed by watch event with invalid
    // value for target and expect StateMachine to request watch cancellation with
    // WATCH_VALUE_PARSE_ERROR.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    clock.advanceBy(2.seconds)

    // Report assignment1 via watch event.
    val diff1: DiffAssignment = assignment1.toDiff(Generation.EMPTY)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            ByteString.copyFrom(diff1.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment1))
      )
    )

    // Report another watch event with invalid content.
    val output = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.EtcdWatchEvent(
        target,
        // Report the same event with newer generation.
        WatchEvent.VersionedValue(
          getSuccessorVersion(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation)
          ),
          ByteString.copyFromUtf8(
            "foo"
          )
        )
      )
    )

    assert(output.nextTickerTime == TickerTime.MAX)
    assert(output.actions.size == 1)

    // Expect watch to be cancelled with WATCH_VALUE_PARSE_ERROR cancellation code.
    output.actions.head match {
      case DriverAction.CancelEtcdWatch(_, reason) =>
        assert(reason.status.getCode == Status.DATA_LOSS.getCode)
        assert(reason.code == StoreErrorCode.WATCH_VALUE_PARSE_ERROR)
      case action => fail(s"Unexpected action $action")
    }
  }

  test("State machine cancels watch on unused diff from watch event") {
    // Test plan: Send a watch event with a diff that would unused by the Store, expect StateMachine
    // to request watch cancellation with DIFF_UNUSED_ERROR.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    clock.advanceBy(2.seconds)

    // Report assignment1 via watch event.
    val diff1: DiffAssignment = assignment1.toDiff(Generation.EMPTY)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            ByteString.copyFrom(diff1.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment1))
      )
    )

    val assignment2 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 2)
          )
      )

    val assignment3 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 3)
          )
      )

    // Report another watch event with a diff that cannot be applied, because the known assignment
    // State machine knows only about assignment1, and it cannot apply the diff(assignment3,
    // assignment2).
    val output = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.EtcdWatchEvent(
        target,
        WatchEvent.VersionedValue(
          EtcdClientHelper.getVersionFromNonLooseGeneration(assignment3.generation),
          ByteString.copyFrom(
            assignment3.toDiff(assignment2.generation).toProto.toByteArray
          )
        )
      )
    )

    assert(output.nextTickerTime == TickerTime.MAX)
    assert(output.actions.size == 1)
    output.actions.head match {
      case DriverAction.CancelEtcdWatch(_, reason) =>
        assert(reason.status.getCode == Status.DATA_LOSS.getCode)
        assert(reason.code == StoreErrorCode.DIFF_UNUSED_ERROR)
      case action => fail(s"Unexpected action $action")
    }
  }

  test("State machine cancels watch on invalid diff generation in watch event.") {
    // Test plan: Send a watch event with assignment SliceMap generation older than known generation
    // in the store and expect the watch to be cancelled with DIFF_GENERATION_ERROR.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    val assignment2 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 2)
          )
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    clock.advanceBy(2.seconds)

    // Report assignment1 via watch event.
    val diff1: DiffAssignment = assignment2.toDiff(Generation.EMPTY)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation),
            ByteString.copyFrom(diff1.toProto.toByteArray)
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment2))
      )
    )

    val assignment3 = createProposal(predecessorOpt = Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 3)
          )
      )

    val output = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.EtcdWatchEvent(
        target,
        // Store knows about assignment2, and it expects to receive partial SliceMap with
        // generation of assignment2, but we pass an older assignment i.e. assignment1
        // which is not expected because assignment diffs are expected to be written for consecutive
        // generations.
        WatchEvent.VersionedValue(
          EtcdClientHelper.getVersionFromNonLooseGeneration(assignment3.generation),
          ByteString.copyFrom(
            assignment3.toDiff(assignment1.generation).toProto.toByteArray
          )
        )
      )
    )

    assert(output.nextTickerTime == TickerTime.MAX)
    assert(output.actions.size == 1)
    output.actions.head match {
      case DriverAction.CancelEtcdWatch(_, reason) =>
        assert(reason.status.getCode == Status.DATA_LOSS.getCode)
        assert(reason.code == StoreErrorCode.DIFF_GENERATION_ERROR)
      case action => fail(s"Unexpected action $action")
    }
  }

  test("State machine retries with backoff on data corruption") {
    // Test plan: Pass a watch event with valid assignment, followed by watch event with invalid
    // value for target, report watch failure and expect watch to be retried. Fail with invalid
    // watch value again, expect watch to be retried with exponential backoff.

    /** Assert output has only [[DriverAction.CancelEtcdWatch]] with [[Status.DATA_LOSS]]. */
    def assertWatchCancelled(output: StateMachineOutput[DriverAction]): Unit = {
      assert(output.nextTickerTime == TickerTime.MAX)
      assert(output.actions.size == 1)

      // Expect watch to be cancelled with WATCH_VALUE_PARSE_ERROR cancellation code.
      output.actions.head match {
        case DriverAction.CancelEtcdWatch(_, reason) =>
          assert(reason.status.getCode == Status.DATA_LOSS.getCode)
          assert(reason.code == StoreErrorCode.WATCH_VALUE_PARSE_ERROR)
        case action => fail(s"Unexpected action $action")
      }
    }

    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    val assignment1 = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(
              STORE_INCARNATION.value,
              1
            )
          )
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a successful watch event and expect no action to be requested from driver.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(target, WatchEvent.Causal)
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    clock.advanceBy(2.seconds)

    // Report assignment1 via watch event.
    val diffBytes = ByteString.copyFrom(assignment1.toDiff(Generation.EMPTY).toProto.toByteArray)
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            diffBytes
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.UseAssignment(target, assignment1))
      )
    )

    // Report another watch event with invalid content and expect output to cancel the watch.
    assertWatchCancelled(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          // Report the same event with newer generation.
          WatchEvent.VersionedValue(
            getSuccessorVersion(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation)
            ),
            ByteString.copyFromUtf8(
              "foo"
            )
          )
        )
      )
    )

    // Report a watch failure, expect a retry after 5.seconds
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(target, Status.DATA_LOSS)
      ) ==
      StateMachineOutput(clock.tickerTime() + 5.seconds, Seq.empty)
    )

    // Expect a watch to be requested after 5 seconds.
    clock.advanceBy(5.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    // Report a valid watch value event.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation),
            diffBytes
          )
        )
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )

    // Report a bad watch value again and expect watch to be cancelled.
    assertWatchCancelled(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchEvent(
          target,
          WatchEvent.VersionedValue(
            getSuccessorVersion(
              EtcdClientHelper.getVersionFromNonLooseGeneration(assignment1.generation)
            ),
            ByteString.copyFromUtf8(
              "foo"
            )
          )
        )
      )
    )

    // Report a watch failure because of corruption and expect a retried to be scheduled with
    // backoff after 10 seconds.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWatchFailure(
          target,
          Status.DATA_LOSS
        )
      ) ==
      StateMachineOutput(clock.tickerTime() + 10.seconds, Seq.empty)
    )

    clock.advanceBy(10.seconds)
    assert(
      storeDriver.onAdvance(
        clock.tickerTime(),
        clock.instant()
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )
  }

  test("Caching error metric incremented when assigner knows more than the store") {
    // Test plan: Verify that if the store gets an OccFailure on an assignment write indicating that
    // the latest generation in the store is actually behind the latest assignment that the writer
    // knows about (i.e. the assignment used as the basis for the new assignment in the write), then
    // the store increments the caching_error metric used for alerting indicating
    // ASSIGNER_KNOWS_GREATER_GENERATION_THAN_DURABLE_STORE.

    val target = Target(s"$getSafeName")
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val sec = pool.createExecutionContext(s"$getSafeName-store")
    val store =
      EtcdStore.create(
        sec,
        client,
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )

    // Simulate a write based on a predecessor assignment that the store somehow lost. Simulate the
    // case where the store lost everything (has no assignment at all).
    val lostPredecessorAssignment1: Assignment = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        Generation(Incarnation(STORE_INCARNATION.value), 42)
      )
    assert(
      awaitOccFailure(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(lostPredecessorAssignment1))
        )
      ) == Generation.EMPTY
    )

    // Verify that the error count metric was incremented.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.ASSIGNER_KNOWS_GREATER_ASSIGNMENT_GENERATION_THAN_DURABLE_STORE,
        target.getLoggerPrefix
      ) == 1
    )

    // Now simulate the case where the store has some assignment, but it's behind an assignment
    // that's known to the writer. Create an assignment with a generation ahead of the latest
    // assignment in the store, and attempt a write using it as a predecessor.
    val someAssignment: Assignment = awaitCommitted(
      store.writeAssignment(
        target,
        shouldFreeze = false,
        createProposal(predecessorOpt = None)
      )
    )
    val lostPredecessorAssignment2: Assignment = createProposal(predecessorOpt = None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        someAssignment.generation.copy(number = someAssignment.generation.number.value + 1L)
      )
    assert(
      awaitOccFailure(
        store.writeAssignment(
          target,
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(lostPredecessorAssignment2))
        )
      ) == someAssignment.generation
    )

    // Verify that the error count metric was incremented.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.ASSIGNER_KNOWS_GREATER_ASSIGNMENT_GENERATION_THAN_DURABLE_STORE,
        target.getLoggerPrefix
      ) == 2
    )
  }

  test("EtcdStoreConfig requires non-loose Incarnation") {
    // Test plan: verify that creating an EtcdStoreConfig with a loose incarnation fails.
    assertThrow[IllegalArgumentException]("Store incarnation must be non-loose") {
      EtcdStoreConfig.create(Incarnation.MIN)
    }
  }

  test("store error when applying diff on write response") {
    // Test plan: Verify that the store cancels the watch when it encounters an error while applying
    // the diff from a watch event. Verify this by giving the driver a watch event with an empty
    // value string which should not be parseable.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock

    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )
    val assignment: Assignment = createProposal(None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    val output: StateMachineOutput[DriverAction] = storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(
        target,
        Promise[WriteAssignmentResult](),
        shouldFreeze = false,
        createProposal(predecessorOpt = Some(assignment))
      )
    )
    val etcdWriteRequest: EtcdWriteRequest = output.actions match {
      case Seq(PerformEtcdWrite(_, etcdWriteRequest: EtcdWriteRequest)) => etcdWriteRequest
      case _ => fail("...")
    }

    val nonZeroGeneration: Generation = Generation(Incarnation(STORE_INCARNATION.value), 1)
    val badDiffAssignment: DiffAssignment = DiffAssignment.fromProto(
      etcdWriteRequest.diffAssignment.toProto.withDiffGeneration(nonZeroGeneration.toProto)
    )
    val badWriteRequest: EtcdWriteRequest =
      etcdWriteRequest.copy(diffAssignment = badDiffAssignment)
    val actions = storeDriver
      .onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWriteResponse(target, badWriteRequest, Success(WriteResponse.Committed(None)))
      )
      .actions

    assert(actions.size == 2)
    assert(actions.head.isInstanceOf[DriverAction.CompletedWrite])
    actions.tail.head match {
      case cancelWatchAction: DriverAction.CancelEtcdWatch =>
        assert(
          cancelWatchAction.target == target &&
          cancelWatchAction.reason.code == StoreErrorCode.DIFF_UNUSED_ERROR
        )
      case _ => fail("expected cancelWatchAction")
    }
  }

  gridTest("predecessor generation does not equal configured store incarnation")(
    Vector(
      STORE_INCARNATION.value + 1,
      STORE_INCARNATION.value - 1
    )
  ) { incarnation =>
    // Test plan: Verify that a write request with a predecessor generation that does not match the
    // configured store incarnation throws. Verify this by calling store.writeAssignment with an
    // assignment whose predecessor is too high and validating that it increments the state machines
    // error metric, and validating with a too low predecessor as well.
    val target = Target(s"$getSafeName")
    val store =
      EtcdStore.create(
        pool.createExecutionContext(s"$getSafeName-store"),
        etcd.createEtcdClient(EtcdClient.Config(NAMESPACE)),
        EtcdStoreConfig.create(STORE_INCARNATION),
        testRandom
      )

    val stateMachineErrorCode: String =
      CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR(AlertOwnerTeam.CachingTeam).toString
    val initialErrorCount: Double = MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      "caching_errors",
      Map(
        "severity" -> Severity.CRITICAL.toString,
        "error_code" -> stateMachineErrorCode
      )
    )

    val differentIncarnationAssignment: Assignment = createProposal(None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(incarnation, 1)
          )
      )

    store.writeAssignment(
      target,
      shouldFreeze = false,
      createProposal(Some(differentIncarnationAssignment))
    )

    AssertionWaiter("waiting for error metric to update").await {
      assertResult(initialErrorCount + 1) {
        MetricUtils.getMetricValue(
          CollectorRegistry.defaultRegistry,
          "caching_errors",
          Map(
            "severity" -> Severity.CRITICAL.toString,
            "error_code" -> stateMachineErrorCode
          )
        )
      }
    }
  }

  test("full assignment is written when history is unknown after getting a valid write response") {
    // Test plan: Verify that a full assignment is written when the store does not know the history
    // of the target, but has a usable assignment, and as received a write response indicating a
    // successful previous write. This should result in a full assignment write because the history
    // is still unknown. Verify this by informing the driver of a valid assignment, and then of a
    // write response containing a diff from that assignment. Validate that the follow write
    // request results in a full assignment write.
    val target = Target("target")
    val stateMachine = new EtcdStoreStateMachine(testRandom, defaultConfig)
    val clock = new FakeTypedClock
    val storeDriver =
      new TestStateMachineDriver[EtcdStoreStateMachine.Event, EtcdStoreStateMachine.DriverAction](
        stateMachine
      )

    // Ensure the target is tracked.
    assert(
      storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EnsureTargetTracked(target)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.PerformEtcdWatch(
            target,
            EtcdStoreStateMachine.forTest.WATCH_DURATION,
            EtcdStoreStateMachine.forTest.ETCD_WATCH_PAGE_LIMIT
          )
        )
      )
    )

    val assignment1: Assignment = createProposal(None)
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 1)
          )
      )

    // First inform it of the full assignment.
    storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.InformAssignmentRequest(target, assignment1)
    )

    val assignment2: Assignment = createProposal(Some(assignment1))
      .commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        EtcdClientHelper
          .createGenerationFromVersion(
            Version(STORE_INCARNATION.value, 2)
          )
      )
    val assignment2Diff = assignment2.toDiff(assignment1.generation)
    val diffAssignmentByteString: ByteString =
      ByteString.copyFrom(assignment2Diff.toProto.toByteArray)
    val version = EtcdClientHelper.getVersionFromNonLooseGeneration(assignment2.generation)

    // Now act as if it is receiving a write response from the store for the diff assignment above
    // this should use the diff, and have an unknown history.
    storeDriver.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.EtcdWriteResponse(
        target,
        EtcdWriteRequest(
          Promise.successful(Store.WriteAssignmentResult.Committed(assignment2)),
          assignment2Diff,
          assignment2,
          version,
          diffAssignmentByteString,
          None,
          isIncremental = true
        ),
        Success(WriteResponse.Committed(Some(version)))
      )
    )

    // Now verify that writing another assignment creates a full assignment write because the
    // history is still unknown.
    LogCapturer.withCapturer(new Regex("Writing full assignment for")) { capturer =>
      val output: StateMachineOutput[DriverAction] = storeDriver.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WriteRequest(
          target,
          Promise[WriteAssignmentResult](),
          shouldFreeze = false,
          createProposal(predecessorOpt = Some(assignment2))
        )
      )
      assert(output.actions.size == 1)
      assert(output.actions.head.isInstanceOf[PerformEtcdWrite])

      // Verify exactly one full assignment write was logged and the reason contains an unknown
      // history size.
      val events = capturer.getCapturedEvents
      assert(events.size == 1)
      assert(events.head.formattedMessage.contains("Stored history size: Unknown"))
    }
  }
}
