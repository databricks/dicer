package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.TargetP
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Target}
import java.net.URI

/**
 * Converts [[TargetP]] to [[Target]]. This is a trait rather than a concrete implementation because
 * the Dicer assigner needs to normalize targets: for Kubernetes targets running in the same cluster
 * as the assigner, the cluster URI ([[Target.clusterOpt]]) must be empty. Otherwise, the assigner
 * would risk tracking state for two alternate representations of the same logical target. For
 * example, in the Dicer assigner running in kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01, the
 * targets "softstore-storelet" and
 * "kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01:softstore-storelet" are logically equivalent,
 * but for purposes of state tracking (including generator maps and assignment keys in etcd), we
 * prefer the short-form "softstore-storelet".
 */
private[dicer] trait TargetUnmarshaller {

  /** Creates a [[Target]] from the given `proto`. */
  @throws[IllegalArgumentException]("if the proto is invalid")
  def fromProto(proto: TargetP): Target
}
private[dicer] object TargetUnmarshaller {

  /**
   * The marshaller to use in client code. This marshaller preserves the target cluster URI for
   * Kubernetes targets when it is present.
   */
  val CLIENT_UNMARSHALLER: TargetUnmarshaller = TargetHelper.fromProto(_)

  /**
   * Creates a marshaller to use in the Dicer assigner. This marshaller normalizes Kubernetes
   * targets to remove the cluster URI when the target is running in the same cluster as the
   * assigner.
   */
  def createAssignerUnmarshaller(assignerCluster: URI): TargetUnmarshaller = { proto: TargetP =>
    val target = TargetHelper.fromProto(proto)
    target match {
      case kubernetesTarget: KubernetesTarget =>
        if (kubernetesTarget.clusterOpt.contains(assignerCluster)) {
          // Normalize the target by removing the cluster URI. Per spec (see
          // [[TargetUnMarshaller]]), the canonical representation of a target running in the same
          // cluster is a target with an empty cluster field.
          Target(target.name)
        } else {
          target
        }
      case _: AppTarget =>
        target
    }
  }
}
