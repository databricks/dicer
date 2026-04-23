package com.databricks.caching.util

import scala.util.Try
import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.conf.trusted.LocationConf
import com.databricks.common.alias.RichScalaPB.RichMessage

/**
 * Represents a cluster for which a configuration is overridden, corresponding to the proto message
 * [[ConfigScopeP]].
 */
case class ConfigScope(clusterUri: String) {
  require(
    clusterUri.startsWith("kubernetes-cluster:"),
    s"Cluster URI must start with 'kubernetes-cluster:': $clusterUri"
  )

  override def toString: String = clusterUri
}

object ConfigScope {

  /**
   * REQUIRES: `configScopeP.clusterUri` must be non-empty.
   *
   * Factory method that creates a `ConfigScope` instance from a [[ConfigScopeP]] proto message.
   */
  def fromProto(configScopeP: ConfigScopeP): ConfigScope = {
    require(configScopeP.clusterUri.nonEmpty, "Cluster URI must be specified.")
    new ConfigScope(clusterUri = configScopeP.getClusterUri)
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
