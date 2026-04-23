package com.databricks.dicer.external.javaapi;

import com.databricks.dicer.client.javaapi.ClerkConfImpl;

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

  /**
   * Converts this {@link ClerkConfig} to a {@link ClerkConfImpl} for internal Java-Scala interop.
   */
  ClerkConfImpl toScala() {
    return clerkConf;
  }

  /** Returns a new {@link Builder} with default settings. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ClerkConfig}. */
  public static final class Builder {

    /** The delegate Scala clerk config builder */
    private final ClerkConfImpl.Builder scalaBuilder = ClerkConfImpl.builder();

    private Builder() {}

    /**
     * @see ClerkConfImpl.Builder#setSliceletPort(int)
     */
    public Builder setSliceletPort(int sliceletPort) {
      scalaBuilder.setSliceletPort(sliceletPort);
      return this;
    }

    /** Builds a {@link ClerkConfig}. */
    public ClerkConfig build() {
      return new ClerkConfig(scalaBuilder.build());
    }

    /** Exposes the underlying Scala builder for {@link DicerTestEnvironment}. */
    ClerkConfImpl.Builder toScalaForTest() {
      return scalaBuilder;
    }
  }
}
