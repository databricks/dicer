package com.databricks.api.proto.infra.infra

/**
 * Holds relational references from a Kubernetes cluster to other infra entities
 * (e.g. the region it belongs to).
 *
 * @param regionUri URI of the region this cluster belongs to.
 */
class KubernetesClusterRelation(regionUri: String) {

  /** Returns the URI of the region this cluster belongs to, or an empty string if unknown. */
  def getRegionUri: String = regionUri
}

/** Specification for a Kubernetes cluster. */
class KubernetesCluster(uri: String) {

  /** Returns the cluster's identifier URI. */
  def getUri: String = uri

  /**
   * Returns the relational references for this cluster.
   *
   * The region URI is extracted from the cluster URI path, which follows the format:
   * `kubernetes-cluster:{env}/{cloud}/{domain}/{region}/{cluster-type}/{index}`.
   */
  def getRelation: KubernetesClusterRelation = {
    val pathParts: Array[String] = uri.stripPrefix("kubernetes-cluster:").split("/")
    // Region is the 4th path element (index 3) in the standard cluster URI format.
    val regionUri: String = if (pathParts.length >= 4) pathParts(3) else ""
    new KubernetesClusterRelation(regionUri)
  }
}
