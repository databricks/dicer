package com.databricks.dicer.client.featurerollouts

import java.io.File

import scala.concurrent.duration._
import scala.io.{BufferedSource, Source}

import javax.annotation.concurrent.ThreadSafe
import scalapb.TextFormatException

import com.databricks.api.proto.dicer.client.featurerollouts.DicerClientFeatureRolloutConfigP
import com.databricks.caching.util.{CachingErrorCode, DeterministicSampling, PrefixLogger, Severity}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.external.Target

/**
 * The production implementation of [[DicerClientFeatureRolloutFlag]] that resolves the rollout
 * flags from the .textproto files. Instances should be created by
 * [[DicerClientFeatureRolloutFlagImpl.create]].
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

  private val logger: PrefixLogger =
    PrefixLogger.create(getClass, DicerClientFeatureRolloutFlagImpl.LOGGER_PREFIX)

  final override def isEnabled(flagName: String, target: Target): Boolean = {
    ruleByFlagName.get(flagName) match {
      case Some(rule: DicerClientFeatureRolloutRule) =>
        applyRuleForTarget(rule, target, flagName)
      case None =>
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.DICER_CLIENT_FEATURE_ROLLOUT_FLAG_NOT_FOUND,
          s"Feature rollout config not found for '$flagName'; defaults to false",
          every = DicerClientFeatureRolloutFlagImpl.FLAG_NOT_FOUND_ALERT_THROTTLE
        )
        false
    }
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

private[featurerollouts] object DicerClientFeatureRolloutFlagImpl {

  /** The suffix for the configuration files, which must be .textproto files. */
  private val CONFIG_FILE_SUFFIX: String = ".textproto"

  /**
   * Prefix attached to alerts and log lines from this component so the `prefix` label on
   * `caching_errors` metrics distinguishes them from other callers.
   */
  private val LOGGER_PREFIX: String = "dicer-client-feature-rollout"

  /** Rate at which the missing-flag alert may fire. */
  private val FLAG_NOT_FOUND_ALERT_THROTTLE: FiniteDuration = 1.minute

  /**
   * Creates a [[DicerClientFeatureRolloutFlagImpl]] by loading every `.textproto` file in
   * `configDirPath` and resolving each feature's applicable rule for `regionUri`. Misconfiguration
   * fails fast at process startup rather than being papered over with degraded-mode flags, so
   * deployments with a bad config directory don't silently roll out behavior changes.
   */
  @throws[IllegalArgumentException]("If `configDirPath` does not exist or is not a directory.")
  @throws[IllegalArgumentException]("if any `.textproto` file in `configDirPath` is malformed.")
  private[featurerollouts] def create(
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
