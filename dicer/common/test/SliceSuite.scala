package com.databricks.dicer.common

import com.databricks.api.proto.dicer.friend.SliceP
import com.databricks.caching.util.TestUtils.{
  assertThrow,
  checkComparisons,
  checkEquality,
  loadTestData
}
import com.google.protobuf.ByteString
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.external.SliceKey
import com.databricks.testing.DatabricksTest
import com.google.common.collect.Range
import com.google.common.hash.Hashing
import com.databricks.dicer.common.test.SliceKeyTestDataP
import java.net.URI
import java.nio.charset.StandardCharsets
import scala.util.Random
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{
  HighSliceKey,
  InfinitySliceKey,
  ResourceAddress,
  Slice,
  SliceKey
}

class SliceSuite extends DatabricksTest {

  /** Some sample Slices in increasing order. */
  private val orderedSlices: IndexedSeq[Slice] = buildOrderedSlices()

  /** Builds a sequence of slices in increasing order from test data. */
  private def buildOrderedSlices(): IndexedSeq[Slice] = {
    val testData: SliceKeyTestDataP =
      loadTestData[SliceKeyTestDataP]("dicer/common/test/data/slice_key_test_data.textproto")
    val orderedHighSliceKeys: Seq[HighSliceKey] =
      testData.orderedHighSliceKeys.map { highSliceKey: SliceKeyTestDataP.HighSliceKeyP =>
        highSliceKeyFromProto(highSliceKey)
      }
    val builder = Seq.newBuilder[Slice]
    for (i: Int <- orderedHighSliceKeys.indices) {
      val low: HighSliceKey = orderedHighSliceKeys(i)
      for (j: Int <- i + 1 until orderedHighSliceKeys.size) {
        builder += Slice(low.asFinite, orderedHighSliceKeys(j))
      }
    }
    builder.result().toIndexedSeq
  }

  test("SliceKey newFingerprintBuilder") {
    // Test plan: convert key values to fingerprint slice keys. Verify that the expected fingerprint
    // function (FarmHash Fingerprint64) is used.

    val expectedFunction =
      (bytes: Array[Byte]) =>
        ByteString.copyFrom(Hashing.farmHashFingerprint64().hashBytes(bytes).asBytes())

    // From string.
    {
      val actual = SliceKey.newFingerprintBuilder().putString("hello").build()
      val expectedBytes = expectedFunction(Array('h', 'e', 'l', 'l', 'o'))
      assert(actual.bytes == expectedBytes)
    }
    // From Unicode string.
    {
      val actual = SliceKey.newFingerprintBuilder().putString("adiós").build()

      // UTF-8 encoding should be used for Unicode strings.
      val accentBytes = "ó".getBytes(StandardCharsets.UTF_8)
      assert(accentBytes.size == 2)
      val expectedBytes =
        expectedFunction(Array('a', 'd', 'i', accentBytes(0), accentBytes(1), 's'))
      assert(actual.bytes == expectedBytes)
    }
    // From bytes.
    {
      val bytes = Array[Byte](10, -42, 47)
      val actual = SliceKey.newFingerprintBuilder().putBytes(bytes).build()
      val expectedBytes = expectedFunction(bytes)
      assert(actual.bytes == expectedBytes)
    }
  }

  test("SliceKey.fromRawBytes") {
    // Test plan: Create SliceKeys from raw bytes without hashing. Verify that the bytes are
    // preserved verbatim.

    // From string (manually encoded to UTF-8).
    {
      val key: String = "hello"
      val actual = SliceKey.fromRawBytes(
        ByteString.copyFromUtf8(key)
      )
      val expectedBytes = ByteString.copyFrom(Array[Byte]('h', 'e', 'l', 'l', 'o'))
      assert(actual.bytes == expectedBytes)
    }
    // From Unicode string.
    {
      val key: String = "adiós"
      val actual = SliceKey.fromRawBytes(
        ByteString.copyFromUtf8(key)
      )

      // UTF-8 encoding should be used for Unicode strings.
      val accentBytes = "ó".getBytes(StandardCharsets.UTF_8)
      assert(accentBytes.size == 2)
      val expectedBytes =
        ByteString.copyFrom(Array[Byte]('a', 'd', 'i', accentBytes(0), accentBytes(1), 's'))
      assert(actual.bytes == expectedBytes)
    }
    // From bytes.
    {
      val bytes = Array[Byte](10, -42, 47)
      val actual = SliceKey.fromRawBytes(ByteString.copyFrom(bytes))
      assert(actual.bytes == ByteString.copyFrom(bytes))
    }
  }

  test("Slice compare") {
    // Test plan: define some slices ordered by min-inclusive then max-exclusive key and verify
    // that comparison operations work as expected.

    checkComparisons(orderedSlices)
  }

  test("Slice equality") {
    // Test plan: define groups of equivalent Slices and verify that equals and hashCode work as
    // expected.

    checkEquality(
      Seq(
        Seq(Slice.FULL, Slice(SliceKey.MIN, InfinitySliceKey), Slice.atLeast(SliceKey.MIN)),
        Seq(Slice(identityKey(10L), identityKey(20L)), Slice(identityKey(10L), identityKey(20L))),
        Seq(Slice(fp("Fili"), fp("Kili")), Slice(fp("Fili"), fp("Kili"))),
        Seq(Slice.atLeast(fp("Kili")), Slice(fp("Kili"), InfinitySliceKey))
      )
    )
  }

  test("Slice compare/equals with zero-padded prefix comparison") {
    // Test plan: verify that different SliceKeys with the same 8-byte zero-padded prefix compare
    // correctly in lexicographical order.
    val emptyKey = identityKey(new Array[Byte](0))
    val zeroEightKey = identityKey(new Array[Byte](8))
    val zeroSevenKey = identityKey(new Array[Byte](7))
    val zeroNineKey = identityKey(new Array[Byte](9))

    assert(zeroSevenKey < zeroEightKey)
    assert(zeroEightKey < zeroNineKey)
    assert(!emptyKey.equals(zeroEightKey))
    assert(emptyKey.hashCode() != zeroEightKey.hashCode())
  }

  test("SliceKeyHelper BigInt conversion") {
    // Test plan: verify that SliceKey conversion to and from BigInt works to spec.
    def test(expected: BigInt, unsignedBytes: Integer*): Unit = {
      val bytes: Array[Byte] = unsignedBytes.map(_.toByte).toArray
      val key: SliceKey = identityKey(bytes)
      val actual: BigInt = key.toBigInt
      assert(actual == expected)

      // Verify round-trip via fromBigInt.
      val roundTrip: SliceKey = SliceKeyHelper.fromBigInt(actual, key.bytes.size)
      assert(key == roundTrip)
    }
    test(expected = 0) // empty is zero!
    test(expected = 0, unsignedBytes = 0, 0) // non-empty may also be zero
    test(expected = 0x0102, unsignedBytes = 1, 2)
    test(expected = 0xFF, unsignedBytes = 0xFF) // bytes treated as unsigned
    test(expected = 0xFE, unsignedBytes = 0, 0, 0xFE) // leading zeroes
    test(expected = 0x2A00, unsignedBytes = 0, 0, 0x2A, 0) // leading and trailing zeroes
  }

  test("SliceKeyHelper BigInt conversion randomized") {
    // Test plan: verify that BigInt conversion round-trips for random values of various
    // bit-lengths and various desired output lengths. The implementation of
    // `SliceKeyHelper.fromBigInt` is internally complicated by the leading zero bytes added by
    // `BigInt.toByteArray` for some positive values, and this test is designed to shake out
    // possible edge cases.

    val rnd = new Random
    for (bitLength <- 0 until 2048) {
      val int: BigInt = if (bitLength == 0) {
        0
      } else {
        // Create a random sequence of bits, but ensure the MSB is set so that we get a number with
        // the desired bit-length.
        BigInt.apply(numbits = bitLength, rnd) | BigInt(1) << (bitLength - 1)
      }
      assert(int.bitLength == bitLength)

      // Attempt conversion to a `SliceKey` with and without leading zeroes to pad the length.
      val requiredLength: Int = (bitLength + 7) / 8
      for (length <- requiredLength until requiredLength + 4) {
        val key = SliceKeyHelper.fromBigInt(int, length)
        val roundTrip = key.toBigInt
        assert(int == roundTrip)
      }
      // Verify that attempts to convert to a slice key with fewer than the required bytes fail.
      assertThrow[IllegalArgumentException](s"length must be at least $requiredLength") {
        SliceKeyHelper.fromBigInt(int, requiredLength - 1)
      }
    }
  }

  test("SliceKeyHelper BigInt conversion negative cases") {
    // Test plan: verify the expected exceptions are thrown on invalid inputs.
    assertThrow[IllegalArgumentException]("length must be at least 2") {
      SliceKeyHelper.fromBigInt(magnitude = 1 << 15, length = 1)
    }
    assertThrow[IllegalArgumentException]("length must be at least 2") {
      SliceKeyHelper.fromBigInt(magnitude = 1 << 14, length = 1)
    }
    assertThrow[IllegalArgumentException]("length must be at least 0") {
      SliceKeyHelper.fromBigInt(magnitude = 0, length = -1)
    }
    assertThrow[IllegalArgumentException]("magnitude must be non-negative") {
      SliceKeyHelper.fromBigInt(magnitude = -1, length = 1)
    }
  }

  test("Slice contains") {
    // Test plan: Given some slices, test methods such as inSlice, asRange, etc.
    val min = SliceKey.MIN
    val someKey: SliceKey = identityKey("Hello")

    val slice1 = Slice(min, someKey)
    val slice2 = Slice.atLeast(someKey)
    val slice3 = Slice.FULL

    // Check for existence in Slice.
    assert(slice1.contains(min))
    assert(slice2.contains(identityKey("MustExist")))
    assert(!slice2.contains(identityKey("Bye")))
    assert(slice3.contains(min))
    assert(slice3.contains(identityKey("MustExist")))
  }

  test("SliceHelper proto roundtriping") {
    // Test plan: Create a few Slices with different keys - min, max and some reglar key. Ensure
    // that the proto created from it can then be used to make a Slice that is identical to the
    // original Slice.
    val min = SliceKey.MIN
    val someKey: SliceKey = identityKey("Hello")

    val slice1 = Slice(min, someKey)
    val slice2 = Slice.atLeast(someKey)
    val slice3 = Slice.FULL

    // Check roundtripping of each Slice.
    for (slice <- Array(slice1, slice2, slice3)) {
      val proto: SliceP = slice.toProto
      logger.info(s"Slice: $slice, Proto: $proto")

      // Check low, high values and then check roundtripping.
      assert(proto.getLowInclusive == slice.lowInclusive.bytes)
      slice.highExclusive match {
        case InfinitySliceKey => assert(proto.highExclusive.isEmpty)
        case highExclusive: SliceKey =>
          assert(proto.getHighExclusive == highExclusive.bytes)
      }
      val newSlice = SliceHelper.fromProto(proto)
      assert(slice == newSlice)
    }
  }

  /** Creates a [[SliceP]] with the UTF-8 encodings of the given bounds. */
  private def createSliceProto(
      lowInclusive: Option[String],
      highExclusive: Option[String]): SliceP = {
    new SliceP(
      lowInclusive = lowInclusive.map(ByteString.copyFromUtf8),
      highExclusive = highExclusive.map(ByteString.copyFromUtf8)
    )
  }

  test("SliceHelper fromProto") {
    // Test plan: verify fromProto conversion for various SliceP instances. Augments the proto
    // round-tripping tests by exercising some non-canonical but valid representations of Slice
    // protos, e.g., leaving `low_inclusive` unset rather than explicitly setting it to an empty
    // string.

    // For low_inclusive, not set means "empty", for high_exclusive, not set means Infinity. As a
    // result, the default proto maps to Slice.FULL.
    assert(SliceHelper.fromProto(SliceP.defaultInstance) == Slice.FULL)
    assert(
      SliceHelper
        .fromProto(createSliceProto(None, Some("a"))) == Slice(SliceKey.MIN, identityKey("a"))
    )
    assert(
      SliceHelper
        .fromProto(createSliceProto(Some(""), Some("a"))) == Slice(SliceKey.MIN, identityKey("a"))
    )
    assert(
      SliceHelper.fromProto(createSliceProto(Some("a"), None)) == "a" -- ∞
    )
  }

  test("SliceHelper fromProto invalid") {
    // Test plan: checks the IllegalArgumentException is thrown for various invalid SliceP
    // instances.

    assertThrow[IllegalArgumentException]("Low key must be less than high key") {
      SliceHelper.fromProto(createSliceProto(Some(""), Some("")))
    }
    assertThrow[IllegalArgumentException]("Low key must be less than high key") {
      SliceHelper.fromProto(createSliceProto(None, Some("")))
    }
    assertThrow[IllegalArgumentException]("Low key must be less than high key") {
      SliceHelper.fromProto(createSliceProto(Some(""), Some("")))
    }
    assertThrow[IllegalArgumentException]("Low key must be less than high key") {
      SliceHelper.fromProto(createSliceProto(Some("b"), Some("")))
    }
    assertThrow[IllegalArgumentException]("Low key must be less than high key") {
      SliceHelper.fromProto(createSliceProto(Some("b"), Some("a")))
    }
  }

  test("SliceHelper intersection") {
    // Test plan: Verify that the intersection of two slices is consistent with the value returned
    // by `Range.intersection`, modulo the different handling of empty intersections described in
    // the `SliceHelper.intersectionOf` spec.

    for (slice1 <- orderedSlices) {
      for (slice2 <- orderedSlices) {
        // Use `Range.intersection` as the reference implementation. This method will either throw
        // `IllegalArgumentException` or return an empty range for non-intersecting arguments. We
        // expect `None` from the implementation under test in both of those cases.
        val expected: Option[Slice] = try {
          val range: Range[SliceKey] = rangeFromSlice(slice1).intersection(rangeFromSlice(slice2))
          if (range.isEmpty) None else Some(sliceFromRange(range))
        } catch {
          // Range.intersection throws to indicate unconnected, empty slices.
          case _: IllegalArgumentException => None
        }
        val actual: Option[Slice] = slice1.intersection(slice2)
        assert(actual == expected, s"$slice1.intersection($slice2)")
      }
    }
  }

  test("Resource compare") {
    // Test plan: Create a few URIs and the corresponding Resource. Perform comparison
    // operations on all of them w.r.t. each other including themselves.

    // URIs in increasing order (per the `Comparable` implementation).
    val uris = IndexedSeq(
      URI.create("dicer-assigner.svc.cluster.local"),
      URI.create("http://test.com"),
      URI.create("https://softstore-0")
    )

    val resourceAddrs: IndexedSeq[ResourceAddress] = uris.map(ResourceAddress(_))
    checkComparisons(resourceAddrs)
  }

}
