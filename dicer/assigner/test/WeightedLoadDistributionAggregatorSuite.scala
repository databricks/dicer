package com.databricks.dicer.assigner

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.Random

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.assigner.WeightedLoadDistributionAggregatorSuite.{
  ERROR_FRACTION,
  HALF_LIFE,
  SLICE
}
import com.databricks.dicer.common.SliceKeyHelper.RichSliceKey
import com.databricks.dicer.common.SliceletData.LoadDistribution
import com.databricks.dicer.common.SliceletData.LoadDistribution.CdfPoint
import com.databricks.dicer.common.testing.SliceTestUtils._
import com.databricks.dicer.external.{HighSliceKey, InfinitySliceKey, Slice, SliceKey}
import com.databricks.testing.DatabricksTest

class WeightedLoadDistributionAggregatorSuite extends DatabricksTest {

  /**
   * Converts a `(String, Double)` tuple to `(SliceKey, Double)`, allowing tests to use
   * `"key" -> fraction` syntax where a CDF point is expected.
   */
  private implicit def toKeyFraction(keyFraction: (String, Double)): (SliceKey, Double) = {
    val (key, fraction): (String, Double) = keyFraction
    (identityKey(key), fraction)
  }

  /** Creates a distribution from `(key, fraction)` pairs. */
  private def createDistribution(points: (SliceKey, Double)*): LoadDistribution = {
    LoadDistribution(
      points = points.map { pair: (SliceKey, Double) =>
        val (key, fraction): (SliceKey, Double) = pair
        CdfPoint(key, fraction)
      },
      maxErrorFraction = ERROR_FRACTION
    )
  }

  /** Returns `distribution`'s cumulative fraction at `key`, requiring a point exactly at `key`. */
  private def findFractionForKey(distribution: LoadDistribution, key: SliceKey): Double = {
    val pointOpt: Option[CdfPoint] =
      distribution.points.find((point: CdfPoint) => point.key.compare(key) == 0)
    assert(pointOpt.isDefined, s"expected a point at $key, got ${distribution.points}")
    pointOpt.get.cumulativeLoadFraction
  }

  /** Asserts two fractions are equal within a small floating point tolerance. */
  private def assertFractionEquals(expected: Double, actual: Double): Unit = {
    assert(
      Math.abs(expected - actual) < 1e-9,
      s"expected fraction $expected but got $actual"
    )
  }

  test("addDistribution rejects an invalid load") {
    // Test plan: Verify that addDistribution rejects a negative load.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    assertThrow[IllegalArgumentException]("must be non-negative") {
      aggregator.addDistribution(
        age = 0.seconds,
        load = -1.0,
        createDistribution("m" -> 1.0)
      )
    }
  }

  test("addDistribution rejects a negative age") {
    // Test plan: Verify that addDistribution rejects a negative age.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    assertThrow[IllegalArgumentException]("age must be non-negative") {
      aggregator.addDistribution(
        age = (-1).seconds,
        load = 10.0,
        createDistribution("m" -> 1.0)
      )
    }
  }

  test("addDistribution rejects points outside the Slice") {
    // Test plan: Verify that addDistribution rejects distributions with points outside the Slice.
    // We test a series of keys that are outside the slice (below, on the high bound, and above).
    for (key: String <- Seq("a", "z", "zz")) {
      val aggregator = new WeightedLoadDistributionAggregator("b" -- "z", HALF_LIFE)
      val distribution: LoadDistribution = if (key < "b") {
        createDistribution(key -> 0.5, "m" -> 1.0)
      } else {
        createDistribution("m" -> 0.5, key -> 1.0)
      }
      assertThrow[IllegalArgumentException]("distribution points must be contained in slice") {
        aggregator.addDistribution(
          age = 0.seconds,
          load = 10.0,
          distribution
        )
      }
    }
  }

  test("compute returns None when no distributions were added") {
    // Test plan: Verify that compute() returns None when no distributions have been added.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    assert(aggregator.compute().isEmpty)
  }

  test("compute returns a single input verbatim") {
    // Test plan: Verify that compute() returns a lone distribution unchanged (same points and
    // error fraction).
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    val soleDistribution: LoadDistribution =
      createDistribution("f" -> 0.5, "m" -> 1.0)
    aggregator.addDistribution(age = 0.seconds, load = 42.0, soleDistribution)
    assert(aggregator.compute().contains(soleDistribution))
  }

  test("compute reflects distributions added after a prior compute") {
    // Test plan: Verify that compute() reflects all inputs added so far, including distributions
    // added before a previous compute() call.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 1.0))
    assertFractionEquals(1.0, findFractionForKey(aggregator.compute().get, "m"))

    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 0.4))
    assertFractionEquals(0.7, findFractionForKey(aggregator.compute().get, "m"))
  }

  test("compute averages two equally-weighted inputs at a shared key") {
    // Test plan: Verify that two inputs of equal load/age that both sample key "m" produce an
    // aggregate fraction at "m" equal to the plain average of their fractions.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 1.0))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 0.4))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.7, findFractionForKey(result, "m"))
  }

  test("compute weights inputs by load") {
    // Test plan: Verify that the aggregate is pulled proportionally toward the heavier input's
    // fraction. Use two inputs sampling "m" with different loads, one far heavier than the other
    // (0.9*0.2 + 0.1*1.0 = 0.28).
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 90.0, createDistribution("m" -> 0.2))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 1.0))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.28, findFractionForKey(result, "m"))
  }

  test("compute decays older inputs by age") {
    // Test plan: Verify that the aggregate reflects age-decayed weights. Use two equal-load inputs
    // sampling "m" of unequal age, one aged a full half-life (weight halved). The fresh input's
    // weight is 1.0 and the aged input's is 0.5, so normalized weights are 2/3 and 1/3.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 0.9))
    aggregator.addDistribution(age = HALF_LIFE, load = 10.0, createDistribution("m" -> 0.3))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.7, findFractionForKey(result, "m"))
  }

  test("compute falls back to equal weights when all loads are zero") {
    // Test plan: Verify that the aggregator falls back to an unweighted average when all inputs
    // have zero load.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 0.0, createDistribution("m" -> 0.2))
    aggregator.addDistribution(age = HALF_LIFE, load = 0.0, createDistribution("m" -> 0.8))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.5, findFractionForKey(result, "m"))
  }

  test("the aggregate tops out at 1.0 regardless of the input loads' magnitude") {
    // Test plan: Verify that the absolute magnitude of the input loads does not scale the
    // aggregate; it is a CDF and so must top out at 1.0, not at the loads' sum. Feed two inputs
    // that both reach 1.0 at the same key "m" with positive loads, and verify the aggregate at "m"
    // remains exactly 1.0.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 5e15, createDistribution("m" -> 1.0))
    aggregator.addDistribution(age = 0.seconds, load = 3e15, createDistribution("m" -> 1.0))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(1.0, findFractionForKey(result, "m"))
  }

  test("compute carries the maximum error fraction across inputs") {
    // Test plan: Verify that the aggregate error is reported as the max of the inputs' error
    // fractions.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      LoadDistribution(IndexedSeq(CdfPoint("m", 1.0)), maxErrorFraction = 0.05)
    )
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      LoadDistribution(IndexedSeq(CdfPoint("t", 1.0)), maxErrorFraction = 0.2)
    )
    assertFractionEquals(0.2, aggregator.compute().get.maxErrorFraction)
  }

  test("compute's output keys are the sorted deduplicated union of inputs' keys") {
    // Test plan: Verify that the output point keys are exactly the deduplicated union of the
    // inputs' keys, sorted ascending.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution("m" -> 0.3, "t" -> 0.6)
    )
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution("f" -> 0.5, "m" -> 0.5)
    )
    val outputKeys: Seq[SliceKey] = aggregator.compute().get.points.map((p: CdfPoint) => p.key)
    assert(outputKeys == Seq[SliceKey]("f", "m", "t"))
  }

  test("an input's fraction is interpolated between two of its points") {
    // Test plan: Verify that fractions are interpolated between an input's sampled points.
    // Input A samples "a" -> 0.2 and "e" -> 0.6; input B samples "c" -> 0.4. Since "c" is halfway
    // between "a" and "e", A should interpolate to 0.4 there. Verify the equal-weight aggregate
    // at "c" is 0.4, which establishes that A contributed the expected fraction.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution("a" -> 0.2, "e" -> 0.6)
    )
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution("c" -> 0.4)
    )
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.4, findFractionForKey(result, "c"))
  }

  test("an empty distribution contributes uniform density") {
    // Test plan: Verify that an empty distribution contributes uniform density, i.e. its CDF
    // contributions at all the union keys are linearly interpolated between 0 and 1. Given an empty
    // input A (no points) and an input B sampling "2", verify A contributes uniform density: an
    // empty distribution ramps uniformly 0 -> 1.0 across the Slice, so at "2" (0x32 = 50, exactly
    // halfway to "d") A's value is 0.5. B samples "2"->0.3, so the equal-weight aggregate at "2" is
    // 0.5*0.5 + 0.5*0.3 = 0.4.
    val slice: Slice = "" -- "d"
    val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution())
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("2" -> 0.3))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.5 * 0.5 + 0.5 * 0.3, findFractionForKey(result, "2"))
  }

  test("compute correctly handles fractions at the Slice low bound") {
    // Test plan: Verify that aggregation correctly handles fractions at the Slice low bound.
    // Input A reports "" -> 0.6; input B only samples "m" so it should interpolate "" as zero.
    // With equal load/age, the aggregate fraction at "" must be 0.5*0.6 + 0.5*0.0 = 0.3.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("" -> 0.6))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 1.0))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.3, findFractionForKey(result, ""))
  }

  test("an input ramps from 0 below its first point") {
    // Test plan: Verify that interpolation correctly ramps from 0 below an input's first point.
    // Given an input A whose only point is "d"->1.0, and an input B contributing union key "2"
    // below it, verify A ramps from 0 at the Slice low bound "" up to 1.0 at "d". "2" (0x32 = 50)
    // sits exactly halfway from "" to "d" (0x64 = 100), giving A a fraction of 0.5 there. B samples
    // "2" -> 0.3, so the equal-weight aggregate at "2" is 0.5*0.5 + 0.5*0.3 = 0.4.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("d" -> 1.0))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("2" -> 0.3))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.5 * 0.5 + 0.5 * 0.3, findFractionForKey(result, "2"))
  }

  test("interpolation below a sample with fraction 0.0 remains at 0.0") {
    // Test plan: Verify that an input whose first sampled fraction is 0.0 contributes 0.0
    // at lower keys. Input A samples "m" -> 0.0 and input B samples "f" -> 0.6. With equal
    // load/age, the aggregate at "f" must be 0.5*0.0 + 0.5*0.6 = 0.3.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("m" -> 0.0))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("f" -> 0.6))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.3, findFractionForKey(result, "f"))
  }

  test("an input ramps toward 1.0 above its last point") {
    // Test plan: Verify that interpolation correctly ramps toward 1.0 above an input's last point.
    // Given an input A whose last point is "2"->0.6 and an input B contributing union key "K" above
    // it, verify A ramps from 0.6 at "2" (0x32 = 50) up toward 1.0 at the high bound "d" (100). "K"
    // (0x4b = 75) sits exactly halfway in [50, 100), so A's fraction there is 0.6 + 0.5*(1.0 - 0.6)
    // = 0.8. B samples "K"->0.9, so the equal-weight aggregate at "K" is 0.5*0.8 + 0.5*0.9 = 0.85.
    val slice: Slice = "" -- "d"
    val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("2" -> 0.6))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("K" -> 0.9))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.5 * 0.8 + 0.5 * 0.9, findFractionForKey(result, "K"))
  }

  test("interpolation above a sample with fraction 1.0 remains at 1.0") {
    // Test plan: Verify that an input whose last sampled fraction is 1.0 still contributes 1.0
    // at higher keys. Input A samples "f" -> 1.0 and input B samples "t" -> 0.4. With equal
    // load/age, the aggregate at "t" must be 0.5*1.0 + 0.5*0.4 = 0.7.
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("f" -> 1.0))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("t" -> 0.4))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.7, findFractionForKey(result, "t"))
  }

  test("the largest key of a finite Slice is interpolated to exactly 1.0") {
    // Test plan: Verify that for a finite Slice ["", k.successor()), interpolation at k (the
    // largest possible key in the Slice) reaches exactly 1.0. Input A samples only "f" -> 0.4;
    // input B samples k -> 1.0, making k a union key. Verify the equal-weight aggregate at k is
    // exactly 1.0, establishing that A's interpolated fraction at k is also 1.0.
    val topKey: SliceKey = "m"
    val slice: Slice = "" -- topKey.successor()
    val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("f" -> 0.4))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution(topKey -> 1.0))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(1.0, findFractionForKey(result, topKey))
  }

  test("compute handles an unbounded Slice gracefully") {
    // Test plan: Verify that fractions are interpolated correctly beyond an input's last sample in
    // an unbounded Slice.
    // Input A reports "f" -> 0.4 and input B reports "t" -> 1.0, so the equal-weight aggregate
    // at "t" must be strictly between 0.7 and 1.0.
    val slice: Slice = "".andGreater
    val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("f" -> 0.4))
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution("t" -> 1.0))
    val result: LoadDistribution = aggregator.compute().get
    val fractionAtT: Double = findFractionForKey(result, "t")
    assert(fractionAtT > 0.7 && fractionAtT < 1.0)
  }

  test("keys of differing byte lengths interpolate correctly") {
    // Test plan: Verify that computation correctly handles keys of differing byte lengths.
    // For an input A whose gap spans keys of different byte lengths -- the one-byte key
    // [0x40] and the two-byte key [0x40, 0x80] -- and an input B contributing the union key
    // [0x40, 0x40] between them, the CDF should interpolate correctly by comparing keys at a common
    // padded byte length. [0x40, 0x40] is halfway between [0x40, 0x00] and [0x40, 0x80], so A
    // interpolates to 0.4; B samples it at 0.5, giving an aggregate of 0.45.
    val lowKey: SliceKey = identityKey(Array[Byte](0x40))
    val midKey: SliceKey = identityKey(0x40, 0x40)
    val highKey: SliceKey = identityKey(0x40, 0x80)
    val slice: Slice = "" -- "z"
    val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution(lowKey -> 0.2, highKey -> 0.6)
    )
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution(midKey -> 0.5))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.45, findFractionForKey(result, midKey))
  }

  test("interpolation is based strictly on integer distance") {
    // Test plan: Verify that interpolation positions a key by its integer value, not its apparent
    // "rank" in key order. Keys that differ only by trailing zero bytes -- k, k.successor(),
    // k.successor().successor() -- all have the same integer value, so they are zero distance
    // apart, even though k.successor() "looks like" it is between the other two. Given input A
    // sampling k and k.successor().successor(), and input B contributing the middle key
    // k.successor(), verify A's fraction at k.successor() is its lower point k's fraction (0.3),
    // NOT the midpoint of 0.3 and 0.8 that its rank would suggest -- with zero distance between the
    // points, there is nothing to interpolate across. B samples k.successor()->0.5, so the
    // aggregate is 0.5*0.3 + 0.5*0.5.
    val k: SliceKey = "m"
    val kSucc: SliceKey = k.successor()
    val kSuccSucc: SliceKey = k.successor().successor()
    val aggregator = new WeightedLoadDistributionAggregator(SLICE, HALF_LIFE)
    aggregator.addDistribution(
      age = 0.seconds,
      load = 10.0,
      createDistribution(k -> 0.3, kSuccSucc -> 0.8)
    )
    aggregator.addDistribution(age = 0.seconds, load = 10.0, createDistribution(kSucc -> 0.5))
    val result: LoadDistribution = aggregator.compute().get
    assertFractionEquals(0.5 * 0.3 + 0.5 * 0.5, findFractionForKey(result, kSucc))
  }

  test("randomized distribution test") {
    // Test plan: Verify that the aggregator operates correctly across a range of random inputs. The
    // output construction does not throw (this implicitly verifies it is a valid CDF) and the
    // output contains exactly the union of input keys. Mix up a random number of distributions with
    // random loads, ages, and sampled keys over shared random Slice boundaries.

    val seed: Long = Random.nextLong()
    val random = new Random(seed)
    for (iteration: Int <- 0 until 1000) {
      withClue(s"seed=$seed, iteration=$iteration: ") {
        // Select a random Slice. 20% of the time, it is unbounded to the bottom/top, respectively.
        val low: Long = random.nextInt(10000).toLong
        val high: Long = low + 1 + random.nextInt(1000000)
        val lowKey: SliceKey = if (random.nextInt(5) == 0) SliceKey.MIN else toSliceKey(low)
        val highKey: HighSliceKey =
          if (random.nextInt(5) == 0) InfinitySliceKey else toSliceKey(high)
        val slice: Slice = Slice(lowKey, highKey)

        val aggregator = new WeightedLoadDistributionAggregator(slice, HALF_LIFE)

        // 10% of the time, give all distributions zero load (i.e. equal weight).
        val allZeroLoads: Boolean = random.nextInt(10) == 0

        // Create a set of random distributions.
        val distributions: Seq[LoadDistribution] = (0 until random.nextInt(11)).map { _: Int =>
          val keys: Seq[SliceKey] = (0 until random.nextInt(21))
            .map { _: Int =>
              toSliceKey(randomInRange(low, high, random))
            }
            .distinct
            .sorted
          // Select a random fraction in [0, 1] for each key (fractions may repeat).
          val fractions: Seq[Double] = keys.indices.map { _: Int =>
            random.nextInt(101) / 100.0
          }.sorted
          val points: Seq[CdfPoint] = keys.zip(fractions).map {
            case (key: SliceKey, fraction: Double) => CdfPoint(key, fraction)
          }
          val distribution = LoadDistribution(points, maxErrorFraction = random.nextDouble())
          // Pick a random load and age. Outside all-zero mixtures, the load is zero 20% of
          // the time.
          val load: Double =
            if (allZeroLoads || random.nextInt(5) == 0) 0.0 else random.nextDouble() * 10000.0
          val age: FiniteDuration = random.nextInt(100001).milliseconds
          aggregator.addDistribution(age, load, distribution)
          distribution
        }

        // Compute the aggregate distribution. Success confirms the mixture yields a valid CDF.
        val result: Option[LoadDistribution] = aggregator.compute()
        assert(result.isDefined == distributions.nonEmpty)
        // Verify the output contains exactly the union of input keys.
        result.foreach { aggregateDist: LoadDistribution =>
          val expectedKeys: Set[SliceKey] = distributions.flatMap { input: LoadDistribution =>
            input.points.map((point: CdfPoint) => point.key)
          }.toSet
          assert(aggregateDist.points.map((point: CdfPoint) => point.key).toSet == expectedKeys)
          // Verify the output max error fraction is the maximum of the input max error fractions.
          assert(
            aggregateDist.maxErrorFraction == distributions.map { distribution: LoadDistribution =>
              distribution.maxErrorFraction
            }.max
          )
        }
      }
    }
  }

}

private object WeightedLoadDistributionAggregatorSuite {

  /** Half-life used for the age-decay weighting in tests. */
  val HALF_LIFE: FiniteDuration = 10.seconds

  /** A toy error fraction used in tests. Does not affect computation, only reporting */
  val ERROR_FRACTION = 0.1

  /** A finite Slice `["", "z")` used for tests. */
  val SLICE: Slice = "" -- "z"
}
