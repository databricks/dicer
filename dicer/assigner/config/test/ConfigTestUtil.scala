package com.databricks.dicer.assigner.config

import java.io.File
import scala.concurrent.duration._
import io.prometheus.client.CollectorRegistry
import org.scalatest.Assertions.assertResult
import os.Path
import com.databricks.api.proto.dicer.assigner.config.{
  AdvancedTargetConfigFieldsP,
  LoadWatcherConfigP
}
import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.api.proto.dicer.external.{LoadBalancingMetricConfigP, TargetConfigFieldsP}
import com.databricks.caching.util.{AssertionWaiter, CachingErrorCode, MetricUtils, Severity}
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  HealthWatcherTargetConfig,
  KeyOfDeathProtectionConfig,
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  LoadWatcherTargetConfig,
  TargetWatchRequestRateLimitConfig
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.external.Target

/**
 *  Utility object for configuration testing.
 *  It provides the following functionalities:
 *    - ConfigWriter: this class provides a convenient way to handle configuration testing by
 *      writing configs to temporary directories.
 *    - createConfig(): this method creates an [[InternalTargetConfig]] given a handful of optional
 *      parameter values.
 */
object ConfigTestUtil {

  /** Helper writing target configs to temporary directories. */
  class ConfigWriter {

    // Temporary directories created for this writer.
    private val path: Path = os.temp.dir()
    private val advancedPath: Path = os.temp.dir()

    /**
     * Writes a file with the given `filename` and `contents` to the temporary directory from which
     * target configuration files will be read. We intentionally take a [[String]] for the contents
     * rather than [[TargetConfigP]] because we want the tests to clearly model what our customers
     * will be doing when they write their own config files.
     */
    def writeConfig(filename: String, contents: String): Unit = {
      val configPath = path / filename
      os.write(configPath, contents)
    }

    /**
     * Writes a file with the given `directory`, filename`, and `contents` to the temporary
     * directory from which target configuration files will be read. See [[writeConfig]] for
     * details.
     */
    def writeConfigInDirectory(directory: String, filename: String, contents: String): Unit = {
      val configPath: Path = path / directory / filename
      os.write(configPath, contents, createFolders = true)
    }

    /**
     * Writes a file with the given `filename` and `contents` to the temporary directory from which
     * advanced configuration files will be read. See remarks on [[writeConfig]].
     */
    def writeAdvancedConfig(filename: String, contents: String): Unit = {
      val configPath = advancedPath / filename
      os.write(configPath, contents)
    }

    /** Returns the directory for target configs. */
    def getTargetConfigDirectory: File = new File(path.toString())

    /** Returns the directory for advanced target configs. */
    def getAdvancedTargetConfigDirectory: File = new File(advancedPath.toString())
  }

  /**
   * Creates an [[InternalTargetConfig]] given a handful of optional parameter values. Double-checks
   * that proto parsing works as expected. This is a useful convenience method because most
   * configuration parameters are not settable via protos at present.
   */
  def createConfig(
      primaryRateMaxLoadHint: Double,
      primaryRateImbalanceToleranceHintOpt: Option[ImbalanceToleranceHintP] = None,
      primaryRateReservationHintOpt: Option[ReservationHintP] = None,
      watchMinDurationSecondsOpt: Option[Int] = None): InternalTargetConfig = {
    val lbConfig: LoadBalancingConfig = LoadBalancingConfig(
      loadBalancingInterval = LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL,
      churnConfig = ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(
        maxLoadHint = primaryRateMaxLoadHint,
        imbalanceToleranceHint =
          primaryRateImbalanceToleranceHintOpt.getOrElse(ImbalanceToleranceHintP.DEFAULT),
        uniformLoadReservationHint =
          primaryRateReservationHintOpt.getOrElse(ReservationHintP.NO_RESERVATION)
      )
    )
    val loadWatcherConfig: LoadWatcherTargetConfig = watchMinDurationSecondsOpt match {
      case Some(watchMinDurationSeconds) =>
        LoadWatcherTargetConfig.DEFAULT.copy(minDuration = watchMinDurationSeconds.seconds)
      case None => LoadWatcherTargetConfig.DEFAULT
    }
    val config = InternalTargetConfig(
      loadWatcherConfig = loadWatcherConfig,
      loadBalancingConfig = lbConfig,
      keyReplicationConfig = KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
      healthWatcherConfig = HealthWatcherTargetConfig.DEFAULT,
      keyOfDeathProtectionConfig = KeyOfDeathProtectionConfig.DEFAULT,
      targetRateLimitConfig = TargetWatchRequestRateLimitConfig.DEFAULT
    )
    // Try parsing as well, just to make sure the assumptions in this helper are valid.
    val proto = TargetConfigFieldsP(
      primaryRateMetricConfig = Some(
        LoadBalancingMetricConfigP(
          Some(primaryRateMaxLoadHint),
          primaryRateImbalanceToleranceHintOpt,
          primaryRateReservationHintOpt
        )
      )
    )
    val advancedProto: AdvancedTargetConfigFieldsP = watchMinDurationSecondsOpt match {
      case Some(watchMinDurationSeconds: Int) =>
        AdvancedTargetConfigFieldsP(
          loadWatcherConfig =
            Some(LoadWatcherConfigP(minDurationSeconds = Some(watchMinDurationSeconds)))
        )
      case None => AdvancedTargetConfigFieldsP()
    }
    // When parsing, the choice of config scope has no effect because we haven't defined any
    // overrides.
    val parsedConfig = InternalTargetConfig.fromProtos(proto, advancedProto)
    assert(config == parsedConfig)
    config
  }

  // Helpers on InternalTargetConfigMetrics.
  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  private val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /**
   * Asserts that the `missingTargetsEventCount` is continuously increasing.
   * At least 2 increments are required to confirm that the event count consistently increases
   * before the abnormality gets resolved.
   */
  def mssingTargetsEventCountIncreasing(): Unit = {
    val initialEventCount: Int = MetricUtils.getPrefixLoggerErrorCount(
      Severity.DEGRADED,
      CachingErrorCode.MISSING_TARGETS_IN_DYNAMIC_CONFIG,
      prefix = ""
    )
    AssertionWaiter("missing targets event count increasing").await {
      assert(
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.DEGRADED,
          CachingErrorCode.MISSING_TARGETS_IN_DYNAMIC_CONFIG,
          prefix = ""
        ) > initialEventCount + 2
      )
    }
  }

  def getDynamicConfigUnavailableValue: Int =
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_dynamic_config_unavailable",
        Map.empty[String, String]
      )
      .toInt

  def getDynamicConfigMalformedValue: Int =
    MetricUtils
      .getMetricValue(
        registry,
        metric = "dicer_malformed_dynamic_config",
        Map.empty[String, String]
      )
      .toInt

  /**
   * Asserts that config values recorded in metrics are the same as in the expected config.
   * @param config the config being matched.
   */
  def assertMetricsMatchTargetConfig(target: Target, config: InternalTargetConfig): Unit = {
    // Compare load balancing config.
    val loadBalancingConfigEnabledValue: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_load_balancing_enabled",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val maxLoadHint: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_target_config_primary_rate_metric_config_max_load_hint",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val imbalanceToleranceRatio: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_target_config_primary_rate_metric_config_imbalance_tolerance_ratio",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val reservationRatio: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_target_config_primary_rate_metric_config_uniform_load_reservation_ratio",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val primaryRateMetric: LoadBalancingMetricConfig = config.loadBalancingConfig.primaryRateMetric
    assertResult(expected = 1)(loadBalancingConfigEnabledValue)
    assertResult(primaryRateMetric.maxLoadHint)(maxLoadHint)
    assertResult(primaryRateMetric.imbalanceToleranceRatio)(imbalanceToleranceRatio)
    assertResult(primaryRateMetric.uniformLoadReservationRatio)(reservationRatio)

    // Compare load watcher config.
    val minDurationSeconds: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_load_watcher_config_min_duration_seconds",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val maxAgeSeconds: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_load_watcher_config_max_age_seconds",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val useTopKeys: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_load_watcher_config_use_top_keys",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val loadWatcherConfig: LoadWatcherTargetConfig = config.loadWatcherConfig
    assertResult(loadWatcherConfig.minDuration.toSeconds)(minDurationSeconds)
    assertResult(loadWatcherConfig.maxAge.toSeconds)(maxAgeSeconds)
    assertResult(loadWatcherConfig.useTopKeys)(useTopKeys == 1)

    val stateTransferConfigEnabledValue: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_state_transfer_enabled",
        Map("targetName" -> target.getTargetNameLabel)
      )
    // State transfer metric is always exported to be 1 to indicate that Dicer always generates
    // state transfer annotation for all targets.
    assertResult(1)(stateTransferConfigEnabledValue)

    // Compare key replication config.
    val minReplicas: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_key_replication_config_min_replicas",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val maxReplicas: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_key_replication_config_max_replicas",
        Map("targetName" -> target.getTargetNameLabel)
      )
    val keyReplicationConfig: KeyReplicationConfig = config.keyReplicationConfig
    assertResult(keyReplicationConfig.minReplicas)(minReplicas)
    assertResult(keyReplicationConfig.maxReplicas)(maxReplicas)

    // Compare watch request rate limit config.
    val clientRequestsPerSecond: Double = MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_advanced_target_config_watch_request_rate_limit_client_requests_per_second",
        Map("targetName" -> target.getTargetNameLabel)
      )
    assertResult(config.targetRateLimitConfig.clientRequestsPerSecond.toDouble)(
      clientRequestsPerSecond
    )
  }
}
