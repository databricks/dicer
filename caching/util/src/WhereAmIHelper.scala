package com.databricks.caching.util

import com.databricks.conf.trusted.LocationConf

import scala.util.control.NonFatal
import java.net.URI

/**
 * Utility object that supplies information on which Kubernetes cluster the current process is
 * running in, to enable Dicer sharding of a target running in a different cluster from the
 * Assigner.
 *
 * IMPORTANT NOTE: As of writing (2024/12/07) this relies on the presence of the `LOCATION`
 * environment variable which is still being rolled out everywhere, and currently only guaranteed to
 * be populated for services defined using standard frameworks (relying on k8sconfig and/or
 * servicecfg). For services that have a hard dependency on WhereAmI support, it is recommended to
 * add a lint check for your service configuration on the presence of `LOCATION`.
 */
object WhereAmIHelper {

  /** The scheme for Kubernetes cluster URIs in <internal link>. */
  private val KUBERNETES_CLUSTER_SCHEME: String = "kubernetes-cluster"

  /** Gets the URI of the Kubernetes cluster where the current process is running, if available. */
  def getClusterUri: Option[URI] = {
    try {
      LocationConf.singleton.location.getKubernetesClusterUri match {
        case "" | null => None
        case uri: String =>
          validateCluster(new URI(uri))
          Some(new URI(uri))
      }
    } catch {
      case NonFatal(_) => None
    }
  }

  /**
   * Gets the region URI of the Kubernetes cluster where the current process is running, if
   * available.
   */
  def getRegionUri: Option[String] = {
    LocationConf.singleton.location.getRegionUri match {
      case "" | null => None
      case uri: String =>
        Some(uri)
    }
  }

  /**
   * Validates that the given Kubernetes cluster URI matches the spec at <internal link>, e.g.,
   * "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01".
   */
  def validateCluster(cluster: URI): Unit = {
    // Verify that the URI has the expected scheme.
    val scheme: String = Option(cluster.getScheme()).getOrElse("")
    require(
      scheme == KUBERNETES_CLUSTER_SCHEME,
      s"Expected a $KUBERNETES_CLUSTER_SCHEME URI, got: $cluster"
    )
    // Verify that the URI is opaque, i.e., does not represent a hierarchical URI where the
    // scheme-specific part begins with / or where there is no scheme (as in, for example,
    // "https://www.databricks.com" or "relative_uri").
    require(
      cluster.isOpaque(),
      s"Expected an opaque URI, got: $cluster"
    )
    // The path specification (which in the java URI API is called the scheme-specific part for an
    // opaque/non-hierarchical URI) is expected to consist of 6 parts:
    //  - environment (e.g., "dev")
    //  - cloud provider (e.g., "aws")
    //  - regulatory domain (e.g., "public")
    //  - region (e.g., "region8")
    //  - cluster type (e.g., "gc" for GENERAL_CLASSIC)
    //  - cluster code (e.g., "01" for the first GENERAL_CLASSIC cluster in the region)
    // Per the advice at <internal link>, we do not attempt to parse or validate the contents of the
    // parts.
    val path = Option(cluster.getSchemeSpecificPart()).getOrElse("")
    val parts: Array[String] = path.split("/")
    require(
      parts.length == 6,
      s"Cluster URI path must have 6 parts: parts=[${parts.mkString(", ")}]"
    )
    // To validate that the cluster URI consists of just the schema and path, nothing more,
    // construct a new URI with the same scheme and path, and compare it to the original.
    val expectedCluster = URI.create(s"$KUBERNETES_CLUSTER_SCHEME:$path")
    require(
      expectedCluster == cluster,
      s"Expected a normalized kubernetes-cluster URI like %normalizedCluster, got: $cluster"
    )
  }
}
