package com.databricks.dicer.assigner.config

import io.prometheus.client.Gauge

import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  LoadWatcherTargetConfig,
  TargetWatchRequestRateLimitConfig
}
import com.databricks.dicer.common.TargetName

/** Contains metrics that record values in [[InternalTargetConfig]] across targets. */
object InternalTargetConfigMetrics {

  /**
   * Metric set to 1 for each target name the Assigner has a configuration for. This provides an
   * explicit signal of which targets are configured, which can be used to count configured targets
   * or as a join target in dashboards and alerts.
   */
  private val targetConfigured = Gauge
    .build()
    .name("dicer_assigner_target_configured")
    .help("Set to 1 for each configured target name.")
    .labelNames("targetName")
    .register()

  /** Metric indicating whether load balancing is enabled for targets with a given name. */
  private val loadBalancingConfigEnabled = Gauge
    .build()
    .name("dicer_assigner_load_balancing_enabled")
    .help("Whether load balancing is enabled for each target name.")
    .labelNames("targetName")
    .register()

  private val stateTransferConfigEnabled = Gauge
    .build()
    .name("dicer_assigner_state_transfer_enabled")
    .help("Whether state transfer is enabled for each target name.")
    .labelNames("targetName")
    .register()

  private val useAlternativeTargetEnabled = Gauge
    .build()
    .name("dicer_assigner_use_alternative_target_enabled")
    .help("Set to 1 for target names with use_alternative_target enabled and 0 otherwise.")
    .labelNames("targetName")
    .register()

  /*
   * Metric containing configured values for fields in [[LoadBalancingMetricConfigP]] for each
   * target name. These values are configured through target config and they become irrelevant when
   * load balancing is disabled (i.e., when dicer_assigner_load_balancing_enabled is false).
   */
  private val targetConfigPrimaryRateMetricConfigMaxLoadHint = Gauge
    .build()
    .name("dicer_assigner_target_config_primary_rate_metric_config_max_load_hint")
    .help("The max load hint configured for each target name.")
    .labelNames("targetName")
    .register()

  private val targetConfigPrimaryRateMetricConfigImbalanceToleranceRatio = Gauge
    .build()
    .name("dicer_assigner_target_config_primary_rate_metric_config_imbalance_tolerance_ratio")
    .help("The imbalance tolerance ratio configured for each target name.")
    .labelNames("targetName")
    .register()

  private val targetConfigPrimaryRateMetricConfigLoadReservationRatio = Gauge
    .build()
    .name("dicer_assigner_target_config_primary_rate_metric_config_uniform_load_reservation_ratio")
    .help("The uniform load reservation ratio configured for each target name.")
    .labelNames("targetName")
    .register()

  /*
   * Metrics containing configured values for fields in [[LoadWatcherConfigP]] for each target.
   * These fields are configured through advanced target config.
   */
  private val advancedTargetConfigLoadWatcherConfigMinDurationSeconds = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_load_watcher_config_min_duration_seconds")
    .help("The min duration seconds configured for each target name.")
    .labelNames("targetName")
    .register()

  private val advancedTargetConfigLoadWatcherConfigMaxAgeSeconds = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_load_watcher_config_max_age_seconds")
    .help("The max age seconds configured for each target name.")
    .labelNames("targetName")
    .register()

  private val advancedTargetConfigLoadWatcherConfigUseTopKeys = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_load_watcher_config_use_top_keys")
    .help("The use top keys configured for each target name.")
    .labelNames("targetName")
    .register()

  private val advancedTargetConfigLoadWatcherConfigUseLoadDistribution = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_load_watcher_config_use_load_distribution")
    .help("The use load distribution configured for each target name.")
    .labelNames("targetName")
    .register()

  /**
   * Metrics containing configured values for fields in [[KeyReplicationConfigP]] for each target.
   * These fields are configured through advanced target config.
   */
  private val advancedTargetConfigKeyReplicationConfigMinReplicas = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_key_replication_config_min_replicas")
    .help("The minReplicas configured for each target name.")
    .labelNames("targetName")
    .register()

  private val advancedTargetConfigKeyReplicationConfigMaxReplicas = Gauge
    .build()
    .name("dicer_assigner_advanced_target_config_key_replication_config_max_replicas")
    .help("The maxReplicas configured for each target name.")
    .labelNames("targetName")
    .register()

  /**
   * Metric containing the configured watch request rate limit (requests per second per client)
   * for each target name. Configured through advanced target config.
   */
  private val advancedTargetConfigWatchRequestRateLimitClientRequestsPerSecond = Gauge
    .build()
    .name(
      "dicer_assigner_advanced_target_config_watch_request_rate_limit_client_requests_per_second"
    )
    .help("The watch request rate limit (requests per second per client) for each target name.")
    .labelNames("targetName")
    .register()

  /** Exports values in `targetConfig` to metrics. */
  def exportAssignerConfigStats(
      targetName: TargetName,
      targetConfig: InternalTargetConfig): Unit = {
    targetConfigured.labels(targetName.value).set(1)
    setLoadWatcherConfigStats(targetName, targetConfig.loadWatcherConfig)
    loadBalancingConfigEnabled.labels(targetName.value).set(1)
    setLoadBalancingConfigStats(targetName.value, targetConfig.loadBalancingConfig)
    // TODO(<internal bug>): Remove the stateTransferConfigEnabled metric and graph after the
    //                  enableStateTransfer config is removed from the code in all places.
    stateTransferConfigEnabled.labels(targetName.value).set(1)
    useAlternativeTargetEnabled
      .labels(targetName.value)
      .set(if (targetConfig.useAlternativeTarget) 1 else 0)
    setKeyReplicationConfigStats(targetName, targetConfig.keyReplicationConfig)
    setWatchRequestRateLimitConfigStats(targetName, targetConfig.targetRateLimitConfig)
  }

  /** Set to 1 when polling SAFE dynamic target config fails or times out; cleared on success. */
  private val dynamicConfigUnavailabilityGauge = Gauge
    .build()
    .name("dicer_dynamic_config_unavailable")
    .help(
      "Set to 1 when polling SAFE dynamic target config fails or times out; " +
      "cleared when polling succeeds."
    )
    .register()

  /**
   * Set to 1 when at least one dynamic target config value polled from SAFE cannot be parsed or
   * fails validation.
   */
  private val dynamicConfigMalformedGauge = Gauge
    .build()
    .name("dicer_malformed_dynamic_config")
    .help("Set to 1 when at least one Dicer dynamic target config is malformed.")
    .register()

  /**
   * Set to 1 for each static target without a valid dynamic config. This metric is only set when
   * [[DynamicTargetConfigProvider]] is used.
   *
   * Note that when SAFE gradual rollout is enabled, the dynamic config rollout might be slower than
   * the binary rollout, so it's possible that the target is missing the dynamic config for a longer
   * period of time.
   */
  private val staticTargetMissingDynamicConfigGauge = Gauge
    .build()
    .name("dicer_static_target_missing_dynamic_config")
    .help("Set to 1 when a static target is missing a valid dynamic config.")
    .labelNames("targetName")
    .register()

  /**
   * Set to 1 for each target present in [[DynamicTargetConfigProvider]]'s current serving config
   * but absent from static config.
   */
  private val dynamicOnlyTargetGauge = Gauge
    .build()
    .name("dicer_assigner_dynamic_only_target")
    .help("Whether a target is present in current serving config but absent from static config.")
    .labelNames("targetName")
    .register()

  def setDynamicConfigUnavailableMetrics(unavailable: Boolean): Unit = {
    dynamicConfigUnavailabilityGauge.set(if (unavailable) 1 else 0)
  }

  def setDynamicConfigMalformedMetrics(malformed: Boolean): Unit = {
    dynamicConfigMalformedGauge.set(if (malformed) 1 else 0)
  }

  /** Set the static target missing dynamic config metric for a given target. */
  def setStaticTargetMissingDynamicConfigMetrics(targetName: TargetName, missing: Boolean): Unit = {
    staticTargetMissingDynamicConfigGauge.labels(targetName.value).set(if (missing) 1 else 0)
  }

  /** Set the dynamic-only target metric for a given target. */
  def setDynamicOnlyTargetMetrics(targetName: TargetName): Unit = {
    dynamicOnlyTargetGauge.labels(targetName.value).set(1)
  }

  /** Clears the dynamic-only target metric for all targets. */
  def clearAllDynamicOnlyTargetMetrics(): Unit = {
    dynamicOnlyTargetGauge.clear()
  }

  /** Exports the fields in `loadWatcherConfig` to metrics. */
  private def setLoadWatcherConfigStats(
      targetName: TargetName,
      loadWatcherConfig: LoadWatcherTargetConfig): Unit = {
    advancedTargetConfigLoadWatcherConfigMinDurationSeconds
      .labels(targetName.value)
      .set(loadWatcherConfig.minDuration.toSeconds)
    advancedTargetConfigLoadWatcherConfigMaxAgeSeconds
      .labels(targetName.value)
      .set(loadWatcherConfig.maxAge.toSeconds)
    advancedTargetConfigLoadWatcherConfigUseTopKeys
      .labels(targetName.value)
      .set(if (loadWatcherConfig.useTopKeys) 1 else 0)
    advancedTargetConfigLoadWatcherConfigUseLoadDistribution
      .labels(targetName.value)
      .set(if (loadWatcherConfig.useLoadDistribution) 1 else 0)
  }

  /** Exports the fields in `keyReplicationConfig` to metrics. */
  private def setKeyReplicationConfigStats(
      targetName: TargetName,
      keyReplicationConfig: KeyReplicationConfig): Unit = {
    advancedTargetConfigKeyReplicationConfigMinReplicas
      .labels(targetName.value)
      .set(keyReplicationConfig.minReplicas)
    advancedTargetConfigKeyReplicationConfigMaxReplicas
      .labels(targetName.value)
      .set(keyReplicationConfig.maxReplicas)
  }

  /** Exports the fields in `loadBalancingConfig` to metrics. */
  private def setLoadBalancingConfigStats(
      targetName: String,
      loadBalancingConfig: LoadBalancingConfig): Unit = {
    val primaryRateMetric: LoadBalancingMetricConfig = loadBalancingConfig.primaryRateMetric
    targetConfigPrimaryRateMetricConfigMaxLoadHint
      .labels(targetName)
      .set(loadBalancingConfig.primaryRateMetric.maxLoadHint)
    targetConfigPrimaryRateMetricConfigImbalanceToleranceRatio
      .labels(targetName)
      .set(primaryRateMetric.imbalanceToleranceRatio)
    targetConfigPrimaryRateMetricConfigLoadReservationRatio
      .labels(targetName)
      .set(primaryRateMetric.uniformLoadReservationRatio)
  }

  /** Exports the fields in `rateLimitConfig` to metrics. */
  private def setWatchRequestRateLimitConfigStats(
      targetName: TargetName,
      rateLimitConfig: TargetWatchRequestRateLimitConfig): Unit = {
    advancedTargetConfigWatchRequestRateLimitClientRequestsPerSecond
      .labels(targetName.value)
      .set(rateLimitConfig.clientRequestsPerSecond)
  }

  object forTest {

    /** Resets all metrics. */
    def clearMetrics(): Unit = {
      targetConfigured.clear()
      loadBalancingConfigEnabled.clear()
      stateTransferConfigEnabled.clear()
      useAlternativeTargetEnabled.clear()
      targetConfigPrimaryRateMetricConfigMaxLoadHint.clear()
      targetConfigPrimaryRateMetricConfigImbalanceToleranceRatio.clear()
      targetConfigPrimaryRateMetricConfigLoadReservationRatio.clear()
      advancedTargetConfigLoadWatcherConfigMinDurationSeconds.clear()
      advancedTargetConfigLoadWatcherConfigMaxAgeSeconds.clear()
      advancedTargetConfigLoadWatcherConfigUseTopKeys.clear()
      advancedTargetConfigLoadWatcherConfigUseLoadDistribution.clear()
      dynamicConfigUnavailabilityGauge.clear()
      dynamicConfigMalformedGauge.clear()
      staticTargetMissingDynamicConfigGauge.clear()
      clearAllDynamicOnlyTargetMetrics()
      advancedTargetConfigWatchRequestRateLimitClientRequestsPerSecond.clear()
    }
  }
}
