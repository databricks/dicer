package com.databricks.dicer.client

import io.prometheus.client.CollectorRegistry

import com.databricks.caching.util.MetricUtils
import com.databricks.dicer.client.testing.ScalaClerkHarness
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.{Slicelet, Target}

/** Runs [[ClerkRetrySuiteBase]] against the in-process Scala Clerk's retry picker. */
class ScalaClerkRetrySuite extends ClerkRetrySuiteBase {

  override protected def createClerkHarness(target: Target, slicelet: Slicelet): ScalaClerkHarness =
    ScalaClerkHarness.create(testEnv.createClerk(slicelet))

  override protected def getNextStubForKeyCallCountTrackerOpt(
      target: Target,
      resourceType: String): Option[MetricUtils.ChangeTracker[Double]] =
    Some(MetricUtils.ChangeTracker[Double] { () =>
      MetricUtils.getMetricValue(
        CollectorRegistry.defaultRegistry,
        "dicer_clerk_getnextstubforkey_call_count_total",
        Map(
          "targetName" -> target.getTargetNameLabel,
          "resourceType" -> resourceType
        )
      )
    })

  override protected def getPickedResourceCountTrackerOpt(
      target: Target): Option[MetricUtils.ChangeTracker[Double]] =
    Some(MetricUtils.ChangeTracker[Double] { () =>
      MetricUtils.getMetricValuesSum(
        CollectorRegistry.defaultRegistry,
        "dicer_clerk_getnextstubforkey_picked_resource_count_histogram_sum",
        Map("targetName" -> target.getTargetNameLabel)
      )
    })
}
