package com.databricks.dicer.assigner.algorithm

import scala.concurrent.duration._
import scala.util.Random

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.caching.util.{CachingErrorCode, FakeTypedClock, MetricUtils, Severity}
import com.databricks.caching.util.TestUtils.TestName

import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig
}
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  ProposedAssignment,
  TargetHelper,
  TestSliceUtils
}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.Target
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
  protected def target: Target = Target(getSafeName)

  protected def defaultTargetConfig: InternalTargetConfig = {
    InternalTargetConfig.forTest.DEFAULT
  }

  /**
   * Creates a random number generator and prints the seed chosen for the generator to make
   * reproducing flaky test failures easier. (If a test flakes, temporarily hard-code the logged
   * seed in this method and rerun the test that failed.)
   */
  protected def createRng(): Random = {
    val seed: Long = Random.nextLong()
    logger.info(s"Creating RNG with seed $seed")
    new Random(seed)
  }

  /**
   * Creates a generation with a generation number tracking the current time, as reported by
   * [[fakeClock]].
   */
  protected def createGeneration(): Generation = {
    TestSliceUtils.createLooseGeneration(fakeClock.instant().toEpochMilli)
  }

  /** Creates `n` resources with names like "resource0", "resource1", etc. */
  protected def createNResources(n: Int)(implicit salt: String = ""): Resources = {
    val healthyResources: Seq[Squid] = (0 until n).map { i =>
      createTestSquid(s"resource$i", salt)
    }
    Resources.create(healthyResources)
  }

  /**
   * Creates `n` resources with names like "resource0", "resource1", etc. starting from
   * `startIndex`.
   */
  protected def createNResourcesFrom(startIndex: Int, n: Int)(
      implicit salt: String = ""): Resources = {
    val healthyResources: Seq[Squid] = (startIndex until startIndex + n).map { i =>
      createTestSquid(s"resource$i", salt)
    }
    Resources.create(healthyResources)
  }

  /**
   * Returns the expected ratio of `max_load_hint` that will be reserved for future potential load
   * given `reservationHint`.
   */
  protected def getExpectedReservationRatio(reservationHint: ReservationHintP): Double = {
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
  protected def commitProposal(proposal: ProposedAssignment): Assignment = {
    proposal.predecessorOpt match {
      case Some(predecessor: Assignment) if createGeneration() == predecessor.generation =>
        fakeClock.advanceBy(1.second)
      case _ =>
    }
    proposal.commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      createGeneration()
    )
  }

  /**
   * Calls [[Algorithm.generateInitialAssignment()]] to generate an initial assignment. The proposal
   * generated by the algorithm is committed using [[commitProposal()]].
   */
  protected def generateInitialAssignment(
      target: Target,
      resources: Resources,
      keyReplicationConfig: KeyReplicationConfig): Assignment = {
    val proposal: ProposedAssignment =
      ProposedAssignment(
        predecessorOpt = None,
        Algorithm.generateInitialAssignment(target, resources, keyReplicationConfig)
      )
    commitProposal(proposal)
  }

  /**
   * Calls [[Algorithm.generateAssignment()]] to generate a successor assignment. The proposal
   * generated by the algorithm is committed using [[commitProposal()]].
   */
  protected def generateAssignment(
      target: Target,
      targetConfig: InternalTargetConfig,
      resources: Resources,
      predecessorAndBaseAssignment: Assignment,
      loadMap: LoadMap): Assignment = {
    val proposal: ProposedAssignment = ProposedAssignment(
      predecessorOpt = Some(predecessorAndBaseAssignment),
      Algorithm.generateAssignment(
        fakeClock.instant(),
        target,
        targetConfig,
        resources,
        predecessorAndBaseAssignment.sliceMap,
        loadMap
      )
    )
    commitProposal(proposal)
  }

  /**
   * Calls [[Algorithm.generateHomomorphicAssignment()]] to generate a homomorphic assignment.
   * The proposal generated by the algorithm is committed using [[commitProposal()]].
   */
  protected def generateHomomorphicAssignment(
      target: Target,
      resources: Resources,
      predecessorAndBaseAssignment: Assignment): Assignment = {
    val proposal: ProposedAssignment = ProposedAssignment(
      predecessorOpt = Some(predecessorAndBaseAssignment),
      Algorithm.generateHomomorphicAssignment(
        target,
        resources,
        baseAssignmentSliceMap = predecessorAndBaseAssignment.sliceMap
      )
    )
    commitProposal(proposal)
  }

  protected def createConfigForLoadBalancing(
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
}
