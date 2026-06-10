package com.databricks.rpc.tls;

import java.io.File;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Java-friendly interface for {@link TLSOptions} that delegates all functionality to the underlying
 * Scala {@link TLSOptions} object.
 *
 * <p>This is the OSS-minimal subset of the internal JTLSOptions. It exposes only the builder
 * methods needed for file-based TLS configuration.
 */
public final class JTLSOptions {

  private final TLSOptions delegate;

  private JTLSOptions(TLSOptions delegate) {
    this.delegate = delegate;
  }

  /** Gets the underlying Scala TLSOptions object. */
  public TLSOptions toTLSOptions() {
    return delegate;
  }

  /** Creates a new builder for constructing JTLSOptions. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for creating JTLSOptions instances. Mirrors the Scala TLSOptions.Builder API. */
  @NotThreadSafe
  public static final class Builder {
    private TLSOptions.Builder delegateBuilder;

    private Builder() {
      this.delegateBuilder = TLSOptions.builder();
    }

    /**
     * Add the provided root certificates to the trust manager's key store.
     *
     * @param rootCerts file containing root certificates
     * @return this builder
     */
    public Builder addTrustManager(File rootCerts) {
      delegateBuilder = delegateBuilder.addTrustManager(rootCerts);
      return this;
    }

    /**
     * Add the provided key material to the key manager's key store.
     *
     * @param certChain file containing certificate chain
     * @param privateKey file containing private key
     * @return this builder
     */
    public Builder addKeyManager(File certChain, File privateKey) {
      delegateBuilder = delegateBuilder.addKeyManager(certChain, privateKey);
      return this;
    }

    /** Builds and returns a new JTLSOptions instance. */
    public JTLSOptions build() {
      TLSOptions tlsOptions = delegateBuilder.build();
      return new JTLSOptions(tlsOptions);
    }
  }
}
