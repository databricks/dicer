package com.databricks.dicer.common
import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.MetricUtils
import com.databricks.dicer.common.SubscriberHandler.{Location, MetricsKey}
import com.databricks.dicer.common.SubscriberHandlerMetrics.TargetMatchedLabels
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target

object SubscriberHandlerMetricUtils {

  /** The default metrics collector registry. */
  private val registry = CollectorRegistry.defaultRegistry

  /** Gets the number of Clerks for the `target` handled by `location` at `version`. */
  def getNumClerksByHandler(location: Location, target: Target, version: Long): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_subscriber_num_subscribers",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "type" -> "Clerk",
          "version" -> version.toString,
          "handlerLocation" -> location.toString
        )
      )
      .toLong
  }

  /** Gets the number of Slicelets for the `target` handled by location` at `version`. */
  def getNumSliceletsByHandler(location: Location, target: Target, version: Long): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_subscriber_num_subscribers",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "type" -> "Slicelet",
          "version" -> version.toString,
          "handlerLocation" -> location.toString
        )
      )
      .toLong
  }

  /** Gets the number of watch requests received by the subscriber handler. */
  def getNumWatchRequests(
      handlerTarget: Target,
      requestTarget: Target,
      callerService: String,
      metricsKey: MetricsKey,
      handlerLocation: Location): Long = {
    val matchedLabels: TargetMatchedLabels =
      SubscriberHandlerMetrics.getWatchRequestTargetMatchedLabels(handlerTarget, requestTarget)
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_watch_requests_total",
        Map(
          "handlerTargetCluster" -> handlerTarget.getTargetClusterLabel,
          "handlerTargetName" -> handlerTarget.getTargetNameLabel,
          "handlerTargetInstanceId" -> handlerTarget.getTargetInstanceIdLabel,
          "requestTargetCluster" -> requestTarget.getTargetClusterLabel,
          "requestTargetName" -> requestTarget.getTargetNameLabel,
          "requestTargetInstanceId" -> requestTarget.getTargetInstanceIdLabel,
          "callerService" -> callerService,
          "type" -> metricsKey.typeLabel,
          "version" -> metricsKey.versionLabel,
          "handlerLocation" -> handlerLocation.toString,
          "matchedName" -> matchedLabels.matchedName,
          "matchedCluster" -> matchedLabels.matchedCluster,
          "matchedInstanceId" -> matchedLabels.matchedInstanceId
        )
      )
      .toLong
  }

  /** Gets the number of watch requests that were load shed. */
  def getNumWatchRequestsLoadShed(target: Target): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_watch_requests_load_shed_total",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toLong
  }

}
