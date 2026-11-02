package com.databricks.dicer.client
import com.databricks.dicer.external.Target
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.caching.util.WhereAmIHelper
import io.prometheus.client.{Counter, Gauge}
import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP

import java.net.URI
import com.databricks.dicer.client.SliceletMetrics.{locationInfoGauge, latestState}

/*
 * We export the following metrics related to the load observed on a Slicelet:
 *  - total attributed load: total load for keys that are assigned to this Slicelet.
 *  - unattributed load: load for keys that are not assigned to this Slicelet.
 *  - load increment calls: number of load-reporting calls, including nonpositive increments.
 *  - slice key handles created: number of SliceKeyHandles opened.
 *  - outstanding slice key handles: number of SliceKeyHandles opened but not yet closed.
 */
class SliceletMetrics(target: Target) {

  // Memoize the child counters and gauges for the given target.
  private val loadIncrementCallsChild: Counter.Child = SliceletMetrics.loadIncrementCalls
    .labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel
    )
  private val sliceKeyHandlesCreatedChild: Counter.Child = SliceletMetrics.sliceKeyHandlesCreated
    .labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel
    )
  private val sliceKeyHandlesOutstandingChild: Gauge.Child =
    SliceletMetrics.sliceKeyHandlesOutstanding
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel
      )
  private val attributedLoadCounterChild: Counter.Child = SliceletMetrics.attributedLoadCounter
    .labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel
    )
  private val unattributedLoadCounterChild: Counter.Child = SliceletMetrics.unattributedLoadCounter
    .labels(
      target.getTargetClusterLabel,
      target.getTargetNameLabel,
      target.getTargetInstanceIdLabel
    )

  /** Records one load-reporting call, regardless of the increment's value or key assignment. */
  def onLoadIncrement(): Unit = loadIncrementCallsChild.inc()

  /**
   * Records creation of a new handle by incrementing the
   * dicer_slicelet_slicekeyhandles_created_total counter metric and the
   * dicer_slicelet_slicekeyhandles_outstanding gauge metric.
   */
  def onSliceKeyHandleCreated(): Unit = {
    sliceKeyHandlesCreatedChild.inc()
    sliceKeyHandlesOutstandingChild.inc()
  }

  /**
   * Records destruction of a handle by decrementing the dicer_slicelet_slicekeyhandles_outstanding
   * gauge metric.
   */
  def onSliceKeyHandleClosed(): Unit = sliceKeyHandlesOutstandingChild.dec()

  /**
   * Increments attributed load by the given value in the dicer_slicelet_attributed_load_total
   * counter metric.
   */
  def incrementAttributedLoadBy(value: Int): Unit = attributedLoadCounterChild.inc(value)

  /**
   * Increments unattributed load by the given value in the dicer_slicelet_unattributed_load_total
   * counter metric.
   */
  def incrementUnattributedLoadBy(value: Int): Unit = unattributedLoadCounterChild.inc(value)

  /**
   * Records the location information for the Slicelet in the dicer_slicelet_location_info gauge
   * metric.
   */
  def recordLocationInfo(): Unit = {
    // Note that we use the ASCII string representation of the URI to align with our use of the
    // ASCII string representation for the cluster label in metrics (see
    // TargetHelper.getTargetClusterLabel).
    val whereAmIClusterUri: String =
      WhereAmIHelper.getClusterUri.map((_: URI).toASCIIString).getOrElse("")
    locationInfoGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        whereAmIClusterUri
      )
      .set(1)
  }

  /** Increments the counter for this Slicelet state. */
  def onHeartbeat(state: SliceletDataP.State): Unit = {
    latestState
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        state.toString
      )
      .inc()
  }
}

object SliceletMetrics {

  private val loadIncrementCalls = Counter
    .build()
    .name("dicer_slicelet_load_increment_calls_total")
    .help("The number of load increment calls, including nonpositive increments")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val sliceKeyHandlesCreated = Counter
    .build()
    .name("dicer_slicelet_slicekeyhandles_created_total")
    .help("The number of SliceKeyHandles opened")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  private val sliceKeyHandlesOutstanding = Gauge
    .build()
    .name("dicer_slicelet_slicekeyhandles_outstanding")
    .help("The number of SliceKeyHandles opened and not yet closed")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val attributedLoadCounter = Counter
    .build()
    .name("dicer_slicelet_attributed_load_total")
    .help("Per-resource total attributed load information")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  @SuppressWarnings(
    Array(
      "BadMethodCall-PrometheusCounterNamingConvention",
      "reason: Renaming existing prod metric would break dashboards and alerts"
    )
  )
  private val unattributedLoadCounter = Counter
    .build()
    .name("dicer_slicelet_unattributed_load_total")
    .help("Per-resource unattributed load information")
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  private val latestState = Counter
    .build()
    .name("dicer_slicelet_current_state_total")
    .help("Records how many times a Slicelet has reported this state as its current state.")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "state")
    .register()

  /**
   * Temporary metric to check the correctness of the cluster location information from WhereAmI (if
   * available). We want to check the correctness of this information before we start populating the
   * cluster URI in Target identifiers for control plane Slicelets because if for whatever reason it
   * were incorrect, then those control plane Slicelets would suddenly appear to Dicer as remote
   * Slicelets.
   */
  private val locationInfoGauge = Gauge
    .build()
    .name("dicer_slicelet_location_info")
    .help("Records the location info for the Slicelet provided through WhereAmI, if available.")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "whereAmIClusterUri")
    .register()

  object forTest {

    /** Clears the total attributed load recorded in the Prometheus metrics for all targets. */
    def clearAttributedLoadMetric(): Unit = {
      attributedLoadCounter.clear()
    }

    /** Clears the unattributed load recorded in the Prometheus metrics for all targets. */
    def clearUnattributedLoadMetric(): Unit = {
      unattributedLoadCounter.clear()
    }
  }
}
