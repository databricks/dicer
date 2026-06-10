package com.databricks.dicer.client

/**
 * Internal config for a Clerk or Slicelet.
 *
 * [[InternalClientConfig]] is designed to be extensible for future use. Any property that pertains
 * to the client itself should be included in the [[InternalClientConfig]].
 *
 * @param sliceLookupConfig The configuration identifying the [[SliceLookup]] this client uses.
 * @param subscriberDebugName The debug name shown in the log and string representation of the
 *                            Clerk/Slicelet.
 */
case class InternalClientConfig(sliceLookupConfig: SliceLookupConfig, subscriberDebugName: String)
