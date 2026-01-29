package com.databricks.rpc.tls

import java.io.File

import io.grpc.ChannelCredentials
import io.grpc.ServerCredentials
import io.grpc.TlsChannelCredentials
import io.grpc.TlsServerCredentials

/**
 * Minimal TLSOptions implementation.
 *
 * Many call sites provide "keystore/truststore" paths. Here they are interpreted as PEM files:
 * - keystore: certificate chain + private key (may be a single combined PEM)
 * - truststore: trusted certificates (PEM)
 *
 * This implementation uses gRPC's native TLS API (TlsChannelCredentials and TlsServerCredentials)
 * for transport-independent TLS configuration.
 */
final class TLSOptions private (
    private val keyManagerCertChainOpt: Option[File],
    private val keyManagerPrivateKeyOpt: Option[File],
    private val trustManagerCertsOpt: Option[File]) {

  /**
   * Creates gRPC ChannelCredentials for TLS clients.
   *
   * Uses gRPC's transport-independent TLS API for client channel configuration.
   */
  def channelCredentials(): ChannelCredentials = {
    val builder: TlsChannelCredentials.Builder = TlsChannelCredentials.newBuilder()
    // Add trust manager (which server certs are accepted).
    for (certs: File <- trustManagerCertsOpt) {
      builder.trustManager(certs)
    }
    // Add key manager for mTLS (client certificate authentication).
    (keyManagerCertChainOpt, keyManagerPrivateKeyOpt) match {
      case (Some(certChain), Some(privateKey)) =>
        builder.keyManager(certChain, privateKey)
      case _ => // No client cert (server-only TLS)
    }
    builder.build()
  }

  /**
   * Creates gRPC ServerCredentials for TLS servers.
   *
   * Uses gRPC's transport-independent TLS API for server configuration.
   */
  def serverCredentials(): ServerCredentials = {
    // Server TLS always requires a cert + private key.
    val (certChain, privateKey): (File, File) =
      (keyManagerCertChainOpt, keyManagerPrivateKeyOpt) match {
        case (Some(c), Some(k)) => (c, k)
        case _ =>
          throw new IllegalStateException(
            "Server TLS requires a key manager (certificate chain + private key)."
          )
      }
    val builder: TlsServerCredentials.Builder =
      TlsServerCredentials.newBuilder().keyManager(certChain, privateKey)
    for (certs: File <- trustManagerCertsOpt) {
      // If a trust bundle is provided, require client certificates (mTLS).
      builder.trustManager(certs).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
    }
    builder.build()
  }

}

object TLSOptions {

  /** Creates a new builder for TLSOptions. */
  def builder = new Builder()

  /** Builder class for [[TLSOptions]].*/
  class Builder {
    // Stash the inputs so call sites can provide them incrementally.
    // We keep them as Files because that's what Netty's SslContextBuilder consumes.
    private var keyManagerCertChainOpt: Option[File] = None
    private var keyManagerPrivateKeyOpt: Option[File] = None
    private var trustManagerCertsOpt: Option[File] = None

    def build(): TLSOptions = {
      new TLSOptions(
        keyManagerCertChainOpt = keyManagerCertChainOpt,
        keyManagerPrivateKeyOpt = keyManagerPrivateKeyOpt,
        trustManagerCertsOpt = trustManagerCertsOpt
      )
    }

    /**
     * Adds a key manager.
     *
     * Some call sites pass the same path for both parameters. This supports a combined PEM file
     * that contains both certificate chain and private key.
     */
    def addKeyManager(certChain: File, privateKey: File): Builder = {
      keyManagerCertChainOpt = Some(certChain)
      keyManagerPrivateKeyOpt = Some(privateKey)
      this
    }

    /** Adds a trust manager (trusted PEM certificates). */
    def addTrustManager(rootCerts: File): Builder = {
      trustManagerCertsOpt = Some(rootCerts)
      this
    }
  }
}
