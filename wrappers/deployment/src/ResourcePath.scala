package com.databricks.infra.lib

import com.databricks.api.proto.infra.infra.{KubernetesCluster, Region}

/** A resource that is part of the infrastructure data model. */
sealed trait InfraResource {
  def uri: Option[String]
}

object InfraResource {

  /** A resource identifying a Kubernetes cluster. */
  case class KubernetesClusterResource(uri: Option[String]) extends InfraResource

  /** A resource identifying a region. */
  case class RegionResource(uri: Option[String]) extends InfraResource
}

object ResourcePath {

  /**
   * Resolves `uri` to the infrastructure resource it names, or `None` if `idm` does not recognize
   * it.
   */
  def getFromUri(idm: InfraDataModel, uri: String): Option[InfraResource] = {
    uri.split(':').headOption match {
      case Some("kubernetes-cluster") =>
        idm.getInfraDef.kubernetesClusters
          .get(uri)
          .map(
            (cluster: KubernetesCluster) =>
              InfraResource.KubernetesClusterResource(Some(cluster.getUri))
          )
      case Some("region") =>
        idm.getInfraDef.regions
          .get(uri)
          .map((region: Region) => InfraResource.RegionResource(Some(region.getUri)))
      case _ => None
    }
  }
}
