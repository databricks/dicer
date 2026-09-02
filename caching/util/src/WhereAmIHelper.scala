package com.databricks.caching.util

import io.prometheus.client.Counter

import com.databricks.api.proto.infra.Environment
import com.databricks.conf.trusted.LocationConf

import scala.util.Try
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

  /**
   * Counter for [[validateCluster]] outcomes compared against [[KubernetesClusterUri.fromUri]]. Add
   * parity status (see [[ClusterUriParity]]) and the validated cluster URI as labels.
   */
  // TODO(<internal bug>): Cleanup once prod reports parity.
  private val clusterUriParityCount: Counter = Counter
    .build()
    .name("caching_util_where_am_i_cluster_uri_parity_total")
    .labelNames("status", "clusterUri")
    .help(
      "Counter of WhereAmIHelper.validateCluster calls, labeled by whether " +
      "KubernetesClusterUri.fromUri agreed that the cluster URI names a valid cluster."
    )
    .register()

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
   * Gets the legacy `kube_context` alias of the Kubernetes cluster where the current process is
   * running, if available (e.g., `"prod-cloud1-region1"`).
   *
   * The value comes from `WhereAmI.KubernetesLocation.legacy_kube_context` (which mirrors
   * `KubernetesCluster.deprecated_aliases.kube_context` in the IDM model). Standard service
   * deployment frameworks (k8sconfig, servicecfg) populate this field on every pod's `LOCATION`
   * env var; callers that need the kubeContext should still treat absence as a possibility and
   * handle the `None` case explicitly rather than relying on the framework guarantee.
   */
  def getLegacyKubeContext: Option[String] = {
    LocationConf.singleton.location.getLegacyKubeContext match {
      case "" | null => None
      case ctx: String => Some(ctx)
    }
  }

  /**
   * Gets the deployment environment of the Kubernetes cluster where the current process is running.
   * The returned string is the canonical resource-ID component name: one of `"dev"`, `"staging"`,
   * or `"prod"`. Returns None when the environment is unspecified.
   */
  def getEnvironment: Option[String] = {
    LocationConf.singleton.location.getEnvironment match {
      case Environment.Kind.KIND_UNSPECIFIED => None
      case kind: Environment.Kind => Some(kind.toString.toLowerCase())
    }
  }

  /**
   * Validates that the given Kubernetes cluster URI matches the spec at <internal link>,
   * e.g., "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01", and records a metric
   * with the comparison of the current outcome to the outcome of validation via
   * [[KubernetesClusterUri.fromUri]].
   */
  def validateCluster(cluster: URI): Unit = {
    // Capture the validation failure, if any, it can be thrown later after the parity metric has
    // been recorded.
    val validation: Try[Unit] = Try {
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
      // The path specification (which in the java URI API is called the scheme-specific part
      // for an opaque/non-hierarchical URI) is expected to consist of 6 parts:
      //  - environment (e.g., "dev")
      //  - cloud provider (e.g., "aws")
      //  - regulatory domain (e.g., "public")
      //  - region (e.g., "region8")
      //  - cluster type (e.g., "gc" for GENERAL_CLASSIC)
      //  - cluster code (e.g., "01" for the first GENERAL_CLASSIC cluster in the region)
      // Per the advice at <internal link>, we do not attempt to parse or validate the contents of
      // the parts.
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

    // Determine parity outcome by comparing the `validation` result with the outcome of
    // `KubernetesClusterUri.fromUri`.
    val parity: ClusterUriParity =
      (validation.isSuccess, KubernetesClusterUri.fromUri(cluster.toASCIIString).isDefined) match {
        case (true, false) => ClusterUriParity.ValidateOnly
        // $COVERAGE-OFF$: Unreachable because IDM contains normalized cluster URIs, which
        // `validateCluster` always accepts.
        case (false, true) => ClusterUriParity.FromUriOnly
        // $COVERAGE-ON$
        case _ => ClusterUriParity.Match
      }
    clusterUriParityCount.labels(parity.label, cluster.toASCIIString).inc()

    // Throw the validation failure, if any, now that the parity has been recorded.
    validation.get
  }

  /**
   * The outcome of comparing [[validateCluster]]'s verdict on a cluster URI against
   * [[KubernetesClusterUri.fromUri]], surfaced as the `status` metric label.
   */
  private[util] sealed trait ClusterUriParity {

    /** The Prometheus `status` label value recorded for this outcome. */
    def label: String
  }

  private[util] object ClusterUriParity {

    /** Both accepted the cluster URI, or both rejected it. */
    case object Match extends ClusterUriParity {
      override def label: String = "match"
    }

    /**
     * `validateCluster` accepted the cluster URI but `fromUri` returned `None`.
     */
    case object ValidateOnly extends ClusterUriParity {
      override def label: String = "validateOnly"
    }

    /** `fromUri` resolved the cluster URI but `validateCluster` rejected it. */
    case object FromUriOnly extends ClusterUriParity {
      override def label: String = "fromUriOnly"
    }
  }
}
