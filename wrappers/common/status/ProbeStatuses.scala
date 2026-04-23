package com.databricks.common.status

/** Result of a readiness probe check: an HTTP status code and a human-readable message. */
case class ProbeStatus(code: Int, content: String)

/** Reports readiness probe status, exposed by InfoService via the `/ready` endpoint. */
trait ProbeStatusSource {
  def getStatus: ProbeStatus
}

/**
 * OSS wrapper for ProbeStatuses.
 *
 * Re-exports ProbeStatuses from the ReadinessProbeTracker wrapper to maintain the same import
 * path as the internal version.
 */
object ProbeStatuses {
  val OK_STATUS: Int =
    com.databricks.rpc.armeria.ProbeStatuses.OK_STATUS
  val NOT_YET_READY_STATUS: Int =
    com.databricks.rpc.armeria.ProbeStatuses.NOT_YET_READY_STATUS

  def notYetReady(serviceName: String): ProbeStatus =
    ProbeStatus(NOT_YET_READY_STATUS, s"The $serviceName has not yet been initialized")

  def ok(serviceName: String): ProbeStatus =
    ProbeStatus(OK_STATUS, s"$serviceName is available")
}
