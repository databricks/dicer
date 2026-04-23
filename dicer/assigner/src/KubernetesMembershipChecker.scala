package com.databricks.dicer.assigner

import scala.concurrent.duration._

import com.databricks.caching.util.SequentialExecutionContext

/**
 * OSS stub for [[KubernetesMembershipChecker]]. The real implementation polls the Kubernetes
 * API for pod membership; in OSS this is not available, so only the Factory / NoOpFactory
 * surface is provided.
 */
class KubernetesMembershipChecker private[assigner] (
    sec: SequentialExecutionContext, // Unused in OSS stub; present for API compatibility.
    assignerInfo: AssignerInfo,
    namespace: String,
    appName: String,
    pollingInterval: FiniteDuration,
    rpcPort: Int) {

  /** Starts polling. No-op in OSS. */
  def start(): Unit = {}

  /** Test-only accessors for internal state. */
  private[assigner] object forTest {

    /** Stops polling. No-op in OSS. */
    def stop(): Unit = {}
  }
}

object KubernetesMembershipChecker {

  /** Default polling interval for the membership checker in production. */
  val DEFAULT_POLLING_INTERVAL: FiniteDuration = 1.second

  /**
   * Factory for creating [[KubernetesMembershipChecker]] instances.
   */
  trait Factory {

    /**
     * Creates a [[KubernetesMembershipChecker]] for the given assigner, or
     * [[None]] if disabled.
     */
    def create(
        assignerInfo: AssignerInfo,
        assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker]
  }

  /**
   * OSS stub for [[DefaultFactory]]. Always returns a no-op [[Factory]] since K8s polling is not
   * available in OSS.
   */
  object DefaultFactory {

    /** Returns a no-op [[Factory]] in OSS. Return type is [[Factory]] (not [[DefaultFactory]])
     *  since the OSS stub does not define [[DefaultFactory]] as a class. Callers should store the
     *  result as [[Factory]].
     */
    def create(
        namespace: String,
        appName: String,
        pollingInterval: FiniteDuration,
        rpcPort: Int): Factory =
      new Factory {
        override def create(
            assignerInfo: AssignerInfo,
            assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker] = None
      }
  }

  /** Records a successful membership checker initialization. No-op in OSS. */
  private[assigner] def recordInitSuccess(): Unit = {}

  /** Records a failed membership checker initialization. No-op in OSS. */
  private[assigner] def recordInitFailure(): Unit = {}
}
