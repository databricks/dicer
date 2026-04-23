package com.databricks.caching.util

import com.databricks.backend.common.reflection.ReflectionUtils

import java.net.URI
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.{ClassSymbol, Mirror, Symbol, TypeTag, runtimeMirror, typeOf}
import scala.util.Random
import io.grpc.Status
import scalapb.{
  GeneratedMessage,
  GeneratedMessageCompanion,
  Message,
  TextFormat,
  TextFormatError
}
import org.scalactic.source.Position
import org.scalatest
import org.scalatest.exceptions.TestFailedException
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{Args, Assertions, BeforeAndAfterEachTestData, Suite, Tag, TestData}

// A grab-bag of helper utilities that will be moved to name-specific helper utility files as
// utilities of similar nature are added.

object TestUtils {

  private val logger = PrefixLogger.create(getClass, "")

  /**
   * Helper reading test data from a textproto format data dependency.
   *
   * To use, add a data dependency to the build target, e.g., `data = ["//dicer/common/test/data"]`,
   * and read a particular input file by specifying the universe relative path, e.g.,:
   *
   * ```
   * val generationTestData: GenerationTestDataP =
   *     TestUtils.loadTestData("dicer/common/test/data/generation_test_data.textproto")
   * ```
   */
  def loadTestData[T <: GeneratedMessage with Message[T]](universeRelativePath: String)(
      implicit companion: GeneratedMessageCompanion[T]): T = {
    val path = Paths.get(sys.env("TEST_SRCDIR"), sys.env("TEST_WORKSPACE"), universeRelativePath)
    val contents = Files.readAllLines(path).asScala.mkString("\n")
    val result: Either[TextFormatError, T] = TextFormat.fromAscii(companion, contents)
    if (result.isLeft) {
      throw new RuntimeException(s"Failed to parse test data at $path: ${result.left.get}")
    }
    result.right.get
  }

  /** Generate a URI for localhost with the given port. */
  def getLocalhostURI(port: Int): URI = URI.create(s"https://localhost:$port")

  /** A trait that can be inherited by any test suite to get the current test name. */
  trait TestName extends Suite with BeforeAndAfterEachTestData {
    private var testName: String = ""

    override def beforeEach(testData: TestData): Unit = {
      testName = testData.name
      super.beforeEach(testData)
    }

    abstract override def runTest(testName: String, args: Args): scalatest.Status = {
      super.runTest(testName, args)
    }

    /**
     * Returns [[getSuffixedSafeName()]] with an empty suffix.
     *
     * See [[getSuffixedSafeName()]] for details on the format and escaping rules.
     */
    def getSafeName: String = getSuffixedSafeName(suffix = "")

    /**
     * Returns a test name in a format that can be safely used as a Dicer `AppTarget`. Useful for
     * creating unique identifiers in tests. Returned string is guaranteed to be 42 characters or
     * less.
     *
     * Format: `{escaped_test_name}-{class_hash}-{test_hash}`
     *
     * Where:
     *  - `escaped_test_name`: Test name with special characters replaced by '-' or '0'
     *  - `class_hash`: 4-character hex hash of the class name (e.g., "AB12")
     *  - `test_hash`: 4-character hex hash of the test name (e.g., "CD34")
     *
     * Character escaping rules:
     *  - Converts to lowercase
     *  - Replaces non-alphanumeric characters with '-' (dash)
     *  - Exception: First characters use 'a' instead of '-' (DNS label requirement)
     *  - Exception: Last characters use '0' instead of '-' (DNS label requirement)
     *  - Exception: No consecutive '-' characters.
     */
    def getSafeAppTargetName: String = transformToSafeAppTargetName(getSafeName)

    /**
     * Returns the given string `s` transformed into a format that can be safely used as a Dicer
     * `AppTarget`. See [[getSafeAppTargetName]] for details.
     */
    def transformToSafeAppTargetName(s: String): String = {
      val safeName: String =
        getSuffixedSafeNameInternal(s, suffix = "", maxLength = 42).replaceAll("-{2,}", "-")
      // App target names must start with [a-z], so if the first character of `safeName` is not
      // [a-z] after `getSuffixedSafeNameInternal`, then remove it and prepend an "a" for the return
      // value. Note: the first character of the string returned from `getSuffixedSafeNameInternal`
      // is guaranteed to be `[0-9a-z]`.
      if (safeName.nonEmpty && safeName.head.isDigit) "a" + safeName.tail else safeName
    }

    /**
     * REQUIRES: `suffix` must be 32 characters or less.
     *
     * Returns a test name in a format that can be safely used as a DNS label, file name, or Dicer
     * `Target`. Useful for creating unique identifiers in tests. Returned string is guaranteed to
     * be 63 characters or less.
     *
     * Format: `{escaped_test_name}-{class_hash}-{test_hash}-{escaped_suffix}`
     *
     * Where:
     *  - `escaped_test_name`: Test name with special characters replaced by '-' or '0'
     *  - `class_hash`: 4-character hex hash of the class name (e.g., "AB12")
     *  - `test_hash`: 4-character hex hash of the test name (e.g., "CD34")
     *  - `escaped_suffix`: Escaped using the same rules as the test name
     *
     * Character escaping rules:
     *  - Converts to lowercase
     *  - Replaces non-alphanumeric characters with '-' (dash)
     *  - Exception: First and last characters use '0' instead of '-' (DNS label requirement)
     *  - Collapses runs of multiple '-' to a single '-' (gridTest names contain a space followed by
     *    a parenthesis, which would otherwise result in multiple consecutive hyphens)
     *
     * If the test name would cause the total length to exceed 63 characters, it is truncated to fit
     * within the limit.
     *
     * Example:
     *  - `getSuffixedSafeName("v2")` for test "My Test!" → `my-test0-ab12-cd34-v2`
     *
     * @param suffix Suffix to append (omitted if empty). Must be 32 characters or less.
     * @return Safe name with hash codes, max 63 characters
     */
    def getSuffixedSafeName(suffix: String): String = {
      getSuffixedSafeNameInternal(testName, suffix, maxLength = 63)
    }

    /**
     * Return the set of test names in this suite, converted to the format returned by
     * [[getSafeName]]. This can be used when it is necessary to pre-register all values of
     * [[getSafeName]] that will be used across the tests in a suite. Note that the list of tests
     * is only accessible after the suite constructor completes, so this should only be called after
     * that, e.g. in `beforeAll`.
     */
    def getAllSafeNames: Set[String] = {
      testNames.map { testName: String =>
        // Default to a `maxLength` of 63 which is compliant with the requirements for Dicer
        // `Target` names.
        getSuffixedSafeNameInternal(testName, suffix = "", maxLength = 63)
      }
    }

    /**
     * Converts a string into a format safe for use as a DNS label, file name, or identifier.
     *
     * Transformation rules:
     * - Converts all letters to lowercase
     * - Preserves ASCII alphanumeric characters (a-z, A-Z, 0-9)
     * - Replaces all other characters with '-' (dash)
     * - Special case: First and last characters use '0' instead of '-' for non-alphanumeric
     *   characters (DNS label requirement - labels cannot start or end with a dash)
     * - Truncates the result to a maximum of 63 characters
     *
     * Examples:
     * - "HelloWorld" → "helloworld"
     * - "test-name" → "test-name"
     * - "My Test!" → "my-test0" (exclamation at end becomes '0')
     * - "_test_" → "0test0" (underscores at start/end become '0')
     * - "a@b#c" → "a-b-c"
     *
     * @param unescapedName The string to escape. Must be non-empty.
     * @return The escaped string, guaranteed to be 63 characters or less
     */
    @throws[AssertionError]("If unescapedName is empty.")
    def escapeName(unescapedName: String): String = {
      assert(unescapedName.nonEmpty, "Test name is not set")
      val nameBuilder = new mutable.StringBuilder()
      val length = unescapedName.length.min(63) // truncate to 63 characters
      for (i <- 0 until length) {
        val c: Char = unescapedName(i)

        // Note that we manually check that characters are within the ASCII range, since the
        // RichChar helpers (e.g., `isLetter`) match unicode letters as well.
        if (c >= 'a' && c <= 'z') nameBuilder.append(c.toLower)
        else if (c >= 'A' && c <= 'Z') nameBuilder.append(c.toLower)
        else if (c >= '0' && c <= '9') nameBuilder.append(c)
        else if (i == 0 || i == length - 1) nameBuilder.append('0')
        else nameBuilder.append('-')
      }
      nameBuilder.toString()
    }

    /**
     * REQUIRES: `suffix` must be 32 characters or less.
     * REQUIRES: `maxLength` must be at least 31.
     *
     * Internal implementation for [[getSuffixedSafeName()]].
     *
     * @param maxLength the maximum length of the return value.
     */
    private def getSuffixedSafeNameInternal(
        testName: String,
        suffix: String,
        maxLength: Int): String = {
      // Limit the suffix to `maxLength - 31` characters, since the whole name must be `maxLength`
      // characters or less, we need to add a 10-character hash part at the end
      // ("-{classHash}-{testHash}"), and we want at least some of the test name to be present.
      require(
        suffix.length <= maxLength - 31,
        s"Suffix $suffix must be ${maxLength - 31} characters or less"
      )

      // Escape the suffix to ensure it follows the same rules
      val escapedSuffix: String = if (suffix.nonEmpty) escapeName(suffix) else ""

      // Get the 4-character hash codes for the class and test names. Note that we get a linter
      // error when using this.getClass.getSimpleName directly, so we use this (recommended by the
      // linter) instead.
      val classHash: String = getHash4Chars(ReflectionUtils.getSimpleName(this.getClass()))
      val testHash: String = getHash4Chars(testName)

      // The hash part is "-{classHash}-{testHash}" which is 10 characters (1 + 4 + 1 + 4)
      val hashPart: String = s"-${classHash}-${testHash}"

      // Build the final suffix. Since suffix is guaranteed <= 32 chars and hash part is 10 chars,
      // the total suffix (including dash) will be at most 43 chars, which is always < 63
      val optionalSuffix: String = if (escapedSuffix.nonEmpty) {
        s"-${escapedSuffix}"
      } else {
        ""
      }
      val finalSuffix: String = hashPart + optionalSuffix

      val maxTestNameLength: Int = maxLength - finalSuffix.length
      val escapedTestName: String = escapeName(testName)

      // Truncate the escaped test name if needed to fit within the limit
      val finalName: String = if (escapedTestName.length > maxTestNameLength) {
        escapedTestName.take(Math.max(0, maxTestNameLength))
      } else {
        escapedTestName
      }

      (finalName + finalSuffix).toLowerCase.replaceAll("-{2,}", "-")
    }

    /** Generates a 4-character hash code from a string, formatted as lowercase hexadecimal. */
    private def getHash4Chars(str: String): String = {
      val hash: Int = str.hashCode()
      // Take the lower 16 bits (2 bytes) to get a 4-character hex string
      val hashLower16Bits: Int = hash & 0xFFFF
      f"$hashLower16Bits%04x"
    }
  }

  /**
   * A mixin supporting parameterized tests which decorates test names with a set of provided
   * parameters to disambiguate multiple runs of the same test. This is useful for parameterized
   * test suites, where an entire suite is repeated for multiple sets of parameters.
   */
  trait ParameterizedTestNameDecorator extends AnyFunSuite {

    /**
     * A map of param name to value for the suite. Each test run within this suite instance will
     * have a human-readable interpretation of these params appended to each test name.
     */
    def paramsForDebug: Map[String, Any]

    override def test(testName: String, testTags: Tag*)(testFun: => Any)(
        implicit pos: Position): Unit = {
      super.test(decorateTestName(testName), testTags: _*)(testFun)
    }

    private def decorateTestName(testName: String): String = {
      val paramsString = paramsForDebug.map { case (k, v) => s"[$k=$v]" }.mkString("")
      // Put the params before the base test name for better interaction with
      // `TestName.getSafeName`, which truncates inputs beyond 63 characters. If the params were
      // instead a suffix to the test name, the params could be entirely cut off, leading
      // `getSafeName` to return the same string for different test runs with different params.
      s"($paramsString) $testName"
    }
  }

  /**
   * Per [[Status]].equals docs, equality is not well-defined and the implementation tests
   * referential equality, but for our purposes we need to check that the fields match.
   */
  def assertStatusEqual(s1: Status, s2: Status): Unit = {
    assert(s1.getCode == s2.getCode, s"${s1.getCode} == ${s2.getCode}")
    assert(s1.getDescription == s2.getDescription, s"${s1.getDescription} == ${s2.getDescription}")
    assert(s1.getCause == s2.getCause, s"${s1.getCause} == ${s2.getCause}")
  }

  /**
   * Same specs as `assertThrows`. Expects the exception to contain `expectedErrorMsg`. Returns the
   * matching exception for further validation.
   */
  def assertThrow[T <: Throwable](expectedErrorMsg: String)(
      f: => Any)(implicit classTag: ClassTag[T], pos: Position): T = {
    try {
      val exception: T = Assertions.intercept[T](f)(classTag, pos)
      assert(
        Option(exception.getMessage).exists(_.contains(expectedErrorMsg)),
        s"Expected error message missing: $expectedErrorMsg, Received: $exception"
      )
      exception
    } catch {
      case e: TestFailedException =>
        logger.info(s"Expected exception $classTag not thrown: $expectedErrorMsg\n$e")
        throw e
    }
  }

  /**
   * REQUIRES: `sorted` is sorted in increasing order with distinct elements.
   *
   * Checks operators (<, <=, etc) for different pairs of elements (including self) and asserts
   * that the operators return the expected answers.
   */
  def checkComparisons[T <: Ordered[T]](sorted: IndexedSeq[T]): Unit = {
    // For each compare with itself and every other element.
    for (i <- sorted.indices) {
      val iElement = sorted(i)

      assert(iElement == iElement, s"$iElement == $iElement")
      assert(iElement <= iElement, s"$iElement <= $iElement")
      assert(iElement >= iElement, s"$iElement >= $iElement")
      assert(iElement.equals(iElement), s"${iElement}.equals($iElement)")

      assert(!(iElement != iElement), s"!($iElement != $iElement)")
      assert(!(iElement < iElement), s"!($iElement < $iElement)")
      assert(!(iElement > iElement), s"!($iElement > $iElement)")
      for (j <- i + 1 until sorted.length) {
        val jElement = sorted(j)
        // Compare the ith element with the jth element and ensure that all the operators state that
        // the ith element is strictly less than the jth element.
        assert(iElement != jElement, s"$iElement != $jElement")
        assert(iElement < jElement, s"$iElement < $jElement")
        assert(iElement <= jElement, s"$iElement <= $jElement")

        // True calls on jElement.
        assert(jElement > iElement, s"$jElement > $iElement")
        assert(jElement >= iElement, s"$jElement >= $iElement")
        assert(jElement != iElement, s"$jElement != $iElement")

        // Check for false statements.
        assert(!(iElement == jElement), s"!($iElement == $jElement)")
        assert(!iElement.equals(jElement), s"!${iElement}.equals($jElement)")
        assert(!(iElement > jElement), s"!($iElement > $jElement)")
        assert(!(iElement >= jElement), s"!($iElement >= $jElement)")

        // False calls on jElement.
        assert(!(jElement < iElement), s"!($jElement < $iElement)")
        assert(!(jElement <= iElement), s"!($jElement <= $iElement)")
        assert(!(jElement == iElement), s"!($jElement == $iElement)")
        assert(!jElement.equals(iElement), s"!${jElement}.equals($iElement)")
      }
    }
  }

  /**
   * REQUIRES: `sorted` is sorted in increasing order with distinct elements.
   *
   * Checks `comparer` for different pairs of elements (including self) and asserts that it returns
   * a value with the expected sign (consistent with the comparison of the corresponding indices).
   */
  def checkComparer[T](sorted: IndexedSeq[T], comparer: (T, T) => Int): Unit = {
    for (i <- sorted.indices) {
      for (j <- sorted.indices) {
        // Since the inputs are sorted and unique, the value comparison should agree with the index
        // comparison.
        val actual: Int = comparer(sorted(i), sorted(j))
        val expected: Int = i.compareTo(j)
        assert(actual.signum == expected.signum, s"$actual.signum == $expected.signum")
      }
    }
  }

  /**
   * Checks the implementations of `equals` and `hashCode` against the given equality "groups",
   * where each group contains equivalent instances:
   *
   *  - For all pairs of elements withing a single group, `equals` returns true and `hashCode`
   *    returns the same value.
   *  - For all pairs of elements across groups, `equals` returns false. Hash code collisions are
   *    technically permitted, but we require the number of distinct hash codes to be at at least
   *    90% the number of groups.
   *  - Finally, tests comparisons with null and with some mismatched types.
   */
  def checkEquality(groups: Seq[Seq[_]]): Unit = {
    // Check equality of elements within each group and collect distinct hash codes.
    val hashCodes = mutable.Set[Int]()
    for (group: Seq[Any] <- groups) {
      for (x: Any <- group) {
        hashCodes.add(x.hashCode())
        for (y: Any <- group) {
          assert(x == y, s"$x == $y")
          assert(x.equals(y), s"${x}.equals($y)")
          assert(x.hashCode() == y.hashCode(), s"${x}.hashCode() == ${y}.hashCode()")
        }
      }
    }
    assert(
      hashCodes.size.toDouble / groups.size.toDouble >= 0.9,
      "insufficient distinct hash codes"
    )

    // Check inequality of elements across groups.
    for (tuple1 <- groups.zipWithIndex) {
      val (xs, i): (Seq[Any], Int) = tuple1
      for (tuple2 <- groups.zipWithIndex) {
        val (ys, j): (Seq[Any], Int) = tuple2
        if (i != j) {
          for (x: Any <- xs) {
            for (y: Any <- ys) {
              assert(x != y, s"$x != $y")
              assert(!x.equals(y), s"!${x}.equals($y)")
            }
          }
        }
      }
    }

    // Check inequality with null and with a different type.
    object DifferentTypeInstance
    for (xs: Seq[Any] <- groups) {
      for (x: Any <- xs) {
        assert(!x.equals(null), s"!${x}.equals(null)")
        assert(x != null, s"$x != null")
        assert(null != x, s"null != $x")
        assert(!x.equals(DifferentTypeInstance), s"!${x}.equals(DifferentTypeInstance)")
        assert(x != DifferentTypeInstance, s"$x != DifferentTypeInstance")
        assert(DifferentTypeInstance != x, s"DifferentTypeInstance != $x")
      }
    }
  }

  /**
   * Returns a new [[Random]] instance, logging the used seed for repeatability. Uses `seedOpt` if
   * present, otherwise randomly generates a seed.
   */
  def newRandomWithLoggedSeed(seedOpt: Option[Long] = None): Random = {
    val seed: Long = seedOpt.getOrElse(new Random().nextLong())
    logger.info(s"Random seed: $seed")
    new Random(seed)
  }

  /**
   * Convenience to convert `duration` to a [[FiniteDuration]], throwing an [[AssertionError]] if
   * it's not finite.
   */
  def asFiniteDuration(duration: Duration): FiniteDuration = {
    duration match {
      case finite: FiniteDuration => finite
      case _ => throw new AssertionError(s"Duration was non-finite: $duration")
    }
  }

  /**
   * Asserts that two double values are approximately equal within the given tolerance.
   * This is useful for floating point comparisons in tests.
   */
  def assertApproxEqual(actual: Double, expected: Double, tolerance: Double = 0.0001): Unit = {
    assert(
      math.abs(actual - expected) < tolerance,
      s"""Expected $actual to be within $tolerance of $expected, but difference
        was ${math.abs(actual - expected)}"""
    )
  }

  /**
   * Blocks the calling thread briefly to allow time for a potential non-event to manifest, thereby
   * confirming that a specific event does not occur in a test. This method exists only as a last
   * resort. Its use should raise immediate red flags during code review. Any test that blocks
   * waiting for a “non-event” is inherently fragile, nondeterministic, and conceptually suspect.
   * You should assume this method is the wrong tool unless every other option has been exhausted
   * and the rationale is both compelling and well-documented.
   *
   * Before invoking it, test authors must first explore deterministic alternatives. For example,
   * many asynchronous behaviors stem from decisions made by lower-level, passive components
   * (see <internal link>), where time can be explicitly controlled and events be
   * explicitly observed in a test.
   */
  def shamefullyAwaitForNonEventInAsyncTest(): Unit = {
    Thread.sleep(200)
  }

  /**
   * Helper function to block on the result of a [[scala.concurrent.Future]] in tests.
   *
   * This is acceptable in tests because tests are single-threaded and don't need async benefits
   * and because blocking allows for simpler and more readable test code.
   *
   * @param awaitable The [[scala.concurrent.Awaitable]] to block on
   * @param timeout The maximum time to wait for the result
   * @tparam T The result type
   * @return The result of the awaitable
   */
  @SuppressWarnings(
    Array("AwaitError", "AwaitWarning", "reason:blocking is acceptable in tests")
  )
  def awaitResult[T](awaitable: scala.concurrent.Awaitable[T], timeout: Duration): T = {
    scala.concurrent.Await.result(awaitable, timeout)
  }

  /**
   * Helper function to block on a [[scala.concurrent.Future]] until it completes in tests.
   *
   * See [[awaitResult]] for more details on why blocking is acceptable in tests.
   *
   * @param awaitable The [[scala.concurrent.Awaitable]] to block on
   * @param timeout The maximum time to wait for completion
   * @tparam T The result type
   * @return The awaitable after it has completed
   */
  @SuppressWarnings(
    Array("AwaitError", "AwaitWarning", "reason:blocking is acceptable in tests")
  )
  def awaitReady[T](awaitable: scala.concurrent.Awaitable[T], timeout: Duration): awaitable.type = {
    scala.concurrent.Await.ready(awaitable, timeout)
  }

  /** Returns all variants of an enum-like sealed trait `T`. */
  @throws[IllegalArgumentException]("if T is not a sealed trait")
  @throws[IllegalArgumentException]("if T has no known subclasses")
  def getAllVariantsOfEnumLikeTrait[T: TypeTag]: Set[T] = {
    val classSymbol: ClassSymbol = typeOf[T].typeSymbol.asClass
    require(classSymbol.isSealed, "Not a sealed trait")

    val subclasses: Set[Symbol] = classSymbol.knownDirectSubclasses
    require(subclasses.nonEmpty, "No known direct subclasses")

    val mirror: Mirror = runtimeMirror(getClass.getClassLoader)
    subclasses.map { subclass: Symbol =>
      mirror.reflectModule(subclass.asClass.module.asModule).instance.asInstanceOf[T]
    }
  }

  /**
   * Throws an [[AssertionError]] when `condition` is false. Intentionally masks
   * [[scala.Predef.assert()]] to ensure that all errors include a debug string, and preferred to
   * the [[Assertions.assert()]] macro for performance reasons (particularly in the CPU intensive
   * [[checkComparer()]], [[checkComparer()]], and [[checkEquality()]] permutation tests).
   */
  private def assert(condition: Boolean, debugString: => String): Unit = {
    if (!condition) {
      throw new AssertionError(debugString)
    }
  }
}
