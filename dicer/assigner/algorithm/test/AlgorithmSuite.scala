package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable
import scala.collection.immutable.IndexedSeq
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Random

import org.scalatest.Suite

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.caching.util.TestUtils.ParameterizedTestNameDecorator
import com.databricks.dicer.assigner.AssignmentStats.{AssignmentChangeStats, AssignmentLoadStats}
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig
}
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  ProposedAssignment,
  ProposedSliceAssignment,
  SliceAssignment,
  SliceKeyHelper,
  SliceMapHelper
}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.testing.DatabricksTest

/**
 * The suite that tests the algorithm across various different key replication configs. Also see
 * [[NonParameterizedAlgorithmSuite]] and [[AlgorithmChaosSuite]] for other algorithm test suites.
 */
class AlgorithmSuite extends DatabricksTest {

  override def nestedSuites: IndexedSeq[Suite] = {
    IndexedSeq(
      new ParameterizedAlgorithmSuite(KeyReplicationConfig(minReplicas = 1, maxReplicas = 1)),
      new ParameterizedAlgorithmSuite(KeyReplicationConfig(minReplicas = 3, maxReplicas = 3)),
      new ParameterizedAlgorithmSuite(KeyReplicationConfig(minReplicas = 3, maxReplicas = 5))
    )
  }
}

/**
 * The suite containing algorithm tests that are parameterized with various
 * [[KeyReplicationConfig]]s. Test cases in this suite will create algorithm configs by default with
 * the given [[keyReplicationConfig]] (see [[defaultTargetConfig]]).
 */
class ParameterizedAlgorithmSuite(keyReplicationConfig: KeyReplicationConfig)
    extends AlgorithmSuiteBase
    with ParameterizedTestNameDecorator {

  override val paramsForDebug: Map[String, Any] = Map(
    "keyReplicationConfig" -> keyReplicationConfig
  )

  override def defaultTargetConfig: InternalTargetConfig = {
    InternalTargetConfig.forTest.DEFAULT.copy(keyReplicationConfig = keyReplicationConfig)
  }

  /** Defines an expected sequence of memberships during a rolling restart. */
  private trait RestartStrategy {
    def getMembershipSequence(numResources: Int): Vector[Resources]
  }

  /**
   * Restart strategy in which pods are restarted in reverse order, as with a typical StatefulSet.
   * For example, in a set with 3 pods, the sequence is:
   *
   *  - { pod-0, pod-1, pod-2 }
   *  - { pod-0, pod-1 }
   *  - { pod-0, pod-1, pod-2 }
   *  - { pod-0, pod-2 }
   *  - { pod-0, pod-1, pod-2 }
   *  - { pod-1, pod-2 }
   *  - { pod-0, pod-1, pod-2 }
   *
   * See https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/#deployment-and-scaling-guarantees
   * for details of the deployment policy used for StatefulSets.
   */
  private object StatefulSetRestartStrategy extends RestartStrategy {
    override def getMembershipSequence(numResources: Int): Vector[Resources] = {
      val memberships = Vector.newBuilder[Resources]
      val membership = mutable.HashSet[Squid]()

      // Create initial membership.
      for (i <- 0 until numResources) {
        membership.add(createTestSquid(s"pod-$i"))
      }
      memberships += Resources.create(membership)

      // Simulate pod restarts.
      for (i <- numResources - 1 to 0 by -1) {
        val restartingResource: Squid = createTestSquid(s"pod-$i")

        // Resource stops.
        membership.remove(restartingResource)
        memberships += Resources.create(membership)

        // Resource starts.
        membership.add(restartingResource)
        memberships += Resources.create(membership)
      }
      memberships.result()
    }
  }

  /**
   * Restart strategy in which new pods are started before old pods are stopped, as with a typical
   * Deployment. For example, in a set with 3 pods, the sequence is:
   *
   *  - { pod-0, pod-1, pod-2 }
   *  - { pod-0, new-pod-0, pod-1, pod-2 }
   *  - { new-pod-0, pod-1, pod-2 }
   *  - { new-pod-0, pod-1, new-pod-1, pod-2 }
   *  - { new-pod-0, new-pod-1, pod-2 }
   *  - { new-pod-0, new-pod-1, pod-2, new-pod-2 }
   *  - { new-pod-0, new-pod-1, new-pod-2 }
   */
  private object DeploymentRestartStrategy extends RestartStrategy {
    override def getMembershipSequence(numResources: Int): Vector[Resources] = {
      val memberships = Vector.newBuilder[Resources]
      val membership = mutable.HashSet[Squid]()

      // Create initial membership.
      for (i <- 0 until numResources) {
        membership.add(createTestSquid(s"pod-$i"))
      }
      memberships += Resources.create(membership)

      // Simulate starting new pods and stopping old pods.
      for (i <- 0 until numResources) {
        // New pod starts.
        membership.add(createTestSquid(s"new-pod-$i"))
        memberships += Resources.create(membership)

        // Old pod stops.
        membership.remove(createTestSquid(s"pod-$i"))
        memberships += Resources.create(membership)
      }
      memberships.result()
    }
  }

  /**
   * Describes the total churn during a simulation of membership changes, e.g., during a rolling
   * restart of a StatefulSet.
   */
  private case class MembershipChangesSummary(
      additionChurnRatio: Double,
      removalChurnRatio: Double,
      loadBalancingChurnRatio: Double,
      finalAssignment: Assignment) {
    override def toString: String = {
      s"additionChurnRatio=$additionChurnRatio, removalChurnRatio=$removalChurnRatio," +
      s"loadBalancingChurnRatio=$loadBalancingChurnRatio"
    }
  }

  /**
   * Uses the algorithm to generate a sequence of assignments based on the given sequence of
   * memberships. Each assignment has the assigned resources from the corresponding membership set.
   * Returns the final assignment as well as the aggregate churn observed during the simulation.
   */
  private def simulateMembershipChanges(
      targetConfig: InternalTargetConfig,
      loadMap: LoadMap,
      memberships: Seq[Resources]): MembershipChangesSummary = {
    // Produce initial assignment using the initial membership.
    val initial: Assignment =
      generateInitialAssignment(target, memberships.head, targetConfig.keyReplicationConfig)

    // Run another round of the algorithm so that we can get an assignment respecting the initial
    // load map. This round is not reflect in the change summary since we're interested in churn
    // caused by the membership changes, not the initial load balancing.
    val loadBalancedInitialAssignment: Assignment =
      generateAssignment(target, targetConfig, memberships.head, initial, loadMap)
    simulateMembershipChanges(loadBalancedInitialAssignment, targetConfig, loadMap, memberships)
  }

  /** Overload accepting an initial assignment. */
  private def simulateMembershipChanges(
      initial: Assignment,
      targetConfig: InternalTargetConfig,
      loadMap: LoadMap,
      memberships: Seq[Resources]): MembershipChangesSummary = {
    var predecessor: Assignment = initial

    // Keep running sums for churn measurements as we run through the remaining memberships.
    var additionChurnRatio: Double = 0
    var removalChurnRatio: Double = 0
    var loadBalancingChurnRatio: Double = 0
    for (membership: Resources <- memberships.tail) {
      val assignment: Assignment =
        generateAssignment(target, targetConfig, membership, predecessor, loadMap)

      // Compute stats to update the churn ratio. Compute stats relative to the expected adjusted
      // load map.
      val adjustedLoadMap: LoadMap = loadMap.withAddedUniformLoad(
        targetConfig.loadBalancingConfig.primaryRateMetric.getUniformReservedLoad(
          availableResourceCount = membership.availableResources.size
        )
      )
      val assignmentChangeStats: AssignmentChangeStats =
        AssignmentChangeStats.calculate(
          predecessor,
          assignment,
          adjustedLoadMap
        )
      additionChurnRatio += assignmentChangeStats.additionChurnRatio
      removalChurnRatio += assignmentChangeStats.removalChurnRatio
      loadBalancingChurnRatio += assignmentChangeStats.loadBalancingChurnRatio

      // Set the predecessor for the next round.
      predecessor = assignment
    }
    MembershipChangesSummary(
      additionChurnRatio = additionChurnRatio,
      removalChurnRatio = removalChurnRatio,
      loadBalancingChurnRatio = loadBalancingChurnRatio,
      finalAssignment = predecessor
    )
  }

  test("Initial assignments have the desired properties") {
    // Test plan: Verify that initial assignments have the common desired properties for assignments
    // as described in [[TestSliceUtils.assertDesirableAssignmentProperties]], and in addition, do
    // not have any record of previous load (since it is not known). Verify this for various number
    // of resources and key replication configurations.
    for (numResources: Int <- Seq(1, 2, 10, 20)) {
      logger.info(s"Using numResources $numResources")
      val resources: Resources = createNResources(numResources)
      val targetConfig: InternalTargetConfig = defaultTargetConfig
      val asn: Assignment =
        generateInitialAssignment(
          target,
          resources,
          targetConfig.keyReplicationConfig
        )
      assertDesirableAssignmentProperties(targetConfig, asn, resources)

      // Verify that `primaryRateLoadOpt` is empty for all Slices.
      for (sliceAssignment <- asn.sliceMap.entries) {
        assert(sliceAssignment.primaryRateLoadOpt.isEmpty)
      }
    }
  }

  test("Initial assignment slices are uniformly sized and evenly distributed among resources") {
    // Test plan: Verify that initial assignments have a power of 2 number of equally sized slices
    // which are each replicated according to min replicas in configuration (or number of available
    // resources, whichever is less), and are distributed evenly among resources. Supposing the
    // number of slices in a given initial assignment is N, verify the expected properties by
    // generating all bitstrings of length log2(N) + 2, using them as slice keys, looking up the
    // containing slice, and verifying that each slice is hit 2^2 = 4 times. For example, supposing
    // N = 4, the algorithm is expected to generate slices with boundaries minKey, 0b01, 0b10, 0b11,
    // ∞, and we will generate slice keys 0b0000, 0b0001, 0b0010, 0b0011, 0b0100, ..., 0b1111, where
    // the first 4 slice keys will hit the first slice in the assignment, the second batch of 4 will
    // hit the second slice, etc. We also verify that each resource gets close to an equal number of
    // hits (the algorithm for initial assignments assigns resources in round-robin order, so we
    // expect that the number of hits per resource is within 4 of the average number of hits per
    // resource.)
    for (numResources: Int <- Seq(1, 2, 10, 20)) {
      logger.info(s"Using numResources $numResources")
      val resources: Resources = createNResources(numResources)
      val targetConfig: InternalTargetConfig = defaultTargetConfig
      val asn: Assignment =
        generateInitialAssignment(
          target,
          resources,
          targetConfig.keyReplicationConfig
        )
      assertDesirableAssignmentProperties(targetConfig, asn, resources)

      // Generate bitstrings long enough that each slice is hit multiple times. Since currently
      // the algorithm generates a power of 2 number of slices, the `+ 2` should result in exactly
      // 2^2 = 4 lookups per slice.
      val numBits: Int = (Math.log(asn.sliceMap.entries.size) / Math.log(2)).toInt + 2
      val step: BigInt = BigInt(1) << (java.lang.Long.SIZE - numBits)
      // Track number of lookups that go to each range/slice.
      val hitsPerSlice: mutable.Map[Slice, Int] = mutable.Map[Slice, Int]().withDefaultValue(0)
      // Track number of lookups that go to each resource.
      val hitsPerResource = mutable.Map[Squid, Int]().withDefaultValue(0)

      // Iterate through all the different possible values of `numBits` most significant bits. We
      // do this by starting at 0 and adding `step` until we overflow 8 bytes. At each step we
      // increment the number of hits by 1 for the slice that contains the key, and increment the
      // number of hits by 1 for each resource to which the slice is assigned.
      for (i <- BigInt(0) until (BigInt(1) << 64) by step) {
        val key = SliceKeyHelper.fromBigInt(magnitude = i, length = 8)
        val entry: SliceAssignment = asn.sliceMap.lookUp(key)
        val slice: Slice = entry.slice
        hitsPerSlice(slice) += 1
        for (resource: Squid <- entry.resources) {
          hitsPerResource(resource) += 1
        }
      }
      val adjustedMinReplicas = targetConfig.keyReplicationConfig.minReplicas.min(numResources)
      for (sliceAssignment: SliceAssignment <- asn.sliceMap.entries) {
        val slice: Slice = sliceAssignment.slice
        val hits: Option[Int] = hitsPerSlice.get(slice)
        assert(hits.isDefined, s"Every slice should have been looked up: $slice")

        // See comment above on why we expect each range to be looked up 4 times.
        assert(hits.get == 4, s"$slice not looked up 4 times")
      }
      assert(hitsPerResource.size == numResources, "Every resource should have been looked up")
      val expectedHitsPerResource
          : Double = Math.pow(2, numBits) / numResources * adjustedMinReplicas
      for ((_, hits) <- hitsPerResource) {
        // The difference in the number of slices assigned to each resource is at most 1, since
        // resources are assigned slice replicas in round-robin order, but the number of slice
        // replicas may not evenly divide the number of resources. Since each range is looked up 4
        // times, the deviation from average is less than 4.
        assert(Math.abs(expectedHitsPerResource - hits) < 4)
      }
    }
  }

  test("Update to initial assignment with same resources and uniform load yields expected asn") {
    // Test plan: Verify that generating an updated assignment from an initial assignment, based on
    // the same parameters and set of available resources, and where the load is uniformly spread in
    // the keyspace, results in an assignment with the same set of slices as before and assigned to
    // the same resources, but where the load has been recorded in the assignment.
    val resources: Resources = createResources("resource0")
    val targetConfig = defaultTargetConfig
    val asn1: Assignment =
      generateInitialAssignment(
        target,
        resources,
        targetConfig.keyReplicationConfig
      )

    // We need to supply a loadMap covering the full range
    val loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP
    val asn2: Assignment =
      generateAssignment(target, targetConfig, resources, asn1, loadMap)

    // Verify: the slices in the proposed assignment match those from the initial assignment and
    // load has been recorded.
    assert(asn1.sliceAssignments.size == asn2.sliceAssignments.size)
    for (tuple <- asn1.sliceAssignments.zip(asn2.sliceAssignments)) {
      val (element1, element2): (SliceAssignment, SliceAssignment) = tuple
      assert(element1.slice == element2.slice)
      assert(element1.resources == element2.resources)
      assert(element1.primaryRateLoadOpt.isEmpty)
      assert(element2.primaryRateLoadOpt.contains(loadMap.getLoad(element1.slice)))
    }
  }

  test("Splits to between MIN and MAX avg slice replicas per resource when resources scaled up") {
    // Test plan: Verify that when the number of resources increases significantly, the algorithm
    // splits slices to ensure that the new assignment has between `MIN_AVG_SLICE_REPLICAS` and
    // `MAX_AVG_SLICE_REPLICAS` per resource, with balanced load among them. We do this by creating
    // an initial assignment with a single slice, then generating a new assignment based on it but
    // where the number of resources has increased significantly.
    val rng: Random = createRng()
    val targetConfig = defaultTargetConfig
    val initialAssignment: Assignment =
      generateInitialAssignment(
        target,
        createNResources(targetConfig.keyReplicationConfig.minReplicas),
        targetConfig.keyReplicationConfig
      )

    // Increase the number of resources by between 3x and 10x, forcing a large increase in the
    // number of slices in the assignment to fall within the min/max bounds.
    val numResources = initialAssignment.assignedResources.size * (3 + rng.nextInt(8))
    val resources: Resources = createNResources(numResources)
    logger.info(s"Using numResources $numResources")

    // Make load uniform.
    val loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP
    val newAssignment: Assignment =
      generateAssignment(target, targetConfig, resources, initialAssignment, loadMap)
    assertDesirableAssignmentProperties(targetConfig, newAssignment, resources)
  }

  test(
    "Splits to between MIN and MAX avg slice replicas per resource even with unsplittable slices"
  ) {
    // Test plan: Verify that when the number of resources increases significantly, even if the base
    // assignment contains unsplittable slices (where we leverage the fact that the
    // algorithm/LoadMap is unwilling to synthesize keys longer than 8 bytes), the algorithm still
    // splits slices to ensure that the new assignment has between `MIN_AVG_SLICE_REPLICAS` and
    // `MAX_AVG_SLICE_REPLICAS` per resource, with balanced load among them.
    val rng: Random = createRng()
    val generation: Generation = createGeneration()
    val targetConfig = InternalTargetConfig.forTest.DEFAULT
    val startingResources: Resources =
      createNResources(targetConfig.keyReplicationConfig.minReplicas)
    val assignment: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      ("" -- 10) @@ generation -> startingResources.availableResources,
      (10 -- 11) @@ generation -> startingResources.availableResources,
      (11 -- 50) @@ generation -> startingResources.availableResources,
      (50 -- 51) @@ generation -> startingResources.availableResources,
      (51 -- ∞) @@ generation -> startingResources.availableResources
    )

    // Increase the number of resources by between 3x and 10x, forcing a large increase in the
    // number of slices in the assignment to fall within the min/max bounds.
    val numResources = assignment.assignedResources.size * (3 + rng.nextInt(8))
    logger.info(s"Using numResources $numResources")
    val resources: Resources = createNResources(numResources)
    // Make load uniform.
    val loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP
    val newAssignment: Assignment =
      generateAssignment(target, targetConfig, resources, assignment, loadMap)
    assertDesirableAssignmentProperties(targetConfig, newAssignment, resources)
  }


  test("Slices are split according to the split threshold") {
    // Test plan: Verify that Slices which are above the split threshold are split until the
    // resulting slices are below the split threshold. Verify this by constructing a load map with
    // Slices that are above the split threshold (where some are hot enough to require multiple
    // splits), and checking that the resulting assignment contains only Slices that are below the
    // split threshold.

    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint = 100)
    // For simplicity, all slices in this test have `minReplicas` replicas.
    val minReplicas: Int = targetConfig.keyReplicationConfig.minReplicas

    // Generate an initial assignment with minReplicas resources.
    val resources = createNResources(minReplicas)
    val initial: Assignment =
      generateInitialAssignment(target, resources, targetConfig.keyReplicationConfig)
    // Sanity check number of base slices: 32 Slice replicas per resource, divided by number of
    // replicas each slice and then rounded to the nearest power of 2 in initial assignments.
    assert(
      initial.sliceMap.entries.size == Math
        .pow(
          2,
          Math.ceil(Math.log(32.0 * resources.availableResources.size / minReplicas) / Math.log(2))
        )
    )
    assertDesirableAssignmentProperties(targetConfig, initial, resources)

    // Construct a load map where most Slices are cool, but a few slice replicas are above the split
    // threshold:
    //  - One Slice's replicas are slightly above
    //  - One Slice's replicas have twice the split threshold and needs to be split once
    //  - One Slice's replicas have thrice the split threshold and needs to be split twice
    //    (resulting in 4 subslices).
    val expectedSplitThreshold: Double = 10.0 // 10% * maxLoadHint
    val loadMapBuilder = LoadMap.newBuilder()
    for (tuple <- initial.sliceMap.entries.zipWithIndex) {
      val (sliceAssignment, i): (SliceAssignment, Int) = tuple
      val slice: Slice = sliceAssignment.slice
      val load: Double = i match {
        case 10 => expectedSplitThreshold * sliceAssignment.resources.size * 1.1
        case 20 => expectedSplitThreshold * sliceAssignment.resources.size * 2.0
        case 30 => expectedSplitThreshold * sliceAssignment.resources.size * 3.0
        case _ => expectedSplitThreshold * sliceAssignment.resources.size / 10.0
      }
      loadMapBuilder.putLoad(LoadMap.Entry(slice, load))
    }
    val loadMap = loadMapBuilder.build()

    // Run the algorithm again and verify that the expected Slices are split.
    val assignment: Assignment =
      generateAssignment(target, targetConfig, resources, initial, loadMap)
    // 32 Slice replicas per resource + some splits.
    assert(calculateNumSliceReplicas(assignment) > 32 * resources.availableResources.size)
    assertDesirableAssignmentProperties(targetConfig, assignment, resources)
    for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
      val slice: Slice = sliceAssignment.slice
      val sliceReplicaLoad
          : Double = sliceAssignment.primaryRateLoadOpt.get / sliceAssignment.resources.size
      assert(
        sliceReplicaLoad <= expectedSplitThreshold,
        s"unexpected sliceReplicaLoad for Slice $slice: $sliceReplicaLoad " +
        s"(expectedSplitThreshold: $expectedSplitThreshold)"
      )
    }
  }

  test("Splits slices to meet MIN avg slices per resource constraint") {
    // Test plan: Verify that the algorithm will split slices purely to meet the
    // `MIN_AVG_SLICE_REPLICAS` constraint (i.e. even when the slices are cold and beneath the split
    // threshold). Verify this by running the algorithm with a predecessor assignment that has too
    // few Slices but where no Slice exceeds the split threshold, and checking that the resulting
    // assignment has enough Slices and that the expected Slices are split (i.e., the Slices with
    // the most load).

    val maxLoadHint: Double = 100
    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint)
    val minReplicas: Int = targetConfig.keyReplicationConfig.minReplicas

    // Generate an initial assignment with minReplicas resources.
    val initialResources = createNResources(minReplicas)
    val initial: Assignment =
      generateInitialAssignment(target, initialResources, targetConfig.keyReplicationConfig)
    // Sanity check number of base slices: 32 Slice replicas per resource, divided by number of
    // replicas each slice and then rounded to the nearest power of 2 in initial assignments.
    assert({
      val minSliceReplicasBound: Double = 32.0 * initialResources.availableResources.size
      val minSlicesBound: Double = minSliceReplicasBound / minReplicas
      val log2MinSliceBound: Double = Math.log(minSlicesBound) / Math.log(2.0)
      val minSlicesBoundClosestPowOf2: Double = Math.pow(2, Math.ceil(log2MinSliceBound))
      initial.sliceMap.entries.size == minSlicesBoundClosestPowOf2
    })
    assertDesirableAssignmentProperties(targetConfig, initial, initialResources)

    // Generate a load map such that all existing Slices are cool enough.
    val loadMap = LoadMap.newBuilder().putLoad(LoadMap.Entry(Slice.FULL, 2 * maxLoadHint)).build()

    // Triple the number of resources and run the algorithm again. Verify that just enough Slices
    // are created to reach the minimum required count (32 * numResources).
    val resources = createNResources(initialResources.availableResources.size * 3)
    val assignment: Assignment =
      generateAssignment(target, targetConfig, resources, initial, loadMap)
    // 32 Slice replicas per resource
    assert(calculateNumSliceReplicas(assignment) == 32 * resources.availableResources.size)
    assertDesirableAssignmentProperties(targetConfig, assignment, resources)

    // Verify that the algorithm split the hottest Slices, which implies that the hottest Slice will
    // have at most twice the load of the coolest Slice in the current example.
    val sliceLoads: Vector[Double] = assignment.sliceMap.entries.map {
      sliceAssignment: SliceAssignment =>
        loadMap.getLoad(sliceAssignment.slice)
    }
    assert(sliceLoads.min <= sliceLoads.max / 2.0)
  }

  test("Merges coldest slices: randomized assignment inputs") {
    // Test plan: Verify that the algorithm always merges coldest slices using randomized assignment
    // inputs. Verify this by randomly generating an assignment with too many Slice replicas (more
    // than 64 per resource), and checking that the merger did not merge any Slices when a better
    // merge candidate was available. We do this by identifying merged Slices in the output of the
    // algorithm (no splits are expected given the inputs) and checking whether any adjacent Slices
    // could have been merged instead to produce a lower combined load.

    val rng: Random = createRng() // logs RNG seed for failure reproducibility
    // Choose a maxLoadHint that is high enough to prevent splits or load balancing decisions
    // outside of merge (since each Slice has load between 1 and 10), but also low enough that the
    // uniform load reservation is unlikely to affect the outcome.
    val maxLoadHint = 200.0
    // The exact churn config doesn't really matter for merging, but we use default to align with
    // production.
    val targetConfig: InternalTargetConfig =
      createConfigForLoadBalancing(ChurnConfig.DEFAULT, maxLoadHint)
    val numResources = 10
    val resources = createNResources(numResources)
    val predecessor: Assignment = commitProposal(
      ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(
          numSlices = numResources * 80, // > 64 Slices per resource
          resources.availableResources.toVector,
          numMaxReplicas = targetConfig.keyReplicationConfig.maxReplicas,
          rng
        )
      )
    )

    // Intentionally restrict the number and range of distinct load values per Slice to better
    // exercise cases for the merge logic.
    val loadMap = LoadMap
      .newBuilder()
      .putLoad(
        predecessor.sliceMap.entries.map { sliceAssignment: SliceAssignment =>
          LoadMap.Entry(sliceAssignment.slice, rng.nextInt(10) + 1)
        }: _*
      )
      .build()

    // Run the algorithm!
    val actual: Assignment = generateAssignment(
      target,
      targetConfig,
      resources,
      predecessor,
      loadMap
    )
    // Verify that merging actually reduced the number of slice replicas.
    assert(calculateNumSliceReplicas(predecessor) > actual.sliceAssignments.size)
    assert(calculateNumSliceReplicas(actual) <= numResources * 64)
    // Determine which Slices have been merged by zipping the new assignment with its predecessor.
    type IntersectionEntry =
      SliceMap.IntersectionEntry[SliceAssignment, SliceAssignment]
    val zipSliceMap: SliceMap[IntersectionEntry] =
      SliceMap.intersectSlices(predecessor.sliceMap, actual.sliceMap)
    val mergedSliceAssignments: Set[SliceAssignment] = zipSliceMap.entries
      .filter { entry: IntersectionEntry =>
        val before: SliceAssignment = entry.leftEntry
        val after: SliceAssignment = entry.rightEntry
        assert(after.slice.contains(before.slice), s"Slice was split! $before -> $after")
        after.slice != before.slice // Slice was merged!
      }
      .map { entry: IntersectionEntry =>
        entry.rightEntry
      }
      .toSet

    // Find the best (remaining) merge candidate, i.e., the adjacent Slices with the least
    // combined load.
    var bestMergedLoad = Double.MaxValue
    var previousSliceAssignment: SliceAssignment = actual.sliceAssignments.head
    for (sliceAssignment: SliceAssignment <- actual.sliceAssignments.tail) {
      val mergedLoad = loadMap.getLoad(previousSliceAssignment.slice) + loadMap.getLoad(
          sliceAssignment.slice
        )
      bestMergedLoad = math.min(bestMergedLoad, mergedLoad)
      previousSliceAssignment = sliceAssignment
    }
    // Any Slices merged Slices had better have combined load that is at most as high as that of
    // the best remaining merge candidate.
    for (sliceAssignment: SliceAssignment <- mergedSliceAssignments) {
      val mergedLoad = loadMap.getLoad(sliceAssignment.slice)
      assert(
        mergedLoad <= bestMergedLoad,
        s"Merged Slice with load $mergedLoad, " +
        s"but best remaining merge candidate would have total load $bestMergedLoad"
      )
    }
  }

  test("Reassigns slices from unhealthy to healthy resources: single resource") {
    // Test plan: Verify that the algorithm reassigns slices that are assigned to an unhealthy
    // resource to a healthy resource. Verify this by calling the algorithm with a base assignment
    // where all slices are assigned to an unhealthy resource, and checking that all slices get
    // reassigned to a (provided) healthy resource.
    val generation: Generation = createGeneration()
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val assignment: Assignment =
      createAssignment(
        generation,
        AssignmentConsistencyMode.Affinity,
        ("" -- ∞) @@ generation -> Seq("resourceUnhealthy")
      )
    // Load doesn't matter for this test.
    val loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP
    // Create at least minReplicas resources, so that slices can be minimally replicated.
    val healthyResources: Resources =
      createNResources(targetConfig.keyReplicationConfig.minReplicas)
    val newAssignment: Assignment =
      generateAssignment(
        target,
        targetConfig,
        healthyResources,
        assignment,
        loadMap
      )
    // The new assignment will contain multiple `sliceAssignments` to satisfy
    // MIN_AVG_SLICE_REPLICAS. Ensure each of them is assigned to `healthyResources`.
    for (sliceAssignments: SliceAssignment <- newAssignment.sliceMap.entries) {
      assert(sliceAssignments.resources == healthyResources.availableResources)
    }
  }

  test("Maintains desired asn properties when resources scale and up down") {
    // Test plan: Verify that the algorithm maintains the expected load per resource when scaling up
    // and down the number of available resources, and does so for all possible uniform load
    // reservation hints to ensure that the algorithm correctly accounts for reserved load. Verify
    // this by scaling the number of available resources one by one up to 10, then back down to 1.

    val maxLoadHint: Double = 100.0
    val rng: Random = createRng() // logs RNG seed for failure reproducibility
    for (reservationHint: ReservationHintP <- ReservationHintP.values) {
      val reservationRatio: Double = getExpectedReservationRatio(reservationHint)
      def createLoadMap(resources: Resources): LoadMap = {
        // To ensure sufficiently aggressive LB, create a load map such that the average resource
        // has the maximum configured load less the reserved load (so that when reserved load is
        // factored in by the algorithm, it perceives the total adjusted load to be at the max).
        createRandomLoadMap(
          rng,
          totalLoad = maxLoadHint * resources.availableResources.size * (1 - reservationRatio)
        )
      }
      val target = Target(escapeName(s"$getSafeName-$reservationHint"))
      val targetConfig =
        createConfigForLoadBalancing(
          ChurnConfig.DEFAULT,
          maxLoadHint,
          ImbalanceToleranceHintP.DEFAULT,
          reservationHint
        )

      // To account for churn penalties, we need to allow for higher imbalance tolerance when
      // checking assignment properties than in most tests.
      val lbConfig: LoadBalancingConfig = targetConfig.loadBalancingConfig
      val imbalanceTolerance = maxLoadHint * lbConfig.primaryRateMetric.imbalanceToleranceRatio * 2

      val resourceNames = ArrayBuffer("resource1")
      var assignment: Assignment =
        generateInitialAssignment(
          target,
          createResources(resourceNames: _*),
          targetConfig.keyReplicationConfig
        )
      var nextGeneration: Generation = createGeneration()

      // Increase number of resources one by one.
      for (i <- 2 to 10) {
        resourceNames += s"resource$i"
        val resources: Resources = createResources(resourceNames: _*)
        assignment =
          generateAssignment(target, targetConfig, resources, assignment, createLoadMap(resources))
        assertDesirableAssignmentProperties(
          targetConfig,
          assignment,
          resources,
          imbalanceToleranceOverrideOpt = Some(imbalanceTolerance)
        )
      }
      // Decrease number of resources one by one.
      for (i <- 9 to 1 by -1) {
        // Pick a resource at rng to remove.
        resourceNames.remove(rng.nextInt(resourceNames.size))
        nextGeneration = createGeneration()
        val resources: Resources = createResources(resourceNames: _*)
        assignment =
          generateAssignment(target, targetConfig, resources, assignment, createLoadMap(resources))
        assertDesirableAssignmentProperties(
          targetConfig,
          assignment,
          resources,
          imbalanceToleranceOverrideOpt = Some(imbalanceTolerance)
        )
      }
    }
  }

  test("Generates well-formed asn when resources restart with new UUIDs but with same address") {
    // Test plan: Verify that when resources change their incarnation UUIDs but not their addresses
    // (e.g. when they come back after a restart/crash) and we generate a new assignment, the new
    // assignment contains the updated SQUIDs.
    val targetConfig = defaultTargetConfig
    val resources: Resources = createNResources(10)
    val firstAssignment: Assignment =
      generateInitialAssignment(target, resources, targetConfig.keyReplicationConfig)
    val updatedResources: Resources = createNResources(10)(salt = "'")
    val assignment: Assignment =
      generateAssignment(
        target,
        targetConfig,
        updatedResources,
        firstAssignment,
        LoadMap.UNIFORM_LOAD_MAP
      )

    // Validate that the generated assignment includes `updatedResources`.
    assertDesirableAssignmentProperties(targetConfig, assignment, updatedResources)
  }

  test("Update assignment with no available resources") {
    // Test plan: Verify that when the algorithm tries to generate a new assignment with no
    // available resources, the resulting assignment is the same as the predecessor assignment but
    // with the load information updated.
    val targetConfig = defaultTargetConfig
    val predecessor: Assignment =
      generateInitialAssignment(target, createNResources(10), targetConfig.keyReplicationConfig)

    // Create a load map including load information for each of the Slices in `predecessor`. In
    // parallel, create the expected proposed assignment, which will be identical to the predecessor
    // except for the load information.
    val rng: Random = createRng()
    val loadMapBuilder = LoadMap.newBuilder()
    for (sliceAssignment: SliceAssignment <- predecessor.sliceMap.entries) {
      val primaryRateLoad: Double = rng.nextDouble() * 100
      loadMapBuilder.putLoad(LoadMap.Entry(sliceAssignment.slice, primaryRateLoad))
    }
    val loadMap: LoadMap = loadMapBuilder.build()

    val expectedSlices: SliceMap[ProposedSliceAssignment] =
      predecessor.sliceMap.map(SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR) {
        sliceAssignment: SliceAssignment =>
          ProposedSliceAssignment(
            sliceAssignment.slice,
            sliceAssignment.resources,
            Some(loadMap.getLoad(sliceAssignment.slice))
          )
      }
    val expectedAssignment =
      commitProposal(ProposedAssignment(Some(predecessor), expectedSlices))

    // Run the algorithm and verify the expected outcome.
    val assignment = generateAssignment(
      target,
      targetConfig,
      Resources.create(healthyResources = Set.empty),
      predecessor,
      loadMap
    )
    assert(assignment == expectedAssignment)
  }

  test("Simulate rolling restart") {
    // Test plan: run simulations of StatefulSet and Deployment rolling restarts in which pods are
    // restarted one at a time. Verify for various load and set sizes that the algorithm does not
    // cause excessive churn (see remarks on expectations inline) and that the end assignment is
    // reasonably well-balanced. Testing with low load ensures that the algorithm is exploiting
    // restarts to perform more aggressive load balancing while respecting any configured
    // reservation for potential future uniform load.

    val maxLoadHint: Double = 100
    val rng: Random = createRng()

    // When a pod is restarted, load is removed from the old pod and added to the new pod.
    // If churn minimization were the only goal, we would want each key to migrate exactly once,
    // but that would restrict migration to pods that have already restarted, and result in
    // significant load imbalance. Instead, we allow each key (or fragment of load) to be moved
    // ~twice on average, once in response to the addition of a new pod and once in response to
    // the removal of an old pod.
    //
    // We allow a 5% buffer since individual Slices may be unlucky and migrate more than twice,
    // and may account for a substantial fraction of the load because we use non-uniform load in
    // these tests.
    val maxAdditionChurnRatio: Double = 1.05
    val maxRemovalChurnRatio: Double = 1.05

    // In practice, the algorithm should rarely need to do any load balancing in the course of a
    // rolling restart with stable load. Verify that most churn is due to either the addition or
    // removal of a resource.
    val maxLoadBalancingChurnRatio: Double =
      if (keyReplicationConfig.minReplicas == 1) {
        0.02
      } else {
        // When the minimum configured replicas is greater than 1, we slightly relax the max
        // load balancing churn, as the requirement that the same slice cannot be assigned on the
        // same resource more than once tends to make it harder to find a new resource for a slice
        // on unhealthy resource, and tends to make more imbalanced load distribution and more
        // load balancing churn.
        //
        // For example, when number of replicas is 1, the slices being assigned might be:
        //   ["a", "b") load = 10, ["b", "c") load = 10, ["c", "d") load = 10, ["d", "e") load = 10
        // when minReplicas is 2, in order to satisfy the minimum replicas requirement, the slice
        // replicas being assigned might become:
        //   ["a", "c") load = 10, ["a", "c") load = 10, ["c", "e") load = 10, ["c", "e") load = 10
        // The latter is generally harder to assign than the former and tends to cause more load
        // balancing churn.
        //
        // So in this particular test, when a resource is removed from healthy resources, the
        // replication nature described above makes it harder to find a new resource for the slice
        // replicas on the unhealthy resource, and makes the new algorithm more load-imbalanced.
        // And when a new resource is added to the healthy resources subsequently, the algorithm
        // runs based on a less-balanced predecessor assignment, and thus cause more load balancing
        // churn.
        0.04
      }

    case class TestCase(
        reservationHint: ReservationHintP,
        numResources: Int,
        loadPerResource: Double,
        restartStrategy: RestartStrategy) {
      def description: String = {
        s"$restartStrategy, n=$numResources, loadPerResource=$loadPerResource, " +
        s"uniformLoadReservationHint=$reservationHint"
      }
    }

    // Define test cases for various permutations of service size, load, restart strategy, and
    // uniform load reservations.
    val testCases = Seq(
      TestCase(
        reservationHint = ReservationHintP.NO_RESERVATION,
        numResources = 2,
        loadPerResource = maxLoadHint / 8,
        restartStrategy = StatefulSetRestartStrategy
      ),
      TestCase(
        reservationHint = ReservationHintP.SMALL_RESERVATION,
        numResources = 9,
        loadPerResource = maxLoadHint,
        restartStrategy = DeploymentRestartStrategy
      ),
      TestCase(
        reservationHint = ReservationHintP.MEDIUM_RESERVATION,
        numResources = 3,
        loadPerResource = maxLoadHint / 4,
        restartStrategy = DeploymentRestartStrategy
      ),
      TestCase(
        reservationHint = ReservationHintP.LARGE_RESERVATION,
        numResources = 4,
        loadPerResource = maxLoadHint / 2,
        restartStrategy = StatefulSetRestartStrategy
      )
    )
    for (testCase <- testCases) {
      val targetConfig: InternalTargetConfig = createConfigForLoadBalancing(
        // Disable churn penalty so that the algorithm can be more aggressive in load balancing to
        // new resources.
        ChurnConfig.ZERO_PENALTY,
        maxLoadHint,
        uniformLoadReservationHint = testCase.reservationHint
      )

      // Build a non-uniform load map containing the desired total load.
      val loadMap =
        createRandomLoadMap(
          rng,
          testCase.loadPerResource * testCase.numResources,
          numSlices = 256,
          maxToMinDensityRatio = 5
        )

      // Create membership history for a rolling update.
      val memberships: Vector[Resources] =
        testCase.restartStrategy.getMembershipSequence(testCase.numResources)
      val outcome: MembershipChangesSummary =
        simulateMembershipChanges(targetConfig, loadMap, memberships)
      val description: String = testCase.description
      logger.info(
        s"$description: outcome=$outcome, testCase=$this, " +
        s"additionChurnRatio=${outcome.additionChurnRatio} (<= $maxAdditionChurnRatio) " +
        s"removalChurnRatio=${outcome.removalChurnRatio} (<= $maxRemovalChurnRatio)"
      )
      assert(outcome.additionChurnRatio <= maxAdditionChurnRatio, description)
      assert(outcome.removalChurnRatio <= maxRemovalChurnRatio, description)
      assert(outcome.loadBalancingChurnRatio <= maxLoadBalancingChurnRatio, description)

      // During a restart, key churn is inevitable and Dicer exploits this opportunity to perform
      // more aggressive load balancing than is normally applied. When checking assignment
      // properties, we expect +/- 10% of the mean resource load in this test, whereas the default
      // is +/- 10% of the max load hint. The imbalance tolerance is expected to be adjusted upward
      // by any configured reservation for uniform load.
      val loadBalancingConfig: LoadBalancingConfig = targetConfig.loadBalancingConfig
      val reservationRatio: Double = getExpectedReservationRatio(
        loadBalancingConfig.primaryRateMetric.uniformLoadReservationHint
      )
      val imbalanceTolerance
          : Double = (testCase.loadPerResource + reservationRatio * maxLoadHint) * 0.1
      assertDesirableAssignmentProperties(
        targetConfig,
        outcome.finalAssignment,
        memberships.last,
        imbalanceToleranceOverrideOpt = Some(imbalanceTolerance)
      )
      // Also defensively validate default LB expectations in case `imbalanceTolerance` is looser
      // than the default due to a test bug.
      assertDesirableAssignmentProperties(
        targetConfig,
        outcome.finalAssignment,
        memberships.last
      )
    }
  }

  test("Uniform load reservations") {
    // Test plan: Verify that uniform load reservations cause the algorithm to produce assignments
    // that are "prepared" for sudden increases in load spread ~uniformly across the keyspace (i.e.
    // would be well-balanced if that happened), even if the current load is insignificant and even
    // when the target goes through a rolling restart (where the algorithm aggressively load
    // balances). See historical note below for more context.
    //
    // Each test case has the following flow:
    //  - Generate an initial assignment with imbalanced but insignificant load. Simulate a rolling
    //    restart which forces the algorithm to load balance aggressively: although the initial load
    //    is not significant, it's all the Assigner has to work with when migrating keys as servers
    //    restart.
    //  - Generate a roughly uniform load distribution and verify that the latest assignment works
    //    well for that distribution, despite being guided by the initial non-uniform load.
    //
    // Historical note: Before Dicer had the concept of a uniform load reservation, targets with
    // long periods of insignificant load would end up with highly skewed assignments. This was
    // because the algorithm takes advantage of pod restarts to perform more aggressive load
    // balancing even if the load is insignificant (the churn is unavoidable since the keys need to
    // be moved anyway). Combined with the fact that when load is low it can have a highly volatile
    // distribution (e.g. a few random keys have some load and the rest have none), the resulting
    // assignments would end up getting over-indexed on a relatively meaningless load distribution,
    // and when meaningful load appeared it resulted in significant load imbalance.

    val target = Target(getSafeName)
    val rng: Random = createRng()
    val maxLoadHint: Double = 10000.0
    val numResources: Int = 5

    for (reservationHint <- ReservationHintP.values) {
      val reservationRatio: Double = getExpectedReservationRatio(reservationHint)
      for (_ <- 0 until 5) {
        val targetConfig: InternalTargetConfig = createConfigForLoadBalancing(
          ChurnConfig.DEFAULT,
          maxLoadHint,
          ImbalanceToleranceHintP.TIGHT,
          reservationHint
        )
        val membershipSequence: Vector[Resources] =
          DeploymentRestartStrategy.getMembershipSequence(numResources)
        val initialResources: Resources = membershipSequence.head
        val initialAssignment: Assignment =
          generateInitialAssignment(target, initialResources, targetConfig.keyReplicationConfig)

        // Construct a load map such that there is significant imbalance between resources. We use
        // an adversarial approach where one resource has no load, and the remaining resources each
        // have a single Slice with non-zero load. If no uniform load reservation were configured,
        // this would result in all "empty" Slices getting assigned to the resource with no load.
        val resourcesWithNonZeroLoad = mutable.Set.empty[Squid]
        val initialLoadMapBuilder = LoadMap.newBuilder()
        for (sliceAssignment: SliceAssignment <- initialAssignment.sliceMap.entries) {
          var sliceLoad: Double = 0
          for (resource: Squid <- sliceAssignment.resources) {
            val sliceReplicaLoad: Double =
              if (resourcesWithNonZeroLoad.size < numResources - 1
                && resourcesWithNonZeroLoad.add(resource)) {
                // If this is the first time we've seen this resource, give it a Slice with non-zero
                // load. The last resource we encounter will have no load.
                1.0
              } else {
                0.0
              }
            sliceLoad += sliceReplicaLoad
          }
          initialLoadMapBuilder.putLoad(LoadMap.Entry(sliceAssignment.slice, sliceLoad))
        }
        val initialLoadMap: LoadMap = initialLoadMapBuilder.build()

        // Simulate a rolling restart to "bake in" the initial imbalanced load into the assignment.
        val initialSummary: MembershipChangesSummary = simulateMembershipChanges(
          initialAssignment,
          targetConfig,
          initialLoadMap,
          membershipSequence
        )
        // Generate a new load map with significant but roughly uniform load and verify that the
        // assignment is still "good enough".
        val roughlyUniformLoadMap: LoadMap = createRandomLoadMap(
          rng,
          totalLoad = maxLoadHint * numResources / 2, // Half of the max load.
          maxToMinDensityRatio = 1.2 // The hottest subslices are 20% more dense than the coldest.
        )

        // What is "good enough"? The reservation ratio determines whether any resource is likely to
        // exceed the maximum load.
        val assignmentLoadStats: AssignmentLoadStats =
          AssignmentLoadStats.calculate(initialSummary.finalAssignment, roughlyUniformLoadMap)
        val loadPerResource: Map[Squid, Double] =
          assignmentLoadStats.loadByResource
        val meanLoad: Double = loadPerResource.values.sum / numResources
        val maxLoad: Double = loadPerResource.values.max
        // With a uniform-load reservation in place, we expect the reserved uniform load to play a
        // significant role in the actual load balancing decisions. This increases with higher
        // reservation ratios. This test (somewhat arbitrarily) allows up to 2x imbalance with
        // reservationRatio = 0.01, and up to 1.2x imbalance with reservationRatio = 0.4.
        val multiplier = 1 - 0.5 * Math.log10(reservationRatio)
        assert(maxLoad <= multiplier * meanLoad, s"reservationRatio=$reservationRatio")
      }
    }
  }
}
