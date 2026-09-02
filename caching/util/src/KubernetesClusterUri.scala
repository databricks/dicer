package com.databricks.caching.util

import com.databricks.infra.lib.{InfraDataModel, InfraResource, ResourcePath}

/**
 * A validated Kubernetes cluster IDM URI, e.g. "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"
 * (see <internal link>).
 *
 * An instance can only be obtained through [[KubernetesClusterUri.fromUri]], which resolves the URI
 * against the [[InfraDataModel]] owned by the Infra team, so holding one is a proof that the URI
 * named a real cluster in the model.
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
   * Parses `uri` as a Kubernetes cluster IDM URI, resolving it against the embedded
   * [[InfraDataModel]]. Returns `None` when `uri` is not a `kubernetes-cluster:` URI, or the
   * cluster URI is not defined in IDM (see [[ResourcePath.getFromUri]]).
   *
   * The embedded model is co-versioned with the binary, so a cluster turned up after the binary was
   * built resolves to `None`.
   */
  def fromUri(uri: String): Option[KubernetesClusterUri] = {
    val clusterOpt: Option[InfraResource.KubernetesClusterResource] =
      ResourcePath.getFromUri(InfraDataModel.fromEmbedded, uri).collect {
        case cluster: InfraResource.KubernetesClusterResource => cluster
      }
    val resultOpt: Option[KubernetesClusterUri] =
      clusterOpt.flatMap(_.uri).map(new KubernetesClusterUri(_))
    IdmUriParseMetrics.recordParse(
      IdmUriParseMetrics.UriType.KubernetesCluster,
      succeeded = resultOpt.isDefined
    )
    resultOpt
  }
}
