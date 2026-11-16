package com.databricks.dicer.external

import java.net.URI

/**
 * REQUIRES: `uri` is non-empty.
 *
 * A routable address for a resource within a target that is sharded by Dicer (a target may be a k8s
 * StatefulSet for instance). The resource is always a k8s pod at present. In the future, support
 * might be extended to targets that (say) span multiple clusters in which case the addressed
 * resource might still be a pod, or something else.
 *
 * @param uri the address for the resource, used to route messages to it.
 */
final class ResourceAddress private (val uri: URI) extends Ordered[ResourceAddress] {
  require(uri.toString.nonEmpty, "URI must not be empty")

  override def toString: String = uri.toString

  override def compare(that: ResourceAddress): Int = uri.compareTo(that.uri)

  override def hashCode(): Int = uri.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case that: ResourceAddress => this.uri == that.uri
    case _ => false
  }
}
private[dicer] object ResourceAddress {
  def apply(uri: URI): ResourceAddress = new ResourceAddress(uri)
}
