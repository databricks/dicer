package com.databricks.dicer.assigner

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.collection.mutable

import com.databricks.caching.util.Lock.withLock

/**
 * FakeKubernetesTargetWatcherFactory - A [[KubernetesTargetWatcher.Factory]] that returns fake k8s
 * target watchers and enables simulating pod termination signals.
 */
@ThreadSafe
class FakeKubernetesTargetWatcherFactory extends KubernetesTargetWatcher.Factory {

  /**
   * Lock to protect [[targetWatcherMap]] and operations (start, stop) performed on each
   * TargetWatcher.
   */
  private val lock = new ReentrantLock()

  /**
   * Map from KubernetesWatchTarget to KubernetesTargetWatcher. We maintain this to trigger
   * termination handlers for a target.
   */
  @GuardedBy("lock")
  private val targetWatcherMap =
    new mutable.HashMap[KubernetesWatchTarget, FakeKubernetesTargetWatcher]()

  /**
   * Fake [[KubernetesTargetWatcher]] for a specific watch target (see [[KubernetesWatchTarget]]).
   * The invalidation callback it was started with doubles as the "is started" flag: it is present
   * iff the watch is currently started.
   */
  @ThreadSafe
  private class FakeKubernetesTargetWatcher extends KubernetesTargetWatcher {

    /** The full set of target pod UUIDs that are currently terminating. */
    @GuardedBy("lock")
    private[FakeKubernetesTargetWatcherFactory] var terminatingResourceUuids: Set[UUID] = Set.empty

    /** The callback passed to [[start]], or [[None]] while the watch is stopped. */
    @GuardedBy("lock")
    private var invalidationCallbackOpt: Option[() => Unit] = None

    /** Returns the callback passed to [[start]], or [[None]] if the watcher is not started. */
    def getInvalidationCallbackOpt: Option[() => Unit] = withLock(lock) {
      invalidationCallbackOpt
    }

    override def start(invalidationCallback: () => Unit): Unit = withLock(lock) {
      invalidationCallbackOpt = Some(invalidationCallback)
    }

    override def stop(): Unit = withLock(lock) {
      invalidationCallbackOpt = None
    }

    override def getTerminatingPods(): Set[UUID] = withLock(lock) {
      terminatingResourceUuids
    }
  }

  override def create(kubernetesWatchTarget: KubernetesWatchTarget): KubernetesTargetWatcher =
    withLock(lock) {
      targetWatcherMap.getOrElseUpdate(
        kubernetesWatchTarget,
        new FakeKubernetesTargetWatcher()
      )
    }

  /**
   * Simulates Kubernetes marking `podUuid` as terminating for `kubernetesWatchTarget`.
   *
   * NOTE: It is OK for the callback to be invoked multiple times for the same podUuid.
   */
  @throws[IllegalArgumentException](
    "if no watch on kubernetesWatchTarget has been established, or the watch is not started"
  )
  def simulateTerminationSignal(
      kubernetesWatchTarget: KubernetesWatchTarget,
      podUuid: UUID): Unit = {
    // Record the terminating pod and read the callback under the lock. We invoke the callback ONLY
    // after releasing the lock, since we should never invoke callbacks under the lock (and also k8s
    // watchers make no guarantees about serial execution of callbacks).
    val invalidationCallback: () => Unit = withLock(lock) {
      val targetWatcher: FakeKubernetesTargetWatcher = targetWatcherMap.getOrElse(
        kubernetesWatchTarget,
        throw new IllegalArgumentException(
          s"No watch on $kubernetesWatchTarget has been established"
        )
      )

      // The callback is present iff the watch is started; a signal only makes sense while running.
      val callback: () => Unit = targetWatcher.getInvalidationCallbackOpt.getOrElse(
        throw new IllegalArgumentException(
          s"Watch on $kubernetesWatchTarget has not been started"
        )
      )

      // Record the pod as terminating so the watch's getTerminatingPods() reports it.
      targetWatcher.terminatingResourceUuids = targetWatcher.terminatingResourceUuids + podUuid
      callback
    } // lock released

    // NOTE: As documented on the watcher's start(), the callback is not guaranteed to be serialized
    // (it can be invoked concurrently from multiple threads).
    invalidationCallback()
  }
}
