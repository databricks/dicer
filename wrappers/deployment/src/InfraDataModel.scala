package com.databricks.infra.lib

import com.databricks.api.proto.infra.infra.{KubernetesCluster, Region}

/** Trait for accessing infrastructure definitions. */
trait InfraDataModel {
  def getInfraDef: ComputeInfraDefinition

  /** Returns the Kubernetes cluster with the given URI, or `None` if not found. */
  def getKubernetesClusterByUri(uri: String): Option[KubernetesCluster]
}

/**
 * Provides minimal infrastructure metadata for Dicer, specifically Kubernetes cluster URIs
 * used by Targets.
 */
object InfraDataModel {

  /** Returns some example Kubernetes clusters and regions for testing. */
  lazy val fromEmbedded: InfraDataModel = new InfraDataModel {
    override def getInfraDef: ComputeInfraDefinition = {
      val testClusters = Map(
        "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01" ->
        new KubernetesCluster("kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"),
        "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01" ->
        new KubernetesCluster("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
      )
      val testRegions = Map(
        "region:prod/cloud1/public/region1" ->
        new Region("region:prod/cloud1/public/region1"),
        "region:dev/cloud1/public/region1" ->
        new Region("region:dev/cloud1/public/region1")
      )
      new ComputeInfraDefinition(testClusters, testRegions)
    }

    override def getKubernetesClusterByUri(uri: String): Option[KubernetesCluster] =
      // First check the static map, then fall back to constructing a cluster from the URI directly.
      // This allows the implementation to handle any well-formed cluster URI without requiring all
      // clusters to be listed in the embedded test data.
      getInfraDef.kubernetesClusters
        .get(uri)
        .orElse(
          if (uri.nonEmpty) Some(new KubernetesCluster(uri)) else None
        )
  }
}

/**
 * Container for infrastructure definitions.
 *
 * @param kubernetesClusters Map of cluster URI to cluster metadata.
 * @param regions Map of region URI to region metadata.
 */
class ComputeInfraDefinition(
    val kubernetesClusters: Map[String, KubernetesCluster],
    val regions: Map[String, Region])
