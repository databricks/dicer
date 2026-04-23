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
    setLoadWatcherConfigStats(targetName, targetConfig.loadWatcherConfig)
    loadBalancingConfigEnabled.labels(targetName.value).set(1)
    setLoadBalancingConfigStats(targetName.value, targetConfig.loadBalancingConfig)
    // TODO(<internal bug>): Remove the stateTransferConfigEnabled metric and graph after the
    //                  enableStateTransfer config is removed from the code in all places.
    stateTransferConfigEnabled.labels(targetName.value).set(1)
    setKeyReplicationConfigStats(targetName, targetConfig.keyReplicationConfig)
    setWatchRequestRateLimitConfigStats(targetName, targetConfig.targetRateLimitConfig)
  }

  /** A gauge indicating whether the dynamic values are unavailable during Assigner startup. */
  private val dynamicConfigUnavailabilityGauge = Gauge
    .build()
    .name("dicer_dynamic_config_unavailable")
    .help("Whether the dicer dynamic values are unavailable")
    .register()

  /** A gauge indicating whether the malformed value is polled form SAFE. */
  private val dynamicConfigMalformedGauge = Gauge
    .build()
    .name("dicer_malformed_dynamic_config")
    .help("Whether the dicer dynamic values are malformed")
    .register()

  def setDynamicConfigUnavailableMetrics(unavailable: Boolean): Unit = {
    dynamicConfigUnavailabilityGauge.set(if (unavailable) 1 else 0)
  }

  def setDynamicConfigMalformedMetrics(malformed: Boolean): Unit = {
    dynamicConfigMalformedGauge.set(if (malformed) 1 else 0)
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
      loadBalancingConfigEnabled.clear()
      stateTransferConfigEnabled.clear()
      targetConfigPrimaryRateMetricConfigMaxLoadHint.clear()
      targetConfigPrimaryRateMetricConfigImbalanceToleranceRatio.clear()
      targetConfigPrimaryRateMetricConfigLoadReservationRatio.clear()
      advancedTargetConfigLoadWatcherConfigMinDurationSeconds.clear()
      advancedTargetConfigLoadWatcherConfigMaxAgeSeconds.clear()
      advancedTargetConfigLoadWatcherConfigUseTopKeys.clear()
      dynamicConfigUnavailabilityGauge.clear()
      dynamicConfigMalformedGauge.clear()
      advancedTargetConfigWatchRequestRateLimitClientRequestsPerSecond.clear()
    }
  }
}
