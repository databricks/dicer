package com.databricks.dicer.assigner

import java.util.UUID

import com.databricks.caching.util.{Cancellable, ValueStreamCallback}
import com.databricks.dicer.external.ResourceAddress

/**
 * An opaque version identifier for a resource set, comparable by length first and then
 * lexicographically.
 *
 * This implements the resource version spec described by the k8s docs here:
 * https://kubernetes.io/docs/reference/using-api/api-concepts/#resource-versions.
 *
 * @param value the underlying version string.
 */
case class ResourceVersion(value: String) extends Ordered[ResourceVersion] {

  override def compare(that: ResourceVersion): Int = {
    val lengthDiff: Int = this.value.length - that.value.length
    if (lengthDiff != 0) lengthDiff
    else this.value.compareTo(that.value)
  }
}

/**
 * A versioned snapshot of resources identified by their UUIDs and routable addresses.
 *
 * TODO(<internal bug>): Change this to return URI instead of ResourceAddress.
 *
 * @param version the version of this resource set, used to compare freshness.
 * @param resources mapping from resource UUID to its routable address.
 */
case class VersionedResourceSet(version: ResourceVersion, resources: Map[UUID, ResourceAddress])

/**
 * Watches for changes to the set of resources in a target and delivers updates via a
 * [[ValueStreamCallback]].
 *
 * This trait is a temporary abstraction that decouples resource discovery from the preferred
 * assigner mechanism. The initial implementation delegates to [[KubernetesMembershipChecker]]
 * for pod-level resource discovery. Once the consistent-hash preferred assigner is stable, this
 * trait and its implementations may be consolidated or removed.
 */
trait ResourceWatcher {

  /**
   * Starts the watcher. Must be called before [[watch]].
   */
  def start(): Unit

  /**
   * Watches for updates to the resource set. Updates are delivered to `callback` as they become
   * available until some time after the returned handle is cancelled.
   */
  def watch(callback: ValueStreamCallback[VersionedResourceSet]): Cancellable

  /**
   * Watches the health of the underlying connection used to discover resources. The `callback`
   * receives `true` when the connection is healthy and `false` when it is not.
   *
   * When the connection health is a dependency for the server running (e.g., the server cannot
   * serve meaningful responses without an up-to-date resource set), the server's own health
   * should be tied to the connection's health.
   */
  def watchConnectionHealth(callback: ValueStreamCallback[Boolean]): Cancellable
}
