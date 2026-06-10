package com.databricks.dicer.client.javaapi

import com.typesafe.config.Config

import com.databricks.backend.common.util.CurrentProjectInfo
import com.databricks.conf.Configs
import com.databricks.conf.RawConfigSingleton
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.external.ClerkConf
import com.databricks.rpc.tls.TLSOptions

/**
 * A concrete implementation of [[ClerkConf]] intended for Java usage only; do not
 * use directly from Scala code.
 *
 * The cake pattern with mixins (used in DB-CONF in Scala) is not supported in Java.
 * This class provides a concrete implementation of [[ClerkConf]] that the Java shim can
 * pass to the Scala Clerk.
 *
 * This class is declared public to allow access from Java, but its visibility is restricted
 * via a Bazel rule.
 */
final class ClerkConfImpl private (
    projectName: String,
    rawConfig: Config,
    tlsOptions: Option[TLSOptions])
    extends ProjectConf(projectName, rawConfig)
    with ClerkConf {

  override protected def dicerTlsOptions: Option[TLSOptions] = tlsOptions
}

object ClerkConfImpl {

  /**
   * Creates a [[ClerkConfImpl]] instance.
   *
   * @param baseConfOpt optional base config override. Defaults to [[RawConfigSingleton]] if None.
   * @param tlsOptions the TLS options to use for Dicer RPCs.
   * @param sliceletPortOpt optional override for "databricks.dicer.slicelet.rpc.port".
   */
  @throws[IllegalStateException]("if CurrentProject is not initialized")
  @throws[IllegalArgumentException]("if sliceletPortOpt contains a non-positive value")
  def create(
      baseConfOpt: Option[Config],
      tlsOptions: Option[TLSOptions],
      sliceletPortOpt: Option[Int]
  ): ClerkConfImpl = {
    val projectName: String = CurrentProjectInfo.projectNameOpt.getOrElse(
      throw new IllegalStateException(
        "CurrentProject not initialized. Ensure DatabricksMain has started or call " +
        "CurrentProject.initializeProject() in tests."
      )
    )
    val baseConf: Config = baseConfOpt.getOrElse(RawConfigSingleton.conf)
    val configWithOverrides: Config = sliceletPortOpt match {
      case Some(port: Int) =>
        require(port > 0, "sliceletPort must be positive")
        Configs.parseMap(Map("databricks.dicer.slicelet.rpc.port" -> port)).withFallback(baseConf)
      case None => baseConf
    }
    new ClerkConfImpl(
      projectName = projectName,
      rawConfig = configWithOverrides,
      tlsOptions = tlsOptions
    )
  }

  private[dicer] object forTest {

    /** Returns the TLS options configured on the given [[ClerkConfImpl]]. */
    def getTlsOptions(conf: ClerkConfImpl): Option[TLSOptions] = conf.dicerTlsOptions
  }
}
