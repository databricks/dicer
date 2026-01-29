package com.databricks.dicer.assigner

import io.grpc.{StatusException, Status}

object AssignerRpcTestHelper {

  /** Creates an exception with the ABORTED status code. */
  def createAbortedStatusException(message: String): Exception = {
    new StatusException(Status.ABORTED.withDescription(message))
  }

}
