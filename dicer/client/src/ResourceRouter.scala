package com.databricks.dicer.client

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.github.blemale.scaffeine.{Cache, Scaffeine}

import com.databricks.caching.util.{ConsistentHashRing, PrefixLogger, WatchValueCell}
import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter

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
 */
class ResourceRouter[Stub <: AnyRef] private[dicer] (
    clerkAssignmentConsumer: WatchValueCell.Consumer[ClerkAssignment],
    logPrefix: String,
    stubFactory: ResourceAddress => Stub,
    stubCacheLifetime: FiniteDuration) {

  private val logger = PrefixLogger.create(getClass, logPrefix)

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
          clerkAssignment.assignment.sliceMap.lookUp(key).indexedResources
        resources(Random.nextInt(resources.length))
      }
    resourceOpt.map(getOrCreateStub)
  }

  /**
   * Two-level sharding variant of [[getStubForKey]]. Resolves `primaryKey` to the resources owning
   * it in the assignment, and then uses the precomputed [[ConsistentHashRing]] over those
   * resources keyed on `secondaryKey` to deterministically pick one. Single-replica slices skip
   * the ring and return their lone replica. If no assignment is currently known, returns None.
   */
  def getStubForKey(primaryKey: SliceKey, secondaryKey: SliceKey): Option[Stub] = {
    val resourceOpt: Option[Squid] =
      clerkAssignmentConsumer.getLatestValueOpt.map { clerkAssignment: ClerkAssignment =>
        val sliceAssignment: SliceAssignment =
          clerkAssignment.assignment.sliceMap.lookUp(primaryKey)
        clerkAssignment.hashRingsBySlice.get(sliceAssignment.slice) match {
          case Some(ring: ConsistentHashRing[Squid, SliceKey]) => ring.lookup(secondaryKey)
          // Single-replica slices do not have a precomputed hash ring and are not present in the
          // map.
          case None => sliceAssignment.indexedResources.head
        }
      }
    resourceOpt.map(getOrCreateStub)
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
