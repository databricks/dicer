package com.databricks.dicer.client.featurerollouts

import java.io.File

import scala.io.{BufferedSource, Source}

import javax.annotation.concurrent.ThreadSafe
import scalapb.TextFormatException
import io.prometheus.client.Gauge

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutConfigP
import com.databricks.caching.util.{
  CachingErrorCode,
  CachingErrorMetrics,
  DeterministicSampling,
  Severity,
  WhereAmIHelper
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.external.Target

/**
 * The production implementation of [[DicerClientFeatureRolloutFlag]] that resolves the rollout
 * flags from the .textproto files. Instances should be created by
 * [[DicerClientFeatureRolloutFlagImpl.createForPathAndRegion]].
 *
 * @param ruleByFlagName Per-feature applicable [[DicerClientFeatureRolloutRule]] for the captured
 *                       region. Features with no applicable rule (i.e. no default and no matching
 *                       override), or not present in the deployment environment's configuration
 *                       directory, will be absent from the map and trigger the missing-flag alert
 *                       when being queried.
 */
@ThreadSafe
private[featurerollouts] class DicerClientFeatureRolloutFlagImpl private (
    private val ruleByFlagName: Map[String, DicerClientFeatureRolloutRule])
    extends DicerClientFeatureRolloutFlag {

  final override def isEnabled(flagName: String, target: Target): Boolean = {
    val enabled: Boolean = ruleByFlagName.get(flagName) match {
      case Some(rule: DicerClientFeatureRolloutRule) =>
        applyRuleForTarget(rule, target, flagName)
      case None =>
        CachingErrorMetrics.recordError(
          Severity.DEGRADED,
          CachingErrorCode.DICER_CLIENT_FEATURE_ROLLOUT_FLAG_NOT_FOUND,
          s"${DicerClientFeatureRolloutFlagImpl.ALERT_PREFIX}," +
          s"flagName=$flagName,targetName=${target.name}"
        )
        false
    }
    DicerClientFeatureRolloutFlagImpl.recordIsEnabled(flagName, target, enabled)
    enabled
  }

  /** Evaluates the rollout rule to determine whether the feature is enabled for `target`. */
  private def applyRuleForTarget(
      rule: DicerClientFeatureRolloutRule,
      target: Target,
      flagName: String): Boolean = {
    val targetName: TargetName = TargetName.forTarget(target)
    if (rule.forceEnableTargetNames.contains(targetName)) {
      true
    } else if (rule.forceDisableTargetNames.contains(targetName)) {
      false
    } else {
      DeterministicSampling.isSampled(
        // The enable fraction should be applied to the fully-qualified target instance.
        item = target.toParseableDescription,
        sampleNamespace = flagName,
        sampleFraction = rule.targetInstanceEnableFraction
      )
    }
  }
}

private[dicer] object DicerClientFeatureRolloutFlagImpl {

  /**
   * Universe-relative root containing per-environment subdirectories (dev/, staging/, prod/)
   * of .textproto rollout files.
   */
  private val CONFIG_BASE_DIR: String = "dicer/client/feature-rollouts"

  /** The suffix for the configuration files, which must be .textproto files. */
  private val CONFIG_FILE_SUFFIX: String = ".textproto"

  /**
   * Prefix label on `caching_errors` metric so alert dashboards distinguish this component from
   * other callers.
   */
  private val ALERT_PREFIX: String = "dicer-client-feature-rollout"

  /**
   * Gauge reporting the last resolved enabled state of each (target, flagName) pair queried via
   * [[isEnabled]]. Set to 1.0 when enabled and 0.0 when disabled (including the flag-not-found
   * path).
   */
  private val isEnabledGauge: Gauge = Gauge
    .build()
    .name("dicer_client_feature_rollout_flag_enabled")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "flagName")
    .help(
      "The last resolved enabled state of a Dicer client feature rollout flag for a given " +
      "target. 1.0 means enabled, 0.0 means disabled. Updated on every isEnabled query."
    )
    .register()

  /**
   * Records the resolved enabled state for `flagName` and `target` on [[isEnabledGauge]]. Set to
   * 1.0 when `enabled` is true and 0.0 otherwise.
   */
  private def recordIsEnabled(flagName: String, target: Target, enabled: Boolean): Unit = {
    isEnabledGauge
      .labels(
        target.getTargetClusterLabel,
        target.name,
        target.getTargetInstanceIdLabel,
        flagName
      )
      .set(if (enabled) 1.0 else 0.0)
  }

  /**
   * Fallback [[DicerClientFeatureRolloutFlag]] whose [[isEnabled]] always returns false. Returned
   * by [[createForPathAndRegion]] when the deployment environment or region URI is unavailable at
   * process startup, so feature-rollout consumers degrade to the disabled (safe) path until the
   * next deploy with a correctly resolved WhereAmI.
   */
  private val ALL_DISABLED_FLAG: DicerClientFeatureRolloutFlag =
    new DicerClientFeatureRolloutFlag {
      override def isEnabled(flagName: String, target: Target): Boolean = false
    }

  /**
   * Creates a production [[DicerClientFeatureRolloutFlag]] by loading every `.textproto` file from
   * the per-environment subdirectory under [[CONFIG_BASE_DIR]] selected by the current environment
   * where the code is running, and resolving each feature's rule for the current region from the
   * WhereAmI environment variables. If the environment or region URI is unavailable, fires the
   * corresponding alert(s) and returns an [[DicerClientFeatureRolloutFlag]] that always returns
   * false.
   */
  private[dicer] def create(): DicerClientFeatureRolloutFlag = {
    val envOpt: Option[String] = WhereAmIHelper.getEnvironment
    val regionUriOpt: Option[String] = WhereAmIHelper.getRegionUri
    (envOpt, regionUriOpt) match {
      case (Some(envSubdir: String), Some(regionUri: String)) =>
        createForPathAndRegion(configDirPath = s"$CONFIG_BASE_DIR/$envSubdir", regionUri)
      case _ =>
        if (envOpt.isEmpty) {
          CachingErrorMetrics.recordError(
            Severity.DEGRADED,
            CachingErrorCode.DICER_CLIENT_FEATURE_ROLLOUT_ENV_UNAVAILABLE,
            ALERT_PREFIX
          )
        }
        if (regionUriOpt.isEmpty) {
          CachingErrorMetrics.recordError(
            Severity.DEGRADED,
            CachingErrorCode.DICER_CLIENT_FEATURE_ROLLOUT_REGION_URI_UNAVAILABLE,
            ALERT_PREFIX
          )
        }
        ALL_DISABLED_FLAG
    }
  }

  /**
   * Creates a [[DicerClientFeatureRolloutFlagImpl]] by loading every `.textproto` file in
   * `configDirPath` and resolving each feature's applicable rule for `regionUri`. Misconfiguration
   * fails fast at process startup rather than being papered over with degraded-mode flags, so
   * deployments with a bad config directory don't silently roll out behavior changes.
   */
  @throws[IllegalArgumentException]("If `configDirPath` does not exist or is not a directory.")
  @throws[IllegalArgumentException]("if any `.textproto` file in `configDirPath` is malformed.")
  private[featurerollouts] def createForPathAndRegion(
      configDirPath: String,
      regionUri: String): DicerClientFeatureRolloutFlagImpl = {
    val configsByFlagName: Map[String, DicerClientFeatureRolloutConfig] =
      loadConfigsByFlagName(configDirPath)
    val ruleByFlagName: Map[String, DicerClientFeatureRolloutRule] =
      resolveRulesByRegion(configsByFlagName, regionUri)
    new DicerClientFeatureRolloutFlagImpl(ruleByFlagName)
  }

  /**
   * Converts a Map of feature flag names to [[DicerClientFeatureRolloutConfig]] to a map of flag
   * names to [[DicerClientFeatureRolloutRule]]. For each configured feature, it resolves the
   * rollout rule applicable to `regionUri` (the matching override rule, falling back to the
   * default rule). Features with neither an override rule nor a default rule are omitted.
   */
  private def resolveRulesByRegion(
      configsByFlagName: Map[String, DicerClientFeatureRolloutConfig],
      regionUri: String): Map[String, DicerClientFeatureRolloutRule] = {
    val builder = Map.newBuilder[String, DicerClientFeatureRolloutRule]
    for (entry <- configsByFlagName) {
      val (flagName, config): (String, DicerClientFeatureRolloutConfig) = entry
      // Try to an override rule for the flag name and the region.
      val overrideRuleOpt: Option[DicerClientFeatureRolloutRule] =
        config.overrideRules
          .find { ruleOverride: DicerClientFeatureRolloutRuleOverride =>
            // The RuleOverride should contain one scope that matches the desired region.
            ruleOverride.overrideScopes.exists { scope: DicerClientFeatureRolloutScope =>
              scope.regionUri == regionUri
            }
          }
          .map((_: DicerClientFeatureRolloutRuleOverride).overrideRule)
      // Append the override rule or the default rule to the resulting map.
      for (rule: DicerClientFeatureRolloutRule <- overrideRuleOpt.orElse(config.defaultRuleOpt)) {
        builder += flagName -> rule
      }
    }
    builder.result()
  }

  /**
   * Loads every `.textproto` file in `configDirPath` into a map keyed by feature flag names that
   * are the same as the file names.
   */
  @throws[IllegalArgumentException]("If `configDirPath` does not exist or is not a directory.")
  @throws[IllegalArgumentException]("if any `.textproto` file in `configDirPath` is malformed.")
  private def loadConfigsByFlagName(
      configDirPath: String): Map[String, DicerClientFeatureRolloutConfig] = {
    val configDir = new File(configDirPath)
    require(
      configDir.isDirectory,
      s"Feature rollout config directory does not exist: $configDirPath"
    )
    val configFiles: Array[File] = configDir.listFiles.filter { file: File =>
      file.isFile && file.getName.endsWith(CONFIG_FILE_SUFFIX)
    }
    configFiles.map { file: File =>
      val featureName: String = file.getName.stripSuffix(CONFIG_FILE_SUFFIX)
      featureName -> parseConfigFile(file)
    }.toMap
  }

  /** Parses a single textproto file into a [[DicerClientFeatureRolloutConfig]] instance. */
  @throws[IllegalArgumentException]("if the is not a valid textproto.")
  private def parseConfigFile(file: File): DicerClientFeatureRolloutConfig = {
    val fileContent: String = readFileContents(file)
    try {
      val proto: DicerClientFeatureRolloutConfigP =
        DicerClientFeatureRolloutConfigP.fromAscii(fileContent)
      DicerClientFeatureRolloutConfig.fromProto(proto)
    } catch {
      case e: TextFormatException =>
        throw new IllegalArgumentException(s"Bad textproto format in $file: ${e.getMessage}", e)
    }
  }

  /** Reads `file`'s full contents as a UTF-8 string. */
  private def readFileContents(file: File): String = {
    val fileSource: BufferedSource = Source.fromFile(file, "utf-8")
    try {
      fileSource.mkString
    } finally {
      fileSource.close()
    }
  }
}
