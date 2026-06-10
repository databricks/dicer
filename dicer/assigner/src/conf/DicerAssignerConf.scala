package com.databricks.dicer.assigner.conf

import java.io.ByteArrayInputStream
import java.security.cert.{CertificateException, CertificateFactory, X509Certificate}
import java.util.Base64

import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.databricks.backend.common.util.Project
import com.databricks.backend.k8sauthmanagerclient.KamEndpoint
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.SafeConfigUtil.DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX
import com.databricks.caching.util.{
  CachingErrorCode,
  ConfigScope,
  PrefixLogger,
  SafeBatchFlagHelper,
  ServerConf,
  Severity
}
import com.databricks.conf.trusted.{LocationConf, ProjectConf}
import com.databricks.conf.{Config, ConfigParser, DbConf}
import com.databricks.dicer.assigner.MigrationMode
import com.databricks.dicer.assigner.conf.DicerAssignerConf.ExecutionMode
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
   * Timeout for Terminating state in HealthWatcher. Once this expires, Dicer forgets about the
   * pod entirely. We set this to 6 hours (21600s) because some pods may never receive a SIGTERM
   * (e.g. due to a kubelet bug or network partition) and remain alive long after Kubernetes has
   * marked them as Terminating. Forgetting about such a pod too early would cause Dicer to lose
   * track of an active resource, which can lead to incorrect assignment decisions.
   *
   * For the same reason, if this value is set to less than the Kubernetes termination grace period,
   * a pod that is still sending heartbeats may be forgotten and then re-admitted as healthy.
   */
  val terminatingTimeoutPeriod: FiniteDuration =
    configure[Long]("databricks.dicer.assigner.terminatingTimeoutPeriod", 21600).seconds

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
    configure[Long]("databricks.dicer.assigner.notReadyTimeoutPeriodSeconds", 10).seconds
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

/**
 * Configuration parameters for a remote membership checker that the Assigner can use to establish
 * the identity of an Assigner instance running in a remote cluster. The parameters describe how to
 * connect to both the remote K8s API server as well as the K8s Auth Manager service that provides
 * bearer tokens for connecting to the former.
 *
 * @throws IllegalArgumentException if any entry of
 *                                  `databricks.dicer.assigner.remote.k8sApiServers` contains an
 *                                  unrecognized key, an empty `kubeContext`, an empty or non-
 *                                  `https://` `kubeApiUrl`, or a `caCertBase64` that does not
 *                                  decode to a parseable, currently-valid X.509 CA certificate.
 * @throws IllegalArgumentException if `databricks.dicer.assigner.remote.kamDbnsIdentifier` is set
 *                                  without `kamDestinationClusterUri` (or vice versa), or if
 *                                  `kamDestinationClusterUri` does not resolve via embedded IDM.
 * @throws IllegalArgumentException if `remoteK8sApiServers` and the KAM endpoint are not
 *                                  configured together (i.e., one is non-empty / configured
 *                                  while the other is not).
 */
trait RemoteMembershipCheckerConf extends DbConf {

  /**
   * List of remote K8s API server connection infos that can be used to poll for peer Assigner
   * pods.
   */
  val remoteK8sApiServers: Seq[RemoteMembershipCheckerConf.RemoteK8sApiServerInfo] =
    configure[Seq[Map[String, String]]](
      "databricks.dicer.assigner.remote.k8sApiServers",
      Seq.empty[Map[String, String]]
    ).zipWithIndex.map {
      case (entry: Map[String, String], i: Int) =>
        try {
          val unknownKeys: Set[String] =
            entry.keySet.diff(RemoteMembershipCheckerConf.RemoteK8sApiServerInfo.ALLOWED_KEYS)
          require(
            unknownKeys.isEmpty,
            s"unknown key(s) $unknownKeys, allowed keys are: " +
            s"${RemoteMembershipCheckerConf.RemoteK8sApiServerInfo.ALLOWED_KEYS}"
          )
          RemoteMembershipCheckerConf.RemoteK8sApiServerInfo.create(
            kubeContext = entry.getOrElse("kubeContext", ""),
            kubeApiUrl = entry.getOrElse("kubeApiUrl", ""),
            caCertBase64 = entry.getOrElse("caCertBase64", "")
          )
        } catch {
          case e: IllegalArgumentException =>
            throw new IllegalArgumentException(
              s"databricks.dicer.assigner.remote.k8sApiServers[$i]: ${e.getMessage}",
              e
            )
        }
    }

  /**
   * K8s auth manager service endpoint used by checkers to obtain bearer tokens for connecting to
   * remote K8s apiservers. `None` when `kamDbnsIdentifier` and `kamDestinationClusterUri` are
   * unset (their defaults). Setting one without the other is rejected at conf-load.
   */
  val kamEndpoint: Option[KamEndpoint] = {
    val rawIdentifier: String =
      configure("databricks.dicer.assigner.remote.kamDbnsIdentifier", "")
    val rawDestinationClusterUri: String =
      configure("databricks.dicer.assigner.remote.kamDestinationClusterUri", "")
    if (rawIdentifier.isEmpty && rawDestinationClusterUri.isEmpty) {
      None
    } else {
      require(
        rawIdentifier.nonEmpty,
        "databricks.dicer.assigner.remote.kamDbnsIdentifier must be non-empty when " +
        "kamDestinationClusterUri is configured"
      )
      require(
        rawDestinationClusterUri.nonEmpty,
        "databricks.dicer.assigner.remote.kamDestinationClusterUri must be non-empty when " +
        "kamDbnsIdentifier is configured"
      )
      // `KamEndpoint.Dbns` validates `destinationClusterUri` via embedded IDM and throws
      // IllegalArgumentException on unresolvable input.
      try {
        Some(KamEndpoint.Dbns(rawIdentifier, rawDestinationClusterUri))
      } catch {
        case e: IllegalArgumentException =>
          throw new IllegalArgumentException(
            s"databricks.dicer.assigner.remote.kamDestinationClusterUri " +
            s"($rawDestinationClusterUri) is invalid: ${e.getMessage}",
            e
          )
      }
    }
  }

  // Surface a partial misconfiguration at conf-load time so downstream callers don't have to
  // re-validate. The two halves of the remote-cluster config must be configured together: a KAM
  // endpoint without remote API servers is dead config, and remote API servers without a KAM
  // endpoint can't mint bearer tokens.
  if (remoteK8sApiServers.nonEmpty) {
    require(
      kamEndpoint.isDefined,
      "databricks.dicer.assigner.remote.kamDbnsIdentifier and kamDestinationClusterUri must " +
      "be configured when databricks.dicer.assigner.remote.k8sApiServers is non-empty"
    )
  }
  if (kamEndpoint.isDefined) {
    require(
      remoteK8sApiServers.nonEmpty,
      "databricks.dicer.assigner.remote.k8sApiServers must be non-empty when " +
      "databricks.dicer.assigner.remote.kamDbnsIdentifier / kamDestinationClusterUri are configured"
    )
  }
}

object RemoteMembershipCheckerConf {

  /**
   * Connection info for a remote K8s API server, holding the CA cert as decoded bytes ready for
   * use as a TLS trust anchor.
   *
   * @param kubeContext K8s context of the remote K8s API server.
   * @param kubeApiUrl URL of the remote K8s API server.
   * @param caCertBytes Decoded CA certificate bytes used as a TLS trust anchor when verifying
   *                    the remote apiserver's server certificate.
   */
  case class RemoteK8sApiServerInfo private (
      kubeContext: String,
      kubeApiUrl: String,
      caCertBytes: Vector[Byte])

  object RemoteK8sApiServerInfo {

    /** Map keys recognized in each entry of `databricks.dicer.assigner.remote.k8sApiServers`. */
    private[RemoteMembershipCheckerConf] val ALLOWED_KEYS: Set[String] =
      Set("kubeContext", "kubeApiUrl", "caCertBase64")

    /**
     * Returns a [[RemoteK8sApiServerInfo]] with the given `kubeContext`, `kubeApiUrl`, and
     * `caCertBase64`. This method performs basic validation of the parameters and decodes
     * `caCertBase64` into its byte form.
     */
    @throws[IllegalArgumentException](
      "if any argument is empty, `kubeApiUrl` is not an `https://` URL with a host, or " +
      "`caCertBase64` is not valid base64 / decodes to empty bytes / does not decode to a " +
      "parseable, currently-valid X.509 CA certificate"
    )
    private[RemoteMembershipCheckerConf] def create(
        kubeContext: String,
        kubeApiUrl: String,
        caCertBase64: String): RemoteK8sApiServerInfo = {
      require(kubeContext.nonEmpty, "kubeContext must not be empty")
      require(kubeApiUrl.nonEmpty, "kubeApiUrl must not be empty")
      require(
        kubeApiUrl.startsWith("https://"),
        "kubeApiUrl must be an https:// URL (bearer-token auth requires TLS)"
      )
      require(kubeApiUrl != "https://", "kubeApiUrl must include a host after https://")
      require(caCertBase64.nonEmpty, "caCertBase64 must not be empty")
      val caCertBytes: Vector[Byte] = decodeCaCertBase64(caCertBase64).toVector
      new RemoteK8sApiServerInfo(kubeContext, kubeApiUrl, caCertBytes)
    }

    /**
     * Decodes `caCertBase64` from standard base64 and returns the non-empty decoded bytes that
     * also parse as a currently-valid X.509 CA certificate (basicConstraints `cA=true` and
     * within `notBefore`/`notAfter`).
     */
    @throws[IllegalArgumentException](
      "if `caCertBase64` is not valid base64, decodes to empty bytes, or doesn't decode to a " +
      "parseable, currently-valid X.509 CA certificate"
    )
    private def decodeCaCertBase64(caCertBase64: String): Array[Byte] = {
      val decoded: Array[Byte] =
        try Base64.getDecoder.decode(caCertBase64)
        catch {
          case e: IllegalArgumentException =>
            throw new IllegalArgumentException(
              s"caCertBase64 is not valid base64: ${e.getMessage}",
              e
            )
        }
      require(decoded.nonEmpty, "caCertBase64 must decode to non-empty bytes")
      // Validate that the decoded bytes are actually a usable CA certificate at conf-load time,
      // so misconfiguration surfaces here rather than as an opaque OkHttp/JSSE error during the
      // TLS handshake on the first poll. We check three things:
      //   1. The bytes parse as an X.509 certificate.
      //   2. The cert is currently valid (within its `notBefore`/`notAfter` window).
      //   3. The cert is a CA (basicConstraints `cA=true`); a leaf cert used as a trust anchor
      //      would not validate any chain at handshake time in production.
      val cert: X509Certificate =
        try {
          CertificateFactory
            .getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(decoded)) match {
            case x509: X509Certificate => x509
            case other =>
              throw new IllegalArgumentException(
                s"caCertBase64 must decode to an X.509 certificate, got " +
                s"${other.getClass.getName}"
              )
          }
        } catch {
          case e: CertificateException =>
            throw new IllegalArgumentException(
              s"caCertBase64 must decode to a parseable X.509 certificate: ${e.getMessage}",
              e
            )
        }
      try {
        cert.checkValidity()
      } catch {
        case e: CertificateException =>
          throw new IllegalArgumentException(
            s"caCertBase64 must decode to a currently-valid X.509 certificate: ${e.getMessage}",
            e
          )
      }
      // `getBasicConstraints` returns -1 for non-CA certs; a non-negative value means cA=true
      // (the value itself is the path-length constraint).
      require(
        cert.getBasicConstraints >= 0,
        "caCertBase64 must decode to a CA certificate (basicConstraints cA=true)"
      )
      decoded
    }
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

  /**
   * Selects which [[MigrationMode]] the Assigner uses to stage the migration of the
   * preferred-assigner driver from the etcd-backed driver to the consistent-hashing driver.
   * The wire value is the mode's [[MigrationMode.name]].
   *
   * @throws com.databricks.conf.ConfigParseException at startup if the conf value is not a
   *         known mode name. The framework's message names the offending wire value; the
   *         attached cause is an [[IllegalArgumentException]] whose message lists every known
   *         mode and is rendered into the assigner's startup log via the standard chained
   *         stack trace.
   */
  val preferredAssignerMigrationMode: MigrationMode = configure[MigrationMode](
    propertyName = "databricks.dicer.assigner.preferredAssigner.migrationMode",
    defaultValue = MigrationMode.ShadowMode,
    parser = new ConfigParser[MigrationMode] {
      override def parse(mapper: DatabricksObjectMapper, json: String): MigrationMode =
        MigrationMode.fromName(mapper.readValue[String](json))
    }
  )
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
 *  - `PreferredAssignerConf`: Configuration for the preferred assigner, including etcd settings.
 *  - `RemoteMembershipCheckerConf`: Configuration for the remote membership checker.
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
    with GeneratorConf
    with RemoteMembershipCheckerConf
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
   * The number of threads in the [[SequentialExecutionContextPool]] backing the Assigner's
   * per-target SECs (generators, subscriber handlers, and other components). Each deployment
   * is expected to set this in its service-conf; see e.g.
   * `dicer/production/assigner/deploy/conf/service-conf.jsonnet` for the rationale behind
   * the default value.
   */
  val secPoolThreadCount: Int =
    configure("databricks.dicer.assigner.secPoolThreadCount", 8)
  require(secPoolThreadCount > 0, "secPoolThreadCount must be greater than 0.")

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
    targetConfigBatchFlag.flagName == DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX + "batchFlag",
    "we must use a literal in the BatchFeatureFlag initializer, but that literal value must be " +
    "consistent with our expected prefix"
  )

  /**
   * Dynamic target migration configurations delivered via a single SAFE feature flag. The value
   * is a JSON-serialized [[TargetMigrationConfigP]] instance.
   */
  @FeatureFlagDefinition(
    team = "platform-team",
    description = "The SAFE flag for Dicer's dynamic target migration configurations"
  )
  protected val targetMigrationConfigFlag: FeatureFlag[String] =
    FeatureFlag("databricks.dicer.assigner.targetMigrationConfig", "")

  /**
   * Safe batch flag does not give push notification when value gets changed. Assigner will
   * periodically poll the flag value and the `pollInterval` variable specifies the interval between
   * two consecutive polls.
   */
  val dynamicConfigPollInterval: FiniteDuration =
    configure[FiniteDuration]("databricks.dicer.assigner.dynamicConfigPollInterval", 120.seconds)

  /**
   * The interval at which the Assigner periodically polls the target migration config SAFE flag.
   * Separate from [[dynamicConfigPollInterval]] since we intend on polling this flag more
   * frequently so that target migration config changes propagate quickly across Assigners.
   */
  val dynamicTargetMigrationConfigPollInterval: FiniteDuration =
    configure[FiniteDuration](
      "databricks.dicer.assigner.dynamicTargetMigrationConfigPollInterval",
      10.seconds
    )

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
   * SAFE feature flag for the AssignerProtoLogger generation sample fraction.
   *
   * Controls what fraction of assignment generations are sampled for proto logging events.
   *
   * Sampling is performed by generation number, meaning we sample assignments across all targets
   * rather than sampling a subset of targets. For example, with a 0.1 (10%) sample fraction,
   * approximately 10% of assignment generations will be logged across ALL targets.
   *
   * Value should be between [0.0, 1.0]. Default is 0.0 (disabled).
   */
  @FeatureFlagDefinition(
    team = "platform-team",
    description = "Assignment Generation sampling fraction for Assigner Proto Logger (0.0 to 1.0)"
  )
  final val protoLoggerGenerationSampleFractionFlag: FeatureFlag[Double] =
    FeatureFlag("databricks.dicer.assigner.protoLogger.dynamicGenerationSampleFraction", 0.0)

  /** Interval at which the SAFE flag for proto logging sample fraction is polled. */
  private[dicer] val protoLoggerGenerationSampleFractionPollInterval: FiniteDuration = 120.seconds

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
    targetConfigBatchFlag.getSubFlagValues(runtimeContext = None).flatMap {
      case (key: String, value: String) =>
        // Drops malformed sub-flag values so they don't break the rest of the batch.
        // Drops emit a metric that pages SAFE oncall.
        SafeBatchFlagHelper
          .parseSubFlag[String](
            targetConfigBatchFlag.flagName,
            key,
            DicerAssignerConf.TARGET_CONFIG_CONSUMER_SITE,
            value
          )
          .map { parsed: String =>
            key -> parsed
          }
    }
  }

  /**
   * Returns the JSON-serialized target migration config value from the
   * `databricks.dicer.assigner.targetMigrationConfig` SAFE feature flag.
   */
  def getDynamicTargetMigrationConfig: Option[String] = {
    val unparsedTargetMigrationConfig: String =
      targetMigrationConfigFlag.getCurrentValue(runtimeContext = RuntimeContext.EMPTY)
    // The flag's declared default, which is an empty string (see `targetMigrationConfigFlag`
    // above), is returned when no selector matches the current runtime context. If the default
    // empty string is returned, we convert it to `None` so it's more explicit to the consumer
    // that no dynamic target migration config was returned.
    if (unparsedTargetMigrationConfig.nonEmpty) Some(unparsedTargetMigrationConfig) else None
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

  val executionMode: ExecutionMode =
    configure[ExecutionMode](
      propertyName = "databricks.dicer.assigner.executionMode",
      defaultValue = ExecutionMode.ASSIGNER_SERVICE,
      parser = new ConfigParser[ExecutionMode] {
        override def parse(mapper: DatabricksObjectMapper, json: String): ExecutionMode = {
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
   * When this is true and the assigner receives a request from a service, the assigner requires
   * that the value of the `target` field matches the value implied by the App Identifier
   * headers attached to the request. This implies that all KubernetesTargets are rejected.
   * AppTarget WatchRequests are exempt from this check when their app name header names a
   * service in trustedWatchAnyTargetServices.  See `Assigner.validateTarget` for more details.
   */
  val enableTargetValidationViaAppIdentifierHeaders: Boolean =
    configure(
      "databricks.dicer.assigner.enableTargetValidationViaAppIdentifierHeaders",
      defaultValue = false
    )

  /**
   * Whether the Assigner should apply rate limiting for watch requests.
   *
   * When this is true, the Assigner applies rate limiting for watch requests at the HTTP layer
   * using target and client identifying headers. Rate limits for targets can be individually and
   * dynamically configured via `InternalTargetConfig.TargetWatchRequestRateLimitConfig`.
   */
  val enableWatchRequestRateLimiting: Boolean =
    configure(
      "databricks.dicer.assigner.enableWatchRequestRateLimiting",
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
   * `consumer_site` metric label for the SAFE batch-flag parse-failure counter at the
   * `targetConfigBatchFlag` consumer site. The `batch_flag` label is the real flag's
   * `flagName` (read at runtime from `targetConfigBatchFlag.flagName`). The
   * `SafeBatchFlagEvaluationParseFailure` alert keys on `(batch_flag, consumer_site)`.
   */
  private val TARGET_CONFIG_CONSUMER_SITE: String = "dicer_assigner_target_config"

  /**
   * The type determining what functionality the dicer assigner application will perform - either
   * starting and running the dicer assigner service, or executing an etcd bootstrapping task.
   */
  sealed trait ExecutionMode {

    /** Stable string identifier used in static config and logs. */
    def name: String
  }

  object ExecutionMode {

    /**
     * The dicer assigner application will start and keep running the assigner service that
     * generates assignments and responds to watch requests.
     */
    case object ASSIGNER_SERVICE extends ExecutionMode {
      override val name: String = "assigner_service"
    }

    /**
     * The dicer assigner application will start the etcd bootstrapper task that writes initial
     * metadata to the etcd cluster, and exit after it finishes. We use this configuration to
     * leverage the existing Assigner service (including its ability to talk to the dicer-etcd
     * cluster) during new region bring-up to initialize dicer-etcd, and so avoid creating a whole
     * new service just for etcd initialization. In the future if we have a separate 'aux service',
     * we can move etcd initialization functionality there
     */
    case object ETCD_BOOTSTRAPPER extends ExecutionMode {
      override val name: String = "etcd_bootstrapper"
    }

    /** All cases of [[ExecutionMode]]. */
    val values: Vector[ExecutionMode] = Vector(ASSIGNER_SERVICE, ETCD_BOOTSTRAPPER)

    /**
     * Parses a string to the [[ExecutionMode]] whose value matches.
     * @param name string to parse.
     * @return value matching to `name`.
     * @throws IllegalArgumentException if `name` does not match any value
     */
    @throws[IllegalArgumentException]("if `name` does not match any value")
    def fromName(name: String): ExecutionMode =
      values
        .find((_: ExecutionMode).name == name)
        .getOrElse(
          throw new IllegalArgumentException(s"$name does not match a value of ExecutionMode")
        )
  }

  private val logger = PrefixLogger.create(this.getClass, "")
}
