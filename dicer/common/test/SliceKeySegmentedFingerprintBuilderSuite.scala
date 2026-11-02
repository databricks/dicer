package com.databricks.dicer.common

import java.nio.ByteBuffer

import com.databricks.caching.util.TestUtils.assertThrow
import com.google.protobuf.ByteString
import com.databricks.dicer.external.SliceKey
import com.databricks.dicer.friend.external.SliceKeySegmentedFingerprintBuilder
import com.databricks.testing.DatabricksTest

class SliceKeySegmentedFingerprintBuilderSuite extends DatabricksTest {

  test("fingerprints each segment in order") {
    // Test plan: Verify that the key is the concatenation of each segment's fingerprint, including
    // a trailing empty segment. Do this by building a three-segment key with every put overload
    // and comparing it with single-segment keys built from the same values.
    val key: SliceKey = SliceKeySegmentedFingerprintBuilder
      .create()
      .putString("workspace")
      .putLong(123L)
      .nextFingerprint()
      .putBytes(ByteString.copyFromUtf8("table"))
      .putBytes(ByteBuffer.wrap(Array[Byte](1, 2)))
      .putBytes(Array[Byte](3, 4))
      .nextFingerprint()
      .build()
    val first: SliceKey = SliceKey
      .newFingerprintBuilder()
      .putString("workspace")
      .putLong(123L)
      .build()
    val second: SliceKey = SliceKey
      .newFingerprintBuilder()
      .putString("table")
      .putBytes(Array[Byte](1, 2, 3, 4))
      .build()
    val empty: SliceKey = SliceKey.newFingerprintBuilder().build()
    assert(key.toRawBytes == first.toRawBytes.concat(second.toRawBytes).concat(empty.toRawBytes))
  }

  test("builds eight segments") {
    // Test plan: Verify that the maximum number of segments produces a 64-byte key that is the
    // concatenation of each segment's fingerprint. Do this by putting different values into every
    // segment and comparing the key with single-segment keys built from the same values.
    val builder: SliceKeySegmentedFingerprintBuilder = SliceKeySegmentedFingerprintBuilder.create()
    var expected: ByteString = ByteString.EMPTY
    for (i: Int <- 0 until 8) {
      if (i > 0) {
        builder.nextFingerprint()
      }
      builder.putString(s"segment$i").putLong(i.toLong)
      val segmentKey: SliceKey =
        SliceKey.newFingerprintBuilder().putString(s"segment$i").putLong(i.toLong).build()
      expected = expected.concat(segmentKey.toRawBytes)
    }
    val key: SliceKey = builder.build()
    assert(key.toRawBytes == expected)
    assert(key.toRawBytes.size() == 64)
  }

  test("build leaves the builder usable") {
    // Test plan: Verify that `build()` does not modify the builder. Do this by building the same
    // builder repeatedly, then putting more values into its open segment and starting another
    // segment, and comparing each key with one built by a fresh builder from the same values.
    val builder: SliceKeySegmentedFingerprintBuilder =
      SliceKeySegmentedFingerprintBuilder.create().putString("first").nextFingerprint().putLong(1L)
    val key: SliceKey = builder.build()
    assert(builder.build() == key)

    builder.putLong(2L)
    val keyAfterPut: SliceKey = SliceKeySegmentedFingerprintBuilder
      .create()
      .putString("first")
      .nextFingerprint()
      .putLong(1L)
      .putLong(2L)
      .build()
    assert(builder.build() == keyAfterPut)

    builder.nextFingerprint().putString("third")
    val keyAfterNextFingerprint: SliceKey = SliceKeySegmentedFingerprintBuilder
      .create()
      .putString("first")
      .nextFingerprint()
      .putLong(1L)
      .putLong(2L)
      .nextFingerprint()
      .putString("third")
      .build()
    assert(builder.build() == keyAfterNextFingerprint)
  }

  test("build throws for more than eight segments") {
    // Test plan: Verify that `build()` throws once a ninth segment is started, including after a
    // put and with a tenth segment, and that `nextFingerprint()` itself does not throw.
    val builder: SliceKeySegmentedFingerprintBuilder = SliceKeySegmentedFingerprintBuilder.create()
    for (_ <- 0 until 8) {
      builder.nextFingerprint()
    }
    assertThrow[IllegalArgumentException]("must not exceed 8 segments, got 9") {
      builder.build()
    }
    builder.putLong(9L)
    assertThrow[IllegalArgumentException]("must not exceed 8 segments, got 9") {
      builder.build()
    }
    builder.nextFingerprint()
    assertThrow[IllegalArgumentException]("must not exceed 8 segments, got 10") {
      builder.build()
    }
  }
}
