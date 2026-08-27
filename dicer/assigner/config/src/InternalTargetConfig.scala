package com.databricks.dicer.assigner.config

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.databricks.api.proto.dicer.assigner.config.{
  AdvancedTargetConfigFieldsP,
  InternalDicerTargetConfigP,
  LoadWatcherConfigP,
  HealthWatcherConfigP,
  TargetWatchRequestRateLimitConfigP
}
import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.api.proto.dicer.external.{
  LoadBalancingMetricConfigP,
  TargetConfigFieldsP,
  KeySensitivityConfigP,
  KeyReplicationConfigP
}
import com.databricks.api.proto.dicer.external.KeySensitivityConfigP.SliceKeySensitivityP
import com.databricks.caching.util.JsonSerializableConfig
import com.databricks.dicer.common.SliceKeySensitivity
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  KeyOfDeathProtectionConfig,
  KeySensitivityConfig,
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadWatcherTargetConfig,
  HealthWatcherTargetConfig,
  TargetWatchRequestRateLimitConfig,
  fromProtos
}

/**
 * Stores the config for the given `target`.
 *
 * NOTE: [[TargetConfigValidator.validateEquivalentConfigScopesHaveMatchingConfig]] relies on the
 * value equality (i.e. `.equals`) of the entire [[InternalTargetConfig]] instance. As a result,
 * it also transitively relies on value equality of the entire [[Authorizer]] instance.
 *
 * @param loadWatcherConfig      The configuration for the load watcher.
 * @param loadBalancingConfig    the configuration to use for load balancing in the Dicer assigner.
 * @param keyReplicationConfig   Asymmetric key replication configuration for the target.
 * @param healthWatcherConfig    The configuration for the health watcher.
 * @param targetRateLimitConfig  The configuration for per-target rate limiting.
 * @param authorizer             The authorization policy for this target, or
 *                               [[AuthorizerHelper.DEFAULT_AUTHORIZER]] if no policy is configured.
 *                               TODO(<internal bug>): Replace the [[authorizer]] field with a
 *                                             AuthorizerConfig class which can create different
 *                                             Authorizers for different targets, rather than hard-
 *                                             coding the Authorizer class in the config fields.
 * @param keySensitivityConfig   Customer attestation of whether this target's SliceKeys are
 *                               sensitive. Determines whether Slices and SliceKeys appear
 *                               in Central Logfood in prod.
 * @param useAlternativeTarget   When true, the Assigner treats this target as canonicalized to its
 *                               AppTarget identity, using the AppTarget carried in a watch
 *                               request's `alternative_target` field in place of `target`.
 */
case class InternalTargetConfig(
    loadWatcherConfig: LoadWatcherTargetConfig,
    loadBalancingConfig: LoadBalancingConfig,
    keyReplicationConfig: KeyReplicationConfig,
    healthWatcherConfig: HealthWatcherTargetConfig,
    keyOfDeathProtectionConfig: KeyOfDeathProtectionConfig,
    targetRateLimitConfig: TargetWatchRequestRateLimitConfig,
    authorizer: Authorizer,
    keySensitivityConfig: KeySensitivityConfig,
    useAlternativeTarget: Boolean) {

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
    if (keyOfDeathProtectionConfig != KeyOfDeathProtectionConfig.DEFAULT) {
      builder.append(s", $keyOfDeathProtectionConfig")
    }
    if (targetRateLimitConfig != TargetWatchRequestRateLimitConfig.DEFAULT) {
      builder.append(s", $targetRateLimitConfig")
    }
    if (keySensitivityConfig != KeySensitivityConfig.DEFAULT) {
      builder.append(s", $keySensitivityConfig")
    }
    if (useAlternativeTarget) {
      builder.append(s", useAlternativeTarget=$useAlternativeTarget")
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
   * @param proto Proto representation of customer-controlled configuration. The authorizer is
   *              parsed from `proto.authorizer`; an unset field decodes to
   *              [[AuthorizerHelper.DEFAULT_AUTHORIZER]].
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

    // TargetWatchRequestRateLimitConfig is an optional field in AdvancedTargetConfigFields. When
    // not defined, uses the DEFAULT configuration.
    val targetRateLimitConfig: TargetWatchRequestRateLimitConfig =
      advancedProto.targetWatchRequestRateLimitConfig
        .map(TargetWatchRequestRateLimitConfig.fromProto)
        .getOrElse(TargetWatchRequestRateLimitConfig.DEFAULT)

    // TODO(<internal bug>): populate load balancing interval from advanced config proto.
    val loadBalancingConfig =
      LoadBalancingConfig(
        loadBalancingInterval = LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL,
        ChurnConfig.DEFAULT,
        primaryRateMetric
      )

    // TODO(<internal bug>): Populate the key of death protection config from advanced config proto
    // once that is enabled.
    val keyOfDeathProtectionConfig = KeyOfDeathProtectionConfig.DEFAULT

    // Decode the authorizer `Any`; an absent field decodes to the default authorizer.
    val authorizer: Authorizer = AuthorizerHelper.fromAnyProto(proto.authorizer)

    // Decode the key sensitivity config; an absent field decodes to the default sensitivity config.
    val keySensitivityConfig: KeySensitivityConfig =
      proto.keySensitivityConfig
        .map(KeySensitivityConfig.fromProto)
        .getOrElse(KeySensitivityConfig.DEFAULT)

    // use_alternative_target defaults to false.
    val useAlternativeTarget: Boolean = advancedProto.useAlternativeTarget.getOrElse(false)
    InternalTargetConfig(
      loadWatcherConfig,
      loadBalancingConfig,
      replicationConfig,
      healthWatcherConfig,
      keyOfDeathProtectionConfig,
      targetRateLimitConfig,
      authorizer,
      keySensitivityConfig,
      useAlternativeTarget
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
   * @param useLoadDistribution whether the per-key load distribution (CDF) reported by the Slicelet
   *                            will be used (i.e. integrated into the load map).
   */
  case class LoadWatcherTargetConfig(
      minDuration: FiniteDuration,
      maxAge: FiniteDuration,
      useTopKeys: Boolean,
      useLoadDistribution: Boolean) {
    require(minDuration > Duration.Zero, "minDuration must be positive")
    require(maxAge > Duration.Zero, "maxAge must be positive")

    override def toString: String =
      s"LoadWatcherTargetConfig(minDuration=$minDuration, maxAge=$maxAge, " +
      s"useTopKeys=$useTopKeys, useLoadDistribution=$useLoadDistribution)"

    def toProto: LoadWatcherConfigP = {
      LoadWatcherConfigP.of(
        Some(minDuration.toSeconds.toInt),
        Some(maxAge.toSeconds.toInt),
        Some(useTopKeys),
        Some(useLoadDistribution)
      )
    }
  }

  object LoadWatcherTargetConfig {
    val DEFAULT: LoadWatcherTargetConfig =
      LoadWatcherTargetConfig(
        minDuration = 1.minute,
        maxAge = 5.minutes,
        useTopKeys = true,
        useLoadDistribution = false
      )

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
      val useLoadDistribution: Boolean =
        proto.useLoadDistribution.getOrElse(DEFAULT.useLoadDistribution)
      LoadWatcherTargetConfig(minDuration, maxAge, useTopKeys, useLoadDistribution)
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
   * Customer attestation of whether a target's SliceKeys are sensitive.
   *
   * SliceKey-bearing fields are always logged to Lumberjack and remain available in regional
   * Logfood. The attestation only determines whether those Slices and SliceKeys also appear
   * in Central Logfood in prod: only non-sensitive Slices and SliceKeys appear.
   *
   * @param sliceKeySensitivity The customer's attestation of this target's SliceKey sensitivity,
   *                            or `Unspecified` when the customer has made no attestation.
   */
  case class KeySensitivityConfig(sliceKeySensitivity: SliceKeySensitivity) {
    override def toString: String =
      s"KeySensitivityConfig(sliceKeySensitivity=$sliceKeySensitivity)"

    /** Converts this instance to a [[KeySensitivityConfigP]] proto object. */
    def toProto: KeySensitivityConfigP = {
      val sliceKeySensitivityP: SliceKeySensitivityP = sliceKeySensitivity match {
        case SliceKeySensitivity.NonSensitive => SliceKeySensitivityP.NON_SENSITIVE
        case SliceKeySensitivity.Sensitive => SliceKeySensitivityP.SENSITIVE
        case SliceKeySensitivity.Unspecified => SliceKeySensitivityP.UNSPECIFIED
      }
      KeySensitivityConfigP.of(Some(sliceKeySensitivityP))
    }
  }

  object KeySensitivityConfig {

    /** Default configuration: the customer has not attested SliceKey sensitivity. */
    val DEFAULT: KeySensitivityConfig = KeySensitivityConfig(
      sliceKeySensitivity = SliceKeySensitivity.Unspecified
    )

    /** Parses the proto representation of [[KeySensitivityConfig]]. */
    def fromProto(proto: KeySensitivityConfigP): KeySensitivityConfig = {
      // Map SENSITIVE and UNSPECIFIED to distinct values (both treated as sensitive) so an explicit
      // sensitive attestation can be told apart from no attestation, such as when reporting metrics
      // on how customers have configured their targets.
      val sliceKeySensitivity: SliceKeySensitivity =
        proto.sliceKeySensitivity match {
          case Some(SliceKeySensitivityP.NON_SENSITIVE) => SliceKeySensitivity.NonSensitive
          case Some(SliceKeySensitivityP.SENSITIVE) => SliceKeySensitivity.Sensitive
          case Some(SliceKeySensitivityP.UNSPECIFIED) => SliceKeySensitivity.Unspecified
          case None => SliceKeySensitivity.Unspecified
        }
      KeySensitivityConfig(sliceKeySensitivity)
    }
  }

  /**
   * Health watcher configuration for a target.
   *
   * REQUIRES: if `permitRunningToNotReady` is true, `observeSliceletReadiness` must also be true.
   *
   * @param observeSliceletReadiness whether the Assigner should use a Slicelet's reported readiness
   *                                 state to decide its status on startup, or whether the Assigner
   *                                 should mask that state to Running from the NotReady state.
   * @param permitRunningToNotReady  whether to allow transitions from Running to NotReady. When
   *                                 true, Slicelets reporting NOT_READY will immediately transition
   *                                 to NotReady. When false, NOT_READY reports from Slicelets
   *                                 labeled Running by the Assigner are ignored. This setting only
   *                                 applies when observeSliceletReadiness is true.
   * @throws[IllegalArgumentException] if permitRunningToNotReady is true but
   *                                   observeSliceletReadiness is false.
   */
  case class HealthWatcherTargetConfig(
      observeSliceletReadiness: Boolean,
      permitRunningToNotReady: Boolean
  ) {
    // The HealthWatcher only computes the NotReady status for Slicelets when it is faithfully
    // reporting the Slicelet's readiness state.  As such, `permitRunningToNotReady` only has an
    // effect if `observeSliceletReadiness` is true. Setting `permitRunningToNotReady` to true when
    // `observeSliceletReadiness` is false is likely a misconfiguration.
    require(
      !permitRunningToNotReady || observeSliceletReadiness,
      "permitRunningToNotReady can only be true if observeSliceletReadiness is also true"
    )

    override def toString: String =
      "HealthWatcherConfig(" +
      s"observeSliceletReadiness=$observeSliceletReadiness, " +
      s"permitRunningToNotReady=$permitRunningToNotReady)"

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
      val permitRunningToNotReadyOpt: Option[Boolean] =
        if (permitRunningToNotReady !=
          HealthWatcherTargetConfig.DEFAULT.permitRunningToNotReady) {
          Some(permitRunningToNotReady)
        } else {
          None
        }
      HealthWatcherConfigP.of(observeSliceletReadinessOpt, permitRunningToNotReadyOpt)
    }
  }

  object HealthWatcherTargetConfig {
    val DEFAULT: HealthWatcherTargetConfig = HealthWatcherTargetConfig(
      observeSliceletReadiness = false,
      permitRunningToNotReady = false
    )

    def fromProto(proto: HealthWatcherConfigP): HealthWatcherTargetConfig = {
      val observeSliceletReadiness: Boolean =
        proto.observeSliceletReadiness match {
          case Some(observeSliceletReadiness: Boolean) =>
            observeSliceletReadiness
          case None => DEFAULT.observeSliceletReadiness
        }
      val permitRunningToNotReady: Boolean =
        proto.permitRunningToNotReady match {
          case Some(permitRunningToNotReady: Boolean) =>
            permitRunningToNotReady
          case None => DEFAULT.permitRunningToNotReady
        }
      HealthWatcherTargetConfig(observeSliceletReadiness, permitRunningToNotReady)
    }
  }

  /**
   * Configuration for the key of death protection for a target.
   *
   * @param homomorphicGenerationEnabled Whether to generate homomorphic assignments when a key of
   *                                     scenarios is detected. See
   *                                     [[Algorithm.generateHomomorphicAssignment]] for more
   *                                     details. When disabled, Dicer will continue to generate
   *                                     regular assignments for the target during key of death
   *                                     scenarios.
   */
  case class KeyOfDeathProtectionConfig(
      homomorphicGenerationEnabled: Boolean
  ) {
    override def toString: String =
      "KeyOfDeathProtectionConfig" +
      s"(homomorphicGenerationEnabled=$homomorphicGenerationEnabled)"
  }

  object KeyOfDeathProtectionConfig {
    // TODO(<internal bug>): Allow this config to be created from proto after the configuration for
    // key of death protection is exposed to customers.
    val DEFAULT: KeyOfDeathProtectionConfig = KeyOfDeathProtectionConfig(
      homomorphicGenerationEnabled = false
    )
  }

  /**
   * Watch request rate limiting configuration for a target instance.
   *
   * @param clientRequestsPerSecond Maximum watch requests per second per client. Must be >= 0.
   *                                Use 0 to disable traffic for this target.
   * @throws IllegalArgumentException If clientRequestsPerSecond < 0.
   */
  case class TargetWatchRequestRateLimitConfig(clientRequestsPerSecond: Long) {
    require(
      clientRequestsPerSecond >= 0,
      s"clientRequestsPerSecond must be >= 0, got $clientRequestsPerSecond"
    )

    /** The maximum number of requests that can be made in a burst. Capped at `Long.MAX_VALUE`. */
    val burstCapacity: Long = try {
      Math.multiplyExact(
        clientRequestsPerSecond,
        TargetWatchRequestRateLimitConfig.BURST_CAPACITY_IN_SECONDS
      )
    } catch {
      case _: ArithmeticException => Long.MaxValue
    }

    override def toString: String =
      s"TargetWatchRequestRateLimitConfig(clientRequestsPerSecond=$clientRequestsPerSecond, " +
      s"burstCapacity=$burstCapacity)"

    /**
     * Converts this instance to a [[TargetWatchRequestRateLimitConfigP]] proto object.
     */
    def toProto: TargetWatchRequestRateLimitConfigP = {
      TargetWatchRequestRateLimitConfigP.of(Some(clientRequestsPerSecond))
    }
  }

  object TargetWatchRequestRateLimitConfig {

    /**
     * Burst capacity in seconds of request throughput. Must be >= 1. E.g., if
     * clientRequestsPerSecond=2, burst capacity is 2 * 10 = 20 requests.
     *
     * This means each client can burst up to BURST_CAPACITY_IN_SECONDS * clientRequestsPerSecond
     * requests before being rate limited. We do not expect clients to require much burst headroom,
     * but allow for it to accomodate clients that may send additional watch requests in close
     * proximity, e.g. due to a new assignment generation that causes reestablishment of watches.
     */
    private[dicer] val BURST_CAPACITY_IN_SECONDS: Long = 10L

    /**
     * Default per-target rate limiting configuration for Watch requests.
     *
     * Used when a config exists for the target name, but the target doesn't specify a rate limit
     * override in the configuration proto.
     *
     * `clientRequestsPerSecond`: 2 request per second per client. Well-behaved clients (Slicelets,
     * Clerks) send Watch requests at ~0.4 req/s (every 2.5 seconds), so 2 req/s is ~5x the
     * normal expected rate.
     *
     * With BURST_CAPACITY_IN_SECONDS = 10, each client can burst up to 20 requests before
     * being rate limited.
     */
    val DEFAULT: TargetWatchRequestRateLimitConfig =
      TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = 2L)

    /**
     * Parses and validates the proto representation of [[TargetWatchRequestRateLimitConfig]].
     */
    def fromProto(proto: TargetWatchRequestRateLimitConfigP): TargetWatchRequestRateLimitConfig = {
      val clientRequestsPerSecond: Long = proto.clientRequestsPerSecond match {
        case Some(rate: Long) => rate
        case None => DEFAULT.clientRequestsPerSecond
      }
      TargetWatchRequestRateLimitConfig(clientRequestsPerSecond)
    }
  }

  /**
   * The default [[InternalTargetConfig]] used for targets that do not have a checked-in config.
   * This is only used when `allowDefaultTargetConfigForExperimentalTargets` is enabled on the Dicer
   * Assigner.
   *
   * The `maxLoadHint` is set to 100,000. For targets which report 1000 load per request (a typical
   * recommendation), this corresponds to 100 QPS of traffic serving capacity per pod. The goal is
   * that this default is high enough to avoid unnecessary churn for experimental targets, but not
   * so high as to effectively disable Dicer's load balancing.
   */
  val DEFAULT_FOR_EXPERIMENTAL_TARGETS: InternalTargetConfig = InternalTargetConfig(
    LoadWatcherTargetConfig.DEFAULT,
    loadBalancingConfig = LoadBalancingConfig(
      LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(maxLoadHint = 100000)
    ),
    KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
    HealthWatcherTargetConfig.DEFAULT,
    KeyOfDeathProtectionConfig.DEFAULT,
    TargetWatchRequestRateLimitConfig.DEFAULT,
    AuthorizerHelper.DEFAULT_AUTHORIZER,
    KeySensitivityConfig.DEFAULT,
    useAlternativeTarget = false
  )

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
      HealthWatcherTargetConfig.DEFAULT,
      KeyOfDeathProtectionConfig.DEFAULT,
      TargetWatchRequestRateLimitConfig.DEFAULT,
      AuthorizerHelper.DEFAULT_AUTHORIZER,
      KeySensitivityConfig.DEFAULT,
      useAlternativeTarget = false
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
  override def toJsonString: String =
    InternalTargetConfigJsonConverter.toJsonString(toProto)

  /** Converts this instance to a [[InternalDicerTargetConfigP]] proto object. */
  // TODO(<internal bug>): Modify this function once key of death protection config is added to advanced
  // config.
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

    // Only set the target rate limit config in the proto if it differs from the default to avoid
    // updating the dynamic config for customers who don't specify this field.
    val targetRateLimitConfigProtoOpt: Option[TargetWatchRequestRateLimitConfigP] =
      if (config.targetRateLimitConfig == TargetWatchRequestRateLimitConfig.DEFAULT) {
        None
      } else {
        Some(config.targetRateLimitConfig.toProto)
      }

    // Only set the key sensitivity config in the proto if it differs from the default to avoid
    // updating the dynamic config for customers who don't specify this field.
    val keySensitivityConfigProtoOpt: Option[KeySensitivityConfigP] =
      if (config.keySensitivityConfig == KeySensitivityConfig.DEFAULT) {
        None
      } else {
        Some(config.keySensitivityConfig.toProto)
      }

    // Only set use_alternative_target in the proto when enabled, to avoid updating the dynamic
    // config for targets that leave it at the default (false).
    val useAlternativeTargetOpt: Option[Boolean] =
      if (config.useAlternativeTarget) Some(true) else None

    val targetConfigProto: TargetConfigFieldsP = TargetConfigFieldsP.of(
      primaryRateMetricConfig = Some(config.loadBalancingConfig.primaryRateMetric.toProto),
      keyReplicationConfig = keyReplicationConfigProtoOpt,
      authorizer = AuthorizerHelper.toAnyProto(config.authorizer),
      keySensitivityConfig = keySensitivityConfigProtoOpt
    )

    val loadWatcherConfigProto: LoadWatcherConfigP = {
      val proto: LoadWatcherConfigP = config.loadWatcherConfig.toProto
      // When `use_load_distribution` was introduced, there were already many customers with
      // non-empty LoadWatcherConfigP in their configs. If `useLoadDistribution` is at its default,
      // clear it from the proto to avoid updating the dynamic config for customers who don't
      // specify this field.
      if (config.loadWatcherConfig.useLoadDistribution ==
        LoadWatcherTargetConfig.DEFAULT.useLoadDistribution) {
        proto.copy(useLoadDistribution = None)
      } else {
        proto
      }
    }

    val advancedConfigProto: AdvancedTargetConfigFieldsP = AdvancedTargetConfigFieldsP.of(
      loadWatcherConfig = Some(loadWatcherConfigProto),
      // This field in advanced config is deprecated, but still populate this filed in advanced
      // config so the generated dynamic config can be recognized by possible stale assigner binary
      // in production.
      keyReplicationConfig = keyReplicationConfigProtoOpt,
      healthWatcherConfig = healthWatcherConfigProtoOpt,
      targetWatchRequestRateLimitConfig = targetRateLimitConfigProtoOpt,
      useAlternativeTarget = useAlternativeTargetOpt
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
      InternalTargetConfigJsonConverter.fromJsonString(jsonString)
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
