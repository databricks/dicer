package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.rpc.tls.TLSOptions

import java.net.URI

/** Factory for a [[DicerTeeEventEmitter]]. */
object DicerTeeEventEmitterImpl {

  /**
   * Returns the no-op event emitter, since Dicer Tee is not available in open source. Parameters
   * are unused, but kept for compatibility with the internal implementation.
   */
  def create(
      sec: SequentialExecutionContext,
      dicerTeeURI: URI,
      tlsOptionsOpt: Option[TLSOptions],
      timeoutMs: Long,
      numRetryAttempts: Int): DicerTeeEventEmitter = {
    DicerTeeEventEmitter.getNoopEmitter
  }
}
