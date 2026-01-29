package com.databricks.dicer.external

import java.net.URI

import com.databricks.conf.DbConf
import com.databricks.conf.trusted.{LocationConf, RPCPortConf}
import com.databricks.dicer.common.{CommonSslConf, InternalClientConf, WatchServerConf}
import com.databricks.rpc.tls.TLSOptions

/** The Dicer Clerk config that an application should derive from when using Dicer. */
trait ClerkConf extends DicerClientConf {

  /** Returns the Slicelet URI that the Clerk needs to connect to. */
  private[dicer] final def getSliceletURI(sliceletHostName: String): URI = {
    URI.create(s"$sliceletHostName:$dicerSliceletRpcPort")
  }

  /**
   * Whether to enable rate limiting in the Clerk for watch requests.
   *
   * While a well-behaved peer will only send a watch response after the Clerk's requested timeout
   * duration or when a new assignment is available, it is possible for a misbehaving peer to DOS
   * the Clerk by immediately responding to watch requests. When rate-limiting in the Clerk is
   * enabled, the Clerk will delay sending consecutive watch requests too quickly to avoid this
   * scenario.
   *
   * Note: as of 1/26/26, the only set of Clerk peers that may be intentionally misbehaving are
   * internal-system Slicelets, which run in an untrusted environment (using `dicer-assigner-untrusted`).
   *
   * Note: this setting applies to all Clerks in the process, not just for some targets. Because
   * sending watch requests is an internal dicer logic — not a customer-controlled behavior —
   * applying uniform rate limiting across all targets is reasonable.
   */
  private[dicer] final val enableClerkRateLimiting: Boolean =
    configure("databricks.dicer.clerk.enableClerkRateLimiting", false)
}

/** The Dicer Slicelet config that a customer should derive from when using Dicer. */
trait SliceletConf extends DicerClientConf with WatchServerConf {

  /** The Assigner host name. Must be set in Slicelet's environment config. */
  private[dicer] final val assignerHost = configure("databricks.dicer.assigner.host", "")

  /**
   * Hostname that can be used by client to connect to the Slicelet host.
   * Defaults to environment variable POD_IP.
   */
  private[dicer] final val sliceletHostNameOpt: Option[String] =
    configure("databricks.dicer.slicelet.hostname", envVars.get("POD_IP"))

  /** Unique identifier for the slicelet. Defaults to environment variable: POD_UID. */
  private[dicer] final val sliceletUuidOpt: Option[String] =
    configure("databricks.dicer.slicelet.uuid", envVars.get("POD_UID"))

  /** Kubernetes namespace for the slicelet host. Defaults to environment variable: NAMESPACE. */
  private[dicer] final val sliceletKubernetesNamespaceOpt: Option[String] =
    configure("databricks.dicer.slicelet.kubernetesNamespace", envVars.get("NAMESPACE"))

  /** This is only for internal Databricks compatibility and is not supported in open source. */
  private[dicer] final val watchFromDataPlane: Boolean =
    configure("databricks.dicer.client.watchFromDataPlane", false)

  /**
   * Whether to enable rate limiting in the Slicelet for watch requests. See
   * [[ClerkConf.enableClerkRateLimiting]] for details on the purpose and affecting scope of
   * rate-limiting in Dicer clients.
   *
   * Note: as of 1/26/26, there are no known Slicelet peers that may be intentionally misbehaving;
   * this feature can still help prevent overload in Slicelets in the event of a bug in the watch
   * request flow from the Assigner (See <internal bug>).
   */
  private[dicer] final val enableSliceletRateLimiting: Boolean =
    configure("databricks.dicer.slicelet.enableSliceletRateLimiting", false)
}

/**
 * The Dicer client config - see specs on `ClerkConf` and `SliceletConf`. An application should not
 * extend this conf object. Instead, it should extend `ClerkConf` or `SliceletConf`.
 */
trait DicerClientConf
    extends DbConf
    with RPCPortConf
    with CommonSslConf
    with InternalClientConf
    with LocationConf {

  /**
   * TlsOptions that should be set by Dicer clients, in their service configuration. Most services
   * should already have one defined. It is used by Dicer clients to communicate with its internal
   * service for getting assignments.
   */
  protected def dicerTlsOptions: Option[TLSOptions]

  /**
   * TLSOptions for connecting to the Assigner.
   * If not overridden, it defaults to [[dicerTlsOptions]].
   */
  protected def dicerClientTlsOptions: Option[TLSOptions]

  /**
   * TLSOptions for allowing the Slicelet to start a gRPC server.
   * If not overridden, it defaults to [[dicerTlsOptions]].
   */
  protected def dicerServerTlsOptions: Option[TLSOptions]

  /**
   * The client may be directed to use specific addresses for its watch, which are stored in a
   * cache. This conf controls how long we keep unaccessed stubs in the cache. We want this value to
   * be longer than the connection idle timeout, which is currently 60 seconds by default so that we
   * don't end up creating multiple connections to the same endpoint.
   */
  private[dicer] final val watchStubCacheTimeSeconds: Int =
    configure("databricks.dicer.client.watchStubCacheTimeSeconds", 5 * 60)
}
