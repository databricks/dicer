package com.databricks.dicer.client

import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable
import scala.concurrent.duration._

import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.PrefixLogger
import com.databricks.dicer.external.Target

/**
 * A cache of [[SliceLookup]] instances keyed by their lookup identity ([[SliceLookupConfig]]) and
 * watch connection ([[WatchStubManagerConfig]]). `subscriberDebugName` is excluded so clients with
 * different debug names share a lookup.
 *
 * The cache is used to avoid creating duplicate [[SliceLookup]] instances, and therefore
 * unnecessary assignment sync RPCs, for the same config.
 */
@ThreadSafe
class SliceLookupCache {

  private val logger = PrefixLogger.create(getClass, "")

  /**
   * Map of [[SliceLookup]] indexed on [[Target]] and [[SliceLookupCache.LookupKey]], to enable
   * reuse of lookups across client instances.
   *
   * While the key contains [[Target]] (via its [[SliceLookupConfig]]), we break it into a two-level
   * map to get detailed tracking of lookups for metrics purposes (i.e., to distinguish between
   * lookups for the same target with different configs).
   */
  @GuardedBy("lock")
  private val sliceLookupMap
      : mutable.Map[Target, mutable.Map[SliceLookupCache.LookupKey, SliceLookup]] =
    mutable.Map.empty

  /** Lock protecting mutable state. */
  private val lock: ReentrantLock = new ReentrantLock()

  /**
   * Returns a [[SliceLookup]] instance for the given config.
   *
   * If a [[SliceLookup]] instance for the given config already exists, it is returned. Otherwise,
   * a new [[SliceLookup]] instance is created and returned. The new instance is created by calling
   * the provided [[lookupFactory]] function.
   *
   * Two configs share a cached lookup iff both their [[SliceLookupConfig]] and their
   * [[WatchStubManagerConfig]] are equal, so lookups differing only in a watch-connection field
   * (e.g. watch address or TLS options) are kept distinct.
   *
   * The returned [[SliceLookup]] instance may or may not have been started. The caller must still
   * call [[SliceLookup.start]] before any other methods. Calling [[SliceLookup.start]] multiple
   * times is allowed, but will have no effect past the first call.
   *
   * @param config The configuration for the [[SliceLookup]] used by the client.
   * @param lookupFactory A function that creates a [[SliceLookup]] instance for the given config.
   * @return A [[SliceLookup]] instance for the given config.
   */
  def getOrElseCreate(config: InternalClientConfig, lookupFactory: => SliceLookup): SliceLookup =
    withLock(lock) {
      val target: Target = config.sliceLookupConfig.target
      val key: SliceLookupCache.LookupKey =
        SliceLookupCache.LookupKey(config.sliceLookupConfig, config.watchStubManagerConfig)
      sliceLookupMap.get(target) match {
        case Some(targetMap: mutable.Map[SliceLookupCache.LookupKey, SliceLookup]) =>
          val configMatched: Boolean = targetMap.contains(key)
          ClientMetrics.recordSliceLookupCacheTargetHit(target, configMatched)
          if (!configMatched) {
            logger.warn(
              s"SliceLookup cache miss due to config mismatch for $target. New config: $config",
              every = 10.seconds
            )
          }
          targetMap.getOrElseUpdate(key, lookupFactory)
        case None =>
          val newTargetMap: mutable.Map[SliceLookupCache.LookupKey, SliceLookup] = mutable.Map.empty
          val lookup = lookupFactory
          newTargetMap.put(key, lookup)
          sliceLookupMap.put(target, newTargetMap)
          lookup
      }
    }
}

object SliceLookupCache {

  /**
   * The cache key identifying a reusable [[SliceLookup]], combining its lookup identity
   * ([[SliceLookupConfig]]) and watch connection ([[WatchStubManagerConfig]]), excluding
   * `subscriberDebugName`. Two clients share a cached lookup iff both parts are equal.
   */
  private case class LookupKey(
      sliceLookupConfig: SliceLookupConfig,
      watchStubManagerConfig: WatchStubManagerConfig)
}
