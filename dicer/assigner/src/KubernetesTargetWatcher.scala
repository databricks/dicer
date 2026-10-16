package com.databricks.dicer.assigner

import java.util.UUID

/** Kubernetes watch target. We use appName and namespace to watch pods for given Dicer target. */
case class KubernetesWatchTarget(appName: String, namespace: String)

/** Abstraction to watch target application pods in Kubernetes. */
trait KubernetesTargetWatcher {

  /**
   * Starts watching the target, invoking `invalidationCallback` when the result of
   * [[getTerminatingPods]] may have changed. Invocations may be "squelched" (the callback may fire
   * once for several changes) and may include "spurious wakeups" (no actual change), but a caller
   * that reads [[getTerminatingPods]] after the receipt of an upcall observes the latest state. It
   * may also be invoked concurrently, so it must be thread-safe.
   */
  def start(invalidationCallback: () => Unit): Unit

  /** Stop watching the target. */
  def stop(): Unit

  /**
   * Returns the UUIDs of all target pods that are currently terminating, i.e. that Kubernetes has
   * stamped with a deletion timestamp.
   *
   * An empty result is returned before/while the watcher is started/starting.
   */
  def getTerminatingPods(): Set[UUID]
}

object KubernetesTargetWatcher {

  /** Factory for [[KubernetesTargetWatcher]]s. */
  trait Factory {

    /** Creates a [[KubernetesTargetWatcher]] for the given target. */
    def create(kubernetesWatchTarget: KubernetesWatchTarget): KubernetesTargetWatcher
  }

  /** Kubernetes watcher factory that returns no-op watchers. */
  object NoOpFactory extends KubernetesTargetWatcher.Factory {
    override def create(kubernetesWatchTarget: KubernetesWatchTarget): KubernetesTargetWatcher =
      new KubernetesTargetWatcher {
        override def start(invalidationCallback: () => Unit): Unit = {}
        override def stop(): Unit = {}
        override def getTerminatingPods(): Set[UUID] = Set.empty
      }
  }

  /**
   * Returns a [[Factory]] for no-op [[KubernetesTargetWatcher]]s (functionality being removed).
   */
  def newFactory(apiClientFactory: KubernetesApiClientFactory): Factory = NoOpFactory
}
