package com.databricks.dicer.common

import com.databricks.conf.DbConf
import com.databricks.dicer.common.InternalClientConf.allowMultipleClientInstancesPropertyName

/** Dicer-internal client configuration. Should not be used or modified by any external callers. */
private[dicer] trait InternalClientConf extends DbConf {

  /**
   * Whether to allow multiple Clerks per target and Slicelets within this process.
   *
   * Should only ever be modified by the caching team to support single process tests simulating a
   * production environment with multiple Clerk and Slicelet processes.
   */
  private[dicer] val allowMultipleClientInstances: Boolean = configure[Boolean](
    allowMultipleClientInstancesPropertyName,
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

  /** The name of the conf property for [[allowMultipleClientInstances]]. */
  private[dicer] val allowMultipleClientInstancesPropertyName: String =
    "databricks.dicer.internal.cachingteamonly.allowMultipleClientInstances"
}
