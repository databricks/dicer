package com.databricks.dicer.assigner.algorithm

import java.time.Instant

import scala.collection.mutable

import com.databricks.caching.util.{CachingErrorCode, PrefixLogger}
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.{
  ProposedSliceAssignment,
  SliceAssignment,
  SliceMapHelper,
  SliceWithResources
}
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}

/**
 * The "stateless" algorithm that given the previous assignment and various signals, generates
 * the new assignment.
 */
object Algorithm {

  // Minimum and maximum number of slice replicas that should be in the assignment per resource.
  // That is, if there are `N` resources, the assignment should have between
  // `N * MIN_AVG_SLICE_REPLICAS` and `N * MAX_AVG_SLICE_REPLICAS` slices replicas (not base
  // slices).
  private[assigner] val MIN_AVG_SLICE_REPLICAS: Int = 32
  private[assigner] val MAX_AVG_SLICE_REPLICAS: Int = MIN_AVG_SLICE_REPLICAS * 2

  /**
   * REQUIRES: `resources.availableResources` is not empty.
   *
   * Generates the initial assignment for `target` by assigning slices to `resources`. The returned
   * assignment is guaranteed to assign at least one slice to each available resource, and minimally
   * replicate each slice according to `keyReplicationConfig`. If the number of available resources
   * is less than `keyReplicationConfig.minReplicas`, the number of replicas will be adjusted
   * accordingly.
   */
  def generateInitialAssignment(
      target: Target,
      resources: Resources,
      keyReplicationConfig: KeyReplicationConfig): SliceMap[ProposedSliceAssignment] = {
    InitialAssignmentAlgorithm.generateInitialAssignment(target, resources, keyReplicationConfig)
  }

  /**
   * Given the fact that a signal change has occurred, generates a new assignment and returns it.
   * The returned assignment is guaranteed to assign at least one slice to each available resource.
   * If the set of available resources is empty, the returned assignment will be the same as the
   * input assignment, except that its primary rate load will be overwritten by `loadMap`.
   *
   * @param instant the time at which the assignment is being generated. Used to determine the churn
   *                penalty applied for recently reassigned Slices (it is used to determine how
   *                recent the reassignment was).
   * @param target the target that is being load balanced, used for debugging purposes only
   * @param resources the current set of healthy pods
   * @param baseAssignmentSliceMap the slice map of the predecessor for the new assignment.
   * @param loadMap the current load on the application.
   * @param targetConfig the configuration parameters specific to the target
   */
  def generateAssignment(
      instant: Instant,
      target: Target,
      targetConfig: InternalTargetConfig,
      resources: Resources,
      baseAssignmentSliceMap: SliceMap[SliceAssignment],
      loadMap: LoadMap): SliceMap[ProposedSliceAssignment] = {
    val availableResourceCount: Int = resources.availableResources.size
    val sliceAssignments: SliceMap[SliceWithResources] =
      if (availableResourceCount == 0) {
        // No available resources, return the same assignment.
        baseAssignmentSliceMap.map((_: SliceWithResources).slice) {
          sliceAssignment: SliceAssignment =>
            sliceAssignment.sliceWithResources
        }
      } else {
        // Load balance while accounting for predicted future uniformly-distributed load.
        val adjustedLoadMap: LoadMap =
          computeAdjustedLoadMap(targetConfig.loadBalancingConfig, availableResourceCount, loadMap)

        // We need to compute the total adjusted load across all Slices to figure out the thresholds
        // (merge, split, replicate, de-replicate, imbalance) to use in the algorithm.
        val totalAdjustedLoad: Double = adjustedLoadMap.getLoad(Slice.FULL)
        val config =
          Config.create(
            target,
            targetConfig.loadBalancingConfig,
            targetConfig.keyReplicationConfig,
            availableResourceCount,
            totalAdjustedLoad
          )

        val assignment =
          new MutableAssignment(
            instant,
            baseAssignmentSliceMap,
            resources,
            adjustedLoadMap,
            config.churnConfig
          )
        AlgorithmExecutor.run(config, resources, assignment)
        validateAssignment(config, resources, assignment)
        assignment.toSliceAssignments
      }
    // Record the application-measured load (not the uniform reservation adjusted load, which is
    // only used internally by the algorithm for decision-making).
    sliceAssignments.map(SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR) {
      sliceWithResources: SliceWithResources =>
        ProposedSliceAssignment(
          sliceWithResources,
          Some(loadMap.getLoad(sliceWithResources.slice))
        )
    }
  }

  /**
   * Generates a homomorphic assignment for the given target and predecessor assignment to
   * prevent the spreading of bad keys across resources. A homomorphic assignment maintains the
   * topological structure of its predecessor, guaranteeing that slices that did not share a
   * resource in the predecessor must not share resources in the new assignment. If too few
   * resources are available, some keys may be temporarily blackholed to maintain the predecessor's
   * topology. No keys will be reassigned for load balancing or replication purposes, and the
   * homomorphic assignment generation should be reserved solely for key of death scenarios. Note
   * that the load tracked by the predecessor assignment is propagated to the new assignment.
   *
   * @param targetForDebug the target for which the assignment is being generated, used for
   *                       debugging only.
   * @param targetConfig the configuration parameters specific to the target
   * @param resources the current set of healthy pods.
   * @param baseAssignmentSliceMap the slice map of the predecessor for the new assignment.
   */
  def generateHomomorphicAssignment(
      targetForDebug: Target,
      resources: Resources,
      baseAssignmentSliceMap: SliceMap[SliceAssignment]): SliceMap[ProposedSliceAssignment] = {
    // Generate a new homomorphic assignment, taking into account any resource health changes.
    HomomorphicAssignmentAlgorithm.run(
      targetForDebug,
      resources,
      baseAssignmentSliceMap
    )
  }

  /**
   * Calculate the adjusted load map based on the uniform load reservation, the available
   * resource count, and the current load map.
   *
   * @param loadBalancingConfig The load balancing configuration.
   * @param availableResourceCount The number of available resources.
   * @param loadMap The current load map.
   */
  private[assigner] def computeAdjustedLoadMap(
      loadBalancingConfig: LoadBalancingConfig,
      availableResourceCount: Int,
      loadMap: LoadMap): LoadMap = {
    val uniformReservedLoad: Double =
      loadBalancingConfig.primaryRateMetric.getUniformReservedLoad(
        availableResourceCount
      )
    loadMap.withAddedUniformLoad(uniformReservedLoad)
  }

  /**
   * Configuration of the algorithm. Defines the thresholds used in the policy and migration phases,
   * and churn penalties.
   *
   * @param target Identifies the sharded service, for logging purposes.
   * @param churnConfig Configuration determining the penalty for churn, or key reassignments.
   * @param desiredLoadRange The range of desired load per resource/slice.
   * @param resourceAdjustedKeyReplicationConfig The key replication configuration, adjusted based
   *                                             on the number of available resources.
   */
  private[assigner] case class Config private (
      target: Target,
      churnConfig: ChurnConfig,
      desiredLoadRange: DesiredLoadRange,
      resourceAdjustedKeyReplicationConfig: KeyReplicationConfig)

  private[assigner] object Config {

    /**
     * REQUIRES: `numResources` must be positive.
     *
     * Creates configuration to use when load balancing is enabled.
     *
     * The `totalAdjustedLoad` is the total load across all Slices, including any adjustments made
     * based on the uniform load reservation.
     */
    def create(
        target: Target,
        loadBalancingConfig: LoadBalancingConfig,
        replicationConfig: KeyReplicationConfig,
        numResources: Int,
        totalAdjustedLoad: Double): Config = {
      val desiredLoadRange: DesiredLoadRange =
        calculateDesiredLoadRange(loadBalancingConfig, numResources, totalAdjustedLoad)
      // Adjust the replication bounds to account for the number of resources.
      // TODO(<internal bug>): Fires an alert when available resources are less than minReplicas.
      val resourceAdjustedKeyReplicationConfig: KeyReplicationConfig =
        KeyReplicationConfig(
          minReplicas = replicationConfig.minReplicas.min(numResources),
          maxReplicas = replicationConfig.maxReplicas.min(numResources)
        )
      Config(
        target,
        loadBalancingConfig.churnConfig,
        desiredLoadRange,
        resourceAdjustedKeyReplicationConfig
      )
    }
  }

  /**
   * REQUIRES: `splitThreshold` is positive.
   * REQUIRES: `0 < minDesiredLoadExistingResource <= maxDesiredLoad`.
   * REQUIRES: `0 < minDesiredLoadNewResource <= maxDesiredLoad`.
   *
   * A range of desired load per resource/slice replica. Used in the configuration of the algorithm.
   *
   * @param splitThreshold                 The per-replica load threshold above which a Slice must
   *                                       be split.
   * @param minDesiredLoadExistingResource The minimum desired load per existing resource (assigned
   *                                       in the predecessor assignment), inclusive. Load balancing
   *                                       continues until all existing resources have load greater
   *                                       than this value, or until no other improvements are
   *                                       possible.
   * @param minDesiredLoadNewResource      Similar to [[minDesiredLoadExistingResource]] but for new
   *                                       resources (that were not assigned in the predecessor
   *                                       assignment).
   * @param maxDesiredLoad                 The maximum desired load per resource. Load balancing
   *                                       continues until all resources have load less than or
   *                                       equal to this value, or until no other improvements are
   *                                       possible.
   */
  private[assigner] case class DesiredLoadRange private (
      splitThreshold: Double,
      minDesiredLoadExistingResource: Double,
      minDesiredLoadNewResource: Double,
      maxDesiredLoad: Double) {
    require(splitThreshold > 0.0, s"splitThreshold must be positive: $splitThreshold")
    require(
      minDesiredLoadExistingResource > 0.0,
      s"minDesiredLoadExistingResource must be positive: $minDesiredLoadExistingResource"
    )
    require(
      minDesiredLoadNewResource > 0.0,
      s"minDesiredLoadNewResource must be positive: $minDesiredLoadNewResource"
    )
    require(
      maxDesiredLoad >= minDesiredLoadExistingResource,
      s"maxDesiredLoad must be greater than or equal to minDesiredLoadExistingResource: " +
      s"maxDesiredLoad=$maxDesiredLoad, minDesiredLoad=$minDesiredLoadExistingResource"
    )
    require(
      maxDesiredLoad >= minDesiredLoadNewResource,
      s"maxDesiredLoad must be greater than or equal to minDesiredLoadNewResource: " +
      s"maxDesiredLoad=$maxDesiredLoad, minDesiredLoad=$minDesiredLoadNewResource"
    )
  }

  /**
   * REQUIRES: `numResources` must be positive.
   *
   * Calculates the desired load range per resource based on the average load, adjusted for the
   * load reservation, the maximum load hint, and the imbalance tolerance ratio.
   *
   * @param loadBalancingConfig The load balancing configuration. Contains the imbalance tolerance
   *                            ratio and the maximum load hint.
   * @param numResources The number of resources available.
   * @param totalAdjustedLoad The total load across all Slices (adjusted based on the uniform load
   *                          reservation).
   */
  private[assigner] def calculateDesiredLoadRange(
      loadBalancingConfig: LoadBalancingConfig,
      numResources: Int,
      totalAdjustedLoad: Double): DesiredLoadRange = {
    require(numResources > 0, s"numResources must be positive, but was: $numResources")
    // In case a customer has grossly underestimated their usage, use an adjusted value for the
    // `maxLoadHint` to ensure that we don't end up with a too-small absolute imbalance tolerance
    // (since the imbalance tolerance is proportional to the `maxLoadHint`).
    val averagePerResourceLoad: Double = totalAdjustedLoad / numResources
    val adjustedMaxLoadHint: Double =
      loadBalancingConfig.primaryRateMetric.maxLoadHint.max(averagePerResourceLoad)

    // Ensure a positive imbalance tolerance, needed to satisfy the
    // `minDesiredLoad < maxDesiredLoad` requirement for the Config constructor.
    val imbalanceTolerance: Double =
      (loadBalancingConfig.primaryRateMetric.imbalanceToleranceRatio * adjustedMaxLoadHint)
        .max(Double.MinPositiveValue)

    // When LB is enabled, we want to attempt to split Slices whenever their per-replica load
    // accounts for more than the absolute imbalance tolerance.
    val splitThreshold = imbalanceTolerance

    // Determine minimum and maximum desired load per resource, based on the imbalance tolerance.
    // We want no resource to be above the average load by more than the imbalance tolerance. In
    // addition, we want each existing resource (that was assigned in the predecessor assignment)
    // to have more than the average load minus the imbalance tolerance, or more than zero load
    // when the average load is less than the imbalance tolerance. For new resources, we load
    // balance more aggressively and require that each new resource has more than the average
    // load. When a new resource is added to the assignment, we want it to pick up a fair share of
    // the load: this ensures that after a rolling restart of the service (when churn is
    // inevitable), or when upsizing a service (likely in response to increasing load or even
    // overload), load is spread evenly across the new resources. Otherwise, we want to avoid
    // churn when load balancing is already good enough, to avoid overreacting to small
    // fluctuations in load when the service is not under pressure and there is no "forced" churn
    // due to membership changes.
    val minDesiredLoadExistingResource: Double =
      (averagePerResourceLoad - imbalanceTolerance).max(Double.MinPositiveValue)
    val minDesiredLoadNewResource: Double = averagePerResourceLoad.max(Double.MinPositiveValue)
    val maxDesiredLoad: Double = (averagePerResourceLoad + imbalanceTolerance)
      .max(minDesiredLoadExistingResource)
      .max(minDesiredLoadNewResource)

    DesiredLoadRange(
      splitThreshold = splitThreshold,
      minDesiredLoadExistingResource = minDesiredLoadExistingResource,
      minDesiredLoadNewResource = minDesiredLoadNewResource,
      maxDesiredLoad = maxDesiredLoad
    )
  }

  /**
   * Validate various properties about [[assignment]], assuming it corresponds to an assignment for
   * [[resources]]. Logs if any of the expected properties are violated and reports an DEGRADED
   * severity error so that we are alerted of the condition and can investigate.
   */
  private def validateAssignment(
      config: Config,
      resources: Resources,
      assignment: MutableAssignment): Unit = {
    val logger = PrefixLogger.create(this.getClass, config.target.getLoggerPrefix)
    val numResources: Int = resources.availableResources.size
    val numSliceReplicas: Int = assignment.currentNumTotalSliceReplicas
    logger.expect(
      numSliceReplicas >= numResources * MIN_AVG_SLICE_REPLICAS,
      CachingErrorCode.ASSIGNER_TOO_FEW_SLICES,
      // $COVERAGE-OFF$: Unreachable because the MIN_AVG_SLICE_REPLICAS should be always satisfied.
      s"Number of slice replicas $numSliceReplicas is too small for $numResources resources"
      // $COVERAGE-ON$
    )
    logger.expect(
      numSliceReplicas <= numResources * MAX_AVG_SLICE_REPLICAS,
      CachingErrorCode.ASSIGNER_TOO_MANY_SLICES,
      // $COVERAGE-OFF$: Unreachable because the MAX_AVG_SLICE_REPLICAS should be always satisfied.
      s"Number of slice replicas $numSliceReplicas is too large for $numResources resources"
      // $COVERAGE-ON$
    )
  }
  object forTest {
    val MIN_AVG_SLICE_REPLICAS: Int = Algorithm.MIN_AVG_SLICE_REPLICAS
    val MAX_AVG_SLICE_REPLICAS: Int = Algorithm.MAX_AVG_SLICE_REPLICAS
  }
}

/** Implementation of the algorithm for generating an initial assignment for a target. */
private[assigner] object InitialAssignmentAlgorithm {

  /**
   * PRECONDITION: `resources.availableResources` is not empty
   *
   * Generates an initial assignment for the given resources. As we don't have any load
   * information, the slices will be equally sized and each resource will be given a (roughly)
   * equal number of them, and each slice will have minimum possible number of slice replicas.
   */
  def generateInitialAssignment(
      target: Target,
      resources: Resources,
      keyReplicationConfig: KeyReplicationConfig): SliceMap[ProposedSliceAssignment] = {
    require(resources.availableResources.nonEmpty)
    val logger = PrefixLogger.create(this.getClass, target.getLoggerPrefix)
    logger.info(s"Generating initial assignment for $resources")

    // High level algorithm:
    // 1. Choose the minimum number of replicas for each slice, which is the configured minReplicas
    //    clamped by the number of available resources.
    // 2. Pick the number of *base slices* `N`, a power of 2 such that the assignment will have a
    //    **slice replica** number between `MIN_AVG_SLICE_REPLICAS * numResources` and
    //    `MAX_AVG_SLICE_REPLICAS * numResources`.
    // 3. Divide up the keyspace (keys from 0 to 1 << 32) into `N` equal sections (the fact that `N`
    //    is a power of 2 makes the equal division possible). This means each split will be
    //    represented by a key that is less than 32 bits.
    // 4. Iterate through these sections and create slices between each adjacent one.
    // 5. Assign replicas for each slice to resources in a round-robin manner (e.g. if min replicas
    //    is 2, then the first slice will be assigned pod-0 and pod-1, the second slice will be
    //    assigned pod-2 and pod-3, etc.).

    val adjustedMinReplicas: Int =
      keyReplicationConfig.minReplicas.min(resources.availableResources.size)

    // We want at least `numResources * MIN_AVG_SLICE_REPLICAS` slice replicas. Given each slice has
    // fixed `adjustedMinReplicas` replicas, the number of base slices should be at least
    // `⌈numResources * MIN_AVG_SLICE_REPLICAS / adjustedMinReplicas⌉` - then round up to the next
    // power of 2. This is just
    // `2 ^ ceil(log2(ceil(numResources * MIN_AVG_SLICE_REPLICAS / minReplicas)))`.
    //
    // For example:
    // - For 7 resources and minReplicas == 1, we want at least 7 * 32 = 224 slices replicas, and
    //   also 224 base slices, which we round up to the nearest power of two, 256.
    // - For 7 resources and minReplicas == 3, we want at least 7 * 32 = 224 slices replicas, which
    //   means at least ⌈224/3⌉ == 75 base slices, and then we round it up to the nearest power of
    //   two, 128. This yields a total of 128 * 3 = 384 slice replicas.
    //
    // Note that when the base slice count is rounded up to the **closest** power of 2, we can
    // ensure the total number of slice replicas does not exceed
    // `MAX_AVG_SLICE_REPLICAS * numResources`. Proof:
    //
    // totalSliceReplicas
    //   = sliceCount * minReplicas
    //   = 2 ^ ⌈log2(⌈numResources * MIN_AVG_SLICE_REPLICAS / minReplicas⌉)⌉ * minReplicas
    //
    // Notice that for any x > 0, ⌈log2(⌈x⌉)⌉ = ⌈log2(x)⌉ (rounding x up to an integer cannot push
    // it past the next power of two), we have
    //
    // totalSliceReplicas
    //   = 2 ^ ⌈log2(⌈numResources * MIN_AVG_SLICE_REPLICAS / minReplicas⌉)⌉ * minReplicas
    //   = 2 ^ ⌈log2(numResources * MIN_AVG_SLICE_REPLICAS / minReplicas)⌉ * minReplicas
    //  <= 2 ^ (log2(numResources * MIN_AVG_SLICE_REPLICAS / minReplicas) + 1) * minReplicas
    //   = (numResources * MIN_AVG_SLICE_REPLICAS / minReplicas * 2) * minReplicas
    //   = (numResources * minReplicas / minReplicas) * (MIN_AVG_SLICE_REPLICAS * 2)
    //   = numResources * MAX_AVG_SLICE_REPLICAS
    val minSliceReplicaCount
        : Int = resources.availableResources.size * Algorithm.MIN_AVG_SLICE_REPLICAS
    val minSliceCount: Int = Math.ceil(minSliceReplicaCount.toDouble / adjustedMinReplicas).toInt

    // We use a trick to round up to the nearest power of two: we take the `minSliceCount`,
    // subtract 1 (to deal with the case where `minSliceReplicaCount` is already a power of two),
    // find the highest bit, and then shift it by one. E.g.
    //
    // minSliceCount        0b01110000 (224)
    // minSliceCount - 1    0b01101111 (223)
    // highestOneBit        0b01000000 (128)
    //      128 << 1        0b10000000 (256)
    //
    // minSliceCount        0b10000000 (256)
    // minSliceCount - 1    0b01111111 (255)
    // highestOneBit        0b01000000 (128)
    //      128 << 1        0b10000000 (256)
    val sliceCount: Int = Integer.highestOneBit(minSliceCount - 1) << 1

    // We create `sliceCount` Slices with uniformly spaced boundaries.
    val slices: Vector[Slice] = LoadMap.getUniformPartitioning(sliceCount)

    // Assign slices to resources round-robin. If the number of resources is less than the
    // configured min replicas, then adjust the min replicas down to the number of available
    // resources.
    val sliceAssignments = Vector.newBuilder[ProposedSliceAssignment]
    // The ordering of elements in a Set (resources.availableResources) is not guaranteed to be
    // stable across binaries or even across repeated iterations in the same binary (which we do
    // below), so we first create a deterministic order by converting to a Vector and sorting.
    val sortedResources: Vector[Squid] = resources.availableResources.toVector.sorted
    var it: Iterator[Squid] = sortedResources.iterator
    for (slice: Slice <- slices) {
      val resourcesBuilder = mutable.Set[Squid]()
      // Take the next `adjustedMinReplicas` resources and assign them each a replica of the slice,
      // resetting the iterator if necessary if we hit the end. Note the loop is guaranteed to
      // terminate because `sortedResources` contains no less than `adjustedMinReplicas` unique
      // resources.
      while (resourcesBuilder.size < adjustedMinReplicas) {
        if (!it.hasNext) {
          it = sortedResources.iterator
        }
        val squid: Squid = it.next()
        resourcesBuilder += squid
      }
      // Since the initial assignment is produced without any load information, we set
      // `primaryRateLoadOpt` to None.
      sliceAssignments += ProposedSliceAssignment(
        slice,
        resourcesBuilder.toSet,
        primaryRateLoadOpt = None
      )
    }
    new SliceMap(sliceAssignments.result(), (_: ProposedSliceAssignment).slice)
  }
}

/** The class that generates a homomorphic assignment in the case of key of death scenarios. */
private[assigner] object HomomorphicAssignmentAlgorithm {

  /**
   * Generates the homomorphic assignment, as described in
   * `Algorithm.generateHomomorphicAssignment()`.
   */
  def run(
      targetForDebug: Target,
      resources: Resources,
      baseAssignmentSliceMap: SliceMap[SliceAssignment]): SliceMap[ProposedSliceAssignment] = {

    val logger = PrefixLogger.create(this.getClass, targetForDebug.getLoggerPrefix)
    logger.info(s"Generating homomorphic assignment for ${resources.availableResources}")

    // If the available resources are a subset of the base assignment's resources, return the same
    // assignment as the predecessor. We do an early return here to avoid the overhead of running
    // the main homomorphic assignment algorithm body.
    val baseAssignmentResources: Set[Squid] = baseAssignmentSliceMap.entries.flatMap {
      sliceAssignment: SliceAssignment =>
        sliceAssignment.sliceWithResources.resources
    }.toSet
    if (resources.availableResources.subsetOf(baseAssignmentResources)) {
      val proposedSliceAssignments: SliceMap[ProposedSliceAssignment] =
        baseAssignmentSliceMap.map(SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR) {
          sliceAssignment: SliceAssignment =>
            ProposedSliceAssignment(
              sliceAssignment.sliceWithResources,
              sliceAssignment.primaryRateLoadOpt
            )
        }
      return proposedSliceAssignments
    }

    // High level algorithm:
    // 1. Identify all unhealthy resources in the predecessor assignment, and organize them from
    //    hottest (most load-bearing) to coldest.
    // 2. For each unhealthy resource, assign all slice replicas on it to a new, healthy resource
    //    not previously present in the old assignment. The hottest resources are prioritized for
    //    replacement to minimize the traffic disruption during key of death scenarios, especially
    //    when there are not enough healthy resources to replace all unhealthy ones in the
    //    predecessor.
    //    a. If there are fewer new, healthy resources than unhealthy resources, some keys may be
    //       temporarily blackholed to their existing unhealthy resource to maintain the
    //       predecessor's topology.
    //    b. If new, healthy resources with nothing assigned are still available, they will be
    //       ignored.
    // 3. Continuously healthy resources -- resources in both the predecessor assignment and the
    //    set of resources provided -- will maintain the exact same slice replica set as in the
    //    predecessor, remaining unchanged.
    //
    // These steps guarantee that the key of death will never appear on more resources than it did
    // in the predecessor assignment, as slices are never merged or replicated. Furthermore, slice
    // assignment boundaries are guaranteed to be the same as the predecessor.

    // Calculate the load per slice replica based on the predecessor assignment's tracked load and
    // the number of resources assigned to each slice.
    val loadPerSliceReplica: Map[Slice, Double] = baseAssignmentSliceMap.entries.map {
      sliceAssignment: SliceAssignment =>
        val sliceWithResources: SliceWithResources = sliceAssignment.sliceWithResources
        val slice: Slice = sliceWithResources.slice
        val numResources: Int = sliceWithResources.resources.size
        val primaryRateLoad: Double = sliceAssignment.primaryRateLoadOpt.getOrElse(0.0)
        // SliceWithResources requires that the resources be non-empty, so we will not encounter
        // any divide-by-zero errors here.
        (slice, primaryRateLoad / numResources.toDouble)
    }.toMap

    // Keep track of the slice replicas assigned to each resource, which will be modified
    // when slice replicas are reassigned from unhealthy to healthy resources.
    val sliceReplicasPerResource = mutable.Map[Squid, Set[Slice]]().withDefaultValue(Set.empty)
    for (sliceAssignment: SliceAssignment <- baseAssignmentSliceMap.entries) {
      val sliceWithResources: SliceWithResources = sliceAssignment.sliceWithResources
      val slice: Slice = sliceWithResources.slice
      for (resource: Squid <- sliceWithResources.resources) {
        sliceReplicasPerResource(resource) += slice
      }
    }

    // Identify all resources in the base assignment.
    val baseResources: Set[Squid] = baseAssignmentSliceMap.entries.flatMap {
      sliceAssignment: SliceAssignment =>
        sliceAssignment.sliceWithResources.resources
    }.toSet

    // Add all unhealthy resources to a priority queue, sorted by load in descending order.
    val sortedUnhealthyResources: mutable.PriorityQueue[ResourceWithLoad] =
      mutable.PriorityQueue.empty
    for (oldResource: Squid <- baseResources) {
      if (!resources.availableResources.contains(oldResource)) {
        val resourceLoad: Double = sliceReplicasPerResource(oldResource).toSeq.map { slice: Slice =>
          loadPerSliceReplica(slice)
        }.sum
        sortedUnhealthyResources.enqueue(
          ResourceWithLoad(
            oldResource,
            resourceLoad
          )
        )
      }
    }

    // Find all new, healthy resources that are not present in the predecessor assignment.
    val unusedHealthyResources: Vector[Squid] = resources.availableResources.toVector
      .filter { resource: Squid =>
        !baseResources.contains(resource)
      }

    // In order from hottest to coldest, replace each of the unhealthy resources with the new,
    // healthy resources.
    val resourceReplacements: Vector[(ResourceWithLoad, Squid)] =
      sortedUnhealthyResources.toVector
        .zip(unusedHealthyResources)
    for (resourceReplacement: (ResourceWithLoad, Squid) <- resourceReplacements) {
      val (oldResourceWithLoad, newResource): (ResourceWithLoad, Squid) = resourceReplacement
      val oldResource: Squid = oldResourceWithLoad.squid

      // Update the resource-to-slice replica mappings.
      sliceReplicasPerResource(newResource) = sliceReplicasPerResource(oldResource)
      sliceReplicasPerResource.remove(oldResource)
    }

    // Convert the slice replicas to resource mappings into a SliceMap.
    val resourcesPerSlice = mutable.Map[Slice, Set[Squid]]().withDefaultValue(Set.empty)
    for (resource: Squid <- sliceReplicasPerResource.keys) {
      val sliceReplicas: Set[Slice] = sliceReplicasPerResource(resource)
      for (slice: Slice <- sliceReplicas) {
        resourcesPerSlice(slice) += resource
      }
    }

    val slicesWithResources: Vector[SliceWithResources] = resourcesPerSlice
      .map {
        case (slice: Slice, resources: Set[Squid]) =>
          SliceWithResources(slice, resources)
      }
      .toVector
      .sortBy(SliceMapHelper.SLICE_WITH_RESOURCES_ACCESSOR)
    val homomorphicAssignmentSliceMap: SliceMap[SliceWithResources] =
      new SliceMap(slicesWithResources, SliceMapHelper.SLICE_WITH_RESOURCES_ACCESSOR)

    // Validate the resulting slice map.
    validateHomomorphicAssignment(
      targetForDebug,
      baseAssignmentSliceMap,
      homomorphicAssignmentSliceMap
    )

    // Convert the slices with resources to proposed slice assignments.
    // Create proposed assignment, attributing the load measured by the application to each Slice in
    // the proposal.
    val proposedSliceAssignments: SliceMap[ProposedSliceAssignment] =
      homomorphicAssignmentSliceMap.map(SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR) {
        sliceWithResources: SliceWithResources =>
          ProposedSliceAssignment(
            sliceWithResources,
            // Slice assignments are guaranteed to be the same in homomorphic assignments as the
            // predecessor, so we can look up the primary rate load for each corresponding slice.
            baseAssignmentSliceMap.lookUp(sliceWithResources.slice.lowInclusive).primaryRateLoadOpt
          )
      }

    proposedSliceAssignments
  }

  /**
   * Verifies the properties of a homomorphic assignment, including:
   * - The homomorphic assignment must have the same slice boundaries as the base assignment.
   * - The homomorphic assignment must have the same replica groups per resource as the base
   *   assignment, though the underlying resource may be different.
   *
   * Logs if any of the expected properties are violated and reports a DEGRADED severity error
   * so that we are alerted of the condition and can investigate.
   *
   * @param baseAssignmentSliceMap the slice map of the base assignment.
   * @param homomorphicAssignmentSliceMap the slice map of the homomorphic assignment, as created
   *                                      by [[generateHomomorphicAssignment()]].
   */
  private def validateHomomorphicAssignment(
      targetForDebug: Target,
      baseAssignmentSliceMap: SliceMap[SliceAssignment],
      homomorphicAssignmentSliceMap: SliceMap[SliceWithResources]
  ): Unit = {
    val logger = PrefixLogger.create(this.getClass, targetForDebug.getLoggerPrefix)

    // Verify that slice boundaries are the same.
    val baseAssignmentEntries: Vector[SliceAssignment] = baseAssignmentSliceMap.entries.toVector
    val homomorphicAssignmentEntries: Vector[SliceWithResources] =
      homomorphicAssignmentSliceMap.entries
    // Though zip() takes the shorter of the two collections, we do not need to verify length
    // equality here, since SliceMap guarantees that the entries must span the entire key space. As
    // a result, if the lengths are different, the differing slice boundary entries will be caught
    // within the range of the shorter collection.
    val hasSameSliceBoundaries: Boolean = baseAssignmentEntries
      .zip(homomorphicAssignmentEntries)
      .forall { entries =>
        val (
          baseSliceAssignment,
          homomorphicSliceAssignment
        ): (SliceAssignment, SliceWithResources) = entries
        val baseSlice: Slice = SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR(baseSliceAssignment)
        val homomorphicSlice: Slice =
          SliceMapHelper.SLICE_WITH_RESOURCES_ACCESSOR(homomorphicSliceAssignment)
        val sliceBoundariesMatch: Boolean = baseSlice == homomorphicSlice

        // Log an error if the slice boundaries have changed. Note that forall() short-circuits on
        // the first false, so the logger will only catch the first difference. This is desired,
        // since the first change may render all subsequent checks false as well.
        logger.expect(
          sliceBoundariesMatch,
          CachingErrorCode.ASSIGNER_INVALID_HOMOMORPHIC_ASSIGNMENT,
          s"Slice boundaries have changed for: $baseSlice -> $homomorphicSlice."
        )

        sliceBoundariesMatch
      }

    // If slice boundaries have changed, return early. The first change affects subsequent checks,
    // and we no longer need to check replica groupings since boundary changes affect them.
    if (!hasSameSliceBoundaries) {
      return
    }

    // Verify that the replica groupings are the same.
    //
    // For both the base and homomorphic assignments, we group the slice replicas by the resources
    // they are assigned to. We ignore the actual underlying resource by converting this grouping
    // into a multi-set by recording the number of times each group appears.
    val baseReplicasPerResource: mutable.Map[Squid, Set[Slice]] =
      mutable.Map.empty.withDefaultValue(Set.empty)
    for (sliceAssignment: SliceAssignment <- baseAssignmentSliceMap.entries) {
      val slice: Slice = SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR(sliceAssignment)
      for (resource: Squid <- sliceAssignment.resources) {
        baseReplicasPerResource(resource) += slice
      }
    }
    val baseReplicaGroups: Vector[Set[Slice]] = baseReplicasPerResource.values.toVector
    val baseReplicaGroupCounts: Map[Set[Slice], Int] = baseReplicaGroups
      .groupBy(identity)
      .mapValues(
        (group: Vector[Set[Slice]]) => group.size
      )
      .toMap

    // Create the replica group multi-set for the homomorphic assignment.
    val homomorphicReplicasPerResource: mutable.Map[Squid, Set[Slice]] =
      mutable.Map.empty.withDefaultValue(Set.empty)
    for (sliceWithResources: SliceWithResources <- homomorphicAssignmentSliceMap.entries) {
      val slice: Slice = SliceMapHelper.SLICE_WITH_RESOURCES_ACCESSOR(sliceWithResources)
      for (resource: Squid <- sliceWithResources.resources) {
        homomorphicReplicasPerResource(resource) += slice
      }
    }
    val homomorphicReplicaGroups: Vector[Set[Slice]] =
      homomorphicReplicasPerResource.values.toVector
    val homomorphicReplicaGroupCounts: Map[Set[Slice], Int] = homomorphicReplicaGroups
      .groupBy(identity)
      .mapValues(
        (group: Vector[Set[Slice]]) => group.size
      )
      .toMap
      .withDefaultValue(0)

    // Verify that the replica group multi-sets are the same.
    for (sliceReplicaSet: Set[Slice] <- baseReplicaGroupCounts.keys) {
      val expectedReplicaSetCount: Int = baseReplicaGroupCounts(sliceReplicaSet)
      // If the replica set is not present in the homomorphic assignment, the count will be 0.
      val actualReplicaSetCount: Int = homomorphicReplicaGroupCounts(sliceReplicaSet)
      logger.expect(
        expectedReplicaSetCount == actualReplicaSetCount,
        CachingErrorCode.ASSIGNER_INVALID_HOMOMORPHIC_ASSIGNMENT,
        s"Expected replica group $sliceReplicaSet to appear $expectedReplicaSetCount times, " +
        s"but found $actualReplicaSetCount times."
      )
    }
  }

  /**
   * This class represents a resource containing the given load, calculated using its current slice
   * replica set, so that resources may be sorted by load.
   */
  case class ResourceWithLoad(
      squid: Squid,
      load: Double
  ) extends Ordered[ResourceWithLoad] {
    override def compare(that: ResourceWithLoad): Int = {
      // Compare based on load.
      this.load.compare(that.load)
    }
  }
}
