package com.databricks.dicer.client.javaapi

import com.databricks.dicer.external.DicerClientConf
import com.databricks.rpc.tls.TLSOptions

/**
 * This trait provides shared implementations of methods required by [[DicerClientConf]].
 *
 * '''TLS Configuration:''' Users of configurations mixing in this trait should provide the
 * necessary static configs for TLS connections:
 * - databricks.dicer.library.client.keystore
 * - databricks.dicer.library.client.truststore
 * - databricks.dicer.library.server.keystore
 * - databricks.dicer.library.server.truststore
 *
 * If TLS is not configured through static configs, then no TLS is applied.
 */
private[javaapi] trait DicerClientConfImplCommon { self: DicerClientConf =>

  /**
   * This method should not be used by Dicer and always returns None.
   *
   * Previously, this method was used to construct server and client TLS options as static
   * configs were not required. Dicer now expects users to provide the necessary static configs
   * for TLS (see class documentation).
   *
   * This method is kept only because it is required by the [[SliceletConf]] trait.
   */
  override protected def dicerTlsOptions: Option[TLSOptions] = None
}
