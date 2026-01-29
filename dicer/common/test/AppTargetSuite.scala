package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.TargetP
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.test.TargetTestDataP
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Target}
import com.databricks.testing.DatabricksTest

private class AppTargetSuite extends DatabricksTest {

  private val TEST_DATA: TargetTestDataP =
    loadTestData[TargetTestDataP]("dicer/common/test/data/target_test_data.textproto")

  /** A valid identifier that has the longest permitted length. */
  private val LONGEST_APP_NAME: String = TEST_DATA.validAppTargetNames.last
  assert(LONGEST_APP_NAME.length == 42)

  /** A valid instance ID that has the longest permitted length. */
  private val LONGEST_INSTANCE_ID: String = TEST_DATA.validInstanceIds.last
  assert(LONGEST_INSTANCE_ID.length == 63)

  private val INSTANCE_IDS: Seq[String] = TEST_DATA.validInstanceIds

  /** Valid target permutations constructed using `TEST_DATA.validTargetNames`. */
  private val VALID_TARGETS: Seq[AppTarget] = {
    for {
      instanceId: String <- INSTANCE_IDS
      name: String <- TEST_DATA.validAppTargetNames
      appTarget <- Target.createAppTarget(name, instanceId) match {
        case _: KubernetesTarget =>
          throw new IllegalStateException("Target.createAppTarget returns unexpected type")
        case appTarget: AppTarget => Some(appTarget)
      }
    } yield appTarget
  }

  /** An invalid identifier that exceeds the length limit. */
  private val TOO_LONG_APP_NAME = TEST_DATA.invalidAppTargetNames.last
  assert(TOO_LONG_APP_NAME.length == 43)

  test("Valid names") {
    // Test plan: verify that we're able to construct AppTarget using valid names (i.e. names that
    // are valid DNS labels) and valid instance ids.
    for (validName: String <- TEST_DATA.validAppTargetNames) {
      val target = Target.createAppTarget(validName, "a123")
      assert(target.name == validName)
    }
  }

  test("Invalid names") {
    // Test plan: verify that `Target.createAppTarget(invalidName, _)` throws an
    // `IllegalArgumentException`.
    for (invalidName: String <- TEST_DATA.invalidAppTargetNames) {
      assertThrow[IllegalArgumentException]("Name is invalid") {
        Target.createAppTarget(invalidName, "a123")
      }
    }
  }

  test("Valid instance IDs") {
    // Test plan: verify that we're able to construct AppTarget using valid instance IDs that
    // match the requirements for App Identifiers.
    for (validInstanceId: String <- TEST_DATA.validInstanceIds) {
      val target = Target.createAppTarget("name", validInstanceId)
      target match {
        case _: KubernetesTarget =>
          throw new IllegalStateException("Unexpected target type in VALID_TARGETS")
        case appTarget: AppTarget =>
          assert(appTarget.instanceId == validInstanceId)
      }
    }
  }

  test("Invalid instance IDs") {
    // Test plan: verify that `Target.createAppTarget(_, invalidInstanceId)` throws an
    // `IllegalArgumentException`.
    for (invalidInstanceId: String <- TEST_DATA.invalidInstanceIds) {
      assertThrow[IllegalArgumentException]("Instance ID is invalid") {
        Target.createAppTarget("name", invalidInstanceId)
      }
    }
  }

  test("toString") {
    // Test plan: verify that the string representation of the app target is of the form
    // `appTarget:name:instanceId`.
    assert(Target.createAppTarget("name", "instance-id").toString == "appTarget:name:instance-id")
  }

  test("Parse") {
    // Test plan: verify that AppTarget object round-trip via TargetHelper.parse and
    // Target.toParseableDescription.
    for (target: AppTarget <- VALID_TARGETS) {
      assert(TargetHelper.parse(target.toParseableDescription) == target)
    }
  }

  test("Proto conversion") {
    // Test plan: verify that AppTarget object round-trip via TargetHelper.fromProto and
    // Target.toProto.
    for (target: AppTarget <- VALID_TARGETS) {
      assert(TargetHelper.fromProto(target.toProto) == target)
    }
  }

  test("Valid proto") {
    // Test plan: Verify that a proto with `type = APP` that is valid does not throw an exception
    // when unmarshalled.

    // Verify: Parsing proto with `targetType = APP` succeeds with valid fields.
    val target1: Target = TargetHelper.fromProto(
      TargetP(
        name = Some("name"),
        targetType = Some(TargetP.Type.APP),
        instanceId = Some("instance-id")
      )
    )
    target1 match {
      case _: KubernetesTarget =>
        throw new IllegalStateException("TargetHelper.fromProto returned unexpected type")
      case appTarget: AppTarget =>
        assert(appTarget.name == "name")
        assert(appTarget.instanceId == "instance-id")
    }

    // Verify: Parsing proto with `targetType = APP` ignores `kubernetesClusterUri`.
    val target2: Target = TargetHelper.fromProto(
      TargetP(
        name = Some("name"),
        kubernetesClusterUri = Some("kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"),
        targetType = Some(TargetP.Type.APP),
        instanceId = Some("instance-id")
      )
    )
    target2 match {
      case _: KubernetesTarget =>
        throw new IllegalStateException("TargetHelper.fromProto returned unexpected type")
      case appTarget: AppTarget =>
        assert(appTarget.name == "name")
        assert(appTarget.instanceId == "instance-id")
    }
  }

  test("Invalid proto") {
    // Test plan: Verify that a proto with `type = APP` that is invalid throws an exception when
    // unmarshalled.

    {
      val proto: TargetP =
        TargetP(name = Some("name"), targetType = Some(TargetP.Type.APP), instanceId = None)
      assertThrow[IllegalArgumentException]("instanceId must be defined for AppTarget") {
        TargetHelper.fromProto(proto)
      }
    }

    {
      val proto: TargetP =
        TargetP(name = Some("name"), targetType = Some(TargetP.Type.APP), instanceId = Some(""))
      assertThrow[IllegalArgumentException]("Instance ID is invalid") {
        TargetHelper.fromProto(proto)
      }
    }

    {
      val proto: TargetP =
        TargetP(
          name = Some("name"),
          targetType = Some(TargetP.Type.APP),
          instanceId = Some("12345")
        )
      assertThrow[IllegalArgumentException]("Instance ID is invalid") {
        TargetHelper.fromProto(proto)
      }
    }
  }

  test("equality") {
    // Test plan: verify that app target equality and hash codes are based on name and instance id.
    TestUtils.checkEquality(
      groups = VALID_TARGETS.zipWithIndex
        .map {
          case (target: AppTarget, _: Int) =>
            Seq(target, Target.createAppTarget(target.name, target.instanceId))
        }
    )
  }

  test("metric labels") {
    // Test plan: verify that the expected metric label values are returned for app targets.
    val target = Target.createAppTarget("foobar", "app-1234")
    assert(target.getTargetNameLabel == "foobar")
    assert(target.getKubernetesAppName == "foobar")
    assert(target.getTargetClusterLabel == "")
    assert(target.getTargetInstanceIdLabel == "app-1234")
  }
}
