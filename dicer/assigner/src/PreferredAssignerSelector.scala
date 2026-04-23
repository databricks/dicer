package com.databricks.dicer.assigner

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.collection.Searching._

import com.google.common.hash.{HashFunction, Hashing}

/**
 * Selects the preferred Assigner from the given set of eligible Assigners. The selector is
 * deterministic: the same set of eligible assigners will always result in the same preferred
 * assigner.
 */
private[assigner] object PreferredAssignerSelector {

  // FarmHash is fast and distributes similar inputs (e.g. sequential UIDs) uniformly across the
  // output space, which gives even vnode placement on the ring.
  private val FARM_HASH: HashFunction = Hashing.farmHashFingerprint64()

  /**
   * The number of virtual node positions per pod. Higher values improve distribution uniformity
   * at the cost of more entries in the ring.
   */
  private val VIRTUAL_NODES_PER_POD: Int = 100

  /** The precomputed consistent hash ring position for the preferred assigner lookup. */
  private val PREFERRED_ASSIGNER_HASH: Long = hash("PreferredAssigner")

  /** Compares ring entries by hash position only; the UUID is irrelevant for lookups. */
  private val RING_ORDERING: Ordering[(Long, UUID)] = Ordering.by { entry =>
    val (key, _): (Long, UUID) = entry
    key
  }

  /**
   * Selects the preferred Assigner from the given set of eligible Assigners.
   *
   * @param eligibleAssigners The set of eligible Assigner UUIDs to select from.
   * @return The preferred Assigner UUID, or None if no eligible Assigners are available.
   */
  def selectPreferredAssigner(eligibleAssigners: Set[UUID]): Option[UUID] = {
    // O(n) where n = eligibleAssigners.size * VIRTUAL_NODES_PER_POD: place each assigner at
    // VIRTUAL_NODES_PER_POD positions on the ring. Rebuilds the ring on every call. If this
    // becomes a bottleneck, we could maintain the ring across calls and only recompute the
    // diff on membership changes.
    val ring: Vector[(Long, UUID)] = (for {
      uuid: UUID <- eligibleAssigners.toVector
      vnodeIndex: Int <- 1 to VIRTUAL_NODES_PER_POD
    } yield hash(s"$uuid-$vnodeIndex") -> uuid).sortBy { entry =>
      // O(n log n) sort by hash position.
      val (key, _): (Long, UUID) = entry
      key
    }

    if (ring.isEmpty) {
      None
    } else {
      // O(log n) binary search (Searching.search uses binary search on IndexedSeq) for the
      // first ring position >= the key hash (clockwise walk), wrapping to the first entry if
      // all positions are less.
      // When there is no exact match, insertionPoint returns the index where the value would
      // be inserted — i.e. the index of the first element larger than the search key, which
      // is exactly the clockwise successor we want.
      val searchKey: (Long, UUID) = (PREFERRED_ASSIGNER_HASH, new UUID(0L, 0L))
      val index: Int = ring.search(searchKey)(RING_ORDERING).insertionPoint
      val (_, owner): (Long, UUID) =
        if (index < ring.length) ring(index) else ring.head
      Some(owner)
    }
  }

  /** Hashes a string to a 64-bit ring position using FarmHash fingerprint64. */
  private def hash(key: String): Long = {
    FARM_HASH.hashString(key, StandardCharsets.UTF_8).asLong()
  }
}
