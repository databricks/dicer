package com.databricks.dicer.friend

import com.databricks.api.proto.dicer.friend.SliceP

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.util.Random
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.{
  ProposedSliceAssignment,
  SliceAssignment,
  SliceHelper,
  SliceMapHelper,
  SliceWithResources
}
import com.databricks.dicer.common.SliceHelper.RichSlice
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.test.SliceMapTestDataP
import com.databricks.dicer.common.test.SliceMapTestDataP.IntersectTestCaseP.{
  LeftEntryP,
  RightEntryP
}
import com.databricks.dicer.common.test.SliceMapTestDataP.{
  IntersectTestCaseP,
  PartialSliceMapErrorTestCaseP,
  PartialSliceMapTestCaseP
}
import com.databricks.dicer.common.test.SliceMapTestDataP.PartialSliceMapTestCaseP.{
  ExpectedEntryP,
  ValueTypeP
}
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.SliceMap.{GapEntry, IntersectionEntry}
import com.databricks.testing.DatabricksTest

/**
 * Direct tests for [[SliceMap]]. The abstraction is mostly tested indirectly via its dependents
 * (see `AssignmentSuite` and `AssignmentSyncDriverSuite` in particular).
 */
class SliceMapSuite extends DatabricksTest {

  private val TEST_DATA: SliceMapTestDataP =
    loadTestData[SliceMapTestDataP]("dicer/common/test/data/slice_map_test_data.textproto")

  test("lookUp") {
    // Test plan: Populate a SliceMap with slices and then verify the expected slices are looked up
    // for all boundary values and random values.

    val rng = new Random
    for (_ <- 0 until 100) { // 100 trials.
      val size = rng.nextInt(1000) + 1 // Random map size (cannot be empty).
      val sliceMap: SliceMap[Slice] = SliceMap.ofSlices(createCompleteSlices(size, rng))

      // Check all boundaries.
      for (slice: Slice <- sliceMap.entries) {
        assert(sliceMap.lookUp(slice.lowInclusive) == slice)
      }
      // Lookup random 1000 random keys.
      for (_ <- 0 until 1000) {
        val key: SliceKey = identityKey(rng.nextLong())
        val actual: Slice = sliceMap.lookUp(key)
        assert(actual.contains(key))

        // Verify that actual is actually in the map by exercising the `findIndexInCompleteEntries`
        // utility.
        val index: Int =
          SliceMap.findIndexInOrderedDisjointEntries(sliceMap.entries, key, sliceMap.getSlice)
        val expected: Slice = sliceMap.entries(index)
        assert(actual eq expected) // should be the same reference
      }
    }
  }

  test("validateCompleteSlices") {
    // Test plan: Create slices that do not cover the full range and check that they fail
    // validation.
    for (testCase <- TEST_DATA.validateCompleteSlicesTestCases) {
      val slices: Seq[Slice] = testCase.slices.map((slice: SliceP) => SliceHelper.fromProto(slice))
      if (testCase.getExpectedError.isEmpty) {
        SliceMap.forTest.validateCompleteSlices(slices)
      } else {
        assertThrow[IllegalArgumentException](testCase.getExpectedError) {
          SliceMap.forTest.validateCompleteSlices(slices)
        }
      }
    }
  }

  /**
   * Returns `SliceMap.intersectSlices(left, right)` and compares the result with the expected
   * result that is computed by:
   *
   *  - Exhaustively finding intersections between all pairings of entries in `left` and `right`.
   *  - Sorting those intersections.
   *  - Constructing the resulting expected SliceMap from the sorted intersections.
   */
  private def intersectSlicesAndCompareWithReferenceImplementation[T, U](
      left: SliceMap[T],
      right: SliceMap[U]): SliceMap[IntersectionEntry[T, U]] = {
    val actual: SliceMap[IntersectionEntry[T, U]] = SliceMap.intersectSlices(left, right)

    // We expect entries for every intersecting entry in left and right. Check all pairs for
    // intersection.
    val expectedEntries = immutable.Vector.newBuilder[IntersectionEntry[T, U]]
    for (leftEntry: T <- left.entries) {
      val leftSlice: Slice = left.getSlice(leftEntry)
      for (rightEntry: U <- right.entries) {
        val rightSlice: Slice = right.getSlice(rightEntry)
        val intersectionOpt: Option[Slice] = leftSlice.intersection(rightSlice)
        if (intersectionOpt.isDefined) {
          val intersection: Slice = intersectionOpt.get
          expectedEntries += IntersectionEntry(intersection, leftEntry, rightEntry)
        }
      }
    }
    val sortedExpectedEntries: immutable.Vector[IntersectionEntry[T, U]] =
      expectedEntries.result().sortBy(SliceMap.INTERSECTION_ENTRY_ACCESSOR)
    val expected: SliceMap[IntersectionEntry[T, U]] =
      SliceMap.ofIntersectionEntries(sortedExpectedEntries)
    assert(actual == expected)
    actual
  }

  test("SliceMap intersectSlices") {
    // Test plan: verify the expected output for various inputs to `intersectSlices`.
    type ResultEntry = SliceMap.IntersectionEntry[(Slice, String), (Slice, Int)]
    for (testCase: IntersectTestCaseP <- TEST_DATA.intersectTestCases) {
      val leftEntries: Vector[(Slice, String)] = testCase.leftEntries.map { entry: LeftEntryP =>
        (SliceHelper.fromProto(entry.getSlice), entry.getValue)
      }.toVector
      val rightEntries: Vector[(Slice, Int)] = testCase.rightEntries.map { entry: RightEntryP =>
        (SliceHelper.fromProto(entry.getSlice), entry.getValue)
      }.toVector

      val left =
        new SliceMap[(Slice, String)](leftEntries, getSlice = { case (slice: Slice, _) => slice })
      val right =
        new SliceMap[(Slice, Int)](rightEntries, getSlice = { case (slice: Slice, _) => slice })

      val actual: SliceMap[ResultEntry] =
        intersectSlicesAndCompareWithReferenceImplementation(left, right)
      val expected: SliceMap[ResultEntry] = SliceMap.ofIntersectionEntries(
        testCase.expectedIntersectionEntries.map {
          expectedEntry: IntersectTestCaseP.ExpectedEntryP =>
            IntersectionEntry(
              SliceHelper.fromProto(expectedEntry.getSlice),
              leftEntries(expectedEntry.getLeftIndex),
              rightEntries(expectedEntry.getRightIndex)
            )
        }.toVector
      )
      assert(actual == expected)
    }
  }

  test("SliceMap intersectSlices randomized") {
    // Test plan: validate the behavior of `intersectSlices` against a reference implementation that
    // exhaustively checks for intersections between all pairs of Slices in the left and right
    // inputs.

    val rng = new Random
    for (_ <- 0 until 100) {
      val leftSize: Int = rng.nextInt(10) + 1 // need at least one entry for complete SliceMap
      val left: SliceMap[Slice] = SliceMap.ofSlices(createCompleteSlices(leftSize, rng))
      val rightSize: Int = rng.nextInt(10) + 1
      val right: SliceMap[Slice] = SliceMap.ofSlices(createCompleteSlices(rightSize, rng))
      intersectSlicesAndCompareWithReferenceImplementation(left, right)
    }
  }

  test("SliceMap createFromOrderedDisjointEntries") {
    // Test plan: verify that ordered Slices are filled as expected by
    // createFromOrderedDisjointEntries.
    for (testCase: PartialSliceMapTestCaseP <- TEST_DATA.partialSliceMapTestCases) {
      val input: Seq[Slice] =
        testCase.inputSlices.map((slice: SliceP) => SliceHelper.fromProto(slice))
      val expected: SliceMap[GapEntry[Slice]] = SliceMap.ofGapEntries(
        testCase.expectedEntries.map { entry: ExpectedEntryP =>
          entry.getValueType match {
            case ValueTypeP.SOME =>
              GapEntry.Some(SliceHelper.fromProto(entry.getSlice))
            case ValueTypeP.GAP =>
              GapEntry.Gap(SliceHelper.fromProto(entry.getSlice))
            case ValueTypeP.VALUE_TYPE_P_UNSPECIFIED =>
              throw new IllegalArgumentException("Invalid value type")
          }
        }.toVector,
        SliceMap.SLICE_ACCESSOR
      )
      val actual: SliceMap[GapEntry[Slice]] =
        SliceMap.createFromOrderedDisjointEntries(input, getSlice = { slice: Slice =>
          slice
        })
      assert(actual == expected)
    }
  }

  test("SliceMap createFromOrderedDisjointEntries negative") {
    // Test plan: verify that IllegalArgumentException is thrown by createFromOrderedDisjointEntries
    // when the inputs include overlapping or out of order Slices.
    for (testCase: PartialSliceMapErrorTestCaseP <- TEST_DATA.partialSliceMapErrorTestCases) {
      val input: Seq[Slice] =
        testCase.inputSlices.map((slice: SliceP) => SliceHelper.fromProto(slice))
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        SliceMap.createFromOrderedDisjointEntries(input, getSlice = (slice: Slice) => slice)
      }
    }
  }

  test("SliceMap coalesce") {
    // Verify [[SliceMap.coalesceSlices]] correctly coalesce equal entries in SliceMap. Verify this
    // by calling it with various SliceMap of SliceWithResources and see if the result is as
    // expected.

    def setSlice(sliceWithResources: SliceWithResources, slice: Slice): SliceWithResources = {
      sliceWithResources.copy(slice = slice)
    }

    {
      val sliceMap = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0")),
          SliceWithResources(10 -- 20, Set("resource0")),
          SliceWithResources(20 -- 30, Set("resource1")),
          SliceWithResources(30 -- 40, Set("resource2")),
          SliceWithResources(40 -- 50, Set("resource2")),
          SliceWithResources(50 -- 60, Set("resource0", "resource1")),
          SliceWithResources(60 -- ∞, Set("resource0", "resource1"))
        )
      )

      val actual: SliceMap[SliceWithResources] = SliceMap.coalesceSlices(sliceMap, setSlice)

      val expected: SliceMap[SliceWithResources] = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 20, Set("resource0")),
          SliceWithResources(20 -- 30, Set("resource1")),
          SliceWithResources(30 -- 50, Set("resource2")),
          SliceWithResources(50 -- ∞, Set("resource0", "resource1"))
        )
      )
      assert(actual == expected)
    }

    {
      val sliceMap = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0")),
          SliceWithResources(10 -- 20, Set("resource1")),
          SliceWithResources(20 -- ∞, Set("resource2"))
        )
      )

      val actual: SliceMap[SliceWithResources] = SliceMap.coalesceSlices(sliceMap, setSlice)

      val expected: SliceMap[SliceWithResources] = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0")),
          SliceWithResources(10 -- 20, Set("resource1")),
          SliceWithResources(20 -- ∞, Set("resource2"))
        )
      )
      assert(actual == expected)
    }

    {
      val sliceMap = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0")),
          SliceWithResources(10 -- 20, Set("resource0")),
          SliceWithResources(20 -- ∞, Set("resource0"))
        )
      )

      val actual: SliceMap[SliceWithResources] = SliceMap.coalesceSlices(sliceMap, setSlice)

      val expected: SliceMap[SliceWithResources] = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- ∞, Set("resource0"))
        )
      )
      assert(actual == expected)
    }

    {
      val sliceMap = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0", "resource1")),
          SliceWithResources(10 -- 20, Set("resource1", "resource2")),
          SliceWithResources(20 -- ∞, Set("resource2", "resource0"))
        )
      )

      val actual: SliceMap[SliceWithResources] = SliceMap.coalesceSlices(sliceMap, setSlice)

      val expected: SliceMap[SliceWithResources] = SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 10, Set("resource0", "resource1")),
          SliceWithResources(10 -- 20, Set("resource1", "resource2")),
          SliceWithResources(20 -- ∞, Set("resource2", "resource0"))
        )
      )
      assert(actual == expected)
    }
  }

  test("Randomized SliceMap coalesce") {
    // Test plan: Verify SliceMap coalesce works correctly for randomized SliceMap. Create a
    // SliceMap with entries that have randomized boundaries and values, and coalesce it, then
    // create a MutableSliceMap and put the same entries and the same values to it. Verify the
    // SliceMap's coalesced result with the MutableSliceMap, which always coalesces. Test this
    // multiple times with SliceMaps of different sizes.

    def setSlice(sliceWithResource: SliceWithResources, slice: Slice): SliceWithResources = {
      sliceWithResource.copy(slice = slice)
    }

    val resources: IndexedSeq[Squid] = IndexedSeq("resource0", "resource1", "resource2")
    val rng = new Random

    for (_ <- 0 until 100) { // 100 trials.
      val size = rng.nextInt(1000) + 1 // Random map size (cannot be empty).

      // Complete slices with randomized boundaries.
      val slices: Vector[Slice] = createCompleteSlices(size, rng)

      val mutableSliceMap = new MutableSliceMap[Squid]

      // Create SliceMap from `slices` with randomized values, and fill the mutableSliceMap with the
      // same slices and values.
      val sliceMapEntriesBuilder = new VectorBuilder[SliceWithResources]
      for (slice: Slice <- slices) {
        val resource: Squid = resources(rng.nextInt(resources.length))
        sliceMapEntriesBuilder += SliceWithResources(slice, Set(resource))
        mutableSliceMap.put(slice, resource)
      }
      val sliceMap: SliceMap[SliceWithResources] =
        SliceMapHelper.ofSlicesWithResources(sliceMapEntriesBuilder.result())

      val actualEntries: Vector[SliceWithResources] =
        SliceMap.coalesceSlices(sliceMap, setSlice).entries
      val expectedEntries: Vector[SliceWithResources] = mutableSliceMap.iterator.map {
        case (slice, squid) => SliceWithResources(slice, Set(squid))
      }.toVector

      assert(actualEntries == expectedEntries)
    }
  }

  test("SliceMap equality") {
    // Test plan: Verify that SliceMaps which have the same entries compare equal, and don't
    // otherwise. Verify this by creating several SliceMaps, some of which have the same entries,
    // and check that when SliceMaps have the same entries, they compare equal, otherwise do not.
    // Also verify that SliceMap does not compare equal with other types.

    // Setup: SliceMaps with different entry types. Each pair in the Vector has the same entries,
    // but a different slice accessor.
    val sliceMaps: Vector[SliceMap[_]] = Vector(
      SliceMap.ofSlices(Vector[Slice](Slice.FULL)),
      new SliceMap(Vector[Slice](Slice.FULL), (x: Slice) => x),
      SliceMapHelper.ofProposedSliceAssignments(
        immutable.Vector[ProposedSliceAssignment](
          ("" -- 10) -> Set("resource1"),
          (10 -- 40) -> Set("resource2"),
          (40 -- ∞) -> Set("resource1")
        )
      ),
      new SliceMap(
        immutable.Vector[ProposedSliceAssignment](
          ("" -- 10) -> Set("resource1"),
          (10 -- 40) -> Set("resource2"),
          (40 -- ∞) -> Set("resource1")
        ),
        (x: ProposedSliceAssignment) => x.slice
      ),
      SliceMapHelper.ofSlicesWithResources(
        immutable.Vector(
          SliceWithResources("" -- 20, Set("resource0")),
          SliceWithResources(20 -- 40, Set("resource1")),
          SliceWithResources(40 -- ∞, Set("resource2"))
        )
      ),
      new SliceMap(
        immutable.Vector(
          SliceWithResources("" -- 20, Set("resource0")),
          SliceWithResources(20 -- 40, Set("resource1")),
          SliceWithResources(40 -- ∞, Set("resource2"))
        ),
        (x: SliceWithResources) => x.slice
      )
    )

    // Verify: SliceMaps compare equal iff they have the same entries.
    for (i: Int <- sliceMaps.indices) {
      for (j: Int <- sliceMaps.indices) {
        if (sliceMaps(i).entries == sliceMaps(j).entries) {
          assert(sliceMaps(i).equals(sliceMaps(j)))
          assert(sliceMaps(j).equals(sliceMaps(i)))
        } else {
          assert(!sliceMaps(i).equals(sliceMaps(j)))
          assert(!sliceMaps(j).equals(sliceMaps(i)))
        }
      }
    }

    // Verify: SliceMap compares not equal with other types.
    for (sliceMap <- sliceMaps) {
      assert(!sliceMap.equals(Slice.FULL))
      assert(!sliceMap.equals("foo"))
    }
  }

  test("SliceMap toString representation") {
    // Test plan: Verify that the toString method of SliceMap returns a string representation of the
    // SliceMap that is a comma separated list of the entries in the SliceMap.
    val sliceMap: SliceMap[Slice] =
      SliceMap.ofSlices(
        immutable.Vector[Slice](
          ("" -- 0x0000000000000001),
          (0x0000000000000001 -- 0x0000000000000002),
          (0x0000000000000002 -- ∞)
        )
      )
    assert(
      sliceMap.toString == "{[\"\" .. 0x0000000000000001), [0x0000000000000001 .. " +
      "0x0000000000000002), [0x0000000000000002 .. ∞)}"
    )
  }

  test("GapEntry: Some.isDefined returns true and Some.get returns the value") {
    // Test plan: Verify that calling `isDefined` on a GapEntry.Some returns true and calling `get`
    // returns the value.
    val proposedSliceAssignment: ProposedSliceAssignment = ("" -- 10) -> Set(
        "resource1"
      )
    val some = GapEntry.Some(proposedSliceAssignment)
    assert(some.isDefined === true)
    assert(some.get === proposedSliceAssignment)
  }

  test("GapEntry: Gap.isDefined returns false and Gap.get throws NoSuchElementException") {
    // Test plan: Verify that calling `isDefined` on a GapEntry.Gap returns false and calling `get`
    // throws a NoSuchElementException.
    val gap = GapEntry.Gap[SliceAssignment](Slice.FULL)
    assert(gap.isDefined === false)
    assertThrow[NoSuchElementException]("GapEntry.Gap.get") { gap.get }
  }
}
