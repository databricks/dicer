package com.databricks.rpc

/**
 * Test utils for RPC framework.
 */
object RPCTestUtils {

  /**
   * Returns an [[RPCContext]] that can be used in unit tests.
   */
  def testRPCContext(): RPCContext = RPCContext()
}
