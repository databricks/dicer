package com.databricks.caching.util

import scala.util.Try
import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.conf.trusted.LocationConf
import com.databricks.common.alias.RichScalaPB.RichMessage

/** Represents the scope of a configuration override. */
sealed trait ConfigScope

/**
 * Represents a cluster for which a configuration is overridden.
 *
 * @throws IllegalArgumentException if `clusterUri` does not start with "kubernetes-cluster:".
 */
case class ClusterConfigScope @throws[IllegalArgumentException](
  "if clusterUri does not start with \"kubernetes-cluster:\""
)(clusterUri: String)
    extends ConfigScope {
  require(
    clusterUri.startsWith("kubernetes-cluster:"),
    s"Cluster URI must start with 'kubernetes-cluster:': $clusterUri"
  )

  override def toString: String = clusterUri
}

object ClusterConfigScope {

  /**
   * Returns a [[ClusterConfigScope]] containing the current Databricks cluster URI from the given
   * [[LocationConf]].
   */
  @throws[IllegalArgumentException]("if the LocationConf does not include a cluster URI")
  def fromLocationConf(conf: LocationConf): ClusterConfigScope = {
    ClusterConfigScope(
      clusterUri = conf.location.kubernetesClusterUri.getOrElse(
        throw new IllegalArgumentException(
          s"LocationConf does not include a cluster URI: ${conf.location}"
        )
      )
    )
  }
}

/**
 * Represents an instance for which a configuration is overridden.
 *
 * @param instanceId the identifier of the instance whose configuration is overridden.
 *
 * @throws IllegalArgumentException if `instanceId` is not an RFC 1123 label (see [[Rfc1123]]).
 */
case class InstanceConfigScope @throws[IllegalArgumentException](
  "if instanceId is not a valid RFC 1123 label"
)(instanceId: String)
    extends ConfigScope {
  // TODO(<internal bug>): Share instance ID validation with Target.
  require(Rfc1123.isValid(instanceId), s"Instance ID is invalid: $instanceId")

  override def toString: String = instanceId
}

object ConfigScope {

  /**
   * Factory method that creates a `ConfigScope` from a [[ConfigScopeP]] proto message.
   *
   * @throws IllegalArgumentException if the scope is unset, if it contains a `cluster_uri` that
   *                                  does not begin with "kubernetes-cluster:", or if it contains
   *                                  an `instance_id` that is not an RFC 1123 label.
   */
  @throws[IllegalArgumentException](
    "if the scope is unset, if it contains a cluster_uri that does not begin with " +
    "\"kubernetes-cluster:\", or if it contains an instance_id that is not an RFC 1123 label"
  )
  def fromProto(configScopeP: ConfigScopeP): ConfigScope = {
    configScopeP.scope match {
      case ConfigScopeP.Scope.ClusterUri(clusterUri: String) => ClusterConfigScope(clusterUri)
      case ConfigScopeP.Scope.InstanceId(instanceId: String) =>
        InstanceConfigScope(instanceId)
      case ConfigScopeP.Scope.Empty =>
        throw new IllegalArgumentException("Config scope must be specified.")
    }
  }

  /**
   * Finds the config override corresponding to the given `configScope`, ignoring malformed scopes.
   *
   * @param configScope the config scope in which this service is currently running.
   * @param scopedOverrides a list of config scope specific overrides.
   * @tparam ConfigP the type of the scoped config proto, which is typically:
   *                         - [[SoftstoreNamespaceConfigFieldsP]]
   *                         - [[AdvancedConfigFieldsP]]
   * @return None if no override is found, otherwise return the corresponding override.
   */
  @throws[IllegalArgumentException]("if `configScope` is defined in multiple overrides")
  def findScopeOverride[ConfigP <: scalapb.Message[ConfigP]](
      configScope: ConfigScope,
      scopedOverrides: Seq[(Seq[ConfigScopeP], ConfigP)]): Option[ConfigP] = {
    var matchingConfigProto: Option[ConfigP] = None
    for (entry <- scopedOverrides) {
      val (scopeProtos, configProto): (Seq[ConfigScopeP], ConfigP) = entry
      // FlatMap automatically discards None values.
      val parsedScopes: Seq[ConfigScope] = scopeProtos
        .flatMap { configScopeP: ConfigScopeP =>
          Try {
            ConfigScope.fromProto(configScopeP)
          }.toOption
        }
      if (parsedScopes.contains(configScope)) {
        if (matchingConfigProto.isDefined) {
          throw new IllegalArgumentException(
            s"At most one override can be defined for the scope: $configScope"
          )
        }
        matchingConfigProto = Some(configProto)
      }
    }
    matchingConfigProto
  }
}
