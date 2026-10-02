package com.databricks.dicer.assigner.config

import java.io.File

import com.databricks.caching.util.{
  ClusterConfigScope,
  ConfigScope,
  InstanceConfigScope,
  SafeConfigProvider,
  SafeConfigUtil
}
import com.databricks.conf.trusted.DeploymentModes
import com.databricks.dicer.assigner.config.TargetConfigReader.TargetDefaultAndOverride
import com.databricks.dicer.common.TargetName

/**
 * Supplies static configs to Dicer's SAFE update and diff tools.
 *
 * Rejects instance-scoped overrides when generating SAFE configs. This provider runs in tooling,
 * not in the assigner service; the runtime provider is created by [[TargetConfigProviderFactory]].
 *
 * @param defaultConfigsAndOverrides the mapping of deployment modes to the calculated
 *                                   [[TargetDefaultAndOverride]]s for all target names within each
 *                                   mode.
 * @param productionCanaryConfigs [[InternalTargetConfig]] for all target names in the canary
 *                                region.
 * @param canaryScope the config region.
 */
class DicerSafeConfigProvider private (
    defaultConfigsAndOverrides: Map[
      DeploymentModes.Value,
      Map[TargetName, TargetDefaultAndOverride]],
    productionCanaryConfigs: Map[TargetName, InternalTargetConfig],
    canaryScope: ClusterConfigScope)
    extends SafeConfigProvider[NamedInternalTargetConfig] {

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

  @throws[IllegalArgumentException]("if the target has instance-scoped overrides")
  override def getScopedOverrides(
      mode: DeploymentModes.Value,
      configTarget: String): Map[ClusterConfigScope, NamedInternalTargetConfig] = {
    val targetName = TargetName(configTarget)
    val configs: Map[ConfigScope, InternalTargetConfig] =
      getDefaultAndOverride(mode, targetName).overrides
    configs.map {
      case (scope: ConfigScope, config: InternalTargetConfig) =>
        scope match {
          case clusterScope: ClusterConfigScope =>
            clusterScope -> NamedInternalTargetConfig(targetName, config)
          case instanceScope: InstanceConfigScope =>
            throw new IllegalArgumentException(
              s"Instance-scoped config overrides are not yet supported for Dicer " +
              s"target $targetName: $instanceScope"
            )
        }
    }
  }

  // TODO(<internal bug>): Support multiple canary config clusters.
  override def getProductionCanaryConfigs(
      canaryConfigScope: ClusterConfigScope): Map[String, NamedInternalTargetConfig] = {
    if (canaryConfigScope == canaryScope) {
      productionCanaryConfigs.map {
        case (targetName: TargetName, config: InternalTargetConfig) =>
          targetName.value -> NamedInternalTargetConfig(targetName, config)
      }
    } else {
      throw new NoSuchElementException(s"$canaryConfigScope is not a valid canary scope")
    }
  }

  override def canaryConfigScopes: Set[ClusterConfigScope] = Set(canaryScope)

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

object DicerSafeConfigProvider {
  def create(
      configDirectoryPrefixes: Seq[(File, File)],
      canaryConfigScope: ClusterConfigScope): DicerSafeConfigProvider = {

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

    val provider: DicerSafeConfigProvider = new DicerSafeConfigProvider(
      defaultConfigsAndOverrides,
      productionCanaryConfigs,
      canaryConfigScope
    )
    SafeConfigProvider.validate(provider)
    provider
  }

  object forTest {

    /**
     * Test-only constructor that takes in the resolved configs directly. This is provided such
     * that test can easily create a provider with the desired configs, without needing to write
     * config textprotos to files.
     */
    def create(
        defaultConfigsAndOverrides: Map[
          DeploymentModes.Value,
          Map[TargetName, TargetDefaultAndOverride]],
        productionCanaryConfigs: Map[TargetName, InternalTargetConfig],
        canaryScope: ClusterConfigScope): DicerSafeConfigProvider = {
      val provider: DicerSafeConfigProvider = new DicerSafeConfigProvider(
        defaultConfigsAndOverrides,
        productionCanaryConfigs,
        canaryScope
      )
      SafeConfigProvider.validate(provider)
      provider
    }
  }
}
