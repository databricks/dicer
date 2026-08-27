package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext

/** Factory for a [[DicerSimulatorEventEmitter]]. */
object DicerSimulatorEventLogEmitter {

  /**
   * Returns the no-op emitter, since the DicerSimulatorEvent emitter is not yet supported in open
   * source Dicer.
   */
  def create(loggingSec: SequentialExecutionContext): DicerSimulatorEventEmitter = {
    DicerSimulatorEventEmitter.getNoopEmitter
  }
}
