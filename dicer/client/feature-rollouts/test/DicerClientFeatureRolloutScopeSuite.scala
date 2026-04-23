package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutScopeP
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutScopeSuite extends DatabricksTest {

  test("DicerClientFeatureRolloutScope fromProto with valid region URI") {
    // Test plan: Verify that fromProto produces the correct scope when given a valid proto with a
    // region URI. Do this by constructing a proto with a known valid region URI and asserting the
    // resulting scope has the expected regionUri field.
    val proto: DicerClientFeatureRolloutScopeP =
      DicerClientFeatureRolloutScopeP(regionUri = Some("region:prod/cloud1/public/region2"))
    val scope: DicerClientFeatureRolloutScope = DicerClientFeatureRolloutScope.fromProto(proto)
    assert(scope.regionUri == "region:prod/cloud1/public/region2")
  }

  test("DicerClientFeatureRolloutScope fromProto throws when regionUri is absent") {
    // Test plan: Verify that fromProto throws IllegalArgumentException when the regionUri field
    // is not set. Do this by calling fromProto with an empty proto and asserting on the exception.
    assertThrow[IllegalArgumentException]("regionUri must be set") {
      DicerClientFeatureRolloutScope.fromProto(DicerClientFeatureRolloutScopeP())
    }
  }

  gridTest("DicerClientFeatureRolloutScope throws for invalid region URIs")(
    Seq(
      // Strings that bear no resemblance to a region URI.
      "",
      "hello",
      "foo bar",
      "123",
      // Wrong scheme (not "region:").
      "kubernetes-cluster:prod/cloud1/public/region2/clustertype2/01",
      // Right scheme, wrong path structure (not exactly four non-empty segments).
      "region:",
      "region:prod/cloud1/public",
      "region:prod/cloud1/public/region2/clustertype2/01",
      "region:prod//public/region2"
    )
  ) { regionUri: String =>
    // Test plan: Verify that constructing a DicerClientFeatureRolloutScope with a regionUri that
    // does not match the expected format throws IllegalArgumentException. Do this by calling
    // fromProto with each invalid URI and asserting on the exception message.
    val proto: DicerClientFeatureRolloutScopeP =
      DicerClientFeatureRolloutScopeP(regionUri = Some(regionUri))
    assertThrow[IllegalArgumentException]("regionUri must match format") {
      DicerClientFeatureRolloutScope.fromProto(proto)
    }
  }
}
