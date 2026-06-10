package com.databricks.dicer.assigner

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.{
  AssertionWaiter,
  CachingErrorCode,
  FakeTypedClock,
  MetricUtils,
  Severity,
  StateMachineOutput,
  TickerTime
}
import com.google.protobuf.ByteString
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.{
  PreferredAssignerProposal,
  WriteResult
}
import com.databricks.dicer.common.{EtcdClientHelper, Generation, Incarnation}
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.assigner.EtcdPreferredAssignerStoreStateMachine._
import com.databricks.caching.util.EtcdClient.{Version, WatchEvent, WriteResponse}
import com.databricks.caching.util.EtcdClient.WriteResponse.KeyState
import io.grpc.{Status, StatusException}

import java.net.URI
import java.time.Instant
import java.util.UUID
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

class EtcdPreferredAssignerStoreStateMachineSuite extends DatabricksTest {

  /** Random used for the store to make backoff durations deterministic. */
  private val testRandom = new Random {
    override def nextDouble = 0.5
  }

  private val storeConfig = EtcdPreferredAssignerStoreStateMachine.DEFAULT_CONFIG

  /** An arbitrary store incarnation for generations across this suite. */
  private val STORE_INCARNATION = Incarnation(24)

  private val clock = new FakeTypedClock

  /** Represents the non-existence of any preferred assigner entry in the store. */
  private val NON_EXISTENT_PREFERRED_ASSIGNER = PreferredAssignerValue.NoAssigner(Generation.EMPTY)

  /** Three assigners used for the test. */
  private val ASSIGNERS = Seq(
    AssignerInfo(
      UUID.fromString("11111111-1234-5678-abcd-68454e98b111"),
      new URI("http://assigner-1:1234")
    ),
    AssignerInfo(
      UUID.fromString("22222222-1234-5678-abcd-68454e98b222"),
      new URI("http://assigner-2:4567")
    ),
    AssignerInfo(
      UUID.fromString("33333333-1234-5678-abcd-68454e98b222"),
      new URI("http://assigner-3:6789")
    )
  )

  /**
   * Creates and starts the state machine (by calling the `onAdvance`), verifies the
   * `PerformEtcdWatch` action is received by the driver.
   */
  private def createAndStartStateMachine(): EtcdPreferredAssignerStoreStateMachine = {
    val stateMachine =
      new EtcdPreferredAssignerStoreStateMachine(STORE_INCARNATION, testRandom, storeConfig)
    // At the first advance, the state machine triggers the watch event.
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction
            .PerformEtcdWatch(
              EtcdPreferredAssignerStoreStateMachine.companionForTest.WATCH_DURATION
            )
        )
      )
    )
    stateMachine
  }

  test("PreferredAssignerStore.Config must be within the valid range") {
    // Test plan: Verify that the store configuration is within the valid range.
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

  test("Requests an etcd watch when first advanced") {
    // Test plan: Verify that the when first advanced after initialization, the state machine
    // requests the driver to start watching the preferred assigner in etcd, and that subsequent
    // advances return no action.
    val stateMachine =
      new EtcdPreferredAssignerStoreStateMachine(STORE_INCARNATION, testRandom, storeConfig)

    // Before the first advance, the preferred assigner should not exist in the store.
    assert(stateMachine.forTest.getLatestPreferredAssigner == NON_EXISTENT_PREFERRED_ASSIGNER)

    // Verify that when first advanced the state machine requests the driver to start an etcd watch.
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction
            .PerformEtcdWatch(
              EtcdPreferredAssignerStoreStateMachine.companionForTest.WATCH_DURATION
            )
        )
      )
    )

    // Verify that the latest known preferred assigner is unchanged.
    assert(stateMachine.forTest.getLatestPreferredAssigner == NON_EXISTENT_PREFERRED_ASSIGNER)

    // Verify that the second advance should return no Action.
    clock.advanceBy(1.second) // A spurious wakeup.
    val output = stateMachine.onAdvance(clock.tickerTime(), clock.instant())
    assert(output == StateMachineOutput(TickerTime.MAX, Seq.empty))
  }

  test("PreferredAssignerInformed accepts only newer preferred assigners") {
    // Test plan: Verify that state machine only incorporates preferred assigners with fresher
    // generations when PreferredAssignerInformed events are received.
    val stateMachine = createAndStartStateMachine()
    val incarnation: Incarnation = Incarnation(STORE_INCARNATION.value)

    // Initialize the cache with a preferred assigner.
    val preferredAssigner1 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 42))
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(preferredAssigner1)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.ApplyPreferredAssigner(preferredAssigner1))
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner1)

    // Verify that the state machine accepts a newer preferred assigner.
    val preferredAssigner2 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 43))
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(preferredAssigner2)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.ApplyPreferredAssigner(preferredAssigner2))
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner2)

    // Verify that the state machine rejects an older preferred assigner.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(
          PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 20))
        )
      ) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner2)
  }

  test("PreferredAssignerInformed incorporates preferred assigners from other store incarnations") {
    // Test plan: Verify that state machine incorporates preferred assigners from other store
    // incarnations which it is informed of, while only allowing its cached knowledge to move
    // forward.
    val stateMachine = createAndStartStateMachine()

    // Check that the state machine incorporates preferred assigners from lower store incarnations.
    val olderIncarnationPreferredAssigner = PreferredAssignerValue.SomeAssigner(
      ASSIGNERS.head,
      Generation(Incarnation(STORE_INCARNATION.value - 2), 42)
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(olderIncarnationPreferredAssigner)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.ApplyPreferredAssigner(
            olderIncarnationPreferredAssigner
          )
        )
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == olderIncarnationPreferredAssigner)

    // Check that the state machine incorporates a preferred assigners from this assigner's
    // configured store incarnation.
    val preferredAssigner0 = PreferredAssignerValue.SomeAssigner(
      ASSIGNERS(1),
      Generation(Incarnation(STORE_INCARNATION.value), 42)
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(preferredAssigner0)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.ApplyPreferredAssigner(preferredAssigner0))
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner0)

    // Check that the state machine incorporates preferred assigners from higher store incarnations
    // even if there's cached knowledge from the configured incarnation.
    val preferredAssigner1 = PreferredAssignerValue.SomeAssigner(
      ASSIGNERS(2),
      Generation(Incarnation(STORE_INCARNATION.value + 2), 42)
    )
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.PreferredAssignerInformed(preferredAssigner1)
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.ApplyPreferredAssigner(preferredAssigner1))
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner1)

    // Check that the state machine ignores preferred assigners from versions before its cached
    // version.
    for (olderGeneration <- Seq(
        // Lower incarnation.
        Generation(STORE_INCARNATION, 42),
        // Same incarnation, lower generation number.
        Generation(Incarnation(STORE_INCARNATION.value + 2), 40)
      )) {
      assert(
        stateMachine.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.PreferredAssignerInformed(
            PreferredAssignerValue.SomeAssigner(
              ASSIGNERS(1),
              olderGeneration
            )
          )
        ) ==
        StateMachineOutput(TickerTime.MAX, Seq.empty)
      )
      assert(stateMachine.forTest.getLatestPreferredAssigner == preferredAssigner1)
    }
  }

  test("Write preferred assigner proposal") {
    // Test plan: Verify that when the state machine receives a write request event, that it chooses
    // an appropriate generation for the new preferred assigner and requests the driver to perform
    // the write. Also verify that the state machine requests the write even if the predecessor is
    // does not match the latest known preferred assigner generation in the cache.
    val stateMachine = createAndStartStateMachine()
    val promise = Promise[WriteResult]()
    val incarnation: Incarnation = Incarnation(STORE_INCARNATION.value)

    val nextInstant: Instant = clock.instant()

    // Setup: use `informPreferredAssigner` to set the preferred assigner to `ASSIGNERS(1)`.
    val assigner1 = PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 39))
    stateMachine.onEvent(
      clock.tickerTime(),
      nextInstant,
      Event.PreferredAssignerInformed(assigner1)
    )

    // Case 1: when the predecessor has a generation which matches the cached preferred assigner
    // generation but has an inconsistent value for that generation, the state machine still
    // requests the driver to perform the write with the expected generation.
    val wrongPredecessor1 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(2), Generation(incarnation, 39))
    val proposal1 =
      PreferredAssignerProposal(Some(wrongPredecessor1.generation), Some(ASSIGNERS(1)))
    val write1 = Event.WriteRequest(promise, proposal1)
    val expectedDriverAction1 = DriverAction
      .WritePreferredAssignerToEtcd(
        promise,
        Some(EtcdClientHelper.getVersionFromNonLooseGeneration(wrongPredecessor1.generation)),
        PreferredAssignerValue.SomeAssigner(
          ASSIGNERS(1),
          Generation(incarnation, nextInstant.toEpochMilli)
        )
      )
    assert(
      stateMachine.onEvent(clock.tickerTime(), nextInstant, write1) ==
      StateMachineOutput(TickerTime.MAX, Seq(expectedDriverAction1))
    )

    // Case 2: when the predecessor is ahead of the cached preferred assigner generation, the state
    // machine still requests the driver to perform the write with the expected generation.
    val wrongPredecessor2 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 50))
    val proposal2 =
      PreferredAssignerProposal(Some(wrongPredecessor2.generation), Some(ASSIGNERS(2)))
    val write2 = Event.WriteRequest(promise, proposal2)
    val expectedDriverAction2 = DriverAction
      .WritePreferredAssignerToEtcd(
        promise,
        Some(EtcdClientHelper.getVersionFromNonLooseGeneration(wrongPredecessor2.generation)),
        PreferredAssignerValue.SomeAssigner(
          ASSIGNERS(2),
          Generation(incarnation, nextInstant.toEpochMilli)
        )
      )
    assert(
      stateMachine.onEvent(clock.tickerTime(), nextInstant, write2) ==
      StateMachineOutput(TickerTime.MAX, Seq(expectedDriverAction2))
    )

    // Case 3: when the predecessor matches the cached preferred assigner, the state machine still
    // requests the driver to perform the write with the expected generation.
    val correctPredecessor = assigner1
    val proposal3 =
      PreferredAssignerProposal(Some(correctPredecessor.generation), Some(ASSIGNERS(2)))
    val write3 = Event.WriteRequest(promise, proposal3)
    val expectedDriverAction3 = DriverAction
      .WritePreferredAssignerToEtcd(
        promise,
        Some(EtcdClientHelper.getVersionFromNonLooseGeneration(correctPredecessor.generation)),
        PreferredAssignerValue.SomeAssigner(
          ASSIGNERS(2),
          Generation(incarnation, nextInstant.toEpochMilli)
        )
      )
    assert(
      stateMachine.onEvent(clock.tickerTime(), nextInstant, write3) ==
      StateMachineOutput(TickerTime.MAX, Seq(expectedDriverAction3))
    )
  }

  test("EtcdWriteResponse event") {
    // Test plan: Verify that the state machine requests the driver to complete the write with a
    // CompleteWrite action and the expected WriteResult in response to different kinds of
    // EtcdWriteResponse events:
    // 1. Write failure due to an exception.
    // 2. Write failure to an OCC check failure.
    // 3. Write success.
    val stateMachine = createAndStartStateMachine()
    val incarnation: Incarnation = Incarnation(STORE_INCARNATION.value)
    val promise = Promise[WriteResult]()

    // Setup: use `informPreferredAssigner` to set the initial preferred assigner to `ASSIGNERS(1)`.
    val assigner1 = PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 39))
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerInformed(assigner1)
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    val proposedPredecessorVersionOpt: Option[Version] =
      Some(EtcdClientHelper.getVersionFromNonLooseGeneration(assigner1.generation))
    val proposedPreferredAssigner =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(2), Generation(incarnation, 50))

    // Case 1: The write failed with an exception. Verify that the state machine asks the driver to
    // fail the write with the same exception, and the latest known preferred assigner isn't
    // updated.
    val exception = new Exception("exception")
    val etcdWriteResponse1 =
      Event.EtcdWriteResponse(
        promise,
        proposedPredecessorVersionOpt,
        proposedPreferredAssigner,
        Failure(exception)
      )
    assert(
      stateMachine.onEvent(clock.tickerTime(), clock.instant(), etcdWriteResponse1) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.CompleteWrite(promise, Failure(exception)))
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // Case 2a: The write failed with an OCC check failure due to a version mismatch between the
    // proposal and what's in the store. Verify that the state machine asks the driver to complete
    // the write indicating the actual generation of the preferred assigner in the store, and that
    // the preferred assigner isn't updated.
    val actualVersion2a = Version(STORE_INCARNATION.value, 40)
    val actualGeneration2a = EtcdClientHelper.createGenerationFromVersion(actualVersion2a)
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWriteResponse(
          promise,
          proposedPredecessorVersionOpt,
          proposedPreferredAssigner,
          Success(
            WriteResponse.OccFailure(
              KeyState.Present(
                actualVersion2a,
                newKeyVersionLowerBoundExclusive = Version(STORE_INCARNATION.value, 41)
              )
            )
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction
            .CompleteWrite(promise, Success(WriteResult.OccFailure(actualGeneration2a)))
        )
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // Case 2b: Same as 2a above, but where the OCC failure occurred because no preferred assigner
    // currently exists in the store.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWriteResponse(
          promise,
          proposedPredecessorVersionOpt,
          proposedPreferredAssigner,
          Success(
            WriteResponse.OccFailure(
              KeyState.Absent(Version(STORE_INCARNATION.value, 41))
            )
          )
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.CompleteWrite(
            promise,
            Success(
              WriteResult.OccFailure(Generation.EMPTY)
            )
          )
        )
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // Case 3: The write was successful. Verify that the state machine asks the driver to apply the
    // new preferred assigner value, and to complete the write with a committed result. Also verify
    // that the latest known preferred assigner is updated.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWriteResponse(
          promise,
          proposedPredecessorVersionOpt,
          proposedPreferredAssigner,
          Success(WriteResponse.Committed(newKeyVersionLowerBoundExclusiveOpt = None))
        )
      ) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.ApplyPreferredAssigner(proposedPreferredAssigner),
          DriverAction
            .CompleteWrite(promise, Success(WriteResult.Committed(proposedPreferredAssigner)))
        )
      )
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == proposedPreferredAssigner)
  }

  test("Receive Etcd watch failure") {
    // Test plan: Verify the state machine retries watch requests with exponential backoff when
    // Etcd watch failure events are received.
    val testEpoch: TickerTime = clock.tickerTime()
    val stateMachine = createAndStartStateMachine()
    val incarnation: Incarnation = Incarnation(STORE_INCARNATION.value)

    // Setup: use `informPreferredAssigner` to set the preferred assigner to `ASSIGNERS(1)`.
    val assigner1 = PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 39))
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerInformed(assigner1)
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // Verify that when we receive consecutive watch failures, the state machine updates the next
    // watch time and re-establishes the watch within the `maxWatchRetryDelay`. Additionally, we
    // need to verify that the next watch time increases when we receive multiple watch failures.
    var previousNextWatchTimeAfterFailure = Duration.Zero
    for (_ <- 0 to 3) {
      // Set up: a watch failure is received.
      val watchFailure = Event.EtcdWatchFailure(Status.DATA_LOSS)

      // Verify: the next watch time is within the backoff range.
      val output = stateMachine.onEvent(testEpoch, clock.instant(), watchFailure)
      val nextWatchTime: FiniteDuration = output.nextTickerTime.-(testEpoch)
      assert(nextWatchTime <= storeConfig.maxWatchRetryDelay)
      assert(nextWatchTime >= storeConfig.minWatchRetryDelay)
      assert(output.actions == Seq.empty)

      // Verify that the state machine retries the watch request after time advances by
      // `storeConfig.maxWatchRetryDelay`.
      clock.advanceBy(storeConfig.maxWatchRetryDelay)
      assert(
        stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
        StateMachineOutput(
          TickerTime.MAX,
          Seq(
            DriverAction
              .PerformEtcdWatch(
                EtcdPreferredAssignerStoreStateMachine.companionForTest.WATCH_DURATION
              )
          )
        )
      )

      // Verify: if this is not the first time of failure, the next watch time is longer than the
      // previous one.
      if (previousNextWatchTimeAfterFailure != Duration.Zero) {
        assert(nextWatchTime > previousNextWatchTimeAfterFailure)
      }
      previousNextWatchTimeAfterFailure = nextWatchTime
    }
  }

  test("Receive watch event from the Etcd") {
    // Test plan: Verify the state machine behavior when an Etcd watch event is received.
    // 1. Whenever we receive a preferred assigner from the watch, regardless of whether the causal
    //    signal has been received yet, the cached preferred assigner should be updated.
    // 2. When the Casual event itself is received, no update happens at that point.
    // 3. If the watched preferred assigner is stale, the state machine should ignore it.
    // 4. If the watched value cannot be parsed, the state machine should log a caching critical
    //    error and cancel and recreate the watch, retrying with exponential backoff until the value
    //    becomes valid again.
    val stateMachine = createAndStartStateMachine()
    val testEpoch: TickerTime = clock.tickerTime()
    val incarnation: Incarnation = Incarnation(STORE_INCARNATION.value)

    def getPreferredAssignerStoreCorruptedCount: Int = {
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.CRITICAL,
        CachingErrorCode.PREFERRED_ASSIGNER_STORE_CORRUPTED,
        prefix = "dicer-preferred-assigner"
      )
    }

    // Setup: use `informPreferredAssigner` to set the preferred assigner to `ASSIGNERS(1)`.
    val assigner1 = PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 39))
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerInformed(assigner1)
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // Case 1: the causal event has not been received.
    val assigner0 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(0), Generation(incarnation, 42))
    val watchEvent1 = Event.EtcdWatchEvent(
      WatchEvent.VersionedValue(
        Version(STORE_INCARNATION.value, 42),
        value = assigner0.toSpecProto.toByteString
      )
    )

    // Verify: still need to update the preferred assigner.
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), watchEvent1) ==
      StateMachineOutput(TickerTime.MAX, Seq(DriverAction.ApplyPreferredAssigner(assigner0)))
    )
    // Verify that the preferred assigner is updated.
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner0)

    // Case 2 the causal event was just received. No update happens at that point.
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), Event.EtcdWatchEvent(WatchEvent.Causal)) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
    // Verify: the preferred assigner remains the same.
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner0)

    clock.advanceBy(1.second)
    // Case 2.2: after the causal event, and a watch with an updated preferred assigner is
    // received, the preferred assigner gets updated again.
    val assigner2 = PreferredAssignerValue.SomeAssigner(ASSIGNERS(2), Generation(incarnation, 55))
    val watchEvent3 = Event.EtcdWatchEvent(
      WatchEvent.VersionedValue(
        Version(STORE_INCARNATION.value, 55),
        value = assigner2.toSpecProto.toByteString
      )
    )
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), watchEvent3) ==
      StateMachineOutput(TickerTime.MAX, Seq(DriverAction.ApplyPreferredAssigner(assigner2)))
    )
    // Verify that the preferred assigner is updated.
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner2)

    // Case 3: the watched preferred assigner is stale.
    val stalePreferredAssigner =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(0), Generation(incarnation, 15))
    val watchEvent3b = Event.EtcdWatchEvent(
      WatchEvent.VersionedValue(
        Version(STORE_INCARNATION.value, 15),
        value = stalePreferredAssigner.toSpecProto.toByteString
      )
    )
    // Verify: no action returned.
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), watchEvent3b) ==
      StateMachineOutput(TickerTime.MAX, Seq.empty)
    )
    // Verify that the preferred assigner isn't updated.
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner2)

    // Case 4: the watched value cannot be parsed.
    val initialStoreCorruptedCount: Int = getPreferredAssignerStoreCorruptedCount

    val watchEvent4 = Event.EtcdWatchEvent(
      WatchEvent.VersionedValue(
        Version(STORE_INCARNATION.value, 15),
        value = ByteString.copyFromUtf8("invalid")
      )
    )

    // Verify: The driver cancels the etcd watch.
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), watchEvent4) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.CancelEtcdWatch(
            StoreErrorReason(Status.DATA_LOSS, StoreErrorCode.WATCH_VALUE_PARSE_ERROR)
          )
        )
      )
    )
    // Verify that the preferred assigner isn't updated.
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner2)
    // Verify that the store corrupted count gets increased.
    val storeCorruptedCountAfterEvent4: Int = getPreferredAssignerStoreCorruptedCount
    assert(storeCorruptedCountAfterEvent4 == initialStoreCorruptedCount + 1)

    // Verify that the watch is recreated with retries after the previous cancellation is complete
    // and sufficient time has elapsed. (See the "Receive Etcd watch failure" test in
    // PreferredAssignerStoreSuite for complete cross-component testing of the retry and backoff
    // behavior.)
    assert(
      stateMachine
        .onEvent(clock.tickerTime(), clock.instant(), Event.EtcdWatchFailure(Status.DATA_LOSS)) ==
      StateMachineOutput(clock.tickerTime() + storeConfig.minWatchRetryDelay, Seq())
    )
    clock.advanceBy(storeConfig.minWatchRetryDelay)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction
            .PerformEtcdWatch(
              EtcdPreferredAssignerStoreStateMachine.companionForTest.WATCH_DURATION
            )
        )
      )
    )
    // Pretend the store is still corrupted and that we get another data loss error.  This will
    // result in the watch being recreated yet again after a longer delay.
    assert(
      stateMachine
        .onEvent(clock.tickerTime(), clock.instant(), Event.EtcdWatchFailure(Status.DATA_LOSS)) ==
      StateMachineOutput(clock.tickerTime() + storeConfig.minWatchRetryDelay * 2, Seq())
    )
    clock.advanceBy(storeConfig.minWatchRetryDelay * 2)
    assert(
      stateMachine.onAdvance(clock.tickerTime(), clock.instant()) ==
      StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction
            .PerformEtcdWatch(
              EtcdPreferredAssignerStoreStateMachine.companionForTest.WATCH_DURATION
            )
        )
      )
    )

    // Pretend a valid preferred assigner is received after the store is corrupted. Verify that the
    // state machine is able to incorporate the new preferred assigner, and the the store corrupted
    // counter isn't further increased.
    val newAssigner = PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(incarnation, 60))
    val watchEvent5 = Event.EtcdWatchEvent(
      WatchEvent.VersionedValue(
        Version(STORE_INCARNATION.value, 60),
        value = newAssigner.toSpecProto.toByteString
      )
    )
    assert(
      stateMachine.onEvent(testEpoch, clock.instant(), watchEvent5) ==
      StateMachineOutput(TickerTime.MAX, Seq(DriverAction.ApplyPreferredAssigner(newAssigner)))
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == newAssigner)
    val storeCorruptedCountAfterEvent5: Int = getPreferredAssignerStoreCorruptedCount
    assert(storeCorruptedCountAfterEvent5 == storeCorruptedCountAfterEvent4)
  }

  test("State machine chooses non-loose incarnations in configured store incarnation") {
    // Test plan: Verify that when the state machine brings a preferred assigner into existence for
    // the first time in the store, it chooses a generation with an incarnation that is non-loose
    // and in the configured store incarnation. Verify this by attempting a write of a brand new
    // preferred assigner, and checking the chosen generation for the new preferred assigner.
    val stateMachine = createAndStartStateMachine()
    val promise = Promise[WriteResult]()
    val preferredAssignerProposal = PreferredAssignerProposal(
      predecessorGenerationOpt = None,
      newPreferredAssignerInfoOpt = Some(ASSIGNERS(1))
    )
    val output: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(promise, preferredAssignerProposal)
    )
    assert(output.actions.size == 1)
    val chosenGeneration: Generation = output.actions.head match {
      case DriverAction.WritePreferredAssignerToEtcd(
          _,
          _,
          PreferredAssignerValue.SomeAssigner(_, generation: Generation)
          ) =>
        generation
      case _ => fail("expected a WritePreferredAssignerToEtcd action")
    }
    assert(chosenGeneration.incarnation.value == STORE_INCARNATION.value)
    assert(chosenGeneration.incarnation.isNonLoose)
  }

  test("State machine maintains the same incarnation on preferred assigner updates") {
    // Test plan: Verify that when the state machine updates the preferred assigner in the store
    // (i.e. there already exists a preferred assigner in the store, which is being updated), that
    // the chosen generation has the same incarnation as the predecessor for the write. Verify this
    // by attempting to update the preferred assigner from ASSIGNERS(1) to ASSIGNERS(2) and checking
    // that the chosen generation has the same incarnation as the existing preferred assigner entry.
    val stateMachine = createAndStartStateMachine()
    val promise = Promise[WriteResult]()
    val initialIncarnation: Incarnation = Incarnation(STORE_INCARNATION.value)
    val predecessorGeneration = Generation(initialIncarnation, 39)
    val preferredAssignerProposal =
      PreferredAssignerProposal(Some(predecessorGeneration), Some(ASSIGNERS(2)))
    val output: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(promise, preferredAssignerProposal)
    )
    assert(output.actions.size == 1)
    val chosenGeneration: Generation = output.actions.head match {
      case DriverAction.WritePreferredAssignerToEtcd(
          _,
          _,
          PreferredAssignerValue.SomeAssigner(_, generation: Generation)
          ) =>
        generation
      case _ => fail("expected a WritePreferredAssignerToEtcd action")
    }
    assert(chosenGeneration.incarnation == initialIncarnation)
  }

  test("State machine learns from insufficiently generation failures") {
    // Test plan: Verify that the state machine learns from failures due to use of an insufficiently
    // high generation in the chosen generation when attempting to write a preferred assigner
    // and there presently exists no preferred assigner in the store. Verify this by failing a write
    // with an StatusException indicating that the preferred assigner is absent from the store, but
    // could be created using some safe generation, and checking that the state machine uses
    // this information to choose a sufficiently high generation in a subsequent write.
    val stateMachine = createAndStartStateMachine()
    val promise = Promise[WriteResult]()
    val preferredAssignerProposal1 = PreferredAssignerProposal(
      predecessorGenerationOpt = None,
      newPreferredAssignerInfoOpt = Some(ASSIGNERS(1))
    )

    // Setup: Make the state machine tell us what incarnation it would use for a write by delivering
    // a write request to it and observing the chosen incarnation. Then use that to simulate a
    // write failure which informs the state machine of a higher next safe generation.
    val firstWriteAttemptOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(promise, preferredAssignerProposal1)
    )
    assert(firstWriteAttemptOutput.actions.size == 1)
    val firstPreferredAssigner: PreferredAssignerValue =
      firstWriteAttemptOutput.actions.head match {
        case DriverAction.WritePreferredAssignerToEtcd(
            _,
            _,
            preferredAssigner: PreferredAssignerValue
            ) =>
          preferredAssigner
        case _ => fail("expected a WritePreferredAssignerToEtcd action")
      }

    // Verify: Tell the state machine that the write failed and that the next safe generation
    // to use is a higher one than it first attempted. Check that the state machine uses this
    // information in a second attempt to make ASSIGNERS(1) the preferred assigner.
    val nextSafeKeyVersionLowerBoundExclusive =
      Version(STORE_INCARNATION.value, firstPreferredAssigner.generation.number.value + 42)
    val expectedStatusExceptionDescription =
      s"Write for the initial preferred assigner $firstPreferredAssigner " +
      s"failed due to use of an insufficiently high version to guarantee " +
      s"monotonicity. Initial PA writes must currently have a generation of " +
      s"at least $nextSafeKeyVersionLowerBoundExclusive."
    // Verify: a `DriverAction.CompleteWrite(promise, Failure(...))` action is returned.
    val failureOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.EtcdWriteResponse(
        promise,
        predecessorVersionOpt = None,
        firstPreferredAssigner,
        Success(WriteResponse.OccFailure(KeyState.Absent(nextSafeKeyVersionLowerBoundExclusive)))
      )
    )
    assert(failureOutput.nextTickerTime == TickerTime.MAX)
    assert(failureOutput.actions.size == 1)
    failureOutput.actions.head match {
      case DriverAction.CompleteWrite(actualPromise, Failure(ex: StatusException)) =>
        assert(actualPromise eq promise)
        assertResult(expectedStatusExceptionDescription)(ex.getStatus.getDescription)
      case other => fail(s"expected a CompleteWrite with StatusException Failure, got: $other")
    }

    val preferredAssignerProposal2 = PreferredAssignerProposal(
      predecessorGenerationOpt = None,
      newPreferredAssignerInfoOpt = Some(ASSIGNERS(1))
    )
    val secondWriteAttemptOutput: StateMachineOutput[DriverAction] = stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WriteRequest(promise, preferredAssignerProposal2)
    )
    assert(secondWriteAttemptOutput.actions.size == 1)
    val secondPreferredAssigner: PreferredAssignerValue =
      secondWriteAttemptOutput.actions.head match {
        case DriverAction.WritePreferredAssignerToEtcd(
            _,
            _,
            preferredAssigner: PreferredAssignerValue
            ) =>
          preferredAssigner
        case _ => fail("expected a WritePreferredAssignerToEtcd action")
      }
    assert(
      secondPreferredAssigner.generation >
      EtcdClientHelper.createGenerationFromVersion(nextSafeKeyVersionLowerBoundExclusive)
    )
  }

  test("Error emits when assigner knows about a later assigner than store's knowledge") {
    // Test plan: Verify that the state machine emits a error when it learns about a preferred
    // assigner that has a generation number higher than the store's knowledge. Verify this for
    // both KeyState.Absent and KeyState.Present cases.
    val initialErrorCount: Int = MetricUtils.getPrefixLoggerErrorCount(
      Severity.CRITICAL,
      CachingErrorCode.ASSIGNER_KNOWS_LATER_PREFERRED_ASSIGNER_VALUE_THAN_DURABLE_STORE,
      prefix = "dicer-preferred-assigner"
    )

    val stateMachine = createAndStartStateMachine()
    val promise = Promise[WriteResult]()

    // Set up: set an arbitrary `nextSafeKeyVersionLowerBoundExclusive` when there's no preferred
    // assigner key.
    var nextSafeKeyVersionLowerBoundExclusive = Version(0, 42)

    // Propose a preferred assigner with a generation number higher than the store's knowledge.
    val predecessorVersion: Version =
      EtcdClientHelper.getVersionFromNonLooseGeneration(Generation(STORE_INCARNATION, 42))

    // Verify: the state machine outputs OccFailure, and a caching degraded error is emitted.
    assert(
      stateMachine.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.EtcdWriteResponse(
          promise,
          Some(predecessorVersion),
          PreferredAssignerValue.NoAssigner(Generation.EMPTY),
          Success(WriteResponse.OccFailure(KeyState.Absent(nextSafeKeyVersionLowerBoundExclusive)))
        )
      ) == StateMachineOutput(
        TickerTime.MAX,
        Seq(DriverAction.CompleteWrite(promise, Success(WriteResult.OccFailure(Generation.EMPTY))))
      )
    )
    AssertionWaiter("error-count-increment").await {
      assert(
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.ASSIGNER_KNOWS_LATER_PREFERRED_ASSIGNER_VALUE_THAN_DURABLE_STORE,
          prefix = "dicer-preferred-assigner"
        ) == initialErrorCount + 1
      )
    }

    // Setup: use `informPreferredAssigner` to set the preferred assigner to `ASSIGNERS(1)`.
    val assigner1 =
      PreferredAssignerValue.SomeAssigner(ASSIGNERS(1), Generation(STORE_INCARNATION, 39))
    stateMachine.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.PreferredAssignerInformed(assigner1)
    )
    assert(stateMachine.forTest.getLatestPreferredAssigner == assigner1)

    // The assigner proposes a predecessor which has a higher generation than store's knowledge.
    val predecessorGeneration2 = Generation(STORE_INCARNATION.getNextNonLooseIncarnation, 42)
    val predecessorVersion2 =
      EtcdClientHelper.getVersionFromNonLooseGeneration(predecessorGeneration2)

    // `nextSafeKeyVersionLowerBoundExclusive` is set to be higher than `assigner1`'s generation.
    nextSafeKeyVersionLowerBoundExclusive =
      Version(STORE_INCARNATION.value, assigner1.generation.number.value + 42)

    // Verify: the state machine outputs OccFailure, and a caching degraded error is emitted.
    assert(
      stateMachine
        .onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.EtcdWriteResponse(
            promise,
            Some(predecessorVersion2),
            assigner1,
            Success(
              WriteResponse.OccFailure(
                KeyState.Present(
                  Version(assigner1.generation.incarnation.value, assigner1.generation.number),
                  nextSafeKeyVersionLowerBoundExclusive
                )
              )
            )
          )
        ) == StateMachineOutput(
        TickerTime.MAX,
        Seq(
          DriverAction.CompleteWrite(promise, Success(WriteResult.OccFailure(assigner1.generation)))
        )
      )
    )
    AssertionWaiter("error-count-increment").await {
      assert(
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.CRITICAL,
          CachingErrorCode.ASSIGNER_KNOWS_LATER_PREFERRED_ASSIGNER_VALUE_THAN_DURABLE_STORE,
          prefix = "dicer-preferred-assigner"
        ) == initialErrorCount + 2
      )
    }
  }

}
