package com.databricks.dicer.assigner.conf

import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.databricks.backend.common.util.Project
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.SafeConfigUtil.DICER_CONFIG_FLAGS_NAME_PREFIX
import com.databricks.caching.util.{
  CachingErrorCode,
  ConfigScope,
  PrefixLogger,
  ServerConf,
  Severity
}
import com.databricks.conf.trusted.{LocationConf, ProjectConf}
import com.databricks.conf.{Config, ConfigParser, DbConf}
import com.databricks.dicer.assigner.conf.DicerAssignerConf.ExecutionMode
import com.databricks.dicer.assigner.conf.StoreConf.StoreEnum
import com.databricks.dicer.common.{CommonSslConf, Incarnation, WatchServerConf}
import com.databricks.featureflag.client.utils.RuntimeContext
import com.databricks.featureflag.client.{DynamicConf, FeatureFlagDefinition}
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.rpc.tls.{TLSOptions, TLSOptionsMigration}

/** Configuration parameters for Assignment Generator. */
trait GeneratorConf extends DbConf {

  /** The generator only observes and generates assignments with this store incarnation. */
  def storeIncarnation: Incarnation = Incarnation(storeIncarnationFlag)

  /** Flag for [[storeIncarnation]]. */
  private val storeIncarnationFlag: Long =
    configure[Long]("databricks.dicer.assigner.storeIncarnation", 0)
}

/** Configuration parameters for HealthWatcher. */
trait HealthConf extends DbConf {

  /**
   * The delay before the first health report is emitted. Note that in the absence of observing an
   * initial set of assigned resources with which to bootstrap health status, this delay is used to
   * ensure the watcher has collected sufficient signals before emitting its first health report.
   */
  val initialHealthReportDelayPeriod: FiniteDuration =
    configure[Long]("databricks.dicer.assigner.initialHealthReportDelayPeriodSeconds", 30).seconds

  /**
   * If a heartbeat is not received from a resource in this period, the health watcher declares that
   * resource as unhealthy.
   */
  val unhealthyTimeoutPeriod: FiniteDuration =
    configure[Long]("databricks.dicer.assigner.unhealthyTimeoutPeriodSeconds", 30).seconds

  /**
   * Timeout for Terminating state in HealthWatcher. We configure this to be more than the default
   * termination grace period in Kubernetes ie. 30 seconds. Timeout is set for Terminating state
   * which is the final state for a resource, just so we don't keep unused pods forever in
   * HealthWatcher.
   *
   * NOTE: If this value is set to less than Kubernetes termination grace period, it may lead to
   *  an already terminated pod becoming healthy again if heartbeat is received from the Slicelet
   *  after this timeout period.
   */
  val terminatingTimeoutPeriod: FiniteDuration =
    configure[Long]("databricks.dicer.assigner.terminatingTimeoutPeriod", 300).seconds

  /**
   * Flapping protection timeout for the NotReady state in HealthWatcher. A Slicelet in the NotReady
   * state will not be allowed to transition to Running until this duration has elapsed since the
   * last NOT_READY heartbeat (each NOT_READY heartbeat resets the timer). The default of 10s
   * corresponds to roughly 3 heartbeat intervals: if we have not heard NOT_READY in that time, the
   * Slicelet is likely healthy again and safe to transition to Running when the Slicelet's next
   * reported state is `RUNNING`. If the Slicelet is unresponsive rather than recovered, the
   * `unhealthyTimeoutPeriod` will expire and the Slicelet will be removed from the Assignment
   * regardless.
   */
  val notReadyTimeoutPeriod: FiniteDuration =
    configure[Long]("databricks.dicer.assigner.notReadyTimeoutPeriod", 10).seconds
}

/** Configuration parameters for the LoadWatcher. */
trait LoadWatcherConf extends DbConf {

  /**
   * Whether to allow targets to use top key information reported by the Slicelet. It still has to
   * be enabled in each target's `LoadWatcherConfigP.use_top_keys` - this config can be set to false
   * as a killswitch to disable top keys across all targets.
   * TODO(<internal bug>): Remove this config once we are confident in top key handling.
   */
  val allowTopKeys: Boolean = configure[Boolean]("databricks.dicer.assigner.allowTopKeys", true)
}

/** Configuration parameters for the Assigner store. */
trait StoreConf extends DbConf {

  /**
   * Parses [[String]] config values to [[StoreEnum.Value]].
   */
  private class StoreEnumParser extends ConfigParser[StoreEnum.Value] {
    override def parse(mapper: DatabricksObjectMapper, json: String): StoreEnum.Value = {
      StoreEnum.fromName(mapper.readValue[String](json))
    }
  }

  /**
   * The type of store the Assigner uses to store assignments. [[StoreEnum]] defines the types.
   */
  // Note: If this value is changed to `StoreEnum.ETCD`, consider revisiting `SubscriberHandler` to
  // optimize syncing and caching of partial assignments while handling watch requests.
  val store: StoreEnum.Value =
    configure[StoreEnum.Value](
      "databricks.dicer.assigner.store.type",
      StoreEnum.IN_MEMORY,
      new StoreEnumParser
    )

}

object StoreConf {

  /**
   * The types of [[com.databricks.dicer.assigner.Store]]s that the Assigner supports.
   */
  object StoreEnum extends Enumeration {

    /**
     * Corresponds to [[com.databricks.dicer.assigner.InMemoryStore]].
     */
    val IN_MEMORY: StoreEnum.Value = Value("in_memory")

    /**
     * Corresponds to [[com.databricks.dicer.assigner.EtcdStore]].
     */
    val ETCD: StoreEnum.Value = Value("etcd")

    /**
     * Parses a string to the [[StoreEnum]] whose value matches.
     * @param name string to parse.
     * @return value matching to `name`.
     * @throws IllegalArgumentException if `name` does not match any value
     */
    @throws[IllegalArgumentException]
    def fromName(name: String): Value =
      values
        .find(_.toString == name)
        .getOrElse(throw new IllegalArgumentException(s"$name does not match a value of StoreEnum"))
  }
}

/** Configuration parameters for the preferred assigner. */
trait PreferredAssignerConf extends DbConf {

  /** Whether preferred assigner mode is enabled. */
  val preferredAssignerEnabled: Boolean =
    configure("databricks.dicer.assigner.preferredAssigner.modeEnabled", false)

  /**
   * The store incarnation for the preferred assigner store.
   */
  val preferredAssignerStoreIncarnation: Long =
    configure("databricks.dicer.assigner.preferredAssigner.storeIncarnation", 1L)

  /**
   * The service endpoints of the etcd instance that the Assigner uses for the preferred assigner.
   */
  val preferredAssignerEtcdEndpoints: Seq[String] =
    configure("databricks.dicer.assigner.preferredAssigner.etcd.endpoints", Seq.empty[String])

  /**
   * Configures the etcd client within the Assigner to use SSL to establish a connection to etcd.
   *
   * TODO(<internal bug>): Merge `preferredAssignerEtcdSslEnabled` and the ssl arguments into one single
   * unified field, e.g. `preferredAssignerEtcdSslArgsOpt`.
   */
  val preferredAssignerEtcdSslEnabled: Boolean =
    configure("databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled", true)
}

/**
 * REQUIRES: `replicaCount` > 0
 * REQUIRES: `replicaCount` == 1 when `PreferredAssignerConf.preferredAssignerEnabled` is false.
 *
 * Configuration for assigner. Dependencies:
 *
 *  - `ProjectConf`: Enables project-specific configuration overrides in static configuration. See
 *    usage in [[ProjectConf]] comment.
 *  - `ServerConf`: Configuration for RPC client and server.
 *  - `LocationConf`: Configuration capturing shard context for the server.
 *  - `HealthConf`: Configuration for Health watcher.
 *  - `GeneratorConf`: Configuration for Assignment Generator.
 *  - `WatchServerConf`: Configuration for the Assignment Watch server.
 *  - `CommonSslConf`: SSL configuration for Assigner servers.
 *  - `StoreConf`: Configuration for the Assigner store type.
 *  - `PreferredAssignerConf`: Configuration for the preferred assigner, including etcd settings.
 *  - `DynamicConf`: Support dynamic configuration of Dicer targets using SAFE.
 */
class DicerAssignerConf(config: Config)
    extends ProjectConf(Project.DicerAssigner, config)
    with ServerConf
    with LocationConf
    with HealthConf
    with LoadWatcherConf
    with WatchServerConf
    with CommonSslConf
    with PreferredAssignerConf
    with StoreConf
    with GeneratorConf
    with DynamicConf {

  final override def dicerTlsOptions: Option[TLSOptions] =
    TLSOptionsMigration.convert(sslArgs)

  /**
   * Path to target config directory in the container. This should contain the contents of
   * (the appropriate one of) `dicer/external/config/(dev|staging|prod)` in universe.
   */
  val targetConfigDirectory: String =
    configure("databricks.dicer.assigner.targetConfigDirectory", "")

  /**
   * Path to advanced target config directory in the container. This should contain the contents of
   * (the appropriate one of) `dicer/assigner/advanced_config/(dev|staging|prod)` in universe.
   */
  val advancedTargetConfigDirectory: String =
    configure("databricks.dicer.assigner.advancedTargetConfigDirectory", "")

  /**
   * Duration after which a generator with zero active watch requests will be considered inactive
   * and garbage collected by shutting it down. It should be set to a high value (e.g., 5 minutes)
   * to avoid premature cleanup during temporary outages.
   *
   * Note that generator inactivity checks happen periodically based on `inactivityScanInterval`,
   * therefore, the actual time a generator is shut down after the last active watch request may be
   * in [`generatorInactivityDeadline`, `generatorInactivityDeadline + inactivityScanInterval`].
   */
  val generatorInactivityDeadline: FiniteDuration =
    configure[Long](
      "databricks.dicer.assigner.generatorInactivityDeadlineSeconds",
      300
    ).seconds
  require(generatorInactivityDeadline.toSeconds > 0)

  /** The interval at which the Assigner periodically checks if it has inactive generators. */
  val generatorInactivityScanInterval: FiniteDuration =
    configure[Long](
      "databricks.dicer.assigner.generatorInactivityScanIntervalSeconds",
      60
    ).seconds
  require(generatorInactivityScanInterval.toSeconds > 0)

  /**
   * The number of assigner replicas. This must be greater than 0, and must not be greater than 1
   * when not in Preferred Assigner mode.
   */
  val replicaCount: Int = configure("databricks.dicer.assigner.replicaCount", 1)
  require(replicaCount > 0, "replicaCount must be greater than 0.")
  if (!preferredAssignerEnabled) {
    require(
      replicaCount == 1,
      "replicaCount must be 1 if not preferredAssignerEnabled."
    )
  }

  /**
   * Determines whether to have Assigner apply configs from the dynamic config (Default OFF).
   */
  @FeatureFlagDefinition(team = "platform-team")
  protected val enableDynamicConfig: FeatureFlag[Boolean] =
    FeatureFlag("databricks.dicer.enableDynamicConfig", false)

  /**
   * Dynamic Dicer target configurations via SAFE batch feature flag. Each target has its own sub-
   * flag, where the key is of the form "databricks.dicer.assigner.targetConfig.${target.name}".
   *
   * The value is a json-serialized [[InternalTargetConfigP]] instance.
   */
  @FeatureFlagDefinition(
    team = "platform-team",
    description = "The batch flag for Dicer target dynamic configurations"
  )
  protected val targetConfigBatchFlag: BatchFeatureFlag = BatchFeatureFlag(
    "databricks.dicer.assigner.targetConfig.batchFlag"
  )
  iassert(
    targetConfigBatchFlag.flagName == DICER_CONFIG_FLAGS_NAME_PREFIX + "batchFlag",
    "we must use a literal in the BatchFeatureFlag initializer, but that literal value must be " +
    "consistent with our expected prefix"
  )

  /**
   * Safe batch flag does not give push notification when value gets changed. Assigner will
   * periodically poll the flag value and the `pollInterval` variable specifies the interval between
   * two consecutive polls.
   */
  val dynamicConfigPollInterval: FiniteDuration =
    configure[FiniteDuration]("databricks.dicer.assigner.dynamicConfigPollInterval", 120.seconds)

  /**
   * Determines whether to enforce the use of static configuration for dicer-assigner service.
   * If set to true, dynamic configuration for ALL targets will be ignored (regardless of whether
   * `enableDynamicConfig` is true).
   */
  private val forceDisableDynamicConfig: Boolean =
    configure("databricks.dicer.assigner.forceDisableDynamicConfig", false)

  /** The watch RPC timeout that the Assigner suggests to Clerks that directly connect to it. */
  @FeatureFlagDefinition(
    team = "platform-team",
    description = "The RPC timeout that the assigner suggests clerks to use in their watch calls."
  )
  private val assignerSuggestedClerkWatchTimeout: FeatureFlag[Int] =
    FeatureFlag("databricks.dicer.assigner.assignerSuggestedClerkWatchTimeoutSeconds", 5)

  /**
   * Whether the Assigner should use [[InternalTargetConfig.DEFAULT_FOR_EXPERIMENTAL_TARGETS]] for
   * targets that do not have a checked-in config. When enabled, watch requests for unconfigured
   * targets will use this default config instead of being rejected. This is intended for allowing
   * experimentation with Dicer in dev environments without needing to check in target configs.
   */
  val allowDefaultTargetConfigForExperimentalTargets: Boolean =
    configure("databricks.dicer.assigner.allowDefaultTargetConfigForExperimentalTargets", false)

  /** Whether the Assigner should forward events for Dicer Tee. */
  val enableDicerTeeForwarding: Boolean =
    configure("databricks.dicer.assigner.tee.forwardingEnabled", false)

  /**
   * URI of DicerTeeBackend, where [[com.databricks.dicer.assigner.DicerTeeEventEmitter]] will emit
   * events to.
   */
  val dicerTeeURI: String = configure(
    "databricks.dicer.assigner.tee.dicerTeeURI",
    "dicer-tee-service.dicer-tee.svc.cluster.local"
  )

  /**
   * Generation sample fraction for AssignerProtoLogger. This controls what fraction of generations
   * are sampled for proto logging events. Should be between 0.0 and 1.0.
   * 0.0 means no logging, 1.0 means all generations are logged.
   */
  val protoLoggerGenerationSampleFraction: Double =
    configure("databricks.dicer.assigner.protoLogger.generationSampleFraction", 0.0)

  /**
   * Gets the latest dynamic target configurations from the
   * "databricks.dicer.assigner.targetConfig.batchFlag" batch feature flag.
   */
  def getDynamicTargetConfigs: Map[String, String] = {
    // We want to return the latest known configuration on every call independent of the current
    // runtime context. None of the contextual information exposed by the runtime context (e.g.,
    // workspaceId, accountId, etc.) determines which configuration should be used. We also do not
    // want to use a background context because we do not want to "pin" the returned configuration
    // for the lifetime of that context.
    targetConfigBatchFlag.getSubFlagValues(runtimeContext = None).map {
      case (key: String, value: String) =>
        key -> DatabricksObjectMapper.fromJson[String](value)
    }
  }

  /** Gets the runtime value of `enableDynamicConfig`. */
  def dynamicConfigEnabled: Boolean = {
    if (forceDisableDynamicConfig) {
      false
    } else {
      // The `enableDynamicConfig` is independent of `RuntimeContext` (i.e., workspaceId, accountId,
      // requestId, subscriptionId), and we do not utilize customDimensions (instead, we use the
      // standard dimensions defined by SAFE (<internal link>)). Therefore,
      // we set `runtimeContext` to `RuntimeContext.EMPTY` here.
      enableDynamicConfig.getCurrentValue(runtimeContext = RuntimeContext.EMPTY)
    }
  }

  /**
   * Tries to derive [[ConfigScope]] from `conf`. Returns `Some` scope if successful; if there is a
   * failure, returns `None`.
   */
  def getConfigScope: Option[ConfigScope] = {
    try {
      Some(ConfigScope.fromLocationConf(this))
    } catch {
      case NonFatal(e) =>
        DicerAssignerConf.logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.BAD_SHARD_CONFIGURATION,
          s"Failed to parse config scope information from the configuration: ${e.getMessage}"
        )
        None
    }
  }

  /**
   * Returns the current watch RPC timeout that the Assigner suggests to Clerks that directly
   * connect to it. This value may change over the lifetime of the Assigner via dynamic config
   * updates.
   */
  def getAssignerSuggestedClerkWatchTimeout: FiniteDuration = {
    assignerSuggestedClerkWatchTimeout.getCurrentValue().seconds
  }

  val executionMode: ExecutionMode.Value =
    configure[ExecutionMode.Value](
      propertyName = "databricks.dicer.assigner.executionMode",
      defaultValue = ExecutionMode.ASSIGNER_SERVICE,
      parser = new ConfigParser[ExecutionMode.Value] {
        override def parse(mapper: DatabricksObjectMapper, json: String): ExecutionMode.Value = {
          ExecutionMode.fromName(mapper.readValue[String](json))
        }
      }
    )

  /**
   * Store namespace prefix used to construct a store namespace in which the assigner stores data.
   * This value must be unique per deployed assigner service to ensure that separate deployments do
   * not overwrite each other's data, which could result in undefined behavior and lead to an
   * outage.
   */
  val storeNamespacePrefix: String =
    configure(
      "databricks.dicer.assigner.storeNamespacePrefix",
      defaultValue = ""
    )

  /**
   * Whether the assigner performs validation of the target in a watch request using App
   * Identifier headers attached to the request.
   *
   * When this is false, the assigner trusts the client (Clerk or Slicelet) to truthfully populate
   * the `target` field of the `ClientRequest` with the target of the application on behalf of which
   * the client is acting.
   *
   * When this is true and the assigner receives a request from a service that is not part of the
   * [[targetValidationAllowedServices]], the assigner requires that the value of the `target` field
   * matches the value implied by the App Identifier headers attached to the request. See
   * `Assigner.validateTarget` for more details.
   */
  val enableTargetValidationViaAppIdentifierHeaders: Boolean =
    configure(
      "databricks.dicer.assigner.enableTargetValidationViaAppIdentifierHeaders",
      defaultValue = false
    )

  /**
   * Set of services that are allowed to send watch requests to the assigner for any target. The
   * services in this set are specified by their App Name (<internal link>). If
   * the assigner receives a request from a service with a App Name that is not in this
   * set and [[enableTargetValidationViaAppIdentifierHeaders]] is true, then the request's target is
   * not trusted at face value and the assigner uses App Identifier headers attached to the
   * request to validate the target. See `Assigner.validateTarget` for more details.
   */
  val trustedWatchAnyTargetServices: Set[String] = configure(
    "databricks.dicer.assigner.trustedWatchAnyTargetServices",
    defaultValue = Set.empty[String]
  )
}

object DicerAssignerConf {

  /**
   * The type determining what functionality the dicer assigner application will perform - either
   * starting and running the dicer assigner service, or executing an etcd bootstrapping task.
   */
  object ExecutionMode extends Enumeration {

    /**
     * The dicer assigner application will start and keep running the assigner service that
     * generates assignments and responds to watch requests.
     */
    val ASSIGNER_SERVICE: ExecutionMode.Value = Value("assigner_service")

    /**
     * The dicer assigner application will start the etcd bootstrapper task that writes initial
     * metadata to the etcd cluster, and exit after it finishes. We use this configuration to
     * leverage the existing Assigner service (including its ability to talk to the dicer-etcd
     * cluster) during new region bring-up to initialize dicer-etcd, and so avoid creating a whole
     * new service just for etcd initialization. In the future if we have a separate 'aux service',
     * we can move etcd initialization functionality there
     */
    val ETCD_BOOTSTRAPPER: ExecutionMode.Value = Value("etcd_bootstrapper")

    /**
     * Parses a string to the [[ExecutionMode]] whose value matches.
     * @param name string to parse.
     * @return value matching to `name`.
     * @throws IllegalArgumentException if `name` does not match any value
     */
    def fromName(name: String): Value =
      values
        .find((_: ExecutionMode.Value).toString == name)
        .getOrElse(
          throw new IllegalArgumentException(s"$name does not match a value of ExecutionMode")
        )
  }

  private val logger = PrefixLogger.create(this.getClass, "")
}
