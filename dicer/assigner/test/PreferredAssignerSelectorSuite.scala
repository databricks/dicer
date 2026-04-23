package com.databricks.dicer.assigner

import java.util.UUID

import com.databricks.testing.DatabricksTest

class PreferredAssignerSelectorSuite extends DatabricksTest {

  test("selectPreferredAssigner returns None for an empty assigner set") {
    // Test plan: Verify that an empty eligible set produces None. Verify this by calling
    // selectPreferredAssigner with an empty set and asserting the result is None.
    val result: Option[UUID] =
      PreferredAssignerSelector.selectPreferredAssigner(Set.empty)
    assert(result.isEmpty)
  }

  test("selectPreferredAssigner returns the sole UUID for a single-element set") {
    // Test plan: Verify that a single eligible assigner is always selected. Verify this by
    // calling selectPreferredAssigner with a single UUID and asserting it is returned.
    val uuid: UUID = UUID.randomUUID()
    val result: Option[UUID] =
      PreferredAssignerSelector.selectPreferredAssigner(Set(uuid))
    assert(result == Some(uuid))
  }

  test("selectPreferredAssigner is deterministic for the same inputs") {
    // Test plan: Verify that repeated calls with the same assigner set produce the same result.
    // Verify this by calling selectPreferredAssigner 100 times and asserting all results are
    // identical.
    val assigners: Set[UUID] = (1 to 5).map(_ => UUID.randomUUID()).toSet

    val first: Option[UUID] =
      PreferredAssignerSelector.selectPreferredAssigner(assigners)
    for (_ <- 1 to 100) {
      assert(PreferredAssignerSelector.selectPreferredAssigner(assigners) == first)
    }
  }

  test("adding an assigner only moves the selection to the new assigner") {
    // Test plan: Verify the consistent hashing invariant: when an assigner is added, the
    // selected assigner either stays the same or becomes the newly added one. Verify this by
    // comparing the selection before and after adding an assigner.
    val baseAssigners: Set[UUID] = (1 to 5).map(_ => UUID.randomUUID()).toSet
    val extraAssigner: UUID = UUID.randomUUID()

    val before: Option[UUID] =
      PreferredAssignerSelector.selectPreferredAssigner(baseAssigners)
    val after: Option[UUID] =
      PreferredAssignerSelector.selectPreferredAssigner(baseAssigners + extraAssigner)
    if (before != after) {
      assert(
        after == Some(extraAssigner),
        s"Selection moved from $before to $after, but should only move to $extraAssigner"
      )
    }
  }

  test("selectPreferredAssigner selects different assigners for different sets") {
    // Test plan: Verify that different assigner sets produce different selections. Verify this
    // by generating many random assigner sets and checking that at least 2 distinct UUIDs are
    // selected across them.
    val results: Set[UUID] = (0 until 100).flatMap { _: Int =>
      val assigners: Set[UUID] = (1 to 5).map(_ => UUID.randomUUID()).toSet
      PreferredAssignerSelector.selectPreferredAssigner(assigners)
    }.toSet

    assert(results.size >= 2, "Expected at least 2 different assigners across random sets")
  }
}
