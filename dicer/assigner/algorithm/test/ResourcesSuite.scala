package com.databricks.dicer.assigner.algorithm

import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.TestSliceUtils.createTestSquid
import com.databricks.dicer.friend.Squid

class ResourcesSuite extends DatabricksTest {

  test("toString summarizes empty resources") {
    // Test plan: Verify that toString properly renders the object as a zero-count summary
    // (initial state of the `AssignmentGenerator`), by asserting Resources.empty.toString
    // returns a count of 0.

    val resources: Resources = Resources.empty
    assert(resources.toString == "Resources(count=0, squids=[])", s"Got $resources")
  }

  test("toString summarizes less than MAX_SQUIDS_TO_PRINT (5) resources") {
    // Test plan: Verify that toString properly summarizes the small number of resources,
    // rendering the count and printing all available resources.

    val testSquids: IndexedSeq[Squid] =
      (0 until 2).map(i => createTestSquid(s"resource$i", creationTimeOffset = i))
    val many = Resources.create(testSquids)
    val s: String = many.toString

    // Expecting the newest squid (resource1) to be output first in the array.
    val expectedString = s"Resources(count=2, squids=[${testSquids(1)}, ${testSquids(0)}])"

    assert(s.toString == expectedString, s"Got $s")
  }

  test("toString summarizes exactly MAX_SQUIDS_TO_PRINT (5) resources") {
    // Test plan: Verify the boundary at MAX_SQUIDS_TO_PRINT: with exactly five resources,
    // all five squids are printed (newest first) and no ellipsis is appended.

    val testSquids: IndexedSeq[Squid] =
      (0 until 5).map(i => createTestSquid(s"resource$i", creationTimeOffset = i))
    val many = Resources.create(testSquids)
    val s: String = many.toString

    // Expecting all five squids, newest first, with no trailing ellipsis.
    val expectedString = s"Resources(count=5, squids=[${testSquids(4)}, ${testSquids(3)}, " +
      s"${testSquids(2)}, ${testSquids(1)}, ${testSquids(0)}])"

    assert(s == expectedString, s"Got $s")
  }

  test("toString summarizes more than MAX_SQUIDS_TO_PRINT (5) resources") {
    // Test plan: Verify that toString properly summarizes rather than enumerates over large
    // sets of resources, rendering the count and capping displayed squids utilizing
    // ellipses to imply the existence of more squids. We also want to ensure that printed
    // squids are the five newest squids to be created.

    val testSquids: IndexedSeq[Squid] =
      (0 until 6).map(i => createTestSquid(s"resource$i", creationTimeOffset = i))
    val many = Resources.create(testSquids)
    val s: String = many.toString

    // Expecting the newest squids to be displayed first and ellipses will be printed,
    // while oldest squid (resource0) will not be included in the string.
    val expectedString = s"Resources(count=6, squids=[${testSquids(5)}, ${testSquids(4)}, " +
      s"${testSquids(3)}, ${testSquids(2)}, ${testSquids(1)}, ...])"

    assert(s.toString == expectedString, s"Got $s")
  }

  test("toString breaks creation time ties deterministically by resource address") {
    // Test plan: Verify that squids sharing a creation time are ordered deterministically by
    // resource address (ascending), rather than by arbitrary set-iteration order. Create three
    // squids with the same creation time (the default offset of 0) and named resource0..resource2,
    // and assert they are printed in resource-address order (resource0 first).

    val testSquids: IndexedSeq[Squid] =
      (0 until 3).map(i => createTestSquid(s"resource$i"))
    val many = Resources.create(testSquids)
    val s: String = many.toString

    // All three share a creation time, so the tie-break on resource address decides order:
    // "resource0" < "resource1" < "resource2".
    val expectedString =
      s"Resources(count=3, squids=[${testSquids(0)}, ${testSquids(1)}, ${testSquids(2)}])"

    assert(s == expectedString, s"Got $s")
  }
}
