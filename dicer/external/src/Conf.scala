package com.databricks.dicer.external

import java.net.URI

import scala.concurrent.duration._

import com.databricks.conf.DbConf
import com.databricks.conf.trusted.{LocationConf, RPCPortConf}
import com.databricks.dicer.client.DicerClientProtoLoggerConf
import com.databricks.dicer.client.featurerollouts.{
  DicerClientFeatureRolloutFlag,
  DicerClientFeatureRolloutFlagImpl
}
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

  /**
   * Fallback delay before attempting to start the Slicelet's assignment lookup, if the readiness
   * poller is blocked. See [[SliceletSliceLookup]] for more details.
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[dicer] final val blockedReadinessCheckStartDelay: FiniteDuration =
    configure[Long](
      "databricks.dicer.internal.cachingteamonly.blockedReadinessCheckStartDelayMillis",
      5000
    ).millis

  /**
   * Interval at which the readiness poller polls the readiness provider. See [[SliceletImpl]] for
   * more details.
   *
   * **IMPORTANT**: This value should be set by Caching team only.
   */
  private[dicer] final val readinessProviderPollInterval: FiniteDuration =
    configure[Long](
      "databricks.dicer.internal.cachingteamonly.readinessProviderPollIntervalMillis",
      1000
    ).millis
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

  /**
   * Returns whether the rollout flag named `flagName` is enabled for `target` per the rollout
   * configuration resolved for the current environment and region. See
   * `dicer/client/feature-rollouts/proto/dicer_client_feature_rollout_config.proto` for the
   * configuration schema.
   *
   * This method should be called only once at Slicelet or Clerk initialization for each feature,
   * with the resulting Boolean passed down to lower-level components. Avoid relying on rollout
   * flags from components lower than [[SliceletImpl]] or [[ClerkImpl]].
   *
   * Slicelets or Clerks should call this method to get the rollout flag values instead of directly
   * querying the [[DicerCliengFeatureRolloutFlag]] singleton. This allows the
   * InternalDicerTestEnvironment to inject controllable return values for
   * `isFeatureRolloutFlagEnabled` for test purpose (by using its own implementation of
   * DicerClientConf with `isFeatureRolloutFlagEnabled` overridden).
   */
  private[dicer] def isFeatureRolloutFlagEnabled(flagName: String, target: Target): Boolean =
    DicerClientConf.featureRolloutFlagSingleton.isEnabled(flagName, target)
}

private object DicerClientConf {

  /** Process-wide [[DicerClientFeatureRolloutFlag]] singleton. */
  private val featureRolloutFlagSingleton: DicerClientFeatureRolloutFlag =
    DicerClientFeatureRolloutFlagImpl.create()
}
