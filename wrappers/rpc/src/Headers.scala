package com.databricks.common.http

/** Wrapper object providing a minimal set of header constants for the open source version. */
object Headers {

  /** Header that defines the app that is sending the request. */
  val HEADER_DATABRICKS_APP_SPEC_NAME = "X-Databricks-App-Spec-Name"

  /** Header that defines the app instance id that is sending the request. */
  val HEADER_DATABRICKS_APP_INSTANCE_ID = "X-Databricks-App-Instance-Id"

  /** Header carrying the base64-encoded secondary slice key for Dicer two-level sharding. */
  val HEADER_DICER_SECONDARY_SLICE_KEY = "x-databricks-internal-dicer-secondary-slice-key"
}
