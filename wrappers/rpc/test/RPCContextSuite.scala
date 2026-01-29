package com.databricks.rpc

import com.databricks.testing.DatabricksTest
import com.databricks.common.serviceidentity.ServiceIdentity

class RPCContextSuite extends DatabricksTest {

  test("RPCContext.getCallerIdentity always returns None") {
    // Test plan: verify that the caller identity returned by the RPCContext is always none.
    val rpcContext = RPCContext()
    val callerIdentity: Option[ServiceIdentity] = rpcContext.getCallerIdentity
    assert(callerIdentity.isEmpty)
  }
}
