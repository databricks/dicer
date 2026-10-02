package com.databricks.dicer.common

import scala.collection.immutable
import scala.util.Random

import com.databricks.dicer.common.testing.SliceTestUtils.{LowInclusiveLongFluent, fp, toSliceKey}
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{assertThrow, checkEquality}
import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.test.SliceSetTestDataP
import com.databricks.dicer.common.test.SliceSetTestDataP.CanonicalizationTestCaseP

class SliceSetSuite extends DatabricksTest {
  private val TEST_DATA: SliceSetTestDataP = TestUtils.loadTestData[SliceSetTestDataP](
    "dicer/common/test/data/slice_set_test_data.textproto"
  )

  test("construction throws when not ordered") {
    // Test plan: Verify that an exception is thrown when constructing a SliceSet with unordered
    // Slices.
    val incorrectFirstSlice = Slice(10, 20)
    val incorrectLastSlice = Slice(0, 9)
    assertThrow[IllegalArgumentException](
      s"$incorrectLastSlice is not strictly after $incorrectFirstSlice"
    ) {
      // Need to use "new" because the apply methods sort them.
      new SliceSetImpl(Vector(incorrectFirstSlice, incorrectLastSlice))
    }
  }

  test("construction throws when connected") {
    // Test plan: Verify that an exception is thrown when constructing a SliceSet with connected
    // Slices.
    val incorrectFirstSlice = Slice(0, 10)
    val incorrectLastSlice = Slice(10, 20)
    assertThrow[IllegalArgumentException](
      s"$incorrectLastSlice is not strictly after $incorrectFirstSlice"
    ) {
      // Need to use "new" because the apply methods coalesce them.
      new SliceSetImpl(Vector(incorrectFirstSlice, incorrectLastSlice))
    }
  }

  test("Contains") {
    // Test plan: create a SliceSetImpl and verify that it contains the expected keys.
    val set = SliceSetImpl(0 -- 10, 20 -- 40, 45 -- 65)
    assert(set.contains(0))
    assert(set.contains(4))
    assert(set.contains(9))
    assert(!set.contains(10))
    assert(!set.contains(11))
    assert(set.contains(20))
    assert(set.contains(29))
    assert(set.contains(30))
    assert(set.contains(39))
    assert(!set.contains(40))
    assert(!set.contains(44))
    assert(set.contains(45))
    assert(set.contains(64))
    assert(!set.contains(65))
  }

  test("Canonicalization") {
    // Test plan: create `SliceSetImpl`s with overlapping, disordered, contiguous, and repeating
    // input Slices. Verify that the constructed SliceSet has the expected "canonical" Slices
    // (ordered and coalesced). This behavior is also covered by other tests cases: randomized
    // tests, canonical equality test, and by the internal `SliceSetImpl.checkSlices()` that is
    // called whenever a set is constructed.

    for (testCase: CanonicalizationTestCaseP <- TEST_DATA.canonicalizationTestCases) {
      val inputSlices: Seq[Slice] = testCase.inputSlices.map(SliceHelper.fromProto)
      val expectedSlices: Seq[Slice] = testCase.expectedOutputSlices.map(SliceHelper.fromProto)
      val set = SliceSetImpl(inputSlices: _*)
      assert(set.slices == expectedSlices, s"Unexpected output for ${testCase.description}")
    }
  }

  /** Empty sets, constructed in various ways.. */
  private val emptySets = {
    immutable.Seq(SliceSetImpl.empty, SliceSetImpl(), SliceSetImpl.newBuilder.build())
  }

  /** Some sample Slice keys, in order. */
  private val orderedSliceKeys = {
    immutable.Seq[SliceKey]("", 0, 1, 42, 10, 20, "Fili", fp("Fili"), "Kili", fp("Kili")).sorted
  }

  test("Contains empty") {
    // Test plan: verify that an empty SliceSet contains no keys.
    for (emptySet: SliceSetImpl <- emptySets) {
      assert(emptySet.isEmpty)
      assert(!emptySet.nonEmpty)
      assert(emptySet.slices.isEmpty)
      for (key: SliceKey <- orderedSliceKeys) {
        assert(!emptySet.contains(key))
      }
    }
  }

  test("Builder negative") {
    // Test plan: supply Slices to the builder out of order and verify that an
    // IllegalExceptionArgument is thrown.
    val builder: SliceSetImplBuilder = SliceSetImpl.newBuilder.add(10 -- 20)
    assertThrow[IllegalArgumentException]("Slices must be added to the builder in order") {
      builder.add(0 -- 5)
    }
    assertThrow[IllegalArgumentException]("Slices must be added to the builder in order") {
      builder.add(5 -- 10)
    }
    assertThrow[IllegalArgumentException]("Slices must be added to the builder in order") {
      builder.add(10 -- 15)
    }
    // .. but adding Slices that are equivalent or that sort after is fine.
    val set: SliceSetImpl = builder.add(10 -- 20).add(10 -- 21).build()
    assert(set.slices == Vector(10 -- 21))
  }

  test("Builder reuse") {
    // Test plan: verify that builders are reset after `build()` is called (rather than throwing or
    // returning bogus results on reuse).
    val builder: SliceSetImplBuilder = SliceSetImpl.newBuilder

    builder.add(10 -- 20)
    builder.add(30 -- 40)
    assert(builder.build().slices == Vector(10 -- 20, 30 -- 40))
    builder.add(35 -- 45)
    assert(builder.build().slices == Vector(35 -- 45))
  }

  test("Canonical equality") {
    // Test plan: construct equivalence groups including alternate ways of constructing the same
    // sets and test equals/hashCode.

    val equalityGroups: Seq[Seq[SliceSetImpl]] = Seq(
      emptySets,
      // Sets that contain equivalent coalesced slices (contiguous breakdowns are different).
      Seq(
        SliceSetImpl(10 -- 40, 50 -- 60), // canonical
        SliceSetImpl(10 -- 20, 20 -- 30, 30 -- 40, 50 -- 60),
        SliceSetImpl(10 -- 25, 25 -- 40, 50 -- 60)
      ),
      // Sets the contain equivalent coalesced slices (made up of different overlapping slices).
      Seq(
        SliceSetImpl(10 -- 30, 50 -- 60), // canonical
        SliceSetImpl(10 -- 25, 15 -- 30, 50 -- 55, 51 -- 60),
        SliceSetImpl(10 -- 25, 10 -- 30, 50 -- 55, 50 -- 60)
      )
    )
    checkEquality(equalityGroups)
  }

  /**
   * Some sample upper bound Slice keys, in order. First n-1 entries are the same as
   * orderedSliceKeys.
   */
  private val orderedHighSliceKeys: immutable.Seq[HighSliceKey] = {
    orderedSliceKeys ++ Seq(InfinitySliceKey)
  }

  /**
   * Creates a random Slice with lower bound from [[orderedSliceKeys]] and upper bound from
   * [[orderedHighSliceKeys]].
   */
  private def createRandomSlice(rng: Random): Slice = {
    val lowIndex = rng.nextInt(orderedSliceKeys.size)
    val lowInclusive: SliceKey = orderedSliceKeys(lowIndex)

    // Ensure non-empty by picking a higher high exclusive key.
    val highIndex = rng.nextInt(orderedHighSliceKeys.size - lowIndex - 1) + lowIndex + 1
    val highExclusive: HighSliceKey = orderedHighSliceKeys(highIndex)
    Slice(lowInclusive, highExclusive)
  }

  /**
   * Creates a set initialized with `n` randomly created Slices. Note: the resulting set may have
   * fewer than n Slices since the set is in canonical form.
   */
  private def createRandomSet(rng: Random, n: Int): (SliceSetImpl, immutable.Vector[Slice]) = {
    val builder = immutable.Vector.newBuilder[Slice]
    for (_ <- 0 until n) {
      builder += createRandomSlice(rng)
    }
    val slices: immutable.Vector[Slice] = builder.result()
    (SliceSetImpl(slices), slices)
  }

  test("Randomized build and contains test") {
    // Test plan: create random sets from random Slices, and verify that containment checks work as
    // expected given the random input Slices.
    val rng = new Random
    for (_ <- 0 until 1000) {
      val (set, slices): (SliceSetImpl, immutable.Vector[Slice]) =
        createRandomSet(rng, n = rng.nextInt(5))
      for (key: SliceKey <- orderedSliceKeys) {
        // We expect `contains` to return true if any of the input Slices contained the given key.
        val expected = slices.exists { slice: Slice =>
          slice.contains(key)
        }
        val actual = set.contains(key)
        assert(actual == expected, s"set=$set, slices=$slices, key=$key")
      }
    }
  }

  test("Diff") {
    // Test plan: compute the diff between two sets and verify that diffs reflect expectations.
    // Using the example illustrated in the `SliceSet.diff` docs.
    val left = SliceSetImpl(0 -- 10, 20 -- 50)
    val right = SliceSetImpl(0 -- 5, 12 -- 30, 40 -- 50)
    val expectedLeftOnly = SliceSetImpl(5 -- 10, 30 -- 40)
    val expectedRightOnly = SliceSetImpl(12 -- 20)
    val (actualLeftOnly, actualRightOnly) = SliceSetImpl.diff(left, right)
    assert(actualLeftOnly == expectedLeftOnly)
    assert(actualRightOnly == expectedRightOnly)
  }

  test("Randomized Diff") {
    // Test plan: create pairs of random sets out of random Slices. Verify that the results are
    // consistent with what we get by calling `contains` with known test keys.
    val rng = new Random
    for (_ <- 0 until 1000) {
      val (left, leftSlices): (SliceSetImpl, immutable.Vector[Slice]) =
        createRandomSet(rng, n = rng.nextInt(5))
      val (right, rightSlices): (SliceSetImpl, immutable.Vector[Slice]) =
        createRandomSet(rng, n = rng.nextInt(5))
      val (leftOnly, rightOnly): (SliceSetImpl, SliceSetImpl) = SliceSetImpl.diff(left, right)

      // For each test key, check whether it is contained in the left and right sets.
      for (key: SliceKey <- orderedSliceKeys) {
        val inLeft = leftSlices.exists { slice: Slice =>
          slice.contains(key)
        }
        val inRight = rightSlices.exists { slice: Slice =>
          slice.contains(key)
        }
        assert(left.contains(key) == inLeft)
        assert(right.contains(key) == inRight)
        val expectedInLeftOnly = inLeft && !inRight
        assert(leftOnly.contains(key) == expectedInLeftOnly)
        val expectedInRightOnly = inRight && !inLeft
        assert(rightOnly.contains(key) == expectedInRightOnly)
      }
    }
  }
}
