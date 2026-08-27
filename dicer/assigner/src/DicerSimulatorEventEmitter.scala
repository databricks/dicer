package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.assigner.AssignmentGenerator.Event
import com.databricks.dicer.common.SliceKeySensitivity
import com.databricks.dicer.external.Target

/**
 * A trait for emitting [[AssignmentGenerator.Event]]s that can be consumed and replayed by the
 * Dicer Simulator (e.g. by emitting them to Lumberjack).
 */
trait DicerSimulatorEventEmitter {

  /**
   * Emits `event` for replay by the Dicer Simulator if it is a replayable event; otherwise does
   * nothing.
   *
   * @param target the target that the event corresponds to.
   * @param event the event to potentially log.
   * @param sliceKeySensitivity the customer's attestation of whether this target's SliceKeys are
   *                            sensitive.
   */
  def maybeEmitEvent(target: Target, event: Event, sliceKeySensitivity: SliceKeySensitivity): Unit
}

object DicerSimulatorEventEmitter {

  /**
   * An emitter that does nothing, used as a placeholder in Assigner when Dicer Simulator event
   * logging is not enabled.
   */
  private object NoopEmitter extends DicerSimulatorEventEmitter {
    override def maybeEmitEvent(
        target: Target,
        event: Event,
        sliceKeySensitivity: SliceKeySensitivity): Unit = { /* Do nothing. */ }
  }

  /** Get the [[NoopEmitter]] singleton. */
  def getNoopEmitter: DicerSimulatorEventEmitter = {
    NoopEmitter
  }

  /**
   * Creates a [[DicerSimulatorEventEmitter]] that logs every replayable event for Simulator replay.
   *
   * @param loggingSec the sequential execution context for async log submission.
   */
  def create(loggingSec: SequentialExecutionContext): DicerSimulatorEventEmitter = {
    DicerSimulatorEventLogEmitter.create(loggingSec)
  }
}
