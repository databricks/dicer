package com.databricks.dicer.assigner.config

import java.io.File

import com.databricks.caching.util.{ConfigScope, SafeConfigProvider, SafeConfigUtil}
import com.databricks.conf.trusted.DeploymentModes
import com.databricks.dicer.assigner.config.TargetConfigReader.TargetDefaultAndOverride
import com.databricks.dicer.common.TargetName

/**
 * A SAFE config tool provider defined for Dicer. It gathers config information in a way that is
 * compatible with the [[ConfigUpdater]].
 *
 * @param defaultConfigsAndOverrides the mapping of deployment modes to the calculated
 *                                   [[TargetDefaultAndOverride]]s for all target names within each
 *                                   mode.
 * @param productionCanaryConfigs [[InternalTargetConfig]] for all target names in the canary
 *                                region.
 * @param canaryScope the config region.
 */
class DicerConfigProvider(
    defaultConfigsAndOverrides: Map[
      DeploymentModes.Value,
      Map[TargetName, TargetDefaultAndOverride]],
    productionCanaryConfigs: Map[TargetName, InternalTargetConfig],
    canaryScope: ConfigScope)
    extends SafeConfigProvider[NamedInternalTargetConfig] {

  require(
    defaultConfigsAndOverrides.keySet == SafeConfigProvider.VALID_MODES,
    s"default configs should be provided for exactly ${SafeConfigProvider.VALID_MODES}"
  )
  require(
    productionCanaryConfigs.keySet == defaultConfigsAndOverrides(DeploymentModes.Production).keySet,
    s"Must provide a canary config for each namespace in production"
  )

  override def configTargets(mode: DeploymentModes.Value): Set[String] = {
    defaultConfigsAndOverrides(mode).keySet.map { targetName: TargetName =>
      targetName.value
    }
  }

  override def getDefaultConfig(
      mode: DeploymentModes.Value,
      configTarget: String): NamedInternalTargetConfig = {
    val targetName: TargetName = TargetName(configTarget)
    val config: InternalTargetConfig = getDefaultAndOverride(mode, targetName).default
    NamedInternalTargetConfig(targetName, config)
  }

  override def getScopedOverrides(
      mode: DeploymentModes.Value,
      configTarget: String): Map[ConfigScope, NamedInternalTargetConfig] = {
    val targetName = TargetName(configTarget)
    val configs: Map[ConfigScope, InternalTargetConfig] =
      getDefaultAndOverride(mode, targetName).overrides
    configs.map {
      case (scope: ConfigScope, config: InternalTargetConfig) =>
        scope -> NamedInternalTargetConfig(targetName, config)
    }
  }

  override def getProductionCanaryConfigs: Map[String, NamedInternalTargetConfig] = {
    productionCanaryConfigs.map {
      case (targetName: TargetName, config: InternalTargetConfig) =>
        targetName.value -> NamedInternalTargetConfig(targetName, config)
    }
  }

  /** Returns the canary config scope. */
  override def canaryConfigScope: ConfigScope = canaryScope

  /** Gets the default and override config for the given mode and target. */
  private def getDefaultAndOverride(
      mode: DeploymentModes.Value,
      targetName: TargetName): TargetDefaultAndOverride = {
    val targetConfigMap: Map[TargetName, TargetDefaultAndOverride] = defaultConfigsAndOverrides(
      mode
    )
    targetConfigMap.getOrElse(
      targetName,
      throw new IllegalArgumentException(s"$targetName does not exist in $mode")
    )
  }
}

object DicerConfigProvider {
  def create(
      configDirectoryPrefixes: Seq[(File, File)],
      canaryConfigScope: ConfigScope): DicerConfigProvider = {

    // Gets the default configs with overrides for all deployment modes.
    val defaultConfigsAndOverrides
        : Map[DeploymentModes.Value, Map[TargetName, TargetDefaultAndOverride]] =
      configDirectoryPrefixes.foldLeft(
        Map.empty[DeploymentModes.Value, Map[TargetName, TargetDefaultAndOverride]]
      ) { (acc, configDirectoryPrefix) =>
        val (targetConfigDirectoryPrefix, advancedConfigDirectoryPrefix): (File, File) =
          configDirectoryPrefix
        val currentModeConfigs
            : Map[DeploymentModes.Value, Map[TargetName, TargetDefaultAndOverride]] =
          SafeConfigProvider.SHORT_NAMES_TO_VALID_MODES.map {
            case (modeShortName: String, mode: DeploymentModes.Value) =>
              val targetConfigDir: File = new File(targetConfigDirectoryPrefix, modeShortName)
              val advancedConfigDir: File = new File(advancedConfigDirectoryPrefix, modeShortName)

              val configsInMode: Map[TargetName, TargetDefaultAndOverride] = TargetConfigReader
                .readFullConfigMapFromDirectories(targetConfigDir, advancedConfigDir)

              mode -> configsInMode
          }

        // Merge the configs for the current directory prefix with the accumulated configs.
        acc ++ currentModeConfigs.map {
          case (mode: DeploymentModes.Value, configs: Map[TargetName, TargetDefaultAndOverride]) =>
            mode -> (acc.getOrElse(mode, Map.empty[TargetName, TargetDefaultAndOverride]) ++
            configs)
        }
      }

    // Gets the production canary configs.
    val productionCanaryConfigs: Map[TargetName, InternalTargetConfig] =
      configDirectoryPrefixes.foldLeft(Map.empty[TargetName, InternalTargetConfig]) {
        (acc, configDirectoryPrefix) =>
          val (targetConfigDirectoryPrefix, advancedConfigDirectoryPrefix): (File, File) =
            configDirectoryPrefix
          val targetConfigDir: File =
            new File(targetConfigDirectoryPrefix, SafeConfigUtil.PROD_MODE_SHORT_NAME)
          val advancedConfigDir: File =
            new File(advancedConfigDirectoryPrefix, SafeConfigUtil.PROD_MODE_SHORT_NAME)

          acc ++ TargetConfigReader.readScopeConfigMapFromDirectories(
            Some(canaryConfigScope),
            targetConfigDir,
            advancedConfigDir
          )
      }

    new DicerConfigProvider(
      defaultConfigsAndOverrides,
      productionCanaryConfigs,
      canaryConfigScope
    )
  }
}
