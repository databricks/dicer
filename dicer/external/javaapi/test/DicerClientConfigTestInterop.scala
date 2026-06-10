package com.databricks.dicer.external.javaapi

import com.databricks.dicer.client.javaapi.SliceletConfImpl
import com.databricks.dicer.client.javaapi.ClerkConfImpl
import com.databricks.rpc.tls.TLSOptions

/**
 * Exposes package-private accessors for use by Java tests such as [[SliceletTest]] and
 * [[ClerkTest]].
 *
 * This is necessary because accessing Scala companion object members from Java requires
 * going through the mangled name (e.g. `SliceletConfImpl$.MODULE$.forTest()`).
 */
object DicerClientConfigTestInterop {

  /** Returns the configured TLS options from the given [[SliceletConfig]]. */
  def sliceletTlsOptions(config: SliceletConfig): Option[TLSOptions] =
    SliceletConfImpl.forTest.getTlsOptions(config.toScala())

  /** Returns the configured TLS options from the given [[ClerkConfig]]. */
  def clerkTlsOptions(config: ClerkConfig): Option[TLSOptions] =
    ClerkConfImpl.forTest.getTlsOptions(config.toScala())
}
