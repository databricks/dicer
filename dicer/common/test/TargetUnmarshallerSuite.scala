package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.TargetP
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import java.net.URI

/**
 * Narrow tests for [[TargetUnmarshaller]] specializations. End-to-end marshalling/unmarshalling
 * tests are in [[TargetSuite]].
 */
class TargetUnmarshallerSuite extends DatabricksTest {

  private val CLUSTER_URI1 = URI.create("kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01")
  private val CLUSTER_URI2 = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01")

  test("Target from same cluster is normalized") {
    // Test plan: the unmarshaller used by the assigner should strip out the cluster URI when it
    // matches the assigner's URI, but not when it refers to a different cluster.

    val unmarshaller = TargetUnmarshaller.createAssignerUnmarshaller(CLUSTER_URI1)
    val targetProtoSameCluster = TargetP(
      name = Some("softstore-storelet"),
      kubernetesClusterUri = Some(CLUSTER_URI1.toASCIIString)
    )
    val targetProtoNoCluster =
      TargetP(name = Some("softstore-storelet"), kubernetesClusterUri = None)
    val targetProtoDifferentCluster = TargetP(
      name = Some("softstore-storelet"),
      kubernetesClusterUri = Some(CLUSTER_URI2.toASCIIString)
    )

    // Parse all three targets. The first two are logically equivalent, and should be normalized to
    // identical `Target` objects with the `clusterOpt` fields cleared. The cluster URI should be
    // preserved for the third target.
    val targetSameCluster: Target = unmarshaller.fromProto(targetProtoSameCluster)
    val targetNoCluster: Target = unmarshaller.fromProto(targetProtoNoCluster)
    val targetDifferentCluster: Target = unmarshaller.fromProto(targetProtoDifferentCluster)
    assert(targetSameCluster == Target("softstore-storelet"))
    assert(targetNoCluster == Target("softstore-storelet"))
    assert(targetSameCluster == targetNoCluster)
    assert(
      targetDifferentCluster == Target.createKubernetesTarget(CLUSTER_URI2, "softstore-storelet")
    )
  }

  test("Client unmarshaller preserves cluster") {
    // Test plan: the unmarshaller used by the client should preserve the cluster URI when it is
    // present in the target.
    val unmarshaller = TargetUnmarshaller.CLIENT_UNMARSHALLER
    val targetProto = TargetP(
      name = Some("softstore-storelet"),
      kubernetesClusterUri = Some(CLUSTER_URI1.toASCIIString)
    )
    val target = unmarshaller.fromProto(targetProto)
    assert(target == Target.createKubernetesTarget(CLUSTER_URI1, "softstore-storelet"))
  }

  test("Unmarshallers handle AppTarget") {
    // Test plan: Verify that the client and assigner unmarshallers correctly handle AppTargets.
    val expectedTarget: Target = Target.createAppTarget("internal-system", "app-12345678")

    val clientUnmarshalledTarget: Target = TargetUnmarshaller.CLIENT_UNMARSHALLER.fromProto(
      expectedTarget.toProto
    )
    assert(clientUnmarshalledTarget == expectedTarget)

    val assignerUnmarshalledTarget: Target = TargetUnmarshaller
      .createAssignerUnmarshaller(CLUSTER_URI1)
      .fromProto(expectedTarget.toProto)
    assert(assignerUnmarshalledTarget == expectedTarget)
  }
}
