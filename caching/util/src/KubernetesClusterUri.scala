package com.databricks.caching.util

import java.net.URI

/**
 * A validated Kubernetes cluster IDM URI, e.g. "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"
 * (see <internal link>).
 *
 * An instance can only be obtained through [[KubernetesClusterUri.fromUri]], which validates the
 * structure of the URI with [[WhereAmIHelper.validateCluster]], so holding one is a proof that the
 * URI is well formed.
 */
final class KubernetesClusterUri private (val uri: String) {

  override def toString: String = uri

  override def hashCode(): Int = uri.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case that: KubernetesClusterUri => this.uri == that.uri
    case _ => false
  }
}

object KubernetesClusterUri {

  /**
   * Parses `uri` as a Kubernetes cluster IDM URI, validating its structure with
   * [[WhereAmIHelper.validateCluster]]. Returns `None` when `uri` is not a well-formed
   * Kubernetes cluster URI.
   */
  def fromUri(uri: String): Option[KubernetesClusterUri] = {
    try {
      WhereAmIHelper.validateCluster(URI.create(uri))
      Some(new KubernetesClusterUri(uri))
    } catch {
      case _: IllegalArgumentException => None
    }
  }
}
