package com.databricks.dicer.external

import java.net.URI

import com.databricks.caching.util.{Rfc1123, WhereAmIHelper}

/**
 * Identifier for a Dicer-sharded service.
 */
sealed trait Target {

  /**
   * Returns the name of the Dicer-sharded service. See [[Target.apply]] for more details.
   */
  def name: String

  /**
   * Returns a human-readable and parseable representation of the target that roundtrips via
   * `TargetHelper.parse`. Used for:
   *  - keys in assignment storage
   *  - zpage parameters
   */
  private[dicer] def toParseableDescription: String
}

object Target {

  /**
   * REQUIRES: `name` is valid according to the parameter description.
   *
   * Creates a new [[Target]] with the given name.
   *
   * @param name The name of the Dicer-sharded service as defined in <internal link> or
   *             <internal link>, e.g., "softstore-storelet" for the Softstore cache. It should
   *             match the name defined in Dicer config (dicer/external/config). Since these names
   *             are used to identify Kubernetes resources, they must meet the requirements for RFC
   *             1123 label names:
   *                - contain at most 63 characters
   *                - contain only lowercase alphanumeric characters or '-'
   *                - start with an alphanumeric character
   *                - end with an alphanumeric character
   *
   * See https://kubernetes.io/docs/concepts/overview/working-with-objects/names for details.
   */
  def apply(name: String): Target = new KubernetesTarget(clusterOpt = None, name = name)

  /**
   * REQUIRES: `name` and `cluster` are valid according to the parameter descriptions.
   *
   * Creates a new [[Target]] with the given cluster and name.
   *
   * @param cluster The URI of the Kubernetes cluster where the Dicer-sharded service is running.
   *                The URI must be a well-formed Kubernetes cluster identifier as described at
   *                <internal link>, e.g., "kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01".
   * @param name The name of the Dicer-sharded service. See [[Target.apply(String)*]] for more
   *             details.
   */
  private[dicer] def createKubernetesTarget(cluster: URI, name: String): Target =
    new KubernetesTarget(clusterOpt = Some(cluster), name = name)

  /**
   * REQUIRES: `name` and `instanceId` are valid according to the parameter descriptions.
   *
   * Creates a new [[Target]] for a Dicer-sharded service that is identified by the given `name` and
   * `instanceId`.
   *
   * @param name The name of the Dicer-sharded service. Must meet the requirements for RFC 1123
   *             label names:
   *                - contain at most 63 characters
   *                - contain only lowercase alphanumeric characters or '-'
   *                - start with an alphanumeric character
   *                - end with an alphanumeric character
   * @param instanceId The identifier of the running instance of a Dicer-sharded service. A running
   *                   instance is a deployment of the service whose pods should be sharded together
   *                   by Dicer as a single group. Must satisfy the same RFC 1123 label name
   *                   requirements as `name`.
   */
  // TODO(<internal bug>): "App" is a legacy concept; rename AppTarget to Target once KubernetesTarget is
  // removed and only one Target type remains.
  private[dicer] def createAppTarget(name: String, instanceId: String): Target =
    new AppTarget(name, instanceId)

  /** Validates that the given target name conforms with RFC 1123. */
  private[dicer] def validateName(name: String): Unit = {
    require(Rfc1123.isValid(name), s"Target name must match regex ${Rfc1123.REGEX}: $name")
  }
}

/**
 * Identifier for a Kubernetes target.
 *
 * @param clusterOpt the URI of the Kubernetes cluster where the Dicer-sharded service is running.
 *
 *                   When this is not defined, Dicer assumes that the sharded service is running in
 *                   the same cluster as the Dicer backend. If multiple instances of a sharded
 *                   service, each running in a different cluster, are connecting to the same Dicer
 *                   backend (e.g., a GENERAL cluster Dicer backend), then the cluster URI is
 *                   required to differentiate between those instances. See
 *                   [[Target.apply(URI,String)*]] for more details.
 * @param name See [[Target.name]].
 */
private[dicer] class KubernetesTarget private[external] (
    private[dicer] val clusterOpt: Option[URI],
    val name: String)
    extends Target {
  validate()

  /**
   * String representation of the target. The [[clusterOpt]] URI is canonical (see [[validate]])
   * as is this string, which we can therefore use when comparing targets. We prefer this to
   * comparing URI objects directly, since those comparisons are more relaxed (multiple "equivalent"
   * representations) and relatively expensive.
   */
  private val canonicalStringRep = clusterOpt match {
    case Some(cluster: URI) => s"$cluster:$name"
    case None => name
  }

  override def toString: String = canonicalStringRep

  override def equals(obj: Any): Boolean = obj match {
    // Compare canonical string representations instead of URI objects since URI comparisons are so
    // expensive.
    case that: KubernetesTarget => this.canonicalStringRep == that.canonicalStringRep
    case _ => false
  }

  override def hashCode(): Int = canonicalStringRep.hashCode

  /**
   * See [[Target.toParseableDescription]]. For Kubernetes targets without cluster overrides, the
   * description is just the target name. Otherwise, it is of the form `targetCluster:targetName`.
   */
  override private[dicer] def toParseableDescription: String = canonicalStringRep

  private def validate(): Unit = {
    Target.validateName(name)
    for (cluster: URI <- clusterOpt) {
      WhereAmIHelper.validateCluster(cluster)
    }
  }
}

/**
 * Identifier for a Dicer-sharded service that is globally uniquely identified by a `name` and
 * `instanceId` pair. See [[Target.createAppTarget]] for the validity requirements (any RFC 1123
 * label).
 *
 * Note: unlike KubernetesTargets, AppTargets do not need to encode location information (e.g.,
 * cluster URI) about the Dicer-sharded service because the `instanceId` is globally unique across
 * all instances and clusters.
 *
 * @param name See [[Target.createAppTarget]].
 * @param instanceId See [[Target.createAppTarget]].
 */
private[dicer] class AppTarget private[external] (val name: String, val instanceId: String)
    extends Target {
  validate()

  private val canonicalStringRep = s"appTarget:$name:$instanceId"

  override def toString: String = canonicalStringRep

  override def equals(obj: Any): Boolean = obj match {
    case that: AppTarget => this.name == that.name && this.instanceId == that.instanceId
    case _ => false
  }

  override def hashCode(): Int = canonicalStringRep.hashCode

  /**
   * See [[Target.toParseableDescription]]. For app targets, the description is of the form
   * `appTarget:targetName:instanceId`.
   */
  override private[dicer] def toParseableDescription: String = canonicalStringRep

  private def validate(): Unit = {
    Target.validateName(name)
    AppTarget.validateInstanceId(instanceId)
  }
}

private[dicer] object AppTarget {

  /**
   * Validates that the given `instanceId` is a valid RFC 1123 label. See [[Target.createAppTarget]]
   * for more details.
   */
  @throws[IllegalArgumentException]("if instanceId is not a valid RFC 1123 label")
  private def validateInstanceId(instanceId: String): Unit = {
    require(Rfc1123.isValid(instanceId), s"Instance ID is invalid: $instanceId")
  }
}
