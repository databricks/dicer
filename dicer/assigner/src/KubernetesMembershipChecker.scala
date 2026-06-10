package com.databricks.dicer.assigner

import scala.concurrent.duration._

import com.databricks.caching.util.{Cancellable, SequentialExecutionContext, ValueStreamCallback}

/**
 * OSS stub for [[KubernetesMembershipChecker]]. The real implementation polls the Kubernetes
 * API for pod membership; in OSS this is not available, so only the Factory / NoOpFactory
 * surface is provided.
 */
class KubernetesMembershipChecker private[assigner] (
    sec: SequentialExecutionContext,
    assignerInfo: AssignerInfo,
    namespace: String,
    appName: String,
    pollingInterval: FiniteDuration,
    rpcPort: Int,
    kubeContextLabelOpt: Option[String])
    extends ResourceWatcher {

  /** Starts polling. No-op in OSS. */
  override def start(): Unit = {}

  /** Watches for resource set updates. No-op in OSS. */
  override def watch(callback: ValueStreamCallback[VersionedResourceSet]): Cancellable = {
    Cancellable.NO_OP_CANCELLABLE
  }

  /** Watches connection health. No-op in OSS. */
  override def watchConnectionHealth(callback: ValueStreamCallback[Boolean]): Cancellable = {
    Cancellable.NO_OP_CANCELLABLE
  }

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
}
