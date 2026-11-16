package com.databricks.testing

/** Used to check if the code is running under an unit test. */
object TestEnvironment {

  /** True when `BAZEL_TEST=1`, which Bazel sets in the test process's environment. */
  final val IS_TEST: Boolean = System.getenv("BAZEL_TEST") == "1"
}
