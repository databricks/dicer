package com.databricks.infra.lib

import com.databricks.api.proto.infra.infra.KubernetesCluster

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

  /** Returns some example Kubernetes clusters for testing. */
  lazy val fromEmbedded: InfraDataModel = new InfraDataModel {
    override def getInfraDef: ComputeInfraDefinition = {
      val testClusters = Map(
        "kubernetes-cluster:test-env1/cloud-provider1/domain1/region1/cluster-type1/01" ->
        new KubernetesCluster(
          "kubernetes-cluster:test-env1/cloud-provider1/domain1/region1/cluster-type1/01"
        ),
        "kubernetes-cluster:test-env2/cloud-provider2/domain2/region2/cluster-type2/02" ->
        new KubernetesCluster(
          "kubernetes-cluster:test-env2/cloud-provider2/domain2/region2/cluster-type2/02"
        ),
        "kubernetes-cluster:test-env3/cloud-provider3/domain3/region3/cluster-type3/03" ->
        new KubernetesCluster(
          "kubernetes-cluster:test-env3/cloud-provider3/domain3/region3/cluster-type3/03"
        )
      )
      new ComputeInfraDefinition(testClusters)
    }

    override def getKubernetesClusterByUri(uri: String): Option[KubernetesCluster] =
      // First check the static map, then fall back to constructing a cluster from the URI
      // directly. This allows the OSS implementation to handle any well-formed cluster URI
      // without requiring all clusters to be listed in the embedded test data.
      getInfraDef.kubernetesClusters
        .get(uri)
        .orElse(
          if (uri.nonEmpty) Some(new KubernetesCluster(uri)) else None
        )
  }
}

/**
 * Container for infrastructure definitions, specifically Kubernetes clusters.
 *
 * @param kubernetesClusters Map of cluster identifiers to cluster metadata.
 */
class ComputeInfraDefinition(val kubernetesClusters: Map[String, KubernetesCluster])
