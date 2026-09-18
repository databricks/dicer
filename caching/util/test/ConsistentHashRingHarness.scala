package com.databricks.caching.util

import com.google.protobuf.ByteString

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

object ConsistentHashRingHarness {

  /** Selects the TypeMapper used by a String-based ConsistentHashRing test harness. */
  sealed trait TypeMapperFunction {

    /** Returns the TypeMapper implementation used by the in-process Scala harness. */
    def toRealTypeMapper: ConsistentHashRing.TypeMapper[String, String]
  }

  object TypeMapperFunction {

    /** Maps nodes and keys to their UTF-8 bytes. */
    case object Utf8 extends TypeMapperFunction {
      override def toRealTypeMapper: ConsistentHashRing.TypeMapper[String, String] =
        new ConsistentHashRing.TypeMapper[String, String] {
          override def mapNode(node: String): ByteString = ByteString.copyFromUtf8(node)
          override def mapKey(key: String): ByteString = ByteString.copyFromUtf8(key)
        }
    }

    /** Maps all nodes to the same set of vnode positions and keys to their UTF-8 bytes. */
    case object CollidingNode extends TypeMapperFunction {
      override def toRealTypeMapper: ConsistentHashRing.TypeMapper[String, String] =
        new ConsistentHashRing.TypeMapper[String, String] {
          override def mapNode(node: String): ByteString = ByteString.EMPTY
          override def mapKey(key: String): ByteString = ByteString.copyFromUtf8(key)
        }
    }
  }
}

/** A [[ConsistentHashRingHarness]] backed by the in-process Scala [[ConsistentHashRing]]. */
class ScalaConsistentHashRingHarness(ring: ConsistentHashRing[String, String])
    extends ConsistentHashRingHarness {

  override def nodes: Vector[String] = ring.nodes

  override def lookup(key: String): String = ring.lookup(key = key)
}
