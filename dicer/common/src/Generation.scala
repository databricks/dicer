package com.databricks.dicer.common

import java.time.Instant
import com.databricks.api.proto.dicer.common.GenerationP
import com.databricks.caching.util.UnixTimeVersion

import scala.math.Ordering.Long

/**
 * REQUIRES: `value` is non-negative.
 *
 * Represents the incarnation number of a versioned object (see [[Generation]]).
 *
 * Incarnations form the upper-64 bits of the 128-bit Generation. The storage layer only increases
 * the lower-64 bits of the Generation when creating new versioned objects, which then makes it
 * possible to create greater Generations than ever before by simply increasing the Incarnation.
 *
 * An incarnation number encodes whether or not the set of all versioned objects within
 * that incarnation form a linear history and whether certain application-specific optimizations or
 * strong semantics are supported (see [[isNonLoose]] for definition). Incarnations encoding these
 * strong semantics for their versioned objects are called "non-loose" incarnations, and otherwise
 * are called "loose" incarnations.
 *
 * Non-durable stores which are unable to reliably track object incarnations should choose a fixed
 * number (and odd, to form a "loose" incarnation, see [[isNonLoose]]) for all chosen object
 * incarnations. Durable stores, on the other hand, are expected (but not required) to utilize
 * non-loose incarnations and provide linear histories of their versioned objects.
 */
case class Incarnation private (value: Long) extends AnyVal with Ordered[Incarnation] {

  override def compare(that: Incarnation): Int = Long.compare(this.value, that.value)

  /**
   * There are two types of Incarnations, called "loose" and "non-loose". When "loose" and
   * "non-loose" are used in the context of generations, the terms mean that the generation's
   * incarnation is loose or non-loose.
   *
   * Non-loose generations are used by durable stores and guarantee linear (non-divergent) histories
   * of their versioned objects. That is, if an object has generations g1 and g2, g1.incarnation ==
   * g2.incarnation, and g1 < g2, then the object at generation g2 is guaranteed to have been
   * created causally after the object at generation g1. Non-loose generations are also used by
   * individual stores to express or implement support for certain optimizations or even additional
   * semantic guarantees on their versioned objects which are application specific. For example,
   * assignments in non-loose incarnations support diffs in order to efficiently sync assignment
   * state among system components, and additionally make strong guarantees about continuous
   * assignment of slices. See [[SliceAssignment]] for details.
   *
   *   Note: Since <internal link>, linear histories are
   *   technically guaranteed as long as g1.incarnation.storeIncarnation ==
   *   g2.incarnation.storeIncarnation, however to maintain backwards compatibility with existing
   *   client code, the definitions of loose and non-loose on [[Incarnation]]s as a whole were
   *   preserved. If needed, the concepts of loose and non-loose could be changed to apply
   *   specifically to the store incarnation, however since incarnations still represent distinct
   *   lifetimes of a versioned object in the store (and therefore object specific "non-loose"
   *   features like assignment diffing and continuous assignment guarantees do not apply across
   *   incarnations), there exists no present need to change this.
   *
   * Loose generations are used by non-durable stores and do not have the above guarantee. That is,
   * for a given object, the set of all generations within the same loose incarnation may form a
   * divergent history. For example, an object in a loose incarnation may have generation numbers
   * 8, 9, and 10, but 10 may have been generated causally after 8 but in parallel with 9, and so
   * not be aware of 9's existence. There may even be multiple versions with generation number 9. In
   * general, anything is possible.
   *
   * "Non-loose" is encoded as a positive even value. Otherwise, the incarnation is "loose". This
   * interleaving of loose and non-loose incarnations allows us to always be able to move "ahead" in
   * the versioning space to either loose or non-loose incarnations as needed (e.g. if a durable
   * store went kaput and we needed to move ahead to a non-durable store in an emergency).
   */
  def isNonLoose: Boolean = value != 0 && (value & 1) == 0

  /** See [[isNonLoose]]. */
  def isLoose: Boolean = !isNonLoose

  /** Returns the next loose Incarnation. */
  def getNextLooseIncarnation: Incarnation = getNextIncarnation(loose = true)

  /** Returns the next non-loose Incarnation. */
  def getNextNonLooseIncarnation: Incarnation = getNextIncarnation(loose = false)

  override def toString: String = s"$value"

  /** Returns the next loose Incarnation if `loose` otherwise the next non-loose incarnation. */
  private def getNextIncarnation(loose: Boolean): Incarnation = {
    // Though this is potentially awkward we do a while loop here so that if the definition of loose
    // changes that won't affect correctness. With the current definition loose and non-loose
    // (roughly) alternate so it won't run for more than 2 iterations. Due to the
    // UPPER_BOUND_FAT_FINGER_GUARDRAIL it will also not infinitely loop.
    var nextIncarnation: Incarnation = Incarnation(value + 1)
    while (nextIncarnation.isLoose != loose) {
      nextIncarnation = Incarnation(nextIncarnation.value + 1)
    }
    nextIncarnation
  }
}

object Incarnation {

  /** The minimum, legal [[Incarnation]]. This is a loose [[Incarnation]]. */
  val MIN: Incarnation = Incarnation(0)

  /**
   * REQUIRES: `value` is non-negative.
   * REQUIRES: `value` is less than 5L << 48.
   *
   * Returns a new instance from `value`.
   */
  def apply(value: Long): Incarnation = {
    require(
      value < UPPER_BOUND_FAT_FINGER_GUARDRAIL,
      s"Value is too large ($value >= (5 << 48)). Was this intentional?"
    )
    require(value >= 0, "Incarnation number must be non-negative")
    new Incarnation(value)
  }

  /**
   * An upper bound that prevents us from accidentally exhausting the Incarnation space, as the
   * incarnation value can never go backwards. The value itself does not represent any sort of
   * logical requirement of the system.
   *
   * The reason for this bound being so large is that `store incarnations` originally
   * formed the upper 16 bits of the whole 64 bit incarnation, while `entity incarnations` formed
   * the lower 48 bits. Some mt-shards had a value of 4 for their store incarnation while using
   * InMemoryStore, so to ensure that we produce larger generations than those, we must use an
   * Incarnation at least as large as was ever used in those shards, which is 4 << 48 + 1.
   *
   * Note that this must be a function rather than a val; otherwise, it would have to be placed
   * above `MIN` since apply() depends on this value being initialized.
   */
  private def UPPER_BOUND_FAT_FINGER_GUARDRAIL: Long = 5L << 48

  object forTest {

    /** Returns the UPPER_BOUND_FAT_FINGER_GUARDRAIL to use in tests. */
    def getUpperBoundFatFingerGuardrail: Long = UPPER_BOUND_FAT_FINGER_GUARDRAIL
  }
}

/**
 * REQUIRES: number is non-negative
 *
 * The generation is used as a version number. It has two parts to it - an [[Incarnation]] and a
 * generation number. One should think of this as a 128-bit integer with the incarnation number
 * comprising the most significant bits. The incarnation number is present to allow generations to
 * be monotonically increasing across multiple incarnations of the versioned object within and
 * across storage layers (see [[Incarnation]]).
 *
 * In general a (durable) store can simply achieve monotonicity using a simple per-object 64-bit
 * generation number that is advanced on every update in the store (expected to track time in
 * milliseconds since the Unix epoch to serve as a debugging aid, subject to monotonicity
 * constraints). To maintain monotonicity in the case that objects can potentially be deleted and
 * re-created (e.g. due to garbage collecting unused objects), the store can additionally maintain a
 * high watermark for used versions and require that writes for new keys propose versions greater
 * than this watermark.
 *
 * No matter how a particular storage layer decides to achieve monotonicity for generations, to
 * maintain monotonicity across whole storage layer changes (e.g. due to loss or corruption), the
 * store incarnation must be bumped (see [[Incarnation]]), making all future generations chosen by
 * the Assigner to be strictly greater than all previous generations. Bumping the store incarnation
 * is expected to be an extremely rare occurrence.
 *
 * Note that the above discussion applies to durable stores which are able to guarantee
 * monotonicity. Non-durable stores in general do not have such versioning guarantees, and
 * monotonicity is best-effort. Whether guaranteed monotonicity is supported (and perhaps other
 * strong semantics that are specific to the type of versioned object) is encoded in the
 * [[Incarnation]]. See [[Incarnation]] for details.
 *
 * @param incarnation the incarnation number corresponding to this generation
 * @param number the generation number corresponding to this generation
 */
case class Generation(incarnation: Incarnation, number: UnixTimeVersion)
    extends Ordered[Generation] {
  require(number >= 0, s"Unix time version must be non-negative")

  /** Returns the proto corresponding this abstraction. */
  def toProto: GenerationP = {
    new GenerationP(incarnation = Some(incarnation.value), number = Some(number.value))
  }

  override def toString: String = s"$incarnation#$number"

  /**
   * Returns the value of the generation number as an [[Instant]], assuming that the generation
   * number tracks the number of millis since the Unix epoch.
   */
  def toTime: Instant = number.toTime

  override def compare(that: Generation): Int = {
    val incarnationCmp = this.incarnation.compare(that.incarnation)
    if (incarnationCmp != 0) {
      incarnationCmp
    } else {
      this.number.compare(that.number)
    }
  }
}

object Generation {

  /**
   * The "zero" generation that is used to represent "no knowledge". Not a valid generation for an
   * assignment/redirect. Sorts before any other generation.
   */
  val EMPTY: Generation = Generation(Incarnation.MIN, UnixTimeVersion.MIN)

  /**
   * Returns a [[Generation]] corresponding to `proto`.
   *
   * @throws IllegalArgumentException if the `proto` is not valid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: GenerationP): Generation = {
    Generation(
      incarnation = Incarnation(proto.getIncarnation),
      number = proto.getNumber
    )
  }

  /**
   * REQUIRES: `storeIncarnation` <= lowerBoundExclusive.incarnation.
   *
   * Returns a [[Generation]] in `incarnation` which is strictly greater than `lowerBoundExclusive`.
   *
   * The returned value also attempts to track the current time `now` in milliseconds with its
   * generation number, but will deviate from the current time if the resulting value would not be
   * greater than `lowerBoundExclusive`.
   */
  def createForCurrentTime(
      incarnation: Incarnation,
      now: Instant,
      lowerBoundExclusive: Generation): Generation = {
    require(
      lowerBoundExclusive.incarnation <= incarnation,
      f"Impossible due to store incarnation: ${lowerBoundExclusive.incarnation} > $incarnation"
    )

    val nowMillis: Long = now.toEpochMilli
    val genForCurrentTime = Generation(incarnation, nowMillis)
    if (genForCurrentTime > lowerBoundExclusive) {
      genForCurrentTime
    } else {
      // Using the current time would cause the generation to fall below the specified lower bound.
      // To track time as closely as possible while still respecting the lower bound, return
      // the next larger value.

      // This doesn't handle the case that we overflow the lower 64 bits. Should consider whether
      // we should.
      Generation(
        incarnation,
        lowerBoundExclusive.number.value + 1
      )
    }
  }
}
