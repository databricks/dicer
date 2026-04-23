package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.google.protobuf.ByteString
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.{MetricUtils, TestUtils}
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.common.test.SliceKeyTestDataP
import com.databricks.dicer.common.TestSliceUtils.{
  highSliceKeyFromProto,
  identityKey,
  singleByteIdentityKey
}
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.SliceKeyHelper._
import com.databricks.dicer.common.test.SliceKeyTestDataP.ToStringTestCaseP

class SliceKeySuite extends DatabricksTest {
  // Since SliceKey mostly encapsulates Bytes, we do not retest the Bytes methods via SliceKey.
  // In that sense, these tests are somewhat more glass box. For SliceKey, we mostly test the end
  // keys.

  /** Converts a sequence of int32 values from proto to a ByteString. */
  private def intsToBytes(ints: Seq[Int]): ByteString = {
    ByteString.copyFrom(ints.map(_.toByte).toArray)
  }

  test("ordering") {
    // Test plan: perform generic tests of ordering operators given a manually constructed sequence
    // of SliceKey values in the expected sort order.
    TestUtils.checkComparisons(SliceKeySuite.ORDERED_SLICE_KEYS)
  }

  test("equality") {
    // Test plan: perform generic tests of equality and hash-code logic given a manually constructed
    // set of equality groups, and using our sample ordered slice keys.
    TestUtils.checkEquality(
      groups = Seq(
        Seq(InfinitySliceKey, InfinitySliceKey),
        Seq(identityKey(), identityKey()),
        Seq(singleByteIdentityKey(0), singleByteIdentityKey(0)),
        Seq(identityKey(0, 0), identityKey(0, 0))
      )
    )
    TestUtils.checkEquality(groups = SliceKeySuite.ORDERED_SLICE_KEYS.map { key: HighSliceKey =>
      // Include two references to each key and a copy.
      val copy = key match {
        case key: SliceKey =>
          // Use fromRawBytes to create a copy with the same bytes for equality testing.
          SliceKey.fromRawBytes(key.toRawBytes)
        case InfinitySliceKey => InfinitySliceKey
      }
      Seq(key, key, copy)
    })
  }

  test("isFinite") {
    // Test plan: Verify that isFinite returns true for finite SliceKeys, and false otherwise (for
    // the infinite SliceKey). Verify this by checking the return value of isFinite on various
    // finite SliceKeys, and on the infinite SliceKey.
    for (highSliceKey: HighSliceKey <- SliceKeySuite.ORDERED_SLICE_KEYS) {
      if (highSliceKey != InfinitySliceKey) {
        assert(highSliceKey.isFinite)
      } else {
        assert(!highSliceKey.isFinite)
      }
    }
  }

  test("asFinite") {
    // Test plan: Verify that asFinite returns a finite SliceKey for HighSliceKeys which are finite,
    // and throws a ClassCastException otherwise.
    for (highSliceKey: HighSliceKey <- SliceKeySuite.ORDERED_SLICE_KEYS) {
      if (highSliceKey != InfinitySliceKey) {
        val sliceKey: SliceKey = highSliceKey.asFinite
        assert(sliceKey == highSliceKey)
      } else {
        assertThrow[ClassCastException]("InfinitySliceKey is not finite") {
          highSliceKey.asFinite
        }
      }
    }
  }

  test("SliceKey toString") {
    // Test plan: Run toString on SliceKeys created from shared proto test data. Verify that the
    // string representations match the expected values specified in the test data.
    for (testCase: ToStringTestCaseP <- SliceKeySuite.TEST_DATA.toStringTestCases) {
      val key: SliceKey = TestSliceUtils.sliceKeyFromProto(testCase.sliceKey.get)
      if (testCase.getIsSuccessor) {
        assertResult(testCase.expectedString.get)(key.successor().toString)
      } else {
        assertResult(testCase.expectedString.get)(key.toString)
      }
    }
  }

  test("toDetailedDebugString") {
    // Test plan: verify we have detailed, useful output for various keys:
    //  - InfinitySliceKey
    //  - 8-byte keys, where the signed representation is positive, zero, or negative
    //  - 8-byte key with leading zeroes
    //  - 9-byte key with trailing zero
    //  - 9-byte keys with leading zeroes
    //  - 9-byte key without trailing zero
    //  - 7-byte key
    //  - Keys retrieved by roundtripping a proto
    assert(InfinitySliceKey.toDetailedDebugString == "InfinitySliceKey")

    // 8-byte keys.
    assert(
      identityKey(0X0316C733C33E6980L).toDetailedDebugString ==
      """SliceKey {
         |  0x0316c733c33e6980
         |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
         |  bytesSize: 8
         |  bytes: [3, 22, -57, 51, -61, 62, 105, -128]
         |  bytesPrefix: 222584256734325120
         |  bytesPrefix (hex): 316c733c33e6980
         |  bytesPrefix (bytes): [3, 22, -57, 51, -61, 62, 105, -128]
         |}""".stripMargin
    )
    assert(
      identityKey(0, 0, 0, 0, 0, 0, 0, 0).toDetailedDebugString ==
      """SliceKey {
          |  0x0000000000000000
          |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
          |  bytesSize: 8
          |  bytes: [0, 0, 0, 0, 0, 0, 0, 0]
          |  bytesPrefix: 0
          |  bytesPrefix (hex): 0
          |  bytesPrefix (bytes): [0, 0, 0, 0, 0, 0, 0, 0]
          |}""".stripMargin
    )
    assert(
      identityKey(-1, 0, 0, 0, 0, 0, 0, 1).toDetailedDebugString ==
      """SliceKey {
          |  0xff00000000000001
          |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
          |  bytesSize: 8
          |  bytes: [-1, 0, 0, 0, 0, 0, 0, 1]
          |  bytesPrefix: -72057594037927935
          |  bytesPrefix (hex): ff00000000000001
          |  bytesPrefix (bytes): [-1, 0, 0, 0, 0, 0, 0, 1]
          |}""".stripMargin
    )

    // 8-byte keys with leading zeroes.
    assert(
      identityKey(0, 0, 0, 0, 0, 42, 0, 0).toDetailedDebugString ==
      """SliceKey {
          |  0x00000000002a0000
          |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
          |  bytesSize: 8
          |  bytes: [0, 0, 0, 0, 0, 42, 0, 0]
          |  bytesPrefix: 2752512
          |  bytesPrefix (hex): 2a0000
          |  bytesPrefix (bytes): [0, 0, 0, 0, 0, 42, 0, 0]
          |}""".stripMargin
    )

    // 9-byte key with trailing zero.
    assert(
      identityKey(3, 22, -57, 51, -61, 62, 105, -128, 0).toDetailedDebugString ==
      """SliceKey {
        |  0x0316c733c33e6980\0
        |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
        |  bytesSize: 9
        |  bytes: [3, 22, -57, 51, -61, 62, 105, -128, 0]
        |  bytesPrefix: 222584256734325120
        |  bytesPrefix (hex): 316c733c33e6980
        |  bytesPrefix (bytes): [3, 22, -57, 51, -61, 62, 105, -128]
        |}""".stripMargin
    )

    // 9-byte keys with leading zeroes.
    assert(
      identityKey(0, 0, 0, 0, 0, 0, 0, 0, 0).toDetailedDebugString ==
      """SliceKey {
        |  0x0000000000000000\0
        |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
        |  bytesSize: 9
        |  bytes: [0, 0, 0, 0, 0, 0, 0, 0, 0]
        |  bytesPrefix: 0
        |  bytesPrefix (hex): 0
        |  bytesPrefix (bytes): [0, 0, 0, 0, 0, 0, 0, 0]
        |}""".stripMargin
    )
    assert(
      identityKey(0, 0, 0, 0, 0, 42, 0, 0, 0).toDetailedDebugString ==
      """SliceKey {
        |  0x00000000002a0000\0
        |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
        |  bytesSize: 9
        |  bytes: [0, 0, 0, 0, 0, 42, 0, 0, 0]
        |  bytesPrefix: 2752512
        |  bytesPrefix (hex): 2a0000
        |  bytesPrefix (bytes): [0, 0, 0, 0, 0, 42, 0, 0]
        |}""".stripMargin
    )

    // 9-byte key without trailing zero.
    assert(
      identityKey(3, 22, -57, 51, -61, 62, 105, -128, 1).toDetailedDebugString ==
      """SliceKey {
        |  \x03\x16\xC73\xC3>i\x80\x01
        |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
        |  bytesSize: 9
        |  bytes: [3, 22, -57, 51, -61, 62, 105, -128, 1]
        |  bytesPrefix: 222584256734325120
        |  bytesPrefix (hex): 316c733c33e6980
        |  bytesPrefix (bytes): [3, 22, -57, 51, -61, 62, 105, -128]
        |}""".stripMargin
    )

    // 7-byte key.
    assert(
      identityKey(3, 22, -57, 51, -61, 62, 105).toDetailedDebugString ==
      """SliceKey {
          |  \x03\x16\xC73\xC3>i
          |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
          |  bytesSize: 7
          |  bytes: [3, 22, -57, 51, -61, 62, 105]
          |  bytesPrefix: 222584256734324992
          |  bytesPrefix (hex): 316c733c33e6900
          |  bytesPrefix (bytes): [3, 22, -57, 51, -61, 62, 105, 0]
          |}""".stripMargin
    )

    // Round-trip Slice example.
    val slice = Slice(identityKey(0X0316C733C33E6980L), identityKey(0X0A9CC225AE26B8B1L))
    val roundtripSlice = SliceHelper.fromProto(slice.toProto)
    assert(
      roundtripSlice.lowInclusive.toDetailedDebugString ==
      """SliceKey {
          |  0x0316c733c33e6980
          |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
          |  bytesSize: 8
          |  bytes: [3, 22, -57, 51, -61, 62, 105, -128]
          |  bytesPrefix: 222584256734325120
          |  bytesPrefix (hex): 316c733c33e6980
          |  bytesPrefix (bytes): [3, 22, -57, 51, -61, 62, 105, -128]
          |}""".stripMargin
    )
    assert(
      roundtripSlice.highExclusive.toDetailedDebugString ==
      """SliceKey {
        |  0x0a9cc225ae26b8b1
        |  bytesClass: com.google.protobuf.ByteString$LiteralByteString
        |  bytesSize: 8
        |  bytes: [10, -100, -62, 37, -82, 38, -72, -79]
        |  bytesPrefix: 764699503837493425
        |  bytesPrefix (hex): a9cc225ae26b8b1
        |  bytesPrefix (bytes): [10, -100, -62, 37, -82, 38, -72, -79]
        |}""".stripMargin
    )
  }

  test("fingerprint builder") {
    // Test plan: Verify that the fingerprint builder produces the expected results
    // using test cases from shared proto test data.

    for (testCase <- SliceKeySuite.TEST_DATA.farmhashBuilderTestCases) {
      val description: String = testCase.description.get

      // Setup: build the key by applying operations
      var builder = SliceKey.newFingerprintBuilder()
      for (op <- testCase.operations) {
        import SliceKeyTestDataP.FarmhashBuilderTestCaseP.BuilderOperationP.Operation
        op.operation match {
          case Operation.PutLong(value) =>
            builder = builder.putLong(value)
          case Operation.PutString(value) =>
            builder = builder.putString(value)
          case Operation.PutBytes(value) =>
            builder = builder.putBytes(value)
          case Operation.Empty =>
            throw new IllegalArgumentException("Empty operation in test data")
        }
      }
      val key: SliceKey = builder.build()

      // Verify: key matches expected raw bytes
      val expectedRawBytes: ByteString = intsToBytes(testCase.expectedRawBytes)
      assertResult(
        expectedRawBytes,
        s"Builder test case '$description' should produce expected raw bytes"
      )(key.toRawBytes)
    }
  }

  test("farmhash builder golden bytes") {
    // Test plan: Verify that the fingerprint builder produces expected raw bytes for single-bytes
    // inputs using test cases from shared proto test data.

    for (testCase <- SliceKeySuite.TEST_DATA.farmhashBuilderVsDeprecatedTestCases) {
      val description: String = testCase.description.get
      val inputBytes: ByteString = intsToBytes(testCase.inputBytes)

      val keyFromBuilder: SliceKey =
        SliceKey.newFingerprintBuilder().putBytes(inputBytes).build()

      val expectedRawBytes: ByteString = intsToBytes(testCase.expectedRawBytes)
      assertResult(
        expectedRawBytes,
        s"Builder test case '$description' should produce expected raw bytes"
      )(keyFromBuilder.toRawBytes)
    }
  }

  test("fromTrustedFingerprint") {
    // Test plan: Verify that fromTrustedFingerprint correctly creates SliceKeys for
    // valid inputs (bytes <= 32) and round-trips correctly.

    for (highSliceKey: HighSliceKey <- SliceKeySuite.ORDERED_SLICE_KEYS) {
      if (highSliceKey.isFinite) {
        val original: SliceKey = highSliceKey.asFinite
        val bytes: ByteString = original.toRawBytes

        if (bytes.size() <= 32) {
          val roundtrip: SliceKey = SliceKey.fromTrustedFingerprint(bytes)

          assertResult(original, s"Round trip should succeed for $original")(roundtrip)
          assertResult(bytes, s"Bytes should match for $original")(roundtrip.toRawBytes)
        }
      }
    }
  }

  test("fromTrustedFingerprint validation") {
    // Test plan: Verify that fromTrustedFingerprint correctly rejects invalid inputs
    // (bytes > 32) using test cases from shared proto test data.

    for (testCase <- SliceKeySuite.TEST_DATA.fromTrustedFingerprintErrorTestCases) {
      val rawBytes: Array[Byte] = testCase.rawBytes.map(_.toByte).toArray
      val expectedError: String = testCase.expectedError.get

      assertThrow[IllegalArgumentException](expectedError) {
        SliceKey.fromTrustedFingerprint(ByteString.copyFrom(rawBytes))
      }
    }
  }

  test("putBytes overloads") {
    // Test plan: Verify that all putBytes overloads (ByteString, Array[Byte], ByteBuffer) produce
    // identical SliceKey results when given equivalent byte data.

    // Setup: create test data.
    val testBytes: Array[Byte] = Array[Byte](1, 2, 3, 4, 5)
    val testByteString: ByteString = ByteString.copyFrom(testBytes)
    val testByteBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(testBytes)

    // Setup: build keys with each overload.
    val keyFromByteString: SliceKey = SliceKey
      .newFingerprintBuilder()
      .putString("prefix-")
      .putBytes(testByteString)
      .build()

    val keyFromByteArray: SliceKey = SliceKey
      .newFingerprintBuilder()
      .putString("prefix-")
      .putBytes(testBytes)
      .build()

    val keyFromByteBuffer: SliceKey = SliceKey
      .newFingerprintBuilder()
      .putString("prefix-")
      .putBytes(testByteBuffer)
      .build()

    // Verify: all three overloads produce the same result.
    assert(keyFromByteString == keyFromByteArray)
    assert(keyFromByteString == keyFromByteBuffer)
    assert(keyFromByteArray == keyFromByteBuffer)

    // Verify: ByteBuffer position is not modified after putBytes.
    testByteBuffer.rewind()
    assertResult(0)(testByteBuffer.position())
    SliceKey.newFingerprintBuilder().putBytes(testByteBuffer).build()
    assertResult(0, "putBytes should not modify ByteBuffer position")(testByteBuffer.position())
  }

  test("slice key size metrics") {
    // Test plan: Verify that the metrics tracking the sizes of slice keys are correctly updated
    // when slice keys being created.

    // Gets the value of slice key byte size bucket metric whose label is "le".
    def getSliceKeySizeLe(le: Int): Int = {
      MetricUtils
        .getMetricValue(
          CollectorRegistry.defaultRegistry,
          "dicer_slice_key_byte_size_bucket",
          Map("le" -> le.toDouble.toString)
        )
        .toInt
    }

    val initialBuckets: Map[Int, Int] = Seq(7, 8, 9, 10, 16, 32, 64, 128).map { le: Int =>
      (le, getSliceKeySizeLe(le))
    }.toMap

    // 8-byte slice key, should increase buckets greater than or equal to 8.
    identityKey(1, 2, 3, 4, 5, 6, 7, 8)
    assert(getSliceKeySizeLe(7) == initialBuckets(7))
    assert(getSliceKeySizeLe(8) == initialBuckets(8) + 1)
    assert(getSliceKeySizeLe(9) == initialBuckets(9) + 1)
    assert(getSliceKeySizeLe(10) == initialBuckets(10) + 1)
    assert(getSliceKeySizeLe(16) == initialBuckets(16) + 1)

    // Another 8-byte slice key, should increase buckets greater than or equal to 8.
    identityKey(0, 0, 0, 0, 0, 0, 0, 0)
    assert(getSliceKeySizeLe(7) == initialBuckets(7))
    assert(getSliceKeySizeLe(8) == initialBuckets(8) + 2)
    assert(getSliceKeySizeLe(9) == initialBuckets(9) + 2)
    assert(getSliceKeySizeLe(10) == initialBuckets(10) + 2)
    assert(getSliceKeySizeLe(16) == initialBuckets(16) + 2)

    // 9-byte slice key, should increase buckets greater than or equal to 9.
    identityKey(0, 0, 0, 0, 0, 0, 0, 0, 0)
    assert(getSliceKeySizeLe(7) == initialBuckets(7))
    assert(getSliceKeySizeLe(8) == initialBuckets(8) + 2)
    assert(getSliceKeySizeLe(9) == initialBuckets(9) + 3)
    assert(getSliceKeySizeLe(10) == initialBuckets(10) + 3)
    assert(getSliceKeySizeLe(16) == initialBuckets(16) + 3)
    assert(getSliceKeySizeLe(128) == initialBuckets(128) + 3)

    // 42-byte slice key, should increase buckets greater or equal to than 42.
    identityKey(new Array[Byte](42))
    assert(getSliceKeySizeLe(7) == initialBuckets(7))
    assert(getSliceKeySizeLe(8) == initialBuckets(8) + 2)
    assert(getSliceKeySizeLe(9) == initialBuckets(9) + 3)
    assert(getSliceKeySizeLe(10) == initialBuckets(10) + 3)
    assert(getSliceKeySizeLe(16) == initialBuckets(16) + 3)
    assert(getSliceKeySizeLe(32) == initialBuckets(32) + 3)
    assert(getSliceKeySizeLe(64) == initialBuckets(64) + 4)
    assert(getSliceKeySizeLe(128) == initialBuckets(128) + 4)
  }
}
object SliceKeySuite {

  /** Load test data from textproto file. */
  private val TEST_DATA: SliceKeyTestDataP =
    loadTestData[SliceKeyTestDataP]("dicer/common/test/data/slice_key_test_data.textproto")

  /**
   * A sequence of [[HighSliceKey]] values, containing the minimum and maximum HighSliceKeys,
   * in the expected sorted order.
   */
  private val ORDERED_SLICE_KEYS: Vector[HighSliceKey] =
    TEST_DATA.orderedHighSliceKeys.map { highSliceKey: SliceKeyTestDataP.HighSliceKeyP =>
      highSliceKeyFromProto(highSliceKey)
    }.toVector
}
