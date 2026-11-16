package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext

import com.databricks.dicer.common.Assignment
import com.databricks.dicer.common.SliceKeySensitivity
import com.databricks.dicer.external.Target
import com.databricks.featureflag.client.DynamicConf

/**
 * The Assigner's structured logging utility. No-op logger implementation.
 */
private[assigner] trait AssignerProtoLogger {

  /**
   * Convenience method to log assignment update events.
   *
   * @param target the target for which the assignment was updated.
   * @param assignment the assignment containing generation and resource information.
   * @param contextOpt optional assignment generation context.
   * @param sliceKeySensitivity the customer's attestation of whether this target's SliceKeys are
   *                            sensitive.
   */
  def logAssignmentUpdate(
      target: Target,
      assignment: Assignment,
      contextOpt: Option[AssignmentGenerator.AssignmentGenerationContext],
      sliceKeySensitivity: SliceKeySensitivity): Unit

  /**
   * Convenience method to log preferred assigner change events.
   *
   * @param preferredAssignerValue the preferred assigner value containing role and assigner info
   *      as PreferredAssignerValue.
   */
  def logPreferredAssignerChange(preferredAssignerValue: PreferredAssignerValue): Unit
}

/** No-op implementation of [[AssignerProtoLogger]] that does not perform any logging. */
private object NoopAssignerProtoLogger extends AssignerProtoLogger {

  override def logAssignmentUpdate(
      target: Target,
      assignment: Assignment,
      contextOpt: Option[AssignmentGenerator.AssignmentGenerationContext],
      sliceKeySensitivity: SliceKeySensitivity): Unit = {
    // No-op
    ()
  }

  override def logPreferredAssignerChange(preferredAssignerValue: PreferredAssignerValue): Unit = {
    // No-op
    ()
  }
}

private[assigner] object AssignerProtoLogger {

  /**
   * Creates a new [[AssignerProtoLogger]] instance. Always returns [[NoopAssignerProtoLogger]].
   *
   * @param assignerInfo the AssignerInfo.
   * @param sampleFractionConf the config for the sample fraction.
   * @param loggingSec the sequential execution context for async logging operations.
   */
  def create(
      assignerInfo: AssignerInfo,
      sampleFractionConf: DynamicConf#FeatureFlag[Double],
      loggingSec: SequentialExecutionContext): AssignerProtoLogger = {
    NoopAssignerProtoLogger
  }

  /**
   * A convenience method to create an [[AssignerProtoLogger]] that does not log any events.
   *
   * @param loggingSec the sequential execution context for async logging operations (unused).
   */
  def createNoop(loggingSec: SequentialExecutionContext): AssignerProtoLogger = {
    NoopAssignerProtoLogger
  }
}
