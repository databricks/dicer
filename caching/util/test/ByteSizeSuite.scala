package com.databricks.caching.util

import com.databricks.caching.util.ByteSize.ByteSizeUnit
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class ByteSizeSuite extends DatabricksTest {

  // Constants for storage measurement prefixes.
  private val Ki: Long = 1L << 10
  private val Mi: Long = Ki << 10
  private val Gi: Long = Mi << 10
  private val Ti: Long = Gi << 10
  private val Pi: Long = Ti << 10
  private val Ei: Long = Pi << 10

  // Constants for metric measurement prefixes.
  private val K: Long = 1000
  private val M: Long = K * 1000
  private val G: Long = M * 1000
  private val T: Long = G * 1000
  private val P: Long = T * 1000
  private val E: Long = P * 1000

  test("Test ByteSize.toReadableString") {
    // Test plan: Run ByteSize.toReadableString to convert byte sizes to human-readable strings and
    // verify the result.

    // Test table contains byte counts and the expected corresponding `toReadableString` output.
    val testTable = Seq[(Number, String)](
      // Size less than 1024 should remain unchanged for the digit part.
      (128, "128 B"),
      // Size equal to 1024 should be 1 KiB.
      (Ki, "1 KiB"),
      // Size equal to 1024 should be 1 KiB.
      (Ki, "1 KiB"),
      // Size equal to 1024*1.5 should be 1.5 KiB.
      (1.5 * Ki, "1.5 KiB"),
      // Size has more than 3 digits should only display two digits.
      (18.532 * Ki, "18.53 KiB"),
      (24.891 * Ki, "24.89 KiB"),
      // Round up if the third digit of the fractional part is greater than 5.
      (24.239 * Ki, "24.24 KiB"),
      (24.296 * Ki, "24.3 KiB"),
      // Don't round up if the third digit of the fractional part is less than or equal to 5.
      (24.295 * Ki, "24.29 KiB"),
      (24.290 * Ki, "24.29 KiB"),
      // Test some MiB-magnitude inputs.
      (333 * Mi, "333 MiB"),
      (512.1234 * Mi, "512.12 MiB"),
      // Test some GiB-magnitude inputs.
      (215.4 * Gi, "215.4 GiB"),
      (246.1234 * Gi, "246.12 GiB"),
      // Should have commas for thousands separators.
      (1023.1234 * Gi, "1,023.12 GiB"),
      // Test TiB-magnitude input.
      (5.123 * Ti, "5.12 TiB"),
      // Test PiB-magnitude input.
      (5.123 * Pi, "5.12 PiB"),
      // Test EiB-magnitude input.
      (5.123 * Ei, "5.12 EiB"),
      // Size equal to 0 should returns "0 B"
      (0, "0 B"),
      // Size less than 0 should also be converted.
      (-10, "-10 B"),
      (-1228, "-1.2 KiB"),
      // Test LongMax and LongMin
      (Long.MaxValue, "8 EiB"),
      (Long.MinValue, "-8 EiB")
    )

    for (tuple <- testTable) {
      val (byteCount, expectedString): (Number, String) = tuple
      val byteSize = ByteSize(byteCount.longValue())
      assert(byteSize.toReadableString == expectedString, s"ByteSize($byteCount).toReadableString")
    }
  }

  test("Test ByteSize.toString") {
    // Test plan: Test the ByteString.toString function with a variety of inputs (signs and
    // magnitudes). Verify that the expected output is produced and that parsing the output using
    // `ByteSize.fromString` returns the original input.

    // Test table contains byte counts and the expected corresponding `toString` output.
    val testTable = Seq[(Number, String)](
      // Test special-case: 0.
      (0, "0 B"),
      // Extremes.
      (Long.MaxValue, "9223372036854775807 B"),
      (Long.MinValue, "-8 EiB"),
      // B examples (with non-zero values the 10 least significant bits).
      (1, "1 B"),
      (-1, "-1 B"),
      (42, "42 B"),
      (-42, "-42 B"),
      (Ki + 1, "1025 B"),
      (-Ki - 1, "-1025 B"),
      // KiB examples.
      (Ki, "1 KiB"),
      (-Ki, "-1 KiB"),
      (42 * Ki, "42 KiB"),
      (-42 * Ki, "-42 KiB"),
      (1.5 * Mi, "1536 KiB"),
      (-1.5 * Mi, "-1536 KiB"),
      (Mi + Ki, "1025 KiB"),
      (-Mi + Ki, "-1023 KiB"),
      (K, "1 KB"),
      (-K, "-1 KB"),
      (42 * K, "42 KB"),
      (-42 * K, "-42 KB"),
      (1.5 * M, "1500 KB"),
      (-1.5 * M, "-1500 KB"),
      (M + K, "1001 KB"),
      (-M + K, "-999 KB"),
      (125 * Ki, "125 KiB"), // 125 KiB rather than 128 KB
      (250 * Ki, "250 KiB"), // 250 KiB rather than 256 KB
      (500 * Ki, "500 KiB"), // 250 KiB rather than 512 KB
      (750 * Ki, "750 KiB"), // 250 KiB rather than 768 KB
      // MiB examples.
      (Mi, "1 MiB"),
      (-Mi, "-1 MiB"),
      (42 * Mi, "42 MiB"),
      (-42 * Mi, "-42 MiB"),
      (Gi + Mi, "1025 MiB"),
      (-Gi + Mi, "-1023 MiB"),
      (M, "1 MB"),
      (-M, "-1 MB"),
      (42 * M, "42 MB"),
      (-42 * M, "-42 MB"),
      (G + M, "1001 MB"),
      (-G + M, "-999 MB"),
      (125 * Mi, "125 MiB"), // 125 MiB rather than 128 MB
      (250 * Mi, "250 MiB"), // 250 MiB rather than 256 MB
      (500 * Mi, "500 MiB"), // 250 MiB rather than 512 MB
      (750 * Mi, "750 MiB"), // 250 MiB rather than 768 MB
      // GiB examples.
      (Gi, "1 GiB"),
      (-Gi, "-1 GiB"),
      (42 * Gi, "42 GiB"),
      (-42 * Gi, "-42 GiB"),
      (Ti + Gi, "1025 GiB"),
      (-Ti + Gi, "-1023 GiB"),
      (G, "1 GB"),
      (-G, "-1 GB"),
      (42 * G, "42 GB"),
      (-42 * G, "-42 GB"),
      (T + G, "1001 GB"),
      (-T + G, "-999 GB"),
      (125 * Gi, "125 GiB"), // 125 GiB rather than 128 GB
      (250 * Gi, "250 GiB"), // 250 GiB rather than 256 GB
      (500 * Gi, "500 GiB"), // 250 GiB rather than 512 GB
      (750 * Gi, "750 GiB"), // 250 GiB rather than 768 GB
      // TiB examples.
      (Ti, "1 TiB"),
      (-Ti, "-1 TiB"),
      (42 * Ti, "42 TiB"),
      (-42 * Ti, "-42 TiB"),
      (Pi + Ti, "1025 TiB"),
      (-Pi + Ti, "-1023 TiB"),
      (T, "1 TB"),
      (-T, "-1 TB"),
      (42 * T, "42 TB"),
      (-42 * T, "-42 TB"),
      (P + T, "1001 TB"),
      (-P + T, "-999 TB"),
      (125 * Ti, "125 TiB"), // 125 TiB rather than 128 TB
      (250 * Ti, "250 TiB"), // 250 TiB rather than 256 TB
      (500 * Ti, "500 TiB"), // 250 TiB rather than 512 TB
      (750 * Ti, "750 TiB"), // 250 TiB rather than 768 TB
      // PiB examples.
      (Pi, "1 PiB"),
      (-Pi, "-1 PiB"),
      (42 * Pi, "42 PiB"),
      (-42 * Pi, "-42 PiB"),
      (Ei + Pi, "1025 PiB"),
      (-Ei + Pi, "-1023 PiB"),
      (P, "1 PB"),
      (-P, "-1 PB"),
      (42 * P, "42 PB"),
      (-42 * P, "-42 PB"),
      (E + P, "1001 PB"),
      (-E + P, "-999 PB"),
      (125 * Pi, "125 PiB"), // 125 PiB rather than 128 PB
      (250 * Pi, "250 PiB"), // 250 PiB rather than 256 PB
      (500 * Pi, "500 PiB"), // 250 PiB rather than 512 PB
      (750 * Pi, "750 PiB"), // 250 PiB rather than 768 PB
      // EiB examples.
      (Ei, "1 EiB"),
      (-Ei, "-1 EiB"),
      (E, "1 EB"),
      (-E, "-1 EB")
    )
    for (tuple <- testTable) {
      val (byteCount, expectedString): (Number, String) = tuple
      val byteSize = ByteSize(byteCount.longValue())
      assert(byteSize.toString == expectedString, s"ByteSize($byteCount).toString")
      assert(
        ByteSize.fromString(expectedString) == byteSize,
        s"ByteSize.fromString($expectedString)"
      )
    }
  }

  test("Test ByteSize.fromString") {
    // Test plan: Test the ByteSize.fromString function with both valid and invalid inputs.
    // Verify the function returns / throws expected results.

    val ERR_FORMAT_MSG: String = "The input memory size is not in the correct format."
    val ERR_TOO_HIGH_MEMORY_MSG: String = "The input memory size must be less than 8 EiB."
    val ERR_TOO_LOW_MEMORY_MSG: String =
      "The input memory size must be greater than or equal to -8 EiB."

    // Test table containing invalid string representations of `ByteSize` and the expected error
    // messages.
    val invalidTestCases = Seq[(String, String)](
      ("20", ERR_FORMAT_MSG), // Invalid: no memory unit
      ("20 mb", ERR_FORMAT_MSG), // Invalid: lower case
      ("20 YB", ERR_FORMAT_MSG), // Invalid: YB not supported
      ("1.5 MB", ERR_FORMAT_MSG), // Invalid: fractional number
      ("8 iB", ERR_FORMAT_MSG), // Invalid: "iB" is not a correct unit
      ("8 EiB", ERR_TOO_HIGH_MEMORY_MSG), // Invalid: too high
      ("-9 EiB", ERR_TOO_LOW_MEMORY_MSG) // Invalid: too low
    )
    for (tuple <- invalidTestCases) {
      val (inputString, expectedErrMsg): (String, String) = tuple
      assertThrow[IllegalArgumentException](expectedErrMsg) {
        ByteSize.fromString(inputString)
      }
    }

    // Test table containing valid string representations of `ByteSize` and the expected
    // corresponding byte counts.
    val testTable = Seq[(String, Long)](
      ("0 KB", 0),
      ("0 KiB", 0),
      ("0 B", 0),
      ("-20 KiB", -20 * Ki),
      ("20 B", 20),
      ("12KB", 12 * K),
      ("12 KB", 12 * K),
      ("12 KiB", 12 * Ki),
      ("5MB", 5 * M),
      ("5 MB", 5 * M),
      ("5 MiB", 5 * Mi),
      ("12GB", 12 * G),
      ("12 GB", 12 * G),
      ("12 GiB", 12 * Gi),
      ("12TB", 12 * T),
      ("12 TB", 12 * T),
      ("12 TiB", 12 * Ti),
      ("12PB", 12 * P),
      ("12 PB", 12 * P),
      ("12 PiB", 12 * Pi),
      ("7EB", 7 * E),
      ("7 EB", 7 * E),
      ("7 EiB", 7 * Ei)
    )
    for (tuple <- testTable) {
      val (inputString, expectedByteCount): (String, Long) = tuple
      val byteSize = ByteSize.fromString(inputString)
      assert(byteSize.byteCount == expectedByteCount, s"ByteSize.fromString($inputString)")
    }
  }

  test("Initialization with units") {
    // Test plan: Verify that ByteSize initialized with different units creates the expected result.
    val testTable: Seq[(ByteSizeUnit, Long)] = Seq(
      (ByteSize.BYTE, 1),
      (ByteSize.KIBIBYTE, Ki),
      (ByteSize.MEBIBYTE, Mi),
      (ByteSize.GIBIBYTE, Gi),
      (ByteSize.TEBIBYTE, Ti),
      (ByteSize.PEBIBYTE, Pi),
      (ByteSize.EXBIBYTE, Ei)
    )
    for (tuple <- testTable) {
      val (unit, byteCount): (ByteSizeUnit, Long) = tuple
      assert(ByteSize(1, unit).byteCount == byteCount)
    }

    // Try some multiples also.
    assert(ByteSize(42, ByteSize.KIBIBYTE) == ByteSize(42 * Ki))
    assert(ByteSize(1024, ByteSize.MEBIBYTE) == ByteSize(Gi))
    assert(ByteSize(2000, ByteSize.BYTE) == ByteSize(2000))
  }

  test("ByteSize arithmetic operations") {
    // Test plan: Verify that ByteSize can correctly perform arithmetic calculations ("+" and "-").
    // Verify this by performing arithmetic calculation for different ByteSizes and expressions.
    // Verify either the correct results are calculated, or the proper exceptions are thrown.

    assert(ByteSize(1) + ByteSize(1) == ByteSize(2))
    assert(ByteSize(1) - ByteSize(1) == ByteSize(0))
    assert(ByteSize(1) + ByteSize(-1) == ByteSize(0))
    assert(ByteSize(1) - ByteSize(2) == ByteSize(-1))
    assert(ByteSize(1) + ByteSize(2) + ByteSize(3) == ByteSize(6))
    assert(ByteSize(1) - ByteSize(2) + ByteSize(3) == ByteSize(2))
    assert(ByteSize(Long.MaxValue) - ByteSize(Long.MaxValue) == ByteSize(0))
    assert(ByteSize(Long.MaxValue) + ByteSize(Long.MinValue) == ByteSize(-1))

    assert(
      ByteSize.fromString("1 KB") + ByteSize.fromString("2 KB") ==
      ByteSize.fromString("3 KB")
    )
    assert(
      ByteSize.fromString("1 KB") + ByteSize.fromString("1 MB") ==
      ByteSize.fromString("1001 KB")
    )
    assert(
      ByteSize.fromString("1 GiB") - ByteSize.fromString("1 MiB") ==
      ByteSize.fromString("1023 MiB")
    )
    assert(
      ByteSize.fromString("-1 GiB") + ByteSize.fromString("1025 MiB") ==
      ByteSize.fromString("1 MiB")
    )

    assertThrow[ArithmeticException]("overflow") {
      ByteSize(Long.MaxValue) + ByteSize(1)
    }
    assertThrow[ArithmeticException]("overflow") {
      ByteSize(Long.MinValue) - ByteSize(1)
    }
    assertThrow[ArithmeticException]("overflow") {
      ByteSize.fromString("7 EiB") + ByteSize.fromString("7 EiB")
    }
  }

  test("ByteSize ordering") {
    // Verify that ByteSize correctly implements [[Ordered]]. Verify this by calling `sorted` on
    // a sequence of ByteSizes, and verifying the result is correctly sorted.

    // An unsorted list of ByteSizes.
    val byteSizes: Seq[ByteSize] =
      Seq(
        "0 KB",
        "0 KiB",
        "0 B",
        "-20 KiB",
        "20 B",
        "12KB",
        "12 KB",
        "12 KiB",
        "5 KiB",
        "5MB",
        "-12GB",
        "12 GB",
        "12 GiB",
        "7EB",
        "12TB",
        "12 TB",
        "5 MB",
        "5 MiB",
        "12 TiB",
        "12PB",
        "12 PB",
        "12 PiB",
        "-7 EB",
        "7 EiB"
      ).map(ByteSize.fromString)

    // The sorted result of ByteSizes should be equal to the sorted result of their raw bytes count.
    val actualRawByteCounts: Seq[Long] =
      byteSizes.sorted.map { case ByteSize(bytes: Long) => bytes }
    val expectedRawByteCounts: Seq[Long] =
      byteSizes.map { case ByteSize(bytes: Long) => bytes }.sorted
    assert(actualRawByteCounts == expectedRawByteCounts)
  }

  test("isPositive and isNonNegative") {
    // Test plan: Verify that `ByteSize.isPositive` and `ByteSize.isNonNegative` return the expected
    // result for different values.
    assert(ByteSize(1).isPositive)
    assert(ByteSize(1).isNonNegative)
    assert(ByteSize(8, ByteSize.GIBIBYTE).isPositive)
    assert(ByteSize(8, ByteSize.GIBIBYTE).isNonNegative)
    assert(!ByteSize(0).isPositive)
    assert(ByteSize(0).isNonNegative)
    assert(!ByteSize(-1).isPositive)
    assert(!ByteSize(-1).isNonNegative)
    assert(!ByteSize(-1, ByteSize.TEBIBYTE).isPositive)
    assert(!ByteSize(-1, ByteSize.TEBIBYTE).isNonNegative)
  }
}
