package com.databricks.rpc.armeria

/**
 * OSS wrapper for ReadinessProbeTracker.
 *
 * In the internal Databricks version, this tracks pod readiness via Kubernetes probes. In the OSS
 * version, we provide a stub implementation that can be controlled via testing APIs.
 */
object ReadinessProbeTracker {

  /**
   * Mutable state for testing. In the internal version, this would be controlled by actual pod
   * readiness probes.
   */
  @volatile private var podReady: Boolean = true

  /**
   * Returns whether the pod is ready.
   *
   * In OSS, this defaults to always return true.
   */
  def isPodReady: Boolean = podReady

  /**
   * Testing API: Resets the tracker to its initial state (ready).
   *
   * This is used by tests to ensure a clean state between test cases.
   */
  def resetForTesting(): Unit = {
    podReady = true
  }

  /**
   * Testing API: Updates the pod readiness status.
   *
   * @param status
   *   The probe status. In the OSS version, we only check if the status code is 200 (OK) to
   *   determine readiness.
   */
  def updatePodStatusForTesting(status: Int): Unit = {
    podReady = status == 200
  }
}

/**
 * Common probe statuses.
 */
object ProbeStatuses {
  val OK_STATUS: Int = 200
  val NOT_YET_READY_STATUS: Int = 418
}
