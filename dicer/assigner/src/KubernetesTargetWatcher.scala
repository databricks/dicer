package com.databricks.dicer.assigner

import java.util.UUID

/** Kubernetes watch target. We use appName and namespace to watch pods for given Dicer target. */
case class KubernetesWatchTarget(appName: String, namespace: String)

/** Abstraction to watch target application pods in Kubernetes. */
trait KubernetesTargetWatcher {

  /** Start watching the target. */
  def start(): Unit

  /** Stop watching the target. */
  def stop(): Unit
}

object KubernetesTargetWatcher {

  /** Type to handle termination signal given a pod UUID.  */
  type TerminationHandler = UUID => Unit

  /** Factory for [[KubernetesTargetWatcher]]s.  */
  trait Factory {
    def create(
        kubernetesWatchTarget: KubernetesWatchTarget,
        terminationHandler: TerminationHandler): KubernetesTargetWatcher
  }

  /** Kubernetes watcher factory that returns no-op watchers.  */
  object NoOpFactory extends KubernetesTargetWatcher.Factory {
    override def create(
        kubernetesWatchTarget: KubernetesWatchTarget,
        terminationHandler: TerminationHandler): KubernetesTargetWatcher =
      new KubernetesTargetWatcher {
        override def start(): Unit = {}
        override def stop(): Unit = {}
      }
  }

  /**
   * Returns a [[Factory]] for no-op [[KubernetesTargetWatcher]]s (functionality being removed).
   */
  def newFactory(apiClientFactory: KubernetesApiClientFactory): Factory = NoOpFactory
}
