package com.databricks.dicer.client.javaapi

import com.typesafe.config.Config

import com.databricks.backend.common.util.CurrentProject
import com.databricks.conf.Configs
import com.databricks.conf.trusted.ProjectConf
import com.databricks.conf.RawConfigSingleton
import com.databricks.dicer.external.ClerkConf

/**
 * A concrete implementation of [[ClerkConf]].
 *
 * The cake pattern with mixins (used in DB-CONF in Scala) is not supported in Java.
 * This class provides a concrete implementation of [[ClerkConf]] that the Java shim can
 * instantiate and pass to the Scala Clerk.
 *
 * Intended for Java usage only; do not use directly from Scala code.
 *
 * See [[DicerClientConfImplCommon]] for TLS configuration requirements.
 *
 * @throws IllegalStateException if [[CurrentProject]] is not initialized.
 */
final class ClerkConfImpl private[javaapi] (rawConfig: Config)
    extends ProjectConf(
      CurrentProject.projectOpt.getOrElse(
        throw new IllegalStateException(
          "CurrentProject not initialized. Ensure DatabricksMain has started or call " +
          "CurrentProject.initializeProject() in tests."
        )
      ),
      rawConfig
    )
    with ClerkConf
    with DicerClientConfImplCommon

object ClerkConfImpl {

  /** Creates a builder for constructing [[ClerkConfImpl]] instances. */
  def builder(): Builder = new Builder()

  /** Builder for constructing [[ClerkConfImpl]] instances. */
  final class Builder {

    /** Optional "databricks.dicer.slicelet.rpc.port" override. */
    private var sliceletPortOpt: Option[Int] = None

    /** Optional base config override. Defaults to [[RawConfigSingleton.conf]] if not set. */
    private var baseConfOpt: Option[Config] = None

    /**
     * REQUIRE: sliceletPort is positive.
     *
     * Overrides "databricks.dicer.slicelet.rpc.port" for the created conf.
     *
     * This is intended for callers that need to create multiple Clerks with different
     * Slicelet ports in the same process. One example is S2SProxy, where the Slicelet runs on
     * port 443 instead of the default 24510.
     */
    def setSliceletPort(sliceletPort: Int): Builder = {
      require(sliceletPort > 0, "sliceletPort must be positive")
      this.sliceletPortOpt = Some(sliceletPort)
      this
    }

    /** Builds a [[ClerkConfImpl]] instance. */
    def build(): ClerkConfImpl = {
      val baseConf: Config = baseConfOpt.getOrElse(RawConfigSingleton.conf)
      val overrides: Map[String, Any] = Seq(
        sliceletPortOpt.map((port: Int) => "databricks.dicer.slicelet.rpc.port" -> port)
      ).flatten.toMap
      val configWithOverrides: Config =
        if (overrides.nonEmpty) Configs.parseMap(overrides).withFallback(baseConf)
        else baseConf
      new ClerkConfImpl(configWithOverrides)
    }

    private[dicer] object forTest {

      /** Sets the base config for testing, and returns the builder. */
      def setBaseConfig(baseConf: Config): Builder = {
        Builder.this.baseConfOpt = Some(baseConf)
        Builder.this
      }
    }
  }
}
