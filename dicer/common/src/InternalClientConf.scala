package com.databricks.dicer.common

import com.databricks.conf.DbConf
import com.databricks.dicer.common.InternalClientConf.{
  allowMultipleSliceletInstancesPropertyName,
  allowMultipleClerksShareLookupPerTargetPropertyName
}

/** Dicer-internal client configuration. Should not be used or modified by any external callers. */
private[dicer] trait InternalClientConf extends DbConf {

  /**
   * Whether to allow multiple Slicelet instances within this process.
   *
   * Should only ever be modified by the caching team to support single process tests simulating a
   * production environment with multiple Slicelet processes.
   */
  private[dicer] val allowMultipleSliceletInstances: Boolean = configure[Boolean](
    allowMultipleSliceletInstancesPropertyName,
    defaultValue = false
  )

  /**
   * Whether to share [[SliceLookup]] instances across Clerks for the same target.
   *
   * By default, this is set to false, meaning that a new [[SliceLookup]] instance will be created
   * for each Clerk for the same target. When enabled, the same [[SliceLookup]] instance will be
   * reused for all Clerks for the same target.
   */
  private[dicer] val allowMultipleClerksShareLookupPerTarget: Boolean = configure[Boolean](
    allowMultipleClerksShareLookupPerTargetPropertyName,
    defaultValue = false
  )

  /**
   * The process's environment variables. We have this as part of the config instead of accessing
   * `sys.env` directly so that we can set environment variables in tests, since Scala/Java doesn't
   * support actually modifying the process's environment variables.
   */
  private[dicer] def envVars: Map[String, String] = sys.env
}

object InternalClientConf {

  /** The name of the conf property for [[allowMultipleSliceletInstances]]. */
  private[dicer] val allowMultipleSliceletInstancesPropertyName: String =
    "databricks.dicer.internal.cachingteamonly.allowMultipleSliceletInstances"

  /** The name of the conf property for [[allowMultipleClerksShareLookupPerTarget]]. */
  private[dicer] val allowMultipleClerksShareLookupPerTargetPropertyName: String =
    "databricks.dicer.internal.cachingteamonly.allowMultipleClerksShareLookupPerTarget"
}
