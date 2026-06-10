package com.databricks.caching.util

import scala.collection.mutable

import com.databricks.caching.util.ConsistentHashRingGoldenData.{
  EXPECTED_NODES_WITH_1_VNODE,
  EXPECTED_NODES_WITH_50_VNODES
}
import com.google.protobuf.ByteString
import com.databricks.testing.DatabricksTest

trait ConsistentHashRingSuiteBase extends DatabricksTest {

  /**
   * Creates a consistent hash ring with String nodes and keys that the tests run against,
   * containing `nodes` with `vnodesPerNode` virtual nodes per node and a default TypeMapper that
   * maps these Strings to their UTF-8 bytes.
   */
  protected def createConsistentHashRing(
      nodes: Vector[String],
      vnodesPerNode: Int): ConsistentHashRingHarness

  test("Create rejects bad arguments") {
    // Test plan: Verify that create() throws an exception when vnodesPerNode is zero or negative.
    // create() should also reject an empty nodes vector.

    // Non-positive vnodesPerNode.
    TestUtils.assertThrow[Exception]("must be > 0, got 0") {
      createConsistentHashRing(nodes = Vector("a"), vnodesPerNode = 0)
    }
    TestUtils.assertThrow[Exception]("must be > 0, got -1") {
      createConsistentHashRing(nodes = Vector("a"), vnodesPerNode = -1)
    }

    // Empty nodes set.
    TestUtils.assertThrow[Exception]("nodes must not be empty") {
      createConsistentHashRing(nodes = Vector.empty[String], vnodesPerNode = 16)
    }
  }

  test("Lookup is deterministic across repeated calls and identical rings") {
    // Test plan: Verify that repeated lookups of the same keys on the same ring return the same
    // nodes, and that two rings built from identical inputs agree on every key.
    val nodes: Vector[String] = Vector("a", "b", "c", "d")
    val ring1: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = nodes, vnodesPerNode = 16)
    val ring2: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = nodes, vnodesPerNode = 16)
    assert(ring1.nodes.toSet == nodes.toSet)
    assert(ring2.nodes.toSet == nodes.toSet)

    for (i: Int <- 0 until 200) {
      val key: String = s"key_$i"
      val owner: String = ring1.lookup(key = key)
      // Repeated lookups on the same ring are deterministic.
      assert(ring1.lookup(key = key) == owner)
      // Two rings built from identical inputs agree on every key.
      assert(ring2.lookup(key = key) == owner)
    }
  }

  test("Key distribution is roughly uniform across nodes") {
    // Test plan: Verify that key-to-node assignment is approximately balanced. Route 500 keys
    // across 5 nodes (each with 64 virtual nodes) and assert each node receives within +/- 25%
    // of the mean.
    val nodes: Vector[String] = (0 until 5).map((i: Int) => s"node_$i").toVector
    val ring: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = nodes, vnodesPerNode = 64)
    assert(ring.nodes.toSet == nodes.toSet)

    val totalKeys: Int = 500
    val keysPerNode: mutable.Map[String, Int] = mutable.Map.empty
    for (i: Int <- 0 until totalKeys) {
      val owner: String = ring.lookup(key = s"key_$i")
      keysPerNode(owner) = keysPerNode.getOrElse(owner, 0) + 1
    }

    val mean: Double = totalKeys.toDouble / nodes.size
    for (entry <- keysPerNode) {
      val (node, keysCount): (String, Int) = entry
      val deviation: Double = math.abs(keysCount - mean) / mean
      assert(
        deviation <= 0.25,
        s"Node $node deviated $deviation from mean $mean (count=$keysCount)"
      )
    }
  }

  test("Adding a node either maintains the same owner for keys or moves them to the new node") {
    // Test plan: Verify that adding a node either maintains the same owner for tested keys or
    // moves them to the new node, following the consistent hashing invariant.
    val originalRing: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = Vector("a", "b", "c"), vnodesPerNode = 32)
    assert(originalRing.nodes.toSet == Set("a", "b", "c"))

    val keys: Seq[String] = (0 until 1000).map((i: Int) => s"key_$i")
    val originalOwners: Map[String, String] =
      keys.map((key: String) => key -> originalRing.lookup(key = key)).toMap

    val updatedRing: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = Vector("a", "b", "c", "d"), vnodesPerNode = 32)
    assert(updatedRing.nodes.toSet == Set("a", "b", "c", "d"))

    // Every key must either stay on its original owner or move to "d". No key may have switched
    // between two pre-existing nodes.
    var movedToNewNode: Int = 0
    for (key: String <- keys) {
      val newOwner: String = updatedRing.lookup(key = key)
      assert(
        newOwner == originalOwners(key) || newOwner == "d",
        s"Key $key moved from ${originalOwners(key)} to $newOwner (neither original nor new)"
      )
      if (newOwner == "d") movedToNewNode += 1
    }
    // Confirm that "d" owns at least one key on the larger ring.
    assert(movedToNewNode > 0, "New node d owns no keys")
  }

  test("Removing one node keeps other keys on their original node") {
    // Test plan: Verify that when a node is removed, the other keys remain on their original node.
    // Keys that were originally owned by the removed node should get moved to another node.
    val originalRing: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = Vector("a", "b", "c", "d"), vnodesPerNode = 32)
    assert(originalRing.nodes.toSet == Set("a", "b", "c", "d"))

    val keys: Seq[String] = (0 until 1000).map((i: Int) => s"key_$i")
    val originalOwners: Map[String, String] =
      keys.map((key: String) => key -> originalRing.lookup(key = key)).toMap
    // Confirm that "d" owns at least one key, so that we can verify that its keys were moved.
    assert(originalOwners.values.toSet.contains("d"), "Test setup: d should own at least one key")

    val updatedRing: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = Vector("a", "b", "c"), vnodesPerNode = 32)
    assert(updatedRing.nodes.toSet == Set("a", "b", "c"))

    // Keys originally owned by "d" must route to one of the remaining nodes, and every other key
    // must still route to its original owner.
    for (key: String <- keys) {
      val newOwner: String = updatedRing.lookup(key = key)
      if (originalOwners(key) == "d") {
        assert(
          Set("a", "b", "c").contains(newOwner),
          s"Key $key was on d, now on $newOwner (not in remaining nodes)"
        )
      } else {
        assert(
          newOwner == originalOwners(key),
          s"Key $key moved from ${originalOwners(key)} to $newOwner (should not have moved)"
        )
      }
    }
  }

  test("Hash ring behavior is stable across code changes") {
    // Test plan: Verify with a golden test that the hash ring behavior, and the results produced
    // by the hash ring, do not change even when the code for the ring is modified. This is because
    // the consistent hashing mechanism is meant to be used across processes and pods, which may
    // be subject to different code versions but are still required to agree on the same
    // key-to-node assignments. Running this against the Rust harness additionally proves the Rust
    // and Scala implementations agree.

    // Create a ring with 500 nodes and 1 vnode per node, and verify the hardcoded selection
    // for 100 keys.
    val nodes: Vector[String] = (0 until 500).map((i: Int) => s"node_$i").toVector
    val keys: Seq[String] = (0 until 100).map((i: Int) => s"key_$i")
    val ring: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = nodes, vnodesPerNode = 1)
    assert(ring.nodes.toSet == nodes.toSet)
    for (i: Int <- keys.indices) {
      val owner: String = ring.lookup(key = keys(i))
      assert(
        owner == EXPECTED_NODES_WITH_1_VNODE(i),
        s"Key ${keys(i)} is assigned to $owner, expected ${EXPECTED_NODES_WITH_1_VNODE(i)}"
      )
    }

    // Run the same verification with a new ring with 50 vnodes per node. We use the same keys,
    // though these are likely now assigned to different nodes.
    val newRing: ConsistentHashRingHarness =
      createConsistentHashRing(nodes = nodes, vnodesPerNode = 50)
    assert(newRing.nodes.toSet == nodes.toSet)
    for (i: Int <- keys.indices) {
      val owner: String = newRing.lookup(key = keys(i))
      assert(
        owner == EXPECTED_NODES_WITH_50_VNODES(i),
        s"Key ${keys(i)} is assigned to $owner, expected ${EXPECTED_NODES_WITH_50_VNODES(i)}"
      )
    }
  }
}

/** Runs [[ConsistentHashRingSuiteBase]] against the in-process Scala [[ConsistentHashRing]]. */
class ScalaConsistentHashRingSuite extends ConsistentHashRingSuiteBase {

  /** Maps String nodes and keys to their UTF-8 bytes. */
  private val STRING_TYPE_MAPPER: ConsistentHashRing.TypeMapper[String, String] =
    new ConsistentHashRing.TypeMapper[String, String] {
      override def mapNode(node: String): ByteString = ByteString.copyFromUtf8(node)
      override def mapKey(key: String): ByteString = ByteString.copyFromUtf8(key)
    }

  override protected def createConsistentHashRing(
      nodes: Vector[String],
      vnodesPerNode: Int): ConsistentHashRingHarness =
    new ScalaConsistentHashRingHarness(
      ConsistentHashRing.create[String, String](
        nodes = nodes,
        vnodesPerNode = vnodesPerNode,
        typeMapper = STRING_TYPE_MAPPER
      )
    )
}
