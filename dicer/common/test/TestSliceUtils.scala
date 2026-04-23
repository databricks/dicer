package com.databricks.dicer.common

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.mutable
import scala.language.implicitConversions
import scala.util.Random
import com.google.common.collect.{BoundType, Range}
import com.google.common.hash.{HashCode, Hashing}
import com.google.common.primitives.Longs
import com.google.protobuf.ByteString
import org.scalatest.Assertions
import com.databricks.caching.util.{AsciiTable, AssertionWaiter, TestUtils, UnixTimeVersion}
import com.databricks.caching.util.AsciiTable.Header
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap, Resources}
import com.databricks.dicer.assigner.config.InternalTargetConfig
import com.databricks.dicer.assigner.AssignmentStats.AssignmentLoadStats
import com.databricks.dicer.client.{ClerkImpl, SliceletImpl}
import com.databricks.dicer.external.{
  Clerk,
  HighSliceKey,
  InfinitySliceKey,
  ResourceAddress,
  Slice,
  SliceKey,
  Slicelet,
  Target
}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.dicer.common.test.SimpleDiffAssignmentP
import com.databricks.dicer.common.test.SimpleDiffAssignmentP.{
  SimpleSliceAssignmentP,
  SubsliceAnnotationP,
  TransferP
}
import com.databricks.dicer.common.test.SliceKeyTestDataP.HighSliceKeyP
import com.databricks.dicer.common.test.SliceKeyTestDataP.HighSliceKeyP.{
  InfinitySliceKeyP,
  SliceKeyFunctionP,
  SliceKeyP
}

import scala.concurrent.duration.Duration

/** Utilities relevant for Slices, Assignments, etc, i.e., any Slice-related abstractions */
object TestSliceUtils extends Assertions {

  /**
   * Generate a random Long between [low, high). Note that this may be prone to double rounding
   * effects, thus should generally only be used when `low` and `high` are sufficiently far apart.
   */
  def randomInRange(low: Long, high: Long, rng: Random): Long = {
    low + (rng.nextDouble() * (high - low).toDouble).toLong
  }

  /**
   * A loose ([[Incarnation.isLoose]]) incarnation suitable for tests leveraging the in-memory
   * store.
   */
  val LOOSE_INCARNATION: Incarnation = Incarnation(1703182631)

  /**
   * Asserts expected properties of the given assignment:
   *
   *  - The number of replicas for each slice is between the min and max replicas as specified in
   *    `targetConfig` (or if the number of available resources is less than min replicas, then
   *    each slice is expected to be assigned to all resources).
   *  - Each resource in `resources` is assigned at least one Slice.
   *  - Has between [[Algorithm.forTest.MIN_AVG_SLICE_REPLICAS]] and
   *    [[Algorithm.forTest.MAX_AVG_SLICE_REPLICAS]] slice replicas per resource.
   *  - Each available resource has approximately the same total load. The `targetConfig` is
   *    consulted to determine what an acceptable imbalance is. Callers may optionally specify
   *    `imbalanceToleranceOverrideOpt` to override the default however, as there may be test cases
   *    where a higher or lower tolerance is warranted. For example, some tests construct examples
   *    that cannot be effectively load-balanced, as when a resource is assigned a single-key Slice
   *    that is too hot.
   *  - All keys must be included exactly once in the assignment.
   *
   * This is test code rather the production code because its assertions and assumptions are overly
   * strong for all production cases.
   *
   * `imbalanceToleranceOverrideOpt` optionally overrides the imbalance tolerance, which is the
   * maximum amount the load for any resource may be above or below from the average load. For
   * example, if the average load is 100 and `imbalanceToleranceOverrideOpt` is `Some(30)`, then
   * resources may have load between 70 and 130 inclusive.
   */
  def assertDesirableAssignmentProperties(
      targetConfig: InternalTargetConfig,
      assignment: Assignment,
      resources: Resources,
      imbalanceToleranceOverrideOpt: Option[Double] = None
  ): Unit = {
    // Check that the assignment contains all available resources.
    val actualResources: Set[Squid] =
      assignment.sliceAssignments.flatMap { sliceAssignment =>
        sliceAssignment.resources
      }.toSet
    val availableResources: Set[Squid] = resources.availableResources
    assert(
      actualResources == availableResources,
      s"expected all available resources (${availableResources.toSeq.sorted}) to be assigned, " +
      s"but found that the set of resources actually assigned were ${actualResources.toSeq.sorted}"
    )

    // Check that the assignment has between [[Algorithm.forTest.MIN_AVG_SLICES]] and
    // [[Algorithm.forTest.MAX_AVG_SLICES]] slices per resource.
    val numResources: Int = availableResources.size
    val numTotalSliceReplicas: Int = calculateNumSliceReplicas(assignment)
    assert(numTotalSliceReplicas >= numResources * Algorithm.forTest.MIN_AVG_SLICE_REPLICAS)
    assert(numTotalSliceReplicas <= numResources * Algorithm.forTest.MAX_AVG_SLICE_REPLICAS)
    val minReplicas: Int = targetConfig.keyReplicationConfig.minReplicas
    val maxReplicas: Int = targetConfig.keyReplicationConfig.maxReplicas
    val adjustedMinReplicas: Int = minReplicas.min(numResources)
    for (sliceAssignment <- assignment.sliceAssignments) {
      val numReplicas: Int = sliceAssignment.resources.size
      assert(numReplicas >= adjustedMinReplicas)
      assert(numReplicas <= maxReplicas)
    }

    // Calculate the load per resource, number of slices per resource, and the coldest slice replica
    // per resource.
    val reservationAdjustedLoadStats: AssignmentLoadStats =
      AssignmentLoadStats.calculateSelfTrackedAdjustedLoadStats(
        assignment,
        targetConfig.loadBalancingConfig
      )
    val reservationAdjustedLoadByResource: Map[Squid, Double] =
      reservationAdjustedLoadStats.loadByResource
    val reservationAdjustedLoadBySlice: Map[Slice, Double] =
      reservationAdjustedLoadStats.loadBySlice
    val numSlicesByResource: Map[Squid, Int] =
      reservationAdjustedLoadStats.numOfAssignedSlicesByResource
    val totalReservationAdjustedLoad: Double = reservationAdjustedLoadByResource.values.sum
    val coldestSliceReplicaByResource = mutable.Map[Squid, Double]()
    for (resource: Squid <- availableResources) {
      coldestSliceReplicaByResource.put(resource, Double.MaxValue)
    }
    for (sliceAssignment: SliceAssignment <- assignment.sliceAssignments) {
      val slice: Slice = sliceAssignment.slice
      val sliceLoad: Double = reservationAdjustedLoadBySlice(slice)
      val numReplicas: Int = sliceAssignment.resources.size
      for (resource: Squid <- sliceAssignment.resources) {
        coldestSliceReplicaByResource(resource) =
          coldestSliceReplicaByResource(resource).min(sliceLoad / numReplicas)
      }
    }

    // Check that each resource has roughly the same load.
    val averageReservationAdjustedLoad: Double = totalReservationAdjustedLoad / numResources
    val maxLoad: Double = reservationAdjustedLoadByResource.values.max
    val minLoad: Double = reservationAdjustedLoadByResource.values.min
    val loadBalancingConfig: InternalTargetConfig.LoadBalancingConfig =
      targetConfig.loadBalancingConfig
    val primaryRateMetric: InternalTargetConfig.LoadBalancingMetricConfig =
      loadBalancingConfig.primaryRateMetric
    // If not overridden by `imbalanceToleranceOverrideOpt`, the imbalance tolerance is relative to
    // the max load hint or the average load per resource, whichever is higher.
    val adjustedMaxLoadHint: Double =
      primaryRateMetric.maxLoadHint.max(averageReservationAdjustedLoad)
    val imbalanceTolerance: Double = imbalanceToleranceOverrideOpt.getOrElse(
      adjustedMaxLoadHint * primaryRateMetric.imbalanceToleranceRatio
    )
    // Returns a table summarizing information about the resources, and includes row entries for the
    // max, average, and min desired load to assist debugging when the assertions below fail.
    def getResourcesAsciiTable: String = {
      val tableEntries: Seq[(String, Double, String, String, String)] =
        (actualResources.toSeq.map { resource: Squid =>
          (
            resource.toString,
            reservationAdjustedLoadByResource(resource),
            (reservationAdjustedLoadByResource(resource) *
            (1.0 + loadBalancingConfig.churnConfig.maxPenaltyRatio)).toString,
            numSlicesByResource(resource).toString,
            coldestSliceReplicaByResource(resource).toString
          )
        } ++ Seq(
          (
            "Max Desired Load",
            averageReservationAdjustedLoad + imbalanceTolerance,
            "-",
            "-",
            "-"
          ),
          (
            "Average Load",
            averageReservationAdjustedLoad,
            "-",
            "-",
            "-"
          ),
          (
            "Min Desired Load",
            (averageReservationAdjustedLoad - imbalanceTolerance).max(
              Double.MinPositiveValue
            ),
            "-",
            "-",
            "-"
          )
        )).sortBy(_._2).reverse
      val table = new AsciiTable(
        Header("Resource"),
        Header("Load"),
        Header("Max Churn Adjusted Load"),
        Header("Slice count"),
        Header("Coldest slice load")
      )
      for (tableEntry <- tableEntries) {
        table.appendRow(
          tableEntry._1,
          tableEntry._2.toString,
          tableEntry._3,
          tableEntry._4,
          tableEntry._5
        )
      }
      table.toString()
    }

    assert(
      minLoad >= averageReservationAdjustedLoad - imbalanceTolerance,
      s"expected resource load to be above " +
      s"$averageReservationAdjustedLoad - $imbalanceTolerance " +
      s"(imbalanceToleranceMinToAvgRatio=${imbalanceTolerance / adjustedMaxLoadHint}), " +
      s"but min load was $minLoad yielding an actual lower imbalance ratio of " +
      s"${(averageReservationAdjustedLoad - minLoad) / adjustedMaxLoadHint}. " +
      s"Resources had load\n$getResourcesAsciiTable\ntargetConfig: $targetConfig\n"
    )
    assert(
      maxLoad <= averageReservationAdjustedLoad + imbalanceTolerance,
      s"expected resource load to be below " +
      s"$averageReservationAdjustedLoad + $imbalanceTolerance " +
      s"(imbalanceToleranceAvgToMaxRatio=${imbalanceTolerance / adjustedMaxLoadHint}), " +
      s"but max load was $maxLoad yielding an actual upper imbalance ratio of " +
      s"${(maxLoad - averageReservationAdjustedLoad) / adjustedMaxLoadHint}. " +
      s"resources had load\n$getResourcesAsciiTable\ntargetConfig: $targetConfig\n"
    )
  }

  /**
   * Returns true if any of the subslice annotations within `assignment` contain state transfer
   * information.
   */
  def hasStateTransfers(assignment: Assignment): Boolean = {
    assignment.sliceAssignments
      .flatMap((_: SliceAssignment).subsliceAnnotationsByResource.values.flatten)
      .exists((_: SubsliceAnnotation).stateTransferOpt.isDefined)
  }

  /**
   * REQUIRES: `range` bounds follow the semantics of [[Slice]], i.e. it has a closed lower bound,
   * and if it has an upper bound then it is open.
   *
   * Converts a [[Range]] to an equivalent [[Slice]], or throws [[IllegalArgumentException]] if
   * there is no equivalent representation.
   */
  def sliceFromRange(range: Range[SliceKey]): Slice = {
    // Note: there is an equivalent implementation in `object MutableSliceMap`, unfortunately they
    // use different shaded versions of guava and thus must be duplicated.
    require(range.hasLowerBound)
    require(range.lowerBoundType() == BoundType.CLOSED)
    if (range.hasUpperBound) {
      require(range.upperBoundType() == BoundType.OPEN)
      Slice(range.lowerEndpoint(), range.upperEndpoint())
    } else {
      Slice.atLeast(range.lowerEndpoint())
    }
  }

  /** Converts a [[Slice]] to an equivalent [[Range]]. */
  def rangeFromSlice(slice: Slice): Range[SliceKey] = slice.highExclusive match {
    // Note: there is an equivalent implementation in `object MutableSliceMap`, unfortunately they
    // use different shaded versions of guava and thus must be duplicated.
    case highExclusive: SliceKey => Range.closedOpen(slice.lowInclusive, highExclusive)
    case InfinitySliceKey => Range.atLeast(slice.lowInclusive)
  }

  /**
   * An arbitrary creation time used for all test SQUIDs. Since test squids have deterministic
   * UUIDs, the specific creation time doesn't matter: the creation time will not need to be used
   * to break ties.
   */
  private val TEST_SQUID_CREATION_TIME_MILLIS: Long = 1680279132000L

  /**
   * Creates a Squid for the given URI. Produces a deterministic "UUID" by taking a fingerprint
   * of the URI plus the given salt.
   */
  def createTestSquid(uri: String, salt: String = ""): Squid = {
    val resourceAddress = ResourceAddress(URI.create(uri))
    val hash: HashCode =
      Hashing.murmur3_128().hashBytes((uri + salt).getBytes(StandardCharsets.UTF_8))
    val bytes: Array[Byte] = hash.asBytes()
    val uuidInScareQuotes = UUID.nameUUIDFromBytes(bytes)
    Squid(resourceAddress, TEST_SQUID_CREATION_TIME_MILLIS, uuidInScareQuotes)
  }

  /**
   * REQUIRES: `resourceNames` are unique.
   *
   * Creates `Resources` with available resources with the given names.
   */
  def createResources(resourceNames: String*): Resources = {
    val healthyResources: Seq[Squid] = resourceNames.map { name =>
      createTestSquid(name)
    }
    val resources = Resources.create(healthyResources)
    require(
      resources.availableResources.size == resourceNames.size,
      s"resourceNames must not contain duplicate addresses: $resourceNames"
    )
    resources
  }

  /** Creates a [[SliceKey]] with FarmHash Fingerprint64 of the given application key. */
  def fp(applicationKey: String): SliceKey =
    SliceKey.newFingerprintBuilder().putString(applicationKey).build()

  /** Creates a [[SliceKey]] with FarmHash Fingerprint64 of the given application key. */
  def fp(applicationKey: Array[Byte]): SliceKey =
    SliceKey.newFingerprintBuilder().putBytes(applicationKey).build()

  /** Creates a [[SliceKey]] with the given application key encoded to UTF-8. */
  def identityKey(applicationKey: String): SliceKey = {
    SliceKey.fromRawBytes(
      ByteString.copyFrom(applicationKey.getBytes(StandardCharsets.UTF_8))
    )
  }

  /** Creates a [[SliceKey]] with the given application key bytes. */
  def identityKey(applicationKey: Array[Byte]): SliceKey = {
    SliceKey.fromRawBytes(ByteString.copyFrom(applicationKey))
  }

  /** Creates a [[SliceKey]] with the given application key integers interpreted as bytes. */
  def identityKey(applicationKey: Int*): SliceKey = {
    SliceKey.fromRawBytes(ByteString.copyFrom(applicationKey.map(_.toByte).toArray))
  }

  /** Creates a [[SliceKey]] with the raw bytes of the given application key. */
  def identityKey(applicationKey: Long): SliceKey = {
    SliceKey.fromRawBytes(ByteString.copyFrom(Longs.toByteArray(applicationKey)))
  }

  /** Creates a [[SliceKey]] with a single byte. */
  def singleByteIdentityKey(applicationKey: Int): SliceKey = {
    SliceKey.fromRawBytes(ByteString.copyFrom(Array(applicationKey.toByte)))
  }

  /**
   * Creates a [[SliceKey]] with SHA-256 and FarmHash Fingerprint64 of the given application key.
   */
  def encryptedFp(applicationKey: String): SliceKey = {
    encryptedFp(applicationKey.getBytes(StandardCharsets.UTF_8))
  }

  /**
   * Creates a [[SliceKey]] with SHA-256 and FarmHash Fingerprint64 of the given application key.
   */
  def encryptedFp(applicationKey: Array[Byte]): SliceKey = {
    val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
    fp(messageDigest.digest(applicationKey))
  }

  /**
   * Creates an ordered sequence of `n` slices with random 8-byte boundaries covering the full key
   * space ["" .. ∞).
   */
  def createCompleteSlices(n: Int, rng: Random): Vector[Slice] = {
    require(n > 0)

    val sortedLows: Vector[SliceKey] = {
      val lows = mutable.Set[SliceKey]()

      // Include "" for completeness.
      lows.add(SliceKey.MIN)
      while (lows.size < n) {
        lows.add(identityKey(rng.nextLong()))
      }
      lows.toVector.sorted
    }
    val sortedHighs: Vector[HighSliceKey] = sortedLows.tail ++ Vector(
        InfinitySliceKey
      )
    sortedLows.zip(sortedHighs).map {
      case (lowInclusive: SliceKey, highExclusive: HighSliceKey) =>
        Slice(lowInclusive, highExclusive)
    }
  }

  /**
   * Returns a randomly generated load map with `numSlices` and the given total load. The Slices
   * have random boundaries. The "density" of load in each Slice is also random, but the ratio of
   * the highest to the lowest density is no more than `maxToMinDensityRatio`. Density is defined as
   * the load per unit of Slice size (where the Slice size unit is based on [[LoadMap]]'s definition
   * of uniform load).
   *
   * TODO(<internal bug>): Write tests for this method in `TestSliceUtilsSuite`.
   * TODO(<internal bug>): Support creating load maps that have hot keys.
   */
  def createRandomLoadMap(
      rng: Random,
      totalLoad: Double,
      numSlices: Int = 128,
      maxToMinDensityRatio: Double = 4.0): LoadMap = {
    // Creates `numSlices` Slices with random boundaries.
    val slices: Vector[Slice] = createCompleteSlices(numSlices, rng)

    // Choose relative load values for each Slice. We'll scale to get the desired `totalLoad` after
    // we've chosen relative load values for all Slices.
    val relativeLoads: Vector[Double] = slices.map { slice: Slice =>
      val relativeDensity: Double = 1.0 + rng.nextDouble() * (maxToMinDensityRatio - 1.0)
      val sliceSize: Double = LoadMap.UNIFORM_LOAD_MAP.getLoad(slice)
      relativeDensity * sliceSize
    }
    // Normalize the relative loads so that they sum to `totalLoad`.
    val scalingFactor: Double = totalLoad / relativeLoads.sum
    val entries: Seq[LoadMap.Entry] = slices.zip(relativeLoads).map {
      case (slice: Slice, relativeLoad: Double) =>
        LoadMap.Entry(slice, relativeLoad * scalingFactor)
    }
    LoadMap.newBuilder().putLoad(entries: _*).build()
  }

  /**
   * Creates a random Slice together with `numSubslices` ordered, disjoint, and complete random
   * subslices of this Slice.
   */
  def createRandomSliceWithSubslices(numSubslices: Int, rng: Random): (Slice, Vector[Slice]) = {
    require(numSubslices > 0)
    // Creates some complete Slices, then choose `numSubslices` contiguous subslices from it, and
    // use the combination of them as the returned Slice. Choose from `3 * numSubslices` complete
    // Slices for higher randomness level.
    val completeSlices: Vector[Slice] = createCompleteSlices(3 * numSubslices, rng)
    val startIndex: Int = rng.nextInt(completeSlices.size - numSubslices)
    val subslices: Vector[Slice] = completeSlices.slice(startIndex, startIndex + numSubslices)
    val slice = Slice(subslices.head.lowInclusive, subslices.last.highExclusive)
    (slice, subslices)
  }

  /**
   * Creates an assignment in which slice boundaries are chosen at random and resources assigned to
   * `numSlices` slices are chosen at random from `resources`. The resulting proposal includes every
   * resource at least once. Each Slice will have 1 replica at least, and `numMaxReplicas` replicas
   * at most.
   */
  def createRandomProposal(
      numSlices: Int,
      resources: IndexedSeq[Squid],
      numMaxReplicas: Int,
      rng: Random): SliceMap[ProposedSliceAssignment] = {
    require(numMaxReplicas >= 1)
    require(resources.size >= numMaxReplicas)
    require(numSlices * numMaxReplicas >= resources.size)

    // Keep track of resources that have not been used in the assignment yet.
    val unusedResources: mutable.Set[Squid] = mutable.Set[Squid]() ++ resources
    val sliceAssignments: Vector[ProposedSliceAssignment] =
      createCompleteSlices(numSlices, rng).map { slice: Slice =>
        val selectedResources = mutable.Set[Squid]()

        // In order to ensure all resources will be assigned, we first greedily assigned unused ones
        // to the Slice (but not assigning more than `numMaxReplicas` resources to it).
        while (unusedResources.nonEmpty && selectedResources.size < numMaxReplicas) {
          val resource: Squid = unusedResources.head
          selectedResources += resource
          unusedResources -= resource
        }

        // A random desired number of replicas for the Slice. If the Slice already has this number
        // of replicas, we don't try to shrink the replicas to fit this number, to ensure all
        // `resources` will be assigned.
        val numReplicas: Int = 1 + rng.nextInt(numMaxReplicas)
        while (selectedResources.size < numReplicas) {
          selectedResources += resources(rng.nextInt(resources.size))
        }

        slice -> selectedResources
      }
    SliceMapHelper.ofProposedSliceAssignments(sliceAssignments)
  }

  /**
   * Create a proposal with random boundaries where, with high probability, each assignment keeps
   * the same resource as `predecessor`, for a random point in each slice. This helps generate an
   * assignment with a lot of subslice annotations. Each Slice will have 1 replica at least, and
   * `numMaxReplicas` replicas at most.
   */
  def createBiasedProposal(
      numSlices: Int,
      predecessor: Assignment,
      resources: IndexedSeq[Squid],
      numMaxReplicas: Int,
      rng: Random): SliceMap[ProposedSliceAssignment] = {
    require(numMaxReplicas >= 1)
    require(resources.nonEmpty)
    val sliceAssignments: Vector[ProposedSliceAssignment] =
      createCompleteSlices(numSlices, rng).map { slice: Slice =>
        val selectedResources = mutable.ArrayBuffer[Squid]()

        // In order to build the new set of resource for this `slice`, first, pick a random key in
        // the Slice and take all the resources that owned that key in `predecessor`, so that it
        // will create some continuous assignment.
        val lowLong: Long =
          if (slice.lowInclusive == SliceKey.MIN) {
            0L
          } else {
            Longs.fromByteArray(slice.lowInclusive.bytes.toByteArray)
          }
        val highLong: Long = slice.highExclusive match {
          case key: SliceKey => Longs.fromByteArray(key.bytes.toByteArray)
          case InfinitySliceKey =>
            // The Long is treated as unsigned, so -1 is the largest value.
            -1L
        }
        val randomKey = randomInRange(lowLong, highLong, rng)
        selectedResources ++= predecessor.sliceMap.lookUp(randomKey).resources

        // With 10% probability, remove a random resource from the previously assigned resources.
        if (rng.nextDouble() < 0.1) {
          selectedResources.remove(rng.nextInt(selectedResources.size))
        }

        // With 10% probability, try to add a random (possibly new, possibly already chosen)
        // resource.
        if (rng.nextDouble() < 0.1 || selectedResources.isEmpty) {
          selectedResources += resources(rng.nextInt(resources.size))
        }

        // Keep `numMaxReplicas` requirement.
        while (selectedResources.size > numMaxReplicas) {
          selectedResources.remove(rng.nextInt(selectedResources.size))
        }

        slice -> selectedResources
      }

    SliceMapHelper.ofProposedSliceAssignments(sliceAssignments)
  }

  def sampleProposal(): SliceMap[ProposedSliceAssignment] = {
    createProposal(
      ("" -- "Dori") -> Seq("Pod2"),
      ("Dori" -- "Fili") -> Seq("Pod0"),
      ("Fili" -- "Kili") -> Seq("Pod1"),
      ("Kili" -- "Nori") -> Seq("Pod2"),
      ("Nori" -- ∞) -> Seq("Pod3")
    )
  }

  /**
   * Creates a [[ProposedSliceAssignment]] mapping `slice` to `resources`. Assigns
   * [[ProposedSliceAssignment.primaryRateLoadOpt]] assuming uniform load in the key
   * space.
   */
  private[common] def createProposedSliceAssignmentAssumingUniformLoad(
      slice: Slice,
      resources: Iterable[Squid]): ProposedSliceAssignment = {
    ProposedSliceAssignment(
      slice,
      resources.toSet,
      primaryRateLoadOpt = Some(LoadMap.UNIFORM_LOAD_MAP.getLoad(slice))
    )
  }

  /**
   * Creates a [[SliceAssignment]] mapping `slice` to `resources` as of `generation` and
   * with the given `subsliceAnnotationsByResource`. Assigns [[SliceAssignment.primaryRateLoadOpt]]
   * assuming uniform load in the key space.
   */
  private[common] def createSliceAssignmentAssumingUniformLoad(
      slice: Slice,
      generation: Generation,
      resources: Iterable[Squid],
      subsliceAnnotationsByResource: Map[Squid, Vector[SubsliceAnnotation]]): SliceAssignment = {
    SliceAssignment(
      sliceWithResources = SliceWithResources(slice, resources.toSet),
      generation,
      subsliceAnnotationsByResource,
      primaryRateLoadOpt = Some(LoadMap.UNIFORM_LOAD_MAP.getLoad(slice))
    )
  }

  /**
   * REQUIRES: `subslices` are disjoint and ordered subslices of `slice`.
   *
   * Creates a [[SliceAssignment]] that assigns `slice` to a random set of resources
   * as of `generation`, with a random primary rate load and random SubsliceAnnotations generated
   * from `subslices`.
   */
  def createRandomSliceAssignment(
      slice: Slice,
      subslices: Vector[Slice],
      generation: Generation,
      rng: Random): SliceAssignment = {
    // Number of resources assigned. Cannot be 0.
    val numResources: Int = rng.nextInt(10) + 1
    // Randomly assigned resources.
    val resources: Seq[Squid] = (0 until numResources).map { _ =>
      createTestSquid(uri = s"Pod${rng.nextInt(100)}")
    }
    // Random annotations for each assigned resource.
    val subsliceAnnotationsByResource: Map[Squid, Seq[SubsliceAnnotation]] = {
      if (subslices.isEmpty) {
        Map.empty
      } else {
        resources.map { resource: Squid =>
          val subsliceAnnotations: Vector[SubsliceAnnotation] =
            subslices.map { subslice: Slice =>
              // Create a SubsliceAnnotation for `subslice` with random generation number.
              val randomGenerationNumber: Long =
                randomInRange(low = 0, high = generation.number.value, rng)
              SubsliceAnnotation(
                subslice,
                continuousGenerationNumber = randomGenerationNumber,
                stateTransferOpt = None
              )
            }
          resource -> subsliceAnnotations
        }.toMap
      }
    }

    val primaryRateLoad: Double = rng.nextDouble()
    (slice @@ generation -> resources)
      .withPrimaryRateLoad(primaryRateLoad) | subsliceAnnotationsByResource
  }

  //
  // Code leveraging this DSL should use `import com.databricks.dicer.common.TestSliceUtils._`. See
  // TestSliceUtilsSuite.scala for sample usage, as the DSL types and function signatures may be
  // difficult to understand without context.
  //

  /** Shorthand for `identityKey(key)`. */
  implicit def toSliceKey(key: String): SliceKey = identityKey(key)

  /** Shorthand for `identityKey(key)`. */
  implicit def toSliceKey(key: Long): SliceKey = identityKey(key)

  /** Shorthand for `Generation(LOOSE_INCARNATION, number)`. */
  implicit def createLooseGeneration(generationNumber: Long): Generation =
    Generation(LOOSE_INCARNATION, generationNumber)

  /** Shorthand for "create Squid with deterministic UUID derived from URI". */
  implicit def toSquid(uri: String): Squid = createTestSquid(uri)

  /** Shorthand for converting an Iterable of URI strings to an Iterable of [[Squid]]. */
  implicit def toSquidIterable(uriIterable: Iterable[String]): Iterable[Squid] =
    uriIterable.map(toSquid)

  /**
   * Shorthand for converting a pair of a URI string and a value of type `T` to a pair of [[Squid]]
   * and the same value. Use cases include convenient manual creation of
   * [[SliceAssignment.subsliceAnnotationsByResource]].
   */
  implicit def toSquidWithValue[T](uriWithValue: (String, T)): (Squid, T) = {
    val (uri, value): (String, T) = uriWithValue
    toSquid(uri) -> value
  }

  /** Creates a [[ResourceAddress]] containing the URI parsed from the `uri` String. */
  implicit def toResourceAddress(uri: String): ResourceAddress = {
    ResourceAddress(URI.create(uri))
  }

  /** Shorthand for `InfinitySliceKey`. */
  def `∞` : HighSliceKey = InfinitySliceKey

  /**
   * Creates a [[SliceMap]] of [[ProposedSliceAssignment]]s given a non-empty sequence
   * of entries.
   */
  def createProposal(
      firstEntry: ProposedAssignmentEntry,
      remainingEntries: ProposedAssignmentEntry*): SliceMap[ProposedSliceAssignment] = {
    val sliceAssignments = Vector.newBuilder[ProposedSliceAssignment]
    sliceAssignments ++= firstEntry.toSliceAssignments
    for (entry: ProposedAssignmentEntry <- remainingEntries) {
      sliceAssignments ++= entry.toSliceAssignments
    }
    SliceMapHelper.ofProposedSliceAssignments(sliceAssignments.result())
  }

  /**
   * Creates an [[Assignment]] given a generation and a complete, ordered, and disjoint
   * sequence of [[SliceAssignment]].
   */
  def createAssignment(
      generation: Generation,
      consistencyMode: AssignmentConsistencyMode,
      firstEntry: SliceAssignment,
      remainingEntries: SliceAssignment*): Assignment = {
    createAssignment(generation, consistencyMode, firstEntry +: remainingEntries)
  }

  /**
   * Creates an [[Assignment]] given a generation and a non-empty, disjoint, and ordered
   * sequence of [[SliceAssignment]] entries.
   */
  def createAssignment(
      generation: Generation,
      consistencyMode: AssignmentConsistencyMode,
      entries: Iterable[SliceAssignment]): Assignment = {
    Assignment(
      isFrozen = false,
      consistencyMode,
      generation,
      SliceMapHelper.ofSliceAssignments(entries.toVector)
    )
  }

  /**
   * Treats `lowInclusive` as the low inclusive bound for a Slice.
   *
   * Supports syntax like `(fp("Fili") -- fp("Kili"))` as shorthand for
   * `Slice(fp("Fili"), fp("Kili"))`.
   */
  implicit class LowInclusiveSliceKeyFluent(lowInclusive: SliceKey) {

    /** Shorthand for `Slice(lowInclusive, highExclusive)`. */
    def --(highExclusive: HighSliceKey): Slice = Slice(lowInclusive, highExclusive)

    /**
     * Shorthand for `Slice.atLeast(lowInclusive)`. Enables syntax like `20.andGreater` as shorthand
     * for `Slice.atLeast(identityKey(Longs.toByteArray(20)))`.
     */
    def andGreater: Slice = Slice.atLeast(lowInclusive)
  }

  /**
   * Treats `slice` as the assigned Slice for a given slice-generation pair.
   */
  implicit class SubsliceAnnotationSliceFluent(slice: Slice) {

    /** Shorthand for `(slice, Some(UnixTimeVersion(generationNumber)))` */
    def |(generationNumber: Long): (Slice, Option[UnixTimeVersion]) =
      (slice, Some(UnixTimeVersion(generationNumber)))

    /** Shorthand for `(slice, Some(UnixTimeVersion(generationNumber)))` */
    def |(generationNumber: Int): (Slice, Option[UnixTimeVersion]) =
      (slice, Some(UnixTimeVersion(generationNumber.longValue())))

    /** Shorthand for `(slice, generationNumberOpt)` */
    def |(generationNumberOpt: Option[UnixTimeVersion]): (Slice, Option[UnixTimeVersion]) =
      (slice, generationNumberOpt)

  }

  /**
   * Treats `lowInclusive` as the low inclusive bound for a Slice.
   *
   * Supports syntax like `("a" -- "b")` as shorthand for
   * `Slice(identityKey("a"), IdentityKey("b"))`.
   */
  implicit class LowInclusiveStringFluent(lowInclusive: String)
      extends LowInclusiveSliceKeyFluent(lowInclusive)

  /**
   * Treats `lowInclusive` as the low inclusive bound for a Slice.
   *
   * Supports syntax like `(10L -- 20L)` as shorthand for
   * `Slice(identityKey(10L), IdentityKey(20L))`.
   */
  implicit class LowInclusiveLongFluent(lowInclusive: Long)
      extends LowInclusiveSliceKeyFluent(lowInclusive)

  /**
   * Treats `incarnation` as the incarnation for a generation.
   *
   * Supports syntax like `42##47` as shorthand for `Generation(Incarnation(2, 42), 47)`.
   */
  implicit class GenerationIncarnationFluent(incarnation: Long) {

    /** Shorthand for `Generation(Incarnation(incarnation), genNum)`. */
    def ##(genNum: Long): Generation = Generation(Incarnation(incarnation), genNum)
  }

  /**
   * Treats `slice` as the assigned Slice for a [[SliceAssignment]] or [[ProposedSliceAssignment]].
   *
   * Supports syntax like `("a" -- "b") -> "pod1"` as shorthand for
   *
   * {{{
   * ProposedSliceAssignment(
   *     Slice(identityKey("a"), identityKey("b")),
   *     ResourceAddress(URI.create("pod1"))
   * )
   * }}}
   *
   * Also an intermediate type for [[SliceAssignmentSliceAndGenerationFluent]].
   */
  implicit class SliceAssignmentSliceFluent(slice: Slice) {

    /** Returns Slice with Generation. See [[SliceAssignmentSliceAndGenerationFluent]]. */
    def @@(generation: Generation): SliceAssignmentSliceAndGenerationFluent = {
      SliceAssignmentSliceAndGenerationFluent(slice, generation)
    }

    /**
     * Creates a [[ProposedSliceAssignment]] mapping `slice` to `resources`. Assigns
     * [[ProposedSliceAssignment.primaryRateLoadOpt]] assuming uniform load in the key space.
     */
    def ->(resources: Iterable[Squid]): ProposedSliceAssignment = {
      createProposedSliceAssignmentAssumingUniformLoad(slice, resources)
    }
  }

  /**
   * Intermediate fluent type capturing the Slice and generation for a
   * [[SliceAssignment]].
   *
   * Supports syntax like `("a" -- "b") @@ 42 -> Seq("pod1", "pod2")` as shorthand for
   *
   * {{{
   * createSliceAssignmentAssumingUniformLoad(
   *   Slice(identityKey("a"), identityKey("b")),
   *   Generation(Incarnation(0), 42),
   *   resources = Set("pod1", "pod2"),
   *   Map.empty
   * )
   * }}}
   */
  case class SliceAssignmentSliceAndGenerationFluent(slice: Slice, generation: Generation) {

    /** Shorthand for
     * {{{
        createSliceAssignmentAssumingUniformLoad(
          slice,
          generation,
          resources,
          Map.empty
        )
     * }}}.
     */
    def ->(resources: Iterable[Squid]): SliceAssignment = {
      createSliceAssignmentAssumingUniformLoad(
        slice,
        generation,
        resources,
        Map.empty
      )
    }
  }

  /** Calculates the total number of slice replicas in the assignment. */
  def calculateNumSliceReplicas(assignment: Assignment): Int = {
    assignment.sliceAssignments.map((_: SliceAssignment).resources.size).sum
  }

  /** Fluent type supporting tweaking of [[SliceAssignment]] instances. */
  implicit class SliceAssignmentFluet(asn: SliceAssignment) {

    /**
     * Returns a new [[SliceAssignment]] that is identical to [[asn]] but with the given
     * `subsliceAnnotationsByResource`.
     */
    def |(subsliceAnnotationsByResource: Map[Squid, Seq[SubsliceAnnotation]]): SliceAssignment = {
      asn.copy(
        subsliceAnnotationsByResource =
          subsliceAnnotationsByResource.mapValues((_: Seq[SubsliceAnnotation]).toVector).toMap
      )
    }

    /**
     * Returns a new [[SliceAssignment]] that is identical to [[asn]] but with the given
     * historical primary rate load measurement.
     */
    def withPrimaryRateLoad(primaryRateLoad: Double): SliceAssignment = {
      asn.copy(primaryRateLoadOpt = Some(primaryRateLoad))
    }

    /**
     * Returns a new [[SliceAssignment]] that is identical to [[asn]] but without any
     * historical primary rate load measurement.
     */
    def clearPrimaryRateLoad(): SliceAssignment = {
      asn.copy(primaryRateLoadOpt = None)
    }
  }

  /** Fluent type supporting tweaking of [[ProposedSliceAssignment]] instances. */
  implicit class ProposedSliceAssignmentFluent(asn: ProposedSliceAssignment) {

    /**
     * Returns a new [[ProposedSliceAssignment]] that is identical to [[asn]] but with
     * the given historical primary rate load measurement.
     */
    def withPrimaryRateLoad(primaryRateLoad: Double): ProposedSliceAssignment = {
      asn.copy(primaryRateLoadOpt = Some(primaryRateLoad))
    }

    /**
     * Returns a new [[ProposedSliceAssignment]] that is identical to [[asn]] but
     * without any historical primary rate load measurement.
     */
    def clearPrimaryRateLoad(): ProposedSliceAssignment = {
      asn.copy(primaryRateLoadOpt = None)
    }

    /**
     * REQUIRES: `n` is positive and `slice` can be subdivided into `n` parts. For example,
     * ["a", "a\0") can not be subdivided because there is no key between "a" and "a\0".
     *
     * Creates a [[ProposedAssignmentEntry]] representing the current Slice assignment subdivided
     * into `n` roughly uniform parts.
     */
    def subdivide(n: Int): ProposedAssignmentEntry.Subdivided = {
      ProposedAssignmentEntry.Subdivided(asn, n)
    }

    /**
     * Shorthand for `sliceAssignment.withPrimaryRateLoad(primaryLoadPerSlice * n).subdivide(n)`.
     */
    def subdivide(
        n: Int,
        primaryRateLoadPerSlice: Double): TestSliceUtils.ProposedAssignmentEntry.Subdivided = {
      withPrimaryRateLoad(primaryRateLoadPerSlice * n).subdivide(n)
    }
  }

  /**
   * REQUIRES: `n` is positive and `slice` can be subdivided into `n` parts. For example,
   * ["a", "a\0") can not be subdivided because there is no key between "a" and "a\0".
   *
   * Subdivides `slice` into `n` parts that are roughly equal weight per the assumptions of a
   * uniform [[LoadMap]].
   */
  private def subdivideSlice(n: Int, slice: Slice): Seq[Slice] = {
    // Implementation uses `LoadMap`'s apportioning support to break up the Slice into ~uniformly
    // loaded subslices, assuming uniform load in the requested Slice.
    val loadMap: LoadMap = LoadMap.newBuilder().putLoad(LoadMap.Entry(slice, 1.0)).build()
    val subdivisions = Seq.newBuilder[Slice]
    var previousHigh: SliceKey = slice.lowInclusive
    for (_ <- 0 until n - 1) {
      val split: LoadMap.Split = loadMap.getSplit(Slice.atLeast(previousHigh), 1.0 / n)
      require(
        split.splitKey.isFinite && split.splitKey > previousHigh,
        s"subdividing failed: previousHigh=$previousHigh, split=$split"
      )
      subdivisions += Slice(previousHigh, split.splitKey)
      previousHigh = split.splitKey.asFinite
    }
    // Last subdivision is the remainder.
    require(previousHigh < slice.highExclusive, "subdividing failed")
    subdivisions += Slice(previousHigh, slice.highExclusive)
    subdivisions.result()
  }

  /**
   * An entry used to build a [[SliceMap[ProposedSliceAssignment]] in [[createProposal()]]. An
   * entry may represent either a single [[ProposedSliceAssignment]] or a subdivision of same.
   */
  sealed trait ProposedAssignmentEntry {

    /** Converts this entry to a sequence of [[ProposedSliceAssignment]] instances. */
    def toSliceAssignments: Seq[ProposedSliceAssignment]
  }

  object ProposedAssignmentEntry {

    /** Represents a single [[ProposedSliceAssignment]]. */
    case class Single(sliceAssignment: ProposedSliceAssignment) extends ProposedAssignmentEntry {
      override def toSliceAssignments: Seq[ProposedSliceAssignment] =
        Seq(sliceAssignment)
    }

    /**
     * Represents a proposed Slice assignment that should be subdivided into multiple ~uniform
     * subslices in the assignment proposal. Useful for tests that need to generate test assignments
     * with a large number of Slices (e.g., in order to trigger merges, which occur only after each
     * resource in an assignment has more than 64 Slices, too many to easily read in a test case).
     */
    case class Subdivided(sliceAssignment: ProposedSliceAssignment, n: Int)
        extends ProposedAssignmentEntry {
      require(n >= 1)
      override def toSliceAssignments: Seq[ProposedSliceAssignment] = {
        val subdivisions: Seq[Slice] = subdivideSlice(n, sliceAssignment.slice)
        val loadPerSliceOpt: Option[Double] = sliceAssignment.primaryRateLoadOpt.map {
          totalLoad: Double =>
            totalLoad / n
        }
        subdivisions.map { subdivision: Slice =>
          ProposedSliceAssignment(
            subdivision,
            sliceAssignment.resources,
            loadPerSliceOpt
          )
        }
      }
    }
  }

  /**
   * Implicitly converts a single [[ProposedSliceAssignment]] to a
   * [[ProposedAssignmentEntry]].
   */
  implicit def toProposedAssignmentEntry(
      sliceAssignment: ProposedSliceAssignment): ProposedAssignmentEntry = {
    ProposedAssignmentEntry.Single(sliceAssignment)
  }

  /** Parses a [[SimpleDiffAssignmentP]] from the test data. */
  def parseSimpleDiffAssignment(proto: SimpleDiffAssignmentP): DiffAssignment = {
    val sliceMapEntries: Vector[SliceAssignment] =
      proto.sliceAssignments.map(parseSimpleSliceAssignment).toVector
    DiffAssignment(
      isFrozen = false,
      consistencyMode =
        if (proto.getIsConsistent) AssignmentConsistencyMode.Strong
        else AssignmentConsistencyMode.Affinity,
      generation = Generation.fromProto(proto.getGeneration),
      sliceMap = proto.partialDiffGeneration match {
        case Some(partialDiffGeneration) =>
          DiffAssignmentSliceMap.Partial(
            Generation.fromProto(partialDiffGeneration),
            SliceMap.createFromOrderedDisjointEntries(
              sliceMapEntries,
              SliceMapHelper.SLICE_ASSIGNMENT_ACCESSOR
            )
          )
        case None =>
          DiffAssignmentSliceMap.Full(
            SliceMapHelper.ofSliceAssignments(sliceMapEntries)
          )
      }
    )
  }

  /** Parses a [[SimpleSliceAssignmentP]] into a [[SliceAssignment]]. */
  def parseSimpleSliceAssignment(proto: SimpleSliceAssignmentP): SliceAssignment = {
    val slice: Slice = SliceHelper.fromProto(proto.getSlice)
    val generation = Generation.fromProto(proto.getGeneration)
    val subsliceAnnotationsByResource: Map[Squid, Vector[SubsliceAnnotation]] =
      proto.subsliceAnnotations
        .groupBy(_.getResource)
        .map {
          case (resource: String, annotations: Seq[SubsliceAnnotationP]) =>
            createTestSquid(resource) -> annotations.map { annotation: SubsliceAnnotationP =>
              SubsliceAnnotation(
                SliceHelper.fromProto(annotation.getSlice),
                annotation.getGenerationNumber,
                stateTransferOpt = annotation.stateTransfer.map { transfer: TransferP =>
                  Transfer(
                    transfer.getId,
                    createTestSquid(transfer.getFromResource)
                  )
                }
              )
            }.toVector
        }
    (slice @@ generation) -> proto.resources | subsliceAnnotationsByResource
  }

  /** Blocks until `clerk` has the `expected` assignment. */
  def awaitAssignment(clerk: Clerk[ResourceAddress], expected: Assignment): Unit = {
    awaitAssignment(clerk.impl, expected)
  }

  /** Blocks until `clerk` has the `expected` assignment. */
  def awaitAssignment(clerk: ClerkImpl[ResourceAddress], expected: Assignment): Unit = {
    AssertionWaiter("Await the expected assignment on Clerk").await {
      val assignmentOpt: Option[Assignment] = clerk.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.contains(expected))
    }
  }

  /** Blocks until `slicelet` has the `expected` assignment. */
  def awaitAssignment(slicelet: Slicelet, expected: Assignment): Unit = {
    AssertionWaiter("Await the expected assignment on Slicelet").await {
      val assignmentOpt: Option[Assignment] =
        slicelet.impl.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.contains(expected))
    }
  }

  /** Blocks until `sliceletImpl` has the `expected` assignment. */
  def awaitAssignment(sliceletImpl: SliceletImpl, expected: Assignment): Unit = {
    AssertionWaiter("Await the expected assignment on Slicelet").await {
      val assignmentOpt: Option[Assignment] =
        sliceletImpl.forTest.getLatestAssignmentOpt
      assert(assignmentOpt.contains(expected))
    }
  }

  /** Blocks until `testEnv`'s assigner has the `expected` assignment for the given `target`. */
  def awaitAssignment(
      testEnv: InternalDicerTestEnvironment,
      target: Target,
      expected: Assignment): Unit = {
    AssertionWaiter("Await the expected assignment on Assigner").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.contains(expected))
    }
  }

  /** Blocks until `testEnv`'s assigner has an assignment for `target` with `resources`. */
  def awaitAssignmentWithResources(
      testEnv: InternalDicerTestEnvironment,
      target: Target,
      resources: Set[Squid]): Assignment = {
    AssertionWaiter(s"Await assignment with $resources").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      assert(assignment.assignedResources == resources)
      assignment
    }
  }

  /** Converts a SliceKeyP to a SliceKey, with the appropriate slice key function applied. */
  def sliceKeyFromProto(sliceKeyP: SliceKeyP): SliceKey = {
    val bytes: Array[Byte] = sliceKeyP.applicationKey.map((_: Int).toByte).toArray
    sliceKeyP.getSliceKeyFunction match {
      case SliceKeyFunctionP.IDENTITY =>
        SliceKey.fromRawBytes(ByteString.copyFrom(bytes))
      case SliceKeyFunctionP.FARM_HASH_FINGERPRINT_64 =>
        SliceKey.newFingerprintBuilder().putBytes(bytes).build()
      case SliceKeyFunctionP.SLICE_KEY_FUNCTION_P_UNSPECIFIED =>
        throw new IllegalArgumentException("Function unspecified.")
    }
  }

  /** Converts a HighSliceKeyP to a HighSliceKey. */
  def highSliceKeyFromProto(highSliceKeyP: HighSliceKeyP): HighSliceKey = {
    highSliceKeyP.key match {
      case HighSliceKeyP.Key.SliceKey(sliceKeyP: SliceKeyP) =>
        identityKey(sliceKeyP.applicationKey: _*)
      case HighSliceKeyP.Key.InfinitySliceKey(_: InfinitySliceKeyP) =>
        InfinitySliceKey
      case HighSliceKeyP.Key.Empty =>
        throw new IllegalArgumentException("HighSliceKeyP has no key set")
    }
  }
}
