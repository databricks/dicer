package com.databricks.dicer.common

import com.databricks.api.proto.infra.infra.KubernetesCluster
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.test.TargetTestDataP
import com.databricks.dicer.common.test.TargetTestDataP.InvalidUriTestCaseP
import com.databricks.dicer.external.{KubernetesTarget, Target}
import com.databricks.infra.lib.InfraDataModel
import com.databricks.testing.DatabricksTest
import java.net.URI

import com.databricks.api.proto.dicer.common.TargetP

class TargetSuite extends DatabricksTest {

  private val TEST_DATA: TargetTestDataP =
    loadTestData[TargetTestDataP]("dicer/common/test/data/target_test_data.textproto")

  /** A valid identifier that has the longest permitted length. */
  private val LONG_ID: String = TEST_DATA.validTargetNames.last
  assert(LONG_ID.length == 63)

  /** All known <internal link> Kubernetes clusters. */
  private val K8S_CLUSTER_URIS: Vector[URI] =
    InfraDataModel.fromEmbedded.getInfraDef.kubernetesClusters.valuesIterator.map {
      cluster: KubernetesCluster =>
        URI.create(cluster.getUri)
    }.toVector

  /** Valid target permutations constructed using `TEST_DATA.validTargetNames`. */
  private val VALID_TARGETS: Vector[Target] =
    TEST_DATA.validTargetNames.take(2).toVector.map { name: String =>
      Target(name)
    } ++ {
      for (cluster: URI <- K8S_CLUSTER_URIS; name: String <- TEST_DATA.validTargetNames)
        yield Target.createKubernetesTarget(cluster, name)
    }

  /** An invalid identifier that exceeds the length limit. */
  private val TOO_LONG_ID = TEST_DATA.invalidTargetNames.last
  assert(TOO_LONG_ID.length == 64)

  test("Valid names") {
    // Test plan: verify that we're able to construct Target using valid names (i.e. names that are
    // valid DNS labels).
    for (validName: String <- TEST_DATA.validTargetNames) {
      val target = Target(validName)
      assert(target.name == validName)
    }
  }

  test("Invalid names") {
    // Test plan: verify that `Target(invalidName)` throws an `IllegalArgumentException`.
    for (invalidName: String <- TEST_DATA.invalidTargetNames) {
      assertThrow[IllegalArgumentException]("Target name must match regex") {
        Target(invalidName)
      }
    }
  }

  gridTest("Invalid cluster URIs (parse)")(TEST_DATA.invalidUriTestCases) {
    testCase: InvalidUriTestCaseP =>
      // Test plan: verify that `Target(cluster, name)` throws an `IllegalArgumentException`.
      assertThrow[IllegalArgumentException](testCase.getExpectedErrorMessage) {
        TargetHelper.parse(testCase.getClusterUri + ":valid-name")
      }
  }

  test("toString") {
    // Test plan: verify that the string representation of the target is of the form `name`.
    assert(Target("name").toString == "name")
  }

  test("Parse") {
    // Test plan: verify that Target object round-trip via TargetHelper.parse and
    // Target.toParseableDescription.
    for (target: Target <- VALID_TARGETS) {
      assert(TargetHelper.parse(target.toParseableDescription) == target)
    }
  }

  test("Proto conversion") {
    // Test plan: verify that Target object round-trip via TargetHelper.fromProto and
    // Target.toProto.
    for (target: Target <- VALID_TARGETS) {
      assert(TargetHelper.fromProto(target.toProto) == target)
    }
  }

  test("Parse from proto with various target_types") {
    // Test plan: Verify that parsing a proto with a `None`, `TYPE_UNSPECIFIED`, or `KUBERNETES`
    // `target_type` results in a KubernetesTarget.
    val expectedTarget = Target("name")

    for (targetTypeOpt: Option[TargetP.Type] <- Seq(
        Some(TargetP.Type.KUBERNETES),
        Some(TargetP.Type.TYPE_UNSPECIFIED),
        None
      )) {
      val proto: TargetP =
        TargetP(name = Some(expectedTarget.name), targetType = targetTypeOpt)
      val target: Target = TargetHelper.fromProto(proto)
      assert(target == expectedTarget)
      assert(target match {
        case _: KubernetesTarget => true
        case _ => false
      })
    }
  }

  test("equality") {
    // Test plan: verify that target equality and hash codes are based on name and cluster.
    TestUtils.checkEquality(
      groups = VALID_TARGETS.zipWithIndex
        .filter {
          case (_: Target, i: Int) =>
            // Comparing all pairs is prohibitively expensive, so we only include every tenth
            // target.
            i % 10 == 0
        }
        .map {
          case (target: KubernetesTarget, _: Int) =>
            val copy = target.clusterOpt match {
              case Some(cluster: URI) => Target.createKubernetesTarget(cluster, target.name)
              case None => Target(target.name)
            }
            Seq(target, copy)
          case (_: Target, _: Int) =>
            throw new IllegalStateException("Unexpected target type in VALID_TARGETS")
        }
    )
  }

  test("metric labels") {
    // Test plan: verify that the expected metric label values are returned for targets with and
    // without clusters.
    val targetWithCluster =
      Target.createKubernetesTarget(
        URI.create("kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"),
        "foobar"
      )
    assert(targetWithCluster.getTargetNameLabel == "foobar")
    assert(targetWithCluster.getKubernetesAppName == "foobar")
    assert(
      targetWithCluster.getTargetClusterLabel ==
      "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"
    )
    assert(targetWithCluster.getTargetInstanceIdLabel == "")

    val targetWithoutCluster = Target("foobar")
    assert(targetWithoutCluster.getTargetNameLabel == "foobar")
    assert(targetWithoutCluster.getKubernetesAppName == "foobar")
    assert(targetWithoutCluster.getTargetClusterLabel == "")
    assert(targetWithoutCluster.getTargetInstanceIdLabel == "")
  }
}
