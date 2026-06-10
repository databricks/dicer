package com.databricks.caching.util

import com.databricks.conf.trusted.DeploymentModes

/** A trait for objects that can be serialized as JSON strings. */
trait JsonSerializableConfig {
  def toJsonString: String
}

/**
 * REQUIRES: all implementations of this trait must be validated by [[SafeConfigProvider.validate]].
 *
 * A trait providing SAFE configs, including:
 *  - The config targets for dev, staging, and prod.
 *  - The default config for dev, staging, and prod.
 *  - A map of [[ConfigScope]]s to their corresponding overrides for dev, staging, and prod.
 *  - The canary config for each canary scope and namespace in production.
 *
 * - Only `DeploymentModes.Development`, `DeploymentModes.Staging` and `DeploymentModes.Production`
 * are considered to be valid modes.
 *
 * @note In the proto, one override can correspond to one or more [[ConfigScope]]s. However, in the
 *       SAFE config tool provider, we do not preserve this property. Instead, we require each
 *       override to correspond to only one [[ConfigScope]], thereby duplicates are removed.
 *       A downside of this approach is that specifying one override with multiple [[ConfigScope]]s
 *       in the proto results in it being broken down into multiple overrides. This leads to a
 *       longer SAFE Jsonnet file content. We prefer this approach since duplications might result
 *       in misconfiguration.
 */
trait SafeConfigProvider[Config <: JsonSerializableConfig] {

  /** Gets the config targets for the given mode. */
  @throws[NoSuchElementException]("if `mode` is not a valid mode")
  def configTargets(mode: DeploymentModes.Value): Set[String]

  /** Gets the default config for the given mode. */
  @throws[NoSuchElementException]("if `mode` is not a valid mode")
  @throws[IllegalArgumentException]("if `configTarget` does not exist under `mode`")
  def getDefaultConfig(mode: DeploymentModes.Value, configTarget: String): Config

  /**
   * Gets the map from [[ConfigScope]]s to their corresponding overridden configs for the given
   * mode.
   */
  @throws[NoSuchElementException]("if `mode` is not a valid mode")
  @throws[IllegalArgumentException]("if `configTarget` does not exist under `mode`")
  def getScopedOverrides(
      mode: DeploymentModes.Value,
      configTarget: String): Map[ConfigScope, Config]

  /**
   * Gets the configs from the targets to their corresponding configs which will be used to canary
   * production config changes.
   */
  @throws[NoSuchElementException]("if `canaryScope` is not a valid canary scope")
  def getProductionCanaryConfigs(canaryScope: ConfigScope): Map[String, Config]

  /** Returns the canary config scopes. */
  def canaryConfigScopes: Set[ConfigScope]

}

/** Object that includes mappings and sets related to deployment modes. */
object SafeConfigProvider {

  /** The mapping from the short names of the deployment modes to the deployment modes. */
  val SHORT_NAMES_TO_VALID_MODES: Map[String, DeploymentModes.Value] = Map(
    SafeConfigUtil.DEV_MODE_SHORT_NAME -> DeploymentModes.Development,
    SafeConfigUtil.STAGING_MODE_SHORT_NAME -> DeploymentModes.Staging,
    SafeConfigUtil.PROD_MODE_SHORT_NAME -> DeploymentModes.Production
  )

  /** The valid deployment modes. */
  val VALID_MODES: Set[DeploymentModes.Value] = SHORT_NAMES_TO_VALID_MODES.values.toSet

  /** Common validation logic for all [[SafeConfigProvider]] implementations. */
  @throws[IllegalArgumentException]("if the config provider is invalid")
  @throws[NoSuchElementException]("if a required mode is missing from the config provider")
  def validate(configProvider: SafeConfigProvider[_]): Unit = {
    for (mode: DeploymentModes.Value <- VALID_MODES) {
      require(
        configProvider.configTargets(mode).nonEmpty,
        s"The configTargets for all modes $VALID_MODES must be provided, " +
        s"missing mode: $mode"
      )
    }
    require(
      configProvider.canaryConfigScopes.nonEmpty,
      s"The canary config scopes must be provided"
    )
    // TODO(<internal bug>): Add test coverage for multiple canary shards and lift this requirement.
    require(
      configProvider.canaryConfigScopes.size < 2,
      s"Multiple canary config scopes are not supported yet"
    )
    for (canaryScope: ConfigScope <- configProvider.canaryConfigScopes) {
      require(
        configProvider.getProductionCanaryConfigs(canaryScope).keySet ==
        configProvider.configTargets(DeploymentModes.Production),
        s"The config provider should prepare a canary config for each namespace in production. " +
        s"Production config targets ${configProvider.configTargets(DeploymentModes.Production)}" +
        s"Canary config target ${configProvider.getProductionCanaryConfigs(canaryScope).keySet}"
      )
    }
  }
}
