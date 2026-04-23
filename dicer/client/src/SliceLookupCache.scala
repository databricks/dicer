package com.databricks.dicer.client

import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable
import scala.concurrent.duration._

import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.PrefixLogger
import com.databricks.dicer.external.Target

/**
 * A cache of [[SliceLookup]] instances keyed by [[SliceLookupConfig]]s.
 *
 * The cache is used to avoid creating duplicate [[SliceLookup]] instances, and therefore
 * unnecessary assignment sync RPCs, for the same [[SliceLookupConfig]].
 */
@ThreadSafe
class SliceLookupCache {

  private val logger = PrefixLogger.create(getClass, "SliceLookupCache")

  /**
   * Map of [[SliceLookup]] indexed on [[Target]] and [[SliceLookupConfig]], to enable reuse of
   * lookups across client instances.
   *
   * While an [[SliceLookupConfig]] contains [[Target]], we break it into a two level of map to get
   * detailed tracking of lookups for metrics purposes (i.e., to distinguish between lookups for
   * the same target with different [[SliceLookupConfig]]s).
   */
  @GuardedBy("lock")
  private val sliceLookupMap: mutable.Map[Target, mutable.Map[SliceLookupConfig, SliceLookup]] =
    mutable.Map.empty

  /** Lock protecting mutable state. */
  private val lock: ReentrantLock = new ReentrantLock()

  /**
   * Returns a [[SliceLookup]] instance for the given [[SliceLookupConfig]].
   *
   * If a [[SliceLookup]] instance for the given config already exists, it is returned. Otherwise,
   * a new [[SliceLookup]] instance is created and returned. The new instance is created by calling
   * the provided [[lookupFactory]] function.
   *
   * The returned [[SliceLookup]] instance may or may not have been started. The caller must still
   * call [[SliceLookup.start]] before any other methods. Calling [[SliceLookup.start]] multiple
   * times is allowed, but will have no effect past the first call.
   *
   * @param config The configuration for the [[SliceLookup]] used by the client.
   * @param lookupFactory A function that creates a [[SliceLookup]] instance for the given
   *                      [[SliceLookupConfig]].
   * @return A [[SliceLookup]] instance for the given config.
   */
  def getOrElseCreate(config: SliceLookupConfig, lookupFactory: => SliceLookup): SliceLookup =
    withLock(lock) {
      val target: Target = config.target
      sliceLookupMap.get(target) match {
        case Some(targetMap: mutable.Map[SliceLookupConfig, SliceLookup]) =>
          val configMatched: Boolean = targetMap.contains(config)
          ClientMetrics.recordSliceLookupCacheResult(config.target, configMatched)
          if (!configMatched) {
            logger.warn(
              s"SliceLookup cache miss due to config mismatch for $target. New config: $config",
              every = 10.seconds
            )
          }
          targetMap.getOrElseUpdate(config, lookupFactory)
        case None =>
          val newTargetMap: mutable.Map[SliceLookupConfig, SliceLookup] = mutable.Map.empty
          val lookup = lookupFactory
          newTargetMap.put(config, lookup)
          sliceLookupMap.put(target, newTargetMap)
          lookup
      }
    }
}
