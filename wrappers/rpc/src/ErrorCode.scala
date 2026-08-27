package com.databricks

sealed trait ErrorCode {
  def name: String = toString
}

/** Provides a minimal set of error codes used in Dicer. */
object ErrorCode {

  /**
   * Unknown error. This error generally should not be returned explicitly, but may be used as a
   * fallback in some cases.
   */
  case object UNKNOWN extends ErrorCode

  /**
   * Operation was performed on a resource that does not exist, e.g. a file or directory was not
   * found.
   */
  case object NOT_FOUND extends ErrorCode

  /**
   * Internal error. This means that some invariants expected by the underlying system have been
   * broken. This error code is reserved for serious errors, which generally cannot be resolved by
   * the user.
   */
  case object INTERNAL_ERROR extends ErrorCode

  /**
   * The operation is rejected because of request rate limit, for example rate limiting applied to
   * users, workspaces, IP addresses, etc.
   */
  case object REQUEST_LIMIT_EXCEEDED extends ErrorCode

  /**
   * The service is currently unavailable. This is most likely a transient condition, which can be
   * corrected by retrying with a backoff. Note that it is not always safe to retry non-idempotent
   * operations.
   */
  case object TEMPORARILY_UNAVAILABLE extends ErrorCode
}
