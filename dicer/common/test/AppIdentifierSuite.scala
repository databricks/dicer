package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.testing.AppIdentifierTestUtils
import com.databricks.testing.DatabricksTest

/** Tests for [[AppIdentifier]]. */
class AppIdentifierSuite extends DatabricksTest {

  // TODO(<internal bug>): AppIdentifierTestUtils configures the app identifier by replacing the
  // process-wide AppConf singleton, so each test case here mutates state shared by the whole
  // process. These test cases are therefore only correct when they do not run concurrently: two
  // cases configuring different app identifiers at the same time would observe each other's
  // values. Once AppConf supports scoped test utilities, each case can configure its own app
  // identifier in isolation and this constraint goes away.

  override def afterEach(): Unit = {
    try {
      // Clear the process-wide app identifier to avoid leaking the configured app identifier into
      // other suites running in this process.
      AppIdentifierTestUtils.clearForTest()
    } finally {
      super.afterEach()
    }
  }

  /**
   * Configures the process-wide app identifier source with `name` and `instanceId` and returns the
   * resulting [[AppIdentifier]].
   *
   * Each call returns a distinct instance, which is how a test obtains two identifiers to compare
   * given that [[AppIdentifier.getFromEnv]] is the only way to obtain one.
   */
  private def getIdentifierFromEnv(name: String, instanceId: String): AppIdentifier = {
    AppIdentifierTestUtils.configureForTest(name = name, instanceId = instanceId)
    AppIdentifier.getFromEnv match {
      case Some(appIdentifier: AppIdentifier) => appIdentifier
      case None => fail(s"getFromEnv returned no identifier for $name / $instanceId")
    }
  }

  test("getFromEnv returns the app identifier configured for the process") {
    // Test plan: Verify that getFromEnv returns the app name and app instance configured for the
    // process. Do this by configuring both and confirming the returned identifier carries them.

    // Setup: Configure both an app name and an app instance for the process.
    val appIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")

    // Verify: The returned identifier carries the configured values.
    assertResult("test-app")(appIdentifier.name)
    assertResult("test-instance")(appIdentifier.instanceId)
  }

  test("getFromEnv returns None when neither the app name nor the app instance is configured") {
    // Test plan: Verify that getFromEnv returns None when the process has no app identifier at all,
    // as is the case for an unassigned warmpool pod or a process without the app-metadata mount. Do
    // this by clearing the process-wide app identifier.

    // Setup: Clear the process-wide app identifier.
    AppIdentifierTestUtils.clearForTest()

    // Verify: getFromEnv yields no identifier.
    assertResult(None)(AppIdentifier.getFromEnv)
  }

  test("getFromEnv throws when the configured app name or instance is invalid") {
    // Test plan: Verify that an app name or instance violating the identity constraints surfaces
    // as a thrown IllegalArgumentException. Do this by configuring a name containing uppercase
    // characters and '_', both of which the constraints reject.

    // Setup: Configure an app name that fails validation.
    AppIdentifierTestUtils.configureForTest(
      name = "Invalid_Name",
      instanceId = "test-instance"
    )

    // Verify: Reading the identifier throws.
    assertThrow[IllegalArgumentException]("Invalid app identifier 'Invalid_Name'") {
      AppIdentifier.getFromEnv
    }

    // Setup: Configure an app instance that fails validation.
    AppIdentifierTestUtils.configureForTest(
      name = "test-app",
      instanceId = "Invalid_Instance"
    )

    // Verify: Reading the identifier throws.
    assertThrow[IllegalArgumentException]("Invalid app instance identifier 'Invalid_Instance'") {
      AppIdentifier.getFromEnv
    }
  }

  test("identifiers with the same app name and app instance are equal and share a hash code") {
    // Test plan: Verify that equality is structural rather than by reference, which matters because
    // AppIdentifier is not a case class and so defines equals and hashCode by hand. Do this by
    // obtaining two separately created identifiers for the same app name and app instance.
    val appIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")
    val sameAppIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")

    // Verify: The two identifiers are distinct objects that compare equal and hash alike.
    assert(!appIdentifier.eq(sameAppIdentifier), "expected two distinct instances")
    assertResult(sameAppIdentifier)(appIdentifier)
    assertResult(sameAppIdentifier.hashCode())(appIdentifier.hashCode())
  }

  test("identifiers differing in app name or app instance are unequal") {
    // Test plan: Verify that equality considers both fields, so that identifiers differing in
    // either one do not compare equal. Do this by comparing an identifier against one that differs
    // only in its app name and one that differs only in its app instance.
    val appIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")
    val differentName: AppIdentifier = getIdentifierFromEnv("other-app", "test-instance")
    val differentInstanceId: AppIdentifier = getIdentifierFromEnv("test-app", "other-instance")

    // Verify: Neither identifier compares equal to the original.
    assert(appIdentifier != differentName, "identifiers with different app names must be unequal")
    assert(
      appIdentifier != differentInstanceId,
      "identifiers with different app instances must be unequal"
    )
  }

  test("an identifier is unequal to a value that is not an app identifier") {
    // Test plan: Verify that equals rejects a value of an unrelated type rather than throwing. Do
    // this by comparing an identifier against a string.
    val appIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")

    // Verify: The identifier does not equal an unrelated value.
    assert(!appIdentifier.equals("test-app"), "an identifier must not equal a String")
  }

  test("toString includes the app name and the app instance") {
    // Test plan: Verify that the string form names both parts of the identifier, since it is what
    // appears in logs. Do this by rendering a configured identifier.
    val appIdentifier: AppIdentifier = getIdentifierFromEnv("test-app", "test-instance")

    // Verify: Both configured values appear in the rendered identifier.
    assertResult("AppIdentifier(test-app, test-instance)")(appIdentifier.toString)
  }
}
