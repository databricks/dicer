package com.databricks.dicer.client.javaapi

import com.databricks.backend.common.util.CurrentProjectInfo
import com.databricks.conf.Config
import com.databricks.conf.RawConfigSingleton
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.external.SliceletConf
import com.databricks.rpc.tls.TLSOptions

/**
 * A concrete implementation of [[SliceletConf]] intended for Java usage only; do not use
 * directly from Scala code.
 *
 * The cake pattern with mixins (used in DB-CONF in Scala) is not supported in Java.
 * This class provides a concrete implementation of [[SliceletConf]] that the Java shim can
 * pass to the Scala Slicelet.
 *
 * This class is declared public to allow access from Java, but its visibility is restricted
 * to package-private via a Bazel rule.
 */
final class SliceletConfImpl private (
    projectName: String,
    baseConf: Config,
    tlsOptions: Option[TLSOptions],
    serverTlsOptions: Option[TLSOptions])
    extends ProjectConf(projectName, baseConf)
    with SliceletConf {

  override protected def dicerTlsOptions: Option[TLSOptions] = tlsOptions

  override protected def dicerServerTlsOptions: Option[TLSOptions] =
    serverTlsOptions.orElse(dicerTlsOptions)
}

object SliceletConfImpl {

  /**
   * Creates a [[SliceletConfImpl]] with an optional inbound server TLS override.
   *
   * @param baseConfOpt optional base config override. Defaults to [[RawConfigSingleton]] if None.
   * @param tlsOptions the TLS options to use for Dicer RPCs.
   * @param serverTlsOptions inbound server TLS options, falling back to tlsOptions if None.
   */
  @throws[IllegalStateException]("if CurrentProject is not initialized")
  def create(
      baseConfOpt: Option[Config],
      tlsOptions: Option[TLSOptions],
      serverTlsOptions: Option[TLSOptions]): SliceletConfImpl = {
    val projectName = CurrentProjectInfo.projectNameOpt.getOrElse(
      throw new IllegalStateException(
        "CurrentProject not initialized. Ensure DatabricksMain has started or call " +
        "CurrentProject.initializeProject() in tests."
      )
    )
    val baseConf: Config = baseConfOpt.getOrElse(RawConfigSingleton.conf)
    new SliceletConfImpl(
      projectName = projectName,
      baseConf = baseConf,
      tlsOptions = tlsOptions,
      serverTlsOptions = serverTlsOptions
    )
  }

  private[dicer] object forTest {

    /** Returns the TLS options configured on the given [[SliceletConfImpl]]. */
    def getTlsOptions(conf: SliceletConfImpl): Option[TLSOptions] = conf.dicerTlsOptions
  }
}
