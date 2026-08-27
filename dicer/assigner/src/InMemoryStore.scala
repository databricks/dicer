package com.databricks.dicer.assigner

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

import com.databricks.caching.util.CachingErrorCode.STORE_INCARNATION_ASSIGNMENT_MISMATCH
import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  ValueStreamCallback
}
import com.databricks.dicer.common.Assignment.AssignmentValueCell
import com.databricks.dicer.common.{
  AssignmentConsistencyMode,
  Assignment,
  Generation,
  Incarnation,
  ProposedAssignment
}
import com.databricks.dicer.external.Target
import com.databricks.dicer.common.TargetHelper.TargetOps
import java.time.Instant

import com.databricks.caching.util.AssertMacros.iassert

/**
 * REQUIRES: `storeIncarnation` is loose.
 *
 * In-memory store that maintains known assignments and supports "writing" assignments (where the
 * store assigns generations with [[Incarnation.isLoose]] incarnations). These assignments are
 * ephemeral, and an assignment may be lost forever if the Assigner hosting the in-memory store
 * crashes before distributing it to Clerks and Slicelets, or if all Clerks and Slicelets that are
 * aware of the assignment crash before syncing it with the Assigner. The Assigner learns about
 * assignments written by other Assigner processes via a sync protocol, and those assignments are
 * passed to [[informAssignment]] for this store.
 *
 * The store operates entirely within the scope of the configured `storeIncarnation`. That is, it
 * is only capable of choosing generations in this store incarnation, and is only capable of caching
 * assignments that it learns about from this store incarnation.
 *
 * TODO(<internal bug>): Support caching assignments from future store incarnations to make store
 * incarnation changes less disruptive.
 */
class InMemoryStore private (sec: SequentialExecutionContext, val storeIncarnation: Incarnation)
    extends Store {
  require(storeIncarnation.isLoose, "Store incarnation must be loose.")

  import Store.WriteAssignmentResult

  // All private methods must be called from `sec`, which protects all internal state for the store.
  // To satisfy this requirement, we immediately hop onto the `sec` from each public entry point.

  /** All requests are routed to the [[TargetState]] object for the relevant target. */
  private val targets = mutable.Map[Target, TargetState]()

  override def writeAssignment(
      target: Target,
      shouldFreeze: Boolean,
      proposal: ProposedAssignment): Future[WriteAssignmentResult] = sec.call {
    getState(target).writeAssignment(shouldFreeze, proposal)
  }

  override def watchAssignments(
      target: Target,
      callback: ValueStreamCallback[Assignment]): Cancellable = sec.callCancellable {
    getState(target).watchAssignments(callback)
  }

  override def informAssignment(target: Target, assignment: Assignment): Unit =
    sec.run {
      getState(target).informAssignment(assignment)
    }

  override def getLatestKnownAssignment(target: Target): Future[Option[Assignment]] =
    sec.call {
      targets.get(target).flatMap { state: TargetState =>
        state.getLatestAssignment
      }
    }

  /**
   * REQUIRES: `previousGeneration` is empty or has the same store incarnation as the store.
   *
   * Chooses the generation for an assignment to be written to the store, assuming that its
   * predecessor had `previousGeneration`, which should be empty when there is no predecessor, as
   * when generating the first assignment for a target. When choosing a generation without a
   * predecessor, the returned generation has a loose incarnation. When choosing a generation with a
   * predecessor, the returned generation has the same incarnation, but greater generation number.
   *
   * The generation number tracks milliseconds since the Unix epoch as indicated by the given
   * `now` time.
   */
  private def chooseNextGeneration(previousGeneration: Generation, now: Instant): Generation = {
    if (previousGeneration != Generation.EMPTY) {
      iassert(
        previousGeneration.incarnation == storeIncarnation,
        s"Unable to overwrite assignment whose generation is not in the store incarnation of the " +
        s"store. storeIncarnation: $storeIncarnation, previousGeneration store incarnation: " +
        s"${previousGeneration.incarnation}"
      )
    }

    Generation.createForCurrentTime(storeIncarnation, now, previousGeneration)
  }

  /**
   * Gets the state associated with the given target, creating a new [[TargetState]] object if none
   * exists.
   */
  private def getState(target: Target): TargetState = {
    sec.assertCurrentContext()
    targets.getOrElseUpdate(target, new TargetState(target))
  }

  /**
   * State maintained for each `target` encountered by the in-memory store.
   *
   * Not thread-safe. All calls must be on `sec`.
   */
  private class TargetState(target: Target) {
    private val logger =
      PrefixLogger.create(
        getClass,
        s"target: ${target.getLoggerPrefix}, store incarnation: $storeIncarnation"
      )
    private val cell = new AssignmentValueCell

    /** Implements [[Store.writeAssignment()]] for `target`. */
    def writeAssignment(
        shouldFreeze: Boolean,
        proposal: ProposedAssignment): WriteAssignmentResult = {
      sec.assertCurrentContext()
      val predecessorGeneration: Generation = proposal.predecessorGenerationOrEmpty
      val existingAssignmentOpt: Option[Assignment] = getLatestAssignment
      val existingGeneration: Generation = existingAssignmentOpt match {
        case Some(existingAssignment: Assignment) => existingAssignment.generation
        case None => Generation.EMPTY
      }

      if (existingGeneration != predecessorGeneration) {
        logger.info(
          s"Write for assignment based on predecessor $predecessorGeneration failed " +
          s"OCC checks. Current assignment in the store is $existingGeneration."
        )
        WriteAssignmentResult.OccFailure(existingAssignmentGeneration = existingGeneration)
      } else {
        // The OCC check succeeded! Try to choose a generation for the proposed assignment so that
        // it can be committed.
        val generation: Generation =
          chooseNextGeneration(existingGeneration, now = sec.getClock.instant())
        val assignment: Assignment =
          proposal.commit(
            isFrozen = shouldFreeze,
            AssignmentConsistencyMode.Affinity,
            generation
          )
        // We already log the formatted assignment in the generator, so we don't do it again here
        // because it's expensive and adds unnecessary bulk.
        logger.info(s"Committed assignment $generation")

        // Cache the new assignment and inform all watchers via the assignment cell.
        cell.setValue(assignment)
        WriteAssignmentResult.Committed(assignment)
      }
    }

    /** Implements [[Store.watchAssignments()]] for `target`. */
    def watchAssignments(callback: ValueStreamCallback[Assignment]): Cancellable = {
      sec.assertCurrentContext()
      cell.watch(callback)
    }

    /** Implements [[Store.informAssignment()]] for `target`. */
    def informAssignment(assignment: Assignment): Unit = {
      sec.assertCurrentContext()
      val latestAssignmentOpt: Option[Assignment] = getLatestAssignment
      val latestGeneration: Generation = latestAssignmentOpt match {
        case Some(latestAssignment: Assignment) => latestAssignment.generation
        case None => Generation.EMPTY
      }
      if (storeIncarnation != assignment.generation.incarnation) {
        logger.info(
          s"Ignoring informed assignment since it belongs to another " +
          s"store incarnation ${assignment.generation.incarnation}",
          10.seconds
        )
      } else if (latestGeneration < assignment.generation) {
        assignment.toChunkedString().foreach { chunk: String =>
          logger.info(s"Caching read assignment: $chunk")
        }

        // Cache the new assignment and inform all watchers via the assignment cell.
        cell.setValue(assignment)
      } else {
        logger.debug(
          s"Ignoring informed assignment as we already know it or a newer assignment: " +
          s"$latestGeneration >= ${assignment.generation}",
          every = 10.seconds
        )
      }
    }

    /** Return latest assignment for the [[target]]. */
    def getLatestAssignment: Option[Assignment] = {
      sec.assertCurrentContext()
      val latestAssignmentOpt: Option[Assignment] = cell.getLatestValueOpt
      val latestGeneration: Generation = latestAssignmentOpt match {
        case Some(latestAssignment: Assignment) => latestAssignment.generation
        case None => Generation.EMPTY
      }
      // Either the assignment generation is empty or belongs to the store incarnation of the store.
      logger.assert(
        latestGeneration == Generation.EMPTY ||
        latestGeneration.incarnation == storeIncarnation,
        STORE_INCARNATION_ASSIGNMENT_MISMATCH,
        s"Current assignment generation $latestGeneration does not have " +
        s"the correct store incarnation: $storeIncarnation"
      )
      latestAssignmentOpt
    }
  }
}
object InMemoryStore {

  /** Creates an in-memory store that generates assignments with loose incarnations. */
  def apply(sec: SequentialExecutionContext, storeIncarnation: Incarnation): InMemoryStore = {
    new InMemoryStore(sec, storeIncarnation)
  }

}
