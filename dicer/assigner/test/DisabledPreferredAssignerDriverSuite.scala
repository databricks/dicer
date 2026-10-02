package com.databricks.dicer.assigner

import com.databricks.dicer.assigner.testing.{PreferredAssignerTestUtils}

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.{
  Cancellable,
  LoggingStreamCallback,
  SequentialExecutionContext,
  TestUtils
}
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.testing.DatabricksTest

import scala.concurrent.duration.Duration

class DisabledPreferredAssignerDriverSuite extends DatabricksTest {

  private val ASSIGNER_INFO = AssignerInfo(
    uuid = java.util.UUID.randomUUID(),
    uri = new java.net.URI("http://localhost:1212")
  )

  /** The sequential executor for the suite. */
  private val sec = SequentialExecutionContext.createWithDedicatedPool(
    name = this.getClass.getName,
    alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
  )

  test("DisabledPreferredAssignerDriver exposes no consistent-hashing state") {
    // Test plan: Verify the disabled driver reports no consistent-hashing snapshot, so the Assigner
    // debug page shows the consistent-hashing section as inactive for the non-CH driver.
    val driver: PreferredAssignerDriver =
      new DisabledPreferredAssignerDriver
    val stateOpt: Option[ConsistentHashingState] =
      TestUtils.awaitResult(driver.consistentHashingStateView, Duration.Inf)
    assertResult(None)(stateOpt)
  }

  test("DisabledPreferredAssignerDriver should always return ModeDisabled") {
    // Test plan: verify that no matter what methods are called and how many times they are called,
    // the DisabledPreferredAssignerDriver should always return ModeDisabled for the preferred
    // assigner value.
    // Additionally, verify that it sets the assigner role gauge to PREFERRED_BECAUSE_PA_DISABLED.
    val driver: PreferredAssignerDriver =
      new DisabledPreferredAssignerDriver

    driver.start(ASSIGNER_INFO, AssignerProtoLogger.createNoop(sec))

    val expectedPreferredAssignerValue: PreferredAssignerValue.ModeDisabled =
      PreferredAssignerValue.ModeDisabled(Generation.EMPTY)

    PreferredAssignerTestUtils.assertAssignerRoleGaugeMatches(
      PreferredAssignerMetrics.MonitoredAssignerRole.PREFERRED_BECAUSE_PA_DISABLED
    )

    // Use some arbitrary values for opId and incarnation.
    for (tuple <- Seq((39L, 1L), (40L, 2L), (41L, 3L))) {
      val (opId, incarnation): (Long, Long) = tuple
      // Check the initial preferred assigner.
      assert(
        PreferredAssignerTestUtils
          .getLatestKnownPreferredAssignerBlocking(driver, sec) == expectedPreferredAssignerValue
      )

      val arbitraryPreferredAssignerValue = PreferredAssignerValue.SomeAssigner(
        AssignerInfo(
          uuid = java.util.UUID.randomUUID(),
          uri = new java.net.URI("http://localhost:34215")
        ),
        Generation(Incarnation(incarnation), number = 1L)
      )

      // Send heartbeat request with an arbitrary preferred assigner value.
      val heartbeatResponse: HeartbeatResponse = TestUtils.awaitResult(
        driver.handleHeartbeatRequest(
          HeartbeatRequest(opId, arbitraryPreferredAssignerValue)
        ),
        Duration.Inf
      )

      // Verify that the response contains the expected preferred assigner value and the same opId.
      assert(heartbeatResponse.opId == opId)
      assert(heartbeatResponse.preferredAssignerValue == expectedPreferredAssignerValue)

      // Check the preferred assigner after the heartbeat request.
      assert(
        PreferredAssignerTestUtils
          .getLatestKnownPreferredAssignerBlocking(driver, sec) == expectedPreferredAssignerValue
      )

      // Verify that the `watch` has no effect on the disabled preferred assigner value.
      val callback1 = new LoggingStreamCallback[PreferredAssignerConfig](sec)
      val callback2 = new LoggingStreamCallback[PreferredAssignerConfig](sec)
      val cancellable1: Cancellable = driver.watch(callback1)
      val cancellable2: Cancellable = driver.watch(callback2)

      // Check the preferred assigner after the watch.
      assert(
        PreferredAssignerTestUtils
          .getLatestKnownPreferredAssignerBlocking(driver, sec) == expectedPreferredAssignerValue
      )
      // Cancel the callbacks and check the preferred assigner.
      cancellable1.cancel()
      cancellable2.cancel()

      // Check the preferred assigner after the cancellations.
      assert(
        PreferredAssignerTestUtils
          .getLatestKnownPreferredAssignerBlocking(driver, sec) == expectedPreferredAssignerValue
      )

      // Send termination notice.
      driver.sendTerminationNotice()
      // Check the preferred assigner after the termination notice.
      assert(
        PreferredAssignerTestUtils
          .getLatestKnownPreferredAssignerBlocking(driver, sec) == expectedPreferredAssignerValue
      )
      PreferredAssignerTestUtils.assertAssignerRoleGaugeMatches(
        PreferredAssignerMetrics.MonitoredAssignerRole.PREFERRED_BECAUSE_PA_DISABLED
      )
    }
  }

}
