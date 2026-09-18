package com.databricks.caching.util

import scala.util.Try
import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.conf.trusted.LocationConf
import com.databricks.common.alias.RichScalaPB.RichMessage

/**
 * Represents a cluster for which a configuration is overridden, corresponding to the proto message
 * [[ConfigScopeP]].
 *
 * @throws IllegalArgumentException if `clusterUri` does not start with "kubernetes-cluster:".
 */
case class ConfigScope @throws[IllegalArgumentException](
  "if clusterUri does not start with \"kubernetes-cluster:\""
)(clusterUri: String) {
  require(
    clusterUri.startsWith("kubernetes-cluster:"),
    s"Cluster URI must start with 'kubernetes-cluster:': $clusterUri"
  )

  override def toString: String = clusterUri
}

object ConfigScope {

  /**
   * Factory method that creates a `ConfigScope` from a [[ConfigScopeP]] proto message.
   *
   * @throws IllegalArgumentException if the scope is unset or contains a string in the cluster_uri
   *                                  fieldthat does not begin with "kubernetes-cluster:", or is an
   *                                  instance-id scope. Instance ID scoped overrides are not yet
   *                                  supported.
   */
  @throws[IllegalArgumentException](
    "if the scope is unset or contains a string in the cluster_uri field that does not begin " +
    "with \"kubernetes-cluster:\", or is an instance-id scope. Instance ID scoped overrides are " +
    "not yet supported."
  )
  def fromProto(configScopeP: ConfigScopeP): ConfigScope = {
    configScopeP.scope match {
      case ConfigScopeP.Scope.ClusterUri(clusterUri: String) => ConfigScope(clusterUri)
      case ConfigScopeP.Scope.InstanceId(instanceId: String) =>
        throw new IllegalArgumentException(
          s"Instance-scoped config overrides are not yet supported: $instanceId"
        )
      case ConfigScopeP.Scope.Empty =>
        throw new IllegalArgumentException("Config scope must be specified.")
    }
  }

  /** Extracts the current Databricks cluster URI from the given [[LocationConf]]. */
  @throws[IllegalArgumentException]("if the LocationConf does not include a cluster URI")
  def fromLocationConf(conf: LocationConf): ConfigScope = {
    ConfigScope(
      clusterUri = conf.location.kubernetesClusterUri.getOrElse(
        throw new IllegalArgumentException(
          s"LocationConf does not include a cluster URI: ${conf.location}"
        )
      )
    )
  }

  /**
   * Finds the config override corresponding to the given `configScope`.
   *
   * @param configScope the config scope in which this service is currently running.
   * @param scopedOverrides a list of config scope specific overrides.
   * @tparam ConfigP the type of the scoped config proto, which is typically:
   *                         - [[SoftstoreNamespaceConfigFieldsP]]
   *                         - [[AdvancedConfigFieldsP]]
   * @return None if no override is found, otherwise return the corresponding override.
   * @throws IllegalArgumentException if `configScope` is defined in multiple overrides.
   */
  def findScopeOverride[ConfigP <: scalapb.Message[ConfigP]](
      configScope: ConfigScope,
      scopedOverrides: Seq[(Seq[ConfigScopeP], ConfigP)]): Option[ConfigP] = {
    var matchingConfigProto: Option[ConfigP] = None
    for (entry <- scopedOverrides) {
      val (scopeProtos, configProto): (Seq[ConfigScopeP], ConfigP) = entry
      // FlatMap automatically discards None values.
      val matchingScopes: Seq[ConfigScope] = scopeProtos
        .flatMap { configScopeP: ConfigScopeP =>
          Try {
            ConfigScope.fromProto(configScopeP)
          }.toOption
        }
      if (matchingScopes.contains(configScope)) {
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
