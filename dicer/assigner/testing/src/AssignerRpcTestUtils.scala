package com.databricks.dicer.assigner.testing

import io.grpc.{StatusException, Status}

object AssignerRpcTestUtils {

  /** Creates an exception with the ABORTED status code. */
  def createAbortedStatusException(message: String): Exception = {
    new StatusException(Status.ABORTED.withDescription(message))
  }

}
