package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.implicitConversions

import com.databricks.caching.util.RealtimeTypedClock
import com.databricks.dicer.assigner.MigrationTestAssignment._
import com.databricks.dicer.assigner.AssignmentStats.AssignmentLoadStats
import com.databricks.dicer.assigner.algorithm.LoadMap
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{
  Assignment,
  AssignmentConsistencyMode,
  Generation,
  SliceAssignment,
  SliceMapHelper,
  SubsliceAnnotation,
  TestSliceUtils
}
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.Squid
import com.databricks.caching.util.UnixTimeVersion

/*
 * The file contains test utils for the migration phase test of the Algorithm where the exact slice
 * boundaries are not interesting. Only meant to be used for Algorithm tests in
 * com.databricks.dicer.assigner.AlgorithmSuite.
 */

/**
 * REQUIRES: Each [[TestResourceAssignment]] has the same size of
 *           [[TestResourceAssignment.interestingSliceReplicas]].
 * REQUIRES: The [[TestSliceReplica.Assigned]] at the same index in each
 *           [[TestResourceAssignment.interestingSliceReplicas]] should have the same load value.
 *           For example:
 *           {{{
 *             MigrationTestAssignment(
 *               "pod0" --> Seq(35.0, 30.0),
 *               "pod1" --> Seq(35.0, ----),
 *               "pod2" --> Seq(----, ----)
 *             )
 *           }}}
 *           is a legal representation, while
 *           {{{
 *             MigrationTestAssignment(
 *               "pod0" --> Seq(35.0, 30.0),
 *               "pod1" --> Seq(36.0, ----),
 *               "pod2" --> Seq(----, ----)
 *             )
 *           }}}
 *           is an illegal one, because the first slice has 2 replicas with different loads 35.0
 *           and 36.0.
 * REQUIRES: The load of each interesting slice replica should be greater than 1.0.
 *
 * Simplified representation of an assignment used in migration phase tests that supports conversion
 * to real assignments (see [[assignment]]) and assertion of compatability with real assignments
 * (see [[assertHasSameInterestingSlicesAndTotalLoadsAs]]) for test verification purpose:
 *
 *  - The specific Slice boundaries are not relevant to the migration phase, so this class only
 *    represents the loads and continuously assigned ages (see [[TestSliceReplica]]) of the slice
 *    replicas assigned to each resource in [[resourceAssignments]]. The slices are distinguished
 *    from each other by their indices in [[TestResourceAssignment.interestingSliceReplicas]]. For
 *    example, in
 *    {{{
 *      MigrationTestAssignment(
 *        "pod0" --> Seq(35.0, 30.0),
 *        "pod1" --> Seq(35.0, ----),
 *        "pod2" --> Seq(----, ----)
 *      )
 *    }}}
 *    The first interesting slice contains 2 replicas with 35.0 load each assigned on pod0 and pod1,
 *    and the second interesting slice contains 1 replica with 30.0 load assigned on pod0. Note that
 *    pod2 is assigned neither the first nor the second aforementioned slices, and will be assigned
 *    only "placeholder" slices (see below).
 *  - Other than the [[TestResourceAssignment.interestingSliceReplicas]], the
 *    MigrationTestAssignment automatically creates the "placeholder" slices in the generated real
 *    assignments, either to satisfy the requirements of [[TestResourceAssignment.extraLoad]], or to
 *    make the slices fill up the whole slice space.
 *  IMPORTANT note:
 *  - While [[MigrationTestAssignment]] is designed to simplify the construction of migration phase
 *    tests, it does not completely eliminate the need to choose slice and resource load values in
 *    such a way that avoids interference from the policy phase of the algorithm. For example,
 *    although [[MigrationTestAssignment]] will create single-key slices for each of the interesting
 *    slices to avoid the possibility of splits in the policy phase, these interesting slices are
 *    still susceptible to replication or de-replication if their load is too high or too low. Also,
 *    if the interesting slices have relatively little load compared to the total load on the
 *    resources, then it is possible that the placeholder slices could end up being hotter than the
 *    interesting slices, causing the migration phase to consider them instead of the interesting
 *    slices for migration. Users should call [[assertHasSameInterestingSlicesAndTotalLoadsAs]] to
 *    check that the final result is as expected.
 */
class MigrationTestAssignment(resourceAssignments: Seq[TestResourceAssignment]) {

  /** Number of the slices the test is interested in. */
  private val interestingSlicesCount: Int = resourceAssignments.head.interestingSliceReplicas.size

  for (resourceAssignment: TestResourceAssignment <- resourceAssignments) {
    require(
      resourceAssignment.interestingSliceReplicas.size == interestingSlicesCount,
      s"Each resource should have the same number of interesting slices."
    )
  }

  /** An arbitrary default resource to assign the placeholder slices with 0 load. */
  private val defaultResource: Squid = resourceAssignments.head.resource

  /**
   * assignment: A real assignment converted from the [[resourceAssignments]] such that:
   *   - Each slice in [[resourceAssignments]] is represented in the returned assignment as a
   *     single-key slice, assigned to the corresponding resources, given the configured load, and
   *     where each replica is annotated with a "continuously assigned since" generation based on
   *     the configured age. Additionally, if a resource in [[resourceAssignments]] is specified to
   *     have extra load (see [[TestResourceAssignment]]), then additionally single key slices with
   *     load are assigned to the resource to increase the resource's load by this amount.
   *   - [[TestResourceAssignment.interestingSliceReplicas]] represents a replica of the slice
   *     represented by that index.
   *   - The slices filling [[TestResourceAssignment.extraLoad]] have 1 replica and at most 1.0
   *     load each.
   *   - The unfilled gaps of the slice space will be filled with slices containing 0 load
   *     assigned to arbitrary resources.
   *
   * loadMap: A load map containing the same load distribution as the `assignment`.
   */
  val (assignment, loadMap): (Assignment, LoadMap) = {
    val interestingSliceAssignments = ArrayBuffer.empty[SliceAssignment]

    val generation: Generation =
      TestSliceUtils.createLooseGeneration(RealtimeTypedClock.instant().toEpochMilli)

    val sliceAssignmentsBuilder = Vector.newBuilder[SliceAssignment]
    val loadMapBuilder = LoadMap.newBuilder()

    // Fill up the gap of ["", 0) with 0 load, so that the interesting slices start from [0, 1)
    // rather than ["", 0), simplifying implementation and debugging.
    sliceAssignmentsBuilder += ("" -- 0) @@ generation -> Seq(defaultResource)

    var sliceIndex: Int = 0

    // Fill up all the interesting slice replicas with single-key slices. These slices are from
    // [0, 1) to [interestingSlicesCount - 1, interestingSlicesCount).
    while (sliceIndex < interestingSlicesCount) {
      val slice: Slice = sliceIndex -- (sliceIndex + 1)
      val assignedResources = mutable.Set.empty[Squid]
      var sliceLoad: Double = 0
      val subsliceAnnotations = mutable.Map.empty[Squid, Vector[SubsliceAnnotation]]
      // Find out which resources are assigned to `slice`, record them in `assignedResources` and
      // add SubsliceAnnotations for them in `subsliceAnnotations` based on the required
      // continuously assigned duration.
      for (resourceAssignment: TestResourceAssignment <- resourceAssignments) {
        val resource: Squid = resourceAssignment.resource
        val sliceReplicaLoad: TestSliceReplica =
          resourceAssignment.interestingSliceReplicas(sliceIndex)
        sliceReplicaLoad match {
          case assigned: TestSliceReplica.Assigned =>
            assignedResources += resource
            assert(
              sliceReplicaLoad.load > 1.0,
              s"Load on assigned slice replicas must be greater than 1.0 to exclude the " +
              s"interference from placeholder slices"
            )
            sliceLoad += sliceReplicaLoad.load
            subsliceAnnotations(resource) = Vector(
              SubsliceAnnotation(
                slice,
                UnixTimeVersion(
                  generation.number.value - assigned.continuouslyAssignedDuration.toMillis
                ),
                stateTransferOpt = None
              )
            )
            // Make sure each assigned slice replica for the same slice has the same load.
            require(
              sliceReplicaLoad.load == sliceLoad / assignedResources.size,
              s"All assigned slice replicas for the same slice should have the same load. " +
              s"Got a slice replica at the ${sliceIndex + 1}th column for resource $resource " +
              s"with load ${sliceReplicaLoad.load} but the average slice replica load is " +
              s"${sliceLoad / assignedResources.size}."
            )
          case TestSliceReplica.Unassigned =>
            ()
        }
      }
      val sliceAssignment: SliceAssignment =
        (slice @@ generation -> assignedResources | subsliceAnnotations.toMap)
          .withPrimaryRateLoad(sliceLoad)
      sliceAssignmentsBuilder += sliceAssignment
      interestingSliceAssignments += sliceAssignment
      loadMapBuilder.putLoad(LoadMap.Entry(slice, sliceLoad))
      sliceIndex += 1
    }

    // Fill up the slices to satisfy the extra load.
    for (resourceAssignment: TestResourceAssignment <- resourceAssignments) {
      var extraLoadToFill: Double = resourceAssignment.extraLoad
      while (extraLoadToFill > 0) {
        val slice: Slice = sliceIndex -- (sliceIndex + 1)
        val sliceReplicaLoad: Double = 1.0.min(extraLoadToFill)
        sliceAssignmentsBuilder += (slice @@ generation -> Seq(resourceAssignment.resource))
          .withPrimaryRateLoad(sliceReplicaLoad)
        loadMapBuilder.putLoad(LoadMap.Entry(slice, sliceReplicaLoad))
        extraLoadToFill -= sliceReplicaLoad
        sliceIndex += 1
      }
    }

    // Fill up the remaining gap with 0.0 load.
    loadMapBuilder.putLoad(LoadMap.Entry(sliceIndex -- ∞, 0.0))
    sliceAssignmentsBuilder += ((sliceIndex -- ∞) @@ generation -> Seq(defaultResource))
      .withPrimaryRateLoad(0.0)
    (
      Assignment(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        generation,
        SliceMapHelper.ofSliceAssignments(sliceAssignmentsBuilder.result())
      ),
      loadMapBuilder.build()
    )
  }

  /**
   * Asserts that a given `assignment` is compatible with the current [[MigrationTestAssignment]]
   * in such a way that:
   * - There is a subset of SliceAssignments in `assignment` that match all interesting slices
   *   (see [[TestResourceAssignment.interestingSliceReplicas]]) in the [[MigrationTestAssignment]]
   *   in terms of load and assigned resources (but ignore the exact slice boundaries and subslice
   *   annotations).
   * - Each resource in [[resourceAssignments]] has the same total load as that in the given
   *   `assignment`.
   *
   * Used for verifying a real assignment generated by Algorithm against an expected
   * [[MigrationTestAssignment]]. We only check the properties mentioned above because we're only
   * interested in checking the final state of the interesting slices and total resource load, and
   * not the rest of the assignment.
   *
   * @param actualAssignment  The actual generated assignment to be verified with the expected
   *                    [[MigrationTestAssignment]].
   * @param description The description or debug message to be shown when the assertion fails.
   */
  def assertHasSameInterestingSlicesAndTotalLoadsAs(
      actualAssignment: Assignment,
      description: String): Unit = {
    // Track the Slices in `assignment` that have been already matched to any interesting slices.
    val matchedSlices = mutable.Set.empty[Slice]

    for (interestingSliceIndex: Int <- 1 to interestingSlicesCount) {
      val expectedSliceAssignment: SliceAssignment =
        // Note this is the assignment converted from the MigrationTestAssignment rather than the
        // actual assignment passed in.
        assignment.sliceAssignments(interestingSliceIndex)
      val matchedSliceAssignmentOpt: Option[SliceAssignment] =
        actualAssignment.sliceAssignments.find { sliceAssignment: SliceAssignment =>
          Math.abs(
            sliceAssignment.primaryRateLoadOpt.get -
            expectedSliceAssignment.primaryRateLoadOpt.get
          ) < 1e-6 &&
          sliceAssignment.resources == expectedSliceAssignment.resources &&
          !matchedSlices.contains(sliceAssignment.slice)
        }
      assert(
        matchedSliceAssignmentOpt.isDefined,
        s"Cannot find expected slice assignment: $expectedSliceAssignment.\n Test description: " +
        s"$description.\n Actual assignment: $actualAssignment"
      )
      matchedSlices += matchedSliceAssignmentOpt.get.slice
    }

    // Calculate actual total load for each resource.
    val rawLoadByResource: Map[Squid, Double] =
      AssignmentLoadStats.calculateSelfTrackedLoadStats(actualAssignment).loadByResource

    // Verify total load for each resource.
    for (testResourceAssignment: TestResourceAssignment <- resourceAssignments) {
      val resource: Squid = testResourceAssignment.resource
      val expectedLoad: Double = testResourceAssignment.totalLoad
      val actualLoad: Double = rawLoadByResource(resource)
      assert(
        Math.abs(expectedLoad - actualLoad) < 1e-6,
        s"Expect the total load for ${testResourceAssignment.resource} to be $expectedLoad, " +
        s"but got $actualLoad"
      )
    }
  }
}

object MigrationTestAssignment {

  /** See [[MigrationTestAssignment]] constructor. */
  def create(resourceAssignments: TestResourceAssignment*): MigrationTestAssignment = {
    new MigrationTestAssignment(resourceAssignments)
  }

  /**
   * Representation of a slice replica in a [[MigrationTestAssignment]]. See
   * [[TestSliceReplica.Assigned]] and [[TestSliceReplica.Unassigned]] for more information.
   */
  sealed trait TestSliceReplica {

    /** Returns the _raw_ load of the slice replica. */
    def load: Double
  }

  object TestSliceReplica {

    /**
     * The class representing that a slice replica is assigned on some resource in a
     * [[MigrationTestAssignment]], with the raw replica load being `load` and the continuous
     * assigned age being `continuouslyAssignedDuration`. We specially emphasize the slice replica
     * is "assigned" using this class because we will specify both "assigned" and "unassigned" slice
     * replicas in a MigrationTestAssignment to make the table in an aligned format for the
     * readability of tests.
     *
     * See examples on [[MigrationTestAssignment]]'s constructor for how it is used.
     *
     * @param continuouslyAssignedDuration The continuously assigned duration of the slice replica,
     *                                     relative to the generation of the assignment that owns
     *                                     this replica (rather than [[fakeClock]]). Used to support
     *                                     varying churn penalties within the test assignment.
     *
     * @throws IllegalArgumentException    if `load` is non-positive.
     * @throws IllegalArgumentException    if `continuouslyAssignedDuration` is negative.
     */
    case class Assigned @throws[IllegalArgumentException]()(
        override val load: Double,
        continuouslyAssignedDuration: FiniteDuration
    ) extends TestSliceReplica {
      require(load > 0.0, "load must be positive.")
      require(
        continuouslyAssignedDuration >= Duration.Zero,
        "continuouslyAssignedDuration must be non-negative."
      )

      override def toString: String = continuouslyAssignedDuration match {
        case Duration.Zero => s"$load"
        case continuouslyAssignedDuration: FiniteDuration =>
          s"$load ($continuouslyAssignedDuration)"
      }

      /** Shorthand method to override the continuouslyAssignedDuration. */
      def `@@`(continuouslyAssignedDuration: FiniteDuration): Assigned = {
        this.copy(continuouslyAssignedDuration = continuouslyAssignedDuration)
      }
    }

    /**
     * Representing that a slice is not assigned to a resource in [[TestResourceAssignment]] or
     * [[MigrationTestAssignment]]
     */
    case object Unassigned extends TestSliceReplica {
      override def load: Double = 0.0

      /*
       * Shorthand methods that return an `Unassigned` and can be recursively combined to
       * create literal representations of Unassigned with various lengths, e.g. `--.--.--`, so
       * users can use ones with proper length to make the columns in the test assignment
       * representation aligned.
       */
      def `-` : Unassigned.type = Unassigned
      def `--` : Unassigned.type = Unassigned
    }

    /* Top level shorthand methods that return [[Unassigned]]. */
    def `--` : Unassigned.type = Unassigned
    def `---` : Unassigned.type = Unassigned
  }

  /**
   * Class encapsulating the properties of a [[resource]] in a [[MigrationTestAssignment]]: a
   * resource will contain a sequence of "interesting" slice replicas, which means the test is
   * focusing on how these slice replicas are being migrated in migration phase. The
   * [[MigrationTestAssignment]] will create these (assigned) slice replicas using single-key slices
   * to avoid splitting (see [[MigrationTestAssignment.assignment]]), and can specially verify these
   * slice replicas in the output assignment (see
   * [[MigrationTestAssignment.assertHasSameInterestingSlicesAndTotalLoadsAs]]). A resource also has
   * an amount of "uninteresting" extra load, so the caller can control the total load of the
   * resource without manually crafting complex "interesting slice replicas" to satisfy a certain
   * total load.
   *
   * @param resource                 The incarnation of the resource.
   * @param interestingSliceReplicas The slice replicas owned by the resource that the test is
   *                                 interested in. The slices are distinguished by their indices
   *                                 in this sequence. See
   *                                 [[MigrationTestAssignment.assignment]] for more information.
   * @param extraLoad                The extra load assigned to the resource other than those in
   *                                 [[interestingSliceReplicas]]. See
   *                                 [[MigrationTestAssignment.assignment]] for more information.
   */
  case class TestResourceAssignment(
      resource: Squid,
      interestingSliceReplicas: Seq[TestSliceReplica],
      extraLoad: Double) {

    require(extraLoad >= 0.0)

    /**
     * The total load of the resource, i.e. sum of load from [[interestingSliceReplicas]] and
     * [[extraLoad]].
     */
    lazy val totalLoad: Double = {
      val interestingLoad: Double = interestingSliceReplicas.map((_: TestSliceReplica).load).sum
      extraLoad + interestingLoad
    }

    /**
     * Overwrites the [[extraLoad]] to create a new [[TestResourceAssignment]] whose load from
     * [[interestingSliceReplicas]] and [[extraLoad]] sums up to the given total `load`.
     */
    def withTotal(load: Double): TestResourceAssignment = {
      val interestingLoad: Double = interestingSliceReplicas.map((_: TestSliceReplica).load).sum
      require(
        load >= interestingLoad,
        s"Total load must be no less than existing interesting load."
      )
      this.copy(extraLoad = load - interestingLoad)
    }
  }

  implicit class TestResourceAssignmentFluent(resourceUri: String) {

    /**
     * Shorthand and implicit conversion to create a [[TestResourceAssignment]] with simple syntax,
     * e.g. `"pod0" --> Seq(42.0, 43.0)` creates a
     * `TestResourceAssignment(resource = "pod0", Seq(42.0, 43.0), extraLoad = 0.0)`.
     */
    def -->(replicas: Seq[TestSliceReplica]): TestResourceAssignment = {
      TestResourceAssignment(resourceUri, replicas, extraLoad = 0.0)
    }
  }

  /**
   * Implicit conversion from a load value to a [[TestSliceReplica]], with zero continuously
   * assigned duration.
   */
  implicit def doubleToTestSliceReplica(load: Double): TestSliceReplica.Assigned =
    TestSliceReplica.Assigned(load, continuouslyAssignedDuration = Duration.Zero)
}
