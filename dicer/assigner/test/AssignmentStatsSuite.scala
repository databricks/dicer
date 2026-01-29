package com.databricks.dicer.assigner

import scala.concurrent.duration._
import scala.util.Random

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.ReservationHintP
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.{FakeTypedClock, TestUtils}
import com.databricks.dicer.assigner.AssignmentStats.{AssignmentChangeStats, AssignmentLoadStats}
import com.databricks.dicer.assigner.config.ChurnConfig
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  LoadBalancingConfig,
  LoadBalancingMetricConfig
}
import com.databricks.dicer.assigner.algorithm.LoadMap
import com.databricks.dicer.assigner.algorithm.LoadMap.Entry
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  ProposedAssignment,
  SliceAssignment,
  TestSliceUtils
}
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

class AssignmentStatsSuite extends DatabricksTest with TestName {

  /** Clock used to determine assignment generations. */
  private val fakeClock = new FakeTypedClock()

  /** Random number generator with randomly generated logged seed. */
  private val rng: Random = TestUtils.newRandomWithLoggedSeed()

  /**
   * Wrapper function for [[AssignmentChangeStats.calculate]] that additionally validates the
   * following invariants:
   * - Each of the churn ratios (additionChurnRatio, removalChurnRatio, loadBalancingChurnRatio) is
   *   non-negative.
   * - The sum of the churn ratios is at most 1.0.
   */
  @throws[AssertionError]("If any of the invariants above is not met.")
  private def calculateAndValidateAssignmentChangeStats(
      prevAsn: Assignment,
      curAsn: Assignment,
      loadMap: LoadMap
  ): AssignmentChangeStats = {
    // Calculate the assignment change stats.
    val assignmentChangeStats = AssignmentChangeStats.calculate(prevAsn, curAsn, loadMap)

    // Validate that each of the churn ratios is non-negative.
    assert(
      assignmentChangeStats.removalChurnRatio >= 0.0,
      "removalChurnRatio should be non-negative, but got " +
      assignmentChangeStats.removalChurnRatio
    )
    assert(
      assignmentChangeStats.additionChurnRatio >= 0.0,
      "additionChurnRatio should be non-negative, but got " +
      assignmentChangeStats.additionChurnRatio
    )
    assert(
      assignmentChangeStats.loadBalancingChurnRatio >= 0.0,
      "loadBalancingChurnRatio should be non-negative, but got " +
      assignmentChangeStats.loadBalancingChurnRatio
    )

    // Validate that the sum of churn ratios is at most 1.0.
    val churnRatioSum: Double =
      assignmentChangeStats.removalChurnRatio +
      assignmentChangeStats.additionChurnRatio +
      assignmentChangeStats.loadBalancingChurnRatio
    assert(
      churnRatioSum <= 1.0,
      s"The sum of churn ratios should be at most 1.0, but got $churnRatioSum"
    )

    // Return the assignment change stats.
    assignmentChangeStats
  }

  test("AssignmentChangeStats.calculate prefers removal churn over addition churn") {
    // Test plan: Create an assignment change where the set of assigned resources has been
    // completely changed, and verify that the assignment change stats are calculated correctly,
    // with preference for attributing churn to removal churn over addition churn.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 1.0 / 3.0))
        .putLoad(Entry(HALF_POINT -- ∞, 2.0 / 3.0))
        .build()
    val prevAsn: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )
    val curAsn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("pod2"),
      (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("pod3")
    )

    // Calculate and verify the assignment change stats from prevAsn to curAsn.
    // Since the set of assigned resources has been completely changed, the churn ratio is 1.0 for
    // removal.
    val assignmentChangeStats: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)
    assert(assignmentChangeStats.removalChurnRatio == 1.0)
    assert(assignmentChangeStats.additionChurnRatio == 0)
    assert(assignmentChangeStats.loadBalancingChurnRatio == 0)
    assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)
  }

  test("AssignmentChangeStats.calculate prefers addition churn over load balancing churn") {
    // Test plan: Create multiple assignments, and verify that the assignment change stats are
    // calculated correctly, with preference for attributing churn to addition churn over load
    // balancing churn.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- HALF_POINT, 2.0 / 3.0),
          Entry(HALF_POINT -- ∞, 1.0 / 3.0)
        )
        .build()
    val asn0: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )

    // Create the second assignment.
    // Split [0, HALF_POINT) into [0, HALF_POINT / 2) and [HALF_POINT / 2, HALF_POINT),
    // with the former being reassigned to pod2.
    val asn1: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod2"),
      (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )

    // Calculate and verify the assignment change stats from asn0 to asn1.
    val assignmentChangeStats: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(asn0, asn1, loadMap)
    assert(assignmentChangeStats.additionChurnRatio == 1.0 / 3.0)
    assert(assignmentChangeStats.removalChurnRatio == 0)
    assert(assignmentChangeStats.loadBalancingChurnRatio == 0)
    assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)

    // Create the third assignment.
    // Slice [0, HALF_POINT / 2) and [HALF_POINT / 2, HALF_POINT) switches pods.
    // pod1 is removed and Slice [HALF_POINT -- ∞) is reassigned to pod0.
    val asn2: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod0"),
      (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod2"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod0")
    )

    // Calculate and verify the assignment change stats from asn1 to asn2.
    val assignmentChangeStats2: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(asn1, asn2, loadMap)
    assert(assignmentChangeStats2.additionChurnRatio == 0)
    assert(assignmentChangeStats2.removalChurnRatio == 1.0 / 3.0)
    assert(assignmentChangeStats2.loadBalancingChurnRatio == 2.0 / 3.0)
    assert(assignmentChangeStats2.isMeaningfulAssignmentChange == true)
  }

  test("AssignmentChangeStats.calculate does not equate 0 load to resource removal") {
    // Test plan: Create an assignment change where the one resource has positive load before and 0
    // load after, and verify that the assignment change stats are calculated correctly, with
    // all churn attributed to load balancing churn.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 1.0))
        .putLoad(Entry(HALF_POINT -- ∞, 0.0))
        .build()
    val prevAsn: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )
    val curAsn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("pod1"),
      (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("pod0")
    )

    // Calculate and verify the assignment change stats from prevAsn to curAsn.
    // The load shifts entirely from pod0 to pod1, so all churn should be attributed to load
    // balancing churn.
    val assignmentChangeStats: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)
    assert(assignmentChangeStats.removalChurnRatio == 0)
    assert(assignmentChangeStats.additionChurnRatio == 0)
    assert(assignmentChangeStats.loadBalancingChurnRatio == 1.0)
    assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)
  }

  test("AssignmentChangeStats.calculate handles assignment changes with multiple replicas") {
    // Test plan: Verify that when there are multiple resources assigned to a Slice, where some of
    // the previous resources are removed from the assignment and some current resources are newly
    // added to the assignment, the churn ratio is first attributed to removal, then to addition,
    // and finally to load balancing.

    // Case 1: There are both removed and newly added resources.
    {
      // Setup: Create a previous and a current assignment, total load is 100.
      //
      // old asn: r1: 50, r2: 50
      // new asn:         r2: 33, r3: 33, r4: 33
      val loadMap =
        LoadMap
          .newBuilder()
          .putLoad(Entry("" -- ∞, 100))
          .build()
      val prevAsn: Assignment = createAssignment(
        12 ## 42,
        AssignmentConsistencyMode.Affinity,
        ("" -- ∞) @@ (12 ## 42) -> Seq("r1", "r2")
      )
      val curAsn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- ∞) @@ (12 ## 59) -> Seq("r2", "r3", "r4")
      )
      val assignmentChangeStats: AssignmentChangeStats =
        calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)

      // Verify: 33 units of load didn't move (on r2), yielding a total churn of 100 - 33 = 67.
      // Account them as much as possible to removal, then addition and finally load balancing,
      // resulting:
      // Removal churn: 50, addition churn: 17, load balancing churn: 00.
      assertResult(expected = 50)(Math.round(assignmentChangeStats.removalChurnRatio * 100))
      assertResult(expected = 17)(Math.round(assignmentChangeStats.additionChurnRatio * 100))
      assertResult(expected = 0)(Math.round(assignmentChangeStats.loadBalancingChurnRatio * 100))
      assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)
    }

    // Case 2: There is no removed or added resource and there is only load balancing churn.
    {
      // Setup: Create a previous and a current assignment, total load is 100.
      //
      // "" -------------------------------------- Kili ---------------------------------------- ∞
      // total:             42                      |                    42
      // old asn: r1: 21, r2: 21                    |                    r3: 21, r4: 21
      // new asn:         r2: 14, r3: 14, r4: 14    |    r1: 14, r2: 14, r3: 14
      val loadMap =
        LoadMap
          .newBuilder()
          .putLoad(Entry("" -- "Kili", 42))
          .putLoad(Entry("Kili" -- ∞, 42))
          .build()
      val prevAsn: Assignment = createAssignment(
        12 ## 42,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Kili") @@ (12 ## 42) -> Seq("r1", "r2"),
        ("Kili" -- ∞) @@ (12 ## 42) -> Seq("r2", "r3", "r4")
      )
      val curAsn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Kili") @@ (12 ## 59) -> Seq("r3", "r4"),
        ("Kili" -- ∞) @@ (12 ## 59) -> Seq("r1", "r2", "r3")
      )
      val assignmentChangeStats: AssignmentChangeStats =
        calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)

      // Verify: Both ["", Kili) and [Kili, ∞) have 21 - 14 = 14 load not moved, so the churn load
      // on each of them is 42 - 14 = 28, and total churn load on the assignment is 28 * 2 = 56.
      // There's no removed or additional resource, so all of them are load balancing churn.
      assertResult(expected = 0)(Math.round(assignmentChangeStats.removalChurnRatio * 42 * 2))
      assertResult(expected = 0)(Math.round(assignmentChangeStats.additionChurnRatio * 42 * 2))
      assertResult(expected = 56)(
        Math.round(assignmentChangeStats.loadBalancingChurnRatio * 42 * 2)
      )
      assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)
    }

    // Case 3: No churn but with large and complex load values.
    {
      val loadMap =
        LoadMap
          .newBuilder()
          // Use large and complex values for the load, to intentionally trigger floating point
          // calculation error.
          .putLoad(Entry("" -- "Nori", 39284392.32994))
          .putLoad(Entry("Nori" -- ∞, 953923235.23439938))
          .build()
      // Assigned exactly the same set of resources before and after, for all Slices.
      val prevAsn: Assignment = createAssignment(
        12 ## 42,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Fili") @@ (12 ## 42) -> Seq("r1", "r2", "r3"),
        ("Fili" -- ∞) @@ (12 ## 42) -> Seq("r1", "r2", "r3")
      )
      val curAsn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Fili") @@ (12 ## 59) -> Seq("r1", "r2", "r3"),
        ("Fili" -- "Kili") @@ (12 ## 59) -> Seq("r1", "r2", "r3"),
        ("Kili" -- ∞) @@ (12 ## 59) -> Seq("r1", "r2", "r3")
      )
      val assignmentChangeStats: AssignmentChangeStats =
        calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)

      // Verify: The churn ratios are 0.0.
      assertResult(expected = 0.0)(assignmentChangeStats.additionChurnRatio)
      assertResult(expected = 0.0)(assignmentChangeStats.removalChurnRatio)
      assertResult(expected = 0.0)(assignmentChangeStats.loadBalancingChurnRatio)
      assert(assignmentChangeStats.isMeaningfulAssignmentChange == false)
    }
  }

  test("AssignmentChangeStats.calculate handles zero total load gracefully") {
    // Test plan: Provide `AssignmentChangeStats.calculate` with zero total load, and verify that
    // the churn ratios are 0.0 as opposed to NaN. Additionally, verify that the assignment
    // change is meaningful because the SliceMap has changed, even though the total load churn
    // is also 0.0.

    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- "Kili", 0))
        .putLoad(Entry("Kili" -- ∞, 0))
        .build()
    val prevAsn: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Kili") @@ (12 ## 42) -> Seq("r1", "r2"),
      ("Kili" -- ∞) @@ (12 ## 42) -> Seq("r2", "r3", "r4")
    )
    val curAsn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Kili") @@ (12 ## 59) -> Seq("r3", "r4"),
      ("Kili" -- ∞) @@ (12 ## 59) -> Seq("r1", "r2", "r3")
    )
    val assignmentChangeStats: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)

    // Verify: The churn ratios are 0.0.
    assert(assignmentChangeStats.additionChurnRatio == 0.0)
    assert(assignmentChangeStats.removalChurnRatio == 0.0)
    assert(assignmentChangeStats.loadBalancingChurnRatio == 0.0)

    // Verify: The assignment change is meaningful because the SliceMap has changed, even though
    // the total load churn is 0.0.
    assert(assignmentChangeStats.isMeaningfulAssignmentChange == true)
  }

  test("AssignmentChangeStats.calculate does not falsely report meaningful assignment changes") {
    // Test plan: Create an assignment change where the slice boundaries are changed, but the
    // underlying resources for each key are the same. Verify that the assignment change is not
    // reported as meaningful.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue
    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT / 2, 1.0 / 4.0))
        .putLoad(Entry(HALF_POINT / 2 -- HALF_POINT, 1.0 / 4.0))
        .putLoad(Entry(HALF_POINT -- ∞, 1.0 / 2.0))
        .build()
    val prevAsn: Assignment = createAssignment(
      12 ## 42,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 8) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT / 8 -- HALF_POINT / 4) @@ (12 ## 42) -> Seq("pod0"),
      (HALF_POINT / 4 -- ∞) @@ (12 ## 42) -> Seq("pod1")
    )
    val curAsn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT / 4) @@ (12 ## 59) -> Seq("pod0"),
      (HALF_POINT / 4 -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod1"),
      (HALF_POINT / 2 -- ∞) @@ (12 ## 59) -> Seq("pod1")
    )

    // Calculate and verify the assignment change stats from prevAsn to curAsn.
    // Since the underlying resources for each key are the same, the assignment change is not
    // reported as meaningful, and the total churn (and thus churn ratios) are 0.0.
    val assignmentChangeStats: AssignmentChangeStats =
      calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)
    assert(assignmentChangeStats.removalChurnRatio == 0.0)
    assert(assignmentChangeStats.additionChurnRatio == 0.0)
    assert(assignmentChangeStats.loadBalancingChurnRatio == 0.0)
    assert(assignmentChangeStats.isMeaningfulAssignmentChange == false)
  }

  test(
    "AssignmentChangeStats.calculate does not generate invalid churn ratios with randomized inputs"
  ) {
    // Test plan: Stress test this method by generating random load maps and assignments. All
    // calculated churn ratios should be in the range [0.0, 1.0], and the sum of the churn ratios
    // should not exceed 1.0. Violations to these invariants would lead to the AssignmentChangeStats
    // constructor throwing an error and this test failing.

    // Create a set of resources.
    val numResources: Int = 10
    val resources: IndexedSeq[Squid] = (0 until numResources).map { i =>
      createTestSquid(s"resource$i")
    }

    // Generate an initial previous assignment.
    var prevAsn: Assignment =
      ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(
          numSlices = 10,
          resources = resources,
          numMaxReplicas = numResources,
          rng
        )
      ).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        TestSliceUtils.createLooseGeneration(fakeClock.instant().toEpochMilli)
      )

    for (_ <- 0 until 10000) {
      // Randomly generate a load map with at least 20 slices, guaranteeing that there will be more
      // than one Slice per resource.
      val loadMapNumSlices: Int = rng.nextInt(100) + 20
      val loadMap: LoadMap = createRandomLoadMap(rng, totalLoad = 100, loadMapNumSlices)

      // Generate a current assignment.
      val curAsnSlices: Int = rng.nextInt(100) + 20
      val curAsn: Assignment =
        ProposedAssignment(
          predecessorOpt = None,
          createRandomProposal(
            numSlices = curAsnSlices,
            resources = resources,
            numMaxReplicas = numResources,
            rng
          )
        ).commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          TestSliceUtils.createLooseGeneration(fakeClock.instant().toEpochMilli)
        )

      // Calculate the assignment change stats, and just verify that it does not throw.
      calculateAndValidateAssignmentChangeStats(prevAsn, curAsn, loadMap)

      prevAsn = curAsn
    }
  }

  test("AssignmentLoadStats.calculate handles loadMap whose slice boundaries match assignment's") {
    // Test plan: Create a manual assignment and loadMap where the slices in the loadMap exactly
    // match the slices in the assignment. Verify that the per-resource / per-slice load info
    // matches what we expect.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 1.0 / 3.0))
        .putLoad(Entry(HALF_POINT -- ∞, 2.0 / 3.0))
        .build()
    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2"),
      (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")
    )

    // Calculate the load stats.
    val loadStats = AssignmentLoadStats.calculate(asn, loadMap)

    // Verify per-resource load.
    // With the current loadMap:
    // - r1: 1/2 * 1/3 = 1/6
    // - r2: 1/2 * 1/3 + 1/2 * 2/3 = 1/2
    // - r3: 1/2 * 2/3 = 1/3
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 1.0 / 6.0,
      "r2" -> 1.0 / 2.0,
      "r3" -> 1.0 / 3.0
    )
    val loadPerResource: Map[Squid, Double] = loadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // With the current loadMap:
    // - slice0: 1/3
    // - slice1: 2/3
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 1.0 / 3.0),
      (assignmentSlices(1), 2.0 / 3.0)
    )
    val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test("calculateAdjustedLoadStats handles loadMap whose slice boundaries match assignment's") {
    // Test plan: Create a manual assignment and loadMap where the slices in the loadMap exactly
    // match the slices in the assignment. Verify that the per-resource / per-slice load info,
    // adjusted for the uniform load reservation, matches what we expect.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val loadMap =
      LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 1.0 / 3.0))
        .putLoad(Entry(HALF_POINT -- ∞, 2.0 / 3.0))
        .build()
    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2"),
      (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")
    )

    // Calculate the load stats.
    val adjustedLoadStats =
      AssignmentLoadStats.calculateAdjustedLoadStats(asn, loadMap, loadBalancingConfig)

    // Adjusted Load (reported load + reserved uniform load):
    // Total reserved uniform load
    // = maxLoadHint * uniformLoadReservationHint * numResource
    // = 10 * 0.1 * 3 = 3
    // - r1: 1/6 + 3/4 = 11/12
    // - r2: 1/2 + 3/2 = 2
    // - r3: 1/3 + 3/4 = 13/12
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 11.0 / 12.0,
      "r2" -> 2.0,
      "r3" -> 13.0 / 12.0
    )
    val loadPerResource: Map[Squid, Double] = adjustedLoadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // With the adjusted loadMap:
    // - slice0: 1/3 + 3/2 = 11/6
    // - slice1: 2/3 + 3/2 = 13/6
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 11.0 / 6.0),
      (assignmentSlices(1), 13.0 / 6.0)
    )
    val loadPerSlice: Map[Slice, Double] = adjustedLoadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] =
      adjustedLoadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test(
    "AssignmentLoadStats.calculate handles loadMap whose slice boundaries do not match assignment's"
  ) {
    // Test plan: Create manual assignments and loadMaps, and verify that the per-resource /
    // per-slice load info matches what we expect. The slices in the loadMap do not exactly match
    // the slices in the assignment. We separate the test into two cases:
    // 1. The assignment has more fine-grained slices than the loadMap.
    // 2. The assignment has fewer fine-grained slices than the loadMap.
    // This is done to ensure that the number of slices per resource is correctly calculated
    // according to the slices defined in the assignment (and not the loadMap).

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    // Case 1: The assignment has more fine-grained slices than the loadMap.
    {
      val loadMap = LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 2.0 / 3.0), Entry(HALF_POINT -- ∞, 1.0 / 3.0))
        .build()
      val asn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- HALF_POINT / 4) @@ (12 ## 59) -> Seq("pod2"), // slice0
        (HALF_POINT / 4 -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod2"), // slice1
        (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"), // slice2
        (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1") // slice3
      )

      // Calculate the load stats.
      val loadStats = AssignmentLoadStats.calculate(asn, loadMap)

      // Verify per-resource load.
      // With the current loadMap:
      // - pod0: 1/2 * 2/3 = 1/3
      // - pod1: 1/2 * 2/3 = 1/3
      // - pod2: 1 * 1/3 = 1/3
      val expectedLoadPerResource = Map[Squid, Double](
        "pod0" -> 1.0 / 3.0,
        "pod1" -> 1.0 / 3.0,
        "pod2" -> 1.0 / 3.0
      )
      val loadPerResource: Map[Squid, Double] = loadStats.loadByResource
      assertResult(expectedLoadPerResource)(loadPerResource)

      // Verify per-slice load.
      // With the current loadMap:
      // - slice0: 1/4 * 2/3 = 1/6
      // - slice1: 1/4 * 2/3 = 1/6
      // - slice2: 1/2 * 2/3 = 1/3
      // - slice3: 1 * 1/3 = 1/3
      val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
      val expectedLoadPerSlice = Map[Slice, Double](
        (assignmentSlices(0), 1.0 / 6.0), // slice0
        (assignmentSlices(1), 1.0 / 6.0), // slice1
        (assignmentSlices(2), 1.0 / 3.0), // slice2
        (assignmentSlices(3), 1.0 / 3.0) // slice3
      )
      val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
      assertResult(expectedLoadPerSlice)(loadPerSlice)

      // Verify numOfAssignedSlicesByResource.
      // - pod0: 1
      // - pod1: 1
      // - pod2: 2
      val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
        "pod0" -> 1,
        "pod1" -> 1,
        "pod2" -> 2
      )
      val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
      assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
    }

    // Case 2: The assignment has fewer fine-grained slices than the loadMap.
    {
      val loadMap = LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- HALF_POINT / 2, 1.0 / 2.0),
          Entry(HALF_POINT / 2 -- HALF_POINT, 1.0 / 2.0),
          Entry(HALF_POINT -- ∞, 0.0)
        )
        .build()
      val asn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"),
        (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("pod1")
      )

      // Calculate the load stats.
      val loadStats = AssignmentLoadStats.calculate(asn, loadMap)

      // Verify per-resource load.
      // With the current loadMap:
      // - pod0: 1/2 + 1/2 = 1
      // - pod1: 0
      val expectedLoadPerResource = Map[Squid, Double](
        "pod0" -> 1.0,
        "pod1" -> 0.0
      )
      val loadPerResource: Map[Squid, Double] = loadStats.loadByResource
      assertResult(expectedLoadPerResource)(loadPerResource)

      // Verify per-slice load.
      // With the current loadMap:
      // - slice0: 1/2 + 1/2 = 1
      // - slice1: 0
      val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
      val expectedLoadPerSlice = Map[Slice, Double](
        (assignmentSlices(0), 1.0), // slice0
        (assignmentSlices(1), 0.0) // slice1
      )
      val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
      assertResult(expectedLoadPerSlice)(loadPerSlice)

      // Verify numOfAssignedSlicesByResource.
      // - pod0: 1
      // - pod1: 1
      val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
        "pod0" -> 1,
        "pod1" -> 1
      )
      val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
      assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
    }
  }

  test(
    "calculateAdjustedLoadStats handles loadMap whose slice boundaries do not match assignment's"
  ) {
    // Test plan: Create manual assignments and loadMaps, and verify that the per-resource /
    // per-slice load info matches what we expect. The slices in the loadMap do not exactly match
    // the slices in the assignment. We separate the test into two cases:
    // 1. The assignment has more fine-grained slices than the loadMap.
    // 2. The assignment has fewer fine-grained slices than the loadMap.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    // Case 1: The assignment has more fine-grained slices than the loadMap.
    {
      val loadMap = LoadMap
        .newBuilder()
        .putLoad(Entry("" -- HALF_POINT, 2.0 / 3.0), Entry(HALF_POINT -- ∞, 1.0 / 3.0))
        .build()
      val asn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- HALF_POINT / 4) @@ (12 ## 59) -> Seq("pod2"), // slice0
        (HALF_POINT / 4 -- HALF_POINT / 2) @@ (12 ## 59) -> Seq("pod2"), // slice1
        (HALF_POINT / 2 -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"), // slice2
        (HALF_POINT -- ∞) @@ (12 ## 42) -> Seq("pod1") // slice3
      )

      // Calculate the load stats.
      val adjustedLoadStats =
        AssignmentLoadStats.calculateAdjustedLoadStats(asn, loadMap, loadBalancingConfig)

      // Adjusted Load (reported load + reserved uniform load):
      // Total reserved uniform load
      // = maxLoadHint * uniformLoadReservationHint * numResource
      // = 10 * 0.1 * 3 = 3
      // - pod0: 1/3 + 3/4 = 13/12
      // - pod1: 1/3 + 3/2 = 11/6
      // - pod2: 1/3 + 3/4 = 13/12
      val expectedAdjustedLoadPerResource = Map[Squid, Double](
        "pod0" -> 13.0 / 12.0,
        "pod1" -> 11.0 / 6.0,
        "pod2" -> 13.0 / 12.0
      )
      val adjustedLoadPerResource: Map[Squid, Double] =
        adjustedLoadStats.loadByResource
      assertResult(expectedAdjustedLoadPerResource)(adjustedLoadPerResource)

      // Verify per-slice load.
      // With the current loadMap:
      // - slice0: 1/6 + 3/8 = 13/24
      // - slice1: 1/6 + 3/8 = 13/24
      // - slice2: 1/3 + 3/4 = 13/12
      // - slice3: 1/3 + 3/2 = 11/6
      val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
      val expectedLoadPerSlice = Map[Slice, Double](
        (assignmentSlices(0), 13.0 / 24.0), // slice0
        (assignmentSlices(1), 13.0 / 24.0), // slice1
        (assignmentSlices(2), 13.0 / 12.0), // slice2
        (assignmentSlices(3), 11.0 / 6.0) // slice3
      )
      val loadPerSlice: Map[Slice, Double] = adjustedLoadStats.loadBySlice
      assertResult(expectedLoadPerSlice)(loadPerSlice)

      // Verify numOfAssignedSlicesByResource.
      // - pod0: 1
      // - pod1: 1
      // - pod2: 2
      val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
        "pod0" -> 1,
        "pod1" -> 1,
        "pod2" -> 2
      )
      val numOfAssignedSlicesByResource: Map[Squid, Int] =
        adjustedLoadStats.numOfAssignedSlicesByResource
      assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
    }

    // Case 2: The assignment has fewer fine-grained slices than the loadMap.
    {
      val loadMap = LoadMap
        .newBuilder()
        .putLoad(
          Entry("" -- HALF_POINT / 2, 1.0 / 2.0),
          Entry(HALF_POINT / 2 -- HALF_POINT, 1.0 / 2.0),
          Entry(HALF_POINT -- ∞, 0.0)
        )
        .build()
      val asn: Assignment = createAssignment(
        12 ## 59,
        AssignmentConsistencyMode.Affinity,
        ("" -- HALF_POINT) @@ (12 ## 59) -> Seq("pod0"),
        (HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("pod1")
      )

      // Calculate the load stats.
      val adjustedLoadStats =
        AssignmentLoadStats.calculateAdjustedLoadStats(asn, loadMap, loadBalancingConfig)

      // Adjusted Load (reported load + reserved uniform load):
      // Total reserved uniform load
      // = maxLoadHint * uniformLoadReservationHint * numResource
      // = 10 * 0.1 * 2 = 2
      // - pod0: 1 + 2/2 = 2
      // - pod1: 0 + 2/2 = 1
      val expectedLoadPerResource = Map[Squid, Double](
        "pod0" -> 2.0,
        "pod1" -> 1.0
      )
      val loadPerResource: Map[Squid, Double] = adjustedLoadStats.loadByResource
      assertResult(expectedLoadPerResource)(loadPerResource)

      // Verify per-slice load.
      // With the adjusted loadMap:
      // - slice0: 1 + 2/2 = 2
      // - slice1: 0 + 2/2 = 1
      val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
      val expectedLoadPerSlice = Map[Slice, Double](
        (assignmentSlices(0), 2.0), // slice0
        (assignmentSlices(1), 1.0) // slice1
      )
      val loadPerSlice: Map[Slice, Double] = adjustedLoadStats.loadBySlice
      assertResult(expectedLoadPerSlice)(loadPerSlice)

      // Verify numOfAssignedSlicesByResource.
      // - pod0: 1
      // - pod1: 1
      val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
        "pod0" -> 1,
        "pod1" -> 1
      )
      val numOfAssignedSlicesByResource: Map[Squid, Int] =
        adjustedLoadStats.numOfAssignedSlicesByResource
      assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
    }
  }

  test("calculateUniformKeyAssignmentLoadStats returns correct load stats") {
    // Test plan: Create a loadMap with a non-uniform load distribution. The number of slices in the
    // loadMap does not match the number of resources. Verify that the load stats match what we
    // expect.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    // loadMap contains 2 slices, but the assignment has 3 resources.
    val loadMap = LoadMap
      .newBuilder()
      .putLoad(Entry("" -- HALF_POINT, 1.0 / 3.0))
      .putLoad(Entry(HALF_POINT -- ∞, 2.0 / 3.0))
      .build()
    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Kili") @@ (12 ## 59) -> Seq("r1"),
      ("Kili" -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")
    )

    val resources: Set[Squid] = asn.assignedResources

    // Calculate the load stats.
    val loadStats =
      AssignmentLoadStats.calculateUniformKeyAssignmentLoadStats(resources, loadMap)

    // Verify per-resource load.
    //
    // Note: We cannot verify the per-resource load on a resource-by-resource basis since the
    // resources are not given in any particular order, and the load stats are computed with an
    // arbitrary mapping of resource to hypothetical slice. We can only verify that the values in
    // the per-resource load map are the exact same as the values in the per-slice load map.
    val perSliceLoadValues: Vector[Double] = loadStats.loadBySlice.values.toVector.sorted
    val perResourceLoadValues: Vector[Double] = loadStats.loadByResource.values.toVector.sorted
    assertResult(expected = perSliceLoadValues)(actual = perResourceLoadValues)

    // Get the new uniform slices.
    val uniformSlices: Vector[Slice] = LoadMap.getUniformPartitioning(resources.size)

    // Verify per-slice load.
    // - slice0: 2/3 * 1/3 = 2/9
    // - slice1: (1/3 * 1/3) + (1/3 * 2/3) = 1/3
    // - slice2: 2/3 * 2/3 = 4/9
    val expectedLoadPerSlice = Map[Slice, Double](
      (uniformSlices(0), 2.0 / 9.0), // slice0
      (uniformSlices(1), 1.0 / 3.0), // slice1
      (uniformSlices(2), 4.0 / 9.0) // slice2
    )
    val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 1
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 1,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test("calculateSelfTrackedLoadStats handles simple assignment") {
    // Test plan: Create a simple assignment where all slices in the assignment have load tracked
    // inside of the assignment. Verify that the per-resource / per-slice load info matches what we
    // expect.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      (("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2")).withPrimaryRateLoad(1.0 / 3.0),
      ((HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")).withPrimaryRateLoad(2.0 / 3.0)
    )

    // Calculate the load stats.
    val loadStats = AssignmentLoadStats.calculateSelfTrackedLoadStats(asn)

    // Verify per-resource load.
    // With the current loadMap:
    // - r1: 1/2 * 1/3 = 1/6
    // - r2: 1/2 * 1/3 + 1/2 * 2/3 = 1/2
    // - r3: 1/2 * 2/3 = 1/3
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 1.0 / 6.0,
      "r2" -> 1.0 / 2.0,
      "r3" -> 1.0 / 3.0
    )
    val loadPerResource: Map[Squid, Double] = loadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // With the current loadMap:
    // - slice0: 1/3
    // - slice1: 2/3
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 1.0 / 3.0),
      (assignmentSlices(1), 2.0 / 3.0)
    )
    val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test("calculateSelfTrackedLoadStats handles assignment holding slices with no load tracked") {
    // Test plan: Create an assignment where some slices in the assignment have load tracked, and
    // some do not. Verify that the per-resource / per-slice load info matches what we expect, with
    // load assumed to be 0.0 for a slice that does not have load tracked.

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    // Create an assignment where slice0 has no load tracked, and slice1 has load tracked.
    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      (("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2")).clearPrimaryRateLoad,
      ((HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")).withPrimaryRateLoad(1.0)
    )

    // Calculate the load stats.
    val loadStats = AssignmentLoadStats.calculateSelfTrackedLoadStats(asn)

    // Verify per-resource load.
    // When no load is tracked, the load is assumed to be 0.0.
    // - r1: 0
    // - r2: 0 + 1/2 * 1 = 1/2
    // - r3: 1/2 * 1 = 1/2
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 0.0,
      "r2" -> 1.0 / 2.0,
      "r3" -> 1.0 / 2.0
    )
    val loadPerResource: Map[Squid, Double] = loadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // - slice0: 0
    // - slice1: 1
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 0.0),
      (assignmentSlices(1), 1.0)
    )
    val loadPerSlice: Map[Slice, Double] = loadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] = loadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test("calculateSelfTrackedAdjustedLoadStats handles simple assignment") {
    // Test plan: Create a simple assignment where all slices in the assignment have load tracked
    // inside of the assignment. Verify that the per-resource / per-slice load info after adjustment
    // for the uniform load reservation matches what we expect.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      (("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2")).withPrimaryRateLoad(1.0 / 3.0),
      ((HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")).withPrimaryRateLoad(2.0 / 3.0)
    )

    val adjustedLoadStats: AssignmentLoadStats =
      AssignmentLoadStats.calculateSelfTrackedAdjustedLoadStats(asn, loadBalancingConfig)

    // Verify per-resource load.
    // Total reserved uniform load
    // = maxLoadHint * uniformLoadReservationHint * numResource
    // = 10 * 0.1 * 3 = 3
    // - r1: 1/6 + 3/4 = 11/12
    // - r2: 1/2 + 3/2 = 2
    // - r3: 1/3 + 3/4 = 13/12
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 11.0 / 12.0,
      "r2" -> 2.0,
      "r3" -> 13.0 / 12.0
    )
    val loadPerResource: Map[Squid, Double] = adjustedLoadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // - slice0: 1/3 + 3/2 = 11/6
    // - slice1: 2/3 + 3/2 = 13/6
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 11.0 / 6.0),
      (assignmentSlices(1), 13.0 / 6.0)
    )
    val loadPerSlice: Map[Slice, Double] = adjustedLoadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] =
      adjustedLoadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }

  test(
    "calculateSelfTrackedAdjustedLoadStats handles assignment holding slices with no load tracked"
  ) {
    // Test plan: Create an assignment where some slices in the assignment have load tracked, and
    // some do not. Verify that the per-resource / per-slice load info after adjustment for the
    // uniform load reservation matches what we expect, with load proportional to the uniform
    // distribution assigned to slices that do not have load tracked.

    val loadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = 10,
        uniformLoadReservationHint = ReservationHintP.SMALL_RESERVATION // 0.1
      )
    )

    // When using the byte representation of Longs as SliceKeys, the max value is the half-way point
    // between "" and ∞.
    val HALF_POINT: Long = Long.MaxValue

    val asn: Assignment = createAssignment(
      12 ## 59,
      AssignmentConsistencyMode.Affinity,
      (("" -- HALF_POINT) @@ (12 ## 59) -> Seq("r1", "r2")).clearPrimaryRateLoad,
      ((HALF_POINT -- ∞) @@ (12 ## 59) -> Seq("r2", "r3")).withPrimaryRateLoad(1.0)
    )

    val adjustedLoadStats: AssignmentLoadStats =
      AssignmentLoadStats.calculateSelfTrackedAdjustedLoadStats(asn, loadBalancingConfig)

    // Verify per-resource load.
    // Total reserved uniform load
    // = maxLoadHint * uniformLoadReservationHint * numResource
    // = 10 * 0.1 * 3 = 3
    // When no load is tracked, the load is assumed to be uniformly distributed according to the
    // proportion of the slice size over the total keyspace.
    // - r1: 1/4 + 3/4 = 1
    // - r2: 1/4 + 1/2 + 3/2 = 9/4
    // - r3: 1/2 + 3/4 = 5/4
    val expectedLoadPerResource = Map[Squid, Double](
      "r1" -> 1.0,
      "r2" -> 9.0 / 4.0,
      "r3" -> 5.0 / 4.0
    )
    val loadPerResource: Map[Squid, Double] = adjustedLoadStats.loadByResource
    assertResult(expectedLoadPerResource)(loadPerResource)

    // Verify per-slice load.
    // - slice0: 1/2 + 3/2 = 2
    // - slice1: 1 + 3/2 = 5/2
    val assignmentSlices: Seq[Slice] = asn.sliceAssignments.map((_: SliceAssignment).slice)
    val expectedLoadPerSlice = Map[Slice, Double](
      (assignmentSlices(0), 2.0),
      (assignmentSlices(1), 5.0 / 2.0)
    )
    val loadPerSlice: Map[Slice, Double] = adjustedLoadStats.loadBySlice
    assertResult(expectedLoadPerSlice)(loadPerSlice)

    // Verify numOfAssignedSlicesByResource.
    // - r1: 1
    // - r2: 2
    // - r3: 1
    val expectedNumOfAssignedSlicesByResource = Map[Squid, Int](
      "r1" -> 1,
      "r2" -> 2,
      "r3" -> 1
    )
    val numOfAssignedSlicesByResource: Map[Squid, Int] =
      adjustedLoadStats.numOfAssignedSlicesByResource
    assertResult(expectedNumOfAssignedSlicesByResource)(numOfAssignedSlicesByResource)
  }
}
