package com.databricks.dicer.client.featurerollouts

import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutScopeTestDataP
import com.databricks.api.proto.dicer.client.featurerollouts.test.DicerClientFeatureRolloutScopeTestDataP.{
  InvalidScopeTestCaseP,
  ValidScopeTestCaseP
}
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutScopeSuite extends DatabricksTest {

  private val TEST_DATA: DicerClientFeatureRolloutScopeTestDataP =
    TestUtils.loadTestData[DicerClientFeatureRolloutScopeTestDataP](
      "dicer/client/feature-rollouts/test/data/scope_test_data.textproto"
    )

  namedGridTest("DicerClientFeatureRolloutScope fromProto accepts valid scope protos")(
    TEST_DATA.validScopeTestCases.map((c: ValidScopeTestCaseP) => c.getDescription -> c).toMap
  ) { testCase: ValidScopeTestCaseP =>
    // Test plan: Verify that fromProto accepts every valid scope proto from the shared textproto.
    // Do this by iterating over each `valid_scope_test_cases` entry, calling fromProto, and
    // asserting the returned scope's regionUri matches the input proto's regionUri.
    val scope: DicerClientFeatureRolloutScope =
      DicerClientFeatureRolloutScope.fromProto(testCase.getScopeProto)
    assert(scope.regionUri == testCase.getScopeProto.getRegionUri)
  }

  namedGridTest("DicerClientFeatureRolloutScope fromProto rejects invalid scope protos")(
    TEST_DATA.invalidScopeTestCases.map((c: InvalidScopeTestCaseP) => c.getDescription -> c).toMap
  ) { testCase: InvalidScopeTestCaseP =>
    // Test plan: Verify that fromProto rejects every invalid scope proto from the shared
    // textproto. Do this by iterating over each `invalid_scope_test_cases` entry and asserting
    // fromProto throws IllegalArgumentException whose message contains the expected substring.
    assertThrow[IllegalArgumentException](testCase.getExpectedErrorSubstring) {
      DicerClientFeatureRolloutScope.fromProto(testCase.getScopeProto)
    }
  }
}
