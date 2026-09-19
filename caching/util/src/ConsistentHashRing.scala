package com.databricks.caching.util

import java.nio.ByteBuffer

import scala.collection.immutable

import com.google.protobuf.ByteString
import com.google.common.hash.Hashing

/**
 * Immutable consistent hash ring mapping lookup keys of type `K` to physical nodes of type `T`.
 *
 * The ring distinguishes between physical nodes (the `T` values that the caller supplies) and
 * virtual nodes (the `vnodesPerNode` ring positions occupied by each physical node). These
 * virtual nodes are created to balance key ownership even with a small number of physical nodes,
 * with a high `vnodesPerNode` value improving distribution uniformity at the cost of more entries
 * in the ring. A lookup hashes the key's byte representation, finds the smallest ring position
 * greater than or equal to that hash, and returns the physical node that owns that position. If
 * no such position exists, the lookup wraps to the smallest entry.
 *
 * If two virtual nodes hash to the same ring position, the one whose physical node comes later
 * in `nodes` wins. The ring therefore depends on the order of `nodes`, so callers that need
 * independent processes to build identical rings must order `nodes` deterministically everywhere.
 *
 * @param nodes the physical nodes to place on the ring. Must be non-empty.
 * @param vnodesPerNode the number of virtual nodes to create for each physical node.
 * @param typeMapper maps physical nodes and lookup keys to their byte representations.
 * @tparam T Physical node type. Each `T` is placed at `vnodesPerNode` positions on the ring.
 * @tparam K Lookup key type. Routed to a single owning `T`.
 */
final class ConsistentHashRing[T, K] private (
    val nodes: Vector[T],
    vnodesPerNode: Int,
    typeMapper: ConsistentHashRing.TypeMapper[T, K]) {
  require(nodes.nonEmpty, "nodes must not be empty")
  require(vnodesPerNode > 0, s"vnodesPerNode must be > 0, got $vnodesPerNode")

  /**
   * A tree map containing the 64-bit ring positions and insertion sequence numbers as keys, and
   * the owning physical nodes as values. Each physical node appears `vnodesPerNode` times.
   */
  private val ring: immutable.TreeMap[(Long, Long), T] = {
    val builder = immutable.TreeMap.newBuilder[(Long, Long), T]
    // To preserve last-writer-wins semantics while traversing toward the first vnode at or above a
    // key's hash, count down from the Long maximum. A lookup from `(hash, Long.MinValue)` then
    // encounters the most recently inserted vnode first when positions collide.
    var insertionSequencer: Long = Long.MaxValue
    for (node: T <- nodes) {
      val nodeByteString: ByteString = typeMapper.mapNode(node)
      for (vnodeIndex: Int <- 1 to vnodesPerNode) {
        // Append the 4-byte vnodeIndex to the node bytes to generate unique vnode byte sequences.
        val buffer: ByteBuffer = ByteBuffer.allocate(nodeByteString.size + 4)
        nodeByteString.copyTo(buffer)
        buffer.putInt(vnodeIndex)
        // Change the buffer to read mode.
        buffer.flip()
        builder += ((hashBytes(buffer), insertionSequencer) -> node)
        insertionSequencer -= 1
      }
    }
    builder.result()
  }

  /** Returns the node responsible for `key`. */
  def lookup(key: K): T = {
    val hashedValue: Long = hashBytes(typeMapper.mapKey(key).asReadOnlyByteBuffer())
    val lookupKey: (Long, Long) = (hashedValue, Long.MinValue)
    val owningVnodeIterator: Iterator[((Long, Long), T)] = ring.iteratorFrom(lookupKey)
    // Wrap to the first entry if no iterator entry is >= the hashed value.
    val (_, node): ((Long, Long), T) =
      if (owningVnodeIterator.hasNext) owningVnodeIterator.next() else ring.head
    node
  }

  /**
   * Returns an iterator over the ring's nodes in ring order, starting from the node responsible
   * for `key`. Each subsequent element is the next node encountered while walking the ring
   * clockwise and wrapping around back to the node responsible for `key`.
   * Note: For cases where `vnodesPerNode` > 1, the iterator will visit each node `vnodesPerNode`
   * times.
   */
  def lookupIterator(key: K): Iterator[T] = {
    val hashedValue: Long = hashBytes(typeMapper.mapKey(key).asReadOnlyByteBuffer())
    val lookupKey: (Long, Long) = (hashedValue, Long.MinValue)
    // Start from the first entry >= `hashedValue`, or wrap to the first entry on the ring if no
    // such entry exists. A `def` (not a `val`) is used so that each reference below creates a fresh
    // iterator instance.
    def keyOwnerIterator: Iterator[((Long, Long), T)] = {
      val owningVnodeIterator: Iterator[((Long, Long), T)] = ring.iteratorFrom(lookupKey)
      if (owningVnodeIterator.hasNext) owningVnodeIterator else ring.iterator
    }
    // Safe to call next() since `nodes` must be non-empty.
    val keyOwnerRingKey: (Long, Long) = keyOwnerIterator.next() match {
      case (ringKey, _) => ringKey
    }
    // An iterator that collects entries from the start of the ring until the node responsible for
    // `key`. In the case where `keyOwnerRingKey` corresponds to the first node on the ring, this
    // iterator will be empty.
    val wrapAroundIterator: Iterator[((Long, Long), T)] = ring.iterator.takeWhile(
      entry => {
        val (ringKey, _): ((Long, Long), T) = entry
        keyOwnerRingKey != ringKey
      }
    )
    // Concatenate the two iterators to get the full set of nodes in ring order starting from
    // `keyOwnerRingKey` and wrapping around.
    val orderedNodes: Iterator[T] = (keyOwnerIterator ++ wrapAroundIterator).map(
      entry => {
        // Extract the node from the ring entry.
        val (_, node): (_, T) = entry
        node
      }
    )
    orderedNodes
  }

  /** Hashes the bytes in `buffer` (position to limit) to a 64-bit value using FarmHash. */
  private def hashBytes(buffer: ByteBuffer): Long = {
    Hashing
      .farmHashFingerprint64()
      .newHasher()
      .putBytes(buffer)
      .hash()
      .asLong()
  }
}

object ConsistentHashRing {

  /**
   * Maps caller-supplied physical nodes and lookup keys to their byte representations. Used
   * to compute the 64-bit ring positions of each.
   */
  trait TypeMapper[T, K] {

    /** Maps a physical node to a byte representation. Equal nodes must map to equal bytes. */
    def mapNode(node: T): ByteString

    /** Maps a lookup key to a byte representation. Equal keys must map to equal bytes. */
    def mapKey(key: K): ByteString
  }

  /**
   * Creates a ring with the given nodes and `vnodesPerNode` positions per node.
   *
   * @param nodes         Nodes to place on the ring. Must be non-empty.
   * @param vnodesPerNode Number of ring positions per node. Must be positive.
   * @param typeMapper    Maps nodes and keys to the byte representations the ring hashes.
   */
  @throws[IllegalArgumentException]("if nodes is empty or vnodesPerNode is not positive")
  def create[T, K](
      nodes: Vector[T],
      vnodesPerNode: Int,
      typeMapper: TypeMapper[T, K]): ConsistentHashRing[T, K] =
    new ConsistentHashRing[T, K](nodes, vnodesPerNode, typeMapper)
}
