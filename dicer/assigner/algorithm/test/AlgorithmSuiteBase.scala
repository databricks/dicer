package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.caching.util.{CachingErrorCode, FakeTypedClock, MetricUtils, Severity}
import com.databricks.caching.util.TestUtils.TestName

import com.databricks.dicer.assigner.ProposedAssignment
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.testing.ConfigTestUtils
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  SizeLimitMetricConfig
}
import com.databricks.dicer.common.{Assignment, Generation, SliceAssignment, TargetHelper}
import com.databricks.dicer.common.testing.{SliceTestUtils}
import com.databricks.dicer.common.testing.SliceTestUtils.{createRandomLoadMap, createTestSquid}
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

/**
 * Base class for parameterized and non-parameterized [[AlgorithmSuite]] classes containing common
 * state and helpers.
 */
abstract class AlgorithmSuiteBase extends DatabricksTest with TestName {

  /**
   * Clock used to determine assignment generations and measure the passage of time, which is used
   * to figure out the penalty to apply for "recent" reassignments.
   */
  protected val fakeClock = new FakeTypedClock()

  override def afterEach(): Unit = {
    // General assertion to ensure no unexpected scenarios happened during each run of the
    // Algorithm.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_TOO_FEW_SLICES,
        prefix = TargetHelper.TargetOps(target).getLoggerPrefix
      ) == 0
    )
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_TOO_MANY_SLICES,
        prefix = TargetHelper.TargetOps(target).getLoggerPrefix
      ) == 0
    )
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_SLICE_AFTER_MERGE_TOO_HOT,
        prefix = TargetHelper.TargetOps(target).getLoggerPrefix
      ) == 0
    )
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.ASSIGNER_INVALID_HOMOMORPHIC_ASSIGNMENT,
        prefix = TargetHelper.TargetOps(target).getLoggerPrefix
      ) == 0
    )
    super.afterEach()
  }

  /** Target that is unique to the current test case. */
  final protected def target: Target = Target(getSafeName)

  protected def defaultTargetConfig: InternalTargetConfig = {
    InternalTargetConfig.forTest.DEFAULT
  }

  /**
   * Creates a random number generator and prints the seed chosen for the generator to make
   * reproducing flaky test failures easier. (If a test flakes, temporarily hard-code the logged
   * seed in this method and rerun the test that failed.)
   */
  final protected def createRng(): Random = {
    val seed: Long = Random.nextLong()
    logger.info(s"Creating RNG with seed $seed")
    new Random(seed)
  }

  /**
   * Creates a generation with a generation number tracking the current time, as reported by
   * [[fakeClock]].
   */
  final protected def createGeneration(): Generation = {
    SliceTestUtils.createLooseGeneration(fakeClock.instant().toEpochMilli)
  }

  /** Creates `n` resources with names like "resource0", "resource1", etc. */
  final protected def createNResources(n: Int, creationTimeOffset: Long = 0): Resources = {
    val healthyResources: Seq[Squid] = (0 until n).map { i =>
      createTestSquid(s"resource$i", creationTimeOffset)
    }
    Resources.create(healthyResources)
  }

  /**
   * Creates `n` resources with names like "resource0", "resource1", etc. starting from
   * `startIndex`.
   */
  final protected def createNResourcesFrom(
      startIndex: Int,
      n: Int,
      creationTimeOffset: Long = 0): Resources = {
    val healthyResources: Seq[Squid] = (startIndex until startIndex + n).map { i =>
      createTestSquid(s"resource$i", creationTimeOffset)
    }
    Resources.create(healthyResources)
  }

  /**
   * Returns the expected ratio of `max_load_hint` that will be reserved for future potential load
   * given `reservationHint`.
   */
  final protected def getExpectedReservationRatio(reservationHint: ReservationHintP): Double = {
    reservationHint match {
      case ReservationHintP.NO_RESERVATION => 0.01
      case ReservationHintP.SMALL_RESERVATION => 0.1
      case ReservationHintP.MEDIUM_RESERVATION => 0.2
      case ReservationHintP.LARGE_RESERVATION => 0.4
    }
  }

  /**
   * Commits the given proposal as an unfrozen assignment using affinity mode and with a generation
   * tracking [[fakeClock]]. Allows tests to ignore these details, since they are irrelevant to the
   * algorithm we are testing. Time is automatically advanced to avoid collisions with the
   * predecessor generation (tests that explicitly reason about time advancing, e.g. for churn
   * penalties, must manually advance [[fakeClock]]).
   */
  final protected def commitProposal(proposal: ProposedAssignment): Assignment = {
    proposal.predecessorOpt match {
      case Some(predecessor: Assignment) if createGeneration() == predecessor.generation =>
        fakeClock.advanceBy(1.second)
      case _ =>
    }
    proposal.commit(
      isFrozen = false,
      createGeneration()
    )
  }

  /**
   * Calls [[Algorithm.generateInitialAssignment()]] to generate an initial assignment. The proposal
   * generated by the algorithm is committed using [[commitProposal()]].
   */
  final protected def generateInitialAssignment(
      target: Target,
      resources: Resources,
      keyReplicationConfig: KeyReplicationConfig): Assignment = {
    val proposal: ProposedAssignment =
      ProposedAssignment(
        predecessorOpt = None,
        sliceMap = Algorithm.generateInitialAssignment(target, resources, keyReplicationConfig),
        assignerServiceInfoOpt = None
      )
    commitProposal(proposal)
  }

  /**
   * Calls [[Algorithm.generateAssignment()]] to generate a successor assignment. The proposal
   * generated by the algorithm is committed using [[commitProposal()]].
   */
  final protected def generateAssignment(
      target: Target,
      targetConfig: InternalTargetConfig,
      resources: Resources,
      predecessorAndBaseAssignment: Assignment,
      loadMap: LoadMap): Assignment = {
    generateAssignmentWithSizes(
      target,
      targetConfig,
      resources,
      predecessorAndBaseAssignment,
      loadMap,
      sizeMapOpt = None
    )
  }

  /** Like [[generateAssignment]], but also passes `sizeMapOpt` to the algorithm. */
  final protected def generateAssignmentWithSizes(
      target: Target,
      targetConfig: InternalTargetConfig,
      resources: Resources,
      predecessorAndBaseAssignment: Assignment,
      loadMap: LoadMap,
      sizeMapOpt: Option[SizeMap]): Assignment = {
    val proposal: ProposedAssignment = ProposedAssignment(
      predecessorOpt = Some(predecessorAndBaseAssignment),
      sliceMap = Algorithm
        .generateAssignment(
          fakeClock.instant(),
          target,
          targetConfig,
          resources,
          predecessorAndBaseAssignment.sliceMap,
          loadMap,
          sizeMapOpt
        )
        .sliceAssignments,
      assignerServiceInfoOpt = None
    )
    commitProposal(proposal)
  }

  /**
   * Calls [[Algorithm.generateHomomorphicAssignment()]] to generate a homomorphic assignment.
   * The proposal generated by the algorithm is committed using [[commitProposal()]].
   */
  final protected def generateHomomorphicAssignment(
      target: Target,
      resources: Resources,
      predecessorAndBaseAssignment: Assignment): Assignment = {
    val proposal: ProposedAssignment = ProposedAssignment(
      predecessorOpt = Some(predecessorAndBaseAssignment),
      sliceMap = Algorithm
        .generateHomomorphicAssignment(
          target,
          resources,
          baseAssignmentSliceMap = predecessorAndBaseAssignment.sliceMap
        )
        .sliceAssignments,
      assignerServiceInfoOpt = None
    )
    commitProposal(proposal)
  }

  final protected def createConfigForLoadBalancing(
      churnConfig: ChurnConfig,
      maxLoadHint: Double,
      imbalanceToleranceHint: ImbalanceToleranceHintP = ImbalanceToleranceHintP.DEFAULT,
      uniformLoadReservationHint: ReservationHintP = ReservationHintP.NO_RESERVATION)
      : InternalTargetConfig = {
    defaultTargetConfig.copy(
      loadBalancingConfig = LoadBalancingConfig(
        loadBalancingInterval = 10.seconds, // doesn't matter to algo
        churnConfig,
        LoadBalancingMetricConfig(maxLoadHint, imbalanceToleranceHint, uniformLoadReservationHint)
      )
    )
  }

  /**
   * Returns a [[MutableAssignment]] initialized from `predecessor` with the given measurements and
   * no churn penalties, for tests that run individual algorithm phases.
   */
  final protected def createMutableAssignment(
      predecessor: Assignment,
      resources: Resources,
      loadMap: LoadMap,
      sizeMapOpt: Option[SizeMap]): MutableAssignment = {
    new MutableAssignment(
      fakeClock.instant(),
      predecessor.sliceMap,
      resources,
      loadMap,
      sizeMapOpt,
      ConfigTestUtils.ZERO_PENALTY_CHURN_CONFIG
    )
  }

  /**
   * Returns the [[Algorithm.Config]] for `targetConfig` on `resources` with the total load in
   * `loadMap` (without load reservation adjustments), for tests that run individual algorithm
   * phases.
   */
  final protected def createAlgorithmConfig(
      targetConfig: InternalTargetConfig,
      resources: Resources,
      loadMap: LoadMap): Algorithm.Config = {
    Algorithm.Config.create(
      target,
      targetConfig.loadBalancingConfig,
      targetConfig.keyReplicationConfig,
      resources.availableResources.size,
      loadMap.getLoad(Slice.FULL)
    )
  }

  /** Returns `targetConfig` with a size limit hint of `sizeLimitHint` per resource. */
  final protected def withSizeLimit(
      targetConfig: InternalTargetConfig,
      sizeLimitHint: Double): InternalTargetConfig = {
    targetConfig.copy(
      loadBalancingConfig = targetConfig.loadBalancingConfig.copy(
        primaryGaugeMetricOpt = Some(SizeLimitMetricConfig(sizeLimitHint))
      )
    )
  }

  /**
   * Returns a random size map with the given `totalSize`, generated the same way as
   * [[createRandomLoadMap]] (but independently of any load map).
   */
  protected def createRandomSizeMap(
      rng: Random,
      totalSize: Double,
      numSlices: Int,
      maxToMinDensityRatio: Double): SizeMap = {
    val randomMap: LoadMap =
      createRandomLoadMap(rng, totalLoad = totalSize, numSlices, maxToMinDensityRatio)
    val sizeEntries: Vector[SizeMap.Entry] = randomMap.sliceMap.entries.map {
      entry: LoadMap.Entry =>
        SizeMap.Entry(entry.slice, entry.load)
    }
    SizeMap.newBuilder().putSize(sizeEntries: _*).build()
  }

  /** Returns the total size borne by each resource in `assignment` according to `sizeMap`. */
  protected def getSizeByResource(assignment: Assignment, sizeMap: SizeMap): Map[Squid, Double] = {
    val sizes = mutable.Map[Squid, Double]().withDefaultValue(0.0)
    for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
      val sliceSize: Double = sizeMap.getSize(sliceAssignment.slice)
      for (resource: Squid <- sliceAssignment.resourcesSet) {
        sizes(resource) += sliceSize
      }
    }
    sizes.toMap
  }

  /**
   * Returns the maximum desired size per resource that the algorithm commits to (see
   * [[Algorithm.applyDesiredSizeLimit]]) for `assignment` on `resources`, with a size limit hint of
   * `sizeLimitHint` and the sizes in `sizeMap`.
   */
  final protected def getMaxDesiredSize(
      sizeLimitHint: Double,
      resources: Resources,
      assignment: Assignment,
      sizeMap: SizeMap): Double = {
    // Load does not affect the maximum desired size.
    val config: Algorithm.Config = createAlgorithmConfig(
      withSizeLimit(defaultTargetConfig, sizeLimitHint),
      resources,
      LoadMap.UNIFORM_LOAD_MAP
    )
    val mutableAssignment: MutableAssignment =
      createMutableAssignment(assignment, resources, LoadMap.UNIFORM_LOAD_MAP, Some(sizeMap))
    mutableAssignment.lockReplicaCounts()
    Algorithm.applyDesiredSizeLimit(config, mutableAssignment)
    mutableAssignment.getMaxDesiredSizeOpt.getOrElse(fail("Size limit was not applied."))
  }
}
