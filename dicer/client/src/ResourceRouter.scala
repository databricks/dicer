package com.databricks.dicer.client

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.github.blemale.scaffeine.{Cache, Scaffeine}

import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter

import com.databricks.dicer.common.Assignment.AssignmentValueCellConsumer
import com.databricks.dicer.common.Assignment
import com.databricks.dicer.external.{ResourceAddress, SliceKey}
import com.databricks.dicer.friend.Squid
import com.databricks.caching.util.PrefixLogger

/**
 * Abstraction to route keys to application-defined stubs.
 *
 * The implementation uses Dicer to map the key to a ResourceAddress, and then calls an
 * application-supplied stub factory to map the address to a stub. As an optimization, it caches the
 * resulting stub for a configurable period of time before asking the application to recreate it.
 *
 * @param assignmentConsumer Assignment consumer exposing the latest assignment to the router.
 * @param logPrefix Prefix to use with the PrefixLogger
 * @param stubFactory An application supplied function to create a stub given an address
 * @param stubCacheLifetime How long stubs are cached before being recreated
 */
class ResourceRouter[Stub <: AnyRef] private[dicer] (
    assignmentConsumer: AssignmentValueCellConsumer,
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
      assignmentConsumer.getLatestValueOpt.map { assignment: Assignment =>
        val resources: Vector[Squid] = assignment.sliceMap.lookUp(key).indexedResources
        resources(Random.nextInt(resources.length))
      }
    resourceOpt.map { resource: Squid =>
      // Look up the corresponding stub from the map, or create and remember a new resource if no
      // entry exists.
      resourceMap.get(resource, _ => {
        logger.info(s"Creating resource stub for $resource")
        stubFactory(resource.resourceAddress)
      })
    }
  }
}
