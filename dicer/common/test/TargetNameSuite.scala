package com.databricks.dicer.common

import java.net.URI

import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.test.TargetTestDataP
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest

class TargetNameSuite extends DatabricksTest {

  private val TEST_DATA: TargetTestDataP =
    loadTestData[TargetTestDataP]("dicer/common/test/data/target_test_data.textproto")

  /** A Kubernetes cluster URI used to construct cluster-scoped targets. */
  private val CLUSTER_URI: URI = URI.create("kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01")

  /** Returns targets of every variant with the given `name`. */
  private def targetsWithName(name: String): Seq[Target] = Seq(
    Target(name),
    Target.createKubernetesTarget(CLUSTER_URI, name),
    Target.createAppTarget(name, "a123"),
    Target.createAppTarget(name, "b456")
  )

  gridTest("Valid names")(TEST_DATA.validTargetNames) { name: String =>
    // Test plan: Verify that TargetName accepts every valid target name from the shared textproto.
    // Do this by constructing a TargetName from each name and asserting that its value and string
    // representation are the name.
    val targetName = TargetName(name)
    assert(targetName.value == name)
    assert(targetName.toString == name)
  }

  gridTest("Invalid names")(TEST_DATA.invalidTargetNames) { name: String =>
    // Test plan: Verify that TargetName rejects every invalid target name from the shared
    // textproto. Do this by asserting that constructing a TargetName from each name throws an
    // IllegalArgumentException.
    assertThrow[IllegalArgumentException]("Target name must match regex") {
      TargetName(name)
    }
  }

  test("forTarget returns the target's name") {
    // Test plan: Verify that forTarget returns the target's name regardless of the target variant
    // or cluster. Do this by calling forTarget on targets of every variant with the same name and
    // asserting the result equals a TargetName constructed directly from that name.
    for (target: Target <- targetsWithName("softstore-storelet")) {
      assert(TargetName.forTarget(target) == TargetName("softstore-storelet"))
    }
  }

  test("matches") {
    // Test plan: Verify that matches compares only the target name, ignoring the target variant,
    // cluster, and instance ID. Do this by asserting that a TargetName matches targets of every
    // variant with the same name, and matches none with a different name.
    val targetName = TargetName("softstore-storelet")
    for (target: Target <- targetsWithName("softstore-storelet")) {
      assert(targetName.matches(target), s"$targetName should match $target")
    }
    for (target: Target <- targetsWithName("softstore-storelet-2")) {
      assert(!targetName.matches(target), s"$targetName should not match $target")
    }
  }
}
