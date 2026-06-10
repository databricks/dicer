package com.databricks.dicer.external.javaapi;

import com.databricks.dicer.client.javaapi.SliceletConfImpl;

import com.databricks.rpc.tls.JTLSOptions;
import com.databricks.rpc.tls.TLSOptions;
import com.typesafe.config.Config;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;
import scala.Option;
import scala.compat.java8.OptionConverters;

/**
 * Configuration for creating a {@link Slicelet} in the Java API.
 *
 * <p>Use {@link #builder()} to construct an instance.
 */
public final class SliceletConfig {

  /** The underlying Scala implementation of the slicelet configuration. */
  private final SliceletConfImpl sliceletConf;

  /** Private constructor. Use {@link Builder} to create an instance. */
  private SliceletConfig(SliceletConfImpl sliceletConf) {
    this.sliceletConf = sliceletConf;
  }

  /** Returns a new {@link Builder}. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Converts this {@link SliceletConfig} to a {@link SliceletConfImpl} for internal Java-Scala
   * interop.
   */
  SliceletConfImpl toScala() {
    return sliceletConf;
  }

  /** Builder for {@link SliceletConfig}. */
    @NotThreadSafe
  public static final class Builder {

    /**
     * TLS options override for Dicer RPCs.
     *
     * <p>Note: Today there is no use case for different client vs server TLS options. If one arises
     * in the future, separate parameters can be added.
     */
    private Optional<JTLSOptions> tlsOptions = Optional.empty();

    /** Base config override. When unset, fallback to {@link RawConfigSingleton}. */
    private Optional<Config> baseConfigOpt = Optional.empty();

    private Builder() {}

    /**
     * Sets the TLS configuration used for Dicer RPCs. If static configs such as
     * "databricks.dicer.library.client.truststore" are set explicitly for client or server, they
     * take precedence over this value.
     */
    public Builder setTlsOptions(JTLSOptions tlsOptions) {
      this.tlsOptions = Optional.of(tlsOptions);
      return this;
    }

    /** Sets the base config. Package-private for internal test use only. */
    Builder setBaseConfigForTest(Config baseConfig) {
      this.baseConfigOpt = Optional.of(baseConfig);
      return this;
    }

    /**
     * Builds a {@link SliceletConfig}.
     *
     * @throws IllegalStateException if CurrentProject is not initialized.
     */
    public SliceletConfig build() {
      Option<TLSOptions> scalaTlsOptions =
          OptionConverters.toScala(tlsOptions.map(JTLSOptions::toTLSOptions));
      Option<Config> scalaBaseConfigOpt = OptionConverters.toScala(baseConfigOpt);
      SliceletConfImpl sliceletConf = SliceletConfImpl.create(scalaBaseConfigOpt, scalaTlsOptions);
      return new SliceletConfig(sliceletConf);
    }
  }
}
