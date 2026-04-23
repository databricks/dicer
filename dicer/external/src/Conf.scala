package com.databricks.dicer.external

import java.net.URI

import com.databricks.conf.DbConf
import com.databricks.conf.trusted.{LocationConf, RPCPortConf}
import com.databricks.dicer.client.DicerClientProtoLoggerConf
import com.databricks.dicer.common.{CommonSslConf, InternalClientConf, WatchServerConf}
import com.databricks.rpc.tls.TLSOptions

/** The Dicer Clerk config that an application should derive from when using Dicer. */
trait ClerkConf extends DicerClientConf {

  /** Returns the Slicelet URI that the Clerk needs to connect to. */
  private[dicer] final def getSliceletURI(sliceletHostName: String): URI = {
    URI.create(s"$sliceletHostName:$dicerSliceletRpcPort")
  }
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
    with LocationConf
    with DicerClientProtoLoggerConf {

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
   * Unique identifier for this Dicer client instance. Defaults to environment variable: POD_UID.
   *
   * For Clerks, this is the primary client identifier. For Slicelets, existing code uses
   * `databricks.dicer.slicelet.uuid` (see [[SliceletConf.sliceletUuidOpt]]).
   */
  private[dicer] final val clientUuidOpt: Option[String] =
    configure("databricks.dicer.internal.cachingteamonly.clientUuid", envVars.get("POD_UID"))
}
