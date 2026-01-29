package com.databricks.caching.util

import com.databricks.conf.trusted.RPCPortConf
import com.databricks.rpc.SslArguments

/** A trait to encapsulate information needed to start a service. */
trait ServerConf extends RPCPortConf {

  /**
   * Optional path to the certificate file (used by this process, as either the client or server),
   * configured using DB_CONF.
   */
  private val cert = configure[Option[String]]("databricks.rpc.cert", None)

  /**
   * Optional path to the private key file (used by this process, as either the client or server),
   * configured using DB_CONF.
   */
  private val key = configure[Option[String]]("databricks.rpc.key", None)

  /**
   * Optional path to the CA certificate file (trust store) for verifying peer certificates,
   * configured using DB_CONF. If this is used on the server side, mTLS will be enabled.
   */
  private val truststore = configure[Option[String]]("databricks.rpc.truststore", None)

  /**
   * SSL/TLS configuration for mutual TLS (mTLS) authentication.
   *
   * When enabled and `sslArgs` is used, this client/server will require TLS for all connections and
   * verify peer certificates against the configured trust store. Enabled when all three
   * `databricks.rpc` confs (cert, key, truststore) are set.
   */
  lazy val sslArgs: SslArguments = {
    if (cert.isDefined && key.isDefined && truststore.isDefined) {
      SslArguments(cert, key, truststore)
    } else {
      SslArguments.disabled
    }
  }

  /** Loopback RPC port is not configured by default. */
  val loopbackRpcPortOpt: Option[Int] = None
}
