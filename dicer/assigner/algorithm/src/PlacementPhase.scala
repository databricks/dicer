package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable
import scala.concurrent.duration._

import com.databricks.caching.util.PrefixLogger
import com.databricks.dicer.assigner.algorithm.Algorithm.{Config, MIN_AVG_SLICE_REPLICAS}
import com.databricks.dicer.common.TargetHelper.TargetOps

/**
 * Runs the placement phase of the algorithm. It iteratively reassigns, replicates and dereplicates
 * slices to various resources while minimizing our objective function (see
 * [[PlacementPhase.calculateOperationDelta]]). Uses a greedy local search algorithm that
 * iteratively finds improving operations until no improvement is possible or a time limit is
 * reached.
 *
 * After this phase completes, the assignment is guaranteed to:
 * - Assign at least one slice to each resource.
 * - Keep the total number of slice replicas above `num_resources * MIN_AVG_SLICE_REPLICAS`. (It may
 *   increase the slice replicas above `num_resources * MAX_AVG_SLICE_REPLICAS` due to replicating a
 *   very hot slice. We allow it this flexibility because there may be no other option for cooling
 *   down a resource with a single very hot slice, and cooling an overly hot resource is more
 *   critical to avoid overload.)
 */
private[assigner] object PlacementPhase {

  /** Multiplier for the empty resource penalty. */
  val EMPTY_RESOURCE_PENALTY_MULTIPLIER: Double = 100.0

  /**
   * Maximum time allowed for the greedy local search to consider new options. In most cases it
   * should terminate earlier because no further improvements to the objective function can be made.
   */
  val PLACEMENT_TIMEOUT: FiniteDuration = 30.seconds

  /** Penalty coefficient for resources that are below the minimum desired load. */
  val UNDERSHOOT_PENALTY_COEFFICIENT: Double = 4.0

  /** Penalty coefficient for resources that are above the maximum desired load. */
  val OVERSHOOT_PENALTY_COEFFICIENT: Double = 16.0

  def place(config: Config, assignment: MutableAssignment): Unit = {
    new PlacementPhase(config, assignment).run()
  }

  private class PlacementPhase(config: Config, assignment: MutableAssignment) {

    private val logger = PrefixLogger.create(this.getClass, config.target.getLoggerPrefix)

    // Total slice replica limits.
    private val minTotalSliceReplicas: Int = MIN_AVG_SLICE_REPLICAS * assignment
        .resourceStatesIteratorFromColdest()
        .size

    /**
     * Empty resources have a very large penalty to prioritize filling them. See
     * `calculateOperationDelta` for more details.
     */
    private val emptyResourcePenalty: Double =
      config.desiredLoadRange.maxDesiredLoad * EMPTY_RESOURCE_PENALTY_MULTIPLIER

    /** Represents an operation on slice placement. */
    private sealed trait Operation

    /** Reassigns a slice replica from one resource to another. */
    private case class Reassign(
        sliceAssignment: assignment.MutableSliceAssignment,
        fromResource: assignment.ResourceState,
        toResource: assignment.ResourceState
    ) extends Operation

    /** Adds a new replica of a slice to a resource. */
    private case class Replicate(
        sliceAssignment: assignment.MutableSliceAssignment,
        toResource: assignment.ResourceState
    ) extends Operation

    /** Removes a replica of a slice from a resource. */
    private case class Dereplicate(
        sliceAssignment: assignment.MutableSliceAssignment,
        resourceToDeallocate: assignment.ResourceState
    ) extends Operation

    /** Runs the placement phase using a greedy local search algorithm. */
    def run(): Unit = {
      val startTime: Long = System.currentTimeMillis()
      val deadline: Deadline = PLACEMENT_TIMEOUT.fromNow

      // Gather all resources and slices (snapshot to avoid iterator invalidation).
      val resourceStates: Vector[assignment.ResourceState] =
        assignment.resourceStatesIteratorFromColdest().toVector

      var iterations: Int = 0

      // Iteratively improve placement by generating candidate operations (Reassign, Replicate,
      // Dereplicate) using heuristics, evaluating them, and applying the best one. When no
      // improvement is found for the current hottest resource, exclude it and try the next one
      // until no resources remain or the deadline is reached.
      while (assignment.eligibleResourcesRemain && deadline.hasTimeLeft()) {
        iterations += 1
        val hottestResource: assignment.ResourceState = assignment.hottestResourceState

        val candidateOps: Seq[Operation] =
          generateCandidateOperations(hottestResource)

        var bestOp: Option[Operation] = None
        var bestDelta: Double = 0.0 // Negative delta = improvement.

        for (op: Operation <- candidateOps) {
          val delta: Double = calculateOperationDelta(op, resourceStates)
          if (delta < bestDelta) {
            bestDelta = delta
            bestOp = Some(op)
          }
        }

        logger.trace(
          s"Iteration $iterations: ${candidateOps.size} candidates, bestDelta=$bestDelta, " +
          s"bestOp=$bestOp"
        )

        // Apply best operation found. We ensure the improvement is at least 1e-10 (note negative
        // values represent improvement) to avoid infinite loops due to floating point precision
        // errors. If no improvement found, exclude this resource and try the next one.
        if (bestOp.isDefined && bestDelta < -1e-10) {
          applyOperation(bestOp.get)
        } else {
          hottestResource.exclude()
        }
      }

      if (!deadline.hasTimeLeft()) {
        logger.warn(
          s"Placement exceeded ${PLACEMENT_TIMEOUT.toSeconds}s deadline after $iterations " +
          s"iterations, stopping"
        )
      }

      logger.trace(
        s"Placement took ${System.currentTimeMillis() - startTime}ms for ${resourceStates.size} " +
        s"resources and ${assignment.sliceAssignmentsIterator.size} slices ($iterations iterations)"
      )
    }

    /**
     * Calculates the penalty delta for an operation. Returns negative value if operation improves
     * (reduces) penalty.
     */
    private def calculateOperationDelta(
        op: Operation,
        resourceStates: Vector[assignment.ResourceState]
    ): Double = {
      // The penalty of a particular assignment is defined as:
      //   penalty(assignment, prev) =
      //     churn_penalty +
      //     undershoot_penalty +
      //     overshoot_penalty +
      //     empty_resource_penalty +
      //     replication_penalty
      // where:
      //  - churn_penalty is the penalty for slice movement, to encourage better locality. For each
      //    slice, the penalty is `churnConfig.maxPenaltyRatio * slice_per_replica_load` if the
      //    slice is newly assigned to a resource, and this is summed over all slices. See
      //    `ChurnConfig` for more details.
      //  - undershoot_penalty is for a resource being below the minimum desired load, to encourage
      //    better load balancing. It is calculated as the sum of
      //    `(1 + (min_desired_load - resource_load) / min_desired_load) ^ 2 *
      //       (min_desired_load - resource_load) * 4.0` for each resource that is below the minimum
      //    desired load. The coefficient of 4.0 is higher than the churn penalty ratio, so that we
      //    are willing to move some amount of load to avoid undershoot. The quadratic term adds a
      //    multiplier varying between 1.0 and 4.0 as the undershoot increases.
      //  - overshoot_penalty is for a resource being above the maximum desired load, to encourage
      //    better load balancing. It is calculated as the sum of
      //    `(resource_load / max_desired_load) ^ 2 * (resource_load - max_desired_load) * 16.0` for
      //    each resource that is above the maximum desired load. The coefficient of 16.0 is higher
      //    than the undershoot penalty, since overshoot can lead to server overload and is more
      //    risky. The quadratic term adds a multiplier starting from 1.0, increasing as the
      //    overshoot increases.
      //  - empty_resource_penalty is for any empty resource, so that we prioritize filling them
      //    (since this phase guarantees that every resource ends up non-empty). We set it to a very
      //    high value of `max_desired_load * 100` (for each empty resource) so that it dominates
      //    other penalties and there will be a slice that can be reassigned or replicated to the
      //    empty resource to reduce the penalty.
      //  - replication_penalty is the penalty for the additional replicas in the assignment, to
      //    encourage having fewer replicas unless needed to balance load. The cost of adding
      //    another replica is the `slice_per_replica_load` before the replication, i.e. the total
      //    penalty of a slice with `i` replicas is
      //    `replication_penalty(i) = sum_i total_slice_load / i` which is
      //    `total_slice_load * (1/1 + 1/2 + 1/3 + ... + 1/(i-1))`. Note that upreplicating also
      //    imposes churn cost in addition to the replication penalty.
      //
      // As an example, suppose that a resource is 10 load under the min desired load of 1000, and
      // the smallest slice that can be reassigned to it has load 100. Then, the churn penalty of
      // that slice is 100 * 0.25 = 25 (based on the default churn penalty ratio). But the
      // undershoot penalty will be 1.01^2 * 10 * 4 = 40.8, so the solver will prefer to reassign
      // the slice to avoid undershoot. However, if the resource was instead only 1 load under the
      // min desired load, the solver would not reassign a slice with 100 load to it.
      //
      // The coefficients for these different penalties have been set somewhat arbitrarily based on
      // what seems reasonable, and likely could use more tuning.
      //
      // When determining the change in penalty from an operation, to speed up the calculation we
      // only consider the affected resources, rather than recalculating the penalty for all
      // resources.
      op match {
        case reassign: Reassign =>
          calculateReassignDelta(reassign, resourceStates)
        case replicate: Replicate =>
          calculateReplicateDelta(replicate, resourceStates)
        case dereplicate: Dereplicate =>
          calculateDereplicateDelta(dereplicate, resourceStates)
      }
    }

    /**
     * Calculates the undershoot/overshoot penalty for a single resource, assuming it has the given
     * load and state.
     *
     * @param load The load on the resource.
     * @param isNewResource Whether this resource was newly added in this assignment cycle.
     * @param isResourceEmpty Whether this resource has no slices assigned to it.
     */
    private def resourcePenalty(
        load: Double,
        isNewResource: Boolean,
        isResourceEmpty: Boolean
    ): Double = {
      if (isResourceEmpty) {
        return emptyResourcePenalty
      }

      val minDesired: Double = if (isNewResource) {
        config.desiredLoadRange.minDesiredLoadNewResource
      } else {
        config.desiredLoadRange.minDesiredLoadExistingResource
      }
      val maxDesired: Double = config.desiredLoadRange.maxDesiredLoad

      if (load < minDesired) {
        Math.pow((1 + (minDesired - load) / minDesired), 2) * (minDesired - load) *
        UNDERSHOOT_PENALTY_COEFFICIENT
      } else if (load > maxDesired) {
        Math.pow(load / maxDesired, 2) * (load - maxDesired) * OVERSHOOT_PENALTY_COEFFICIENT
      } else {
        0.0
      }
    }

    /** Calculates the penalty delta for a Reassign operation (affects 2 resources + churn cost). */
    private def calculateReassignDelta(
        reassign: Reassign,
        resourceStates: Vector[assignment.ResourceState]
    ): Double = {
      val sliceAsn: assignment.MutableSliceAssignment = reassign.sliceAssignment
      val fromResource: assignment.ResourceState = reassign.fromResource
      val toResource: assignment.ResourceState = reassign.toResource

      // Churn cost: moving away from a base assignment location adds churn.
      val isFromInBaseAssignment: Boolean =
        !sliceAsn.isNewlyAssignedToResource(fromResource.resource)
      val churnCost: Double = if (isFromInBaseAssignment) {
        sliceAsn.rawLoadPerReplica * config.churnConfig.maxPenaltyRatio
      } else {
        0.0
      }
      val sliceLoad: Double = sliceAsn.rawLoadPerReplica

      // Old penalties for affected resources.
      val oldFromPenalty: Double = resourcePenalty(
        fromResource.getTotalLoad,
        fromResource.isNew,
        fromResource.getAssignedSlices.isEmpty
      )
      val oldToPenalty: Double = resourcePenalty(
        toResource.getTotalLoad,
        toResource.isNew,
        toResource.getAssignedSlices.isEmpty
      )

      // New loads after reassignment.
      val newFromLoad: Double = fromResource.getTotalLoad - sliceAsn.rawLoadPerReplica
      val newToLoad: Double = toResource.getTotalLoad + sliceLoad

      // New penalties for affected resources.
      val newFromPenalty: Double =
        resourcePenalty(newFromLoad, fromResource.isNew, fromResource.getAssignedSlices.size == 1)
      val newToPenalty: Double =
        resourcePenalty(newToLoad, toResource.isNew, isResourceEmpty = false)

      // Delta = (new penalties) - (old penalties) + churn cost.
      (newFromPenalty + newToPenalty) - (oldFromPenalty + oldToPenalty) + churnCost
    }

    /**
     * Calculates the penalty delta for a Replicate operation (affects all existing resources with
     * slice + new resource).
     */
    private def calculateReplicateDelta(
        replicate: Replicate,
        resourceStates: Vector[assignment.ResourceState]
    ): Double = {
      val sliceAsn: assignment.MutableSliceAssignment = replicate.sliceAssignment
      val toResource: assignment.ResourceState = replicate.toResource

      val currentPerReplicaLoad: Double = sliceAsn.rawLoadPerReplica
      val newPerReplicaLoad: Double = sliceAsn.rawLoad / (sliceAsn.currentNumReplicas + 1)
      val loadDecrease: Double = currentPerReplicaLoad - newPerReplicaLoad

      // Resource penalty delta for ALL existing resources with this slice (load decreases).
      var resourcePenaltyDelta: Double = 0.0
      for (rs: assignment.ResourceState <- sliceAsn.getAllocatedResourceStates) {
        val oldPenalty: Double =
          resourcePenalty(rs.getTotalLoad, rs.isNew, rs.getAssignedSlices.isEmpty)
        val newLoad: Double = rs.getTotalLoad - loadDecrease
        val newPenalty: Double = resourcePenalty(newLoad, rs.isNew, rs.getAssignedSlices.isEmpty)
        resourcePenaltyDelta += newPenalty - oldPenalty
      }

      // Resource penalty delta for to resource (gains new replica).
      val oldToPenalty: Double = resourcePenalty(
        toResource.getTotalLoad,
        toResource.isNew,
        toResource.getAssignedSlices.isEmpty
      )
      val newToLoad: Double = toResource.getTotalLoad + newPerReplicaLoad
      val newToPenalty: Double =
        resourcePenalty(newToLoad, toResource.isNew, isResourceEmpty = false)
      resourcePenaltyDelta += newToPenalty - oldToPenalty

      // Replication cost: adding load costs the current per-replica load.
      val replicationCost: Double = currentPerReplicaLoad

      // Churn cost: replicating to a new resource incurs churn penalty.
      val churnCost: Double = newPerReplicaLoad * config.churnConfig.maxPenaltyRatio

      resourcePenaltyDelta + replicationCost + churnCost
    }

    /**
     * Calculates the penalty delta for a Dereplicate operation (affects all current resources with
     * the slice).
     */
    private def calculateDereplicateDelta(
        dereplicate: Dereplicate,
        resourceStates: Vector[assignment.ResourceState]
    ): Double = {
      val sliceAsn: assignment.MutableSliceAssignment = dereplicate.sliceAssignment
      val resourceToDeallocate: assignment.ResourceState = dereplicate.resourceToDeallocate

      val currentPerReplicaLoad: Double = sliceAsn.rawLoadPerReplica
      val newPerReplicaLoad: Double = sliceAsn.rawLoad / (sliceAsn.currentNumReplicas - 1)
      val loadIncrease: Double = newPerReplicaLoad - currentPerReplicaLoad

      // Resource penalty delta for the resource being deallocated (loses this replica entirely).
      var resourcePenaltyDelta: Double = 0.0
      val oldDeallocPenalty: Double = resourcePenalty(
        resourceToDeallocate.getTotalLoad,
        resourceToDeallocate.isNew,
        resourceToDeallocate.getAssignedSlices.isEmpty
      )
      val newDeallocLoad: Double = resourceToDeallocate.getTotalLoad - currentPerReplicaLoad
      val newDeallocPenalty: Double = resourcePenalty(
        newDeallocLoad,
        resourceToDeallocate.isNew,
        resourceToDeallocate.getAssignedSlices.size == 1
      )
      resourcePenaltyDelta += newDeallocPenalty - oldDeallocPenalty

      // Resource penalty delta for ALL remaining resources with this slice (load increases).
      for (rs: assignment.ResourceState <- sliceAsn.getAllocatedResourceStates
        if rs != resourceToDeallocate) {
        val oldPenalty: Double =
          resourcePenalty(rs.getTotalLoad, rs.isNew, rs.getAssignedSlices.isEmpty)
        val newLoad: Double = rs.getTotalLoad + loadIncrease
        val newPenalty: Double = resourcePenalty(newLoad, rs.isNew, rs.getAssignedSlices.isEmpty)
        resourcePenaltyDelta += newPenalty - oldPenalty
      }

      // Dereplication benefit: removing load is a benefit (negative cost).
      val dereplicationCost: Double = -newPerReplicaLoad

      resourcePenaltyDelta + dereplicationCost
    }

    /**
     * Generates candidate operations (Reassign, Replicate, Dereplicate) prioritized by heuristics.
     * We only generate operations considering the hottest non-excluded resource.
     */
    private def generateCandidateOperations(
        hottestResource: assignment.ResourceState
    ): Seq[Operation] = {
      val candidates: mutable.ArrayBuffer[Operation] = mutable.ArrayBuffer[Operation]()

      val fromLoad: Double = hottestResource.getTotalLoad

      // Get slices assigned to hottest resource, sorted by load.
      val slicesOnHottest: Seq[assignment.MutableSliceAssignment] =
        hottestResource.getAssignedSlices.toSeq

      // Only consider Move and Dereplicate operations if `hottestResource` has > 1 slice, so it
      // will remain non-empty after the operation.
      if (hottestResource.getAssignedSlices.size > 1) {
        // Generate Move candidates: from hottest resource to colder resources.
        for (slice: assignment.MutableSliceAssignment <- slicesOnHottest) {
          for (toResource: assignment.ResourceState <- assignment
              .resourceStatesIteratorFromColdest()) {
            // Check: toResource doesn't have this slice (which also filters the case where
            // toResource == hottestResource).
            if (!slice.isAllocatedToResource(toResource.resource)) {
              candidates += Reassign(slice, hottestResource, toResource)
            }
          }
        }

        // Generate Dereplicate candidates: remove replica from hottest resource.
        // Only if above min total slice replicas.
        if (assignment.currentNumTotalSliceReplicas > minTotalSliceReplicas) {
          for (slice: assignment.MutableSliceAssignment <- slicesOnHottest) {
            // Check: dereplicating won't get us below the min.
            if (slice.currentNumReplicas >
              config.resourceAdjustedKeyReplicationConfig.minReplicas) {
              candidates += Dereplicate(slice, hottestResource)
            }
          }
        }
      }

      // Generate Replicate candidates: add replica to colder resources.
      for (slice: assignment.MutableSliceAssignment <- slicesOnHottest) {
        // Check: replicating won't get us above the max.
        if (slice.currentNumReplicas < config.resourceAdjustedKeyReplicationConfig.maxReplicas) {
          for (toResource: assignment.ResourceState <- assignment
              .resourceStatesIteratorFromColdest()) {
            // Check: toResource doesn't have this slice (which also filters the case where
            // toResource == hottestResource).
            if (!slice.isAllocatedToResource(toResource.resource)) {
              candidates += Replicate(slice, toResource)
            }
          }
        }
      }

      candidates.toSeq
    }

    /** Applies an operation to the assignment. */
    private def applyOperation(op: Operation): Unit = {
      op match {
        case Reassign(sliceAssignment, fromResource, toResource) =>
          sliceAssignment.reassignResource(fromResource, toResource)

        case Replicate(sliceAssignment, toResource) =>
          sliceAssignment.allocateResource(toResource)

        case Dereplicate(sliceAssignment, resourceToDeallocate) =>
          sliceAssignment.deallocateResource(resourceToDeallocate)
      }
    }
  }
}
