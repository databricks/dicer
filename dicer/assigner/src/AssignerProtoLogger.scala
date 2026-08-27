package com.databricks.dicer.assigner

import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.caching.util.WatchValueCell

import com.databricks.dicer.common.Assignment
import com.databricks.dicer.common.SliceKeySensitivity
import com.databricks.dicer.external.Target

/**
 * A member pod discovered during a Kubernetes membership check: its UUID paired with its
 * Kubernetes pod name. Maps to the `MemberPodP` proto in the membership-check log.
 */
private[assigner] case class MemberPod(uuid: String, podName: String)

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
   * Convenience method to log membership check events from the [[KubernetesMembershipChecker]].
   *
   * @param latencyMs the elapsed time of the Kubernetes API call in milliseconds.
   * @param httpStatusCode the HTTP status code returned by the Kubernetes API.
   * @param namespace the Kubernetes namespace of the application whose membership is being checked.
   * @param appName the name of the application whose membership is being checked.
   * @param emitterPodNameOpt the pod name of the assigner emitting this log, if known.
   * @param members the pods discovered for the given namespace + appName, each carrying its UUID
   *                and pod name as a [[MemberPod]].
   * @param filteredMembers the subset of `members` that passed readiness/termination filtering.
   * @param kubernetesResourceVersion the resource version token returned by the Kubernetes API.
   * @param kubeContextOpt the kube context of the tracked application, if known.
   */
  def logMembershipCheck(
      latencyMs: Long,
      httpStatusCode: Int,
      namespace: String,
      appName: String,
      emitterPodNameOpt: Option[String],
      members: Seq[MemberPod],
      filteredMembers: Seq[MemberPod],
      kubernetesResourceVersion: String,
      kubeContextOpt: Option[String]): Unit

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

  override def logMembershipCheck(
      latencyMs: Long,
      httpStatusCode: Int,
      namespace: String,
      appName: String,
      emitterPodNameOpt: Option[String],
      members: Seq[MemberPod],
      filteredMembers: Seq[MemberPod],
      kubernetesResourceVersion: String,
      kubeContextOpt: Option[String]): Unit = {
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
   * @param sampleFractionCell provides the fraction of generations to sample.
   * @param loggingSec the sequential execution context for async logging operations.
   */
  def create(
      assignerInfo: AssignerInfo,
      sampleFractionCell: WatchValueCell.Consumer[Double],
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
