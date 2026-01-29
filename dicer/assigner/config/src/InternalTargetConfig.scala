package com.databricks.dicer.assigner.config

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.databricks.api.proto.dicer.assigner.config.{
  AdvancedTargetConfigFieldsP,
  InternalDicerTargetConfigP,
  LoadWatcherConfigP,
  HealthWatcherConfigP
}
import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.api.proto.dicer.external.KeyReplicationConfigP
import com.databricks.api.proto.dicer.external.{LoadBalancingMetricConfigP, TargetConfigFieldsP}
import com.databricks.caching.util.JsonSerializableConfig
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  LoadBalancingConfig,
  LoadWatcherTargetConfig,
  KeyReplicationConfig,
  HealthWatcherTargetConfig,
  fromProtos
}
import com.databricks.rpc.DatabricksObjectMapper

/**
 * Stores the config for the given `target`.
 *
 * @param loadWatcherConfig    The configuration for the load watcher.
 * @param loadBalancingConfig  the configuration to use for load balancing in the Dicer assigner.
 * @param keyReplicationConfig Asymmetric key replication configuration for the target.
 * @param healthWatcherConfig  The configuration for the health watcher.
 */
case class InternalTargetConfig(
    loadWatcherConfig: LoadWatcherTargetConfig,
    loadBalancingConfig: LoadBalancingConfig,
    keyReplicationConfig: KeyReplicationConfig,
    healthWatcherConfig: HealthWatcherTargetConfig) {

  override def toString: String = {
    // Format non-default configuration parameters.
    val builder = mutable.StringBuilder.newBuilder
    builder.append(s"InternalTargetConfig(")
    if (loadWatcherConfig != LoadWatcherTargetConfig.DEFAULT) {
      builder.append(s", $loadWatcherConfig")
    }
    builder.append(s", $loadBalancingConfig")
    if (keyReplicationConfig != KeyReplicationConfig.DEFAULT_SINGLE_REPLICA) {
      builder.append(s", $keyReplicationConfig")
    }
    if (healthWatcherConfig != HealthWatcherTargetConfig.DEFAULT) {
      builder.append(s", $healthWatcherConfig")
    }
    builder.append(")").toString()
  }
}

object InternalTargetConfig {

  /**
   * Validates and parses proto representation of configuration.
   *
   * TODO(<internal bug>): incorporate advanced configuration options (`TargetConfigP` contains only
   *                  customer-controlled settings).
   * TODO(<internal bug>): take into account region overrides in `TargetConfigP`.
   *
   * @param proto Proto representation of customer-controlled configuration.
   * @param advancedProto Proto representation of Dicer-team-controlled configuration. If no
   *                      advanced configuration is present for a target, the caller should supply
   *                      the default proto, which is semantically equivalent: by design, all fields
   *                      of the advanced configuration are optional.
   */
  def fromProtos(
      proto: TargetConfigFieldsP,
      advancedProto: AdvancedTargetConfigFieldsP): InternalTargetConfig = {
    val loadWatcherConfig = LoadWatcherTargetConfig.fromProto(advancedProto.getLoadWatcherConfig)
    val primaryRateMetric = LoadBalancingMetricConfig.fromProto(proto.getPrimaryRateMetricConfig)
    // KeyReplicationConfig is an optional field in TargetConfigFields, and also used to be a
    // (now deprecated) optional field in AdvancedTargetConfigFields. The KeyReplicationConfig is
    // determined with the following priority:
    //
    // 1. The value in TargetConfigFields.
    // 2. The value in AdvancedTargetConfigFields (deprecated, for backward compatibility) if 1 is
    //    unspecified.
    // 3. A default single-replica config if neither 1 or 2 is defined.
    //
    // TODO(<internal bug>): Remove the ability to parse advancedProto.keyReplicationConfig when it's
    //                  cleaned up everywhere.
    val replicationConfig: KeyReplicationConfig =
      (proto.keyReplicationConfig, advancedProto.keyReplicationConfig) match {
        case (Some(keyReplicationConfig), _) =>
          KeyReplicationConfig.fromProto(keyReplicationConfig)
        case (None, Some(deprecatedKeyReplicationConfig)) =>
          KeyReplicationConfig.fromProto(deprecatedKeyReplicationConfig)
        case (None, None) =>
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA
      }

    // HealthWatcherConfig is an optional field in AdvancedTargetConfigFields. When not defined,
    // its single field defaults to false.
    val healthWatcherConfig: HealthWatcherTargetConfig =
      advancedProto.healthWatcherConfig
        .map(HealthWatcherTargetConfig.fromProto)
        .getOrElse(HealthWatcherTargetConfig.DEFAULT)

    // TODO(<internal bug>): populate load balancing interval from advanced config proto.
    val loadBalancingConfig =
      LoadBalancingConfig(
        loadBalancingInterval = LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL,
        ChurnConfig.DEFAULT,
        primaryRateMetric
      )
    InternalTargetConfig(
      loadWatcherConfig,
      loadBalancingConfig,
      replicationConfig,
      healthWatcherConfig
    )
  }

  /**
   * REQUIRES: `minDuration` is positive.
   * REQUIRES: `maxAge` is positive.
   *
   * Configuration for the load watcher. Since the load watcher is scoped to a single target, the
   * configuration is per-target.
   *
   * @param minDuration if a Slicelet reports load for windows shorter than this duration, those
   *                    measurements will not contribute to the [[LoadMap]] returned from
   *                    [[LoadWatcher.getPrimaryRateLoadMap()]].
   * @param maxAge      maximum age of a load report that will be incorporated into the aggregate
   *                    load map.
   * @param useTopKeys  whether fine-grained top key information reported by the Slicelet will be
   *                    used (i.e. integrated into the load map).
   */
  case class LoadWatcherTargetConfig(
      minDuration: FiniteDuration,
      maxAge: FiniteDuration,
      useTopKeys: Boolean) {
    require(minDuration > Duration.Zero, "minDuration must be positive")
    require(maxAge > Duration.Zero, "maxAge must be positive")

    override def toString: String =
      s"LoadWatcherTargetConfig(minDuration=$minDuration, maxAge=$maxAge, useTopKeys=$useTopKeys)"

    def toProto: LoadWatcherConfigP = {
      LoadWatcherConfigP.of(
        Some(minDuration.toSeconds.toInt),
        Some(maxAge.toSeconds.toInt),
        Some(useTopKeys)
      )
    }
  }

  object LoadWatcherTargetConfig {
    val DEFAULT: LoadWatcherTargetConfig =
      LoadWatcherTargetConfig(minDuration = 1.minute, maxAge = 5.minutes, useTopKeys = true)

    /** Parses and validates the proto representation of [[LoadWatcherTargetConfig]]. */
    def fromProto(proto: LoadWatcherConfigP): LoadWatcherTargetConfig = {
      val minDuration: FiniteDuration = proto.minDurationSeconds match {
        case Some(minDurationSeconds: Int) => minDurationSeconds.seconds
        case None => DEFAULT.minDuration
      }
      val maxAge: FiniteDuration = proto.maxAgeSeconds match {
        case Some(maxAgeSeconds: Int) => maxAgeSeconds.seconds
        case None => DEFAULT.maxAge
      }
      val useTopKeys: Boolean = proto.useTopKeys.getOrElse(DEFAULT.useTopKeys)
      LoadWatcherTargetConfig(minDuration, maxAge, useTopKeys)
    }
  }

  /**
   * REQUIRES: `loadBalancingInterval` is positive.
   *
   * Load-balancing configuration for a target. These parameters determine when and how load
   * balancing is performed for a particular target.
   *
   * @param loadBalancingInterval The interval at which Dicer should produce new assignments,
   *                              independent of resource health changes. Not customer configurable.
   * @param churnConfig Configuration for churn penalties applied to recently reassigned keys.
   * @param primaryRateMetric Configuration for the primary rate load metric. Note that at present,
   *                          Dicer does not support LB using multiple metrics or non-rate metrics,
   *                          but it is convenient to group related configuration parameters in this
   *                          field.
   */
  case class LoadBalancingConfig(
      loadBalancingInterval: FiniteDuration,
      churnConfig: ChurnConfig,
      primaryRateMetric: LoadBalancingMetricConfig) {
    require(loadBalancingInterval > Duration.Zero, "LB interval must be positive")

    override def toString: String = {
      // Format non-default configuration parameters.
      val builder = mutable.StringBuilder.newBuilder
      builder.append(s"LoadBalancingConfig(primaryRate=$primaryRateMetric")
      if (loadBalancingInterval != LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL) {
        builder.append(s", LoadBalancingInterval=$loadBalancingInterval")
      }
      if (churnConfig != ChurnConfig.DEFAULT) {
        builder.append(s", $churnConfig")
      }
      builder.append(")").toString()
    }
  }

  object LoadBalancingConfig {

    /** Default [[LoadBalancingConfig.loadBalancingInterval]] value. */
    val DEFAULT_LOAD_BALANCING_INTERVAL: FiniteDuration = 1.minute
  }

  /**
   * REQUIRES: `maxLoadHint` is a positive, finite number.
   *
   * @param maxLoadHint The maximum load that a resource can handle. See
   *                    [[LoadBalancingMetricConfigP.maxLoadHint]] for details.
   * @param imbalanceToleranceHint The tolerance for load imbalance in this metric. See
   *                               [[LoadBalancingMetricConfigP]] for details.
   * @param uniformLoadReservationHint Determines how much reserved load, uniformly distributed in
   *                                   the hashed key space, should be accounted for when load
   *                                   balancing. See [[ReservationHintP]] for details.
   */
  case class LoadBalancingMetricConfig(
      maxLoadHint: Double,
      imbalanceToleranceHint: ImbalanceToleranceHintP = ImbalanceToleranceHintP.DEFAULT,
      uniformLoadReservationHint: ReservationHintP = ReservationHintP.NO_RESERVATION) {
    require(
      maxLoadHint.signum > 0 && !maxLoadHint.isNaN && !maxLoadHint.isInfinite,
      "max load must be a positive, finite number"
    )

    override def toString: String = {
      // Format non-default configuration parameters.
      val builder = mutable.StringBuilder.newBuilder
      builder.append(s"LoadBalancingMetricConfig(maxLoadHint=$maxLoadHint")
      if (imbalanceToleranceHint != ImbalanceToleranceHintP.DEFAULT) {
        builder.append(s", imbalanceToleranceHint=$imbalanceToleranceHint")
      }
      if (uniformLoadReservationHint != ReservationHintP.NO_RESERVATION) {
        builder.append(s", uniformLoadReservationHint=$uniformLoadReservationHint")
      }
      builder.append(")").toString()
    }

    def toProto: LoadBalancingMetricConfigP = {
      LoadBalancingMetricConfigP.of(
        Some(maxLoadHint),
        Some(imbalanceToleranceHint),
        Some(uniformLoadReservationHint)
      )
    }

    /**
     * Returns the imbalance ratio above which Dicer will try to rebalance the load. See
     * [[LoadBalancingMetricConfigP.ImbalanceToleranceHintP]] for the definition of imbalance ratio.
     */
    def imbalanceToleranceRatio: Double = imbalanceToleranceHint match {
      case ImbalanceToleranceHintP.DEFAULT => 0.1
      case ImbalanceToleranceHintP.TIGHT => 0.025
      case ImbalanceToleranceHintP.LOOSE => 0.4
    }

    /**
     * Returns the ratio of capacity Dicer will reserve for potential future load.
     * See comments in [[LoadBalancingMetricConfigP.ReservationHintP]] for detailed definition.
     */
    def uniformLoadReservationRatio: Double = uniformLoadReservationHint match {
      case ReservationHintP.NO_RESERVATION => 0.01
      case ReservationHintP.SMALL_RESERVATION => 0.1
      case ReservationHintP.MEDIUM_RESERVATION => 0.2
      case ReservationHintP.LARGE_RESERVATION => 0.4
    }

    /**
     * Computes the total reserved load, uniformly distributed in the hashed key space, that should
     * be accounted for when load balancing. See [[ReservationHintP]] for details.
     */
    def getUniformReservedLoad(availableResourceCount: Int): Double = {
      // The reservation hints each correspond to a ratio of the `maxLoadHint` per resource in the
      // assignment.
      uniformLoadReservationRatio * maxLoadHint * availableResourceCount
    }

    /**
     * Returns the amount a resource's load can differ from the average load before Dicer will
     * attempt to load balance.
     */
    def absoluteImbalanceTolerance: Double = maxLoadHint * imbalanceToleranceRatio
  }
  object LoadBalancingMetricConfig {

    /** Validates and parses `proto`. */
    def fromProto(proto: LoadBalancingMetricConfigP): LoadBalancingMetricConfig = {
      LoadBalancingMetricConfig(
        maxLoadHint = proto.getMaxLoadHint,
        imbalanceToleranceHint = proto.getImbalanceToleranceHint,
        uniformLoadReservationHint = proto.getUniformLoadReservationHint
      )
    }
  }

  /**
   * Asymmetric key replication configuration for a target. Each slice will be assigned to a number
   * of replicas within [`minReplicas`, `maxReplicas`] inclusive (unless the number of all available
   * resources is less than `minReplicas`, in which case each Slice will be assigned to all the
   * available resources).
   *
   * @throws IllegalArgumentException If minReplicas < 1.
   * @throws IllegalArgumentException If minReplicas > maxReplicas.
   */
  case class KeyReplicationConfig @throws[IllegalArgumentException]()(
      minReplicas: Int,
      maxReplicas: Int
  ) {
    if (minReplicas < 1) {
      throw new IllegalArgumentException(s"minReplicas $minReplicas less than 1")
    }
    if (minReplicas > maxReplicas) {
      throw new IllegalArgumentException(
        s"minReplicas $minReplicas greater than maxReplicas $maxReplicas"
      )
    }

    def toProto: KeyReplicationConfigP = {
      KeyReplicationConfigP.of(Some(minReplicas), Some(maxReplicas))
    }

    override def toString: String = {
      s"KeyReplicationConfig(minReplicas=$minReplicas, maxReplicas=$maxReplicas)"
    }
  }

  object KeyReplicationConfig {
    val DEFAULT_SINGLE_REPLICA = KeyReplicationConfig(minReplicas = 1, maxReplicas = 1)

    /**
     * Parses and validates the proto representation of [[KeyReplicationConfig]]. See
     * [[KeyReplicationConfig]] case class for other parameter requirements.
     */
    @throws[IllegalArgumentException]("If minReplicas or maxReplicas is not defined in proto.")
    def fromProto(proto: KeyReplicationConfigP): KeyReplicationConfig = {
      val minReplicasOpt: Option[Int] = proto.minReplicas
      val maxReplicasOpt: Option[Int] = proto.maxReplicas
      if (minReplicasOpt.isEmpty) {
        throw new IllegalArgumentException("minReplicas is not defined in proto.")
      }
      if (maxReplicasOpt.isEmpty) {
        throw new IllegalArgumentException("maxReplicas is not defined in proto.")
      }
      KeyReplicationConfig(minReplicasOpt.get, maxReplicasOpt.get)
    }
  }

  /**
   * Health watcher configuration for a target.
   *
   * @param observeSliceletReadiness whether the Assigner should use a Slicelet's reported readiness
   *                                 state to decide its status on startup, or whether the Assigner
   *                                 should mask that state to Running from the NOT_READY state.
   *                                 Note that even when observing the Slicelet's state, certain
   *                                 status transitions are disallowed (e.g., Running -> NotReady).
   */
  case class HealthWatcherTargetConfig(
      observeSliceletReadiness: Boolean
  ) {
    override def toString: String =
      "HealthWatcherConfig(" +
      s"observeSliceletReadiness=$observeSliceletReadiness)"

    def toProto: HealthWatcherConfigP = {
      // Only set the field in the proto if it differs from the default. This is to avoid updating
      // the dynamic config for customers who don't specify this field.
      val observeSliceletReadinessOpt: Option[Boolean] =
        if (observeSliceletReadiness !=
          HealthWatcherTargetConfig.DEFAULT.observeSliceletReadiness) {
          Some(observeSliceletReadiness)
        } else {
          None
        }
      HealthWatcherConfigP.of(observeSliceletReadinessOpt)
    }
  }

  object HealthWatcherTargetConfig {
    val DEFAULT: HealthWatcherTargetConfig = HealthWatcherTargetConfig(
      observeSliceletReadiness = false
    )

    def fromProto(proto: HealthWatcherConfigP): HealthWatcherTargetConfig = {
      val observeSliceletReadiness: Boolean =
        proto.observeSliceletReadiness match {
          case Some(observeSliceletReadiness: Boolean) =>
            observeSliceletReadiness
          case None => DEFAULT.observeSliceletReadiness
        }
      HealthWatcherTargetConfig(observeSliceletReadiness)
    }
  }

  object forTest {

    /**
     * A default [[InternalTargetConfig]] for convenience. Tests should avoid relying on the values
     * configured here. If the values carry any semantic significance, callers should override
     * fields explicitly.
     */
    val DEFAULT: InternalTargetConfig = InternalTargetConfig(
      LoadWatcherTargetConfig.DEFAULT,
      loadBalancingConfig = LoadBalancingConfig(
        LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL,
        ChurnConfig.DEFAULT,
        LoadBalancingMetricConfig(maxLoadHint = 1.0)
      ),
      KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
      HealthWatcherTargetConfig.DEFAULT
    )
  }
}

/**
 * Internal target configuration with the associated target name.
 *
 * @param targetName Name of sharded services for which this configuration applies.
 * @param config Configuration for the target.
 */
case class NamedInternalTargetConfig(targetName: TargetName, config: InternalTargetConfig)
    extends JsonSerializableConfig {
  override def toJsonString: String = {
    val proto: InternalDicerTargetConfigP = toProto
    DatabricksObjectMapper.toJson(proto)
  }

  /** Converts this instance to a [[InternalDicerTargetConfigP]] proto object. */
  def toProto: InternalDicerTargetConfigP = {

    // If the current key replication config equals the default config, we output an empty
    // KeyReplicationConfigP proto to avoid updating the dynamic config for customers who don't
    // specify this field. Because this field is optional but has a Scala-level default value.
    val keyReplicationConfigProtoOpt: Option[KeyReplicationConfigP] =
      if (config.keyReplicationConfig == KeyReplicationConfig.DEFAULT_SINGLE_REPLICA) {
        None
      } else {
        Some(config.keyReplicationConfig.toProto)
      }

    // Only set the health watcher config in the proto if it differs from the default to avoid
    // updating the dynamic config for customers who don't specify this field.
    val healthWatcherConfigProtoOpt: Option[HealthWatcherConfigP] =
      if (config.healthWatcherConfig == HealthWatcherTargetConfig.DEFAULT) {
        None
      } else {
        Some(config.healthWatcherConfig.toProto)
      }

    val targetConfigProto: TargetConfigFieldsP = TargetConfigFieldsP.of(
      Some(config.loadBalancingConfig.primaryRateMetric.toProto),
      keyReplicationConfigProtoOpt
    )

    val advancedConfigProto: AdvancedTargetConfigFieldsP = AdvancedTargetConfigFieldsP.of(
      loadWatcherConfig = Some(config.loadWatcherConfig.toProto),
      // This field in advanced config is deprecated, but still populate this filed in advanced
      // config so the generated dynamic config can be recognized by possible stale assigner binary
      // in production.
      keyReplicationConfig = keyReplicationConfigProtoOpt,
      healthWatcherConfig = healthWatcherConfigProtoOpt
    )
    InternalDicerTargetConfigP(
      target = Some(targetName.value),
      targetConfig = Some(targetConfigProto),
      advancedConfig = Some(advancedConfigProto)
    )
  }
}
object NamedInternalTargetConfig {

  /**
   * Factory method that creates an InternalTargetConfig from a Json string, typically coming from
   * SAFE flags.
   */
  def fromJsonString(jsonString: String): NamedInternalTargetConfig = {
    val proto: InternalDicerTargetConfigP = Try[InternalDicerTargetConfigP](
      // DatabricksObjectMapper requires adding //api/rpc:rpc_parser to the dependency list
      // for proto and JSON conversion.
      DatabricksObjectMapper.fromJson[InternalDicerTargetConfigP](jsonString)
    ) match {
      case Failure(e) =>
        throw new IllegalArgumentException(
          "Cannot parse JSON into a valid InternalDicerTargetConfigP.",
          e
        )
      case Success(parsedProto) =>
        parsedProto
    }
    val targetName = TargetName(proto.getTarget)
    val config: InternalTargetConfig = fromProtos(proto.getTargetConfig, proto.getAdvancedConfig)
    NamedInternalTargetConfig(targetName, config)
  }
}
