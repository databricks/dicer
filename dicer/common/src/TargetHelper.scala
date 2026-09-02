package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.TargetP
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Target}
import java.net.URI

/** Utility methods for [[Target]] that are internal (not part of the public API). */
private[dicer] object TargetHelper {

  /**
   * Creates a Target from the given `proto`.
   *
   * Supports both [[KubernetesTarget]] and [[AppTarget]] types based on the proto's `target_type`
   * field:
   *   - TYPE_UNSPECIFIED or KUBERNETES: Creates a [[KubernetesTarget]] (TYPE_UNSPECIFIED is
   *     treated as KUBERNETES for backward compatibility with protos that predate the `target_type`
   *     field).
   *   - APP: Creates an [[AppTarget]] using the `instance_id` field.
   *
   * @param proto The proto representation of the target.
   * @throws IllegalArgumentException if the proto is invalid.
   */
  @throws[IllegalArgumentException]("if the proto is invalid")
  def fromProto(proto: TargetP): Target = {
    val name: String = proto.getName
    proto.getTargetType match {
      case TargetP.Type.TYPE_UNSPECIFIED | TargetP.Type.KUBERNETES =>
        proto.kubernetesClusterUri match {
          case Some(cluster: String) =>
            Target.createKubernetesTarget(URI.create(cluster), name)
          case None =>
            Target(name)
        }
      case TargetP.Type.APP =>
        require(proto.instanceId.isDefined, "instanceId must be defined for AppTarget")
        Target.createAppTarget(name, proto.getInstanceId)
    }
  }

  /**
   * Parses the [[Target.toParseableDescription]] representation of a target.
   *
   * Supports parsing both [[KubernetesTarget]] and [[AppTarget]] formats:
   *   - For [[AppTarget]]: expects format `appTarget:targetName:instanceId`
   *   - For [[KubernetesTarget]] with cluster: expects format `clusterUri:targetName`
   *   - For [[KubernetesTarget]] without cluster: expects just `targetName`
   *
   * @param description The string representation of the target to parse.
   */
  def parse(description: String): Target = {
    if (description.startsWith("appTarget:")) {
      val delimiterIndex: Int = description.indexOf(":", 10)
      val name: String = description.substring(10, delimiterIndex)
      val instanceId: String = description.substring(delimiterIndex + 1)
      Target.createAppTarget(name, instanceId)
    } else {
      val delimiterIndex: Int = description.lastIndexOf(":")
      if (delimiterIndex == -1) {
        // No delimiter, which means the target does not specify a cluster.
        Target(description)
      } else {
        // Delimiter found, which means the target specifies a cluster.
        val clusterStr: String = description.substring(0, delimiterIndex)
        val cluster = new URI(clusterStr)
        val name: String = description.substring(delimiterIndex + 1)
        Target.createKubernetesTarget(cluster, name)
      }
    }
  }

  /**
   * Returns whether the component handling `localTarget` should serve an incoming request from
   * `requestTarget`.
   *
   * For two [[KubernetesTarget]]s whose names match but whose cluster identifiers differ, the
   * assignment is still served. This is used for our handling of SMK migration, where a target
   * being migrated may straddle multiple clusters.
   *
   * An [[AppTarget]] `localTarget` should serve an assignment to a same-name [[KubernetesTarget]]
   * `requestTarget`. This allows KubernetesTarget Clerks to successfully watch for assignments
   * from AppTarget Slicelets, which de-couples the migration of Clerks from Slicelets. This
   * relationship is asymmetric: a [[KubernetesTarget]] `localTarget` should not serve an
   * assignment to a request with an [[AppTarget]] `requestTarget`. We will migrate Slicelets to
   * [[AppTarget]] completely before Clerks begin migrating, so the reverse direction is never a
   * valid migration state.
   *
   * While this special case is allowed, the correct distribution of assignments from Slicelets to
   * Clerks depends on the network configuration of the Clerk (i.e., the `sliceletHostname` of the
   * Clerk). This dependency already exists for KubernetesTarget Clerks talking to KubernetesTarget
   * Slicelets due to the SMK migration special case described above.
   *
   * Other types of mismatches other than these special cases are not served (e.g., two
   * [[AppTarget]]s with the same name but different instance IDs).
   *
   * TODO(<internal bug>): Tighten this check up after SMK migration finishes.
   *
   * @param localTarget the target configured on the local component handling the watch request
   * @param requestTarget the target supplied by the incoming watch request
   */
  def shouldServeRequestTarget(localTarget: Target, requestTarget: Target): Boolean = {
    (localTarget, requestTarget) match {
      // Both App: serve only when identical.
      case (localAppTarget: AppTarget, requestAppTarget: AppTarget) =>
        localAppTarget == requestAppTarget

      // Both Kubernetes: serve when names match, tolerating a cluster mismatch during SMK
      // migration.
      // App local and Kubernetes request: serve when names match, tolerating the type mismatch
      // during target migration.
      case (_: KubernetesTarget, _: KubernetesTarget) | (_: AppTarget, _: KubernetesTarget) =>
        localTarget.name == requestTarget.name

      // Kubernetes local and App request: never serve.
      case (_: KubernetesTarget, _: AppTarget) =>
        false
    }
  }

  implicit class TargetOps(target: Target) {

    /** Converts the target to its proto representation. */
    def toProto: TargetP = {
      target match {
        case kubernetesTarget: KubernetesTarget =>
          TargetP(
            name = Some(kubernetesTarget.name),
            kubernetesClusterUri = kubernetesTarget.clusterOpt.map { cluster: URI =>
              cluster.toASCIIString
            },
            targetType = Some(TargetP.Type.KUBERNETES)
          )
        case appTarget: AppTarget =>
          TargetP(
            name = Some(appTarget.name),
            kubernetesClusterUri = None,
            targetType = Some(TargetP.Type.APP),
            instanceId = Some(appTarget.instanceId)
          )
      }
    }

    /** Returns the "targetName" label value to use in metrics. */
    private[dicer] def getTargetNameLabel: String = target.name

    /**
     * Returns the k8s app name for the target, which is assumed to match the target name (by
     * convention, Dicer target names should be chosen to match the service def name, which is
     * expected to, in turn, determine the k8s app name.)
     */
    private[dicer] def getKubernetesAppName: String = target.name

    /** Returns the "targetCluster" label value to use in metrics. */
    private[dicer] def getTargetClusterLabel: String =
      target match {
        case kubernetesTarget: KubernetesTarget =>
          kubernetesTarget.clusterOpt
            .map { cluster: URI =>
              cluster.toASCIIString
            }
            .getOrElse("")
        case _: AppTarget =>
          // AppTargets are not defined by their cluster.
          ""
      }

    /** Returns the "targetInstanceId" label value to use in metrics. */
    private[dicer] def getTargetInstanceIdLabel: String = target match {
      case _: KubernetesTarget => ""
      case appTarget: AppTarget => appTarget.instanceId
    }

    /** Returns a description of the target used as a prefix for related log messages. */
    private[dicer] def getLoggerPrefix: String = target.toParseableDescription
  }
}
