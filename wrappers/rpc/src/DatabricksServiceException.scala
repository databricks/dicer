package com.databricks.api.base

import com.databricks.ErrorCode

/** A simple exception that carries an [[ErrorCode]]. */
case class DatabricksServiceException(
    errorCode: ErrorCode,
    message: String,
    cause: Throwable = null
) extends RuntimeException(message, cause) {

  /** Gets the error code. */
  def getErrorCode: ErrorCode = errorCode
}

object DatabricksServiceException {
  def apply(errorCode: ErrorCode, message: String): DatabricksServiceException =
    new DatabricksServiceException(errorCode, message)
}
