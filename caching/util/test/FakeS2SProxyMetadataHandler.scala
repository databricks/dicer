package com.databricks.caching.util

import io.grpc.{Metadata, Status}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/**
 * A [[FakeProxy.MetadataHandler]] that implements S2S Proxy metadata handling.
 *
 * Extracts the upstream host and port from the `X-Databricks-Upstream-Host-Port` header, or
 * returns [[None]] to signal that [[FakeProxy]] should fall back to its configured fallback ports
 * if the header is absent.
 *
 * This object is intended for constructing a [[FakeProxy]] to simulate an S2S proxy server in
 * tests:
 * {{{
 *   val proxy: FakeProxy = FakeProxy.createAndStart(FakeS2SProxyMetadataHandler)
 * }}}
 */
object FakeS2SProxyMetadataHandler extends FakeProxy.MetadataHandler {

  /** The header from which S2S Proxy extracts the base64-encoded upstream host-port string. */
  private val UPSTREAM_HOST_PORT_HEADER: String = "X-Databricks-Upstream-Host-Port"

  override def extractUpstreamHostAndPort(metadata: Metadata): Option[(String, Int)] = {
    val hostPortHeader: String =
      metadata.get(
        Metadata.Key.of(UPSTREAM_HOST_PORT_HEADER, Metadata.ASCII_STRING_MARSHALLER)
      )

    if (hostPortHeader == null) {
      return None
    }

    // Parse the header. Note that the real S2S Proxy would route the request to a random pod if it
    // failed to parse the header, but for our testing purposes, we would rather fail fast since
    // that should never happen.
    val hostPortString: String = new String(Base64.getDecoder.decode(hostPortHeader), UTF_8)
    val parts: Array[String] = hostPortString.split(':')
    if (parts.length != 2) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Malformed upstream host-port header")
        .asException
    }
    val host: String = parts(0)
    val port: Int = Try(parts(1).toInt)
      .getOrElse(throw Status.INVALID_ARGUMENT.withDescription("Malformed port").asException)
    Some((host, port))
  }
}
