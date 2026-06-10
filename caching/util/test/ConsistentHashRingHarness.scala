package com.databricks.caching.util

/**
 * A wrapper around a [[ConsistentHashRing]] of String nodes and keys, providing a common interface
 * to both the Scala version (running in the main test process) or the Rust version (running in a
 * subprocess). This allows the same test suite to be run against both implementations.
 */
trait ConsistentHashRingHarness {

  /** See [[ConsistentHashRing.nodes]]. */
  def nodes: Vector[String]

  /** See [[ConsistentHashRing.lookup]]. */
  def lookup(key: String): String
}

/** A [[ConsistentHashRingHarness]] backed by the in-process Scala [[ConsistentHashRing]]. */
class ScalaConsistentHashRingHarness(ring: ConsistentHashRing[String, String])
    extends ConsistentHashRingHarness {

  override def nodes: Vector[String] = ring.nodes

  override def lookup(key: String): String = ring.lookup(key = key)
}
