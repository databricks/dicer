package com.databricks.dicer.client

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.github.blemale.scaffeine.{Cache, Scaffeine}

import com.databricks.caching.util.{ConsistentHashRing, PrefixLogger, WatchValueCell}
import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter

import com.databricks.dicer.client.ClerkMetrics.ResourceType
import com.databricks.dicer.common.{SliceAssignment, Generation}
import com.databricks.dicer.external.{ResourceAddress, SliceKey}
import com.databricks.dicer.friend.Squid

/**
 * Abstraction to route keys to application-defined stubs.
 *
 * The implementation uses Dicer to map the key to a ResourceAddress, and then calls an
 * application-supplied stub factory to map the address to a stub. As an optimization, it caches the
 * resulting stub for a configurable period of time before asking the application to recreate it.
 *
 * @param clerkAssignmentConsumer Consumer exposing the latest [[ClerkAssignment]] to the router.
 * @param logPrefix Prefix to use with the PrefixLogger.
 * @param stubFactory An application-supplied function to create a stub given an address.
 * @param stubCacheLifetime How long stubs are cached before being recreated.
 * @param metrics A reference to [[ClerkMetrics]] for metric reporting.
 */
class ResourceRouter[Stub <: AnyRef] private[dicer] (
    clerkAssignmentConsumer: WatchValueCell.Consumer[ClerkAssignment],
    logPrefix: String,
    stubFactory: ResourceAddress => Stub,
    stubCacheLifetime: FiniteDuration,
    metrics: ClerkMetrics) {

  private val logger = PrefixLogger.create(getClass, logPrefix)

  private val retryAwarePicker: ResourceRouter.RetryAwarePicker =
    new ResourceRouter.RetryAwarePicker(clerkAssignmentConsumer)

  /**
   * Cached map from addresses to stubs, to avoid creating new stub on each request. Thread-safe
   * (per spec).
   */
  private val resourceMap: Cache[Squid, Stub] =
    SCaffeineCacheInfoExporter.registerCache(
      "resource_addresses_to_stubs",
      Scaffeine()
        .expireAfterAccess(stubCacheLifetime)
        .build()
    )

  /**
   * Given a key, return the corresponding stub, using Dicer to map the key to a resource
   * address and the stub factory to map the address to a stub. If no assignment is currently known,
   * returns None.
   */
  def getStubForKey(key: SliceKey): Option[Stub] = {
    val resourceOpt: Option[Squid] =
      clerkAssignmentConsumer.getLatestValueOpt.map { clerkAssignment: ClerkAssignment =>
        val resources: Vector[Squid] =
          clerkAssignment.assignment.sliceMap.lookUp(key).resources
        resources(Random.nextInt(resources.length))
      }
    resourceOpt.map(getOrCreateStub)
  }

  /**
   * Two-level sharding variant of [[getStubForKey]]. The `primaryKey` selects the set of resources
   * that own it in the assignment, and the `secondaryKey` deterministically selects one resource
   * among that set. The same `(primaryKey, secondaryKey)` pair resolves to the same resource under
   * the same assignment. If no assignment is currently known, returns None.
   */
  def getStubForKey(primaryKey: SliceKey, secondaryKey: SliceKey): Option[Stub] = {
    val resourceOpt: Option[Squid] =
      clerkAssignmentConsumer.getLatestValueOpt.map { clerkAssignment: ClerkAssignment =>
        val sliceInfo: SliceInfo = clerkAssignment.sliceInfoMap.lookUp(primaryKey)
        sliceInfo.twoLevelHashRingOpt match {
          case Some(ring: ConsistentHashRing[Squid, SliceKey]) => ring.lookup(secondaryKey)
          // Single-replica slices do not have a precomputed hash ring. Defer to the Assignment
          // sliceMap to lookup the resource.
          case None => clerkAssignment.assignment.sliceMap.lookUp(primaryKey).resources.head
        }
      }
    resourceOpt.map(getOrCreateStub)
  }

  /** See [[ClerkImpl.getNextStubForKey]] for spec details. */
  def getNextStubForKey(
      key: SliceKey,
      retryTokenOpt: Option[RetryTokenImpl]): Option[(Stub, RetryTokenImpl)] = {
    retryAwarePicker.getSquidForKey(key, retryTokenOpt, metrics) match {
      case Some((squid: Squid, token: RetryTokenImpl)) =>
        // Observes the number of assigned resources picked so far.
        metrics.observeGetNextStubForKeyPickedResourceCount(token.pickedResourceIndices.size)
        Some((getOrCreateStub(squid), token))
      case None => None
    }
  }

  /**
   * Converts a given [[Squid]] to a stub, looking it up in the cache if present or creating and
   * caching it via [[stubFactory]] if not.
   */
  private def getOrCreateStub(resource: Squid): Stub =
    resourceMap.get(resource, (_: Squid) => {
      logger.info(s"Creating resource stub for $resource")
      stubFactory(resource.resourceAddress)
    })
}

/** Companion object for [[ResourceRouter]]. */
private object ResourceRouter {

  /**
   * Picks a squid for getNextStubForKey, given a [[RetryTokenImpl]] and the local assignment
   * state [[ClerkAssignment]].
   *
   * @param clerkAssignmentConsumer Consumer exposing the latest [[ClerkAssignment]] to the picker.
   */
  private final class RetryAwarePicker(
      clerkAssignmentConsumer: WatchValueCell.Consumer[ClerkAssignment]) {

    /**
     * Implements the picking algorithm specified by [[ResourceRouter.getNextStubForKey]],
     * returning the chosen [[Squid]] and the [[RetryTokenImpl]] to thread into the next call
     * (if any), or `None` when the clerk has no assignment.
     *
     * @param key The requested SliceKey.
     * @param retryTokenOpt The request's retry token.
     * @param metrics A reference to [[ClerkMetrics]] for metric reporting.
     * @return The next squid pick and the token for the next call (if any).
     */
    def getSquidForKey(
        key: SliceKey,
        retryTokenOpt: Option[RetryTokenImpl],
        metrics: ClerkMetrics): Option[(Squid, RetryTokenImpl)] = {
      clerkAssignmentConsumer.getLatestValueOpt match {
        case Some(clerkAssignment: ClerkAssignment) =>
          val sliceInfo: SliceInfo = clerkAssignment.sliceInfoMap.lookUp(key)
          val sliceAssignment: SliceAssignment = clerkAssignment.assignment.sliceMap.lookUp(key)
          // Sorts the assigned resources to ensure the indices of the picked resources are
          // consistent across calls.
          val sliceAssignedResources: Vector[Squid] = sliceAssignment.resourcesSet.toVector.sorted
          val sliceFallbackSquidOpt: Option[Squid] = sliceInfo.fallbackSquidOpt
          val assignmentGeneration: Generation = clerkAssignment.assignment.generation

          val (token, wasTokenReset): (RetryTokenImpl, Boolean) = retryTokenOpt match {
            case Some(token: RetryTokenImpl)
                if clerkAssignment.assignment.generation != token.assignmentGeneration =>
              // Resets the token's state on a different assignment generation since the slice's
              // assigned resources might have changed. This should pick up updates from slice
              // reassignments, slice boundary changes, etc. This means that the
              // pickedResourceIndices list & fallbackPicked flag reset on every assignment
              // generation change. This can be optimized in the future by taking a checksum of
              // the slice assignment (using the squid UUIDs) instead of relying on the generation.
              (RetryTokenImpl.create(assignmentGeneration), true)
            case Some(token: RetryTokenImpl) => (token, false)
            // Creates a fresh token if the caller did not pass one in.
            case None => (RetryTokenImpl.create(assignmentGeneration), false)
          }

          // The number of assigned resources this token picks before falling back.
          val pickableResourceCount: Int =
            math.min(sliceAssignedResources.size, RetryTokenImpl.MAX_PICKED_ASSIGNED_RESOURCES)
          val pickedResourceCount: Int = token.pickedResourceIndices.size
          if (pickedResourceCount < pickableResourceCount) {
            // Picks a random unpicked assigned resource and records its index.
            val pickedResourceIndexSet: Set[Int] = token.pickedResourceIndices.toSet
            val unpickedResourceIndexSet: Set[Int] =
              sliceAssignedResources.indices.toSet.diff(pickedResourceIndexSet)
            val newResourceIndex: Int =
              unpickedResourceIndexSet.toVector(Random.nextInt(unpickedResourceIndexSet.size))
            if (wasTokenReset) {
              metrics.incrementGetNextStubForKeyCallCount(
                ResourceType.AssignedResourceAfterTokenReset
              )
            } else {
              metrics.incrementGetNextStubForKeyCallCount(ResourceType.AssignedResource)
            }
            Some(
              (sliceAssignedResources(newResourceIndex), token.withPickedIndex(newResourceIndex))
            )
          } else if (!token.fallbackPicked && sliceFallbackSquidOpt.isDefined) {
            metrics.incrementGetNextStubForKeyCallCount(ResourceType.FallbackResource)
            Some((sliceFallbackSquidOpt.get, token.withFallbackPicked))
          } else {
            // Returns a random assigned resource after exhausting assigned resources and the
            // fallback squid. As of writing this (Aug 18th, 2026), picking a random assigned
            // resource is the same behaviour as getStubForKey.
            metrics.incrementGetNextStubForKeyCallCount(ResourceType.RandomAssignedResource)
            Some((sliceAssignedResources(Random.nextInt(sliceAssignedResources.size)), token))
          }

        // No assignment.
        case None => None
      }
    }
  }
}

/**
 * Tracks the context for a chain of getNextStubForKey calls. See [[ClerkImpl.getNextStubForKey]]
 * for how the token is used. A [[RetryTokenImpl]] can be handled by any Clerk instance for a
 * target, which is necessary when an end-client drives retries across process boundaries (e.g.
 * through a proxy).
 *
 * Stores the assignment generation to detect assignment updates (i.e. a new resource assigned to a
 * slice), the indices of the assigned resources it has already picked, and whether the fallback
 * resource has been picked.
 *
 * @param assignmentGeneration      The assignment generation on token creation.
 * @param pickedResourceIndices     Indices into `SliceAssignment.orderedResources` of the slice's
 *                                  assigned resources already picked.
 * @param fallbackPicked            Whether the slice's fallback resource has already been picked.
 */
private[dicer] final class RetryTokenImpl private (
    private[client] val assignmentGeneration: Generation,
    private[client] val pickedResourceIndices: Vector[Int],
    private[client] val fallbackPicked: Boolean) {

  /** Returns a copy of this token with `index` appended to the picked indices. */
  @throws[IllegalArgumentException]("if the index is negative")
  def withPickedIndex(index: Int): RetryTokenImpl = {
    require(index >= 0, "picked index must be non-negative")
    new RetryTokenImpl(assignmentGeneration, pickedResourceIndices :+ index, fallbackPicked)
  }

  /** Returns a copy of this token with the fallbackPicked flag set to true. */
  def withFallbackPicked: RetryTokenImpl =
    new RetryTokenImpl(assignmentGeneration, pickedResourceIndices, fallbackPicked = true)
}

/** Companion object for [[RetryTokenImpl]]. */
private object RetryTokenImpl {

  /**
   * The maximum number of assigned resources picked in a [[RetryTokenImpl]]. This bounds the token
   * size. Most targets wouldn't exceed this many assigned resources, but if one does, only this
   * many are picked before falling back, even if the slice has more assigned resources. The number
   * is big enough that it's probably acceptable to use a fallback pod at that point.
   *
   * TODO: (<internal bug>) Once the [[RetryTokenImpl]] serialization code is implemented, add a unit
   * test to ensure the serialized [[RetryTokenImpl]] size is within the RPC header size limit
   * since it will be passed in the RPC header for certain use-cases.
   */
  val MAX_PICKED_ASSIGNED_RESOURCES: Int = 32

  /** Creates a [[RetryTokenImpl]] at `assignmentGeneration` without any picked resources. */
  def create(assignmentGeneration: Generation): RetryTokenImpl =
    new RetryTokenImpl(assignmentGeneration, Vector.empty, fallbackPicked = false)
}
