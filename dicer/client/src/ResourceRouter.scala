package com.databricks.dicer.client

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.github.blemale.scaffeine.{Cache, Scaffeine}

import com.databricks.caching.util.{ConsistentHashRing, PrefixLogger, WatchValueCell}
import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter

import com.databricks.dicer.client.ClerkMetrics.ResourceType
import com.databricks.dicer.common.SliceAssignment
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
        // Observes the number of resources picked so far.
        metrics.observeGetNextStubForKeyPickedResourceCount(token.pickedResources.size)
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
          val sliceFallbackSquidOpt: Option[Squid] = sliceInfo.fallbackSquidOpt
          // Creates a fresh token if the caller did not pass one in.
          val token: RetryTokenImpl = retryTokenOpt.getOrElse(RetryTokenImpl.create())

          val pickedResourceSet: Set[Squid] = token.pickedResources.toSet
          // Computes the set difference: assigned resources minus picked resources.
          val unpickedResourceSet: Set[Squid] = sliceAssignment.resourcesSet.diff(pickedResourceSet)
          if (unpickedResourceSet.nonEmpty) {
            // Picks a random unpicked assigned resource and records it.
            val unpickedResources: Vector[Squid] = unpickedResourceSet.toVector
            val newResource: Squid = unpickedResources(Random.nextInt(unpickedResources.size))
            metrics.incrementGetNextStubForKeyCallCount(ResourceType.AssignedResource)
            Some((newResource, token.withPickedResource(newResource)))
          } else if (sliceFallbackSquidOpt.isDefined &&
            !pickedResourceSet.contains(sliceFallbackSquidOpt.get)) {
            val sliceFallbackSquid: Squid = sliceFallbackSquidOpt.get
            metrics.incrementGetNextStubForKeyCallCount(ResourceType.FallbackResource)
            Some((sliceFallbackSquid, token.withPickedResource(sliceFallbackSquid)))
          } else {
            // Returns a random assigned resource after exhausting assigned resources and the
            // fallback squid. As of writing this (Aug 18th, 2026), picking a random assigned
            // resource is the same behaviour as getStubForKey.
            val sliceAssignedResources: Vector[Squid] = sliceAssignment.resources
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
 * for how the token is used.
 *
 * Stores the resources it has already picked, including the fallback resource.
 *
 * @param pickedResources The resources already picked, including the fallback resource.
 */
private[dicer] final class RetryTokenImpl private (
    private[client] val pickedResources: Vector[Squid]) {

  /** Returns a copy of this token with `resource` appended to the picked resources. */
  def withPickedResource(resource: Squid): RetryTokenImpl =
    new RetryTokenImpl(pickedResources :+ resource)
}

/** Companion object for [[RetryTokenImpl]]. */
private object RetryTokenImpl {

  /** Creates a [[RetryTokenImpl]] without any picked resources. */
  def create(): RetryTokenImpl = new RetryTokenImpl(Vector.empty)
}
