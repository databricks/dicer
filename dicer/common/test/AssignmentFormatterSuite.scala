package com.databricks.dicer.common

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.io.Source
import scala.util.Random

import com.databricks.testing.DatabricksTest
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.{SliceMap, Squid}

class AssignmentFormatterSuite extends DatabricksTest {

  /** Returns the contents of the file at `path` as a string. */
  private def readFile(path: String): String = {
    val source = Source.fromFile(path, "UTF-8")
    val result = source.mkString
    source.close()
    result
  }

  test("AssignmentFormatter formats Assignment with subslice annotations") {
    // Test plan: golden test for Assignment.toString to validate that the string representation is
    // readable. Each Slice Assignment only has Subslice Annotations for one resource.

    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      // No annotation.
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      // Assigned multiple resource, with annotations for pod1.
      (("Balin" -- "Kili") @@ (43 ## 20) ->
      Seq("https://pod1", "https://pod2", "https://pod3") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      // With annotation.
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      // No annotation.
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    assert(
      assignment.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
  |├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │         │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |│ |-Bofur  │ pod1                 │ -                │ -     │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |│ |-Fili   │ pod1                 │ -                │ -     │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |│ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
  |│ Nori     │ pod2                 │ 50 (PT0S)        │ (N/A) │         │
  |└──────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("AssignmentFormatter formats Assignment with many subslice intersections") {
    // Test plan: Verify that Assignment.toString returns representation showing continuous
    // generation number for each resource in each intersected subslice correctly, where each Slice
    // Assignment may have multiple resources with annotations and they may overlap with each other,
    // thus creating a large number of intersected Subslices in the table.

    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "aa") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("aa" -- "zz") @@ (43 ## 20) ->
      Seq("https://pod1", "https://pod2", "https://pod3", "https://pod4") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("aa" -- "cc", 10, stateTransferOpt = None),
          SubsliceAnnotation("ff" -- "ww", 15, stateTransferOpt = None)
        ),
        "https://pod2" -> Seq(
          SubsliceAnnotation("gg" -- "xx", 5, stateTransferOpt = None)
        ),
        "https://pod3" -> Seq(
          SubsliceAnnotation("aa" -- "hh", 10, stateTransferOpt = None)
        ),
        "https://pod4" -> Seq(
          SubsliceAnnotation("aa" -- "hh", 12, stateTransferOpt = None),
          SubsliceAnnotation("hh" -- "ss", 18, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(420.0),
      (("zz" -- ∞) @@ (43 ## 30) -> Seq("https://pod1", "https://pod2", "https://pod3") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("zzaa" -- "zzbb", 10, stateTransferOpt = None),
          SubsliceAnnotation("zzss" -- "zzzz", 10, stateTransferOpt = None)
        ),
        "https://pod2" -> Seq(
          SubsliceAnnotation("zzmm" -- "zznn", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5)
    )

    // Read the expected output from a file, the table has too many lines to fit in the Scala file.
    val expectedOutput =
      readFile("dicer/common/test/golden_files/assignment_with_many_intersections.txt")
    assert(
      assignment.toString() == s"$assignmentGeneration\n\n" + expectedOutput
    )
  }

  test("AssignmentFormatter with lots of resources and slices toString") {
    // Test plan: calls toString on an assignment with many resources and Slices to validate that
    // the debug output is truncated. Only the first 16 resources and the first 32 Slices should
    // be rendered.

    val proposedSliceMap: SliceMap[ProposedSliceAssignment] =
      createRandomProposal(
        1000,
        (0 until 100).map(i => createTestSquid(s"pod$i")),
        numMaxReplicas = 3,
        new Random
      )
    val assignment: Assignment =
      ProposedAssignment(predecessorOpt = None, proposedSliceMap)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          generation = 42
        )
    val truncatedString: String = assignment.toString
    val untruncatedStringBuilder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      untruncatedStringBuilder,
      maxResources = Int.MaxValue,
      maxSlices = Int.MaxValue,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    val untruncatedString: String = untruncatedStringBuilder.toString()
    assert(truncatedString.length < untruncatedString.length / 10)
    assert(truncatedString.contains("16 of 100 resources"))
    assert(truncatedString.contains("32 of 1000 slices"))
  }

  test("AssignmentFormatter appendAssignmentToStringBuilder with load stats overrides") {
    // Test plan: call appendAssignmentToStringBuilder with `loadPerResourceOpt` and
    // `loadPerSliceOverrideOpt` parameters, and validate against a golden snippet that these
    // stats are correctly overridden and displayed.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )

    val loadPerResourceOpt: Option[Map[Squid, Double]] = Some(
      Map((createTestSquid("https://pod1"), 130.5), (createTestSquid("https://pod2"), 12.0))
    )
    val loadPerSliceOverrideOpt: Option[Map[Slice, Double]] = Some(
      Map(
        ("" -- "Balin", 30.0),
        ("Balin" -- "Kili", 15.5),
        ("Nori" -- ∞, 12.0)
      )
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt,
      loadPerSliceOverrideOpt,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(
      builder.toString() ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ 130.5           │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ 12.0            │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
  |├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 30.0  │         │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ 15.5  │         │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │         │
  |│ |-Bofur  │ pod1                 │ -                │ -     │         │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │         │
  |│ |-Fili   │ pod1                 │ -                │ -     │         │
  |│ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
  |│ Nori     │ pod2                 │ 50 (PT0S)        │ 12.0  │         │
  |└──────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder formats Assignment with truncation - even") {
    // Test plan: verify that an even number of slices is displayed correctly.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).clearPrimaryRateLoad(),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(10.0)
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 4,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )

    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |4 of 6 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |│ BBB     │ pod2    │ 50 (PT0S)        │ 20.0 │                                              │
  |│ CCC     │ pod3    │ 50 (PT0S)        │ 30.0 │                                              │
  |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEE     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ FFF     │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder formats Assignment with truncation - odd") {
    // Test plan: verify that an odd number of slices is displayed correctly.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 3,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )

    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |3 of 5 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |│ BBB     │ pod2    │ 50 (PT0S)        │ 20.0 │                                              │
  |│ CCC     │ pod3    │ 50 (PT0S)        │ 30.0 │                                              │
  |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEE     │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder formats Assignment with truncation - contiguous") {
    // Test plan: Verify that when truncating middle slices with contiguous omitted slices, the
    // output is collapsed into a single summary row.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).clearPrimaryRateLoad(),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 4,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |4 of 6 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0 │                                              │
  |│ BBB     │ pod2    │ 50 (PT0S)        │ 20.0 │                                              │
  |│ CCC     │ pod3    │ 50 (PT0S)        │ 30.0 │                                              │
  |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEE     │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder formats Assignment with truncation - empty") {
    // Test plan: verify that when truncating to 0 slices, the output is empty.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 0,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )

    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |0 of 6 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 6 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder formats Assignment with truncation - unassigned ranges") {
    // Test plan: verify that unassigned ranges are treated separately and not combined with
    // omitted slices
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).clearPrimaryRateLoad()
    )

    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 4,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = squidFilterOpt
    )

    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |4 of 5 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0 │                                              │
  |│ BBB     │ pod1    │ 50 (PT0S)        │ 20.0 │                                              │
  |│ CCC     │ ...     │ ...              │ ...  │ 0 slice(s) omitted and 1 unassigned range(s) │
  |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEE     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ FFF     │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder with stats overrides - Squid filtered") {
    // Test plan: call appendAssignmentToStringBuilder with the `squidFilterOpt` parameter
    // specified, and validate against a golden snippet that only stats related to the resource
    // defined in `squidFilterOpt` are displayed.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      // Assigned to 2 resources, and there are subslice annotations for each.
      (("Balin" -- "Fili") @@ (43 ## 20) -> Seq("https://pod1", "https://pod2") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        ),
        "https://pod2" -> Seq(
          SubsliceAnnotation("Ben" -- "Doge", 18, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      ("Fili" -- "Kili") @@ (43 ## 20) -> Seq("https://pod2"),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None))
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )

    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt
    )

    // Only pod1-related information is displayed - the Slice or Subslice containing only
    // pod2-related information are displayed with "Assigned to Other Resources" or
    // "No Annotation Info".
    assert(
      builder.toString() ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌──────────┬──────────────────────┬──────────────────┬───────┬──────────────────────────────────────────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details                                      │
  |├──────────┼──────────────────────┼──────────────────┼───────┼──────────────────────────────────────────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │                                              │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │                                              │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │                                              │
  |│ |-Bofur  │ pod1                 │ -                │ -     │                                              │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │                                              │
  |│ Fili     │ ...                  │ ...              │ ...   │ 0 slice(s) omitted and 1 unassigned range(s) │
  |│ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │                                              │
  |│ Nori     │ ...                  │ ...              │ ...   │ 0 slice(s) omitted and 1 unassigned range(s) │
  |└──────────┴──────────────────────┴──────────────────┴───────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder with top keys defined") {
    // Test plan: verify that when appendAssignmentToStringBuilder is called with `topKeysOpt` set,
    // the top keys are displayed in the "Details" column. The top keys must be listed in the same
    // row as the load for their containing slice.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(15.5),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(100.5)
    )

    val topKeysOpt: Option[SortedMap[SliceKey, Double]] =
      Some(
        SortedMap(
          toSliceKey("Bofur") -> 14.5,
          toSliceKey("Nori") -> 42.0,
          toSliceKey("Ori") -> 30.0
        )
      )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt,
      squidFilterOpt = None
    )

    // Read the expected output from a file, the table is too wide for the 100 character line limit.
    val expectedOutput =
      readFile("dicer/common/test/golden_files/assignment_with_top_keys_defined.txt")
    assert(
      builder.toString() == s"$assignmentGeneration\n\n" + expectedOutput
    )
  }

  test("appendAssignmentToStringBuilder with state transfer") {
    // Test plan: verify that when appendAssignmentToStringBuilder is called with an assignment
    // containing state transfer information, it is displayed in the "Details" column. If there are
    // both state transfer information and top keys, both are displayed.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation(
            "Balin" -- "Bifur",
            10,
            stateTransferOpt = Some(Transfer(0, "https://pod0"))
          ),
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation(
            "Dwalin" -- "Fili",
            15,
            stateTransferOpt = Some(Transfer(1, "https://pod2"))
          )
        )
      )).withPrimaryRateLoad(15.5),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(100.5)
    )

    val topKeysOpt: Option[SortedMap[SliceKey, Double]] =
      Some(SortedMap(toSliceKey("Bofur") -> 14.5))

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt,
      squidFilterOpt = None
    )
    // Read the expected output from a file, the table is too wide for the 100 character line limit.
    val expectedOutput =
      readFile("dicer/common/test/golden_files/assignment_with_state_transfer.txt")
    assert(
      builder.toString() == s"$assignmentGeneration\n\n" + expectedOutput
    )
  }

  test("Full DiffAssignment toString") {
    // Test plan: create an Assignment and generate a DiffAssignment with a `diffGeneration` that
    // guarantees the resulting DiffAssignment is Full. Validate that DiffAssignment.toString
    // returns the expected value.
    val assignmentGeneration: Generation = 0 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (0 ## 10) -> Seq("https://pod1", "https://pod2"))
        .withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (0 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Nori") @@ (0 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (0 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val diffAssignment: DiffAssignment = assignment.toDiff(0 ## 49)

    assert(
      diffAssignment.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
  |├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │         │
  |│ |        │ pod2                 │ -                │ -     │         │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │         │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │         │
  |│ |-Bofur  │ pod1                 │ -                │ -     │         │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │         │
  |│ |-Fili   │ pod1                 │ -                │ -     │         │
  |│ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
  |│ Nori     │ pod2                 │ 50 (PT0S)        │ (N/A) │         │
  |└──────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("Partial DiffAssignment with continuous subslices toString") {
    // Test plan: create a Partial DiffAssignment and validate that the string representation is
    // readable.
    val assignmentGeneration: Generation = 2 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (2 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (2 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Mori") @@ (2 ## 30) -> Seq("https://pod1", "https://pod2") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Mori", 10, stateTransferOpt = None)
        ),
        "https://pod2" -> Seq(
          SubsliceAnnotation("Kili" -- "Mori", 15, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Mori" -- "Nori") @@ (2 ## 24) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Mori" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(50.4),
      (("Nori" -- ∞) @@ (2 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val diffAssignmentGeneration: Generation = 2 ## 25
    val diffAssignment: DiffAssignment = assignment.toDiff(diffAssignmentGeneration)

    assert(
      diffAssignment.toString ==
      s"""$assignmentGeneration
  |[Partial diff from $diffAssignmentGeneration]
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌─────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key │ Address              │ Slice Generation │ Load  │ Details │
  |├─────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""      │ (Gap)                │ (Gap)            │ (Gap) │ (Gap)   │
  |│ Kili    │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
  |│ |       │ pod2 @15 (PT-0.035S) │ -                │ -     │         │
  |│ Mori    │ (Gap)                │ (Gap)            │ (Gap) │ (Gap)   │
  |│ Nori    │ pod2                 │ 50 (PT0S)        │ (N/A) │         │
  |└─────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin,
      diffAssignment.toString
    )
  }

  test("DiffAssignment with frozen assignment") {
    // Test plan: Verify that a DiffAssignment with a frozen assignment is displayed correctly.
    // Verify this by creating a frozen assignment and generating a DiffAssignment. Check that the
    // output of DiffAssignment.toString contains the correct information.

    val assignmentGeneration: Generation = 43 ## 50
    val assignmentEntries = Vector(
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2", "https://pod3")).clearPrimaryRateLoad()
    )
    val assignment = Assignment(
      isFrozen = true,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      generation = assignmentGeneration,
      sliceMap = SliceMapHelper.ofSliceAssignments(assignmentEntries)
    )
    val diffAssignment: DiffAssignment = assignment.toDiff(0 ## 49)

    assert(
      diffAssignment.toString ==
      s"""$assignmentGeneration
  |FROZEN
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
  |├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │         │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │         │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │         │
  |│ |-Bofur  │ pod1                 │ -                │ -     │         │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │         │
  |│ |-Fili   │ pod1                 │ -                │ -     │         │
  |│ Kili     │ pod1 @10 (PT-0.04S)  │ 30 (PT-0.02S)    │ 100.5 │         │
  |│ Nori     │ pod2                 │ 50 (PT0S)        │ (N/A) │         │
  |│ |        │ pod3                 │ -                │ -     │         │
  |└──────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendDiffAssignmentToStringBuilder with maxSlices < total slices") {
    // Test plan: Verify that when appendDiffAssignmentToStringBuilder is called with a maxSlices
    // value that is less than the total number of slices in the assignment, the output is truncated
    // to the maxSlices value. Verify this by creating an assignment with more slices than the
    // maxSlices value and checking that the output of appendAssignmentToStringBuilder contains the
    // correct number of slices.

    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1"))
        .withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val diffAssignment: DiffAssignment = assignment.toDiff(0 ## 49)

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendDiffAssignmentToStringBuilder(
      diffAssignment,
      builder,
      maxResources = 16,
      maxSlices = 2
    )
    assert(
      builder.toString() ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices:
  |┌──────────┬──────────────────────┬──────────────────┬───────┬─────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load  │ Details │
  |├──────────┼──────────────────────┼──────────────────┼───────┼─────────┤
  |│ ""       │ pod1                 │ 10 (PT-0.04S)    │ 42.0  │         │
  |│ Balin    │ pod1                 │ 20 (PT-0.03S)    │ (N/A) │         │
  |│ |-Bifur  │ pod1 @10 (PT-0.04S)  │ -                │ -     │         │
  |│ |-Bofur  │ pod1                 │ -                │ -     │         │
  |│ |-Dwalin │ pod1 @15 (PT-0.035S) │ -                │ -     │         │
  |│ |-Fili   │ pod1                 │ -                │ -     │         │
  |└──────────┴──────────────────────┴──────────────────┴───────┴─────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("formatAssignmentChunks with large budget produces one chunk") {
    // Test plan: verify that formatAssignmentChunks with a budget large enough to hold the full
    // assignment produces exactly one chunk.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("CCC" -- ∞) @@ (43 ## 30) -> Seq("https://pod1")).withPrimaryRateLoad(30.0)
    )

    val chunks: Seq[mutable.StringBuilder] = AssignmentFormatter.formatAssignmentChunks(
      () => new mutable.StringBuilder(),
      assignment,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(chunks.size == 1)
    assert(
      chunks(0).toString ==
      s"""$assignmentGeneration
    |
    |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
    |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
    |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
    |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
    |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
    |
    |┌─────────┬─────────┬──────────────────┬──────┬─────────┐
    |│ Low Key │ Address │ Slice Generation │ Load │ Details │
    |├─────────┼─────────┼──────────────────┼──────┼─────────┤
    |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0 │         │
    |│ BBB     │ pod1    │ 20 (PT-0.03S)    │ 20.0 │         │
    |│ CCC     │ pod1    │ 30 (PT-0.02S)    │ 30.0 │         │
    |└─────────┴─────────┴──────────────────┴──────┴─────────┘
    |
    |<internal link>
    |""".stripMargin
    )
  }

  test("formatAssignmentChunks splits across tables and applies part suffix") {
    // Test plan: use a tight character budget (1000 chars) to force the slice assignment table to
    // overflow into multiple chunks. Verify that:
    //  - multiple chunks are produced
    //  - each chunk carries the correct "(part i/N)" suffix
    //  - continuation chunks carry the assignment generation as their first body line
    //  - the first chunk packs the generation string, the full resource table, AND the start of
    //    the slice assignment table (header block + first row); the remaining slice rows spill
    //    into continuation chunks, demonstrating that the slice table is split across chunks
    //  - continuation chunks re-emit the slice table header block (top border + header row +
    //    separator) so every chunk is a readable table fragment on its own
    //  - the last chunk ends with the documentation footer (before the suffix)
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 30) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).clearPrimaryRateLoad()
    )

    val chunks: Seq[mutable.StringBuilder] = AssignmentFormatter.formatAssignmentChunks(
      () => new mutable.StringBuilder(),
      assignment,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None,
      maxCharsPerChunk = 1000
    )
    assert(
      chunks(0).toString ==
      s"""$assignmentGeneration
    |
    |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
    |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
    |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
    |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
    |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
    |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
    |
    |┌─────────┬─────────┬──────────────────┬───────┬─────────┐
    |│ Low Key │ Address │ Slice Generation │ Load  │ Details │
    |├─────────┼─────────┼──────────────────┼───────┼─────────┤
    |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0  │         │
    |└─────────┴─────────┴──────────────────┴───────┴─────────┘
    |
    |<internal link>
    |(part 1/2)""".stripMargin
    )
    assert(
      chunks(1).toString ==
      s"""$assignmentGeneration
    |┌─────────┬─────────┬──────────────────┬───────┬─────────┐
    |│ Low Key │ Address │ Slice Generation │ Load  │ Details │
    |├─────────┼─────────┼──────────────────┼───────┼─────────┤
    |│ BBB     │ pod1    │ 20 (PT-0.03S)    │ 20.0  │         │
    |│ CCC     │ pod3    │ 30 (PT-0.02S)    │ 30.0  │         │
    |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0  │         │
    |│ EEE     │ pod1    │ 50 (PT0S)        │ 40.0  │         │
    |│ FFF     │ pod1    │ 50 (PT0S)        │ (N/A) │         │
    |└─────────┴─────────┴──────────────────┴───────┴─────────┘
    |
    |<internal link>
    |(part 2/2)""".stripMargin
    )
  }

  test("formatAssignmentChunks with very small maxChunkChars doesn't split text regions") {
    // Test plan: verify that with a very small maxChunkChars value (10 chars), the text regions
    // are not split into separate chunks and that the table rows are each split into separate
    // chunks.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- ∞) @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(20.0)
    )
    val chunks: Seq[mutable.StringBuilder] = AssignmentFormatter.formatAssignmentChunks(
      () => new mutable.StringBuilder(),
      assignment,
      maxResources = 16,
      maxSlices = 32,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None,
      maxCharsPerChunk = 10
    )
    assert(
      chunks(0).toString ==
      s"""$assignmentGeneration
    |
    |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
    |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
    |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
    |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
    |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
    |
    |<internal link>
    |(part 1/4)""".stripMargin
    )
    assert(
      chunks(1).toString ==
      s"""$assignmentGeneration
    |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
    |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
    |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
    |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
    |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
    |
    |<internal link>
    |(part 2/4)""".stripMargin
    )
    assert(
      chunks(2).toString ==
      s"""$assignmentGeneration
    |
    |┌─────────┬─────────┬──────────────────┬──────┬─────────┐
    |│ Low Key │ Address │ Slice Generation │ Load │ Details │
    |├─────────┼─────────┼──────────────────┼──────┼─────────┤
    |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0 │         │
    |└─────────┴─────────┴──────────────────┴──────┴─────────┘
    |
    |<internal link>
    |(part 3/4)""".stripMargin
    )
    assert(
      chunks(3).toString ==
      s"""$assignmentGeneration
    |┌─────────┬─────────┬──────────────────┬──────┬─────────┐
    |│ Low Key │ Address │ Slice Generation │ Load │ Details │
    |├─────────┼─────────┼──────────────────┼──────┼─────────┤
    |│ BBB     │ pod3    │ 50 (PT0S)        │ 20.0 │         │
    |└─────────┴─────────┴──────────────────┴──────┴─────────┘
    |
    |<internal link>
    |(part 4/4)""".stripMargin
    )
  }

  test(
    "formatAssignmentChunks throws IllegalArgumentException when maxCharsPerChunk is not positive"
  ) {
    // Test plan: Verify that formatAssignmentChunks throws IllegalArgumentException when
    // maxCharsPerChunk is not positive.
    val assignment: Assignment = createAssignment(
      generation = 43 ## 50,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- ∞) @@ (43 ## 20) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    assertThrow[IllegalArgumentException]("maxCharsPerChunk") {
      AssignmentFormatter.formatAssignmentChunks(
        () => new mutable.StringBuilder(),
        assignment,
        maxResources = 16,
        maxSlices = 32,
        loadPerResourceOpt = None,
        loadPerSliceOverrideOpt = None,
        topKeysOpt = None,
        squidFilterOpt = None,
        maxCharsPerChunk = 0
      )
    }
  }

  test("appendSliceAssignmentToStringBuilder") {
    // Test plan: Verify that SliceAssignment.toString returns a string representation that is
    // readable.

    val sliceAssignment: SliceAssignment =
      ("Balin" -- "Kili") @@ (43 ## 20) ->
      Seq("https://pod1", "https://pod2", "https://pod3") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        ),
        "https://pod2" -> Seq(
          SubsliceAnnotation("Bofur" -- "Dwalin", 10, stateTransferOpt = None)
        )
      )
    logger.info(sliceAssignment.toString)
    assert(
      sliceAssignment.toString == """
  |
  |┌──────────┬──────────────────────┬──────────────────┬─────────────────────┬──────────────────────────────────────────────┐
  |│ Low Key  │ Address              │ Slice Generation │ Load                │ Details                                      │
  |├──────────┼──────────────────────┼──────────────────┼─────────────────────┼──────────────────────────────────────────────┤
  |│ Balin    │ pod1                 │ 20 (PT0S)        │ 0.03527832021245558 │                                              │
  |│ |        │ pod2                 │ -                │ -                   │                                              │
  |│ |        │ pod3                 │ -                │ -                   │                                              │
  |│ |-Bifur  │ pod1 @10 (PT-0.01S)  │ -                │ -                   │                                              │
  |│ |        │ pod2                 │ -                │ -                   │                                              │
  |│ |        │ pod3                 │ -                │ -                   │                                              │
  |│ |-Bofur  │ pod1                 │ -                │ -                   │                                              │
  |│ |        │ pod2 @10 (PT-0.01S)  │ -                │ -                   │                                              │
  |│ |        │ pod3                 │ -                │ -                   │                                              │
  |│ |-Dwalin │ pod1 @15 (PT-0.005S) │ -                │ -                   │                                              │
  |│ |        │ pod2                 │ -                │ -                   │                                              │
  |│ |        │ pod3                 │ -                │ -                   │                                              │
  |│ |-Fili   │ pod1                 │ -                │ -                   │                                              │
  |│ |        │ pod2                 │ -                │ -                   │                                              │
  |│ |        │ pod3                 │ -                │ -                   │                                              │
  |│ Kili     │ ...                  │ ...              │ ...                 │ 0 slice(s) omitted and 1 unassigned range(s) │
  |└──────────┴──────────────────────┴──────────────────┴─────────────────────┴──────────────────────────────────────────────┘
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder truncation -- same load") {
    // Test plan: Verify that when truncating slices with the same load, the slices
    // with lower keys are preferred.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Kili" -- "Nori") @@ (43 ## 30) -> Seq("https://pod1")).withPrimaryRateLoad(42.0),
      (("Nori" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 42.0 │                                              │
  |│ Balin   │ pod1    │ 20 (PT-0.03S)    │ 42.0 │                                              │
  |│ Kili    │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test("appendAssignmentToStringBuilder formats Assignment - long slice keys") {
    // Test plan: verifies that the formatter correctly handles long slice keys.

    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBBBBBBBBBBBBBBBBBBBBBBB") @@ (43 ## 10) -> Seq("https://pod1"))
        .withPrimaryRateLoad(10.0),
      (("BBBBBBBBBBBBBBBBBBBBBBBB" -- "CCCCCCCCCCCCCCCCCCCCCCCCCCCC") @@ (43 ## 50) -> Seq(
        "https://pod2"
      )).withPrimaryRateLoad(20.0),
      (("CCCCCCCCCCCCCCCCCCCCCCCCCCCC" -- "DDDDDDDDDDDDDDDDDDDDDDDD") @@ (43 ## 50) -> Seq(
        "https://pod3"
      )).withPrimaryRateLoad(30.0),
      (("DDDDDDDDDDDDDDDDDDDDDDDD" -- "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE") @@ (43 ## 50) -> Seq(
        "https://pod1"
      )).withPrimaryRateLoad(40.0),
      (("EEEEEEEEEEEEEEEEEEEEEEEEEEEEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod2"))
        .clearPrimaryRateLoad()
    )

    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 3,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )

    assert(
      builder.toString ==
      s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
  |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |3 of 5 slices with highest load:
  |┌───────────────────────────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key                       │ Address │ Slice Generation │ Load │ Details                                      │
  |├───────────────────────────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""                            │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |│ BBBBBBBBBBBBBBBBBBBBBBBB      │ pod2    │ 50 (PT0S)        │ 20.0 │                                              │
  |│ CCCCCCCCCCCCCCCCCCCCCCCCCCCC  │ pod3    │ 50 (PT0S)        │ 30.0 │                                              │
  |│ DDDDDDDDDDDDDDDDDDDDDDDD      │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEEEEEEEEEEEEEEEEEEEEEEEEEEEE │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |└───────────────────────────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin
    )
  }

  test("appendAssignmentToStringBuilder truncation -- absent primary rate load treated as 0") {
    // Test plan: Verify that slices with no primary rate load are treated as load 0.0 when
    // selecting which slices to keep.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).clearPrimaryRateLoad(),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("CCC" -- ∞) @@ (43 ## 30) -> Seq("https://pod1")).withPrimaryRateLoad(20.0)
    )
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 3 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
  |│ BBB     │ pod1    │ 20 (PT-0.03S)    │ 10.0 │                                              │
  |│ CCC     │ pod1    │ 30 (PT-0.02S)    │ 20.0 │                                              │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test("appendAssignmentToStringBuilder truncation -- maxSlices >= total slices shows all") {
    // Test plan: Verify that when maxSlices is greater than or equal to the total number of
    // slices, all slices are shown without a truncation header.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("CCC" -- ∞) @@ (43 ## 30) -> Seq("https://pod1")).withPrimaryRateLoad(30.0)
    )
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 5,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |┌─────────┬─────────┬──────────────────┬──────┬─────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details │
  |├─────────┼─────────┼──────────────────┼──────┼─────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 10.0 │         │
  |│ BBB     │ pod1    │ 20 (PT-0.03S)    │ 20.0 │         │
  |│ CCC     │ pod1    │ 30 (PT-0.02S)    │ 30.0 │         │
  |└─────────┴─────────┴──────────────────┴──────┴─────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test("appendAssignmentToStringBuilder throws IllegalArgumentException with negative maxSlices") {
    // Test plan: Verify that appendAssignmentToStringBuilder throws IllegalArgumentException
    // when maxSlices is negative.
    val assignment: Assignment = createAssignment(
      generation = 43 ## 50,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- ∞) @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(20.0)
    )
    assertThrow[IllegalArgumentException]("maxSlices") {
      AssignmentFormatter.appendAssignmentToStringBuilder(
        assignment = assignment,
        builder = new mutable.StringBuilder(),
        maxResources = 16,
        maxSlices = -1,
        loadPerResourceOpt = None,
        loadPerSliceOverrideOpt = None,
        topKeysOpt = None,
        squidFilterOpt = None
      )
    }
  }

  test(
    "appendDiffAssignmentToStringBuilder throws IllegalArgumentException with negative maxSlices"
  ) {
    // Test plan: Verify that appendDiffAssignmentToStringBuilder throws IllegalArgumentException
    // when maxSlices is negative.
    val assignmentGeneration: Generation = 0 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "Balin") @@ (0 ## 10) -> Seq("https://pod1", "https://pod2"))
        .withPrimaryRateLoad(42.0),
      (("Balin" -- "Kili") @@ (0 ## 20) -> Seq("https://pod1") | Map(
        "https://pod1" ->
        Seq(
          SubsliceAnnotation("Bifur" -- "Bofur", 10, stateTransferOpt = None),
          SubsliceAnnotation("Dwalin" -- "Fili", 15, stateTransferOpt = None)
        )
      )).clearPrimaryRateLoad(),
      (("Kili" -- "Nori") @@ (0 ## 30) -> Seq("https://pod1") | Map(
        "https://pod1" -> Seq(
          SubsliceAnnotation("Kili" -- "Nori", 10, stateTransferOpt = None)
        )
      )).withPrimaryRateLoad(100.5),
      (("Nori" -- ∞) @@ (0 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val diffAssignment: DiffAssignment = assignment.toDiff(0 ## 49)

    assertThrow[IllegalArgumentException]("maxSlices") {
      AssignmentFormatter.appendDiffAssignmentToStringBuilder(
        diffAssignment = diffAssignment,
        builder = new mutable.StringBuilder(),
        maxResources = 16,
        maxSlices = -1
      )
    }
  }

  test(
    "appendAssignmentToStringBuilder truncation -- unassigned range and omitted slice at start"
  ) {
    // Test plan: Verify that a contiguous gap containing an unassigned range and an omitted slice
    // at the start of the table is collapsed into a single summary row.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod3")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(50.0)
    )
    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = squidFilterOpt
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 1 unassigned range(s) │
  |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ EEE     │ pod1    │ 50 (PT0S)        │ 50.0 │                                              │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test(
    "appendAssignmentToStringBuilder truncation -- unassigned range and kept slice at start"
  ) {
    // Test plan: Verify that a gap containing an unassigned range followed by a kept slice
    // at the start of the table is handled as a summary row followed by the kept slice.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod3")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(50.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("EEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(10.0)
    )
    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = squidFilterOpt
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ ...     │ ...              │ ...  │ 0 slice(s) omitted and 1 unassigned range(s) │
  |│ BBB     │ pod1    │ 50 (PT0S)        │ 50.0 │                                              │
  |│ CCC     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
  |│ DDD     │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 0 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test(
    "appendAssignmentToStringBuilder truncation -- omitted slices and unassigned ranges at end"
  ) {
    // Test plan: Verify that a contiguous gap containing both omitted slices and an unassigned
    // range at the end of the table is collapsed into a single summary row.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(50.0),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(99.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("EEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(20.0)
    )
    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = squidFilterOpt
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 50.0 │                                              │
  |│ BBB     │ pod1    │ 20 (PT-0.03S)    │ 40.0 │                                              │
  |│ CCC     │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 1 unassigned range(s) │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test(
    "appendAssignmentToStringBuilder truncation -- omitted slices and unassigned ranges in middle"
  ) {
    // Test plan: Verify that a contiguous gap containing both omitted slices and an unassigned
    // range in the middle of the table is collapsed into a single summary row.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(50.0),
      (("BBB" -- "CCC") @@ (43 ## 20) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(10.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(20.0),
      (("EEE" -- ∞) @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(99.0)
    )
    val squidFilterOpt: Option[Squid] = Some(createTestSquid("https://pod1"))
    val builder = mutable.StringBuilder.newBuilder
    AssignmentFormatter.appendAssignmentToStringBuilder(
      assignment,
      builder,
      maxResources = 16,
      maxSlices = 2,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = squidFilterOpt
    )
    assert(builder.toString == s"""$assignmentGeneration
  |
  |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
  |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
  |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
  |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
  |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
  |
  |2 of 4 slices with highest load:
  |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
  |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
  |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
  |│ ""      │ pod1    │ 10 (PT-0.04S)    │ 50.0 │                                              │
  |│ BBB     │ ...     │ ...              │ ...  │ 2 slice(s) omitted and 1 unassigned range(s) │
  |│ EEE     │ pod1    │ 50 (PT0S)        │ 99.0 │                                              │
  |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
  |
  |<internal link>
  |""".stripMargin)
  }

  test("formatAssignmentChunks formats Assignment with truncation and chunking combined") {
    // Test plan: Verify that when truncating slices and chunking the output, the slices with
    // the highest load are chosen and correctly split across chunks.
    val assignmentGeneration: Generation = 43 ## 50
    val assignment: Assignment = createAssignment(
      generation = assignmentGeneration,
      AssignmentConsistencyMode.Affinity,
      (("" -- "BBB") @@ (43 ## 10) -> Seq("https://pod1")).withPrimaryRateLoad(10.0),
      (("BBB" -- "CCC") @@ (43 ## 50) -> Seq("https://pod2")).withPrimaryRateLoad(20.0),
      (("CCC" -- "DDD") @@ (43 ## 50) -> Seq("https://pod3")).withPrimaryRateLoad(30.0),
      (("DDD" -- "EEE") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("EEE" -- "FFF") @@ (43 ## 50) -> Seq("https://pod1")).withPrimaryRateLoad(40.0),
      (("FFF" -- ∞) @@ (43 ## 50) -> Seq("https://pod2")).clearPrimaryRateLoad()
    )
    val chunks: Seq[mutable.StringBuilder] = AssignmentFormatter.formatAssignmentChunks(
      () => new mutable.StringBuilder(),
      assignment,
      maxResources = 16,
      maxSlices = 4,
      loadPerResourceOpt = None,
      loadPerSliceOverrideOpt = None,
      topKeysOpt = None,
      squidFilterOpt = None,
      maxCharsPerChunk = 2000
    )
    assert(chunks(0).toString == s"""$assignmentGeneration
    |
    |┌──────────────┬──────────────────────────────────────┬──────────────────────┬─────────────────┐
    |│ Address      │ Resource UUID                        │ Creation Time        │ Attributed Load │
    |├──────────────┼──────────────────────────────────────┼──────────────────────┼─────────────────┤
    |│ https://pod1 │ d1eaf1f9-7b39-3651-bb77-1d10433dacfd │ 2023-03-31T16:12:12Z │ -               │
    |│ https://pod2 │ 570ee4ff-e8ec-3177-a6bc-b726beb829c9 │ 2023-03-31T16:12:12Z │ -               │
    |│ https://pod3 │ 5751882d-485c-3c8a-94ef-5166d2191616 │ 2023-03-31T16:12:12Z │ -               │
    |└──────────────┴──────────────────────────────────────┴──────────────────────┴─────────────────┘
    |
    |4 of 6 slices with highest load:
    |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
    |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
    |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
    |│ ""      │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
    |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
    |
    |<internal link>
    |(part 1/2)""".stripMargin)
    assert(chunks(1).toString == s"""$assignmentGeneration
    |┌─────────┬─────────┬──────────────────┬──────┬──────────────────────────────────────────────┐
    |│ Low Key │ Address │ Slice Generation │ Load │ Details                                      │
    |├─────────┼─────────┼──────────────────┼──────┼──────────────────────────────────────────────┤
    |│ BBB     │ pod2    │ 50 (PT0S)        │ 20.0 │                                              │
    |│ CCC     │ pod3    │ 50 (PT0S)        │ 30.0 │                                              │
    |│ DDD     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
    |│ EEE     │ pod1    │ 50 (PT0S)        │ 40.0 │                                              │
    |│ FFF     │ ...     │ ...              │ ...  │ 1 slice(s) omitted and 0 unassigned range(s) │
    |└─────────┴─────────┴──────────────────┴──────┴──────────────────────────────────────────────┘
    |
    |<internal link>
    |(part 2/2)""".stripMargin)
  }
}
