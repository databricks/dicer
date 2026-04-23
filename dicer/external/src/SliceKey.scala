package com.databricks.dicer.external

import com.google.common.primitives.{Ints, Longs, UnsignedLongs}

import com.google.protobuf.ByteString
import com.databricks.caching.util.Bytes

/**
 * A Slice key is a representation of an immutable sequence of bytes. Dicer assigns ranges of Slice
 * keys, known as Slices, to resources, like pods. They are derived from the application keys routed
 * by Dicer [[Clerk]]s, and affinitized to Dicer [[Slicelet]]s.
 *
 * @param bytes immutable sequence of bytes
 */
final class SliceKey private (val bytes: ByteString) extends AnyRef with HighSliceKey {

  SliceKey.slice_key_byte_size_histogram.observe(bytes.size())

  /**
   * The first 8 bytes of this key's `bytes` field formatted as a big-endian [[Long]]. If `bytes` is
   * shorter than 8 bytes, this prefix is padded with 0-bytes at the end.
   *
   * This field is used to compare SliceKeys more efficiently using [[Long]] comparison. If the
   * prefixes of two SliceKeys are not equal, a byte-by-byte lexicographical comparison can be
   * skipped.
   */
  private val bytesPrefix: Long = {
    val len: Int = bytes.size()
    // Convert bytes into unsigned longs and zero-pad.
    @inline def byteAt(i: Int): Long = if (i < len) bytes.byteAt(i) & 0xff else 0
    byteAt(0) << 56 | byteAt(1) << 48 | byteAt(2) << 40 | byteAt(3) << 32 |
    byteAt(4) << 24 | byteAt(5) << 16 | byteAt(6) << 8 | byteAt(7)
  }

  /**
   * Returns the raw bytes of this key. These bytes can be used as prefixes for storage keys
   * to efficiently identify rows belonging to a given Slice.
   *
   * The returned bytes can be deserialized back to a [[SliceKey]] using
   * `SliceKeyAccessor.fromRawBytes`.
   */
  def toRawBytes: ByteString = bytes

  override def compare(that: HighSliceKey): Int = that match {
    case that: SliceKey => this.compare(that)
    case InfinitySliceKey => -1
  }

  /** Result of comparing operand `this` with `that`. */
  def compare(that: SliceKey): Int = {
    val comparePrefixResult = java.lang.Long.compareUnsigned(this.bytesPrefix, that.bytesPrefix)
    if (comparePrefixResult != 0) {
      return comparePrefixResult
    }

    if (this.bytes.size() <= 8 || that.bytes.size() <= 8) {
      return this.bytes.size().compareTo(that.bytes.size())
    }
    ByteString.unsignedLexicographicalComparator().compare(this.bytes, that.bytes)
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: SliceKey => this.bytesPrefix == that.bytesPrefix && this.bytes == that.bytes
    case _ => false
  }

  override def hashCode(): Int = {
    if (this.bytes.size() <= 8) {
      // Simple inline combination of the individual hashes of `bytesPrefix` and `bytes.size()`
      31 * Longs.hashCode(this.bytesPrefix) + Ints.hashCode(this.bytes.size())
    } else {
      this.bytes.hashCode()
    }
  }

  /**
   * A human-readable version of the bytes in a SliceKey where we print out printable characters
   * normally and the unprintable ones are printed with their hex code.
   *
   * Keys of length 8 are assumed to be 64-bit fingerprints (which we recommend, created using
   * [[newFingerprintBuilder]]) and are printed as fixed-width (leading zeroes), hex-encoded, and
   * unsigned 64-bit integers to make assignment debug strings easier to interpret. Keys of length 9
   * that end in a 0 byte are printed as 8-byte keys with the suffix "\0" - these can appear due to
   * the Assigner isolating hot keys.
   */
  override def toString: String = {
    val len: Int = bytes.size()
    if (len == 8) {
      "0x%016x".format(bytesPrefix)
    } else if (len == 9 && bytes.byteAt(8) == 0) {
      "0x%016x\\0".format(bytesPrefix)
    } else {
      // Use Bytes.toString to escape non-ASCII characters.
      Bytes.toString(bytes)
    }
  }

  override def isFinite: Boolean = true

  override def asFinite: SliceKey = this

  private[dicer] override def toDetailedDebugString: String = {
    // In addition to the default toString, include:
    //  - The ByteString implementation class name, which may be rope, literal, etc.
    //  - The size of the key.
    //  - The actual bytes.
    //  - The prefix of the key as a long in decimal, hex formats, and as raw bytes.
    s"""SliceKey {
       |  $toString
       |  bytesClass: ${bytes.getClass.getName}
       |  bytesSize: ${bytes.size}
       |  bytes: [${bytes.toByteArray.mkString(", ")}]
       |  bytesPrefix: $bytesPrefix
       |  bytesPrefix (hex): ${UnsignedLongs.toString(bytesPrefix, /*radix=*/ 16)}
       |  bytesPrefix (bytes): [${Longs.toByteArray(bytesPrefix).mkString(", ")}]
       |}""".stripMargin
  }
}

/** Companion object for creating [[SliceKey]]s and for getting min keys. */
object SliceKey {

  /**
   * Buckets used by [[slice_key_byte_size_histogram]].
   *
   * We expect all slice keys created by Dicer customers to have 8 bytes, and a few slice keys
   * created internally by Dicer to represent high boundaries of hot keys to have 9 bytes. So we
   * add 7, 8, 9, 10 as bucket boundaries to verify this. In addition, we add a few larger buckets
   * in case there're larger slice keys created by Dicer customers in production.
   */
  private val SLICE_KEY_BYTE_SIZE_BUCKETS: Seq[Double] =
    Seq(7.0, 8.0, 9.0, 10.0, 16.0, 32.0, 64.0, 128.0)

  /**
   * Histogram tracking sizes of slice keys in bytes.
   *
   * We don't append target information as labels for this metric as we do for other Dicer metrics,
   * because target information is not available here, and it's not necessarily associated with
   * slice keys. If we need target information, we can get it from kubernetes_pod_name or
   * kubernetes_namespace labels in production metrics.
   */
  private val slice_key_byte_size_histogram = io.prometheus.client.Histogram
    .build()
    .name("dicer_slice_key_byte_size")
    .help("Histogram tracking sizes of slice keys in bytes")
    .buckets(SLICE_KEY_BYTE_SIZE_BUCKETS: _*)
    .register(io.prometheus.client.CollectorRegistry.defaultRegistry)

  /** The minimum key value. */
  val MIN: SliceKey = new SliceKey(ByteString.EMPTY)

  /**
   * Implicit making it more convenient to order [[SliceKey]] instances. Ideally, [[SliceKey]] would
   * implement `Ordered[SliceKey]` and `Ordered[HighSliceKey]`, but due to type erasure it is not
   * possible to implement both, and the `Ordered` trait is not marked contravariant so implementing
   * `Ordered[HighSliceKey]` is insufficient. Without this implicit, the following example would not
   * compile:
   *
   * {{{
   *   val keys: Seq[SliceKey] = ...
   *   f(keys.sorted)
   * }}}
   */
  implicit val ORDERING: Ordering[SliceKey] = (x: SliceKey, y: SliceKey) => x.compare(y)

  /**
   * Creates a new builder for constructing a [[SliceKey]] using the Farmhash fingerprint64
   * algorithm. The builder allows incrementally adding key parts (longs, strings, bytes) which are
   * then hashed together to produce an 8-byte fingerprint.
   *
   * If no values are put in the builder, returns the fingerprint for the empty string, _not_ MIN.
   *
   * Example:
   * {{{
   *   val key = SliceKey.newFingerprintBuilder()
   *     .putString("user-")
   *     .putLong(12345L)
   *     .build()
   * }}}
   */
  def newFingerprintBuilder(): SliceKeyBuilder = {
    import com.google.common.hash.Hashing
    new HasherSliceKeyBuilder(Hashing.farmHashFingerprint64().newHasher())
  }

  /**
   * Creates a [[SliceKey]] from uniformly distributed bytes.
   *
   * IMPORTANT: This method is for ADVANCED USE ONLY. For most applications, use
   * [[newFingerprintBuilder]] instead, which automatically ensures uniform
   * distribution and proper key construction.
   *
   * This method is intended for applications that need to generate custom slice keys
   * with specific distribution properties (e.g., range-based sharding schemes).
   *
   * REQUIREMENTS:
   *  - The input bytes must be uniformly distributed across the keyspace
   *  - Maximum length: 32 bytes
   *  - Bytes should have sufficient entropy to avoid hotspots
   *
   * WHEN TO USE THIS METHOD:
   * Use this method when you are creating NEW slice keys from scratch with custom
   * byte sequences that you have verified to be uniformly distributed.
   *
   * WHEN TO USE fromRawBytes INSTEAD:
   * If you need to deserialize a [[SliceKey]] that was previously serialized (e.g.,
   * from storage, a protobuf, or returned from [[toRawBytes]]), you should use
   * `fromRawBytes` from [[com.databricks.dicer.friend.external.SliceKeyAccessor]]
   * instead. Contact the maintainers to get allowlisted for friend accessor access.
   *
   * @throws IllegalArgumentException if bytes exceeds 32 bytes
   */
  def fromTrustedFingerprint(bytes: ByteString): SliceKey = {
    if (bytes.size() > 32) {
      throw new IllegalArgumentException("must not exceed 32 bytes")
    }
    new SliceKey(bytes)
  }

  /**
   * Creates a [[SliceKey]] from raw bytes. Used exclusively to unmarshal bytes returned from
   * [[SliceKey.toRawBytes]].
   *
   * External applications may be allowlisted for this API via
   * `com.databricks.dicer.friend.external.SliceKeyAccessor`.
   */
  private[dicer] def fromRawBytes(bytes: ByteString): SliceKey = {
    new SliceKey(bytes)
  }
}

/**
 * Trait implemented by [[Slice.highExclusive]] instances, which may be either finite [[SliceKey]]
 * values or the sentinel [[InfinitySliceKey]] object.
 */
sealed trait HighSliceKey extends Ordered[HighSliceKey] {

  /**
   * Returns true if this is a finite [[SliceKey]], or false if this is the [[InfinitySliceKey]].
   */
  def isFinite: Boolean

  /**
   * REQUIRES: `isFinite` (this is a finite [[SliceKey]]). Throws [[ClassCastException]] otherwise.
   *
   * Returns this as a finite [[SliceKey]].
   */
  def asFinite: SliceKey

  /**
   * Builds a detailed representation of the given [[SliceKey]] to aid debugging an apparent data
   * corruption issue in some clients.
   */
  private[dicer] def toDetailedDebugString: String
}

/** Sentinel value that sorts after all [[SliceKey]] values, used for [[Slice.atLeast]]. */
object InfinitySliceKey extends HighSliceKey {
  override def compare(that: HighSliceKey): Int = that match {
    case _: SliceKey => 1
    case InfinitySliceKey => 0
  }

  override def isFinite: Boolean = false

  override def asFinite: SliceKey = throw new ClassCastException("InfinitySliceKey is not finite")

  override def toString: String = "∞"

  private[dicer] override def toDetailedDebugString: String = "InfinitySliceKey"

  // No need to override hashCode or equals, as reference equality is desirable for a singleton.
}
