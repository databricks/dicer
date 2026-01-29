package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.util.Random

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.caching.util.AsciiTable
import com.databricks.dicer.assigner.AssignmentStats.AssignmentLoadStats
import com.databricks.dicer.assigner.config.ChurnConfig
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingMetricConfig
}
import com.databricks.dicer.common.Assignment
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.Squid

/** Chaos tests for the Algorithm. */
class AlgorithmChaosSuite extends AlgorithmSuiteBase {
  private case class DiscreteInverseCdf(points: SortedMap[Double, Double]) {
    private var previousValue = Double.MinValue
    for (entry <- points) {
      val (cumulativeProbability, value): (Double, Double) = entry
      require(0.0 <= cumulativeProbability && cumulativeProbability <= 1.0)
      require(value >= previousValue)
      previousValue = value
    }
  }

  /**
   * Case class encapsulating the configurable parameters for the parameterized chaos test below.
   *
   * @param churnConfig The churn config used for generating new assignment in the chaos test.
   * @param minToAvgImbalanceRatioIcdfBound
   *        An expected upper bound of the ICDF for the distribution of the imbalance ratio that the
   *        minimum resource load can deviate from the average resource load when verifying the
   *        generated assignments. The ratio is relative to the max load hint or average resource
   *        load, whichever is larger. The "bound" means, for each (p, V_bound) in the ICDF bound,
   *        the measured pth percentile imbalance ratio V_measured must be less than or equal to
   *        V_bound.
   * @param avgToMaxImbalanceRatioIcdfBound
   *        Same as [[minToAvgImbalanceRatioIcdfBound]], but bounds the imbalance ratio that the
   *        maximum resource load can deviate from the average resource load.
   */
  private case class ChaosTestCase(
      churnConfig: ChurnConfig,
      minToAvgImbalanceRatioIcdfBound: DiscreteInverseCdf,
      avgToMaxImbalanceRatioIcdfBound: DiscreteInverseCdf
  )

  /** The parameters for the chaos test. */
  private val chaosTestCases: Seq[ChaosTestCase] = {

    // The hard-coded values of ICDF bounds of imbalance ratio below are collected from running the
    // Algorithm many times. Note these numbers are meaningful only based on the fact that the chaos
    // test is using TIGHT imbalance tolerance config.
    Seq(
      ChaosTestCase(
        churnConfig = ChurnConfig.DEFAULT,
        // The imbalance ratio bounds for DEFAULT churn config.
        minToAvgImbalanceRatioIcdfBound = DiscreteInverseCdf(
          points = SortedMap(
            0.10 -> 0.01,
            0.50 -> 0.02,
            0.90 -> 0.03,
            0.95 -> 0.04,
            0.99 -> 0.15,
            1.00 -> 0.30
          )
        ),
        avgToMaxImbalanceRatioIcdfBound = DiscreteInverseCdf(
          points = SortedMap(
            0.10 -> 0.01,
            0.50 -> 0.02,
            0.90 -> 0.03,
            0.95 -> 0.04,
            0.99 -> 0.35,
            1.00 -> 0.70
          )
        ),
      ),
      ChaosTestCase(
        churnConfig = ChurnConfig.ZERO_PENALTY,
        // When using the zero churn penalty, we should almost always be able to satisfy the ideal
        // 2.5% imbalance tolerance target, so we can observe the p99 imbalance ratio bound is
        // about 2.5%. But given that the replication case makes the Algorithm more restricted,
        // the actual maximum imbalance ratio can be high above 2.5%, so we see the p100 imbalance
        // ratio we measured is more than 7%. Similar reasoning applies to
        // `avgToMaxImbalanceRatioIcdfBound` below.
        minToAvgImbalanceRatioIcdfBound = DiscreteInverseCdf(
          points = SortedMap(
            0.10 -> 0.01,
            0.50 -> 0.02,
            0.99 -> 0.15,
            1.00 -> 0.30
          )
        ),
        avgToMaxImbalanceRatioIcdfBound = DiscreteInverseCdf(
          points = SortedMap(
            0.10 -> 0.01,
            0.50 -> 0.02,
            0.99 -> 0.35,
            1.00 -> 0.70
          )
        )
      )
    )
  }

  gridTest("Chaos test")(chaosTestCases) { chaosTestCase: ChaosTestCase =>
    // Test plan: Verify that the algorithm generates reasonable assignments across many rounds of
    // generation. The test begins with an initial assignment, then updates the assignment 1000
    // times, each time with a random set of input parameters. The test verifies that all the
    // produced assignments meet the expected properties, and that the distribution of imbalance
    // ratios does not exceed the expected upper bounds at various quantiles (see `ChaosTestCase`).
    //
    // The range of randomized inputs for each round are as follows:
    // - Number of resources: uniformly distributed between 6 and 8 (in each round, we pick a random
    //   subset of between 6 and 8 resources from a stable set of 8 resources, simulating random
    //   resources in the set going down/up).
    // - Uniform load reservation: uniformly distributed over all options
    // - Min replicas: uniformly distributed over the set {1, 2, 3}
    // - Max replicas: uniformly distributed over the set minReplicas + {0, 2, Inf}
    // - Total load: uniformly distributed between 0 and 1000.0 * numResources * 1.2 (i.e. can be up
    //   to 20% over total capacity).
    // - Whether there's a hot key. If there's a hot key, it has load equal to 50% of the
    //   total load.
    //   - Note: We intentionally do not attempt to simulate multiple hot keys, since when we do,
    //     sometimes those two hot keys end up being on the same resource before the algo runs. When
    //     this happens, neither key can be moved to another resource without making the target
    //     hotter than the source, so the algo can't do anything, causing the resulting assignment
    //     to be highly load imbalanced and forces the test to relax the imbalance tolerance to such
    //     a degree that the check becomes almost meaningless.
    val rng: Random = createRng()

    // Number of rounds of assignment generation
    val generationRounds: Int = 1000
    // Max load hint for all rounds (we vary the total load in each round, instead of this)
    val maxLoadHint: Double = 1000.0
    // The percentage of total load that each hot key in the test accounts for
    val hotKeyLoadFraction: Double = 0.5
    // The number of slices to include in the load maps
    val loadMapNumSlices: Int = 128
    // Max to min load density constraint for generating random load maps
    val loadMapMaxToMinDensityRatio: Double = 4.0
    // Use a TIGHT imbalance tolerance (2.5%) to stress the migration phase of the algorithm.
    val loadImbalanceToleranceHintP: ImbalanceToleranceHintP = ImbalanceToleranceHintP.TIGHT
    // Total set of resources to choose from to be "healthy" in any given round
    val allResources: Resources = createNResources(8)

    // Pick a random subset of between 6 and 8 resources to be the set of healthy resources
    def pickSubsetOfResources(): Resources = {
      Resources.create(rng.shuffle(allResources.availableResources.toSeq).take(rng.nextInt(3) + 6))
    }
    // Choose the uniform load reservation hint uniformly at random among all available options
    def pickUniformLoadReservationHintP: ReservationHintP = ReservationHintP.values(
      rng.nextInt(ReservationHintP.values.length)
    )
    // Choose between {1,2,3} min replicas and max replicas in the range [minReplicas,
    // numResources]. If there is a hot key in this test, this method ensures that maxReplicas is
    // high enough that the hot key can be replicated sufficiently to meet load balancing
    // constraints. In particular, given the hot key load fraction, the maxReplicas is set to be at
    // least the same fraction of available resources:
    //
    // minimum max replicas
    // = Math.ceil(totalLoad * hotKeyLoadFraction / maxDesiredLoad)
    // < Math.ceil(totalLoad * hotKeyLoadFraction / averageLoad)
    // = Math.ceil(totalLoad * hotKeyLoadFraction / (totalLoad / numResources))
    // = Math.ceil(numResources * hotKeyLoadFraction)
    def pickKeyReplicationConfig(numResources: Int, hasHotKey: Boolean): KeyReplicationConfig = {
      val minReplicas: Int = 1 + rng.nextInt(3)
      val maxReplicaMinBound: Int = if (hasHotKey) {
        Math.ceil(numResources * hotKeyLoadFraction).toInt
      } else {
        1
      }
      val maxReplicas: Int =
        (minReplicas + rng.nextInt(numResources - minReplicas + 1)).max(maxReplicaMinBound)
      KeyReplicationConfig(
        minReplicas = minReplicas,
        maxReplicas = maxReplicas
      )
    }
    // Choose a total load that is anywhere between 0% and 120% of the max capacity
    def pickTotalLoad(numResources: Int): Double = {
      rng.nextDouble() * 1.2 * maxLoadHint * numResources
    }
    // Choose whether this round will have a hot key
    def pickHasHotKey: Boolean = rng.nextBoolean()

    // Start with an initial assignment over a random subset of resources
    var previousAssignment: Assignment =
      generateInitialAssignment(
        target,
        pickSubsetOfResources(),
        KeyReplicationConfig.DEFAULT_SINGLE_REPLICA
      )

    // ArrayBuffers to track the imbalance ratios as the Algorithm iterates:
    // each time a new assignment is generated, the measured min imbalance ratio
    // (i.e. (avg - min)/ adjustedMaxLoadHint) and max imbalance ratio
    // (i.e. (max - avg) / adjustedMaxLoadHint) is recorded in these arrays.
    val minLoadImbalanceRatios = mutable.ArrayBuffer[Double]()
    val maxLoadImbalanceRatios = mutable.ArrayBuffer[Double]()

    for (_: Int <- 0 until generationRounds) {
      val resources: Resources = pickSubsetOfResources()
      val hasHotKey: Boolean = pickHasHotKey
      val totalLoad: Double = pickTotalLoad(resources.availableResources.size)
      val loadMap: LoadMap = if (!hasHotKey) {
        createRandomLoadMap(
          rng,
          totalLoad = totalLoad,
          numSlices = loadMapNumSlices,
          maxToMinDensityRatio = loadMapMaxToMinDensityRatio
        )
      } else {
        // Include a hot key in the load map by first generating a load map with (1 -
        // `hotKeyLoadFraction`) load, then picking some random slice's low-key to be the hot key,
        // and splicing it out from the load map to assign it `hotKeyLoadFraction` of the total
        // load, then re-build the load map with this new single hot key slice.
        val baseLoadMap: LoadMap = createRandomLoadMap(
          rng,
          totalLoad = totalLoad * (1.0 - hotKeyLoadFraction),
          numSlices = loadMapNumSlices,
          maxToMinDensityRatio = loadMapMaxToMinDensityRatio
        )
        val hotKey: SliceKey =
          baseLoadMap.sliceMap
            .entries(rng.nextInt(baseLoadMap.sliceMap.entries.size))
            .slice
            .lowInclusive
        val entries: Vector[LoadMap.Entry] = baseLoadMap.sliceMap.entries.flatMap {
          (loadMapEntry: LoadMap.Entry) =>
            if (loadMapEntry.slice.lowInclusive == hotKey) {
              val slice: Slice = loadMapEntry.slice
              val key: SliceKey = slice.lowInclusive
              val left: LoadMap.Entry =
                LoadMap.Entry(Slice(key, key.successor()), totalLoad * hotKeyLoadFraction)
              val right: LoadMap.Entry =
                LoadMap.Entry(Slice(key.successor(), slice.highExclusive), loadMapEntry.load)
              Vector(left, right)
            } else {
              Vector(loadMapEntry)
            }
        }
        LoadMap.newBuilder().putLoad(entries: _*).build()
      }
      val targetConfig = createConfigForLoadBalancing(
        chaosTestCase.churnConfig,
        maxLoadHint,
        imbalanceToleranceHint = loadImbalanceToleranceHintP,
        uniformLoadReservationHint = pickUniformLoadReservationHintP
      ).copy(
        keyReplicationConfig =
          pickKeyReplicationConfig(resources.availableResources.size, hasHotKey)
      )
      val newAssignment: Assignment = generateAssignment(
        target,
        targetConfig,
        resources,
        previousAssignment,
        loadMap
      )

      val metric: LoadBalancingMetricConfig = targetConfig.loadBalancingConfig.primaryRateMetric
      val totalRawLoad: Double =
        newAssignment.sliceMap.entries.map(_.primaryRateLoadOpt.getOrElse(0.0)).sum
      val totalReservedLoad: Double = targetConfig.loadBalancingConfig.primaryRateMetric
        .getUniformReservedLoad(
          availableResourceCount = newAssignment.assignedResources.size
        )
      val totalReservationAdjustedLoad: Double = totalRawLoad + totalReservedLoad
      val averageReservationAdjustedLoad
          : Double = totalReservationAdjustedLoad / resources.availableResources.size
      val adjustedMaxLoadHint: Double = metric.maxLoadHint.max(averageReservationAdjustedLoad)

      // Verify that the assignment is reasonable. However, we don't care about the imbalance
      // verification inside `assertDesirableAssignmentProperties`, as we are performing more
      // fine-grained verification later based on statistics. So we just pass in an
      // `imbalanceToleranceOverrideOpt` that is large enough to pass
      // `assertDesirableAssignmentProperties`.
      assertDesirableAssignmentProperties(
        targetConfig,
        newAssignment,
        resources,
        imbalanceToleranceOverrideOpt = Some(adjustedMaxLoadHint)
      )

      // Calculate the reservation adjusted load for each resource in the new assigment, and then
      // record the minimum and maximum values of them for statistics verification.
      val reservationAdjustedLoadStats: AssignmentLoadStats =
        AssignmentLoadStats.calculateSelfTrackedAdjustedLoadStats(
          newAssignment,
          targetConfig.loadBalancingConfig
        )
      val reservationAdjustedLoadByResource: Map[Squid, Double] =
        reservationAdjustedLoadStats.loadByResource
      minLoadImbalanceRatios +=
      (averageReservationAdjustedLoad - reservationAdjustedLoadByResource.values.min) /
      adjustedMaxLoadHint
      maxLoadImbalanceRatios +=
      (reservationAdjustedLoadByResource.values.max - averageReservationAdjustedLoad) /
      adjustedMaxLoadHint

      previousAssignment = newAssignment
    }

    // Finished all iterations of assignment generation. Verify the imbalance ratio statistics.
    for (entry <- Seq(
        // The "min" and "max" Strings indicating imbalance ratio types used in debug messages.
        (minLoadImbalanceRatios, chaosTestCase.minToAvgImbalanceRatioIcdfBound, "min"),
        (maxLoadImbalanceRatios, chaosTestCase.avgToMaxImbalanceRatioIcdfBound, "max")
      )) {
      val (actualImbalanceRatios, imbalanceRatioBound, imbalanceRatioType): (
          Seq[Double],
          DiscreteInverseCdf,
          String) = entry
      val sortedImbalanceRatios: Seq[Double] = actualImbalanceRatios.sorted
      for (entry <- imbalanceRatioBound.points) {
        val (cumulativeProbability, imbalanceRatioBound): (Double, Double) = entry
        val indexAtCumulativeProbability: Int =
          ((sortedImbalanceRatios.size - 1) * cumulativeProbability).toInt
        val measuredImbalanceRatio: Double = sortedImbalanceRatios(indexAtCumulativeProbability)
        def getDebugMessageTable: AsciiTable = {
          new AsciiTable(
            AsciiTable.Header("percentile"),
            AsciiTable.Header(s"measured $imbalanceRatioType imbalance ratio"),
            AsciiTable.Header(s"$imbalanceRatioType imbalance ratio bound")
          ).appendRow(
            (cumulativeProbability * 100).toInt.toString,
            measuredImbalanceRatio.toString,
            imbalanceRatioBound.toString
          )
        }
        assert(
          sortedImbalanceRatios(indexAtCumulativeProbability) <= imbalanceRatioBound,
          s"\n$getDebugMessageTable\n" // Insert newlines to show the table in a clear format.
        )
      }
    }
  }
}
