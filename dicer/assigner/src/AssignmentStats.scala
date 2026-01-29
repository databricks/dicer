package com.databricks.dicer.assigner

import scala.collection.mutable

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap}
import com.databricks.dicer.assigner.config.InternalTargetConfig.LoadBalancingConfig
import com.databricks.dicer.common.{Assignment, SliceAssignment}
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.{SliceMap, Squid}

object AssignmentStats {

  /**
   * An immutable data structure that represents the statistics information associated with a
   * change from a previous assignment to a new one. These statistics include churn ratios and
   * whether the assignment change meaningfully alters the resources assigned to any SliceKey.
   *
   * Churn is defined as the amount of load that moved between the previous assignment and the
   * new assignment. Currently, we classify the churn ratio into three types that model the
   * percentage of load reassigned between nodes: addition churn ratio, removal churn ratio, and
   * load balancing churn ratio. These churn ratios are tracked via and visible on both Prometheus
   * metrics and ZPage.
   *
   * @param additionChurnRatio the fraction of total load moved due to adding new resources to a
   *                           Target
   * @param removalChurnRatio the fraction of total load moved due to resource deletions from a
   *                          Target
   * @param loadBalancingChurnRatio the fraction of total load moved due to load balancing.
   * @param isMeaningfulAssignmentChange whether the assignment change is meaningful, where
   *                                     meaningful signifies a change in the resources assigned
   *                                     to any SliceKey.
   */
  case class AssignmentChangeStats private (
      additionChurnRatio: Double,
      removalChurnRatio: Double,
      loadBalancingChurnRatio: Double,
      isMeaningfulAssignmentChange: Boolean
  )

  object AssignmentChangeStats {

    /**
     * Calculates the [[AssignmentChangeStats]] for an assignment change. The churn is calculated
     * using the load distribution indicated by `loadMap`, while the load possibly carried in
     * `previousAssignment` and `currentAssignment` is ignored.
     */
    def calculate(
        previousAssignment: Assignment,
        currentAssignment: Assignment,
        loadMap: LoadMap
    ): AssignmentChangeStats = {
      var removalChurnLoad: Double = 0
      var additionChurnLoad: Double = 0
      var loadBalancingChurnLoad: Double = 0
      var isMeaningfulAssignmentChange: Boolean = false
      var totalLoad: Double = 0

      val intersectedAssignments =
        SliceMap.intersectSlices(previousAssignment.sliceMap, currentAssignment.sliceMap)
      for (intersectionEntry <- intersectedAssignments.entries) {
        val intersection: Slice = intersectionEntry.slice
        val sliceTotalLoad: Double = loadMap.getLoad(intersection)
        totalLoad += sliceTotalLoad
        val previous: SliceAssignment = intersectionEntry.leftEntry
        val current: SliceAssignment = intersectionEntry.rightEntry
        // Note: the following are the load carried by the `loadMap`, but spread out between
        // previous / current number of replicas.
        val previousPerSliceReplicaLoad: Double = sliceTotalLoad / previous.resources.size
        val currentPerSliceReplicaLoad: Double = sliceTotalLoad / current.resources.size

        // Compute and classify churn for this slice.
        //
        // The total churn for this slice will be computed by summing up the amount of
        // load that didn't move and taking the difference with the total load on the slice.
        //
        // The total churn is classified into three types:
        // * Removal churn: Load that had to move because a resource was removed.
        // * Addition churn: Load that moved to "load up" a new resource.
        // * Load balancing churn: Load that moved between resources due to load balancing.
        //
        // Classification can be tricky because for a given unit of new load showing up on a
        // resource, it's sometimes up to us to make a call about how to classify it.
        //
        // For example, consider if a slice was assigned to r1 and r2 in the old assignment (but
        // not assigned to r3), each with load 50, and r1 was removed in the new assignment, and
        // the slice was assigned to r2, r3, and r4 in the new assignment (where only r4 is a new
        // resource):
        //
        // old asn: r1: 50, r2: 50, r3: 0
        // new asn:         r2: 33, r3: 33, r4: 33
        //
        // In this example, 33 units of load didn't move (on r2), yielding a total churn of
        // 100 - 33 = 67 which we can classify in different ways according to different approaches:
        //
        // * Approach 1: Account as much as possible to removal (r1), then the rest to addition
        //   (r4):
        //   * Removal churn: 50, addition churn: 17, load balancing churn: 00
        // * Approach 2: Account as much as possible to addition (r4), then the rest to removal
        //   (r1):
        //   * Removal churn: 34, addition churn: 33, load balancing churn: 00
        // * Approach 3: Consider 17 units to be load balancing from r2 to r3, then account the
        //   rest to removal (r1):
        //   * Removal churn: 50, addition churn: 00, load balancing churn: 17
        // * Approach 4: Consider 17 units to be load balancing from r2 to r3, then to addition
        //   (r4), and then the rest to removal (r1):
        //   * Removal churn: 17, addition churn: 33, load balancing churn: 17
        //
        // So, to eliminate ambiguity and make the definitions of removal, addition, and load
        // balancing churn well defined, we follow this basic rule when classifying churn:
        //
        // ** Prefer to attribute as much load as possible to removal churn, then addition churn,
        // ** and finally load balancing churn.
        //
        // The reasoning for this is because removal churn is churn that we absolutely cannot
        // avoid. By attributing as much churn as we can to removal churn, we're accurately
        // reflecting in this metric the amount of churn that was strictly unavoidable by Dicer.
        // We then attribute as much as possible to addition churn, since when there is a new
        // resource, the primary reason for moving the load is to fill up the new resource, and
        // not load balancing. Finally, load balancing churn is all the load we moved that wasn't
        // because we had to (removal) and wasn't because we were filling up a new pod (addition).

        // Recording the total load that was removed from removed resources (i.e. all the load that
        // had been assigned to resources that are no longer in the current *assignment*).
        var sliceLoadOnRemovedResources: Double = 0
        // Recording the total load on newly added resources (i.e. all the load that is currently
        // assigned to resources that were not in the previous *assignment*).
        var sliceLoadOnAddedResources: Double = 0
        // Recording the amount of load that didn't move at all between the previous and current
        // assignment.
        var sliceLoadNotMoved: Double = 0

        for (previousResource: Squid <- previous.resources) {
          if (!currentAssignment.assignedResources.contains(previousResource)) {
            sliceLoadOnRemovedResources += previousPerSliceReplicaLoad
          }
        }
        for (currentResource: Squid <- current.resources) {
          if (!previousAssignment.assignedResources.contains(currentResource)) {
            sliceLoadOnAddedResources += currentPerSliceReplicaLoad
          } else if (previous.resources.contains(currentResource)) {
            // Note that even if a resource is assigned the Slice in both previous and current
            // assignments, only the common part of previous load and current load on the resource
            // is considered as "not moved".
            sliceLoadNotMoved += previousPerSliceReplicaLoad.min(currentPerSliceReplicaLoad)
          }
        }

        // The total churn for the current Slice. We will classify it into 3 churn types using the
        // strategy in the discussion above. Based on how `sliceTotalLoad` and
        // `sliceLoadNotMoved` are calculated, the result should always be non-negative
        // mathematically. However, we still max the diff with 0.0 so that the result won't be
        // negative because of floating point errors.
        var sliceChurnLoadBudget: Double = (sliceTotalLoad - sliceLoadNotMoved).max(0.0)

        // First prefer to attribute churn budget to removal churn as much as possible.
        val sliceRemovalChurnLoad: Double = sliceLoadOnRemovedResources.min(sliceChurnLoadBudget)
        removalChurnLoad += sliceRemovalChurnLoad
        sliceChurnLoadBudget -= sliceRemovalChurnLoad

        // Then attribute churn budget to addition churn.
        val sliceAdditionChurnLoad: Double = sliceLoadOnAddedResources.min(sliceChurnLoadBudget)
        additionChurnLoad += sliceAdditionChurnLoad
        sliceChurnLoadBudget -= sliceAdditionChurnLoad

        // The remaining churn budget is attributed to load balancing.
        val sliceLoadBalancingChurnLoad: Double = sliceChurnLoadBudget
        loadBalancingChurnLoad += sliceLoadBalancingChurnLoad

        // Note: We need to explicitly check if there was a SliceMap change instead of checking if
        // churn > 0 because there could be a SliceMap changes for slices with no load, but we
        // should still consider this a meaningful assignment change.
        isMeaningfulAssignmentChange ||= (previous.resources != current.resources)
      }

      // Avoid division by zero errors.
      if (totalLoad == 0.0) {
        // When the total load is 0, the churn load must also be 0. Despite that the churn ratios
        // (0.0 / 0.0) are mathematically undefined, we stipulate the churn ratios to be 0.0 to
        // indicate that this assignment change introduces no churn to the Dicer-sharded workload.
        AssignmentChangeStats(
          additionChurnRatio = 0.0,
          removalChurnRatio = 0.0,
          loadBalancingChurnRatio = 0.0,
          isMeaningfulAssignmentChange
        )
      } else {
        AssignmentChangeStats(
          additionChurnRatio = additionChurnLoad / totalLoad,
          removalChurnRatio = removalChurnLoad / totalLoad,
          loadBalancingChurnRatio = loadBalancingChurnLoad / totalLoad,
          isMeaningfulAssignmentChange
        )
      }
    }
  }

  /**
   * An immutable data structure that represents the load statistics for an assignment.
   * The "load" values for this class can represent various meanings, e.g. the raw load
   * aggregated by LoadWatcher or tracked in an assignment, the reservation-adjusted load used
   * by the Algorithm, or load distribution assuming a certain hypothetical assignment. See the
   * factory methods in its companion object for more details.
   *
   * @param loadByResource the load on each resource in the assignment
   * @param loadBySlice the load on each slice in the assignment
   * @param numOfAssignedSlicesByResource the number of assigned slices per resource in the
   *                                      assignment.
   */
  case class AssignmentLoadStats private (
      loadByResource: Map[Squid, Double],
      loadBySlice: Map[Slice, Double],
      numOfAssignedSlicesByResource: Map[Squid, Int]
  )

  object AssignmentLoadStats {

    /**
     * Calculates the [[AssignmentLoadStats]] according to the `assignment` and the load
     * distribution in the `loadMap`. The load tracked by the `assignment` itself is ignored.
     */
    def calculate(
        assignment: Assignment,
        loadMap: LoadMap
    ): AssignmentLoadStats = {
      val loadByResource = mutable.Map[Squid, Double]().withDefaultValue(0.0)
      val loadBySlice = mutable.Map[Slice, Double]().withDefaultValue(0.0)
      val numOfAssignedSlicesByResource = mutable.Map[Squid, Int]().withDefaultValue(0)

      for (sliceAssignment: SliceAssignment <- assignment.sliceAssignments) {
        val slice: Slice = sliceAssignment.slice

        // Even though the assignment corresponding to the `loadMap` may not exactly match the
        // `assignment` passed in, we can still get the load on each slice accurately since
        // `loadMap` will handle load apportioning internally.
        val sliceLoad: Double = loadMap.getLoad(slice)
        loadBySlice(slice) += sliceLoad

        val sliceResources: Set[Squid] = sliceAssignment.resources
        for (resource: Squid <- sliceResources) {
          loadByResource(resource) += sliceLoad / sliceResources.size
          numOfAssignedSlicesByResource(resource) += 1
        }
      }

      AssignmentLoadStats(
        loadByResource = loadByResource.toMap,
        loadBySlice = loadBySlice.toMap,
        numOfAssignedSlicesByResource = numOfAssignedSlicesByResource.toMap
      )
    }

    /**
     * Calculates the [[AssignmentLoadStats]] for an assignment from the load tracked by the
     * assignment itself. If, for a given slice, the load is not tracked, the load is assumed to be
     * 0.0.
     */
    def calculateSelfTrackedLoadStats(
        assignment: Assignment
    ): AssignmentLoadStats = {
      // Create a load map from the load tracked by the assignment itself.
      val loadMapBuilder: LoadMap.Builder = LoadMap.newBuilder()
      for (sliceAssignment: SliceAssignment <- assignment.sliceAssignments) {
        val slice: Slice = sliceAssignment.slice
        val sliceLoad: Double = sliceAssignment.primaryRateLoadOpt.getOrElse(0.0)
        loadMapBuilder.putLoad(LoadMap.Entry(slice, sliceLoad))
      }
      val loadMap: LoadMap = loadMapBuilder.build()

      calculate(assignment, loadMap)
    }

    /**
     * PRECONDITION: `resources` must be non-empty.
     *
     * Given a set of resources, calculates the load statistics for a hypothetical assignment where
     * the same number of SliceKeys are assigned to each resource. An arbitrary 1:1 mapping from
     * hypothetical, equal-size slices to the given resources is used to compute the load on each
     * resource.
     */
    def calculateUniformKeyAssignmentLoadStats(
        resources: Set[Squid],
        loadMap: LoadMap
    ): AssignmentLoadStats = {
      // Validate the precondition.
      iassert(
        resources.nonEmpty,
        s"resources must be non-empty, but got $resources"
      )

      // Obtain the total number of resources able to be used in the new hypothetical assignment.
      val numResources: Int = resources.size
      // We simply divide up the key space into `numResources` slices of (almost) equal size, then
      // compute the load for each one.
      // Note: In theory, we don't care about the exact load on each resource but rather only the
      // load distribution/imbalance across resources, since we are calculating hypothetical data
      // anyway. However, for consistency with `calculate` and `calculateAdjustedLoadStats`, this
      // method returns an `AssignmentLoadStat`, which requires an exact mapping of resources to
      // their loads. To produce this, we create an arbitrary 1:1 mapping between the hypothetical
      // slices and resources. The `loadBySlice` and `numOfAssignedSlicesByResource` fields in
      // `AssignmentLoadStats` are also populated similarly based on these hypothetical slices and
      // mapping.
      val slices: Vector[Slice] = LoadMap.getUniformPartitioning(numResources)
      // Sort so that if the resources don't change, the assignment is stable.
      val sortedResources: Seq[Squid] = resources.toVector.sorted
      val loadByResource: Map[Squid, Double] = sortedResources
        .zip(slices)
        .map {
          case (resource: Squid, slice: Slice) => (resource, loadMap.getLoad(slice))
        }
        .toMap
      val loadBySlice: Map[Slice, Double] =
        slices.map((slice: Slice) => (slice, loadMap.getLoad(slice))).toMap

      // Since each resource is assigned to exactly one slice, the number of assigned slices per
      // resource is 1.
      val numOfAssignedSlicesByResource: Map[Squid, Int] =
        sortedResources.map((resource: Squid) => (resource, 1)).toMap

      AssignmentLoadStats(
        loadByResource,
        loadBySlice,
        numOfAssignedSlicesByResource
      )
    }

    /**
     * Calculates the load statistics for an assignment after adjusting the load map to account for
     * the uniform load reservation. Like in `calculate`, load tracked by the `assignment` itself
     * is ignored.
     *
     * @param loadBalancingConfig the load balancing configuration, which is used to adjust the load
     *                            map to account for the uniform load reservation
     */
    def calculateAdjustedLoadStats(
        assignment: Assignment,
        loadMap: LoadMap,
        loadBalancingConfig: LoadBalancingConfig
    ): AssignmentLoadStats = {
      val adjustedLoadMap: LoadMap = Algorithm.computeAdjustedLoadMap(
        loadBalancingConfig,
        assignment.assignedResources.size,
        loadMap
      )

      calculate(assignment, adjustedLoadMap)
    }

    /**
     * Calculates the load statistics for an assignment after adjusting the load tracked by the
     * assignment itself to account for the uniform load reservation. If no load is tracked for a
     * slice, the load is assumed to be uniform, rendering the load on that slice the same as the
     * slice size in proportion to the total SliceKey range.
     *
     * @param loadBalancingConfig the load balancing configuration, which is used to adjust the load
     *                            tracked by the assignment itself to account for the uniform load
     *                            reservation.
     */
    def calculateSelfTrackedAdjustedLoadStats(
        assignment: Assignment,
        loadBalancingConfig: LoadBalancingConfig
    ): AssignmentLoadStats = {
      // Create a load map from the load tracked by the assignment itself.
      val loadMapBuilder: LoadMap.Builder = LoadMap.newBuilder()
      for (sliceAssignment: SliceAssignment <- assignment.sliceAssignments) {
        val slice: Slice = sliceAssignment.slice
        val sliceSize: Double = LoadMap.UNIFORM_LOAD_MAP.getLoad(slice)
        val sliceLoad: Double = sliceAssignment.primaryRateLoadOpt.getOrElse(sliceSize)
        loadMapBuilder.putLoad(LoadMap.Entry(slice, sliceLoad))
      }
      val loadMap: LoadMap = loadMapBuilder.build()

      // [[calculateAdjustedLoadStats]] will add in the reservation-adjusted load to the load map
      // created from the assignment-tracked load.
      calculateAdjustedLoadStats(assignment, loadMap, loadBalancingConfig)
    }
  }

  /**
   * An immutable data structure that represents the churn and load statistics for a reassignment.
   * See [[AssignmentChangeStats]] for more details on churn statistics. Three types of load
   * statistics are computed: one for the previous assignment, one for the current assignment, and
   * one for a hypothetical assignment where the same number of SliceKeys are assigned to each
   * resource present in the current assignment. See [[AssignmentLoadStats]] for more details on
   * the values included in each set of load statistics.
   *
   * This class guarantees the following invariants:
   * - The churn statistics, load statistics before and after the reassignment, and the hypothetical
   *   load statistics based on an equal-size assignment must all be calculated from the same
   *   assignment change event, which is defined as having the same previous assignment, current
   *   assignment, and load map. While not explicitly verified in the private constructor, this
   *   property is enforced by the public factory methods in [[ReassignmentChurnAndLoadStats]]
   *   companion object.
   * - The resources in `loadStatsUniformKeyAssignment` must match the resources in
   *   `loadStatsAfter`. This is verified in the private constructor.
   *
   * @param churnStats the [[AssignmentChangeStats]] for the reassignment
   * @param loadStatsBefore the [[AssignmentLoadStats]] based on the previous assignment and the
   *                        load distribution at the moment of reassignment
   * @param loadStatsAfter the [[AssignmentLoadStats]] based on the current assignment and the load
   *                       distribution at the moment of reassignment
   * @param loadStatsUniformKeyAssignment the [[AssignmentLoadStats]] based on a hypothetical
   *                                 assignment using the resources of the current assignment and
   *                                 the load distribution at the moment of reassignment. See
   *                                 [[AssignmentLoadStats.calculateUniformKeyAssignmentLoadStats]]
   *                                 for more details on the hypothetical assignment.
   */
  case class ReassignmentChurnAndLoadStats private (
      churnStats: AssignmentChangeStats,
      loadStatsBefore: AssignmentLoadStats,
      loadStatsAfter: AssignmentLoadStats,
      loadStatsUniformKeyAssignment: AssignmentLoadStats
  ) {
    // Verify that the resources in `loadStatsUniformKeyAssignment` are the same as the ones in
    // `loadStatsAfter`.
    iassert(
      loadStatsUniformKeyAssignment.loadByResource.keySet == loadStatsAfter.loadByResource.keySet,
      "The resources in `loadStatsUniformKeyAssignment` and `loadStatsAfter` must match, but got " +
      loadStatsUniformKeyAssignment.loadByResource.keySet + " and " +
      loadStatsAfter.loadByResource.keySet
    )
  }

  object ReassignmentChurnAndLoadStats {

    /**
     * Calculates the [[ReassignmentChurnAndLoadStats]] upon a reassignment. Both the load tracked
     * by the `previousAssignment` and load tracked by the `currentAssignment` are ignored.
     */
    def calculate(
        previousAssignment: Assignment,
        currentAssignment: Assignment,
        loadMap: LoadMap): ReassignmentChurnAndLoadStats = {
      val churnStats = AssignmentChangeStats.calculate(
        previousAssignment,
        currentAssignment,
        loadMap
      )
      val loadStatsBefore = AssignmentLoadStats.calculate(
        previousAssignment,
        loadMap
      )
      val loadStatsAfter = AssignmentLoadStats.calculate(
        currentAssignment,
        loadMap
      )
      val loadStatsUniformKeyAssignment =
        AssignmentLoadStats.calculateUniformKeyAssignmentLoadStats(
          currentAssignment.assignedResources,
          loadMap
        )

      ReassignmentChurnAndLoadStats(
        churnStats,
        loadStatsBefore,
        loadStatsAfter,
        loadStatsUniformKeyAssignment
      )
    }
  }
}
