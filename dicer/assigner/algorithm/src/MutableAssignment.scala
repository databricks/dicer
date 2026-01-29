package com.databricks.dicer.assigner.algorithm

import java.time.Instant

import scala.collection.mutable

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.dicer.assigner.config.ChurnConfig
import com.databricks.dicer.common.{SliceAssignment, SliceWithResources}
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.Squid
import com.databricks.dicer.friend.SliceMap

/**
 * A mutable assignment of Slices to resources. It supports the following operations efficiently:
 *
 *  - Get the least and most loaded resources.
 *  - Get the list of Slices assigned to a resource.
 *  - Allocate/deallocate a resource to/from a Slice.
 *
 * Any resource that is in `baseAssignmentSliceMap` but not `resources.availableResources` is
 * considered unhealthy and returned by [[getUnhealthyResourceStates]], and unhealthy resources are
 * never returned by [[resourceStatesIteratorFromColdest]]. Resources in
 * `resources.availableResources` but not
 * `assignment` are considered healthy, just with 0 slice replicas assigned to them initially (thus
 * they will be the coldest resources).
 *
 * Note that this mutable assignment abstraction is only used as a helper of [[Algorithm]],
 * providing efficient methods for the Algorithm to conveniently represent, inspect and manipulate
 * the assignment. Its interfaces are closely coupled with Algorithm's implementation and is not
 * meant to be used elsewhere.
 *
 * @param instant ignored but kept for interface compatibility with internal code.
 * @param baseAssignmentSliceMap the slice map of the base assignment that is used to initialize the
 *                               current assignment.
 * @param resources the set of available resources.
 * @param adjustedLoadMap load information to use for load balancing. May include adjustments to
 *                        actual recent load measurements to account for (say) estimated future
 *                        load.
 * @param churnConfig ignored but kept for interface compatibility with internal code.
 */
private[assigner] class MutableAssignment(
    instant: Instant,
    baseAssignmentSliceMap: SliceMap[SliceAssignment],
    resources: Resources,
    adjustedLoadMap: LoadMap,
    churnConfig: ChurnConfig) {

  /**
   * Defines an ordering that can be used to sort Slice assignments by per-replica load.
   *
   * Orders by `MutableSliceAssignment.slice` after load, so that Slices with the same load are not
   * sorted in an arbitrary order. This is not strictly necessary, but it makes the algorithm
   * deterministic, facilitating testing.
   */
  val sliceAsnOrderingByRawLoadPerReplica: Ordering[MutableSliceAssignment] = Ordering.by {
    sliceAssignment: MutableSliceAssignment =>
      (sliceAssignment.rawLoadPerReplica, sliceAssignment.slice)
  }

  /**
   * States for resources in the assignment, sorted by [[ResourceState.getTotalLoad]] and then
   * by [[ResourceState.resource]]. Because load is mutable, it is critical to remove and then
   * re-add entries from the set when modifying load, otherwise the data structure will be
   * corrupted.
   *
   * Includes only resources that are in `resources.availableResources`.
   *
   * Implementation note: initially populated in [[unhealthyResourceStates]] initializer.
   */
  private val resourceStatesByTotalLoad = mutable.SortedSet.empty[ResourceState](Ordering.by {
    resourceState: ResourceState =>
      (resourceState.getTotalLoad, resourceState.resource)
  })

  /**
   * All Slice assignments ordered and keyed by [[MutableSliceAssignment.slice.lowInclusive]] so
   * that successor Slice assignments can be found efficiently for
   * [[MutableSliceAssignment.mergeWithSuccessor()]].
   *
   * INVARIANT: `sliceAssignments` are disjoint and complete.
   *
   * Implementation note: initially populated in [[unhealthyResourceStates]] initializer.
   */
  private val mutableSliceAssignments = mutable.TreeMap.empty[SliceKey, MutableSliceAssignment]

  /** The set of resources assigned in the [[baseAssignmentSliceMap]]. */
  private val baseAssignmentAssignedResources: Set[Squid] =
    baseAssignmentSliceMap.entries.flatMap { sliceAssignment: SliceAssignment =>
      sliceAssignment.resources
    }.toSet

  /** Tracks the current total number of slice replicas. */
  private var numTotalSliceReplicas: Int = 0

  /** States for all resources in the assignment. */
  private var allResourceStates: Vector[ResourceState] = Vector.empty

  /**
   * The state for each resource that is in the [[baseAssignmentSliceMap]] but not in
   * [[resources.availableResources]]. These resources are considered unhealthy and will have all of
   * their slice replicas unassigned in the algorithm's policy phase.
   *
   * During initialization of this vector (which is immutable), we also populate the mutable
   * [[resourceStatesByTotalLoad]] and [[mutableSliceAssignments]] collections.
   */
  private val unhealthyResourceStates: Vector[ResourceState] = {
    // Add all slices from `baseAssignmentSliceMap` into `mutableSliceAssignments`, and add all
    // resources from `baseAssignmentSliceMap` in to `existingResourceStatesBySquid` below.
    //
    // Note that `existingResourceStatesBySquid` is updated inside
    // `MutableSliceAssignment.createFromExisting` to avoid the duplicated logic to inspect the
    // resources inside `existingSliceAssignment`.
    val existingResourceStatesBySquid = mutable.Map[Squid, ResourceState]()
    for (existingSliceAssignment: SliceAssignment <- baseAssignmentSliceMap.entries) {
      val sliceAssignment = MutableSliceAssignment.createFromExisting(
        existingSliceAssignment,
        // `existingResourceStatesBySquid` will be updated with the resources contained in
        // `existingSliceAssignment`, if it hasn't contained them already.
        resourceStatesBuilder = existingResourceStatesBySquid
      )
      addMutableSliceAssignment(sliceAssignment)
    }

    // Populate `unhealthyResourceStates` and `resourceStatesByTotalLoad` for all resources
    // referenced in `baseAssignmentSliceMap`. Note that we do this _after_ the loop above so that
    // we don't have to repeatedly update the position of resources in
    // `resourceStatesByTotalLoad` as their load is repeatedly modified by adding Slices. This
    // is a performance optimization, as `MutableSliceAssignment` will automatically update
    // `resourceStatesByTotalLoad` when the `MutableSliceAssignment.resourceState` is updated.
    val unhealthyResourceStatesBuilder = Vector.newBuilder[ResourceState]
    for (resourceState: ResourceState <- existingResourceStatesBySquid.values) {
      if (resources.availableResources.contains(resourceState.resource)) {
        resourceStatesByTotalLoad.add(resourceState)
      } else {
        unhealthyResourceStatesBuilder += resourceState
      }
    }

    // Ensure that all resources that are in `availableResources` but not in
    // `baseAssignmentSliceMap` are added to `resourceStatesByTotalLoad`.
    for (resource: Squid <- resources.availableResources) {
      if (!existingResourceStatesBySquid.contains(resource)) {
        resourceStatesByTotalLoad.add(new ResourceState(resource))
      }
    }

    allResourceStates = resourceStatesByTotalLoad.toVector

    unhealthyResourceStatesBuilder.result()
  }

  /**
   * Returns state for all unhealthy resources (those in `baseAssignmentSliceMap` but not in
   * `resource.availableResources`). Note the order of the unhealthy resources is not guaranteed.
   */
  def getUnhealthyResourceStates: Vector[ResourceState] = unhealthyResourceStates

  /**
   * Returns whether any resources that are eligible for load balancing remain. An eligible resource
   * is in [[resources.availableResources]] and has not yet been excluded using
   * [[ResourceState.exclude()]].
   */
  def eligibleResourcesRemain: Boolean = {
    resourceStatesByTotalLoad.nonEmpty
  }

  /**
   * Returns an iterator that traverses all the remaining resources that are eligible for load
   * balancing, from the lowest loaded one to the highest one.
   *
   * IMPORTANT: The returned iterator is invalidated if any Slice is (re-)assigned, i.e. any of the
   * following methods are called:
   *
   * - [[MutableSliceAssignment.allocateResource]],
   * - [[MutableSliceAssignment.deallocateResource]],
   * - [[MutableSliceAssignment.adjustReplicas]],
   * - [[ResourceState.exclude]].
   */
  def resourceStatesIteratorFromColdest(): Iterator[ResourceState] = {
    resourceStatesByTotalLoad.iterator
  }

  /**
   * REQUIRES: `eligibleResourcesRemain` is true.
   *
   * Gets state for an eligible resource with the highest effective load.
   */
  def hottestResourceState: ResourceState = resourceStatesByTotalLoad.last

  /**
   * Returns an iterator over all Slice assignments in the assignment. The iterator is invalidated
   * if the assignment is modified, i.e. either of the following methods is called:
   *
   * - [[MutableSliceAssignment.split]],
   * - [[MutableSliceAssignment.mergeWithSuccessor()]].
   */
  def sliceAssignmentsIterator: Iterator[MutableSliceAssignment] =
    mutableSliceAssignments.values.iterator

  /**
   * Returns the number of slice replicas (rather than base slices) in the current assignment,
   * including those not assigned to any resource!
   */
  def currentNumTotalSliceReplicas: Int = numTotalSliceReplicas

  /**
   * Converts this mutable assignment to a complete, disjoint list of Slice -> Resource mappings.
   */
  def toSliceAssignments: SliceMap[SliceWithResources] = {
    // `mutableSliceAssignments` includes Slice assignments in Slice order, so we do not need to
    // sort them in this method.
    val sliceAssignmentsBuilder = Vector.newBuilder[SliceWithResources]
    sliceAssignmentsBuilder.sizeHint(mutableSliceAssignments.size)
    for (sliceAssignment: MutableSliceAssignment <- this.mutableSliceAssignments.values) {
      sliceAssignmentsBuilder += SliceWithResources(
        sliceAssignment.slice,
        sliceAssignment.allocatedReplicasByResource.keys.toSet
      )
    }
    new SliceMap(sliceAssignmentsBuilder.result(), (_: SliceWithResources).slice)
  }

  /**
   * REQUIRES: a slice assignment for `slice.lowInclusive` does not already exist.
   *
   * Adds `sliceAssignment` to [[mutableSliceAssignments]] and maintains [[numTotalSliceReplicas]].
   */
  private def addMutableSliceAssignment(sliceAssignment: MutableSliceAssignment): Unit = {
    val existingOpt: Option[MutableSliceAssignment] =
      mutableSliceAssignments.put(sliceAssignment.slice.lowInclusive, sliceAssignment)
    iassert(
      existingOpt.isEmpty,
      s"Slice assignment already exists for ${sliceAssignment.slice.lowInclusive}: $existingOpt"
    )
    numTotalSliceReplicas += sliceAssignment.currentNumReplicas
  }

  /**
   * REQUIRES: `sliceAssignment` is in [[mutableSliceAssignments]].
   *
   * Removes `sliceAssignment` from [[mutableSliceAssignments]] and maintains
   * [[numTotalSliceReplicas]].
   */
  private def removeMutableSliceAssignment(sliceAssignment: MutableSliceAssignment): Unit = {
    val existingOpt: Option[MutableSliceAssignment] =
      mutableSliceAssignments.remove(sliceAssignment.slice.lowInclusive)
    iassert(
      existingOpt.contains(sliceAssignment),
      s"$sliceAssignment was not in the assignment: existing=$existingOpt"
    )
    numTotalSliceReplicas -= sliceAssignment.currentNumReplicas
  }

  object forTest {

    /**
     * Checks:
     *
     *  - [[resourceStatesByTotalLoad]] collection has not been corrupted (it is in load order).
     *  - Invariants for [[ResourceState]] entries (see
     *    [[ResourceState.forTest.checkInvariants()]]).
     *  - [[MutableSliceAssignment]] instances in [[mutableSliceAssignments]] have the expected
     *    links.
     *  - [[mutableSliceAssignments]] is complete and disjoint.
     */
    def checkInvariants(): Unit = {
      if (resourceStatesByTotalLoad.nonEmpty) {
        // Verify that `resourceStatesByTotalLoad` is in increasing load order with the resource
        // used to break ties, and check local invariants for each element.
        var previousResourceState = resourceStatesByTotalLoad.head
        previousResourceState.forTest.checkInvariants()
        for (resourceState: ResourceState <- resourceStatesByTotalLoad.tail) {
          resourceState.forTest.checkInvariants()
          iassert(
            (previousResourceState.getTotalLoad, previousResourceState.resource)
            <= ((resourceState.getTotalLoad, resourceState.resource))
          )
          previousResourceState = resourceState
        }
      }

      // Verify that the number of replicas of each slice assignment is correct.
      for (sliceAssignment: MutableSliceAssignment <- sliceAssignmentsIterator) {
        iassert(
          sliceAssignment.currentNumReplicas ==
          sliceAssignment.allocatedReplicasByResource.size
        )
      }

      // Verify that the number of total replicas of the mutable assignment is correct.
      iassert(
        mutableSliceAssignments.values
          .map((_: MutableSliceAssignment).currentNumReplicas)
          .sum == currentNumTotalSliceReplicas
      )

      // Validate that `mutableSliceAssignments` is complete and disjoint.
      toSliceAssignments
    }
  }

  /**
   * The abstraction tracking the states associated with an allocation of a slice replica on a
   * resource.
   *
   * @param resourceState     The state of the resource that the slice replica is assigned to.
   * @param isNewlyAssigned   Whether this allocation is new (unchanged from the base assignment).
   */
  class AllocatedReplica(val resourceState: ResourceState, val isNewlyAssigned: Boolean)

  /**
   * PRECONDITION: `allocatedReplicasByResource.size >= 1`.
   *
   * State maintained for each Slice in the parent [[MutableAssignment]].
   *
   * It represents a slice containing one or multiple "slice replicas". Callers may use
   * [[allocateResource]] or [[deallocateResource]] to add or remove replicas, and
   * [[reassignResource]] to move a replica from one resource to another.
   *
   * The total number of the slice replicas is indicated by [[currentNumReplicas]], which is just
   * the size of [[allocatedReplicasByResource]]. The replica count changes when:
   * - [[allocateResource]] is called (increments by 1)
   * - [[deallocateResource]] is called (decrements by 1)
   * - [[adjustReplicas]] is called (sets to specified value)
   *
   * Each replica of the slice is considered to have evenly distributed equal raw load (see
   * [[rawLoadPerReplica]]). The per-replica raw load is calculated as `rawLoad / numReplicas`.
   *
   * The back links, loads, and positions in [[resourceStatesByTotalLoad]] for the resources
   * inside [[allocatedReplicasByResource]] will be automatically updated, upon construction of this
   * class or any calling of its public methods.
   *
   * INVARIANT: [[ResourceState.getAssignedSlices]] contains `this` for each resource in
   *            [[allocatedReplicasByResource]].
   *
   * @param rawLoad                     The total raw load on the `slice`. Despite the name "raw",
   *                                    in the Algorithm implementation it is actually the load
   *                                    containing the reservation adjustment (see
   *                                    `ReservationHintP`).
   * @param allocatedReplicasByResource Map from the resources (represented by [[Squid]]s) that are
   *                                    currently allocated to the `slice`, to the
   *                                    [[AllocatedReplica]] states associated to the allocated
   *                                    resource.
   */
  class MutableSliceAssignment private (
      val slice: Slice,
      val rawLoad: Double,
      private[MutableAssignment] val allocatedReplicasByResource: mutable.Map[
        Squid,
        AllocatedReplica]) {

    iassert(allocatedReplicasByResource.size >= 1)

    for (allocatedReplica: AllocatedReplica <- allocatedReplicasByResource.values) {
      allocatedReplica.resourceState.addSlice(this)
    }

    /*
     * Implementation note:
     *
     * When allocating a resource to a replica, we always follow this order:
     * 1. Add resource to the MutableSliceAssignment in `allocatedReplicasByResource`.
     * 2. Add slice to the ResourceState.
     *
     * When de-allocating a resource from a replica, we do it in reverse order:
     * 1. Remove slice from the ResourceState.
     * 2. Remove resource from the MutableSliceAssignment's `allocatedReplicasByResource`.
     *
     * This is because removing a slice from a resource requires the resource to update its
     * effective load, which requires looking itself up in the slice.
     */

    /**
     * PRECONDITION: `this` must be contained in the outer MutableAssignment.
     * PRECONDITION: `resourceState` is not currently assigned to this `slice`.
     *
     * Allocates the resource to this `slice` and increments the replica count.
     */
    def allocateResource(resourceState: ResourceState): Unit = {
      assertAttachedToOuterAssignment()
      iassert(
        !isAllocatedToResource(resourceState.resource),
        s"Trying to allocate ${resourceState.resource} to $slice where it is already assigned."
      )

      // Remove existing allocations to update their loads (per-replica load will change).
      val existingAllocatedReplicas: Vector[AllocatedReplica] =
        allocatedReplicasByResource.values.toVector
      for (ar: AllocatedReplica <- existingAllocatedReplicas) {
        ar.resourceState.removeSlice(this)
      }

      MutableAssignment.this.numTotalSliceReplicas += 1

      // Allocate the new resource. For simplicity, we always mark the new allocation as newly
      // assigned even though it might have been in the base assignment.
      val newAllocatedReplica = new AllocatedReplica(resourceState, isNewlyAssigned = true)
      allocatedReplicasByResource(resourceState.resource) = newAllocatedReplica

      // Re-add existing allocations with updated load.
      for (ar: AllocatedReplica <- existingAllocatedReplicas) {
        ar.resourceState.addSlice(this)
      }
      resourceState.addSlice(this)
    }

    /**
     * PRECONDITION: `this` must be contained in the outer MutableAssignment.
     * PRECONDITION: `resourceState` is currently assigned to this `slice`.
     *
     * Deallocates the resource from this `slice` and decrements the replica count.
     */
    def deallocateResource(resourceState: ResourceState): Unit = {
      assertAttachedToOuterAssignment()
      iassert(
        isAllocatedToResource(resourceState.resource),
        s"Trying to deallocate ${resourceState.resource} from $slice where it is not assigned."
      )

      // Remove existing allocations to update their loads (per-replica load will change).
      val existingAllocatedReplicas: Vector[AllocatedReplica] =
        allocatedReplicasByResource.values.toVector
      for (ar: AllocatedReplica <- existingAllocatedReplicas) {
        ar.resourceState.removeSlice(this)
      }

      // Remove the deallocated resource.
      allocatedReplicasByResource.remove(resourceState.resource)

      MutableAssignment.this.numTotalSliceReplicas -= 1

      // Re-add remaining allocations with updated load.
      for (ar: AllocatedReplica <- existingAllocatedReplicas
        if ar.resourceState != resourceState) {
        ar.resourceState.addSlice(this)
      }
    }

    /**
     * PRECONDITION: `this` must be contained in the outer MutableAssignment.
     * PRECONDITION: `fromResource` is currently assigned to this `slice`.
     * PRECONDITION: `toResource` is not currently assigned to this `slice`.
     *
     * Reassigns a replica from one resource to another without changing the replica count.
     * Corresponds to a move operation.
     */
    def reassignResource(fromResource: ResourceState, toResource: ResourceState): Unit = {
      assertAttachedToOuterAssignment()
      iassert(
        isAllocatedToResource(fromResource.resource),
        s"Trying to reassign from ${fromResource.resource} but it is not assigned to $slice."
      )
      iassert(
        !isAllocatedToResource(toResource.resource),
        s"Trying to reassign to ${toResource.resource} but it is already assigned to $slice."
      )

      // Remove from old resource.
      fromResource.removeSlice(this)
      allocatedReplicasByResource.remove(fromResource.resource)

      // Add to new resource. For simplicity, we always mark the new allocation as newly assigned
      // even though it might have been in the base assignment.
      val newAllocatedReplica = new AllocatedReplica(toResource, isNewlyAssigned = true)
      allocatedReplicasByResource(toResource.resource) = newAllocatedReplica
      toResource.addSlice(this)
    }

    /**
     * Returns the raw load (reservation adjusted load, see remarks for [[rawLoad]]) of one single
     * slice replica of this `slice`, assuming the total slice load is evenly distributed among the
     * replicas.
     */
    def rawLoadPerReplica: Double = {
      rawLoad / currentNumReplicas
    }

    /** Returns the current number of replicas for this `slice`. */
    def currentNumReplicas: Int = {
      allocatedReplicasByResource.size
    }

    /**
     * PRECONDITION: `this` must be attached to the outer MutableAssignment.
     *
     * Attempts to split this Slice into two equally loaded halves.
     *
     * When successful, the two split subslices will contain the same number of replicas and
     * assigned resources as before, and the higher level [[MutableAssignment]]'s data structures
     * will be maintained (i.e. removing this slice from the assignment and adding two subslices in
     * its place, which are returned as a tuple).
     *
     * When unsuccessful, returns None. The Slice is unsplittable if it includes only a single key
     * or if it has no load.
     */
    def split(): Option[(MutableSliceAssignment, MutableSliceAssignment)] = {
      assertAttachedToOuterAssignment()
      val existingLoad = adjustedLoadMap.getLoad(slice)
      val split: LoadMap.Split = adjustedLoadMap.getSplit(slice, existingLoad / 2)
      if (slice.lowInclusive < split.splitKey && split.splitKey < slice.highExclusive) {
        iassert(
          split.splitKey.isFinite,
          "`splitKey` is strictly less than `slice.highExclusive` and cannot be infinity"
        )
        val finiteSplitKey: SliceKey = split.splitKey.asFinite
        val slice1 = Slice(slice.lowInclusive, finiteSplitKey)
        val slice2 = Slice(finiteSplitKey, slice.highExclusive)
        val primaryRateLoadPrefix: Double = split.prefixApportionedLoad
        val primaryRateLoadSuffix: Double = existingLoad - primaryRateLoadPrefix

        // Remove the existing slice and maintains all data structures associated with it.
        this.removeFromAllResources()
        removeMutableSliceAssignment(this)

        // Add the subslices and maintain all data structures associated with them. Note that
        // the ResourceStates will be updated in the constructor of MutableSliceAssignment.
        val sliceAssignment1 = new MutableSliceAssignment(
          slice1,
          primaryRateLoadPrefix,
          // The subslices have the same assigned resources as before.
          allocatedReplicasByResource.clone()
        )
        addMutableSliceAssignment(sliceAssignment1)
        val sliceAssignment2 = new MutableSliceAssignment(
          slice2,
          primaryRateLoadSuffix,
          allocatedReplicasByResource.clone()
        )
        addMutableSliceAssignment(sliceAssignment2)
        Some((sliceAssignment1, sliceAssignment2))
      } else {
        // A split is not possible.
        None
      }
    }

    /**
     * PRECONDITION: `this` must be attached to the outer MutableAssignment.
     * PRECONDITION: `this` is not the last Slice in the assignment and `this` is contained in the
     * assignment.
     *
     * Merges this Slice with its successor and returns the merged Slice. The merged Slice:
     *
     *  - Covers the same range of keys as this Slice and its successor.
     *
     *  - Has the given number of replicas `numReplicas`.
     *
     *  - The resources after merging are chosen in a way that maximizes the part of the load that
     *    won't be "moved" before and after merging, i.e. minimizing the churn. This is done by
     *    taking the resources that shares the maximum load from the previous slices (see example
     *    below). Also, unhealthy resources will be excluded from consideration.
     *
     * For example, if we want to merge the slices below into 3 replicas:
     *
     *                    [a, b)              [b, c)               Total Load
     *   pod-0          rawLoad=2.0        rawLoad=3.0              1000.0
     *   pod-1          rawLoad=2.0                                 500.0
     *   pod-2                             rawLoad=3.0              400.0
     *   pod-3          rawLoad=2.0                                 300.0
     *
     * The slice after merging will be:
     *
     *                     [a, c)
     *   pod-0       (5.0 previous common load)
     *   pod-2       (3.0 previous common load)
     *   pod-3       (3.0 previous common load)
     *
     * Note pod-3 is preferred over pod-1 since pod-3 has less total load than pod-1, despite that
     * they have the same previous common load.
     */
    def mergeWithSuccessor(numReplicas: Int): MutableSliceAssignment = {
      assertAttachedToOuterAssignment()
      iassert(
        slice.highExclusive.isFinite,
        s"last Slice in assignment cannot be merged with non-existent successor: $this"
      )
      val successorOpt: Option[MutableSliceAssignment] =
        mutableSliceAssignments.get(slice.highExclusive.asFinite)
      iassert(successorOpt.isDefined, "non-existent successor")
      val successor: MutableSliceAssignment = successorOpt.get

      // Choosing the resources assigned to the slice after merging, using the strategy described
      // in the docs of this method.
      val thisAllocatedResources: Set[ResourceState] = this.allocatedResourcesSet()
      val successorAllocatedResources: Set[ResourceState] = successor.allocatedResourcesSet()
      val resourcesAfterMerge: Vector[ResourceState] =
        (thisAllocatedResources ++ successorAllocatedResources).toVector
          .filter { resource: ResourceState =>
            // Not considering unhealthy resources.
            !unhealthyResourceStates.contains(resource)
          }
          .sortBy { resource: ResourceState =>
            // Calculate the load on `resource` that has been previously assigned on `this.slice` or
            // `successor.slice`. This amount of load is considered "not being moved" after merging.
            var existingRawLoad: Double = 0
            if (thisAllocatedResources.contains(resource)) {
              existingRawLoad += this.rawLoadPerReplica
            }
            if (successorAllocatedResources.contains(resource)) {
              existingRawLoad += successor.rawLoadPerReplica
            }
            // If some resources have the same amount of unmoved load, prefer the ones with less
            // total load.
            (-existingRawLoad, resource.getTotalLoad)
          }
          .take(numReplicas)

      // Construct the allocated replicas for the merged slice.
      val allocatedReplicasAfterMerging: Seq[(Squid, AllocatedReplica)] = resourcesAfterMerge.map {
        resourceState: ResourceState =>
          val resource: Squid = resourceState.resource
          val bothHaveResource: Boolean = isAllocatedToResource(resource) &&
            successor.allocatedReplicasByResource.contains(resource)
          // Only preserve non-new status if both halves had the resource and neither was newly
          // assigned.
          val isNewlyAssigned: Boolean = !(bothHaveResource &&
          !this.allocatedReplicasByResource(resource).isNewlyAssigned &&
          !successor.allocatedReplicasByResource(resource).isNewlyAssigned)
          resource -> new AllocatedReplica(resourceState, isNewlyAssigned)
      }

      // Construct the new mutable slice assignment after merging.
      val sliceAssignment = new MutableSliceAssignment(
        slice = Slice(this.slice.lowInclusive, successor.slice.highExclusive),
        rawLoad = this.rawLoad + successor.rawLoad,
        mutable.Map[Squid, AllocatedReplica](allocatedReplicasAfterMerging: _*)
      )

      // Remove the two slices being merged from the `mutableSliceAssignment` and maintain all the
      // data structures associated with them.
      this.removeFromAllResources()
      successor.removeFromAllResources()
      removeMutableSliceAssignment(this)
      removeMutableSliceAssignment(successor)

      addMutableSliceAssignment(sliceAssignment)
      sliceAssignment
    }

    /**
     * PRECONDITION: `this` must be attached to the outer MutableAssignment.
     * PRECONDITION: `newNumReplicas` must be positive.
     * PRECONDITION: if `newNumReplicas` represents scaling up, there are enough viable resources
     *               (i.e. that don't already have this slice assigned and are not excluded) that
     *               can be allocated to.
     *
     * Adjusts the number of replicas of the current `slice` to `newNumReplicas`.
     *
     * When scaling up (newNumReplicas > current allocations), new replicas are eagerly allocated
     * to the coldest available resources.
     *
     * When scaling down, resources with the highest total load are de-allocated first.
     *
     * Changing the number of replicas will change the per-replica load of the slice, and thus
     * change the load on its assigned resources, which will be automatically maintained by this
     * method.
     */
    def adjustReplicas(newNumReplicas: Int): Unit = {
      assertAttachedToOuterAssignment()
      iassert(newNumReplicas > 0, "newNumReplicas must be positive.")
      if (newNumReplicas == currentNumReplicas) {
        return
      }

      // Maintain the change of load values on the assigned resources caused by the change of
      // replica number. This is done by removing the current slices from its assigned resources,
      // change the number of replicas, and adding the current slice back to the assigned
      // resources.

      // Keeping track of the existing AllocatedReplicas, so we can add them back when needed. Note
      // that we need to keep the AllocatedReplicas rather than only the ResourceStates, as we want
      // to keep `isNewlyAssigned` unchanged.
      val existingAllocatedReplicas: Vector[AllocatedReplica] =
        allocatedReplicasByResource.values.toVector

      // Remove all existing allocations from resources (to update loads properly).
      // NOTE: We must `removeSlice` from ALL resources before modifying the map, so that
      // `rawLoadPerReplica` is consistent for all removals.
      for (allocatedReplica: AllocatedReplica <- existingAllocatedReplicas) {
        allocatedReplica.resourceState.removeSlice(this)
      }
      allocatedReplicasByResource.clear()

      // Update replica counts.
      MutableAssignment.this.numTotalSliceReplicas -= existingAllocatedReplicas.size
      MutableAssignment.this.numTotalSliceReplicas += newNumReplicas

      if (newNumReplicas > existingAllocatedReplicas.size) {
        // Scaling UP or same count: keep all existing, add new allocations if needed.

        // First, add all existing allocations back to the map.
        for (allocatedReplica: AllocatedReplica <- existingAllocatedReplicas) {
          allocatedReplicasByResource(allocatedReplica.resourceState.resource) = allocatedReplica
        }

        // Eagerly allocate new replicas to coldest available resources.
        val numNewAllocations: Int = newNumReplicas - existingAllocatedReplicas.size
        val coldestResources: Vector[ResourceState] = resourceStatesByTotalLoad.iterator
          .filter((rs: ResourceState) => !isAllocatedToResource(rs.resource))
          .take(numNewAllocations)
          .toVector
        require(
          coldestResources.size == numNewAllocations,
          s"not enough viable resources to " +
          s"allocate to: ${coldestResources.size} < $numNewAllocations"
        )

        for (rs: ResourceState <- coldestResources) {
          // For simplicity, we always mark the new allocation as newly assigned even though it
          // might have been in the base assignment.
          val newAllocatedReplica = new AllocatedReplica(rs, isNewlyAssigned = true)
          allocatedReplicasByResource(rs.resource) = newAllocatedReplica
        }
      } else {
        // Scaling DOWN: keep only the coldest resources.
        val resourcesToMaintain: Vector[AllocatedReplica] = existingAllocatedReplicas
          .sortBy { allocatedReplica: AllocatedReplica =>
            // Prefer to keep resources with less total load (de-allocate from hottest).
            allocatedReplica.resourceState.getTotalLoad
          }
          .take(newNumReplicas)

        // Add all allocations to the map.
        for (allocatedReplica: AllocatedReplica <- resourcesToMaintain) {
          allocatedReplicasByResource(allocatedReplica.resourceState.resource) = allocatedReplica
        }
      }

      // Re-add the current allocations with updated `rawLoadPerReplica`.
      for (allocatedReplica: AllocatedReplica <- allocatedReplicasByResource.values) {
        allocatedReplica.resourceState.addSlice(this)
      }
    }

    /** Returns whether this slice is assigned to the given resource. */
    def isAllocatedToResource(resource: Squid): Boolean = {
      allocatedReplicasByResource.contains(resource)
    }

    /** Returns an iterable of all [[ResourceState]]s this slice is allocated to. */
    def getAllocatedResourceStates: Iterable[ResourceState] = {
      allocatedReplicasByResource.values.map((_: AllocatedReplica).resourceState)
    }

    /**
     * PRECONDITION: `resource` is currently assigned to this `slice`.
     *
     * Returns whether the allocation of this slice to the given resource is newly assigned
     * (unchanged from the base assignment). Note that if a slice is assigned away and then back to
     * a resource, it will still be marked as newly assigned (for simplicity of implementation).
     */
    def isNewlyAssignedToResource(resource: Squid): Boolean = {
      allocatedReplicasByResource(resource).isNewlyAssigned
    }

    override def toString: String = {
      s"MutableSliceAssignment(slice=$slice, numReplicas=$currentNumReplicas)"
    }

    /** Returns the set of [[ResourceState]]s currently assigned with this `slice`. */
    private def allocatedResourcesSet(): Set[ResourceState] =
      getAllocatedResourceStates.toSet

    /**
     * Removing the MutableSliceAssignment itself from its assigned [[ResourceState]]s and
     * maintains the updated loads.
     */
    private def removeFromAllResources(): Unit = {
      for (allocatedReplica: AllocatedReplica <- allocatedReplicasByResource.values) {
        allocatedReplica.resourceState.removeSlice(this)
      }
    }

    /**
     * Verifies the precondition that the current MutableSliceAssignment is attached to its outer
     * MutableAssignment instance. Used as safety guard when any API that will modify state is
     * called. E.g. this.adjustReplicas() cannot be called after a successful this.split() call
     * which removes the original MutableSliceAssignment from the MutableAssignment. When the
     * MutableSliceAssignment is not contained in the MutableAssignment (but still has back-refs to
     * the MutableAssignment and ResourceState), calling an API that mutates state can break
     * MutableAssignment's invariants.
     */
    private def assertAttachedToOuterAssignment(): Unit = {
      iassert(
        MutableAssignment.this.mutableSliceAssignments.get(slice.lowInclusive).contains(this),
        s"MutableSliceAssignment for slice $slice is removed from outer MutableAssignment. " +
        s"Its mutation APIs must not be called."
      )
    }
  }

  object MutableSliceAssignment {

    /**
     * Creates a new [[MutableSliceAssignment]] from an `existingSliceAssignment`, using the
     * ResourceStates from `resourceStatesBuilder`, or creating a new ResourceState and putting it
     * into the builder if any ResourceState for resources assigned on `existingSliceAssignment` is
     * not presented in the builder.
     */
    def createFromExisting(
        existingSliceAssignment: SliceAssignment,
        resourceStatesBuilder: mutable.Map[Squid, ResourceState]): MutableSliceAssignment = {
      val allocatedReplicasByResource = mutable.Map[Squid, AllocatedReplica]()
      for (resource: Squid <- existingSliceAssignment.resources) {
        val resourceState: ResourceState =
          resourceStatesBuilder.getOrElseUpdate(resource, { new ResourceState(resource) })
        val allocatedReplica = new AllocatedReplica(resourceState, isNewlyAssigned = false)
        allocatedReplicasByResource(resource) = allocatedReplica
      }
      val adjustedRawLoad: Double = adjustedLoadMap.getLoad(existingSliceAssignment.slice)
      new MutableSliceAssignment(
        existingSliceAssignment.slice,
        adjustedRawLoad,
        allocatedReplicasByResource
      )
    }
  }

  /**
   * State maintained for each resource in the parent [[MutableSliceAssignment]].
   *
   * INVARIANTS:
   *
   * Load invariants, which are preserved by calling [[updateLoadBy()]] whenever assigned Slices
   * are modified.
   *
   *  - The value of [[getTotalLoad]] must be equal to the sum of the loads returned by
   *    [[MutableSliceAssignment.rawLoadPerReplica]] for all Slices in [[getAssignedSlices]].
   *    Because of floating point arithmetic anomalies, this invariant is soft (and checked only in
   *    tests).
   *
   *  - If a Slice is in [[getAssignedSlices]], then its
   *    [[MutableSliceAssignment.allocatedReplicasByResource]] must contain this [[ResourceState]].
   *
   * Slice assignment invariants, which are preserved in [[MutableSliceAssignment]] by calling
   * [[addSlice()]] or [[removeSlice()]] when [[MutableSliceAssignment.allocateResource]]
   * [[MutableSliceAssignment.deallocateResource]] or [[MutableSliceAssignment.adjustReplicas]] is
   * called.
   */
  class ResourceState(val resource: Squid) {

    /** All Slices assigned to the current resource. */
    private val assignedSlices = mutable.HashSet.empty[MutableSliceAssignment]

    /**
     * Total load for the resource across all assigned Slices. Must be updated using
     * [[updateLoadBy()]] whenever [[assignedSlices]] is modified.
     */
    private var totalLoad: Double = 0

    /**
     * Whether this resource was added to the current assignment (relative to the
     * [[baseAssignmentSliceMap]]).
     */
    val isNew: Boolean = !baseAssignmentAssignedResources.contains(resource)

    /** Returns the total load for the resource across all assigned Slices. */
    def getTotalLoad: Double = totalLoad

    /**
     * Gets all Slices assigned to the current resource.
     *
     * Note the returned iterator will be invalidated when the assigned slices of the resource
     * change.
     */
    def getAssignedSlices: Iterable[MutableSliceAssignment] = assignedSlices

    /**
     * Excludes this resource from consideration in load balancing so that it is no longer returned
     * by [[hottestResourceState]] or [[coldestResourceState]]. A resource should be excluded when
     * it should no longer be cooled or heated because:
     *
     *  - It is an overheated resource with only a single remaining Slice, and assigning that Slice
     *    to a different resource will only hurt load balancing since the target resource would then
     *    be at least as hot as the current resource, and there would be unnecessary load churn.
     *  - The more general case of the above: migrating _any_ Slice from the current overheated
     *    resource to the coldest resource would result in worse load balancing.
     *  - No resources in the assignment can/should be cooled further, and the current resource is
     *    already hot enough.
     */
    def exclude(): Unit = {
      resourceStatesByTotalLoad.remove(this)
    }

    /** Returns whether `slice` is assigned on this resource. */
    def isAssigned(slice: MutableSliceAssignment): Boolean = {
      assignedSlices.contains(slice)
    }

    override def toString: String = {
      s"ResourceState(resource=$resource, totalLoad=$totalLoad, " +
      s"numAssignedSlices=${assignedSlices.size})"
    }

    /**
     * PRECONDITION: `this` is currently already tracked in the `sliceState`.
     *
     * Adds `sliceState` to [[assignedSlices]] and updates resource load.
     */
    private[MutableAssignment] def addSlice(sliceState: MutableSliceAssignment): Unit = {
      assignedSlices.add(sliceState)
      updateLoadBy(sliceState.rawLoadPerReplica)
    }

    /**
     * PRECONDITION: `this` is currently assigned to the `sliceState`.
     *
     * Removes `sliceState` from [[assignedSlices]] and updates resource load.
     */
    private[MutableAssignment] def removeSlice(sliceState: MutableSliceAssignment): Unit = {
      assignedSlices.remove(sliceState)
      updateLoadBy(-sliceState.rawLoadPerReplica)
    }

    /**
     * Increments [[totalLoad]] by `value` and updates the position of this resource in the (sorted
     * by load) [[resourceStatesByTotalLoad]] set if the resource is tracked there.
     */
    private def updateLoadBy(value: Double): Unit = {
      // Remove the resource from `resourceStatesByTotalLoad` before modifying `load` (we'll add
      // it back afterwards if it was there before).
      val isInResourceStates = resourceStatesByTotalLoad.remove(this)

      // Ensure that the updated load value is non-negative. Even though Slices have non-negative
      // load, floating point arithmetic can result in negative values (e.g., you can construct an
      // example where x + y - x != y).
      totalLoad = (totalLoad + value).max(0.0)
      if (assignedSlices.isEmpty) {
        totalLoad = 0.0
      }
      if (isInResourceStates) {
        // If `this` was in `resourceStatesByTotalLoad`, then add it back with the updated load.
        resourceStatesByTotalLoad.add(this)
      }
    }

    object forTest {

      /**
       * Checks local invariants for this [[ResourceState]]. See class docs for a description of
       * these invariants.
       */
      def checkInvariants(): Unit = {
        // Note that invariants relating to `resourceStatesByTotalLoad` are checked in
        // `MutableAssignment.forTest.checkInvariants()`.

        // Check that `totalLoad` is equal to the sum of the loads for all Slices in
        // `assignedSlices`.
        var expectedTotalLoad: Double = 0
        for (sliceState: MutableSliceAssignment <- assignedSlices) {
          iassert(
            sliceState.allocatedReplicasByResource(resource).resourceState ==
            ResourceState.this
          )
          expectedTotalLoad += sliceState.rawLoadPerReplica
        }
        iassert(
          // Tolerate a small float point error when comparing the loads.
          Math.abs(expectedTotalLoad - getTotalLoad) < 1e-5,
          s"$expectedTotalLoad != $getTotalLoad"
        )
      }
    }
  }
}
