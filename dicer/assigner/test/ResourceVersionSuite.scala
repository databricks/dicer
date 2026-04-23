package com.databricks.dicer.assigner

import com.databricks.caching.util.TestUtils.checkComparisons
import com.databricks.testing.DatabricksTest

/** Unit tests for the ordering properties of [[ResourceVersion]]. */
class ResourceVersionSuite extends DatabricksTest {

  test("ResourceVersion compare") {
    // Test plan: Verify all pairwise comparison operators on a strictly increasing sequence of
    // ResourceVersions that exercises the three ordering rules: (1) empty is less than any
    // non-empty value, (2) shorter length is less than longer length (so "9" < "10"), and
    // (3) equal-length values fall back to lexicographic comparison.
    val sortedVersions: IndexedSeq[ResourceVersion] =
      IndexedSeq("", "1", "2", "9", "10", "11", "99", "100", "123", "124", "999", "1000")
        .map(ResourceVersion(_))

    checkComparisons(sortedVersions)
  }
}
