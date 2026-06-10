package com.databricks.dicer.assigner

import com.databricks.caching.util.{Cancellable, ValueStreamCallback, WatchValueCell}
import com.databricks.dicer.common.{Generation, Incarnation}
import com.databricks.caching.util.UnixTimeVersion

import scala.concurrent.Future

/**
 * A driver that is used when the preferred assigner mode is disabled. With no state machine
 * created, it always returns a [[PreferredAssignerValue.ModeDisabled]] value. This design is
 * to keep the behavior under the disabled preferred assigner mode as simple as possible.
 */
class DisabledPreferredAssignerDriver(storeIncarnation: Incarnation)
    extends PreferredAssignerDriver {
  require(
    storeIncarnation.isLoose,
    s"Store incarnation must be loose when the preferred assigner feature is disabled: " +
    s"($storeIncarnation)"
  )

  /** The constant disabled preferred assigner value. */
  private val DISABLED_PREFERRED_ASSIGNER_VALUE: PreferredAssignerValue =
    PreferredAssignerValue.ModeDisabled(
      Generation(incarnation = storeIncarnation, UnixTimeVersion.MIN)
    )

  private val preferredAssignerWatchCell: WatchValueCell[PreferredAssignerConfig] =
    new WatchValueCell[PreferredAssignerConfig]()

  /** Sets the preferred assigner role gauge to be `PREFERRED_BECAUSE_PA_DISABLED`. */
  PreferredAssignerMetrics.setAssignerRoleGauge(
    PreferredAssignerMetrics.MonitoredAssignerRole.PREFERRED_BECAUSE_PA_DISABLED
  )

  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit = {
    // In disabled mode, we always act as the preferred.
    // We don't need the logger since this driver does nothing.
    preferredAssignerWatchCell.setValue(
      PreferredAssignerConfig.create(
        preferredAssignerValue = DISABLED_PREFERRED_ASSIGNER_VALUE,
        currentAssignerInfo = assignerInfo
      )
    )
  }

  override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable = {
    preferredAssignerWatchCell.watch(callback)
  }

  override def sendTerminationNotice(): Unit = {
    // Do nothing because we don't have a state machine instance.
  }

  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] = {
    val heartbeatResponse = HeartbeatResponse(request.opId, DISABLED_PREFERRED_ASSIGNER_VALUE)
    Future.successful(heartbeatResponse)
  }

  // No eligibility factors; the driver is always eligible.
  override private[assigner] def selectionEligibilityWatchCell: WatchValueCell.Consumer[Boolean] =
    PreferredAssignerDriver.ALWAYS_ELIGIBLE
}
