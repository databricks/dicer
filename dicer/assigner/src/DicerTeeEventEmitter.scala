package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.assigner.AssignmentGenerator.Event
import com.databricks.dicer.external.Target
import com.databricks.rpc.tls.TLSOptions

import java.net.URI

/**
 * A trait for emitting events to the DicerTee, which uses production data to test potentially
 * different new assigner algorithms. See <internal link> for more details.
 */
trait DicerTeeEventEmitter {

  /**
   * Maybe emit an event to the Dicer Tee service, depending on the implementation of the
   * EventEmitter and whether this is an event that is useful to forward to the Tee.
   *
   * @param target The target that the event corresponds to.
   * @param event The state machine [[AssignmentGenerator.Event]] to emit.
   */
  def maybeEmitEvent(target: Target, event: Event): Unit
}

object DicerTeeEventEmitter {

  /** Default RPC timeout in milliseconds. */
  val DEFAULT_TIMEOUT_MS: Long = 1000

  /** Default number of retry attempts (1 = no retries). */
  val DEFAULT_NUM_RETRY_ATTEMPTS: Int = 1

  /**
   * An event emitter that does nothing, used as a placeholder in Assigner when Dicer Tee forwarding
   * is not enabled.
   */
  private object NoopEmitter extends DicerTeeEventEmitter {
    override def maybeEmitEvent(target: Target, event: Event): Unit = { /* Do nothing. */ }
  }

  /** Get the [[NoopEmitter]] singleton. */
  def getNoopEmitter: DicerTeeEventEmitter = {
    NoopEmitter
  }

  /**
   * Create a [[DicerTeeEventEmitterImpl]] instance. See the doc for [[DicerTeeEventEmitterImpl]]
   * for more details.
   */
  def create(
      sec: SequentialExecutionContext,
      dicerTeeURI: URI,
      tlsOptionsOpt: Option[TLSOptions],
      timeoutMs: Long,
      numRetryAttempts: Int): DicerTeeEventEmitter = {
    DicerTeeEventEmitterImpl.create(sec, dicerTeeURI, tlsOptionsOpt, timeoutMs, numRetryAttempts)
  }
}
