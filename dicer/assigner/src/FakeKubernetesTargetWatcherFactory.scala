package com.databricks.dicer.assigner

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.collection.mutable

import com.databricks.caching.util.Lock.withLock

/**
 * FakeKubernetesTargetWatcherFactory - A [[KubernetesTargetWatcher.Factory]] that returns fake k8s
 * target watchers and enables simulating terminating resource snapshots.
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

  /** Replaces the terminating resource set reported by the fake watcher. */
  @throws[IllegalArgumentException](
    "if no watch on kubernetesWatchTarget has been established, or the watch is not started"
  )
  def simulateTerminatingResources(
      kubernetesWatchTarget: KubernetesWatchTarget,
      terminatingResources: Set[UUID]): Unit = {
    // Record the terminating resources and read the callback under the lock. Release the lock
    // before invoking the callback since it may re-enter the lock.
    val invalidationCallback: () => Unit = withLock(lock) {
      val targetWatcher: FakeKubernetesTargetWatcher = targetWatcherMap.getOrElse(
        kubernetesWatchTarget,
        throw new IllegalArgumentException(
          s"No watch on $kubernetesWatchTarget has been established"
        )
      )

      // The callback is present iff the watch is started; an update only makes sense while running.
      val callback: () => Unit = targetWatcher.getInvalidationCallbackOpt.getOrElse(
        throw new IllegalArgumentException(
          s"Watch on $kubernetesWatchTarget has not been started"
        )
      )

      targetWatcher.terminatingResourceUuids = terminatingResources
      callback
    }

    invalidationCallback()
  }
}
