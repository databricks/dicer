package com.databricks.caching.util
import com.databricks.caching.util.TestUtils.assertStatusEqual
import com.databricks.testing.DatabricksTest
import io.grpc.{Status, StatusException, StatusRuntimeException}

/**
 * This suite includes tests that can be shared between OSS and internal versions. See
 * [[StatusUtilsSuite]] for tests that are specific to the internal version.
 */
class StatusUtilsCommonSuite extends DatabricksTest {

  test("convertExceptionToStatus creates appropriate Status") {
    // Test plan: Pass `StatusException`, `StatusRuntimeException`, `IllegalArgumentException` and
    // some other exception to `convertExceptionToStatus`. Verify that they get converted to an
    // appropriate `Status`.

    // StatusException gets converted properly.
    var status = Status.FAILED_PRECONDITION.withDescription("foo")
    var converted = StatusUtils.convertExceptionToStatus(new StatusException(status))
    assertStatusEqual(status, converted)

    // StatusRuntimeException gets converted properly.
    status = Status.ABORTED.withDescription("bar")
    converted = StatusUtils.convertExceptionToStatus(new StatusRuntimeException(status))
    assertStatusEqual(status, converted)

    // IllegalArgumentException gets converted properly.
    var ex: Throwable = new IllegalArgumentException("test")
    converted = StatusUtils.convertExceptionToStatus(ex)
    assertStatusEqual(
      converted,
      Status.INVALID_ARGUMENT.withDescription("test").withCause(ex)
    )

    // Arbitrary Throwable gets converted properly.
    ex = new RuntimeException("catch me if you can")
    converted = StatusUtils.convertExceptionToStatus(ex)
    assertStatusEqual(
      converted,
      Status.UNKNOWN
        .withDescription("java.lang.RuntimeException: catch me if you can")
        .withCause(ex)
    )
  }

}
