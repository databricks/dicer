package com.databricks.dicer.client
import java.time.Instant

import scala.concurrent.duration.Duration.Inf
import scala.concurrent.{Future}

import com.databricks.dicer.common.TestSliceUtils._
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP.SliceLoadP
import com.databricks.caching.util.{FakeTypedClock, RealtimeTypedClock}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{TestName, loadTestData}
import com.databricks.dicer.client.TestClientUtils.{
  getAttributedLoadMetric,
  getUnattributedLoadMetric
}
import com.databricks.dicer.common.SliceletData.KeyLoad
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  Incarnation,
  ProposedAssignment,
  SliceAssignment,
  SliceHelper,
  SliceWithResources,
  SliceletData,
  TestSliceUtils
}
import com.databricks.dicer.external.{Slice, SliceKey, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.dicer.client.SliceletLoadAccumulator.forTest.getSliceKeyLoadDistributionMetric
import com.databricks.dicer.common.test.SimpleDiffAssignmentP.SimpleSliceAssignmentP
import com.databricks.dicer.common.test.SliceletLoadAccumulatorTestDataP
import com.databricks.dicer.common.test.SliceletLoadAccumulatorTestDataP.ActionP.Action
import com.databricks.dicer.common.test.SliceletLoadAccumulatorTestDataP.IncrementPrimaryLoadP.IncrementByKey
import com.databricks.dicer.common.test.SliceletLoadAccumulatorTestDataP._
import com.databricks.testing.DatabricksTest
import com.databricks.threading.NamedExecutor
import org.scalatest.TestData

class SliceletLoadAccumulatorSuite extends DatabricksTest with TestName {

  private val TEST_DATA: SliceletLoadAccumulatorTestDataP =
    loadTestData[SliceletLoadAccumulatorTestDataP](
      "dicer/common/test/data/slicelet_load_accumulator_test_data.textproto"
    )

  /** The sequence number and the helper method to get the next unique target name. */
  private var sequenceNumber: Int = 0
  private def getNextUniqueTargetName: String = {
    sequenceNumber += 1
    s"target-$sequenceNumber"
  }

  override def beforeEach(testData: TestData): Unit = {
    super.beforeEach(testData)
    SliceletMetrics.forTest.clearAttributedLoadMetric()
    SliceletMetrics.forTest.clearUnattributedLoadMetric()
  }

  /** Performs actions for the accumulator based on the proto. */
  private def performActions(
      accumulator: SliceletLoadAccumulator,
      target: Target,
      fakeClock: FakeTypedClock,
      startTime: Instant,
      validCase: ValidCaseP): Unit = {

    // Track the last recorded unattributed / total attributed load for decay checks, updated in
    // `CheckUnattributedLoad` and `CheckAttributedLoads`.
    var lastUnattributedLoadOpt: Option[Double] = None
    var lastTotalAttributedLoadOpt: Option[Double] = None

    for (actionProto <- validCase.actions) {
      actionProto.action match {
        case Action.IncrementPrimaryLoad(proto) =>
          val key: SliceKey = proto.incrementByKey match {
            case IncrementByKey.Key(key) => TestSliceUtils.toSliceKey(key)
            case IncrementByKey.BytesKey(bytes) => SliceKey.withIdentityFunction(bytes)
            case IncrementByKey.Empty => SliceKey.MIN // Default to MIN if no key is specified
          }
          accumulator.incrementPrimaryRateBy(key, proto.getValue)

        case Action.UpdateMetrics(_) =>
          accumulator.updateMetrics()

        case Action.HistogramBucketCheck(proto) =>
          val debugMsg: String =
            "Debugging hint: " + proto.debugMsg.getOrElse(fail("expected debug message"))
          val bucketIndex: Int = proto.getBucketIndex
          require(bucketIndex >= 0 && bucketIndex <= 255)
          val bucketLeLabel: String = bucketIndex match {
            case 255 => "+Inf"
            case _ => ((bucketIndex + 1).toDouble / 256.0).toString
          }
          val expectedCount: Long = proto.getExpected
          assert(
            getSliceKeyLoadDistributionMetric(bucketLeLabel, target) == expectedCount,
            debugMsg
          )

        case Action.AdvanceClock(_) =>
          fakeClock.advanceBy(1.second)

        case Action.ChangeAssignment(proto) =>
          val assignment: Assignment = createAssignment(
            generation = Generation.fromProto(proto.getGeneration),
            consistencyMode = AssignmentConsistencyMode.Affinity,
            entries = proto.newAssignment.map((proto: SimpleSliceAssignmentP) => {
              val sliceWithResources: SliceWithResources = SliceWithResources(
                slice = SliceHelper.fromProto(proto.getSlice),
                resources = proto.resources.map(createTestSquid(_)).toSet
              )
              SliceAssignment(
                sliceWithResources,
                Generation.fromProto(proto.getGeneration),
                subsliceAnnotationsByResource = Map.empty,
                primaryRateLoadOpt = proto.primaryRateLoadOpt
              )
            })
          )
          val squid: Squid = createTestSquid(proto.getCurrentSquid)

          accumulator.onAssignmentChanged(squid, assignment)

        case Action.CheckUnattributedLoad(proto) =>
          lastUnattributedLoadOpt = Some(
            checkUnattributedLoad(accumulator, target, proto, lastUnattributedLoadOpt)
          )

        case Action.CheckAttributedLoads(proto) =>
          lastTotalAttributedLoadOpt = Some(
            checkAttributedLoads(accumulator, target, proto, startTime, lastTotalAttributedLoadOpt)
          )

        case action => fail(s"unknown action $action")
      }
    }
  }

  /** Checks the unattributed load based on the proto data, and returns the load. */
  private def checkUnattributedLoad(
      accumulator: SliceletLoadAccumulator,
      target: Target,
      proto: CheckUnattributedLoadP,
      lastRecordedLoadOpt: Option[Double]): Double = {
    val debugMessage: String =
      "Debugging hint: " + proto.debugMsg.getOrElse(fail("expected debug message"))
    val (_, unattributedLoad): (_, SliceletData.SliceLoad) = accumulator.readLoad()

    proto.expectedValue.map { expected: Double =>
      assert(unattributedLoad.primaryRateLoad == expected, debugMessage)
    }

    if (proto.getExpectedDecay) {
      // Verify that the unattributed load has decayed since the last check.
      val lastRecordedLoad: Double =
        lastRecordedLoadOpt.getOrElse(fail("last unattributed load not set"))
      assert(unattributedLoad.primaryRateLoad < lastRecordedLoad, debugMessage)
    }

    proto.expectedCounterValue.map { expected: Long =>
      assert(getUnattributedLoadMetric(target) == expected)
    }

    // Return the primary rate load for further assertions (like decay check) if needed.
    unattributedLoad.primaryRateLoad
  }

  /**
   * Checks the attributed loads based on the proto data, and returns the sum load across all
   * attributed Slices.
   */
  private def checkAttributedLoads(
      accumulator: SliceletLoadAccumulator,
      target: Target,
      proto: CheckAttributedLoadsP,
      startTime: Instant,
      lastRecordedTotalLoadOpt: Option[Double]): Double = {
    val debugMessage: String = proto.debugMsg.getOrElse(fail("expected debug message"))
    val (attributedLoads, _): (Seq[SliceletData.SliceLoad], _) = accumulator.readLoad()

    proto.expectedSize.map { expected: Long =>
      assert(attributedLoads.length == expected, debugMessage)
    }

    // Compare the expected slice info with the actual attributed loads.
    if (proto.expectedSliceInfo.nonEmpty) {
      assert(proto.expectedSliceInfo.length == attributedLoads.length)
      attributedLoads.zip(proto.expectedSliceInfo).map {
        case (actualLoad: SliceletData.SliceLoad, expected: ExpectedSliceInfoP) =>
          // Verify the slices match.
          assert(
            actualLoad.slice == SliceHelper.fromProto(expected.getSlice),
            debugMessage
          )
          expected.approximatePrimaryLoad.map { approximate: Double =>
            // Verify the primary rate load is approximately equal to the expected value, allowing
            // a 0.1 margin of error.
            assert(actualLoad.primaryRateLoad > approximate - 0.1, debugMessage)
            assert(actualLoad.primaryRateLoad < approximate + 0.1, debugMessage)
          }

          if (expected.topSliceKeys.nonEmpty) {
            val topSliceKeys: Set[SliceKey] = expected.topSliceKeys.map { bytes =>
              SliceKey.withIdentityFunction(bytes)
            }.toSet
            val actual: Set[SliceKey] = actualLoad.topKeys.map((_: KeyLoad).key).toSet
            assert(
              actual == topSliceKeys,
              s"$debugMessage: expected top keys $topSliceKeys but got $actual"
            )
          }
      }
    }

    val actualTotalLoad: Double =
      attributedLoads.map((_: SliceletData.SliceLoad).primaryRateLoad).sum
    if (proto.getExpectedDecay) {
      // Verify that the unattributed load has decayed since the last check.
      val lastRecordedTotalLoad: Double =
        lastRecordedTotalLoadOpt.getOrElse(fail("last total attributed load not set"))
      assert(actualTotalLoad < lastRecordedTotalLoad, debugMessage)
    }

    proto.expectedCounterValue.map { expected: Long =>
      assert(getAttributedLoadMetric(target) == expected)
    }

    if (proto.expectedLoads.nonEmpty) {
      val expectedAttributedLoads: Seq[SliceletData.SliceLoad] =
        proto.expectedLoads.map { expectedLoad: SliceLoadP =>
          SliceletData.SliceLoad
            .fromProto(expectedLoad)
            .copy(
              // Adjust the window times to match the start time of the test.
              windowLowInclusive = Instant.ofEpochMilli(
                startTime.toEpochMilli + expectedLoad.getWindowLowInclusiveSeconds * 1000
              ),
              windowHighExclusive = Instant.ofEpochMilli(
                startTime.toEpochMilli + expectedLoad.getWindowHighExclusiveSeconds * 1000
              )
            )
        }

      // Compare the attributed loads read from the accumulator with the expected ones, ignoring
      // the ordering of top keys.
      assert(attributedLoads.length == expectedAttributedLoads.length)
      attributedLoads.zip(expectedAttributedLoads).map {
        case (actual, expect) =>
          assert(actual.slice == expect.slice, debugMessage)
          assert(actual.primaryRateLoad == expect.primaryRateLoad, debugMessage)
          assert(
            actual.windowLowInclusive == expect.windowLowInclusive,
            debugMessage
          )
          assert(
            actual.windowHighExclusive == expect.windowHighExclusive,
            debugMessage
          )
          // Compare top keys, ignoring the order.
          assert(actual.topKeys.toSet == expect.topKeys.toSet, debugMessage)
      }
    }

    // Return the total primary rate load for further assertions (like decay check) if needed.
    actualTotalLoad
  }

  test("Valid cases") {
    // Test plan: Verify that SliceletLoadAccumulator correctly handles valid cases --
    // no exceptions thrown.
    for (validCase <- TEST_DATA.validCases) {
      val target = Target(getNextUniqueTargetName)
      val fakeClock = new FakeTypedClock
      val accumulator =
        new SliceletLoadAccumulator(fakeClock, target, new SliceletMetrics(target))
      val testName: String =
        validCase.name.getOrElse(fail("expected test name for valid case"))
      val startTime: Instant = fakeClock.instant()

      try {
        performActions(accumulator, target, fakeClock, startTime, validCase)
      } catch {
        case NonFatal(e) =>
          fail(s"expected no exception for test case $testName, but got ${e.getMessage}")
      }
    }
  }

  test("SliceKey load distribution tracking is thread-safe") {
    // Test plan: Verify that the accumulator's tracking of the distribution of load over SliceKeys
    // is thread-safe. Verify this by issuing many concurrent calls to `incrementPrimaryRateBy` and
    // `updateMetrics` and checking that in the end the Prometheus histogram exactly matches the
    // results of the workload.
    import SliceletLoadAccumulator.forTest.getSliceKeyLoadDistributionMetric
    val target = Target(getSafeName)
    val accumulator =
      new SliceletLoadAccumulator(new FakeTypedClock, target, new SliceletMetrics(target))

    // Setup: Use randomness in the test, but log the seed, so that test failures can be repro'd.
    val seed: Long = Random.nextLong()
    logger.info(s"Creating RNG with seed $seed")
    val rng = new Random(seed)

    // Setup: Execute 10k concurrent requests using 8 threads on random SliceKeys. Record the bucket
    // counts ourselves to check that the counts match at the end of the test.
    val NUM_REQUESTS: Int = 10000
    val ec = NamedExecutor.create(getSafeName + "-Executor", 8)
    val randomSliceKeyMSBs = new Array[Byte](NUM_REQUESTS)
    rng.nextBytes(randomSliceKeyMSBs)
    val expectedCounts = new Array[Long](256)
    for (sliceKeyMsb <- randomSliceKeyMSBs) {
      expectedCounts(sliceKeyMsb.toInt & 0xFF) += 1
    }
    val futures: Seq[Future[Unit]] = randomSliceKeyMSBs
      .flatMap(
        sliceKeyMsb =>
          // Schedule 2 tasks on the executor: 1 for incrementing the rate for the key, and the
          // other for calling `updateMetrics`.
          Seq(Future {
            accumulator.incrementPrimaryRateBy((sliceKeyMsb.toLong & 0XFFL) << 56, value = 1)
          }(ec), Future {
            accumulator.updateMetrics()
          }(ec))
      )

    // Setup: Wait until all futures have completed, then perform one last call to update metrics.
    TestUtils.awaitResult(Future.sequence(futures)(implicitly, ec), Inf)
    accumulator.updateMetrics()

    // Verify: Counts in the Prometheus metric match expectations.
    var cumulativeCount: Long = 0
    for (i <- 0 until 255) {
      cumulativeCount += expectedCounts(i)
      assert(
        getSliceKeyLoadDistributionMetric(
          ((i + 1).toDouble / 256.0).toString,
          target
        ) == cumulativeCount.toDouble
      )
    }
    cumulativeCount += expectedCounts(255)
    assert(getSliceKeyLoadDistributionMetric("+Inf", target) == cumulativeCount)
  }

  test("SliceletLoadAccumulator is thread-safe") {
    // Test plan: Verify that `SliceletLoadAccumulator` is thread-safe by issuing many concurrent
    // calls to **all** public methods, and checking that
    //  - the final assignment has the highest generation number.
    //  - execution completes with no deadlocks or exceptions.

    // Setup: Use randomness in the test, but log the seed, so that test failures can be repro'd.
    val seed: Long = Random.nextLong()
    logger.info(s"Creating RNG with seed $seed")
    val rng = new Random(seed)
    val ec = NamedExecutor.create(getSafeName + "-Executor", 8)

    val target = Target(getSafeName)

    val squids =
      IndexedSeq(createTestSquid("s-00"), createTestSquid("s-01"), createTestSquid("s-02"))

    // Assuming the squid of the current Slicelet (i.e. SliceletLoadAccumulator) is squid1.
    val currentSquid = squids(1)

    // SliceletLoadAccumulator does not take a Squid as an argument, but its `onAssignmentChanged`
    // method does. In production, the Squid is immutable and has the same lifetime as the Slicelet,
    // so we just need to ensure that every time we call `onAssignmentChanged`, we pass the
    // currentSquid.
    val accumulator =
      new SliceletLoadAccumulator(RealtimeTypedClock, target, new SliceletMetrics(target))

    // Setup: create a random array with 1000 unique generation numbers.
    // Ensure generations are unique to avoid non-deterministic behavior when multiple assignments
    // have the same highest generation.
    val numbers: Seq[Int] = 1 to 1000
    val shuffledNumbers = Random.shuffle(numbers)

    val generations: Array[Generation] =
      shuffledNumbers.map((number: Int) => Generation(Incarnation(42), number)).toArray

    // Setup: create a random assignment for each generation.
    val randomAssignments: Array[Assignment] = generations.map { generation =>
      ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(
          numSlices = 10,
          resources = squids,
          numMaxReplicas = 3,
          rng = rng
        )
      ).commit(
        isFrozen = false,
        consistencyMode = AssignmentConsistencyMode.Affinity,
        generation = generation
      )
    }

    // Setup: concurrently executes all methods:
    //  - reports load on the current Squid,
    //  - updates the assignment,
    //  - reads load,
    //  - updates metrics.
    val futures: Seq[Future[Unit]] = (1 to generations.length * 100).map { i =>
      Future {
        // Try to report load on various keys
        accumulator.incrementPrimaryRateBy(key = i % 100, value = 1)
        if (i % 100 == 0) {
          val assignment = randomAssignments(i / 100 - 1)
          accumulator.onAssignmentChanged(currentSquid, assignment)
        } else if (i % 100 == 33) {
          // Periodically update metrics to exercise the metrics path
          accumulator.updateMetrics()
        } else if (i % 100 == 66) {
          // Periodically read load to exercise the read path
          accumulator.readLoad()
        }
        () // returns nothing
      }(ec)
    }

    // Should complete without deadlocks or exceptions
    TestUtils.awaitResult(Future.sequence(futures)(implicitly, ec), Inf)

    val (attributed_loads, _): (Vector[SliceletData.SliceLoad], _) = accumulator.readLoad()

    // Get the assigned slice map for this Squid from the accumulator.
    val assignedSlicesMap: SliceMap[GapEntry[Slice]] =
      SliceMap.createFromOrderedDisjointEntries(
        attributed_loads.map { (_: SliceletData.SliceLoad).slice },
        slice => slice // each entry is already a Slice, so return it as-is
      )

    // Gets the assignment with the highest generation number.
    val highestGenerationAssignment: Assignment =
      randomAssignments.maxBy((_: Assignment).generation)

    // Verify that the final assigned slices matches the assignment with the highest generation.
    val expectedAssignedSlices: Seq[Slice] =
      highestGenerationAssignment
        .getAssignedSliceAssignments(currentSquid)
        .map((_: SliceAssignment).slice)

    val expectedAssignedSlicesMap: SliceMap[GapEntry[Slice]] =
      SliceMap.createFromOrderedDisjointEntries(
        expectedAssignedSlices,
        slice => slice // each entry is already a Slice, so return it as-is
      )

    // Compare the two slice maps by checking that they have the same entries
    assert(assignedSlicesMap.entries.size == expectedAssignedSlicesMap.entries.size)
    assert(
      assignedSlicesMap == expectedAssignedSlicesMap,
      s"expected $expectedAssignedSlicesMap, but got $assignedSlicesMap"
    )
  }
}
