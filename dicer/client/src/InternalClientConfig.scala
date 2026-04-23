package com.databricks.dicer.client

/**
 * Internal config for a Clerk or Slicelet.
 *
 * Note: The [[SliceLookupConfig]] and [[InternalClientConfig]] are conceptually distinct, despite
 * that [[InternalClientConfig]] currently only wraps a [[SliceLookupConfig]]. The
 * [[InternalClientConfig]] is designed to be extensible for future use. Any property that pertains
 * to the client itself should be included in the [[InternalClientConfig]].
 */
case class InternalClientConfig(sliceLookupConfig: SliceLookupConfig)
