package com.databricks.dicer.assigner.config

import com.databricks.dicer.common.ClientType
import com.databricks.dicer.external.Target
import com.databricks.rpc.testing.JettyTestRPCContext
import com.databricks.testing.DatabricksTest
import com.google.protobuf.any.{Any => ProtoAny}

class AuthorizerHelperSuite extends DatabricksTest {

  test("AuthorizerHelper.fromAnyProto always returns DEFAULT_AUTHORIZER") {
    // Test plan: Verify that fromAnyProto always returns the default authorizer, when passed None
    // or an empty proto.
    val noneResult: Authorizer = AuthorizerHelper.fromAnyProto(authorizerOpt = None)
    assertResult(AuthorizerHelper.DEFAULT_AUTHORIZER)(noneResult)

    val someResult: Authorizer = AuthorizerHelper.fromAnyProto(Some(ProtoAny()))
    assertResult(AuthorizerHelper.DEFAULT_AUTHORIZER)(someResult)
  }

  test("AuthorizerHelper.DEFAULT_AUTHORIZER is a no-op authorizer") {
    // Test plan: Verify that the default authorizer never rejects. Do this by passing a Kubernetes
    // target and no headers, and asserting no exception is thrown.
    AuthorizerHelper.DEFAULT_AUTHORIZER.checkAuthorized(
      target = Target("kubernetes-target"),
      rpcContext = JettyTestRPCContext.builder().build(),
      clientType = ClientType.Slicelet,
      trustedWatchAnyTargetServices = Set.empty
    )
  }

  test("AuthorizerHelper.toAnyProto always returns None") {
    // Test plan: Verify that toAnyProto always returns None. Do this by calling toAnyProto with the
    // default authorizer and asserting it returns None.
    val result: Option[ProtoAny] = AuthorizerHelper.toAnyProto(AuthorizerHelper.DEFAULT_AUTHORIZER)
    assertResult(None)(result)
  }
}
