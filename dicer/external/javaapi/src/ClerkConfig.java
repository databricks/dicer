package com.databricks.dicer.external.javaapi;

import com.databricks.dicer.client.javaapi.ClerkConfImpl;

import com.databricks.rpc.tls.JTLSOptions;
import com.databricks.rpc.tls.TLSOptions;
import com.typesafe.config.Config;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;
import scala.Option;
import scala.compat.java8.OptionConverters;

/**
 * Configuration for creating a {@link Clerk} in the Java API.
 *
 * <p>Use {@link #builder()} to construct an instance. Each call to {@link Builder#build()} creates
 * a new independent configuration instance, allowing different {@link Clerk} instances to have
 * different configurations.
 */
public final class ClerkConfig {

  /** The underlying Scala implementation of the clerk configuration. */
  private final ClerkConfImpl clerkConf;

  /** Private constructor. Use {@link Builder} to create an instance. */
  private ClerkConfig(ClerkConfImpl clerkConf) {
    this.clerkConf = clerkConf;
  }

  /** Returns a new {@link Builder} with default settings. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Converts this {@link ClerkConfig} to a {@link ClerkConfImpl} for internal Java-Scala interop.
   */
  ClerkConfImpl toScala() {
    return clerkConf;
  }

  /** Builder for {@link ClerkConfig}. */
    @NotThreadSafe
  public static final class Builder {

    /**
     * TLS options override for Dicer RPCs.
     *
     * <p>Note: Today there is no use case for different client vs server TLS options. If one arises
     * in the future, separate parameters can be added.
     */
    private Optional<JTLSOptions> tlsOptions = Optional.empty();

    /** Optional slicelet port override. */
    private Optional<Integer> sliceletPortOpt = Optional.empty();

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

    /**
     * Overrides "databricks.dicer.slicelet.rpc.port" for the created conf.
     *
     * <p>This is intended for callers that need to create multiple Clerks with different Slicelet
     * ports in the same process. One example is when a DP Clerk talks to a CP Slicelet, which it
     * connects to through S2S Proxy on port 443 instead of the default 24510.
     *
     * @throws IllegalArgumentException if sliceletPort is not positive.
     */
    public Builder setSliceletPort(int sliceletPort) {
      if (sliceletPort <= 0) {
        throw new IllegalArgumentException("sliceletPort must be positive");
      }
      this.sliceletPortOpt = Optional.of(sliceletPort);
      return this;
    }

    /** Sets the base config for test environments. Package-private for internal test use only. */
    Builder setBaseConfigForTest(Config baseConfig) {
      this.baseConfigOpt = Optional.of(baseConfig);
      return this;
    }

    /**
     * Builds a {@link ClerkConfig}.
     *
     * @throws IllegalStateException if CurrentProject is not initialized.
     */
    public ClerkConfig build() {
      Option<Config> scalaBaseConfOpt = OptionConverters.toScala(baseConfigOpt);
      Option<TLSOptions> scalaTlsOptions =
          OptionConverters.toScala(tlsOptions.map(JTLSOptions::toTLSOptions));
      // Due to JVM type erasure, the Scala signature Option[Int] appears as Option<Object> in Java.
      Option<Object> scalaSliceletPort =
          OptionConverters.toScala(sliceletPortOpt.map((Integer port) -> (Object) port));
      return new ClerkConfig(
          ClerkConfImpl.create(scalaBaseConfOpt, scalaTlsOptions, scalaSliceletPort));
    }
  }
}
